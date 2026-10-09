package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.TreeSet;
import java.util.UUID;

/** One native Bulletin sequence manager per HAL attachment; no setup/activation state. */
final class BulletinTransportSession {
    record Received(boolean fresh, byte[] acknowledgement, int localState) { }
    private UUID localId = UUID.randomUUID();
    private UUID peerId;
    private int sequence = 1, state = 1;
    private int retiredThrough = -1;
    private final TreeSet<Integer> received = new TreeSet<>();
    private final LinkedHashSet<UUID> retiredPeers = new LinkedHashSet<>();

    UUID localId() { return localId; }
    int state() { return state; }
    void reset() {
        localId = UUID.randomUUID(); peerId = null; sequence = 1; state = 1;
        retiredThrough = -1; received.clear(); retiredPeers.clear();
    }
    byte[] wrap(byte[] core) {
        if (sequence == Integer.MAX_VALUE) throw new IllegalStateException("Bulletin sequence exhausted");
        return BulletinDistributorCodec.packWithTrailer(core, localId, sequence++, state == 1, state == 0 ? null : state);
    }
    Received receive(int type, boolean response, byte[] payload) {
        BulletinDistributorCodec.MessageAndTrailer split = BulletinDistributorCodec.splitTrailer(payload);
        try {
            BulletinDistributorCodec.Trailer trailer = split.trailer;
            if (trailer == null) throw new IllegalArgumentException("Invalid Bulletin trailer");
            if (trailer.state != null && (trailer.state < 0 || trailer.state > 2)) throw new IllegalArgumentException("Invalid session state");
            BulletinDistributorCodec.InitialSequenceAck ack = type == 12 && !response
                    ? BulletinDistributorCodec.InitialSequenceAck.decode(split.messagePayload) : null;
            if (!trailer.sessionId.equals(peerId)) {
                if (retiredPeers.contains(trailer.sessionId)) return new Received(false, null, state);
                if (peerId != null) {
                    retiredPeers.add(peerId);
                    if (retiredPeers.size() > 8) retiredPeers.remove(retiredPeers.iterator().next());
                }
                peerId = trailer.sessionId; received.clear(); retiredThrough = -1;
            }
            if (trailer.sequence <= retiredThrough || !received.add(trailer.sequence)) {
                return new Received(false, null, state);
            }
            if (received.size() > 256) {
                int first = received.first();
                received.remove(first); retiredThrough = Math.max(retiredThrough, first);
            }
            byte[] acknowledgement = trailer.state != null && trailer.state != 0
                    ? new BulletinDistributorCodec.InitialSequenceAck(null, peerId, trailer.state).encode() : null;
            // Native BLTRemoteObject handles incoming transport state before ACK body.
            if (ack != null) {
                if (Boolean.TRUE.equals(ack.assertion())) state = 1;
                else if (ack.sessionId() == null) state = 0;
                else if (localId.equals(ack.sessionId())) {
                    if (Integer.valueOf(1).equals(ack.state())) state = 2;
                    else if (Integer.valueOf(2).equals(ack.state())) state = 0;
                }
            }
            return new Received(true, acknowledgement, state);
        } finally {
            if (split.messagePayload != payload) Arrays.fill(split.messagePayload, (byte) 0);
        }
    }
}
