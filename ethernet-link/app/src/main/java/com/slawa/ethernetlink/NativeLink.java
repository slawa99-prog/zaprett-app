package com.slawa.ethernetlink;

final class NativeLink {
    private static boolean loaded;
    private static String error = "";
    static {
        try { System.loadLibrary("ethernetlink"); loaded = true; }
        catch (LinkageError | SecurityException e) { error = e.toString(); }
    }
    private NativeLink() {}
    static boolean isLoaded() { return loaded; }
    static String loadError() { return error; }
    static native String getLinkInfo(String interfaceName);
}
