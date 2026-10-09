package dev.applewatchandroid.bridge;

/**
 * Fail-closed ordering for the first long-lived IDS lanes above a ready
 * normal link.
 *
 * <p>The control channel must complete its hello exchange before the initial
 * NanoRegistry Class-D lane starts. Class C starts only after Class D joins,
 * unless the Watch already opened Class-C itself. READY waits until Urgent
 * Class-D and Class-C have both joined <em>and</em> completed socket-pair
 * Handshake, so Check then the phone snapshot go on the wire in that order
 * after the shared ERTM Tx window drains — not in the same I-frame burst as
 * the Urgent/Default/Sync handshakes.</p>
 */
final class IdsBootstrapState {
    enum Phase {
        NEW,
        CONTROL_OPENING,
        CLASS_D_OPENING,
        CLASS_C_OPENING,
        READY,
        CLOSED
    }

    enum Action {
        NONE,
        START_CONTROL,
        START_KEY_PROBE,
        START_PAIRED_CLASS_D,
        START_CLASS_D,
        START_CLASS_C,
        READY
    }

    private static final String CLASS_D_CONNECTOR =
            NanoRegistryInitialIdsRoute
                    .classDSetup()
                    .serviceConnectorName
                    .encode();
    private static final String CLASS_C_CONNECTOR =
            NanoRegistryInitialIdsRoute
                    .classCProperties()
                    .serviceConnectorName
                    .encode();

    private Phase phase =
            Phase.NEW;
    private boolean pairedMode;
    private boolean linkDirectorKeyProbeStarted;
    private int pairedKeyProbesCompleted;
    private boolean pairedControlRecoveryRequested;
    private boolean controlReady;
    private boolean classDJoined;
    private boolean classCJoined;
    private boolean classDHandshake;
    private boolean classCHandshake;

    synchronized Action begin() {
        requireOpen();
        if (phase != Phase.NEW) {
            throw new IllegalStateException(
                    "IDS bootstrap was already started");
        }
        phase = Phase.CONTROL_OPENING;
        return Action.START_CONTROL;
    }

    synchronized Action beginPaired() {
        requireOpen();
        if (phase != Phase.NEW) {
            throw new IllegalStateException(
                    "IDS bootstrap was already started");
        }
        // A new process has no live IDS control TCP. Opening Urgent-D
        // before Hello leaves the service-available block unclaimed, and
        // the Watch answers that SYN with REJECTED_BY_POLICY about 20 s
        // later (live 0.2.213 reconnect, tcp ephemeral→61314).
        pairedMode = true;
        phase = Phase.CONTROL_OPENING;
        return Action.START_CONTROL;
    }

    /**
     * Paired reconnect must not SYN ids-control-channel. Live 20:17–21:13:
     * every phone-opened control TCP is REJECTED_BY_POLICY after 20s, while
     * the setup session that actually activated got control from the Watch.
     */
    synchronized Action beginPairedWaitForWatch() {
        requireOpen();
        if (phase != Phase.NEW) {
            throw new IllegalStateException(
                    "IDS bootstrap was already started");
        }
        pairedMode = true;
        phase = Phase.CONTROL_OPENING;
        return Action.NONE;
    }

    /**
     * Paired attach: open NanoRegistry Class-D Setup in the same burst as
     * LinkDirector Hello (live 0.2.196: {@code 56514→61314} before DeviceLinkState
     * ACK, then Watch {@code ids-control-channel} ~1.1s after LDM). Waiting
     * until after LDM (0.2.298) still got Watch SYN-ACK on Setup then 0x40
     * at 20s with no control — Watch
     * {@code startControlChannelWithDevice:} already called
     * {@code nw_service_connector_start_request} at pipe-connect (EINPROGRESS
     * 0x24 = "already have active request"; later complete never fires if
     * the first unique_connection arrives after that window). Do not SYN
     * {@code ids-control-channel} from the phone.
     */
    synchronized Action beginPairedListenImmediately() {
        requireOpen();
        if (phase != Phase.NEW) {
            throw new IllegalStateException(
                    "IDS bootstrap was already started");
        }
        pairedMode = true;
        phase = Phase.CLASS_D_OPENING;
        return Action.START_CLASS_D;
    }

    /**
     * After DeviceLinkState ACK: Setup already started on attach. NA is
     * still emitted by the host. Do not SYN another named UTun.
     */
    synchronized Action onLinkDirectorReady() {
        requireOpen();
        return Action.NONE;
    }

    /** One bounded recovery after the paired Setup lane's policy timeout. */
    synchronized Action onPairedLanePolicyRejection() {
        requireOpen();
        if (!pairedMode || controlReady || linkDirectorKeyProbeStarted) {
            return Action.NONE;
        }
        // Live 2026-10-05 20:02: all four normal/cloud C/D NoOp exchanges
        // succeeded, then a phone control request was accepted and Hello
        // plus all data lanes resumed. An ACL bounce alone did not do this.
        linkDirectorKeyProbeStarted = true;
        return Action.START_KEY_PROBE;
    }

    /** Wait for all four key-probe receipts before requesting control once. */
    synchronized Action onPairedKeyProbeCompleted() {
        requireOpen();
        if (pairedMode && linkDirectorKeyProbeStarted && !controlReady
                && !pairedControlRecoveryRequested) {
            pairedKeyProbesCompleted++;
            if (pairedKeyProbesCompleted == 4) {
                pairedControlRecoveryRequested = true;
                return Action.START_CONTROL;
            }
        }
        return Action.NONE;
    }

    synchronized Action observe(
            IdsModernSessionCoordinator.EventType type,
            String service) {
        requireOpen();
        if (type == null) {
            throw new IllegalArgumentException(
                    "IDS bootstrap event type is absent");
        }
        if (type
                == IdsModernSessionCoordinator.EventType.CONTROL_READY) {
            controlReady = true;
            if (phase == Phase.CONTROL_OPENING || phase == Phase.NEW) {
                if (classDJoined) {
                    if (!classCJoined) {
                        phase = Phase.CLASS_C_OPENING;
                        return Action.START_CLASS_C;
                    }
                    return maybeReady();
                }
                phase =
                        Phase.CLASS_D_OPENING;
                return Action.START_CLASS_D;
            }
            return maybeReady();
        }
        if (type
                == IdsModernSessionCoordinator.EventType
                .HANDSHAKE_RECEIVED) {
            if (isUrgentClassDLane(service) || isClassDLane(service)) {
                classDHandshake = true;
            } else if (isUrgentClassCLane(service) || (pairedMode && isClassCLane(service))) {
                classCHandshake = true;
            } else {
                return Action.NONE;
            }
            return maybeReady();
        }
        if (type != IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED
                && type != IdsModernSessionCoordinator.EventType.DATA_SERVICE_ACCEPTED
                && type != IdsModernSessionCoordinator.EventType.DATA_RECEIVED) {
            return Action.NONE;
        }
        if (phase == Phase.READY) {
            return Action.NONE;
        }
        boolean isClassD = isClassDLane(service);
        boolean isClassC = isClassCLane(service);

        if (isClassD) {
            classDJoined = true;
            if (isDirectAlloyLane(service)) {
                classDHandshake = true;
            }
            if (!classCJoined
                    && (phase == Phase.CLASS_D_OPENING
                    || (phase == Phase.CONTROL_OPENING && controlReady))) {
                phase =
                        Phase.CLASS_C_OPENING;
                return Action.START_CLASS_C;
            }
            return maybeReady();
        }
        if (isClassC) {
            classCJoined = true;
            if (isDirectAlloyLane(service)) {
                classCHandshake = true;
            }
            return maybeReady();
        }
        return Action.NONE;
    }

    private Action maybeReady() {
        if (phase == Phase.READY) {
            return Action.NONE;
        }
        if ((controlReady || pairedMode)
                && classDJoined
                && classCJoined
                && classDHandshake
                && classCHandshake) {
            phase = Phase.READY;
            return Action.READY;
        }
        return Action.NONE;
    }

    synchronized Phase phase() {
        return phase;
    }

    synchronized boolean ready() {
        return phase == Phase.READY;
    }

    synchronized boolean requiredLaneRejected(String service) {
        return (phase == Phase.CLASS_D_OPENING && !classDJoined && isClassDLane(service))
                || (phase == Phase.CLASS_C_OPENING && !classCJoined && isClassCLane(service));
    }

    synchronized void close() {
        phase =
                Phase.CLOSED;
    }

    private void requireOpen() {
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException(
                    "IDS bootstrap is closed");
        }
    }

    /**
     * Class-D NanoRegistry / UTun lanes. Matching is by connector, topic, or
     * the last UTun protection suffix ({@code ...-D}), never by a
     * {@code default-d} substring inside {@code Default-Default-C}.
     */
    private static boolean isClassDLane(String service) {
        if (service == null) {
            return false;
        }
        if (CLASS_D_CONNECTOR.equals(service)
                || "nano-class-d".equals(service)
                || NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(service)) {
            return true;
        }
        return IdsUtunConnectionName.isClassDIdentifier(service)
                && !IdsUtunConnectionName.isClassCIdentifier(service);
    }

    private static boolean isClassCLane(String service) {
        if (service == null) {
            return false;
        }
        if (CLASS_C_CONNECTOR.equals(service)
                || "nano-class-c".equals(service)
                || NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(service)) {
            return true;
        }
        return IdsUtunConnectionName.isClassCIdentifier(service)
                && !IdsUtunConnectionName.isClassDIdentifier(service);
    }

    /**
     * The NanoRegistry Urgent-D connector that carries Check/PairingMode.
     * Default/Sync {@code ...-D} joins must not count as this handshake.
     */
    private static boolean isUrgentClassDLane(String service) {
        if (service == null) {
            return false;
        }
        if (CLASS_D_CONNECTOR.equals(service)
                || "nano-class-d".equals(service)) {
            return true;
        }
        return IdsUtunConnectionName.lastComponent(service).equals(
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_URGENT,
                        IdsUtunConnectionName.PROTECTION_CLASS_D));
    }

    private static boolean isUrgentClassCLane(String service) {
        if (service == null) {
            return false;
        }
        if (CLASS_C_CONNECTOR.equals(service)
                || "nano-class-c".equals(service)) {
            return true;
        }
        return IdsUtunConnectionName.lastComponent(service).equals(
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_URGENT,
                        IdsUtunConnectionName.PROTECTION_CLASS_C));
    }

    private static boolean isDirectAlloyLane(String service) {
        if (service == null) {
            return false;
        }
        return NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(service)
                || NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(service)
                || "nano-class-d".equals(service)
                || "nano-class-c".equals(service);
    }
}
