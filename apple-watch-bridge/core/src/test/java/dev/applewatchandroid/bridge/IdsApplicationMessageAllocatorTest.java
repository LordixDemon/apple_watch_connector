package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.UUID;

import org.junit.Test;

public final class IdsApplicationMessageAllocatorTest {
    @Test public void durableHealthUuidUsesGlobalCounterAndNeverReusesAnIssuedUuid() {
        var allocator=new IdsApplicationMessageAllocator(new SecureRandom());UUID id=UUID.randomUUID();
        assertEquals(0,allocator.nextOneWay().sequence);
        var health=allocator.nextHealthRequest(id);assertEquals(1,health.sequence);
        assertEquals(id.toString().toUpperCase(java.util.Locale.ROOT),health.messageUuid);
        assertEquals(5,health.flagsWithoutTopic&5);assertNull(health.peerResponseIdentifier);
        assertThrows(IllegalArgumentException.class,()->allocator.nextHealthRequest(id));
        assertThrows(IllegalArgumentException.class,()->allocator.nextHealthRequest(null));
        assertThrows(IllegalArgumentException.class,()->allocator.nextHealthRequest(UUID.fromString("00000000-0000-1000-8000-000000000000")));
        assertEquals(2,allocator.nextOneWay().sequence);
        allocator.close();assertThrows(IllegalStateException.class,()->allocator.nextHealthRequest(UUID.randomUUID()));
    }
    @Test public void durableHealthUuidLedgerIsBoundedWithoutAdvancingCounterOnRefusal() {
        try(var allocator=new IdsApplicationMessageAllocator(new SecureRandom())) {
            for(int i=0;i<256;i++)assertEquals(i,allocator.nextHealthRequest(UUID.randomUUID()).sequence);
            assertThrows(IllegalArgumentException.class,()->allocator.nextHealthRequest(UUID.randomUUID()));
            assertEquals(256,allocator.nextOneWay().sequence);
        }
    }
    @Test
    public void startsAtZeroAndFormatsExactUppercaseUuidV4() {
        ScriptedSecureRandom random =
                new ScriptedSecureRandom(
                        bytesFromZero());
        try (IdsApplicationMessageAllocator allocator =
                     new IdsApplicationMessageAllocator(
                             random)) {
            IdsModernSessionCoordinator.MessageMetadata metadata =
                    allocator.nextOneWay();

            assertEquals(
                    0,
                    metadata.sequence);
            assertEquals(
                    "00010203-0405-4607-8809-0A0B0C0D0E0F",
                    metadata.messageUuid);
            assertEquals(
                    4,
                    UUID.fromString(
                            metadata.messageUuid).version());
            assertEquals(
                    2,
                    UUID.fromString(
                            metadata.messageUuid).variant());
            assertEquals(
                    0,
                    metadata.flagsWithoutTopic);
            assertNull(
                    metadata.peerResponseIdentifier);
            assertNull(
                    metadata.expirySeconds);
            assertNull(
                    metadata.fragmentedMessageId);

            IdsApplicationMessageAllocator.Snapshot snapshot =
                    allocator.snapshot();
            assertEquals(
                    1,
                    snapshot.nextSequence);
            assertFalse(
                    snapshot.exhausted);
        }
    }

    @Test
    public void oneCounterSpansAllMessageKindsAndRestartBeginsAtZero() {
        ScriptedSecureRandom random =
                new ScriptedSecureRandom(
                        filled((byte) 0x11),
                        filled((byte) 0x22),
                        filled((byte) 0x33));
        String requestIdentifier =
                "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE";
        try (IdsApplicationMessageAllocator allocator =
                     new IdsApplicationMessageAllocator(
                             random)) {
            IdsModernSessionCoordinator.MessageMetadata oneWay =
                    allocator.nextOneWay();
            IdsModernSessionCoordinator.MessageMetadata request =
                    allocator.nextRequestExpectingPeerResponse();
            IdsModernSessionCoordinator.MessageMetadata response =
                    allocator.nextResponseToIdentifier(
                            requestIdentifier);

            assertEquals(
                    0,
                    oneWay.sequence);
            assertEquals(
                    1,
                    request.sequence);
            assertEquals(
                    2,
                    response.sequence);
            assertEquals(
                    IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE,
                    request.flagsWithoutTopic);
            assertEquals(
                    requestIdentifier,
                    response.peerResponseIdentifier);
        }

        try (IdsApplicationMessageAllocator restarted =
                     new IdsApplicationMessageAllocator(
                             new ScriptedSecureRandom(
                                     filled((byte) 0x44)))) {
            assertEquals(
                    0,
                    restarted.nextOneWay().sequence);
        }
    }

    @Test
    public void appAckRequestAndReceiptUseFreshControllerSequences() {
        String originalIdentifier =
                "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE";
        try (IdsApplicationMessageAllocator allocator =
                     new IdsApplicationMessageAllocator(
                             new ScriptedSecureRandom(
                                     filled((byte) 0x45),
                                     filled((byte) 0x46)))) {
            IdsModernSessionCoordinator.MessageMetadata request =
                    allocator.nextOneWayRequestingAppAck();
            IdsModernSessionCoordinator.MessageMetadata receipt =
                    allocator.nextAppAckForIdentifier(
                            originalIdentifier);

            assertEquals(
                    0,
                    request.sequence);
            assertEquals(
                    IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                    request.flagsWithoutTopic);
            assertNull(
                    request.peerResponseIdentifier);
            assertEquals(
                    1,
                    receipt.sequence);
            assertEquals(
                    0,
                    receipt.flagsWithoutTopic);
            assertEquals(
                    originalIdentifier,
                    receipt.peerResponseIdentifier);
        }
    }

    @Test
    public void propertyRequestUsesPeerResponseAndAppAckFlags() {
        try (IdsApplicationMessageAllocator allocator =
                     new IdsApplicationMessageAllocator(
                             new ScriptedSecureRandom(
                                     filled((byte) 0x47)))) {
            IdsModernSessionCoordinator.MessageMetadata request =
                    allocator.nextRequestExpectingPeerResponseAndAppAck();
            assertEquals(
                    IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE
                            | IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                    request.flagsWithoutTopic);
            assertNull(
                    request.peerResponseIdentifier);
        }
    }

    @Test
    public void invalidResponseIdentifierDoesNotConsumeSequence() {
        try (IdsApplicationMessageAllocator allocator =
                     new IdsApplicationMessageAllocator(
                             new ScriptedSecureRandom(
                                     filled((byte) 0x55)))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> allocator.nextResponseToIdentifier(
                            "not-a-uuid"));
            assertEquals(
                    0,
                    allocator.nextOneWay().sequence);
        }
    }

    @Test
    public void maximumUint32IsIssuedOnceThenFailsClosed() {
        try (IdsApplicationMessageAllocator allocator =
                     IdsApplicationMessageAllocator.testingAtSequence(
                             new ScriptedSecureRandom(
                                     filled((byte) 0x66)),
                             0xffff_ffffL)) {
            assertEquals(
                    0xffff_ffffL,
                    allocator.nextOneWay().sequence);
            assertTrue(
                    allocator.snapshot().exhausted);
            assertThrows(
                    IllegalStateException.class,
                    allocator::nextOneWay);
        }
    }

    @Test
    public void closedAllocatorCannotIssueOrExposeState() {
        IdsApplicationMessageAllocator allocator =
                new IdsApplicationMessageAllocator(
                        new ScriptedSecureRandom(
                                filled((byte) 0x77)));
        allocator.close();
        assertThrows(
                IllegalStateException.class,
                allocator::nextOneWay);
        assertThrows(
                IllegalStateException.class,
                allocator::snapshot);
    }

    private static byte[] bytesFromZero() {
        byte[] value =
                new byte[16];
        for (int index = 0;
             index < value.length;
             index++) {
            value[index] =
                    (byte) index;
        }
        return value;
    }

    private static byte[] filled(
            byte value) {
        byte[] output =
                new byte[16];
        Arrays.fill(
                output,
                value);
        return output;
    }

    private static final class ScriptedSecureRandom
            extends SecureRandom {
        private final Queue<byte[]> values =
                new ArrayDeque<>();

        private ScriptedSecureRandom(
                byte[]... values) {
            for (byte[] value :
                    values) {
                this.values.add(
                        value.clone());
            }
        }

        @Override
        public void nextBytes(
                byte[] bytes) {
            byte[] value =
                    values.remove();
            if (value.length != bytes.length) {
                throw new IllegalStateException(
                        "Scripted random length mismatch");
            }
            System.arraycopy(
                    value,
                    0,
                    bytes,
                    0,
                    bytes.length);
        }
    }
}
