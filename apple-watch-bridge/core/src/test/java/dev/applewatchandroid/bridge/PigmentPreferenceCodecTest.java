package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PigmentPreferenceCodecTest {
    private static final long NOW = 1791253000000L;
    private static final double SOURCE = 812945799;
    private static final UUID PAIR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EPOCH = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final List<String> NAMES = List.of("standard.navyBlue", "zeus.fall2025.bleuHydra");
    // Independent Python plistlib fixtures; these are arrays at the plist root.
    private static final byte[] NATIVE_ARRAY = hex("62706c6973743030a201025f10117374616e646172642e6e617679426c75655f10177a6575732e66616c6c323032352e626c65754879647261080b1f0000000000000101000000000000000300000000000000000000000000000039");
    private static final byte[] EMPTY_ARRAY = hex("62706c6973743030a0080000000000000101000000000000000100000000000000000000000000000009");

    @Test public void readsIndependentRootArrayAndRejectsWrappedOrMixedValues() {
        assertEquals(NAMES, BinaryPropertyListCodec.decodeStringArray(NATIVE_ARRAY));
        assertEquals(List.of(), BinaryPropertyListCodec.decodeStringArray(EMPTY_ARRAY));
        assertArrayEquals(NATIVE_ARRAY, BinaryPropertyListCodec.encodeStringArray(NAMES));
        assertArrayEquals(EMPTY_ARRAY, BinaryPropertyListCodec.encodeStringArray(List.of()));
        assertThrows(IllegalArgumentException.class, () -> BinaryPropertyListCodec.decodeStringArray(
                BinaryPropertyListCodec.encodeDictionary(Map.of("SelectedPigmentList", NAMES))));
        assertThrows(IllegalArgumentException.class, () -> BinaryPropertyListCodec.decodeStringArray(hex(
                "62706c6973743030a10109080a000000000000010100000000000000020000000000000000000000000000000b")));
    }

    @Test public void nativeNpsListIncludesUnknownNamesWithoutCatalogFiltering() {
        var reports = PigmentPreferenceCodec.decodeObserved(report(PigmentPreferenceCodec.DOMAIN,
                PigmentPreferenceCodec.KEY, NATIVE_ARRAY, SOURCE), NOW);
        assertEquals(1, reports.size());
        assertEquals(NAMES, reports.get(0).names());
        assertEquals(SOURCE, reports.get(0).sourceTimestamp(), 0);
        assertThrows(UnsupportedOperationException.class, () -> reports.get(0).names().clear());
    }

    @Test public void aMalformedAutomaticValueDoesNotEraseTheCompleteSelectedReportInTheSinglePass() {
        var selected = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.KEY, NATIVE_ARRAY, true, SOURCE);
        var invalid = new PairedSyncCodec.UserDefaultsKey(PigmentPreferenceCodec.AUTO_KEY,
                BinaryPropertyListCodec.encodeBoolean(true), true, SOURCE);
        var message = new PairedSyncCodec.UserDefaultsMessage(SOURCE, PigmentPreferenceCodec.DOMAIN, List.of(selected, invalid), false);
        byte[] wire = PairedSyncCodec.encode(message);
        try {
            var decoded = PigmentPreferenceCodec.decodeObservedLists(wire, NOW);
            assertEquals(NAMES, decoded.get(PigmentPreferenceCodec.KEY).get(0).names());
            assertTrue(decoded.get(PigmentPreferenceCodec.AUTO_KEY).isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> decoded.clear());
        } finally { message.destroy(); selected.destroy(); invalid.destroy(); java.util.Arrays.fill(wire, (byte) 0); }
    }

    @Test public void deletionIsUnknownButEmptyArrayIsKnownEmpty() {
        var removed = PigmentPreferenceCodec.decodeObserved(report(PigmentPreferenceCodec.DOMAIN,
                PigmentPreferenceCodec.KEY, null, SOURCE), NOW);
        assertNull(removed.get(0).names());
        var empty = PigmentPreferenceCodec.decodeObserved(report(PigmentPreferenceCodec.DOMAIN,
                PigmentPreferenceCodec.KEY, EMPTY_ARRAY, SOURCE), NOW);
        assertEquals(List.of(), empty.get(0).names());
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCodec.encodeChange(
                removed.get(0), Map.of("standard.blue", true), NOW));
    }

    @Test public void wrongDomainOrKeyMalformedDuplicateOrFutureCannotReplaceSelection() {
        assertTrue(PigmentPreferenceCodec.decodeObserved(report("com.apple.nano",
                PigmentPreferenceCodec.KEY, NATIVE_ARRAY, SOURCE), NOW).isEmpty());
        assertTrue(PigmentPreferenceCodec.decodeObserved(report(PigmentPreferenceCodec.DOMAIN,
                "AutoSelectedPigmentList", NATIVE_ARRAY, SOURCE), NOW).isEmpty());
        for (byte[] value : List.of(new byte[]{0}, BinaryPropertyListCodec.encodeBoolean(true),
                BinaryPropertyListCodec.encodeStringArray(List.of("standard.blue", "standard.blue")))) {
            assertTrue(PigmentPreferenceCodec.decodeObserved(report(PigmentPreferenceCodec.DOMAIN,
                    PigmentPreferenceCodec.KEY, value, SOURCE), NOW).isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCodec.decodeObserved(
                report(PigmentPreferenceCodec.DOMAIN, PigmentPreferenceCodec.KEY, NATIVE_ARRAY, SOURCE + 7), NOW));
        assertThrows(IllegalArgumentException.class, () -> new PigmentPreferenceCodec.Report(NAMES, Double.NaN));
    }

    @Test public void observationsDiscardOldTimestampsAndNeverCrossPairOrSession() {
        var observation = new PigmentPreferenceObservation();
        var report = new PigmentPreferenceCodec.Report(NAMES, SOURCE);
        assertTrue(observation.observe(PAIR, EPOCH, List.of(report), NOW));
        assertFalse(observation.observe(PAIR, EPOCH,
                List.of(new PigmentPreferenceCodec.Report(List.of(), SOURCE)), NOW + 1));
        assertEquals(NAMES, observation.snapshot(PAIR, EPOCH).names());
        assertNull(observation.snapshot(UUID.randomUUID(), EPOCH));
        var nextEpoch = UUID.randomUUID();
        assertNull(observation.snapshot(PAIR, nextEpoch));
        assertTrue(observation.observe(PAIR, nextEpoch,
                List.of(new PigmentPreferenceCodec.Report(List.of(), SOURCE - 1)), NOW + 2));
        assertNull(observation.snapshot(PAIR, EPOCH));
        assertEquals(List.of(), observation.snapshot(PAIR, nextEpoch).names());
        assertTrue(observation.observe(PAIR, nextEpoch,
                List.of(new PigmentPreferenceCodec.Report(null, SOURCE)), NOW + 3));
        assertNull(observation.snapshot(PAIR, nextEpoch).names());
        assertFalse(observation.observe(PAIR, nextEpoch, List.of(report), NOW + 4));
        observation.clear();
        assertNull(observation.snapshot(PAIR, nextEpoch));
    }

    @Test public void completeBaselineCopyCannotBeMutatedByCaller() {
        var names = new ArrayList<>(NAMES);
        var report = new PigmentPreferenceCodec.Report(names, SOURCE);
        names.clear();
        assertEquals(NAMES, report.names());
    }

    @Test public void manualDeltaPreservesUnknownNamesAndWireUsesOriginalDomainAndRootArray() {
        var changes = Map.of("standard.navyBlue", false, "standard.blue", true);
        assertEquals(List.of("zeus.fall2025.bleuHydra", "standard.blue"),
                PigmentPreferenceCodec.merge(NAMES, changes));
        var payload = PigmentPreferenceCodec.encodeChange(new PigmentPreferenceCodec.Report(NAMES, SOURCE), changes, NOW);
        var message = PairedSyncCodec.decodeInbound(payload);
        var keys = message.keys();
        try {
            assertEquals("com.apple.NanoTimeKit", message.domain);
            assertEquals(812945800, message.timestamp, 0);
            assertEquals(1, keys.size());
            assertEquals("SelectedPigmentList", keys.get(0).key);
            assertEquals(Boolean.TRUE, keys.get(0).twoWaySync);
            assertEquals(message.timestamp, keys.get(0).timestamp, 0);
            assertEquals(List.of("zeus.fall2025.bleuHydra", "standard.blue"),
                    BinaryPropertyListCodec.decodeStringArray(keys.get(0).value()));
        } finally { message.destroy(); keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); }
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCodec.encodeChange(
                new PigmentPreferenceCodec.Report(NAMES, SOURCE + 1), changes, NOW));
    }

    @Test public void invalidIdentitiesAndOversizeListsCannotBecomePreferenceWrites() {
        for (String name : List.of("", "standard.blue:0.25", "standard.\nblue", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCodec.merge(NAMES, Map.of(name, true)));
        }
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCodec.names(
                java.util.stream.IntStream.range(0, 1024).mapToObj(i -> "native." + i).toList()));
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCodec.names(
                java.util.stream.IntStream.range(0, 129).mapToObj(i -> "x".repeat(250) + i).toList()));
    }

    @Test public void dispatcherAcceptsOnlyIncomingNpsInConnectedOwnedContext() {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        var notifications = new AtomicInteger();
        BridgeIpcDispatcher.OnPigmentPreferencesListener listener = notifications::incrementAndGet;
        dispatcher.addPigmentPreferencesListener(listener);
        byte[] payload = report(PigmentPreferenceCodec.DOMAIN, PigmentPreferenceCodec.KEY, NATIVE_ARRAY, SOURCE);
        try {
            dispatcher.updateConnectionState(false, "");
            deliver(dispatcher, PairedSyncCodec.PREFERRED_SERVICE, 0, false, payload);
            assertNull(dispatcher.pigmentPreferences(PAIR, EPOCH));
            dispatcher.updateConnectionState(true, "");
            deliver(dispatcher, "com.apple.other", 0, false, payload);
            deliver(dispatcher, PairedSyncCodec.PREFERRED_SERVICE, 1, false, payload);
            deliver(dispatcher, PairedSyncCodec.PREFERRED_SERVICE, 2, false, payload);
            deliver(dispatcher, PairedSyncCodec.PREFERRED_SERVICE, 0, true, payload);
            assertEquals(0, notifications.get());
            deliver(dispatcher, PairedSyncCodec.PREFERRED_SERVICE, 0, false, payload);
            assertEquals(1, notifications.get());
            assertEquals(NAMES, dispatcher.pigmentPreferences(PAIR, EPOCH).names());
            deliver(dispatcher, PairedSyncCodec.FALLBACK_SERVICE, 2, false, payload);
            assertEquals(1, notifications.get()); // Backup cannot supply a timestamped baseline.
            deliver(dispatcher, PairedSyncCodec.FALLBACK_SERVICE, 0, false, payload);
            assertEquals(1, notifications.get()); // Duplicate native report does not re-publish.
            deliver(dispatcher, PairedSyncCodec.PREFERRED_SERVICE, 0, false, new byte[]{0});
            assertEquals(NAMES, dispatcher.pigmentPreferences(PAIR, EPOCH).names());
            dispatcher.updateConnectionState(false, "");
            assertNull(dispatcher.pigmentPreferences(PAIR, EPOCH));
        } finally {
            dispatcher.updateConnectionState(false, "");
            dispatcher.removePigmentPreferencesListener(listener);
        }
    }

    @Test public void preferredAndFallbackNativeUpdatesSurviveHalIpcToOwnedObservation() {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        var payload = report(PigmentPreferenceCodec.DOMAIN, PigmentPreferenceCodec.KEY, NATIVE_ARRAY, SOURCE);
        try {
            for (String route : List.of(PairedSyncCodec.PREFERRED_SERVICE, PairedSyncCodec.FALLBACK_SERVICE)) {
                dispatcher.updateConnectionState(false, "");
                dispatcher.updateConnectionState(true, "");
                assertTrue(PigmentPreferenceCodec.isObservationEnvelope(route, 0, false));
                assertFalse(PigmentPreferenceCodec.isObservationEnvelope(route, 2, false));
                assertFalse(PigmentPreferenceCodec.isObservationEnvelope(route, 0, true));
                try (var nativeEvent = new BridgeApplicationEventCodec.Event(route, 0, false, payload);
                     var ipcEvent = BridgeApplicationEventCodec.decode(BridgeApplicationEventCodec.encode(nativeEvent))) {
                    dispatcher.observeNativePreferences(ipcEvent, PAIR, EPOCH, NOW);
                    assertEquals(NAMES, dispatcher.pigmentPreferences(PAIR, EPOCH).names());
                }
            }
        } finally { dispatcher.updateConnectionState(false, ""); }
        assertFalse(PigmentPreferenceCodec.isObservationEnvelope("com.apple.other", 0, false));
        assertFalse(PigmentPreferenceCodec.isObservationEnvelope(null, 0, false));
    }

    private static void deliver(BridgeIpcDispatcher dispatcher, String topic, int type, boolean response, byte[] payload) {
        try (var event = new BridgeApplicationEventCodec.Event(topic, type, response, payload)) {
            dispatcher.observeNativePreferences(event, PAIR, EPOCH, NOW);
        }
    }
    private static byte[] report(String domain, String key, byte[] value, double timestamp) {
        var field = new PairedSyncCodec.UserDefaultsKey(key, value, true, timestamp);
        var message = new PairedSyncCodec.UserDefaultsMessage(timestamp, domain, List.of(field), false);
        try { return PairedSyncCodec.encode(message); }
        finally { field.destroy(); message.destroy(); }
    }
    private static byte[] hex(String text) {
        return java.util.HexFormat.of().parseHex(text);
    }
}
