package com.slawa.ethernetlink.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads permission records from the AOSP/Samsung DualDumpOutputStream text format. */
final class UsbPermissionSnapshot {
    enum Persistent { NOT_SET, ALLOW, DENY }

    private static final String[] FILTER_FIELDS = {"vendor_id", "product_id", "class", "subclass",
            "protocol", "manufacturer_name", "product_name", "serial_number"};
    final Persistent persistent;
    final boolean temporaryGrant;
    final Map<String, String> deviceFilter;

    private UsbPermissionSnapshot(Persistent persistent, boolean temporaryGrant, Map<String, String> filter) {
        this.persistent = persistent;
        this.temporaryGrant = temporaryGrant;
        this.deviceFilter = filter;
    }

    static UsbPermissionSnapshot read(String dump, Map<String, String> expectedDevice, int userId, int uid) {
        Node host = section(dump, "host_manager");
        Node device = null;
        for (Node candidate : host.entries("devices")) {
            if (expectedDevice.get("name").equals(candidate.value("name"))) {
                require(device == null, "Duplicate current USB device in the system report.");
                device = candidate;
            }
        }
        require(device != null, "The selected USB device is absent from the system report.");
        for (Map.Entry<String, String> field : expectedDevice.entrySet()) {
            require(field.getValue().equals(device.value(field.getKey())),
                    "USB descriptor changed or is missing in report: " + field.getKey());
        }
        Map<String, String> filter = new LinkedHashMap<>();
        for (String field : FILTER_FIELDS) {
            String value = device.value(field);
            require(value != null, "The system report lacks USB field: " + field);
            filter.put(field, value);
        }

        Node manager = section(dump, "permissions_manager");
        Node user = null;
        for (Node candidate : manager.entries("user_permissions")) {
            String id = candidate.value("user_id");
            require(id != null, "Missing user_id in the permissions report.");
            if (Integer.toString(userId).equals(id)) {
                require(user == null, "Duplicate user in the permissions report.");
                user = candidate;
            }
        }
        Persistent persistent = Persistent.NOT_SET;
        boolean temporary = false;
        if (user != null) {
            int matchingRecords = 0;
            for (Node entry : user.entries("device_persistent_permissions")) {
                List<Node> descriptors = entry.entries("device");
                require(descriptors.size() == 1, "Unrecognized persistent USB permission format.");
                boolean matches = true;
                for (String field : FILTER_FIELDS) {
                    String value = descriptors.get(0).value(field);
                    require(value != null, "Incomplete permission device filter: " + field);
                    matches &= filter.get(field).equals(value);
                }
                if (!matches) continue;
                for (Node permission : entry.entries("uid_permission")) {
                    String recordUid = permission.value("uid");
                    String granted = permission.value("is_granted");
                    require(recordUid != null && ("true".equals(granted) || "false".equals(granted)),
                            "Unrecognized persistent UID permission value.");
                    if (Integer.toString(uid).equals(recordUid)) {
                        require(++matchingRecords == 1, "Duplicate persistent permission for the target UID.");
                        persistent = "true".equals(granted) ? Persistent.ALLOW : Persistent.DENY;
                    }
                }
            }
            for (Node entry : user.entries("device_permissions")) {
                if (expectedDevice.get("name").equals(entry.value("device_name"))) {
                    temporary |= entry.scalarValues("uids").contains(Integer.toString(uid));
                }
            }
        }
        return new UsbPermissionSnapshot(persistent, temporary, filter);
    }

    void requireSameDevice(UsbPermissionSnapshot other) {
        require(deviceFilter.equals(other.deviceFilter), "USB device identity changed during configuration.");
    }

    void requirePersistent(boolean allow) {
        require(persistent == (allow ? Persistent.ALLOW : Persistent.DENY),
                "The system accepted the request, but the expected persistent record was not found.");
    }

    private static Node section(String dump, String name) {
        List<String> lines = Arrays.asList(dump.split("\\r?\\n", -1));
        Node result = null;
        for (int i = 0; i < lines.size(); i++) {
            // These are top-level USB service sections, not event logs or saved app defaults.
            if (!lines.get(i).equals("  " + name + "={")) continue;
            require(result == null, "Duplicate USB report section: " + name);
            int end = i + 1;
            while (end < lines.size() && !lines.get(end).equals("  }")) end++;
            require(end < lines.size(), "Incomplete USB report section: " + name);
            Cursor cursor = new Cursor(lines.subList(i + 1, end));
            result = parse(cursor, name, false, null);
            i = end;
        }
        require(result != null, "Missing USB report section: " + name);
        return result;
    }

    private static Node parse(Cursor cursor, String name, boolean array, String closing) {
        Node node = new Node(name, array);
        while (cursor.index < cursor.lines.size()) {
            String text = cursor.lines.get(cursor.index++).trim();
            if (text.equals("}") || text.equals("]")) {
                require(text.equals(closing), "Unexpected closing delimiter in USB report.");
                return node;
            }
            if (text.equals("{")) {
                require(array, "Unexpected anonymous object in USB report.");
                node.children.add(parse(cursor, "", false, "}"));
                continue;
            }
            int equals = text.indexOf('=');
            if (equals >= 0) {
                String key = text.substring(0, equals);
                String value = text.substring(equals + 1);
                if (value.equals("{") || value.equals("[")) {
                    node.children.add(parse(cursor, key, value.equals("["), value.equals("[") ? "]" : "}"));
                } else if (!value.endsWith("={") && !value.endsWith("=[")) {
                    if (!node.values.containsKey(key)) node.values.put(key, new ArrayList<>());
                    node.values.get(key).add(value);
                }
            } else if (array) {
                node.items.add(text);
            }
        }
        require(closing == null, "Truncated USB report object.");
        return node;
    }

    private static final class Cursor {
        final List<String> lines;
        int index;
        Cursor(List<String> lines) { this.lines = lines; }
    }

    private static final class Node {
        final String name;
        final boolean array;
        final List<Node> children = new ArrayList<>();
        final List<String> items = new ArrayList<>();
        final Map<String, List<String>> values = new LinkedHashMap<>();
        Node(String name, boolean array) { this.name = name; this.array = array; }

        String value(String key) {
            List<String> values = this.values.get(key);
            require(values == null || values.size() == 1, "Duplicate USB report field: " + key);
            return values == null ? null : values.get(0);
        }

        List<Node> entries(String key) {
            List<Node> entries = new ArrayList<>();
            for (Node child : children) {
                if (!child.name.equals(key)) continue;
                if (child.array) {
                    for (Node item : child.children) {
                        require(item.name.isEmpty() && !item.array, "Unrecognized USB report array: " + key);
                        entries.add(item);
                    }
                    require(child.items.isEmpty(), "Unexpected scalar USB report entry: " + key);
                } else entries.add(child);
            }
            return entries;
        }

        List<String> scalarValues(String key) {
            List<String> result = new ArrayList<>();
            if (values.containsKey(key)) result.addAll(values.get(key));
            for (Node child : children) {
                if (!child.name.equals(key)) continue;
                require(child.array && child.children.isEmpty(), "Unexpected UID list format.");
                result.addAll(child.items);
            }
            return result;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
