package dev.applewatchandroid.bridge;

/**
 * How long to wait before repeating the same unacked ERTM I-frame.
 *
 * <p>The idle poll is 500 ms. Live 0.2.222 kept retransmitting one TxSeq at
 * that rate for hours after the Watch's I-frame had moved on and its RR
 * stayed on an older ReqSeq. The first few copies are immediate; after that
 * the same TxSeq waits, so the Watch can send the next frame.</p>
 */
final class ErtmRetransmitPace {
    private ErtmRetransmitPace() {
    }

    static long delayMs(int sendsOfThisSequence) {
        if (sendsOfThisSequence <= 4) {
            return 0L;
        }
        if (sendsOfThisSequence <= 12) {
            return 5_000L;
        }
        return 30_000L;
    }
}
