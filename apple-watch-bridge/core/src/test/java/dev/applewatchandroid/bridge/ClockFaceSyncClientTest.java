package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ClockFaceSyncClientTest {
    private static final String PAIR = "5e111a6b-a56a-5b3a-b795-1c9799c1d92a";
    private static final long NOW = 1791257108000L;
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void actualNativeHeaderDecodesAppleTimeVersionGenerationAndVector() throws Exception {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(fixture())) {
            var header = ClockFaceSyncHeaderCodec.decode(frame.header);
            assertEquals(2, header.version()); assertEquals(224, header.sequence());
            assertEquals(812937151.028073, header.timestamp(), .000001);
            assertNotNull(header.peer()); assertNotNull(header.generation());
            assertEquals(1, header.clocks().size());
            assertTrue(header.clocks().containsKey(header.peer()));
        }
    }

    @Test public void reservationsPersistIdentitySequenceCooldownAndCommittedVectorAcrossInstances() throws Exception {
        Path root = temporary.newFolder().toPath();
        var first = ClockFaceSyncHeaderCodec.decode(new ClockFaceSyncClient(root, PAIR).reserveHeader(NOW, true));
        assertEquals(1, first.sequence()); assertEquals(Map.of(first.peer(), 1L), first.clocks());
        assertThrows(IllegalStateException.class, () -> new ClockFaceSyncClient(root, PAIR).remotePeerForDelta());
        assertEquals(NOW + 60000, new ClockFaceSyncClient(root, PAIR).earliestFullRequestMs());
        assertEquals((NOW - ClockFaceSyncHeaderCodec.APPLE_EPOCH_MS) / 1000.0, first.timestamp(), 0);
        assertThrows(IllegalArgumentException.class, () -> new ClockFaceSyncClient(root, PAIR).reserveHeader(NOW + 1, true));
        var second = ClockFaceSyncHeaderCodec.decode(new ClockFaceSyncClient(root, PAIR).reserveHeader(NOW + 2, false));
        assertEquals(first.peer(), second.peer()); assertEquals(first.generation(), second.generation());
        assertEquals(2, second.sequence());
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(fixture())) {
            var remote = ClockFaceSyncHeaderCodec.decode(frame.header);
            new ClockFaceSyncClient(root, PAIR).noteCommittedHeader(frame.header);
            assertEquals(remote.peer(), new ClockFaceSyncClient(root, PAIR).remotePeerForDelta());
            var next = ClockFaceSyncHeaderCodec.decode(new ClockFaceSyncClient(root, PAIR).reserveHeader(NOW + 60000, true));
            assertEquals(3, next.sequence()); assertEquals(first.peer(), next.peer());
            assertEquals(remote.clocks().get(remote.peer()), next.clocks().get(remote.peer()));
        }
        assertThrows(IllegalArgumentException.class, () -> new ClockFaceSyncClient(root, PAIR).reserveHeader(NOW, true));
    }

    @Test public void fullRequestOmitsCancellationAndResponseRequiresHeaderAcceptedBooleanAndNoPriority() throws Exception {
        byte[] header = new ClockFaceSyncClient(temporary.newFolder().toPath(), PAIR).reserveHeader(NOW, true);
        byte[] request = ClockFaceSyncHeaderCodec.fullRequest(header);
        assertEquals(0x65, request[0]); assertEquals(0, request[1]); assertEquals(0, request[2]);
        assertEquals(1, ClockFaceSyncHeaderCodec.decodeFullRequest(request).sequence());
        ByteArrayOutputStream response = new ByteArrayOutputStream(); response.write(0x65); response.write(0);
        ClockFaceSyncHeaderCodec.field(response, 1, header); ClockFaceSyncHeaderCodec.integer(response, 2, 0);
        assertFalse(ClockFaceSyncHeaderCodec.fullResponse(response.toByteArray()).accepted());
        byte[] malformed = Arrays.copyOf(request, request.length + 2);
        malformed[request.length] = 18; malformed[request.length + 1] = 0; // empty cancellation identifier forbidden
        assertThrows(IllegalArgumentException.class, () -> ClockFaceSyncHeaderCodec.decodeFullRequest(malformed));
        assertThrows(IllegalArgumentException.class, () -> ClockFaceSyncHeaderCodec.fullResponse(request));
        response.reset(); response.write(0x65); response.write(0);
        ClockFaceSyncHeaderCodec.field(response, 1, header); ClockFaceSyncHeaderCodec.integer(response, 2, 1);
        ClockFaceSyncHeaderCodec.field(response, 3, new byte[0]);
        assertTrue(ClockFaceSyncHeaderCodec.fullResponse(response.toByteArray()).hasError());
        byte[] duplicate = Arrays.copyOf(header, header.length + 2); duplicate[header.length] = 32; duplicate[header.length + 1] = 2;
        assertThrows(IllegalArgumentException.class, () -> ClockFaceSyncHeaderCodec.decode(duplicate));
    }

    @Test public void corruptOrUnavailableStateNeverFallsBackToNewSenderAndPairsAreSeparated() throws Exception {
        Path root = temporary.newFolder().toPath();
        var first = ClockFaceSyncHeaderCodec.decode(new ClockFaceSyncClient(root, PAIR).reserveHeader(NOW, true));
        String other = UUID.randomUUID().toString();
        var second = ClockFaceSyncHeaderCodec.decode(new ClockFaceSyncClient(root, other).reserveHeader(NOW, true));
        assertNotEquals(first.peer(), second.peer());
        Path state = root.resolve(PAIR).resolve("client-state.bplist");
        Files.write(state, new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> new ClockFaceSyncClient(root, PAIR).reserveHeader(NOW + 60000, true));
        Path unavailable = temporary.newFile().toPath();
        assertThrows(IOException.class, () -> new ClockFaceSyncClient(unavailable, PAIR).reserveHeader(NOW, true));
    }

    @Test public void exactKnownSessionCancellationUsesNativeOptionalFieldAndRejectsArbitraryIdentifiers() throws Exception {
        byte[] header = new ClockFaceSyncClient(temporary.newFolder().toPath(), PAIR).reserveHeader(NOW, true);
        String session = "W2026-10-06T03:33:11.407";
        byte[] request = ClockFaceSyncHeaderCodec.fullRequest(header, session);
        assertEquals(session, ClockFaceSyncHeaderCodec.recoverySession(request));
        assertEquals(1, ClockFaceSyncHeaderCodec.decodeFullRequest(request).sequence());
        assertNull(ClockFaceSyncHeaderCodec.recoverySession(ClockFaceSyncHeaderCodec.fullRequest(header)));
        ByteArrayOutputStream independent = new ByteArrayOutputStream();
        independent.write(0x65); independent.write(0); independent.write(0);
        ClockFaceSyncHeaderCodec.field(independent, 1, header);
        ClockFaceSyncHeaderCodec.field(independent, 2, session.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertArrayEquals(independent.toByteArray(), request);
        for (String invalid : java.util.List.of("", "RESET", session + "\n", "W" + "1".repeat(200), session + "я")) {
            assertThrows(IllegalArgumentException.class, () -> ClockFaceSyncHeaderCodec.fullRequest(header, invalid));
            independent.reset(); independent.write(0x65); independent.write(0); independent.write(0);
            ClockFaceSyncHeaderCodec.field(independent, 1, header);
            ClockFaceSyncHeaderCodec.field(independent, 2, invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] malformed = independent.toByteArray();
            assertThrows(IllegalArgumentException.class, () -> ClockFaceSyncHeaderCodec.decodeFullRequest(malformed));
        }
    }

    @Test public void nativeReplyUsesReservedLocalHeaderRatherThanEchoingRemoteSender() throws Exception {
        byte[] request = fixture();
        byte[] local = new ClockFaceSyncClient(temporary.newFolder().toPath(), PAIR).reserveHeader(NOW, false);
        byte[] reply = ClockFaceSyncAccept.reply(request, local);
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(); prefix.write(0x66); prefix.write(0);
        ClockFaceSyncHeaderCodec.field(prefix, 1, local);
        assertArrayEquals(prefix.toByteArray(), Arrays.copyOf(reply, prefix.size()));
        assertTrue(OperationalCommandPolicy.isAllowed("REQUEST_FACE_COLLECTION"));
        assertFalse(OperationalCommandPolicy.isAllowed("REQUEST_FACE_COLLECTION\nRESET"));
    }
    private static byte[] fixture() throws Exception {
        try (var input = ClockFaceSyncClientTest.class.getResourceAsStream("/clockface-353-start.bin")) { return input.readAllBytes(); }
    }
}
