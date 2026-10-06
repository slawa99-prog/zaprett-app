package com.slawa.ethernetlink;

import java.util.HashSet;
import java.util.Set;

/** Attach/resume never requests permission: Android resolves the saved USB handler.
 * Only an explicit button may request access, with at most one dialog in flight. */
final class UsbPermissionGate {
    private final Set<String> pending=new HashSet<>();
    void retain(Set<String> attached){pending.retainAll(attached);}
    void detached(String key){pending.remove(key);}
    void completed(){pending.clear();}
    boolean shouldRequest(String key,boolean granted,boolean manual){
        if(granted){pending.remove(key);return false;}
        return manual&&pending.add(key);
    }
}
