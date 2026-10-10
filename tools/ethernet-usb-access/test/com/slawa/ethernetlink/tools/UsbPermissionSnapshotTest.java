package com.slawa.ethernetlink.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public final class UsbPermissionSnapshotTest {
    private static final int UID = 11234;
    private static final String PATH = "/dev/bus/usb/002/002";
    private static int checked;

    public static void main(String[] args) throws Exception {
        Map<String, String> expected = expected();
        if (args.length == 4) {
            String dump = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
            UsbPermissionSnapshot actual = UsbPermissionSnapshot.read(dump, expected, 0, Integer.parseInt(args[1]));
            verify(actual.persistent.name().equals(args[2]) && actual.temporaryGrant == Boolean.parseBoolean(args[3]));
            System.out.println("Provided phone report: parsed and expected permission records matched.");
            return;
        }
        UsbPermissionSnapshot none = read(report("", ""));
        verify(none.persistent == UsbPermissionSnapshot.Persistent.NOT_SET && !none.temporaryGrant);
        // A saved default app is not a permission grant.
        verify(read(report("", "") + "  profile_group_settings={\n    device_preferences={\n"
                + "      uid=" + UID + "\n      is_granted=true\n    }\n  }\n").persistent
                == UsbPermissionSnapshot.Persistent.NOT_SET);
        UsbPermissionSnapshot allowed = read(report(record("SERIAL-A", UID, true), ""));
        allowed.requirePersistent(true);
        verify(allowed.persistent == UsbPermissionSnapshot.Persistent.ALLOW);
        UsbPermissionSnapshot denied = read(report(record("SERIAL-A", UID, false), temporary(false)));
        denied.requirePersistent(false);
        verify(denied.persistent == UsbPermissionSnapshot.Persistent.DENY && denied.temporaryGrant);
        verify(read(report("", temporary(false))).temporaryGrant);
        verify(read(report("", temporary(true))).temporaryGrant);
        verify(!read(report("", temporary(false).replace("uids=" + UID, "uids=19999"))).temporaryGrant);
        verify(read(report(record("SERIAL-B", UID, true), "")).persistent == UsbPermissionSnapshot.Persistent.NOT_SET);
        verify(read(report(record("SERIAL-A", UID + 1, true), "")).persistent == UsbPermissionSnapshot.Persistent.NOT_SET);
        verify(read(report(record("SERIAL-A", UID, true), "").replace("user_id=0", "user_id=10")).persistent
                == UsbPermissionSnapshot.Persistent.NOT_SET);
        String repeated = "      device_persistent_permissions=[\n" + indent(anonymous(record("SERIAL-B", UID, true)), 8)
                + indent(anonymous(record("SERIAL-A", UID, false)), 8) + "      ]\n";
        verify(read(report(repeated, "")).persistent == UsbPermissionSnapshot.Persistent.DENY);
        String multipleUsers = report(record("SERIAL-A", UID, true), "").replace("    user_permissions={\n",
                "    user_permissions=[\n      {\n        user_id=10\n      }\n      {\n")
                .replace("    }\n  }\n}\n", "      }\n    ]\n  }\n}\n");
        verify(read(multipleUsers).persistent == UsbPermissionSnapshot.Persistent.ALLOW);
        fail(() -> read(report(record("SERIAL-A", UID, true), "").replace("is_granted=true", "is_granted=unknown")));
        fail(() -> read(report(record("SERIAL-A", UID, true), "").replace("  permissions_manager={", "  settings_manager={")));
        fail(() -> read(report(record("SERIAL-A", UID, true), "").replace("serial_number=SERIAL-A\n", "")));
        fail(() -> read(report(record("SERIAL-A", UID, true), "").replace("name=" + PATH, "name=/dev/bus/usb/002/003")));
        fail(() -> read(report(record("SERIAL-A", UID, true), "").replace("    user_permissions={", "    user_permissions={\n      user_id=0")));
        fail(() -> read(report(record("SERIAL-A", UID, true) + record("SERIAL-A", UID, false), "")));
        fail(() -> read(report(record("SERIAL-A", UID, true), "").replace("      device_persistent_permissions={", "      device_persistent_permissions=[")));
        fail(() -> read(report(record("SERIAL-A", UID, true), "").replace("  }\n}\n", "")));
        fail(() -> none.requirePersistent(true));
        fail(() -> denied.requirePersistent(true));
        fail(() -> allowed.requirePersistent(false));
        fail(() -> none.requireSameDevice(read(report("", "").replace("SERIAL-A", "SERIAL-B"))));
        System.out.println("USB report parser: " + checked + " record/scope/format checks passed.");
    }

    private static Map<String, String> expected() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("name", PATH);
        map.put("vendor_id", "3034"); map.put("product_id", "33107");
        map.put("class", "0"); map.put("subclass", "0"); map.put("protocol", "0");
        map.put("manufacturer_name", "Realtek"); map.put("product_name", "USB 10/100/1000 LAN");
        return map;
    }

    private static UsbPermissionSnapshot read(String dump) {
        return UsbPermissionSnapshot.read(dump, expected(), 0, UID);
    }

    private static String filter(String serial) {
        return "vendor_id=3034\nproduct_id=33107\nclass=0\nsubclass=0\nprotocol=0\n"
                + "manufacturer_name=Realtek\nproduct_name=USB 10/100/1000 LAN\nserial_number=" + serial + "\n";
    }

    private static String report(String persistent, String temporary) {
        return "OEM header\n{\n  host_manager={\n    devices={\n      name=" + PATH + "\n"
                + indent(filter("SERIAL-A"), 6) + "      configurations=[\n        {\n          id=1\n        }\n      ]\n"
                + "    }\n  }\n  permissions_manager={\n    user_permissions={\n      user_id=0\n"
                + persistent + temporary + "    }\n  }\n}\n";
    }

    private static String record(String serial, int uid, boolean allow) {
        return "      device_persistent_permissions={\n        device={\n" + indent(filter(serial), 10)
                + "        }\n        uid_permission={\n          uid=" + uid + "\n          is_granted=" + allow
                + "\n        }\n      }\n";
    }

    private static String temporary(boolean array) {
        return "      device_permissions={\n        device_name=" + PATH + "\n"
                + (array ? "        uids=[\n          19999\n          " + UID + "\n        ]\n" : "        uids=" + UID + "\n")
                + "      }\n";
    }

    private static String anonymous(String entry) {
        return entry.replace("      device_persistent_permissions={", "{");
    }

    private static String indent(String text, int spaces) {
        StringBuilder result = new StringBuilder();
        String pad = new String(new char[spaces]).replace('\0', ' ');
        for (String line : text.split("\n")) result.append(pad).append(line).append('\n');
        return result.toString();
    }

    private static void verify(boolean value) {
        if (!value) throw new AssertionError("Unexpected USB permission record");
        checked++;
    }

    private static void fail(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { checked++; return; }
        throw new AssertionError("Malformed or mismatched state must not be verified");
    }
}
