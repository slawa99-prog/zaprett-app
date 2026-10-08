package com.slawa.ethernetlink;

import java.text.SimpleDateFormat;
import java.util.*;

/** Pure observations on one unchanged adapter. Unknown is never Link Down. */
final class LineMonitor {
    static final String[] COUNTERS={"RX errors","Frame/align errors","RX missed","CRC/FCS","RX packets","TX packets"};
    static final class Delta {
        long baseline=-1,previous=-1,current=-1;boolean reset,increased,started;
        void sample(long value){
            if(!started){started=true;baseline=value;}
            current=value;if(value<0)return;
            if(previous>=0){if(value<previous)reset=true;else if(value>previous)increased=true;}
            previous=value;
        }
        long value(){return baseline>=0&&current>=0&&!reset?current-baseline:-1;}
        String describe(){
            if(reset)return "не определён (сброс или переполнение)";
            if(baseline<0)return "недоступно (нет исходного значения)";
            if(current<0)return "недоступно (нет текущего чтения)";
            return "+"+value();
        }
    }
    private final long wallStart;
    private final boolean hardwareCounters;
    final Delta[] counters=new Delta[Rtl8153Counters.COUNT];
    final List<String> events=new ArrayList<>();
    int samples,unknownSamples,unknownSpeedSamples,flaps,downEvents,speedChanges,speedDrops,lastLink=-1,lastSpeed=-1;
    long elapsed,downSince=-1,downTotal,maxGap;boolean downHadUp,seenUp,seenDown,observedGap,historyTrimmed;
    LineMonitor(long wallStart,boolean hardwareCounters){this.wallStart=wallStart;this.hardwareCounters=hardwareCounters;for(int i=0;i<counters.length;i++)counters[i]=new Delta();}
    void sample(long at,int link,int speed,long[] values){
        at=Math.max(elapsed,at);if(samples>0){maxGap=Math.max(maxGap,at-elapsed);if(at-elapsed>1500)observedGap=true;}elapsed=at;samples++;
        for(int i=0;i<counters.length;i++)counters[i].sample(values==null||i>=values.length?-1:values[i]);
        // A reset of the packet tally also invalidates an apparently unchanged zero error tally.
        boolean tallyDecreased=false;for(Delta d:counters)if(d.reset)tallyDecreased=true;
        if(tallyDecreased)for(Delta d:counters)d.reset=true;
        if(link<0){unknownSamples++;return;}
        if(link==0){
            seenDown=true;
            if(downSince<0){downSince=at;downHadUp=lastLink==1;if(downHadUp)downEvents++;event(at,downHadUp?"Link Down — начало обрыва":"Линк отсутствует при первом наблюдении");}
        }else{
            seenUp=true;
            if(speed<=0)unknownSpeedSamples++;
            if(downSince>=0){
                long duration=at-downSince;downTotal+=duration;if(downHadUp)flaps++;
                event(at,"Link Up — восстановление; без линка ≈ "+seconds(duration)+" с");downSince=-1;downHadUp=false;
            }else if(lastLink<0)event(at,"Link Up"+(speed>0?" · "+speed+" Мбит/с":""));
            if(speed>0){
                if(lastSpeed>0&&lastSpeed!=speed){speedChanges++;if(speed<lastSpeed)speedDrops++;event(at,"Скорость: "+lastSpeed+" → "+speed+" Мбит/с");}
                lastSpeed=speed;
            }
        }
        lastLink=link;
    }
    private void event(long at,String text){
        if(events.size()==100){events.remove(0);historyTrimmed=true;}
        String clock=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date(wallStart+at));
        events.add(clock+" (+"+seconds(at)+" с) · "+text);
    }
    String measurements(){
        StringBuilder s=new StringBuilder("Link flaps: "+flaps+"\nОбрывы после Link Up: "+downEvents);
        if(downSince>=0)s.append("\nЛинк не восстановлен · ≈ ").append(seconds(elapsed-downSince)).append(" с");
        s.append("\nБез линка суммарно: ≈ ").append(seconds(downTotal+(downSince>=0?elapsed-downSince:0))).append(" с")
            .append("\nИзменения скорости: ").append(speedChanges).append(" · понижения: ").append(speedDrops)
            .append("\nПоследняя скорость при Link Up: ").append(lastSpeed>0?lastSpeed+" Мбит/с":"не определена");
        for(int i=0;i<counters.length;i++)s.append('\n').append(COUNTERS[i]).append(": ").append(!hardwareCounters||i==Rtl8153Counters.CRC_FCS?"Не поддерживается":counters[i].describe());
        if(unknownSamples>0)s.append("\nНе удалось прочитать линк: ").append(unknownSamples).append(" раз");
        if(unknownSpeedSamples>0)s.append("\nНе удалось прочитать скорость: ").append(unknownSpeedSamples).append(" раз");
        return s.toString();
    }
    String verdict(boolean completed){
        String prefix=completed?"":"Тест не завершён. ";
        if(samples==0)return prefix+"Нет измерений.";
        boolean strong=counters[Rtl8153Counters.ALIGN_ERRORS].increased||counters[Rtl8153Counters.CRC_FCS].increased;
        if(flaps>0||downEvents>0||speedDrops>0||strong)
            return prefix+"Обнаружены признаки нестабильности физического соединения: "+
                (flaps>0||downEvents>0?"пропадание линка; ":"")+(speedDrops>0?"понижение скорости; ":"")+
                (counters[Rtl8153Counters.ALIGN_ERRORS].increased?"рост frame/align; ":"")+(counters[Rtl8153Counters.CRC_FCS].increased?"рост CRC/FCS; ":"")+
                "проверь кабель, коннекторы, розетку и порт коммутатора. Намеренные переподключения тоже попадают в журнал.";
        if(!seenUp&&!seenDown)return prefix+"Состояние физического линка прочитать не удалось. Оценить стабильность нельзя.";
        if(!seenUp||seenDown)return prefix+"Стабильный Link Up не подтверждён: во время теста линк отсутствовал.";
        if(counters[Rtl8153Counters.RX_ERRORS].increased||counters[Rtl8153Counters.RX_MISSED].increased)
            return prefix+"Обрывы и понижение скорости не замечены, но выросли RX errors или RX missed. Эти счётчики сами по себе не доказывают повреждение кабеля; повтори тест и проверь адаптер, нагрузку и порт.";
        if(unknownSamples>0||unknownSpeedSamples>0||observedGap)return prefix+"В доступных наблюдениях обрывы и ошибки не обнаружены. Есть пропуски чтения; стабильность за весь интервал не подтверждена.";
        if(!completed)return prefix+"В измеренной части обрывы и признаки ошибок не обнаружены.";
        if(!hardwareCounters||counters[0].value()<0||counters[1].value()<0||counters[2].value()<0)
            return "За время теста обрывы и понижение скорости не замечены. Аппаратные ошибки оценены не полностью: счётчики недоступны, сброшены или переполнены.";
        if(counters[Rtl8153Counters.RX_PACKETS].value()<=0)
            return "Линк оставался стабильным, доступные счётчики ошибок не выросли. Приём трафика за тест не подтверждён; проверка под нагрузкой не выполнена.";
        return "За время теста линия стабильна по доступным показателям. Обрывы, понижение скорости и рост доступных аппаратных ошибок не обнаружены. CRC/FCS отдельно не измеряется.";
    }
    String history(){return events.isEmpty()?"Событий пока нет":(historyTrimmed?"Показаны последние 100 событий; итоговые счётчики полные.\n":"")+String.join("\n",events);}
    String report(boolean completed,String reason){
        StringBuilder s=new StringBuilder("Длительность наблюдения: "+seconds(elapsed)+" с\n");
        if(!reason.isEmpty())s.append(reason).append('\n');s.append(verdict(completed)).append("\n\n").append(measurements()).append("\n\nЖурнал:\n").append(history());
        s.append("\n\nОпрос примерно каждые 0.5 с. Время обрывов приблизительное; более короткие события могут быть пропущены. Нагрузка не создаётся.\n");
        s.append("samples=").append(samples).append(" unknown=").append(unknownSamples).append(" max_gap_ms=").append(maxGap).append('\n');
        for(int i=0;i<counters.length;i++)s.append(COUNTERS[i]).append(" baseline=").append(counters[i].baseline).append(" final=").append(counters[i].current).append(" decrease=").append(counters[i].reset).append('\n');
        return s.toString();
    }
    static String seconds(long millis){return String.format(Locale.US,"%.1f",millis/1000.0);}
}
