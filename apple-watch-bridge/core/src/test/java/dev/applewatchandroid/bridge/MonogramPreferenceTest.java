package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.*;
import static org.junit.Assert.*;

public class MonogramPreferenceTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final double STAMP = NOW / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS;
    private static final UUID PAIR = UUID.randomUUID(), EPOCH = UUID.randomUUID();

    static byte[] frame(String domain, String key, byte[] value, Double stamp) {
        var item = new PairedSyncCodec.UserDefaultsKey(key, value, true, stamp);
        var message = new PairedSyncCodec.UserDefaultsMessage(STAMP, domain, List.of(item), false);
        try { return PairedSyncCodec.encode(message); }
        finally { message.destroy(); item.destroy(); }
    }
    private static byte[] textFrame(String text, Double stamp) {
        return frame(MonogramPreferenceCodec.DOMAIN, MonogramPreferenceCodec.KEY,
                text == null ? null : BinaryPropertyListCodec.encodeStringRoot(text), stamp);
    }

    @Test public void independentBinaryPlistStringRootsMatchAppleRepresentation() {
        String[] text = {"AB", "É", "你好", "𐐀"};
        String[] base64 = {
                "YnBsaXN0MDBSQUIIAAAAAAAAAQEAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAs=",
                "YnBsaXN0MDBhAMkIAAAAAAAAAQEAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAs=",
                "YnBsaXN0MDBiT2BZfQgAAAAAAAABAQAAAAAAAAABAAAAAAAAAAAAAAAAAAAADQ==",
                "YnBsaXN0MDBi2AHcAAgAAAAAAAABAQAAAAAAAAABAAAAAAAAAAAAAAAAAAAADQ=="};
        for (int i = 0; i < text.length; i++) {
            byte[] bytes = Base64.getDecoder().decode(base64[i]);
            assertEquals(text[i], BinaryPropertyListCodec.decodeStringRoot(bytes));
            assertArrayEquals(bytes, BinaryPropertyListCodec.encodeStringRoot(text[i]));
            assertThrows(IllegalArgumentException.class,
                    () -> BinaryPropertyListCodec.decodeBoolean(bytes));
        }
        assertThrows(IllegalArgumentException.class,
                () -> BinaryPropertyListCodec.decodeStringRoot(BinaryPropertyListCodec.encodeBoolean(false)));
    }

    @Test public void nativeTextDeletionAndMalformedReportsStayDistinct() {
        assertEquals("AB", MonogramPreferenceCodec.decodeObserved(textFrame("AB", null), NOW).get(0).text());
        assertNull(MonogramPreferenceCodec.decodeObserved(textFrame(null, STAMP), NOW).get(0).text());
        for (String bad : List.of("", "ABCDEF", "😀", "1️⃣", "©︎")) {
            assertTrue(MonogramPreferenceCodec.decodeObserved(textFrame(bad, STAMP), NOW).isEmpty());
        }
        assertTrue(MonogramPreferenceCodec.decodeObserved(textFrame("AB", STAMP + 6), NOW).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> textFrame("AB", Double.NaN));
        assertTrue(MonogramPreferenceCodec.decodeObserved(frame("foreign", MonogramPreferenceCodec.KEY,
                BinaryPropertyListCodec.encodeStringRoot("AB"), STAMP), NOW).isEmpty());
        assertTrue(MonogramPreferenceCodec.decodeObserved(frame(MonogramPreferenceCodec.DOMAIN, "CustomMonogram",
                BinaryPropertyListCodec.encodeStringRoot("AB"), STAMP), NOW).isEmpty());
    }

    @Test public void allNativeCharacterSetObservationsMatchJavaValidation() throws Exception {
        String json;
        try (var stream = getClass().getResourceAsStream("/native-monogram-rules-70.json")) {
            assertNotNull(stream);
            json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matcher = Pattern.compile("\\[(\\d+),(\\d+)\\]").matcher(json);
        var nativeEmoji = new BitSet(0x110000);
        int ranges = 0, checked = 0;
        while (matcher.find()) {
            nativeEmoji.set(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)) + 1);
            ranges++;
        }
        assertEquals(157, ranges);
        assertEquals(1431, nativeEmoji.cardinality());
        for (int scalar = 0; scalar < 0x110000; scalar++) {
            if (scalar >= 0xd800 && scalar <= 0xdfff) continue;
            String text = new String(Character.toChars(scalar));
            if (NativeMonogramTextRules.valid(text) == nativeEmoji.get(scalar)) fail("Native scalar mismatch: " + scalar);
            checked++;
        }
        assertEquals(1112064, checked);
        assertFalse(NativeMonogramTextRules.valid("\ud800"));
        assertFalse(NativeMonogramTextRules.valid("ééé"));
        assertTrue(NativeMonogramTextRules.valid("AB CD"));
    }

    @Test public void onlyOwnedTypeZeroReportsEstablishTextAndSourceOrdering() {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        dispatcher.updateConnectionState(false, "test");
        dispatcher.updateConnectionState(true, "test");
        try {
            byte[] payload = textFrame("AB", STAMP);
            for (String service : List.of(IdsApplicationRoute.PREFERENCE_SYNC_SERVICE, PairedSyncCodec.PREFERRED_SERVICE, "unrelated")) {
                for (int type : new int[]{0, 2}) for (boolean response : new boolean[]{false, true}) {
                    dispatcher.updateConnectionState(false, "test");
                    dispatcher.updateConnectionState(true, "test");
                    try (var event = new BridgeApplicationEventCodec.Event(service, type, response, payload)) {
                        dispatcher.observeNativePreferences(event, PAIR, EPOCH, NOW);
                    }
                    assertEquals(type == 0 && !response && !service.equals("unrelated"),
                            dispatcher.monogramPreferences(PAIR, EPOCH) != null);
                }
            }
            var observation = new MonogramPreferenceObservation();
            assertTrue(observation.observe(PAIR, EPOCH, List.of(new MonogramPreferenceCodec.Report("AB", STAMP)), NOW));
            assertFalse(observation.observe(PAIR, EPOCH, List.of(new MonogramPreferenceCodec.Report("CD", STAMP)), NOW + 1));
            assertFalse(observation.observe(PAIR, EPOCH, List.of(new MonogramPreferenceCodec.Report("CD", STAMP - 1)), NOW + 2));
            assertTrue(observation.observe(PAIR, EPOCH, List.of(new MonogramPreferenceCodec.Report(null, STAMP + 1)), NOW + 1000));
            assertNull(observation.snapshot(PAIR, EPOCH).text());
            assertNull(observation.snapshot(PAIR, UUID.randomUUID()));
            observation.observe(PAIR, UUID.randomUUID(), List.of(), NOW + 2000);
            assertNull(observation.snapshot(PAIR, EPOCH));
        } finally { dispatcher.updateConnectionState(false, "test"); }
        assertNull(dispatcher.monogramPreferences(PAIR, EPOCH));
    }

    @Test public void oldUnscopedCustomTextCannotReplayIntoAnotherPair() throws Exception {
        var file = Files.createTempFile("monogram-cache", ".bin").toFile();
        PairedSyncPreferenceStore.setStoreFileForTesting(file);
        var key = new PairedSyncCodec.UserDefaultsKey(MonogramPreferenceCodec.KEY,
                BinaryPropertyListCodec.encodeStringRoot("AB"), true, STAMP);
        var other = new PairedSyncCodec.UserDefaultsKey("unrelated", BinaryPropertyListCodec.encodeBoolean(true), true, STAMP);
        var message = new PairedSyncCodec.UserDefaultsMessage(STAMP, MonogramPreferenceCodec.DOMAIN, List.of(key, other), false);
        try {
            PairedSyncPreferenceStore.merge(message);
            var payloads = PairedSyncPreferenceStore.twoWayPayloads();
            assertEquals(1, payloads.size());
            var restored = PairedSyncCodec.decodeInbound(payloads.get(0));
            var keys = restored.keys();
            try { assertEquals(1, keys.size()); assertEquals("unrelated", keys.get(0).key); }
            finally { keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); restored.destroy(); }
        } finally {
            message.destroy(); key.destroy(); other.destroy();
            PairedSyncPreferenceStore.setStoreFileForTesting(null);
            Files.deleteIfExists(file.toPath());
        }
    }
}
