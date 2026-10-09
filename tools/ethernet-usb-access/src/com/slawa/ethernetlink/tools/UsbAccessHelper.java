package com.slawa.ethernetlink.tools;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Runs once via app_process as the already-authorized ADB shell. No app is installed. */
public final class UsbAccessHelper {
    private static final String VERSION = "1.0-test1";

    public static void main(String[] args) {
        try {
            run(TargetPolicy.mode(args));
        } catch (Throwable failure) {
            while (failure instanceof InvocationTargetException
                    && ((InvocationTargetException) failure).getCause() != null) {
                failure = ((InvocationTargetException) failure).getCause();
            }
            System.err.println("RESULT=ERROR");
            System.err.println("error=" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            System.err.println("Save this output. No root or security-setting changes are required.");
            System.exit(1);
        }
    }

    private static void run(String mode) throws Exception {
        System.out.println("Ethernet Link USB access helper " + VERSION);
        System.out.println("mode=" + mode);
        System.out.println("phone=" + Build.MANUFACTURER + " " + Build.MODEL + "; API " + Build.VERSION.SDK_INT);
        int currentUser = (Integer) Class.forName("android.app.ActivityManager")
                .getMethod("getCurrentUser").invoke(null);
        TargetPolicy.host(android.os.Process.myUid(), Build.MANUFACTURER, Build.VERSION.SDK_INT, currentUser);

        Service usb = new Service("usb", "android.hardware.usb.IUsbManager");
        Service packages = new Service("package", "android.content.pm.IPackageManager");
        PackageInfo app = application(packages);
        UsbDevice device = device(usb);
        String deviceIdentity = fingerprint(device);
        int uid = app.applicationInfo.uid;
        System.out.println("package=" + TargetPolicy.PACKAGE);
        System.out.println("version=" + app.versionName);
        System.out.println("signing_certificate=verified");
        System.out.println("user=" + TargetPolicy.USER + "; uid=" + uid);
        System.out.println("adapter=0bda:8153; path=" + device.getDeviceName());

        Method check = usb.api.getMethod("hasDevicePermissionWithIdentity",
                UsbDevice.class, String.class, int.class, int.class);
        boolean before = hasPermission(usb, check, device, uid);
        System.out.println("current_access.before=" + before);
        if ("check".equals(mode)) {
            System.out.println("RESULT=CHECK_ONLY");
            System.out.println("No settings were changed. This checks current access, not disk persistence.");
            return;
        }

        // Resolve every required API and revalidate identity BEFORE the sole settings write.
        Method setter = usb.api.getMethod("setDevicePersistentPermission",
                UsbDevice.class, int.class, UserHandle.class, boolean.class);
        UserHandle user = (UserHandle) UserHandle.class.getMethod("of", int.class)
                .invoke(null, TargetPolicy.USER);
        PackageInfo latestApp = application(packages);
        UsbDevice latestDevice = device(usb);
        int latestUser = (Integer) Class.forName("android.app.ActivityManager")
                .getMethod("getCurrentUser").invoke(null);
        TargetPolicy.host(android.os.Process.myUid(), Build.MANUFACTURER, Build.VERSION.SDK_INT, latestUser);
        TargetPolicy.unchanged(uid, latestApp.applicationInfo.uid, deviceIdentity, fingerprint(latestDevice));

        boolean allow = "grant".equals(mode);
        setter.invoke(usb.proxy, latestDevice, uid, user, allow);
        System.out.println("persistent_permission.request=" + (allow ? "ALLOW" : "DENY"));
        System.out.println("persistent_permission.setter=ACCEPTED");
        boolean after = hasPermission(usb, check, latestDevice, uid);
        System.out.println("current_access.after=" + after);
        if (after != allow) {
            throw new IllegalStateException("The setting was accepted, but current access does not match. "
                    + "Keep the reports; this firmware needs further investigation.");
        }
        System.out.println("RESULT=" + (allow ? "GRANT_ACCEPTED" : "BLOCK_ACCEPTED"));
        System.out.println(allow
                ? "Now unplug and reconnect the adapter. Open Ethernet Link without pressing Allow."
                : "This stores a DENIAL, not the original ask-every-time behavior. Grant enables access again.");
        System.out.println("Automatic application launch is not changed. Reconnection/reboot have not been tested here.");
    }

    private static PackageInfo application(Service packages) throws Exception {
        Method get;
        Object flags;
        try {
            get = packages.api.getMethod("getPackageInfo", String.class, long.class, int.class);
            flags = (long) PackageManager.GET_SIGNING_CERTIFICATES;
        } catch (NoSuchMethodException olderAndroid) {
            get = packages.api.getMethod("getPackageInfo", String.class, int.class, int.class);
            flags = PackageManager.GET_SIGNING_CERTIFICATES;
        }
        PackageInfo app = (PackageInfo) get.invoke(packages.proxy, TargetPolicy.PACKAGE, flags, TargetPolicy.USER);
        if (app == null || app.applicationInfo == null || app.signingInfo == null) {
            throw new IllegalStateException("Install the existing Ethernet Link v2 APK in the owner's profile first.");
        }
        Signature[] signatures = app.signingInfo.getApkContentsSigners();
        String[] hashes = new String[signatures == null ? 0 : signatures.length];
        for (int i = 0; i < hashes.length; i++) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray());
            StringBuilder text = new StringBuilder(64);
            for (byte b : digest) {
                text.append(Character.forDigit((b & 0xff) >>> 4, 16));
                text.append(Character.forDigit(b & 0xf, 16));
            }
            hashes[i] = text.toString();
        }
        TargetPolicy.app(app.packageName, app.applicationInfo.uid, app.sharedUserId, hashes);
        return app;
    }

    private static UsbDevice device(Service usb) throws Exception {
        Bundle devices = new Bundle();
        usb.api.getMethod("getDeviceList", Bundle.class).invoke(usb.proxy, devices);
        List<UsbDevice> matching = new ArrayList<>();
        for (String key : devices.keySet()) {
            Object value = devices.get(key);
            if (!(value instanceof UsbDevice)) continue;
            UsbDevice device = (UsbDevice) value;
            if (device.getVendorId() == TargetPolicy.VENDOR && device.getProductId() == TargetPolicy.PRODUCT) {
                matching.add(device);
            }
        }
        TargetPolicy.devices(matching.size());
        return matching.get(0);
    }

    private static String fingerprint(UsbDevice d) {
        // The path changes on re-enumeration. Do not query a protected USB serial number here.
        return d.getDeviceName() + "|" + d.getDeviceId() + "|" + d.getVendorId() + ":" + d.getProductId()
                + "|" + d.getDeviceClass() + ":" + d.getDeviceSubclass() + ":" + d.getDeviceProtocol()
                + "|" + d.getManufacturerName() + "|" + d.getProductName() + "|" + d.getVersion()
                + "|" + d.getConfigurationCount() + "|" + d.getInterfaceCount();
    }

    private static boolean hasPermission(Service usb, Method check, UsbDevice device, int uid) throws Exception {
        return (Boolean) check.invoke(usb.proxy, device, TargetPolicy.PACKAGE, -1, uid);
    }

    private static final class Service {
        final Class<?> api;
        final Object proxy;

        Service(String name, String interfaceName) throws Exception {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, name);
            if (binder == null) throw new IllegalStateException("Android service unavailable: " + name);
            api = Class.forName(interfaceName);
            proxy = Class.forName(interfaceName + "$Stub").getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            if (proxy == null) throw new IllegalStateException("Cannot access Android service: " + name);
        }
    }

    private UsbAccessHelper() { }
}
