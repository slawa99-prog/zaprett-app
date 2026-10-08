package com.slawa.ethernetlink;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Main-document failures must not be confused with failed ads, frames or scripts. */
final class WebPageState {
    private String document;
    private boolean failed,visible;
    void started(String url){document=url;failed=false;visible=false;}
    boolean isDocument(String url){return sameDocument(document,url);}
    boolean fail(String url){if(!isDocument(url))return false;failed=true;return true;}
    boolean sslFailed(String url){return !visible&&fail(url);}
    boolean committed(String url){if(!isDocument(url)||failed)return false;visible=true;return true;}
    boolean failed(){return failed;}
    static boolean sameDocument(String a,String b){
        if(a==null||b==null)return false;
        try{
            URI x=new URI(a),y=new URI(b);
            if(x.getHost()==null||y.getHost()==null)return false;
            return Objects.equals(lower(x.getScheme()),lower(y.getScheme()))
                &&lower(x.getHost()).equals(lower(y.getHost()))&&port(x)==port(y)
                &&Objects.equals(x.getRawUserInfo(),y.getRawUserInfo())
                &&path(x).equals(path(y))&&Objects.equals(x.getRawQuery(),y.getRawQuery());
        }catch(Exception e){return false;}
    }
    private static String lower(String s){return s==null?"":s.toLowerCase(Locale.ROOT);}
    private static String path(URI u){String s=u.getRawPath();return s==null||s.isEmpty()?"/":s;}
    private static int port(URI u){return u.getPort()>=0?u.getPort():"https".equalsIgnoreCase(u.getScheme())?443:80;}
}
