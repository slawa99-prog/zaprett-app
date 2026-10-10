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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs once via app_process as the already-authorized ADB shell. No app is installed. */
public final class UsbAccessHelper {
    private static final String VERSION = "1.0-test3";
    private static String stage = "startup";
    private static boolean requestAccepted;

    public static void main(String[] args) {
        try {
            run(TargetPolicy.mode(args));
        } catch (Throwable failure) {
            while (failure instanceof InvocationTargetException
                    && ((InvocationTargetException) failure).getCause() != null) {
                failure = ((InvocationTargetException) failure).getCause();
            }
            System.err.println("RESULT=ERROR");
            System.err.println("error.stage=" + stage);
            System.err.println("persistent_permission.setter_accepted=" + requestAccepted);
            System.err.println("error=" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            failure.printStackTrace(System.err);
            System.err.println("Save this output. No root or security-setting changes are required.");
            System.exit(1);
        }
    }

    private static void run(String mode) throws Exception {
        stage = "validate_target";
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

        // hasDevicePermissionWithIdentity fails on this Samsung/AOSP path because the
        // service reads a USB serial without clearing the calling identity (UID 2000).
        // Use the read-only system dump for verification. The setter below still enforces
        // MANAGE_USB itself; no package identity or permission checks are patched.
        stage = "read_permission_records_before";
        UsbPermissionSnapshot before = snapshot(device, uid);
        printSnapshot("before", before);
        if ("check".equals(mode)) {
            System.out.println("RESULT=CHECK_ONLY");
            System.out.println("No settings were changed. These are system permission records, not an app USB-open test.");
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
        stage = "set_persistent_permission";
        setter.invoke(usb.proxy, latestDevice, uid, user, allow);
        requestAccepted = true;
        System.out.println("persistent_permission.request=" + (allow ? "ALLOW" : "DENY"));
        System.out.println("persistent_permission.setter=ACCEPTED");
        stage = "verify_persistent_record";
        UsbDevice finalDevice = device(usb);
        PackageInfo finalApp = application(packages);
        TargetPolicy.unchanged(uid, finalApp.applicationInfo.uid, deviceIdentity, fingerprint(finalDevice));
        UsbPermissionSnapshot after = snapshot(finalDevice, uid);
        before.requireSameDevice(after);
        printSnapshot("after", after);
        after.requirePersistent(allow);
        System.out.println("RESULT=" + (allow ? "GRANT_VERIFIED" : "BLOCK_VERIFIED"));
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

    private static void printSnapshot(String when, UsbPermissionSnapshot snapshot) {
        System.out.println("verification.source=dumpsys_usb");
        System.out.println("persistent_permission.record." + when + "=" + snapshot.persistent);
        System.out.println("temporary_permission.record." + when + "=" + snapshot.temporaryGrant);
    }

    private static UsbPermissionSnapshot snapshot(UsbDevice device, int uid) throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("name", device.getDeviceName());
        expected.put("vendor_id", Integer.toString(device.getVendorId()));
        expected.put("product_id", Integer.toString(device.getProductId()));
        expected.put("class", Integer.toString(device.getDeviceClass()));
        expected.put("subclass", Integer.toString(device.getDeviceSubclass()));
        expected.put("protocol", Integer.toString(device.getDeviceProtocol()));
        expected.put("manufacturer_name", String.valueOf(device.getManufacturerName()));
        expected.put("product_name", String.valueOf(device.getProductName()));
        return UsbPermissionSnapshot.read(usbDump(), expected, TargetPolicy.USER, uid);
    }

    private static String usbDump() throws Exception {
        File report = File.createTempFile("ethernet-usb-report-", ".txt", new File("/data/local/tmp"));
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "-t", "5", "usb")
                    .redirectErrorStream(true).redirectOutput(report).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("USB system report timed out.");
            if (process.exitValue() != 0) throw new IOException("dumpsys usb failed: " + process.exitValue());
            if (report.length() > 4 * 1024 * 1024) throw new IOException("USB system report exceeds 4 MiB.");
            try (FileInputStream input = new FileInputStream(report);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 4 * 1024 * 1024) throw new IOException("USB report size limit.");
                    bytes.write(buffer, 0, count);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (!report.delete() && report.exists()) System.err.println("warning=Temporary USB report was not removed.");
        }
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
