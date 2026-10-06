package com.slawa.ethernetlink;

import java.util.*;

public final class UsbSupportTest {
    private static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    private static byte[] bytes(int v,int n){byte[] b=new byte[n];b[0]=(byte)v;b[1]=(byte)(v>>>8);return b;}
    public static void main(String[] args){
        check(Rtl8153Status.decode(new byte[]{(byte)0x93,0,2,0},4).speed==1000,"actual S24 RTL8153 report");
        check(Rtl8153Status.decode(bytes(0x403,4),4,2500).speed==2500,"RTL8156 2500");
        check(Rtl8153Status.decode(bytes(0x403,4),4,1000).speed<0,"8153 cannot report 2500");
        check(Rtl8153Status.decode(bytes(0x13,4),4,100).speed<0,"8152 cap");
        check(Rtl8153Status.decode(bytes(0x0b,4),4,100).speed==100,"8152 100");
        for(int invalid:new int[]{0x413,0x203,0x103,0x1403,0x4403})
            check(Rtl8153Status.decode(bytes(invalid,4),4,2500).speed<0,"ambiguous/high bits");
        for(int[] c:new int[][]{{0xa400,1000,1},{0x8400,1000,0},{0x6400,100,1},{0x4400,100,0},{0x2400,10,1},{0x0400,10,0}}){
            Rtl8153Status.Sample s=AsixStatus.decode(bytes(c[0],2),2);
            check(s.link==1&&s.speed==c[1]&&s.duplex==c[2],"ASIX modes");
        }
        check(AsixStatus.decode(bytes(0xa000,2),2).link==0,"ASIX stale speed hidden without carrier");
        check(AsixStatus.decode(bytes(0xc400,2),2).speed<0,"ASIX reserved encoding");
        check(AsixStatus.decode(bytes(0xffff,2),2).link<0,"ASIX all ones");
        for(int n:new int[]{-1,0,1,3})check(AsixStatus.decode(bytes(0xa400,2),n).link<0,"ASIX invalid size");
        final int[] calls={0};
        Rtl8153Status.Sample asix=AsixStatus.read((type,req,value,index,buffer,n,timeout)->{
            check(type==0xc0&&req==2&&value==3&&(index==0x11||index==0x1f)&&n==2&&timeout==500,"ASIX IN-only fields");
            calls[0]++;System.arraycopy(bytes(index==0x1f?0:0xa400,2),0,buffer,0,2);return 2;
        },new StringBuilder());
        check(calls[0]==4&&asix.speed==1000,"ASIX page guards and fresh status samples");
        check(AsixStatus.read((a,b,c,d,e,f,g)->{e[0]=1;return 2;},new StringBuilder()).link<0,"never read PHY on nonzero page");
        final int[] unstable={0};
        check(AsixStatus.read((a,b,c,d,e,f,g)->{
            int v=d==0x1f?0:++unstable[0]==1?0xa400:0x6400;
            System.arraycopy(bytes(v,2),0,e,0,2);return 2;
        },new StringBuilder()).speed<0,"ASIX autonegotiation transition");
        Set<String> ids=new HashSet<>();
        for(int[][] group:new int[][][]{UsbAdapterCatalog.REALTEK_IDS,UsbAdapterCatalog.ASIX_IDS})
            for(int[] id:group){check(ids.add(id[0]+":"+id[1]),"unique IDs");check(UsbAdapterCatalog.find(id[0],id[1])!=null,"known ID");}
        check(ids.size()==40,"catalog size");
        check(UsbAdapterCatalog.find(0x0bda,0x8153)==UsbAdapterCatalog.Kind.REALTEK_GBE,"RTL8153 preserved");
        check(UsbAdapterCatalog.find(0x0bda,0x8156)==UsbAdapterCatalog.Kind.REALTEK_2G5,"RTL8156");
        check(UsbAdapterCatalog.find(0x0b95,0x1790)==UsbAdapterCatalog.Kind.ASIX,"ASIX");
        check(UsbAdapterCatalog.find(0x0bda,0x8157)==null&&UsbAdapterCatalog.find(0x1234,0x8153)==null,"unknown IDs never probed");
        UsbPermissionGate gate=new UsbPermissionGate();
        check(gate.shouldRequest("a",false,false),"first attach prompts");
        check(!gate.shouldRequest("a",false,false),"deny/resume cannot loop");
        check(gate.shouldRequest("a",false,true),"explicit retry after denial");
        check(!gate.shouldRequest("a",true,true),"already allowed, no dialog");
        gate.detached("a");check(gate.shouldRequest("a",false,false),"reused path after detach");
        gate.retain(Collections.emptySet());check(gate.shouldRequest("a",false,false),"detach while app paused");
        System.out.println("USB families, protocols, 40-ID whitelist and permission lifecycle passed");
    }
}
