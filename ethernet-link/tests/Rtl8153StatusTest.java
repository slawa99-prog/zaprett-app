package com.slawa.ethernetlink;
public final class Rtl8153StatusTest {
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    private static byte[] bytes(int status){return new byte[]{(byte)status,(byte)(status>>>8),0,0};}
    private static Rtl8153Status.Sample status(int status){return Rtl8153Status.decode(bytes(status),4);}
    public static void main(String[] args){
        check(status(0x13).speed==1000 && status(0x13).duplex==1,"1000 full");
        check(status(0x0b).speed==100 && status(0x0b).duplex==1,"100 full");
        check(status(0x0a).speed==100 && status(0x0a).duplex==0,"100 half");
        check(status(0x07).speed==10,"10 full");
        check(status(0x73).speed==1000,"flow-control flags do not change speed");
        check(status(0).link==0 && status(0x11).speed<0,"down hides retained speed bits");
        check(status(0xffff).link<0,"all ones is invalid, never 1000");
        check(status(0x1b).speed<0 && status(0x03).speed<0,"conflicting/missing speed flags");
        check(status(0x413).speed<0,"unsupported high-speed flags");
        for(int count:new int[]{-1,0,1,2,3})check(Rtl8153Status.decode(bytes(0x13),count).speed<0,"short/failed transfer "+count);
        check(Rtl8153Status.decode(new byte[0],4).link<0,"bounds");
        check(Rtl8153Status.combine(status(0x13),status(0x0b)).speed<0,"speed change");
        check(Rtl8153Status.combine(status(0x13),status(0)).speed<0,"unplug during sample");
        check(Rtl8153Status.combine(status(0xffff),status(0x13)).speed<0,"one failed sample");
        final int[] calls={0};
        Rtl8153Status.Sample read=Rtl8153Status.read((type,request,value,index,buffer,length,timeout)->{
            check(type==0xc0,"read-only vendor/device transfer");
            check(request==5 && value==0xe908 && index==0x133,"exact PHY status request");
            check(length==4 && buffer.length==4 && timeout>0 && timeout<=1000,"size and finite timeout");
            calls[0]++;System.arraycopy(bytes(0x13),0,buffer,0,4);return 4;
        },new StringBuilder());
        check(calls[0]==2 && read.speed==1000,"two fresh samples");
        System.out.println("RTL8153 protocol, read-only requests and transition checks passed");
    }
}
