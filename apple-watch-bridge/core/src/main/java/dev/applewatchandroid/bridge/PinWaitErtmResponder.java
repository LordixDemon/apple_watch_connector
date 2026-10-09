package dev.applewatchandroid.bridge;

/** Keeps the negotiated ERTM receive cursor alive while a person enters a PIN. */
final class PinWaitErtmResponder {
    private final int remoteCid;
    private final int expectedSequence;
    private final boolean fcsEnabled;
    private long nextHeartbeatMs;

    PinWaitErtmResponder(int remoteCid, int expectedSequence, boolean fcsEnabled) {
        this.remoteCid = remoteCid;
        this.expectedSequence = expectedSequence;
        this.fcsEnabled = fcsEnabled;
    }

    byte[] heartbeat(long nowMs) {
        if (nowMs < nextHeartbeatMs) return null;
        nextHeartbeatMs = nowMs + 1_000L;
        return receiverReady(false);
    }

    boolean consumes(L2capErtmCodec.Frame frame) {
        if (frame.supervisory) {
            return frame.supervisoryFunction == L2capErtmCodec.SUPERVISORY_RR
                    || frame.supervisoryFunction == L2capErtmCodec.SUPERVISORY_RNR;
        }
        return frame.txSequence == (expectedSequence + 63) % 64;
    }

    byte[] response(L2capErtmCodec.Frame frame) {
        if (!consumes(frame)) return null;
        if (frame.supervisory) {
            return frame.poll ? receiverReady(true) : null;
        }
        return receiverReady(false);
    }

    private byte[] receiverReady(boolean finalBit) {
        return L2capErtmCodec.encodeReceiverReady(
                remoteCid, expectedSequence, finalBit, fcsEnabled);
    }
}
