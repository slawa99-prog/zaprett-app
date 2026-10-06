package com.slawa.ethernetlink;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import java.util.ArrayList;
import java.util.List;

final class Rtl8153Usb {
    private final Context context;
    private final UsbManager manager;
    Rtl8153Usb(Context context){
        this.context=context.getApplicationContext();
        manager=(UsbManager)context.getSystemService(Context.USB_SERVICE);
    }
    static final class Reading {
        boolean available, permissionNeeded;
        String message="";
        Rtl8153Status.Sample sample;
    }
    String permissionAction(){return context.getPackageName()+".USB_PERMISSION";}
    private static boolean supported(UsbDevice d){return d.getVendorId()==0x0bda && d.getProductId()==0x8153;}
    private List<UsbDevice> devices(){
        List<UsbDevice> list=new ArrayList<>();
        if(manager!=null)for(UsbDevice d:manager.getDeviceList().values())if(supported(d))list.add(d);
        return list;
    }
    /** Only called following an explicit button tap. Never opens/claims the device here. */
    String requestPermission(){
        try{
            List<UsbDevice> list=devices();
            if(list.size()!=1)return list.isEmpty()?"Подключи адаптер RTL8153":"Оставь подключённым один адаптер RTL8153";
            UsbDevice d=list.get(0);
            if(manager.hasPermission(d))return "USB-доступ уже разрешён";
            Intent intent=new Intent(permissionAction()).setPackage(context.getPackageName());
            PendingIntent pending=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            manager.requestPermission(d,pending);
            return "Подтверди доступ к адаптеру в окне Android";
        }catch(RuntimeException e){return "Не удалось запросить USB-доступ: "+e.getClass().getSimpleName();}
    }
    Reading read(StringBuilder report){
        Reading r=new Reading();
        report.append("\n[RTL8153 direct USB]\n");
        try{
            List<UsbDevice> list=devices();
            report.append("usb.supported_count=").append(list.size()).append('\n');
            if(list.size()!=1){
                if(list.size()>1)r.message="Для USB-проверки оставь подключённым один RTL8153.";
                return r;
            }
            r.available=true;
            UsbDevice device=list.get(0);
            boolean permission=manager.hasPermission(device);
            report.append("usb.permission=").append(permission).append('\n');
            r.permissionNeeded=!permission;
            if(!permission){r.message="Нажми «Разрешить USB» и подтверди доступ к адаптеру.";return r;}
            UsbDeviceConnection connection=manager.openDevice(device);
            if(connection==null){report.append("usb.open=null\n");r.message="Android не открыл USB-адаптер. Открой диагностику.";return r;}
            report.append("usb.open=ok\nusb.interface_claimed=false\n");
            try{
                // Device-recipient EP0 vendor IN reads do not need an interface claim.
                // Do not add claimInterface(force), setConfiguration, reset or OUT writes.
                r.sample=Rtl8153Status.read(connection::controlTransfer,report);
                UsbDevice current=manager.getDeviceList().get(device.getDeviceName());
                if(current==null || !supported(current) || current.getDeviceId()!=device.getDeviceId()){
                    report.append("usb.detached_during_read=true\n");
                    r.sample=null;r.available=false;r.message="Адаптер отключён во время проверки.";
                }else if(r.sample.link<0 || r.sample.speed<0 && r.sample.link==1){
                    r.message="Не удалось прочитать устойчивую скорость через USB. Открой диагностику.";
                }
            }finally{connection.close();}
        }catch(RuntimeException e){
            report.append("usb.exception=").append(e).append('\n');
            r.sample=null;r.message="Ошибка USB-доступа. Открой диагностику.";
        }
        return r;
    }
}
