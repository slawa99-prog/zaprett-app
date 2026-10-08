package com.slawa.ethernetlink;

import android.app.Activity;
import android.content.*;
import android.hardware.usb.UsbDevice;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

final class DiagnosticsPanel implements AutoCloseable {
    private static final int INK=0xff0f172a,MUTED=0xff64748b;
    final ScrollView view;
    private final Activity activity;
    private final AutoDiagnostics engine;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final TextView summary,progress;
    private final TextView[] labels=new TextView[AutoDiagnostics.TITLES.length],details=new TextView[AutoDiagnostics.TITLES.length];
    private final Button start,copy;
    private final PhysicalLinePanel physical;
    private boolean physicalMode;
    private AutoDiagnostics.Session session;
    private Future<?> task;
    private boolean running,stale;
    private int generation;
    private String timestamp="";
    DiagnosticsPanel(Activity activity){
        this.activity=activity;engine=new AutoDiagnostics(activity);
        view=new ScrollView(activity);view.setFillViewport(true);
        LinearLayout container=new LinearLayout(activity);container.setOrientation(LinearLayout.VERTICAL);view.addView(container);
        LinearLayout modes=new LinearLayout(activity);modes.setPadding(dp(16),dp(4),dp(16),0);container.addView(modes);
        Button quickMode=new Button(activity);quickMode.setText("Автодиагностика");quickMode.setTextSize(12);quickMode.setAllCaps(false);
        Button lineMode=new Button(activity);lineMode.setText("Тест линии");lineMode.setTextSize(12);lineMode.setAllCaps(false);
        modes.addView(quickMode,new LinearLayout.LayoutParams(0,dp(48),1));modes.addView(lineMode,new LinearLayout.LayoutParams(0,dp(48),1));quickMode.setEnabled(false);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(12),dp(20),dp(20));container.addView(root);
        root.addView(text("Автодиагностика",22,true,INK));
        summary=text("Проверка по кабелю: линк, IP и DHCP, шлюз, DNS, интернет, ping и jitter. Нажми «Запустить».",14,false,MUTED);root.addView(summary,space(8));
        LinearLayout buttons=new LinearLayout(activity);
        start=new Button(activity);start.setText("Запустить");start.setAllCaps(false);start.setOnClickListener(v->{if(running)cancel("Проверка остановлена.");else begin();});
        copy=new Button(activity);copy.setText("Копировать");copy.setAllCaps(false);copy.setEnabled(false);copy.setOnClickListener(v->{
            ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Автодиагностика Ethernet",report()));Toast.makeText(activity,"Результат скопирован",Toast.LENGTH_SHORT).show();
        });
        buttons.addView(start,new LinearLayout.LayoutParams(0,dp(50),1));buttons.addView(copy,new LinearLayout.LayoutParams(0,dp(50),1));root.addView(buttons,space(10));
        progress=text("Проверка ещё не запускалась",12,false,MUTED);root.addView(progress,space(4));
        for(int i=0;i<labels.length;i++){
            LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(12),dp(12),dp(12));
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xffffffff);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),0xffe2e8f0);card.setBackground(bg);
            labels[i]=text(AutoDiagnostics.TITLES[i],15,true,INK);details[i]=text("Ожидает запуска",13,false,MUTED);details[i].setTextIsSelectable(true);
            card.addView(labels[i]);card.addView(details[i],space(5));root.addView(card,space(8));
        }
        physical=new PhysicalLinePanel(activity);physical.view.setVisibility(View.GONE);container.addView(physical.view);
        quickMode.setOnClickListener(v->{physical.setVisible(false);physicalMode=false;root.setVisibility(View.VISIBLE);physical.view.setVisibility(View.GONE);quickMode.setEnabled(false);lineMode.setEnabled(true);view.scrollTo(0,0);networksChanged();});
        lineMode.setOnClickListener(v->{if(running)cancel("Проверка остановлена при смене режима.");physicalMode=true;root.setVisibility(View.GONE);physical.view.setVisibility(View.VISIBLE);quickMode.setEnabled(true);lineMode.setEnabled(false);view.scrollTo(0,0);});
    }
    boolean isRunning(){return running||physical.isRunning();}
    private void begin(){
        if(physical.isRunning()){Toast.makeText(activity,"Дождись остановки теста линии",Toast.LENGTH_SHORT).show();return;}
        final int token=++generation;running=true;stale=false;session=new AutoDiagnostics.Session();final AutoDiagnostics.Session current=session;
        timestamp=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date());start.setText("Остановить");copy.setEnabled(true);
        summary.setText("Проверяю Ethernet… Обычно это занимает 10–30 секунд.");summary.setTextColor(INK);
        for(int i=0;i<labels.length;i++){labels[i].setText(AutoDiagnostics.TITLES[i]);labels[i].setTextColor(INK);details[i].setText("Ожидает проверки");}
        progress.setText("Шаг 1 из "+labels.length);
        task=worker.submit(()->engine.run(current,new AutoDiagnostics.Listener(){
            public void row(int i,AutoDiagnostics.State state,String detail){handler.post(()->{
                if(token!=generation)return;
                String prefix=state==AutoDiagnostics.State.PASS?"✓ ":state==AutoDiagnostics.State.SKIP?"— ":"! ";
                labels[i].setText(prefix+AutoDiagnostics.TITLES[i]);labels[i].setTextColor(state==AutoDiagnostics.State.PASS?0xff15803d:state==AutoDiagnostics.State.FAIL?0xffb91c1c:state==AutoDiagnostics.State.WARN?0xffa16207:MUTED);
                details[i].setText(detail);progress.setText(i+1<labels.length?"Шаг "+(i+2)+" из "+labels.length:"Завершаю проверку…");
            });}
            public void finished(String result){handler.post(()->{
                if(token!=generation)return;running=false;start.setText("Повторить");summary.setText(result);progress.setText("Проверено: "+timestamp);
            });}
        }));
    }
    void networksChanged(){
        if(session!=null&&!stale&&engine.changed(session)){
            if(running)cancel("Сеть изменилась. Запусти проверку заново.");
            else{stale=true;summary.setText("Сеть изменилась. Ниже результат предыдущей проверки; запусти новую.");}
        }
    }
    void usbDetached(UsbDevice device){physical.usbDetached(device);if(running)cancel("USB-адаптер отключён. Проверка остановлена.");else if(session!=null){stale=true;summary.setText("USB-адаптер отключён. Ниже результат предыдущей проверки.");}}
    void setVisible(boolean visible){physical.setVisible(visible&&physicalMode);if(!visible&&running)cancel("Проверка остановлена при выходе из раздела.");if(visible)networksChanged();}
    private void cancel(String reason){
        generation++;running=false;stale=true;if(session!=null)session.cancel();if(task!=null)task.cancel(true);
        start.setText("Повторить");summary.setText(reason);progress.setText("Проверка не завершена");
        for(TextView detail:details)if("Ожидает проверки".contentEquals(detail.getText()))detail.setText("Не проверено — остановлено");
    }
    String report(){
        StringBuilder out=new StringBuilder("\n[Автодиагностика Ethernet Link "+BuildConfig.VERSION_NAME+"]\n"+timestamp+"\n");
        out.append("Выполняется: ").append(running).append("; сеть изменилась / остановлено: ").append(stale).append('\n').append(summary.getText()).append('\n');
        for(int i=0;i<labels.length;i++)out.append('\n').append(labels[i].getText()).append(":\n").append(details[i].getText()).append('\n');
        return out.append("\nPing не измеряет CRC-ошибки. Тесты используют выбранную Ethernet-сеть.\nJitter — средняя абсолютная разница RTT соседних успешных ping; таймаут разрывает пару.\n").append(physical.report()).toString();
    }
    @Override public void close(){physical.close();if(session!=null)session.cancel();if(task!=null)task.cancel(true);generation++;worker.shutdownNow();engine.close();handler.removeCallbacksAndMessages(null);}
    private TextView text(String value,int size,boolean bold,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams space(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;}
}
