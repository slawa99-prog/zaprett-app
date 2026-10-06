package com.slawa.ethernetlink;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.*;

final class LinkDetector {
    private final Context context;
    private final ConnectivityManager cm;
    private final UsbLink usb;
    LinkDetector(Context context) {
        this.context = context.getApplicationContext();
        cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        usb = new UsbLink(context);
    }
    static final class Result {
        String iface, method = "—", duplex = "—", estimate = "Нет данных Android", report = "";
        int speed = -1, link = LinkDecision.UNKNOWN;
        boolean found, conflict, denied, usbAvailable, usbPermissionNeeded, usbDirect;
        String usbMessage = "",adapter="";
    }
    private static final class Net {
        final Network network;
        final NetworkCapabilities caps;
        Net(Network n, NetworkCapabilities c) { network = n; caps = c; }
    }
    Result detect() {
        StringBuilder log = new StringBuilder("Ethernet Link "+BuildConfig.VERSION_NAME+"\n");
        log.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())).append('\n');
        log.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
           .append("; Android ").append(Build.VERSION.RELEASE).append("; API ").append(Build.VERSION.SDK_INT)
           .append("\nINTERNET granted: ").append(context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED)
           .append("\nNative loaded: ").append(NativeLink.isLoaded()).append('\n');
        if (!NativeLink.isLoaded()) log.append("Native error: ").append(NativeLink.loadError()).append('\n');
        usbInfo(log);
        Map<String, Net> networks = networks(log);
        TreeSet<String> names = new TreeSet<>(networks.keySet());
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            if (e != null) while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (ethernetName(n)) names.add(n);
            }
        } catch (Exception e) { log.append("Interface enumeration: ").append(e).append('\n'); }
        String[] sysNames = new File("/sys/class/net").list();
        if (sysNames != null) for (String n : sysNames) { if (ethernetName(n)) names.add(n); }
        else log.append("sysfs interface list unavailable\n");
        // Probes work even when Java hides unaddressed interfaces (no DHCP yet).
        Set<String> knownNames = new HashSet<>(names);
        names.add("eth0"); names.add("eth1");
        Result best = new Result();
        int count = 0;
        for (String name : names) {
            if (++count > 8) { log.append("Further interfaces omitted\n"); break; }
            Result r = probe(name, networks.get(name), knownNames.contains(name), log);
            if (rank(r) > rank(best)) best = r;
        }
        UsbLink.Reading u = usb.read(log);
        if (u.sample != null) {
            // A USB result belongs to this USB device, never an assumed eth0 mapping.
            Result direct = new Result();
            direct.found = true; direct.usbDirect = true;
            direct.iface = u.adapter+" USB"; direct.method = u.adapter+" / USB";
            direct.link = u.sample.link; direct.speed = u.sample.speed;
            direct.duplex = u.sample.duplex == 1 ? "Full Duplex" : u.sample.duplex == 0 ? "Half Duplex" : "—";
            direct.conflict = !u.sample.error.isEmpty();
            direct.estimate = "Не используется для USB-проверки";
            best = direct;
        }
        best.usbAvailable = u.available;
        best.usbPermissionNeeded = u.permissionNeeded;
        best.usbMessage = u.message;
        best.adapter=u.adapter;
        if(u.available&&!best.found){best.found=true;best.iface=u.adapter;}
        log.append("\nSelected: ").append(best.iface).append("; link=").append(best.link)
           .append("; exact Mbps=").append(best.speed).append("; conflict=").append(best.conflict).append('\n');
        log.append("Android bandwidth is an estimate, never proof of 100/1000.\n");
        best.report = log.toString();
        return best;
    }
    private static int rank(Result r) {
        if (!r.found) return 0;
        if (r.link == LinkDecision.UP) return r.speed > 0 ? 4 : 3;
        return r.link == LinkDecision.UNKNOWN ? 2 : 1;
    }
    private Result probe(String name, Net net, boolean enumerated, StringBuilder log) {
        Result r = new Result(); r.iface = name;
        log.append("\n[").append(name).append("]\n");
        String base = "/sys/class/net/" + name + "/";
        int before = integer(read(base + "carrier", log));
        int speed = LinkDecision.speed(read(base + "speed", log));
        String duplex = read(base + "duplex", log);
        String oper = read(base + "operstate", log);
        Map<String,String> nativeValues = new HashMap<>();
        if (NativeLink.isLoaded()) try {
            String raw = NativeLink.getLinkInfo(name);
            if (raw != null) {
                log.append(raw);
                for (String line : raw.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) nativeValues.put(line.substring(0,eq),line.substring(eq+1));
                }
            }
        } catch (LinkageError | RuntimeException e) { log.append("Native call: ").append(e).append('\n'); }
        int after = integer(read(base + "carrier", log));
        int modern = LinkDecision.speed(nativeValues.get("glinksettings.speed"));
        int legacy = LinkDecision.speed(nativeValues.get("gset.speed"));
        int linkBefore = integer(nativeValues.get("glink.before.link"));
        int linkAfter = integer(nativeValues.get("glink.after.link"));
        if (integer(nativeValues.get("glink.after.errno")) == 19) linkAfter = 0;
        boolean registered = false;
        if (net != null) try {
            NetworkCapabilities current = cm.getNetworkCapabilities(net.network);
            LinkProperties lp = cm.getLinkProperties(net.network);
            registered = current != null && current.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                && !current.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && lp != null && name.equals(lp.getInterfaceName());
            if (registered) {
                r.estimate = "↓ " + mbps(current.getLinkDownstreamBandwidthKbps()) + " / ↑ " + mbps(current.getLinkUpstreamBandwidthKbps());
                log.append("Android bandwidth estimate Kbps: down=").append(current.getLinkDownstreamBandwidthKbps())
                   .append(" up=").append(current.getLinkUpstreamBandwidthKbps()).append('\n');
            }
        } catch (RuntimeException e) { log.append("Network recheck: ").append(e).append('\n'); }
        r.found = enumerated || registered || "1".equals(nativeValues.get("interface.exists")) || before >= 0 || after >= 0 || oper != null;
        LinkDecision decision = LinkDecision.resolve(new int[]{before,after,linkBefore,linkAfter}, registered,
                new int[]{speed,modern,legacy});
        r.link = decision.link; r.speed = decision.speed; r.conflict = decision.conflict;
        if (r.speed > 0) {
            List<String> methods = new ArrayList<>();
            if (speed > 0) methods.add("sysfs");
            if (modern > 0) methods.add("GLINKSETTINGS");
            if (legacy > 0) methods.add("GSET");
            r.method = String.join(" + ", methods);
            List<Integer> duplexes = new ArrayList<>();
            if (speed > 0 && ("full".equals(duplex) || "half".equals(duplex))) duplexes.add("full".equals(duplex) ? 1 : 0);
            if (modern > 0) duplexes.add(integer(nativeValues.get("glinksettings.duplex")));
            if (legacy > 0) duplexes.add(integer(nativeValues.get("gset.duplex")));
            int d = -1; boolean dConflict = false;
            for (int v : duplexes) if (v == 0 || v == 1) { if (d >= 0 && d != v) dConflict = true; d = v; }
            if (!dConflict && d >= 0) r.duplex = d == 1 ? "Full Duplex" : "Half Duplex";
        }
        for (Map.Entry<String,String> e : nativeValues.entrySet()) {
            if (e.getKey().endsWith(".errno") && ("1".equals(e.getValue()) || "13".equals(e.getValue()))) r.denied = true;
        }
        return r;
    }
    private Map<String,Net> networks(StringBuilder log) {
        Map<String,Net> result = new HashMap<>();
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                LinkProperties p = cm.getLinkProperties(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && p != null && safeName(p.getInterfaceName())) result.put(p.getInterfaceName(), new Net(n,c));
            }
        } catch (RuntimeException e) { log.append("ConnectivityManager: ").append(e).append('\n'); }
        return result;
    }
    private void usbInfo(StringBuilder log) {
        try {
            UsbManager manager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
            for (UsbDevice device : manager.getDeviceList().values()) {
                log.append(String.format(Locale.US, "USB VID:PID=%04x:%04x", device.getVendorId(), device.getProductId()));
                try { log.append(" product=").append(device.getProductName()); } catch (SecurityException ignored) { }
                log.append('\n'); // No USB serial numbers, claims or permission requests.
            }
        } catch (RuntimeException e) { log.append("USB enumeration: ").append(e.getClass().getSimpleName()).append('\n'); }
    }
    private static boolean safeName(String n) { return n != null && n.matches("[A-Za-z0-9_.-]{1,15}"); }
    private static boolean ethernetName(String n) { return safeName(n) && (n.matches("eth[0-9]+") || n.startsWith("en") || n.matches("usb[0-9]+")); }
    private static int integer(String s) { try { return Integer.parseInt(s); } catch (RuntimeException e) { return -1; } }
    private static String mbps(int kbps) { return kbps > 0 ? String.format(Locale.US,"%.1f Мбит/с",kbps/1000.0) : "—"; }
    private static String read(String path, StringBuilder log) {
        try (BufferedReader input = new BufferedReader(new FileReader(path))) {
            String value = input.readLine();
            log.append(path).append('=').append(value).append('\n');
            return value == null ? null : value.trim();
        } catch (Exception e) { log.append(path).append(": ").append(e.getMessage()).append('\n'); return null; }
    }
}
