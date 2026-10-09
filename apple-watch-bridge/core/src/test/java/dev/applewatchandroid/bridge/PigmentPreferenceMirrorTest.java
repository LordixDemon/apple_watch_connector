package dev.applewatchandroid.bridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class PigmentPreferenceMirrorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID PAIR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EPOCH = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final long NOW = 1791253000000L;
    private static final double SOURCE = 812945799;
    private static final List<String> NAMES = List.of("standard.blue", "zeus.unknown");
    private static final List<String> AUTOMATIC = List.of("standard.blue", "standard.red", "automatic.unknown");
    private Path root() { return temporary.getRoot().toPath(); }
    private PigmentPreferenceMirror seeded() throws IOException {
        var store = new PigmentPreferenceMirror(root(), PAIR);
        assertTrue(store.observe(List.of(new PigmentPreferenceCodec.Report(NAMES, SOURCE)),
                List.of(new PigmentPreferenceCodec.Report(AUTOMATIC, SOURCE)), NOW));
        return store;
    }
    private PigmentPreferenceCommand.Command command(PigmentPreferenceMirror store, UUID epoch,
                                                     Map<String, Boolean> changes, long now) {
        var current = store.snapshot(epoch);
        return PigmentPreferenceCommand.parse(PigmentPreferenceCommand.createMirror(current, PAIR, epoch,
                current.value().sourceTimestamp(), current.value().names(), changes, now));
    }
    @Test public void absentStoreDoesNotInventAutomaticOrGlobalColors() throws Exception {
        var store = new PigmentPreferenceMirror(root(), PAIR);
        assertNull(store.snapshot(EPOCH));
        assertFalse(store.observe(List.of(), NOW));
        assertFalse(Files.exists(root().resolve(PAIR.toString())));
    }
    @Test public void persistedRemoteBaselineWorksAfterIdleAndRebindsOnlyToItsOwnedPair() throws Exception {
        seeded();
        var epoch = UUID.randomUUID();
        var reopened = new PigmentPreferenceMirror(root(), PAIR);
        long later = NOW + 86400000;
        var baseline = reopened.snapshot(epoch);
        assertEquals(PigmentPreferenceMirror.Origin.REMOTE, baseline.value().origin());
        assertEquals(NOW, baseline.value().updatedAt());
        assertTrue(baseline.writable(later));
        assertNotNull(command(reopened, epoch, Map.of("standard.red", true), later));
        assertNull(new PigmentPreferenceMirror(root(), UUID.randomUUID()).snapshot(epoch));
    }
    @Test public void exactOutgoingListTimestampAndRequestPersistBeforeAnyTransportAndSurviveCrash() throws Exception {
        var store = seeded(); UUID request = UUID.randomUUID();
        var cmd = command(store, EPOCH, Map.of("standard.red", true, "standard.blue", false), NOW);
        byte[] wire = store.prepare(cmd, EPOCH, request, NOW);
        var reloaded = new PigmentPreferenceMirror(root(), PAIR).snapshot(UUID.randomUUID());
        var reports = PigmentPreferenceCodec.decodeObserved(wire, NOW);
        assertEquals(1, reports.size());
        assertEquals(reloaded.value().names(), reports.get(0).names());
        assertEquals(reloaded.value().sourceTimestamp(), reports.get(0).sourceTimestamp(), 0);
        assertEquals(List.of("zeus.unknown", "standard.red"), reports.get(0).names());
        assertEquals(request, reloaded.value().request());
        assertEquals(PigmentPreferenceMirror.Origin.LOCAL, reloaded.value().origin());
        assertTrue(reloaded.writable(NOW));
    }
    @Test public void successiveManualWritesInOneMillisecondNeverReuseTimestampOrLosePriorChanges() throws Exception {
        var store = seeded();
        store.prepare(command(store, EPOCH, Map.of("standard.red", true), NOW), EPOCH, UUID.randomUUID(), NOW);
        double first = store.snapshot(EPOCH).value().sourceTimestamp();
        byte[] wire = store.prepare(command(store, EPOCH, Map.of("standard.green", true), NOW), EPOCH, UUID.randomUUID(), NOW);
        var report = PigmentPreferenceCodec.decodeObserved(wire, NOW).get(0);
        assertTrue(report.sourceTimestamp() > first);
        assertEquals(List.of("standard.blue", "zeus.unknown", "standard.red", "standard.green"), report.names());
    }
    @Test public void newerRemoteOverridesLocalButOldConflictsNeverRollItBack() throws Exception {
        var store = seeded();
        store.prepare(command(store, EPOCH, Map.of("standard.red", true), NOW), EPOCH, UUID.randomUUID(), NOW);
        var local = store.snapshot(EPOCH).value();
        assertFalse(store.observe(List.of(new PigmentPreferenceCodec.Report(List.of("peer.old"), SOURCE)), NOW + 1));
        assertFalse(store.observe(List.of(new PigmentPreferenceCodec.Report(List.of("peer.collision"), local.sourceTimestamp())), NOW + 1));
        assertEquals(local, store.snapshot(EPOCH).value());
        assertTrue(store.observe(List.of(new PigmentPreferenceCodec.Report(List.of("peer.new"), SOURCE + 2)), NOW + 2000));
        assertEquals(List.of("peer.new"), store.snapshot(EPOCH).value().names());
        assertEquals(PigmentPreferenceMirror.Origin.REMOTE, store.snapshot(EPOCH).value().origin());
    }
    @Test public void actualExactEchoChangesProvenanceWithoutInventingANewerTimestamp() throws Exception {
        var store = seeded();
        store.prepare(command(store, EPOCH, Map.of("standard.red", true), NOW), EPOCH, UUID.randomUUID(), NOW);
        var local = store.snapshot(EPOCH).value();
        assertTrue(store.observe(List.of(new PigmentPreferenceCodec.Report(local.names(), local.sourceTimestamp())), NOW + 1));
        var echoed = store.snapshot(EPOCH).value();
        assertEquals(local.sourceTimestamp(), echoed.sourceTimestamp(), 0);
        assertEquals(PigmentPreferenceMirror.Origin.REMOTE, echoed.origin());
        assertNull(echoed.request());
        assertFalse(store.observe(List.of(new PigmentPreferenceCodec.Report(echoed.names(), echoed.sourceTimestamp())), NOW + 2));
    }

    @Test public void reorderedNativeSetEchoPromotesBothListsWithoutInventingTimestampOrReplaying() throws Exception {
        var store = seeded();
        byte[] wire = store.prepare(command(store, EPOCH, Map.of("standard.blue", true), NOW),
                EPOCH, UUID.randomUUID(), NOW);
        Arrays.fill(wire, (byte) 0);
        var local = store.snapshot(EPOCH);
        var selected = new ArrayList<>(local.value().names()); Collections.reverse(selected);
        var automatic = new ArrayList<>(local.automatic().names()); Collections.reverse(automatic);
        assertTrue(store.observe(List.of(new PigmentPreferenceCodec.Report(selected, local.value().sourceTimestamp())),
                List.of(new PigmentPreferenceCodec.Report(automatic, local.automatic().sourceTimestamp())), NOW + 1));
        var echoed = new PigmentPreferenceMirror(root(), PAIR).snapshot(EPOCH);
        assertEquals(PigmentPreferenceMirror.Origin.REMOTE, echoed.value().origin());
        assertEquals(PigmentPreferenceMirror.Origin.REMOTE, echoed.automatic().origin());
        assertNull(echoed.value().request()); assertNull(echoed.automatic().request());
        assertEquals(local.value().sourceTimestamp(), echoed.value().sourceTimestamp(), 0);
        assertEquals(local.automatic().sourceTimestamp(), echoed.automatic().sourceTimestamp(), 0);
        assertEquals(selected, echoed.value().names()); assertEquals(automatic, echoed.automatic().names());
        assertFalse(store.observe(List.of(new PigmentPreferenceCodec.Report(local.value().names(), local.value().sourceTimestamp())),
                List.of(new PigmentPreferenceCodec.Report(local.automatic().names(), local.automatic().sourceTimestamp())), NOW + 2));
    }

    @Test public void sameTimestampDifferentSetCannotPromoteLocalPreferences() throws Exception {
        var store = seeded();
        byte[] wire = store.prepare(command(store, EPOCH, Map.of("standard.blue", true), NOW), EPOCH, UUID.randomUUID(), NOW);
        Arrays.fill(wire, (byte) 0);
        var local = store.snapshot(EPOCH);
        assertFalse(store.observe(List.of(new PigmentPreferenceCodec.Report(List.of("standard.blue", "different.name"), local.value().sourceTimestamp())),
                List.of(new PigmentPreferenceCodec.Report(List.of("standard.red", "different.auto"), local.automatic().sourceTimestamp())), NOW + 1));
        assertEquals(local, store.snapshot(EPOCH));
    }

    @Test public void queuedDualBaselineAcceptsPermutationButRejectsDifferentMembership() throws Exception {
        var store = seeded(); var current = store.snapshot(EPOCH);
        var selected = new ArrayList<>(NAMES); Collections.reverse(selected);
        var automatic = new ArrayList<>(AUTOMATIC); Collections.reverse(automatic);
        var cmd = PigmentPreferenceCommand.parse(PigmentPreferenceCommand.createMirror(current, PAIR, EPOCH,
                SOURCE, selected, SOURCE, automatic, Map.of("standard.red", true), NOW));
        var reordered = new PigmentPreferenceMirror.Snapshot(PAIR, EPOCH,
                new PigmentPreferenceMirror.Entry(selected, SOURCE, NOW, PigmentPreferenceMirror.Origin.REMOTE, null),
                new PigmentPreferenceMirror.Entry(automatic, SOURCE, NOW, PigmentPreferenceMirror.Origin.REMOTE, null));
        PigmentPreferenceCommand.requireMirror(cmd, reordered, PAIR, EPOCH, NOW);
        automatic.set(0, "different.auto");
        var conflict = new PigmentPreferenceMirror.Snapshot(PAIR, EPOCH, reordered.value(),
                new PigmentPreferenceMirror.Entry(automatic, SOURCE, NOW, PigmentPreferenceMirror.Origin.REMOTE, null));
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.requireMirror(cmd, conflict, PAIR, EPOCH, NOW));
    }
    @Test public void deletionIsDurableAndCannotBecomeAnEmptyWriteBaseline() throws Exception {
        var store = seeded();
        assertTrue(store.observe(List.of(new PigmentPreferenceCodec.Report(null, SOURCE + 1)), NOW));
        var reloaded = new PigmentPreferenceMirror(root(), PAIR);
        assertNull(reloaded.snapshot(EPOCH).value().names());
        assertFalse(reloaded.snapshot(EPOCH).writable(NOW));
        assertThrows(IllegalArgumentException.class, () -> command(reloaded, EPOCH, Map.of("standard.red", true), NOW));
        assertTrue(reloaded.observe(List.of(new PigmentPreferenceCodec.Report(List.of(), SOURCE + 2)), NOW + 2000));
        assertTrue(reloaded.snapshot(EPOCH).writable(NOW + 2000));
    }
    @Test public void changedMirrorOrForeignPairOrEpochCannotPrepareQueuedWrite() throws Exception {
        var store = seeded(); var cmd = command(store, EPOCH, Map.of("standard.red", true), NOW);
        var current = store.snapshot(EPOCH);
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.requireMirror(cmd, current, UUID.randomUUID(), EPOCH, NOW));
        assertThrows(IllegalArgumentException.class, () -> PigmentPreferenceCommand.requireMirror(cmd, current, PAIR, UUID.randomUUID(), NOW));
        store.observe(List.of(new PigmentPreferenceCodec.Report(List.of("peer.new"), SOURCE + 1)), NOW);
        assertThrows(IllegalArgumentException.class, () -> store.prepare(cmd, EPOCH, UUID.randomUUID(), NOW));
    }
    @Test public void failedAtomicWriteReturnsNoWireAndRequiresValidatedReopen() throws Exception {
        var store = seeded(); var cmd = command(store, EPOCH, Map.of("standard.red", true), NOW);
        var path = root().resolve(PAIR.toString()).resolve("state.bin");
        Files.delete(path); Files.createDirectory(path);
        assertThrows(IOException.class, () -> store.prepare(cmd, EPOCH, UUID.randomUUID(), NOW));
        assertFalse(store.available()); assertNull(store.snapshot(EPOCH));
        assertThrows(IOException.class, () -> store.observe(List.of(new PigmentPreferenceCodec.Report(NAMES, SOURCE)), NOW));
        assertThrows(IOException.class, () -> new PigmentPreferenceMirror(root(), PAIR));
    }
    @Test public void corruptionTruncationForeignStoreAndSymlinkCannotSupplyABaseline() throws Exception {
        seeded(); var path = root().resolve(PAIR.toString()).resolve("state.bin");
        byte[] bytes = Files.readAllBytes(path);
        UUID foreign = UUID.randomUUID();
        var copied = root().resolve(foreign.toString()).resolve("state.bin");
        Files.createDirectories(copied.getParent()); Files.write(copied, bytes);
        assertThrows(IOException.class, () -> new PigmentPreferenceMirror(root(), foreign));
        bytes[bytes.length - 1] ^= 1; Files.write(path, bytes);
        assertThrows(IOException.class, () -> new PigmentPreferenceMirror(root(), PAIR));
        Files.write(path, new byte[8]);
        assertThrows(IOException.class, () -> new PigmentPreferenceMirror(root(), PAIR));
        Files.delete(path); Files.createSymbolicLink(path, root().resolve("missing"));
        assertThrows(IOException.class, () -> new PigmentPreferenceMirror(root(), PAIR));
    }
    @Test public void clockRollbackAndFutureNativeReportsCannotInventFreshness() throws Exception {
        var store = seeded();
        assertFalse(store.snapshot(EPOCH).writable(NOW - 1));
        assertThrows(IllegalArgumentException.class, () -> command(store, EPOCH, Map.of("standard.red", true), NOW - 1));
        assertFalse(store.observe(List.of(new PigmentPreferenceCodec.Report(List.of("future"), SOURCE + 100)), NOW));
        assertEquals(NAMES, store.snapshot(EPOCH).value().names());
    }
    @Test public void boundedIpcRetainsProvenanceAndDoesNotMutateLiveObservation() throws Exception {
        var store = seeded();
        store.prepare(command(store, EPOCH, Map.of("standard.red", true), NOW), EPOCH, UUID.randomUUID(), NOW);
        var snapshot = store.snapshot(EPOCH); byte[] wire = PigmentMirrorIpcCodec.encode(snapshot);
        assertEquals(snapshot, PigmentMirrorIpcCodec.decode(wire));
        assertThrows(IOException.class, () -> PigmentMirrorIpcCodec.decode(Arrays.copyOf(wire, wire.length + 1)));
        assertThrows(IOException.class, () -> PigmentMirrorIpcCodec.decode(new byte[PigmentMirrorIpcCodec.MAX_FRAME + 1]));
        var badOrigin = wire.clone(); badOrigin[52] = 2;
        assertThrows(IOException.class, () -> PigmentMirrorIpcCodec.decode(badOrigin));
        var dispatcher = BridgeIpcDispatcher.getInstance();
        dispatcher.updateConnectionState(false, "Watch");
        dispatcher.updateConnectionState(true, "Watch");
        try {
            dispatcher.observePigmentMirror(snapshot);
            assertEquals(snapshot, dispatcher.pigmentMirror(PAIR, EPOCH));
            assertNull(dispatcher.pigmentPreferences(PAIR, EPOCH));
            assertNull(dispatcher.pigmentMirror(PAIR, UUID.randomUUID()));
            dispatcher.updateConnectionState(false, "Watch");
            assertNull(dispatcher.pigmentMirror(PAIR, EPOCH));
        } finally { dispatcher.updateConnectionState(false, "Watch"); }
    }

    @Test public void automaticReceiptCanPrecedeSelectedWithoutInventingAnEmptySelection() throws Exception {
        var store = new PigmentPreferenceMirror(root(), PAIR);
        assertTrue(store.observe(List.of(), List.of(new PigmentPreferenceCodec.Report(AUTOMATIC, SOURCE)), NOW));
        assertNull(store.snapshot(EPOCH));
        var reopened = new PigmentPreferenceMirror(root(), PAIR);
        assertTrue(reopened.observe(List.of(new PigmentPreferenceCodec.Report(NAMES, SOURCE)), NOW));
        assertEquals(AUTOMATIC, reopened.snapshot(EPOCH).automatic().names());
        assertTrue(reopened.snapshot(EPOCH).manualWritable(NOW));
    }

    @Test public void legacySelectedStoreIsReadableButCannotSeedAutomaticTracking() throws Exception {
        var path = root().resolve(PAIR.toString()).resolve("state.bin");
        Files.createDirectories(path.getParent());
        var buffer = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(buffer)) {
            out.writeInt(0x50474d31); PigmentPreferenceMirror.uuid(out, PAIR);
            PigmentPreferenceMirror.writeEntry(out, new PigmentPreferenceMirror.Entry(NAMES, SOURCE,
                    NOW, PigmentPreferenceMirror.Origin.REMOTE, null));
        }
        byte[] body = buffer.toByteArray();
        buffer.write(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        Files.write(path, buffer.toByteArray());
        var store = new PigmentPreferenceMirror(root(), PAIR);
        assertEquals(NAMES, store.snapshot(EPOCH).value().names());
        assertNull(store.snapshot(EPOCH).automatic());
        assertFalse(store.snapshot(EPOCH).manualWritable(NOW));
        assertThrows(IllegalArgumentException.class, () -> command(store, EPOCH, Map.of("standard.red", true), NOW));
        assertTrue(store.observe(List.of(), List.of(new PigmentPreferenceCodec.Report(List.of(), SOURCE)), NOW));
        assertTrue(new PigmentPreferenceMirror(root(), PAIR).snapshot(EPOCH).manualWritable(NOW));
    }

    @Test public void manualAdditionAndRemovalBothLeaveAutomaticTrackingInOneDurableTransaction() throws Exception {
        var store = seeded(); UUID request = UUID.randomUUID();
        byte[] wire = store.prepare(command(store, EPOCH, Map.of("standard.red", true, "standard.blue", false), NOW), EPOCH, request, NOW);
        var snapshot = new PigmentPreferenceMirror(root(), PAIR).snapshot(EPOCH);
        assertEquals(List.of("zeus.unknown", "standard.red"), snapshot.value().names());
        assertEquals(List.of("automatic.unknown"), snapshot.automatic().names());
        assertEquals(request, snapshot.value().request()); assertEquals(request, snapshot.automatic().request());
        assertEquals(PigmentPreferenceMirror.Origin.LOCAL, snapshot.automatic().origin());
        assertEquals(snapshot.value().sourceTimestamp(), snapshot.automatic().sourceTimestamp(), 0);
        var message = PairedSyncCodec.decodeInbound(wire); var keys = message.keys();
        try {
            assertEquals(2, keys.size());
            assertEquals(List.of(PigmentPreferenceCodec.KEY, PigmentPreferenceCodec.AUTO_KEY),
                    keys.stream().map(k -> k.key).collect(java.util.stream.Collectors.toList()));
            for (var key : keys) { assertEquals(Boolean.TRUE, key.twoWaySync); assertEquals(message.timestamp, key.timestamp, 0); }
            assertEquals(snapshot.value().names(), PigmentPreferenceCodec.decodeObserved(wire, NOW).get(0).names());
            assertEquals(snapshot.automatic().names(), PigmentPreferenceCodec.decodeObserved(wire, NOW, PigmentPreferenceCodec.AUTO_KEY).get(0).names());
        } finally { message.destroy(); keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); Arrays.fill(wire, (byte) 0); }
    }

    @Test public void automaticOnlyChangesInvalidateAnAlreadyQueuedManualBaseline() throws Exception {
        var store = seeded(); var cmd = command(store, EPOCH, Map.of("standard.red", true), NOW);
        assertTrue(store.observe(List.of(), List.of(new PigmentPreferenceCodec.Report(List.of("new.auto"), SOURCE + 1)), NOW));
        assertEquals(NAMES, store.snapshot(EPOCH).value().names());
        assertThrows(IllegalArgumentException.class, () -> store.prepare(cmd, EPOCH, UUID.randomUUID(), NOW));
    }

    @Test public void automaticDeletionDisablesManualWritesButPreservesVisiblePalette() throws Exception {
        var store = seeded();
        store.observe(List.of(), List.of(new PigmentPreferenceCodec.Report(null, SOURCE + 1)), NOW);
        var reopened = new PigmentPreferenceMirror(root(), PAIR);
        assertEquals(NAMES, reopened.snapshot(EPOCH).value().names());
        assertFalse(reopened.snapshot(EPOCH).manualWritable(NOW));
        assertThrows(IllegalArgumentException.class, () -> command(reopened, EPOCH, Map.of("standard.red", true), NOW));
        reopened.observe(List.of(), List.of(new PigmentPreferenceCodec.Report(List.of(), SOURCE + 2)), NOW + 2000);
        assertTrue(reopened.snapshot(EPOCH).manualWritable(NOW + 2000));
    }

    @Test public void unchangedVisibleChoiceStillRemovesAutomaticOwnership() throws Exception {
        var store = seeded();
        store.prepare(command(store, EPOCH, Map.of("standard.blue", true), NOW), EPOCH, UUID.randomUUID(), NOW);
        assertEquals(NAMES, store.snapshot(EPOCH).value().names());
        assertEquals(List.of("standard.red", "automatic.unknown"), store.snapshot(EPOCH).automatic().names());
    }

    @Test public void timestampAdvancesPastTheNewerOfBothListsAndBothExactEchoesPromoteProvenance() throws Exception {
        var store = seeded();
        store.observe(List.of(), List.of(new PigmentPreferenceCodec.Report(AUTOMATIC, SOURCE + 2)), NOW + 2000);
        byte[] wire = store.prepare(command(store, EPOCH, Map.of("standard.blue", false), NOW + 2000), EPOCH, UUID.randomUUID(), NOW + 2000);
        var local = store.snapshot(EPOCH);
        assertTrue(local.value().sourceTimestamp() > SOURCE + 2);
        assertTrue(store.observe(PigmentPreferenceCodec.decodeObserved(wire, NOW + 2000),
                PigmentPreferenceCodec.decodeObserved(wire, NOW + 2000, PigmentPreferenceCodec.AUTO_KEY), NOW + 2001));
        assertEquals(PigmentPreferenceMirror.Origin.REMOTE, store.snapshot(EPOCH).value().origin());
        assertEquals(PigmentPreferenceMirror.Origin.REMOTE, store.snapshot(EPOCH).automatic().origin());
        assertNull(store.snapshot(EPOCH).automatic().request());
    }

    @Test public void automaticIpcRegressionCannotDiscardAnOwnedNewerValue() throws Exception {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        var snapshot = seeded().snapshot(EPOCH);
        dispatcher.updateConnectionState(false, "Watch"); dispatcher.updateConnectionState(true, "Watch");
        try {
            dispatcher.observePigmentMirror(snapshot);
            dispatcher.observePigmentMirror(new PigmentPreferenceMirror.Snapshot(PAIR, EPOCH, snapshot.value()));
            assertEquals(snapshot, dispatcher.pigmentMirror(PAIR, EPOCH));
            var older = new PigmentPreferenceMirror.Entry(List.of(), SOURCE - 1, NOW, PigmentPreferenceMirror.Origin.REMOTE, null);
            dispatcher.observePigmentMirror(new PigmentPreferenceMirror.Snapshot(PAIR, EPOCH, snapshot.value(), older));
            assertEquals(snapshot, dispatcher.pigmentMirror(PAIR, EPOCH));
        } finally { dispatcher.updateConnectionState(false, "Watch"); }
    }

    @Test public void legacySingleListCommandCannotBypassTheManualAutomaticBaselineGate() throws Exception {
        var store = seeded(); var full = command(store, EPOCH, Map.of("standard.blue", false), NOW);
        var legacy = new PigmentPreferenceCommand.Command(full.pair(), full.sourceTimestamp(), full.baselineHash(), full.changes());
        assertThrows(IllegalArgumentException.class, () -> store.prepare(legacy, EPOCH, UUID.randomUUID(), NOW));
        assertEquals(AUTOMATIC, new PigmentPreferenceMirror(root(), PAIR).snapshot(EPOCH).automatic().names());
    }
}
