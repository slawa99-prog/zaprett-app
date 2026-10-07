package com.slawa.ethernetlink;
public final class AutoDiagnosticsTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        ProbeStats stats=new ProbeStats();stats.add(0,1000);stats.add(0,3000);stats.add(1,-1);
        check(stats.replies==2&&stats.timeouts==1&&Math.abs(stats.loss()-100.0/3)<0.001,"loss denominator uses only measured echo requests");
        check(stats.describe().contains("2.0 мс"),"microseconds converted to milliseconds");
        stats.add(2,-1);check(!stats.measurable()&&Double.isNaN(stats.loss()),"permission/network error must not be 100% packet loss");
        check(stats.describe().contains("Потери не вычислены"),"unavailable is explicit");
        ProbeStats blocked=new ProbeStats();for(int i=0;i<6;i++)blocked.add(1,-1);
        check(blocked.loss()==100&&blocked.describe().contains("не доказывает"),"all unanswered ping is not proof of no internet");
        DiagnosisRules.Evidence e=new DiagnosisRules.Evidence();e.linkUp=true;
        check(DiagnosisRules.summary(e).contains("IP-подключение"),"link without DHCP/IP remains distinct");
        e.network=true;check(DiagnosisRules.summary(e).contains("IP-адреса или маршрута"),"no address cannot pass");
        e.configured=true;e.vpn=true;check(DiagnosisRules.summary(e).contains("отключи VPN"),"VPN must block active direct tests");
        e.vpn=false;e.tcp=true;check(DiagnosisRules.summary(e).contains("DNS-проверка"),"IP pass and DNS failure localized");
        e.dns=true;check(DiagnosisRules.summary(e).contains("не подтверждён"),"DNS alone does not prove HTTPS");
        e.httpsResponses=1;e.ping=blocked;String result=DiagnosisRules.summary(e);
        check(result.contains("интернет подтверждён")&&result.contains("не CRC"),"HTTPS reachability beats filtered ping; no cable CRC diagnosis");
        e.httpsResponses=2;e.ping=stats;
        check(DiagnosisRules.summary(e).contains("обоим")&&!DiagnosisRules.summary(e).contains("пропуски"),"unmeasurable ping cannot manufacture packet-loss warning");
        System.out.println("Autodiagnosis verdicts, partial internet, VPN and honest ping/loss statistics passed");
    }
}
