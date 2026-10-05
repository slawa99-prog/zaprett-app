package com.slawa.ethernetlink;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.NetworkInterface;
import java.util.*;

public class MainActivity extends Activity {
    private TextView state,speed,details;
    private ConnectivityManager cm;
    private final Handler h=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){ public void run(){ refresh(); h.postDelayed(this,1500); }};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24),dp(48),dp(24),dp(24)); root.setBackgroundColor(Color.rgb(248,250,252));

        TextView title=t("Ethernet Link",30,true,Color.rgb(15,23,42)); root.addView(title,lp(-1,-2,0));
        TextView sub=t("Скорость физического Ethernet-соединения",15,false,Color.GRAY); root.addView(sub,lp(-1,-2,8));

        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding(dp(20),dp(28),dp(20),dp(28)); card.setBackgroundColor(Color.WHITE); card.setElevation(dp(4));
        root.addView(card,lp(-1,-2,28));

        state=t("Проверяю…",18,true,Color.GRAY); card.addView(state);
        speed=t("—",64,true,Color.rgb(15,23,42)); card.addView(speed,lp(-2,-2,18));
        TextView unit=t("Мбит/с",18,false,Color.GRAY); card.addView(unit);
        details=t("",14,false,Color.GRAY); details.setGravity(Gravity.CENTER); card.addView(details,lp(-1,-2,24));

        Button btn=new Button(this); btn.setText("Обновить"); btn.setTextSize(17); btn.setOnClickListener(v->refresh());
        root.addView(btn,lp(-1,dp(58),24));
        TextView note=t("Это LINK 10/100/1000, а не Speedtest.",13,false,Color.GRAY); note.setGravity(Gravity.CENTER);
        root.addView(note,lp(-1,-2,18));
        setContentView(root);
    }

    private TextView t(String s,int size,boolean bold,int color){
        TextView v=new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v;
    }
    private LinearLayout.LayoutParams lp(int w,int hgt,int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,hgt); p.topMargin=dp(top); return p;
    }
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    @Override protected void onResume(){super.onResume();h.removeCallbacks(tick);h.post(tick);}
    @Override protected void onPause(){h.removeCallbacks(tick);super.onPause();}

    private void refresh(){ new Thread(()->{ Result r=detect(); h.post(()->show(r)); }).start(); }

    private Result detect(){
        String iface=findIface();
        if(iface==null)return new Result(false,false,null,-1,null,null);
        int carrier=readInt("/sys/class/net/"+iface+"/carrier",-1);
        int sp=readInt("/sys/class/net/"+iface+"/speed",-1);
        String duplex=read("/sys/class/net/"+iface+"/duplex");
        String method=sp>0?"sysfs":null;
        boolean link=carrier==1;

        if(NativeLink.isLoaded()){
            try{
                String raw=NativeLink.getLinkInfo(iface);
                if(raw!=null){
                    String[] p=raw.split("\\|",-1);
                    int ns=p.length>0?pi(p[0],-1):-1, nd=p.length>1?pi(p[1],-1):-1, nl=p.length>2?pi(p[2],-1):-1;
                    if(sp<=0&&ns>0){sp=ns;method="ethtool/ioctl";}
                    if((duplex==null||duplex.isEmpty())&&nd>=0)duplex=nd==1?"full":"half";
                    if(carrier<0&&nl>=0)link=nl==1;
                }
            }catch(Throwable ignored){}
        }
        if(carrier<0)link=isActive(iface)||sp>0;
        return new Result(true,link,iface,sp,duplex,method);
    }

    private String findIface(){
        try{
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities c=cm.getNetworkCapabilities(n);
                LinkProperties l=cm.getLinkProperties(n);
                if(c!=null&&l!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)&&l.getInterfaceName()!=null)
                    return l.getInterfaceName();
            }
        }catch(Throwable ignored){}
        try{
            Enumeration<NetworkInterface> e=NetworkInterface.getNetworkInterfaces();
            if(e!=null)for(NetworkInterface ni:Collections.list(e)){
                String n=ni.getName(); if(n!=null&&(n.startsWith("eth")||n.startsWith("enx")||n.startsWith("usb")))return n;
            }
        }catch(Throwable ignored){}
        return null;
    }

    private boolean isActive(String iface){
        try{
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities c=cm.getNetworkCapabilities(n); LinkProperties l=cm.getLinkProperties(n);
                if(c!=null&&l!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)&&iface.equals(l.getInterfaceName()))return true;
            }
        }catch(Throwable ignored){} return false;
    }

    private void show(Result r){
        if(!r.found){
            state.setText("Ethernet не подключён"); state.setTextColor(Color.rgb(220,38,38)); speed.setText("—");
            details.setText("Подключи USB-C → Ethernet и кабель"); return;
        }
        if(!r.link){
            state.setText("Адаптер найден, линка нет"); state.setTextColor(Color.rgb(220,38,38)); speed.setText("—");
            details.setText("Интерфейс: "+r.iface+"\nПроверь кабель и порт"); return;
        }
        state.setText("Ethernet подключён"); state.setTextColor(Color.rgb(22,163,74));
        if(r.speed>0){speed.setText(String.valueOf(r.speed)); if(r.speed>=1000)speed.setTextColor(Color.rgb(22,163,74));}
        else speed.setText("?");
        String d=r.duplex==null?"—":("full".equalsIgnoreCase(r.duplex)?"Full Duplex":("half".equalsIgnoreCase(r.duplex)?"Half Duplex":r.duplex));
        details.setText("Интерфейс: "+r.iface+"\nДуплекс: "+d+"\nМетод: "+(r.method==null?"Android не дал прочитать скорость":r.method));
    }

    private static String read(String p){try(BufferedReader b=new BufferedReader(new FileReader(p))){String s=b.readLine();return s==null?null:s.trim();}catch(Throwable e){return null;}}
    private static int readInt(String p,int d){return pi(read(p),d);}
    private static int pi(String s,int d){try{return Integer.parseInt(s.trim());}catch(Throwable e){return d;}}

    static final class Result{
        final boolean found,link; final String iface,duplex,method; final int speed;
        Result(boolean f,boolean l,String i,int s,String d,String m){found=f;link=l;iface=i;speed=s;duplex=d;method=m;}
    }
}
