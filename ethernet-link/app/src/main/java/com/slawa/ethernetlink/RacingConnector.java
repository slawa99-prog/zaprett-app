package com.slawa.ethernetlink;

import java.io.IOException;
import java.net.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Staggered address racing, using only the caller's Network DNS and bound channels.
 * A broken IPv6 route must not consume the site's two-second WebSocket deadline.
 * Nonblocking connects need no extra thread per candidate or default route. */
final class RacingConnector implements BoundProxy.Connector,AutoCloseable {
    interface Resolver { InetAddress[] resolve(String host)throws IOException; }
    interface ChannelFactory { SocketChannel open()throws IOException; }
    private static final int STAGGER_MS=200,CONNECT_MS=6000;
    private final Resolver resolver;
    private final ChannelFactory factory;
    private final Set<SocketChannel> pending=new HashSet<>();
    private final Set<Selector> selectors=new HashSet<>();
    private final Map<String,InetAddress> preferred=new LinkedHashMap<String,InetAddress>(128,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,InetAddress> e){return size()>128;}
    };
    private final AtomicLong opened=new AtomicLong(),raced=new AtomicLong(),errors=new AtomicLong(),dnsMax=new AtomicLong(),connectMax=new AtomicLong();
    private volatile boolean closed;
    RacingConnector(Resolver resolver,ChannelFactory factory){this.resolver=resolver;this.factory=factory;}
    @Override public Socket open(String host,int port)throws IOException{
        if(closed)throw new IOException("connector closed");
        long dnsStart=System.nanoTime();
        InetAddress[] resolved;
        try{resolved=resolver.resolve(host);}catch(IOException e){errors.incrementAndGet();throw e;}
        maximum(dnsMax,elapsed(dnsStart));
        InetAddress first;synchronized(this){first=preferred.get(host);}
        List<InetAddress> addresses=order(resolved,first);
        if(addresses.isEmpty())throw new UnknownHostException("No addresses on Ethernet");
        long started=System.nanoTime(),deadline=started+CONNECT_MS*1_000_000L,nextLaunch=started;
        Selector selector=Selector.open();
        List<SocketChannel> attempts=new ArrayList<>();
        SocketChannel winner=null;
        IOException failure=new SocketTimeoutException("Ethernet connect timeout");
        int next=0,connecting=0;
        try{
            synchronized(this){if(closed)throw new IOException("connector closed");selectors.add(selector);}
            while(winner==null&&!closed&&System.nanoTime()<deadline){
                long now=System.nanoTime();
                if(next<addresses.size()&&(connecting==0||now>=nextLaunch)){
                    InetAddress address=addresses.get(next++);
                    SocketChannel channel=null;
                    if(next>1)raced.incrementAndGet();
                    try{
                        channel=factory.open();attempts.add(channel);
                        synchronized(this){if(closed)throw new IOException("connector closed");pending.add(channel);}
                        channel.configureBlocking(false);
                        if(channel.connect(new InetSocketAddress(address,port))){winner=channel;break;}
                        channel.register(selector,SelectionKey.OP_CONNECT);connecting++;
                    }catch(IOException e){failure=e;closeChannel(channel);}
                    nextLaunch=System.nanoTime()+STAGGER_MS*1_000_000L;
                }
                if(connecting==0){if(next==addresses.size())throw failure;continue;}
                long wake=next<addresses.size()?Math.min(nextLaunch,deadline):deadline;
                selector.select(Math.max(1,Math.min(250,(wake-System.nanoTime())/1_000_000L)));
                Iterator<SelectionKey> keys=selector.selectedKeys().iterator();
                while(keys.hasNext()){
                    SelectionKey key=keys.next();keys.remove();
                    SocketChannel channel=(SocketChannel)key.channel();
                    try{
                        if(key.isValid()&&channel.finishConnect()){winner=channel;break;}
                    }catch(IOException e){failure=e;key.cancel();closeChannel(channel);connecting--;}
                }
            }
            if(winner==null){if(closed)throw new IOException("connector closed");throw failure;}
        }catch(IOException e){errors.incrementAndGet();throw e;}
        finally{
            // Unregister keys before the winning channel returns to blocking mode.
            try{selector.close();}catch(IOException ignored){}
            synchronized(this){selectors.remove(selector);}
            for(SocketChannel channel:attempts)if(channel!=winner){closeChannel(channel);synchronized(this){pending.remove(channel);}}
        }
        try{
            winner.configureBlocking(true);
            synchronized(this){
                if(closed)throw new IOException("connector closed");
                pending.remove(winner);preferred.put(host,winner.socket().getInetAddress());
            }
            maximum(connectMax,elapsed(started));opened.incrementAndGet();return winner.socket();
        }catch(IOException e){closeChannel(winner);synchronized(this){pending.remove(winner);}errors.incrementAndGet();throw e;}
    }
    static List<InetAddress> order(InetAddress[] input,InetAddress preferred){
        List<InetAddress> v4=new ArrayList<>(),v6=new ArrayList<>(),out=new ArrayList<>();
        if(input==null)return out;
        for(InetAddress a:input){List<InetAddress> family=a instanceof Inet6Address?v6:v4;if(!family.contains(a))family.add(a);}
        boolean ipv6=input.length>0&&input[0] instanceof Inet6Address;
        if(preferred!=null&&(v4.remove(preferred)||v6.remove(preferred))){out.add(preferred);ipv6=!(preferred instanceof Inet6Address);}
        while((!v4.isEmpty()||!v6.isEmpty())&&out.size()<8){
            List<InetAddress> family=ipv6?v6:v4;if(family.isEmpty())family=ipv6?v4:v6;
            out.add(family.remove(0));ipv6=!ipv6;
        }
        return out;
    }
    private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000L;}
    private static void maximum(AtomicLong counter,long value){counter.accumulateAndGet(value,Math::max);}
    private static void closeChannel(SocketChannel c){if(c!=null)try{c.close();}catch(IOException ignored){}}
    String report(){return "connector.opened="+opened+"\nconnector.raced="+raced+"\nconnector.errors="+errors+
        "\nconnector.dns_max_ms="+dnsMax+"\nconnector.connect_max_ms="+connectMax+"\n";}
    @Override public synchronized void close(){
        closed=true;for(Selector s:selectors)s.wakeup();for(SocketChannel c:pending)closeChannel(c);pending.clear();preferred.clear();
    }
}
