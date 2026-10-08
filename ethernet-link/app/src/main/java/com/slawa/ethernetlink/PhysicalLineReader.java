package com.slawa.ethernetlink;

import android.content.Context;
import android.hardware.usb.*;
import android.net.*;
import java.io.*;
import java.net.NetworkInterface;
import java.util.*;

/** Captures one physical source for an entire test. Never infers carrier from IP reachability. */
final class PhysicalLineReader implements AutoCloseable {
    static final class Sample {
        int link=-1,speed=-1;long[] counters=Rtl8153Counters.unavailable();String details="";
    }
    private final UsbManager manager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbAdapterCatalog.Kind family;
    private String iface;
    private int index;
    final String description;
    final boolean countersSupported;
    PhysicalLineReader(Context context)throws IOException{
        manager=(UsbManager)context.getSystemService(Context.USB_SERVICE);
        List<UsbDevice> candidates=new ArrayList<>();
        if(manager!=null)for(UsbDevice d:manager.getDeviceList().values())if(UsbLink.kind(d)!=null)candidates.add(d);
        if(candidates.size()>1)throw new IOException("Оставь один Ethernet-адаптер для теста.");
        if(candidates.size()==1){
            device=candidates.get(0);family=UsbLink.kind(device);
            if(!manager.hasPermission(device))throw new IOException("На вкладке «Линк» нажми «Разрешить USB», затем повтори тест.");
            connection=manager.openDevice(device);if(connection==null)throw new IOException("Не удалось открыть USB-адаптер.");
            description=String.format(Locale.US,"%s · USB %04x:%04x",family.label,device.getVendorId(),device.getProductId());
            // Only the positively identified RTL8153 is enabled for experimental tally reads.
            countersSupported=family==UsbAdapterCatalog.Kind.REALTEK_GBE;
        }else{
            Set<String> names=new TreeSet<>();
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities c=cm.getNetworkCapabilities(n);LinkProperties p=cm.getLinkProperties(n);
                if(c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&p!=null&&safe(p.getInterfaceName()))names.add(p.getInterfaceName());
            }
            Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
            if(all!=null)while(all.hasMoreElements()){String n=all.nextElement().getName();if(safe(n)&&(n.matches("eth[0-9]+")||n.startsWith("en")||n.matches("usb[0-9]+")))names.add(n);}
            if(names.size()!=1)throw new IOException(names.isEmpty()?"Физический Ethernet-интерфейс не найден.":"Найдено несколько Ethernet-интерфейсов. Оставь один для теста.");
            iface=names.iterator().next();NetworkInterface n=NetworkInterface.getByName(iface);
            if(n==null)throw new IOException("Ethernet-интерфейс исчез.");index=n.getIndex();
            description="Драйвер · "+iface;countersSupported=false;
        }
    }
    private void checkIdentity()throws IOException{
        if(device!=null){
            UsbDevice d=manager.getDeviceList().get(device.getDeviceName());
            if(d==null||d.getDeviceId()!=device.getDeviceId()||d.getVendorId()!=device.getVendorId()||d.getProductId()!=device.getProductId())
                throw new IOException("USB-адаптер отключён или заменён. Тест остановлен; это не засчитано как обрыв кабеля.");
            if(!manager.hasPermission(d))throw new IOException("USB-доступ потерян. Тест остановлен.");
        }else{
            NetworkInterface n=NetworkInterface.getByName(iface);
            if(n==null||n.getIndex()!=index)throw new IOException("Ethernet-интерфейс отключён или заменён. Тест остановлен.");
        }
    }
    Sample read()throws IOException{
        checkIdentity();Sample result=new Sample();StringBuilder log=new StringBuilder();
        if(device!=null){
            try{
                Rtl8153Status.Sample link=family==UsbAdapterCatalog.Kind.ASIX?AsixStatus.read(connection::controlTransfer,log):Rtl8153Status.read(connection::controlTransfer,log,family.maxMbps);
                result.link=link.link;result.speed=link.speed;
            }catch(RuntimeException e){log.append("link.read_error=").append(e.getClass().getSimpleName()).append('\n');}
            if(countersSupported)try{result.counters=Rtl8153Counters.read(connection::controlTransfer,log);}
            catch(RuntimeException e){log.append("tally.read_error=").append(e.getClass().getSimpleName()).append('\n');}
        }else{
            int before=readInt("carrier"),speed=readInt("speed");Map<String,String> nativeValues=new HashMap<>();
            if(NativeLink.isLoaded()){
                String raw=NativeLink.getLinkInfo(iface);if(raw!=null){log.append(raw);for(String line:raw.split("\n")){int split=line.indexOf('=');if(split>0)nativeValues.put(line.substring(0,split),line.substring(split+1));}}
            }
            LinkDecision d=LinkDecision.resolve(new int[]{before,readInt("carrier"),number(nativeValues.get("glink.before.link")),number(nativeValues.get("glink.after.link"))},false,
                new int[]{speed,number(nativeValues.get("glinksettings.speed")),number(nativeValues.get("gset.speed"))});
            result.link=d.link;result.speed=d.speed;
        }
        checkIdentity();result.details=log.toString();return result;
    }
    boolean matches(UsbDevice d){return device!=null&&d!=null&&device.getDeviceId()==d.getDeviceId()&&device.getDeviceName().equals(d.getDeviceName());}
    private int readInt(String name){try(BufferedReader r=new BufferedReader(new FileReader("/sys/class/net/"+iface+"/"+name))){return number(r.readLine());}catch(Exception e){return -1;}}
    private static int number(String s){try{return Integer.parseInt(s.trim());}catch(Exception e){return -1;}}
    private static boolean safe(String s){return s!=null&&s.matches("[A-Za-z0-9_.-]{1,15}");}
    @Override public void close(){if(connection!=null){connection.close();connection=null;}}
}
