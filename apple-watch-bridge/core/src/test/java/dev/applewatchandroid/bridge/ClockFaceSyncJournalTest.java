package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ClockFaceSyncJournalTest {
    private static final String PAIR = "5e111a6b-a56a-5b3a-b795-1c9799c1d92a";
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void decodesActualWatchFaceConfiguration() throws Exception {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(fixture("config"));
             NtkSyncMessageCodec.Message message = ClockFaceSyncFrame.decodeChange(frame.changes.get(0))) {
            assertEquals(1, message.type());
            assertEquals("UpdateFaceConfiguration", message.typeName());
            assertEquals(UUID.fromString("3122508d-c08f-536e-bc37-d7f82cb8675d"), message.faceUuid());
            assertTrue(new String(message.payload(), StandardCharsets.UTF_8).contains("com.apple.NTKLeghornFaceBundle"));
            assertFalse(message.multipart());
            assertTrue(message.progress() > 0 && message.progress() < 1);
            assertNotNull(ClockFaceSyncAccept.reply(fixture("config")));
        }
    }

    @Test public void decodesNativeMutableDataAndColorPayloadWithoutInventingFaces() throws Exception {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(fixture("descriptors"));
             NtkSyncMessageCodec.Message message = ClockFaceSyncFrame.decodeChange(frame.changes.get(0))) {
            assertEquals(10, message.type());
            assertNull(message.faceUuid());
            assertEquals("WidgetComplications", message.complicationCollectionIdentifier());
            assertEquals("com.apple.weather.watchapp", message.complicationClientId());
            byte[] payload = message.payload();
            assertEquals("bplist00", new String(payload, 0, 8, StandardCharsets.US_ASCII));
            payload[0] = 0;
            assertEquals('b', message.payload()[0]);
        }
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(fixture("colors"));
             NtkSyncMessageCodec.Message message = ClockFaceSyncFrame.decodeChange(frame.changes.get(0))) {
            assertEquals(11, message.type());
            assertEquals("ColorSync", message.typeName());
            assertNull(message.faceUuid());
            assertTrue(message.payload().length > 0);
        }
    }

    @Test public void rejectsTruncatedOverflowAndDuplicateProtobufFields() {
        byte[][] malformed = {
                {0x67, 0, 0, 0x18, (byte) 0x80},
                {0x67, 0, 0, 0x18, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, 2},
                {0x67, 0, 0, 0x0a, 1, 1, 0x0a, 1, 2, 0x12, 1, 65, 0x18, 0},
                {0x67, 0, 0, 0x0a, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 7},
                {0x67, 0, 0, 0x0a, 1, 1, 0x12, 1, (byte) 0xff, 0x18, 0}
        };
        for (byte[] bytes : malformed) {
            assertThrows(IllegalArgumentException.class, () -> ClockFaceSyncFrame.parse(bytes));
            assertNull(ClockFaceSyncAccept.reply(bytes));
        }
    }

    @Test public void rejectsForgedArchiveClassUidTypeAndProgress() throws Exception {
        byte[] archive = archiveFrom(fixture("config"));
        Map<String, Object> root = decoded(archive);
        List<Object> objects = objects(root);
        Map<String, Object> message = message(root);
        Map<String, Object> clazz = castMap(objects.get(((AppleBinaryPropertyList.Uid) message.get("$class")).value()));
        clazz.put("$classname", "UnexpectedExecutableClass");
        byte[] invalidClass = AppleBinaryPropertyList.encode(root);
        assertThrows(IllegalArgumentException.class, () -> NtkSyncMessageCodec.decode(invalidClass));
        root = decoded(archive);
        message = message(root);
        message.put("faceUUID", new AppleBinaryPropertyList.Uid(8191));
        byte[] invalidUid = AppleBinaryPropertyList.encode(root);
        assertThrows(IllegalArgumentException.class, () -> NtkSyncMessageCodec.decode(invalidUid));
        root = decoded(archive);
        message(root).put("messageType", 12L);
        byte[] invalidType = AppleBinaryPropertyList.encode(root);
        assertThrows(IllegalArgumentException.class, () -> NtkSyncMessageCodec.decode(invalidType));
        root = decoded(archive);
        int progress = ((AppleBinaryPropertyList.Uid) message(root).get("progress")).value();
        objects(root).set(progress, 2.0d);
        byte[] invalidProgress = AppleBinaryPropertyList.encode(root);
        assertThrows(IllegalArgumentException.class, () -> NtkSyncMessageCodec.decode(invalidProgress));
    }

    @Test public void duplicateSurvivesNewJournalInstanceAndPairsRemainSeparate() throws Exception {
        Path root = temporary.newFolder().toPath();
        byte[] packet = fixture("config");
        ClockFaceSyncJournal.Receipt first = new ClockFaceSyncJournal(root, PAIR).commit(packet);
        assertFalse(first.duplicate());
        assertEquals(1, first.changes());
        assertTrue(new ClockFaceSyncJournal(root, PAIR).commit(packet).duplicate());
        try (var files = Files.list(root.resolve(PAIR))) {
            List<Path> saved = files.toList();
            assertEquals(1, saved.size());
            assertArrayEquals(packet, Files.readAllBytes(saved.get(0)));
        }
        String otherPair = "11111111-1111-4111-8111-111111111111";
        assertFalse(new ClockFaceSyncJournal(root, otherPair).commit(packet).duplicate());
        assertThrows(IllegalArgumentException.class, () -> new ClockFaceSyncJournal(root, "../escape"));
    }

    @Test public void failedStorageOrValidationNeverReturnsReceipt() throws Exception {
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncJournal journal = new ClockFaceSyncJournal(root, PAIR);
        assertThrows(IllegalArgumentException.class, () -> journal.commit(new byte[] {0x67, 0, 0, 0}));
        assertFalse(Files.exists(root.resolve(PAIR)));
        Files.write(root.resolve(PAIR), new byte[] {1});
        assertThrows(IOException.class, () -> journal.commit(fixture("config")));
    }

    @Test public void corruptedExistingRecordIsNotAcceptedAsDuplicate() throws Exception {
        Path root = temporary.newFolder().toPath();
        ClockFaceSyncJournal journal = new ClockFaceSyncJournal(root, PAIR);
        byte[] packet = fixture("config");
        journal.commit(packet);
        Path saved;
        try (var files = Files.list(root.resolve(PAIR))) { saved = files.findFirst().orElseThrow(); }
        byte[] corrupt = packet.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(saved, corrupt);
        assertThrows(IOException.class, () -> journal.commit(packet));
    }

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/clockface-352-" + name + ".bin"));
    }

    private static byte[] archiveFrom(byte[] packet) {
        for (int offset = 0; offset + 8 <= packet.length; offset++) {
            if (new String(packet, offset, 8, StandardCharsets.US_ASCII).equals("bplist00")) {
                return Arrays.copyOfRange(packet, offset, packet.length);
            }
        }
        throw new AssertionError("Fixture has no archive");
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
    private static Map<String, Object> decoded(byte[] bytes) {
        return castMap(mutable(AppleBinaryPropertyList.decode(bytes)));
    }
    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new java.util.LinkedHashMap<>();
            for (var entry : map.entrySet()) result.put(entry.getKey(), mutable(entry.getValue()));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new java.util.ArrayList<>();
            for (Object item : list) result.add(mutable(item));
            return result;
        }
        return value;
    }
    @SuppressWarnings("unchecked") private static List<Object> objects(Map<String, Object> root) {
        return (List<Object>) root.get("$objects");
    }
    private static Map<String, Object> message(Map<String, Object> root) {
        AppleBinaryPropertyList.Uid uid = (AppleBinaryPropertyList.Uid) castMap(root.get("$top")).get("root");
        return castMap(objects(root).get(uid.value()));
    }
}
