package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** One non-replayed delta session. Transport acceptance never means the Watch applied the change. */
final class ClockFaceDeltaSession implements AutoCloseable {
    enum Stage { START_READY, START_WAIT, BATCH_READY, BATCH_WAIT, END_READY, END_WAIT,
        READBACK_WAIT, APPLIED, REJECTED, UNKNOWN }
    private static final long READBACK_TIMEOUT_MS = 180_000;
    final UUID pair, epoch, watchPeer;
    final String session;
    private final ClockFaceDeltaPlan plan;
    private Stage stage = Stage.START_READY;
    private UUID pendingIdentifier;
    private UUID phonePeer, phoneGeneration;
    private long lastSequence;
    private int batchIndex;
    private long deadline, lastNow, endUnixMs;
    private boolean packetPrepared;
    private boolean closed;

    ClockFaceDeltaSession(UUID pair, UUID epoch, UUID watchPeer, String session, ClockFaceDeltaPlan plan, long elapsedMs) {
        if (pair == null || epoch == null || watchPeer == null || plan == null || elapsedMs < 0
                || elapsedMs > Long.MAX_VALUE - 1800000L
                || session == null || !session.matches("P[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}")) throw invalid();
        this.pair = pair; this.epoch = epoch; this.watchPeer = watchPeer; this.session = session; this.plan = plan;
        lastNow = elapsedMs; deadline = elapsedMs + plan.writeTimeoutMs();
    }

    Stage stage() { return stage; }
    String face() { return plan.face; }
    ClockFaceDeltaPlan.Kind kind() { return plan.kind; }
    boolean terminal() { return stage == Stage.APPLIED || stage == Stage.REJECTED || stage == Stage.UNKNOWN; }

    byte[] prepare(byte[] header, long elapsedMs) {
        tick(elapsedMs);
        if (closed || terminal() || packetPrepared) throw invalid();
        var identity = ClockFaceSyncHeaderCodec.decode(header);
        if (identity.peer().equals(watchPeer) || identity.sequence() <= lastSequence
                || phonePeer != null && (!phonePeer.equals(identity.peer()) || !phoneGeneration.equals(identity.generation()))) throw invalid();
        byte[] packet;
        if (stage == Stage.START_READY) packet = ClockFaceDeltaProtocol.start(header, session, plan.writeTimeoutMs() / 1000.0);
        else if (stage == Stage.END_READY) packet = ClockFaceDeltaProtocol.end(header, session);
        else if (stage == Stage.BATCH_READY) {
            List<byte[]> changes = plan.copyBatch(batchIndex);
            try { packet = ClockFaceDeltaProtocol.batch(header, session, batchIndex, changes); }
            finally { for (byte[] bytes : changes) Arrays.fill(bytes, (byte) 0); }
        } else throw invalid();
        phonePeer = identity.peer(); phoneGeneration = identity.generation(); lastSequence = identity.sequence();
        packetPrepared = true; return packet;
    }

    void sent(UUID identifier, long elapsedMs) {
        tick(elapsedMs);
        if (closed || terminal() || identifier == null || !packetPrepared) throw invalid();
        stage = switch (stage) {
            case START_READY -> Stage.START_WAIT;
            case BATCH_READY -> Stage.BATCH_WAIT;
            case END_READY -> Stage.END_WAIT;
            default -> throw invalid();
        };
        pendingIdentifier = identifier; packetPrepared = false;
    }

    /** An unrelated packet is ignored; a malformed correlated reply leaves outcome unknown. */
    boolean response(String topic, int flags, UUID identifier, byte[] bytes, long unixMs, long elapsedMs) {
        tick(elapsedMs);
        if (closed || terminal() || pendingIdentifier == null || !pendingIdentifier.equals(identifier)
                || !IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE.equals(topic)
                || (flags & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE) != 0) return false;
        try {
            var reply = ClockFaceDeltaProtocol.response(bytes);
            int expected = stage == Stage.START_WAIT ? ClockFaceSyncAccept.START
                    : stage == Stage.BATCH_WAIT ? ClockFaceSyncAccept.BATCH : ClockFaceSyncAccept.END;
            if (reply.type() != expected || !session.equals(reply.session())
                    || !watchPeer.equals(reply.header().peer()) || expected == ClockFaceSyncAccept.BATCH && reply.index() != batchIndex) return false;
            pendingIdentifier = null;
            if (!reply.accepted() || reply.hasError() || reply.didRollback()) {
                // A batch/end error may follow a partially applied transaction; no automatic retry.
                stage = expected == ClockFaceSyncAccept.START ? Stage.REJECTED : Stage.UNKNOWN;
            } else if (stage == Stage.START_WAIT) stage = Stage.BATCH_READY;
            else if (stage == Stage.BATCH_WAIT) stage = ++batchIndex < plan.batchCount() ? Stage.BATCH_READY : Stage.END_READY;
            else {
                long readbackTimeout = Math.max(READBACK_TIMEOUT_MS, plan.writeTimeoutMs());
                if (unixMs <= ClockFaceSyncHeaderCodec.APPLE_EPOCH_MS || elapsedMs > Long.MAX_VALUE - readbackTimeout) throw invalid();
                endUnixMs = unixMs; deadline = elapsedMs + readbackTimeout;
                stage = Stage.READBACK_WAIT;
            }
        } catch (IllegalArgumentException malformed) { stage = Stage.UNKNOWN; pendingIdentifier = null; }
        if (terminal()) plan.close();
        return true;
    }

    /** Caller supplies only a newly committed receiver snapshot, never staging or the requested plan. */
    boolean observe(UUID observedPair, UUID observedEpoch, ClockFaceCollection collection, long elapsedMs) {
        tick(elapsedMs);
        if (closed || stage != Stage.READBACK_WAIT || !pair.equals(observedPair) || !epoch.equals(observedEpoch)
                || collection == null || !collection.complete() || collection.observedAt <= endUnixMs
                || collection.observedAt <= plan.baselineObservedAt) return false;
        stage = plan.matches(collection) ? Stage.APPLIED : Stage.UNKNOWN;
        plan.close(); return true;
    }

    void tick(long elapsedMs) {
        if (elapsedMs < 0 || elapsedMs < lastNow || elapsedMs >= deadline) {
            if (!terminal()) { stage = Stage.UNKNOWN; pendingIdentifier = null; plan.close(); }
        }
        lastNow = Math.max(lastNow, elapsedMs);
    }

    void disconnected() { if (!terminal()) { stage = Stage.UNKNOWN; pendingIdentifier = null; plan.close(); } }
    @Override public void close() { disconnected(); closed = true; plan.close(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid native face delta session transition"); }
}
