package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ClockFaceSyncReceiverTest {
    private static final String PAIR = "5e111a6b-a56a-5b3a-b795-1c9799c1d92a";
    private static final String FACE = "3122508d-c08f-536e-bc37-d7f82cb8675d";
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void warmReceiptCacheStillChecksBytesAndNeverSharesMutableProjection() throws Exception {
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncReceiver receiver = completeSmallSession(root);
        assertEquals(1, receiver.accept(fixture("end"), 1100).observedFaces());
        receiver.snapshot().configurations.clear();
        assertEquals(1, receiver.accept(fixture("end"), 1101).observedFaces());
        Path snapshot;
        try (var files = Files.list(root.resolve("state").resolve(PAIR))) {
            snapshot = files.filter(p -> p.toString().endsWith(".snapshot")).findFirst().orElseThrow();
        }
        byte[] original = Files.readAllBytes(snapshot), corrupt = original.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(snapshot, corrupt);
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(fixture("end"), 1102));
        Files.write(snapshot, original);
        assertEquals(1, receiver.accept(fixture("end"), 1103).observedFaces());
        receiver.accept(withSession(start(true), "second-local-session"), 1200);
        receiver.accept(withSession(batch(0, change(5, "[]".getBytes(StandardCharsets.UTF_8), 0, 0, 0)),
                "second-local-session"), 1201);
        var receipt = receiver.accept(withSession(fixture("end"), "second-local-session"), 1202);
        assertEquals(0, receipt.observedFaces());
        assertEquals(1202, receipt.observedAt());
    }

    @Test public void compactionRetainsPendingRecordsAcrossRestartAndRefusedGap() throws Exception {
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncReceiver receiver = receiver(root);
        receiver.accept(start(true), 1000);
        byte[] pending = batch(0, actualChange());
        receiver.accept(pending, 1001);
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(batch(2, actualChange()), 1002));
        Path path = root.resolve("journal").resolve(PAIR).resolve(ClockFaceSyncJournal.sha256(pending) + ".sydata");
        receiver(root).compactJournal();
        assertTrue(Files.exists(path));
        assertThrows(IllegalArgumentException.class, () -> receiver(root).accept(fixture("end"), 1003));
        assertTrue(Files.exists(path));
        receiver(root).accept(batch(1, actualChange()), 1004);
        receiver(root).accept(batch(2, actualChange()), 1005);
        assertEquals("COMPLETED", receiver(root).accept(fixture("end"), 1006).stage());
        assertFalse(Files.exists(path));
    }

    @Test public void missingPackageDefersCleanupWithoutRevokingDurableReceipt() throws Exception {
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncReceiver receiver = completeSmallSession(root);
        String hash = receiver.snapshot().packageDigests.get(FACE);
        Path packagePath = root.resolve("state").resolve(PAIR).resolve("packages").resolve(hash + ".watchface");
        byte[] bytes = Files.readAllBytes(packagePath);
        Files.delete(packagePath);
        receiver.accept(withSession(start(false), "resource-repair-session"), 1100);
        assertTrue(receiver.takeJournalMaintenanceFailure());
        assertEquals("COMPLETED", receiver.accept(withSession(fixture("end"), "resource-repair-session"), 1101).stage());
        assertTrue(receiver.takeJournalMaintenanceFailure());
        assertThrows(IOException.class, receiver::compactJournal);
        assertEquals(1, receiver.snapshot().configurations.size());
        Files.write(packagePath, bytes);
        assertTrue(receiver.compactJournal() > 0);
        assertArrayEquals(bytes, receiver.snapshot().nativePackage(FACE));
    }

    @Test public void journalCleanupIgnoresUnknownFilesDirectoriesAndSymlinks() throws Exception {
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncJournal journal = new ClockFaceSyncJournal(root, PAIR);
        byte[] packet = fixture("start");
        journal.commit(packet);
        Path directory = root.resolve(PAIR), unrelated = directory.resolve("notes.txt");
        Files.writeString(unrelated, "keep");
        Path folder = directory.resolve("a".repeat(64) + ".sydata");
        Files.createDirectory(folder);
        Path target = root.resolve("outside"); Files.writeString(target, "keep target");
        Path link = directory.resolve("b".repeat(64) + ".sydata");
        Files.createSymbolicLink(link, target);
        assertEquals(1, journal.discardUnreferenced(java.util.Set.of()));
        assertTrue(Files.exists(unrelated)); assertTrue(Files.isDirectory(folder));
        assertTrue(Files.isSymbolicLink(link)); assertEquals("keep target", Files.readString(target));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ClockFaceSyncJournal.sha256(new byte[0]));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ClockFaceSyncJournal.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    private static ClockFaceSyncReceiver completeSmallSession(Path root) throws Exception {
        ClockFaceSyncReceiver receiver = receiver(root);
        receiver.accept(start(true), 1000);
        receiver.accept(batch(0, change(0, zip(config(), "face.json"), 0, 0, 0),
                change(5, ("[\"" + FACE + "\"]").getBytes(StandardCharsets.UTF_8), 0, 0, 0),
                change(4, FACE.getBytes(StandardCharsets.UTF_8), 0, 0, 0)), 1001);
        assertTrue(receiver.accept(fixture("end"), 1002).complete());
        return receiver;
    }

    private static byte[] withSession(byte[] packet, String session) throws Exception {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(packet)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {(byte) frame.messageId, 0, 0}); field(out, 1, frame.header);
            byte[] id = session.getBytes(StandardCharsets.UTF_8);
            if (frame.messageId == ClockFaceSyncAccept.START) {
                out.write(0x10); out.write(frame.resetSync ? 1 : 0); field(out, 3, id);
            } else {
                field(out, 2, id);
                if (frame.messageId == ClockFaceSyncAccept.BATCH) {
                    out.write(0x18); varint(out, frame.batchIndex);
                    for (byte[] change : frame.changes) field(out, 4, change);
                }
            }
            return out.toByteArray();
        }
    }

    @Test public void completeActualModernResetSessionNeedsNoResetStoreObjectAndReloadsAfterEnd() throws Exception {
        Path root = temporary.newFolder().toPath();
        List<byte[]> frames = actualFullSession();
        ClockFaceSyncReceiver receiver = receiver(root);
        receiver.accept(frames.get(0), 1000);
        for (int i = 1; i < frames.size() - 1; i++) receiver.accept(frames.get(i), 1000 + i);
        assertEquals(0, receiver(root).snapshot().configurations.size());
        var receipt = receiver(root).accept(frames.get(frames.size() - 1), 2000);
        assertEquals("COMPLETED", receipt.stage());
        assertEquals(209, receipt.batches());
        assertTrue(receipt.complete());
        var snapshot = receiver(root).snapshot();
        assertEquals(1, snapshot.configurations.size());
        assertTrue(snapshot.ordered.contains(snapshot.selected));
        assertEquals("com.apple.NTKLeghornFaceBundle",
                NtkFacePayloadCodec.configurationBundle(snapshot.configurations.get(snapshot.selected)));
        assertEquals(2000, snapshot.observedAt);
        assertTrue(snapshot.complicationCatalogComplete);
        assertTrue(snapshot.complicationCatalog.size() > 20);
        var observation = ClockFaceObservationCodec.fromCollection(UUID.fromString(PAIR), UUID.randomUUID(), snapshot);
        assertEquals(NativeComplicationCatalog.forObservation(snapshot.complicationCatalog, snapshot.configurations),
                ClockFaceObservationCodec.decode(ClockFaceObservationCodec.encode(observation)).complicationCatalog());
    }

    @Test public void knownRefusedGapPersistsAndEndCannotPublishPrefixButRetransmissionCanRepair() throws Exception {
        Path root = temporary.newFolder().toPath();
        List<byte[]> frames = actualFullSession();
        receiver(root).accept(frames.get(0), 1000);
        for (int i = 1; i <= 67; i++) receiver(root).accept(frames.get(i), 1000 + i);
        // Skip actual batch67, observe68; then END must refuse even after process reload.
        assertThrows(IllegalArgumentException.class, () -> receiver(root).accept(frames.get(69), 1100));
        assertThrows(IllegalArgumentException.class, () -> receiver(root).accept(frames.get(frames.size() - 1), 1101));
        assertEquals(0, receiver(root).snapshot().configurations.size());
        for (int i = 68; i < frames.size() - 1; i++) receiver(root).accept(frames.get(i), 1200 + i);
        assertTrue(receiver(root).accept(frames.get(frames.size() - 1), 2000).complete());
    }

    private List<byte[]> actualFullSession() throws Exception {
        List<byte[]> result = new ArrayList<>();
        for (String folder : List.of("clockface-355-session", "clockface-355-descriptors")) {
            Path path = Path.of(getClass().getResource("/" + folder).toURI());
            try (var files = Files.list(path)) {
                for (Path file : files.toList()) result.add(Files.readAllBytes(file));
            }
        }
        result.sort(java.util.Comparator.comparingLong(bytes -> {
            try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(bytes)) {
                return frame.messageId == ClockFaceSyncAccept.START ? -1
                        : frame.messageId == ClockFaceSyncAccept.END ? Long.MAX_VALUE : frame.batchIndex;
            }
        }));
        assertEquals(211, result.size());
        return result;
    }

    @Test public void pendingPayloadCannotPublishAfterRestartAndExactRetransmissionRepairsIt() throws Exception {
        Path root = temporary.newFolder().toPath();
        List<byte[]> frames = actualFullSession();
        receiver(root).accept(frames.get(0), 1000);
        receiver(root).accept(frames.get(1), 1001);
        Path record = root.resolve("journal").resolve(PAIR)
                .resolve(ClockFaceSyncJournal.sha256(frames.get(1)) + ".sydata");
        // Model a process death after the durable state reference and before
        // its journal payload became durable.
        Files.delete(record);
        assertThrows(IOException.class, () -> receiver(root).accept(frames.get(frames.size() - 1), 1100));
        assertEquals(0, receiver(root).snapshot().configurations.size());
        byte[] retransmission;
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(frames.get(1))) {
            byte[] header = frame.header.clone();
            // The header's first field is its fixed64 timestamp. Alter its
            // least significant byte to model native retransmission metadata.
            header[1] ^= 1;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {0x67, 0, 0});
            field(out, 1, header); field(out, 2, frame.sessionId);
            out.write(0x18); varint(out, frame.batchIndex);
            for (byte[] change : frame.changes) field(out, 4, change);
            retransmission = out.toByteArray();
        }
        assertNotEquals(ClockFaceSyncJournal.sha256(frames.get(1)), ClockFaceSyncJournal.sha256(retransmission));
        assertTrue(receiver(root).accept(retransmission, 1101).duplicate());
        for (int i = 2; i < frames.size(); i++) receiver(root).accept(frames.get(i), 1200 + i);
        assertTrue(receiver(root).snapshot().complete());
    }

    @Test public void failedJournalWriteHasNoReceiptAndItsPendingBatchCanBeRetried() throws Exception {
        Path root = temporary.newFolder().toPath();
        List<byte[]> frames = actualFullSession();
        receiver(root).accept(frames.get(0), 1000);
        Path record = root.resolve("journal").resolve(PAIR)
                .resolve(ClockFaceSyncJournal.sha256(frames.get(1)) + ".sydata");
        Files.createDirectory(record);
        assertThrows(IOException.class, () -> receiver(root).accept(frames.get(1), 1001));
        assertThrows(IOException.class, () -> receiver(root).accept(frames.get(frames.size() - 1), 1100));
        assertEquals(0, receiver(root).snapshot().configurations.size());
        Files.delete(record);
        assertTrue(receiver(root).accept(frames.get(1), 1101).duplicate());
        for (int i = 2; i < frames.size(); i++) receiver(root).accept(frames.get(i), 1200 + i);
        assertTrue(receiver(root).snapshot().complete());
    }

    @Test public void malformedBatchStillPersistsGapBeforeEndAndAcceptsCorrectedRetry() throws Exception {
        Path root = temporary.newFolder().toPath();
        receiver(root).accept(fixture("start"), 1000);
        assertThrows(IllegalArgumentException.class,
                () -> receiver(root).accept(batch(0, new byte[] {0}), 1001));
        assertThrows(IllegalArgumentException.class, () -> receiver(root).accept(fixture("end"), 1002));
        assertEquals(0, receiver(root).snapshot().configurations.size());
        receiver(root).accept(batch(0, actualChange()), 1003);
        assertEquals("COMPLETED", receiver(root).accept(fixture("end"), 1004).stage());
    }

    @Test public void legacyUnfinishedSessionRequiresFreshStartAndPreservesCommittedInventory() throws Exception {
        Path root = temporary.newFolder().toPath();
        receiver(root).accept(fixture("start"), 1000);
        receiver(root).accept(batch(0, actualChange()), 1001);
        Path state = root.resolve("state").resolve(PAIR).resolve("session-state.bplist");
        @SuppressWarnings("unchecked") Map<String, Object> saved = new LinkedHashMap<>((Map<String, Object>)
                AppleBinaryPropertyList.decode(Files.readAllBytes(state)));
        saved.remove("observedBatchCount"); saved.put("version", 1L);
        Files.write(state, AppleBinaryPropertyList.encode(saved));
        assertThrows(IllegalArgumentException.class, () -> receiver(root).accept(fixture("end"), 1002));
        assertEquals(0, receiver(root).snapshot().configurations.size());
        List<byte[]> frames = actualFullSession();
        receiver(root).accept(frames.get(0), 2000);
        for (int i = 1; i < frames.size(); i++) receiver(root).accept(frames.get(i), 2000 + i);
        assertTrue(receiver(root).snapshot().complete());
    }

    @Test public void recoveryIdentifierComesOnlyFromDurableUnfinishedSessionOfTheSamePair() throws Exception {
        Path root = temporary.newFolder().toPath();
        assertNull(receiver(root).unfinishedSessionForRecovery());
        List<byte[]> frames = actualFullSession();
        receiver(root).accept(frames.get(0), 1000);
        String expected;
        try (ClockFaceSyncFrame start = ClockFaceSyncFrame.parse(frames.get(0))) { expected = start.sessionText(); }
        assertEquals(expected, receiver(root).unfinishedSessionForRecovery());
        assertNull(new ClockFaceSyncReceiver(root.resolve("journal"), root.resolve("state"),
                UUID.randomUUID().toString()).unfinishedSessionForRecovery());
        receiver(root).accept(frames.get(1), 1001);
        byte[] end = frames.get(frames.size() - 1);
        byte[] aborted = Arrays.copyOf(end, end.length + 2); aborted[end.length] = 0x20; aborted[end.length + 1] = 1;
        receiver(root).accept(aborted, 1002);
        assertNull(receiver(root).unfinishedSessionForRecovery());
    }

    @Test public void actualConfigurationBecomesVisibleOnlyAfterSuccessfulEndAndReload() throws Exception {
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncReceiver receiver = receiver(root);
        receiver.accept(fixture("start"), 1000);
        receiver.accept(batch(0, actualChange()), 1001);
        assertEquals(0, receiver.snapshot().configurations.size());
        assertFalse(receiver.snapshot().complete());
        var completed = receiver(root).accept(fixture("end"), 1002);
        assertEquals("COMPLETED", completed.stage());
        assertEquals(1, completed.observedFaces());
        assertFalse(completed.complete());
        ClockFaceCollection snapshot = receiver(root).snapshot();
        assertArrayEquals(config(), snapshot.configurations.get(FACE));
        assertEquals(1002, snapshot.observedAt);
        assertFalse(snapshot.selectionKnown);
        assertFalse(snapshot.orderKnown);
        assertTrue(receiver(root).accept(fixture("end"), 1003).duplicate());
        assertEquals(1002, receiver(root).snapshot().observedAt);
    }

    @Test public void missingOrConflictingBatchCannotAdvanceOrPublishCollection() throws Exception {
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncReceiver receiver = receiver(root);
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(batch(0, actualChange()), 1000));
        receiver.accept(fixture("start"), 1001);
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(batch(1, actualChange()), 1002));
        receiver.accept(batch(0, actualChange()), 1003);
        assertTrue(receiver(root).accept(batch(0, actualChange()), 1004).duplicate());
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(batch(0, change(3, null, 0, 0, 0)), 1005));
        assertEquals(0, receiver(root).snapshot().configurations.size());
        assertThrows(IllegalArgumentException.class, () -> receiver(root).accept(fixture("end"), 1006));
        receiver(root).accept(batch(1, actualChange()), 1007);
        assertEquals(1, receiver(root).accept(fixture("end"), 1008).observedFaces());
    }

    @Test public void rollbackAndErrorEndDiscardStagingAndRetainConfirmedSnapshot() throws Exception {
        for (byte[] suffix : new byte[][] {{0x20, 1}, {0x1a, 0}}) {
            Path root = temporary.newFolder().toPath();
            ClockFaceSyncReceiver receiver = receiver(root);
            receiver.accept(fixture("start"), 1000);
            receiver.accept(batch(0, actualChange()), 1001);
            byte[] end = fixture("end");
            byte[] cancelled = Arrays.copyOf(end, end.length + suffix.length);
            System.arraycopy(suffix, 0, cancelled, end.length, suffix.length);
            assertEquals("ABORTED", receiver.accept(cancelled, 1002).stage());
            assertEquals(0, receiver(root).snapshot().configurations.size());
            assertTrue(receiver(root).accept(cancelled, 1003).duplicate());
            assertThrows(IllegalArgumentException.class, () -> receiver(root).accept(end, 1004));
        }
    }

    @Test public void fullResetProjectionUsesZipJsonOrderAndPayloadSelection() throws Exception {
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncReceiver receiver = receiver(root);
        receiver.accept(start(true), 1000);
        byte[] order = ("[\"" + FACE.toUpperCase(java.util.Locale.ROOT) + "\"]").getBytes(StandardCharsets.UTF_8);
        receiver.accept(batch(0, change(-1, null, 0, 0, 0), change(0, zip(config(), "face.json"), 0, 0, 0),
                change(5, order, 0, 0, 0), change(4, FACE.getBytes(StandardCharsets.UTF_8), 0, 0, 0)), 1001);
        assertFalse(receiver.snapshot().complete());
        assertTrue(receiver.accept(fixture("end"), 1002).complete());
        ClockFaceCollection snapshot = receiver(root).snapshot();
        assertEquals(List.of(FACE), snapshot.ordered);
        assertEquals(FACE, snapshot.selected);
        assertArrayEquals(config(), snapshot.configurations.get(FACE));
    }

    @Test public void nativeZeroBasedWideLoadIsReassembledAndMissingPartNeverPublishes() throws Exception {
        byte[] payload = zip(config(), "face.json");
        int partSize = 256, parts = (payload.length + partSize - 1) / partSize;
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncReceiver receiver = receiver(root);
        receiver.accept(start(true), 1000);
        List<byte[]> changes = new ArrayList<>();
        changes.add(change(-1, null, 0, 0, 0));
        for (int part = 0; part < parts; part++) {
            byte[] data = Arrays.copyOfRange(payload, part * partSize, Math.min(payload.length, (part + 1) * partSize));
            changes.add(change(0, data, parts, part, partSize));
        }
        changes.add(change(5, ("[\"" + FACE + "\"]").getBytes(StandardCharsets.UTF_8), 0, 0, 0));
        changes.add(change(4, FACE.getBytes(StandardCharsets.UTF_8), 0, 0, 0));
        receiver.accept(batch(0, changes.toArray(byte[][]::new)), 1001);
        assertTrue(receiver.accept(fixture("end"), 1002).complete());
        assertArrayEquals(config(), receiver(root).snapshot().configurations.get(FACE));

        Path missing = temporary.newFolder().toPath();
        ClockFaceSyncReceiver incomplete = receiver(missing);
        incomplete.accept(start(true), 1000);
        incomplete.accept(batch(0, change(-1, null, 0, 0, 0),
                change(0, Arrays.copyOfRange(payload, 0, partSize), parts, 0, partSize)), 1001);
        assertThrows(IllegalArgumentException.class, () -> incomplete.accept(fixture("end"), 1002));
        assertEquals(0, receiver(missing).snapshot().configurations.size());
    }

    @Test public void storageFailureDoesNotAdvanceSessionAndCorruptJournalCannotPublish() throws Exception {
        Path root = temporary.newFolder().toPath();
        Files.createDirectories(root.resolve("state"));
        Files.write(root.resolve("state").resolve(PAIR), new byte[] {1});
        assertThrows(IOException.class, () -> receiver(root).accept(fixture("start"), 1000));
        Path valid = temporary.newFolder().toPath();
        ClockFaceSyncReceiver receiver = receiver(valid);
        receiver.accept(fixture("start"), 1000);
        byte[] packet = batch(0, actualChange());
        receiver.accept(packet, 1001);
        Path record = valid.resolve("journal").resolve(PAIR).resolve(ClockFaceSyncJournal.sha256(packet) + ".sydata");
        byte[] corrupt = packet.clone(); corrupt[0] ^= 1;
        Files.write(record, corrupt);
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(fixture("end"), 1002));
        assertEquals(0, receiver.snapshot().configurations.size());
    }

    @Test public void nativePayloadRejectsMalformedJsonUuidAndZipTraversal() throws Exception {
        for (String json : List.of("{\"bundle id\":1}", "{\"a\":1,\"a\":2}", "[01]", "[1e999]",
                "[\"\\ud800\"]", "[\"\\uＦＦＦＦ\"]", "[true,]", "{\"a\":NaN}")) {
            assertThrows(IllegalArgumentException.class, () -> NtkFacePayloadCodec.configurationBundle(json.getBytes(StandardCharsets.UTF_8)));
        }
        assertEquals("😀", ((List<?>) BoundedJson.decode("[\"\\ud83d\\ude00\"]".getBytes(StandardCharsets.UTF_8), 64)).get(0));
        assertThrows(IllegalArgumentException.class, () -> NtkFacePayloadCodec.selected("1-1-1-1-1".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> NtkFacePayloadCodec.ordered(("[\"" + FACE + "\",\"" + FACE + "\"]").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> NtkFacePayloadCodec.configurationFromZip(zip(config(), "../face.json")));
        assertThrows(IOException.class, () -> NtkFacePayloadCodec.configurationFromZip(zip(config(), "Resources/face.json")));
    }

    @Test public void largerNtkPlistCallerBoundDoesNotLoosenExistingConsumers() throws Exception {
        int size = 1100 * 1024;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write("bplist00".getBytes(StandardCharsets.US_ASCII));
            out.writeByte(0x4f); out.writeByte(0x12); out.writeInt(size); out.write(new byte[size]);
            int offsets = bytes.size();
            out.writeInt(8); out.write(new byte[6]); out.writeByte(4); out.writeByte(1);
            out.writeLong(1); out.writeLong(0); out.writeLong(offsets);
        }
        byte[] encoded = bytes.toByteArray();
        assertThrows(IllegalArgumentException.class, () -> AppleBinaryPropertyList.decode(encoded));
        assertEquals(size, ((byte[]) AppleBinaryPropertyList.decodeBounded(encoded, ClockFaceSyncFrame.MAX_BYTES)).length);
        assertThrows(IllegalArgumentException.class, () -> AppleBinaryPropertyList.decodeBounded(encoded, Integer.MAX_VALUE));
    }

    private static ClockFaceSyncReceiver receiver(Path root) {
        return new ClockFaceSyncReceiver(root.resolve("journal"), root.resolve("state"), PAIR);
    }
    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/clockface-353-" + name + ".bin"));
    }
    private static byte[] actualChange() throws IOException {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(Files.readAllBytes(Path.of("src/test/resources/clockface-352-config.bin")))) {
            return frame.changes.get(0).clone();
        }
    }
    private static byte[] config() throws IOException {
        try (var message = ClockFaceSyncFrame.decodeChange(actualChange())) { return message.payload(); }
    }
    private static byte[] start(boolean reset) throws IOException {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(fixture("start"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {0x66, 0, 0}); field(out, 1, frame.header); out.write(0x10); out.write(reset ? 1 : 0); field(out, 3, frame.sessionId);
            return out.toByteArray();
        }
    }
    private static byte[] batch(long index, byte[]... changes) throws IOException {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(fixture("start"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {0x67, 0, 0}); field(out, 1, frame.header); field(out, 2, frame.sessionId); out.write(0x18); varint(out, index);
            for (byte[] change : changes) field(out, 4, change);
            return out.toByteArray();
        }
    }
    private static byte[] change(int type, byte[] payload, long parts, long part, long max) throws IOException {
        byte[] actual = actualChange();
        int begin = 0;
        while (begin + 8 <= actual.length && !new String(actual, begin, 8, StandardCharsets.US_ASCII).equals("bplist00")) begin++;
        Map<String, Object> archive = castMap(mutable(AppleBinaryPropertyList.decode(Arrays.copyOfRange(actual, begin, actual.length))));
        @SuppressWarnings("unchecked") List<Object> objects = (List<Object>) archive.get("$objects");
        Map<String, Object> message = castMap(objects.get(((AppleBinaryPropertyList.Uid) castMap(archive.get("$top")).get("root")).value()));
        message.put("messageType", (long) type);
        if (type == -1 || type == 4 || type == 5) message.put("faceUUID", new AppleBinaryPropertyList.Uid(0));
        message.put("payload", payload == null ? new AppleBinaryPropertyList.Uid(0) : append(objects, payload));
        message.put("numberOfParts", append(objects, parts)); message.put("partNumber", append(objects, part));
        message.put("maxPartSize", append(objects, max));
        if (parts > 0) {
            Object faceObject = objects.get(((AppleBinaryPropertyList.Uid) message.get("faceUUID")).value());
            Map<String, Object> uuid = new LinkedHashMap<>(castMap(faceObject));
            UUID id = UUID.fromString("22222222-2222-4222-8222-222222222222");
            uuid.put("NS.uuidbytes", ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
            message.put("wideLoadId", append(objects, uuid));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x08); varint(out, type == 0 ? 0 : type == 3 || type == 7 ? 2 : 1);
        field(out, 2, "fixture-local-only".getBytes(StandardCharsets.US_ASCII));
        field(out, 4, AppleBinaryPropertyList.encode(archive));
        return out.toByteArray();
    }
    private static AppleBinaryPropertyList.Uid append(List<Object> objects, Object value) {
        objects.add(value); return new AppleBinaryPropertyList.Uid(objects.size() - 1);
    }
    private static void field(ByteArrayOutputStream out, int field, byte[] bytes) { varint(out, (field << 3) | 2); varint(out, bytes.length); out.write(bytes, 0, bytes.length); }
    private static void varint(ByteArrayOutputStream out, long value) { while ((value & ~127L) != 0) { out.write(((int) value & 127) | 128); value >>>= 7; } out.write((int) value); }
    private static byte[] zip(byte[] bytes, String entry) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry(entry)); zip.write(bytes); zip.closeEntry(); }
        return out.toByteArray();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Object value) { return (Map<String, Object>) value; }
    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> map) { Map<Object, Object> result = new LinkedHashMap<>(); for (var entry : map.entrySet()) result.put(entry.getKey(), mutable(entry.getValue())); return result; }
        if (value instanceof List<?> list) { List<Object> result = new ArrayList<>(); for (Object item : list) result.add(mutable(item)); return result; }
        return value;
    }
}
