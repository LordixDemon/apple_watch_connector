package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PigmentPreferenceCommandTest {
    private static final long NOW = 1791253000000L;
    private static final double SOURCE = 812945799;
    private static final UUID PAIR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EPOCH = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final List<String> NAMES = List.of("standard.navyBlue", "zeus.fall2025.bleuHydra");
    private static PigmentPreferenceObservation.Value current(List<String> names, double source, long at) {
        return new PigmentPreferenceObservation.Value(PAIR, EPOCH, names, source, at);
    }
    private static String command(Map<String, Boolean> changes) {
        return PigmentPreferenceCommand.create(current(NAMES, SOURCE, NOW), PAIR, EPOCH, SOURCE, NAMES, changes, NOW);
    }
    @Test public void nativeManualDeltaPreservesUnknownColorsAndUsesOriginalNpsValue() {
        var changes = new LinkedHashMap<String, Boolean>();
        changes.put("standard.navyBlue", false); changes.put("standard.blue", true);
        String line = command(changes);
        var parsed = PigmentPreferenceCommand.parse(line);
        changes.clear();
        assertEquals(2, parsed.changes().size());
        assertThrows(UnsupportedOperationException.class, () -> parsed.changes().clear());
        byte[] payload = PigmentPreferenceCommand.nativePayload(parsed, current(NAMES, SOURCE, NOW), PAIR, EPOCH, NOW);
        var nativeMessage = PairedSyncCodec.decodeInbound(payload);
        var keys = nativeMessage.keys();
        try {
            assertEquals("com.apple.NanoTimeKit", nativeMessage.domain);
            assertEquals(812945800, nativeMessage.timestamp, 0);
            assertEquals(1, keys.size());
            assertEquals("SelectedPigmentList", keys.get(0).key);
            assertEquals(Boolean.TRUE, keys.get(0).twoWaySync);
            assertEquals(List.of("zeus.fall2025.bleuHydra", "standard.blue"),
                    BinaryPropertyListCodec.decodeStringArray(keys.get(0).value()));
        } finally { nativeMessage.destroy(); keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); }
    }
    @Test public void unknownDeletedStaleFutureOrDifferentTargetCannotPrepareWrite() {
        for (var value : List.of(current(null, SOURCE, NOW), current(NAMES, SOURCE, NOW - 300001),
                current(NAMES, SOURCE, NOW + 1), current(NAMES, SOURCE, 0))) {
            assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.create(
                    value, PAIR, EPOCH, SOURCE, NAMES, Map.of("standard.blue", true), NOW));
        }
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.create(
                null, PAIR, EPOCH, SOURCE, NAMES, Map.of("standard.blue", true), NOW));
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.create(
                current(NAMES, SOURCE, NOW), EPOCH, EPOCH, SOURCE, NAMES, Map.of("standard.blue", true), NOW));
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.create(
                current(NAMES, SOURCE, NOW), PAIR, PAIR, SOURCE, NAMES, Map.of("standard.blue", true), NOW));
    }
    @Test public void changedBaselineAtBinderOrAfterQueueDelayNeverProducesNativePayload() {
        var value = current(NAMES, SOURCE, NOW);
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.create(
                value, PAIR, EPOCH, SOURCE - 1, NAMES, Map.of("standard.blue", true), NOW));
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.create(
                value, PAIR, EPOCH, SOURCE, List.of("standard.blue"), Map.of("standard.blue", true), NOW));
        var parsed = PigmentPreferenceCommand.parse(command(Map.of("standard.blue", true)));
        for (var changed : List.of(current(NAMES, SOURCE + 0.5, NOW),
                current(List.of("standard.navyBlue", "zeus.newColor"), SOURCE, NOW))) {
            assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.nativePayload(parsed, changed, PAIR, EPOCH, NOW));
        }
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.nativePayload(parsed, value, EPOCH, EPOCH, NOW));
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.nativePayload(parsed, value, PAIR, PAIR, NOW));
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.nativePayload(parsed, value, PAIR, EPOCH, NOW + 300001));
    }
    @Test public void emptyBaselineCanAddButDeletionNeverBecomesAnEmptyBaseline() {
        var empty = current(List.of(), SOURCE, NOW);
        var line = PigmentPreferenceCommand.create(empty, PAIR, EPOCH, SOURCE, List.of(), Map.of("standard.blue", true), NOW);
        assertNotNull(PigmentPreferenceCommand.nativePayload(PigmentPreferenceCommand.parse(line), empty, PAIR, EPOCH, NOW));
    }
    @Test public void exactPeerRepeatRenewsAnOldBaselineWithoutInventingANewerSourceChange() {
        var observation = new PigmentPreferenceObservation();
        var report = new PigmentPreferenceCodec.Report(NAMES, SOURCE);
        observation.observe(PAIR, EPOCH, List.of(report), NOW);
        long later = NOW + 300001;
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.create(
                observation.snapshot(PAIR, EPOCH), PAIR, EPOCH, SOURCE, NAMES, Map.of("standard.blue", true), later));
        assertTrue(observation.observe(PAIR, EPOCH, List.of(report), later));
        var refreshed = observation.snapshot(PAIR, EPOCH);
        assertEquals(SOURCE, refreshed.sourceTimestamp(), 0);
        assertEquals(later, refreshed.observedAt());
        String line = PigmentPreferenceCommand.create(refreshed, PAIR, EPOCH, SOURCE, NAMES,
                Map.of("standard.blue", true), later);
        assertNotNull(PigmentPreferenceCommand.nativePayload(PigmentPreferenceCommand.parse(line), refreshed, PAIR, EPOCH, later));
    }
    @Test public void reorderedPeerRepeatRenewsReceiptAndUsesTheSameNativeSetBaseline() {
        var observation = new PigmentPreferenceObservation();
        observation.observe(PAIR, EPOCH, List.of(new PigmentPreferenceCodec.Report(NAMES, SOURCE)), NOW);
        var reversed = new ArrayList<>(NAMES); Collections.reverse(reversed);
        long later = NOW + 300001;
        assertTrue(observation.observe(PAIR, EPOCH, List.of(new PigmentPreferenceCodec.Report(reversed, SOURCE)), later));
        assertEquals(SOURCE, observation.snapshot(PAIR, EPOCH).sourceTimestamp(), 0);
        assertEquals(later, observation.snapshot(PAIR, EPOCH).observedAt());
        var cmd = PigmentPreferenceCommand.parse(PigmentPreferenceCommand.create(
                observation.snapshot(PAIR, EPOCH), PAIR, EPOCH, SOURCE, reversed, Map.of("standard.blue", true), later));
        assertNotNull(PigmentPreferenceCommand.nativePayload(cmd, current(reversed, SOURCE, later), PAIR, EPOCH, later));
    }
    @Test public void olderConflictingAndClockRollbackReportsCannotRenewReceiptAge() {
        var observation = new PigmentPreferenceObservation();
        observation.observe(PAIR, EPOCH, List.of(new PigmentPreferenceCodec.Report(NAMES, SOURCE)), NOW);
        assertFalse(observation.observe(PAIR, EPOCH,
                List.of(new PigmentPreferenceCodec.Report(NAMES, SOURCE - 1)), NOW + 100));
        assertFalse(observation.observe(PAIR, EPOCH,
                List.of(new PigmentPreferenceCodec.Report(List.of("standard.blue"), SOURCE)), NOW + 100));
        assertFalse(observation.observe(PAIR, EPOCH,
                List.of(new PigmentPreferenceCodec.Report(NAMES, SOURCE)), NOW - 1));
        assertEquals(NOW, observation.snapshot(PAIR, EPOCH).observedAt());
        assertEquals(NAMES, observation.snapshot(PAIR, EPOCH).names());
    }
    @Test public void unsupportedIdentitiesAndLimitsFailBeforeQueueing() {
        for (String name : List.of("", "standard.blue:0.5", "standard.\nblue", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> command(Map.of(name, false)));
        }
        assertThrows(IllegalArgumentException.class, () -> command(Map.of()));
        var oversized = new LinkedHashMap<String, Boolean>();
        for (int i = 0; i < 1023; i++) oversized.put("x".repeat(100) + i, false);
        assertThrows(IllegalArgumentException.class, () -> command(oversized));
        var full = java.util.stream.IntStream.range(0, 1023).mapToObj(i -> "native." + i).toList();
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.create(
                current(full, SOURCE, NOW), PAIR, EPOCH, SOURCE, full, Map.of("new.color", true), NOW));
    }
    @Test public void binaryFrameIsCanonicalAndCannotSmuggleOtherCommandsOrTrailingData() {
        String line = command(Map.of("standard.blue", true));
        assertTrue(OperationalCommandPolicy.isAllowed(line));
        for (String bad : List.of(line + "\nERASE", PigmentPreferenceCommand.PREFIX + "!", line + "AAAA")) {
            assertFalse(OperationalCommandPolicy.isAllowed(bad));
            assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.parse(bad));
        }
        byte[] raw = Base64.getDecoder().decode(line.substring(PigmentPreferenceCommand.PREFIX.length()));
        for (int offset : List.of(0, 4, raw.length - 1)) {
            var bad = raw.clone(); bad[offset] = (byte) 127;
            String invalid = PigmentPreferenceCommand.PREFIX + Base64.getEncoder().encodeToString(bad);
            assertFalse(OperationalCommandPolicy.isAllowed(invalid));
        }
    }
    @Test public void nativeIdsQueueAndAckCannotClaimAppliedOrRepeatAfterSessionChange() {
        var statuses = new ArrayList<BridgeCommandCodec.Status>();
        var tracker = new OperationalRequestTracker(2, statuses::add);
        UUID epoch = tracker.newEpoch();
        var request = new BridgeCommandCodec.Request(UUID.randomUUID(), epoch, 1000, command(Map.of("standard.blue", true)));
        assertTrue(tracker.accept(request, 0, () -> true));
        tracker.idsQueued(request, IdsApplicationRoute.PREFERENCE_SYNC_SERVICE, "native-pigment-message", -1);
        tracker.appAck(IdsApplicationRoute.PREFERENCE_SYNC_SERVICE, "native-pigment-message", 1);
        assertEquals(BridgeCommandCodec.Stage.APP_ACK_RECEIVED, statuses.get(statuses.size() - 1).stage());
        tracker.newEpoch();
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, statuses.get(statuses.size() - 1).stage());
        assertFalse(tracker.canSend(request, 2));
        assertFalse(tracker.accept(request, 2, () -> failEnqueue()));
    }
    private static boolean failEnqueue() { fail("Stale command must not be enqueued"); return false; }
}
