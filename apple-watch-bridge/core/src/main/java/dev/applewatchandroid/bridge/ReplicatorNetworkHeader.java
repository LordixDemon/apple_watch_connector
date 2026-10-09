package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;

/**
 * Native watchOS26.2 Replicator NetworkMessenger framing, independent of IDS
 * advertisements and OPACK bodies. This codec neither opens a connection nor
 * allocates the advertised payload. See WATCH_FACES_REPLICATOR_39.md for evidence.
 */
final class ReplicatorNetworkHeader {
    static final int BYTES = 72;
    // Protocol magic from the native binary, never a paired-device identifier.
    private static final UUID PREFIX = UUID.fromString("64d52923-8384-4b61-b55b-e53e9d20272c");
    static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    static final int MAX_SEQUENCE_COUNT = 1024;

    enum MessageType {
        DATA(0), FILE(1), EXTENDED_ATTRIBUTES(2), FAILURE(3), COMPRESSED_FILE(4);
        final int wireValue;
        MessageType(int wireValue) { this.wireValue = wireValue; }
    }
    enum Priority {
        HIGH(0), MEDIUM(1), LOW(2);
        final int wireValue;
        Priority(int wireValue) { this.wireValue = wireValue; }
    }
    private static final MessageType[] TYPES = MessageType.values();
    private static final Priority[] PRIORITIES = Priority.values();

    final UUID messageId;
    final UUID senderId;
    final int payloadBytes;
    final MessageType messageType;
    final int sequenceCount;
    final int sequenceIndex;
    final Priority priority;

    ReplicatorNetworkHeader(UUID messageId, UUID senderId, int payloadBytes,
            MessageType messageType, int sequenceCount, int sequenceIndex, Priority priority) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.senderId = Objects.requireNonNull(senderId, "senderId");
        this.messageType = Objects.requireNonNull(messageType, "messageType");
        this.priority = Objects.requireNonNull(priority, "priority");
        if (payloadBytes < 0 || payloadBytes > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Replicator payload length out of bounds");
        if (sequenceCount < 1 || sequenceCount > MAX_SEQUENCE_COUNT
                || sequenceIndex < 0 || sequenceIndex >= sequenceCount)
            throw new IllegalArgumentException("Replicator sequence out of bounds");
        this.payloadBytes = payloadBytes;
        this.sequenceCount = sequenceCount;
        this.sequenceIndex = sequenceIndex;
    }

    static ReplicatorNetworkHeader decode(byte[] encoded) {
        if (encoded == null || encoded.length != BYTES)
            throw new IllegalArgumentException("Replicator header must contain 72 bytes");
        ByteBuffer in = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (!PREFIX.equals(uuid(in)) || in.getInt() != BYTES)
            throw new IllegalArgumentException("Unsupported Replicator header preamble");
        UUID message = uuid(in), sender = uuid(in);
        int length = in.getInt(), type = in.getInt(), count = in.getInt(), index = in.getInt(), priority = in.getInt();
        if (type < 0 || type >= TYPES.length || priority < 0 || priority >= PRIORITIES.length)
            throw new IllegalArgumentException("Unsupported Replicator header enum");
        return new ReplicatorNetworkHeader(message, sender, length,
                TYPES[type], count, index, PRIORITIES[priority]);
    }

    byte[] encode() {
        ByteBuffer out = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        uuid(out, PREFIX);
        out.putInt(BYTES);
        uuid(out, messageId);
        uuid(out, senderId);
        out.putInt(payloadBytes).putInt(messageType.wireValue).putInt(sequenceCount)
                .putInt(sequenceIndex).putInt(priority.wireValue);
        return out.array();
    }

    private static UUID uuid(ByteBuffer in) {
        // UUID tuples use their canonical byte order; surrounding UInt32s are LE.
        return new UUID(Long.reverseBytes(in.getLong()), Long.reverseBytes(in.getLong()));
    }

    private static void uuid(ByteBuffer out, UUID value) {
        out.putLong(Long.reverseBytes(value.getMostSignificantBits()))
                .putLong(Long.reverseBytes(value.getLeastSignificantBits()));
    }
}
