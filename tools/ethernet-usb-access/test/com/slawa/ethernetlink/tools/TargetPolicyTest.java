package com.slawa.ethernetlink.tools;

public final class TargetPolicyTest {
    private static int checked;

    public static void main(String[] args) {
        if (!"check".equals(TargetPolicy.mode(new String[0]))) throw new AssertionError("read-only default");
        if (!"grant".equals(TargetPolicy.mode(new String[]{"grant"}))) throw new AssertionError("explicit grant");
        if (!"block".equals(TargetPolicy.mode(new String[]{"block"}))) throw new AssertionError("explicit denial");
        deny(() -> TargetPolicy.mode(new String[]{"reset"}));
        deny(() -> TargetPolicy.mode(new String[]{"grant", "other.package"}));
        TargetPolicy.host(2000, "samsung", 36, 0);
        deny(() -> TargetPolicy.host(0, "samsung", 36, 0));
        deny(() -> TargetPolicy.host(10566, "samsung", 36, 0));
        deny(() -> TargetPolicy.host(2000, "realme", 36, 0));
        deny(() -> TargetPolicy.host(2000, "samsung", 30, 0));
        deny(() -> TargetPolicy.host(2000, "samsung", 36, 10));
        String[] cert = {TargetPolicy.CERT_SHA256};
        TargetPolicy.app(TargetPolicy.PACKAGE, 10566, null, cert);
        deny(() -> TargetPolicy.app("other.package", 10566, null, cert));
        deny(() -> TargetPolicy.app(TargetPolicy.PACKAGE, 2000, null, cert));
        deny(() -> TargetPolicy.app(TargetPolicy.PACKAGE, 1010566, null, cert));
        deny(() -> TargetPolicy.app(TargetPolicy.PACKAGE, 99001, null, cert));
        deny(() -> TargetPolicy.app(TargetPolicy.PACKAGE, 10566, "shared.uid", cert));
        deny(() -> TargetPolicy.app(TargetPolicy.PACKAGE, 10566, null, null));
        deny(() -> TargetPolicy.app(TargetPolicy.PACKAGE, 10566, null, new String[0]));
        deny(() -> TargetPolicy.app(TargetPolicy.PACKAGE, 10566, null, new String[]{"wrong"}));
        deny(() -> TargetPolicy.app(TargetPolicy.PACKAGE, 10566, null, new String[]{cert[0], cert[0]}));
        TargetPolicy.devices(1);
        deny(() -> TargetPolicy.devices(0));
        deny(() -> TargetPolicy.devices(2));
        TargetPolicy.unchanged(10566, 10566, "/dev/bus/usb/002/002", "/dev/bus/usb/002/002");
        deny(() -> TargetPolicy.unchanged(10566, 10567, "same", "same"));
        deny(() -> TargetPolicy.unchanged(10566, 10566, "old device", "new device"));
        System.out.println("TargetPolicy: valid target accepted; " + checked + " unsafe/invalid cases rejected.");
    }

    private static void deny(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            checked++;
            return;
        }
        throw new AssertionError("Expected scope validation to reject this operation");
    }
}
