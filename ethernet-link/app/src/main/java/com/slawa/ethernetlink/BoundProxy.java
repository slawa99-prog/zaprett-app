package com.slawa.ethernetlink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only CONNECT relay. The caller supplies an Ethernet-bound connector.
 * TLS remains end-to-end in WebView; this class never decrypts or logs traffic.
 * There is deliberately no default-network connector or direct fallback.
 */
final class BoundProxy implements AutoCloseable {
    interface Connector { Socket open(String host,int port) throws IOException; }
    private final Connector connector;
    private final ServerSocket server;
    private final Set<Socket> sockets=new HashSet<>();
    private final Semaphore slots=new Semaphore(24);
    private final ExecutorService workers=new ThreadPoolExecutor(0,48,30,TimeUnit.SECONDS,
            new SynchronousQueue<>(),r->{Thread t=new Thread(r,"ethernet-tunnel");t.setDaemon(true);return t;});
    private volatile boolean closed;
    BoundProxy(Connector connector) throws IOException {
        this.connector=connector;
        server=new ServerSocket(0,32,InetAddress.getByAddress(new byte[]{127,0,0,1}));
        Thread acceptor=new Thread(this::accept,"ethernet-proxy");acceptor.setDaemon(true);acceptor.start();
    }
    int port(){return server.getLocalPort();}
    private void accept(){
        while(!closed){
            Socket client=null;
            try{
                client=server.accept();
                if(!slots.tryAcquire()){closeSocket(client);continue;}
                try{
                    track(client);
                    final Socket accepted=client;
                    workers.execute(()->{try{handle(accepted);}finally{forget(accepted);slots.release();}});
                }catch(IOException|RejectedExecutionException e){forget(client);slots.release();}
            }catch(IOException e){closeSocket(client);if(!closed)close();}
        }
    }
    private void handle(Socket client){
        Socket upstream=null;
        try{
            client.setSoTimeout(10000);client.setTcpNoDelay(true);
            String header=readHeader(client.getInputStream());
            String host=target(header);
            if(host==null){respond(client,"403 Forbidden");return;}
            upstream=connector.open(host,443);track(upstream);
            upstream.setSoTimeout(30000);upstream.setTcpNoDelay(true);
            client.setSoTimeout(30000);
            respond(client,"200 Connection Established");
            final Socket remote=upstream;
            workers.execute(()->pipe(client,remote));
            pipe(remote,client);
        }catch(IOException|RejectedExecutionException e){
            // A lost bound Network fails here. It is never retried over Wi-Fi/cellular.
            if(upstream==null)try{respond(client,"502 Bad Gateway");}catch(IOException ignored){}
        }finally{forget(upstream);forget(client);}
    }
    static String target(String header){
        if(header==null)return null;
        String first=header.split("\r\n",2)[0];
        String[] parts=first.split(" ",-1);
        if(parts.length!=3||!"CONNECT".equals(parts[0])||!("HTTP/1.1".equals(parts[2])||"HTTP/1.0".equals(parts[2])))return null;
        String authority=parts[1];
        if(!authority.endsWith(":443"))return null;
        String host=authority.substring(0,authority.length()-4);
        if(host.length()>253||!host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"))return null;
        if(host.contains(".."))return null;
        return host;
    }
    private static String readHeader(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int state=0;
        while(b.size()<16384){
            int c=in.read();if(c<0)throw new EOFException();b.write(c);
            if(state==0)state=c=='\r'?1:0;
            else if(state==1)state=c=='\n'?2:0;
            else if(state==2)state=c=='\r'?3:0;
            else if(c=='\n')return b.toString(StandardCharsets.US_ASCII.name());
            else state=0;
        }
        throw new IOException("CONNECT header too large");
    }
    private static void respond(Socket s,String status)throws IOException{
        s.getOutputStream().write(("HTTP/1.1 "+status+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
    }
    private void pipe(Socket source,Socket destination){
        try{
            byte[] bytes=new byte[65536];int n;
            InputStream in=source.getInputStream();OutputStream out=destination.getOutputStream();
            while(!closed&&(n=in.read(bytes))!=-1)out.write(bytes,0,n);
        }catch(IOException ignored){}
        finally{forget(source);forget(destination);}
    }
    private synchronized void track(Socket s)throws IOException{
        if(closed){closeSocket(s);throw new IOException("relay closed");}sockets.add(s);
    }
    private synchronized void forget(Socket s){if(s!=null){sockets.remove(s);closeSocket(s);}}
    private static void closeSocket(Socket s){if(s!=null)try{s.close();}catch(IOException ignored){}}
    @Override public synchronized void close(){
        if(closed)return;closed=true;
        try{server.close();}catch(IOException ignored){}
        for(Socket s:sockets)closeSocket(s);sockets.clear();workers.shutdownNow();
    }
}
