package com.slawa.ethernetlink;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.util.*;

final class UsbLink {
    private final Context context;
    private final UsbManager manager;
    private final UsbPermissionGate gate=new UsbPermissionGate();
    private int attachBroadcasts,attachLaunches,manualRequests,permissionReplies;
    UsbLink(Context context){
        this.context=context.getApplicationContext();
        manager=(UsbManager)context.getSystemService(Context.USB_SERVICE);
    }
    static final class Reading {
        boolean available,permissionNeeded;
        String message="",adapter="";
        Rtl8153Status.Sample sample;
    }
    String permissionAction(){return context.getPackageName()+".USB_PERMISSION";}
    private static String key(UsbDevice d){return d.getDeviceName()+":"+d.getDeviceId();}
    void detached(UsbDevice d){if(d!=null)gate.detached(key(d));}
    void attached(boolean launch){if(launch)attachLaunches++;else attachBroadcasts++;}
    void permissionResult(){permissionReplies++;gate.completed();}
    String permissionReport(){return "\n[USB permission flow]\nmode=system_default_or_manual_button\nattach_broadcasts="+attachBroadcasts+
        "\nattach_launches="+attachLaunches+"\nmanual_requests="+manualRequests+"\npermission_replies="+permissionReplies+"\n";}
    static UsbAdapterCatalog.Kind kind(UsbDevice d){
        UsbAdapterCatalog.Kind k=UsbAdapterCatalog.find(d.getVendorId(),d.getProductId());
        if(k==UsbAdapterCatalog.Kind.ASIX){
            // Linux binds these IDs only in their vendor Ethernet interface mode.
            for(int i=0;i<d.getInterfaceCount();i++){
                UsbInterface f=d.getInterface(i);
                if(f.getInterfaceClass()==255&&f.getInterfaceSubclass()==255&&f.getInterfaceProtocol()==0)return k;
            }
            return null;
        }
        return k;
    }
    private List<UsbDevice> devices(){
        List<UsbDevice> list=new ArrayList<>();
        if(manager!=null)for(UsbDevice d:manager.getDeviceList().values())if(kind(d)!=null)list.add(d);
        return list;
    }
    /** Android owns permission and the saved default. Never grants permission itself. */
    String requestPermission(boolean manual){
        try{
            List<UsbDevice> list=devices();
            Set<String> attached=new HashSet<>();for(UsbDevice d:list)attached.add(key(d));
            gate.retain(attached);
            if(list.size()!=1)return manual?(list.isEmpty()?"Подключи поддерживаемый адаптер":"Оставь подключённым один Ethernet-адаптер"):"";
            UsbDevice d=list.get(0);
            if(!gate.shouldRequest(key(d),manager.hasPermission(d),manual))return manual?"USB-доступ уже разрешён или запрос показан":"";
            Intent intent=new Intent(permissionAction()).setPackage(context.getPackageName());
            PendingIntent pending=PendingIntent.getBroadcast(context,0,intent,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            manager.requestPermission(d,pending);
            manualRequests++;
            return "Подтверди доступ. Если есть «Всегда использовать», отметь этот пункт.";
        }catch(RuntimeException e){gate.completed();return manual?"Не удалось запросить USB-доступ: "+e.getClass().getSimpleName():"";}
    }
    Reading read(StringBuilder report){
        Reading r=new Reading();report.append("\n[Direct USB]\n");
        try{
            List<UsbDevice> list=devices();
            report.append("usb.supported_count=").append(list.size()).append('\n');
            if(list.size()!=1){
                if(list.size()>1)r.message="Для USB-проверки оставь подключённым один Ethernet-адаптер.";
                else if(manager!=null&&!manager.getDeviceList().isEmpty())
                    r.message="Для этого USB-устройства нет прямого метода чтения. Если скорость не определилась, пришли диагностику.";
                return r;
            }
            r.available=true;
            UsbDevice device=list.get(0);UsbAdapterCatalog.Kind family=kind(device);r.adapter=family.label;
            report.append("usb.family=").append(family).append('\n');
            report.append(String.format(Locale.US,"usb.id=%04x:%04x\n",device.getVendorId(),device.getProductId()));
            boolean permission=manager.hasPermission(device);
            report.append("usb.permission=").append(permission).append('\n');
            r.permissionNeeded=!permission;
            if(!permission){r.message="Разреши USB-доступ. В окне Android выбери «Всегда использовать», если этот пункт доступен.";return r;}
            UsbDeviceConnection connection=manager.openDevice(device);
            if(connection==null){report.append("usb.open=null\n");r.message="Android не открыл USB-адаптер. Открой диагностику.";return r;}
            report.append("usb.open=ok\nusb.interface_claimed=false\n");
            try{
                r.sample=family==UsbAdapterCatalog.Kind.ASIX?AsixStatus.read(connection::controlTransfer,report)
                    :Rtl8153Status.read(connection::controlTransfer,report,family.maxMbps);
                UsbDevice current=manager.getDeviceList().get(device.getDeviceName());
                if(current==null||kind(current)!=family||current.getDeviceId()!=device.getDeviceId()){
                    report.append("usb.detached_during_read=true\n");
                    r.sample=null;r.available=false;r.message="Адаптер отключён во время проверки.";
                }else if(r.sample.link<0||r.sample.speed<0&&r.sample.link==1)
                    r.message="Не удалось прочитать устойчивую скорость через USB. Открой диагностику.";
            }finally{connection.close();}
        }catch(RuntimeException e){
            report.append("usb.exception=").append(e).append('\n');
            r.sample=null;r.message="Ошибка USB-доступа. Открой диагностику.";
        }
        return r;
    }
}
