package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class WifiNetworkSyncTest {
    private static final String STATUS = "Wifi is enabled\nWifiInfo: SSID: \"Test café\", BSSID: 00:11:22:33:44:55, Security type: 2, Supplicant state: COMPLETED\nNetworkCapabilities: [ NOT_METERED&INTERNET&VALIDATED ]";
    private static final String SCAN = "  00:11:22:33:44:55 5785 -51 0.0 Test café [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]\n";
    private static final String CONFIG = "<WifiConfigStoreData><NetworkList><Network><WifiConfiguration>"
            + "<string name=\"SSID\">&quot;Test café&quot;</string>"
            + "<string name=\"PreSharedKey\">&quot;testpass123&quot;</string>"
            + "<boolean name=\"HiddenSSID\" value=\"false\"/>"
            + "<byte-array name=\"AllowedKeyMgmt\" num=\"1\">02</byte-array>"
            + "</WifiConfiguration></Network></NetworkList></WifiConfigStoreData>";
    private static byte[] xml(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @SuppressWarnings("unchecked")
    private static Object resolve(Object value, List<?> objects) {
        if (!(value instanceof AppleBinaryPropertyList.Uid uid)) return value;
        Object raw = objects.get(uid.value());
        if (!(raw instanceof Map<?, ?> map)) return raw;
        if (map.containsKey("NS.keys")) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            var keys = (List<?>) map.get("NS.keys"); var values = (List<?>) map.get("NS.objects");
            for (int i = 0; i < keys.size(); i++) result.put((String) resolve(keys.get(i), objects), resolve(values.get(i), objects));
            return result;
        }
        return ((List<?>) map.get("NS.objects")).stream().map(child -> resolve(child, objects)).toList();
    }
    private static Map<?, ?> unpack(byte[] archive) {
        var plist = (Map<?, ?>) AppleBinaryPropertyList.decode(archive);
        assertEquals("NSKeyedArchiver", plist.get("$archiver")); assertEquals(100000L, plist.get("$version"));
        var objects = (List<?>) plist.get("$objects");
        for (Object object : objects) if (object instanceof Map<?, ?> descriptor && descriptor.containsKey("$classname")) {
            assertTrue(List.of("NSDictionary", "NSArray").contains(descriptor.get("$classname")));
            assertEquals(List.of(descriptor.get("$classname"), "NSObject"), descriptor.get("$classes"));
        }
        return (Map<?, ?>) resolve(((Map<?, ?>) plist.get("$top")).get("root"), objects);
    }
    @Test public void nativeArchiveIsSingleV2AddWithPasswordAndRsn() {
        var root = unpack(AndroidWifiNetwork.select(xml(CONFIG), STATUS, SCAN).archive());
        assertEquals(1, root.size()); var v2 = (Map<?, ?>) root.get("V2"); assertEquals(1, v2.size());
        var network = (Map<?, ?>) v2.get("ADD");
        assertEquals("Test café", network.get("SSID_STR")); assertEquals("testpass123", network.get("WiFiNetworkPasswordString"));
        assertEquals(2L, network.get("AP_MODE")); assertEquals(false, network.get("IS_NETWORK_CAPTIVE"));
        // Native wifid maps true to CWFNetworkProfile's hidden state. The
        // Unicode SSID must stay intact while its broadcast status is false.
        assertEquals(false, network.get("UserDirected"));
        var rsn = (Map<?, ?>) network.get("RSN_IE");
        assertEquals(4L, rsn.get("IE_KEY_RSN_MCIPHER")); assertEquals(List.of(4L), rsn.get("IE_KEY_RSN_UCIPHERS"));
        assertEquals(List.of(2L), rsn.get("IE_KEY_RSN_AUTHSELS"));
        assertFalse(root.containsKey("REMOVE")); assertFalse(root.containsKey("KnownNetworks"));
    }
    @Test public void noCredentialsInCommandOrDiagnostics() {
        assertEquals("AndroidWifiNetwork[credentials withheld]", AndroidWifiNetwork.select(xml(CONFIG), STATUS, SCAN).toString());
        assertTrue(OperationalCommandPolicy.isAllowed(WifiNetworkSyncCodec.COMMAND));
        assertFalse(OperationalCommandPolicy.isAllowed(WifiNetworkSyncCodec.COMMAND + ":testpass123"));
        assertFalse(OperationalCommandPolicy.isAllowed(WifiNetworkSyncCodec.COMMAND + "\nFINISH_SETUP"));
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_C, IdsApplicationRoute.forTopic(WifiNetworkSyncCodec.TOPIC).idsProtectionClass);
    }
    private static void refused(String config, String status, String scan) {
        try { AndroidWifiNetwork.select(xml(config), status, scan); fail("Unsupported network accepted"); }
        catch (IllegalArgumentException expected) {
            assertEquals("Current Wi-Fi network cannot be shared", expected.getMessage());
            assertNull(expected.getCause());
        }
    }
    @Test public void disconnectedRefused() { refused(CONFIG, STATUS.replace("COMPLETED", "DISCONNECTED"), SCAN); }
    @Test public void captiveOrUnvalidatedRefused() { refused(CONFIG, STATUS.replace("&VALIDATED", ""), SCAN); }
    @Test public void enterpriseRefused() { refused(CONFIG.replace(">02<", ">04<"), STATUS, SCAN); }
    @Test public void saeRefused() { refused(CONFIG, STATUS.replace("Security type: 2,", "Security type: 4,"), SCAN); }
    @Test public void missingActualScanRefused() { refused(CONFIG, STATUS, SCAN.replace("00:11:22:33:44:55", "00:11:22:33:44:66")); }
    @Test public void mixedCipherRefused() { refused(CONFIG, STATUS, SCAN.replace("CCMP-128", "CCMP-128+TKIP")); }
    @Test public void hiddenRefused() { refused(CONFIG.replace("value=\"false\"", "value=\"true\""), STATUS, SCAN); }
    @Test public void unknownSsidRefused() { refused(CONFIG.replace("Test café", "Different"), STATUS, SCAN); }
    @Test public void duplicateNetworkRefused() {
        String record = CONFIG.substring(CONFIG.indexOf("<Network>"), CONFIG.indexOf("</Network>") + 10);
        refused(CONFIG.replace("</NetworkList>", record + "</NetworkList>"), STATUS, SCAN);
    }
    @Test public void duplicateSecretRefused() {
        refused(CONFIG.replace("</WifiConfiguration>", "<string name=\"PreSharedKey\">secret</string></WifiConfiguration>"), STATUS, SCAN);
    }
    @Test public void externalEntityRefusedWithoutLogging() {
        refused("<!DOCTYPE WifiConfigStoreData [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>" + CONFIG, STATUS, SCAN);
    }
    @Test public void malformedXmlRefused() { refused(CONFIG.substring(0, 30), STATUS, SCAN); }
    @Test public void wideConfigRefused() {
        try { AndroidWifiNetwork.select(new byte[AndroidWifiNetwork.MAX_CONFIG + 1], STATUS, SCAN); fail(); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("Test")); }
    }
    @Test public void invalidCredentialsRefusedByCodec() {
        for (String password : List.of("short", "x".repeat(64), "password\n", "пароль123")) {
            try { WifiNetworkSyncCodec.addWpa2("test", password); fail(); } catch (IllegalArgumentException expected) { }
        }
        try { WifiNetworkSyncCodec.addWpa2("é".repeat(17), "testpass123"); fail(); } catch (IllegalArgumentException expected) { }
    }
}
