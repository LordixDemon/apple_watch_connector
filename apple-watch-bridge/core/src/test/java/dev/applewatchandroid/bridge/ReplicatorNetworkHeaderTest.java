package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.Arrays;
import org.junit.Test;

public class ReplicatorNetworkHeaderTest {
    private byte[] nativeHeader() throws Exception {
        try (var in = getClass().getResourceAsStream("replicator-39-native-header.bin")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    @Test
    public void readsAndReproducesActualNativeBinaryGetter() throws Exception {
        // Native NetworkSyncHeader.data getter, not our encoder or OPACKCoder.
        byte[] bytes = nativeHeader();
        var header = ReplicatorNetworkHeader.decode(bytes);
        assertEquals(UUID.fromString("11223344-5566-7788-9900-aabbccddeeff"), header.messageId);
        assertEquals(UUID.fromString("22334455-6677-8899-0011-aabbccddeeff"), header.senderId);
        assertEquals(432, header.payloadBytes);
        assertEquals(ReplicatorNetworkHeader.MessageType.DATA, header.messageType);
        assertEquals(1, header.sequenceCount);
        assertEquals(0, header.sequenceIndex);
        assertEquals(ReplicatorNetworkHeader.Priority.HIGH, header.priority);
        assertArrayEquals(bytes, header.encode());
    }

    @Test
    public void matchesEveryNativeMessageTypeAndPriority() throws Exception {
        byte[] all;
        try (var in = getClass().getResourceAsStream("replicator-39-native-header-enums.bin")) {
            assertNotNull(in);
            all = in.readAllBytes();
        }
        assertEquals(15 * 72, all.length);
        int offset = 0;
        for (var type : ReplicatorNetworkHeader.MessageType.values()) {
            for (var priority : ReplicatorNetworkHeader.Priority.values()) {
                byte[] encoded = Arrays.copyOfRange(all, offset, offset + 72);
                var decoded = ReplicatorNetworkHeader.decode(encoded);
                assertEquals(type, decoded.messageType);
                assertEquals(priority, decoded.priority);
                assertArrayEquals(encoded, decoded.encode());
                offset += 72;
            }
        }
    }

    @Test
    public void rejectsInvalidPreambleAndBoundsBeforePayloadAllocation() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ReplicatorNetworkHeader.decode(null));
        for (int size : new int[]{0, 20, 71, 73}) {
            assertThrows(IllegalArgumentException.class, () -> ReplicatorNetworkHeader.decode(new byte[size]));
        }
        byte[] prefix = nativeHeader();
        prefix[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ReplicatorNetworkHeader.decode(prefix));
        for (int[] change : new int[][]{
                {16, 71}, {16, -1}, {52, -1}, {52, ReplicatorNetworkHeader.MAX_PAYLOAD_BYTES + 1},
                {56, 5}, {56, -1}, {60, 0}, {60, ReplicatorNetworkHeader.MAX_SEQUENCE_COUNT + 1},
                {64, -1}, {64, 1}, {68, 3}, {68, -1}}) {
            byte[] changed = nativeHeader();
            ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(change[0], change[1]);
            assertThrows(IllegalArgumentException.class, () -> ReplicatorNetworkHeader.decode(changed));
        }
    }

    @Test
    public void acceptsNativeEmptyFailureAndBoundedSequence() throws Exception {
        byte[] bytes = nativeHeader();
        ByteBuffer out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(52, 0).putInt(56, 3).putInt(60, 2).putInt(64, 1).putInt(68, 2);
        var header = ReplicatorNetworkHeader.decode(bytes);
        assertEquals(ReplicatorNetworkHeader.MessageType.FAILURE, header.messageType);
        assertEquals(ReplicatorNetworkHeader.Priority.LOW, header.priority);
        assertEquals(1, header.sequenceIndex);
        assertEquals(0, header.payloadBytes);
        assertArrayEquals(bytes, header.encode());
    }
}
