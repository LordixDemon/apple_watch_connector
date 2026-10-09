package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;

public class NanoSystemSettingsDiagnosticsTest {
    private static final String REQUEST = "00112233-4455-6677-8899-aabbccddeeff";

    @Test public void readsLegacyAndCategorizedPathsWithoutLoggingTheirValues() {
        var client = new NanoSystemSettingsDiagnostics();
        client.queued(24, REQUEST);
        byte[] category = concat(new byte[]{8, 2}, text(2, "/private/var/mobile/sysdiagnose"),
                text(3, "private-device-sysdiagnose.tar.gz"), text(3, "private-app.ips"));
        byte[] payload = concat(text(1, "old-private-name.ips"), field(2, category), new byte[]{0x78, 1});
        String result = client.response(25, true, REQUEST, payload);
        assertNotNull(result);
        assertTrue(result.contains("files=3"));
        assertTrue(result.contains("categories={2=2}"));
        assertFalse(result.contains("private-device"));
        assertFalse(result.contains("private-app"));
        assertFalse(result.contains("old-private-name"));
        assertEquals(3, client.files().size());
        assertEquals(2, client.files().get(1).category);
        assertEquals("/private/var/mobile/sysdiagnose", client.files().get(1).directory);
        assertThrows(UnsupportedOperationException.class, () -> client.files().clear());
    }

    @Test public void requiresMatchingRequestTypeIdentifierAndResponseBit() {
        var client = new NanoSystemSettingsDiagnostics();
        client.queued(24, REQUEST);
        assertNull(client.response(25, true, UUID.randomUUID().toString(), new byte[0]));
        assertNull(client.response(25, false, REQUEST, new byte[0]));
        assertNull(client.response(6, true, REQUEST, new byte[0]));
        assertNotNull(client.response(25, true, REQUEST, new byte[0]));
        assertNull(client.response(25, true, REQUEST, new byte[0]));
        client.queued(24, REQUEST);
        client.reset();
        assertNull(client.response(25, true, REQUEST, new byte[0]));
        assertTrue(client.files().isEmpty());
    }

    @Test public void malformedOrExcessiveResponseCannotReplacePreviousInventory() {
        var client = new NanoSystemSettingsDiagnostics();
        client.queued(24, REQUEST);
        client.response(25, true, REQUEST, text(1, "known.ips"));
        for (byte[] invalid : new byte[][]{new byte[]{10, 5, 1}, new byte[]{10, 1, (byte)255},
                new byte[]{8, 1}, new byte[]{0}, text(1, "bad\npath"), new byte[256 * 1024 + 1]}) {
            client.queued(24, REQUEST);
            assertThrows(IllegalArgumentException.class, () -> client.response(25, true, REQUEST, invalid));
            assertEquals("known.ips", client.files().get(0).path);
        }
        ByteArrayOutputStream oversized = new ByteArrayOutputStream();
        for (int i = 0; i < 1025; i++) oversized.writeBytes(text(1, "x"));
        client.queued(24, REQUEST);
        assertThrows(IllegalArgumentException.class,
                () -> client.response(25, true, REQUEST, oversized.toByteArray()));
        assertEquals("known.ips", client.files().get(0).path);
    }

    @Test public void requestSurfaceIsReadOnlyAndAboutValuesStayPrivate() {
        for (int type : new int[]{5, 24}) NanoSystemSettingsDiagnostics.requireReadOnlyRequest(type, new byte[0]);
        for (int type : new int[]{7, 8, 15, 19, 26, 27}) {
            assertThrows(IllegalArgumentException.class,
                    () -> NanoSystemSettingsDiagnostics.requireReadOnlyRequest(type, new byte[0]));
        }
        assertThrows(IllegalArgumentException.class,
                () -> NanoSystemSettingsDiagnostics.requireReadOnlyRequest(24, new byte[]{1}));
        var route = IdsApplicationRoute.forTopic(NanoSystemSettingsDiagnostics.TOPIC);
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_D, route.idsProtectionClass);
        assertEquals(300, route.idsPriority);
        var client = new NanoSystemSettingsDiagnostics();
        client.queued(5, REQUEST);
        String result = client.response(6, true, REQUEST, new byte[]{8, 42});
        assertTrue(result.contains("fields={1=1}"));
        assertFalse(result.contains("private-serial"));
    }

    @Test public void aboutScalarsFollowNativeFieldNumbersAndAbsenceRemainsUnknown() {
        var decoded = NanoSystemSettingsDiagnostics.decodeAbout(new byte[]{
                8, 42, 16, 3, 24, 4, 32, 5, 40, 99, 48, 1, 56, 7, 64, 8, 72, 1});
        assertEquals(Long.valueOf(42), decoded.availableStorageBytes());
        assertEquals(Long.valueOf(3), decoded.apps());
        assertEquals(Long.valueOf(4), decoded.songs());
        assertEquals(Long.valueOf(5), decoded.photos());
        assertEquals(Long.valueOf(99), decoded.batteryCapacity());
        assertEquals(Boolean.TRUE, decoded.charging());
        assertEquals(Long.valueOf(7), decoded.purgeableBytes());
        assertEquals(Long.valueOf(8), decoded.userDeletableBytes());
        assertNull(NanoSystemSettingsDiagnostics.decodeAbout(new byte[0]).batteryCapacity());
        assertNull(NanoSystemSettingsDiagnostics.decodeAbout(new byte[]{40, 0}).charging());
        assertEquals(Boolean.FALSE, NanoSystemSettingsDiagnostics.decodeAbout(new byte[]{48, 0}).charging());
    }

    @Test public void invalidAboutCannotReplaceAnObservationAndResetDiscardsIt() {
        var client = new NanoSystemSettingsDiagnostics();
        client.queued(5, REQUEST);
        assertNull(client.response(6, false, REQUEST, new byte[]{40, 99}));
        assertNull(client.about());
        client.response(6, true, REQUEST, new byte[]{40, 99, 48, 1});
        assertEquals(Long.valueOf(99), client.about().batteryCapacity());
        for (byte[] invalid : new byte[][]{new byte[]{40}, new byte[]{40, 1, 40, 2},
                new byte[]{48, 2}, new byte[]{42, 0}, new byte[]{40, (byte)128}}) {
            client.queued(5, REQUEST);
            assertThrows(IllegalArgumentException.class, () -> client.response(6, true, REQUEST, invalid));
            assertEquals(Long.valueOf(99), client.about().batteryCapacity());
        }
        client.reset();
        assertNull(client.about());
        assertNull(client.response(6, true, REQUEST, new byte[]{40, 0}));
    }

    private static byte[] text(int number, String value) {
        return field(number, value.getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] field(int number, byte[] value) {
        var out = new ByteArrayOutputStream();
        out.write((number << 3) | 2);
        int length = value.length;
        while (length >= 128) { out.write((length & 127) | 128); length >>>= 7; }
        out.write(length); out.writeBytes(value); return out.toByteArray();
    }
    private static byte[] concat(byte[]... values) {
        var out = new ByteArrayOutputStream();
        for (var value : values) out.writeBytes(value);
        return out.toByteArray();
    }
}
