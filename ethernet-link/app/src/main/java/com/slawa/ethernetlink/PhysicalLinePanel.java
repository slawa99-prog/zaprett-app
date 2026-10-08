package com.slawa.ethernetlink;

import android.app.Activity;
import android.content.*;
import android.hardware.usb.UsbDevice;
import android.os.*;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

final class PhysicalLinePanel implements AutoCloseable {
    private static final int[] DURATIONS={30,60,300,900};
    final LinearLayout view;
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Spinner duration;
    private final Button start,copy;
    private final TextView status,measurements,history;
    private final ProgressBar progress;
    private Session session;private boolean running;private int generation;
    private String lastReport="Тест физической линии ещё не запускался.";
    private static final class Session {
        volatile boolean cancelled;volatile String reason="";volatile PhysicalLineReader reader;
        synchronized void cancel(String text){if(!cancelled){reason=text;cancelled=true;}}
    }
    PhysicalLinePanel(Activity activity){
        this.activity=activity;
        view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);view.setPadding(dp(20),dp(12),dp(20),dp(20));
        view.addView(text("Тест физической линии",22,true));
        view.addView(text("Обрывы, изменение скорости и доступные аппаратные ошибки. Интернет и IP-адрес не нужны.",14,false),space(8));
        view.addView(text("Длительность",13,true),space(12));
        duration=new Spinner(activity);ArrayAdapter<String> choices=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,new String[]{"30 секунд","1 минута","5 минут","15 минут"});
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);duration.setAdapter(choices);duration.setSelection(1);view.addView(duration,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout buttons=new LinearLayout(activity);
        start=new Button(activity);start.setText("Запустить");start.setAllCaps(false);start.setOnClickListener(v->{if(running)cancel("Остановлено пользователем.");else begin();});
        copy=new Button(activity);copy.setText("Копировать");copy.setAllCaps(false);copy.setEnabled(false);copy.setOnClickListener(v->{((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Тест физической линии",report()));Toast.makeText(activity,"Результат скопирован",Toast.LENGTH_SHORT).show();});
        buttons.addView(start,new LinearLayout.LayoutParams(0,dp(50),1));buttons.addView(copy,new LinearLayout.LayoutParams(0,dp(50),1));view.addView(buttons,space(8));
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);view.addView(progress,space(8));
        status=text("Выбери время и нажми «Запустить».",15,true);view.addView(status,space(12));
        measurements=text("",14,false);measurements.setTextIsSelectable(true);view.addView(measurements,space(12));
        history=text("",13,false);history.setTextIsSelectable(true);view.addView(history,space(12));
        view.addView(text("Наблюдение без генерации нагрузки. Экран остаётся включённым; выход из раздела останавливает тест. Короткие обрывы между опросами могут быть пропущены.",12,false),space(14));
    }
    boolean isRunning(){return running;}
    private void begin(){
        final int token=++generation,seconds=DURATIONS[duration.getSelectedItemPosition()];final Session current=new Session();session=current;
        running=true;start.setText("Остановить");start.setEnabled(true);duration.setEnabled(false);copy.setEnabled(false);view.setKeepScreenOn(true);
        progress.setMax(seconds);progress.setProgress(0);status.setText("Подключаюсь к адаптеру…");measurements.setText("");history.setText("");
        lastReport="Подготовка теста физической линии.";
        worker.submit(()->run(current,token,seconds));
    }
    private void run(Session current,int token,int seconds){
        LineMonitor monitor=null;String source="",timestamp="",first="",last="",reason="";boolean completed=false;
        try(PhysicalLineReader reader=new PhysicalLineReader(activity.getApplicationContext())){
            current.reader=reader;source=reader.description;
            if(current.cancelled)return;
            PhysicalLineReader.Sample sample=reader.read();
            if(current.cancelled)return;
            long began=SystemClock.elapsedRealtime();timestamp=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date());
            monitor=new LineMonitor(System.currentTimeMillis(),reader.countersSupported);monitor.sample(0,sample.link,sample.speed,sample.counters);first=last=sample.details;
            if(sample.link<0&&sample.counters[0]<0)throw new java.io.IOException("Прямое чтение физического линка недоступно на этом адаптере/телефоне. Тест не может оценить линию.");
            publish(token,seconds,monitor,source,timestamp,first,last,false,"");
            long deadline=began+seconds*1000L,next=began;
            while(!current.cancelled){
                long now=SystemClock.elapsedRealtime();next=Math.min(deadline,Math.max(next+500,now));
                while(!current.cancelled&&(now=SystemClock.elapsedRealtime())<next)Thread.sleep(Math.min(100,next-now));
                if(current.cancelled)break;
                sample=reader.read();if(current.cancelled)break;
                long at=SystemClock.elapsedRealtime()-began;monitor.sample(at,sample.link,sample.speed,sample.counters);last=sample.details;
                completed=SystemClock.elapsedRealtime()>=deadline;
                publish(token,seconds,monitor,source,timestamp,first,last,completed,"");
                if(completed)break;
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();reason="Тест остановлен.";}
        catch(Exception e){reason=e instanceof java.io.IOException?e.getMessage():"Чтение остановлено: "+e.getClass().getSimpleName();}
        finally{
            if(current.cancelled){completed=false;reason=current.reason;}current.reader=null;
            final String finalReason=reason==null?"Тест остановлен.":reason;
            if(monitor!=null)publish(token,seconds,monitor,source,timestamp,first,last,completed,finalReason);
            else{final String noData=finalReason.isEmpty()?"Тест остановлен. Нет измерений.":finalReason;handler.post(()->{if(token==generation){status.setText(noData);lastReport=noData;}});}
            handler.post(()->{if(token==generation){running=false;start.setText("Повторить");start.setEnabled(true);duration.setEnabled(true);copy.setEnabled(true);view.setKeepScreenOn(false);}});
        }
    }
    private void publish(int token,int seconds,LineMonitor monitor,String source,String timestamp,String first,String last,boolean completed,String reason){
        final String data=monitor.measurements(),events=monitor.history();final int at=(int)(monitor.elapsed/1000);
        final boolean ended=completed||!reason.isEmpty();
        final String title=ended?(reason.isEmpty()?"":reason+"\n")+monitor.verdict(completed):"Идёт тест: "+Math.min(at,seconds)+" / "+seconds+" с\n"+source;
        final String report="\n[Тест физической линии Ethernet Link "+BuildConfig.VERSION_NAME+"]\n"+timestamp+"\nИсточник: "+source+"\nВыбрано: "+seconds+" с\n"+
            monitor.report(completed,ended?reason:"Тест выполняется; промежуточные данные.")+"\n[first read]\n"+first+"\n[last read]\n"+last;
        handler.post(()->{if(token!=generation)return;progress.setProgress(Math.min(at,seconds));status.setText(title);measurements.setText(data);history.setText("Журнал событий\n"+events);lastReport=report;copy.setEnabled(true);});
    }
    void cancel(String reason){if(running&&session!=null){session.cancel(reason);start.setEnabled(false);status.setText("Останавливаю тест…");view.setKeepScreenOn(false);}}
    void usbDetached(UsbDevice device){if(session!=null&&session.reader!=null&&session.reader.matches(device))cancel("USB-адаптер отключён. Это не засчитано как обрыв кабеля.");}
    void setVisible(boolean visible){if(!visible)cancel("Тест остановлен при выходе из раздела.");}
    String report(){return lastReport;}
    @Override public void close(){cancel("Приложение закрыто.");generation++;worker.shutdownNow();handler.removeCallbacksAndMessages(null);view.setKeepScreenOn(false);}
    private TextView text(String value,int size,boolean bold){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(bold?0xff0f172a:0xff64748b);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams space(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;}
}
