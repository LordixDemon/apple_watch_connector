package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class IdsCredentialsDiagnosticsTest {
    @Test
    public void nestedAccountSyncReportsOnlyKnownSelectorAndShape() {
        byte[] payload = AppleBinaryPropertyList.encode(java.util.Map.of("command", 15L,
                "sync-payload", java.util.Map.of("command", 5L, "secret@example.invalid", "private-value",
                        "sps-phone-numbers", java.util.List.of("+12025550123"), "sps-device-udid", IdsSpsCompanionInfoTest.UDID)));
        String description = IdsCredentialsDiagnostics.describe(payload);
        assertTrue(description.contains("command=command(15)"));
        assertTrue(description.contains("sync-payload=dictionary(4)"));
        assertTrue(description.contains("sync-payload.command=command(5)"));
        assertFalse(description.contains("secret"));
        assertFalse(description.contains("private-value"));
        assertTrue(description.contains("sps-phone-numbers=array(1)"));
        assertTrue(description.contains("sps-device-udid=string("));
        assertFalse(description.contains("1202555"));
        assertFalse(description.contains(IdsSpsCompanionInfoTest.UDID));
    }

    @Test
    public void serviceDiscoveryShowsOnlyBoundedAppleTopics() {
        String description = IdsCredentialsDiagnostics.describe(AppleBinaryPropertyList.encode(
                java.util.Map.of("command",17L,"serviceTypes",java.util.List.of("com.apple.private.alloy.bluetoothregistry", "person@example.invalid"))));
        assertTrue(description.contains("com.apple.private.alloy.bluetoothregistry"));
        assertTrue(description.contains("redacted-service"));
        assertFalse(description.contains("person@"));
    }

    @Test
    public void remoteAccountInventoryDoesNotExposeAccountContents() {
        String description = IdsCredentialsDiagnostics.describe(AppleBinaryPropertyList.encode(
                java.util.Map.of("command",18L,"accountMap",java.util.Map.of(
                        "com.apple.private.alloy.bluetoothregistry",java.util.List.of(java.util.Map.of("secret", "private@example.invalid"))))));
        assertTrue(description.contains("bluetoothregistry=array(1)"));
        assertFalse(description.contains("secret"));
        assertFalse(description.contains("private@"));
    }
    @Test
    public void describesTypesWithoutCredentialOrIdentifierValues() throws Exception {
        byte[] payload;
        try (java.io.InputStream source = getClass().getResourceAsStream(
                "/ids/credentials-diagnostics.bplist")) {
            assertNotNull(source);
            payload = source.readAllBytes();
        }
        String description = IdsCredentialsDiagnostics.describe(payload);
        assertTrue(description.contains("type=command(3)"));
        assertTrue(description.contains("token=bytes(4)"));
        assertTrue(description.contains("nested=dictionary(1)"));
        assertFalse(description.contains("private@example.invalid"));
        assertFalse(description.contains("+380123456789"));
        assertFalse(description.contains("sensitive-user-key"));
        assertFalse(description.contains("secret"));
    }

    @Test
    public void unknownAndMalformedPayloadsAreBoundedAndDoNotThrow() {
        assertEquals("non-bplist bytes=3", IdsCredentialsDiagnostics.describe(new byte[3]));
        assertEquals("invalid-bplist bytes=8", IdsCredentialsDiagnostics.describe(
                "bplist00".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals("oversized bytes=65537", IdsCredentialsDiagnostics.describe(new byte[65537]));
    }
}
