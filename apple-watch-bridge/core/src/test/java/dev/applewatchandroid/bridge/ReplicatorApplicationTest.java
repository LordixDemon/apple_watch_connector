package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public class ReplicatorApplicationTest {
    private static final UUID SESSION = UUID.fromString("33445566-7788-9900-1122-aabbccddeeff");

    @Test public void respondsToWatchInitiatedSessionAndAcknowledgesItsCompletion() {
        try (var session = new ReplicatorReadSession(SESSION.toString(), "Phone", line -> {})) {
            initialRequest(session);
            UUID peerSession = UUID.randomUUID();
            Map<?, ?> request = path(ReplicatorReadSession.requestBody(peerSession, UUID.randomUUID().toString(), "Watch"),
                "handshake", "_0", "request", "_0");
            var introduced = new java.util.LinkedHashMap<Object, Object>(request);
            introduced.put("relationshipState", Map.of("introduced", Map.of()));
            var watchDevice = new java.util.LinkedHashMap<Object, Object>((Map<?, ?>) request.get("device"));
            watchDevice.put("protocolVersion", Map.of("current", 16L, "minimum", 4L));
            introduced.put("device", watchDevice);
            UUID requestId = accept(session, Map.of("handshake", Map.of("_0", Map.of("request", Map.of("_0", introduced)))));
            assertEquals(requestId, UUID.fromString((String) envelope(session.pollOutbound()).get("responseToID")));
            Map<?, ?> reply = path((Map<?, ?>) OpackDecoder.decode((byte[]) envelope(session.pollOutbound()).get("encodedBody")),
                "handshake", "_0", "response", "_0");
            assertEquals(peerSession, UUID.fromString((String) reply.get("sessionID")));
            assertEquals(SESSION.toString().toUpperCase(java.util.Locale.ROOT), path(reply, "device").get("id"));
            assertEquals(List.of(), path(reply, "recordManifest").get("recordVersions"));
            Map<?, ?> nativeComplete = path(ReplicatorReadSession.completeBody(peerSession), "handshake", "_0", "complete", "_0");
            var complete = new java.util.LinkedHashMap<Object, Object>(nativeComplete);
            complete.put("relationshipState", Map.of("introduced", Map.of()));
            complete.put("mismatchedZones", List.of("another-client/unsupported-zone"));
            UUID completeId = accept(session, Map.of("handshake", Map.of("_0", Map.of("complete", Map.of("_0", complete)))));
            assertEquals(completeId, UUID.fromString((String) envelope(session.pollOutbound()).get("responseToID")));
            assertNull(session.pollOutbound());
            complete.put("mismatchedZones", List.of("com.apple.nanotimekit.replicator.library/library_snapshots"));
            assertThrows(IllegalArgumentException.class, () -> accept(session, Map.of("handshake", Map.of("_0", Map.of("complete", Map.of("_0", complete))))));
            assertNull(session.pollOutbound());
            assertThrows(IllegalArgumentException.class, () -> accept(session,
                ReplicatorReadSession.requestBody(UUID.randomUUID(), UUID.randomUUID().toString(), "Watch")));
            assertNull(session.pollOutbound());
        }
    }

    @Test public void completesOnlyMatchingCompatibleResponseAndDoesNotAckAnAck() {
        try (var session = new ReplicatorReadSession(SESSION.toString(), "Phone", line -> {})) {
            Map<?, ?> request = initialRequest(session);
            Map<String, Object> response = Map.of("sessionID", request.get("sessionID"), "relationshipState", Map.of("paired", Map.of()),
                "device", request.get("device"), "zoneVersions", Map.of(), "recordManifest", Map.of("recordVersions", List.of()));
            UUID messageId = accept(session, Map.of("handshake", Map.of("_0", Map.of("response", Map.of("_0", response)))));
            Map<?, ?> ack = envelope(session.pollOutbound());
            assertEquals(messageId, UUID.fromString((String) ack.get("responseToID")));
            assertTrue(((Map<?, ?>) OpackDecoder.decode((byte[]) ack.get("encodedBody"))).containsKey("ack"));
            Map<?, ?> complete = path((Map<?, ?>) OpackDecoder.decode((byte[]) envelope(session.pollOutbound()).get("encodedBody")),
                "handshake", "_0", "complete", "_0");
            assertEquals(request.get("sessionID"), complete.get("sessionID"));
            assertEquals(List.of(), path(complete, "recordManifest").get("recordVersions"));
            assertNull(session.pollOutbound());
            accept(session, Map.of("ack", Map.of("_0", Map.of())));
            assertNull(session.pollOutbound());
            accept(session, Map.of("handshake", Map.of("_0", Map.of("response", Map.of("_0", response)))));
            assertNotNull(session.pollOutbound()); assertNull(session.pollOutbound());
        }
    }

    @Test public void rejectsWrongSessionOrMissingSnapshotZonesBeforeReplying() {
        try (var session = new ReplicatorReadSession(SESSION.toString(), "Phone", line -> {})) {
            Map<?, ?> request = initialRequest(session);
            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("sessionID", SESSION.toString()); response.put("relationshipState", Map.of("paired", Map.of()));
            response.put("device", request.get("device"));
            assertThrows(IllegalArgumentException.class, () -> accept(session, Map.of("handshake", Map.of("_0", Map.of("response", Map.of("_0", response))))));
            response.put("sessionID", request.get("sessionID"));
            var device = new java.util.LinkedHashMap<Object, Object>((Map<?, ?>) request.get("device")); device.put("zones", List.of());
            response.put("device", device);
            assertThrows(IllegalArgumentException.class, () -> accept(session, Map.of("handshake", Map.of("_0", Map.of("response", Map.of("_0", response))))));
            assertNull(session.pollOutbound());
        }
    }

    private static Map<?, ?> initialRequest(ReplicatorReadSession session) {
        return path((Map<?, ?>) OpackDecoder.decode((byte[]) envelope(session.initialFrame()).get("encodedBody")), "handshake", "_0", "request", "_0");
    }
    private static Map<?, ?> envelope(byte[] frame) {
        assertNotNull(frame);
        return (Map<?, ?>) OpackDecoder.decode(Arrays.copyOfRange(frame, 72, frame.length));
    }
    private static UUID accept(ReplicatorReadSession session, Map<String, Object> body) {
        UUID id = UUID.randomUUID(), sender = UUID.randomUUID();
        byte[] payload = OpackEncoder.encode(Map.of("id", id.toString(), "senderDeviceID", sender.toString(),
            "messageType", ReplicatorReadSession.MESSAGE_TYPE, "encodedBody", OpackEncoder.encode(body)));
        session.accept(new ReplicatorNetworkHeader(id, sender, payload.length, ReplicatorNetworkHeader.MessageType.DATA, 1, 0,
            ReplicatorNetworkHeader.Priority.HIGH), payload);
        return id;
    }

    @Test public void usesSameCanonicalPhoneIdentityInHeaderEnvelopeAndDescriptor() {
        var session = new ReplicatorReadSession(SESSION.toString(), "Current phone", line -> {});
        byte[] frame = session.initialFrame();
        var header = ReplicatorNetworkHeader.decode(Arrays.copyOf(frame, 72));
        Map<?, ?> envelope = (Map<?, ?>) OpackDecoder.decode(Arrays.copyOfRange(frame, 72, frame.length));
        assertEquals(SESSION, header.senderId);
        assertEquals(SESSION.toString().toUpperCase(java.util.Locale.ROOT), envelope.get("senderDeviceID"));
        assertEquals(header.messageId, UUID.fromString((String) envelope.get("id")));
        assertEquals(frame.length - 72, header.payloadBytes);
        Map<?, ?> body = (Map<?, ?>) OpackDecoder.decode((byte[]) envelope.get("encodedBody"));
        assertEquals(envelope.get("senderDeviceID"), path(body, "handshake", "_0", "request", "_0", "device").get("id"));
    }

    @Test public void matchesGenuineNativePhoneHandshakeWithSnapshotZones() throws Exception {
        Map<?, ?> nativeRequest;
        try (var in = getClass().getResourceAsStream("opack-40-handshake-readonly.bin")) {
            assertNotNull(in);
            nativeRequest = path((Map<?, ?>) OpackDecoder.decode(in.readAllBytes()), "handshake", "_0", "request", "_0");
        }
        Map<?, ?> ours = path((Map<?, ?>) OpackDecoder.decode(OpackEncoder.encode(
            ReplicatorReadSession.requestBody(SESSION, "controlled-simulator", "Controlled codec probe"))),
            "handshake", "_0", "request", "_0");
        for (String key : List.of("sessionID", "relationshipState"))
            assertEquals(nativeRequest.get(key), ours.get(key));
        assertEquals(Map.of(
            "library_snapshots::com.apple.nanotimekit.replicator.library", Map.of("empty", Map.of()),
            "gallery_snapshots::com.apple.nanotimekit.replicator.library", Map.of("empty", Map.of())), ours.get("zoneVersions"));
        Map<?, ?> nativeDevice = path(nativeRequest, "device"), device = path(ours, "device");
        for (String key : List.of("id", "name", "protocolVersion", "deviceType", "isSource", "messageTypes"))
            assertEquals(nativeDevice.get(key), device.get(key));
        // Swift dictionary iteration order differs, but key/value pairs must match.
        assertEquals(zonePairs((List<?>) nativeDevice.get("zones")), zonePairs((List<?>) device.get("zones")));
    }

    @Test public void handlesSplitHeadersBodiesCoalescedFramesAndWipesOwnedPayload() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] frame = frame(payload.length, payload);
        for (int split = 0; split <= frame.length; split++) {
            List<byte[]> delivered = new ArrayList<>(), borrowed = new ArrayList<>();
            try (var reader = new ReplicatorStreamReader((header, bytes) -> {
                delivered.add(bytes.clone()); borrowed.add(bytes);
            })) {
                reader.accept(chunk(0, false, Arrays.copyOfRange(frame, 0, split)));
                reader.accept(chunk(0, true, Arrays.copyOfRange(frame, split, frame.length)));
                assertEquals(1, delivered.size()); assertArrayEquals(payload, delivered.get(0));
                assertArrayEquals(new byte[5], borrowed.get(0));
            }
        }
        List<Integer> sizes = new ArrayList<>();
        try (var reader = new ReplicatorStreamReader((h, p) -> sizes.add(p.length))) {
            byte[] empty = frame(0, new byte[0]);
            byte[] combined = Arrays.copyOf(frame, frame.length + empty.length + frame.length);
            System.arraycopy(empty, 0, combined, frame.length, empty.length);
            System.arraycopy(frame, 0, combined, frame.length + empty.length, frame.length);
            reader.accept(chunk(5, true, combined));
            assertEquals(List.of(5, 0, 5), sizes);
        }
    }

    @Test public void rejectsTruncationMalformedPreambleAndAggregateAllocation() {
        try (var reader = new ReplicatorStreamReader((h, p) -> fail("Partial payload delivered"))) {
            assertThrows(IllegalArgumentException.class, () -> reader.accept(chunk(0, true, new byte[1])));
        }
        try (var reader = new ReplicatorStreamReader((h, p) -> fail("Invalid payload delivered"))) {
            assertThrows(IllegalArgumentException.class, () -> reader.accept(chunk(0, false, new byte[72])));
        }
        try (var reader = new ReplicatorStreamReader((h, p) -> fail("Partial payload delivered"))) {
            byte[] header = frame(ReplicatorNetworkHeader.MAX_PAYLOAD_BYTES, new byte[0]);
            reader.accept(chunk(0, false, header)); reader.accept(chunk(4, false, header));
            assertThrows(IllegalStateException.class, () -> reader.accept(chunk(8, false, header)));
        }
        try (var reader = new ReplicatorStreamReader((h, p) -> {})) {
            for (int i = 0; i < 16; i++) reader.accept(chunk(i, false, new byte[0]));
            assertThrows(IllegalStateException.class, () -> reader.accept(chunk(16, false, new byte[0])));
        }
    }

    @Test public void boundsEncoderAndPreservesLengthsIntegersAndUtf8() {
        for (int n : new int[]{0, 32, 33, 255, 256, 65535, 65536}) {
            byte[] bytes = new byte[n]; Arrays.fill(bytes, (byte) 0x5a);
            assertArrayEquals(bytes, (byte[]) OpackDecoder.decode(OpackEncoder.encode(bytes)));
        }
        List<Long> numbers = List.of(-1L, -2L, Long.MIN_VALUE, 0L, 39L, 40L, 255L, 256L, 65535L, 65536L, 0xffffffffL, 0x100000000L);
        assertEquals(numbers, OpackDecoder.decode(OpackEncoder.encode(numbers)));
        assertEquals("Preview 🌍", OpackDecoder.decode(OpackEncoder.encode("Preview 🌍")));
        assertThrows(IllegalArgumentException.class, () -> OpackEncoder.encode(new byte[OpackDecoder.MAX_BYTES]));
        assertThrows(IllegalArgumentException.class, () -> OpackEncoder.encode(Map.of(1, "invalid")));
    }

    private static Map<?, ?> path(Map<?, ?> map, String... keys) {
        for (String key : keys) map = (Map<?, ?>) map.get(key);
        return map;
    }
    private static Map<Object, Object> zonePairs(List<?> alternating) {
        assertEquals(4, alternating.size());
        return Map.of(alternating.get(0), alternating.get(1), alternating.get(2), alternating.get(3));
    }
    private static byte[] frame(int length, byte[] payload) {
        byte[] header = new ReplicatorNetworkHeader(SESSION, SESSION, length,
            ReplicatorNetworkHeader.MessageType.DATA, 1, 0, ReplicatorNetworkHeader.Priority.HIGH).encode();
        byte[] result = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, result, header.length, payload.length); return result;
    }
    private static byte[] chunk(long id, boolean finished, byte[] data) {
        return ByteBuffer.allocate(9 + data.length).putLong(id).put((byte)(finished ? 1 : 0)).put(data).array();
    }
}
