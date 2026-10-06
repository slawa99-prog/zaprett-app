package com.slawa.ethernetlink;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.slawa.ethernetlink.ProxyRegressionTest.*;

public final class ProxyIdleTest {
    public static void main(String[] args)throws Exception{
        for(boolean upload:new boolean[]{false,true}){
            AtomicReference<Throwable> failure=new AtomicReference<>();
            try(ServerSocket server=server();BoundProxy proxy=new BoundProxy((h,p)->new Socket("127.0.0.1",server.getLocalPort()),300)){
                Thread backend=run(failure,()->{
                    try(Socket s=server.accept()){
                        s.setSoTimeout(3000);
                        if(upload){for(int i=0;i<16;i++)check(s.getInputStream().read()==i,"one-way upload "+i);}
                        else for(int i=0;i<16;i++){s.getOutputStream().write(i);Thread.sleep(75);}
                    }
                });
                try(Socket s=connect(proxy)){
                    if(upload)for(int i=0;i<16;i++){s.getOutputStream().write(i);Thread.sleep(75);}
                    else for(int i=0;i<16;i++)check(s.getInputStream().read()==i,"one-way download "+i);
                }
                backend.join(3000);check(!backend.isAlive()&&failure.get()==null,"one-way backend: "+failure.get());
                check(proxy.report().contains("proxy.idle_closed=0"),"active traffic is never idle");
            }
        }
        try(ServerSocket server=server();BoundProxy proxy=new BoundProxy((h,p)->new Socket("127.0.0.1",server.getLocalPort()),200);
            Socket client=connect(proxy);Socket peer=server.accept()){
            peer.setSoTimeout(2000);check(client.getInputStream().read()==-1,"truly idle client released");
            check(peer.getInputStream().read()==-1,"truly idle upstream released");
        }
        System.out.println("One-way traffic survives 4 idle periods; fully idle tunnels expire");
    }
}
