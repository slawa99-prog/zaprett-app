package com.slawa.ethernetlink.tools;

/** Exact scope for the one-time Samsung / RTL8153 configuration helper. */
final class TargetPolicy {
    static final String PACKAGE = "com.slawa.ethernetlink.v2";
    static final String CERT_SHA256 =
            "8415b59b44031e31d6584253cf1424267dd32e713810920e229d43a6184ee354";
    static final int USER = 0;
    static final int VENDOR = 0x0bda;
    static final int PRODUCT = 0x8153;

    static String mode(String[] args) {
        if (args.length == 0) return "check";
        if (args.length == 1 && ("check".equals(args[0])
                || "grant".equals(args[0]) || "block".equals(args[0]))) return args[0];
        throw new IllegalArgumentException("Usage: UsbAccessHelper [check|grant|block]");
    }

    static void host(int processUid, String manufacturer, int api, int foregroundUser) {
        require(processUid == 2000, "Run with adb shell (UID 2000), not as an app or root.");
        require("samsung".equalsIgnoreCase(manufacturer), "This helper targets Samsung phones only.");
        require(api >= 31, "Android 12 or newer is required.");
        require(foregroundUser == USER, "Switch to the phone's owner profile (user 0).");
    }

    static void app(String packageName, int uid, String sharedUserId, String[] certificates) {
        require(PACKAGE.equals(packageName), "Unexpected application package.");
        require(uid >= 10000 && uid < 99000, "The application must be installed in owner user 0.");
        require(sharedUserId == null, "Shared application UIDs are not supported.");
        require(certificates != null && certificates.length == 1
                && CERT_SHA256.equals(certificates[0]), "Ethernet Link signing certificate mismatch.");
    }

    static void devices(int matchingCount) {
        require(matchingCount == 1, matchingCount == 0
                ? "Connect the RTL8153 adapter (0bda:8153) to the phone."
                : "More than one RTL8153 is connected. Leave only one adapter connected.");
    }

    static void unchanged(int originalUid, int currentUid, String originalDevice, String currentDevice) {
        require(originalUid == currentUid && originalDevice.equals(currentDevice),
                "The application or USB device changed. No permission was changed; run again.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private TargetPolicy() { }
}
