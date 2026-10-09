package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 23S303 wifid secure Foundation archive, IDS DATA, not a protobuf or Setup replay. */
final class WifiNetworkSyncCodec {
    static final String TOPIC = "com.apple.private.alloy.wifi.networksync";
    static final String COMMAND = "SYNC_CURRENT_WIFI";

    private WifiNetworkSyncCodec() { }

    /** Add one verified WPA2-PSK/CCMP network without replacing the Watch's network list. */
    static byte[] addWpa2(String ssid, String password) {
        if (ssid == null || ssid.isEmpty() || ssid.getBytes(StandardCharsets.UTF_8).length > 32
                || ssid.indexOf('\0') >= 0 || password == null || password.length() < 8
                || password.length() > 63) throw new IllegalArgumentException("Unsupported Wi-Fi credentials");
        for (int i = 0; i < password.length(); i++) {
            if (password.charAt(i) < 32 || password.charAt(i) > 126) {
                throw new IllegalArgumentException("Unsupported Wi-Fi passphrase encoding");
            }
        }
        Map<String, Object> rsn = new LinkedHashMap<>();
        rsn.put("IE_KEY_RSN_VERSION", 1L);
        rsn.put("IE_KEY_RSN_MCIPHER", 4L);
        rsn.put("IE_KEY_RSN_UCIPHERS", List.of(4L));
        rsn.put("IE_KEY_RSN_AUTHSELS", List.of(2L));
        rsn.put("IE_KEY_RSN_CAPS", 0L);
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("SSID_STR", ssid);
        network.put("AP_MODE", 2L);
        // wifid 23S303 FUN_100074968 reads UserDirected as hidden; the
        // CWFNetworkProfile bridge (100079af0) passes it to setHiddenState:.
        // AndroidWifiNetwork accepts only a verified broadcast network.
        network.put("UserDirected", false);
        network.put("enabled", true);
        network.put("WEP", false);
        network.put("RSN_IE", rsn);
        network.put("WiFiNetworkPasswordString", password);
        network.put("IS_NETWORK_CAPTIVE", false);
        network.put("IS_NETWORK_WHITELISTING_CAPTIVE", false);
        network.put("IS_NETWORK_EAP", false);
        network.put("IS_NETWORK_CUSTOMIZED", false);
        network.put("IS_NETWORK_CONFIGURED", false);
        return new Archive().encode(Map.of("V2", Map.of("ADD", network)));
    }

    /** Only Foundation collection classes accepted by native wifid; primitive NSNumber values. */
    private static final class Archive {
        final List<Object> objects = new ArrayList<>(List.of("$null"));
        final Map<String, Integer> classes = new LinkedHashMap<>();
        byte[] encode(Object value) {
            var root = object(value);
            return AppleBinaryPropertyList.encode(Map.of("$version", 100000L,
                    "$archiver", "NSKeyedArchiver", "$top", Map.of("root", root), "$objects", objects));
        }
        AppleBinaryPropertyList.Uid object(Object value) {
            int index = objects.size();
            objects.add(value);
            if (value instanceof Map<?, ?> dictionary) {
                List<Object> keys = new ArrayList<>(), values = new ArrayList<>();
                for (var entry : dictionary.entrySet()) {
                    keys.add(object(entry.getKey())); values.add(object(entry.getValue()));
                }
                objects.set(index, Map.of("$class", descriptor("NSDictionary"), "NS.keys", keys, "NS.objects", values));
            } else if (value instanceof List<?> array) {
                List<Object> values = new ArrayList<>();
                for (Object child : array) values.add(object(child));
                objects.set(index, Map.of("$class", descriptor("NSArray"), "NS.objects", values));
            } else if (!(value instanceof String || value instanceof Long || value instanceof Boolean)) {
                throw new IllegalArgumentException("Unsupported Wi-Fi archive class");
            }
            return new AppleBinaryPropertyList.Uid(index);
        }
        AppleBinaryPropertyList.Uid descriptor(String name) {
            Integer index = classes.get(name);
            if (index == null) {
                index = objects.size(); classes.put(name, index);
                objects.add(Map.of("$classname", name, "$classes", List.of(name, "NSObject")));
            }
            return new AppleBinaryPropertyList.Uid(index);
        }
    }
}
