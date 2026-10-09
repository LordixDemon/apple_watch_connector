package dev.applewatchandroid.bridge;

/** Public setup progress, independent of journal wording and transport ownership. */
final class SetupProgressState {
    static final String PREFIX = "WATCH_SETUP_PHASE_V1:";
    enum Phase {
        IDLE, STARTING, DISCOVERING, CONNECTING, PIN_REQUIRED, SECURITY, IDS,
        REGISTRY, CONFIGURING, ACTIVATING, ACTIVATION_INPUT, ACTIVATED, SYNCING,
        WAITING_FOR_WATCH, VERIFYING_RECONNECT, VERIFIED, FAILED, STOPPING, STOPPED
    }
    private Phase phase = Phase.IDLE;
    Phase phase() { return phase; }
    void reset() { phase = Phase.STARTING; }
    void ownerConfirmed() { phase = Phase.VERIFIED; }

    boolean observe(Phase next) {
        if (next == null || next == Phase.IDLE || next == phase) return false;
        if (phase == Phase.FAILED || phase == Phase.STOPPED || phase == Phase.VERIFIED) return false;
        if (next == Phase.FAILED) { phase = next; return true; }
        if (phase == Phase.STOPPING && next != Phase.STOPPED && next != Phase.FAILED) return false;
        if (rank(next) < rank(phase)) return false;
        phase = next;
        return true;
    }
    private static int rank(Phase value) {
        // Owner input temporarily replaces the activation label without losing its position.
        return value == Phase.ACTIVATION_INPUT ? Phase.ACTIVATING.ordinal() : value.ordinal();
    }
    static Phase decode(String line) {
        if (line == null || !line.startsWith(PREFIX) || line.length() > 96) {
            throw new IllegalArgumentException("Invalid setup progress event");
        }
        return Phase.valueOf(line.substring(PREFIX.length()));
    }
    static String encode(Phase value) { return PREFIX + value.name(); }

    /** Publication/ACK cannot establish a physical Watch face. */
    static Phase postCommit(AppleWatchPostCommitCoordinator.Snapshot state) {
        if (state.phase == AppleWatchPostCommitCoordinator.Phase.FORWARD_RECOVERY_REQUIRED) return Phase.FAILED;
        if (state.phase == AppleWatchPostCommitCoordinator.Phase.COMPLETE) return Phase.VERIFIED;
        if (state.phase == AppleWatchPostCommitCoordinator.Phase.VERIFYING_OPERATIONAL_HEALTH) return Phase.VERIFYING_RECONNECT;
        if (state.phase == AppleWatchPostCommitCoordinator.Phase.WAITING_FOR_CLOCK
                || state.initialSyncPrepared && state.pairedSyncSent) return Phase.WAITING_FOR_WATCH;
        if (state.activationConfirmed) return state.initialSyncPrepared ? Phase.SYNCING : Phase.ACTIVATED;
        return state.activationAttempt > 0 ? Phase.ACTIVATING : Phase.CONFIGURING;
    }
}
