package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Allocates IDS socket-pair application metadata for one controller lifetime.
 *
 * <p>iOS 26.6 {@code IDSUTunDeliveryController} initializes its protected
 * uint32 message-ID counter to zero and returns a post-increment value. That
 * same value becomes both the socket-pair sequence number and message ID.
 * The client-side IDS framework creates a fresh random UUIDv4 and formats it
 * with uppercase hexadecimal for every send.</p>
 *
 * <p>This implementation deliberately fails closed after issuing
 * {@code 0xffffffff}. Apple's uint32 arithmetic wraps, but reusing an already
 * issued identifier in one live controller would make correlation ambiguous.
 * A new controller gets a new allocator and starts from zero.</p>
 */
final class IdsApplicationMessageAllocator
        implements AutoCloseable {
    private static final long MAX_SEQUENCE =
            0xffff_ffffL;
    private static final char[] HEX =
            "0123456789ABCDEF".toCharArray();

    private SecureRandom random;
    private long nextSequence;
    private boolean exhausted;
    private boolean closed;
    private final java.util.Set<UUID> clientIds=new java.util.HashSet<>();

    IdsApplicationMessageAllocator(
            SecureRandom random) {
        this(
                random,
                0);
    }

    private IdsApplicationMessageAllocator(
            SecureRandom random,
            long firstSequence) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "IDS application random source is absent");
        }
        if (firstSequence < 0
                || firstSequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException(
                    "IDS first application sequence must fit uint32");
        }
        this.random = random;
        nextSequence =
                firstSequence;
    }

    synchronized IdsModernSessionCoordinator.MessageMetadata
            nextOneWay() {
        return allocate(
                0,
                null);
    }

    synchronized IdsModernSessionCoordinator.MessageMetadata
            nextOneWayRequestingAppAck() {
        return allocate(
                IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                null);
    }

    synchronized IdsModernSessionCoordinator.MessageMetadata
            nextRequestExpectingPeerResponseAndAppAck() {
        return allocate(
                IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE
                        | IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                null);
    }

    synchronized IdsModernSessionCoordinator.MessageMetadata
            nextRequestExpectingPeerResponse() {
        return allocate(
                IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE,
                null);
    }

    /** A durable APK Health session allocates its UUID before the HAL emits any packets. */
    synchronized IdsModernSessionCoordinator.MessageMetadata nextHealthRequest(UUID message) {
        requireOpen();
        if(message==null || message.version()!=4 || message.variant()!=2 || clientIds.size()>=256
                || clientIds.contains(message)) throw new IllegalArgumentException("Invalid/repeated Health message UUID");
        var metadata=allocate(IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE|IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                null,message.toString().toUpperCase(java.util.Locale.ROOT));
        clientIds.add(message);return metadata;
    }

    synchronized IdsModernSessionCoordinator.MessageMetadata
            nextResponseToIdentifier(
                    String outgoingResponseIdentifier) {
        requireCanonicalUuid(
                outgoingResponseIdentifier,
                "IDS outgoing response identifier");
        return allocate(
                0,
                outgoingResponseIdentifier);
    }

    synchronized IdsModernSessionCoordinator.MessageMetadata
            nextAppAckForIdentifier(
                    String outgoingResponseIdentifier) {
        requireCanonicalUuid(
                outgoingResponseIdentifier,
                "IDS AppAck response identifier");
        return allocate(
                0,
                outgoingResponseIdentifier);
    }

    synchronized Snapshot snapshot() {
        requireOpen();
        return new Snapshot(
                nextSequence,
                exhausted);
    }

    private IdsModernSessionCoordinator.MessageMetadata allocate(
            int flags,
            String peerResponseIdentifier) {
        return allocate(flags,peerResponseIdentifier,null);
    }
    private IdsModernSessionCoordinator.MessageMetadata allocate(
            int flags,String peerResponseIdentifier,String suppliedUuid) {
        requireOpen();
        if (exhausted) {
            throw new IllegalStateException(
                    "IDS application sequence space is exhausted");
        }

        String messageUuid = suppliedUuid==null ? nextUppercaseUuidV4() : suppliedUuid;
        long sequence =
                nextSequence;
        if (sequence == MAX_SEQUENCE) {
            exhausted = true;
        } else {
            nextSequence =
                    sequence + 1;
        }
        return new IdsModernSessionCoordinator.MessageMetadata(
                sequence,
                messageUuid,
                flags,
                peerResponseIdentifier,
                null,
                null);
    }

    private String nextUppercaseUuidV4() {
        byte[] bytes =
                new byte[16];
        try {
            random.nextBytes(
                    bytes);
            bytes[6] =
                    (byte) ((bytes[6] & 0x0f) | 0x40);
            bytes[8] =
                    (byte) ((bytes[8] & 0x3f) | 0x80);
            return formatUuid(
                    bytes);
        } finally {
            Arrays.fill(
                    bytes,
                    (byte) 0);
        }
    }

    private static String formatUuid(
            byte[] bytes) {
        char[] output =
                new char[36];
        int byteIndex = 0;
        int outputIndex = 0;
        while (byteIndex < bytes.length) {
            if (outputIndex == 8
                    || outputIndex == 13
                    || outputIndex == 18
                    || outputIndex == 23) {
                output[outputIndex++] = '-';
                continue;
            }
            int value =
                    bytes[byteIndex++] & 0xff;
            output[outputIndex++] =
                    HEX[value >>> 4];
            output[outputIndex++] =
                    HEX[value & 0x0f];
        }
        return new String(
                output);
    }

    private static void requireCanonicalUuid(
            String value,
            String label) {
        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is absent");
        }
        UUID parsed;
        try {
            parsed =
                    UUID.fromString(
                            value);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(
                    label + " is malformed",
                    malformed);
        }
        if (!parsed.toString().equalsIgnoreCase(
                value)) {
            throw new IllegalArgumentException(
                    label + " is not canonical");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS application allocator is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        random = null;
        clientIds.clear();
    }

    /** Test-only construction at the uint32 boundary. */
    static IdsApplicationMessageAllocator testingAtSequence(
            SecureRandom random,
            long firstSequence) {
        return new IdsApplicationMessageAllocator(
                random,
                firstSequence);
    }

    static final class Snapshot {
        final long nextSequence;
        final boolean exhausted;

        private Snapshot(
                long nextSequence,
                boolean exhausted) {
            this.nextSequence =
                    nextSequence;
            this.exhausted =
                    exhausted;
        }
    }
}
