package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable ordered incoming SY sessions; publishes collection facts only at successful END. */
final class ClockFaceSyncReceiver {
    private static final int MAX_BATCHES = 4096;
    private final ClockFaceSyncJournal journal;
    private final Path directory;
    private final String pairing;
    private String factsFingerprint;
    private CollectionFacts cachedFacts;
    private boolean maintenanceDeferred;

    private record CollectionFacts(int faces, boolean complete, long observedAt) { }

    record Receipt(String stage, boolean duplicate, int batches, int changes,
                   int observedFaces, boolean complete, long observedAt) { }

    ClockFaceSyncReceiver(Path journalRoot, Path stateRoot, String pairing) {
        journal = new ClockFaceSyncJournal(journalRoot, pairing);
        this.pairing = pairing;
        directory = stateRoot.resolve(pairing);
    }

    synchronized Receipt accept(byte[] packet, long receivedAt) throws IOException {
        if (receivedAt <= 0) throw new IllegalArgumentException("Invalid collection observation time");
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(packet)) {
            State state = readState();
            String session = frame.sessionText();
            if (frame.messageId == ClockFaceSyncAccept.START) {
                if (session.equals(state.finishedSession)) {
                    journal.commit(packet);
                    return receipt(state, "FINISHED", true, 0);
                }
                if (session.equals(state.session)) {
                    if (state.reset != frame.resetSync) throw invalid("changed reset mode");
                    journal.commit(packet);
                    return receipt(state, "STARTED", true, 0);
                }
                // The reply advertises no restart/rollback support. A new sender
                // session abandons staged records, preserving the previous snapshot.
                state.session = session;
                state.reset = frame.resetSync;
                state.highestSeenBatch = -1;
                state.records.clear();
                state.changeDigests.clear();
                journal.commit(packet);
                saveState(state);
                compactAfterCommit(state, ClockFaceSyncJournal.sha256(packet));
                return receipt(state, "STARTED", false, 0);
            }
            if (frame.messageId == ClockFaceSyncAccept.END && session.equals(state.finishedSession)) {
                if (state.finishedAborted != (frame.rollback || frame.endHasError)) throw invalid("changed END outcome");
                journal.commit(packet);
                return receipt(state, state.finishedAborted ? "ABORTED" : "COMPLETED", true, 0);
            }
            if (!session.equals(state.session) || state.session.isEmpty()) throw invalid("unknown session");
            if (frame.messageId == ClockFaceSyncAccept.BATCH) {
                long index = frame.batchIndex;
                // END has no final index. Remember even refused batches so END
                // cannot publish a prefix after a known missing/invalid batch.
                long seen = Math.min(index, MAX_BATCHES);
                boolean newHighest = seen > state.highestSeenBatch;
                if (newHighest) state.highestSeenBatch = seen;
                if (index >= MAX_BATCHES || index > state.records.size()) {
                    // Refused gaps must survive a restart as well. For accepted
                    // batches the same fact is persisted together with the record
                    // below, avoiding a second atomic write and directory fsync.
                    if (newHighest) saveState(state);
                    throw invalid("noncontiguous batch index");
                }
                String digest = changeDigest(frame.changes);
                if (index < state.records.size()) {
                    if (!digest.equals(state.changeDigests.get((int) index))) throw invalid("conflicting repeated batch");
                    journal.commit(packet);
                    // Native retransmission may carry a newer header/timestamp.
                    // Repair a pending record reference using that authenticated
                    // envelope, after its identical changes are durable.
                    if (!Files.isRegularFile(journal.recordPath(state.records.get((int) index)))) {
                        state.records.set((int) index, ClockFaceSyncJournal.sha256(packet));
                        saveState(state);
                    }
                    return receipt(state, "BATCH_STORED", true, frame.changes.size());
                }
                try {
                    ClockFaceSyncJournal.validateChanges(frame);
                } catch (IllegalArgumentException invalidBatch) {
                    if (newHighest) saveState(state);
                    throw invalidBatch;
                }
                state.records.add(ClockFaceSyncJournal.sha256(packet));
                state.changeDigests.add(digest);
                // Write the record reference before its payload. A crash or failed
                // journal write leaves a missing record, so END cannot publish a
                // prefix. The exact retransmission can complete that pending record.
                saveState(state);
                journal.commitValidated(packet, frame.changes.size());
                return receipt(state, "BATCH_STORED", false, frame.changes.size());
            }
            journal.commit(packet);
            int batches = state.records.size();
            boolean aborted = frame.rollback || frame.endHasError;
            if (!aborted) {
                if (state.records.size() <= state.highestSeenBatch) throw invalid("unresolved batch gap at END");
                ClockFaceCollection collection = project(state);
                collection.observedAt = receivedAt;
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("pairing", pairing);
                envelope.put("session", session);
                envelope.put("collection", collection.encode());
                byte[] encoded = AppleBinaryPropertyList.encode(envelope);
                try {
                    String snapshot = ClockFaceSyncJournal.sha256(encoded);
                    ClockFaceSyncJournal.atomicWrite(directory.resolve(snapshot + ".snapshot"), encoded);
                    state.snapshot = snapshot;
                } finally { Arrays.fill(encoded, (byte) 0); }
            }
            state.finishedSession = state.session;
            state.finishedAborted = aborted;
            state.session = "";
            state.records.clear(); state.changeDigests.clear();
            state.highestSeenBatch = -1;
            saveState(state);
            Receipt saved = receipt(state, aborted ? "ABORTED" : "COMPLETED", false, 0);
            compactAfterCommit(state, ClockFaceSyncJournal.sha256(packet));
            return new Receipt(saved.stage(), false, batches, 0, saved.observedFaces(), saved.complete(), saved.observedAt());
        }
    }

    synchronized ClockFaceCollection snapshot() throws IOException { return readSnapshot(readState().snapshot); }

    /** Explicit owner-thread startup maintenance; constructing a read-only receiver never deletes files. */
    synchronized int compactJournal() throws IOException {
        if (!Files.exists(directory.resolve("session-state.bplist"))) return 0;
        return compact(readState(), null);
    }

    synchronized boolean takeJournalMaintenanceFailure() {
        boolean deferred = maintenanceDeferred;
        maintenanceDeferred = false;
        return deferred;
    }

    private void compactAfterCommit(State state, String envelopeDigest) {
        try { compact(state, envelopeDigest); }
        catch (IOException | IllegalArgumentException failure) {
            // Cleanup cannot revoke a durable successful END or its native ACK.
            maintenanceDeferred = true;
        }
    }

    private int compact(State state, String envelopeDigest) throws IOException {
        ClockFaceCollection collection = readSnapshot(state.snapshot);
        // Do not retire repair evidence until both the snapshot and its external
        // resources are intact. Active batches remain referenced even across gaps.
        for (String hash : new HashSet<>(collection.packageDigests.values())) {
            byte[] bytes = collection.packageStore.read(hash);
            Arrays.fill(bytes, (byte) 0);
        }
        Set<String> retained = new HashSet<>(state.records);
        if (envelopeDigest != null) retained.add(envelopeDigest);
        return journal.discardUnreferenced(retained);
    }

    synchronized String unfinishedSessionForRecovery() throws IOException {
        String session = readState().session;
        return session.isEmpty() ? null : session;
    }

    private ClockFaceCollection project(State state) throws IOException {
        // Native SYReceivingSession invokes the reset delegate at reset START;
        // the full producer need not send an NTK ResetStore object. Stage a
        // fresh phone projection, retaining the prior committed snapshot until END.
        ClockFaceCollection collection = state.reset ? new ClockFaceCollection() : readSnapshot(state.snapshot);
        collection.packageStore = new NativeFacePackageStore(directory.resolve("packages"));
        if (state.reset) collection.hasResetBaseline = true;
        Map<UUID, Partial> partials = new LinkedHashMap<>();
        int messageNumber = 0;
        try {
            for (int index = 0; index < state.records.size(); index++) {
                byte[] bytes = readBounded(journal.recordPath(state.records.get(index)), ClockFaceSyncFrame.MAX_BYTES);
                try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(bytes)) {
                    if (!ClockFaceSyncJournal.sha256(bytes).equals(state.records.get(index))
                            || frame.messageId != ClockFaceSyncAccept.BATCH || frame.batchIndex != index
                            || !frame.sessionText().equals(state.session)
                            || !changeDigest(frame.changes).equals(state.changeDigests.get(index))) throw invalid("stored batch integrity");
                    for (byte[] change : frame.changes) {
                        try (NtkSyncMessageCodec.Message message = ClockFaceSyncFrame.decodeChange(change)) {
                            if (message.type() == -1) {
                                if (messageNumber != 0 || !state.reset) throw invalid("unexpected reset marker");
                            }
                            messageNumber++;
                            if (!message.multipart()) collection.apply(message);
                            else {
                                if (message.wideLoadId() == null || message.numberOfParts() < 1
                                        || message.numberOfParts() > 128 || message.partNumber() >= message.numberOfParts()) {
                                    throw invalid("wide-load metadata");
                                }
                                Partial partial = partials.get(message.wideLoadId());
                                if (partial == null) {
                                    if (partials.size() >= 16) throw invalid("wide-load count");
                                    partial = new Partial(message); partials.put(message.wideLoadId(), partial);
                                }
                                long buffered = 0;
                                for (Partial staged : partials.values()) buffered += staged.total;
                                if (partial.parts[(int) message.partNumber()] == null
                                        && buffered > 32L * 1024 * 1024 - message.payloadLength()) throw invalid("wide-load aggregate bound");
                                if (partial.add(message)) {
                                    try (NtkSyncMessageCodec.Message whole = partial.finish()) { collection.apply(whole); }
                                    partial.close(); partials.remove(message.wideLoadId());
                                }
                            }
                        }
                    }
                } finally { Arrays.fill(bytes, (byte) 0); }
            }
            if (!partials.isEmpty()) throw invalid("incomplete native wide-load");
            return collection;
        } finally { for (Partial partial : partials.values()) partial.close(); }
    }

    private Receipt receipt(State state, String stage, boolean duplicate, int changes) throws IOException {
        CollectionFacts facts;
        if (state.snapshot.isEmpty()) facts = new CollectionFacts(0, false, 0);
        else {
            // Cache only immutable scalars. Every hit still reads bounded bytes
            // and verifies their content hash; mutable projections are never reused.
            byte[] bytes = verifiedSnapshotBytes(state.snapshot);
            try {
                if (!state.snapshot.equals(factsFingerprint)) {
                    ClockFaceCollection collection = decodeSnapshot(bytes);
                    cachedFacts = new CollectionFacts(collection.configurations.size(),
                            collection.complete(), collection.observedAt);
                    factsFingerprint = state.snapshot;
                }
                facts = cachedFacts;
            } finally { Arrays.fill(bytes, (byte) 0); }
        }
        return new Receipt(stage, duplicate, state.records.size(), changes, facts.faces(),
                facts.complete(), facts.observedAt());
    }

    private State readState() throws IOException {
        Path file = directory.resolve("session-state.bplist");
        if (!Files.exists(file)) return new State();
        Map<?, ?> map = map(AppleBinaryPropertyList.decode(readBounded(file, 1024 * 1024)));
        boolean legacy = Long.valueOf(1).equals(map.get("version"));
        Set<String> keys = legacy ? Set.of("version", "pairing", "snapshot", "session", "reset", "records",
                "changeDigests", "finishedSession", "finishedAborted") : Set.of("version", "pairing", "snapshot",
                "session", "reset", "records", "changeDigests", "finishedSession", "finishedAborted", "observedBatchCount");
        if (!map.keySet().equals(keys) || !legacy && !Long.valueOf(2).equals(map.get("version"))
                || !pairing.equals(map.get("pairing"))) throw invalid("state schema/pair");
        State state = new State();
        state.snapshot = text(map, "snapshot"); if (!state.snapshot.isEmpty()) digest(state.snapshot);
        state.session = text(map, "session"); state.finishedSession = text(map, "finishedSession");
        state.reset = bool(map, "reset"); state.finishedAborted = bool(map, "finishedAborted");
        for (Object item : list(map.get("records"))) state.records.add(digest(item));
        for (Object item : list(map.get("changeDigests"))) state.changeDigests.add(digest(item));
        // Legacy active sessions did not persist refused indices; require a new
        // native START rather than silently publishing an unknown prefix.
        if (legacy) state.highestSeenBatch = state.session.isEmpty() ? -1 : MAX_BATCHES;
        else if (map.get("observedBatchCount") instanceof Long count && count >= 0 && count <= MAX_BATCHES + 1L) {
            state.highestSeenBatch = count - 1;
        } else throw invalid("highest batch index");
        if (state.records.size() != state.changeDigests.size()
                || state.session.isEmpty() && (!state.records.isEmpty() || state.highestSeenBatch != -1)
                || state.highestSeenBatch < state.records.size() - 1) throw invalid("state record count");
        return state;
    }

    private void saveState(State state) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", 2L); map.put("pairing", pairing); map.put("snapshot", state.snapshot);
        map.put("session", state.session); map.put("reset", state.reset); map.put("records", state.records);
        map.put("changeDigests", state.changeDigests); map.put("finishedSession", state.finishedSession);
        map.put("finishedAborted", state.finishedAborted);
        // Store the highest observed index plus one; zero means no batch seen.
        map.put("observedBatchCount", state.highestSeenBatch + 1);
        byte[] bytes = AppleBinaryPropertyList.encode(map);
        try { ClockFaceSyncJournal.atomicWrite(directory.resolve("session-state.bplist"), bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private ClockFaceCollection readSnapshot(String fingerprint) throws IOException {
        if (fingerprint.isEmpty()) return new ClockFaceCollection();
        byte[] bytes = verifiedSnapshotBytes(fingerprint);
        try { return decodeSnapshot(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private byte[] verifiedSnapshotBytes(String fingerprint) throws IOException {
        byte[] bytes = readBounded(directory.resolve(digest(fingerprint) + ".snapshot"), 1024 * 1024);
        if (!ClockFaceSyncJournal.sha256(bytes).equals(fingerprint)) {
            Arrays.fill(bytes, (byte) 0);
            throw invalid("snapshot digest");
        }
        return bytes;
    }

    private ClockFaceCollection decodeSnapshot(byte[] bytes) {
            Map<?, ?> envelope = map(AppleBinaryPropertyList.decode(bytes));
            if (!envelope.keySet().equals(Set.of("pairing", "session", "collection"))
                    || !pairing.equals(envelope.get("pairing"))) throw invalid("snapshot pair");
            text(envelope, "session");
            ClockFaceCollection collection = ClockFaceCollection.decode(map(envelope.get("collection")));
            collection.packageStore = new NativeFacePackageStore(directory.resolve("packages"));
            return collection;
    }

    private static byte[] readBounded(Path file, int maximum) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > maximum) throw new IOException("Invalid native state file length");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > maximum) throw new IOException("Native state file grew beyond bound");
        return bytes;
    }
    private static String changeDigest(List<byte[]> changes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (byte[] change : changes) {
                for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (change.length >>> shift));
                digest.update(change);
            }
            byte[] value = digest.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte unit : value) hex.append(Character.forDigit((unit >>> 4) & 15, 16))
                    .append(Character.forDigit(unit & 15, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static Map<?, ?> map(Object value) { if (!(value instanceof Map<?, ?> map)) throw invalid("dictionary"); return map; }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list) || list.size() > MAX_BATCHES) throw invalid("list"); return list;
    }
    private static String text(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String value) || value.length() > 128 || value.indexOf('\0') >= 0) throw invalid("text"); return value;
    }
    private static boolean bool(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof Boolean value)) throw invalid("boolean"); return value;
    }
    private static String digest(Object value) {
        if (!(value instanceof String text) || !text.matches("[0-9a-f]{64}")) throw invalid("digest"); return text;
    }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("Invalid native clockface " + detail); }
    private static final class State {
        String snapshot = "", session = "", finishedSession = "";
        boolean reset, finishedAborted;
        long highestSeenBatch = -1;
        final List<String> records = new ArrayList<>(), changeDigests = new ArrayList<>();
    }

    private static final class Partial implements AutoCloseable {
        final NtkSyncMessageCodec.Message first;
        final byte[][] parts;
        long total;
        int received;
        Partial(NtkSyncMessageCodec.Message message) {
            first = new NtkSyncMessageCodec.Message(message.type(), message.faceUuid(), null, message.label(),
                    message.progress(), message.complicationClientId(), message.complicationCollectionIdentifier(),
                    message.complicationFamily(), message.wideLoadId(), message.numberOfParts(), 0, message.maxPartSize(),
                    message.complicationDescriptor());
            parts = new byte[(int) message.numberOfParts()][];
        }
        boolean add(NtkSyncMessageCodec.Message message) {
            if (message.type() != first.type() || !java.util.Objects.equals(message.faceUuid(), first.faceUuid())
                    || message.numberOfParts() != parts.length || message.maxPartSize() != first.maxPartSize()
                    || !java.util.Objects.equals(message.complicationClientId(), first.complicationClientId())
                    || !java.util.Objects.equals(message.complicationCollectionIdentifier(), first.complicationCollectionIdentifier())
                    || !java.util.Objects.equals(message.complicationFamily(), first.complicationFamily())
                    || (first.complicationDescriptor() == null ? message.complicationDescriptor() != null
                        : !first.complicationDescriptor().same(message.complicationDescriptor()))) throw invalid("inconsistent wide-load");
            byte[] bytes = message.payload();
            long maxPart = first.maxPartSize() == 0 ? 0x465000L : first.maxPartSize();
            int number = (int) message.partNumber();
            if (bytes == null || bytes.length == 0 || bytes.length > maxPart
                    || number < parts.length - 1 && bytes.length != maxPart) {
                if (bytes != null) Arrays.fill(bytes, (byte) 0);
                throw invalid("wide-load part length");
            }
            if (parts[number] != null) {
                boolean same = Arrays.equals(parts[number], bytes); Arrays.fill(bytes, (byte) 0);
                if (!same) throw invalid("conflicting wide-load part");
            } else {
                total += bytes.length;
                if (total > 32 * 1024 * 1024) { Arrays.fill(bytes, (byte) 0); throw invalid("wide-load total bound"); }
                parts[number] = bytes; received++;
            }
            return received == parts.length;
        }
        NtkSyncMessageCodec.Message finish() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
            for (byte[] part : parts) bytes.write(part, 0, part.length);
            byte[] payload = bytes.toByteArray();
            try {
                return new NtkSyncMessageCodec.Message(first.type(), first.faceUuid(), payload, first.label(),
                        first.progress(), first.complicationClientId(), first.complicationCollectionIdentifier(),
                        first.complicationFamily(), null, 0, 0, 0, first.complicationDescriptor());
            } finally { Arrays.fill(payload, (byte) 0); }
        }
        @Override public void close() { first.close(); for (byte[] part : parts) if (part != null) Arrays.fill(part, (byte) 0); }
    }
}
