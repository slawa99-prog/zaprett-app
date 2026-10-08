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
    private TextView state, speed, details, estimate, hint, addresses;
    private Button refresh, usbButton;
    private UsbLink usb;
    private InternetPanel internet;
    private DiagnosticsPanel automatic;
    private ScrollView linkView;
    private Button linkTab,internetTab,checkTab;
    private int selectedTab;
    private BroadcastReceiver usbReceiver,permissionReceiver;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private LinkDetector detector;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private boolean resumed;
    private int generation;
    private String report="Отчёт ещё не готов. Нажми «Обновить».";
    private final Runnable tick=new Runnable(){public void run(){usb.observeTopology();refresh();if(resumed)handler.postDelayed(this,1500);}};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        detector=new LinkDetector(this);
        usb=new UsbLink(this);
        if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction()))usb.attached(true);
        cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(0xfff8fafc);
        shell.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(22),dp(16),dp(22),dp(10));
        header.addView(text("Ethernet Link",28,true,INK));
        header.addView(text("Проверка USB‑C → Ethernet",14,false,MUTED),lp(-1,-2,4));
        LinearLayout tabs=new LinearLayout(this);
        linkTab=tab("Линк",0);internetTab=tab("Интернетометр",1);checkTab=tab("Проверка",2);
        tabs.addView(linkTab,new LinearLayout.LayoutParams(0,dp(46),1));
        LinearLayout.LayoutParams secondTab=new LinearLayout.LayoutParams(0,dp(46),1);secondTab.leftMargin=dp(4);
        tabs.addView(internetTab,secondTab);
        LinearLayout.LayoutParams thirdTab=new LinearLayout.LayoutParams(0,dp(46),1);thirdTab.leftMargin=dp(4);tabs.addView(checkTab,thirdTab);header.addView(tabs,lp(-1,-2,14));shell.addView(header);
        FrameLayout body=new FrameLayout(this);shell.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        linkView=new ScrollView(this);linkView.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(22),dp(12),dp(22),dp(18));linkView.addView(root);
        body.addView(linkView,new FrameLayout.LayoutParams(-1,-1));
        internet=new InternetPanel(this);internet.view.setVisibility(View.GONE);body.addView(internet.view,new FrameLayout.LayoutParams(-1,-1));
        automatic=new DiagnosticsPanel(this);automatic.view.setVisibility(View.GONE);body.addView(automatic.view,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);
        card.setPadding(dp(18),dp(24),dp(18),dp(24));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(18));bg.setStroke(dp(1),0xffe2e8f0);card.setBackground(bg);
        root.addView(card,lp(-1,-2,0));
        state=text("Проверяю…",18,true,MUTED);state.setGravity(Gravity.CENTER);card.addView(state,lp(-1,-2,0));
        speed=text("—",64,true,INK);card.addView(speed,lp(-2,-2,12));
        card.addView(text("Мбит/с · скорость линка",14,false,MUTED),lp(-2,-2,0));
        hint=text("",15,false,MUTED);hint.setGravity(Gravity.CENTER);card.addView(hint,lp(-1,-2,18));
        details=text("",14,false,MUTED);details.setGravity(Gravity.CENTER);card.addView(details,lp(-1,-2,16));
        addresses=text("Мой IP (Ethernet): не назначен\nШлюз: не указан",14,false,INK);
        addresses.setGravity(Gravity.CENTER);addresses.setTextIsSelectable(true);card.addView(addresses,lp(-1,-2,16));
        usbButton=new Button(this);usbButton.setText("Разрешить USB");usbButton.setAllCaps(false);usbButton.setVisibility(View.GONE);
        usbButton.setOnClickListener(v->{Toast.makeText(this,usb.requestPermission(true),Toast.LENGTH_LONG).show();invalidateAndRefresh();});
        root.addView(usbButton,lp(-1,dp(54),18));
        refresh=new Button(this);refresh.setText("Обновить");refresh.setAllCaps(false);refresh.setTextSize(17);refresh.setOnClickListener(v->refresh());root.addView(refresh,lp(-1,dp(54),18));
        Button diagnostics=new Button(this);diagnostics.setText("Технический отчёт");diagnostics.setAllCaps(false);diagnostics.setOnClickListener(v->diagnostics());root.addView(diagnostics,lp(-1,dp(52),6));
        estimate=text("",13,false,MUTED);root.addView(estimate,lp(-1,-2,18));
        root.addView(text("Линк обновляется автоматически. Для чтения скорости линка интернет и IP-адрес не обязательны.",13,false,MUTED),lp(-1,-2,14));
        TextView version=text("Версия "+BuildConfig.VERSION_NAME,11,false,MUTED);version.setGravity(Gravity.CENTER);version.setPadding(0,dp(6),0,0);shell.addView(version,lp(-1,-2,0));
        TextView author=text("developed by SVYATOSLAV",10,false,MUTED);author.setGravity(Gravity.CENTER);author.setPadding(0,dp(2),0,dp(8));shell.addView(author,lp(-1,-2,0));
        setContentView(shell);
        selectedTab=saved==null?0:saved.getInt("tab",0);selectTab(selectedTab);
        // Protected platform USB events must accept privileged OEM senders too.
        // Keep the app-private permission callback in a separate unexported receiver.
        usbReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent i){
                if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())){
                    usb.detached(i.getParcelableExtra(UsbManager.EXTRA_DEVICE));automatic.usbDetached(i.getParcelableExtra(UsbManager.EXTRA_DEVICE));
                }else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction()))usb.attached(false);
                else return;
                usb.observeTopology();invalidateAndRefresh();internet.networksChanged();automatic.networksChanged();
            }
        };
        IntentFilter systemUsb=new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);systemUsb.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        try{
            if(Build.VERSION.SDK_INT>=33)registerReceiver(usbReceiver,systemUsb,Context.RECEIVER_EXPORTED);
            else registerReceiver(usbReceiver,systemUsb);
            usb.receiverStatus(true,true,"");
        }catch(RuntimeException e){usb.receiverStatus(true,false,e.getClass().getSimpleName());usbReceiver=null;}
        permissionReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent i){
                if(!usb.permissionAction().equals(i.getAction()))return;
                usb.permissionResult();invalidateAndRefresh();
            }
        };
        try{
            IntentFilter privatePermission=new IntentFilter(usb.permissionAction());
            if(Build.VERSION.SDK_INT>=33)registerReceiver(permissionReceiver,privatePermission,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(permissionReceiver,privatePermission);
            usb.receiverStatus(false,true,"");
        }catch(RuntimeException e){usb.receiverStatus(false,false,e.getClass().getSimpleName());permissionReceiver=null;}

    }
    @Override protected void onResume(){
        super.onResume();resumed=true;generation++;handler.post(tick);
        internet.setActive(selectedTab==1);automatic.setVisible(selectedTab==2);
        callback=new ConnectivityManager.NetworkCallback(){
            private void changed(boolean link){handler.post(()->{if(resumed){updateAddresses();if(link)invalidateAndRefresh();internet.networksChanged();automatic.networksChanged();}});}
            @Override public void onAvailable(Network n){changed(false);}
            @Override public void onLost(Network n){changed(true);}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){changed(c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));}
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties p){
                NetworkCapabilities c=cm.getNetworkCapabilities(n);changed(c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            }
        };
        try {cm.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().build(),callback);}
        catch(RuntimeException e){callback=null;}
    }
    @Override protected void onPause(){
        resumed=false;generation++;handler.removeCallbacks(tick);internet.setActive(false);automatic.setVisible(false);
        if(callback!=null){try{cm.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}callback=null;}
        super.onPause();
    }
    @Override protected void onDestroy(){
        if(usbReceiver!=null){try{unregisterReceiver(usbReceiver);}catch(RuntimeException ignored){}usbReceiver=null;}
        if(permissionReceiver!=null){try{unregisterReceiver(permissionReceiver);}catch(RuntimeException ignored){}permissionReceiver=null;}
        automatic.close();handler.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()))usb.attached(true);
        invalidateAndRefresh();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("tab",selectedTab);}
    @Override public void onBackPressed(){
        if(selectedTab==1){if(!internet.back())selectTab(0);}else if(selectedTab==2)selectTab(0);else super.onBackPressed();
    }
    private Button tab(String title,int index){
        Button button=new Button(this);button.setText(title);button.setAllCaps(false);button.setTextSize(12);button.setPadding(dp(3),0,dp(3),0);
        button.setOnClickListener(v->selectTab(index));return button;
    }
    private void selectTab(int index){
        selectedTab=index>=0&&index<=2?index:0;
        linkView.setVisibility(selectedTab==0?View.VISIBLE:View.GONE);
        internet.view.setVisibility(selectedTab==1?View.VISIBLE:View.GONE);
        automatic.view.setVisibility(selectedTab==2?View.VISIBLE:View.GONE);
        styleTab(linkTab,selectedTab==0);styleTab(internetTab,selectedTab==1);styleTab(checkTab,selectedTab==2);
        internet.setActive(resumed&&selectedTab==1);automatic.setVisible(resumed&&selectedTab==2);
    }
    private void styleTab(Button button,boolean selected){
        GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(dp(12));bg.setColor(selected?BLUE:0xffe2e8f0);
        button.setBackgroundTintList(null);button.setBackground(bg);button.setTextColor(selected?Color.WHITE:INK);
        button.setSelected(selected);
    }
    private void invalidateAndRefresh(){
        generation++;
        if(resumed){speed.setText("—");state.setText("Проверяю…");hint.setText("");details.setText("");refresh();}
    }
    private void refresh(){
        if(resumed)updateAddresses();
        if(!resumed||automatic.isRunning()||!busy.compareAndSet(false,true))return;
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
    private void updateAddresses(){addresses.setText(EthernetAddresses.read(cm));}
    private void show(LinkDetector.Result r){
        report=r.report;speed.setTextColor(INK);speed.setText("—");
        usbButton.setVisibility(r.usbAvailable&&r.usbPermissionNeeded?View.VISIBLE:View.GONE);
        estimate.setText(r.usbDirect?"Прямая проверка USB-адаптера. Интернет и DHCP не нужны.":"Оценка Android: "+r.estimate+"\nНе подтверждает скорость физического линка.");
        details.setText(r.found?(r.usbDirect?"Адаптер: "+r.adapter:"Интерфейс: "+r.iface)+"\nДуплекс: "+r.duplex+(r.speed>0?"\nИсточник: "+r.method:""):"");
        if(!r.found){state.setText("Ethernet не обнаружен");state.setTextColor(MUTED);hint.setText("Подключи USB‑C адаптер и кабель. Если они уже подключены — открой диагностику.");}
        else if(r.link==LinkDecision.DOWN){state.setText("Линка нет");state.setTextColor(AMBER);hint.setText("Адаптер найден. Проверь кабель и порт.");}
        else if(r.link==LinkDecision.UNKNOWN){state.setText("Статус линка неизвестен");state.setTextColor(AMBER);hint.setText("Интерфейс найден, но состояние соединения прочитать не удалось.");}
        else if(r.speed>0){state.setText("Линк подключён");state.setTextColor(GREEN);speed.setText(Integer.toString(r.speed));speed.setTextColor(r.speed>=1000?GREEN:BLUE);hint.setText(r.usbDirect?"Скорость прочитана из USB-адаптера.":"Скорость прочитана из драйвера.");}
        else {state.setText("Линк подключён");state.setTextColor(GREEN);speed.setText("?");hint.setText(r.conflict?"Данные о скорости расходятся. Подожди следующего обновления.":r.denied?"Android запретил доступ к данным драйвера. Открой диагностику.":"Драйвер не сообщил скорость. Открой диагностику.");}
        if(!r.usbMessage.isEmpty())hint.setText(r.usbMessage);
    }
    private void diagnostics(){
        final String snapshot=report+"\n[Адреса Ethernet]\n"+EthernetAddresses.read(cm)+"\n"+usb.permissionReport()+internet.report()+automatic.report();
        TextView view=text(snapshot,12,false,INK);view.setTypeface(Typeface.MONOSPACE);view.setTextIsSelectable(true);view.setPadding(dp(16),dp(16),dp(16),dp(16));
        ScrollView container=new ScrollView(this);container.addView(view);
        new AlertDialog.Builder(this).setTitle("Диагностика").setView(container)
            .setPositiveButton("Копировать",(d,w)->{ClipboardManager cb=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);cb.setPrimaryClip(ClipData.newPlainText("Ethernet Link",snapshot));Toast.makeText(this,"Отчёт скопирован",Toast.LENGTH_SHORT).show();})
            .setNegativeButton("Закрыть",null).show();
    }
    private TextView text(String value,int size,boolean bold,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams lp(int width,int height,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);p.topMargin=dp(top);return p;}
}
