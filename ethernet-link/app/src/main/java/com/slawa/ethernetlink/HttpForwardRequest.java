package com.slawa.ethernetlink;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One HTTP request per upstream connection. Bodies are streamed, never buffered here. */
final class HttpForwardRequest {
    final String host;
    final byte[] header;
    private HttpForwardRequest(String host,String header){this.host=host;this.header=header.getBytes(StandardCharsets.US_ASCII);}
    static HttpForwardRequest parse(String input){
        if(input==null||!input.endsWith("\r\n\r\n"))return null;
        for(int i=0;i<input.length();i++){
            char c=input.charAt(i);if(c>126||(c<32&&c!='\r'&&c!='\n'&&c!='\t'))return null;
        }
        try{
            String[] lines=input.substring(0,input.length()-4).split("\r\n",-1);
            String[] first=lines[0].split(" ",-1);
            if(first.length!=3||!Arrays.asList("GET","HEAD","POST","OPTIONS").contains(first[0])
                ||!(first[2].equals("HTTP/1.1")||first[2].equals("HTTP/1.0")))return null;
            URI url=new URI(first[1]);
            if(!SpeedtestSite.allowsHttp(url)||url.getRawFragment()!=null)return null;
            String host=url.getHost().toLowerCase(Locale.ROOT),path=url.getRawPath();
            if(path==null||path.isEmpty())path="/";
            if(url.getRawQuery()!=null)path+="?"+url.getRawQuery();
            ArrayList<String[]> fields=new ArrayList<>();Set<String> hop=new HashSet<>();
            Collections.addAll(hop,"connection","proxy-connection","proxy-authorization","proxy-authenticate","keep-alive","te","upgrade");
            boolean length=false,chunked=false,hostSeen=false;
            for(int i=1;i<lines.length;i++){
                int colon=lines[i].indexOf(':');if(colon<=0)return null;
                String name=lines[i].substring(0,colon),value=lines[i].substring(colon+1).trim();
                if(!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||value.indexOf('\r')>=0||value.indexOf('\n')>=0)return null;
                String key=name.toLowerCase(Locale.ROOT);
                if(key.equals("host")){
                    if(hostSeen||!(value.equalsIgnoreCase(host)||value.equalsIgnoreCase(host+":80")))return null;
                    hostSeen=true;
                }else if(key.equals("content-length")){
                    if(length||!value.matches("[0-9]+"))return null;
                    Long.parseLong(value);length=true;
                }else if(key.equals("transfer-encoding")){
                    if(chunked||!value.equalsIgnoreCase("chunked"))return null;
                    chunked=true;
                }else if(key.equals("connection")||key.equals("proxy-connection")){
                    for(String item:value.split(",")){
                        String token=item.trim().toLowerCase(Locale.ROOT);
                        if(!token.matches("[!#$%&'*+.^_`|~0-9a-z-]+"))return null;
                        // Do not strip framing or authority headers named by an invalid peer.
                        if(token.equals("content-length")||token.equals("transfer-encoding")||token.equals("host"))return null;
                        hop.add(token);
                    }
                }
                fields.add(new String[]{name,key,value});
            }
            if(length&&chunked)return null;
            StringBuilder out=new StringBuilder(first[0]+" "+path+" HTTP/1.1\r\nHost: "+host+"\r\n");
            for(String[] field:fields)if(!field[1].equals("host")&&!hop.contains(field[1]))
                out.append(field[0]).append(": ").append(field[2]).append("\r\n");
            // WebView opens a fresh proxy request for each HTTP transaction. No
            // cross-origin keep-alive reuse or response/body rewriting is needed.
            out.append("Connection: close\r\n\r\n");
            return new HttpForwardRequest(host,out.toString());
        }catch(Exception e){return null;}
    }
}
