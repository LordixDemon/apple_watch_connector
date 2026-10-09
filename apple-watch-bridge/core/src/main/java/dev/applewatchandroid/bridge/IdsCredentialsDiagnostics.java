package dev.applewatchandroid.bridge;

import java.util.Map;
import java.util.List;
import java.util.TreeMap;

/** Describes protocol structure without logging credential or identifier values. */
final class IdsCredentialsDiagnostics {
    static final String TOPIC = "com.apple.private.alloy.idscredentials";
    private static final java.util.Set<String> DEVICE_INFO_FIELDS = java.util.Set.of(
            "unique-id", "encryption-key", "encryption-class-a-key", "encryption-class-c-key",
            "private-device-data", "device-name", "hardware-version", "sync-payload");
    private IdsCredentialsDiagnostics() {}

    static String describe(byte[] payload) {
        if (payload == null) return "absent";
        if (payload.length > 64 * 1024) return "oversized bytes=" + payload.length;
        if (payload.length < 8 || payload[0] != 'b' || payload[1] != 'p'
                || payload[2] != 'l' || payload[3] != 'i') {
            return "non-bplist bytes=" + payload.length;
        }
        Object decoded = null;
        try {
            decoded = AppleBinaryPropertyList.decode(payload);
            if (!(decoded instanceof Map<?, ?> message)) return "bplist non-dictionary";
            TreeMap<String, String> fields = new TreeMap<>();
            int unnamed = 0;
            for (Map.Entry<?, ?> field : message.entrySet()) {
                if (fields.size() >= 32) {
                    fields.put("omitted-fields", "count(" + (message.size() - 32) + ")");
                    break;
                }
                String name = field.getKey() instanceof String key ? key : "";
                if (!DEVICE_INFO_FIELDS.contains(name) && !name.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,47}")) {
                    name = "redacted-key-" + (++unnamed);
                }
                Object value = field.getValue();
                String shape = shape(value);
                if ((name.equals("command") || name.equals("type") || name.equals("messageType"))
                        && value instanceof Long command && command >= 0 && command <= 255) {
                    shape = "command(" + command + ")";
                }
                fields.put(name, shape);
                if (name.equals("serviceTypes") && value instanceof List<?> services && services.size() <= 16) {
                    java.util.List<String> topics = new java.util.ArrayList<>();
                    for (Object service : services) {
                        topics.add(service instanceof String topic && topic.matches("com\\.apple\\.[A-Za-z0-9._-]{1,160}")
                                ? topic : "redacted-service");
                    }
                    fields.put("serviceTypes.topics", topics.toString());
                }
                if (name.equals("accountMap") && value instanceof Map<?, ?> accounts && accounts.size() <= 16) {
                    TreeMap<String, String> counts = new TreeMap<>();
                    for (var account : accounts.entrySet()) {
                        if (account.getKey() instanceof String topic && topic.matches("com\\.apple\\.[A-Za-z0-9._-]{1,160}")) {
                            counts.put(topic, shape(account.getValue()));
                        }
                    }
                    fields.put("accountMap.serviceCounts", counts.toString());
                }
                if (name.equals("sync-payload") && value instanceof Map<?, ?> sync) {
                    // Only a known numeric protocol selector is useful here.
                    // Account/token values, including nested keys, stay private.
                    Object syncCommand = sync.get("command");
                    if (syncCommand instanceof Long number && number >= 0 && number <= 255) {
                        fields.put("sync-payload.command", "command(" + number + ")");
                    }
                    for (String key : List.of(IdsSpsCompanionInfo.PHONE_NUMBERS, IdsSpsCompanionInfo.DEVICE_UDID)) {
                        if (sync.containsKey(key)) fields.put("sync-payload." + key, shape(sync.get(key)));
                    }
                }
            }
            return "bplist fields=" + fields;
        } catch (RuntimeException invalid) {
            return "invalid-bplist bytes=" + payload.length;
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }

    private static String shape(Object value) {
        return value instanceof byte[] data ? "bytes(" + data.length + ")"
                : value instanceof String text ? "string(" + text.length() + ")"
                : value instanceof Map<?, ?> map ? "dictionary(" + map.size() + ")"
                : value instanceof List<?> list ? "array(" + list.size() + ")"
                : value instanceof Boolean ? "boolean"
                : value instanceof Number ? "number" : "other";
    }

    /**
     * Logs descriptor VALUES of the Watch device-info response (command 12):
     * device-name, hardware-version, identifier and the nested
     * private-device-data strings (product name/version/build, peer "u" UUID).
     * Public-key blobs stay length-only. Returns null for any other command.
     */
    static String describeDeviceInfoValues(byte[] payload) {
        if (payload == null || payload.length > 64 * 1024) return null;
        if (payload.length < 8 || payload[0] != 'b' || payload[1] != 'p'
                || payload[2] != 'l' || payload[3] != 'i') return null;
        Object decoded = null;
        try {
            decoded = AppleBinaryPropertyList.decode(payload);
            if (!(decoded instanceof Map<?, ?> message)) return null;
            Object command = message.get("command");
            if (!(command instanceof Long number) || number != 12L) return null;
            TreeMap<String, String> out = new TreeMap<>();
            for (String key : List.of("device-name", "hardware-version", "identifier", "unique-id")) {
                Object value = message.get(key);
                if (value instanceof String text && text.length() <= 96) {
                    out.put(key, "\"" + text + "\"");
                }
            }
            Object success = message.get("success");
            if (success instanceof Boolean flag) out.put("success", flag.toString());
            Object privateData = message.get("private-device-data");
            Object nested = privateData;
            if (privateData instanceof byte[] packed
                    && packed.length >= 8 && packed.length <= 64 * 1024
                    && packed[0] == 'b' && packed[1] == 'p') {
                try { nested = AppleBinaryPropertyList.decode(packed); }
                catch (RuntimeException invalid) { nested = privateData; }
            }
            if (nested instanceof Map<?, ?> data) {
                TreeMap<String, String> inner = new TreeMap<>();
                int count = 0;
                for (Map.Entry<?, ?> entry : data.entrySet()) {
                    if (++count > 24) { inner.put("omitted", "count(" + (data.size() - 24) + ")"); break; }
                    String key = entry.getKey() instanceof String name
                            && name.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,47}") ? name : "redacted";
                    Object value = entry.getValue();
                    if (value instanceof String text && text.length() <= 96) inner.put(key, "\"" + text + "\"");
                    else if (value instanceof Number || value instanceof Boolean) inner.put(key, String.valueOf(value));
                    else inner.put(key, shape(value));
                }
                out.put("private-device-data", inner.toString());
            } else if (nested != null) {
                out.put("private-device-data", shape(nested));
            }
            IdsMessageProtectionIdentity.wipeValues(nested instanceof Map<?, ?> ? nested : null);
            return "DEVICE INFO VALUES: " + out;
        } catch (RuntimeException invalid) {
            return null;
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }
}
