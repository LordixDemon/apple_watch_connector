package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class IdsCredentialsDiagnosticsDeviceInfoTest {

    @Test
    public void logsWatchDescriptorValues() {
        Map<String, Object> privateData = new LinkedHashMap<>();
        privateData.put("v", "1");
        privateData.put("pn", "Watch7,5");
        privateData.put("pv", "26.2");
        privateData.put("pb", "23S303");
        privateData.put("u", "F0E1D2C3-B4A5-4678-90AB-CDEF01234567");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("command", 12L);
        message.put("device-name", "Watch");
        message.put("hardware-version", "Watch7,5");
        message.put("identifier", "F0E1D2C3-B4A5-4678-90AB-CDEF01234567");
        message.put("unique-id", "00112233-4455-6677-8899-AABBCCDDEEFF");
        message.put("success", Boolean.TRUE);
        message.put("encryption-key", new byte[249]);
        message.put("private-device-data", privateData);

        byte[] payload = AppleBinaryPropertyList.encode(message);
        String description = IdsCredentialsDiagnostics.describeDeviceInfoValues(payload);
        assertTrue(description, description.startsWith("DEVICE INFO VALUES:"));
        assertTrue(description, description.contains("\"Watch7,5\""));
        assertTrue(description, description.contains("\"26.2\""));
        assertTrue(description, description.contains("\"23S303\""));
        assertTrue(description, description.contains("F0E1D2C3-B4A5-4678-90AB-CDEF01234567"));
        assertTrue(description, description.contains("success=true"));
    }

    @Test
    public void expandsNestedBplistPrivateData() {
        Map<String, String> privateData = new LinkedHashMap<>();
        privateData.put("pn", "Watch7,5");
        privateData.put("pb", "23S303");
        byte[] packed = AppleBinaryPropertyList.encode(privateData);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("command", 12L);
        message.put("private-device-data", packed);

        String description = IdsCredentialsDiagnostics.describeDeviceInfoValues(
                AppleBinaryPropertyList.encode(message));
        assertTrue(description, description.contains("\"23S303\""));
        assertTrue(description, description.contains("\"Watch7,5\""));
    }

    @Test
    public void ignoresOtherCommands() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("command", 11L);
        message.put("unique-id", "00112233-4455-6677-8899-AABBCCDDEEFF");
        assertNull(IdsCredentialsDiagnostics.describeDeviceInfoValues(
                AppleBinaryPropertyList.encode(message)));
    }

    @Test
    public void rejectsNonBplist() {
        assertNull(IdsCredentialsDiagnostics.describeDeviceInfoValues(new byte[]{1, 2, 3}));
        assertNull(IdsCredentialsDiagnostics.describeDeviceInfoValues(null));
    }
}
