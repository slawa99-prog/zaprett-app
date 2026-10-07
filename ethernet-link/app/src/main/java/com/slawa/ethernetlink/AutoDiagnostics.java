package com.slawa.ethernetlink;

import android.content.Context;
import android.net.*;
import android.os.Build;
import android.os.CancellationSignal;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Small, user-started checks on one captured Ethernet Network. No default sockets. */
final class AutoDiagnostics implements AutoCloseable {
    enum State { PASS,WARN,FAIL,SKIP }
    static final String[] TITLES={"Физический линк","IP-адрес Ethernet","Шлюз","Интернет по IP","DNS","Сайты по HTTPS","Ping и потери ответов"};
    interface Listener {void row(int index,State state,String detail);void finished(String summary);}
    static final class Session {
        volatile boolean cancelled;volatile Network network;volatile String fingerprint;
        private final Set<AutoCloseable> resources=new HashSet<>();
        synchronized void add(AutoCloseable c)throws InterruptedIOException{if(cancelled){close(c);throw new InterruptedIOException("cancelled");}resources.add(c);}
        synchronized void remove(AutoCloseable c){resources.remove(c);}
        synchronized void cancel(){cancelled=true;for(AutoCloseable c:resources)close(c);resources.clear();}
        private static void close(AutoCloseable c){try{c.close();}catch(Exception ignored){}}
    }
    static final class Snapshot {
        Network network;LinkProperties properties;boolean vpn,multiple;
        boolean ipv4,ipv6,defaultRoute;
    }
    private final Context context;
    private final ConnectivityManager cm;
    private final ExecutorService dnsWorkers=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"ethernet-dns");t.setDaemon(true);return t;});
    AutoDiagnostics(Context context){this.context=context.getApplicationContext();cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);}
    Snapshot snapshot(){
        Snapshot s=new Snapshot();
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c==null)continue;
            if(c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))s.vpn=true;
            if(c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
                if(s.network!=null){s.multiple=true;continue;}s.network=n;s.properties=cm.getLinkProperties(n);
            }
        }
        if(s.properties!=null){
            for(LinkAddress a:s.properties.getLinkAddresses())if(usable(a.getAddress())){if(a.getAddress() instanceof Inet4Address)s.ipv4=true;else s.ipv6=true;}
            for(RouteInfo r:s.properties.getRoutes())if(r.isDefaultRoute())s.defaultRoute=true;
        }
        return s;
    }
    private static boolean usable(InetAddress a){return !a.isLinkLocalAddress()&&!a.isLoopbackAddress()&&!a.isAnyLocalAddress();}
    static String fingerprint(Snapshot s){return s.network+"|"+s.multiple+"|"+s.vpn+"|"+(s.properties==null?"":s.properties.toString());}
    boolean changed(Session session){return session.fingerprint!=null&&!session.fingerprint.equals(fingerprint(snapshot()));}
    private void check(Session s)throws InterruptedIOException{
        if(s.cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Проверка остановлена");
        if(s.network!=null&&changed(s))throw new InterruptedIOException("Ethernet или настройки сети изменились. Запусти проверку заново.");
    }
    void run(Session session,Listener listener){
        DiagnosisRules.Evidence evidence=new DiagnosisRules.Evidence();int next=0;
        try{
            check(session);LinkDetector.Result link=new LinkDetector(context).detect();check(session);
            evidence.linkUp=link.link==LinkDecision.UP;
            String text=link.link==LinkDecision.DOWN?"Линка нет. Проверь кабель и порт.":link.link==LinkDecision.UP?"Линк подключён.":"Состояние физического линка неизвестно.";
            if(link.speed>0)text+="\n"+link.speed+" Мбит/с · "+link.duplex;
            if(link.usbPermissionNeeded)text+="\nДля точной скорости разреши USB-доступ на вкладке «Линк».";
            if(link.speed==100)text+="\n100 Мбит/с — согласованная скорость, сама по себе это не неисправность.";
            if("Half Duplex".equals(link.duplex))text+="\nHalf Duplex: проверь согласование дуплекса на порту.";
            listener.row(next++,link.link==LinkDecision.DOWN?State.FAIL:evidence.linkUp&&!link.conflict&&!"Half Duplex".equals(link.duplex)?State.PASS:State.WARN,text);
            Snapshot s=snapshot();session.network=s.network;session.fingerprint=fingerprint(s);
            evidence.network=s.network!=null&&s.properties!=null&&!s.multiple;evidence.vpn=s.vpn;evidence.configured=(s.ipv4||s.ipv6)&&s.defaultRoute;
            if(!evidence.network){
                listener.row(next++,State.WARN,s.multiple?"Найдено несколько Ethernet-подключений. Оставь одно для проверки.":"Android не сообщил IP-подключение Ethernet. Линк может существовать без IP-адреса.");
                skip(listener,next,"Нет однозначно выбранного Ethernet-подключения.");
                listener.finished(s.multiple?"Оставь одно Ethernet-подключение и повтори проверку.":DiagnosisRules.summary(evidence));return;
            }
            StringBuilder config=new StringBuilder("Интерфейс: "+s.properties.getInterfaceName());
            for(LinkAddress a:s.properties.getLinkAddresses()){
                config.append('\n').append(a.getAddress().getHostAddress()).append('/').append(a.getPrefixLength());
                if(a.getAddress() instanceof Inet4Address)config.append(" · маска ").append(mask(a.getPrefixLength()));
            }
            config.append("\nMTU: ").append(s.properties.getMtu());
            if(!(s.ipv4||s.ipv6))config.append("\nРабочий IP-адрес не назначен. DHCP или статическая настройка не подтверждены.");
            listener.row(next++,s.ipv4||s.ipv6?State.PASS:State.FAIL,config.toString());
            InetAddress gateway=null;StringBuilder routes=new StringBuilder();
            for(RouteInfo r:s.properties.getRoutes())if(r.isDefaultRoute()){
                if(routes.length()>0)routes.append('\n');
                if(r.hasGateway()){routes.append(r.getGateway().getHostAddress());if(gateway==null||r.getGateway() instanceof Inet4Address)gateway=r.getGateway();}
                else routes.append("Прямой маршрут без адреса шлюза");
            }
            if(!s.defaultRoute)routes.append("Маршрут по умолчанию отсутствует.");
            if(evidence.vpn||!evidence.configured){
                listener.row(next++,s.defaultRoute?State.SKIP:State.WARN,routes+"\nАктивная проверка не выполнена.");
                skip(listener,next,evidence.vpn?"Отключи VPN для проверки Ethernet напрямую.":"Нет IP-адреса или маршрута в интернет.");listener.finished(DiagnosisRules.summary(evidence));return;
            }
            if(gateway!=null){ProbeStats p=ping(session,gateway,3);routes.append('\n').append(p.describe());listener.row(next++,p.replies>0&&p.unavailable==0?State.PASS:State.WARN,routes.toString());}
            else listener.row(next++,State.WARN,routes+"\nАдрес шлюза для ping не указан.");
            check(session);
            String[] targets=s.ipv4?(s.ipv6?new String[]{"1.1.1.1","2606:4700:4700::1111"}:new String[]{"1.1.1.1","8.8.8.8"}):new String[]{"2606:4700:4700::1111","2001:4860:4860::8888"};
            InetAddress pingTarget=InetAddress.getByName(targets[0]);StringBuilder tcp=new StringBuilder();
            for(String target:targets){
                check(session);try{
                    long start=System.nanoTime();tcp(session,InetAddress.getByName(target));evidence.tcp=true;pingTarget=InetAddress.getByName(target);
                    tcp.append(target).append(":443 — соединение за ").append((System.nanoTime()-start)/1000000).append(" мс");break;
                }catch(IOException e){check(session);tcp.append(target).append(":443 — ").append(e.getClass().getSimpleName()).append('\n');}
            }
            listener.row(next++,evidence.tcp?State.PASS:State.WARN,tcp.toString());
            StringBuilder dns=new StringBuilder("DNS Ethernet: ");
            for(InetAddress a:s.properties.getDnsServers())dns.append(a.getHostAddress()).append(' ');
            if(Build.VERSION.SDK_INT>=28&&s.properties.isPrivateDnsActive())dns.append("\nЧастный DNS включён");
            try{
                long start=System.nanoTime();List<InetAddress> addresses=resolve(session,"yandex.ru");check(session);
                evidence.dns=!addresses.isEmpty();dns.append("\nyandex.ru: ").append((System.nanoTime()-start)/1000000).append(" мс");
                for(int i=0;i<Math.min(3,addresses.size());i++)dns.append('\n').append(addresses.get(i).getHostAddress());
            }catch(Exception e){check(session);dns.append("\nDNS-проверка не прошла: ").append(e.getClass().getSimpleName());}
            listener.row(next++,evidence.dns?State.PASS:State.WARN,dns.toString());
            StringBuilder https=new StringBuilder();
            for(String url:new String[]{"https://yandex.ru/","https://www.google.com/generate_204"}){
                check(session);try{int code=https(session,url);if(code>=200)evidence.httpsResponses++;https.append(new URL(url).getHost()).append(" — HTTP ").append(code).append('\n');}
                catch(IOException e){check(session);https.append(new URL(url).getHost()).append(" — ").append(e.getClass().getSimpleName()).append('\n');}
            }
            listener.row(next++,evidence.httpsResponses==2?State.PASS:State.WARN,https.toString()+"Сертификаты проверяются. Перенаправления не открываются.");
            evidence.ping=ping(session,pingTarget,6);
            listener.row(next++,evidence.ping.measurable()&&evidence.ping.timeouts==0?State.PASS:State.WARN,"Узел: "+pingTarget.getHostAddress()+"\n"+evidence.ping.describe()+"\nКороткая выборка: 6 запросов. Это не счётчик CRC.");
            check(session);listener.finished(DiagnosisRules.summary(evidence));
        }catch(InterruptedIOException e){skip(listener,next,"Проверка остановлена; результата нет.");listener.finished(e.getMessage());}
        catch(Exception e){skip(listener,next,"Проверка не завершена.");listener.finished("Не удалось завершить проверку: "+e.getClass().getSimpleName());}
    }
    private static void skip(Listener listener,int from,String reason){for(int i=from;i<TITLES.length;i++)listener.row(i,State.SKIP,reason);}
    static String mask(int prefix){long bits=prefix==0?0:(0xffffffffL<<(32-prefix))&0xffffffffL;return ((bits>>>24)&255)+"."+((bits>>>16)&255)+"."+((bits>>>8)&255)+"."+(bits&255);}
    private ProbeStats ping(Session session,InetAddress target,int count)throws Exception{
        ProbeStats p=new ProbeStats();if(!NativeLink.isLoaded()){p.add(2,-1);return p;}
        int scope=target instanceof Inet6Address?((Inet6Address)target).getScopeId():0;
        for(int i=0;i<count;i++){
            check(session);int[] reply=NativeLink.echoProbe(session.network.getNetworkHandle(),target.getAddress(),scope,700);check(session);
            if(reply==null||reply.length<3){p.add(2,-1);break;}p.add(reply[0],reply[1]);if(reply[0]==2)break;
            if(i+1<count)Thread.sleep(150);
        }
        return p;
    }
    private void tcp(Session session,InetAddress target)throws IOException{
        Socket socket=session.network.getSocketFactory().createSocket();session.add(socket);
        try{socket.connect(new InetSocketAddress(target,443),1800);}finally{session.remove(socket);socket.close();}
    }
    private int https(Session session,String target)throws IOException{
        HttpsURLConnection connection=(HttpsURLConnection)session.network.openConnection(new URL(target),java.net.Proxy.NO_PROXY);
        AutoCloseable close=connection::disconnect;session.add(close);
        try{connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setRequestMethod("HEAD");return connection.getResponseCode();}
        finally{session.remove(close);connection.disconnect();}
    }
    private List<InetAddress> resolve(Session session,String host)throws Exception{
        if(Build.VERSION.SDK_INT>=29)return resolveModern(session,host);
        Future<InetAddress[]> pending=dnsWorkers.submit(()->session.network.getAllByName(host));AutoCloseable close=()->pending.cancel(true);session.add(close);
        try{return Arrays.asList(pending.get(4500,TimeUnit.MILLISECONDS));}finally{session.remove(close);pending.cancel(true);}
    }
    @android.annotation.TargetApi(29)
    private List<InetAddress> resolveModern(Session session,String host)throws Exception{
        CancellationSignal signal=new CancellationSignal();AutoCloseable close=signal::cancel;session.add(close);
        CompletableFuture<List<InetAddress>> answer=new CompletableFuture<>();
        try{
            DnsResolver.getInstance().query(session.network,host,DnsResolver.FLAG_NO_CACHE_LOOKUP|DnsResolver.FLAG_NO_CACHE_STORE,Runnable::run,signal,new DnsResolver.Callback<List<InetAddress>>(){
                @Override public void onAnswer(List<InetAddress> result,int rcode){if(rcode==0)answer.complete(result);else answer.completeExceptionally(new IOException("DNS rcode "+rcode));}
                @Override public void onError(DnsResolver.DnsException e){answer.completeExceptionally(e);}
            });
            return answer.get(4500,TimeUnit.MILLISECONDS);
        }finally{session.remove(close);signal.cancel();}
    }
    @Override public void close(){dnsWorkers.shutdownNow();}
}
