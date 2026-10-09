package dev.applewatchandroid.bridge;

/**
 * Decides whether a Watch ACL {@code 0x13} during an IDS session may start a
 * new paired LE reconnect and control SYN.
 *
 * <p>Live 0.2.122 (20:50): durable {@code CLASS_C_ESTABLISHED} reconnects after
 * every {@code 0x13}, even when this session never completed Hello and Watch
 * already sent NWSC {@code REJECTED_BY_POLICY} (0x40). iOS 26.6
 * {@code cancel_request_inner} never opens a new SYN after 0x40; the retry
 * loop only poisons the Watch into POLICY again.</p>
 */
final class IdsPairedReconnectPolicy {
    /**
     * After DeviceLinkState + Class-D/C NA + Class-D Setup wake, Watch should
     * open {@code ids-control-channel} in ~1.1s (live 0.2.196). ACL bounce
     * cannot clear Watch {@code connecting} (Ghidra: only
     * {@code didConnectControlChannel} calls {@code setConnecting:NO}) and
     * live 0.2.298 showed bounce tearing down an already SYN-ACK'd Urgent-D
     * → Watch:61314 before the 20s NWSC timer. Keep MAX=0; wait for Watch
     * control or Urgent-D {@code REJECTED_BY_POLICY}.
     */
    static final long CONTROL_SILENCE_BOUNCE_MS = 25_000L;
    static final int CONTROL_SILENCE_BOUNCE_MAX = 0;

    static boolean usePairedDataBootstrap(PairingSessionRecord.DurableState state) {
        // IDS_DATA_READY describes a previous process, not an open control
        // connection or still-negotiated data lanes. An interrupted initial
        // setup must repeat Hello + Setup before opening a new NWSC lane.
        // Live .158 skipped both and received 0x40 after 20 seconds, matching
        // watchOS 23S303 Network's unclaimed service-available timeout.
        return state != null && state.wireValue()
                >= PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED.wireValue();
    }
    enum AdvertisedMode { NORMAL, SETUP_ONLY, UNSUPPORTED }

    static AdvertisedMode advertisedMode(java.util.List<Integer> services) {
        if (services.contains(HciCodec.TERMINUS_LINK_SERVICE_ID)) return AdvertisedMode.NORMAL;
        if (services.contains(HciCodec.TERMINUS_PAIRING_SERVICE_ID)) return AdvertisedMode.SETUP_ONLY;
        return AdvertisedMode.UNSUPPORTED;
    }
    private IdsPairedReconnectPolicy() {
    }

    /** Reuse actual credentials evidence only for an activated, authenticated same-pair session. */
    static boolean allowSavedDeviceInfoForPostCommit(
            PairingSessionRecord.DurableState state, boolean previouslyExchanged,
            boolean freshPairing, boolean currentHelloMatchesIdentity, boolean lanesReady) {
        return state != null
                && state.wireValue() >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()
                && previouslyExchanged && !freshPairing && currentHelloMatchesIdentity && lanesReady;
    }

    static boolean allowReconnectAfterDisconnect(
            boolean controlRejectedByPolicy,
            boolean controlHelloCompletedThisSession,
            boolean durablePairedCommit) {
        if (controlRejectedByPolicy) {
            return false;
        }
        if (!controlHelloCompletedThisSession) {
            return false;
        }
        return durablePairedCommit;
    }

    static boolean allowControlSilenceBounce(
            int bouncesAlready,
            boolean controlRejectedByPolicy) {
        return !controlRejectedByPolicy
                && bouncesAlready < CONTROL_SILENCE_BOUNCE_MAX;
    }
}
