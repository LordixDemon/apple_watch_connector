package dev.applewatchandroid.bridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class MonogramPreferenceMirrorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID PAIR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EPOCH = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final long NOW = 1791253000000L;
    private static final double SOURCE = NOW / 1000.0 - 978307200 - 1;
    private Path root() { return temporary.getRoot().toPath(); }
    private MonogramPreferenceMirror store() throws IOException { return new MonogramPreferenceMirror(root(), PAIR); }
    private MonogramPreferenceMirror seeded() throws IOException {
        var result = store(); result.observe(List.of(new MonogramPreferenceCodec.Report("OLD", SOURCE)), NOW); return result;
    }
    private MonogramPreferenceCommand.Command command(MonogramPreferenceMirror store, String text) {
        var snapshot = store.snapshot(EPOCH);
        return MonogramPreferenceCommand.parse(MonogramPreferenceCommand.create(snapshot, PAIR, EPOCH, snapshot.revision(), text, NOW));
    }
    @Test public void unknownIsDistinctFromObservedDeletionAndAllowsOnlyManualCreation() throws Exception {
        var value = store(); var unknown = value.snapshot(EPOCH);
        assertNull(unknown.value()); assertTrue(unknown.writable(NOW));
        assertFalse(unknown.writable(1));
        assertFalse(Files.exists(root().resolve(PAIR.toString())));
        value.observe(List.of(new MonogramPreferenceCodec.Report(null, SOURCE)), NOW);
        var deleted = store().snapshot(EPOCH);
        assertNotNull(deleted.value()); assertNull(deleted.value().text());
        assertNotEquals(unknown.revision(), deleted.revision());
        assertTrue(deleted.writable(NOW));
    }
    @Test public void manualNativePayloadPersistsBeforeReturningWireWithoutClaimingApplication() throws Exception {
        var value = seeded(); var request = UUID.randomUUID();
        byte[] wire = value.prepare(command(value, "É"), EPOCH, request, NOW);
        var pending = store().snapshot(UUID.randomUUID()).value();
        assertEquals("É", pending.text()); assertEquals(request, pending.request());
        assertEquals(MonogramPreferenceMirror.Origin.LOCAL, pending.origin());
        try (var envelope = NtkPreferenceEnvelope.decode(wire, NOW)) {
            assertTrue(envelope.nativeDomain()); assertEquals(1, envelope.keys.size());
            assertEquals("customMonogram", envelope.keys.get(0).key);
        }
        var report = MonogramPreferenceCodec.decodeObserved(wire, NOW).get(0);
        assertEquals(pending.text(), report.text()); assertEquals(pending.sourceTimestamp(), report.sourceTimestamp(), 0);
        Arrays.fill(wire, (byte) 0);
    }
    @Test public void sameMillisecondManualWritesAdvanceTheirClockAndInvalidateQueuedBaseline() throws Exception {
        var value = seeded(); var first = command(value, "ONE");
        value.prepare(first, EPOCH, UUID.randomUUID(), NOW);
        double stamp = value.snapshot(EPOCH).value().sourceTimestamp();
        assertThrows(IllegalArgumentException.class, () -> value.prepare(first, EPOCH, UUID.randomUUID(), NOW));
        value.prepare(command(value, "TWO"), EPOCH, UUID.randomUUID(), NOW);
        assertTrue(value.snapshot(EPOCH).value().sourceTimestamp() > stamp);
    }
    @Test public void onlyExactNativeEchoPromotesLocalAndOlderConflictsCannotRollback() throws Exception {
        var value = seeded(); value.prepare(command(value, "NEW"), EPOCH, UUID.randomUUID(), NOW);
        var local = value.snapshot(EPOCH).value();
        assertFalse(value.observe(List.of(new MonogramPreferenceCodec.Report("BAD", local.sourceTimestamp())), NOW + 1));
        assertFalse(value.observe(List.of(new MonogramPreferenceCodec.Report("OLD", SOURCE)), NOW + 1));
        assertTrue(value.observe(List.of(new MonogramPreferenceCodec.Report("NEW", local.sourceTimestamp())), NOW + 1));
        var remote = store().snapshot(EPOCH).value();
        assertEquals(MonogramPreferenceMirror.Origin.REMOTE, remote.origin()); assertNull(remote.request());
        assertEquals(local.sourceTimestamp(), remote.sourceTimestamp(), 0);
        assertFalse(value.observe(List.of(new MonogramPreferenceCodec.Report("NEW", remote.sourceTimestamp())), NOW + 2));
    }
    @Test public void foreignPairEpochRevisionClockAndFutureObservationAreRejected() throws Exception {
        var value = seeded(); var snapshot = value.snapshot(EPOCH);
        assertThrows(IllegalArgumentException.class, () -> MonogramPreferenceCommand.create(snapshot, UUID.randomUUID(), EPOCH, snapshot.revision(), "X", NOW));
        assertThrows(IllegalArgumentException.class, () -> MonogramPreferenceCommand.create(snapshot, PAIR, UUID.randomUUID(), snapshot.revision(), "X", NOW));
        assertThrows(IllegalArgumentException.class, () -> MonogramPreferenceCommand.create(snapshot, PAIR, EPOCH, "0".repeat(64), "X", NOW));
        assertFalse(snapshot.writable(NOW - 1));
        assertFalse(value.observe(List.of(new MonogramPreferenceCodec.Report("FUT", SOURCE + 20)), NOW));
        assertEquals(snapshot, value.snapshot(EPOCH));
        assertThrows(IllegalArgumentException.class, () -> value.prepare(command(value, "X"), UUID.randomUUID(), UUID.randomUUID(), NOW));
    }
    @Test public void corruptionForeignCopyAndFailedAtomicWriteRequireValidatedReopen() throws Exception {
        var value = seeded(); var path = root().resolve(PAIR.toString()).resolve("state.bin");
        byte[] bytes = Files.readAllBytes(path); UUID other = UUID.randomUUID();
        var foreign = root().resolve(other.toString()).resolve("state.bin");
        Files.createDirectories(foreign.getParent()); Files.write(foreign, bytes);
        assertThrows(IOException.class, () -> new MonogramPreferenceMirror(root(), other));
        bytes[bytes.length - 1] ^= 1; Files.write(path, bytes);
        assertThrows(IOException.class, this::store);
        Files.delete(path); Files.createDirectory(path);
        assertThrows(IOException.class, () -> value.prepare(command(value, "X"), EPOCH, UUID.randomUUID(), NOW));
        assertFalse(value.available());
        assertThrows(IllegalStateException.class, () -> value.snapshot(EPOCH));
        assertThrows(IOException.class, this::store);
    }
    @Test public void ipcPreservesUnknownDeletedAndLocalButRejectsTrailingOrMalformedFrames() throws Exception {
        var value = store();
        assertEquals(value.snapshot(EPOCH), MonogramMirrorIpcCodec.decode(MonogramMirrorIpcCodec.encode(value.snapshot(EPOCH))));
        value.observe(List.of(new MonogramPreferenceCodec.Report(null, SOURCE)), NOW);
        assertEquals(value.snapshot(EPOCH), MonogramMirrorIpcCodec.decode(MonogramMirrorIpcCodec.encode(value.snapshot(EPOCH))));
        value.prepare(command(value, "X"), EPOCH, UUID.randomUUID(), NOW);
        byte[] bytes = MonogramMirrorIpcCodec.encode(value.snapshot(EPOCH));
        assertEquals(value.snapshot(EPOCH), MonogramMirrorIpcCodec.decode(bytes));
        assertThrows(IOException.class, () -> MonogramMirrorIpcCodec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IOException.class, () -> MonogramMirrorIpcCodec.decode(new byte[257]));
        assertThrows(IOException.class, () -> MonogramMirrorIpcCodec.decode(new byte[0]));
    }
    @Test public void commandRejectsEmptyEmojiOversizedUnpaddedAndTrailingFrames() throws Exception {
        var value = store();
        for (String text : List.of("", "😀", "ABCDEF", "\ud800")) {
            assertThrows(IllegalArgumentException.class, () -> command(value, text));
        }
        var snapshot = value.snapshot(EPOCH);
        String line = MonogramPreferenceCommand.create(snapshot, PAIR, EPOCH, snapshot.revision(), "X", NOW);
        assertTrue(OperationalCommandPolicy.isAllowed(line));
        assertFalse(OperationalCommandPolicy.isAllowed(line + "\nPING_WATCH"));
        assertFalse(OperationalCommandPolicy.isAllowed(MonogramPreferenceCommand.PREFIX + "broken"));
        byte[] bytes = Base64.getDecoder().decode(line.substring(MonogramPreferenceCommand.PREFIX.length()));
        String trailing = MonogramPreferenceCommand.PREFIX + Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, bytes.length + 1));
        assertThrows(IllegalArgumentException.class, () -> MonogramPreferenceCommand.parse(trailing));
        assertThrows(IllegalArgumentException.class, () -> MonogramPreferenceCommand.parse(line + "="));
        assertThrows(IllegalArgumentException.class, () -> MonogramPreferenceCommand.parse(MonogramPreferenceCommand.PREFIX + "A".repeat(400)));
    }
    @Test public void dispatcherScopesMirrorAndNeverPublishesLocalAsNativeReceipt() throws Exception {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        dispatcher.updateConnectionState(false, "test");
        var value = seeded(); value.prepare(command(value, "X"), EPOCH, UUID.randomUUID(), NOW);
        var local = value.snapshot(EPOCH);
        dispatcher.observeMonogramMirror(local);
        assertNull(dispatcher.monogramMirror(PAIR, EPOCH));
        dispatcher.updateConnectionState(true, "test");
        try {
            dispatcher.observeMonogramMirror(local);
            assertEquals(local, dispatcher.monogramMirror(PAIR, EPOCH));
            assertNull(dispatcher.monogramPreferences(PAIR, EPOCH));
            assertNull(dispatcher.monogramMirror(UUID.randomUUID(), EPOCH));
            assertNull(dispatcher.monogramMirror(PAIR, UUID.randomUUID()));
            value.observe(List.of(new MonogramPreferenceCodec.Report("X", local.value().sourceTimestamp())), NOW + 1);
            var remote = value.snapshot(EPOCH); dispatcher.observeMonogramMirror(remote);
            dispatcher.observeMonogramMirror(local); // delayed publication cannot undo provenance.
            assertEquals(remote, dispatcher.monogramMirror(PAIR, EPOCH));
        } finally { dispatcher.updateConnectionState(false, "test"); }
        assertNull(dispatcher.monogramMirror(PAIR, EPOCH));
    }
}
