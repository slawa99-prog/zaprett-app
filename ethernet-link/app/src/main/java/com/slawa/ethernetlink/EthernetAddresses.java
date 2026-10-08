package com.slawa.ethernetlink;

import android.net.*;
import android.os.Build;
import java.net.InetAddress;
import java.util.*;

/** Local Ethernet addresses only; never borrows the default Wi-Fi/mobile/VPN network. */
final class EthernetAddresses {
    static String read(ConnectivityManager cm){
        List<String> networks=new ArrayList<>();
        try{
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities c=cm.getNetworkCapabilities(n);
                if(c==null||!c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)||c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
                LinkProperties p=cm.getLinkProperties(n);if(p==null)continue;
                Set<String> ips=new LinkedHashSet<>(),gateways=new LinkedHashSet<>();
                for(LinkAddress a:p.getLinkAddresses()){
                    InetAddress ip=a.getAddress();if(!ip.isAnyLocalAddress()&&!ip.isLoopbackAddress())ips.add(ip.getHostAddress());
                }
                for(RouteInfo r:p.getRoutes())if(r.isDefaultRoute()&&r.hasGateway())gateways.add(r.getGateway().getHostAddress());
                networks.add("Ethernet"+(p.getInterfaceName()==null?"":" · "+p.getInterfaceName())+
                    "\nМой IP: "+(ips.isEmpty()?"не назначен":String.join(", ",ips))+
                    "\nШлюз: "+(gateways.isEmpty()?"не указан":String.join(", ",gateways)));
            }
            Collections.sort(networks);
            return networks.isEmpty()?"Мой IP (Ethernet): не назначен\nШлюз: не указан":String.join("\n\n",networks);
        }catch(RuntimeException e){return "Мой IP (Ethernet): недоступен\nШлюз: недоступен";}
    }
    static String dhcp(LinkProperties properties){
        if(properties!=null&&Build.VERSION.SDK_INT>=30)try{
            InetAddress server=properties.getDhcpServerAddress();
            if(server!=null&&!server.isAnyLocalAddress())return "DHCP-сервер: "+server.getHostAddress();
        }catch(RuntimeException ignored){}
        return "DHCP-сервер: не определён";
    }
}
