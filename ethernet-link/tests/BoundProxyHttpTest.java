package com.slawa.ethernetlink;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundProxyHttpTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static byte[] ascii(String value){return value.getBytes(StandardCharsets.US_ASCII);}
    private static String header(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int state=0;
        while(out.size()<16384){
            int c=in.read();if(c<0)throw new EOFException();out.write(c);
            state=state==0?(c=='\r'?1:0):state==1?(c=='\n'?2:0):state==2?(c=='\r'?3:0):(c=='\n'?4:0);
            if(state==4)return out.toString("US-ASCII");
        }
        throw new IOException("oversized header");
    }
    private static Socket client(BoundProxy proxy)throws IOException{Socket s=new Socket("127.0.0.1",proxy.port());s.setSoTimeout(5000);return s;}
    private static String request(String method,String fields){return method+" http://speedtest.ufanet.ru/backend/empty.php?r=1%202 HTTP/1.1\r\nHost: speedtest.ufanet.ru\r\n"+fields+"\r\n";}
    private static void exchange(byte[] upload,boolean chunked)throws Exception{
        byte[] download=new byte[512*1024];for(int i=0;i<download.length;i++)download[i]=(byte)(i*37);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket endpoint=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){
            Future<?> server=worker.submit(()->{
                try(Socket incoming=endpoint.accept()){
                    incoming.setSoTimeout(5000);String h=header(incoming.getInputStream());
                    check(h.startsWith((upload.length==0?"GET":"POST")+" /backend/empty.php?r=1%202 HTTP/1.1\r\n"),"origin-form URL, query preserved");
                    check(h.contains("Host: speedtest.ufanet.ru\r\n")&&h.contains("Connection: close\r\n"),"authority and one request per connection");
                    check(!h.toLowerCase().contains("proxy-connection:"),"proxy-only header removed");
                    if(chunked)check(h.contains("Transfer-Encoding: chunked\r\n"),"chunked body framing retained");
                    byte[] received=new byte[upload.length];new DataInputStream(incoming.getInputStream()).readFully(received);
                    check(Arrays.equals(upload,received),"upload bytes and chunk framing preserved");
                    incoming.getOutputStream().write(ascii("HTTP/1.1 201 Created\r\nContent-Length: "+download.length+"\r\nConnection: close\r\n\r\n"));
                    incoming.getOutputStream().write(download);
                }catch(IOException e){throw new UncheckedIOException(e);}
            });
            try(BoundProxy proxy=new BoundProxy((host,port)->{
                check(host.equals("speedtest.ufanet.ru")&&port==80,"HTTP goes through supplied Ethernet connector");
                SocketChannel channel=SocketChannel.open(new InetSocketAddress("127.0.0.1",endpoint.getLocalPort()));return channel.socket();
            });Socket browser=client(proxy)){
                String framing=upload.length==0?"":chunked?"Transfer-Encoding: chunked\r\n":"Content-Length: "+upload.length+"\r\n";
                browser.getOutputStream().write(ascii(request(upload.length==0?"GET":"POST",framing+"Proxy-Connection: keep-alive\r\n")));
                browser.getOutputStream().write(upload);
                check(header(browser.getInputStream()).startsWith("HTTP/1.1 201 Created"),"upstream response, no fake CONNECT handshake");
                byte[] received=new byte[download.length];new DataInputStream(browser.getInputStream()).readFully(received);
                check(Arrays.equals(download,received),"binary download preserved");
                check(browser.getInputStream().read()==-1,"response ends without waiting for request socket EOF");
                check(proxy.report().contains("proxy.http_connections=1"),"HTTP session retained in diagnostics");
            }
            server.get(5,TimeUnit.SECONDS);
        }finally{worker.shutdownNow();}
    }
    public static void main(String[] args)throws Exception{
        check(SpeedtestSite.HOME.equals("http://speedtest.ufanet.ru/"),"requested entry point");
        check(SpeedtestSite.allowsNavigation(SpeedtestSite.HOME)&&SpeedtestSite.allowsNavigation("https://speedtest.ufanet.ru/"),"HTTP and HTTPS redirect navigation");
        for(String url:new String[]{"http://ufanet.ru.evil.test/","http://evilufanet.ru/","http://127.0.0.1/","http://speedtest.ufanet.ru:8080/","file:///tmp/x","intent://x","http://user@speedtest.ufanet.ru/"})
            check(!SpeedtestSite.allowsNavigation(url),"unsupported navigation rejected: "+url);
        HttpForwardRequest get=HttpForwardRequest.parse(request("GET","Connection: keep-alive, X-Hop\r\nX-Hop: gone\r\nOrigin: http://speedtest.ufanet.ru\r\n"));
        check(get!=null&&!new String(get.header,StandardCharsets.US_ASCII).contains("X-Hop:"),"connection-specific headers removed");
        check(new String(get.header,StandardCharsets.US_ASCII).contains("Origin: http://speedtest.ufanet.ru\r\n"),"CORS origin preserved");
        for(String bad:new String[]{
            request("POST","Content-Length: 2\r\nTransfer-Encoding: chunked\r\n"),
            request("POST","Content-Length: 2\r\nContent-Length: 2\r\n"),
            request("POST","Content-Length: -1\r\n"),
            request("POST","Transfer-Encoding: gzip, chunked\r\n"),
            request("GET","Connection: Content-Length\r\n"),
            request("GET","Host: other.ufanet.ru\r\n"),
            request("GET"," Folded: no\r\n"),
            "GET http://speedtest.ufanet.ru.evil.test/ HTTP/1.1\r\n\r\n",
            "GET http://speedtest.ufanet.ru/#fragment HTTP/1.1\r\n\r\n",
            "GET http://speedtest.ufanet.ru:8080/ HTTP/1.1\r\n\r\n"})check(HttpForwardRequest.parse(bad)==null,"bad or out-of-scope HTTP rejected");
        exchange(new byte[0],false);
        byte[] payload=new byte[768*1024];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*11);
        exchange(payload,false);
        ByteArrayOutputStream chunks=new ByteArrayOutputStream();
        chunks.write(ascii(Integer.toHexString(payload.length)+"\r\n"));chunks.write(payload);chunks.write(ascii("\r\n0\r\n\r\n"));
        exchange(chunks.toByteArray(),true);
        AtomicInteger opens=new AtomicInteger();
        try(BoundProxy offline=new BoundProxy((host,port)->{opens.incrementAndGet();throw new IOException("Ethernet unavailable");});Socket s=client(offline)){
            s.getOutputStream().write(ascii(request("GET","")));
            check(header(s.getInputStream()).startsWith("HTTP/1.1 502"),"HTTP fails closed when Ethernet cannot connect");
        }
        check(opens.get()==1,"one supplied connector, no default-network retry");
        try(BoundProxy rejecting=new BoundProxy((host,port)->{throw new AssertionError("out-of-scope target reached connector");});Socket s=client(rejecting)){
            s.getOutputStream().write(ascii("GET http://example.org/ HTTP/1.1\r\n\r\n"));
            check(header(s.getInputStream()).startsWith("HTTP/1.1 403"),"unrelated cleartext host rejected");
        }
        System.out.println("Ufanet HTTP GET/POST, binary and chunked upload, download, scoping and Ethernet fail-closed passed");
    }
}
