package dev.applewatchandroid.bridge;

/**
 * Decides whether a reconnect attempt that did not return success should
 * drop the ACL itself.
 *
 * <p>A STOP during a live hold has not finished the reconnect method, so
 * the success flag is still false. Tearing the link down there clears the
 * connection handle before cleanup can send HCI Disconnect. Live 0.2.220
 * logged {@code handle=none} and left the Watch holding the ACL.</p>
 */
final class ReconnectStopPolicy {
    private ReconnectStopPolicy() {
    }

    static boolean tearDownIncompleteReconnect(
            boolean reconnected,
            boolean stopRequested) {
        return !reconnected && !stopRequested;
    }
}
