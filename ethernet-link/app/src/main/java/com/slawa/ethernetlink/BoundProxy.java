package com.slawa.ethernetlink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Loopback CONNECT relay. Every upstream is supplied by the Ethernet connector.
 * TLS/WSS stay opaque. No direct/default-network fallback or traffic inspection. */
final class BoundProxy implements AutoCloseable {
    interface Connector { Socket open(String host,int port) throws IOException; }
    private static final int MAX_TUNNELS=64;
    private final Connector connector;
    private final ServerSocket server;
    private final Set<Socket> sockets=new HashSet<>();
    private final Set<Tunnel> tunnels=ConcurrentHashMap.newKeySet();
    private final Semaphore slots=new Semaphore(MAX_TUNNELS);
    // Two workers per tunnel. Queue covers the short interval between releasing
    // a tunnel slot and its worker returning, instead of rejecting that request.
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(MAX_TUNNELS*2,MAX_TUNNELS*2,20,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_TUNNELS*2),r->{Thread t=new Thread(null,r,"ethernet-tunnel",256*1024);t.setDaemon(true);return t;});
    private final ScheduledExecutorService reaper=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ethernet-idle");t.setDaemon(true);return t;});
    private final int idleMs;
    private final AtomicLong connections=new AtomicLong(),rejected=new AtomicLong(),errors=new AtomicLong(),idleClosed=new AtomicLong(),upBytes=new AtomicLong(),downBytes=new AtomicLong();
    private final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
    private volatile String lastError="";
    private volatile boolean closed;
    BoundProxy(Connector connector)throws IOException{this(connector,60000);}
    BoundProxy(Connector connector,int idleMs)throws IOException{
        this.connector=connector;this.idleMs=idleMs;workers.allowCoreThreadTimeOut(true);
        server=new ServerSocket(0,128,InetAddress.getByAddress(new byte[]{127,0,0,1}));
        reaper.scheduleWithFixedDelay(()->{
            for(Tunnel tunnel:tunnels)if(System.nanoTime()-tunnel.activity>=this.idleMs*1_000_000L){
                if(tunnel.end())idleClosed.incrementAndGet();
            }
        },Math.min(5000,idleMs),Math.min(5000,idleMs),TimeUnit.MILLISECONDS);
        Thread acceptor=new Thread(this::accept,"ethernet-proxy");acceptor.setDaemon(true);acceptor.start();
    }
    int port(){return server.getLocalPort();}
    private void accept(){
        while(!closed){
            Socket client=null;
            try{
                client=server.accept();
                if(!slots.tryAcquire()){rejected.incrementAndGet();closeSocket(client);continue;}
                boolean counted=false;
                try{
                    track(client);int count=active.incrementAndGet();counted=true;peak.accumulateAndGet(count,Math::max);
                    final Socket accepted=client;
                    workers.execute(()->{try{handle(accepted);}finally{forget(accepted);active.decrementAndGet();slots.release();}});
                }catch(IOException|RejectedExecutionException e){forget(client);if(counted)active.decrementAndGet();slots.release();}
            }catch(IOException e){closeSocket(client);if(!closed)close();}
        }
    }
    private final class Tunnel {
        final Socket client,remote;
        final CountDownLatch uploadDone=new CountDownLatch(1);
        final AtomicBoolean ended=new AtomicBoolean();
        volatile long activity=System.nanoTime();
        Tunnel(Socket client,Socket remote){this.client=client;this.remote=remote;}
        boolean end(){if(!ended.compareAndSet(false,true))return false;tunnels.remove(this);forget(client);forget(remote);return true;}
        void pipe(Socket source,Socket destination,AtomicLong bytes){
            try{
                byte[] buffer=new byte[65536];ByteBuffer bytesBuffer=ByteBuffer.wrap(buffer);
                SocketChannel reader=source.getChannel(),writer=destination.getChannel();
                // Android's SocketAdaptor streams share blockingLock across reads
                // and writes. Direct channel I/O has independent read/write locks,
                // so waiting for a TLS response cannot block sending ClientHello.
                InputStream in=reader==null?source.getInputStream():null;
                OutputStream out=writer==null?destination.getOutputStream():null;
                while(!closed&&!ended.get()){
                    bytesBuffer.clear();int n=reader==null?in.read(buffer):reader.read(bytesBuffer);
                    if(n<0){destination.shutdownOutput();return;}
                    activity=System.nanoTime();
                    if(writer==null)out.write(buffer,0,n);
                    else{bytesBuffer.position(0);bytesBuffer.limit(n);while(bytesBuffer.hasRemaining())writer.write(bytesBuffer);}
                    activity=System.nanoTime();bytes.addAndGet(n);
                }
            }catch(IOException e){if(!closed&&!ended.get())lastError="stream: "+e.getClass().getSimpleName();end();}
        }
    }
    private void handle(Socket client){
        Socket upstream=null;Tunnel tunnel=null;
        try{
            client.setSoTimeout(10000);client.setTcpNoDelay(true);
            String host=target(readHeader(client.getInputStream()));
            if(host==null){rejected.incrementAndGet();respond(client,"403 Forbidden");return;}
            upstream=connector.open(host,443);track(upstream);
            upstream.setSoTimeout(0);upstream.setTcpNoDelay(true);client.setSoTimeout(0);
            tunnel=new Tunnel(client,upstream);tunnels.add(tunnel);final Tunnel relay=tunnel;
            respond(client,"200 Connection Established");connections.incrementAndGet();
            workers.execute(()->{try{relay.pipe(relay.client,relay.remote,upBytes);}finally{relay.uploadDone.countDown();}});
            relay.pipe(upstream,client,downBytes);
            // EOF half-closes one direction; its response may still be in flight.
            // An error or cancellation closes both directions immediately.
            relay.uploadDone.await();
        }catch(IOException|RejectedExecutionException e){
            if(!closed){errors.incrementAndGet();lastError="connect: "+e.getClass().getSimpleName();}
            if(upstream==null)try{respond(client,"502 Bad Gateway");}catch(IOException ignored){}
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        finally{if(tunnel!=null)tunnel.end();forget(upstream);forget(client);}
    }
    static String target(String header){
        if(header==null)return null;
        String first=header.split("\r\n",2)[0];String[] parts=first.split(" ",-1);
        if(parts.length!=3||!"CONNECT".equals(parts[0])||!("HTTP/1.1".equals(parts[2])||"HTTP/1.0".equals(parts[2])))return null;
        String authority=parts[1];if(!authority.endsWith(":443"))return null;
        String host=authority.substring(0,authority.length()-4);
        if(host.startsWith("[")&&host.endsWith("]")){
            String literal=host.substring(1,host.length()-1);
            // Numeric IPv6 only: no DNS, credentials, zones, paths or extra ports.
            if(!literal.contains(":")||!literal.matches("[0-9A-Fa-f:.]+"))return null;
            try{InetAddress.getByName(literal);return literal;}catch(UnknownHostException e){return null;}
        }
        if(host.length()>253||!host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")||host.contains(".."))return null;
        return host;
    }
    private static String readHeader(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int state=0;
        while(b.size()<16384){
            int c=in.read();if(c<0)throw new EOFException();b.write(c);
            if(state==0)state=c=='\r'?1:0;
            else if(state==1)state=c=='\n'?2:0;
            else if(state==2)state=c=='\r'?3:0;
            else if(c=='\n')return b.toString(StandardCharsets.US_ASCII.name());else state=0;
        }
        throw new IOException("CONNECT header too large");
    }
    private static void respond(Socket s,String status)throws IOException{
        s.getOutputStream().write(("HTTP/1.1 "+status+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();
    }
    private synchronized void track(Socket s)throws IOException{if(closed){closeSocket(s);throw new IOException("relay closed");}sockets.add(s);}
    private synchronized void forget(Socket s){if(s!=null){sockets.remove(s);closeSocket(s);}}
    private static void closeSocket(Socket s){if(s!=null)try{s.close();}catch(IOException ignored){}}
    String report(){return "proxy.connections="+connections+"\nproxy.peak="+peak+"\nproxy.rejected="+rejected+"\nproxy.errors="+errors+
        "\nproxy.idle_closed="+idleClosed+"\nproxy.up_bytes="+upBytes+"\nproxy.down_bytes="+downBytes+"\nproxy.last_error="+lastError+"\n";}
    @Override public synchronized void close(){
        if(closed)return;closed=true;try{server.close();}catch(IOException ignored){}
        for(Socket s:sockets)closeSocket(s);sockets.clear();tunnels.clear();workers.shutdownNow();reaper.shutdownNow();
    }
}
