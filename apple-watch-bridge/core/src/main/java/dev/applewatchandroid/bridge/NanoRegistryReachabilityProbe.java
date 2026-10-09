package dev.applewatchandroid.bridge;

import java.util.UUID;

/** Read-only application probe; none of these states advances Watch setup. */
final class NanoRegistryReachabilityProbe {
    enum Status { NOT_STARTED, STARTED, QUEUED, DELIVERED, CLIENT_ACKNOWLEDGED, REPLIED }
    private Status status = Status.NOT_STARTED;
    private long sequence;
    private UUID requestId;

    Status status() { return status; }

    boolean start() {
        if (status != Status.NOT_STARTED) return false;
        status = Status.STARTED;
        return true;
    }

    NanoRegistryClassDCodec.PingRequest request() {
        if (status != Status.STARTED) throw new IllegalStateException("Probe was not started");
        // Watch 23S303 registers type 5 as an always-available handler.
        // The response contains its date and optional random padding, not an echo.
        return new NanoRegistryClassDCodec.PingRequest(300, 8.0, 0, null);
    }

    void onQueued(long messageSequence, String messageUuid) {
        if (status != Status.STARTED) throw new IllegalStateException("Probe already queued");
        requestId = UUID.fromString(messageUuid);
        sequence = messageSequence;
        status = Status.QUEUED;
    }

    boolean onAcknowledgement(String service, long acknowledgedSequence) {
        if (status != Status.QUEUED || acknowledgedSequence != sequence
                || !IdsUtunConnectionName.isClassDIdentifier(service)) return false;
        status = Status.DELIVERED;
        return true;
    }

    boolean onResponse(String topic, int protobufType, boolean response,
                       String replyIdentifier, byte[] payload) {
        if ((status != Status.QUEUED && status != Status.DELIVERED && status != Status.CLIENT_ACKNOWLEDGED)
                || !NanoRegistryClassDCodec.SERVICE.equals(topic)
                || protobufType != NanoRegistryClassDCodec.TYPE_PING || !response
                || replyIdentifier == null) return false;
        try {
            if (!requestId.equals(UUID.fromString(replyIdentifier))) return false;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        NanoRegistryClassDCodec.PingResponse decoded =
                NanoRegistryClassDCodec.decodePingResponse(payload);
        try {
            if (!Double.isFinite(decoded.responseDate)) return false;
            status = Status.REPLIED;
            return true;
        } finally {
            decoded.destroy();
        }
    }

    boolean onClientAcknowledgement(String service, String topic, String identifier) {
        if ((status != Status.QUEUED && status != Status.DELIVERED)
                || !IdsUtunConnectionName.isClassDIdentifier(service)
                || !NanoRegistryClassDCodec.SERVICE.equals(topic) || identifier == null) return false;
        try {
            if (!requestId.equals(UUID.fromString(identifier))) return false;
        } catch (IllegalArgumentException malformed) { return false; }
        status = Status.CLIENT_ACKNOWLEDGED;
        return true;
    }
}
