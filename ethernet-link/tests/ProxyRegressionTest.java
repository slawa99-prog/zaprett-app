package com.slawa.ethernetlink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class ProxyRegressionTest {
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    static Socket connect(BoundProxy proxy)throws IOException{
        Socket s=new Socket("127.0.0.1",proxy.port());s.setSoTimeout(3000);
        try{
            s.getOutputStream().write("CONNECT fixture.invalid:443 HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
            while((c=s.getInputStream().read())>=0){b.write(c);if(b.toString("US-ASCII").endsWith("\r\n\r\n"))break;}
            check(b.toString("US-ASCII").startsWith("HTTP/1.1 200"),"CONNECT accepted");return s;
        }catch(Throwable e){s.close();throw e;}
    }
    static Thread run(AtomicReference<Throwable> failure,Task task){
        Thread t=new Thread(()->{try{task.run();}catch(Throwable e){failure.set(e);}});t.setDaemon(true);t.start();return t;
    }
    interface Task{void run()throws Exception;}
    static ServerSocket server()throws IOException{return new ServerSocket(0,128,InetAddress.getByName("127.0.0.1"));}
    static void burst()throws Exception{
        final int count=48;List<Socket> clients=new ArrayList<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        try(ServerSocket server=server();BoundProxy proxy=new BoundProxy((h,p)->new Socket("127.0.0.1",server.getLocalPort()))){
            Thread backend=run(failure,()->{
                List<Socket> peers=new ArrayList<>();
                try{
                    for(int i=0;i<count;i++)peers.add(server.accept());
                    for(Socket peer:peers){peer.setSoTimeout(4000);int tag=peer.getInputStream().read();peer.getOutputStream().write(tag);}
                }finally{for(Socket peer:peers)peer.close();}
            });
            try{
                for(int i=0;i<count;i++)clients.add(connect(proxy));
                for(int i=0;i<count;i++)clients.get(i).getOutputStream().write(i);
                for(int i=0;i<count;i++)check(clients.get(i).getInputStream().read()==i,"parallel stream "+i);
                backend.join(4000);check(!backend.isAlive()&&failure.get()==null,"all parallel streams finished: "+failure.get());
                System.out.println("48 concurrent page/test tunnels passed");
            }finally{for(Socket s:clients)s.close();}
        }
    }
    static void halfClose()throws Exception{
        byte[] payload=new byte[128*1024];new Random(14).nextBytes(payload);AtomicReference<Throwable> failure=new AtomicReference<>();
        try(ServerSocket server=server();BoundProxy proxy=new BoundProxy((h,p)->new Socket("127.0.0.1",server.getLocalPort()))){
            Thread backend=run(failure,()->{
                try(Socket s=server.accept()){
                    s.setSoTimeout(3000);ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
                    while((n=s.getInputStream().read(b))!=-1)bytes.write(b,0,n);
                    s.getOutputStream().write(bytes.toByteArray());
                }
            });
            try(Socket s=connect(proxy)){
                s.getOutputStream().write(payload);s.shutdownOutput();
                byte[] received=new byte[payload.length];new DataInputStream(s.getInputStream()).readFully(received);
                check(Arrays.equals(payload,received),"response survives client half-close");
            }
            backend.join(3000);check(failure.get()==null&&!backend.isAlive(),"half-close backend finished");
        }
        System.out.println("Half-close keeps in-flight response intact");
    }
    // Reproduces Android's requirement to bypass SocketAdaptor streams, whose
    // shared blockingLock otherwise serializes read and write (including TLS).
    static final class ChannelSocket extends Socket {
        final SocketChannel channel;
        ChannelSocket(SocketChannel channel){this.channel=channel;}
        @Override public SocketChannel getChannel(){return channel;}
        @Override public InputStream getInputStream(){throw new AssertionError("Android SocketAdaptor input stream must not be used");}
        @Override public OutputStream getOutputStream(){throw new AssertionError("Android SocketAdaptor output stream must not be used");}
        @Override public void setSoTimeout(int value){}
        @Override public void setTcpNoDelay(boolean value)throws SocketException{channel.socket().setTcpNoDelay(value);}
        @Override public void shutdownOutput()throws IOException{channel.shutdownOutput();}
        @Override public void close()throws IOException{channel.close();}
    }
    static void channelDuplex()throws Exception{
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try(ServerSocket server=server();RacingConnector connector=new RacingConnector(h->new InetAddress[]{InetAddress.getByName("127.0.0.1")},SocketChannel::open);
            BoundProxy proxy=new BoundProxy((h,p)->new ChannelSocket(connector.open(h,server.getLocalPort()).getChannel()))){
            Thread backend=run(failure,()->{
                try(Socket s=server.accept()){
                    s.setSoTimeout(2000);int hello=s.getInputStream().read();
                    check(hello==71,"client hello reaches server while response read is waiting");s.getOutputStream().write(hello);
                }
            });
            try(Socket s=connect(proxy)){
                // Let proxy's download worker block before client writes, as TLS does.
                Thread.sleep(100);s.getOutputStream().write(71);check(s.getInputStream().read()==71,"channel duplex handshake");
            }
            backend.join(3000);check(!backend.isAlive()&&failure.get()==null,"channel duplex: "+failure.get());
        }
        System.out.println("Android channel path forwards both TLS directions without adaptor stream locks");
    }
    public static void main(String[] args)throws Exception{
        burst();if(args.length>0)return;halfClose();channelDuplex();
        check("2001:db8::1".equals(BoundProxy.target("CONNECT [2001:db8::1]:443 HTTP/1.1")),"IPv6 target");
        for(String host:new String[]{"[2001:db8::1%eth0]","[example.com]","[::bad::]","user@[::1]"})
            check(BoundProxy.target("CONNECT "+host+":443 HTTP/1.1")==null,"malformed target rejected");
    }
}
