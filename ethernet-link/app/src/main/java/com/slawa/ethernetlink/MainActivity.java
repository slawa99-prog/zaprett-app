package com.slawa.ethernetlink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int INK=0xff0f172a, MUTED=0xff64748b, BLUE=0xff2563eb, GREEN=0xff15803d, AMBER=0xffa16207;
    private TextView state, speed, details, estimate, hint;
    private Button refresh, usbButton;
    private Rtl8153Usb usb;
    private BroadcastReceiver usbReceiver;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private LinkDetector detector;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private boolean resumed;
    private int generation;
    private String report="Отчёт ещё не готов. Нажми «Обновить».";
    private final Runnable tick=new Runnable(){public void run(){refresh();if(resumed)handler.postDelayed(this,1500);}};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        detector=new LinkDetector(this);
        usb=new Rtl8153Usb(this);
        cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(28),dp(22),dp(28)); root.setBackgroundColor(0xfff8fafc);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        root.addView(text("Ethernet Link",30,true,INK),lp(-1,-2,0));
        root.addView(text("v2 test2 · Проверка USB‑C → Ethernet",14,false,MUTED),lp(-1,-2,5));
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding(dp(18),dp(24),dp(18),dp(24));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(18));bg.setStroke(dp(1),0xffe2e8f0);card.setBackground(bg);
        root.addView(card,lp(-1,-2,24));
        state=text("Проверяю…",18,true,MUTED);state.setGravity(Gravity.CENTER);card.addView(state,lp(-1,-2,0));
        speed=text("—",64,true,INK);card.addView(speed,lp(-2,-2,12));
        card.addView(text("Мбит/с · скорость линка",14,false,MUTED),lp(-2,-2,0));
        hint=text("",15,false,MUTED);hint.setGravity(Gravity.CENTER);card.addView(hint,lp(-1,-2,18));
        details=text("",14,false,MUTED);details.setGravity(Gravity.CENTER);card.addView(details,lp(-1,-2,16));
        usbButton=new Button(this);usbButton.setText("Разрешить USB");usbButton.setAllCaps(false);usbButton.setVisibility(View.GONE);
        usbButton.setOnClickListener(v->{Toast.makeText(this,usb.requestPermission(),Toast.LENGTH_LONG).show();invalidateAndRefresh();});
        root.addView(usbButton,lp(-1,dp(54),18));
        refresh=new Button(this);refresh.setText("Обновить");refresh.setAllCaps(false);refresh.setTextSize(17);refresh.setOnClickListener(v->refresh());root.addView(refresh,lp(-1,dp(54),18));
        Button diagnostics=new Button(this);diagnostics.setText("Диагностика");diagnostics.setAllCaps(false);diagnostics.setOnClickListener(v->diagnostics());root.addView(diagnostics,lp(-1,dp(52),6));
        estimate=text("",13,false,MUTED);root.addView(estimate,lp(-1,-2,18));
        root.addView(text("Подключи адаптер и кабель. Обновление автоматическое. Для чтения линка интернет и IP-адрес не обязательны.",13,false,MUTED),lp(-1,-2,14));
        setContentView(scroll);
        usbReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent i){
                // Re-query UsbManager.hasPermission; never trust incoming intent extras.
                invalidateAndRefresh();
            }
        };
        IntentFilter usbFilter=new IntentFilter(usb.permissionAction());
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        try{
            if(Build.VERSION.SDK_INT>=33)registerReceiver(usbReceiver,usbFilter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(usbReceiver,usbFilter);
        }catch(RuntimeException e){usbReceiver=null;}

    }
    @Override protected void onResume(){
        super.onResume();resumed=true;generation++;handler.post(tick);
        callback=new ConnectivityManager.NetworkCallback(){
            private void changed(){handler.post(()->invalidateAndRefresh());}
            @Override public void onAvailable(Network n){changed();}
            @Override public void onLost(Network n){changed();}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){changed();}
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties p){changed();}
        };
        try {cm.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(),callback);}
        catch(RuntimeException e){callback=null;}
    }
    @Override protected void onPause(){
        resumed=false;generation++;handler.removeCallbacks(tick);
        if(callback!=null){try{cm.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}callback=null;}
        super.onPause();
    }
    @Override protected void onDestroy(){
        if(usbReceiver!=null){try{unregisterReceiver(usbReceiver);}catch(RuntimeException ignored){}usbReceiver=null;}
        worker.shutdownNow();super.onDestroy();
    }
    private void invalidateAndRefresh(){
        generation++;
        if(resumed){speed.setText("—");state.setText("Проверяю…");hint.setText("");details.setText("");refresh();}
    }
    private void refresh(){
        if(!resumed||!busy.compareAndSet(false,true))return;
        final int token=generation;refresh.setEnabled(false);
        worker.execute(()->{
            LinkDetector.Result result;
            try { result=detector.detect(); }
            catch(RuntimeException e){result=new LinkDetector.Result();result.report="Ошибка диагностики: "+e;}
            final LinkDetector.Result ready=result;
            handler.post(()->{
                busy.set(false);refresh.setEnabled(true);
                if(resumed&&generation==token)show(ready);
                else if(resumed)refresh();
            });
        });
    }
    private void show(LinkDetector.Result r){
        report=r.report;speed.setTextColor(INK);speed.setText("—");
        usbButton.setVisibility(r.usbAvailable&&r.usbPermissionNeeded?View.VISIBLE:View.GONE);
        estimate.setText(r.usbDirect?"Прямая проверка USB-адаптера. Интернет и DHCP не нужны.":"Оценка Android: "+r.estimate+"\nНе подтверждает скорость физического линка.");
        details.setText(r.found?(r.usbDirect?"Адаптер: Realtek RTL8153":"Интерфейс: "+r.iface)+"\nДуплекс: "+r.duplex+(r.speed>0?"\nИсточник: "+r.method:""):"");
        if(!r.found){state.setText("Ethernet не обнаружен");state.setTextColor(MUTED);hint.setText("Подключи USB‑C адаптер и кабель. Если они уже подключены — открой диагностику.");}
        else if(r.link==LinkDecision.DOWN){state.setText("Линка нет");state.setTextColor(AMBER);hint.setText("Адаптер найден. Проверь кабель и порт.");}
        else if(r.link==LinkDecision.UNKNOWN){state.setText("Статус линка неизвестен");state.setTextColor(AMBER);hint.setText("Интерфейс найден, но состояние соединения прочитать не удалось.");}
        else if(r.speed>0){state.setText("Линк подключён");state.setTextColor(GREEN);speed.setText(Integer.toString(r.speed));speed.setTextColor(r.speed>=1000?GREEN:BLUE);hint.setText(r.usbDirect?"Скорость прочитана из USB-адаптера.":"Скорость прочитана из драйвера.");}
        else {state.setText("Линк подключён");state.setTextColor(GREEN);speed.setText("?");hint.setText(r.conflict?"Данные о скорости расходятся. Подожди следующего обновления.":r.denied?"Android запретил доступ к данным драйвера. Открой диагностику.":"Драйвер не сообщил скорость. Открой диагностику.");}
        if(!r.usbMessage.isEmpty())hint.setText(r.usbMessage);
    }
    private void diagnostics(){
        final String snapshot=report;
        TextView view=text(snapshot,12,false,INK);view.setTypeface(Typeface.MONOSPACE);view.setTextIsSelectable(true);view.setPadding(dp(16),dp(16),dp(16),dp(16));
        ScrollView container=new ScrollView(this);container.addView(view);
        new AlertDialog.Builder(this).setTitle("Диагностика v2 test2").setView(container)
            .setPositiveButton("Копировать",(d,w)->{ClipboardManager cb=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);cb.setPrimaryClip(ClipData.newPlainText("Ethernet Link",snapshot));Toast.makeText(this,"Отчёт скопирован",Toast.LENGTH_SHORT).show();})
            .setNegativeButton("Закрыть",null).show();
    }
    private TextView text(String value,int size,boolean bold,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams lp(int width,int height,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);p.topMargin=dp(top);return p;}
}
