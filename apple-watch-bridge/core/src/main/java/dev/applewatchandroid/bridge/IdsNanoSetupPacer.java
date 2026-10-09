package dev.applewatchandroid.bridge;

/**
 * Holds the first NanoRegistry Check until the shared ERTM Tx window and
 * IDS TCP send streams are quiet.
 *
 * <p>Live 0.2.118–0.2.120 encoded Check in the handshake I-frame burst.
 * Live 0.2.121 waited 421 ms then fail-opened at {@code outstanding=18}:
 * Check and the 9-byte Alloy handshakes stayed TCP-{@code una}-stuck, then
 * {@code 0x13}. Do not send on a timer while the window is still full.</p>
 *
 * <p>Live PIN 0.2.126: ERTM outstanding dropped to quiet in 536 ms (Watch
 * piggyback retired I-frames, including holes it still SREJ'd) and Check
 * went out while Class-D TCP una was still 117 bytes and a 9-byte handshake
 * sat on another port. Alloy ACK never arrived. Require {@code una==nxt}
 * as well.</p>
 *
 * <p>Live PIN 0.2.127: ERTM drained to outstanding=1 but {@code tcpUna=27}
 * (three 9-byte Alloy handshakes) never moved. Hold cloned the oldest
 * empty-ACK I-frame; Watch ERTM-acked it, payload was already retired.
 * When ERTM is quiet and TCP una is still up, retransmit the TCP payload
 * in a new I-frame. Do not fail-open Check.</p>
 */
final class IdsNanoSetupPacer {
    static final long MIN_WAIT_MS = 40L;
    static final int QUIET_OUTSTANDING = 1;

    private IdsNanoSetupPacer() {
    }

    static boolean isErtmQuiet(
            int ertmOutstandingCount) {
        return ertmOutstandingCount >= 0
                && ertmOutstandingCount <= QUIET_OUTSTANDING;
    }

    static boolean isTransportQuiet(
            int ertmOutstandingCount,
            long tcpUnacknowledgedBytes) {
        return isErtmQuiet(
                ertmOutstandingCount)
                && tcpUnacknowledgedBytes == 0L;
    }

    /**
     * Live 0.2.124 blasted new I-frames while the window was full. Live
     * 0.2.127 refused to TCP-retransmit once the window was empty, so the
     * 9-byte handshakes died. Only mint payload I-frames on a quiet window.
     */
    static boolean shouldRetransmitTcpDuringHold(
            boolean ertmQuiet,
            long tcpUnacknowledgedBytes) {
        return ertmQuiet
                && tcpUnacknowledgedBytes > 0L;
    }

    static boolean shouldSendCheck(
            boolean transportQuiet,
            long elapsedMs) {
        if (elapsedMs < 0) {
            throw new IllegalArgumentException(
                    "IDS nano-setup wait elapsed is negative");
        }
        return transportQuiet
                && elapsedMs >= MIN_WAIT_MS;
    }
}
