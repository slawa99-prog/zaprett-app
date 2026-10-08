package com.slawa.ethernetlink;
import java.util.Locale;
/** Only completed echo requests are loss samples. OS denials are not packet loss. */
final class ProbeStats {
    int replies,timeouts,unavailable;double sumMs,minMs=Double.POSITIVE_INFINITY,maxMs;
    int jitterPairs;double jitterSumMs,previousMs=Double.NaN;
    void add(int status,int micros){
        if(status==0&&micros>=0){
            double ms=micros/1000.0;replies++;sumMs+=ms;minMs=Math.min(minMs,ms);maxMs=Math.max(maxMs,ms);
            if(!Double.isNaN(previousMs)){jitterSumMs+=Math.abs(ms-previousMs);jitterPairs++;}previousMs=ms;
        }else{previousMs=Double.NaN;if(status==1)timeouts++;else unavailable++;}
    }
    boolean measurable(){return unavailable==0&&replies+timeouts>0;}
    double loss(){return measurable()?100.0*timeouts/(replies+timeouts):Double.NaN;}
    double jitter(){return measurable()&&jitterPairs>0?jitterSumMs/jitterPairs:Double.NaN;}
    String describe(){
        if(unavailable>0)return "ICMP недоступен или проверка прервана сетевой ошибкой. Потери не вычислены.";
        if(replies+timeouts==0)return "Нет измерений.";
        String out=String.format(Locale.US,"Ответы: %d/%d · без ответа: %.0f%%",replies,replies+timeouts,loss());
        if(replies>0)out+=String.format(Locale.US,"\nRTT: средний %.1f мс · мин. %.1f · макс. %.1f",sumMs/replies,minMs,maxMs);
        else out+="\nУзел может не отвечать на ping. Это не доказывает отсутствие интернета.";
        out+=Double.isNaN(jitter())?"\nJitter (RTT): недостаточно последовательных ответов":String.format(Locale.US,"\nJitter (RTT): %.1f мс · пар ответов: %d",jitter(),jitterPairs);
        return out;
    }
}
