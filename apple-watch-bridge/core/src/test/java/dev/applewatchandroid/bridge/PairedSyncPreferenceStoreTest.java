package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

public final class PairedSyncPreferenceStoreTest {
    @Test
    public void newerKeyReplacesOlderAndSurvivesReload() throws Exception {
        java.io.File dir = Files.createTempDirectory("paired-sync").toFile();
        java.io.File file = new java.io.File(dir, "prefs.v1");
        PairedSyncPreferenceStore.setStoreFileForTesting(file);
        try {
            PairedSyncPreferenceStore.Result first = PairedSyncPreferenceStore.merge(message(
                    "com.apple.Carousel", "activeFace", new byte[] {1, 2}, 10.0));
            assertEquals(1, first.accepted);
            assertEquals(1, first.entries);
            PairedSyncPreferenceStore.merge(message(
                    "com.apple.Carousel", "activeFace", new byte[] {9}, 4.0));
            assertEquals(2, PairedSyncPreferenceStore.valueLengthForTesting(
                    "com.apple.Carousel", "activeFace"));
            PairedSyncPreferenceStore.Result replaced = PairedSyncPreferenceStore.merge(message(
                    "com.apple.Carousel", "activeFace", new byte[] {3, 4, 5}, 11.0));
            assertEquals(1, replaced.accepted);
            assertEquals(3, PairedSyncPreferenceStore.valueLengthForTesting(
                    "com.apple.Carousel", "activeFace"));

            PairedSyncPreferenceStore.setStoreFileForTesting(file);
            PairedSyncPreferenceStore.Result reloaded = PairedSyncPreferenceStore.merge(message(
                    "com.apple.NanoControlCenter", "buttonOrder", new byte[] {7}, 12.0));
            assertEquals(2, reloaded.entries);
            assertEquals(2, reloaded.domainCount);
            assertEquals(3, PairedSyncPreferenceStore.valueLengthForTesting(
                    "com.apple.Carousel", "activeFace"));
        } finally {
            PairedSyncPreferenceStore.setStoreFileForTesting(null);
        }
    }

    @Test
    public void twoWayPayloadOmitsOneWayKeysAndRoundTrips() throws Exception {
        java.io.File dir = Files.createTempDirectory("paired-sync-mirror").toFile();
        java.io.File file = new java.io.File(dir, "prefs.v1");
        PairedSyncPreferenceStore.setStoreFileForTesting(file);
        try {
            PairedSyncPreferenceStore.merge(message(
                    "com.apple.Carousel", "oneWay", new byte[] {1}, 3.0));
            PairedSyncPreferenceStore.merge(new PairedSyncCodec.UserDefaultsMessage(
                    8.0,
                    "com.apple.Accessibility",
                    List.of(new PairedSyncCodec.UserDefaultsKey(
                            "ZoomTouchEnabled", new byte[] {9, 8}, Boolean.TRUE, 8.0)),
                    false));
            List<byte[]> payloads = PairedSyncPreferenceStore.twoWayPayloads();
            assertEquals(1, payloads.size());
            PairedSyncCodec.UserDefaultsMessage decoded =
                    PairedSyncCodec.decodeInbound(payloads.get(0));
            try {
                assertEquals("com.apple.Accessibility", decoded.domain);
                assertEquals(1, decoded.keys().size());
                PairedSyncCodec.UserDefaultsKey key = decoded.keys().get(0);
                assertEquals("ZoomTouchEnabled", key.key);
                assertArrayEquals(new byte[] {9, 8}, key.value());
                assertEquals(Boolean.TRUE, key.twoWaySync);
            } finally {
                decoded.destroy();
                for (byte[] payload : payloads) {
                    java.util.Arrays.fill(payload, (byte) 0);
                }
            }
            assertTrue(PairedSyncCodec.isInboundUserDefaults(2));
        } finally {
            PairedSyncPreferenceStore.setStoreFileForTesting(null);
        }
    }

    @Test public void globalColorFavoritesNeverReplayButOtherTwoWayKeysRemain() throws Exception {
        java.io.File dir = Files.createTempDirectory("unscoped-colors").toFile();
        java.io.File file = new java.io.File(dir, "prefs.v1");
        PairedSyncPreferenceStore.setStoreFileForTesting(file);
        try {
            var colors = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.KEY,
                    BinaryPropertyListCodec.encodeStringArray(List.of("unknown.otherPair")), true, 10.0);
            var other = new PairedSyncCodec.UserDefaultsKey("unrelatedSetting", new byte[]{9}, true, 11.0);
            var message = new PairedSyncCodec.UserDefaultsMessage(11.0, PigmentPreferenceCodec.DOMAIN, List.of(colors, other), false);
            try { PairedSyncPreferenceStore.merge(message); }
            finally { colors.destroy(); other.destroy(); message.destroy(); }
            // The historical bytes are retained; exclusion applies to outbound planning.
            PairedSyncPreferenceStore.setStoreFileForTesting(file);
            List<byte[]> payloads = PairedSyncPreferenceStore.twoWayPayloads();
            assertEquals(1, payloads.size());
            var decoded = PairedSyncCodec.decodeInbound(payloads.get(0)); var keys = decoded.keys();
            try {
                assertEquals(PigmentPreferenceCodec.DOMAIN, decoded.domain);
                assertEquals(1, keys.size()); assertEquals("unrelatedSetting", keys.get(0).key);
                assertEquals(11.0, keys.get(0).timestamp, 0); assertArrayEquals(new byte[]{9}, keys.get(0).value());
            } finally { decoded.destroy(); keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); payloads.forEach(p -> java.util.Arrays.fill(p, (byte) 0)); }
        } finally { PairedSyncPreferenceStore.setStoreFileForTesting(null); Files.deleteIfExists(file.toPath()); Files.deleteIfExists(dir.toPath()); }
    }

    @Test public void aColorOnlyGlobalDomainProducesNoAutomaticOutboundPacket() throws Exception {
        java.io.File dir = Files.createTempDirectory("unscoped-color-only").toFile();
        java.io.File file = new java.io.File(dir, "prefs.v1");
        PairedSyncPreferenceStore.setStoreFileForTesting(file);
        try {
            var key = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.KEY,
                    BinaryPropertyListCodec.encodeStringArray(List.of("unknown.otherPair")), true, 10.0);
            var message = new PairedSyncCodec.UserDefaultsMessage(10.0, PigmentPreferenceCodec.DOMAIN, List.of(key), false);
            try { PairedSyncPreferenceStore.merge(message); }
            finally { key.destroy(); message.destroy(); }
            assertTrue(PairedSyncPreferenceStore.twoWayPayloads().isEmpty());
        } finally { PairedSyncPreferenceStore.setStoreFileForTesting(null); Files.deleteIfExists(file.toPath()); Files.deleteIfExists(dir.toPath()); }
    }

    @Test public void automaticSelectionsNeverReplayAfterReloadAndUnrelatedDomainsRemain() throws Exception {
        java.io.File dir = Files.createTempDirectory("unscoped-auto-colors").toFile();
        java.io.File file = new java.io.File(dir, "prefs.v1");
        PairedSyncPreferenceStore.setStoreFileForTesting(file);
        try {
            var automatic = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.AUTO_KEY,
                    BinaryPropertyListCodec.encodeStringArray(List.of("unknown.previousPair")), true, 90.0);
            var other = new PairedSyncCodec.UserDefaultsKey("unrelatedSetting", new byte[]{7}, true, 12.0);
            var message = new PairedSyncCodec.UserDefaultsMessage(90.0, PigmentPreferenceCodec.DOMAIN,
                    List.of(automatic, other), false);
            try { PairedSyncPreferenceStore.merge(message); }
            finally { automatic.destroy(); other.destroy(); message.destroy(); }
            var foreign = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.AUTO_KEY,
                    new byte[]{8}, true, 13.0);
            var foreignMessage = new PairedSyncCodec.UserDefaultsMessage(13.0, "com.apple.other", List.of(foreign), false);
            try { PairedSyncPreferenceStore.merge(foreignMessage); }
            finally { foreign.destroy(); foreignMessage.destroy(); }
            PairedSyncPreferenceStore.setStoreFileForTesting(file);
            List<byte[]> payloads = PairedSyncPreferenceStore.twoWayPayloads();
            try {
                assertTrue(PairedSyncPreferenceStore.valueLengthForTesting(
                        PigmentPreferenceCodec.DOMAIN, PigmentPreferenceCodec.AUTO_KEY) > 0);
                assertEquals(2, payloads.size());
                for (byte[] payload : payloads) {
                    var decoded = PairedSyncCodec.decodeInbound(payload); var keys = decoded.keys();
                    try {
                        assertEquals(1, keys.size());
                        if (PigmentPreferenceCodec.DOMAIN.equals(decoded.domain)) {
                            assertEquals("unrelatedSetting", keys.get(0).key);
                            assertEquals(12.0, decoded.timestamp, 0);
                        } else {
                            assertEquals("com.apple.other", decoded.domain);
                            assertEquals(PigmentPreferenceCodec.AUTO_KEY, keys.get(0).key);
                            assertEquals(13.0, decoded.timestamp, 0);
                        }
                    } finally { decoded.destroy(); keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); }
                }
            } finally { payloads.forEach(p -> java.util.Arrays.fill(p, (byte) 0)); }
        } finally { PairedSyncPreferenceStore.setStoreFileForTesting(null); Files.deleteIfExists(file.toPath()); Files.deleteIfExists(dir.toPath()); }
    }

    @Test public void automaticOnlyGlobalDomainCannotProduceAnOutboundPacket() throws Exception {
        java.io.File dir = Files.createTempDirectory("unscoped-auto-only").toFile();
        java.io.File file = new java.io.File(dir, "prefs.v1");
        PairedSyncPreferenceStore.setStoreFileForTesting(file);
        try {
            var key = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.AUTO_KEY,
                    BinaryPropertyListCodec.encodeStringArray(List.of("unknown.previousPair")), true, 90.0);
            var message = new PairedSyncCodec.UserDefaultsMessage(90.0, PigmentPreferenceCodec.DOMAIN, List.of(key), false);
            try { PairedSyncPreferenceStore.merge(message); }
            finally { key.destroy(); message.destroy(); }
            PairedSyncPreferenceStore.setStoreFileForTesting(file);
            assertTrue(PairedSyncPreferenceStore.twoWayPayloads().isEmpty());
        } finally { PairedSyncPreferenceStore.setStoreFileForTesting(null); Files.deleteIfExists(file.toPath()); Files.deleteIfExists(dir.toPath()); }
    }

    private static PairedSyncCodec.UserDefaultsMessage message(
            String domain, String key, byte[] value, double timestamp) {
        return new PairedSyncCodec.UserDefaultsMessage(
                timestamp,
                domain,
                List.of(new PairedSyncCodec.UserDefaultsKey(key, value, null, timestamp)),
                false);
    }
}
