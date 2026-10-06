package com.slawa.ethernetlink;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.slawa.ethernetlink.ProxyRegressionTest.*;

public final class RacingConnectorTest {
    public static void main(String[] args)throws Exception{
        InetAddress v4=InetAddress.getByName("127.0.0.1"),stalled=InetAddress.getByName("127.0.0.2"),v6=InetAddress.getByName("2001:db8::1"),v62=InetAddress.getByName("2001:db8::2");
        check(RacingConnector.order(new InetAddress[]{v6,v62,v4,v4},null).equals(Arrays.asList(v6,v4,v62)),"families interleaved, duplicates removed");
        check(RacingConnector.order(new InetAddress[]{v6,v4},v4).equals(Arrays.asList(v4,v6)),"previous winner preferred, still resolves afresh");
        List<Socket> filled=new ArrayList<>();List<SocketChannel> channels=Collections.synchronizedList(new ArrayList<>());
        // A full TCP listen queue makes the FIRST address silently stall, without
        // relying on public IPs, an external network or a firewall configuration.
        try(ServerSocket blackhole=new ServerSocket(0,1,stalled);ServerSocket good=new ServerSocket(blackhole.getLocalPort(),16,v4)){
            boolean full=false;
            for(int i=0;i<20;i++){
                Socket s=new Socket();try{s.connect(new InetSocketAddress(stalled,blackhole.getLocalPort()),80);filled.add(s);}
                catch(SocketTimeoutException e){s.close();full=true;break;}
            }
            check(full,"stalled first-address fixture ready");AtomicInteger resolutions=new AtomicInteger();
            try(RacingConnector connector=new RacingConnector(host->{resolutions.incrementAndGet();return new InetAddress[]{stalled,v4};},()->{
                SocketChannel c=SocketChannel.open();channels.add(c);return c;
            })){
                long start=System.nanoTime();
                try(Socket socket=connector.open("fixture.invalid",good.getLocalPort());Socket peer=good.accept()){
                    long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                    check(ms<1800,"working address selected before WebSocket deadline: "+ms+"ms");
                    check(channels.size()==2&&!channels.get(0).isOpen(),"losing connection cancelled");
                    socket.getOutputStream().write(91);check(peer.getInputStream().read()==91,"winning socket stays usable");
                    System.out.println("Stalled first address bypassed in "+ms+" ms");
                }
                int before=channels.size();
                try(Socket socket=connector.open("fixture.invalid",good.getLocalPort());Socket peer=good.accept()){
                    check(channels.size()==before+1&&resolutions.get()==2,"later connections prefer reachable address, fresh Network DNS");
                }
            }
            CountDownLatch connecting=new CountDownLatch(1);AtomicReference<Throwable> outcome=new AtomicReference<>();
            try(RacingConnector connector=new RacingConnector(host->new InetAddress[]{stalled},()->{
                SocketChannel c=SocketChannel.open();channels.add(c);connecting.countDown();return c;
            })){
                Thread caller=run(outcome,()->{try(Socket s=connector.open("fixture.invalid",blackhole.getLocalPort())){throw new AssertionError("stalled route connected");}});
                check(connecting.await(2,TimeUnit.SECONDS),"connection began");connector.close();caller.join(1500);
                check(!caller.isAlive()&&outcome.get() instanceof IOException,"network loss cancels pending connects");
            }
        }finally{for(Socket s:filled)s.close();}
        for(SocketChannel c:channels)check(!c.isOpen(),"no leaked candidate or winner");
        try(RacingConnector connector=new RacingConnector(h->{throw new UnknownHostException("bound DNS failed");},()->{throw new AssertionError("no socket without DNS");})){
            try{connector.open("fixture.invalid",443);throw new AssertionError("DNS error ignored");}catch(UnknownHostException expected){}
        }
        System.out.println("Address racing, preference, cancellation and bound-DNS failure passed");
    }
}
