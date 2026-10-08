package com.slawa.ethernetlink;

import java.net.URI;
import java.util.Locale;

/** Site policy shared by WebView navigation and the Ethernet HTTP relay. */
final class SpeedtestSite {
    static final String HOME="http://speedtest.ufanet.ru/";
    static boolean allowsHttpHost(String host){
        if(host==null)return false;
        String name=host.toLowerCase(Locale.ROOT);
        return name.length()<=253&&name.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")&&!name.contains("..")
            &&(name.equals("ufanet.ru")||name.endsWith(".ufanet.ru"));
    }
    static boolean allowsHttp(URI url){
        return "http".equalsIgnoreCase(url.getScheme())&&allowsHttpHost(url.getHost())
            &&url.getRawUserInfo()==null&&(url.getPort()==-1||url.getPort()==80);
    }
    static boolean allowsNavigation(String address){
        if(address==null)return false;
        try{
            URI url=new URI(address);
            return allowsHttp(url)||("https".equalsIgnoreCase(url.getScheme())&&url.getHost()!=null
                &&url.getRawUserInfo()==null&&(url.getPort()==-1||url.getPort()==443));
        }catch(Exception e){return false;}
    }
}
