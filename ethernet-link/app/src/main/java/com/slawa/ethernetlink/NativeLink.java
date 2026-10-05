package com.slawa.ethernetlink;

final class NativeLink {
    private static boolean loaded;
    static {
        try { System.loadLibrary("ethernetlink"); loaded = true; }
        catch (Throwable ignored) { loaded = false; }
    }
    private NativeLink() {}
    static boolean isLoaded() { return loaded; }
    static native String getLinkInfo(String interfaceName);
}
