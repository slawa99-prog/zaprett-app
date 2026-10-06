package com.slawa.ethernetlink;

import java.util.HashSet;
import java.util.Set;

/** One automatic prompt per attachment; a denial never produces a dialog loop. */
final class UsbPermissionGate {
    private final Set<String> attempted=new HashSet<>();
    void retain(Set<String> attached){attempted.retainAll(attached);}
    void detached(String key){attempted.remove(key);}
    boolean shouldRequest(String key,boolean granted,boolean manual){
        if(granted)return false;
        return manual ? mark(key) : attempted.add(key);
    }
    private boolean mark(String key){attempted.add(key);return true;}
}
