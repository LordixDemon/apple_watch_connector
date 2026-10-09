package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.Test;

public final class ClockFaceDeltaProtocolTest {
    static final String FACE = "3122508d-c08f-536e-bc37-d7f82cb8675d";
    static final String NEW_FACE = "12345678-1234-1234-1234-123456789012";
    static final String SESSION = "P2026-10-06T04:30:00.000";
    static final long NOW = 1791261000000L;
    static final UUID PHONE = UUID.fromString("12345678-1111-1111-1111-123456789012");
    static final UUID WATCH = UUID.fromString("12345678-2222-2222-2222-123456789012");
    static final UUID GENERATION = UUID.fromString("12345678-3333-3333-3333-123456789012");

    static byte[] header(UUID sender, long sequence) {
        return ClockFaceSyncHeaderCodec.header(sender, GENERATION, sequence, NOW, Map.of(PHONE, 1L, WATCH, 1L));
    }

    static byte[] actualConfiguration() throws Exception {
        try (var frame = ClockFaceSyncFrame.parse(Files.readAllBytes(Path.of("src/test/resources/clockface-352-config.bin")));
             var message = ClockFaceSyncFrame.decodeChange(frame.changes.get(0))) {
            assertEquals(1, message.type()); return message.payload();
        }
    }

    @Test public void selectAndReorderUseExactNativePayloadAndSymmetricChangeKinds() throws Exception {
        try (var select = ClockFaceSyncFrame.decodeChange(NtkFaceChangeEncoder.select(FACE))) {
            assertEquals(4, select.type()); assertNull(select.faceUuid()); assertFalse(select.multipart());
            assertEquals(FACE.toUpperCase(Locale.ROOT), new String(select.payload(), StandardCharsets.US_ASCII));
        }
        try (var order = ClockFaceSyncFrame.decodeChange(NtkFaceChangeEncoder.order(List.of(FACE, NEW_FACE)))) {
            assertEquals(5, order.type()); assertNull(order.faceUuid());
            assertEquals(List.of(FACE, NEW_FACE), NtkFacePayloadCodec.ordered(order.payload()));
        }
        assertThrows(IllegalArgumentException.class, () -> NtkFaceChangeEncoder.select("wayfinder"));
        assertThrows(IllegalArgumentException.class, () -> NtkFaceChangeEncoder.order(List.of(FACE, FACE.toUpperCase(Locale.ROOT))));
        assertThrows(IllegalArgumentException.class, () -> NtkFaceChangeEncoder.order(Collections.nCopies(257, FACE)));
    }

    @Test public void actualNativeConfigurationIsKeptByteForByteInUpdateAndRootOnlyZipAdd() throws Exception {
        byte[] config = actualConfiguration();
        try (var update = ClockFaceSyncFrame.decodeChange(NtkFaceChangeEncoder.update(FACE, config));
             var add = ClockFaceSyncFrame.decodeChange(NtkFaceChangeEncoder.addConfiguration(NEW_FACE, config));
             var remove = ClockFaceSyncFrame.decodeChange(NtkFaceChangeEncoder.remove(NEW_FACE))) {
            assertEquals(1, update.type()); assertEquals(UUID.fromString(FACE), update.faceUuid());
            assertArrayEquals(config, update.payload());
            assertEquals(0, add.type()); assertEquals(UUID.fromString(NEW_FACE), add.faceUuid());
            assertArrayEquals(config, NtkFacePayloadCodec.configurationFromZip(add.payload()));
            try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(add.payload()))) {
                assertEquals("face.json", zip.getNextEntry().getName());
                zip.readAllBytes(); assertNull(zip.getNextEntry());
            }
            assertEquals(3, remove.type()); assertEquals(UUID.fromString(NEW_FACE), remove.faceUuid());
            assertNull(remove.payload()); assertFalse(remove.multipart());
        }
    }

    @Test public void metadataIsAKeyedNSDictionaryWithNativeSenderSyncVersionTwo() {
        Map<?, ?> archive = (Map<?, ?>) AppleBinaryPropertyList.decode(NtkFaceChangeEncoder.senderMetadata());
        assertEquals("NSKeyedArchiver", archive.get("$archiver")); assertEquals(100000L, archive.get("$version"));
        List<?> objects = (List<?>) archive.get("$objects");
        Map<?, ?> top = (Map<?, ?>) archive.get("$top");
        Map<?, ?> root = (Map<?, ?>) objects.get(((AppleBinaryPropertyList.Uid) top.get("root")).value());
        List<?> keys = (List<?>) root.get("NS.keys"), values = (List<?>) root.get("NS.objects");
        assertEquals(1, keys.size()); assertEquals(1, values.size());
        assertEquals("senderSyncVersion", objects.get(((AppleBinaryPropertyList.Uid) keys.get(0)).value()));
        assertEquals(2L, objects.get(((AppleBinaryPropertyList.Uid) values.get(0)).value()));
        Map<?, ?> descriptor = (Map<?, ?>) objects.get(((AppleBinaryPropertyList.Uid) root.get("$class")).value());
        assertEquals(List.of("NSDictionary", "NSObject"), descriptor.get("$classes"));
    }

    @Test public void nativeStartReplyAdvertisesReceiverVersionAndPreservesSenderVersionInsteadOfEchoing() {
        byte[] request = ClockFaceDeltaProtocol.start(header(PHONE, 1), "W2026-10-06T04:30:00.000");
        byte[] reply = ClockFaceSyncAccept.reply(request, header(WATCH, 2));
        assertNotNull(reply); ClockFaceDeltaProtocol.response(reply);
        // Metadata is the last length-delimited response field; find the binary plist and decode it independently.
        int offset = -1;
        for (int i = 0; i <= reply.length - 8; i++) {
            if (new String(reply, i, 8, StandardCharsets.US_ASCII).equals("bplist00")) { offset = i; break; }
        }
        assertTrue(offset >= 0);
        Map<?, ?> archive = (Map<?, ?>) AppleBinaryPropertyList.decode(Arrays.copyOfRange(reply, offset, reply.length));
        List<?> objects = (List<?>) archive.get("$objects");
        Map<?, ?> root = (Map<?, ?>) objects.get(((AppleBinaryPropertyList.Uid) ((Map<?, ?>) archive.get("$top")).get("root")).value());
        List<?> keys = (List<?>) root.get("NS.keys"), values = (List<?>) root.get("NS.objects");
        Map<String, Object> versions = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) versions.put((String) objects.get(((AppleBinaryPropertyList.Uid) keys.get(i)).value()),
                objects.get(((AppleBinaryPropertyList.Uid) values.get(i)).value()));
        assertEquals(Map.of("senderSyncVersion", 2L, "receiverSyncVersion", 2L), versions);
        try (var frame = ClockFaceSyncFrame.parse(request)) {
            assertFalse(Arrays.equals(frame.metadata, Arrays.copyOfRange(reply, offset, reply.length)));
        }
    }

    @Test public void requestStartIsDeltaSingleBatchIsZeroAndEndDoesNotRollback() {
        byte[] header = header(PHONE, 10);
        byte[] start = ClockFaceDeltaProtocol.start(header, SESSION);
        byte[] batch = ClockFaceDeltaProtocol.batch(header, SESSION, List.of(NtkFaceChangeEncoder.select(FACE)));
        byte[] end = ClockFaceDeltaProtocol.end(header, SESSION);
        try (var s = ClockFaceSyncFrame.parse(start); var b = ClockFaceSyncFrame.parse(batch); var e = ClockFaceSyncFrame.parse(end)) {
            assertFalse(s.resetSync); assertNotNull(s.metadata); assertEquals(SESSION, s.sessionText());
            assertArrayEquals(header, s.header); assertEquals(0, b.batchIndex); assertEquals(1, b.changes.size());
            assertFalse(e.rollback); assertFalse(e.endHasError);
        }
        for (byte[] request : List.of(start, batch, end)) {
            ClockFaceDeltaProtocol.validateRequest(request);
            assertEquals(0, request[2]);
            assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.response(request));
        }
    }

    @Test public void operationalCommandsAreFixedCanonicalReferencesAndNeverRawPacketsOrSetup() throws Exception {
        String select = ClockFaceDeltaCommand.SELECT + FACE;
        String duplicate = ClockFaceDeltaCommand.DUPLICATE + FACE + ":" + NEW_FACE;
        assertTrue(OperationalCommandPolicy.isAllowed(select)); assertTrue(OperationalCommandPolicy.isAllowed(duplicate));
        assertEquals(FACE, ClockFaceDeltaCommand.parse(select).target());
        assertTrue(ClockFaceDeltaCommand.parse(duplicate).duplicate());
        for (String invalid : List.of(ClockFaceDeltaCommand.SELECT + "wayfinder", select + ":RESET", select + "\nSETUP",
                ClockFaceDeltaCommand.SELECT + FACE.toUpperCase(Locale.ROOT),
                ClockFaceDeltaCommand.DUPLICATE + FACE + ":" + FACE, duplicate + ":payload")) {
            assertFalse(OperationalCommandPolicy.isAllowed(invalid));
        }
        byte[] reset = Files.readAllBytes(Path.of("src/test/resources/clockface-353-start.bin"));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.validateRequest(reset));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.validateRequest(
                ClockFaceSyncHeaderCodec.fullRequest(header(PHONE, 1))));
    }

    @Test public void startDeadlineIsAbsoluteAppleTimeAndDurationOrMissingDeadlineIsRefused() {
        byte[] packet = ClockFaceDeltaProtocol.start(header(PHONE, 1), SESSION);
        // Independently walk request fields; deadline precedes the metadata dictionary.
        java.nio.ByteBuffer reader = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        reader.position(3);
        int deadlineOffset = -1;
        while (reader.hasRemaining()) {
            long tag = testVarint(reader);
            if (tag == 49) { deadlineOffset = reader.position(); break; }
            if ((tag & 7) == 0) testVarint(reader);
            else if ((tag & 7) == 2) {
                int length = Math.toIntExact(testVarint(reader)); reader.position(reader.position() + length);
            } else fail("unexpected request field");
        }
        assertTrue(deadlineOffset > 3);
        double deadline = reader.getDouble();
        assertEquals((NOW - 978307200000L) / 1000.0 + 90.0, deadline, 0.000001);
        assertTrue(deadline > 800000000.0); // the old 90.0 was a date in January 2001
        for (double bad : List.of(90.0, Double.NaN, Double.POSITIVE_INFINITY, deadline + 1801)) {
            byte[] changed = packet.clone();
            java.nio.ByteBuffer.wrap(changed).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(deadlineOffset, bad);
            assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.validateRequest(changed));
        }
        byte[] missing = new byte[packet.length - 9];
        System.arraycopy(packet, 0, missing, 0, deadlineOffset - 1);
        System.arraycopy(packet, deadlineOffset + 8, missing, deadlineOffset - 1, packet.length - deadlineOffset - 8);
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.validateRequest(missing));
        ClockFaceDeltaProtocol.validateRequest(packet);
    }

    private static long testVarint(java.nio.ByteBuffer reader) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int value = reader.get() & 255; result |= (long) (value & 127) << shift;
            if ((value & 128) == 0) return result;
        }
        throw new AssertionError("test varint overflow");
    }

    @Test public void deltaRequestEnforcesHeaderVersionSelfClockAndBatchBounds() {
        byte[] header = header(PHONE, 1);
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.batch(header, SESSION, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.batch(header, SESSION,
                Collections.nCopies(9, NtkFaceChangeEncoder.select(FACE))));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.batch(header, SESSION, List.of(new byte[524289])));
        byte[] noOwnClock = ClockFaceSyncHeaderCodec.header(PHONE, GENERATION, 1, NOW, Map.of(WATCH, 1L));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.start(noOwnClock, SESSION));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.start(header, "RESET"));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.end(header, SESSION + "\n"));
    }

    @Test public void repliesHaveNoPriorityAndExplicitErrorEvenIfEmptyIsFailure() {
        for (int type : List.of(0x66, 0x67, 0x69)) {
            var response = ClockFaceDeltaProtocol.response(reply(type, SESSION, WATCH, true, false, false, 0));
            assertEquals(type, response.type()); assertEquals(SESSION, response.session()); assertEquals(WATCH, response.header().peer());
            assertTrue(response.accepted()); assertFalse(response.hasError()); assertFalse(response.didRollback());
            assertEquals(type == 0x67 ? 0 : -1, response.index());
            assertTrue(ClockFaceDeltaProtocol.response(reply(type, SESSION, WATCH, true, true, false, 0)).hasError());
        }
        assertFalse(ClockFaceDeltaProtocol.response(reply(0x66, SESSION, WATCH, false, false, false, 0)).accepted());
        assertTrue(ClockFaceDeltaProtocol.response(reply(0x69, SESSION, WATCH, true, false, true, 0)).didRollback());
    }

    @Test public void truncationDuplicatesMissingFieldsInvalidBooleanIndexWireAndSizeAreRefused() {
        byte[] valid = reply(0x66, SESSION, WATCH, true, false, false, 0);
        // START required accepted is the final field; every truncation is invalid.
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.response(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.response(append(valid, new byte[]{24, 1})));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.response(append(valid, new byte[]{64, 0})));
        valid[valid.length - 1] = 2;
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.response(valid));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.response(reply(0x67, SESSION, WATCH, true, false, false, -1)));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.response(new byte[16385]));
        byte[] end = reply(0x69, SESSION, WATCH, true, false, false, 0);
        assertThrows(IllegalArgumentException.class, () -> ClockFaceDeltaProtocol.response(append(end, new byte[]{24, 0})));
    }

    static byte[] reply(int type, String session, UUID sender, boolean accepted, boolean error, boolean rollback, long index) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(type); out.write(0);
        ClockFaceSyncHeaderCodec.field(out, 1, header(sender, 17));
        ClockFaceSyncHeaderCodec.field(out, 2, session.getBytes(StandardCharsets.US_ASCII));
        if (type == 0x66) ClockFaceSyncHeaderCodec.integer(out, 3, accepted ? 1 : 0);
        if (type == 0x67) ClockFaceSyncHeaderCodec.integer(out, 3, index);
        if (error) ClockFaceSyncHeaderCodec.field(out, type == 0x69 ? 3 : 4, new byte[0]);
        if (rollback) ClockFaceSyncHeaderCodec.integer(out, 4, 1);
        return out.toByteArray();
    }
    private static byte[] append(byte[] bytes, byte[] tail) {
        byte[] result = Arrays.copyOf(bytes, bytes.length + tail.length);
        System.arraycopy(tail, 0, result, bytes.length, tail.length); return result;
    }
}
