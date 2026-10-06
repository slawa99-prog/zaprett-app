package com.slawa.ethernetlink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundProxyTest {
    private static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    private static Socket client(BoundProxy proxy)throws IOException{Socket s=new Socket("127.0.0.1",proxy.port());s.setSoTimeout(3000);return s;}
    private static String request(Socket s,String line)throws IOException{
        s.getOutputStream().write((line+"\r\nHost: example.invalid:443\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=s.getInputStream().read())>=0){b.write(c);if(b.toString("US-ASCII").endsWith("\r\n\r\n"))break;}
        return b.toString("US-ASCII");
    }
    public static void main(String[] args)throws Exception{
        check("speedtest.cdn.yandex.net".equals(BoundProxy.target("CONNECT speedtest.cdn.yandex.net:443 HTTP/1.1\r\n\r\n")),"HTTPS target");
        for(String input:new String[]{"GET https://x/ HTTP/1.1","CONNECT x:80 HTTP/1.1","CONNECT user@host:443 HTTP/1.1","CONNECT /bad:443 HTTP/1.1","CONNECT x:443 HTTP/2","CONNECT x..y:443 HTTP/1.1"})
            check(BoundProxy.target(input)==null,"reject unsupported requests");
        AtomicInteger connected=new AtomicInteger();
        try(ServerSocket echo=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){
            Thread responder=new Thread(()->{
                try(Socket s=echo.accept()){
                    byte[] b=new byte[8192];int n;while((n=s.getInputStream().read(b))!=-1)s.getOutputStream().write(b,0,n);
                }catch(IOException ignored){}
            });responder.setDaemon(true);responder.start();
            try(BoundProxy proxy=new BoundProxy((host,port)->{
                check(host.equals("example.invalid")&&port==443,"only supplied connector resolves destination");
                connected.incrementAndGet();return new Socket("127.0.0.1",echo.getLocalPort());
            })){ 
                try(Socket bad=client(proxy)){check(request(bad,"CONNECT example.invalid:80 HTTP/1.1").startsWith("HTTP/1.1 403"),"port restriction");}
                check(connected.get()==0,"invalid request makes no upstream connection");
                try(Socket s=client(proxy)){
                    check(request(s,"CONNECT example.invalid:443 HTTP/1.1").startsWith("HTTP/1.1 200"),"CONNECT handshake");
                    byte[] payload=new byte[196608];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*31);
                    Thread sender=new Thread(()->{try{s.getOutputStream().write(payload);}catch(IOException e){throw new RuntimeException(e);}});sender.start();
                    byte[] received=new byte[payload.length];new DataInputStream(s.getInputStream()).readFully(received);sender.join(3000);
                    check(Arrays.equals(payload,received),"opaque binary round-trip, no corruption");
                    proxy.close();check(s.getInputStream().read()==-1,"stop closes active tunnel immediately");
                }
            }
            responder.join(3000);check(!responder.isAlive(),"upstream released");
        }
        try(BoundProxy offline=new BoundProxy((host,port)->{throw new IOException("Ethernet lost");});Socket s=client(offline)){
            check(request(s,"CONNECT example.invalid:443 HTTP/1.1").startsWith("HTTP/1.1 502"),"no fallback on network failure");
        }
        check(connected.get()==1,"no hidden direct connector");
        System.out.println("CONNECT routing, bidirectional bytes, network failure and cancellation passed");
    }
}
