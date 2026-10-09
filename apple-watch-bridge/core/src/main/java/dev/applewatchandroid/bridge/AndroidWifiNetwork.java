package dev.applewatchandroid.bridge;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Bounded Android config selection. Diagnostic strings never include SSIDs or passwords. */
final class AndroidWifiNetwork {
    static final int MAX_CONFIG = 1024 * 1024;
    private final String ssid, password;
    private AndroidWifiNetwork(String ssid, String password) { this.ssid = ssid; this.password = password; }
    @Override public String toString() { return "AndroidWifiNetwork[credentials withheld]"; }
    byte[] archive() { return WifiNetworkSyncCodec.addWpa2(ssid, password); }

    /** Only the connected, Internet-validated, broadcast WPA2-PSK/CCMP network is shared. */
    static AndroidWifiNetwork select(byte[] xml, String status, String scan) {
        String current = currentSsid(status);
        String bssid = currentBssid(status);
        if (!status.contains("Security type: 2,") || !status.contains("Supplicant state: COMPLETED")
                || !status.contains("&VALIDATED") || !status.contains("&INTERNET")) fail();
        boolean supportedScan = false;
        for (String line : scan.split("\n")) {
            if (line.stripLeading().startsWith(bssid + " ")) {
                // Mixed TKIP/SAE/EAP suites need their actual IE; never invent them.
                supportedScan = line.contains("[WPA2-PSK-CCMP-128]")
                        && !line.contains("TKIP") && !line.contains("SAE") && !line.contains("EAP");
                break;
            }
        }
        if (!supportedScan || xml == null || xml.length == 0 || xml.length > MAX_CONFIG) fail();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Android's Harmony factory lacks Xerces feature flags. Reject DTDs
            // before parsing; the platform parser cannot resolve an undeclared entity.
            rejectDeclarations(xml);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            // Suppress parser diagnostics, which can echo secret XML into stderr.
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var document = builder.parse(new ByteArrayInputStream(xml));
            var networks = document.getElementsByTagName("Network");
            AndroidWifiNetwork match = null;
            for (int i = 0; i < networks.getLength(); i++) {
                Element network = (Element) networks.item(i);
                var configs = network.getElementsByTagName("WifiConfiguration");
                if (configs.getLength() != 1) fail();
                Map<String, Element> values = new HashMap<>();
                for (var node = configs.item(0).getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element element && !element.getAttribute("name").isEmpty()) {
                        if (values.put(element.getAttribute("name"), element) != null) fail();
                    }
                }
                Element ssid = values.get("SSID");
                if (ssid == null || !ssid.getTagName().equals("string") || !quoted(ssid.getTextContent()).equals(current)) continue;
                if (match != null) fail();
                Element psk = values.get("PreSharedKey"), key = values.get("AllowedKeyMgmt"), hidden = values.get("HiddenSSID");
                if (psk == null || !psk.getTagName().equals("string") || key == null
                        || !key.getTagName().equals("byte-array") || !key.getTextContent().strip().equalsIgnoreCase("02")
                        || hidden == null || !hidden.getTagName().equals("boolean")
                        || !hidden.getAttribute("value").equals("false")) fail();
                match = new AndroidWifiNetwork(current, quoted(psk.getTextContent()));
            }
            if (match == null) fail();
            return match;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Current Wi-Fi network cannot be shared");
        }
    }
    static String currentSsid(String status) {
        var matcher = Pattern.compile("(?m)^WifiInfo: SSID: \"([^\r\n]*)\", BSSID: ").matcher(status);
        if (!matcher.find()) { fail(); }
        return matcher.group(1);
    }
    static String currentBssid(String status) {
        var matcher = Pattern.compile("(?m)^WifiInfo: SSID: .*?, BSSID: ([0-9a-f:]{17}),").matcher(status);
        if (!matcher.find()) { fail(); }
        return matcher.group(1);
    }
    private static String quoted(String value) {
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            fail();
        }
        return value.substring(1, value.length() - 1);
    }
    private static void rejectDeclarations(byte[] xml) {
        for (byte value : xml) if (value == 0) fail(); // UTF-8 config only, no UTF-16 disguise.
        for (String declaration : new String[]{"<!DOCTYPE", "<!ENTITY"}) {
            byte[] needle = declaration.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            outer: for (int i = 0; i <= xml.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) if (xml[i + j] != needle[j]) continue outer;
                fail();
            }
        }
    }
    private static void fail() { throw new IllegalArgumentException("Current Wi-Fi network cannot be shared"); }
}
