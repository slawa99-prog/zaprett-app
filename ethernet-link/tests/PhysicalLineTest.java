package com.slawa.ethernetlink;

import java.util.Arrays;

public final class PhysicalLineTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static long[] stats(long rx,long align,long missed,long packets){return new long[]{rx,align,missed,-1,packets,packets};}
    private static void put(byte[] b,int at,long value,int n){for(int i=0;i<n;i++)b[at+i]=(byte)(value>>>(8*i));}
    public static void main(String[] args){
        byte[] bytes=new byte[64];put(bytes,0,0x123456789L,8);put(bytes,8,1234,8);put(bytes,24,0xf1234567L,4);put(bytes,28,0x1234,2);put(bytes,30,0xabcd,2);
        long[] values=Rtl8153Counters.decode(bytes,64);
        check(values[0]==0xf1234567L&&values[1]==0xabcd&&values[2]==0x1234,"unsigned values and layout offsets");
        check(values[3]==-1&&values[4]==1234&&values[5]==0x123456789L,"CRC unavailable, 64-bit packet totals retained");
        check(Rtl8153Counters.decode(bytes,63)[0]<0&&Rtl8153Counters.decode(null,64)[0]<0,"short reads cannot become zeros");
        byte[] invalid=new byte[64];Arrays.fill(invalid,(byte)255);check(Rtl8153Counters.decode(invalid,64)[0]<0,"all-ff error sentinel rejected");
        check(Rtl8153Counters.decode(new byte[64],64)[0]==0,"an idle zero counter is valid");
        int[] requests={0};Rtl8153Counters.read((type,request,value,index,buffer,length,timeout)->{
            requests[0]++;check(type==0xc0&&request==5&&value==0xe890&&index==0x100&&length==64&&timeout>0,"exact IN-only hardware request");
            System.arraycopy(bytes,0,buffer,0,64);return 64;
        },new StringBuilder());check(requests[0]==1,"no OUT, latch, reset or interface claim needed");
        LineMonitor stable=new LineMonitor(0,true);stable.sample(0,1,100,stats(15,3,6,100));stable.sample(500,1,100,stats(15,3,6,130));
        check(stable.counters[0].value()==0&&stable.counters[4].value()==30,"test-relative deltas, no reset of old errors");
        check(stable.verdict(true).contains("стабильна")&&!stable.verdict(true).contains("проверь"),"constant 100 Mbps is not a fault");
        check(stable.measurements().contains("CRC/FCS: Не поддерживается"),"RX is never relabelled CRC");
        LineMonitor line=new LineMonitor(0,true);line.sample(0,1,1000,stats(0,0,0,0));line.sample(500,0,-1,stats(0,0,0,1));
        check(line.flaps==0&&line.downEvents==1&&line.verdict(true).contains("нестабильности"),"ongoing outage counts as evidence before recovery");
        line.sample(1500,0,-1,stats(0,0,0,1));line.sample(2000,1,100,stats(0,0,0,2));
        check(line.flaps==1&&line.downTotal==1500&&line.speedDrops==1,"one complete up-down-up cycle with duration and speed downgrade");
        line.sample(2500,1,100,stats(0,0,0,3));check(line.flaps==1,"repeated up never duplicates flap");
        LineMonitor initialDown=new LineMonitor(0,false);initialDown.sample(0,0,-1,null);initialDown.sample(1000,1,1000,null);
        check(initialDown.flaps==0&&initialDown.downEvents==0&&initialDown.downTotal==1000,"initial down is not an up-down-up flap");
        LineMonitor unknown=new LineMonitor(0,true);unknown.sample(0,1,1000,stats(0,0,0,0));unknown.sample(500,-1,-1,null);unknown.sample(1000,1,1000,stats(0,0,0,10));
        check(unknown.flaps==0&&unknown.unknownSamples==1&&unknown.verdict(true).contains("пропуски"),"read failure is neither down nor evidence of perfect coverage");
        LineMonitor reset=new LineMonitor(0,true);reset.sample(0,1,1000,stats(50,5,65535,100));reset.sample(500,1,1000,stats(0,0,0,0));
        check(reset.counters[2].value()<0&&reset.counters[2].describe().contains("переполнение"),"counter decrease never becomes huge unsigned delta");
        check(!reset.verdict(true).contains("линия стабильна"),"reset counters cannot certify error-free line");
        LineMonitor packetReset=new LineMonitor(0,true);packetReset.sample(0,1,1000,stats(0,0,0,100));packetReset.sample(500,1,1000,stats(0,0,0,0));
        check(packetReset.counters[0].value()<0,"packet tally reset invalidates seemingly unchanged zero error counters");
        LineMonitor weak=new LineMonitor(0,true);weak.sample(0,1,1000,stats(0,0,0,0));weak.sample(500,1,1000,stats(3,0,1,100));
        check(weak.verdict(true).contains("не доказывают"),"RX errors/missed alone do not prove bad cable");
        weak.sample(1000,1,1000,stats(3,1,1,200));check(weak.verdict(true).contains("нестабильности")&&weak.verdict(true).contains("frame/align"),"alignment error is stronger evidence");
        LineMonitor noTraffic=new LineMonitor(0,true);noTraffic.sample(0,1,1000,stats(0,0,0,100));noTraffic.sample(500,1,1000,stats(0,0,0,100));
        check(noTraffic.verdict(true).contains("нагрузкой не выполнена"),"idle link cannot pass as a load test");
        stable.sample(1000,1,100,null);check(stable.counters[0].value()<0&&stable.verdict(true).contains("не полностью"),"no final reading cannot be reported as +0");
        check(stable.verdict(false).startsWith("Тест не завершён"),"manual cancellation remains partial");
        LineMonitor noRead=new LineMonitor(0,false);noRead.sample(0,-1,-1,null);check(noRead.verdict(false).contains("прочитать не удалось"),"unknown initial status is not a cable outage");
        System.out.println("RTL8153 counters, read-only request, physical flaps, durations, deltas and cautious verdicts passed");
    }
}
