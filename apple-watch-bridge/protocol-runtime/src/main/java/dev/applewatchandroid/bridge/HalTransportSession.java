package dev.applewatchandroid.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/** One controller lease: shared transport handshake, application protocols and cleanup. */
final class HalTransportSession {
    private final BluetoothController hci;
    private final AtomicBoolean stopRequested;
    private final BlockingQueue<char[]> pinInputs;
    private final BlockingQueue<Boolean> bondStoreResults;
    private final BlockingQueue<Boolean>
            pairingSessionStoreResults;
    private final BlockingQueue<byte[]> localIdentityRecords;
    private final BlockingQueue<byte[]> localIdsPublicRecords;
    private byte[] localIdsPublicBundle;
    private final BlockingQueue<byte[]> restoredPairingSessionRecords;
    private final BlockingQueue<OutboundAppMessage> outboundAppMessages;
    private final BlockingQueue<Packet> packets = new LinkedBlockingQueue<>(4096);
    private final java.util.ArrayDeque<Packet> deferredControllerPackets = new java.util.ArrayDeque<>();
    private final BlockingQueue<byte[]> deferredSmpPdus =
            new LinkedBlockingQueue<>(16);
    private volatile CountDownLatch initializationLatch = new CountDownLatch(1);
    private final AtomicInteger initializationStatus =
            new AtomicInteger(-1);
    private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    private final HciAclCreditWindow aclCredits = new HciAclCreditWindow();
    private final HciCodec.AclReassembler aclReassembler =
            new HciCodec.AclReassembler();

    private final BluetoothController.Callbacks callbacks =
            new BluetoothController.Callbacks() {
                @Override
                public void aclDataReceived(byte[] data) {
                    receive(PacketKind.ACL, data);
                }

                @Override
                public void hciEventReceived(byte[] event) {
                    receive(PacketKind.EVENT, event);
                }

                @Override
                public void initializationComplete(int status) {
                    initializationStatus.set(status);
                    initializationLatch.countDown();
                }

                @Override
                public void isoDataReceived(byte[] data) {
                    // ISO is outside this transport.
                }

                @Override
                public void scoDataReceived(byte[] data) {
                    // SCO is outside this transport.
                }
            };

    private volatile boolean initializedByUs;
    private volatile boolean scanEnabled;
    private volatile boolean createPending;
    private volatile boolean attSetupTriggerSent;
    private volatile int attSetupStep;
    private volatile BleSecureConnectionsCrypto.LocalOobMaterial activeLocalOob;
    private volatile byte[] lastMatchedReportData;
    private volatile int connectionHandle = -1;
    private int normalBtClVersion = HciCodec.BT_CL_CURRENT_VERSION;
    private long normalBtClFeatures;
    private volatile int maximumAclDataLength = 0xFFFF;
    private long lastAclCreditStatusMs;
    private BluetoothLocalIdentity localIdentity;
    private int peerConnectionAddressType = -1;
    private byte[] peerConnectionAddress;
    private byte[] activeLongTermKey;
    private PairingSessionRecord activePairingSessionRecord;
    private NormalLinkIdsSessionBridge activeIdsBridge;
    private IdsBootstrapState activeIdsBootstrap;
    private AppleWatchInitialSetupIdsAdapter activeInitialSetup;
    private InitialWifiSyncWorker initialWifiSync = new InitialWifiSyncWorker(RootWifiNetworkReader::currentArchive);
    private String initialWifiMessageUuid;
    private boolean initialWifiReceiptLogged, initialWifiUnavailableLogged;
    private final List<IdsModernSessionCoordinator.SessionEvent> earlyInitialEvents =
            new ArrayList<>();
    private WatchSetupMetadataCodec.Identifier
            activeWatchSetupIdentifier;
    private WatchSetupMetadataCodec.ExtendedMetadata
            activeWatchSetupMetadata;
    private long modernRegistrationDeadlineMs = -1;
    private volatile boolean modernIdsReady;
    private volatile boolean reconnectRequested;
    private volatile boolean initialPropertiesReceived;
    private volatile boolean readyToCommitIsPaired;
    /** Throttles the POST-COMMIT DEFERRED activation-gate log spam. */
    private long postCommitDeferredLogAtMs = -1L;
    private volatile boolean cleaned;
    private final boolean commitIsPairedAuthorized;
    private final String operationalPairingId;
    private boolean operationalSnapshotPublished;
    private boolean healthPeerIdentityPublished;
    private UUID clockFaceObservationEpoch;
    private PigmentPreferenceMirror pigmentMirror;
    private MonogramPreferenceMirror monogramMirror;
    private UUID monogramMirrorPair;
    private UUID pigmentMirrorPair;
    private ClockFaceDeltaSession clockFaceDelta;
    private BridgeCommandCodec.Request clockFaceDeltaRequest;
    private boolean clockFaceDeltaReadRequested;
    private final BulletinTransportSession bulletinTransport = new BulletinTransportSession();
    private AppleWatchPostCommitCoordinator activePostCommitCoordinator;
    private AppleWatchPostCommitIdsAdapter activePostCommitAdapter;
    private final PostCommitDeliveryQueue pendingPostCommitSends = new PostCommitDeliveryQueue();
    private MobileActivationHttpProxy activeActivationProxy;
    private ActivationProxyWorker activationProxyWorker;
    /** Elapsed-time mark set when the Watch was given the activation permit. */
    private long activationPermitSentAtMs = -1L;
    /** When the permit was queued, even if the ERTM window held the frame. */
    private long activationPermitQueuedAtMs = -1L;
    /** L2CAP frames actually handed to the controller for a permit or its drain. */
    private int activationPermitL2capFrames;
    /** Permit was queued but still waiting for socket-pair Handshake. */
    private boolean activationPermitAwaitingFlush;
    /** Bounded CanBeginActivation re-assertions when the Watch stays silent. */
    private int activationPermitResendCount;
    /** Set once the Watch itself drove activation over PBBridge. */
    private boolean watchDrivenActivationObserved;
    /**
     * Live 0.2.190: after the timesync answer a committed-but-unactivated
     * Watch drives ProxyActivation WITHOUT a this-session Class-C snapshot;
     * that authenticated request is itself the Albert evidence, so it
     * authorizes post-commit even while {@link #initialPropertiesReceived}
     * is still false.
     */
    private boolean watchProxyActivationObserved;
    /** Stashed Watch ProxyActivation event until the post-commit adapter exists. */
    private IdsModernSessionCoordinator.SessionEvent pendingWatchProxyActivation;
    private byte[] pendingActivationPayload;
    private MobileActivationHttpProxy.ProxyResponse pendingActivationResponse;
    /** Rate limit for the parked-frame diagnostic, which runs per inbound frame. */
    private long lastPendingStallLogMs = -1L;
    /** Timestamp of the last ERTM keepalive RR frame sent with Poll=true. */
    private long lastKeepalivePollMs = 0L;
    private long lastTcpRetransmitMs;
    /** Peer addresses that answered BT_CL but published no terminusPairing. */
    private final java.util.Set<String> rejectedSetupCandidates =
            new java.util.HashSet<>();
    /** Set once the IDS control Hello and both NanoRegistry lanes are up. */
    private boolean idsBootstrapBarrierPassed;
    /** Set when the peer answered the IDS control Hello for real. */
    private boolean idsControlReadyObserved;
    private boolean neighborAdvertised;
    private boolean pairedKeyProbesStarted;
    /**
     * ACL bounces after LDM+NA with no Watch {@code ids-control-channel}.
     * Not cleared on IDS re-attach so a single START_BRIDGE cannot loop.
     */
    private int idsControlSilenceBounces;
    /**
     * Set once this session ran a fresh IKE/PIN pairing. A session that
     * never paired freshly re-established its encrypted link purely from
     * the persisted pairing record after a local restart — that is the
     * controlled-restart reconnect evidence the operational-health
     * barrier asks for.
     */
    private boolean freshPairingPerformed;
    /** One phone MiniStore republish per resumed paired link. */
    private boolean resumedPhoneRegistrySnapshotSent;
    /**
     * Two-way preference domains waiting for ERTM room. Sending all of
     * them in one burst filled the 32-deep window and the Watch SREJ'd
     * across the sequence wrap (live 0.2.222, TxSeq 29 never acked).
     */
    private final ArrayDeque<byte[]> pendingTwoWayPreferences = new ArrayDeque<>();
    private boolean preferenceMirrorDeferredLogged;
    private int ertmStallTxSeq = -1;
    private int ertmStallSends;
    private long ertmStallNextMs;
    /** Set once Phase.COMPLETE has been announced; avoids repeat logging. */
    private boolean postCommitCompleteLogged;
    /**
     * Watch sent NWSC {@code REJECTED_BY_POLICY} (0x40) on control.
     * iOS cancels and never opens a new SYN; live 0.2.122 reconnected
     * on the following {@code 0x13} and looped POLICY.
     */
    private boolean idsControlRejectedByPolicy;
    /**
     * Urgent D/C Handshake made bootstrap READY, but Check is held until
     * the ERTM Tx window <em>and</em> IDS TCP una drain. Live PIN 0.2.126
     * sent Check after a false ERTM quiet while TCP una was 9/117 bytes.
     */
    private boolean pendingNanoSetup;
    private NanoRegistryReachabilityProbe nanoRegistryProbe = new NanoRegistryReachabilityProbe();
    private final BluetoothLinkMaintenance.AttServer normalAttServer =
            new BluetoothLinkMaintenance.AttServer(this::handleAttRequest);
    private final BluetoothFixedChannelRouter fixedChannelRouter = new BluetoothFixedChannelRouter();
    private IdsDeviceInfoExchange idsDeviceInfoExchange;
    private final NanoSystemSettingsDiagnostics nssDiagnostics = new NanoSystemSettingsDiagnostics();
    private final FindMyLocalDeviceSession findMySession = new FindMyLocalDeviceSession();
    private final FindMyPhoneSession findMyPhone = new FindMyPhoneSession();
    private final ArrayDeque<FindMyPhoneIpcCodec.Result> pendingPhoneReplies = new ArrayDeque<>();
    private final SysdiagnoseArchiveInventory sysdiagnoseInventory = new SysdiagnoseArchiveInventory();
    private final SysdiagnoseCollection sysdiagnoseCollection = new SysdiagnoseCollection();
    private final NativeWatchReboot watchReboot = new NativeWatchReboot();
    private long pendingNanoSetupAtMs = -1L;
    /** Elapsed-ms mark of the last repeating WAIT diagnostic. */
    private long lastNanoSetupWaitLogMs = -1L;

    HalTransportSession(
            BluetoothController hci,
            AtomicBoolean stopRequested,
            BlockingQueue<char[]> pinInputs,
            BlockingQueue<Boolean> bondStoreResults,
            BlockingQueue<Boolean>
                    pairingSessionStoreResults,
            BlockingQueue<byte[]> localIdentityRecords,
            BlockingQueue<byte[]> localIdsPublicRecords,
            BlockingQueue<byte[]> restoredPairingSessionRecords,
            BlockingQueue<OutboundAppMessage> outboundAppMessages,
            boolean commitIsPairedAuthorized,
            String operationalPairingId) {
        this.hci = hci;
        this.stopRequested = stopRequested;
        this.pinInputs = pinInputs;
        this.bondStoreResults =
                bondStoreResults;
        this.pairingSessionStoreResults =
                pairingSessionStoreResults;
        this.localIdentityRecords =
                localIdentityRecords;
        this.localIdsPublicRecords = localIdsPublicRecords;
        this.restoredPairingSessionRecords =
                restoredPairingSessionRecords;
        this.outboundAppMessages =
                outboundAppMessages;
        this.commitIsPairedAuthorized =
                commitIsPairedAuthorized;
        this.operationalPairingId = operationalPairingId;
    }

    private OpticalPairingCode opticalPairing;
    void configureOpticalPairing(byte[] payload) {
        if (initializedByUs || opticalPairing != null || operationalPairingId != null) {
            throw new IllegalStateException("Optical pairing configuration is unavailable");
        }
        opticalPairing = OpticalPairingCode.parse(payload);
    }

    void initialize() throws Exception {
        checkStop();
        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            initializationLatch = new CountDownLatch(1);
            initializationStatus.set(-1);
            log("HAL INITIALIZE: registering VINTF-stable callbacks (attempt " + attempt + "/" + maxAttempts + ").");
            hci.initialize(callbacks);
            if (!initializationLatch.await(
                    HAL_INIT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS)) {
                throw new HostException("Bluetooth HAL initialization timed out");
            }
            int status = initializationStatus.get();
            if (status == BluetoothController.SUCCESS) {
                initializedByUs = true;
                log("HAL INITIALIZED: exclusive controller ownership confirmed.");
                return;
            }
            if (status == BluetoothController.ALREADY_INITIALIZED && attempt < maxAttempts) {
                log("HAL INITIALIZE: stock Bluetooth still releasing HAL (status=1); resetting and retrying in 400ms...");
                try {
                    hci.close();
                } catch (Exception ignored) {
                }
                Thread.sleep(400);
                continue;
            }
            throw new HostException(
                    "Bluetooth HAL initialization status=" + status
                            + (status == BluetoothController.ALREADY_INITIALIZED
                            ? " (stock Bluetooth still owns HAL)"
                            : ""));
        }
    }

    void runTransportHandshake(boolean recoveryOnly)
            throws Exception {
        sendCommandComplete(HciCodec.OPCODE_RESET, new byte[0]);
        byte[] publicAddress = null;
        byte[] randomAddress = null;
        byte[] identityRecord = null;
        try {
            publicAddress = HciCodec.parseBdAddr(
                    sendCommandComplete(
                            HciCodec.OPCODE_READ_BD_ADDR,
                            new byte[0]));
            log("HCI READ PUBLIC ADDRESS PASS: bytes logged=false; "
                    + "the stock identity is not used or modified.");

            identityRecord = localIdentityRecords.poll(
                    LOCAL_IDENTITY_INPUT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
            if (identityRecord == null) {
                throw new HostException(
                        "Stable local Bluetooth identity was not "
                                + "provided by the application");
            }
            localIdentity =
                    BluetoothLocalIdentity.parse(identityRecord);
            randomAddress = localIdentity.address();
            sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_RANDOM_ADDRESS,
                    HciCodec.buildLeSetRandomAddressParameters(
                            randomAddress));
            log("HCI LE SET RANDOM ADDRESS PASS: stable "
                    + "Keystore-derived static-random identity "
                    + "configured; address/IRK logged=false.");
        } finally {
            wipe(publicAddress);
            wipe(randomAddress);
            wipe(identityRecord);
        }
        HciCodec.CommandComplete classicBufferSize = sendCommandComplete(
                HciCodec.OPCODE_READ_BUFFER_SIZE,
                new byte[0]);
        int classicAclDataLength =
                HciCodec.parseAclDataPacketLength(classicBufferSize);
        HciCodec.CommandComplete leBufferSize = sendCommandComplete(
                HciCodec.OPCODE_LE_READ_BUFFER_SIZE,
                new byte[0]);
        int leAclDataLength =
                HciCodec.parseLeAclDataPacketLength(leBufferSize);
        int classicAclPackets = HciCodec.parseAclDataPacketCount(classicBufferSize);
        int leAclPackets = HciCodec.parseLeAclDataPacketCount(leBufferSize);
        maximumAclDataLength = leAclPackets == 0
                ? classicAclDataLength
                : leAclDataLength;
        aclCredits.configure(leAclPackets == 0 ? classicAclPackets : leAclPackets);
        log("HCI ACL BUFFER: classic="
                + classicAclDataLength
                + " LE="
                + leAclDataLength
                + (leAclPackets == 0 ? " (shared classic pool)" : "")
                + " selected="
                + maximumAclDataLength
                + " bytes per host packet; classicPackets=" + classicAclPackets
                + " lePackets=" + leAclPackets + "; " + aclCredits.summary());
        sendCommandComplete(
                HciCodec.OPCODE_SET_EVENT_MASK,
                HciCodec.classicEventMask());
        sendCommandComplete(
                HciCodec.OPCODE_LE_SET_EVENT_MASK,
                HciCodec.leEventMask());
        sendCommandComplete(
                HciCodec.OPCODE_LE_SET_SCAN_PARAMETERS,
                HciCodec.activeScanParameters(localIdentity.addressType()));

        byte[] restoredSession = restoredPairingSessionRecords.poll(
                operationalPairingId != null ? 5000 : 300, TimeUnit.MILLISECONDS);
        if (restoredSession != null) {
            try {
                PairingSessionRecord restored =
                        PairingSessionRecord.parse(restoredSession);
                if (operationalPairingId != null) {
                    try {
                        OperationalSessionPolicy.requireMatchingActivatedPair(restored, operationalPairingId);
                    } catch (RuntimeException invalid) {
                        restored.destroy();
                        throw invalid;
                    }
                    log("OPERATIONAL PAIR VERIFIED: owner-confirmed screen; native durable evidence unchanged; setup replay disabled.");
                }
                if (restored.bluetoothBond() != null
                        || restored.state().wireValue()
                        >= PairingSessionRecord.DurableState
                        .SMP_BONDED_RAW.wireValue()) {
                    activePairingSessionRecord = restored;
                    if (restored.bluetoothBond() != null) {
                        try {
                            peerConnectionAddress =
                                    BluetoothBondSecretRecord
                                            .extractPeerConnectionAddress(
                                                    restored.bluetoothBond());
                            peerConnectionAddressType =
                                    BluetoothBondSecretRecord
                                            .extractPeerConnectionAddressType(
                                                    restored.bluetoothBond());
                        } catch (Exception ignored) {
                        }
                    }
                    java.io.File syncRedriveMarker =
                            BridgePaths.temporary().resolve("aw-redrive-initial-sync").toFile();
                    if (operationalPairingId == null && syncRedriveMarker.exists()
                            && activePairingSessionRecord.state().wireValue()
                            >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()) {
                        persistAndReplaceActivePairingSession(
                                activePairingSessionRecord.redriveInitialSyncCheckpoint());
                        if (!syncRedriveMarker.delete()) {
                            throw new HostException("Initial-sync redrive marker could not be consumed");
                        }
                        log("PAIRING RECORD SYNC REDRIVE: discarded unverified Buddy/Clock checkpoints; activation, pair, bond and keys retained. Awaiting fresh peer sync and visible Clock evidence.");
                    }
                    java.io.File redriveMarker = BridgePaths.temporary().resolve("aw-redrive-activation").toFile();
                    if (operationalPairingId == null && redriveMarker.exists()
                            && activePairingSessionRecord.state().wireValue()
                            >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()) {
                        try {
                            persistAndReplaceActivePairingSession(
                                    activePairingSessionRecord.redriveActivationCheckpoint());
                            log("PAIRING RECORD REDRIVE: owner marker found; durable checkpoint downgraded to IS_PAIRED_COMMITTED (bond, keys and commit retained) so Albert activation runs again.");
                        } finally {
                            //noinspection ResultOfMethodCallIgnored
                            redriveMarker.delete();
                        }
                    }
                    if (activePairingSessionRecord.state().wireValue()
                            >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()
                            && !activePairingSessionRecord.hasObservedSetupEvidence()) {
                        persistAndReplaceActivePairingSession(
                                activePairingSessionRecord.revalidateLegacySetupEvidence());
                        log("PAIRING RECORD REVALIDATION: retained bond and IsPaired commit; legacy synthetic setup checkpoints need fresh Watch evidence.");
                    }
                    boolean reconnected = false;
                    int attemptsMade = 0;
                    String reconnectFailure = "no connection established";
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        attemptsMade = attempt;
                        try {
                            reconnected = reconnectPairedWatch();
                            if (reconnected) {
                                return;
                            }
                            if (activePairingSessionRecord == null) {
                                break;
                            }
                        } catch (StopRequested stopped) {
                            throw stopped;
                        } catch (Exception e) {
                            log("[WatchHal] Reconnect attempt " + attempt + " failed: " + safeMessage(e));
                            String failed = safeMessage(e);
                            reconnectFailure = failed;
                            if (failed.contains("not retrying SYN")
                                    && attempt < 3) {
                                // The Watch still holds stale flows from
                                // the previous session and rejects our
                                // fresh SYN by policy. We now answer its
                                // retransmitted segments with RST, so a
                                // delayed retry lands on a clean slate.
                                log("[WatchHal] Reconnect attempt " + attempt
                                        + ": policy rejection likely caused by stale Watch flows; "
                                        + "waiting 5 s for RST-driven teardown before retrying.");
                                try {
                                    Thread.sleep(5000);
                                } catch (InterruptedException ignored) {
                                }
                                continue;
                            }
                            if (failed.contains("not retrying SYN")
                                    || failed.contains("rejected IDS control by policy")) {
                                break;
                            }
                            if (activePairingSessionRecord == null) {
                                break;
                            }
                        } finally {
                            // Live 0.2.220: STOP during a healthy hold
                            // left reconnected==false, so this block
                            // called checkStop() inside Disconnect and
                            // then cleared connectionHandle. Cleanup
                            // then logged handle=none and never sent
                            // HCI Disconnect, so the Watch kept the ACL.
                            if (ReconnectStopPolicy.tearDownIncompleteReconnect(
                                    reconnected,
                                    stopRequested.get())) {
                                if (connectionHandle != -1) {
                                    try {
                                        sendCommandStatus(
                                                HciCodec.OPCODE_DISCONNECT,
                                                HciCodec.buildDisconnectParameters(connectionHandle));
                                    } catch (Exception ignored) {
                                    }
                                    connectionHandle = -1;
                                }
                                if (createPending) {
                                    try {
                                        sendCommandComplete(
                                                HciCodec.OPCODE_LE_CREATE_CONNECTION_CANCEL,
                                                new byte[0]);
                                    } catch (Exception ignored) {
                                    }
                                    createPending = false;
                                }
                                if (scanEnabled) {
                                    try {
                                        sendCommandComplete(
                                                HciCodec.OPCODE_LE_SET_SCAN_ENABLE,
                                                HciCodec.scanEnableParameters(false));
                                    } catch (Exception ignored) {
                                    }
                                    scanEnabled = false;
                                }
                            }
                        }
                        if (attempt < 3) {
                            try {
                                Thread.sleep(600);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                    if (!reconnected && activePairingSessionRecord != null) {
                        log("[WatchHal] Reconnect failed after " + attemptsMade
                                + " attempt(s); preserving pairing session record.");
                        throw new HostException(
                                "Failed to reconnect to bonded Apple Watch after "
                                        + attemptsMade + " attempt(s): " + reconnectFailure);
                    }
                } else {
                    restored.destroy();
                }
            } finally {
                wipe(restoredSession);
            }
        }

        if (operationalPairingId != null) {
            throw new HostException("Operational reconnect did not restore the confirmed pair; fresh pairing disabled");
        }
        // Treat a successfully submitted enable as possibly effective even
        // if its Command Complete is lost; cleanup must then send Disable.
        scanEnabled = true;
        sendCommandComplete(
                HciCodec.OPCODE_LE_SET_SCAN_ENABLE,
                HciCodec.scanEnableParameters(true));
        log("LE SCAN ACTIVE: FE25/subtype 06, "
                + "target=Watch7,5/NetworkRelay strategy 4, "
                + "runtime compatibility=25..26, "
                + "RSSI >= -75 dBm.");

        // Only complete Watch setup advertisements can supply the PIN
        // binding. BT_CL then confirms the selected peer's service support.
        HciCodec.AdvertisingReport target = null;
        HciCodec.VersionInfo peerVersion = null;
        List<Integer> commonServices = null;
        for (int candidateAttempt = 1;
                candidateAttempt <= MAX_SETUP_CANDIDATES
                        && commonServices == null;
                candidateAttempt++) {
        if (!scanEnabled) {
            scanEnabled = true;
            sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_SCAN_ENABLE,
                    HciCodec.scanEnableParameters(true));
        }
        target = awaitTarget();
        if (!target.isExpectedTarget()) {
            throw new HostException("Selected Watch lacks verified setup metadata");
        }
        {
            activeWatchSetupIdentifier =
                    target.setup.decodedIdentifier;
            activeWatchSetupMetadata =
                    target.setup.decodedMetadata;
            log("WATCH TARGET LOCKED: address logged=false; type="
                    + target.addressType
                    + " RSSI="
                    + target.rssi
                    + " advertisedCode logged=false;"
                    + " product="
                    + target.setup.decodedMetadata
                    .productType()
                    + " watchOS="
                    + target.setup.decodedMetadata
                    .systemVersionString()
                    + " pairingVersion="
                    + target.setup.decodedMetadata
                    .pairingVersion
                    + " setupBytes="
                    + target.setup.watchSetupData.length
                    + " rawLogged=false.");
        }

        sendCommandComplete(
                HciCodec.OPCODE_LE_SET_SCAN_ENABLE,
                HciCodec.scanEnableParameters(false));
        scanEnabled = false;

        createPending = true;
        sendCommandStatus(
                HciCodec.OPCODE_LE_CREATE_CONNECTION,
                HciCodec.buildLeCreateConnectionParameters(
                        target,
                        localIdentity.addressType()));
        HciCodec.LeConnectionComplete connection =
                awaitConnectionComplete(target);
        createPending = false;
        connectionHandle = connection.connectionHandle;
        peerConnectionAddressType =
                connection.peerAddressType;
        peerConnectionAddress =
                connection.peerAddress.clone();
        log(String.format(
                Locale.US,
                "LE ACL CONNECTED: handle=0x%04X; peer address "
                        + "logged=false.",
                connectionHandle));

        if (recoveryOnly) {
            sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.pairingFailed(
                            BluetoothSmpCodec
                                    .FAILURE_UNSPECIFIED_REASON),
                    "SMP STALE-TRANSACTION RECOVERY");
            BridgeClock.sleep(500);
            log("SMP RECOVERY SENT: standard Pairing Failed "
                    + "reason=Unspecified; no secret-bearing "
                    + "SMP or IKE payload was sent.");
            return;
        }

        byte[] versionPduTx = HciCodec.buildBtClVersionPdu();
        sendL2cap(
                HciCodec.BT_CL_SIGNALING_CID,
                versionPduTx,
                "BT_CL VERSION");
        HciCodec.BtClPdu versionPdu =
                awaitBtClOpcode(
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.BT_CL_VERSION_INFO,
                        HciCodec.BT_CL_CURRENT_VERSION,
                        0L,
                        versionPduTx);
        peerVersion =
                HciCodec.parseVersionInfo(versionPdu);
        normalBtClVersion = peerVersion.version;
        normalBtClFeatures = peerVersion.features;
        if (peerVersion.version <= 0 || peerVersion.version > 0x40) {
            throw new HostException(
                    "Implausible Watch BT_CL version " + peerVersion.version);
        }
        log(String.format(
                Locale.US,
                "BT_CL VERSION RX: version=%d features=0x%08X.",
                peerVersion.version,
                peerVersion.features));

        // watchOS keeps the first remote service record per ID and name and
        // ignores a repeat without any reply, so a Watch that already saw
        // this phone answers the advertisement below with silence instead of
        // COMMON_SERVICES. Withdrawing the record first makes the repeat
        // count as new. Best effort: a Watch holding no record simply has
        // nothing to remove, and the confirmation is not required to
        // proceed.
        byte[] withdrawPairingService =
                HciCodec.buildServiceRemovedPdu(
                        peerVersion.version,
                        peerVersion.features,
                        HciCodec.TERMINUS_PAIRING_SERVICE_ID);
        try {
            sendL2cap(
                    HciCodec.BT_CL_SIGNALING_CID,
                    withdrawPairingService,
                    "BT_CL SERVICE_REMOVED (terminusPairing 0x0001, "
                            + "withdrawing any stale record)");
        } finally {
            wipe(withdrawPairingService);
        }

        // The Watch only allocates a remote service record from
        // REMOTE_SERVICES and answers with COMMON_SERVICES. CREATE_CHANNEL
        // for a service ID that was never echoed back is rejected with
        // ACCEPT_CHANNEL status=5. terminusLink stays out of this list:
        // watchOS stores the first record per ID/name, so it is advertised
        // exactly once by BtClNormalLinkHandoff after link encryption.
        byte[] remoteServicesPdu =
                HciCodec.buildRemoteServicesPdu(
                        peerVersion.version,
                        peerVersion.features,
                        List.of(
                                HciCodec.terminusPairingService(),
                                HciCodec.terminusLinkService()));
        List<Integer> offered;
        try {
            sendL2cap(
                    HciCodec.BT_CL_SIGNALING_CID,
                    remoteServicesPdu,
                    "BT_CL REMOTE_SERVICES (terminusPairing 0x0001 + "
                            + "terminusLink 0x0002, ERTM=1)");
            try {
                offered =
                        HciCodec.parseCommonServices(
                                awaitBtClOpcode(
                                        HciCodec.BT_CL_SIGNALING_CID,
                                        HciCodec.BT_CL_COMMON_SERVICES,
                                        peerVersion.version,
                                        peerVersion.features,
                                        remoteServicesPdu));
            } catch (HostException silentPeer) {
                if (candidateAttempt >= MAX_SETUP_CANDIDATES) {
                    throw silentPeer;
                }
                rejectSetupCandidate(
                        target,
                        "no BT_CL COMMON_SERVICES");
                continue;
            }
        } finally {
            wipe(remoteServicesPdu);
        }
        log("BT_CL COMMON_SERVICES RX: ids=" + offered);
        if (!offered.contains(
                HciCodec.TERMINUS_PAIRING_SERVICE_ID)) {
            if (candidateAttempt >= MAX_SETUP_CANDIDATES) {
                throw new HostException(
                        "Watch did not accept the terminusPairing service; "
                                + "common=" + offered);
            }
            rejectSetupCandidate(
                    target,
                    "COMMON_SERVICES without terminusPairing " + offered);
            continue;
        }
        commonServices = offered;
        }
        if (commonServices == null) {
            throw new HostException(
                    "No nearby Apple device published the terminusPairing "
                            + "service within "
                            + MAX_SETUP_CANDIDATES
                            + " candidates");
        }

        // NetworkRelay uses BT_CL. Serve peer ATT transactions centrally;
        // do not launch the unrelated legacy GATT client setup sequence.

        try {
            sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_ADVERTISING_PARAMETERS,
                    HciCodec.advertisingParameters());
            byte[] appleAdvPayload = new byte[]{
                    (byte) 0xFF, 0x4C, 0x00, 0x10, 0x06, 0x01, 0x19, 0x00, 0x00, 0x00, 0x00
            };
            sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_ADVERTISING_DATA,
                    HciCodec.advertisingData(appleAdvPayload, "iPhone"));
            sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_ADVERTISE_ENABLE,
                    HciCodec.advertiseEnableParameters(true));
            log("BLE ADVERTISING ENABLED: Apple Setup Proximity Beacon active");
        } catch (Exception e) {
            log("BLE ADVERTISING ENABLE NOTICE: " + e.getMessage());
        }

        if (target.setup == null || target.setup.watchSetupData.length != 12) {
            throw new HostException("Watch has no complete setup advertisement for PIN binding");
        }
        byte[] setupData = target.setup.watchSetupData;

        sendL2cap(
                HciCodec.BT_CL_SIGNALING_CID,
                HciCodec.buildCreateChannelPdu(
                        peerVersion.version,
                        peerVersion.features,
                        HciCodec.TERMINUS_LOCAL_CID,
                        HciCodec.TERMINUS_PAIRING_SERVICE_ID),
                "BT_CL CREATE_CHANNEL localCID=0x0040 service=0x0001 (terminusPairing)");
        HciCodec.BtClPdu createRsp =
                awaitBtClOpcode(
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.BT_CL_ACCEPT_CHANNEL,
                        peerVersion.version,
                        peerVersion.features);
        HciCodec.AcceptChannel accept =
                HciCodec.parseAcceptChannel(createRsp);
        log(String.format(
                Locale.US,
                "BT_CL PAIRING CHANNEL OPENED: status=%d localCID=0x%04X remoteCID=0x%04X service=0x%04X.",
                accept.status,
                HciCodec.TERMINUS_LOCAL_CID,
                accept.responderLocalCid,
                accept.serviceId));

        if (accept.status == 0) {
            freshPairingPerformed = true;
            log("RUNNING IKE CONTROL SA INIT / PAIRING...");
            probeControlSaInit(accept.responderLocalCid, setupData);
        } else {
            log("MANUAL_PAIRING_TRIGGER: Watch requires manual pairing (status=" + accept.status + "). Retrying CREATE_CHANNEL periodically while user taps (i) on Apple Watch screen...");
            long manualDeadline = BridgeClock.elapsedRealtime() + 600000;
            long nextRetryTime = BridgeClock.elapsedRealtime() + 2000;
            while (BridgeClock.elapsedRealtime() < manualDeadline) {
                long now = BridgeClock.elapsedRealtime();
                if (now >= nextRetryTime) {
                    log("RETRYING BT_CL CREATE_CHANNEL (terminusPairing)...");
                    sendL2cap(
                            HciCodec.BT_CL_SIGNALING_CID,
                            HciCodec.buildCreateChannelPdu(
                                    peerVersion.version,
                                    HciCodec.TERMINUS_LOCAL_CID,
                                    0x0001),
                            "BT_CL CREATE_CHANNEL RETRY");
                    nextRetryTime = now + 2500;
                }
                Packet packet;
                try {
                    packet = nextPacket(Math.min(nextRetryTime, manualDeadline));
                } catch (TimeoutException elapsed) {
                    continue;
                }
                if (packet.kind == PacketKind.ACL) {
                    HciCodec.L2capPdu l2cap = aclReassembler.accept(packet.bytes);
                    if (l2cap != null && l2cap.connectionHandle == connectionHandle) {
                        try {
                            if (l2cap.destinationCid == 0x0004) {
                                byte[] attReply = handleAttRequest(l2cap.payload);
                                if (attReply != null) {
                                    sendL2cap(0x0004, attReply, "ATT RESPONSE");
                                }
                            } else if (l2cap.destinationCid == HciCodec.BT_CL_SIGNALING_CID) {
                                HciCodec.BtClPdu pdu = HciCodec.parseBtCl(peerVersion.version, peerVersion.features, l2cap.payload);
                                log("BT_CL RX during manual wait: opcode=0x" + Integer.toHexString(pdu.opcode));
                                if (pdu.opcode == HciCodec.BT_CL_ACCEPT_CHANNEL) {
                                    HciCodec.AcceptChannel retryAccept = HciCodec.parseAcceptChannel(pdu);
                                    log("BT_CL RETRY ACCEPT: status=" + retryAccept.status + " remoteCID=0x" + Integer.toHexString(retryAccept.responderLocalCid));
                                    if (retryAccept.status == 0) {
                                        probeControlSaInit(retryAccept.responderLocalCid, setupData);
                                        return;
                                    }
                                } else if (pdu.opcode == HciCodec.BT_CL_CREATE_CHANNEL) {
                                    HciCodec.CreateChannel cc = HciCodec.parseCreateChannel(pdu);
                                    log("WATCH INITIATED CREATE_CHANNEL: localCID=0x" + Integer.toHexString(cc.requesterLocalCid) + " service=0x" + Integer.toHexString(cc.serviceId));
                                    sendL2cap(
                                            HciCodec.BT_CL_SIGNALING_CID,
                                            HciCodec.buildAcceptChannelPdu(
                                                    peerVersion.version,
                                                    0,
                                                    cc.serviceId,
                                                    HciCodec.TERMINUS_LOCAL_CID),
                                            "BT_CL ACCEPT_CHANNEL (status=0)");
                                    probeControlSaInit(cc.requesterLocalCid, setupData);
                                    return;
                                }
                            } else if (l2cap.destinationCid == BluetoothSmpCodec.FIXED_CID) {
                                log("SMP RX during manual wait: bytes=" + l2cap.payload.length);
                            }
                        } finally {
                            wipe(l2cap.payload);
                        }
                    }
                }
            }
            throw new HostException(
                    "Manual pairing wait timed out after 10 minutes; PIN was not initiated by Watch");
        }
    }

    private void probeControlSaInit(
            int responderLocalCid,
            byte[] watchSetupData)
            throws Exception {
        IkeV2Codec.InitiatorState initiator =
                IkeV2Codec.createControlSaInit(new SecureRandom());
        log("CONTROL IKE CONFIG: auth=NULL; ENCR=[AES-GCM-16/256,"
                + "ChaCha20-Poly1305]; PRF=HMAC-SHA2-512; "
                + "DH=[X448,X25519]; AdditionalKE1=ML-KEM-1024.");
        log("IKE_SA_INIT TX MATERIAL: spi="
                + HciCodec.toHex(initiator.initiatorSpi)
                + " nonce="
                + HciCodec.toHex(initiator.nonce)
                + " x448Public="
                + HciCodec.toHex(initiator.x448PublicKey)
                + " privateKeyLogged=false.");
        log("IKE_SA_INIT IKE TX: bytes="
                + initiator.ikePacket.length
                + " raw="
                + HciCodec.toHex(initiator.ikePacket));
        log("IKE_SA_INIT uIKE TX: bytes="
                + initiator.uikeFrame.length
                + " raw="
                + HciCodec.toHex(initiator.uikeFrame));
        byte[] ertmRequest =
                L2capErtmCodec.encodeInformationFrame(
                        responderLocalCid,
                        0,
                        0,
                        initiator.uikeFrame,
                        TERMINUS_FCS_ENABLED);
        log("TERMINUS ERTM TX: serviceFlags=0x01 mode=3 FCS=none "
                + "TxSeq=0 ReqSeq=0 SAR=unsegmented "
                + "bytes="
                + ertmRequest.length
                + " raw="
                + HciCodec.toHex(ertmRequest));
        sendL2cap(
                responderLocalCid,
                ertmRequest,
                "TERMINUS ERTM CONTROL IKE_SA_INIT");
        log("IKE_SA_INIT SAFETY BOUNDARY: exactly one "
                + "unauthenticated IKE_SA_INIT SDU was sent; ERTM "
                + "acknowledgements contain no application bytes. "
                + "PIN/private-notify/SMP/bond/activation/"
                + "setup-commit bytes sent=0.");

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        long deadline =
                BridgeClock.elapsedRealtime() + IKE_SA_INIT_TIMEOUT_MS;
        int l2capFrames = 0;
        int ertmInformationFrames = 0;
        int expectedWatchTxSequence = 0;
        boolean requestAcknowledged = false;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet;
            try {
                packet = nextPacket(deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == connectionHandle) {
                    connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected before IKE_SA_INIT response; "
                                    + "status=0x%02X reason=0x%02X",
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log("IKE_SA_INIT ACL DROP: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (l2cap == null
                    || l2cap.connectionHandle != connectionHandle
                    || l2cap.destinationCid != HciCodec.TERMINUS_LOCAL_CID) {
                continue;
            }
            l2capFrames++;
            log(String.format(
                    Locale.US,
                    "IKE_SA_INIT L2CAP RX #%d: cid=0x%04X bytes=%s",
                    l2capFrames,
                    l2cap.destinationCid,
                    HciCodec.toHex(l2cap.payload)));
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                log("IKE_SA_INIT ERTM DECODE FAIL: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(l2cap.payload));
                throw new HostException(
                        "Malformed ERTM frame from Watch",
                        error);
            }
            if (ertm.requestSequence > 1) {
                throw new HostException(
                        "Watch ERTM ReqSeq advanced beyond the single "
                                + "outbound I-frame: "
                                + ertm.requestSequence);
            }
            if (ertm.requestSequence == 1) {
                requestAcknowledged = true;
            }
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "TERMINUS ERTM S-FRAME RX: function=%d "
                                + "ReqSeq=%d P=%d F=%d FCS=%s.",
                        ertm.supervisoryFunction,
                        ertm.requestSequence,
                        ertm.poll ? 1 : 0,
                        ertm.finalBit ? 1 : 0,
                        formatErtmFcs(ertm)));
                if (ertm.poll) {
                    byte[] finalAck =
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    true,
                                    TERMINUS_FCS_ENABLED);
                    sendL2cap(
                            responderLocalCid,
                            finalAck,
                            "TERMINUS ERTM RR FINAL");
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    throw new HostException(
                            "Watch rejected ERTM TxSeq 0; identical "
                                    + "IKE_SA_INIT was not retransmitted");
                }
                continue;
            }
            if (ertm.sar != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented Watch ERTM response is not supported "
                                + "in this bounded probe; SAR="
                                + ertm.sar);
            }
            if (ertm.txSequence
                    == ((expectedWatchTxSequence
                    + L2capErtmCodec.SEQUENCE_MODULUS
                    - 1) % L2capErtmCodec.SEQUENCE_MODULUS)) {
                log("TERMINUS ERTM DUPLICATE I-FRAME: TxSeq="
                        + ertm.txSequence
                        + "; acknowledging without reprocessing.");
                byte[] duplicateAck =
                        L2capErtmCodec.encodeReceiverReady(
                                responderLocalCid,
                                expectedWatchTxSequence,
                                false,
                                TERMINUS_FCS_ENABLED);
                sendL2cap(
                        responderLocalCid,
                        duplicateAck,
                        "TERMINUS ERTM RR DUPLICATE");
                continue;
            }
            if (ertm.txSequence != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            ertmInformationFrames++;
            log(String.format(
                    Locale.US,
                    "TERMINUS ERTM I-FRAME RX #%d: TxSeq=%d "
                            + "ReqSeq=%d F=%d FCS=%s information=%s",
                    ertmInformationFrames,
                    ertm.txSequence,
                    ertm.requestSequence,
                    ertm.finalBit ? 1 : 0,
                    formatErtmFcs(ertm),
                    HciCodec.toHex(ertm.information)));

            byte[] acknowledgement =
                    L2capErtmCodec.encodeReceiverReady(
                            responderLocalCid,
                            expectedWatchTxSequence,
                            false,
                            TERMINUS_FCS_ENABLED);
            sendL2cap(
                    responderLocalCid,
                    acknowledgement,
                    "TERMINUS ERTM RR ACK");

            List<byte[]> ikePackets;
            try {
                ikePackets = decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                log("IKE_SA_INIT uIKE DECODE FAIL: "
                        + error.getMessage()
                        + " buffered="
                        + HciCodec.toHex(decoder.bufferedBytes()));
                throw new HostException(
                        "Malformed uIKE response from Watch",
                        error);
            }
            for (byte[] ikePacket : ikePackets) {
                if (!hasInitiatorSpi(
                        ikePacket,
                        initiator.initiatorSpi)) {
                    log("IKE_SA_INIT STALE DROP: response initiator "
                            + "SPI does not match current request; "
                            + "bytes="
                            + ikePacket.length
                            + " packet bytes logged=false.");
                    continue;
                }
                log("IKE_SA_INIT IKE RX: bytes="
                        + ikePacket.length
                        + " raw="
                        + HciCodec.toHex(ikePacket));
                IkeV2Codec.IkePacketSummary summary;
                try {
                    summary = IkeV2Codec.parseSaInitResponse(
                            ikePacket,
                            initiator.initiatorSpi);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Invalid IKE_SA_INIT response: "
                                    + error.getMessage(),
                            error);
                }
                log(String.format(
                        Locale.US,
                        "IKE_SA_INIT PARSED: responderSPI=%s "
                                + "version=0x%02X flags=0x%02X "
                                + "payloads=%s notifies=%s.",
                        HciCodec.toHex(summary.responderSpi),
                        summary.version,
                        summary.flags,
                        formatHexList(summary.payloadTypes),
                        formatHexList(summary.notifyTypes)));
                for (int notifyType : summary.notifyTypes) {
                    if (notifyType < 0x4000) {
                        throw new HostException(String.format(
                                Locale.US,
                                "Watch returned IKE error notify 0x%04X",
                                notifyType));
                    }
                }
                if (!summary.payloadTypes.contains(IkeV2Codec.PAYLOAD_SA)
                        || !summary.payloadTypes.contains(
                        IkeV2Codec.PAYLOAD_KE)
                        || !summary.payloadTypes.contains(
                        IkeV2Codec.PAYLOAD_NONCE)) {
                        throw new HostException(
                                "IKE_SA_INIT response lacks SA, KE, or nonce");
                }
                IkeV2SessionCrypto.ControlSaInitResponse
                        controlResponse;
                IkeV2SessionCrypto.IkeSaKeys initialKeys;
                try {
                    controlResponse =
                            IkeV2SessionCrypto
                                    .parseControlSaInitResponse(
                                            ikePacket,
                                            initiator.initiatorSpi);
                    initialKeys =
                            IkeV2SessionCrypto.deriveInitialKeys(
                                    initiator,
                                    controlResponse);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Cannot establish initial IKE key schedule: "
                                    + error.getMessage(),
                            error);
                }
                log("IKE INITIAL KEYS DERIVED: X448 + "
                        + "PRF-HMAC-SHA2-512; SK_d=64 SK_ei=36 "
                        + "SK_er=36 SK_pi=64 SK_pr=64; key bytes "
                        + "and shared secret logged=false.");
                IntermediateTransportState intermediate =
                        probeAdditionalKeyExchange(
                        responderLocalCid,
                        initiator,
                        controlResponse,
                        initialKeys,
                        expectedWatchTxSequence);
                AuthenticatedTransportState authenticated =
                        probeControlIkeAuth(
                        responderLocalCid,
                        initiator,
                        controlResponse,
                        intermediate);
                if (opticalPairing != null) {
                    probeOpticalPairing(responderLocalCid, authenticated, watchSetupData);
                    return;
                }
                PinTransportState pinState =
                        probePinAuthMethod(
                                responderLocalCid,
                                authenticated);
                char[] pin = null;
                AppleWatchPairingCrypto.PairingSecrets
                        pairingSecrets = null;
                try {
                    pin = awaitWatchPin(responderLocalCid, pinState);
                    pairingSecrets =
                            AppleWatchPairingCrypto.derive(
                                    pin,
                                    pinState.pinSalt,
                                    watchSetupData);
                    pin = null;
                    log("PAIRING KDF READY: PBKDF2-HMAC-SHA256 "
                            + "iterations=1600000; SPAKE2+ seed="
                            + pairingSecrets.sharedSecret.length
                            + " bytes; mandatory PPK="
                            + pairingSecrets.ppk.length
                            + " bytes; PIN/salt/key bytes "
                            + "logged=false.");
                    probePairingSession(
                            responderLocalCid,
                            pinState,
                            pairingSecrets);
                } finally {
                    if (pin != null) {
                        Arrays.fill(pin, '\0');
                    }
                    if (pairingSecrets != null) {
                        pairingSecrets.destroy();
                    }
                    pinState.destroy();
                }
                return;
            }
        }
        throw new HostException(
                "IKE_SA_INIT response timed out after "
                + IKE_SA_INIT_TIMEOUT_MS
                + " ms; L2CAP frames="
                + l2capFrames
                + " ERTM information frames="
                + ertmInformationFrames
                + " requestAcknowledged="
                + requestAcknowledged
                + " buffered="
                + HciCodec.toHex(decoder.bufferedBytes()));
    }

    private IntermediateTransportState probeAdditionalKeyExchange(
            int responderLocalCid,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse controlResponse,
            IkeV2SessionCrypto.IkeSaKeys initialKeys,
            int initialExpectedWatchTxSequence)
            throws Exception {
        IkeV2SessionCrypto.AdditionalKeRequest request;
        try {
            request =
                    IkeV2SessionCrypto.createAdditionalKeRequest(
                            new SecureRandom(),
                            initiator,
                            controlResponse,
                            initialKeys);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Cannot create ML-KEM-1024 request: "
                            + error.getMessage(),
                    error);
        }
        log("IKE_INTERMEDIATE CONFIG: exchange=43 messageID=1 "
                + "ADDKE1=ML-KEM-1024 publicKey="
                + request.publicKey.length
                + " bytes maxIKEPacket="
                + IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE
                + " encryptedFragments="
                + request.ikePackets.size()
                + " privateKeyLogged=false.");

        int outboundTxSequence = 1;
        int requestSequence = initialExpectedWatchTxSequence;
        java.util.ArrayList<byte[]> sentIntermediateFrames =
                new java.util.ArrayList<>();
        java.util.ArrayList<Integer> sentIntermediateTxSeq =
                new java.util.ArrayList<>();
        for (int index = 0;
                index < request.ikePackets.size();
                index++) {
            byte[] ikePacket = request.ikePackets.get(index);
            byte[] uikeFrame =
                    IkeV2Codec.encodeUikeFrame(ikePacket);
            byte[] ertmRequest =
                    L2capErtmCodec.encodeInformationFrame(
                            responderLocalCid,
                            outboundTxSequence,
                            requestSequence,
                            uikeFrame,
                            TERMINUS_FCS_ENABLED);
            sentIntermediateFrames.add(ertmRequest);
            sentIntermediateTxSeq.add(outboundTxSequence);
            log(String.format(
                    Locale.US,
                    "IKE_INTERMEDIATE TX fragment=%d/%d "
                            + "IKE=%d uIKE=%d ERTM=%d "
                            + "TxSeq=%d ReqSeq=%d rawIKE=%s",
                    index + 1,
                    request.ikePackets.size(),
                    ikePacket.length,
                    uikeFrame.length,
                    ertmRequest.length,
                    outboundTxSequence,
                    requestSequence,
                    HciCodec.toHex(ikePacket)));
            sendL2cap(
                    responderLocalCid,
                    ertmRequest,
                    "TERMINUS ERTM IKE_INTERMEDIATE "
                            + (index + 1)
                            + "/"
                            + request.ikePackets.size());
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }
        int expectedRequestSequence = outboundTxSequence;
        int intermediateRetransmits = 0;
        log("IKE_INTERMEDIATE SAFETY BOUNDARY: only encrypted "
                + "ML-KEM-1024 public-key fragments were sent; "
                + "IKE_AUTH/PIN/SMP/bond/activation/setup-commit "
                + "bytes sent=0.");

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        IkeV2SessionCrypto.IntermediateResponseAccumulator
                accumulator =
                new IkeV2SessionCrypto
                        .IntermediateResponseAccumulator(initialKeys);
        long deadline = BridgeClock.elapsedRealtime()
                + IKE_INTERMEDIATE_TIMEOUT_MS;
        int expectedWatchTxSequence =
                initialExpectedWatchTxSequence;
        int l2capFrames = 0;
        int ikePackets = 0;
        int highestWatchAck = 1;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet;
            try {
                packet = nextPacket(deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == connectionHandle) {
                    connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected during "
                                    + "IKE_INTERMEDIATE; status=0x%02X "
                                    + "reason=0x%02X",
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log("IKE_INTERMEDIATE ACL DROP: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (l2cap == null
                    || l2cap.connectionHandle != connectionHandle
                    || l2cap.destinationCid
                    != HciCodec.TERMINUS_LOCAL_CID) {
                continue;
            }
            l2capFrames++;
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed IKE_INTERMEDIATE ERTM frame: "
                                + error.getMessage(),
                        error);
            }
            if (ertm.requestSequence
                    > expectedRequestSequence) {
                throw new HostException(
                        "Watch acknowledged unsent ERTM sequence "
                                + ertm.requestSequence);
            }
            highestWatchAck = Math.max(
                    highestWatchAck,
                    ertm.requestSequence);
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "IKE_INTERMEDIATE ERTM S-FRAME RX: "
                                + "function=%d ReqSeq=%d P=%d F=%d.",
                        ertm.supervisoryFunction,
                        ertm.requestSequence,
                        ertm.poll ? 1 : 0,
                        ertm.finalBit ? 1 : 0));
                if (ertm.poll) {
                    sendL2cap(
                            responderLocalCid,
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    true,
                                    TERMINUS_FCS_ENABLED),
                            "TERMINUS ERTM RR FINAL "
                                    + "IKE_INTERMEDIATE");
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    // Live 0.2.207: the Watch SREJ'd TxSeq=1 of the
                    // ML-KEM fragments and the host aborted. SREJ/REJ
                    // asks for those I-frames again.
                    if (++intermediateRetransmits > 4) {
                        throw new HostException(
                                "Watch rejected an IKE_INTERMEDIATE "
                                        + "ERTM frame");
                    }
                    boolean selective = ertm.supervisoryFunction
                            == L2capErtmCodec.SUPERVISORY_SREJ;
                    for (int index = 0;
                            index < sentIntermediateTxSeq.size();
                            index++) {
                        int txSeq = sentIntermediateTxSeq.get(index);
                        int distance = (txSeq
                                - ertm.requestSequence
                                + L2capErtmCodec.SEQUENCE_MODULUS)
                                % L2capErtmCodec.SEQUENCE_MODULUS;
                        if (selective
                                ? txSeq == ertm.requestSequence
                                : distance < sentIntermediateTxSeq.size()) {
                            sendL2cap(
                                    responderLocalCid,
                                    sentIntermediateFrames.get(index),
                                    "TERMINUS ERTM IKE_INTERMEDIATE "
                                            + "RETRANSMIT txSeq="
                                            + txSeq);
                            log("IKE_INTERMEDIATE RETRANSMIT: txSeq="
                                    + txSeq
                                    + " after "
                                    + (selective ? "SREJ" : "REJ")
                                    + " reqSeq="
                                    + ertm.requestSequence);
                        }
                    }
                }
                continue;
            }
            if (ertm.sar
                    != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented ERTM I-frame is not expected; SAR="
                                + ertm.sar);
            }
            int previousWatchTxSequence =
                    (expectedWatchTxSequence
                            + L2capErtmCodec.SEQUENCE_MODULUS
                            - 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            if (ertm.txSequence
                    == previousWatchTxSequence) {
                sendL2cap(
                        responderLocalCid,
                        L2capErtmCodec.encodeReceiverReady(
                                responderLocalCid,
                                expectedWatchTxSequence,
                                false,
                                TERMINUS_FCS_ENABLED),
                        "TERMINUS ERTM RR DUPLICATE "
                                + "IKE_INTERMEDIATE");
                continue;
            }
            if (ertm.txSequence
                    != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            log(String.format(
                    Locale.US,
                    "IKE_INTERMEDIATE ERTM I-FRAME RX: "
                            + "TxSeq=%d ReqSeq=%d informationBytes=%d.",
                    ertm.txSequence,
                    ertm.requestSequence,
                    ertm.information.length));
            sendL2cap(
                    responderLocalCid,
                    L2capErtmCodec.encodeReceiverReady(
                            responderLocalCid,
                            expectedWatchTxSequence,
                            false,
                            TERMINUS_FCS_ENABLED),
                    "TERMINUS ERTM RR ACK "
                            + "IKE_INTERMEDIATE");

            List<byte[]> decodedPackets;
            try {
                decodedPackets =
                        decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed IKE_INTERMEDIATE uIKE frame: "
                                + error.getMessage(),
                        error);
            }
            for (byte[] ikePacket : decodedPackets) {
                ikePackets++;
                log("IKE_INTERMEDIATE IKE RX #"
                        + ikePackets
                        + ": bytes="
                        + ikePacket.length
                        + " raw="
                        + HciCodec.toHex(ikePacket));
                byte[] ciphertext;
                try {
                    ciphertext =
                            accumulator.accept(ikePacket);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Cannot authenticate/decrypt "
                                    + "IKE_INTERMEDIATE response: "
                                    + error.getMessage(),
                            error);
                }
                if (ciphertext == null) {
                    continue;
                }
                IkeV2SessionCrypto.AdditionalKeResult result;
                try {
                    result =
                            IkeV2SessionCrypto
                                    .completeAdditionalKeyExchange(
                                            request,
                                            ciphertext,
                                            initialKeys);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "ML-KEM-1024 decapsulation failed: "
                                    + error.getMessage(),
                            error);
                }
                log("IKE_INTERMEDIATE PASS: responder ciphertext="
                        + ciphertext.length
                        + " ML-KEM sharedSecret="
                        + result.sharedSecret.length
                        + " bytes; updated SK_d=64 SK_ei=36 "
                        + "SK_er=36 SK_pi=64 SK_pr=64; "
                        + "secret/key bytes logged=false; "
                        + "highestWatchReqSeq="
                        + highestWatchAck
                        + ".");
                return new IntermediateTransportState(
                        result,
                        request.initiatorIntAuth,
                        accumulator.responderIntAuth(),
                        expectedRequestSequence,
                        expectedWatchTxSequence);
            }
        }
        throw new HostException(
                "IKE_INTERMEDIATE response timed out after "
                        + IKE_INTERMEDIATE_TIMEOUT_MS
                        + " ms; L2CAP frames="
                        + l2capFrames
                        + " IKE packets="
                        + ikePackets
                        + " highestWatchReqSeq="
                        + highestWatchAck
                        + " expectedReqSeq="
                        + expectedRequestSequence
                        + " buffered="
                        + HciCodec.toHex(decoder.bufferedBytes()));
    }

    private AuthenticatedTransportState probeControlIkeAuth(
            int responderLocalCid,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse controlResponse,
            IntermediateTransportState intermediate)
            throws Exception {
        IkeV2SessionCrypto.IkeAuthRequest request;
        try {
            request =
                    IkeV2SessionCrypto
                            .createControlIkeAuthRequest(
                                    new SecureRandom(),
                                    initiator,
                                    controlResponse,
                                    intermediate
                                            .additionalKeResult
                                            .updatedKeys,
                                    intermediate.initiatorIntAuth,
                                    intermediate.responderIntAuth);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Cannot create control IKE_AUTH request: "
                            + error.getMessage(),
                    error);
        }
        log("IKE_AUTH CONFIG: exchange=35 messageID=2 childless=true "
                + "IDi=ID_NULL IDr=ID_KEY_ID(\""
                + IkeV2SessionCrypto.CONTROL_PAIRING_KEY_ID
                + "\") AUTH=NULL/13 INITIAL_CONTACT=true "
                + "SA/TS/CP/EAP/privateNotify=false "
                + "RFC9242 IntAuth=true authData="
                + request.authenticationDataLength
                + " bytes encryptedPackets="
                + request.ikePackets.size()
                + " secret/key/auth bytes logged=false.");

        int outboundTxSequence =
                intermediate.nextOutboundTxSequence;
        int requestSequence =
                intermediate.nextExpectedWatchTxSequence;
        for (int index = 0;
                index < request.ikePackets.size();
                index++) {
            byte[] ikePacket = request.ikePackets.get(index);
            byte[] uikeFrame =
                    IkeV2Codec.encodeUikeFrame(ikePacket);
            byte[] ertmRequest =
                    L2capErtmCodec.encodeInformationFrame(
                            responderLocalCid,
                            outboundTxSequence,
                            requestSequence,
                            uikeFrame,
                            TERMINUS_FCS_ENABLED);
            log(String.format(
                    Locale.US,
                    "IKE_AUTH TX packet=%d/%d IKE=%d uIKE=%d "
                            + "ERTM=%d TxSeq=%d ReqSeq=%d "
                            + "rawEncryptedIKE=%s",
                    index + 1,
                    request.ikePackets.size(),
                    ikePacket.length,
                    uikeFrame.length,
                    ertmRequest.length,
                    outboundTxSequence,
                    requestSequence,
                    HciCodec.toHex(ikePacket)));
            sendL2cap(
                    responderLocalCid,
                    ertmRequest,
                    "TERMINUS ERTM IKE_AUTH "
                            + (index + 1)
                            + "/"
                            + request.ikePackets.size());
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }
        int expectedRequestSequence =
                outboundTxSequence;
        log("IKE_AUTH SAFETY BOUNDARY: encrypted standard IKEv2 "
                + "IDi/INITIAL_CONTACT/IDr/NULL-AUTH only; "
                + "Apple private notify/PIN/SMP/bond/activation/"
                + "setup-commit bytes sent=0.");

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        IkeV2SessionCrypto.IkeAuthResponseAccumulator accumulator =
                new IkeV2SessionCrypto
                        .IkeAuthResponseAccumulator(
                                initiator,
                                controlResponse,
                                intermediate
                                        .additionalKeResult
                                        .updatedKeys,
                                intermediate.initiatorIntAuth,
                                intermediate.responderIntAuth);
        long deadline =
                BridgeClock.elapsedRealtime()
                        + IKE_AUTH_TIMEOUT_MS;
        int expectedWatchTxSequence =
                intermediate.nextExpectedWatchTxSequence;
        int highestWatchAck =
                intermediate.nextOutboundTxSequence;
        int l2capFrames = 0;
        int ikePackets = 0;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet;
            try {
                packet = nextPacket(deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == connectionHandle) {
                    connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected during IKE_AUTH; "
                                    + "status=0x%02X reason=0x%02X",
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log("IKE_AUTH ACL DROP: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (l2cap == null
                    || l2cap.connectionHandle != connectionHandle
                    || l2cap.destinationCid
                    != HciCodec.TERMINUS_LOCAL_CID) {
                continue;
            }
            l2capFrames++;
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed IKE_AUTH ERTM frame: "
                                + error.getMessage(),
                        error);
            }
            if (ertm.requestSequence
                    > expectedRequestSequence) {
                throw new HostException(
                        "Watch acknowledged unsent IKE_AUTH "
                                + "ERTM sequence "
                                + ertm.requestSequence);
            }
            highestWatchAck = Math.max(
                    highestWatchAck,
                    ertm.requestSequence);
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "IKE_AUTH ERTM S-FRAME RX: function=%d "
                                + "ReqSeq=%d P=%d F=%d.",
                        ertm.supervisoryFunction,
                        ertm.requestSequence,
                        ertm.poll ? 1 : 0,
                        ertm.finalBit ? 1 : 0));
                if (ertm.poll) {
                    sendL2cap(
                            responderLocalCid,
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    true,
                                    TERMINUS_FCS_ENABLED),
                            "TERMINUS ERTM RR FINAL IKE_AUTH");
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    throw new HostException(
                            "Watch rejected the IKE_AUTH "
                                    + "ERTM frame");
                }
                continue;
            }
            if (ertm.sar
                    != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented IKE_AUTH ERTM I-frame "
                                + "is not expected; SAR="
                                + ertm.sar);
            }
            int previousWatchTxSequence =
                    (expectedWatchTxSequence
                            + L2capErtmCodec.SEQUENCE_MODULUS
                            - 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            if (ertm.txSequence
                    == previousWatchTxSequence) {
                sendL2cap(
                        responderLocalCid,
                        L2capErtmCodec.encodeReceiverReady(
                                responderLocalCid,
                                expectedWatchTxSequence,
                                false,
                                TERMINUS_FCS_ENABLED),
                        "TERMINUS ERTM RR DUPLICATE IKE_AUTH");
                continue;
            }
            if (ertm.txSequence
                    != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected IKE_AUTH Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            log(String.format(
                    Locale.US,
                    "IKE_AUTH ERTM I-FRAME RX: TxSeq=%d "
                            + "ReqSeq=%d informationBytes=%d.",
                    ertm.txSequence,
                    ertm.requestSequence,
                    ertm.information.length));
            sendL2cap(
                    responderLocalCid,
                    L2capErtmCodec.encodeReceiverReady(
                            responderLocalCid,
                            expectedWatchTxSequence,
                            false,
                            TERMINUS_FCS_ENABLED),
                    "TERMINUS ERTM RR ACK IKE_AUTH");

            List<byte[]> decodedPackets;
            try {
                decodedPackets =
                        decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed IKE_AUTH uIKE frame: "
                                + error.getMessage(),
                        error);
            }
            for (byte[] ikePacket : decodedPackets) {
                ikePackets++;
                log("IKE_AUTH IKE RX #"
                        + ikePackets
                        + ": bytes="
                        + ikePacket.length
                        + " rawEncryptedIKE="
                        + HciCodec.toHex(ikePacket));
                IkeV2SessionCrypto.IkeAuthResponse authResponse;
                try {
                    authResponse =
                            accumulator.accept(ikePacket);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Cannot authenticate/decrypt "
                                    + "IKE_AUTH response: "
                                    + error.getMessage(),
                            error);
                }
                if (authResponse == null) {
                    continue;
                }
                if (highestWatchAck
                        != expectedRequestSequence) {
                    throw new HostException(
                            "Watch IKE_AUTH response did not "
                                    + "acknowledge all request "
                                    + "I-frames; highest="
                                    + highestWatchAck
                                    + " expected="
                                    + expectedRequestSequence);
                }
                log("IKE_AUTH PASS: responder ID_KEY_ID matched; "
                        + "NULL-AUTH verified with updated SK_pr; "
                        + "payloads="
                        + formatHexList(
                                authResponse.payloadTypes)
                        + " notifies="
                        + formatHexList(
                                authResponse.notifyTypes)
                        + " authData="
                        + authResponse.authenticationDataLength
                        + " bytes; childless IKE SA authenticated; "
                        + "secret/key/auth bytes logged=false.");
                return new AuthenticatedTransportState(
                        intermediate,
                        expectedRequestSequence,
                        expectedWatchTxSequence);
            }
        }
        throw new HostException(
                "IKE_AUTH response timed out after "
                        + IKE_AUTH_TIMEOUT_MS
                        + " ms; L2CAP frames="
                        + l2capFrames
                        + " IKE packets="
                        + ikePackets
                        + " highestWatchReqSeq="
                        + highestWatchAck
                        + " expectedReqSeq="
                        + expectedRequestSequence
                        + " buffered="
                        + HciCodec.toHex(decoder.bufferedBytes()));
    }

    private PinTransportState probePinAuthMethod(
            int responderLocalCid,
            AuthenticatedTransportState authenticated)
            throws Exception {
        IkeV2SessionCrypto.IkeSaKeys updatedKeys =
                authenticated.intermediate
                        .additionalKeResult.updatedKeys;
        IkeV2SessionCrypto.PinAuthMethodRequest request;
        try {
            request =
                    IkeV2SessionCrypto
                            .createPinAuthMethodRequest(
                                    new SecureRandom(),
                                    updatedKeys);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Cannot create PIN auth-method request: "
                            + error.getMessage(),
                    error);
        }
        log("PIN_METHOD CONFIG: exchange=37 messageID=3 "
                + "privateNotify=0xC545 protocol=0 spiSize=0 "
                + "NRTLV(type=1,length=1,value=PIN/2) "
                + "plaintextBytes="
                + request.plaintext.length
                + " encryptedPackets="
                + request.ikePackets.size()
                + " secret/key bytes logged=false.");

        int outboundTxSequence =
                authenticated.nextOutboundTxSequence;
        int requestSequence =
                authenticated.nextExpectedWatchTxSequence;
        for (int index = 0;
                index < request.ikePackets.size();
                index++) {
            byte[] ikePacket = request.ikePackets.get(index);
            byte[] uikeFrame =
                    IkeV2Codec.encodeUikeFrame(ikePacket);
            byte[] ertmRequest =
                    L2capErtmCodec.encodeInformationFrame(
                            responderLocalCid,
                            outboundTxSequence,
                            requestSequence,
                            uikeFrame,
                            TERMINUS_FCS_ENABLED);
            log(String.format(
                    Locale.US,
                    "PIN_METHOD TX packet=%d/%d IKE=%d uIKE=%d "
                            + "ERTM=%d TxSeq=%d ReqSeq=%d "
                            + "rawEncryptedIKE=%s",
                    index + 1,
                    request.ikePackets.size(),
                    ikePacket.length,
                    uikeFrame.length,
                    ertmRequest.length,
                    outboundTxSequence,
                    requestSequence,
                    HciCodec.toHex(ikePacket)));
            sendL2cap(
                    responderLocalCid,
                    ertmRequest,
                    "TERMINUS ERTM PIN_METHOD "
                            + (index + 1)
                            + "/"
                            + request.ikePackets.size());
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }
        int expectedRequestSequence =
                outboundTxSequence;
        log("PIN_METHOD SAFETY BOUNDARY: encrypted Apple private "
                + "notify 0xC545 requesting PIN/2 only; PIN value/"
                + "salt bytes/PSK/previous-pairing/IKE pairing "
                + "session/SMP/bond/activation/setup-commit bytes "
                + "sent or logged=0.");

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        IkeV2SessionCrypto.PinAuthMethodResponseAccumulator
                accumulator =
                new IkeV2SessionCrypto
                        .PinAuthMethodResponseAccumulator(
                                updatedKeys);
        long deadline =
                BridgeClock.elapsedRealtime()
                        + PIN_AUTH_METHOD_TIMEOUT_MS;
        int expectedWatchTxSequence =
                authenticated.nextExpectedWatchTxSequence;
        int highestWatchAck =
                authenticated.nextOutboundTxSequence;
        int l2capFrames = 0;
        int ikePackets = 0;
        boolean emptyResponseLogged = false;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet;
            try {
                packet = nextPacket(deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == connectionHandle) {
                    connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected during PIN_METHOD; "
                                    + "status=0x%02X reason=0x%02X",
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log("PIN_METHOD ACL DROP: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (l2cap == null
                    || l2cap.connectionHandle != connectionHandle
                    || l2cap.destinationCid
                    != HciCodec.TERMINUS_LOCAL_CID) {
                continue;
            }
            l2capFrames++;
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed PIN_METHOD ERTM frame: "
                                + error.getMessage(),
                        error);
            }
            if (ertm.requestSequence
                    > expectedRequestSequence) {
                throw new HostException(
                        "Watch acknowledged unsent PIN_METHOD "
                                + "ERTM sequence "
                                + ertm.requestSequence);
            }
            highestWatchAck = Math.max(
                    highestWatchAck,
                    ertm.requestSequence);
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "PIN_METHOD ERTM S-FRAME RX: function=%d "
                                + "ReqSeq=%d P=%d F=%d.",
                        ertm.supervisoryFunction,
                        ertm.requestSequence,
                        ertm.poll ? 1 : 0,
                        ertm.finalBit ? 1 : 0));
                if (ertm.poll) {
                    sendL2cap(
                            responderLocalCid,
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    true,
                                    TERMINUS_FCS_ENABLED),
                            "TERMINUS ERTM RR FINAL PIN_METHOD");
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    throw new HostException(
                            "Watch rejected the PIN_METHOD "
                                    + "ERTM frame");
                }
                continue;
            }
            if (ertm.sar
                    != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented PIN_METHOD ERTM I-frame "
                                + "is not expected; SAR="
                                + ertm.sar);
            }
            int previousWatchTxSequence =
                    (expectedWatchTxSequence
                            + L2capErtmCodec.SEQUENCE_MODULUS
                            - 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            if (ertm.txSequence
                    == previousWatchTxSequence) {
                sendL2cap(
                        responderLocalCid,
                        L2capErtmCodec.encodeReceiverReady(
                                responderLocalCid,
                                expectedWatchTxSequence,
                                false,
                                TERMINUS_FCS_ENABLED),
                        "TERMINUS ERTM RR DUPLICATE PIN_METHOD");
                continue;
            }
            if (ertm.txSequence
                    != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected PIN_METHOD Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            log(String.format(
                    Locale.US,
                    "PIN_METHOD ERTM I-FRAME RX: TxSeq=%d "
                            + "ReqSeq=%d informationBytes=%d.",
                    ertm.txSequence,
                    ertm.requestSequence,
                    ertm.information.length));
            sendL2cap(
                    responderLocalCid,
                    L2capErtmCodec.encodeReceiverReady(
                            responderLocalCid,
                            expectedWatchTxSequence,
                            false,
                            TERMINUS_FCS_ENABLED),
                    "TERMINUS ERTM RR ACK PIN_METHOD");

            List<byte[]> decodedPackets;
            try {
                decodedPackets =
                        decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed PIN_METHOD uIKE frame: "
                                + error.getMessage(),
                        error);
            }
            for (byte[] ikePacket : decodedPackets) {
                ikePackets++;
                log("PIN_METHOD IKE RX #"
                        + ikePackets
                        + ": bytes="
                        + ikePacket.length
                        + " rawEncryptedIKE="
                        + HciCodec.toHex(ikePacket));
                IkeV2SessionCrypto.PinAuthMethodResponse
                        pinResponse;
                try {
                    pinResponse =
                            accumulator.accept(ikePacket);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Cannot authenticate/decrypt "
                                    + "PIN_METHOD response: "
                                    + error.getMessage(),
                            error);
                }
                if (pinResponse == null) {
                    if (accumulator.requestAcknowledged()
                            && !emptyResponseLogged) {
                        emptyResponseLogged = true;
                        log("PIN_METHOD ACK PASS: Watch returned "
                                + "authenticated empty INFORMATIONAL "
                                + "response for initiator MID=3; "
                                + "waiting for responder-originated "
                                + "0xC546 request at responder MID=0.");
                    }
                    continue;
                }
                if (highestWatchAck
                        != expectedRequestSequence) {
                    throw new HostException(
                            "Watch PIN_METHOD response did not "
                                    + "acknowledge all request "
                                    + "I-frames; highest="
                                    + highestWatchAck
                                    + " expected="
                                    + expectedRequestSequence);
                }
                int nextOutboundTxSequence =
                        expectedRequestSequence;
                if (pinResponse.peerRequestMessageId >= 0) {
                    List<byte[]> acknowledgementPackets;
                    try {
                        acknowledgementPackets =
                                IkeV2SessionCrypto
                                        .createWatchInformationalAcknowledgement(
                                                new SecureRandom(),
                                                updatedKeys,
                                                pinResponse
                                                        .peerRequestMessageId);
                    } catch (IllegalArgumentException error) {
                        throw new HostException(
                                "Cannot create Watch INFORMATIONAL "
                                        + "acknowledgement: "
                                        + error.getMessage(),
                                error);
                    }
                    int acknowledgementTxSequence =
                            expectedRequestSequence;
                    for (int index = 0;
                            index < acknowledgementPackets.size();
                            index++) {
                        byte[] acknowledgement =
                                acknowledgementPackets.get(index);
                        byte[] acknowledgementUike =
                                IkeV2Codec.encodeUikeFrame(
                                        acknowledgement);
                        byte[] acknowledgementErtm =
                                L2capErtmCodec
                                        .encodeInformationFrame(
                                                responderLocalCid,
                                                acknowledgementTxSequence,
                                                expectedWatchTxSequence,
                                                acknowledgementUike,
                                                TERMINUS_FCS_ENABLED);
                        log(String.format(
                                Locale.US,
                                "PIN_METHOD WATCH-REQUEST ACK TX "
                                        + "packet=%d/%d IKE=%d "
                                        + "MID=%d flags=0x28 "
                                        + "innerPayload=NONE "
                                        + "TxSeq=%d ReqSeq=%d "
                                        + "rawEncryptedIKE=%s",
                                index + 1,
                                acknowledgementPackets.size(),
                                acknowledgement.length,
                                pinResponse.peerRequestMessageId,
                                acknowledgementTxSequence,
                                expectedWatchTxSequence,
                                HciCodec.toHex(acknowledgement)));
                        sendL2cap(
                                responderLocalCid,
                                acknowledgementErtm,
                                "TERMINUS ERTM PIN_METHOD "
                                        + "WATCH-REQUEST ACK "
                                        + (index + 1)
                                        + "/"
                                        + acknowledgementPackets.size());
                        acknowledgementTxSequence =
                                (acknowledgementTxSequence + 1)
                                        % L2capErtmCodec
                                        .SEQUENCE_MODULUS;
                    }
                    nextOutboundTxSequence =
                            acknowledgementTxSequence;
                }
                int saltLength = pinResponse.pinSalt.length;
                log("PIN_METHOD PASS: response notify 0xC546 "
                        + "authenticated; method=PIN/2 payloads="
                        + formatHexList(
                                pinResponse.payloadTypes)
                        + " notifies="
                        + formatHexList(
                                pinResponse.notifyTypes)
                        + " saltLength="
                        + saltLength
                        + " bytes; salt/PIN/key bytes logged=false; "
                        + "salt retained=volatile-until-KDF; Watch request "
                        + "empty-IKE-ACK sent="
                        + (pinResponse.peerRequestMessageId >= 0)
                        + ".");
                PinTransportState state =
                        new PinTransportState(
                                pinResponse.pinSalt,
                                nextOutboundTxSequence,
                                expectedWatchTxSequence);
                Arrays.fill(
                        pinResponse.pinSalt,
                        (byte) 0);
                return state;
            }
        }
        throw new HostException(
                "PIN_METHOD response timed out after "
                        + PIN_AUTH_METHOD_TIMEOUT_MS
                        + " ms; L2CAP frames="
                        + l2capFrames
                        + " IKE packets="
                        + ikePackets
                        + " highestWatchReqSeq="
                        + highestWatchAck
                        + " expectedReqSeq="
                        + expectedRequestSequence
                        + " buffered="
                        + HciCodec.toHex(decoder.bufferedBytes()));
    }

    private char[] awaitWatchPin(int responderLocalCid, PinTransportState pinState) throws Exception {
        log("PIN_REQUIRED: enter the six-digit code shown by the "
                + "Watch in the app. The value will remain only in "
                + "volatile memory and will not be logged.");
        long deadline =
                BridgeClock.elapsedRealtime()
                        + PIN_INPUT_TIMEOUT_MS;
        PinWaitErtmResponder responder = new PinWaitErtmResponder(
                responderLocalCid, pinState.nextExpectedWatchTxSequence, TERMINUS_FCS_ENABLED);
        List<Packet> deferred = new ArrayList<>();
        List<Packet> currentPdu = new ArrayList<>();
        boolean pinAccepted = false;
        try {
            while (BridgeClock.elapsedRealtime() < deadline) {
                checkStop();
                // Finish a fragmented ACL PDU and observe queued disconnects
                // before starting the expensive PIN KDF.
                if (currentPdu.isEmpty() && packets.isEmpty()) {
                    char[] pin = pinInputs.poll();
                    if (pin != null) {
                        pinAccepted = true;
                        return pin;
                    }
                }
                byte[] heartbeat = responder.heartbeat(BridgeClock.elapsedRealtime());
                if (heartbeat != null) {
                    sendL2cap(responderLocalCid, heartbeat, "PIN WAIT NORMAL ERTM KEEPALIVE");
                }
                Packet packet;
                try {
                    packet = nextPacket(Math.min(deadline, BridgeClock.elapsedRealtime() + 250));
                } catch (TimeoutException idle) {
                    continue;
                }
                if (packet.kind == PacketKind.EVENT) {
                    HciCodec.DisconnectionComplete disconnected =
                            HciCodec.parseDisconnectionComplete(packet.bytes);
                    if (disconnected != null && disconnected.connectionHandle == connectionHandle) {
                        connectionHandle = -1;
                        throw new HostException("Watch disconnected while waiting for PIN; reason=0x"
                                + Integer.toHexString(disconnected.reason));
                    }
                    continue;
                }
                currentPdu.add(packet);
                if (currentPdu.size() + deferred.size() > 256) {
                    throw new HostException("Too many pending ACL packets while waiting for PIN");
                }
                HciCodec.L2capPdu l2cap = aclReassembler.accept(packet.bytes);
                if (l2cap == null) continue;
                boolean consumed = l2cap.connectionHandle != connectionHandle;
                if (!consumed && l2cap.destinationCid == HciCodec.TERMINUS_LOCAL_CID) {
                    L2capErtmCodec.Frame frame = L2capErtmCodec.decode(
                            HciCodec.TERMINUS_LOCAL_CID, l2cap.payload, TERMINUS_FCS_ENABLED);
                    consumed = responder.consumes(frame);
                    byte[] reply = responder.response(frame);
                    if (reply != null) {
                        sendL2cap(responderLocalCid, reply, "PIN WAIT NORMAL ERTM RESPONSE");
                    }
                } else if (!consumed && (l2cap.destinationCid == BluetoothLinkMaintenance.ATT_CID
                        || l2cap.destinationCid == BluetoothLinkMaintenance.LE_SIGNALING_CID)) {
                    serviceNormalFixedChannel(l2cap);
                    consumed = true;
                }
                if (consumed) currentPdu.forEach(p -> wipe(p.bytes));
                else deferred.addAll(currentPdu);
                currentPdu.clear();
            }
        } finally {
            // New IKE/SMP data retains its original ACL framing and order;
            // the normal pairing parser processes it after PIN input.
            if (pinAccepted) deferredControllerPackets.addAll(deferred);
            else deferred.forEach(p -> wipe(p.bytes));
            currentPdu.forEach(p -> wipe(p.bytes));
        }
        throw new HostException(
                "Timed out waiting for the six-digit Watch PIN");
    }

    private void probeOpticalPairing(int cid, AuthenticatedTransportState control,
            byte[] watchSetupData) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] sharedSecret = opticalPairing.boundSharedSecret(watchSetupData);
        byte[] authData = opticalPairing.authenticationData();
        IkeV2SessionCrypto.IkeSaKeys initialKeys = null, pairingKeys = null;
        IkeV2SessionCrypto.AdditionalKeRequest additional = null;
        byte[] intAuthR = null, kemCiphertext = null;
        try {
            PairingTransportCursor cursor = new PairingTransportCursor(
                    control.nextOutboundTxSequence, control.nextExpectedWatchTxSequence);
            IkeV2SessionCrypto.IkeSaKeys controlKeys = control.intermediate.additionalKeResult.updatedKeys;
            try (OpticalAuthMethodNegotiation method = new OpticalAuthMethodNegotiation(controlKeys)) {
                PairingTransportResult<Integer> selection = exchangePairingIke(cid, cursor,
                        OpticalPskSession.controlMethodRequest(random, controlKeys),
                        "OPTICAL METHOD", PIN_AUTH_METHOD_TIMEOUT_MS, method::accept);
                cursor = selection.cursor;
                if (!method.pskSelected()) throw new HostException("Watch did not select optical PSK");
                if (selection.value >= 0) cursor = sendPairingIkeOneWayAndAwaitAck(cid, cursor,
                        IkeV2SessionCrypto.createWatchInformationalAcknowledgement(random, controlKeys, selection.value),
                        "OPTICAL METHOD ACK", PAIRING_IKE_TIMEOUT_MS);
            }
            log("OPTICAL METHOD PASS: PSK/1 selected on authenticated control SA; key/code logged=false.");
            IkeV2Codec.InitiatorState initiator = IkeV2Codec.createOpticalPairingSaInit(random);
            try {
                PairingTransportResult<IkeV2SessionCrypto.ControlSaInitResponse> init = exchangePairingIke(
                        cid, cursor, List.of(initiator.ikePacket), "OPTICAL IKE_SA_INIT", PAIRING_IKE_TIMEOUT_MS,
                        packet -> hasInitiatorSpi(packet, initiator.initiatorSpi)
                                ? IkeV2SessionCrypto.parseOpticalPairingSaInitResponse(packet, initiator.initiatorSpi) : null);
                cursor = init.cursor;
                initialKeys = IkeV2SessionCrypto.deriveInitialKeys(initiator, init.value);
                additional = IkeV2SessionCrypto.createAdditionalKeRequest(random, initiator, init.value, initialKeys);
                IkeV2SessionCrypto.IntermediateResponseAccumulator accumulator =
                        new IkeV2SessionCrypto.IntermediateResponseAccumulator(initialKeys);
                PairingTransportResult<byte[]> intermediate = exchangePairingIke(cid, cursor, additional.ikePackets,
                        "OPTICAL IKE_INTERMEDIATE", PAIRING_IKE_TIMEOUT_MS, accumulator::accept);
                cursor = intermediate.cursor; kemCiphertext = intermediate.value;
                pairingKeys = IkeV2SessionCrypto.completeAdditionalKeyExchange(additional, kemCiphertext, initialKeys).updatedKeys;
                intAuthR = accumulator.responderIntAuth();
                try (OpticalPskSession auth = new OpticalPskSession(initiator, init.value, pairingKeys,
                        additional.initiatorIntAuth, intAuthR, sharedSecret)) {
                    PairingTransportResult<IkeV2SessionCrypto.IkeAuthResponse> accepted = exchangePairingIke(
                            cid, cursor, auth.request(random), "OPTICAL IKE_AUTH", PAIRING_IKE_TIMEOUT_MS, auth::acceptResponse);
                    if (!auth.authenticated()) throw new HostException("Optical peer authentication incomplete");
                    cursor = accepted.cursor;
                }
                log("OPTICAL IKE_AUTH PASS: responder PSK AUTH verified; MID=2; bytes logged=false.");
                exchangePairingPrivateNotifies(cid, cursor, random, pairingKeys, authData,
                        OpticalPskSession.NEXT_INFORMATIONAL_MESSAGE_ID);
            } finally { wipe(initiator.x448PrivateKey); }
        } finally {
            wipe(sharedSecret); wipe(authData); wipe(intAuthR); wipe(kemCiphertext);
            if (initialKeys != null) initialKeys.destroy();
            if (pairingKeys != null) pairingKeys.destroy();
            if (additional != null) wipe(additional.initiatorIntAuth);
            opticalPairing.close();
        }
    }

    private void probePairingSession(
            int responderLocalCid,
            PinTransportState pinState,
            AppleWatchPairingCrypto.PairingSecrets secrets)
            throws Exception {
        SecureRandom random = new SecureRandom();
        PairingTransportCursor cursor =
                new PairingTransportCursor(
                        pinState.nextOutboundTxSequence,
                        pinState.nextExpectedWatchTxSequence);
        IkeV2Codec.InitiatorState initiator =
                IkeV2Codec.createPairingSaInit(random);
        log("PAIRING IKE CONFIG: securePassword=SPAKE2+/0x2AF9; "
                + "PPK=mandatory/fixed-ID-type-1; "
                + "ENCR=AES-GCM-16/256; PRF=HMAC-SHA2-512; "
                + "DH=X448; AdditionalKE1=ML-KEM-1024; "
                + "requestChildless=true.");
        PairingTransportResult<byte[]> saInitResult =
                exchangePairingIke(
                        responderLocalCid,
                        cursor,
                        List.of(initiator.ikePacket),
                        "PAIRING IKE_SA_INIT",
                        PAIRING_IKE_TIMEOUT_MS,
                        packet -> {
                            if (!hasInitiatorSpi(
                                    packet,
                                    initiator.initiatorSpi)) {
                                log("PAIRING IKE_SA_INIT STALE DROP: "
                                        + "response initiator SPI does "
                                        + "not match current request; "
                                        + "packet bytes logged=false.");
                                return null;
                            }
                            return packet;
                        });
        cursor = saInitResult.cursor;

        IkeV2SessionCrypto.ControlSaInitResponse response;
        IkeV2SessionCrypto.IkeSaKeys initialKeys;
        try {
            response =
                    IkeV2SessionCrypto
                            .parsePairingSaInitResponse(
                                    saInitResult.value,
                                    initiator.initiatorSpi);
            initialKeys =
                    IkeV2SessionCrypto.deriveInitialKeys(
                            initiator,
                            response);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Pairing IKE_SA_INIT validation failed: "
                            + error.getMessage(),
                    error);
        }
        log("PAIRING IKE_SA_INIT PASS: responderSPI="
                + HciCodec.toHex(response.responderSpi)
                + "; Watch selected SPAKE2+, USE_PPK, childless, "
                + "and IKE_INTERMEDIATE; key bytes logged=false.");

        IkeV2SessionCrypto.AdditionalKeRequest additionalRequest;
        try {
            additionalRequest =
                    IkeV2SessionCrypto
                            .createAdditionalKeRequest(
                                    random,
                                    initiator,
                                    response,
                                    initialKeys);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Cannot create pairing ML-KEM-1024 request: "
                            + error.getMessage(),
                    error);
        }
        IkeV2SessionCrypto.IntermediateResponseAccumulator
                intermediateAccumulator =
                new IkeV2SessionCrypto
                        .IntermediateResponseAccumulator(
                                initialKeys);
        PairingTransportResult<byte[]> intermediateResult =
                exchangePairingIke(
                        responderLocalCid,
                        cursor,
                        additionalRequest.ikePackets,
                        "PAIRING IKE_INTERMEDIATE",
                        PAIRING_IKE_TIMEOUT_MS,
                        intermediateAccumulator::accept);
        cursor = intermediateResult.cursor;

        IkeV2SessionCrypto.AdditionalKeResult additionalResult;
        IkeV2SessionCrypto.IkeSaKeys ppkKeys;
        try {
            additionalResult =
                    IkeV2SessionCrypto
                            .completeAdditionalKeyExchange(
                                    additionalRequest,
                                    intermediateResult.value,
                                    initialKeys);
            ppkKeys =
                    IkeV2SessionCrypto
                            .applyMandatoryPairingPpk(
                                    additionalResult.updatedKeys,
                                    secrets.ppk);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Pairing ML-KEM/PPK key schedule failed: "
                            + error.getMessage(),
                    error);
        }
        byte[] responderIntAuth =
                intermediateAccumulator.responderIntAuth();
        log("PAIRING IKE_INTERMEDIATE PASS: ML-KEM ciphertext="
                + intermediateResult.value.length
                + " bytes; mandatory PPK transformed "
                + "SK_d/SK_pi/SK_pr; SK_ei/SK_er unchanged; "
                + "secret/key bytes logged=false.");

        IkeV2SessionCrypto.PairingGspmInitiator gspm = null;
        try {
            gspm =
                    IkeV2SessionCrypto
                            .createPairingGspmInitiator(
                                    random,
                                    initiator,
                                    response,
                                    ppkKeys,
                                    additionalRequest
                                            .initiatorIntAuth,
                                    responderIntAuth,
                                    secrets.sharedSecret);

            IkeV2SessionCrypto.PairingExchangeRequest first =
                    gspm.createFirstRequest(random);
            log("PAIRING GSPM TX1: MID=2 payloads="
                    + "[IDi(NULL/physical-device),INITIAL_CONTACT,"
                    + "IDr(saltedPin),GSPM-X,"
                    + "PPK_IDENTITY(type=1)]; "
                    + "NO_PPK_AUTH=false(mandatory PPK); "
                    + "X="
                    + first.gspmDataLength
                    + " bytes; encryptedPackets="
                    + first.ikePackets.size()
                    + "; PIN/key/auth bytes logged=false.");
            PairingTransportResult<byte[]> firstResult =
                    exchangePairingIke(
                            responderLocalCid,
                            cursor,
                            first.ikePackets,
                            "PAIRING GSPM-X",
                            PAIRING_IKE_TIMEOUT_MS,
                            packet -> packet);
            cursor = firstResult.cursor;

            IkeV2SessionCrypto.PairingExchangeRequest second =
                    gspm
                            .acceptFirstResponseAndCreateSecondRequest(
                                    random,
                                    firstResult.value);
            log("PAIRING GSPM RX1/TX2 PASS: Watch IDr and "
                    + "SPAKE2+ Y authenticated structurally; "
                    + "first-response PPK identity is optional; "
                    + "MID=3 Prover confirmation="
                    + second.gspmDataLength
                    + " bytes; confirmation bytes logged=false.");
            PairingTransportResult<byte[]> secondResult =
                    exchangePairingIke(
                            responderLocalCid,
                            cursor,
                            second.ikePackets,
                            "PAIRING GSPM-CONFIRM-P",
                            PAIRING_IKE_TIMEOUT_MS,
                            packet -> packet);
            cursor = secondResult.cursor;

            IkeV2SessionCrypto.PairingExchangeRequest finalRequest =
                    gspm
                            .acceptSecondResponseAndCreateFinalAuthRequest(
                                    random,
                                    secondResult.value);
            log("PAIRING GSPM RX2/TX3 PASS: Watch confirmation "
                    + "verified in constant time; MID=4 "
                    + "AUTH(method=12)+PPK_IDENTITY(type=1), "
                    + "authData="
                    + finalRequest.authenticationDataLength
                    + " bytes; bytes logged=false.");
            PairingTransportResult<
                    IkeV2SessionCrypto.PairingAuthResponse>
                    finalResult =
                    exchangePairingIke(
                            responderLocalCid,
                            cursor,
                            finalRequest.ikePackets,
                            "PAIRING GSPM-AUTH",
                            PAIRING_IKE_TIMEOUT_MS,
                            gspm::acceptFinalAuthResponse);
            cursor = finalResult.cursor;
            log("PAIRING IKE_AUTH PASS: responder secure-password "
                    + "AUTH verified; payloads="
                    + formatHexList(
                            finalResult.value.payloadTypes)
                    + " notifies="
                    + formatHexList(
                            finalResult.value.notifyTypes)
                    + " authData="
                    + finalResult.value
                            .authenticationDataLength
                    + " bytes; nextTxSeq="
                    + cursor.nextOutboundTxSequence
                    + " nextWatchTxSeq="
                    + cursor.nextExpectedWatchTxSequence
                    + ".");

            cursor = exchangePairingPrivateNotifies(
                    responderLocalCid,
                    cursor,
                    random,
                    ppkKeys,
                    secrets.authenticationData);
            log("PAIRING PRIVATE-NOTIFIES PASS: authenticated "
                    + "bidirectional Apple OOB/NetworkRelay exchange "
                    + "and Watch request acknowledgement complete; "
                    + "OOB/private/key bytes logged=false; nextTxSeq="
                    + cursor.nextOutboundTxSequence
                    + " nextWatchTxSeq="
                    + cursor.nextExpectedWatchTxSequence
                    + ".");
        } finally {
            if (gspm != null) {
                gspm.destroy();
            }
            Arrays.fill(responderIntAuth, (byte) 0);
            Arrays.fill(
                    intermediateResult.value,
                    (byte) 0);
        }
    }

    private PairingTransportCursor
            exchangePairingPrivateNotifies(
            int responderLocalCid,
            PairingTransportCursor cursor,
            SecureRandom random,
        IkeV2SessionCrypto.IkeSaKeys ppkKeys,
            byte[] pendingIdsAuthData)
            throws Exception {
        return exchangePairingPrivateNotifies(responderLocalCid, cursor, random, ppkKeys,
                pendingIdsAuthData, IkeV2SessionCrypto.PAIRING_NOTIFIES_MESSAGE_ID);
    }

    private PairingTransportCursor exchangePairingPrivateNotifies(
            int responderLocalCid, PairingTransportCursor cursor, SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys ppkKeys, byte[] pendingIdsAuthData, int messageId)
            throws Exception {
        BleSecureConnectionsCrypto.LocalOobMaterial
                localOob = null;
        AppleNetworkRelayPairingMaterial networkKeys =
                null;
        AppleNetworkRelayInnerAddresses addresses =
                null;
        ApplePairingNotifyPayloads.LocalBatch localBatch =
                null;
        List<ApplePairingNotifyPayloads.PrivateNotify>
                localNotifies = null;
        IkeV2SessionCrypto.PairingPrivateNotifyResponse
                response = null;
        List<ApplePairingNotifyPayloads.PrivateNotify>
                peerNotifies = null;
        ApplePairingNotifyPayloads.PeerBatch peerBatch =
                null;
        PairingSessionRecord pairingSessionRecord =
                null;
        byte[] pairingGeneration = null;
        String localBluetoothCbUuid =
                UUID.randomUUID().toString();
        String localNrUuid =
                UUID.randomUUID().toString();
        String localIdsDeviceUuid =
                requireAppIdsDeviceIdentifier();
        try {
            localOob =
                    BleSecureConnectionsCrypto
                            .generateLocalOob(random);
            networkKeys =
                    AppleNetworkRelayPairingMaterial
                            .generate(random);
            addresses =
                    AppleNetworkRelayInnerAddresses
                            .generate(random);
            localBatch =
                    ApplePairingNotifyPayloads
                            .createLocalBatch(
                                    localOob,
                                    networkKeys,
                                    addresses,
                                    IosCompanionProfile26_6
                                            .DEVICE_NAME,
                                    IosCompanionProfile26_6
                                            .PRODUCT_BUILD_VERSION,
                                    IosCompanionProfile26_6.wireIdsDeviceIdentifier(localIdsDeviceUuid));
            localNotifies = localBatch.notifies();
            IkeV2SessionCrypto.PairingPrivateNotifyRequest
                    request =
                    IkeV2SessionCrypto
                            .createPairingPrivateNotifyRequest(
                                    random,
                                    ppkKeys,
                                    localNotifies, messageId);
            log("PAIRING PRIVATE-NOTIFIES TX: MID=" + messageId + " types="
                    + formatHexList(request.notifyTypes)
                    + " lengths="
                    + request.dataLengths
                    + " encryptedPackets="
                    + request.ikePackets.size()
                    + "; OOB/private/key bytes logged=false.");

            IkeV2SessionCrypto
                    .PairingPrivateNotifyResponseAccumulator
                    accumulator =
                    new IkeV2SessionCrypto
                            .PairingPrivateNotifyResponseAccumulator(
                                    ppkKeys, messageId);
            PairingTransportResult<
                    IkeV2SessionCrypto
                            .PairingPrivateNotifyResponse>
                    notifyResult =
                    exchangePairingIke(
                            responderLocalCid,
                            cursor,
                            request.ikePackets,
                            "PAIRING PRIVATE-NOTIFIES",
                            PAIRING_IKE_TIMEOUT_MS,
                            accumulator::accept);
            cursor = notifyResult.cursor;
            response = notifyResult.value;
            log("PAIRING PRIVATE-NOTIFIES RX: Watch request MID="
                    + response.peerRequestMessageId
                    + " types="
                    + formatHexList(
                            response.notifyTypes())
                    + " lengths="
                    + response.dataLengths()
                    + "; companion request ACK="
                    + accumulator.requestAcknowledged()
                    + "; payload bytes logged=false.");

            peerNotifies = response.notifies();
            peerBatch =
                    ApplePairingNotifyPayloads
                            .parsePeerBatch(peerNotifies);
            log("PAIRING PRIVATE-NOTIFIES VALIDATED: protocol="
                    + peerBatch.protocolVersion
                    + " deviceNameChars="
                    + peerBatch.deviceName.length()
                    + " buildChars="
                    + peerBatch.buildVersion.length()
                    + " deviceType="
                    + peerBatch.deviceType
                    + " alwaysOnWifi="
                    + peerBatch.alwaysOnWifi
                    + " IDS="
                    + (peerBatch.idsDeviceId != null)
                    + "; LE-SC OOB and NetworkRelay layouts valid.");

            pairingGeneration = new byte[16];
            random.nextBytes(pairingGeneration);
            pairingSessionRecord =
                    PairingSessionRecord.createPreSmp(
                            pairingGeneration,
                            pendingIdsAuthData,
                            localOob,
                            networkKeys,
                            addresses,
                            peerBatch,
                            activeWatchSetupMetadata == null ? null : activeWatchSetupMetadata.productType(),
                            null,
                            localBluetoothCbUuid,
                            localNrUuid,
                            localIdsDeviceUuid);
            persistPairingSessionOrFail(
                    pairingSessionRecord);
            log("PAIRING SESSION CHECKPOINT PASS: generation "
                    + "and long-lived NetworkRelay material are "
                    + "atomically sealed before Watch ACK/SMP; "
                    + "secret bytes logged=false.");

            List<byte[]> acknowledgementPackets =
                    IkeV2SessionCrypto
                            .createWatchInformationalAcknowledgement(
                                    random,
                                    ppkKeys,
                                    response.peerRequestMessageId);
            cursor = sendPairingIkeOneWayAndAwaitAck(
                    responderLocalCid,
                    cursor,
                    acknowledgementPackets,
                    "PAIRING PRIVATE-NOTIFIES WATCH-REQUEST ACK",
                    PAIRING_IKE_ACK_TIMEOUT_MS);
            completeLeSecureConnectionsSmp(
                    localOob,
                    peerBatch,
                    pairingSessionRecord);
            completeModernNormalRegistration(
                    HciCodec.BT_CL_CURRENT_VERSION);
            return cursor;
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Pairing private-notify exchange failed: "
                            + error.getMessage(),
                    error);
        } finally {
            if (pairingSessionRecord != null) {
                pairingSessionRecord.destroy();
            }
            wipe(pairingGeneration);
            if (peerBatch != null) {
                peerBatch.destroy();
            }
            destroyPrivateNotifies(peerNotifies);
            if (response != null) {
                response.destroy();
            }
            destroyPrivateNotifies(localNotifies);
            if (localBatch != null) {
                localBatch.destroy();
            }
            if (addresses != null) {
                addresses.destroy();
            }
            if (networkKeys != null) {
                networkKeys.destroy();
            }
            if (localOob != null) {
                localOob.destroy();
            }
        }
    }

    private void completeLeSecureConnectionsSmp(
            BleSecureConnectionsCrypto.LocalOobMaterial
                    localOob,
            ApplePairingNotifyPayloads.PeerBatch
                    peerBatch,
            PairingSessionRecord pairingSessionRecord)
            throws Exception {
        if (localOob == null
                || peerBatch == null
                || pairingSessionRecord == null
                || localIdentity == null
                || peerConnectionAddress == null
                || peerConnectionAddress.length != 6
                || (peerConnectionAddressType != 0
                && peerConnectionAddressType != 1)) {
            throw new HostException(
                    "SMP address/OOB state is incomplete");
        }

        AppleLeScOobData localOobData = null;
        AppleLeScOobData peerOobData = null;
        BleSecureConnectionsCrypto.F5Result f5Result =
                null;
        byte[] localOobSerialized = null;
        byte[] localIdentityAddress = null;
        byte[] peerOobSerialized = null;
        byte[] localPublicKey = null;
        byte[] peerPublicKey = null;
        byte[] peerPublicX = null;
        byte[] peerOobRandomizer = null;
        byte[] peerOobConfirmation = null;
        byte[] expectedPeerOobConfirmation = null;
        byte[] dhKey = null;
        byte[] localNonce = null;
        byte[] peerNonce = null;
        byte[] localTypedAddress = null;
        byte[] peerTypedAddress = null;
        byte[] macKey = null;
        byte[] longTermKey = null;
        byte[] localOobRandomizer = null;
        byte[] localIoCapability = null;
        byte[] peerIoCapability = null;
        byte[] localDhKeyCheck = null;
        byte[] peerDhKeyCheck = null;
        byte[] expectedPeerDhKeyCheck = null;
        byte[] peerIdentityResolvingKey = null;
        byte[] localIdentityResolvingKey = null;
        byte[] bondSecretRecord = null;
        byte[] responsePdu = null;
        byte[] peerPublicKeyPdu = null;
        byte[] peerNoncePdu = null;
        byte[] peerDhKeyCheckPdu = null;
        byte[] peerIdentityPdu = null;
        byte[] peerIdentityAddressPdu = null;
        PairingSessionRecord bondedSessionRecord =
                null;
        try {
            localIdentityAddress = localIdentity.address();
            localOobSerialized =
                    localOob.appleOobData();
            localOobData =
                    AppleLeScOobData.parse(
                            localOobSerialized);
            peerOobSerialized =
                    peerBatch.appleOobData();
            peerOobData =
                    AppleLeScOobData.parse(
                            peerOobSerialized);

            BluetoothSmpCodec.PairingFeatures request =
                    BluetoothSmpCodec
                            .createPairingRequest();
            log(String.format(
                    Locale.US,
                    "SMP TX: Pairing Request io=0x%02X "
                            + "oob=%d auth=0x%02X keySize=%d "
                            + "initiatorKeys=0x%02X "
                            + "responderKeys=0x%02X.",
                    request.ioCapability,
                    request.oobDataFlag,
                    request.authenticationRequirements,
                    request.maximumEncryptionKeySize,
                    request.initiatorKeyDistribution,
                    request.responderKeyDistribution));
            sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    request.encode(),
                    "SMP PAIRING REQUEST");
            responsePdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .PAIRING_RESPONSE,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Pairing Response");
            BluetoothSmpCodec.PairingFeatures response =
                    BluetoothSmpCodec
                            .parsePairingResponse(
                                    responsePdu);
            log(String.format(
                    Locale.US,
                    "SMP RX PASS: Pairing Response io=0x%02X "
                            + "oob=%d auth=0x%02X keySize=%d "
                            + "initiatorKeys=0x%02X "
                            + "responderKeys=0x%02X.",
                    response.ioCapability,
                    response.oobDataFlag,
                    response.authenticationRequirements,
                    response.maximumEncryptionKeySize,
                    response.initiatorKeyDistribution,
                    response.responderKeyDistribution));
            if (response.oobDataFlag
                    != BluetoothSmpCodec
                    .OOB_DATA_PRESENT
                    || (response
                    .authenticationRequirements
                    & BluetoothSmpCodec
                    .AUTH_SECURE_CONNECTIONS) == 0
                    || (response
                    .authenticationRequirements
                    & BluetoothSmpCodec
                    .AUTH_BONDING) == 0
                    || response.maximumEncryptionKeySize
                    != BluetoothSmpCodec
                    .MAXIMUM_ENCRYPTION_KEY_SIZE
                    || (response
                    .initiatorKeyDistribution
                    & BluetoothSmpCodec
                    .KEY_DISTRIBUTION_IDENTITY) == 0
                    || (response
                    .responderKeyDistribution
                    & BluetoothSmpCodec
                    .KEY_DISTRIBUTION_IDENTITY) == 0) {
                throw new HostException(
                        "Watch did not negotiate the required "
                                + "mutual-OOB SC identity bond");
            }

            localPublicKey =
                    localOob.publicKeyForSmp();
            sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .pairingPublicKey(
                                    localPublicKey),
                    "SMP PAIRING PUBLIC KEY");
            peerPublicKeyPdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .PAIRING_PUBLIC_KEY,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Pairing Public Key");
            peerPublicKey =
                    BluetoothSmpCodec
                            .parsePairingPublicKey(
                                    peerPublicKeyPdu);
            peerPublicX =
                    Arrays.copyOfRange(
                            peerPublicKey,
                            0,
                            BleSecureConnectionsCrypto
                                    .P256_VALUE_LENGTH);
            peerOobRandomizer =
                    peerOobData.randomizerForAndroid();
            peerOobConfirmation =
                    peerOobData.confirmationForAndroid();
            expectedPeerOobConfirmation =
                    BleSecureConnectionsCrypto.f4(
                            peerPublicX,
                            peerPublicX,
                            peerOobRandomizer,
                            0);
            if (!MessageDigest.isEqual(
                    peerOobConfirmation,
                    expectedPeerOobConfirmation)) {
                sendL2cap(
                        BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec
                                .pairingFailed(
                                        BluetoothSmpCodec
                                                .FAILURE_CONFIRM_VALUE),
                        "SMP PEER OOB CONFIRMATION FAILURE");
                throw new HostException(
                        "Watch LE-SC OOB commitment "
                                + "does not match its SMP public key");
            }
            log("SMP OOB COMMITMENT PASS: Watch C547 "
                    + "confirmation matches Watch SMP P-256 "
                    + "public key; OOB/public-key bytes "
                    + "logged=false.");

            dhKey =
                    localOob.calculateDhKey(
                            peerPublicKey);
            localNonce =
                    new byte[
                            BleSecureConnectionsCrypto
                                    .AES_VALUE_LENGTH];
            new SecureRandom().nextBytes(localNonce);
            sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .pairingRandom(
                                    localNonce),
                    "SMP PAIRING RANDOM");
            peerNoncePdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .PAIRING_RANDOM,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Pairing Random");
            peerNonce =
                    BluetoothSmpCodec
                            .parsePairingRandom(
                                    peerNoncePdu);
            localTypedAddress =
                    typedAddress(
                            localIdentityAddress,
                            localIdentity.addressType());
            peerTypedAddress =
                    typedAddress(
                            peerConnectionAddress,
                            peerConnectionAddressType);
            f5Result =
                    BleSecureConnectionsCrypto.f5(
                            dhKey,
                            localNonce,
                            peerNonce,
                            localTypedAddress,
                            peerTypedAddress);
            macKey = f5Result.macKey();
            longTermKey =
                    f5Result.longTermKey();
            activeLongTermKey = longTermKey.clone();
            localOobRandomizer =
                    localOobData.randomizerForAndroid();
            localIoCapability =
                    new byte[]{
                            (byte) request.ioCapability,
                            (byte) request.oobDataFlag,
                            (byte) request
                                    .authenticationRequirements
                    };
            peerIoCapability =
                    new byte[]{
                            (byte) response.ioCapability,
                            (byte) response.oobDataFlag,
                            (byte) response
                                    .authenticationRequirements
                    };
            localDhKeyCheck =
                    BleSecureConnectionsCrypto.f6(
                            macKey,
                            localNonce,
                            peerNonce,
                            peerOobRandomizer,
                            localIoCapability,
                            localTypedAddress,
                            peerTypedAddress);
            sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .pairingDhKeyCheck(
                                    localDhKeyCheck),
                    "SMP PAIRING DHKEY CHECK");
            peerDhKeyCheckPdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .PAIRING_DHKEY_CHECK,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Pairing DHKey Check");
            peerDhKeyCheck =
                    BluetoothSmpCodec
                            .parsePairingDhKeyCheck(
                                    peerDhKeyCheckPdu);
            expectedPeerDhKeyCheck =
                    BleSecureConnectionsCrypto.f6(
                            macKey,
                            peerNonce,
                            localNonce,
                            localOobRandomizer,
                            peerIoCapability,
                            peerTypedAddress,
                            localTypedAddress);
            if (!MessageDigest.isEqual(
                    peerDhKeyCheck,
                    expectedPeerDhKeyCheck)) {
                throw new HostException(
                        "Watch LE-SC responder DHKey Check "
                                + "authentication failed");
            }
            log("SMP DHKEY CHECK PASS: fresh Na/Nb and "
                    + "both OOB randomizers authenticated; "
                    + "DHKey/MacKey/LTK/check bytes "
                    + "logged=false.");

            startLeEncryption(longTermKey);
            log("SMP LINK ENCRYPTION PASS: controller "
                    + "accepted SC LTK with RAND=0/EDIV=0; "
                    + "LTK bytes logged=false.");

            peerIdentityPdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .IDENTITY_INFORMATION,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Identity Information");
            peerIdentityResolvingKey =
                    BluetoothSmpCodec
                            .parseIdentityInformation(
                                    peerIdentityPdu);
            peerIdentityAddressPdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .IDENTITY_ADDRESS_INFORMATION,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Identity Address Information");
            BluetoothSmpCodec.IdentityAddress
                    peerIdentity =
                    BluetoothSmpCodec
                            .parseIdentityAddressInformation(
                                    peerIdentityAddressPdu);
            localIdentityResolvingKey =
                    localIdentity.irk();

            bondSecretRecord =
                    BluetoothBondSecretRecord.encode(
                            localIdentity.addressType(),
                            localIdentityAddress,
                            peerConnectionAddressType,
                            peerConnectionAddress,
                            peerIdentity.addressType,
                            peerIdentity.address,
                            longTermKey,
                            localIdentityResolvingKey,
                            peerIdentityResolvingKey,
                            response
                                    .maximumEncryptionKeySize);
            try {
                bondedSessionRecord =
                        pairingSessionRecord
                                .withBluetoothBond(
                                        bondSecretRecord);
                persistPairingSessionOrFail(
                        bondedSessionRecord);
                persistBondSecretOrFail(
                        bondSecretRecord);
            } catch (Exception error) {
                sendL2cap(
                        BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec
                                .pairingFailed(
                                        BluetoothSmpCodec
                                                .FAILURE_UNSPECIFIED_REASON),
                        "SMP BOND STORE FAILURE ABORT");
                throw error;
            }
            log("SMP SESSION COMMIT PASS: durable record now "
                    + "contains the Bluetooth bond and omits both "
                    + "pending C547 objects before local identity "
                    + "distribution; pending IDS auth remains sealed.");

            sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .identityInformation(
                                    localIdentityResolvingKey),
                    "SMP LOCAL IDENTITY INFORMATION");
            sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .identityAddressInformation(
                                    localIdentity.addressType(),
                                    localIdentityAddress),
                    "SMP LOCAL IDENTITY ADDRESS INFORMATION");
            log("SMP LOCAL IDENTITY TX PASS: both local identity "
                    + "PDUs queued on the encrypted ACL; negotiated "
                    + "key distribution=0x03/0x03; Watch completion "
                    + "is not assumed until the post-SMP transition; "
                    + "IRK/LTK/address bytes logged=false.");
            if (activePairingSessionRecord != null) {
                activePairingSessionRecord.destroy();
                activePairingSessionRecord = null;
            }
            activePairingSessionRecord =
                    bondedSessionRecord;
            bondedSessionRecord = null;
            modernRegistrationDeadlineMs =
                    BridgeClock.elapsedRealtime()
                            + MODERN_REGISTRATION_TIMEOUT_MS;
            log("MODERN REGISTRATION WATCHDOG: local deadline="
                    + MODERN_REGISTRATION_TIMEOUT_MS
                    + " ms from completed SMP bond; same encrypted "
                    + "ACL retained.");
        } finally {
            if (bondedSessionRecord != null) {
                bondedSessionRecord.destroy();
            }
            if (f5Result != null) {
                f5Result.destroy();
            }
            if (localOobData != null) {
                localOobData.destroy();
            }
            if (peerOobData != null) {
                peerOobData.destroy();
            }
            wipe(localOobSerialized);
            wipe(localIdentityAddress);
            wipe(peerOobSerialized);
            wipe(localPublicKey);
            wipe(peerPublicKey);
            wipe(peerPublicX);
            wipe(peerOobRandomizer);
            wipe(peerOobConfirmation);
            wipe(expectedPeerOobConfirmation);
            wipe(dhKey);
            wipe(localNonce);
            wipe(peerNonce);
            wipe(localTypedAddress);
            wipe(peerTypedAddress);
            wipe(macKey);
            wipe(longTermKey);
            wipe(localOobRandomizer);
            wipe(localIoCapability);
            wipe(peerIoCapability);
            wipe(localDhKeyCheck);
            wipe(peerDhKeyCheck);
            wipe(expectedPeerDhKeyCheck);
            wipe(peerIdentityResolvingKey);
            wipe(localIdentityResolvingKey);
            wipe(bondSecretRecord);
            wipe(responsePdu);
            wipe(peerPublicKeyPdu);
            wipe(peerNoncePdu);
            wipe(peerDhKeyCheckPdu);
            wipe(peerIdentityPdu);
            wipe(peerIdentityAddressPdu);
        }
    }

    private void completeModernNormalRegistration(
            int peerVersion)
            throws Exception {
        if (activePairingSessionRecord == null
                || activePairingSessionRecord.state()
                != PairingSessionRecord.DurableState
                .SMP_BONDED_RAW
                || modernRegistrationDeadlineMs <= 0) {
            throw new HostException(
                    "Bonded pairing generation is unavailable for "
                            + "modern registration");
        }
        requireModernRegistrationTime(
                "BT_CL normal handoff");

        BtClNormalLinkHandoff normalHandoff =
                BtClNormalLinkHandoff.begin(
                        peerVersion,
                        true);
        NrLinkBluetoothPipeBootstrap bootstrap = null;
        NrLinkBluetoothPipeBootstrap.PreludeHandoff
                preludeHandoff = null;
        NormalLinkPipeSession pipe = null;
        try {
            byte[] serviceAdded =
                    normalHandoff.advertise();
            try {
                sendL2cap(
                        HciCodec.BT_CL_SIGNALING_CID,
                        serviceAdded,
                        "NORMAL BT_CL SERVICE_ADDED terminusLink");
            } finally {
                wipe(
                        serviceAdded);
            }

            HciCodec.BtClPdu commonServices =
                    awaitBtClOpcode(
                            HciCodec.BT_CL_SIGNALING_CID,
                            HciCodec.BT_CL_COMMON_SERVICES,
                            peerVersion,
                            normalHandoff);
            byte[] createChannel =
                    normalHandoff.acceptCommonServices(
                            commonServices);
            try {
                sendL2cap(
                        HciCodec.BT_CL_SIGNALING_CID,
                        createChannel,
                        "NORMAL BT_CL CREATE_CHANNEL "
                                + "localCID=0x0041 service=0x0002");
            } finally {
                wipe(
                        createChannel);
            }

            sendNormalTimeSync();

            HciCodec.BtClPdu acceptedChannel =
                    awaitBtClOpcode(
                            HciCodec.BT_CL_SIGNALING_CID,
                            HciCodec.BT_CL_ACCEPT_CHANNEL,
                            peerVersion);
            normalHandoff.acceptChannel(
                    acceptedChannel);
            int localCid =
                    normalHandoff.requesterLocalCid();
            int remoteCid =
                    normalHandoff.responderLocalCid();
            log(String.format(
                    Locale.US,
                    "NORMAL PIPE OPEN: localCID=0x%04X "
                            + "remoteCID=0x%04X ERTM=true FCS=%s; "
                            + "same encrypted ACL=true.",
                    localCid,
                    remoteCid,
                    normalHandoff.fcsEnabled()));

            bootstrap =
                    NrLinkBluetoothPipeBootstrap.begin(
                            normalHandoff,
                            new SecureRandom());
            byte[] localPrelude =
                    bootstrap.buildOutboundPreludeFrame();
            try {
                sendL2cap(
                        remoteCid,
                        localPrelude,
                        "NORMAL ERTM LOCAL PRELUDE");
            } finally {
                wipe(
                        localPrelude);
            }

            NrLinkBluetoothSession.Negotiated negotiated =
                    null;
            while (negotiated == null) {
                HciCodec.L2capPdu l2cap =
                        awaitNormalL2cap(
                                localCid,
                                modernRegistrationDeadlineMs,
                                "normal prelude");
                L2capErtmCodec.Frame decoded = null;
                try {
                    decoded =
                            L2capErtmCodec.decode(
                                    localCid,
                                    l2cap.payload,
                                    normalHandoff
                                            .fcsEnabled());
                    if (decoded.supervisory) {
                        try (L2capErtmSession.InboundResult
                                control =
                                bootstrap
                                        .acceptPeerControlFrame(
                                                l2cap.payload)) {
                            List<byte[]> immediate =
                                    control
                                            .immediateOutboundFrames();
                            sendNormalFrames(
                                    remoteCid,
                                    immediate,
                                    "NORMAL ERTM PRELUDE CONTROL");
                        }
                        continue;
                    }
                    negotiated =
                            bootstrap
                                    .acceptInboundPreludeFrame(
                                            l2cap.payload);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Normal prelude ERTM validation failed: "
                                    + error.getMessage(),
                            error);
                } finally {
                    if (decoded != null) {
                        wipe(
                                decoded.information);
                    }
                    wipe(
                            l2cap.payload);
                }
            }

            byte[] remotePreludeAck =
                    bootstrap
                            .buildRemotePreludeAcknowledgementFrame();
            try {
                sendL2cap(
                        remoteCid,
                        remotePreludeAck,
                        "NORMAL ERTM REMOTE PRELUDE RR");
            } finally {
                wipe(
                        remotePreludeAck);
            }

            PairingSessionRecord negotiatedRecord =
                    activePairingSessionRecord
                            .withPreludeNegotiated(
                                    negotiated.localRole);
            try {
                persistAndReplaceActivePairingSession(
                        negotiatedRecord);
                negotiatedRecord = null;
            } finally {
                if (negotiatedRecord != null) {
                    negotiatedRecord.destroy();
                }
            }
            log("NORMAL PRELUDE PASS: state=13, flags accepted, "
                    + "ordinary role="
                    + negotiated.localRole
                    + "; UUID bytes logged=false; durable role "
                    + "checkpointed.");

            preludeHandoff =
                    bootstrap.detach();
            pipe =
                    NormalLinkPipeSession
                            .resumeFromPairingRecord(
                                    new SecureRandom(),
                                    preludeHandoff,
                                    activePairingSessionRecord,
                                    IosCompanionProfile26_6
                                            .normalLink(
                                                    activePairingSessionRecord
                                                            .localIdsDeviceUuid()));
            preludeHandoff = null;

            pipe.enableLinkDirectorAnnouncements(LinkDirectorMessageCodec.sequenceSeed(
                    System.currentTimeMillis(), BridgeClock.elapsedRealtimeNanos()), ProtocolHost::log);

            List<byte[]> initialOrdinary =
                    pipe.continueAfterPrelude();
            sendNormalFrames(
                    remoteCid,
                    initialOrdinary,
                    "NORMAL CLASS-D START");

            while (BridgeClock.elapsedRealtime()
                    < modernRegistrationDeadlineMs) {
                checkpointOrdinaryRegistration(
                        pipe);
                if (pipe.registrationBarrierSatisfied()) {
                    log("NORMAL REGISTRATION BARRIER PASS: "
                            + "Class D connected=true, "
                            + "Class C connected=true; physical "
                            + "Watch7,5 confirmKeysForClass(D/C) "
                            + "prerequisites delivered.");
                    attachModernIdsSession(
                            pipe);
                    pipe = null;
                    reconnectRequested = false;
                    runModernIdsBootstrapAndHold(
                            localCid,
                            remoteCid);
                    if (reconnectRequested) {
                        reconnectPairedWatch();
                    }
                    return;
                }

                HciCodec.L2capPdu l2cap =
                        awaitNormalL2cap(
                                localCid,
                                modernRegistrationDeadlineMs,
                                "ordinary Class D/Class C");
                try (NormalLinkPipeSession.InboundResult
                        result =
                        pipe.acceptErtmFrame(
                                l2cap.payload)) {
                    if (result.ertmSupervisory()) {
                        log(String.format(
                                Locale.US,
                                "NORMAL ERTM RX: kind=S function=%d "
                                        + "reqSeq=%d informationBytes=%d; "
                                        + "packet bytes logged=false.",
                                result.ertmSupervisoryFunction(),
                                result.ertmRequestSequence(),
                                result.ertmInformationLength()));
                    } else {
                        log(String.format(
                                Locale.US,
                                "NORMAL ERTM RX: kind=I txSeq=%d "
                                        + "reqSeq=%d informationBytes=%d; "
                                        + "packet bytes logged=false.",
                                result.ertmTxSequence(),
                                result.ertmRequestSequence(),
                                result.ertmInformationLength()));
                    }
                    if (result.ikeRetransmissions() > 0) {
                        log("NORMAL IKE RETRANSMISSION: count="
                                + result.ikeRetransmissions()
                                + "; exact prior request matched and "
                                + "cached byte-identical response reused; "
                                + "packet bytes logged=false.");
                    }
                    if (result.ikeSaInitCapabilityFlags() != 0) {
                        int capabilities =
                                result.ikeSaInitCapabilityFlags();
                        log("NORMAL IKE_SA_INIT PEER CAPABILITIES: "
                                + "childless="
                                + ((capabilities
                                & OrdinaryIkeSaInit
                                .CAPABILITY_CHILDLESS) != 0)
                                + " usePpk="
                                + ((capabilities
                                & OrdinaryIkeSaInit
                                .CAPABILITY_USE_PPK) != 0)
                                + "; capability booleans only; "
                                + "packet/key bytes logged=false; "
                                + "pairing PPK reused=false.");
                    }
                    if (result.preSaStaleEspDrops() > 0) {
                        log("NORMAL PRE-SA STALE ESP DROP: count="
                                + result.preSaStaleEspDrops()
                                + "; responder has no fresh Child SA; "
                                + "link-local multicast "
                                + "packet authenticated=false, "
                                + "delivered=false; address/SPI/packet "
                                + "bytes logged=false.");
                    }
                    for (String observation
                            : result.linkDirectorObservations()) {
                        log("NORMAL LINK DIRECTOR RX: "
                                + observation
                                + "; protected request authenticated=true, "
                                + "empty INFORMATIONAL ACK queued=true, "
                                + "Class-C unlock changed=false.");
                    }
                    List<byte[]> immediate =
                            result.immediateErtmFrames();
                    sendNormalFrames(
                            remoteCid,
                            immediate,
                            "NORMAL ORDINARY IKE/ERTM");

                    List<NormalLinkPipeSession.DeliveredIp>
                            delivered =
                            result.deliveredIp();
                    try {
                        if (!delivered.isEmpty()) {
                            boolean registrationBarrier =
                                    pipe.registrationBarrierSatisfied();
                            if (registrationBarrier) {
                                log("NORMAL REGISTRATION BARRIER PASS: "
                                        + "Class D connected=true, "
                                        + "Class C connected=true; physical "
                                        + "Watch7,5 confirmKeysForClass(D/C) "
                                        + "prerequisites delivered (early IP).");
                                attachModernIdsSession(
                                        pipe);
                                pipe = null;
                                runModernIdsBootstrapWithInitialIp(
                                        localCid,
                                        remoteCid,
                                        delivered);
                                return;
                            }
                            if (!NormalLinkPipeSession
                                    .canDiscardBeforeIdsAttachment(
                                            delivered)) {
                                throw new HostException(
                                        "Normal IP payload is not eligible "
                                                + "for pre-IDS discard: "
                                                + "count="
                                                + delivered.size()
                                                + " first={"
                                                + delivered.get(0)
                                                .safeStructure()
                                                + "}");
                            }
                            log("NORMAL PRE-IDS CLASS-D LINK-LOCAL "
                                    + "MULTICAST DROP: "
                                    + "count="
                                    + delivered.size()
                                    + " first={"
                                    + delivered.get(0)
                                    .safeStructure()
                                    + "}; registration barrier="
                                    + registrationBarrier
                                    + "; ERTM acknowledged=true; "
                                    + "consumer attached=false; "
                                    + "payload destroyed=true.");
                        }
                    } finally {
                        if (pipe != null) {
                            for (NormalLinkPipeSession.DeliveredIp
                                    packet : delivered) {
                                packet.destroy();
                            }
                            delivered.clear();
                        }
                    }

                    List<byte[]> controls =
                            result.controlMessages();
                    try {
                        if (!controls.isEmpty()) {
                            log("NORMAL CONTROL RX: count="
                                    + controls.size()
                                    + "; bytes logged=false.");
                        }
                    } finally {
                        for (byte[] control : controls) {
                            wipe(
                                    control);
                        }
                        controls.clear();
                    }
                } catch (IllegalArgumentException
                        | IllegalStateException error) {
                    throw new HostException(
                            "Ordinary normal-link validation failed: "
                                    + error.getMessage(),
                            error);
                } finally {
                    wipe(
                            l2cap.payload);
                }
            }
            throw new HostException(
                    "Modern registration exceeded the "
                            + MODERN_REGISTRATION_TIMEOUT_MS
                            + " ms post-SMP local deadline");
        } finally {
            if (pipe != null) {
                pipe.close();
            }
            if (preludeHandoff != null) {
                preludeHandoff.close();
            }
            if (bootstrap != null) {
                bootstrap.close();
            }
        }
    }

    /**
     * Transfers the settled normal-link pipe into the long-lived IDS
     * bridge. Every address copy is wiped after the coordinator has
     * cloned it, and no identifier or key material is logged.
     */
    private void attachModernIdsSession(
            NormalLinkPipeSession pipe)
            throws HostException {
        if (pipe == null
                || activePairingSessionRecord == null) {
            throw new HostException(
                    "IDS normal-link ownership is inconsistent");
        }
        if (activeIdsBridge != null) {
            try {
                long lastSeq = activeIdsBridge.ids().sequenceAllocator().peek();
                NwServiceConnectorSequenceStore.save(NwServiceConnectorSequenceStore.reserveAfter(lastSeq));
            } catch (RuntimeException ignored) {
            }
            try {
                activeIdsBridge.close();
            } catch (Exception ignored) {
            }
            activeIdsBridge = null;
        }
        clearClockFaceDeltaOnDisconnect();
        if (pendingActivationPayload != null) {
            wipe(pendingActivationPayload);
            pendingActivationPayload = null;
        }
        if (pendingActivationResponse != null) {
            pendingActivationResponse.destroy();
            pendingActivationResponse = null;
        }
        activeIdsBootstrap = null;
        nanoRegistryProbe = new NanoRegistryReachabilityProbe();
        nssDiagnostics.reset();
        findMySession.reset();
        findMyPhone.reset(null);
        pendingPhoneReplies.clear();
        FIND_MY_PHONE_RESULTS.clear();
        sysdiagnoseInventory.reset();
        sysdiagnoseCollection.reset();
        if (idsDeviceInfoExchange != null) idsDeviceInfoExchange.close();
        String installedIdentifier = requireAppIdsDeviceIdentifier();
        if (!installedIdentifier.equalsIgnoreCase(activePairingSessionRecord.localIdsDeviceUuid())) {
            throw new HostException("Saved pairing belongs to a different IDS installation identity");
        }
        idsDeviceInfoExchange = new IdsDeviceInfoExchange(localIdsPublicBundle,
                installedIdentifier, activePairingSessionRecord.peerIdsDeviceId(),
                activePairingSessionRecord.hasExchangedIdsDeviceInfo());
        if (activeInitialSetup != null) activeInitialSetup.close();
        activeInitialSetup = null;
        operationalSnapshotPublished = false;
        healthPeerIdentityPublished = false;
        bulletinTransport.reset();
        earlyInitialEvents.forEach(IdsModernSessionCoordinator.SessionEvent::close);
        earlyInitialEvents.clear();
        pendingPostCommitSends.close();
        if (activationProxyWorker != null) activationProxyWorker.close();
        activationProxyWorker = null;
        activePostCommitCoordinator = null;
        activePostCommitAdapter = null;
        modernIdsReady = false;
        initialWifiSync.close();
        initialWifiSync = new InitialWifiSyncWorker(RootWifiNetworkReader::currentArchive);
        initialWifiMessageUuid = null;
        initialWifiReceiptLogged = false;
        initialWifiUnavailableLogged = false;
        readyToCommitIsPaired = false;
        initialPropertiesReceived = false;
        // Both readiness flags describe the IDS session being replaced, so a
        // stale true would let post-commit send before the new control Hello.
        idsBootstrapBarrierPassed = false;
        idsControlReadyObserved = false;
        neighborAdvertised = false;
        pairedKeyProbesStarted = false;
        idsControlRejectedByPolicy = false;
        activationPermitSentAtMs = -1L;
        activationPermitQueuedAtMs = -1L;
        activationPermitL2capFrames = 0;
        activationPermitAwaitingFlush = false;
        activationPermitResendCount = 0;
        watchDrivenActivationObserved = false;
        watchProxyActivationObserved = false;
        if (pendingWatchProxyActivation != null) {
            pendingWatchProxyActivation.close();
            pendingWatchProxyActivation = null;
        }

        if (activePairingSessionRecord.state().wireValue()
                < PairingSessionRecord.DurableState
                .CLASS_C_ESTABLISHED.wireValue()
                || !activePairingSessionRecord
                .addressesConfirmed()
                || !activePairingSessionRecord
                .idsAuthenticationAccepted()) {
            throw new HostException(
                    "IDS requires durable Class-D/Class-C addresses "
                            + "and local paired-device registration");
        }

        AppleNetworkRelayInnerAddresses addresses = null;
        byte[] localClassD = null;
        byte[] remoteClassD = null;
        byte[] localClassC = null;
        byte[] remoteClassC = null;
        IdsModernSessionCoordinator ids = null;
        NormalLinkIdsSessionBridge bridge = null;
        try {
            localClassD =
                    pipe.localClassD();
            remoteClassD =
                    pipe.remoteClassD();
            localClassC =
                    pipe.localClassC();
            remoteClassC =
                    pipe.remoteClassC();
            long clockSeed =
                    NwServiceConnectorSequenceAllocator
                            .appleInitialSeed(
                                    System.currentTimeMillis(),
                                    BridgeClock.elapsedRealtimeNanos());
            long persistedSeed =
                    NwServiceConnectorSequenceStore.load();
            long sequenceSeed =
                    NwServiceConnectorSequenceStore.chooseSeed(
                            clockSeed,
                            persistedSeed);
            NwServiceConnectorSequenceStore.save(
                    NwServiceConnectorSequenceStore.reserveAfter(sequenceSeed));
            log("NWSC SEQUENCE SEED: clock="
                    + Long.toUnsignedString(clockSeed)
                    + " persisted="
                    + Long.toUnsignedString(persistedSeed)
                    + " chosen="
                    + Long.toUnsignedString(sequenceSeed));
            byte[] localPrivD = null;
            byte[] remotePubD = null;
            byte[] localPrivC = null;
            byte[] remotePubC = null;
            if (activePairingSessionRecord != null) {
                AppleNetworkRelayPairingMaterial localMat =
                        activePairingSessionRecord.restoreLocalMaterial();
                if (localMat != null) {
                    try {
                        localPrivD = localMat.classDPrivateEd25519();
                        localPrivC = localMat.classCPrivateEd25519();
                    } finally {
                        localMat.destroy();
                    }
                }
                // NWSC creates ephemeral Ed25519 keypairs and discovers peer keys
                // dynamically via No-Op probes and Feedback frames. Seeding the static
                // NetworkRelay pairing material keys causes signature verification to
                // fail ('bad signature on incoming request' TCP RST). Keeping remotePubD
                // and remotePubC null allows dynamic discovery to run cleanly.
            }

            ids =
                    new IdsModernSessionCoordinator(
                            new SecureRandom(),
                            sequenceSeed,
                            localClassD,
                            remoteClassD,
                            localClassC,
                            remoteClassC,
                            localPrivD,
                            remotePubD,
                            localPrivC,
                            remotePubC,
                            activePairingSessionRecord
                                    .localIdsDeviceUuid(),
                            true);
            // A restarted process must not reuse the stale session's
            // 4-tuple: the Watch matches source port 1026 to its
            // still-ESTABLISHED flow and rejects the SYN by policy.
            ids.randomizePortCursor();
            List<IdsStaleFlowRecord> staleFlowSeeds =
                    IdsStaleFlowStore.load();
            byte[] storedLocalD = null;
            byte[] storedRemoteD = null;
            byte[] storedLocalC = null;
            byte[] storedRemoteC = null;
            try {
                storedLocalD = activePairingSessionRecord.copyConfirmedLocalAddress(true);
                storedRemoteD = activePairingSessionRecord.copyConfirmedRemoteAddress(true);
                storedLocalC = activePairingSessionRecord.copyConfirmedLocalAddress(false);
                storedRemoteC = activePairingSessionRecord.copyConfirmedRemoteAddress(false);
                List<IdsStaleFlowRecord> matchingSeeds = new ArrayList<>();
                for (IdsStaleFlowRecord seed : staleFlowSeeds) {
                    if (seed.matchesAddresses(localClassD, remoteClassD, localClassC, remoteClassC)
                            || seed.matchesAddresses(storedLocalD, storedRemoteD,
                                    storedLocalC, storedRemoteC)) matchingSeeds.add(seed);
                }
                ids.seedStaleFlows(matchingSeeds);
                log("STALE FLOW STORE: seeded " + matchingSeeds.size()
                        + "; excluded incompatible address quartets="
                        + (staleFlowSeeds.size() - matchingSeeds.size()) + ".");
                if (storedLocalD != null && storedRemoteD != null) {
                    // Live 0.2.301: arm stale-control adoption with the
                    // quartet saved with the pair. After an unclean
                    // restart the Watch keeps its old control connection
                    // ESTABLISHED and retransmits its bytes
                    // (4915x->61315); with no stored quartet the router
                    // withheld them ("stale control withheld") and the
                    // reconnect stalled — the new data SYN drew
                    // REJECTED_BY_POLICY (zombie-IDS, 03:23).
                    ids.setStaleFlowAddresses(
                            storedLocalD,
                            storedRemoteD);
                }
                if (!Arrays.equals(storedLocalD, localClassD)
                        || !Arrays.equals(storedRemoteD, remoteClassD)) {
                    log("IDS: this IKE assignment differs from the stored inner quartet; "
                            + "stored "
                            + hostPair(storedLocalD, storedRemoteD)
                            + " ike "
                            + hostPair(localClassD, remoteClassD)
                            + "; persisting the Watch assignment.");
                    AppleNetworkRelayInnerAddresses reconciled = null;
                    PairingSessionRecord updated = null;
                    boolean initiator = activePairingSessionRecord.localRole()
                            == NrLinkBluetoothPrelude.LocalRole.INITIATOR;
                    try {
                        reconciled = AppleNetworkRelayInnerAddresses.fromAuthoritative(
                                initiator ? localClassD : remoteClassD,
                                initiator ? remoteClassD : localClassD,
                                initiator ? localClassC : remoteClassC,
                                initiator ? remoteClassC : localClassC);
                        updated = activePairingSessionRecord.withReconciledQuartet(reconciled);
                        persistAndReplaceActivePairingSession(updated);
                        updated = null;
                    } catch (Exception failure) {
                        throw new IllegalStateException(
                                "Cannot persist the Watch address assignment",
                                failure);
                    } finally {
                        if (reconciled != null) {
                            reconciled.destroy();
                        }
                        if (updated != null) {
                            updated.destroy();
                        }
                    }
                }
            } finally {
                wipe(storedLocalD);
                wipe(storedRemoteD);
                wipe(storedLocalC);
                wipe(storedRemoteC);
            }
            try {
                NrOutgoingWireCapture capture = new NrOutgoingWireCapture(
                        BridgePaths.temporary());
                ids.setOutgoingFrameObserver((kind, topic, type, response, frame) -> {
                    String issue = capture.record(kind, topic, type, response, frame);
                    if (issue != null) log("NR WIRE CAPTURE DISABLED: " + issue);
                });
                log("NR WIRE CAPTURE: directory=" + capture.directory()
                        + "; owner-only, bounded; encoded Hello/NR frames before fragmentation; delivery not inferred.");
            } catch (Exception unavailable) {
                log("NR WIRE CAPTURE UNAVAILABLE: " + unavailable.getClass().getSimpleName()
                        + "; IDS connection proceeds normally.");
            }
            IdsBootstrapState bootstrapState =
                    new IdsBootstrapState();
            bridge =
                    new NormalLinkIdsSessionBridge(
                            pipe,
                            ids);
            ids = null;
            activeIdsBridge =
                    bridge;
            L2capErtmSession.diagnosticLogger =
                    ProtocolHost::log;
            NormalLinkPipeSession.diagnosticLogger =
                    ProtocolHost::log;
            NetworkRelayPacketCodec.diagnosticLogger =
                    ProtocolHost::log;
            IdsIpv6TcpRouter.diagnosticLogger =
                    ProtocolHost::log;
            Ipv6TcpStream.diagnosticLogger =
                    ProtocolHost::log;
            IdsPortMap.diagnosticLogger =
                    ProtocolHost::log;
            IdsServiceConnectorCoordinator.diagnosticLogger =
                    ProtocolHost::log;
            PairedSyncCodec.diagnosticLogger =
                    ProtocolHost::log;
            PbBridgeCodec.diagnosticLogger =
                    ProtocolHost::log;
            AppleWatchInitialSetupIdsAdapter.diagnosticLogger =
                    ProtocolHost::log;
            ActivationChallengeManager.attach(
                    line -> {
                        System.out.println(
                                line);
                        System.out.flush();
                    },
                    ProtocolHost::log);
            IdsModernSessionCoordinator.diagnosticLogger =
                    ProtocolHost::log;
            activeIdsBootstrap =
                    bootstrapState;
            bridge = null;
            log("IDS ATTACHED: settled normal-link ownership "
                    + "transferred; NWSC sequence seeded from Apple "
                    + "wall/continuous-clock formula; address/UUID "
                    + "bytes logged=false.");
        } catch (RuntimeException failure) {
            throw new HostException(
                    "Cannot attach IDS to the settled normal link: "
                            + failure.getMessage(),
                    failure);
        } finally {
            if (bridge != null) {
                bridge.close();
            } else if (ids != null) {
                ids.close();
            }
            wipe(
                    localClassD);
            wipe(
                    remoteClassD);
            wipe(
                    localClassC);
            wipe(
                    remoteClassC);
            if (addresses != null) {
                addresses.destroy();
            }
        }
    }

    private void runModernIdsBootstrapAndHold(
            int localCid,
            int remoteCid)
            throws Exception {
        if (activeIdsBridge == null) {
            throw new HostException(
                    "IDS bridge was not attached");
        }
        long bootstrapDeadline =
                BridgeClock.elapsedRealtime()
                        + IDS_BOOTSTRAP_TIMEOUT_MS;
        activeIdsBootstrap =
                new IdsBootstrapState();
        boolean isPairedReconnect = canResumeIdsData();
        log("IDS BOOTSTRAP MODE: " + (isPairedReconnect ? "resume negotiated data; fresh lane handshakes required" : "fresh control Hello")
                + " durable=" + activePairingSessionRecord.state());
        if (isPairedReconnect) {
            log("IDS: Class-D Setup with Hello (0.2.196 wake); NA after LDM; no ids-control SYN.");
        }
        sendNormalFrames(
                remoteCid,
                activeIdsBridge.pollLinkDirectorAnnouncements(
                        System.nanoTime() / 1_000_000L),
                "NORMAL LINK DIRECTOR AFTER IDS");
        executeIdsBootstrapAction(
                isPairedReconnect
                        ? activeIdsBootstrap.beginPairedListenImmediately()
                        : activeIdsBootstrap.begin(),
                remoteCid);
        long idsWaitDeadline = isPairedReconnect
                ? Long.MAX_VALUE
                : bootstrapDeadline;
        long controlSilenceDeadlineMs = -1L;
        while (!modernIdsReady) {
            if (isPairedReconnect
                    && neighborAdvertised
                    && !idsControlReadyObserved
                    && IdsPairedReconnectPolicy.allowControlSilenceBounce(
                            idsControlSilenceBounces,
                            idsControlRejectedByPolicy)) {
                if (controlSilenceDeadlineMs < 0L) {
                    controlSilenceDeadlineMs =
                            BridgeClock.elapsedRealtime()
                                    + IdsPairedReconnectPolicy.CONTROL_SILENCE_BOUNCE_MS;
                } else if (BridgeClock.elapsedRealtime()
                        >= controlSilenceDeadlineMs) {
                    bounceAclForIdsControlSilence();
                    return;
                }
            }
            long frameDeadline = idsWaitDeadline;
            if (isPairedReconnect && controlSilenceDeadlineMs > 0L) {
                frameDeadline = Math.min(
                        controlSilenceDeadlineMs,
                        BridgeClock.elapsedRealtime() + 500L);
            }
            try {
                acceptModernIdsFrame(
                        localCid,
                        remoteCid,
                        frameDeadline,
                        "IDS bootstrap");
            } catch (HostException disconnectError) {
                if (disconnectError.getMessage() != null
                        && disconnectError.getMessage().contains("Timed out awaiting")
                        && isPairedReconnect
                        && !modernIdsReady) {
                    continue;
                }
                if (tryReconnectAfterWatchDisconnect(
                        "IDS bootstrap",
                        disconnectError)) {
                    return;
                }
                throw disconnectError;
            }
        }
        if (!idsBootstrapBarrierPassed) {
            log("IDS BOOTSTRAP BARRIER PASS: current control Hello="
                    + idsControlReadyObserved + ", resumed=" + isPairedReconnect + ", "
                    + "NanoRegistry Class-D lane joined=true, "
                    + "NanoRegistry Class-C lane joined=true.");
            idsBootstrapBarrierPassed = true;
            startPostCommitIfAuthorized(
                    remoteCid);
        }
        log("PAIRED IDS LIVE HOLD: session ready; listening for incoming Watch IDS/activation frames...");
        while (true) {
            try {
                acceptModernIdsFrame(
                        localCid,
                        remoteCid,
                        Long.MAX_VALUE,
                        "live IDS hold");
            } catch (HostException disconnectError) {
                if (tryReconnectAfterWatchDisconnect(
                        "live IDS hold",
                        disconnectError)) {
                    return;
                }
                throw disconnectError;
            }
        }
    }

    /**
     * Begins post-commit setup once the IDS control channel can carry it.
     *
     * <p>The durable record is deliberately replayed from
     * {@code IS_PAIRED_COMMITTED}: a Watch whose buddy setup never received
     * the language, activation permit and paired-sync messages still needs
     * them, even though the local record already claims a later state.</p>
     */
    private void startPostCommitIfAuthorized(
            int remoteCid)
            throws Exception {
        if (operationalPairingId != null) {
            if (!operationalSnapshotPublished && activeIdsBridge != null
                    && idsControlReadyObserved && activeIdsBridge.dataLanesReadyForApplication()) {
                OperationalSessionPolicy.requireMatchingActivatedPair(activePairingSessionRecord, operationalPairingId);
                operationalSnapshotPublished = true;
                publishResumedPhoneRegistrySnapshot(remoteCid);
                UUID epoch = OPERATIONAL_REQUESTS.newEpoch();
                clockFaceObservationEpoch = epoch;
                findMyPhone.reset(epoch);
                pendingPhoneReplies.clear();
                FIND_MY_PHONE_RESULTS.clear();
                System.out.println("BRIDGE_TRANSPORT_V1:READY:" + epoch);
                System.out.flush();
                publishClockFaceObservation(epoch);
                try { publishPigmentMirror(); }
                catch (IOException invalidMirror) { log("PIGMENT MIRROR unavailable; no baseline seeded; values logged=false."); }
                try { publishMonogramMirror(); }
                catch (IOException invalidMirror) { log("MONOGRAM MIRROR unavailable; values logged=false."); }
                log("OPERATIONAL IDS READY: same activated pair; Setup/Albert/Buddy replay disabled.");
            }
            return;
        }
        if (!commitIsPairedAuthorized
                || activePostCommitCoordinator != null
                || activeIdsBridge == null
                || activePairingSessionRecord == null) {
            return;
        }
        if (!deviceInfoAllowsPostCommitResume()) return;
        if (activePairingSessionRecord.state().wireValue()
                < PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED.wireValue()) {
            return;
        }
        if (activePairingSessionRecord.state().wireValue()
                >= PairingSessionRecord.DurableState.OPERATIONAL_HEALTH_CONFIRMED.wireValue()) {
            if (!commitIsPairedAuthorized) {
                log("POST-COMMIT SKIPPED: Watch is already OPERATIONAL_HEALTH_CONFIRMED; operational hold active.");
                return;
            }
            log("POST-COMMIT RESUME: restoring completed record; use initial-sync redrive if the physical Watch remains in Setup.");
        }
        IdsModernSessionCoordinator.Snapshot ids =
                activeIdsBridge.idsSnapshot();
        boolean alreadyCommitted =
                activePairingSessionRecord.state().wireValue()
                        >= PairingSessionRecord.DurableState
                        .IS_PAIRED_COMMITTED.wireValue();
        boolean lanesReady = activeIdsBridge.dataLanesReadyForApplication()
                || (alreadyCommitted && ids.joinedDataCount > 0);
        boolean controlReady = idsControlReadyObserved || ids.peerHelloReceived
                || (canResumeIdsData() && idsBootstrapBarrierPassed && lanesReady);
        if ((!idsBootstrapBarrierPassed && !lanesReady)
                || !controlReady) {
            // The Watch resets any service connector opened before its IDS
            // control Hello, so sending now would only fill the parked queue.
            // The bootstrap barrier alone is not enough: it reports control
            // readiness without waiting for the peer Hello.
            return;
        }
        idsBootstrapBarrierPassed = true;
        if (!initialPropertiesReceived
                && !watchProxyActivationObserved
                && (!alreadyCommitted
                        || activePairingSessionRecord.state().wireValue()
                                < PairingSessionRecord.DurableState
                                .ACTIVATION_CONFIRMED.wireValue())) {
            // A committed-but-unactivated Watch only drives Albert
            // activation after the NanoRegistry Check/snapshot/property
            // handshake (live 0.2.166: paired reconnect without the
            // initial-setup replay got no ProxyActivation within 20 s).
            // Defer post-commit until the replay completes.
            // Live 0.2.190 exception: once the timesync answer lands the
            // Watch can drive ProxyActivation first; that request is
            // stashed and itself authorizes post-commit (see the
            // PBBridge interception in the IDS event loop).
            long nowMs = BridgeClock.elapsedRealtime();
            if (postCommitDeferredLogAtMs < 0
                    || nowMs - postCommitDeferredLogAtMs >= 5000L) {
                postCommitDeferredLogAtMs = nowMs;
                log("POST-COMMIT DEFERRED: activation is not confirmed; "
                        + "waiting for this-session Watch Class-C snapshot; durable="
                        + activePairingSessionRecord.state()
                        + "; restored READY_TO_COMMIT is not Albert evidence."
                        + " (throttled to 1/5s)");
            }
            return;
        }
        if (!controlReady
                || !lanesReady) {
            log("POST-COMMIT DEFERRED: hello="
                    + ids.peerHelloReceived
                    + " controlReadyObserved="
                    + idsControlReadyObserved
                    + " joinedData="
                    + ids.joinedDataCount
                    + " lanesReady="
                    + lanesReady
                    + "; CanBeginActivation stays off the wire until Class-D and Class-C NWSC are active.");
            return;
        }
        if (!idsDeviceInfoExchange.ready()) {
            log("POST-COMMIT IDS CREDENTIALS RESUME: saved mutual exchange retained; current authenticated Hello matches the same Watch and fresh data lanes are ready. Fresh command-12 response remains unobserved.");
        }
        log("COMMIT IS PAIRED AUTHORIZED: activating AppleWatchPostCommitCoordinator "
                + "and MobileActivationHttpProxy; starting post-commit setup & activation.");
        if (activePairingSessionRecord.state()
                == PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED) {
            PairingSessionRecord prepared = null;
            PairingSessionRecord committed = null;
            try {
                prepared = activePairingSessionRecord.isPairedCommitIntentPersisted()
                        ? null
                        : activePairingSessionRecord.prepareIsPairedCommit(true);
                PairingSessionRecord source = prepared != null ? prepared : activePairingSessionRecord;
                committed = source.confirmIsPairedCommit(true);
                persistAndReplaceActivePairingSession(committed);
                committed = null;
                log("POST-COMMIT: activePairingSessionRecord committed to IS_PAIRED_COMMITTED atomically.");
            } finally {
                if (prepared != null) {
                    prepared.destroy();
                }
                if (committed != null) {
                    committed.destroy();
                }
            }
        }
        MobileActivationHttpProxy.wireTap =
                new MobileActivationHttpProxy.WireTap() {
                    @Override
                    public void onWireRequest(
                            MobileActivationHttpProxy.RequestKind kind,
                            String method,
                            String url,
                            List<String> orderedHeaders,
                            byte[] body) {
                        String tag =
                                "outgoing-"
                                        + kind.name().toLowerCase(Locale.US);
                        StringBuilder text =
                                new StringBuilder(
                                        method
                                                + " "
                                                + url
                                                + "\n");
                        for (String header :
                                orderedHeaders) {
                            text.append(
                                    header)
                                    .append(
                                    '\n');
                        }
                        captureActivationArtifact(
                                tag + "-request-headers",
                                text.toString().getBytes(StandardCharsets.UTF_8));
                        captureActivationArtifact(
                                tag + "-request-body",
                                body);
                        log("ACTIVATION WIRE REQUEST: kind=" + kind
                                + " headers=" + orderedHeaders.size()
                                + " bodyBytes=" + (body == null ? 0 : body.length) + ".");
                    }

                    @Override
                    public void onWireResponse(
                            MobileActivationHttpProxy.RequestKind kind,
                            int statusCode,
                            String protocol,
                            List<String> orderedHeaders) {
                        log("ACTIVATION WIRE RESPONSE: kind=" + kind
                                + " status=" + statusCode
                                + " protocol=" + protocol
                                + " headerCount=" + orderedHeaders.size() + ".");
                    }
                };
        activeActivationProxy = new MobileActivationHttpProxy();
        activationProxyWorker = new ActivationProxyWorker(activeActivationProxy);
        activePostCommitCoordinator =
                new AppleWatchPostCommitCoordinator();
        int watchPairingVersion =
                activeWatchSetupMetadata != null
                        ? activeWatchSetupMetadata.pairingVersion
                        : activeIdsBridge.effectivePeerMaxPairingVersion();
        PairingSessionRecord.DurableState coordinatorStartState = activePairingSessionRecord.state();
        if (coordinatorStartState.wireValue() >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()
                && coordinatorStartState.wireValue() < PairingSessionRecord.DurableState.SETUP_COMPLETE.wireValue()) {
            // An unfinished chain replays from ACTIVATION_CONFIRMED so the
            // Watch re-receives the language/Normal/initial-sync messages.
            coordinatorStartState = PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED;
        }
        // Resume verified checkpoints exactly; the owner initial-sync redrive
        // marker above clears legacy false completions without reactivating.
        List<AppleWatchPostCommitCoordinator.Action> actions =
                activePostCommitCoordinator.begin(
                        activePairingSessionRecord.transitionCounter(),
                        coordinatorStartState,
                        activePairingSessionRecord.isPairedCommitIntentPersisted(),
                        watchPairingVersion);
        activeIdsBridge.setPayloadFastRetransmitEnabled(
                false);
        activePostCommitAdapter =
                new AppleWatchPostCommitIdsAdapter(
                        activePostCommitCoordinator,
                        activeIdsBridge);
        executePostCommitActions(
                actions,
                remoteCid);
        publishResumedPhoneRegistrySnapshot(
                remoteCid);
        if (pendingWatchProxyActivation != null) {
            IdsModernSessionCoordinator.SessionEvent stashed =
                    pendingWatchProxyActivation;
            pendingWatchProxyActivation = null;
            log("POST-COMMIT: replaying stashed Watch ProxyActivation into "
                    + "the activation adapter; the request itself bypassed "
                    + "the this-session Class-C snapshot gate (live 0.2.190).");
            try (AppleWatchPostCommitIdsAdapter.InboundResult incoming =
                         activePostCommitAdapter.accept(stashed)) {
                if (incoming.hasActivationRequest()) {
                    if (pendingActivationPayload != null) {
                        throw new HostException("Concurrent activation request before prior proxy completion");
                    }
                    pendingActivationPayload = incoming.archivedActivationRequest();
                    captureActivationArtifact(
                            incoming.activationKind.name().toLowerCase(Locale.US)
                                    + "-request",
                            pendingActivationPayload);
                    watchDrivenActivationObserved = true;
                    log("ACTIVATION PROXY RX: kind=" + incoming.activationKind
                            + "; authenticated Watch request accepted (stashed replay).");
                }
                executePostCommitActions(incoming.actions(), remoteCid);
            } finally {
                stashed.close();
            }
        }
        modernIdsReady = true;
    }

    private void runModernIdsBootstrapWithInitialIp(
            int localCid,
            int remoteCid,
            List<NormalLinkPipeSession.DeliveredIp> initialIp)
            throws Exception {
        if (activeIdsBridge == null) {
            throw new HostException(
                    "IDS bridge was not attached");
        }
        long bootstrapDeadline =
                BridgeClock.elapsedRealtime()
                        + IDS_BOOTSTRAP_TIMEOUT_MS;
        activeIdsBootstrap =
                new IdsBootstrapState();
        boolean isPairedReconnect = canResumeIdsData();
        log("IDS BOOTSTRAP MODE: " + (isPairedReconnect ? "resume negotiated data; fresh lane handshakes required" : "fresh control Hello")
                + " durable=" + activePairingSessionRecord.state());
        if (isPairedReconnect) {
            log("IDS: Class-D Setup with Hello (0.2.196 wake); NA after LDM; no ids-control SYN.");
        }
        sendNormalFrames(
                remoteCid,
                activeIdsBridge.pollLinkDirectorAnnouncements(
                        System.nanoTime() / 1_000_000L),
                "NORMAL LINK DIRECTOR AFTER IDS");
        executeIdsBootstrapAction(
                isPairedReconnect
                        ? activeIdsBootstrap.beginPairedListenImmediately()
                        : activeIdsBootstrap.begin(),
                remoteCid);
        if (initialIp != null && !initialIp.isEmpty()) {
            try {
                NormalLinkIdsSessionBridge.Output output =
                        activeIdsBridge.acceptDeliveredIp(
                                 initialIp);
                processModernIdsOutput(
                        output,
                        remoteCid,
                        "NORMAL IDS RX (INITIAL)");
            } catch (IllegalArgumentException
                    | IllegalStateException failure) {
                throw new HostException(
                        "IDS normal-link validation failed on initial IP: "
                                + failure.getMessage(),
                        failure);
            }
        }
        long idsWaitDeadline = isPairedReconnect
                ? Long.MAX_VALUE
                : bootstrapDeadline;
        long controlSilenceDeadlineMs = -1L;
        while (!modernIdsReady) {
            if (isPairedReconnect
                    && neighborAdvertised
                    && !idsControlReadyObserved
                    && IdsPairedReconnectPolicy.allowControlSilenceBounce(
                            idsControlSilenceBounces,
                            idsControlRejectedByPolicy)) {
                if (controlSilenceDeadlineMs < 0L) {
                    controlSilenceDeadlineMs =
                            BridgeClock.elapsedRealtime()
                                    + IdsPairedReconnectPolicy.CONTROL_SILENCE_BOUNCE_MS;
                } else if (BridgeClock.elapsedRealtime()
                        >= controlSilenceDeadlineMs) {
                    bounceAclForIdsControlSilence();
                    return;
                }
            }
            long frameDeadline = idsWaitDeadline;
            if (isPairedReconnect && controlSilenceDeadlineMs > 0L) {
                frameDeadline = Math.min(
                        controlSilenceDeadlineMs,
                        BridgeClock.elapsedRealtime() + 500L);
            }
            try {
                acceptModernIdsFrame(
                        localCid,
                        remoteCid,
                        frameDeadline,
                        "IDS bootstrap");
            } catch (HostException disconnectError) {
                if (disconnectError.getMessage() != null
                        && disconnectError.getMessage().contains("Timed out awaiting")
                        && isPairedReconnect
                        && !modernIdsReady) {
                    continue;
                }
                if (tryReconnectAfterWatchDisconnect(
                        "IDS bootstrap",
                        disconnectError)) {
                    return;
                }
                throw disconnectError;
            }
        }
        if (!idsBootstrapBarrierPassed) {
            log("IDS BOOTSTRAP BARRIER PASS: current control Hello="
                    + idsControlReadyObserved + ", resumed=" + isPairedReconnect + ", "
                    + "NanoRegistry Class-D lane joined=true, "
                    + "NanoRegistry Class-C lane joined=true; "
                    + "setup/activation commit attempted=false.");
            idsBootstrapBarrierPassed = true;
            startPostCommitIfAuthorized(
                    remoteCid);
        }
        log("IDS LIVE HOLD: authenticated normal-link remains open "
                + "until explicit STOP; only the initial Watch "
                + "Class-C full snapshot may advance the durable "
                + "properties barrier; IsPaired/activation/setup "
                + "commit remains disabled.");
        while (true) {
            try {
                acceptModernIdsFrame(
                        localCid,
                        remoteCid,
                        Long.MAX_VALUE,
                        "live IDS hold");
            } catch (HostException disconnectError) {
                if (tryReconnectAfterWatchDisconnect(
                        "post-commit setup",
                        disconnectError)) {
                    return;
                }
                throw disconnectError;
            }
        }
    }

    private boolean durablePairedCommitReady() {
        return activePairingSessionRecord != null
                && (activePairingSessionRecord.state().wireValue()
                        >= PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED.wireValue()
                        || activePairingSessionRecord.isPairedCommitIntentPersisted()
                        || (commitIsPairedAuthorized && activePairingSessionRecord.state().wireValue()
                                >= PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED.wireValue()));
    }

    private boolean canResumeIdsData() {
        return IdsPairedReconnectPolicy.usePairedDataBootstrap(
                activePairingSessionRecord != null ? activePairingSessionRecord.state() : null);
    }

    /**
     * Reconnects only after a completed Hello on an already-committed
     * pairing. POLICY (0x40) and unpaired identity {@code 0x13} must not
     * open a new control SYN.
     */
    private boolean tryReconnectAfterWatchDisconnect(
            String context,
            HostException disconnectError)
            throws Exception {
        if (disconnectError.getMessage() == null
                || !disconnectError.getMessage().contains("Watch disconnected")
                || activePairingSessionRecord == null
                || activePairingSessionRecord.state().wireValue()
                        < PairingSessionRecord.DurableState.CLASS_C_ESTABLISHED.wireValue()) {
            return false;
        }
        if (!IdsPairedReconnectPolicy.allowReconnectAfterDisconnect(
                idsControlRejectedByPolicy,
                idsControlReadyObserved,
                activePairingSessionRecord.state().wireValue()
                        >= PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED.wireValue())) {
            log("[WatchHal] SKIP PAIRED RECONNECT after "
                    + context
                    + ": policy="
                    + idsControlRejectedByPolicy
                    + " hello="
                    + idsControlReadyObserved
                    + " pairedCommit="
                    + (activePairingSessionRecord.state().wireValue()
                        >= PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED.wireValue())
                    + "; "
                    + disconnectError.getMessage());
            return false;
        }
        if (activePairingSessionRecord != null
                && activePairingSessionRecord.state()
                        == PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED
                && watchDrivenActivationObserved) {
            log("[WatchHal] ACTIVATION DELIVERED BEFORE DISCONNECT: advancing state to ACTIVATION_CONFIRMED.");
            try {
                PairingSessionRecord confirmed =
                        activePairingSessionRecord.advanceTo(
                                PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED);
                persistAndReplaceActivePairingSession(confirmed);
            } catch (Exception advErr) {
                log("[WatchHal] Failed to persist ACTIVATION_CONFIRMED checkpoint: "
                        + safeMessage(advErr));
            }
        }
        log("[WatchHal] PAIRED RECONNECT TRIGGERED: Watch disconnected during "
                + context
                + ": "
                + disconnectError.getMessage()
                + "; requesting paired reconnection...");
        reconnectRequested = true;
        return true;
    }

    private boolean reconnectPairedWatch() throws Exception {
        while (true) {
            if (activePairingSessionRecord == null) {
                return false;
            }
            if (activeWatchSetupIdentifier == null) {
            activeWatchSetupIdentifier =
                    WatchSetupMetadataCodec.decodeIdentifier(
                            WatchSetupMetadataCodec.encodeIdentifier(4, 0, 0, 0));
        }
        if (activeWatchSetupMetadata == null) {
            activeWatchSetupMetadata =
                    WatchSetupMetadataCodec.decodeExtendedMetadata(
                            WatchSetupMetadataCodec.encodeExtendedMetadata(25, 7, 5, false, 0, null));
        }
        byte[] targetAddress = peerConnectionAddress != null ? peerConnectionAddress.clone() : null;
        int targetAddressType = peerConnectionAddressType;
        connectionHandle = -1;

        int ownAddressType = localIdentity.addressType();

        if (targetAddress != null && (targetAddressType == 0 || targetAddressType == 1)) {
            log(String.format(
                    Locale.US,
                    "[WatchHal] PAIRED RECONNECT: initiating direct LE connection to Watch (type=%d ownType=%d)...",
                    targetAddressType, ownAddressType));
            createPending = true;
            sendCommandStatus(
                    HciCodec.OPCODE_LE_CREATE_CONNECTION,
                    HciCodec.buildLeCreateConnectionParameters(
                            targetAddress,
                            targetAddressType,
                            ownAddressType));
            try {
                HciCodec.LeConnectionComplete connection =
                        awaitConnectionComplete(targetAddress, targetAddressType);
                createPending = false;
                connectionHandle = connection.connectionHandle;
                peerConnectionAddressType = connection.peerAddressType;
                peerConnectionAddress = connection.peerAddress.clone();
                log(String.format(
                        Locale.US,
                        "[WatchHal] PAIRED DIRECT LE ACL CONNECTED: handle=0x%04X",
                        connectionHandle));
            } catch (StopRequested stopped) {
                throw stopped;
            } catch (Exception directFailed) {
                createPending = false;
                log("[WatchHal] Direct LE connection failed: "
                        + safeMessage(directFailed)
                        + "; scanning for Watch...");
                try {
                    sendCommandComplete(
                            HciCodec.OPCODE_LE_CREATE_CONNECTION_CANCEL,
                            new byte[0]);
                } catch (Exception ignored) {
                }
            }
        }

        if (connectionHandle == -1) {
            log("[WatchHal] PAIRED RECONNECT: scanning for Watch advertisement after respring/restart...");
            scanEnabled = true;
            sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_SCAN_ENABLE,
                    HciCodec.scanEnableParameters(true));

            HciCodec.AdvertisingReport target = awaitPairedTarget();
            sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_SCAN_ENABLE,
                    HciCodec.scanEnableParameters(false));
            scanEnabled = false;

            if (target != null && connectionHandle == -1) {
                if (target.setup != null) {
                    if (target.setup.decodedIdentifier != null) {
                        activeWatchSetupIdentifier = target.setup.decodedIdentifier;
                    }
                    if (target.setup.decodedMetadata != null) {
                        activeWatchSetupMetadata = target.setup.decodedMetadata;
                    }
                }
                createPending = true;
                sendCommandStatus(
                        HciCodec.OPCODE_LE_CREATE_CONNECTION,
                        HciCodec.buildLeCreateConnectionParameters(
                                target,
                                ownAddressType));
                HciCodec.LeConnectionComplete connection =
                        awaitConnectionComplete(target);
                createPending = false;
                connectionHandle = connection.connectionHandle;
                peerConnectionAddressType = connection.peerAddressType;
                peerConnectionAddress = connection.peerAddress.clone();
                log(String.format(
                        Locale.US,
                        "[WatchHal] PAIRED LE ACL CONNECTED: handle=0x%04X",
                        connectionHandle));
            }
        }

        byte[] ltk = activeLongTermKey;
        if (ltk == null && activePairingSessionRecord != null && activePairingSessionRecord.bluetoothBond() != null) {
            try {
                ltk = BluetoothBondSecretRecord.extractLongTermKey(activePairingSessionRecord.bluetoothBond());
            } catch (Exception extractErr) {
                log("[WatchHal] Could not extract LTK from bond record: " + safeMessage(extractErr));
            }
        }

        if (ltk != null) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {}
            log("[WatchHal] PAIRED RECONNECT: starting LE link encryption with stored LTK...");
            try {
                startLeEncryption(ltk);
                log("[WatchHal] PAIRED RECONNECT: LE link encryption active!");
            } catch (HostException encErr) {
                log("[WatchHal] PAIRED RECONNECT: Encryption failed on candidate ("
                        + safeMessage(encErr)
                        + "); candidate was not the bonded Watch, retrying scan...");
                if (connectionHandle != -1) {
                    try {
                        sendCommandStatus(HciCodec.OPCODE_DISCONNECT, HciCodec.buildDisconnectParameters(connectionHandle));
                        long disconnDeadline = BridgeClock.elapsedRealtime() + 1500;
                        while (BridgeClock.elapsedRealtime() < disconnDeadline) {
                            try {
                                Packet p = nextPacket(disconnDeadline);
                                if (p.kind == PacketKind.EVENT) {
                                    HciCodec.DisconnectionComplete dc = HciCodec.parseDisconnectionComplete(p.bytes);
                                    if (dc != null && dc.connectionHandle == connectionHandle) {
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                    connectionHandle = -1;
                }
                peerConnectionAddress = null;
                return false;
            }
        }

        sendL2cap(
                HciCodec.BT_CL_SIGNALING_CID,
                HciCodec.buildBtClVersionPdu(),
                "BT_CL VERSION");
        HciCodec.BtClPdu versionPdu =
                awaitBtClOpcode(
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.BT_CL_VERSION_INFO,
                        HciCodec.BT_CL_CURRENT_VERSION);
        HciCodec.VersionInfo peerVersion =
                HciCodec.parseVersionInfo(versionPdu);
        normalBtClVersion = peerVersion.version;
        normalBtClFeatures = peerVersion.features;
        log(String.format(
                Locale.US,
                "[WatchHal] PAIRED BT_CL VERSION RX: version=%d features=0x%08X.",
                peerVersion.version,
                peerVersion.features));

        sendL2cap(
                HciCodec.BT_CL_SIGNALING_CID,
                HciCodec.buildRemoteServicesPdu(
                        peerVersion.version,
                        peerVersion.features,
                        List.of(
                                HciCodec.terminusLinkService(),
                                HciCodec.terminusPairingService())),
                "BT_CL REMOTE_SERVICES com.apple.terminusLink / terminusPairing");
        HciCodec.BtClPdu commonPdu =
                awaitBtClOpcode(
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.BT_CL_COMMON_SERVICES,
                        peerVersion.version,
                        peerVersion.features);
        List<Integer> services = HciCodec.parseCommonServices(commonPdu);
        IdsPairedReconnectPolicy.AdvertisedMode mode =
                IdsPairedReconnectPolicy.advertisedMode(services);
        if (mode == IdsPairedReconnectPolicy.AdvertisedMode.SETUP_ONLY) {
            if (operationalPairingId != null) {
                throw new HostException("Confirmed pair advertises setup only; preserving keys and refusing automatic fresh PIN pairing");
            }
            log("PAIRED RECONNECT: Watch advertises setup only; fresh PIN pairing required.");
            disconnectBeforeFreshPairing();
            activePairingSessionRecord.destroy();
            activePairingSessionRecord = null;
            return false;
        }
        if (mode != IdsPairedReconnectPolicy.AdvertisedMode.NORMAL) {
            throw new HostException("Watch did not advertise terminusLink on reconnect; common=" + services);
        }
        int selectedServiceId = HciCodec.TERMINUS_LINK_SERVICE_ID;

        sendL2cap(
                HciCodec.BT_CL_SIGNALING_CID,
                HciCodec.buildCreateChannelPdu(
                        peerVersion.version,
                        peerVersion.features,
                        HciCodec.TERMINUS_LOCAL_CID,
                        selectedServiceId),
                "BT_CL CREATE_CHANNEL localCID=0x0040 service=0x"
                        + Integer.toHexString(selectedServiceId));

        sendNormalTimeSync();
        HciCodec.BtClPdu acceptPdu =
                awaitBtClOpcode(
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.BT_CL_ACCEPT_CHANNEL,
                        peerVersion.version,
                        peerVersion.features);
        HciCodec.AcceptChannel accept =
                HciCodec.parseAcceptChannel(acceptPdu);
        log(String.format(
                Locale.US,
                "[WatchHal] PAIRED TERMINUS PIPE REOPENED: localCID=0x%04X remoteCID=0x%04X service=0x%04X.",
                HciCodec.TERMINUS_LOCAL_CID,
                accept.responderLocalCid,
                selectedServiceId));

        BtClNormalLinkHandoff normalHandoff =
                BtClNormalLinkHandoff.fromReconnectedChannel(
                        peerVersion.version,
                        HciCodec.TERMINUS_LOCAL_CID,
                        accept.responderLocalCid,
                        true);

        if (activePairingSessionRecord != null
                && activePairingSessionRecord.addressesConfirmed()
                && activePairingSessionRecord.localRole()
                == NrLinkBluetoothPrelude.LocalRole.RESPONDER
                && activePairingSessionRecord.state().wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            PairingSessionRecord pinned =
                    activePairingSessionRecord.pinLocalRole(
                            NrLinkBluetoothPrelude.LocalRole.INITIATOR);
            try {
                persistAndReplaceActivePairingSession(pinned);
                pinned = null;
                log("NORMAL PRELUDE: pinned stored role to INITIATOR. "
                        + "The responder projection answered the setup "
                        + "control socket from the Watch address, and "
                        + "the Watch kernel reset that acknowledgement.");
            } finally {
                if (pinned != null) {
                    pinned.destroy();
                }
            }
        }
        NrLinkBluetoothPrelude.PairingState preludeState =
                NrLinkBluetoothPrelude.PairingState.HAS_COMPLETED_PAIRING;
        NrLinkBluetoothPipeBootstrap bootstrap =
                NrLinkBluetoothPipeBootstrap.beginPaired(
                        normalHandoff,
                        activePairingSessionRecord != null
                                ? activePairingSessionRecord.localRole()
                                : NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                        preludeState,
                        new SecureRandom());
        byte[] localPrelude =
                bootstrap.buildOutboundPreludeFrame();
        try {
            sendL2cap(
                    accept.responderLocalCid,
                    localPrelude,
                    "PAIRED NORMAL ERTM LOCAL PRELUDE");
        } finally {
            wipe(localPrelude);
        }

        NrLinkBluetoothSession.Negotiated negotiated = null;
        long preludeDeadline = BridgeClock.elapsedRealtime() + 10_000;
        while (negotiated == null) {
            HciCodec.L2capPdu l2cap =
                    awaitNormalL2cap(
                            HciCodec.TERMINUS_LOCAL_CID,
                            preludeDeadline,
                            "paired normal prelude");
            L2capErtmCodec.Frame decoded = null;
            try {
                decoded =
                        L2capErtmCodec.decode(
                                HciCodec.TERMINUS_LOCAL_CID,
                                l2cap.payload,
                                normalHandoff.fcsEnabled());
                if (decoded.supervisory) {
                    try (L2capErtmSession.InboundResult control =
                                 bootstrap.acceptPeerControlFrame(l2cap.payload)) {
                        List<byte[]> immediate = control.immediateOutboundFrames();
                        sendNormalFrames(
                                accept.responderLocalCid,
                                immediate,
                                "PAIRED NORMAL ERTM PRELUDE CONTROL");
                    }
                    continue;
                }
                negotiated =
                        bootstrap.acceptInboundPreludeFrame(l2cap.payload);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Paired normal prelude ERTM validation failed: "
                                + error.getMessage(),
                        error);
            } finally {
                if (decoded != null) {
                    wipe(decoded.information);
                }
                wipe(l2cap.payload);
            }
        }

        byte[] remotePreludeAck =
                bootstrap.buildRemotePreludeAcknowledgementFrame();
        try {
            sendL2cap(
                    accept.responderLocalCid,
                    remotePreludeAck,
                    "PAIRED NORMAL ERTM REMOTE PRELUDE RR");
        } finally {
            wipe(remotePreludeAck);
        }

        if (negotiated.localRole != activePairingSessionRecord.localRole()) {
            log("NORMAL PRELUDE: elected role="
                    + negotiated.localRole
                    + " stored role="
                    + activePairingSessionRecord.localRole()
                    + "; a confirmed quartet keeps the stored role.");
        } else {
            log("NORMAL PRELUDE: role="
                    + negotiated.localRole
                    + " matches the stored quartet projection.");
        }
        PairingSessionRecord negotiatedRecord =
                activePairingSessionRecord
                        .withPreludeNegotiated(
                                negotiated.localRole);
        try {
            persistAndReplaceActivePairingSession(
                    negotiatedRecord);
            negotiatedRecord = null;
        } finally {
            if (negotiatedRecord != null) {
                negotiatedRecord.destroy();
            }
        }

        NrLinkBluetoothPipeBootstrap.PreludeHandoff preludeHandoff =
                bootstrap.detach();
        NormalLinkPipeSession.diagnosticLogger =
                ProtocolHost::log;
        NormalLinkPipeSession pipe =
                NormalLinkPipeSession.resumeFromPairingRecord(
                        new SecureRandom(),
                        preludeHandoff,
                        activePairingSessionRecord,
                        IosCompanionProfile26_6.normalLink(
                                activePairingSessionRecord.localIdsDeviceUuid()));
        log(addressProjection("provisional", pipe));
        pipe.enableLinkDirectorAnnouncements(LinkDirectorMessageCodec.sequenceSeed(
                System.currentTimeMillis(), BridgeClock.elapsedRealtimeNanos()), ProtocolHost::log);
        List<byte[]> initialOrdinary =
                pipe.continueAfterPrelude();
        sendNormalFrames(
                accept.responderLocalCid,
                initialOrdinary,
                "PAIRED NORMAL CLASS-D START");

        long modernRegistrationDeadlineMs =
                BridgeClock.elapsedRealtime() + 15_000;
        while (BridgeClock.elapsedRealtime()
                < modernRegistrationDeadlineMs) {
            checkpointOrdinaryRegistration(
                    pipe);
            if (pipe.registrationBarrierSatisfied()) {
                log(addressProjection("after-ike", pipe));
                log("PAIRED NORMAL REGISTRATION BARRIER PASS: "
                        + "Class D connected=true, "
                        + "Class C connected=true; physical "
                        + "Watch7,5 confirmKeysForClass(D/C) "
                        + "prerequisites delivered.");
                attachModernIdsSession(
                        pipe);
                pipe = null;
                log("PAIRED NORMAL PIPE RESUMED: ready for modern IDS hold and proxy activation!");
                // Post-commit starts inside the bootstrap below, once the IDS
                // control channel is up. Sending here would race it: the Watch
                // rejects every service connector opened before its control
                // Hello, so the setup messages would only queue.
                fireProactiveStaleFlowResets(
                        accept.responderLocalCid);
                reconnectRequested = false;
                runModernIdsBootstrapAndHold(
                        HciCodec.TERMINUS_LOCAL_CID,
                        accept.responderLocalCid);
                if (!reconnectRequested) {
                    return true;
                }
                log("[WatchHal] PAIRED RECONNECT LOOP: re-establishing connection without call stack recursion...");
                continue;
            }

            HciCodec.L2capPdu l2cap =
                    awaitNormalL2cap(
                            HciCodec.TERMINUS_LOCAL_CID,
                            modernRegistrationDeadlineMs,
                            "paired ordinary Class D/Class C");
            try (NormalLinkPipeSession.InboundResult
                    result =
                    pipe.acceptErtmFrame(
                            l2cap.payload)) {
                if (result.ertmSupervisory()) {
                    log(String.format(
                            Locale.US,
                            "PAIRED NORMAL ERTM RX: kind=S function=%d "
                                    + "reqSeq=%d informationBytes=%d; "
                                    + "packet bytes logged=false.",
                            result.ertmSupervisoryFunction(),
                            result.ertmRequestSequence(),
                            result.ertmInformationLength()));
                } else {
                    log(String.format(
                            Locale.US,
                            "PAIRED NORMAL ERTM RX: kind=I txSeq=%d "
                                    + "reqSeq=%d informationBytes=%d; "
                                    + "packet bytes logged=false.",
                            result.ertmTxSequence(),
                            result.ertmRequestSequence(),
                            result.ertmInformationLength()));
                }
                List<byte[]> immediate =
                        result.immediateErtmFrames();
                sendNormalFrames(
                        accept.responderLocalCid,
                        immediate,
                        "PAIRED NORMAL ORDINARY IKE/ERTM");

                List<NormalLinkPipeSession.DeliveredIp>
                        delivered =
                        result.deliveredIp();
                try {
                    if (!delivered.isEmpty()) {
                        boolean registrationBarrier =
                                pipe.registrationBarrierSatisfied();
                        if (registrationBarrier) {
                            log("PAIRED NORMAL REGISTRATION BARRIER PASS: "
                                    + "Class D connected=true, "
                                    + "Class C connected=true; physical "
                                    + "Watch7,5 confirmKeysForClass(D/C) "
                                    + "prerequisites delivered (early IP).");
                            attachModernIdsSession(
                                    pipe);
                            pipe = null;
                            reconnectRequested = false;
                            runModernIdsBootstrapWithInitialIp(
                                    HciCodec.TERMINUS_LOCAL_CID,
                                    accept.responderLocalCid,
                                    delivered);
                            if (!reconnectRequested) {
                                return true;
                            }
                            log("[WatchHal] PAIRED RECONNECT LOOP: re-establishing connection without call stack recursion (early IP)...");
                            continue;
                        }
                        if (pipe.classDEstablished()) {
                            log("PAIRED NORMAL CLASS-D EARLY IP: "
                                    + delivered.size()
                                    + " packet(s) received before Class C settled; safe early drop/settle.");
                        } else if (!NormalLinkPipeSession
                                .canDiscardBeforeIdsAttachment(
                                        delivered)) {
                            throw new HostException(
                                    "Unexpected unicast IP before ordinary "
                                            + "registration barrier satisfied");
                        }
                    }
                } finally {
                    if (pipe != null) {
                        for (NormalLinkPipeSession.DeliveredIp
                                packet : delivered) {
                            packet.destroy();
                        }
                        delivered.clear();
                    }
                }
            } finally {
                wipe(l2cap.payload);
            }
        }
        throw new HostException(
                "Timed out waiting for paired ordinary Class-D/Class-C registration");
        }
    }

    private void acceptModernIdsFrame(
            int localCid,
            int remoteCid,
            long deadline,
            String label)
            throws Exception {
        long lastHoldRetransmitMs = 0L;
        while (BridgeClock.elapsedRealtime() < deadline) {
            if (!pendingNanoSetup) {
                retryPendingTcpOnTimer(remoteCid);
                retryPendingErtmOnTimer(remoteCid);
            }
            pollActivationProxy(remoteCid);
            pollInitialWifiSync(remoteCid);
            pumpStoredTwoWayPreferences(remoteCid);
            if (activeIdsBridge != null) {
                sendNormalFrames(remoteCid, activeIdsBridge.pollLinkDirectorAnnouncements(
                        System.nanoTime() / 1_000_000L), "NORMAL LINK DIRECTOR CONTROL");
            }
            drainOutboundAppMessages(remoteCid);
            long sliceMs =
                    pendingNanoSetup
                            ? 80L
                            : (!outboundAppMessages.isEmpty() ? 50L : 500L);
            long sliceDeadline = Math.min(deadline, BridgeClock.elapsedRealtime() + sliceMs);
            HciCodec.L2capPdu l2cap = null;
            try {
                l2cap = awaitNormalL2cap(
                        localCid,
                        sliceDeadline,
                        label);
            } catch (HostException timeout) {
                if (timeout.getMessage() != null && timeout.getMessage().contains("Timed out awaiting")
                        && BridgeClock.elapsedRealtime() < deadline) {
                    if (pendingNanoSetup) {
                        boolean holding =
                                pendingNanoSetup;
                        maybeFlushPendingNanoSetup(
                                remoteCid);
                        if (holding && !pendingNanoSetup) {
                            continue;
                        }
                    }
                    if (activeIdsBridge != null) {
                        try {
                            long keepaliveNow = BridgeClock.elapsedRealtime();
                            byte[] rr;
                            if (keepaliveNow - lastKeepalivePollMs >= 3_000L) {
                                lastKeepalivePollMs = keepaliveNow;
                                rr = activeIdsBridge.buildReceiverReadyPoll();
                            } else {
                                rr = activeIdsBridge.buildReceiverReady();
                            }
                            if (rr != null && rr.length > 0) {
                                sendL2cap(remoteCid, rr, "NORMAL ERTM KEEPALIVE RR");
                            }
                                                if (pendingNanoSetup) {
                                // Live 0.2.124: pollRetransmissions while the
                                // window was full minted I-frames to 32.
                                // Live PIN 0.2.127: ERTM quiet, tcpUna=27,
                                // hold cloned the oldest empty-ACK I-frame;
                                // Watch ERTM-acked it and the 9-byte
                                // handshake payload never went again.
                                int outstanding =
                                        activeIdsBridge
                                                .ertmOutstandingCount();
                                long tcpUna =
                                        activeIdsBridge
                                                .tcpUnacknowledgedSendBytes();
                                boolean ertmQuiet =
                                        IdsNanoSetupPacer.isErtmQuiet(
                                                outstanding);
                                activeIdsBridge
                                        .setPayloadFastRetransmitEnabled(
                                                ertmQuiet);
                                if (IdsNanoSetupPacer
                                        .shouldRetransmitTcpDuringHold(
                                                ertmQuiet,
                                                tcpUna)) {
                                    log("[TCP TX RETRANSMIT] hold payload "
                                            + "streams="
                                            + activeIdsBridge
                                            .tcpUnacknowledgedSendSummary()
                                            + " outstanding="
                                            + outstanding
                                            + " tcpUna="
                                            + tcpUna);
                                    NormalLinkIdsSessionBridge.Output
                                            tcpRetrans =
                                            activeIdsBridge
                                                    .retransmitTcpOutstanding();
                                    processModernIdsOutput(
                                            tcpRetrans,
                                            remoteCid,
                                            "NORMAL IDS HOLD TCP RETRANSMIT");
                                    flushPendingIdsFrames(remoteCid);
                                } else {
                                    byte[] oldestErtm =
                                            activeIdsBridge
                                                    .copyOldestUnacknowledgedErtmFrame();
                                    if (oldestErtm != null
                                            && oldestErtm.length > 0) {
                                        long now = BridgeClock.elapsedRealtime();
                                        if (now - lastHoldRetransmitMs >= 600L) {
                                            lastHoldRetransmitMs = now;
                                            log("[ERTM TX] hold retransmit: txSeq="
                                                    + activeIdsBridge
                                                    .ertmOldestTxSequence()
                                                    + " retained="
                                                    + outstanding
                                                    + " tcpUna="
                                                    + tcpUna);
                                            sendL2cap(
                                                    remoteCid,
                                                    oldestErtm,
                                                    "NORMAL ERTM HOLD RETRANSMIT");
                                        }
                                    }
                                }
                                continue;
                            }
                            java.util.List<byte[]> pendingErtm =
                                    activeIdsBridge.copyUnacknowledgedErtmFrames(4);
                            int oldestTx = activeIdsBridge.ertmOldestTxSequence();
                            long nowMs = BridgeClock.elapsedRealtime();
                            if (oldestTx != ertmStallTxSeq) {
                                ertmStallTxSeq = oldestTx;
                                ertmStallSends = 0;
                                ertmStallNextMs = 0L;
                            }
                            if (!pendingErtm.isEmpty() && nowMs >= ertmStallNextMs) {
                                ertmStallSends++;
                                ertmStallNextMs = nowMs + ErtmRetransmitPace.delayMs(ertmStallSends);
                                if (ertmStallSends == 1 || ertmStallSends == 5) {
                                    byte[] sample = pendingErtm.get(0);
                                    int control = sample.length >= 2
                                            ? (sample[0] & 0xff) | ((sample[1] & 0xff) << 8)
                                            : -1;
                                    int wireTx = control < 0 ? -1 : (control >>> 1) & 0x3f;
                                    int wireReq = control < 0 ? -1 : (control >>> 8) & 0x3f;
                                    log("[ERTM TX] stall frame: txSeq="
                                            + oldestTx
                                            + " wireTx="
                                            + wireTx
                                            + " wireReq="
                                            + wireReq
                                            + " bytes="
                                            + sample.length
                                            + " send="
                                            + ertmStallSends
                                            + " retained="
                                            + activeIdsBridge.ertmOutstandingCount());
                                }
                                log("[ERTM TX] timeout retransmit: txSeq="
                                        + oldestTx
                                        + " frames="
                                        + pendingErtm.size()
                                        + " retained="
                                        + activeIdsBridge.ertmOutstandingCount()
                                        + " send="
                                        + ertmStallSends);
                                int index = 0;
                                for (byte[] frame : pendingErtm) {
                                    index++;
                                    sendL2cap(
                                            remoteCid,
                                            frame,
                                            "NORMAL ERTM TIMEOUT RETRANSMIT "
                                                    + index
                                                    + "/"
                                                    + pendingErtm.size());
                                }
                            } else if (pendingErtm.isEmpty()
                                    && IdsNanoSetupPacer.isErtmQuiet(activeIdsBridge.ertmOutstandingCount())) {
                                // pollRetransmissions also opens new
                                // service connectors. That burst filled
                                // the ERTM window (live 0.2.211, 19
                                // frames) and left CanBegin held.
                                retryPendingTcpOnTimer(remoteCid);
                            }
                            driveActivationFallbackIfStalled(remoteCid);
                                            } catch (Exception evidenceFallbackError) {
                            log("INITIAL NANO EVIDENCE FALLBACK WARN: "
                                    + evidenceFallbackError.getMessage());
                        }
                    }
                    drainOutboundAppMessages(remoteCid);
                    drainStaleFlowSeeds(remoteCid);
                    continue;
                }
                throw timeout;
            }
            try {
                NormalLinkIdsSessionBridge.Output output =
                        activeIdsBridge.acceptErtmFrame(
                                l2cap.payload);
                if (!output.ertmFrames().isEmpty() || !output.idsEvents().isEmpty() || !output.passthroughIp().isEmpty()) {
                    log(String.format(
                            java.util.Locale.US,
                            "[LiveIds] acceptFrame: rxPayload=%d -> ertmFrames=%d idsEvents=%d passthroughIp=%d",
                            l2cap.payload.length,
                            output.ertmFrames().size(),
                            output.idsEvents().size(),
                            output.passthroughIp().size()));
                }
                processModernIdsOutput(
                        output,
                        remoteCid,
                        "NORMAL IDS RX");
                // NA before Class-D Setup so Watch NDP is warm when the
                // Urgent-D SYN to :61314 lands (live 0.2.196 wake path).
                maybeAnnounceLocalNeighbor(remoteCid);
                maybeStartPairedClassDSetup(remoteCid);
                flushPendingIdsFrames(remoteCid);
                drainOutboundAppMessages(remoteCid);
                drainStaleFlowSeeds(remoteCid);
                return;
            } catch (IllegalArgumentException
                    | IllegalStateException failure) {
                throw new HostException(
                        "IDS normal-link validation failed: "
                                + failure.getMessage(),
                        failure);
            } finally {
                wipe(
                        l2cap.payload);
            }
        }
    }

    /** Incoming traffic must not suppress TCP recovery after ERTM has drained. */
    private void retryPendingTcpOnTimer(int remoteCid) throws Exception {
        if (activeIdsBridge == null
                || activeIdsBridge.hasDeferredIpv6()
                || !IdsNanoSetupPacer.isErtmQuiet(activeIdsBridge.ertmOutstandingCount())
                || activeIdsBridge.tcpUnacknowledgedSendBytes() == 0L) {
            return;
        }
        long nowMs = BridgeClock.elapsedRealtime();
        if (nowMs - lastTcpRetransmitMs < 1_000L) return;
        lastTcpRetransmitMs = nowMs;
        log("[TCP TX RETRANSMIT] timer streams="
                + activeIdsBridge.tcpUnacknowledgedSendSummary());
        processModernIdsOutput(activeIdsBridge.retransmitTcpOutstanding(),
                remoteCid, "NORMAL IDS TCP TIMER RETRANSMIT");
        flushPendingIdsFrames(remoteCid);
    }

    /** Incoming traffic must not suppress retransmission of retained I-frames. */
    private void retryPendingErtmOnTimer(int remoteCid) throws Exception {
        if (activeIdsBridge == null) return;
        long nowMs = BridgeClock.elapsedRealtime();
        int oldestTx = activeIdsBridge.ertmOldestTxSequence();
        if (oldestTx != ertmStallTxSeq) {
            ertmStallTxSeq = oldestTx;
            ertmStallSends = 0;
            ertmStallNextMs = nowMs + 600L;
        }
        if (nowMs < ertmStallNextMs) return;
        List<byte[]> frames = activeIdsBridge.copyUnacknowledgedErtmFrames(4);
        try {
            if (frames.isEmpty()) return;
            ertmStallSends++;
            ertmStallNextMs = nowMs + Math.max(600L, ErtmRetransmitPace.delayMs(ertmStallSends));
            log("[ERTM TX] timer retransmit: txSeq=" + oldestTx
                    + " frames=" + frames.size() + " send=" + ertmStallSends);
            for (byte[] frame : frames) {
                sendL2cap(remoteCid, frame, "NORMAL ERTM TIMER RETRANSMIT");
            }
        } finally { for (byte[] frame : frames) wipe(frame); }
    }

    private void maybeStartPairedClassDSetup(int remoteCid) throws Exception {
        if (pairedKeyProbesStarted || activeIdsBootstrap == null
                || activeIdsBridge == null
                || !activeIdsBridge.linkDirectorStateAcknowledged()) {
            return;
        }
        pairedKeyProbesStarted = true;
        executeIdsBootstrapAction(
                activeIdsBootstrap.onLinkDirectorReady(),
                remoteCid);
    }

    private void maybeAnnounceLocalNeighbor(int remoteCid) throws Exception {
        if (neighborAdvertised || activeIdsBridge == null
                || !activeIdsBridge.linkDirectorStateAcknowledged()) {
            return;
        }
        // Paired resume must still advertise Class-D/C reachability.
        // Skipping NA when CLOCK_VISIBLE (0.2.294) left Watch NDP cold:
        // no phone named UTun SYN, no NS→NA on the ERTM path, and no
        // Watch-initiated ids-control-channel after DeviceLinkState ACK.
        sendNormalFrames(
                remoteCid,
                activeIdsBridge.announceLocalNeighbor(),
                "NORMAL NEIGHBOR ADVERTISEMENT");
        neighborAdvertised = true;
        log("IDS: unsolicited Class-D/C Neighbor Advertisement after DeviceLinkState ACK.");
    }

    /**
     * Watch {@code IDSUTunController::link:didDisconnect} clears
     * {@code linkLayerConnected} but an in-flight control NWSC can leave
     * {@code connecting=YES} until the request fails. HCI Disconnect forces
     * that failure + {@code pipeDidDisconnect}; the paired reconnect loop
     * then re-runs {@code startControlChannelWithDevice:} on connect.
     */
    private void bounceAclForIdsControlSilence() throws Exception {
        idsControlSilenceBounces++;
        log("IDS: no Watch ids-control-channel within "
                + IdsPairedReconnectPolicy.CONTROL_SILENCE_BOUNCE_MS
                + " ms after LDM+NA; ACL bounce "
                + idsControlSilenceBounces
                + "/"
                + IdsPairedReconnectPolicy.CONTROL_SILENCE_BOUNCE_MAX
                + " to clear Watch control connecting zombie.");
        if (connectionHandle != -1) {
            sendCommandStatus(
                    HciCodec.OPCODE_DISCONNECT,
                    HciCodec.buildDisconnectParameters(connectionHandle));
            long disconnDeadline = BridgeClock.elapsedRealtime() + 1500L;
            while (BridgeClock.elapsedRealtime() < disconnDeadline) {
                try {
                    Packet packet = nextPacket(disconnDeadline);
                    if (packet.kind == PacketKind.EVENT) {
                        HciCodec.DisconnectionComplete complete =
                                HciCodec.parseDisconnectionComplete(packet.bytes);
                        if (complete != null
                                && complete.connectionHandle == connectionHandle) {
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    break;
                }
            }
            connectionHandle = -1;
        }
        // Firmware IDSDeviceConnectionInfo::invalidate cancels NWSC
        // listeners; link:didDisconnect alone does not. Longer offline
        // gives pipe teardown a chance to surface a non-EINPROGRESS
        // error into didConnectControlChannel → setConnecting:NO →
        // FUN_10035bdfc retry after the next IKE/LDM.
        try {
            Thread.sleep(8_000L);
        } catch (InterruptedException ignored) {
        }
        reconnectRequested = true;
    }

    private void pollInitialWifiSync(int remoteCid) {
        if (stopRequested.get() || operationalPairingId != null || activeIdsBridge == null
                || activePostCommitCoordinator == null || activePairingSessionRecord == null) return;
        var setup = activePostCommitCoordinator.snapshot();
        var context = new InitialWifiSyncWorker.Context(UUID.fromString(activePairingSessionRecord.nrUuid()),
                setup.generation, setup.activationConfirmed
                    && activePairingSessionRecord.state().wireValue() >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue(),
                setup.initialSyncPrepared && setup.pbBridgeNormalSent && setup.languageAndLocaleComplete,
                modernIdsReady && connectionHandle != -1, false);
        long now = BridgeClock.elapsedRealtime();
        if (initialWifiSync.request(context, now)) {
            log("WIFI SETUP AUTO: reading current validated phone network asynchronously; attempt="
                    + initialWifiSync.attempts() + "; credentials logged=false.");
        }
        if (activeIdsBridge.hasPendingApplicationFrames() || activeIdsBridge.hasDeferredIpv6()) return;
        byte[] archive = initialWifiSync.take(context, now);
        if (archive != null) {
            try {
                NormalLinkIdsSessionBridge.Output output = activeIdsBridge.sendApplicationData(WifiNetworkSyncCodec.TOPIC, archive);
                try (output) {
                    for (var event : output.idsEvents()) {
                        if (event.type == IdsModernSessionCoordinator.EventType.DATA_SENT
                                && WifiNetworkSyncCodec.TOPIC.equals(event.topic)) initialWifiMessageUuid = event.messageUuid;
                    }
                    if (initialWifiMessageUuid == null) throw new IllegalStateException("No initial Wi-Fi IDS message allocated");
                    initialWifiSync.sent();
                    log("WIFI SETUP AUTO IDS QUEUED: current WPA2/CCMP network, V2 ADD; Watch apply unconfirmed; credentials logged=false.");
                    processModernIdsOutput(output, remoteCid, "WIFI SETUP AUTO TX");
                    flushPendingIdsFrames(remoteCid);
                }
            } catch (Exception failure) {
                initialWifiSync.failed();
                log("WIFI SETUP AUTO unavailable: delivery failed; manual retry remains available after connection; credentials logged=false.");
            } finally { wipe(archive); }
        }
        if (!initialWifiUnavailableLogged && initialWifiSync.phase() == InitialWifiSyncWorker.Phase.UNAVAILABLE) {
            initialWifiUnavailableLogged = true;
            log("WIFI SETUP AUTO unavailable: no supported validated current network or delivery failed; setup continues; credentials logged=false.");
        }
    }

    private void drainOutboundAppMessages(int remoteCid) {
        OPERATIONAL_REQUESTS.expire(BridgeClock.elapsedRealtime());
        drainPhonePingReplies(remoteCid);
        advanceClockFaceDelta(remoteCid);
        if (activeIdsBridge == null || outboundAppMessages.isEmpty()) {
            return;
        }
        OutboundAppMessage msg;
        while ((msg = outboundAppMessages.poll()) != null) {
            byte[] preparedBulletin = null;
            byte[] preparedPing = null;
            byte[] preparedFace = null;
            byte[] preparedWifi = null;
            byte[] preparedPigments = null;
            byte[] preparedMonogram = null;
            boolean retained=false;
            try {
                if(msg.health!=null) {
                    if(BridgeClock.elapsedRealtime()>=msg.health.deadline()) {
                        publishHealthSendStatus(msg.health,BridgeCommandCodec.Stage.EXPIRED);continue;
                    }
                    if(activePairingSessionRecord==null || operationalPairingId==null || !operationalSnapshotPublished) {
                        throw new IllegalArgumentException("No activated Health sender context");
                    }
                    OperationalSessionPolicy.requireMatchingActivatedPair(activePairingSessionRecord,operationalPairingId);
                    msg.health.requireContext(UUID.fromString(operationalPairingId),
                            UUID.fromString(activePairingSessionRecord.localIdsDeviceUuid()),
                            UUID.fromString(activePairingSessionRecord.peerIdsDeviceId()),clockFaceObservationEpoch,BridgeClock.elapsedRealtime());
                    if(idsDeviceInfoExchange==null || !idsDeviceInfoExchange.ready()) {
                        retained=outboundAppMessages.offer(msg);
                        if(retained)return;
                        publishHealthSendStatus(msg.health,BridgeCommandCodec.Stage.REJECTED);continue;
                    }
                }
                if (msg.request != null && !OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                if (msg.request != null && ClockFaceDeltaCommand.matches(msg.request.command())) {
                    beginClockFaceDelta(msg.request, remoteCid);
                    continue;
                }
                byte[] outgoingPayload = msg.payload;
                if (msg.request != null && MonogramPreferenceCommand.matches(msg.request.command())) {
                    if (activePairingSessionRecord == null || operationalPairingId == null || !operationalSnapshotPublished)
                        throw new IllegalArgumentException("No activated monogram sender context");
                    OperationalSessionPolicy.requireMatchingActivatedPair(activePairingSessionRecord, operationalPairingId);
                    if (!msg.request.epoch().equals(clockFaceObservationEpoch)) throw new IllegalArgumentException("Monogram session changed");
                    preparedMonogram = monogramMirror(UUID.fromString(operationalPairingId)).prepare(
                        MonogramPreferenceCommand.parse(msg.request.command()), clockFaceObservationEpoch,
                        msg.request.id(), System.currentTimeMillis());
                    outgoingPayload = preparedMonogram;
                    publishMonogramMirror();
                    if (!OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                }
                if (msg.request != null && PigmentPreferenceCommand.matches(msg.request.command())) {
                    if (activePairingSessionRecord == null || operationalPairingId == null || !operationalSnapshotPublished) {
                        throw new IllegalArgumentException("No activated pigment sender context");
                    }
                    OperationalSessionPolicy.requireMatchingActivatedPair(activePairingSessionRecord, operationalPairingId);
                    UUID pair = UUID.fromString(operationalPairingId);
                    if (!msg.request.epoch().equals(clockFaceObservationEpoch)) throw new IllegalArgumentException("Pigment session changed");
                    if (!OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                    preparedPigments = pigmentMirror().prepare(
                            PigmentPreferenceCommand.parse(msg.request.command()), clockFaceObservationEpoch,
                            msg.request.id(), System.currentTimeMillis());
                    outgoingPayload = preparedPigments;
                    publishPigmentMirror();
                    if (!OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                }
                boolean rebootRequest = msg.request != null
                        && NativeWatchReboot.COMMAND.equals(msg.request.command());
                if (rebootRequest) {
                    if (activePairingSessionRecord == null || operationalPairingId == null
                            || !operationalSnapshotPublished) {
                        throw new IllegalStateException("No activated operational reboot context");
                    }
                    OperationalSessionPolicy.requireMatchingActivatedPair(activePairingSessionRecord, operationalPairingId);
                    watchReboot.begin(true, clockFaceDelta != null && !clockFaceDelta.terminal());
                    log("WATCH REBOOT TX: native NSS type=19 empty; ordinary reboot; no erase/setup; no automatic retry; completion unverified.");
                }
                if (msg.request != null && WifiNetworkSyncCodec.COMMAND.equals(msg.request.command())) {
                    if (activePairingSessionRecord == null || operationalPairingId == null || !operationalSnapshotPublished) {
                        throw new IllegalArgumentException("No activated Wi-Fi sender context");
                    }
                    OperationalSessionPolicy.requireMatchingActivatedPair(activePairingSessionRecord, operationalPairingId);
                    preparedWifi = RootWifiNetworkReader.currentArchive();
                    if (!OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                    outgoingPayload = preparedWifi;
                    log("WIFI SYNC TX: current validated WPA2/CCMP network; V2 ADD only; credentials logged=false; Watch apply unconfirmed.");
                }
                boolean faceRequest = msg.request != null && msg.request.command().equals("REQUEST_FACE_COLLECTION");
                if (faceRequest) {
                    if (clockFaceDelta != null && !clockFaceDelta.terminal()) throw new IllegalStateException("Native face delta is busy");
                    if (activePairingSessionRecord == null) throw new IllegalStateException("No durable clockface pair");
                    byte[] header = clockFaceClient().reserveHeader(System.currentTimeMillis(), true);
                    try {
                        String unfinished = clockFaceReceiver().unfinishedSessionForRecovery();
                        preparedFace = ClockFaceSyncHeaderCodec.fullRequest(header, unfinished);
                        log("CLOCKFACE COLLECTION REQUEST: knownUnfinishedSession=" + (unfinished != null)
                                + "; native synchronization cancellation only, Watch data retained.");
                    }
                    finally { wipe(header); }
                    outgoingPayload = preparedFace;
                }
                if (!msg.dataFrame && IdsApplicationRoute.FIND_MY_LOCAL_SERVICE.equals(msg.topic)) {
                    if (!findMySession.canQueue(BridgeClock.elapsedRealtime())) {
                        if (msg.request != null) OPERATIONAL_REQUESTS.failed(msg.request);
                        log("FIND MY LOCAL TX REJECTED: bounded reply window full; not sent.");
                        continue;
                    }
                    FindMyLocalDeviceCodec.requireSoundType(msg.protobufType);
                    preparedPing = new FindMyLocalDeviceCodec.PlaySoundRequest(
                            System.currentTimeMillis() / 1000.0, null).encode();
                    outgoingPayload = preparedPing;
                }
                if (!msg.dataFrame && IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE.equals(msg.topic)) {
                    BulletinDistributorCodec.MessageAndTrailer split = BulletinDistributorCodec.splitTrailer(msg.payload);
                    try {
                        preparedBulletin = bulletinTransport.wrap(split.trailer != null ? split.messagePayload : msg.payload);
                        outgoingPayload = preparedBulletin;
                    } finally {
                        if (split.messagePayload != msg.payload) wipe(split.messagePayload);
                    }
                }
                log(String.format(
                        Locale.US,
                        "[OUTBOUND APP TX] sending topic=%s type=%d len=%d",
                        msg.topic,
                        msg.protobufType,
                        outgoingPayload != null ? outgoingPayload.length : 0));
                NormalLinkIdsSessionBridge.Output out =
                        msg.health!=null ? activeIdsBridge.sendHealthSyncRequest(outgoingPayload,msg.health.message())
                                : rebootRequest ? activeIdsBridge.sendWatchReboot()
                                : faceRequest ? activeIdsBridge.sendClockFaceCollectionRequest(outgoingPayload)
                                : msg.dataFrame ? activeIdsBridge.sendApplicationData(msg.topic, outgoingPayload)
                                : activeIdsBridge.sendApplicationProtobuf(
                                msg.topic,
                                msg.protobufType,
                                outgoingPayload);
                if(msg.health!=null) {
                    boolean allocated=false;
                    for(var event:out.idsEvents()) {
                        if(event.type==IdsModernSessionCoordinator.EventType.DATA_SENT
                                && IdsApplicationRoute.HEALTH_SYNC_SERVICE.equals(event.topic)
                                && msg.health.message().equals(UUID.fromString(event.messageUuid))) { allocated=true;break; }
                    }
                    if(!allocated)throw new IllegalStateException("Health IDS UUID not allocated");
                    publishHealthSendStatus(msg.health,BridgeCommandCodec.Stage.IDS_QUEUED);
                    log("HEALTH SEND IDS queued: encrypted native request; ciphertext only; samples/anchors not committed.");
                }
                if (msg.request != null) {
                    String messageUuid = null;
                    for (IdsModernSessionCoordinator.SessionEvent event : out.idsEvents()) {
                        if (msg.topic.equals(event.topic)
                                && (msg.dataFrame && event.type == IdsModernSessionCoordinator.EventType.DATA_SENT
                                || event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT
                                && event.protobufType == msg.protobufType)) {
                            messageUuid = event.messageUuid;
                            break;
                        }
                    }
                    OPERATIONAL_REQUESTS.idsQueued(msg.request, msg.topic, messageUuid,
                            msg.request.command().equals("REQUEST_REGISTRY")
                                    ? NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                                    : msg.request.command().equals("REQUEST_DEVICE_ABOUT")
                                    ? NanoSystemSettingsDiagnostics.ABOUT_RESPONSE
                                    : msg.request.command().equals(SysdiagnoseArchiveInventory.COMMAND)
                                    ? SysdiagnoseArchiveInventory.LIST_RESULT
                                    : msg.request.command().equals("PING_WATCH")
                                    ? FindMyLocalDeviceCodec.TYPE_PLAY_SOUND
                                    : faceRequest ? ClockFaceSyncHeaderCodec.FULL_REQUEST : -1);
                }
                processModernIdsOutput(
                        out,
                        remoteCid,
                        "NORMAL OUTBOUND APP TX");
                flushPendingIdsFrames(remoteCid);
            } catch (Exception err) {
                if(msg.health!=null)publishHealthSendStatus(msg.health,BridgeCommandCodec.Stage.FAILED);
                if (msg.request != null) OPERATIONAL_REQUESTS.failed(msg.request);
                log("[OUTBOUND APP TX ERROR] failed to send " + msg.topic + ": " + err.getMessage());
            } finally {
                wipe(preparedBulletin);
                wipe(preparedPing);
                wipe(preparedFace);
                wipe(preparedWifi);
                wipe(preparedPigments);
                wipe(preparedMonogram);
                if(!retained)wipe(msg.payload);
            }
        }
    }

    private void publishClockFaceObservation(UUID epoch) {
        if (operationalPairingId == null || epoch == null) return;
        byte[] frame = null;
        try {
            ClockFaceCollection collection = clockFaceReceiver().snapshot();
            if (collection.observedAt <= 0) return;
            try {
                NativeFacePackageAccess.prepare(BridgePaths.files(), operationalPairingId);
            } catch (Exception unavailable) {
                log("CLOCKFACE EXPORT ACCESS unavailable; collection facts are still published.");
            }
            frame = ClockFaceObservationCodec.encode(ClockFaceObservationCodec.fromCollection(
                    UUID.fromString(operationalPairingId), epoch, collection));
            System.out.println(ClockFaceObservationCodec.PREFIX
                    + java.util.Base64.getEncoder().encodeToString(frame));
            System.out.flush();
            log("CLOCKFACE OBSERVATION published: faces=" + collection.configurations.size()
                    + " complete=" + collection.complete() + " observedAt=" + collection.observedAt);
        } catch (Exception invalid) {
            log("CLOCKFACE OBSERVATION unavailable; native session acceptance unchanged.");
        } finally { wipe(frame); }
    }

    private void beginClockFaceDelta(BridgeCommandCodec.Request request, int remoteCid) throws Exception {
        if (operationalPairingId == null || clockFaceObservationEpoch == null
                || !clockFaceObservationEpoch.equals(request.epoch()) || activeIdsBridge == null
                || clockFaceDelta != null && !clockFaceDelta.terminal()) throw new IllegalStateException("Native face delta is unavailable or busy");
        ClockFaceSyncReceiver receiver = clockFaceReceiver();
        if (receiver.unfinishedSessionForRecovery() != null) throw new IllegalStateException("Native collection receive is unfinished");
        ClockFaceCollection baseline = receiver.snapshot();
        long unixMs = System.currentTimeMillis();
        if (baseline.observedAt > unixMs || unixMs - baseline.observedAt > 300_000) {
            throw new IllegalStateException("Refresh the native face collection before a mutation");
        }
        ClockFaceDeltaPlan plan = ClockFaceDeltaCommand.parse(request.command()).plan(baseline,
                BridgePaths.files().resolve("face-imports"));
        try {
            String session = java.time.format.DateTimeFormatter.ofPattern("'P'uuuu-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(unixMs));
            clockFaceDelta = new ClockFaceDeltaSession(UUID.fromString(operationalPairingId), request.epoch(),
                    clockFaceClient().remotePeerForDelta(), session, plan, BridgeClock.elapsedRealtime());
            clockFaceDeltaRequest = request; clockFaceDeltaReadRequested = false;
            log("CLOCKFACE DELTA begin: kind=" + plan.kind + " nativeSession=" + session
                    + "; delta only, application requires committed readback.");
            advanceClockFaceDelta(remoteCid);
        } catch (Exception failure) { plan.close(); clearClockFaceDeltaOnDisconnect(); throw failure; }
    }

    private void advanceClockFaceDelta(int remoteCid) {
        if (clockFaceDelta == null) return;
        clockFaceDelta.tick(BridgeClock.elapsedRealtime());
        if (activeIdsBridge == null || !clockFaceDelta.epoch.equals(clockFaceObservationEpoch)
                || !clockFaceDelta.pair.toString().equals(operationalPairingId)) clockFaceDelta.disconnected();
        try {
            var stage = clockFaceDelta.stage();
            if (stage == ClockFaceDeltaSession.Stage.START_READY || stage == ClockFaceDeltaSession.Stage.BATCH_READY
                    || stage == ClockFaceDeltaSession.Stage.END_READY) {
                byte[] header = clockFaceClient().reserveHeader(System.currentTimeMillis(), false);
                byte[] packet = null;
                try {
                    packet = clockFaceDelta.prepare(header, BridgeClock.elapsedRealtime());
                    try (var out = activeIdsBridge.sendClockFaceDeltaRequest(packet)) {
                        String identifier = null;
                        for (var event : out.idsEvents()) if (event.type == IdsModernSessionCoordinator.EventType.DATA_SENT
                                && IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE.equals(event.topic)) { identifier = event.messageUuid; break; }
                        if (identifier == null) throw new IllegalStateException("Native delta has no IDS response identifier");
                        clockFaceDelta.sent(UUID.fromString(identifier), BridgeClock.elapsedRealtime());
                        if (stage == ClockFaceDeltaSession.Stage.START_READY) OPERATIONAL_REQUESTS.idsQueued(clockFaceDeltaRequest,
                                IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, identifier, ClockFaceSyncAccept.START);
                        log("CLOCKFACE DELTA TX: stage=" + clockFaceDelta.stage() + " bytes=" + packet.length);
                        processModernIdsOutput(out, remoteCid, "CLOCKFACE DELTA TX"); flushPendingIdsFrames(remoteCid);
                    }
                } finally { wipe(header); wipe(packet); }
            } else if (stage == ClockFaceDeltaSession.Stage.READBACK_WAIT && !clockFaceDeltaReadRequested
                    && clockFaceReceiver().unfinishedSessionForRecovery() == null
                    && System.currentTimeMillis() >= clockFaceClient().earliestFullRequestMs()) {
                byte[] header = clockFaceClient().reserveHeader(System.currentTimeMillis(), true);
                byte[] packet = null;
                try {
                    packet = ClockFaceSyncHeaderCodec.fullRequest(header);
                    try (var out = activeIdsBridge.sendClockFaceCollectionRequest(packet)) {
                        clockFaceDeltaReadRequested = true;
                        processModernIdsOutput(out, remoteCid, "CLOCKFACE DELTA READBACK"); flushPendingIdsFrames(remoteCid);
                        log("CLOCKFACE DELTA READBACK requested: no cancellation; current facts require native END.");
                    }
                } finally { wipe(header); wipe(packet); }
            }
        } catch (Exception failure) {
            if (clockFaceDelta != null) clockFaceDelta.disconnected();
            log("CLOCKFACE DELTA outcome unknown: " + safeMessage(failure));
        }
        finishClockFaceDeltaIfTerminal();
    }

    private void finishClockFaceDeltaIfTerminal() {
        if (clockFaceDelta == null || !clockFaceDelta.terminal()) return;
        log("CLOCKFACE DELTA result: kind=" + clockFaceDelta.kind() + " stage=" + clockFaceDelta.stage()
                + "; APPLIED is emitted only after matching committed native collection.");
        if (clockFaceDeltaRequest != null) {
            publishCommandStatus(new BridgeCommandCodec.Status(clockFaceDeltaRequest.id(), clockFaceDeltaRequest.epoch(),
                    clockFaceDelta.stage() == ClockFaceDeltaSession.Stage.APPLIED
                            ? BridgeCommandCodec.Stage.NATIVE_FACE_APPLIED
                            : clockFaceDelta.stage() == ClockFaceDeltaSession.Stage.REJECTED
                            ? BridgeCommandCodec.Stage.REJECTED : BridgeCommandCodec.Stage.UNKNOWN));
        }
        clockFaceDelta.close(); clockFaceDelta = null; clockFaceDeltaRequest = null; clockFaceDeltaReadRequested = false;
    }

    private void clearClockFaceDeltaOnDisconnect() {
        if (clockFaceDelta != null) { clockFaceDelta.disconnected(); finishClockFaceDeltaIfTerminal(); }
    }

    private ClockFaceSyncClient clockFaceClient() {
        if (activePairingSessionRecord == null) throw new IllegalStateException("No durable clockface pair");
        return new ClockFaceSyncClient(BridgePaths.files().resolve("clockface-client"),
                OperationalSessionPolicy.pairingId(activePairingSessionRecord));
    }

    private ClockFaceSyncReceiver retainedClockFaceReceiver;
    private String retainedClockFacePair;

    private ClockFaceSyncReceiver clockFaceReceiver() {
        if (activePairingSessionRecord == null) throw new IllegalStateException("No durable clockface pair");
        String pair = OperationalSessionPolicy.pairingId(activePairingSessionRecord);
        if (retainedClockFaceReceiver != null && pair.equals(retainedClockFacePair)) return retainedClockFaceReceiver;
        ClockFaceSyncReceiver receiver = new ClockFaceSyncReceiver(
                BridgePaths.files().resolve("clockface-journal"),
                BridgePaths.files().resolve("clockface-receiver"),
                pair);
        try { log("CLOCKFACE JOURNAL: retired=" + receiver.compactJournal()); }
        catch (IOException | IllegalArgumentException failure) { log("CLOCKFACE JOURNAL: maintenance deferred; durable records preserved"); }
        retainedClockFaceReceiver = receiver;
        retainedClockFacePair = pair;
        return receiver;
    }

    private void forwardEncryptedHealthData(IdsModernSessionCoordinator.SessionEvent event) {
        if (operationalPairingId == null || clockFaceObservationEpoch == null
                || !operationalSnapshotPublished
                || !IdsUtunConnectionName.isClassCIdentifier(event.service)
                || event.command != IdsSocketPairCodec.COMMAND_DATA) {
            log("HEALTH DATA ignored: pair/epoch/Class-C data unavailable; no native reply or anchors.");
            return;
        }
        byte[] payload = event.payload(), key = null, frame = null;
        try {
            if (idsDeviceInfoExchange != null && idsDeviceInfoExchange.hasVerifiedPeerIdentity()) {
                key = idsDeviceInfoExchange.peerClassAPublicKey();
            }
            try (var incoming = new HealthDataEventCodec.Event(UUID.fromString(operationalPairingId),
                    clockFaceObservationEpoch, UUID.fromString(event.messageUuid),
                    event.peerResponseIdentifier == null || event.peerResponseIdentifier.isEmpty()
                            ? null : UUID.fromString(event.peerResponseIdentifier),
                    event.streamId, event.flags, key, payload)) {
                frame = HealthDataEventCodec.encode(incoming);
                System.out.println(HealthDataEventCodec.PREFIX + BridgeBase64.encodeToString(frame,BridgeBase64.NO_WRAP));
                System.out.flush();
                log("HEALTH DATA forwarded: encryptedBytes=" + incoming.encryptedSize()
                        + " verifiedPeerKey=" + (key != null)
                        + "; no plaintext/sample/anchor publication; payload logged=false.");
            }
        } catch (IllegalArgumentException invalid) {
            log("HEALTH DATA rejected: invalid or oversized encrypted native dictionary; payload logged=false.");
        } finally { wipe(payload); wipe(key); wipe(frame); }
    }

    private void publishHealthPeerIdentity() {
        if (healthPeerIdentityPublished || operationalPairingId == null || !operationalSnapshotPublished
                || clockFaceObservationEpoch == null || idsDeviceInfoExchange == null
                || !idsDeviceInfoExchange.hasVerifiedPeerIdentity()) return;
        byte[] key = idsDeviceInfoExchange.peerClassAPublicKey(), frame = null;
        try (var identity = new HealthPeerIdentityCodec.Identity(UUID.fromString(operationalPairingId),
                clockFaceObservationEpoch, UUID.fromString(activePairingSessionRecord.localIdsDeviceUuid()),
                UUID.fromString(idsDeviceInfoExchange.verifiedPeerIdentifier()), key)) {
            frame = HealthPeerIdentityCodec.encode(identity);
            System.out.println(HealthPeerIdentityCodec.PREFIX + BridgeBase64.encodeToString(frame,BridgeBase64.NO_WRAP));
            System.out.flush();
            healthPeerIdentityPublished = true;
            log("HEALTH PEER forwarded: correlated IDS command-12 public identity; key values logged=false.");
        } finally { wipe(key); wipe(frame); }
    }

    private PigmentPreferenceMirror pigmentMirror() throws IOException {
        return pigmentMirror(UUID.fromString(operationalPairingId));
    }

    private MonogramPreferenceMirror monogramMirror(UUID pair) throws IOException {
        if (monogramMirror == null || !monogramMirror.available() || !pair.equals(monogramMirrorPair)) {
            monogramMirror = new MonogramPreferenceMirror(BridgePaths.files().resolve("monogram-mirrors"), pair);
            monogramMirrorPair = pair;
        }
        return monogramMirror;
    }
    private void publishMonogramMirror() throws IOException {
        if (!operationalSnapshotPublished || operationalPairingId == null || clockFaceObservationEpoch == null) return;
        byte[] frame = MonogramMirrorIpcCodec.encode(monogramMirror(UUID.fromString(operationalPairingId)).snapshot(clockFaceObservationEpoch));
        try {
            System.out.println(MonogramMirrorIpcCodec.PREFIX + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
            System.out.flush();
        } finally { wipe(frame); }
    }

    private PigmentPreferenceMirror pigmentMirror(UUID pair) throws IOException {
        if (pigmentMirror == null || !pigmentMirror.available() || !pair.equals(pigmentMirrorPair)) {
            var next = new PigmentPreferenceMirror(BridgePaths.files().resolve("pigment-mirrors"), pair);
            pigmentMirror = next;
            pigmentMirrorPair = pair;
        }
        return pigmentMirror;
    }

    private void publishPigmentMirror() throws IOException {
        if (!operationalSnapshotPublished || operationalPairingId == null || clockFaceObservationEpoch == null) return;
        var snapshot = pigmentMirror().snapshot(clockFaceObservationEpoch);
        if (snapshot == null) return;
        byte[] frame = PigmentMirrorIpcCodec.encode(snapshot);
        try {
            System.out.println(PigmentMirrorIpcCodec.PREFIX + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
            System.out.flush();
        } finally { wipe(frame); }
    }

    private void handleIncomingProtobufEvent(
            IdsModernSessionCoordinator.SessionEvent event) {
        if (event == null || event.topic == null) {
            return;
        }
        publishHealthRegistryObservation(event);
        UUID pigmentOwner = activeIdsBridge == null
                || !PigmentPreferenceCodec.isObservationEnvelope(event.topic, event.protobufType, event.response)
                ? null : PigmentPreferenceOwnerPolicy.owner(
                        activePairingSessionRecord, operationalPairingId, idsControlReadyObserved);
        if (pigmentOwner != null) {
            byte[] payload = event.payload();
            try (var envelope = NtkPreferenceEnvelope.decode(payload, System.currentTimeMillis())) {
                long now = System.currentTimeMillis();
                var lists = PigmentPreferenceCodec.decodeObservedLists(envelope);
                try { if (pigmentMirror(pigmentOwner).observe(lists.getOrDefault(PigmentPreferenceCodec.KEY, List.of()),
                        lists.getOrDefault(PigmentPreferenceCodec.AUTO_KEY, List.of()), now)) {
                    // Setup has no operational epoch yet. Its durable paired
                    // value is published only when the same pair becomes READY.
                    publishPigmentMirror();
                } } catch (IOException invalid) { log("PIGMENT MIRROR update unavailable; values logged=false."); }
                try { if (monogramMirror(pigmentOwner).observe(MonogramPreferenceCodec.decodeObserved(envelope), now)) publishMonogramMirror(); }
                catch (IOException invalid) { log("MONOGRAM MIRROR update unavailable; values logged=false."); }
            } catch (IllegalArgumentException invalid) {
                log("NATIVE PREFERENCE MIRROR receipt refused; values logged=false.");
            }
            finally { wipe(payload); }
        }
        if (IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE.equals(event.topic)) {
            byte[] payload = event.payload();
            byte[] acknowledgement = null;
            try {
                BulletinTransportSession.Received received = bulletinTransport.receive(event.protobufType, event.response, payload);
                if (!received.fresh()) {
                    log("BULLETIN RX DUPLICATE: action not forwarded; identifiers/content logged=false.");
                    return;
                }
                acknowledgement = received.acknowledgement();
                if (acknowledgement != null) {
                    if (!offerOutbound(outboundAppMessages, new OutboundAppMessage(
                            IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE, 12, acknowledgement))) {
                        log("BULLETIN SESSION ACK rejected: outbound queue full.");
                    }
                }
                log("BULLETIN SESSION RX: type=" + event.protobufType + " localState=" + received.localState()
                        + "; handshake state is not notification delivery.");
            } catch (IllegalArgumentException malformed) {
                log("BULLETIN RX rejected: invalid native session envelope; content logged=false.");
                return;
            } finally { wipe(payload); wipe(acknowledgement); }
        }
        if (IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE.equals(event.topic)
                || IdsApplicationRoute.HEALTH_SYNC_SERVICE.equals(event.topic)
                || IdsApplicationRoute.TELEPHONY_SERVICE.equals(event.topic)
                || PigmentPreferenceCodec.isObservationEnvelope(event.topic, event.protobufType, event.response)) {
            byte[] payload = event.payload();
            byte[] frame = null;
            try (BridgeApplicationEventCodec.Event applicationEvent =
                         new BridgeApplicationEventCodec.Event(event.topic, event.protobufType, event.response, payload)) {
                frame = BridgeApplicationEventCodec.encode(applicationEvent);
                System.out.println(BridgeApplicationEventCodec.PREFIX + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
                System.out.flush();
            } catch (IllegalArgumentException rejected) {
                log("APPLICATION IPC REJECTED: invalid or oversized event; payload logged=false.");
            } finally {
                wipe(payload);
                wipe(frame);
            }
        }
        if (IdsApplicationRoute.FIND_MY_LOCAL_SERVICE.equals(event.topic)) {
            if (!event.response) {
                byte[] payload = event.payload();
                byte[] frame = null;
                try {
                    var incoming = findMyPhone.receive(event.protobufType, false, event.messageUuid,
                            payload, System.currentTimeMillis(), BridgeClock.elapsedRealtime());
                    if (incoming != null && incoming.request() != null) {
                        frame = FindMyPhoneIpcCodec.encode(incoming.request());
                        System.out.println(FindMyPhoneIpcCodec.REQUEST_PREFIX
                                + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
                        System.out.flush();
                        log("FIND MY PHONE REQUEST forwarded: type=" + event.protobufType
                                + " fresh=true; waiting for APK effect result; identifiers logged=false.");
                    } else if (incoming != null && incoming.cached() != null) {
                        queuePhonePingReply(incoming.cached());
                        log("FIND MY PHONE duplicate: cached reply only; effect not replayed.");
                    } else log("FIND MY PHONE request ignored: stale/duplicate/unsupported/unready.");
                } catch (IllegalArgumentException malformed) {
                    log("FIND MY PHONE request rejected: malformed native request; no effect.");
                } finally { wipe(payload); wipe(frame); }
            }
        } else if (IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE.equals(event.topic)) {
            if (event.protobufType == BulletinDistributorCodec.TYPE_DISMISS_ACTION) {
                log("WATCH ACTION: Notification dismissed on Apple Watch");
                System.out.println("EVENT:NOTIFICATION_DISMISS");
                System.out.flush();
            } else if (event.protobufType == BulletinDistributorCodec.TYPE_SUPPLEMENTARY_ACTION) {
                log("WATCH ACTION: Notification reply from Apple Watch");
                System.out.println("EVENT:NOTIFICATION_REPLY");
                System.out.flush();
            }
        } else if (IdsApplicationRoute.HEALTH_SYNC_SERVICE.equals(event.topic)) {
            log("WATCH TELEMETRY: Health sync packet received from Apple Watch");
            System.out.println("EVENT:HEALTH_UPDATE");
            System.out.flush();
        }
        logWatchTelemetryEvent(event);
    }

    private void publishHealthRegistryObservation(IdsModernSessionCoordinator.SessionEvent event) {
        if(operationalPairingId==null || clockFaceObservationEpoch==null || !operationalSnapshotPublished
                || activePairingSessionRecord==null || !NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                || event.protobufType!=NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                || !IdsUtunConnectionName.isClassCIdentifier(event.service))return;
        byte[] payload=event.payload(),frame=null;
        NanoRegistryPropertyCodec.PropertiesChanged snapshot=null;
        try {
            snapshot=NanoRegistryPropertyCodec.decodePropertiesChanged(payload);
            var observation=HealthRegistryObservationCodec.fromSnapshot(UUID.fromString(operationalPairingId),
                    clockFaceObservationEpoch,UUID.fromString(activePairingSessionRecord.localIdsDeviceUuid()),
                    UUID.fromString(activePairingSessionRecord.peerIdsDeviceId()),snapshot);
            frame=HealthRegistryObservationCodec.encode(observation);
            System.out.println(HealthRegistryObservationCodec.PREFIX+BridgeBase64.encodeToString(frame,BridgeBase64.NO_WRAP));System.out.flush();
        } catch(IllegalArgumentException invalid) {
            log("HEALTH registry observation unavailable: "+(snapshot==null?"snapshot malformed":HealthRegistryObservationCodec.describe(snapshot))
                    +"; values logged=false.");
        } finally { if(snapshot!=null)snapshot.destroy();wipe(payload);wipe(frame); }
    }

    private void queuePhonePingReply(FindMyPhoneIpcCodec.Result result) {
        if (pendingPhoneReplies.size() >= 16) {
            log("FIND MY PHONE reply queue full; delivery unconfirmed.");
        } else pendingPhoneReplies.add(result);
    }

    private void drainPhonePingReplies(int remoteCid) {
        FindMyPhoneIpcCodec.Result result;
        while ((result = FIND_MY_PHONE_RESULTS.poll()) != null) {
            var request = findMyPhone.complete(result, BridgeClock.elapsedRealtime());
            if (request != null) queuePhonePingReply(new FindMyPhoneIpcCodec.Result(
                    request.epoch(), request.messageId(), request.type(), result.didPlay()));
            else log("FIND MY PHONE result ignored: wrong epoch/id/type, expired or already completed.");
        }
        if (activeIdsBridge == null) return;
        while ((result = pendingPhoneReplies.poll()) != null) {
            if (!findMyPhone.current(result.epoch())) continue;
            byte[] payload = new FindMyLocalDeviceCodec.PlaySoundResponse(result.didPlay()).encode();
            try {
                processModernIdsOutput(activeIdsBridge.sendFindMyLocalResponse(
                        result.type(), payload, result.messageId()), remoteCid, "FIND MY PHONE RESULT");
                flushPendingIdsFrames(remoteCid);
                log("FIND MY PHONE RESULT sent: type=" + result.type() + " didPlay=" + result.didPlay()
                        + " response=true; actual phone effect, not a transport ACK.");
            } catch (Exception failure) {
                log("FIND MY PHONE reply send failed: " + failure.getClass().getSimpleName()
                        + "; effect will not be replayed.");
            } finally { wipe(payload); }
        }
    }

    private void logWatchRegistryState(IdsModernSessionCoordinator.SessionEvent event) {
        if (!NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                && !NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(event.topic)) return;
        if (event.protobufType != NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                && !(event.protobufType == NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST
                && event.response)) return;
        byte[] payload = event.payload();
        NanoRegistryPropertyCodec.ApplicationMessage decoded = null;
        try {
            decoded = event.protobufType == NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                    ? NanoRegistryPropertyCodec.decodePropertiesChanged(payload)
                    : NanoRegistryPropertyCodec.decodePropertyResponse(payload);
            List<NanoRegistryPropertyCodec.Property> properties =
                    decoded instanceof NanoRegistryPropertyCodec.PropertiesChanged changed
                            ? changed.properties
                            : ((NanoRegistryPropertyCodec.PropertyResponse) decoded).properties;
            StringBuilder state = new StringBuilder();
            int matchingKeys = 0;
            int numberValues = 0;
            int stringValues = 0;
            int dataValues = 0;
            for (NanoRegistryPropertyCodec.Property property : properties) {
                if (property.value != null) {
                    if (property.value.numberValue != null) numberValues++;
                    if (property.value.stringValue != null) stringValues++;
                    if (property.value.dataValue != null) dataValues++;
                }
                if (!List.of("isSetup", "isPaired", "compatibilityState", "pairingMode",
                        "status", "isActive", "isConnected").contains(property.name)) continue;
                matchingKeys++;
                if (property.value == null || property.value.isError
                        || (property.value.hasIsSet && !property.value.isSet)
                        || property.value.numberValue == null) {
                    state.append(' ').append(property.name).append("=unavailable");
                    continue;
                }
                NanoRegistryPropertyCodec.NumberValue value = property.value.numberValue;
                Object scalar = null;
                if (value.boolValue != null) scalar = value.boolValue;
                else if (value.int32Value != null) scalar = value.int32Value;
                else if (value.int64Value != null) scalar = value.int64Value;
                state.append(' ').append(property.name).append('=').append(
                        scalar != null ? scalar : "unsupported-number");
            }
            log("WATCH REGISTRY RX: type=" + event.protobufType + " response=" + event.response
                    + " properties=" + properties.size() + " setupKeys=" + matchingKeys
                    + " numberValues=" + numberValues + " stringValues=" + stringValues
                    + " dataValues=" + dataValues + state + "; other values logged=false.");
        } catch (IllegalArgumentException rejected) {
            log("WATCH REGISTRY RX: decode failed: " + rejected.getMessage());
        } finally {
            if (decoded != null) decoded.destroy();
            wipe(payload);
        }
    }

    private void logWatchTelemetryEvent(
            IdsModernSessionCoordinator.SessionEvent event) {
        if (event == null || event.topic == null) {
            return;
        }
        if (event.type != IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                && event.type != IdsModernSessionCoordinator.EventType.DATA_RECEIVED) {
            return;
        }
        if (event.topic.endsWith("sharing.paireddevice")) {
            byte[] statePayload = event.payload();
            try {
                Object state = OpackDecoder.decode(statePayload);
                log("WATCH STATE RX: sharing.paireddevice type=" + event.protobufType
                        + " " + OpackDecoder.summarize(state));
            } catch (IllegalArgumentException undecodable) {
                log("WATCH STATE RX: sharing.paireddevice type=" + event.protobufType
                        + " not OPACK; " + ProtobufTelemetry.describe(statePayload));
            } finally { wipe(statePayload); }
        } else if (event.topic.endsWith("ct.commcenter.sim")
                || event.topic.endsWith("pushproxy")
                || event.topic.endsWith("phonecontinuity")) {
            byte[] telemetryPayload = event.payload();
            try {
                log("WATCH TELEMETRY RX: topic=" + idsTopicLogLabel(event.topic)
                        + " type=" + event.protobufType + " "
                        + ProtobufTelemetry.describe(telemetryPayload));
            } finally { wipe(telemetryPayload); }
        }
    }

    /**
     * Fires proactive RFC 5961 resets for Watch TCP flows that outlived
     * the previous process. The Watch identityservicesd keeps its
     * control connecting/connected flag while the old connection looks
     * ESTABLISHED, and only a fatal utun socket error clears it and
     * retries the connector (watchOS 26.2 FUN_10035caa4 path); without
     * these resets a paired reconnect waits out the Watch's ~8.6-min
     * TCP RTO (live 13:08-13:16) or stalls forever when the previous
     * session acknowledged everything (live 0.2.305 15:10 silence).
     * Three bursts cover ERTM loss; the resets are idempotent.
     */
    private void fireProactiveStaleFlowResets(
            int remoteCid)
            throws Exception {
        if (activeIdsBridge == null) {
            return;
        }
        boolean any = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            NormalLinkIdsSessionBridge.Output resets =
                    activeIdsBridge.resetStaleFlows();
            if (resets.ertmFrames().isEmpty()
                    && attempt == 1) {
                resets.close();
                return;
            }
            any = true;
            processModernIdsOutput(
                    resets,
                    remoteCid,
                    "STALE FLOW RESET " + attempt);
            flushPendingIdsFrames(remoteCid);
            if (attempt < 3) {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (any) {
            log("STALE FLOW RESET: proactive RFC 5961 resets fired; "
                    + "the Watch should now drop its stale control TCB "
                    + "and retry ids-control-channel (FUN_10035caa4).");
        }
    }

    /** Drains SEED_STALE_FLOW commands on the HAL thread. */
    private void drainStaleFlowSeeds(
            int remoteCid)
            throws Exception {
        NativeApplicationServiceDiscovery discovery = PENDING_NATIVE_SNAPSHOT_DISCOVERY.getAndSet(null);
        if (discovery != null) {
            try {
                if (activeIdsBridge == null) throw new IllegalStateException("No active normal session");
                activeIdsBridge.discoverNativeSnapshotService(discovery, ProtocolHost::log);
                sendNormalFrames(remoteCid, activeIdsBridge.pollLinkDirectorAnnouncements(
                        System.nanoTime() / 1_000_000L), "NATIVE SNAPSHOT SERVICE DISCOVERY");
            } catch (IllegalStateException unavailable) {
                log("APPLICATION SERVICE DISCOVERY: unavailable on current session; no pairing or setup changes.");
            }
        }
        if (activeIdsBridge != null && PENDING_IDS_KEY_PROBE.getAndSet(false)) {
            processModernIdsOutput(activeIdsBridge.startPairedClassDKeyProbe(),
                    remoteCid, "DIAGNOSTIC NWSC KEY PROBE");
            flushPendingIdsFrames(remoteCid);
        }
        if (activeIdsBridge != null && PENDING_IDS_CONTROL_OPEN.getAndSet(false)) {
            try {
                processModernIdsOutput(activeIdsBridge.startControl(),
                        remoteCid, "DIAGNOSTIC IDS CONTROL OPEN");
                flushPendingIdsFrames(remoteCid);
            } catch (IllegalStateException unavailable) {
                log("DIAGNOSTIC IDS CONTROL OPEN: " + unavailable.getMessage());
            }
        }
        if (PENDING_CLOSE_IKE_SESSION.getAndSet(false) && activeIdsBridge != null) {
            List<byte[]> frames = activeIdsBridge.requestIkeDeletes();
            try {
                sendNormalFrames(remoteCid, frames, "AUTHENTICATED IKE DELETE");
                log("IKE DELETE SENT: Class-C/D transient session teardown requested; bond and activation retained.");
            } finally { for (byte[] frame : frames) wipe(frame); }
        }
        PendingStaleFlowSeed seed;
        while ((seed = PENDING_STALE_FLOW_SEEDS.poll()) != null) {
            try {
                boolean classD =
                        seed.dataClass
                                == OrdinaryIkeAuth.DataClass.CLASS_D;
                byte[] localAddress =
                        activePairingSessionRecord
                                .copyConfirmedLocalAddress(classD);
                byte[] remoteAddress =
                        activePairingSessionRecord
                                .copyConfirmedRemoteAddress(classD);
                try {
                    IdsStaleFlowRecord record =
                            new IdsStaleFlowRecord(
                                    seed.dataClass,
                                    localAddress,
                                    remoteAddress,
                                    seed.localPort,
                                    seed.remotePort,
                                    seed.peerAck,
                                    seed.peerTimestamp,
                                    seed.lastSeenEpochMs);
                    IdsStaleFlowStore.merge(
                            List.of(record));
                    if (activeIdsBridge != null) {
                        activeIdsBridge.seedStaleFlows(
                                List.of(record));
                        log("STALE FLOW SEEDED: "
                                + record.dataClass + " "
                                + record.remotePort + "->"
                                + record.localPort
                                + " seq=" + record.peerAck
                                + "; firing proactive resets.");
                        processModernIdsOutput(
                                activeIdsBridge.resetStaleFlows(),
                                remoteCid,
                                "STALE FLOW RESET (seeded)");
                        flushPendingIdsFrames(remoteCid);
                    }
                } finally {
                    wipe(localAddress);
                    wipe(remoteAddress);
                }
            } catch (RuntimeException failure) {
                log("STALE FLOW SEED FAILED: "
                        + failure.getMessage());
            }
        }
    }

    /**
     * Persists the flows this session observed so the next process can
     * reset them proactively. Called when the control channel is ready
     * and when post-commit completes; both moments follow the arrival
     * of every Watch-initiated flow of the session.
     */
    private void persistStaleFlowObservations(
            String reason) {
        if (activeIdsBridge == null) {
            return;
        }
        try {
            List<IdsStaleFlowRecord> observed =
                    activeIdsBridge.staleFlowObservations();
            if (!observed.isEmpty()) {
                IdsStaleFlowStore.merge(
                        observed);
                log("STALE FLOW STORE: persisted "
                        + observed.size()
                        + " observed flows ("
                        + reason
                        + ").");
            }
        } catch (RuntimeException failure) {
            log("STALE FLOW STORE persist failed: "
                    + failure.getMessage());
        }
    }

    /**
     * Pushes IDS application frames that were prepared before the Watch had
     * a usable service lane.
     *
     * <p>Post-commit setup runs as soon as the commit barrier clears, which
     * is ahead of the Watch opening its PBBridge lane, so those messages —
     * including the activation permit — are parked rather than sent. Without
     * this pump they are never delivered and the Watch never asks the phone
     * to run Albert activation.</p>
     */
    private void flushPendingIdsFrames(
            int remoteCid)
            throws Exception {
        if (activeIdsBridge == null) {
            return;
        }
        if (activeIdsBridge.hasDeferredIpv6()) {
            int held = activeIdsBridge.deferredIpv6Count();
            NormalLinkIdsSessionBridge.Output resumed =
                    activeIdsBridge.drainDeferredIpv6();
            try (resumed) {
                if (!resumed.ertmFrames().isEmpty()) {
                    if (activationPermitQueuedAtMs >= 0
                            && activationPermitSentAtMs < 0) {
                        activationPermitL2capFrames +=
                                resumed.ertmFrames().size();
                        activationPermitAwaitingFlush = true;
                    }
                    log("IDS DEFERRED IPV6 RESUMED: frames="
                            + resumed.ertmFrames().size()
                            + " held=" + held);
                    sendNormalFrames(
                            remoteCid,
                            resumed.ertmFrames(),
                            "IDS DEFERRED IPV6");
                }
            }
        }
        if (!activeIdsBridge.hasPendingApplicationFrames()) {
            completePendingPostCommitSends(remoteCid);
            return;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            NormalLinkIdsSessionBridge.Output flushed =
                    activeIdsBridge.flushPendingApplicationFrames();
            try (flushed) {
                if (flushed.ertmFrames().isEmpty()) {
                    long now = BridgeClock.elapsedRealtime();
                    if (now - lastPendingStallLogMs >= 2_000) {
                        lastPendingStallLogMs = now;
                        log("POST-COMMIT PENDING STALLED: "
                                + activeIdsBridge.pendingApplicationFramesSummary());
                    }
                    return;
                }
                if (activationPermitQueuedAtMs >= 0
                        && activationPermitSentAtMs < 0
                        && !flushed.ertmFrames().isEmpty()) {
                    activationPermitL2capFrames +=
                            flushed.ertmFrames().size();
                    activationPermitAwaitingFlush = true;
                }
                log("POST-COMMIT PENDING FLUSH: frames="
                        + flushed.ertmFrames().size());
                sendNormalFrames(
                        remoteCid,
                        flushed.ertmFrames(),
                        "POST-COMMIT PENDING FLUSH");
            }
            if (!activeIdsBridge.hasPendingApplicationFrames()) {
                completePendingPostCommitSends(remoteCid);
                return;
            }
        }
    }

    private void markActivationPermitOnWireIfFlushed() {
        if (!activationPermitAwaitingFlush
                || activationPermitL2capFrames <= 0
                || activeIdsBridge == null
                || activeIdsBridge.hasPendingApplicationFrames()
                || activeIdsBridge.hasDeferredIpv6()
                || pendingPostCommitSends.size() > 0) {
            return;
        }
        activationPermitAwaitingFlush = false;
        activationPermitSentAtMs = BridgeClock.elapsedRealtime();
        log("POST-COMMIT: activation permit flushed after Handshake; awaiting Watch-driven "
                + "ProxyActivation request for Albert HTTPS activation.");
    }

    /**
     * Reports the absence of an activation response. Silence cannot establish
     * whether this Watch is already activated, so the durable state is unchanged.
     *
     * <p>Live 0.2.173 (session 21:49): when the permit crosses the Watch's
     * own Class-C snapshot on the wire, the Watch can drop it and never
     * follow up with ProxyActivation — the link then idles for minutes.
     * iOS re-asserts CanBeginActivation while it waits, so re-send the
     * permit a bounded number of times before declaring the stall.</p>
     *
     * <p>Live 0.2.205 (2026-10-04 00:59, fresh PIN, same UDID as the
     * 13:56 activation): message 15 does start a drmHandshake session
     * and Albert returns 200, but the Watch never sends the activation
     * ProxyActivation. The 13:56 success was CanBegin alone, answered
     * with BeganActivating 56 ms later. Automatic RetryActivation is
     * therefore not a fallback.</p>
     *
     * <p>The grace starts only after a permit frame is actually flushed.
     * Live 0.2.208 queued the first CanBegin while the ERTM window was
     * full (PERMIT HELD) and the clock still ran, so the resend budget
     * was spent before the Watch had seen the permit.</p>
     */
    private void driveActivationFallbackIfStalled(
            int remoteCid)
            throws Exception {
        if (activePostCommitCoordinator == null
                || activationPermitSentAtMs < 0
                || watchDrivenActivationObserved) {
            return;
        }
        if (BridgeClock.elapsedRealtime() - activationPermitSentAtMs
                < ACTIVATION_REQUEST_GRACE_MS) {
            return;
        }
        AppleWatchPostCommitCoordinator.Snapshot snapshot =
                activePostCommitCoordinator.snapshot();
        if (snapshot.activationConfirmed) {
            activationPermitSentAtMs = -1L;
            activationPermitQueuedAtMs = -1L;
            return;
        }
        if (activationPermitResendCount < 2
                && !snapshot.activationConfirmed
                && activePostCommitAdapter != null
                && activeIdsBridge != null) {
            activationPermitResendCount++;
            activationPermitSentAtMs = -1L;
            activationPermitL2capFrames = 0;
            activationPermitAwaitingFlush = false;
            AppleWatchPostCommitCoordinator.Action resend =
                    AppleWatchPostCommitCoordinator.Action.activation(
                            snapshot.generation,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_ACTIVATION_PERMIT,
                            null,
                            snapshot.activationAttempt
                                    + 1000
                                    + activationPermitResendCount);
            try {
                AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                        HalPostCommitOrchestrator.prepareActionSend(
                                resend,
                                activePostCommitAdapter);
                submitPostCommitSend(
                        prepared,
                        resend,
                        remoteCid);
                markActivationPermitOnWireIfFlushed();
                log("ACTIVATION PERMIT RE-SEND "
                        + activationPermitResendCount
                        + "/2: Watch sent no ProxyActivation within "
                        + ACTIVATION_REQUEST_GRACE_MS
                        + " ms; re-asserting CanBeginActivation. "
                        + "RetryActivation is not sent automatically.");
            } catch (RuntimeException resendFailure) {
                activationPermitSentAtMs = BridgeClock.elapsedRealtime();
                log("ACTIVATION PERMIT RE-SEND failed: "
                        + safeMessage(resendFailure));
            }
            return;
        }
        AppleWatchPostCommitCoordinator.Snapshot finished =
                activePostCommitCoordinator.snapshot();
        if (finished.gizmoFinishedBeforeSession) {
            List<AppleWatchPostCommitCoordinator.Action> accepted =
                    activePostCommitCoordinator
                            .onAlreadyActivatedWatchAccepted(
                                    finished.generation);
            if (!accepted.isEmpty()) {
                activationPermitSentAtMs = -1L;
                activationPermitQueuedAtMs = -1L;
                log("ACTIVATION ALREADY FINISHED: flushed CanBeginActivation "
                        + "was answered only by empty pbbridge type 4; the "
                        + "Watch did not start ProxyActivation. Continuing "
                        + "setup without a new Albert exchange.");
                executePostCommitActions(
                        accepted,
                        remoteCid);
                return;
            }
        }
        activationPermitSentAtMs = -1L;
        activationPermitQueuedAtMs = -1L;
        log("ACTIVATION NOT REQUESTED: Watch sent no ProxyActivation within "
                + ACTIVATION_REQUEST_GRACE_MS
                + " ms; no Albert HTTPS exchange was performed. "
                + "Waiting for an authenticated activation result; state remains unverified.");
        System.out.println("EVENT:SETUP_WAIT:activation");
        System.out.flush();
    }

    private void processModernIdsOutput(
            NormalLinkIdsSessionBridge.Output output,
            int remoteCid,
            String label)
            throws Exception {
        List<IdsBootstrapState.Action> actions =
                new ArrayList<>();
        List<AppleWatchInitialSetupIdsAdapter.Action>
                initialActions =
                new ArrayList<>();
        try (output) {
            sendNormalFrames(
                    remoteCid,
                    output.ertmFrames(),
                    label);
            completePendingPostCommitSends(remoteCid);

            for (IdsModernSessionCoordinator.SessionEvent event
                    : output.idsEvents()) {
                if (event.type == IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED) {
                    OPERATIONAL_REQUESTS.appAck(event.topic, event.peerResponseIdentifier, BridgeClock.elapsedRealtime());
                    if (!initialWifiReceiptLogged && initialWifiMessageUuid != null
                            && WifiNetworkSyncCodec.TOPIC.equals(event.topic)
                            && initialWifiMessageUuid.equals(event.peerResponseIdentifier)) {
                        initialWifiReceiptLogged = true;
                        log("WIFI SETUP AUTO APP ACK: Watch received the network archive; joining the network remains unconfirmed.");
                    }
                } else if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                        && !IdsApplicationRoute.FIND_MY_LOCAL_SERVICE.equals(event.topic)) {
                    OPERATIONAL_REQUESTS.appResponse(event.topic, event.peerResponseIdentifier,
                            event.protobufType, event.response, BridgeClock.elapsedRealtime());
                }
                // Live 0.2.190: a committed-but-unactivated Watch can drive
                // ProxyActivation right after the timesync answer, before
                // any this-session Class-C snapshot. That authenticated
                // request is itself the Albert evidence: stash it and let
                // it authorize post-commit instead of dropping it into the
                // NanoRegistry-only initial adapter.
                if (event.type
                                == IdsModernSessionCoordinator.EventType
                                .PROTOBUF_RECEIVED
                        && IdsApplicationRoute.PB_BRIDGE_SERVICE.equals(
                                event.topic)
                        && event.protobufType
                                == PbBridgeCodec.TYPE_PROXY_ACTIVATION
                        && activePostCommitAdapter == null) {
                    watchProxyActivationObserved = true;
                    if (pendingWatchProxyActivation == null) {
                        pendingWatchProxyActivation = event.copy();
                        log("WATCH PROXY ACTIVATION OBSERVED: stashing the "
                                + "request and authorizing post-commit "
                                + "without a this-session Class-C snapshot "
                                + "(live 0.2.190).");
                    }
                    startPostCommitIfAuthorized(
                            remoteCid);
                }
                if (SysdiagnoseArchiveInventory.TOPIC.equals(event.topic)
                        && (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT
                        || event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED)) {
                    byte[] diagnosticPayload = event.payload();
                    try {
                        if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT) {
                            sysdiagnoseCollection.queued(event.protobufType, diagnosticPayload);
                            if (event.protobufType == SysdiagnoseArchiveInventory.LIST_REQUEST) {
                                sysdiagnoseInventory.queued(event.protobufType, diagnosticPayload);
                            }
                            log("SYSDIAGNOSE DIAGNOSTIC TX: type=" + event.protobufType
                                    + "; nonce logged=false; setup state unchanged.");
                        } else {
                            java.nio.file.Path status = sysdiagnoseCollection.receive(event.protobufType,
                                    diagnosticPayload, BridgePaths.temporary());
                            if (status != null) log("SYSDIAGNOSE COLLECTION STATUS: type=" + event.protobufType
                                    + " bytes=" + diagnosticPayload.length + " privateFile=" + status
                                    + "; content logged=false; setup state unchanged.");
                            SysdiagnoseArchiveInventory.Observation result = sysdiagnoseInventory.receive(
                                    event.protobufType, diagnosticPayload, BridgePaths.temporary());
                            if (result != null && result.correlation != null) {
                                OPERATIONAL_REQUESTS.diagnosticInventoryResponse(
                                        UUID.fromString(result.correlation), BridgeClock.elapsedRealtime());
                            }
                            log("SYSDIAGNOSE INVENTORY RX: type=" + event.protobufType
                                    + " response=" + event.response + " accepted=" + (result != null)
                                    + (result != null ? " correlated=" + result.correlated + " entries="
                                    + result.entries + " privateFile=" + result.file : "")
                                    + "; names/content logged=false; setup state unchanged.");
                        }
                    } catch (java.io.IOException | RuntimeException failure) {
                        log("SYSDIAGNOSE INVENTORY: response/storage unavailable ("
                                + failure.getClass().getSimpleName() + "); setup state unchanged.");
                    } finally { wipe(diagnosticPayload); }
                }
                if (IdsApplicationRoute.FIND_MY_LOCAL_SERVICE.equals(event.topic)) {
                    if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT) {
                        findMySession.queued(event.protobufType, event.response, event.messageUuid,
                                BridgeClock.elapsedRealtime());
                        log("FIND MY LOCAL TX: type=" + event.protobufType + " response=" + event.response
                                + "; native Unix timestamp; pairing/setup state unchanged.");
                    } else if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED) {
                        byte[] pingPayload = event.payload();
                        byte[] frame = null;
                        try {
                            var reply = findMySession.receive(event.protobufType, event.response,
                                    event.peerResponseIdentifier, pingPayload, BridgeClock.elapsedRealtime());
                            FindMyLocalDeviceCodec.PlaySoundResponse unboundObservation = null;
                            if (event.response && (event.protobufType == FindMyLocalDeviceCodec.TYPE_PLAY_SOUND
                                    || event.protobufType == FindMyLocalDeviceCodec.TYPE_PLAY_SOUND_AND_FLASH)) {
                                unboundObservation = FindMyLocalDeviceCodec.PlaySoundResponse.decode(pingPayload);
                            }
                            log("FIND MY LOCAL RX: type=" + event.protobufType + " response=" + event.response
                                    + " hasResponseId=" + (event.peerResponseIdentifier != null)
                                    + " correlated=" + (reply != null)
                                    + (unboundObservation == null ? "" : " nativeDidPlay=" + unboundObservation.didPlay())
                                    + "; actual audible effect requires observation.");
                            if (reply != null) {
                                OPERATIONAL_REQUESTS.appResponse(event.topic, event.peerResponseIdentifier,
                                        event.protobufType, true, BridgeClock.elapsedRealtime());
                                try (var pingEvent = new BridgeApplicationEventCodec.Event(event.topic,
                                        event.protobufType, true, pingPayload)) {
                                    frame = BridgeApplicationEventCodec.encode(pingEvent);
                                    System.out.println(BridgeApplicationEventCodec.PREFIX
                                            + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
                                    System.out.flush();
                                }
                            }
                        } catch (IllegalArgumentException invalid) {
                            log("FIND MY LOCAL RX REJECTED: malformed native response; effect not inferred.");
                        } finally { wipe(pingPayload); wipe(frame); }
                    }
                }
                if (NanoSystemSettingsDiagnostics.TOPIC.equals(event.topic)) {
                    if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT
                            && (event.protobufType == NanoSystemSettingsDiagnostics.ABOUT_REQUEST
                            || event.protobufType == NanoSystemSettingsDiagnostics.LOGS_REQUEST)) {
                        nssDiagnostics.queued(event.protobufType, event.messageUuid);
                        log("NSS DIAGNOSTIC REQUEST: type=" + event.protobufType
                                + "; read-only; awaiting a correlated application response.");
                    } else if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED) {
                        byte[] nssPayload = event.payload();
                        try {
                            String observation = nssDiagnostics.response(event.protobufType,
                                    event.response, event.peerResponseIdentifier, nssPayload);
                            log("NSS DIAGNOSTIC RX: type=" + event.protobufType + " correlated="
                                    + (observation != null) + (observation != null ? "; " + observation : "")
                                    + "; pairing/setup state unchanged.");
                            // Only this verified response is forwarded; unsolicited About frames
                            // cannot become device telemetry in the APK.
                            if (observation != null && event.protobufType == NanoSystemSettingsDiagnostics.ABOUT_RESPONSE) {
                                byte[] frame = null;
                                try (BridgeApplicationEventCodec.Event aboutEvent =
                                             new BridgeApplicationEventCodec.Event(event.topic,
                                                     event.protobufType, true, nssPayload)) {
                                    frame = BridgeApplicationEventCodec.encode(aboutEvent);
                                    System.out.println(BridgeApplicationEventCodec.PREFIX
                                            + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
                                    System.out.flush();
                                } finally { wipe(frame); }
                            }
                        } catch (IllegalArgumentException invalid) {
                            log("NSS DIAGNOSTIC RX REJECTED: " + invalid.getMessage());
                        } finally { wipe(nssPayload); }
                    }
                }
                if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                        && event.command == IdsSocketPairCodec.COMMAND_DICTIONARY) {
                    byte[] dictionary = event.payload();
                    try {
                        log("IDS DICTIONARY RX: topic=" + idsTopicLogLabel(event.topic)
                                + " " + IdsCredentialsDiagnostics.describe(dictionary));
                    } finally { wipe(dictionary); }
                }
                if (event.type == IdsModernSessionCoordinator.EventType.DIRECT_MESSAGING_INFO_SENT) {
                    log("IDS DIRECT MSG INFO TX: " + event.topic + "; basic protocol only; device-connection/concise-ACK extensions disabled.");
                } else if (event.type == IdsModernSessionCoordinator.EventType.CONTROL_MESSAGE
                        && event.topic != null && event.topic.startsWith("DirectMsgInfo/")) {
                    log("IDS DIRECT MSG INFO RX: " + event.topic.substring("DirectMsgInfo/".length()));
                }
                if (idsDeviceInfoExchange != null) {
                    if (event.type == IdsModernSessionCoordinator.EventType.ACK_RECEIVED
                            && idsDeviceInfoExchange.onAcknowledgement(event.service, event.sequence)) {
                        log("IDS DEVICE INFO: local public-identity response acknowledged by Watch.");
                    }
                    if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                            && IdsDeviceInfoExchange.TOPIC.equals(event.topic)
                            && IdsUtunConnectionName.isClassDIdentifier(event.service)) {
                        byte[] payload = event.payload();
                        byte[] response = null;
                        try {
                            String before = idsDeviceInfoExchange.summary();
                            log("IDS CREDENTIALS STRUCTURE: " + IdsCredentialsDiagnostics.describe(payload));
                            String deviceInfoValues = IdsCredentialsDiagnostics.describeDeviceInfoValues(payload);
                            if (deviceInfoValues != null) {
                                log("IDS " + deviceInfoValues);
                            }
                            response = idsDeviceInfoExchange.accept(payload);
                            publishHealthPeerIdentity();
                            if (!before.equals(idsDeviceInfoExchange.summary())) {
                                log("IDS DEVICE INFO: " + idsDeviceInfoExchange.summary() + "; key/identifier values logged=false.");
                            }
                            if (response != null) {
                                sendIdsDeviceInfo(response, true, remoteCid);
                            } else {
                                IdsRemoteAccountExchange.Reply accountReply = null;
                                try {
                                    accountReply = IdsRemoteAccountExchange.accept(payload, this::persistPeerSpsMetadata);
                                } catch (IllegalArgumentException invalidAccountRequest) {
                                    log("IDS ACCOUNT RPC: invalid request; reply not sent; values logged=false.");
                                }
                                if (accountReply != null) {
                                    try (IdsRemoteAccountExchange.Reply reply = accountReply;
                                         NormalLinkIdsSessionBridge.Output sent = activeIdsBridge.sendApplicationData(
                                                 IdsDeviceInfoExchange.TOPIC, reply.payload)) {
                                        log("IDS ACCOUNT RPC TX: command=" + reply.command + "; " + reply.summary);
                                        // Account replies must never satisfy the command-12 key delivery barrier.
                                        processModernIdsOutput(sent, remoteCid, "NORMAL IDS ACCOUNT RPC");
                                    }
                                }
                            }
                        } finally { wipe(payload); wipe(response); }
                    }
                }
                if (activePostCommitAdapter != null) {
                    executePostCommitActions(pendingPostCommitSends.completeReceipt(event), remoteCid);
                }
                log("IDS EVENT: type="
                        + event.type
                        + (event.type == IdsModernSessionCoordinator.EventType.UNKNOWN_COMMAND_IGNORED
                        ? " command=0x" + Integer.toHexString(event.command) : "")
                        + (event.transportEvent != null
                        ? " (" + event.transportEvent + ")"
                        : "")
                        + " service="
                        + idsServiceLogLabel(
                                event.service)
                        + " topic="
                        + idsTopicLogLabel(
                                event.topic)
                        + " protobufType="
                        + (event.protobufType < 0
                        ? "none"
                        : Integer.toString(
                                event.protobufType))
                        + "; payload/sequence/identifier "
                        + "logged=false.");
                if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                        && event.topic != null
                        && event.topic.contains("timesync")) {
                    byte[] tsPayload = event.payload();
                    try {
                        int tsType = TimeSyncLinkCodec.messageType(tsPayload);
                        log("TIMESYNC LINK RX: kTMLSLinkMsgKey=" + tsType
                                + " payloadBytes=" + event.payloadLength());
                        if (tsType == TimeSyncLinkCodec.MSG_REQUEST_STATE
                                && activeIdsBridge != null) {
                            String timeZoneId = java.util.TimeZone.getDefault().getID();
                            byte[][] replies = new byte[][] {
                                    TimeSyncLinkCodec.automaticTimeEnabled(),
                                    TimeSyncLinkCodec.requestAcknowledgement(),
                                    TimeSyncLinkCodec.sourceAndTimeZone(timeZoneId),
                            };
                            for (byte[] reply : replies) {
                                try {
                                    NormalLinkIdsSessionBridge.Output sent =
                                            activeIdsBridge.sendApplicationData(
                                                    event.topic, reply);
                                    processModernIdsOutput(sent, remoteCid,
                                            "TIMESYNC LINK TX");
                                    flushPendingIdsFrames(remoteCid);
                                } finally {
                                    wipe(reply);
                                }
                            }
                            log("TIMESYNC LINK TX: answered Watch kTMLSLinkMsgKey=6 "
                                    + "with msg 12 (automatic time enabled), msg 7 (ack), "
                                    + "msg 4 (TMLSSourceDevice, " + timeZoneId + ").");
                        } else if (tsType == TimeSyncLinkCodec.MSG_CONVERT_BT_TIME
                                && activeIdsBridge != null) {
                            byte[][] replies = TimeSyncLinkCodec.clockConversionResponses(
                                    tsPayload, System.currentTimeMillis(), System.nanoTime());
                            for (byte[] reply : replies) {
                                try {
                                    processModernIdsOutput(activeIdsBridge.sendApplicationData(
                                                    event.topic, reply), remoteCid, "TIMESYNC RTC TX");
                                    flushPendingIdsFrames(remoteCid);
                                } finally { wipe(reply); }
                            }
                            log("TIMESYNC RTC TX: msg 9 conversion followed by msg 1 current device time.");
                        }
                    } catch (IllegalArgumentException malformed) {
                        log("TIMESYNC LINK RX rejected: " + malformed.getMessage());
                    } finally {
                        wipe(tsPayload);
                    }
                }
                if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                        && event.topic != null
                        && event.topic.contains("clockface")) {
                    byte[] facePayload = event.payload();
                    try {
                        log("CLOCKFACE SYNC RX: topic="
                                + idsTopicLogLabel(event.topic)
                                + " command=0x"
                                + Integer.toHexString(event.command)
                                + " flags=0x"
                                + Integer.toHexString(event.flags)
                                + " "
                                + ClockFaceSyncDiagnostics.summarize(facePayload));
                        captureActivationArtifact("clockface-sync", facePayload);
                        if (clockFaceDelta != null && event.peerResponseIdentifier != null) {
                            try {
                                var previous = clockFaceDelta.stage();
                                if (clockFaceDelta.response(event.topic, event.flags, UUID.fromString(event.peerResponseIdentifier),
                                        facePayload, System.currentTimeMillis(), BridgeClock.elapsedRealtime())) {
                                    if (previous == ClockFaceDeltaSession.Stage.START_WAIT) OPERATIONAL_REQUESTS.appResponse(
                                            event.topic, event.peerResponseIdentifier, ClockFaceSyncAccept.START, true, BridgeClock.elapsedRealtime());
                                    log("CLOCKFACE DELTA RESPONSE: stage=" + clockFaceDelta.stage() + "; state not inferred from ACK.");
                                }
                            } catch (IllegalArgumentException malformedIdentifier) {
                                log("CLOCKFACE DELTA response identifier refused.");
                            }
                            finishClockFaceDeltaIfTerminal();
                        }
                        if (IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE.equals(event.topic)
                                && OPERATIONAL_REQUESTS.expectsResponse(event.topic, event.peerResponseIdentifier,
                                ClockFaceSyncHeaderCodec.FULL_REQUEST, BridgeClock.elapsedRealtime())) {
                            try {
                                ClockFaceSyncHeaderCodec.FullResponse response = ClockFaceSyncHeaderCodec.fullResponse(facePayload);
                                OPERATIONAL_REQUESTS.appResponse(event.topic, event.peerResponseIdentifier,
                                        ClockFaceSyncHeaderCodec.FULL_REQUEST, true, BridgeClock.elapsedRealtime());
                                log("CLOCKFACE COLLECTION REQUEST RESPONSE: correlated=true accepted=" + response.accepted()
                                        + " error=" + response.hasError() + "; current collection still requires successful END.");
                            } catch (IllegalArgumentException malformed) {
                                log("CLOCKFACE COLLECTION REQUEST RESPONSE refused: " + safeMessage(malformed));
                            }
                        }
                        // flags 0xd is a CompanionSync request. The reply is
                        // the matching response protobuf, not a copy of the
                        // request. A copied start collides and the Watch
                        // ends the session.
                        boolean expectsResponse = (event.flags
                                & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE) != 0;
                        if (expectsResponse
                                && activeIdsBridge != null
                                && IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE.equals(event.topic)) {
                            byte[] reply = null;
                            try {
                                if (activePairingSessionRecord == null) {
                                    throw new IllegalStateException("Clockface has no durable pair");
                                }
                                ClockFaceSyncReceiver receiver = clockFaceReceiver();
                                ClockFaceSyncReceiver.Receipt receipt = receiver.accept(facePayload, System.currentTimeMillis());
                                if (receiver.takeJournalMaintenanceFailure()) {
                                    log("CLOCKFACE JOURNAL: maintenance deferred after durable commit");
                                }
                                if (receipt.stage().equals("COMPLETED")) {
                                    try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(facePayload)) {
                                        clockFaceClient().noteCommittedHeader(frame.header);
                                    }
                                    publishClockFaceObservation(clockFaceObservationEpoch);
                                    if (clockFaceDelta != null && operationalPairingId != null) {
                                        clockFaceDelta.observe(UUID.fromString(operationalPairingId), clockFaceObservationEpoch,
                                                receiver.snapshot(), BridgeClock.elapsedRealtime());
                                        finishClockFaceDeltaIfTerminal();
                                    }
                                }
                                byte[] localHeader = clockFaceClient().reserveHeader(System.currentTimeMillis(), false);
                                try { reply = ClockFaceSyncAccept.reply(facePayload, localHeader); }
                                finally { wipe(localHeader); }
                                log("CLOCKFACE RECEIVER: stage=" + receipt.stage() + " changes=" + receipt.changes()
                                        + " bytes=" + facePayload.length + " duplicate=" + receipt.duplicate()
                                        + " batches=" + receipt.batches() + " observedFaces=" + receipt.observedFaces()
                                        + " inventoryComplete=" + receipt.complete() + " application=unverified");
                            } catch (Exception failure) {
                                log("CLOCKFACE SYNC RX NOT ACCEPTED: " + safeMessage(failure));
                            }
                            if (reply != null) {
                                try (NormalLinkIdsSessionBridge.Output sent =
                                             activeIdsBridge.sendApplicationDataResponse(
                                                     event.topic,
                                                     reply,
                                                     event.outgoingResponseIdentifier())) {
                                    processModernIdsOutput(
                                            sent, remoteCid, "CLOCKFACE SYNC");
                                    log("CLOCKFACE SYNC TX: replied message=0x"
                                            + Integer.toHexString(reply[0] & 0xff)
                                            + " bytes=" + reply.length);
                                } catch (Exception failure) {
                                    log("CLOCKFACE SYNC TX FAILED: " + safeMessage(failure));
                                } finally {
                                    wipe(reply);
                                }
                            }
                        }
                    } finally {
                        wipe(facePayload);
                    }
                }
                if (event.type
                        == IdsModernSessionCoordinator.EventType
                        .PROTOBUF_RECEIVED) {
                    handleIncomingProtobufEvent(event);
                }
                if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                        && IdsApplicationRoute.HEALTH_SYNC_SERVICE.equals(event.topic)) {
                    forwardEncryptedHealthData(event);
                }
                if (event.type
                        == IdsModernSessionCoordinator.EventType
                        .TRANSPORT_EVENT
                        && event.transportEvent
                        == IdsServiceConnectorCoordinator.EventType
                        .SERVICE_REJECTED_BY_POLICY
                        ) {
                    if (IdsIpsecServiceRoute.CONTROL_SERVICE.equals(event.service)) {
                        if (activeIdsBridge != null && activeIdsBridge.idsSnapshot().peerHelloReceived) {
                            log("IDS: redundant control request rejected; keeping the independently authenticated active Hello.");
                            continue;
                        }
                        // Live 13:56: the Watch RST'd our control SYN and
                        // opened control itself. Live 0.2.209: the same
                        // race completed the TCP handshake first, so the
                        // Watch answered REJECTED_BY_POLICY while its own
                        // 49153→61315 SYN was already on the wire. Aborting
                        // here dropped that connector. Do not open another
                        // SYN; the outgoing attempt is already cleared so
                        // the Watch-initiated connector can be adopted.
                        log("IDS: outgoing control rejected by policy; "
                                + "not opening another SYN. Waiting for the Watch-initiated control connector.");
                        continue;
                    }
                    if (activeIdsBootstrap != null
                            && activeIdsBootstrap.requiredLaneRejected(event.service)) {
                        // Live 0.2.213 reconnect: Urgent-D POLICY aborted
                        // the link before a Watch-initiated lane could
                        // join. The outgoing SYN is already cancelled.
                        if (!freshPairingPerformed) {
                            log("IDS: outgoing data lane rejected by policy during paired reconnect ("
                                    + idsServiceLogLabel(event.service)
                                    + "); probing all C/D endpoint keys before one control recovery request.");
                            IdsBootstrapState.Action recovery =
                                    activeIdsBootstrap.onPairedLanePolicyRejection();
                            if (recovery != IdsBootstrapState.Action.NONE) actions.add(recovery);
                            continue;
                        }
                        throw new HostException("Watch rejected required IDS data lane by policy (NWSC 0x40): "
                                + idsServiceLogLabel(event.service) + "; not retrying SYN");
                    }
                }
                if (event.type
                        == IdsModernSessionCoordinator.EventType
                        .CONTROL_READY) {
                    checkpointIdsControlReady();
                    idsControlReadyObserved = true;
                    persistStaleFlowObservations(
                            "control ready");
                    log("IDS PEER VERSIONS: " + activeIdsBridge.ids().peerVersionSummary()
                            + "; setup advertisement=" + (activeWatchSetupMetadata != null
                            ? activeWatchSetupMetadata.pairingVersion : "unknown"));
                    if (activePairingSessionRecord != null) {
                        log("IDS PEER IDENTITY RELATION: " + activeIdsBridge.ids().peerIdentitySummary(
                                activePairingSessionRecord.peerIdsDeviceId(),
                                activePairingSessionRecord.peerNetworkRelayIdentifier()));
                    }
                    // The setup messages can only be delivered once the peer
                    // Hello has completed; before that the Watch resets the
                    // service connectors that would carry them.
                    startPostCommitIfAuthorized(
                            remoteCid);
                }
                if (activeIdsBootstrap != null) {
                    IdsBootstrapState.Action action =
                            event.type
                                    == IdsModernSessionCoordinator.EventType.TRANSPORT_EVENT
                                    && event.transportEvent
                                    == IdsServiceConnectorCoordinator.EventType.KEY_PROBE_COMPLETED
                                    ? activeIdsBootstrap.onPairedKeyProbeCompleted()
                                    : activeIdsBootstrap.observe(
                                            event.type,
                                            event.service);
                    if (action
                            == IdsBootstrapState.Action.READY) {
                        if (activePairingSessionRecord != null
                                && activePairingSessionRecord.state().wireValue()
                                        >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()) {
                            modernIdsReady = true;
                            readyToCommitIsPaired = true;
                            initialPropertiesReceived = true;
                            idsBootstrapBarrierPassed = true;
                            startPostCommitIfAuthorized(
                                    remoteCid);
                        } else if (activeInitialSetup == null
                                && !pendingNanoSetup) {
                            modernIdsReady = true;
                            pendingNanoSetup = true;
                            pendingNanoSetupAtMs =
                                    BridgeClock.elapsedRealtime();
                            if (activeIdsBridge != null) {
                                activeIdsBridge
                                        .setPayloadFastRetransmitEnabled(
                                                false);
                            }
                            int outstanding =
                                    activeIdsBridge != null
                                            ? activeIdsBridge.ertmOutstandingCount()
                                            : -1;
                            long tcpUna =
                                    activeIdsBridge != null
                                            ? activeIdsBridge
                                            .tcpUnacknowledgedSendBytes()
                                            : -1L;
                            log("INITIAL NANO SETUP WAIT: Urgent Class-D/C "
                                    + "Handshake received; holding Check "
                                    + "until ERTM Tx outstanding<="
                                    + IdsNanoSetupPacer.QUIET_OUTSTANDING
                                    + " and TCP una==nxt (live 0.2.126 PIN "
                                    + "Check sat behind TCP una 117 bytes; "
                                    + "live 0.2.127 tcpUna=27 never "
                                    + "retransmitted). "
                                    + "outstanding="
                                    + outstanding
                                    + " tcpUna="
                                    + tcpUna
                                    + " streams="
                                    + (activeIdsBridge != null
                                            ? activeIdsBridge
                                            .tcpUnacknowledgedSendSummary()
                                            : "none")
                                    + "; identifiers logged=false.");
                        }
                    } else if (action
                            != IdsBootstrapState.Action.NONE) {
                        actions.add(
                                action);
                    }
                }
                if (activeInitialSetup == null && pendingNanoSetup) {
                    maybeFlushPendingNanoSetup(remoteCid);
                }
                if (activeInitialSetup != null) {
                    if (event.type == IdsModernSessionCoordinator.EventType.ACK_RECEIVED
                            && nanoRegistryProbe.onAcknowledgement(event.service, event.sequence)) {
                        log("NANOREGISTRY PROBE: delivery ACK received; application response still required.");
                    }
                    if (event.type == IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED
                            && nanoRegistryProbe.onClientAcknowledgement(event.service, event.topic,
                            event.peerResponseIdentifier)) {
                        log("NANOREGISTRY PROBE: correlated client ACK received; Ping response still required.");
                    }
                    if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED) {
                        byte[] probePayload = event.payload();
                        try {
                            if (nanoRegistryProbe.onResponse(event.topic, event.protobufType,
                                    event.response, event.peerResponseIdentifier, probePayload)) {
                                log("NANOREGISTRY PROBE: correlated Ping response received from the Watch daemon; setup state unchanged.");
                            }
                        } finally {
                            wipe(probePayload);
                        }
                    }
                    if (event.type
                            == IdsModernSessionCoordinator.EventType
                            .PROTOBUF_RECEIVED
                            || event.type
                            == IdsModernSessionCoordinator.EventType
                            .DATA_RECEIVED) {
                        int before =
                                initialActions.size();
                        initialActions.addAll(
                                activeInitialSetup.accept(
                                        event));
                        if (event.topic != null
                                && event.topic.contains(
                                        "timesync")) {
                            byte[] tsPayload =
                                    event.payload();
                            if (tsPayload != null) {
                                log("TIMESYNC DIAG RX: payloadBytes="
                                        + tsPayload.length
                                        + " hex="
                                        + HciCodec.toHex(
                                                tsPayload));
                                captureActivationArtifact(
                                        "timesync-rx",
                                        tsPayload);
                            }
                        }
                        log("INITIAL NANO RX: type="
                                + event.type
                                + " service="
                                + idsServiceLogLabel(
                                        event.service)
                                + " topic="
                                + idsTopicLogLabel(
                                        event.topic)
                                + " protobufType="
                                + (event.protobufType < 0
                                        ? "none"
                                        : Integer.toString(
                                                event.protobufType))
                                + " response="
                                + event.response
                                + " payloadBytes="
                                + event.payloadLength()
                                + " actions="
                                + (initialActions.size()
                                        - before)
                                + "; payload logged=false.");

                    } else {
                        initialActions.addAll(
                                activeInitialSetup.accept(
                                        event));
                    }

                } else if (pendingNanoSetup && (event.type
                        == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                        || event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED)) {
                    // ACKs received before Check was even queued cannot acknowledge it.
                    long bufferedBytes = earlyInitialEvents.stream().mapToLong(
                            IdsModernSessionCoordinator.SessionEvent::payloadLength).sum();
                    if (earlyInitialEvents.size() >= 128
                            || bufferedBytes + event.payloadLength() > 4L * 1024 * 1024) {
                        throw new HostException("Initial IDS event backlog exceeded its capacity");
                    }
                    earlyInitialEvents.add(event.copy());
                    log("[WatchHal] buffered early setup event: type="
                            + event.type
                            + " service="
                            + idsServiceLogLabel(event.service)
                            + " topic="
                            + idsTopicLogLabel(event.topic));
                    // Early events are replayed into the setup adapter only, so
                    // decode their payload here or the Watch state is lost.
                    logWatchTelemetryEvent(event);
                }
                if (event.type
                        == IdsModernSessionCoordinator.EventType
                        .DATA_CHANNEL_JOINED
                        || event.type
                        == IdsModernSessionCoordinator.EventType
                        .DATA_SERVICE_ACCEPTED
                        || event.type
                        == IdsModernSessionCoordinator.EventType
                        .HANDSHAKE_RECEIVED) {
                    log("IDS SERVICE ACTIVE: data channel / service active (topic="
                            + idsTopicLogLabel(event.topic)
                            + ").");

                    if (!initialActions.isEmpty()) {
                        for (AppleWatchInitialSetupIdsAdapter.Action initialAction : initialActions) {
                            executeInitialSetupAction(initialAction, remoteCid);
                        }
                        initialActions.clear();
                    }
                    flushPendingIdsFrames(
                            remoteCid);
                    startPostCommitIfAuthorized(
                            remoteCid);
                }
                if (activePostCommitAdapter != null) {
                    answerLivePropertyRequest(
                            event,
                            remoteCid);
                    if (event.type
                            == IdsModernSessionCoordinator.EventType
                            .PROTOBUF_RECEIVED
                            || event.type
                            == IdsModernSessionCoordinator.EventType
                            .DATA_RECEIVED) {
                        boolean isSetupMessage = event.type
                                == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                                && (IdsApplicationRoute.PB_BRIDGE_SERVICE.equals(event.topic)
                                || NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(event.topic)
                                || NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                                || PairedSyncCodec.isService(event.topic));
                        if (isSetupMessage) {
                            logWatchRegistryState(event);
                            try (AppleWatchPostCommitIdsAdapter.InboundResult incoming =
                                         activePostCommitAdapter.accept(event)) {
                                if (incoming.hasActivationRequest()) {
                                    if (pendingActivationPayload != null) {
                                        throw new HostException("Concurrent activation request before prior proxy completion");
                                    }
                                    pendingActivationPayload = incoming.archivedActivationRequest();
                                    captureActivationArtifact(
                                            incoming.activationKind.name().toLowerCase(Locale.US)
                                                    + "-request",
                                            pendingActivationPayload);
                                    watchDrivenActivationObserved = true;
                                    log("ACTIVATION PROXY RX: kind=" + incoming.activationKind
                                            + " flags=0x" + Integer.toHexString(event.flags)
                                            + " expectsPeerResponse="
                                            + ((event.flags & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE) != 0)
                                            + "; authenticated Watch request accepted.");
                                }
                                executePostCommitActions(incoming.actions(), remoteCid);
                            }
                        }
                    } else if (event.type
                            == IdsModernSessionCoordinator.EventType
                            .APP_ACK_RECEIVED) {
                        AppleWatchPostCommitIdsAdapter.AppAckReceipt receipt =
                                activePostCommitAdapter.acceptAppAck(
                                        event);
                        if (receipt == AppleWatchPostCommitIdsAdapter.AppAckReceipt.PAIRED_SYNC_MATCHED) {
                            log("IDS APP ACK ACCEPTED: topic="
                                    + idsTopicLogLabel(event.topic));
                            // AppAck is receipt evidence only. Watch-local sync
                            // progress is processed from its incoming state payload.

                        }
                    }
                }
            }

            List<NormalLinkPipeSession.DeliveredIp> passthrough =
                    output.passthroughIp();
            try {
                if (!passthrough.isEmpty()) {
                    NormalLinkPipeSession.DeliveredIp first = passthrough.get(0);
                    int nextHeader = (first.packet != null && first.packet.length > 6) ? (first.packet[6] & 0xff) : -1;
                    int len = first.packet != null ? first.packet.length : 0;
                    boolean hasPorts = (nextHeader == 6 || nextHeader == 17) && len >= 44;
                    String ports = hasPorts ? " ports="
                            + (((first.packet[40] & 0xff) << 8) | (first.packet[41] & 0xff)) + "->"
                            + (((first.packet[42] & 0xff) << 8) | (first.packet[43] & 0xff)) : "";
                    log("IDS PASSTHROUGH IP RX: count="
                            + passthrough.size()
                            + " proto=" + nextHeader + " len=" + len + ports
                            + "; payload logged=false; consumer attached=false.");
                }
            } finally {
                for (NormalLinkPipeSession.DeliveredIp packet
                        : passthrough) {
                    packet.destroy();
                }
                passthrough.clear();
            }

            List<byte[]> controls =
                    output.normalControlMessages();
            try {
                if (!controls.isEmpty()) {
                    log("IDS NORMAL CONTROL RX: count="
                            + controls.size()
                            + "; bytes logged=false.");
                }
            } finally {
                for (byte[] control : controls) {
                    wipe(
                            control);
                }
                controls.clear();
            }
        }

        for (IdsBootstrapState.Action action : actions) {
            executeIdsBootstrapAction(
                    action,
                    remoteCid);
        }
        for (AppleWatchInitialSetupIdsAdapter.Action action :
                initialActions) {
            executeInitialSetupAction(
                    action,
                    remoteCid);
        }
        maybeProgressIdsDeviceInfo(remoteCid);
        maybeFlushPendingNanoSetup(
                remoteCid);
        maybeStartNanoRegistryProbe(remoteCid);
    }

    private void maybeStartNanoRegistryProbe(int remoteCid) throws Exception {
        if (activeInitialSetup == null || activeIdsBridge == null
                || !activeInitialSetup.phoneSnapshotAckObserved()
                || initialPropertiesReceived || !nanoRegistryProbe.start()) return;
        NanoRegistryClassDCodec.PingRequest request = nanoRegistryProbe.request();
        try (NormalLinkIdsSessionBridge.Output probe = activeIdsBridge.sendClassDRequestWithClientAcknowledgement(request)) {
            for (IdsModernSessionCoordinator.SessionEvent event : probe.idsEvents()) {
                if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT
                        && event.protobufType == NanoRegistryClassDCodec.TYPE_PING) {
                    nanoRegistryProbe.onQueued(event.sequence, event.messageUuid);
                }
            }
            log("NANOREGISTRY PROBE: type-5 Ping queued with explicit peer-response and client-ACK flags; awaiting a correlated daemon response.");
            processModernIdsOutput(probe, remoteCid, "NORMAL NANOREGISTRY PROBE");
        } finally {
            request.destroy();
        }
    }

    private String requireAppIdsDeviceIdentifier() throws HostException {
        try {
            if (localIdsPublicBundle == null) {
                localIdsPublicBundle = localIdsPublicRecords.poll(LOCAL_IDENTITY_INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            if (localIdsPublicBundle == null) {
                throw new HostException("Local IDS public identities were not provided by the app");
            }
            return IdsMessageProtectionIdentity.publicBundleIdentifier(localIdsPublicBundle);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HostException("Interrupted while loading IDS installation identity", interrupted);
        } catch (IllegalArgumentException invalid) {
            throw new HostException("Invalid IDS installation identity from app", invalid);
        }
    }

    private void persistPeerSpsMetadata(IdsSpsCompanionInfo info) throws Exception {
        byte[] metadata = info.serialize();
        byte[] previous = activePairingSessionRecord.peerSpsMetadata();
        try {
            if (Arrays.equals(metadata, previous)) {
                log("IDS SPS METADATA: identical authenticated metadata already stored; values logged=false.");
                return;
            }
            PairingSessionRecord checkpoint = activePairingSessionRecord.withPeerSpsMetadata(metadata);
            boolean stored = false;
            try {
                persistAndReplaceActivePairingSession(checkpoint);
                stored = true;
                log("IDS SPS METADATA CHECKPOINT PASS: " + info.summary()
                        + "; pairing/setup state unchanged; values logged=false.");
            } finally { if (!stored) checkpoint.destroy(); }
        } finally { wipe(metadata); wipe(previous); }
    }

    private void sendIdsDeviceInfo(byte[] payload, boolean response, int remoteCid) throws Exception {
        try (NormalLinkIdsSessionBridge.Output sent = activeIdsBridge.sendApplicationData(IdsDeviceInfoExchange.TOPIC, payload)) {
            if (response) {
                for (IdsModernSessionCoordinator.SessionEvent event : sent.idsEvents()) {
                    if (event.type == IdsModernSessionCoordinator.EventType.DATA_SENT) {
                        idsDeviceInfoExchange.onResponseQueued(event.sequence);
                    }
                }
            }
            log("IDS DEVICE INFO TX: command=" + (response ? 12 : 11)
                    + "; public identities and correlation values logged=false.");
            processModernIdsOutput(sent, remoteCid, "NORMAL IDS DEVICE INFO");
        }
    }

    private void maybeProgressIdsDeviceInfo(int remoteCid) throws Exception {
        if (idsDeviceInfoExchange == null || activeIdsBridge == null || activeIdsBootstrap == null
                || !activeIdsBootstrap.ready() || !activeIdsBridge.dataLanesReadyForApplication()) return;
        publishHealthPeerIdentity();
        if (IdsNanoSetupPacer.isTransportQuiet(activeIdsBridge.ertmOutstandingCount(), activeIdsBridge.tcpUnacknowledgedSendBytes())) {
            byte[] request = idsDeviceInfoExchange.begin();
            if (request != null) {
                try { sendIdsDeviceInfo(request, false, remoteCid); }
                finally { wipe(request); }
            }
        }
        if (idsDeviceInfoExchange.ready() && !activePairingSessionRecord.hasExchangedIdsDeviceInfo()) {
            PairingSessionRecord checkpoint = activePairingSessionRecord.markIdsDeviceInfoExchanged();
            persistAndReplaceActivePairingSession(checkpoint);
            log("IDS DEVICE INFO CHECKPOINT PASS: authenticated peer identities received; local identities delivered. Initial Check may start when the transport is quiet.");
        }
        if (deviceInfoAllowsPostCommitResume()) {
            startPostCommitIfAuthorized(remoteCid);
        }
    }

    private boolean deviceInfoAllowsPostCommitResume() {
        if (idsDeviceInfoExchange == null) return false;
        if (idsDeviceInfoExchange.ready()) return true;
        if (activeIdsBridge == null || activePairingSessionRecord == null) return false;
        return IdsPairedReconnectPolicy.allowSavedDeviceInfoForPostCommit(
                activePairingSessionRecord.state(),
                activePairingSessionRecord.hasExchangedIdsDeviceInfo(),
                freshPairingPerformed,
                activeIdsBridge.ids().peerHelloMatchesIdentity(activePairingSessionRecord.peerIdsDeviceId()),
                activeIdsBridge.dataLanesReadyForApplication());
    }

    private void maybeFlushPendingNanoSetup(
            int remoteCid)
            throws Exception {
        if (!pendingNanoSetup
                || activeInitialSetup != null
                || activeIdsBridge == null) {
            return;
        }
        maybeProgressIdsDeviceInfo(remoteCid);
        // Ping is a diagnostic, not a prerequisite for sending Check. The
        // 0.2.146 gate disconnected healthy IDS links before setup began.
        if (idsDeviceInfoExchange == null || !idsDeviceInfoExchange.ready()) return;
        int outstanding =
                activeIdsBridge.ertmOutstandingCount();
        long tcpUna =
                activeIdsBridge.tcpUnacknowledgedSendBytes();
        long elapsedMs =
                pendingNanoSetupAtMs < 0
                        ? 0L
                        : BridgeClock.elapsedRealtime()
                                - pendingNanoSetupAtMs;
        boolean ertmQuiet =
                IdsNanoSetupPacer.isErtmQuiet(
                        outstanding);
        activeIdsBridge.setPayloadFastRetransmitEnabled(
                ertmQuiet);
        boolean quiet =
                IdsNanoSetupPacer.isTransportQuiet(
                        outstanding,
                        tcpUna);
        if (!IdsNanoSetupPacer.shouldSendCheck(
                quiet,
                elapsedMs)) {
            if (elapsedMs >= 1_000L
                    && (lastNanoSetupWaitLogMs < 0
                    || elapsedMs - lastNanoSetupWaitLogMs >= 1_000L)) {
                lastNanoSetupWaitLogMs = elapsedMs;
                log("INITIAL NANO SETUP WAIT: still holding Check; "
                        + "ERTM outstanding="
                        + outstanding
                        + " tcpUna="
                        + tcpUna
                        + " streams="
                        + activeIdsBridge.tcpUnacknowledgedSendSummary()
                        + " quiet="
                        + quiet
                        + " waitMs="
                        + elapsedMs
                        + "; will not fail-open (live 0.2.121 "
                        + "outstanding=18 at 421ms; live 0.2.127 "
                        + "tcpUna=27 on a quiet ERTM window).");
            }
            return;
        }
        pendingNanoSetup = false;
        activeIdsBridge.setPayloadFastRetransmitEnabled(
                true);
        log("INITIAL NANO SETUP RELEASE: ERTM outstanding="
                + outstanding
                + " tcpUna="
                + tcpUna
                + " quiet="
                + quiet
                + " waitMs="
                + elapsedMs
                + "; sending Check after a quiet ERTM+TCP window.");
        List<AppleWatchInitialSetupIdsAdapter.Action> actions =
                activateInitialSetup();
        for (AppleWatchInitialSetupIdsAdapter.Action action : actions) {
            executeInitialSetupAction(
                    action,
                    remoteCid);
        }
        if (!earlyInitialEvents.isEmpty()) {
            List<IdsModernSessionCoordinator.SessionEvent> early = new ArrayList<>(earlyInitialEvents);
            earlyInitialEvents.clear();
            log("[WatchHal] replaying " + early.size() + " owned early setup events");
            try {
                for (IdsModernSessionCoordinator.SessionEvent event : early) {
                    for (AppleWatchInitialSetupIdsAdapter.Action action : activeInitialSetup.accept(event)) {
                        executeInitialSetupAction(action, remoteCid);
                    }
                }
            } finally {
                early.forEach(IdsModernSessionCoordinator.SessionEvent::close);
            }
        }
    }

    /**
     * The Watch keeps the previous session's TCP and answers a new
     * control SYN with REJECTED_BY_POLICY. Give the stale segment time
     * to arrive so it can be acknowledged and finished before that SYN.
     */
    private void holdControlSynForStaleTcp(
            int localCid,
            int remoteCid)
            throws Exception {
        log("IDS: holding the control SYN 5s to acknowledge and finish a stale Watch TCP flow.");
        long deadline = BridgeClock.elapsedRealtime() + 5_000L;
        while (BridgeClock.elapsedRealtime() < deadline) {
            try {
                acceptModernIdsFrame(
                        localCid,
                        remoteCid,
                        deadline,
                        "stale TCP hold");
            } catch (HostException timeout) {
                if (timeout.getMessage() != null
                        && timeout.getMessage().contains("Timed out awaiting")) {
                    return;
                }
                throw timeout;
            }
        }
    }

    private void executeIdsBootstrapAction(
            IdsBootstrapState.Action action,
            int remoteCid)
            throws Exception {
        if (action == null
                || action == IdsBootstrapState.Action.NONE) {
            return;
        }
        HalIdsBootstrapOrchestrator orchestrator =
                new HalIdsBootstrapOrchestrator(ProtocolHost::log);
        NormalLinkIdsSessionBridge.Output output =
                orchestrator.executeAction(
                        action,
                        activeIdsBridge);
        processModernIdsOutput(
                output,
                remoteCid,
                "NORMAL IDS TX");
    }

    private List<AppleWatchInitialSetupIdsAdapter.Action>
            activateInitialSetup()
            throws Exception {
        if (activeInitialSetup != null) {
            return List.of();
        }
        checkpointIdsDataReady();
        if (activeIdsBridge == null
                || activePairingSessionRecord == null
                || activeWatchSetupIdentifier == null
                || activeWatchSetupMetadata == null
                || activePairingSessionRecord.state().wireValue()
                < PairingSessionRecord.DurableState
                .IDS_DATA_READY.wireValue()) {
            throw new HostException(
                    "Initial NanoRegistry setup evidence is incomplete");
        }
        try {
            int peerMaxPairingVersion = activeIdsBridge.idsSnapshot().peerHelloReceived
                    ? activeIdsBridge.effectivePeerMaxPairingVersion()
                    : activeWatchSetupMetadata.pairingVersion;
            AppleWatchInitialSetupIdsAdapter adapter =
                    new AppleWatchInitialSetupIdsAdapter(
                            activePairingSessionRecord
                                    .transitionCounter(),
                            activeWatchSetupIdentifier,
                            activeWatchSetupMetadata,
                            peerMaxPairingVersion);
            List<AppleWatchInitialSetupIdsAdapter.Action> actions =
                    adapter.startAfterIdsDataReady();
            activeInitialSetup =
                    adapter;
            modernIdsReady = true;
            log("INITIAL NANO SETUP START: effective peer maximum "
                    + "pairingVersion="
                    + peerMaxPairingVersion
                    + "; Check after a quiet ERTM Tx window as IDS "
                    + "request (23G71 protobuf isResponse=false, socket flags=0); "
                    + "phone full snapshot waits for the Check Alloy ACK "
                    + "then goes as type=2 request with initializeGetters "
                    + "Capabilities (345 UUIDs), timeout=nil and nil "
                    + "andResponse; after its ACK, request the Watch full "
                    + "properties once with type=4; Check waits "
                    + "for TCP una==nxt (live 0.2.126 PIN una 117 then "
                    + "0x13); incoming "
                    + "Watch type-4 requests still get "
                    + "sendPropertyResponse; waiting for the Watch "
                    + "authenticated property dump before Configure/commit/"
                    + "permit; runtime identifiers logged=false.");
            return actions;
        } catch (RuntimeException failure) {
            throw new HostException(
                    "Cannot activate initial NanoRegistry setup: "
                            + failure.getMessage(),
                    failure);
        }
    }

    /**
     * A resumed paired link has no initial-setup adapter, so the Watch's
     * later PropertyRequest would otherwise be logged and dropped. Echo
     * the same phone MiniStore the first setup sends.
     */
    private void answerLivePropertyRequest(
            IdsModernSessionCoordinator.SessionEvent event,
            int remoteCid)
            throws Exception {
        if (event == null
                || activeInitialSetup != null
                || activeIdsBridge == null
                || localIdentity == null
                || event.type != IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                || !NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                || event.protobufType != NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST
                || event.response
                || event.messageUuid == null
                || event.messageUuid.isBlank()) {
            return;
        }
        byte[] bluetoothMacAddress = localIdentity.address();
        NanoRegistryPropertyCodec.PropertyResponse message = null;
        try {
            message = AppleWatchInitialSetupIdsAdapter.Action
                    .livePhonePropertyResponse(bluetoothMacAddress);
            try (NormalLinkIdsSessionBridge.Output sent =
                         activeIdsBridge.sendClassCResponse(
                                 message,
                                 event.messageUuid)) {
                processModernIdsOutput(
                        sent,
                        remoteCid,
                        "LIVE PROPERTY RESPONSE");
            }
            log("LIVE PROPERTY RESPONSE TX: Class-C type=4 echoing the Watch "
                    + "PropertyRequest; identifier logged=false.");
        } finally {
            wipe(bluetoothMacAddress);
            if (message != null) {
                message.destroy();
            }
        }
    }

    /**
     * Republish the phone registry snapshot and ask for the Watch snapshot
     * once on a link that already passed initial setup. The first session
     * delivered both; a later connection does not, and the Watch then has
     * nothing new to merge.
     */
    private void publishResumedPhoneRegistrySnapshot(int remoteCid)
            throws Exception {
        if (resumedPhoneRegistrySnapshotSent
                || activeInitialSetup != null
                || activeIdsBridge == null
                || localIdentity == null
                || activePairingSessionRecord == null
                || activePairingSessionRecord.state().wireValue()
                < PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()) {
            return;
        }
        resumedPhoneRegistrySnapshotSent = true;
        byte[] bluetoothMacAddress = localIdentity.address();
        NanoRegistryPropertyCodec.PropertiesChanged snapshot = null;
        try {
            snapshot = AppleWatchInitialSetupIdsAdapter.Action.livePhoneFullSnapshot(
                    System.currentTimeMillis(),
                    bluetoothMacAddress);
            try (NormalLinkIdsSessionBridge.Output sent =
                         activeIdsBridge.sendClassC(snapshot)) {
                processModernIdsOutput(
                        sent,
                        remoteCid,
                        "RESUMED PHONE SNAPSHOT");
            }
            log("RESUMED PHONE SNAPSHOT TX: Class-C type=2 phone MiniStore "
                    + "republished on the paired link.");
        } finally {
            wipe(bluetoothMacAddress);
            if (snapshot != null) {
                snapshot.destroy();
            }
        }
        NanoRegistryPropertyCodec.PropertyRequest request =
                new NanoRegistryPropertyCodec.PropertyRequest();
        try (NormalLinkIdsSessionBridge.Output sent =
                     activeIdsBridge.sendClassC(request)) {
            processModernIdsOutput(
                    sent,
                    remoteCid,
                    "RESUMED PROPERTY REQUEST");
        } finally {
            request.destroy();
        }
        log("RESUMED PROPERTY REQUEST TX: Class-C type=4 asking the Watch "
                + "for its current registry snapshot.");
        publishStoredTwoWayPreferences(remoteCid);
    }

    /**
     * The Watch already delivered two-way defaults and got an IDS ack, so
     * it does not send them again. A paired phone applies those keys and
     * publishes the same values back; without that the phone is only a
     * file. Live 0.2.219: nine stored domains were app-acked on
     * preferencessync when sent with their original timestamps.
     */
    private void publishStoredTwoWayPreferences(int remoteCid) {
        if (!pendingTwoWayPreferences.isEmpty()) {
            return;
        }
        pendingTwoWayPreferences.addAll(PairedSyncPreferenceStore.twoWayPayloads());
        if (!pendingTwoWayPreferences.isEmpty()) {
            log("PAIREDSYNC MIRROR QUEUED: domains="
                    + pendingTwoWayPreferences.size());
        }
        pumpStoredTwoWayPreferences(remoteCid);
    }

    private void pumpStoredTwoWayPreferences(int remoteCid) {
        if (pendingTwoWayPreferences.isEmpty()
                || activeIdsBridge == null) {
            return;
        }
        // Bulk mirrors must not occupy Class-D's TCP window before the
        // critical PSY publication is delivered. The receipt only releases
        // this scheduling hold; it is never Watch-local completion evidence.
        if (activePostCommitCoordinator != null && activePostCommitAdapter != null) {
            AppleWatchPostCommitCoordinator.Snapshot state =
                    activePostCommitCoordinator.snapshot();
            if (!state.gizmoFinishedBeforeSession && !state.pairedSyncObserved
                    && !activePostCommitAdapter.pairedSyncAppAckObserved()) {
                if (!preferenceMirrorDeferredLogged) {
                    preferenceMirrorDeferredLogged = true;
                    log("PAIREDSYNC MIRROR HELD: waiting for critical PSY delivery.");
                }
                return;
            }
        }
        if (activeIdsBridge.hasDeferredIpv6()
                || !IdsNanoSetupPacer.isTransportQuiet(
                activeIdsBridge.ertmOutstandingCount(),
                activeIdsBridge.tcpUnacknowledgedSendBytes())) {
            return;
        }
        preferenceMirrorDeferredLogged = false;
        byte[] payload = pendingTwoWayPreferences.pollFirst();
        try {
            try (NormalLinkIdsSessionBridge.Output sentOut =
                         activeIdsBridge.sendApplicationProtobuf(
                                 IdsApplicationRoute.PREFERENCE_SYNC_SERVICE,
                                 PairedSyncCodec.PROTOBUF_TYPE_USER_DEFAULTS,
                                 payload)) {
                processModernIdsOutput(
                        sentOut,
                        remoteCid,
                        "PAIREDSYNC MIRROR");
            }
            log("PAIREDSYNC MIRROR TX: remaining="
                    + pendingTwoWayPreferences.size());
        } catch (Exception failure) {
            log("PAIREDSYNC MIRROR FAILED: " + safeMessage(failure));
        } finally {
            wipe(payload);
        }
    }

    private void executeInitialSetupAction(
            AppleWatchInitialSetupIdsAdapter.Action action,
            int remoteCid)
            throws Exception {
        if (action == null
                || activeIdsBridge == null
                || activeInitialSetup == null) {
            throw new HostException(
                    "Initial NanoRegistry action lost its live session");
        }
        NormalLinkIdsSessionBridge.Output output;
        String label;
        switch (action.type) {
            case SEND_COMPATIBILITY_STATE -> {
                NanoRegistryClassDCodec.PairingModeRequest message =
                        action.compatibilityMessage();
                try {
                    output =
                            activeIdsBridge.sendClassD(
                                    message);
                } finally {
                    message.destroy();
                }
                label = "INITIAL NANO TX";
                log("INITIAL NANO TX: Class-D PairingMode state="
                        + action.compatibilityState
                        + " runtimeWatchPairingVersion="
                        + action.runtimeWatchPairingVersion
                        + " protobuf request, socket flags=0 (23G71); message UUID/sequence logged=false.");
            }
            case SEND_PHONE_FULL_SNAPSHOT -> {
                if (localIdentity == null) {
                    throw new HostException(
                            "Phone Class-C snapshot needs the live "
                                    + "static-random Bluetooth address");
                }
                byte[] bluetoothMacAddress =
                        localIdentity.address();
                NanoRegistryPropertyCodec.PropertiesChanged message;
                try {
                    message =
                            action.phoneFullSnapshot(
                                    System.currentTimeMillis(),
                                    bluetoothMacAddress);
                } finally {
                    wipe(bluetoothMacAddress);
                }
                try {
                    output =
                            activeIdsBridge.sendClassC(
                                    message);
                } finally {
                    message.destroy();
                }
                label = "INITIAL NANO TX";
                log("INITIAL NANO TX: Class-C phone full snapshot "
                        + "protobuf request (23G71 type=2, socket flags=0); Capabilities 345 UUIDs "
                        + "make the socket-pair frame exceed 8000 bytes "
                        + "so IDS command 0x15 fragments it with a "
                        + "runtime message ID (live 0.2.119 crashed "
                        + "without that ID); UDID/SerialNumber/"
                        + "ModelNumber/RegulatoryModelNumber remain "
                        + "absent; fabricated=false.");
            }
            case SEND_PHONE_PROPERTY_RESPONSE -> {
                if (localIdentity == null) {
                    throw new HostException(
                            "Phone Class-C property response needs the "
                                    + "live static-random Bluetooth "
                                    + "address");
                }
                if (action.outgoingResponseIdentifier == null
                        || action.outgoingResponseIdentifier.isBlank()) {
                    throw new HostException(
                            "Phone Class-C property response needs the "
                                    + "Watch request identifier");
                }
                byte[] bluetoothMacAddress =
                        localIdentity.address();
                NanoRegistryPropertyCodec.PropertyResponse message;
                try {
                    message =
                            action.propertyResponse(
                                    bluetoothMacAddress);
                } finally {
                    wipe(bluetoothMacAddress);
                }
                try {
                    output =
                            activeIdsBridge.sendClassCResponse(
                                    message,
                                    action.outgoingResponseIdentifier);
                } finally {
                    message.destroy();
                }
                label = "INITIAL NANO TX";
                log("INITIAL NANO TX: Class-C sendPropertyResponse "
                        + "type=4 response echoing Watch request "
                        + "identifier (23G71 "
                        + "idsHandlePropertyRequest); same MiniStore "
                        + "as the phone snapshot; identifier "
                        + "logged=false.");
            }
            case SEND_PROPERTY_REQUEST -> {
                NanoRegistryPropertyCodec.PropertyRequest message =
                        action.propertyRequest();
                try {
                    output = activeIdsBridge.sendClassC(message);
                } finally { message.destroy(); }
                label = "INITIAL NANO TX";
                log("INITIAL NANO TX: Class-C PropertyRequest "
                        + "type=4 protobuf request, expectsPeerResponse=true "
                        + "empty NRPBPropertyRequest; "
                        + "polling Watch full-property snapshot.");
            }
            case PAIRING_TRANSPORT_COMPLETE -> {
                checkpointInitialPropertiesReceived(remoteCid);
                return;
            }
            default -> throw new HostException(
                    "Unsupported initial NanoRegistry action");
        }
        for (IdsModernSessionCoordinator.SessionEvent event : output.idsEvents()) {
            if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT) {
                activeInitialSetup.onPhoneMessageQueued(action.type, event.sequence);
            }
        }
        processModernIdsOutput(
                output,
                remoteCid,
                label);
    }

    private static String idsServiceLogLabel(
            String service) {
        if (service == null) {
            return "none";
        }
        if (IdsIpsecServiceRoute.CONTROL_SERVICE.equals(
                service)) {
            return "control";
        }
        if (NanoRegistryInitialIdsRoute
                .classDSetup()
                .serviceConnectorName
                .encode()
                .equals(
                        service)) {
            return "nano-class-d";
        }
        if (NanoRegistryInitialIdsRoute
                .classCProperties()
                .serviceConnectorName
                .encode()
                .equals(
                        service)) {
            return "nano-class-c";
        }
        int slash =
                service.lastIndexOf(
                        '/');
        if (slash >= 0
                && slash + 1 < service.length()
                && service.substring(
                        slash + 1).startsWith(
                        "UTunDelivery-")) {
            return service.substring(
                    slash + 1);
        }
        return "other";
    }

    private static String idsTopicLogLabel(
            String topic) {
        if (topic == null) {
            return "none";
        }
        if (NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(
                topic)) {
            return "nano-class-d";
        }
        if (NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(
                topic)) {
            return "nano-class-c";
        }
        if (IdsControlChannelCodec.isKnownTypeName(
                topic)) {
            return topic;
        }
        int slash =
                topic.indexOf(
                        '/');
        if (slash > 0
                && IdsControlChannelCodec.isKnownTypeName(
                        topic.substring(
                                0,
                                slash))) {
            return topic;
        }
        if (IdsApplicationRoute.PB_BRIDGE_SERVICE.equals(
                topic)) {
            return "pbbridge";
        }
        if (IdsApplicationRoute.PAIRED_SYNC_SERVICE.equals(
                topic)) {
            return "pairedsync";
        }
        if (IdsApplicationRoute.PREFERENCE_SYNC_SERVICE.equals(
                topic)) {
            return "preferences";
        }
        final String alloyPrefix =
                "com.apple.private.alloy.";
        if (topic.startsWith(
                alloyPrefix)
                && topic.length() > alloyPrefix.length()) {
            return topic.substring(
                    alloyPrefix.length());
        }
        return "other";
    }

    boolean readyForCleanStop() {
        return readyToCommitIsPaired;
    }

    private void checkpointOrdinaryRegistration(
            NormalLinkPipeSession pipe)
            throws Exception {
        PairingSessionRecord.DurableState state =
                activePairingSessionRecord.state();
        if (state
                == PairingSessionRecord.DurableState
                .NETWORK_RELAY_PRELUDE_NEGOTIATED
                && pipe.classDEstablished()) {
            AppleNetworkRelayInnerAddresses authoritative =
                    null;
            PairingSessionRecord next = null;
            try {
                if (activePairingSessionRecord.localRole()
                        == NrLinkBluetoothPrelude.LocalRole
                        .INITIATOR) {
                    authoritative =
                            pipe.copyAuthoritativeAddresses();
                }
                next =
                        activePairingSessionRecord
                                .withClassDEstablished(
                                        authoritative);
                persistAndReplaceActivePairingSession(
                        next);
                next = null;
                log("NORMAL CLASS-D CHECKPOINT PASS: "
                        + "authenticated address authority="
                        + activePairingSessionRecord
                                .addressesConfirmed()
                        + "; address/key bytes logged=false.");
            } finally {
                if (next != null) {
                    next.destroy();
                }
                if (authoritative != null) {
                    authoritative.destroy();
                }
            }
            state =
                    activePairingSessionRecord.state();
        }
        if (state
                == PairingSessionRecord.DurableState
                .CLASS_D_ESTABLISHED
                && pipe.classCEstablished()) {
            PairingSessionRecord classCEstablished = null;
            PairingSessionRecord next =
                    null;
            try {
                classCEstablished =
                        activePairingSessionRecord.advanceTo(
                                PairingSessionRecord.DurableState
                                        .CLASS_C_ESTABLISHED);
                next =
                        classCEstablished
                                .markIdsAuthenticationAccepted();
                persistAndReplaceActivePairingSession(
                        next);
                next = null;
                log("NORMAL CLASS-C CHECKPOINT PASS: "
                        + "registration key-confirmation barrier "
                        + "is durable; local IDS paired-device "
                        + "record accepted and pending authData "
                        + "removed; secret bytes logged=false.");
            } finally {
                if (next != null) {
                    next.destroy();
                }
                if (classCEstablished != null) {
                    classCEstablished.destroy();
                }
            }
        }
    }

    private void checkpointIdsControlReady()
            throws Exception {
        PairingSessionRecord.DurableState state =
                activePairingSessionRecord.state();
        if (state.wireValue()
                >= PairingSessionRecord.DurableState
                .IDS_CONTROL_READY.wireValue()) {
            return;
        }
        if (state
                != PairingSessionRecord.DurableState
                .CLASS_C_ESTABLISHED
                || !activePairingSessionRecord
                .idsAuthenticationAccepted()) {
            throw new HostException(
                    "IDS control Hello arrived before local pairing "
                            + "registration");
        }
        PairingSessionRecord next =
                activePairingSessionRecord.advanceTo(
                        PairingSessionRecord.DurableState
                                .IDS_CONTROL_READY);
        try {
            persistAndReplaceActivePairingSession(
                    next);
            next = null;
            log("IDS CONTROL CHECKPOINT PASS: peer Hello and "
                    + "compatibility accepted; identifiers/sequence "
                    + "logged=false.");
        } finally {
            if (next != null) {
                next.destroy();
            }
        }
    }

    private void checkpointIdsDataReady()
            throws Exception {
        PairingSessionRecord.DurableState state =
                activePairingSessionRecord.state();
        if (state.wireValue()
                >= PairingSessionRecord.DurableState
                .IDS_DATA_READY.wireValue()) {
            return;
        }
        if (state
                != PairingSessionRecord.DurableState
                .IDS_CONTROL_READY) {
            if (state == PairingSessionRecord.DurableState.CLASS_C_ESTABLISHED
                    && activePairingSessionRecord.idsAuthenticationAccepted()) {
                checkpointIdsControlReady();
                state = activePairingSessionRecord.state();
            } else {
                throw new HostException(
                        "IDS data lanes joined before the durable "
                                + "control Hello checkpoint");
            }
        }
        PairingSessionRecord next =
                activePairingSessionRecord.advanceTo(
                        PairingSessionRecord.DurableState
                                .IDS_DATA_READY);
        try {
            persistAndReplaceActivePairingSession(
                    next);
            next = null;
            log("IDS DATA CHECKPOINT PASS: initial Class-D and "
                    + "Class-C lanes joined; application setup "
                    + "commit attempted=false.");
        } finally {
            if (next != null) {
                next.destroy();
            }
        }
    }

    private void checkpointInitialPropertiesReceived(
            int remoteCid)
            throws Exception {
        if (activeInitialSetup == null || !activeInitialSetup.snapshot().identityValidated
                || !activeInitialSetup.snapshot().transportCompletionIssued) {
            throw new HostException("Initial properties require this-session Watch identity evidence");
        }
        // Properties delivered as DataMessage take the initial adapter path
        // rather than the coordinator's protobuf validator. Both must
        // publish the same observed capability state before post-commit.
        activeIdsBridge.setPairedSyncCapability(activeInitialSetup.pairedSyncCapabilityStatus());
        PairingSessionRecord.DurableState state =
                activePairingSessionRecord.state();
        if (state.wireValue()
                >= PairingSessionRecord.DurableState
                .READY_TO_COMMIT_IS_PAIRED.wireValue()) {
            initialPropertiesReceived = true;
            readyToCommitIsPaired = true;
            checkpointReadyToCommitIsPaired(
                    remoteCid);
            return;
        }
        if (state
                == PairingSessionRecord.DurableState
                .INITIAL_PROPERTIES_RECEIVED) {
            initialPropertiesReceived = true;
            checkpointReadyToCommitIsPaired(
                    remoteCid);
            return;
        }
        if (state
                == PairingSessionRecord.DurableState
                .IDS_CONTROL_READY) {
            checkpointIdsDataReady();
            state = activePairingSessionRecord.state();
        }
        if (state
                != PairingSessionRecord.DurableState
                .IDS_DATA_READY
                || activeInitialSetup == null
                || !activeInitialSetup.snapshot()
                .transportCompletionIssued
                || !activeInitialSetup.snapshot()
                .identityValidated) {
            throw new HostException(
                    "Initial properties cannot become durable before "
                            + "the exact Watch identity barrier");
        }
        // Resolve the Watch's own choices before the IsPaired/activation boundary.
        // Missing values must never become the Android phone's locale defaults.
        activeInitialSetup.watchLocaleSnapshot();
        PairingSessionRecord next =
                activePairingSessionRecord.advanceTo(
                        PairingSessionRecord.DurableState
                                .INITIAL_PROPERTIES_RECEIVED);
        try {
            persistAndReplaceActivePairingSession(
                    next);
            next = null;
            initialPropertiesReceived = true;
            log("INITIAL PROPERTIES CHECKPOINT PASS: full Watch "
                    + "Class-C snapshot atomically validated "
                    + "ProductType=Watch7,5, non-empty ModelNumber, "
                    + "ChipID=0x8310 and PairingSessionID capability; "
                    + "Configure sent; IsPaired/activation attempted=false; "
                    + "property values/UUIDs logged=false.");
        } finally {
            if (next != null) {
                next.destroy();
            }
        }
        checkpointReadyToCommitIsPaired(
                remoteCid);
    }

    private void checkpointReadyToCommitIsPaired(
            int remoteCid)
            throws Exception {
        PairingSessionRecord.DurableState state =
                activePairingSessionRecord.state();
        if (state.wireValue()
                >= PairingSessionRecord.DurableState
                .READY_TO_COMMIT_IS_PAIRED.wireValue()) {
            readyToCommitIsPaired = true;
            startPostCommitIfAuthorized(
                    remoteCid);
            return;
        }
        if (state
                != PairingSessionRecord.DurableState
                .INITIAL_PROPERTIES_RECEIVED
                || !initialPropertiesReceived
                || !modernIdsReady
                || activeIdsBridge == null
                || activeInitialSetup == null) {
            throw new HostException(
                    "Commit gate cannot become durable before the local "
                            + "Bridge/daemon-equivalent readiness barrier");
        }
        PairingSessionRecord next =
                activePairingSessionRecord.advanceTo(
                        PairingSessionRecord.DurableState
                                .READY_TO_COMMIT_IS_PAIRED);
        try {
            persistAndReplaceActivePairingSession(
                    next);
            next = null;
            readyToCommitIsPaired = true;
            log("READY-TO-COMMIT CHECKPOINT PASS: iOS-only app "
                    + "removability, Bridge notification, NanoLaunchDaemon "
                    + "XPC and launch-delay entries require no Watch wire "
                    + "message on Android; exact IDS D/C and full-property "
                    + "barriers remain live; IsPaired/activation "
                    + "attempted=false; explicit commit authorization="
                    + commitIsPairedAuthorized
                    + ".");
        } finally {
            if (next != null) {
                next.destroy();
            }
        }

        startPostCommitIfAuthorized(
                remoteCid);
    }

    private void submitPostCommitSend(
            AppleWatchPostCommitIdsAdapter.PreparedSend send,
            AppleWatchPostCommitCoordinator.Action action,
            int remoteCid) throws Exception {
        boolean retained = false;
        try {
            int submittedFrames = send.outboundFrames().size();
            sendNormalFrames(remoteCid, send.outboundFrames(), "POST-COMMIT " + action.type);
            pendingPostCommitSends.add(send);
            retained = true;
            if (action.type == AppleWatchPostCommitCoordinator.ActionType.SEND_ACTIVATION_PERMIT
                    || action.type == AppleWatchPostCommitCoordinator.ActionType.SEND_ACTIVATION_RETRY) {
                if (activationPermitQueuedAtMs < 0) {
                    activationPermitQueuedAtMs = BridgeClock.elapsedRealtime();
                }
                activationPermitL2capFrames += submittedFrames;
                activationPermitAwaitingFlush = true;
                if (submittedFrames == 0) {
                    log("POST-COMMIT PERMIT HELD: type=" + action.type
                            + " produced no L2CAP frame; the ERTM window or deferred IPv6 queue still holds it. "
                            + "The activation grace starts only after a flush.");
                }
            }
            log("POST-COMMIT QUEUED: type=" + action.type
                    + " pending=" + pendingPostCommitSends.size()
                    + "; Watch apply confirmation is separate.");
        } finally {
            if (!retained) send.close();
        }
    }

    private void completePendingPostCommitSends(int remoteCid) throws Exception {
        if (activeIdsBridge == null || activePostCommitAdapter == null) return;
        List<AppleWatchPostCommitCoordinator.Action> actions =
                pendingPostCommitSends.completeDrained(
                        activeIdsBridge.hasPendingApplicationFrames(),
                        activeIdsBridge.hasDeferredIpv6());
        markActivationPermitOnWireIfFlushed();
        executePostCommitActions(actions, remoteCid);
    }

    private void pollActivationProxy(int remoteCid) throws Exception {
        if (activationProxyWorker == null) return;
        ActivationProxyWorker.Result result = activationProxyWorker.poll();
        if (result != null) {
            try (result) {
                if (activePostCommitCoordinator == null
                        || activePostCommitCoordinator.snapshot().generation != result.generation) return;
                if (result.failure == null) {
                    MobileActivationHttpProxy.ProxyResponse response =
                            result.takeResponse();
                    if (result.kind
                            == MobileActivationHttpProxy.RequestKind.ACTIVATION
                            && ActivationChallengeCodec.isChallengePage(
                            response.body())) {
                        // Albert answered with a buddyml page
                        // (FMIPLockChallenge credentials form, an alert
                        // or a retry page). Forwarding any of them to
                        // the Watch only produced MA "Failed to extract
                        // activation record"; hold the Watch request and
                        // resolve the page with the owner instead.
                        ActivationChallengeCodec.PageInfo page =
                                ActivationChallengeCodec.classify(
                                        response.body());
                        captureActivationArtifact(
                                "challenge-page",
                                response.body());
                        ActivationChallengeManager.offerPage(
                                page,
                                result.generation,
                                result.attempt);
                        response.destroy();
                        return;
                    }
                    if (result.kind
                            == MobileActivationHttpProxy.RequestKind.ACTIVATION) {
                        captureActivationArtifact(
                                "activation-raw-body",
                                response.body());
                        captureActivationArtifact(
                                "activation-raw-headers",
                                response.archivedHeaders());
                        response = canonicalizeActivationResponse(response);
                    }
                    if (pendingActivationResponse != null) pendingActivationResponse.destroy();
                    pendingActivationResponse = response;
                    log("ACTIVATION HTTPS RESPONSE: status=" + pendingActivationResponse.statusCode()
                            + " kind=" + result.kind
                            + " bodyBytes=" + pendingActivationResponse.body().length
                            + " bodyPreview=\"" + printablePreview(pendingActivationResponse.body(), 512)
                            + "\".");
                    captureActivationArtifact(
                            result.kind.name().toLowerCase(Locale.US) + "-response-body",
                            pendingActivationResponse.body());
                    captureActivationArtifact(
                            result.kind.name().toLowerCase(Locale.US) + "-response-headers",
                            pendingActivationResponse.archivedHeaders());
                } else {
                    log("ACTIVATION PROXY FAIL: " + safeMessage(result.failure));
                }
                executePostCommitActions(activePostCommitCoordinator.onActivationProxyResult(
                        result.generation, result.attempt, result.kind, result.failure == null), remoteCid);
            }
        }
        pollActivationChallenge(remoteCid);
        pollForceActivationConfirmed(remoteCid);
        pollRedriveActivation(remoteCid);
        pollForceSetupObserved(remoteCid);
        pollRetryActivation(remoteCid);
        pollPublishPairedSync(remoteCid);
        pollSyncProgress(remoteCid);
    }

    /** True once the automatic type-19 completion update went out. */
    private boolean syncProgressAutoSent;

    private void pollSyncProgress(int remoteCid) throws Exception {
        if (activePostCommitAdapter == null
                || activePostCommitCoordinator == null) {
            return;
        }
        if (!syncProgressAutoSent
                && activePostCommitCoordinator.snapshot().initialSyncPrepared) {
            syncProgressAutoSent = true;
            PENDING_SYNC_PROGRESS.add(new double[] {1.0, 3});
            log("SYNC PROGRESS: Watch answered PrepareInitialSync; sending completion update (progress=1.0 state=3).");
        }
        double[] next;
        while ((next = PENDING_SYNC_PROGRESS.poll()) != null) {
            AppleWatchPostCommitIdsAdapter.DiagnosticSend send =
                    activePostCommitAdapter.prepareDiagnosticSyncProgress(
                            next[0],
                            (int) next[1]);
            try {
                sendNormalFrames(remoteCid, send.outboundFrames(), "SYNC_PROGRESS");
                log("SYNC PROGRESS TX: PBBridge type 19 progress=" + next[0]
                        + " state=" + (int) next[1] + " topic=" + send.topic
                        + " sequence=" + send.sequence + ".");
            } finally {
                send.close();
            }
        }
    }

    private void pollPublishPairedSync(int remoteCid) throws Exception {
        if (!PENDING_PUBLISH_PAIRED_SYNC.compareAndSet(true, false)) return;
        if (activePostCommitAdapter == null
                || activePostCommitCoordinator == null) {
            log("PUBLISH PAIRED SYNC: no active post-commit session; diagnostic republish ignored.");
            return;
        }
        double now = (System.currentTimeMillis() / 1000.0) - 978307200.0;
        AppleWatchPostCommitIdsAdapter.DiagnosticSend send =
                activePostCommitAdapter.prepareDiagnosticPairedSyncRepublish(now);
        try {
            sendNormalFrames(remoteCid, send.outboundFrames(), "DIAG PUBLISH_PAIRED_SYNC");
            log("PUBLISH PAIRED SYNC: diagnostic completion republished on the live session; topic="
                    + send.topic + " sequence=" + send.sequence + ".");
        } finally {
            send.close();
        }
    }

    private void pollForceActivationConfirmed(int remoteCid) throws Exception {
        if (!PENDING_FORCE_ACTIVATION_CONFIRMED.compareAndSet(true, false)) return;
        if (activePostCommitCoordinator == null) {
            log("FORCE ACTIVATION CONFIRMED: no active post-commit coordinator; override ignored.");
            return;
        }
        long generation = activePostCommitCoordinator.snapshot().generation;
        log("FORCE ACTIVATION CONFIRMED: owner override — Albert 200 record was delivered and the Watch reported no rejection; checkpointing ACTIVATION_CONFIRMED to resume the setup flow.");
        executePostCommitActions(activePostCommitCoordinator.onActivationSucceeded(generation), remoteCid);
    }

    private void pollForceSetupObserved(int remoteCid) throws Exception {
        if (!PENDING_FORCE_SETUP_OBSERVED.compareAndSet(true, false)) return;
        if (activePostCommitCoordinator == null) {
            log("FORCE SETUP OBSERVED: no active post-commit coordinator; override ignored.");
            return;
        }
        long generation = activePostCommitCoordinator.snapshot().generation;
        log("FORCE SETUP OBSERVED: owner override — the Watch already reported gizmoDidFinishActivating on a previous live session (consumed there); replaying the evidence into this coordinator so the setup-finish chain resumes.");
        executePostCommitActions(activePostCommitCoordinator.onGizmoDidFinishActivating(generation), remoteCid);
    }

    private void pollRedriveActivation(int remoteCid) throws Exception {
        if (!PENDING_REDRIVE_ACTIVATION.compareAndSet(true, false)) return;
        if (activePostCommitCoordinator == null) {
            log("REDRIVE ACTIVATION: no active post-commit coordinator; override ignored.");
            return;
        }
        long generation = activePostCommitCoordinator.snapshot().generation;
        log("REDRIVE ACTIVATION: owner override — the Watch was erased after the synthetic activation checkpoint; downgrading the durable checkpoint to IS_PAIRED_COMMITTED and re-asserting CanBeginActivation so the Watch drives ProxyActivation.");
        activationPermitResendCount = 0;
        executePostCommitActions(activePostCommitCoordinator.onActivationRedriveRequested(generation), remoteCid);
    }

    private void pollRetryActivation(int remoteCid) throws Exception {
        if (!PENDING_RETRY_ACTIVATION.compareAndSet(true, false)) return;
        if (activePostCommitCoordinator == null) {
            log("RETRY ACTIVATION: no active post-commit coordinator; override ignored.");
            return;
        }
        long generation = activePostCommitCoordinator.snapshot().generation;
        log("RETRY ACTIVATION: owner override — sending PBBridge RetryActivation (message 15); the Watch runs _cleanup, returns to Idle and re-starts activation unconditionally.");
        activationPermitResendCount = 0;
        executePostCommitActions(activePostCommitCoordinator.onActivationRetryRequested(generation), remoteCid);
    }

    /**
     * Applies the outcome of the owner-credentials Activation Lock
     * challenge. A resolved record is injected exactly where the Albert
     * worker result would have landed; failures and cancellations take
     * the retryable rejection path so the Watch may re-request.
     */
    private void pollActivationChallenge(int remoteCid) throws Exception {
        ActivationChallengeManager.Resolution resolution =
                ActivationChallengeManager.pollResolution();
        if (resolution == null) return;
        try {
            if (activePostCommitCoordinator == null
                    || activePostCommitCoordinator.snapshot().generation != resolution.generation) {
                return;
            }
            if (resolution.isSuccess()) {
                byte[] deliveryBody = resolution.body;
                try {
                    ActivationResponseCanonicalizer.Result transformed =
                            ActivationResponseCanonicalizer.maybeTransform(
                                    resolution.body);
                    if (transformed.changed()) {
                        log("ACTIVATION BODY CANONICALIZED: variant="
                                + transformed.variant()
                                + " in=" + transformed.inputLength()
                                + " out=" + transformed.body().length + ".");
                        captureActivationArtifact(
                                "activation-delivered-body",
                                transformed.body());
                        deliveryBody = transformed.body();
                    } else {
                        log("ACTIVATION CANONICALIZE SKIPPED: "
                                + transformed.note());
                    }
                } catch (Exception failure) {
                    log("ACTIVATION CANONICALIZE FAILED: "
                            + safeMessage(failure)
                            + "; delivering raw body.");
                }
                if (pendingActivationResponse != null) pendingActivationResponse.destroy();
                pendingActivationResponse =
                        MobileActivationHttpProxy.proxyResponseOf(
                                resolution.statusCode,
                                deliveryBody,
                                resolution.archivedHeaders);
                log("ACTIVATION CHALLENGE RESOLVED: status=" + resolution.statusCode
                        + " bodyBytes=" + resolution.body.length
                        + "; forwarding the activation record to the Watch.");
                captureActivationArtifact(
                        "activation-resolved-body",
                        resolution.body);
                captureActivationArtifact(
                        "activation-resolved-headers",
                        resolution.archivedHeaders);
                executePostCommitActions(activePostCommitCoordinator.onActivationProxyResult(
                        resolution.generation, resolution.attempt,
                        MobileActivationHttpProxy.RequestKind.ACTIVATION, true), remoteCid);
            } else {
                log("ACTIVATION CHALLENGE UNRESOLVED: "
                        + (resolution.cancelled ? "owner cancelled" : safeMessage(new Exception(resolution.failure)))
                        + "; Watch may re-request activation.");
                executePostCommitActions(activePostCommitCoordinator.onActivationProxyResult(
                        resolution.generation, resolution.attempt,
                        MobileActivationHttpProxy.RequestKind.ACTIVATION, false), remoteCid);
            }
        } finally {
            resolution.destroy();
        }
    }

    private SetupProgressState.Phase publishedSetupPhase;

    private void publishSetupProgress() {
        if (operationalPairingId != null || activePostCommitCoordinator == null) return;
        SetupProgressState.Phase phase = SetupProgressState.postCommit(activePostCommitCoordinator.snapshot());
        if (phase == publishedSetupPhase) return;
        publishedSetupPhase = phase;
        System.out.println(SetupProgressState.encode(phase));
        System.out.flush();
    }

    private void executePostCommitActions(
            List<AppleWatchPostCommitCoordinator.Action> initialActions,
            int remoteCid)
            throws Exception {
        publishSetupProgress();
        if (initialActions == null || initialActions.isEmpty()) {
            return;
        }
        if (activePostCommitCoordinator == null
                || activePostCommitAdapter == null) {
            throw new HostException(
                    "Post-commit coordinator or adapter is absent");
        }
        List<AppleWatchPostCommitCoordinator.Action> queue =
                new ArrayList<>(initialActions);
        while (!queue.isEmpty()) {
            AppleWatchPostCommitCoordinator.Action action =
                    queue.remove(0);
            long gen = action.generation;
            switch (action.type) {
                case REQUEST_EXPLICIT_IS_PAIRED_CONFIRMATION,
                     REQUEST_EXPLICIT_IS_PAIRED_RETRY -> {
                    if (commitIsPairedAuthorized) {
                        log("POST-COMMIT: explicit IsPaired confirmation authorized by target configuration.");
                        queue.addAll(
                                action.type == AppleWatchPostCommitCoordinator.ActionType.REQUEST_EXPLICIT_IS_PAIRED_CONFIRMATION
                                        ? activePostCommitCoordinator.onExplicitCommitDecision(gen, true)
                                        : activePostCommitCoordinator.onExplicitCommitRetryDecision(gen, true));
                    } else {
                        log("POST-COMMIT: IsPaired confirmation required; stopping at safe boundary.");
                    }
                }
                case PERSIST_IS_PAIRED_COMMIT_INTENT -> {
                    if (activePairingSessionRecord.state() == PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED
                            && !activePairingSessionRecord.isPairedCommitIntentPersisted()) {
                        PairingSessionRecord next =
                                activePairingSessionRecord.prepareIsPairedCommit(true);
                        persistAndReplaceActivePairingSession(next);
                        log("POST-COMMIT: IsPaired write-ahead commit intent persisted atomically.");
                    }
                    queue.addAll(
                            activePostCommitCoordinator.onCommitIntentPersisted(gen, true));
                }
                case PERSIST_DURABLE_STATE -> {
                    if (action.durableState.wireValue() > activePairingSessionRecord.state().wireValue()) {
                        PairingSessionRecord next =
                                action.durableState == PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED
                                        ? activePairingSessionRecord.confirmIsPairedCommit(true)
                                        : activePairingSessionRecord.advanceObservedSetupTo(action.durableState);
                        persistAndReplaceActivePairingSession(next);
                    }
                    log("POST-COMMIT CHECKPOINT PASS: state=" + action.durableState);
                    queue.addAll(
                            activePostCommitCoordinator.onDurableStatePersisted(gen, action.durableState, true));
                }
                case PERSIST_ACTIVATION_REDRIVE -> {
                    boolean redrivePersisted = false;
                    try {
                        if (activePairingSessionRecord != null
                                && activePairingSessionRecord.state().wireValue()
                                        >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()) {
                            PairingSessionRecord next =
                                    activePairingSessionRecord.redriveActivationCheckpoint();
                            persistAndReplaceActivePairingSession(next);
                        }
                        redrivePersisted = true;
                    } catch (RuntimeException failure) {
                        log("POST-COMMIT: activation redrive persist failed: " + safeMessage(failure));
                    }
                    if (redrivePersisted) {
                        log("POST-COMMIT: activation checkpoint cleared (durable=IS_PAIRED_COMMITTED, keys and bond retained); replaying the activation drive.");
                    }
                    queue.addAll(
                            activePostCommitCoordinator.onActivationRedrivePersisted(gen, redrivePersisted));
                }
                case SEND_PAIRING_MODE_NORMAL,
                     SEND_ACTIVATION_PERMIT,
                     SEND_ACTIVATION_RETRY,
                     SEND_NORMAL_AFTER_LANGUAGE_RELAUNCH,
                     SEND_PREPARE_INITIAL_SYNC,
                     SEND_PB_BRIDGE_NORMAL,
                     SEND_COMPUTED_TIME_ZONE,
                     SEND_LANGUAGE_AND_LOCALE,
                     SEND_PAIRED_SYNC_COMPLETION -> {
                    if (activePostCommitAdapter.isDispatched(action)) {
                        continue;
                    }
                    AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                            HalPostCommitOrchestrator.prepareActionSend(action, activePostCommitAdapter,
                                    action.type == AppleWatchPostCommitCoordinator.ActionType.SEND_LANGUAGE_AND_LOCALE
                                            && activeInitialSetup != null ? activeInitialSetup.watchLocaleSnapshot() : null);
                    submitPostCommitSend(prepared, action, remoteCid);
                    queue.addAll(pendingPostCommitSends.completeDrained(
                            activeIdsBridge.hasPendingApplicationFrames(),
                            activeIdsBridge.hasDeferredIpv6()));
                    markActivationPermitOnWireIfFlushed();
                }
                case EXECUTE_ACTIVATION_HTTPS -> {
                    if (activationProxyWorker == null || pendingActivationPayload == null) {
                        throw new HostException("Activation job has no authenticated Watch request");
                    }
                    try {
                        activationProxyWorker.start(gen, action.activationAttempt,
                                action.activationKind, pendingActivationPayload);
                        log("POST-COMMIT: Albert HTTPS started asynchronously; servicing Bluetooth while awaiting response.");
                    } finally {
                        wipe(pendingActivationPayload);
                        pendingActivationPayload = null;
                    }
                }
                case SEND_ACTIVATION_DATA -> {
                    log("POST-COMMIT: sending activation data to Watch.");
                    if (pendingActivationResponse != null && activePostCommitAdapter != null) {
                        try {
                            AppleWatchPostCommitIdsAdapter.PreparedSend send =
                                    activePostCommitAdapter.prepareActivationData(action, pendingActivationResponse);
                            submitPostCommitSend(send, action, remoteCid);
                            queue.addAll(pendingPostCommitSends.completeDrained(
                                    activeIdsBridge.hasPendingApplicationFrames(),
                                    activeIdsBridge.hasDeferredIpv6()));
                        } finally {
                            pendingActivationResponse.destroy();
                            pendingActivationResponse = null;
                        }
                    } else {
                        log("POST-COMMIT: SEND_ACTIVATION_DATA cannot execute - no pending proxy response.");
                    }
                }
                case WAIT_FOR_PB_BRIDGE_NORMAL_PREREQUISITES -> {
                    log("POST-COMMIT: waiting for Watch language/locale and correlated initial-sync response.");
                }
                case SEND_IDS_LOCAL_PAIRING_SETUP_COMPLETED -> {
                    // iOS dispatches this to its local IDS repository. The Android
                    // equivalent is the durable local setup checkpoint below.
                    log("POST-COMMIT: local IDS setup completion dispatched.");
                    queue.addAll(activePostCommitCoordinator
                            .onIdsSetupCompletedDispatchResult(gen, true));
                }
                case VERIFY_CLOCK_VISIBLE -> {
                    log("POST-COMMIT: waiting for physical Clock observation; not verified by a send or ACK.");
                    System.out.println("EVENT:SETUP_WAIT:clock");
                    System.out.flush();
                }
                case VERIFY_OPERATIONAL_RECONNECT -> {
                    AppleWatchPostCommitCoordinator.OperationalHealthEvidence health =
                            evaluateOperationalHealthEvidence();
                    if (health != null && health.complete()) {
                        log("POST-COMMIT: operational health evidence complete — stock bond present, encrypted paired reconnect after controlled restart (no fresh PIN this session), Class-D/C lanes stable, IDS control+data ready, NanoRegistry Normal and IsSetup observed, bond baseline preserved; confirming.");
                        queue.addAll(activePostCommitCoordinator
                                .onOperationalHealthObserved(gen, health));
                    } else {
                        log("POST-COMMIT: operational health evidence incomplete ("
                                + operationalHealthGaps(health)
                                + "); holding VERIFYING_OPERATIONAL_HEALTH — a later paired-reconnect session re-evaluates it.");
                        System.out.println("EVENT:SETUP_WAIT:reconnect");
                        System.out.flush();
                    }
                }
                case REPORT_FAILURE -> {
                    throw new HostException("Post-commit failure: " + action.failure);
                }
            }
        }
        publishSetupProgress();
        if (!postCommitCompleteLogged
                && activePostCommitCoordinator != null
                && activePostCommitCoordinator.snapshot().phase
                        == AppleWatchPostCommitCoordinator.Phase.COMPLETE) {
            postCommitCompleteLogged = true;
            log("POST-COMMIT COMPLETE: OPERATIONAL_HEALTH_CONFIRMED persisted; Watch pairing, activation, Buddy/IsSetup, PairedSync, Carousel Clock (explicit observation) and controlled-reconnect health barriers all verified. RESULT: PASS criteria met; session keeps the link for live sync.");
            persistStaleFlowObservations(
                    "post-commit complete");
            System.out.println("EVENT:SETUP_COMPLETE:operational");
            System.out.flush();
        }
    }

    /**
     * Builds the operational-health evidence strictly from facts of this
     * live session and the durable record. Nothing is assumed: each field
     * is read from the bond record, the IDS lane state, the control-Hello
     * observation and the post-commit coordinator latches.
     */
    private AppleWatchPostCommitCoordinator.OperationalHealthEvidence evaluateOperationalHealthEvidence() {
        if (activePairingSessionRecord == null
                || activeIdsBridge == null
                || activePostCommitCoordinator == null) {
            return null;
        }
        IdsModernSessionCoordinator.Snapshot ids =
                activeIdsBridge.idsSnapshot();
        AppleWatchPostCommitCoordinator.Snapshot post =
                activePostCommitCoordinator.snapshot();
        boolean stockBond =
                activePairingSessionRecord.hasBluetoothBond();
        return new AppleWatchPostCommitCoordinator.OperationalHealthEvidence(
                stockBond,
                stockBond && !freshPairingPerformed,
                activeIdsBridge.classDLaneStableForApplication(),
                activeIdsBridge.classCLaneStableForApplication(),
                idsControlReadyObserved || ids.peerHelloReceived,
                activeIdsBridge.dataLanesReadyForApplication(),
                post.watchCompatibilityNormalObserved && post.isSetupObserved,
                stockBond);
    }

    private String operationalHealthGaps(
            AppleWatchPostCommitCoordinator.OperationalHealthEvidence evidence) {
        if (evidence == null) {
            return "no pairing record, IDS bridge or post-commit coordinator";
        }
        List<String> gaps = new ArrayList<>();
        if (!evidence.stockBondPresent) gaps.add("stockBond");
        if (!evidence.encryptedReconnectAfterControlledRestart) gaps.add("encryptedReconnectAfterControlledRestart");
        if (!evidence.classDStable) gaps.add("classD");
        if (!evidence.classCStable) gaps.add("classC");
        if (!evidence.idsControlReady) gaps.add("idsControl");
        if (!evidence.idsDataReady) gaps.add("idsData");
        if (!evidence.normalAndIsSetupObserved) gaps.add("nanoRegistryNormal/isSetup");
        if (!evidence.unrelatedBondBaselinePreserved) gaps.add("bondBaseline");
        return gaps.isEmpty() ? "none" : String.join(", ", gaps);
    }

    private String addressProjection(
            String phase,
            NormalLinkPipeSession pipe) {
        byte[] initiator = null;
        byte[] responder = null;
        byte[] pipeLocal = null;
        AppleNetworkRelayInnerAddresses quartet = null;
        try {
            quartet = activePairingSessionRecord.innerAddresses();
            initiator = quartet.initiatorClassD();
            responder = quartet.responderClassD();
            pipeLocal = pipe.localClassD();
            return "ADDRESS PROJECTION "
                    + phase
                    + ": pipeRole="
                    + pipe.localRole()
                    + " recordRole="
                    + activePairingSessionRecord.localRole()
                    + " initiatorHost="
                    + hostSuffix(initiator)
                    + " responderHost="
                    + hostSuffix(responder)
                    + " pipeLocalHost="
                    + hostSuffix(pipeLocal);
        } finally {
            if (quartet != null) {
                quartet.destroy();
            }
            wipe(initiator);
            wipe(responder);
            wipe(pipeLocal);
        }
    }

    private static String hostPair(byte[] local, byte[] remote) {
        return hostSuffix(local) + "->" + hostSuffix(remote);
    }

    private static String hostSuffix(byte[] address) {
        if (address == null || address.length < 12) {
            return "absent";
        }
        return String.format(
                Locale.US,
                "%02x%02x",
                address[10] & 0xff,
                address[11] & 0xff);
    }

    private void persistAndReplaceActivePairingSession(
            PairingSessionRecord replacement)
            throws Exception {
        if (replacement == null
                || activePairingSessionRecord == null) {
            throw new HostException(
                    "Pairing-session replacement is incomplete");
        }
        persistPairingSessionOrFail(
                replacement);
        PairingSessionRecord previous =
                activePairingSessionRecord;
        activePairingSessionRecord =
                replacement;
        previous.destroy();
    }

    private HciCodec.L2capPdu awaitNormalL2cap(
            int destinationCid,
            long deadline,
            String label)
            throws Exception {
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet;
            try {
                packet =
                        nextPacket(
                                deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == connectionHandle) {
                    normalAttServer.reset();
                    connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected during %s; "
                                    + "status=0x%02X reason=0x%02X",
                            label,
                            disconnected.status,
                            disconnected.reason));
                }
                if (packet.bytes.length >= 2 && (packet.bytes[0] & 255) != 0x13) {
                    log(String.format(Locale.US, "NORMAL HCI EVENT RX: event=0x%02X subevent=0x%02X bytes=%d; values logged=false.",
                            packet.bytes[0] & 255,
                            packet.bytes[0] == 0x3e && packet.bytes.length > 2 ? packet.bytes[2] & 255 : 0,
                            packet.bytes.length));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap =
                        aclReassembler.accept(
                                packet.bytes);
            } catch (IllegalArgumentException error) {
                log("NORMAL ACL DROP: "
                        + error.getMessage()
                        + "; bytes logged=false.");
                continue;
            }
            if (l2cap == null) {
                continue;
            }
            if (l2cap.connectionHandle != connectionHandle) {
                wipe(
                        l2cap.payload);
                continue;
            }
            if (l2cap.destinationCid != destinationCid) {
                try { serviceNormalFixedChannel(l2cap); }
                finally { wipe(l2cap.payload); }
                continue;
            }
            log(String.format(
                    Locale.US,
                    "NORMAL RX: cid=0x%04X payloadBytes=%d; "
                            + "bytes logged=false.",
                    destinationCid,
                    l2cap.payload.length));
            return l2cap;
        }
        throw new HostException(
                "Timed out awaiting "
                        + label
                        + " on normal CID");
    }

    private void sendNormalTimeSync() throws Exception {
        byte[] information = HciCodec.buildTimeSyncInformation(
                normalBtClVersion, normalBtClFeatures);
        try {
            sendL2cap(HciCodec.BT_CL_SIGNALING_CID, information,
                    "NORMAL BT_CL TIME_SYNC_INFO unixNanos");
        } finally { wipe(information); }
        byte[] correction = HciCodec.buildTimeSyncCorrection(
                normalBtClVersion, normalBtClFeatures, System.nanoTime());
        try {
            sendL2cap(HciCodec.BT_CL_SIGNALING_CID, correction,
                    "NORMAL BT_CL TIME_SYNC_CORRECTION timebase=1/1");
        } finally { wipe(correction); }
    }

    private void serviceNormalFixedChannel(HciCodec.L2capPdu l2cap) throws Exception {
        int cid = l2cap.destinationCid;
        int opcode = l2cap.payload.length == 0 ? -1 : l2cap.payload[0] & 255;
        log(String.format(Locale.US, "NORMAL OTHER CID RX: cid=0x%04X opcode=0x%02X bytes=%d; values logged=false.",
                cid, opcode, l2cap.payload.length));
        if (cid == HciCodec.BT_CL_SIGNALING_CID && opcode == HciCodec.BT_CL_TIME_SYNC_REQUEST) {
            HciCodec.BtClPdu request = HciCodec.parseBtCl(
                    normalBtClVersion, normalBtClFeatures, l2cap.payload);
            if (request.payload.length != 0) {
                log("NORMAL BT_CL TIME_SYNC_REQUEST rejected: nonempty payload.");
                return;
            }
            sendNormalTimeSync();
            log("NORMAL BT_CL TIME_SYNC_REQUEST answered with information and correction.");
        } else if (cid == BluetoothLinkMaintenance.ATT_CID) {
            byte[] response = normalAttServer.accept(connectionHandle, l2cap.payload);
            try {
                if (response != null) {
                    sendL2cap(cid, response, "NORMAL ATT RESPONSE");
                    log("NORMAL ATT TRANSACTION REPLIED: requestOpcode=" + opcode
                            + " responseOpcode=" + (response[0] & 255));
                }
            } finally { wipe(response); }
        } else if (cid == BluetoothLinkMaintenance.LE_SIGNALING_CID) {
            List<byte[]> responses = BluetoothLinkMaintenance.leSignalingReplies(l2cap.payload);
            try {
                for (byte[] response : responses) sendL2cap(cid, response, "NORMAL LE SIGNALING RESPONSE");
            } finally { responses.forEach(response -> wipe(response)); }
        } else if (cid == HciCodec.BT_CL_SIGNALING_CID && opcode == HciCodec.BT_CL_SERVICE_REMOVED) {
            HciCodec.BtClPdu removed = HciCodec.parseBtCl(HciCodec.BT_CL_CURRENT_VERSION, l2cap.payload);
            int service = HciCodec.parseServiceRemoved(removed);
            byte[] confirmation = HciCodec.buildRemoveConfirmationPdu(HciCodec.BT_CL_CURRENT_VERSION, service, 0);
            try { sendL2cap(cid, confirmation, "BT_CL BACKGROUND REMOVE_CONFIRMATION"); }
            finally { wipe(confirmation); }
            log("BT_CL BACKGROUND SERVICE REMOVAL ACK: service=" + service);
        } else if (cid == BluetoothSmpCodec.FIXED_CID && opcode == BluetoothSmpCodec.PAIRING_FAILED) {
            failOnPostSmpPdu(l2cap.payload, "normal-link fixed channel");
        }
    }

    private void sendNormalFrames(
            int remoteCid,
            List<byte[]> frames,
            String label)
            throws Exception {
        if (frames == null) {
            return;
        }
        try {
            for (int index = 0;
                    index < frames.size();
                    index++) {
                if (index > 0) {
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                sendL2cap(
                        remoteCid,
                        frames.get(index),
                        label
                                + " "
                                + (index + 1)
                                + "/"
                                + frames.size());
            }
        } finally {
            for (byte[] frame : frames) {
                wipe(
                        frame);
            }
        }
    }

    private void requireModernRegistrationTime(
            String label)
            throws HostException {
        if (BridgeClock.elapsedRealtime()
                >= modernRegistrationDeadlineMs) {
            throw new HostException(
                    "Modern registration deadline elapsed before "
                            + label);
        }
    }

    private static byte[] typedAddress(
            byte[] address,
            int addressType) {
        if (address == null
                || address.length != 6
                || (addressType != 0
                && addressType != 1)) {
            throw new IllegalArgumentException(
                    "Bluetooth typed address is invalid");
        }
        byte[] typed = new byte[7];
        System.arraycopy(
                address,
                0,
                typed,
                0,
                address.length);
        typed[6] = (byte) addressType;
        return typed;
    }

    private void persistBondSecretOrFail(
            byte[] record)
            throws Exception {
        BluetoothBondSecretRecord
                .validateSerialized(record);
        bondStoreResults.clear();
        String encoded =
                BridgeBase64.encodeToString(
                        record,
                        BridgeBase64.NO_WRAP);
        System.out.println(
                BOND_SECRET_PREFIX + encoded);
        System.out.flush();
        Boolean stored =
                bondStoreResults.poll(
                        BOND_STORE_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
        if (!Boolean.TRUE.equals(stored)) {
            throw new HostException(
                    stored == null
                            ? "Timed out waiting for encrypted "
                            + "bond-store acknowledgement"
                            : "Application rejected encrypted "
                            + "bond-store write");
        }
        log("SMP BOND STORE PASS: application confirmed "
                + "atomic encrypted persistence; "
                + "plaintext/keys logged=false.");
    }

    private void persistPairingSessionOrFail(
            PairingSessionRecord record)
            throws Exception {
        if (record == null) {
            throw new HostException(
                    "Pairing session record is missing");
        }
        byte[] serialized = null;
        byte[] encoded = null;
        PairingSessionRecord verified = null;
        try {
            serialized = record.serialize();
            verified =
                    PairingSessionRecord.parse(
                            serialized);
            if (verified.state() != record.state()
                    || verified.transitionCounter()
                    != record.transitionCounter()
                    || !MessageDigest.isEqual(
                            verified.generationUuid(),
                            record.generationUuid())) {
                throw new HostException(
                        "Pairing session local readback mismatch");
            }
            pairingSessionStoreResults.clear();
            encoded = BridgeBase64.encode(
                    serialized,
                    BridgeBase64.NO_WRAP);
            System.out.print(PAIRING_SESSION_PREFIX);
            System.out.write(encoded);
            System.out.println();
            System.out.flush();
            Boolean stored =
                    pairingSessionStoreResults.poll(
                            PAIRING_SESSION_STORE_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS);
            if (!Boolean.TRUE.equals(stored)) {
                throw new HostException(
                        stored == null
                                ? "Timed out waiting for encrypted "
                                + "pairing-session acknowledgement"
                                : "Application rejected encrypted "
                                + "pairing-session write");
            }
            log("PAIRING SESSION STORE PASS: application confirmed "
                    + "atomic encrypted persistence "
                    + "and decrypted schema/generation readback; "
                    + "plaintext/keys logged=false.");
        } finally {
            if (verified != null) {
                verified.destroy();
            }
            wipe(serialized);
            wipe(encoded);
        }
    }

    private void startLeEncryption(
            byte[] longTermKey)
            throws Exception {
        checkStop();
        hci.sendHciCommand(
                HciCodec.buildCommand(
                        HciCodec
                                .OPCODE_LE_START_ENCRYPTION,
                        HciCodec
                                .buildLeStartEncryptionParameters(
                                        connectionHandle,
                                        longTermKey)));
        boolean commandAccepted = false;
        long deadline =
                BridgeClock.elapsedRealtime()
                        + SMP_ENCRYPTION_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime()
                < deadline) {
            Packet packet;
            try {
                packet = nextPacket(deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == PacketKind.ACL) {
                HciCodec.L2capPdu l2cap;
                try {
                    l2cap =
                            aclReassembler.accept(
                                    packet.bytes);
                } catch (IllegalArgumentException error) {
                    log("SMP ENCRYPTION ACL DROP: "
                            + error.getMessage());
                    continue;
                }
                if (l2cap != null
                        && l2cap.connectionHandle
                        == connectionHandle
                        && l2cap.destinationCid
                        == BluetoothSmpCodec.FIXED_CID) {
                    deferredSmpPdus.offer(
                            l2cap.payload.clone());
                }
                continue;
            }
            HciCodec.CommandStatus status =
                    HciCodec.parseCommandStatus(
                            packet.bytes);
            if (status != null
                    && status.opcode
                    == HciCodec
                    .OPCODE_LE_START_ENCRYPTION) {
                if (status.status != 0) {
                    throw new HostException(
                            String.format(
                                    Locale.US,
                                    "HCI LE Start Encryption "
                                            + "status=0x%02X",
                                    status.status));
                }
                commandAccepted = true;
                log("HCI LE START ENCRYPTION: "
                        + "Command Status accepted; key "
                        + "bytes logged=false.");
                continue;
            }
            HciCodec.DisconnectionComplete disconnected =
                    HciCodec.parseDisconnectionComplete(
                            packet.bytes);
            if (disconnected != null
                    && disconnected.connectionHandle
                    == connectionHandle) {
                connectionHandle = -1;
                throw new HostException(
                        String.format(
                                Locale.US,
                                "Watch disconnected during "
                                        + "LE encryption; "
                                        + "status=0x%02X "
                                        + "reason=0x%02X",
                                disconnected.status,
                                disconnected.reason));
            }
            HciCodec.EncryptionChange change =
                    HciCodec.parseEncryptionChange(
                            packet.bytes);
            if (change == null) {
                change =
                        HciCodec
                                .parseEncryptionKeyRefreshComplete(
                                        packet.bytes);
            }
            if (change != null
                    && change.connectionHandle
                    == connectionHandle) {
                if (change.status != 0
                        || change.encryptionEnabled == 0) {
                    throw new HostException(
                            String.format(
                                    Locale.US,
                                    "LE Encryption Change "
                                            + "status=0x%02X "
                                            + "enabled=%d",
                                    change.status,
                                    change
                                            .encryptionEnabled));
                }
                if (!commandAccepted) {
                    log("HCI LE START ENCRYPTION: "
                            + "Encryption Change arrived "
                            + "before observable Command Status.");
                }
                return;
            }
        }
        throw new HostException(
                "LE Encryption Change timed out after "
                        + SMP_ENCRYPTION_TIMEOUT_MS
                        + " ms");
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private void probeSmpFeatureNegotiation()
            throws Exception {
        BluetoothSmpCodec.PairingFeatures request =
                BluetoothSmpCodec.createPairingRequest();
        log(String.format(
                Locale.US,
                "SMP DIAGNOSTIC TX: Pairing Request "
                        + "io=0x%02X oob=%d auth=0x%02X "
                        + "keySize=%d initiatorKeys=0x%02X "
                        + "responderKeys=0x%02X; secret bytes "
                        + "present=false.",
                request.ioCapability,
                request.oobDataFlag,
                request.authenticationRequirements,
                request.maximumEncryptionKeySize,
                request.initiatorKeyDistribution,
                request.responderKeyDistribution));
        sendL2cap(
                BluetoothSmpCodec.FIXED_CID,
                request.encode(),
                "SMP DIAGNOSTIC PAIRING REQUEST");

        byte[] responsePdu =
                awaitSmpPdu(
                        BluetoothSmpCodec.PAIRING_RESPONSE,
                        SMP_RESPONSE_TIMEOUT_MS,
                        "SMP Pairing Response");
        BluetoothSmpCodec.PairingFeatures response;
        try {
            response =
                    BluetoothSmpCodec
                            .parsePairingResponse(
                                    responsePdu);
        } finally {
            Arrays.fill(responsePdu, (byte) 0);
        }
        log(String.format(
                Locale.US,
                "SMP DIAGNOSTIC RX PASS: Pairing Response "
                        + "io=0x%02X oob=%d auth=0x%02X "
                        + "keySize=%d initiatorKeys=0x%02X "
                        + "responderKeys=0x%02X.",
                response.ioCapability,
                response.oobDataFlag,
                response.authenticationRequirements,
                response.maximumEncryptionKeySize,
                response.initiatorKeyDistribution,
                response.responderKeyDistribution));
        if (response.oobDataFlag
                != BluetoothSmpCodec.OOB_DATA_PRESENT
                || (response.authenticationRequirements
                & BluetoothSmpCodec
                .AUTH_SECURE_CONNECTIONS) == 0
                || (response.authenticationRequirements
                & BluetoothSmpCodec.AUTH_BONDING) == 0) {
            throw new HostException(
                    "Watch SMP response did not confirm "
                            + "mutual OOB, Secure Connections, "
                            + "and bonding");
        }

        sendL2cap(
                BluetoothSmpCodec.FIXED_CID,
                BluetoothSmpCodec.pairingFailed(
                        BluetoothSmpCodec
                                .FAILURE_UNSPECIFIED_REASON),
                "SMP DIAGNOSTIC CONTROLLED ABORT");
        log("SMP DIAGNOSTIC COMPLETE: physical Watch accepted "
                + "Pairing Request and confirmed mutual OOB/SC/bond; "
                + "sent standard Unspecified Reason abort before "
                + "public-key, nonce, DHKey, LTK, encryption, or "
                + "bond state creation.");
    }

    private byte[] awaitSmpPdu(
            int expectedOpcode,
            long timeoutMs,
            String label)
            throws Exception {
        byte[] deferred =
                deferredSmpPdus.poll();
        if (deferred != null) {
            try {
                return validateExpectedSmpPdu(
                        deferred,
                        expectedOpcode,
                        label);
            } finally {
                wipe(deferred);
            }
        }
        long deadline =
                BridgeClock.elapsedRealtime() + timeoutMs;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet;
            try {
                packet = nextPacket(deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == connectionHandle) {
                    connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected while awaiting %s; "
                                    + "status=0x%02X reason=0x%02X",
                            label,
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log(label + " ACL DROP: "
                        + error.getMessage());
                continue;
            }
            if (l2cap == null
                    || l2cap.connectionHandle
                    != connectionHandle
                    || l2cap.destinationCid
                    != BluetoothSmpCodec.FIXED_CID) {
                continue;
            }
            return validateExpectedSmpPdu(
                    l2cap.payload,
                    expectedOpcode,
                    label);
        }
        throw new HostException(
                label
                        + " timed out after "
                        + timeoutMs
                        + " ms");
    }

    private byte[] validateExpectedSmpPdu(
            byte[] payload,
            int expectedOpcode,
            String label)
            throws HostException {
        int opcode;
        try {
            opcode =
                    BluetoothSmpCodec.opcode(
                            payload);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    label + " is malformed: "
                            + error.getMessage(),
                    error);
        }
        if (opcode
                == BluetoothSmpCodec.PAIRING_FAILED) {
            int reason =
                    BluetoothSmpCodec
                            .parsePairingFailure(
                                    payload);
            throw new HostException(String.format(
                    Locale.US,
                    "Watch returned SMP Pairing Failed "
                            + "reason=0x%02X while awaiting %s",
                    reason,
                    label));
        }
        if (opcode != expectedOpcode) {
            throw new HostException(String.format(
                    Locale.US,
                    "Unexpected SMP opcode=0x%02X "
                            + "while awaiting %s",
                    opcode,
                    label));
        }
        log(String.format(
                Locale.US,
                "%s RX: opcode=0x%02X bytes=%d; "
                        + "payload bytes logged=false.",
                label,
                opcode,
                payload.length));
        return payload.clone();
    }

    private static void destroyPrivateNotifies(
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        if (notifies == null) {
            return;
        }
        for (ApplePairingNotifyPayloads.PrivateNotify notify
                : notifies) {
            if (notify != null) {
                notify.destroy();
            }
        }
    }

    private PairingTransportCursor
            sendPairingIkeOneWayAndAwaitAck(
            int responderLocalCid,
            PairingTransportCursor initialCursor,
            List<byte[]> packetsToSend,
            String label,
            long timeoutMs)
            throws Exception {
        if (packetsToSend == null
                || packetsToSend.isEmpty()) {
            throw new HostException(
                    label + " has no IKE packet");
        }
        int outboundTxSequence =
                initialCursor.nextOutboundTxSequence;
        int expectedWatchTxSequence =
                initialCursor.nextExpectedWatchTxSequence;
        for (int index = 0;
                index < packetsToSend.size();
                index++) {
            checkStop();
            byte[] ikePacket = packetsToSend.get(index);
            byte[] uikeFrame =
                    IkeV2Codec.encodeUikeFrame(ikePacket);
            byte[] ertmFrame =
                    L2capErtmCodec.encodeInformationFrame(
                            responderLocalCid,
                            outboundTxSequence,
                            expectedWatchTxSequence,
                            uikeFrame,
                            TERMINUS_FCS_ENABLED);
            log(String.format(
                    Locale.US,
                    "%s TX packet=%d/%d IKE=%d uIKE=%d "
                            + "ERTM=%d TxSeq=%d ReqSeq=%d; "
                            + "encrypted bytes logged=false",
                    label,
                    index + 1,
                    packetsToSend.size(),
                    ikePacket.length,
                    uikeFrame.length,
                    ertmFrame.length,
                    outboundTxSequence,
                    expectedWatchTxSequence));
            sendL2cap(
                    responderLocalCid,
                    ertmFrame,
                    "TERMINUS ERTM " + label);
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }

        int expectedAcknowledgement =
                outboundTxSequence;
        long deadline =
                BridgeClock.elapsedRealtime() + timeoutMs;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet;
            try {
                packet = nextPacket(deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == connectionHandle) {
                    connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected during %s; "
                                    + "status=0x%02X reason=0x%02X",
                            label,
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log(label + " ACL DROP: "
                        + error.getMessage());
                continue;
            }
            if (l2cap == null
                    || l2cap.connectionHandle
                    != connectionHandle
                    || l2cap.destinationCid
                    != HciCodec.TERMINUS_LOCAL_CID) {
                continue;
            }
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed "
                                + label
                                + " ERTM acknowledgement: "
                                + error.getMessage(),
                        error);
            }
            if (ertm.requestSequence
                    == expectedAcknowledgement) {
                log(String.format(
                        Locale.US,
                        "%s ACK PASS: ReqSeq=%d.",
                        label,
                        ertm.requestSequence));
                return new PairingTransportCursor(
                        expectedAcknowledgement,
                        expectedWatchTxSequence);
            }
            if (!ertm.supervisory) {
                throw new HostException(
                        "Unexpected Watch I-frame while awaiting "
                                + label
                                + " acknowledgement");
            }
            if (ertm.poll) {
                sendL2cap(
                        responderLocalCid,
                        L2capErtmCodec.encodeReceiverReady(
                                responderLocalCid,
                                expectedWatchTxSequence,
                                true,
                                TERMINUS_FCS_ENABLED),
                        "TERMINUS ERTM RR FINAL " + label);
            }
            if (ertm.supervisoryFunction
                    == L2capErtmCodec.SUPERVISORY_REJ
                    || ertm.supervisoryFunction
                    == L2capErtmCodec.SUPERVISORY_SREJ) {
                throw new HostException(
                        "Watch rejected " + label);
            }
        }
        throw new HostException(
                label
                        + " ERTM acknowledgement timed out after "
                        + timeoutMs
                        + " ms; expectedReqSeq="
                        + expectedAcknowledgement);
    }

    private <T> PairingTransportResult<T>
            exchangePairingIke(
            int responderLocalCid,
            PairingTransportCursor initialCursor,
            List<byte[]> requestPackets,
            String label,
            long timeoutMs,
            PairingIkeProcessor<T> processor)
            throws Exception {
        if (requestPackets == null
                || requestPackets.isEmpty()
                || processor == null) {
            throw new HostException(
                    label + " has no request or response processor");
        }
        int outboundTxSequence =
                initialCursor.nextOutboundTxSequence;
        int requestSequence =
                initialCursor.nextExpectedWatchTxSequence;
        for (int index = 0;
                index < requestPackets.size();
                index++) {
            checkStop();
            byte[] ikePacket = requestPackets.get(index);
            byte[] uikeFrame =
                    IkeV2Codec.encodeUikeFrame(ikePacket);
            byte[] ertmRequest =
                    L2capErtmCodec.encodeInformationFrame(
                            responderLocalCid,
                            outboundTxSequence,
                            requestSequence,
                            uikeFrame,
                            TERMINUS_FCS_ENABLED);
            if (suppressPacketBytes(label)) {
                log(String.format(
                        Locale.US,
                        "%s TX packet=%d/%d IKE=%d uIKE=%d "
                                + "ERTM=%d TxSeq=%d ReqSeq=%d; "
                                + "packet bytes logged=false",
                        label,
                        index + 1,
                        requestPackets.size(),
                        ikePacket.length,
                        uikeFrame.length,
                        ertmRequest.length,
                        outboundTxSequence,
                        requestSequence));
            } else {
                log(String.format(
                        Locale.US,
                        "%s TX packet=%d/%d IKE=%d uIKE=%d "
                                + "ERTM=%d TxSeq=%d ReqSeq=%d "
                                + "rawEncryptedOrPublicIKE=%s",
                        label,
                        index + 1,
                        requestPackets.size(),
                        ikePacket.length,
                        uikeFrame.length,
                        ertmRequest.length,
                        outboundTxSequence,
                        requestSequence,
                        HciCodec.toHex(ikePacket)));
            }
            sendL2cap(
                    responderLocalCid,
                    ertmRequest,
                    "TERMINUS ERTM " + label);
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }
        int expectedRequestSequence =
                outboundTxSequence;
        int expectedWatchTxSequence =
                initialCursor.nextExpectedWatchTxSequence;
        int highestWatchAck =
                initialCursor.nextOutboundTxSequence;
        int l2capFrames = 0;
        int ikePackets = 0;
        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        long deadline =
                BridgeClock.elapsedRealtime() + timeoutMs;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet;
            try {
                packet = nextPacket(deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == connectionHandle) {
                    connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected during %s; "
                                    + "status=0x%02X reason=0x%02X",
                            label,
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log(label + " ACL DROP: "
                        + error.getMessage());
                continue;
            }
            if (l2cap == null
                    || l2cap.connectionHandle != connectionHandle
                    || l2cap.destinationCid
                    != HciCodec.TERMINUS_LOCAL_CID) {
                continue;
            }
            l2capFrames++;
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed "
                                + label
                                + " ERTM frame: "
                                + error.getMessage(),
                        error);
            }
            if (ertm.requestSequence
                    > expectedRequestSequence) {
                throw new HostException(
                        "Watch acknowledged unsent "
                                + label
                                + " ERTM sequence "
                                + ertm.requestSequence);
            }
            highestWatchAck = Math.max(
                    highestWatchAck,
                    ertm.requestSequence);
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "%s ERTM S-FRAME RX: function=%d "
                                + "ReqSeq=%d P=%d F=%d.",
                        label,
                        ertm.supervisoryFunction,
                        ertm.requestSequence,
                        ertm.poll ? 1 : 0,
                        ertm.finalBit ? 1 : 0));
                if (ertm.poll) {
                    sendL2cap(
                            responderLocalCid,
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    true,
                                    TERMINUS_FCS_ENABLED),
                            "TERMINUS ERTM RR FINAL "
                                    + label);
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    throw new HostException(
                            "Watch rejected " + label);
                }
                continue;
            }
            if (ertm.sar
                    != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented "
                                + label
                                + " ERTM I-frame is not expected");
            }
            int previousWatchTxSequence =
                    (expectedWatchTxSequence
                            + L2capErtmCodec.SEQUENCE_MODULUS
                            - 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            if (ertm.txSequence
                    == previousWatchTxSequence) {
                sendL2cap(
                        responderLocalCid,
                        L2capErtmCodec.encodeReceiverReady(
                                responderLocalCid,
                                expectedWatchTxSequence,
                                false,
                                TERMINUS_FCS_ENABLED),
                        "TERMINUS ERTM RR DUPLICATE "
                                + label);
                continue;
            }
            if (ertm.txSequence
                    != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected "
                                + label
                                + " Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            sendL2cap(
                    responderLocalCid,
                    L2capErtmCodec.encodeReceiverReady(
                            responderLocalCid,
                            expectedWatchTxSequence,
                            false,
                            TERMINUS_FCS_ENABLED),
                    "TERMINUS ERTM RR ACK " + label);

            List<byte[]> decodedPackets;
            try {
                decodedPackets =
                        decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed "
                                + label
                                + " uIKE frame: "
                                + error.getMessage(),
                        error);
            }
            for (byte[] ikePacket : decodedPackets) {
                ikePackets++;
                if (suppressPacketBytes(label)) {
                    log(label
                            + " IKE RX #"
                            + ikePackets
                            + ": bytes="
                            + ikePacket.length
                            + "; packet bytes logged=false.");
                } else {
                    log(label
                            + " IKE RX #"
                            + ikePackets
                            + ": bytes="
                            + ikePacket.length
                            + " rawEncryptedOrPublicIKE="
                            + HciCodec.toHex(ikePacket));
                }
                T value;
                try {
                    value = processor.accept(ikePacket);
                } catch (RuntimeException error) {
                    throw new HostException(
                            label
                                    + " authentication/parse failed: "
                                    + error.getMessage(),
                            error);
                }
                if (value == null) {
                    continue;
                }
                if (highestWatchAck
                        != expectedRequestSequence) {
                    throw new HostException(
                            "Watch "
                                    + label
                                    + " response did not acknowledge "
                                    + "all request I-frames; highest="
                                    + highestWatchAck
                                    + " expected="
                                    + expectedRequestSequence);
                }
                return new PairingTransportResult<>(
                        value,
                        new PairingTransportCursor(
                                expectedRequestSequence,
                                expectedWatchTxSequence));
            }
        }
        throw new HostException(
                label
                        + " timed out after "
                        + timeoutMs
                        + " ms; L2CAP frames="
                        + l2capFrames
                        + " IKE packets="
                        + ikePackets
                        + " highestWatchReqSeq="
                        + highestWatchAck
                        + " expectedReqSeq="
                        + expectedRequestSequence
                        + " buffered="
                        + HciCodec.toHex(
                                decoder.bufferedBytes()));
    }

    private HciCodec.AdvertisingReport awaitTarget() throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + SCAN_TIMEOUT_MS;
        WatchSetupTargetSelector selector = new WatchSetupTargetSelector();
        WatchDiscoverySelection<HciCodec.AdvertisingReport> choices = new WatchDiscoverySelection<>();
        java.util.Map<String, Long> announced = new java.util.HashMap<>();
        int incomplete = 0;
        while (BridgeClock.elapsedRealtime() < deadline) {
            if (COMPANION_DISCOVERY.get()) {
                HciCodec.AdvertisingReport selected = choices.select(COMPANION_WATCH_SELECTIONS.poll(), BridgeClock.elapsedRealtime());
                if (selected != null) {
                    wipe(lastMatchedReportData);
                    lastMatchedReportData = selected.data != null ? selected.data.clone() : null;
                    return selected;
                }
            }
            Packet packet;
            try { packet = nextPacket(Math.min(deadline, BridgeClock.elapsedRealtime() + 250)); }
            catch (TimeoutException elapsed) { continue; }
            if (packet.kind != PacketKind.EVENT) continue;
            for (HciCodec.AdvertisingReport report : HciCodec.parseLegacyAdvertisingReports(packet.bytes)) {
                if (report.address != null && rejectedSetupCandidates.contains(HciCodec.toHex(report.address))) continue;
                HciCodec.AdvertisingReport target = selector.accept(report, BridgeClock.elapsedRealtime());
                if (target != null) {
                    if (opticalPairing != null) {
                        if (!opticalPairing.matches(target.setup.decodedIdentifier, target.setup.decodedMetadata)) continue;
                        wipe(lastMatchedReportData);
                        lastMatchedReportData = target.data == null ? null : target.data.clone();
                        log("OPTICAL TARGET MATCHED: fresh setup advertisement; name/key/address logged=false.");
                        return target;
                    }
                    if (COMPANION_DISCOVERY.get()) {
                        long now = BridgeClock.elapsedRealtime();
                        String token = choices.observe(HciCodec.toHex(target.address), target, now);
                        if (now - announced.getOrDefault(token, -2_000L) >= 1_000) {
                            announced.put(token, now);
                            log("WATCH_DISCOVERED_V1:" + token + ":" + target.setup.decodedMetadata.productType()
                                    + ":" + target.setup.decodedMetadata.systemVersionString() + ":" + target.rssi);
                        }
                        continue;
                    }
                    wipe(lastMatchedReportData);
                    lastMatchedReportData = target.data != null ? target.data.clone() : null;
                    log("WATCH SETUP TARGET MATCHED: complete 12-byte setup data; scanResponse="
                            + (report.eventType == 4) + " rssi=" + report.rssi + "; identifiers logged=false.");
                    return target;
                }
                if (report.setup != null && incomplete++ < 4) {
                    log("WATCH SETUP SKIP: incomplete or non-target advertisement; setupBytes="
                            + report.setup.watchSetupData.length + " eventType=" + report.eventType
                            + "; identifiers logged=false.");
                }
            }
        }
        throw new HostException("Complete target Watch setup advertisement not found in "
                + SCAN_TIMEOUT_MS + " ms; return the Watch to new-pair animation");
    }

    /**
     * Records a peer that is reachable over BT_CL but is not the Watch, then
     * drops the link so the scan can move to the next Apple device.
     */
    private void rejectSetupCandidate(
            HciCodec.AdvertisingReport candidate,
            String reason)
            throws Exception {
        if (candidate != null && candidate.address != null) {
            rejectedSetupCandidates.add(
                    HciCodec.toHex(candidate.address));
        }
        log("SETUP CANDIDATE REJECTED: "
                + reason
                + "; rssi="
                + (candidate != null ? candidate.rssi : 0)
                + "; probing the next Apple device.");
        createPending = false;
        if (connectionHandle != -1) {
            try {
                sendCommandStatus(
                        HciCodec.OPCODE_DISCONNECT,
                        HciCodec.buildDisconnectParameters(connectionHandle));
                long disconnectDeadline =
                        BridgeClock.elapsedRealtime() + 1_500;
                while (BridgeClock.elapsedRealtime() < disconnectDeadline) {
                    Packet packet = nextPacket(disconnectDeadline);
                    if (packet.kind == PacketKind.EVENT) {
                        HciCodec.DisconnectionComplete done =
                                HciCodec.parseDisconnectionComplete(
                                        packet.bytes);
                        if (done != null
                                && done.connectionHandle == connectionHandle) {
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // A lost Disconnection Complete must not abort the probe.
            }
            connectionHandle = -1;
        }
        aclReassembler.reset();
        peerConnectionAddress = null;
    }

    private HciCodec.AdvertisingReport awaitPairedTarget() throws Exception {
        byte[] peerIrk = null;
        byte[] peerIdentityAddr = null;
        if (activePairingSessionRecord != null && activePairingSessionRecord.bluetoothBond() != null) {
            try {
                peerIrk = BluetoothBondSecretRecord.extractPeerIdentityResolvingKey(activePairingSessionRecord.bluetoothBond());
                peerIdentityAddr = BluetoothBondSecretRecord.extractPeerIdentityAddress(activePairingSessionRecord.bluetoothBond());
            } catch (Exception ignored) {
            }
        }

        long deadline = BridgeClock.elapsedRealtime() + 30_000;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet = nextPacket(deadline);
            if (packet.kind != PacketKind.EVENT) {
                continue;
            }
            HciCodec.LeConnectionComplete incoming =
                    HciCodec.parseLeConnectionComplete(packet.bytes);
            if (incoming != null && incoming.status == 0) {
                connectionHandle = incoming.connectionHandle;
                peerConnectionAddressType = incoming.peerAddressType;
                peerConnectionAddress = incoming.peerAddress.clone();
                log(String.format(
                        Locale.US,
                        "[WatchHal] PAIRED INCOMING LE ACL CONNECTED: handle=0x%04X",
                        connectionHandle));
                return null;
            }
            for (HciCodec.AdvertisingReport report
                    : HciCodec.parseLegacyAdvertisingReports(packet.bytes)) {
                if (report.eventType != 0 && report.eventType != 1) {
                    continue;
                }
                if (peerConnectionAddress != null
                        && Arrays.equals(report.address, peerConnectionAddress)) {
                    log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by peer address.");
                    return report;
                }
                if (peerIdentityAddr != null
                        && Arrays.equals(report.address, peerIdentityAddr)) {
                    log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by peer identity address.");
                    return report;
                }
                if (peerIrk != null) {
                    if (BleSecureConnectionsCrypto.matchesRpa(report.address, peerIrk)) {
                        log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by IRK-resolved RPA (rssi=" + report.rssi + ").");
                        return report;
                    }
                } else if (hasAppleManufacturerData(report.data) && report.rssi >= -50) {
                    log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by Apple 0x004C connectable beacon (rssi=" + report.rssi + ").");
                    return report;
                }
                if (report.setup != null && report.isExpectedTarget()) {
                    log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by setup target.");
                    return report;
                }
            }
        }
        throw new HostException(
                "Paired Watch advertisement not found in 30000 ms");
    }

    private static boolean hasAppleManufacturerData(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        int offset = 0;
        while (offset < data.length) {
            int len = data[offset] & 0xff;
            if (len == 0 || offset + 1 + len > data.length) {
                break;
            }
            int type = data[offset + 1] & 0xff;
            if (type == 0xff && len >= 3) {
                int companyId = ((data[offset + 3] & 0xff) << 8) | (data[offset + 2] & 0xff);
                if (companyId == 0x004c) {
                    return true;
                }
            }
            offset += 1 + len;
        }
        return false;
    }

    private static boolean isNearbyWatchBeacon(byte[] data) {
        if (data == null || data.length < 5) {
            return false;
        }
        int offset = 0;
        while (offset < data.length) {
            int len = data[offset] & 0xff;
            if (len == 0 || offset + 1 + len > data.length) {
                break;
            }
            int type = data[offset + 1] & 0xff;
            if (type == 0xff && len >= 4) {
                int companyId = ((data[offset + 3] & 0xff) << 8) | (data[offset + 2] & 0xff);
                if (companyId == 0x004c) {
                    int appleType = data[offset + 4] & 0xff;
                    if (appleType == 0x10 || appleType == 0x0B || appleType == 0x01) {
                        return true;
                    }
                }
            }
            offset += 1 + len;
        }
        return false;
    }

    private static WatchSetupMetadataCodec.Identifier defaultWatchSetupIdentifier() {
        byte[] raw = WatchSetupMetadataCodec.encodeIdentifier(4, 0x12345, 0x01, 0x01);
        return WatchSetupMetadataCodec.decodeIdentifier(raw);
    }

    private static WatchSetupMetadataCodec.ExtendedMetadata defaultWatchSetupMetadata() {
        byte[] raw = WatchSetupMetadataCodec.encodeExtendedMetadata(
                25,
                7,
                5,
                false,
                0x1A000000L,
                new byte[0]);
        return WatchSetupMetadataCodec.decodeExtendedMetadata(raw);
    }

    private HciCodec.LeConnectionComplete awaitConnectionComplete(
            byte[] expectedAddress,
            int expectedAddressType) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet = nextPacket(deadline);
            if (packet.kind != PacketKind.EVENT) {
                continue;
            }
            HciCodec.LeConnectionComplete complete =
                    HciCodec.parseLeConnectionComplete(packet.bytes);
            if (complete == null) {
                continue;
            }
            createPending = false;
            if (complete.status != 0) {
                throw new HostException(String.format(
                        Locale.US,
                        "LE Connection Complete status=0x%02X",
                        complete.status));
            }
            connectionHandle = complete.connectionHandle;
            if (expectedAddress != null && expectedAddress.length == 6) {
                if (!Arrays.equals(complete.peerAddress, expectedAddress)) {
                    continue;
                }
            }
            return complete;
        }
        throw new HostException("LE direct connection timed out");
    }

    private HciCodec.LeConnectionComplete awaitConnectionComplete(
            HciCodec.AdvertisingReport target) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet = nextPacket(deadline);
            if (packet.kind != PacketKind.EVENT) {
                continue;
            }
            HciCodec.LeConnectionComplete complete =
                    HciCodec.parseLeConnectionComplete(packet.bytes);
            if (complete == null) {
                continue;
            }
            createPending = false;
            if (complete.status != 0) {
                throw new HostException(String.format(
                        Locale.US,
                        "LE Connection Complete status=0x%02X",
                        complete.status));
            }
            // Record every successful live handle before validating its
            // peer, so even a surprising connection is disconnected.
            connectionHandle = complete.connectionHandle;
            if (complete.peerAddressType != target.addressType
                    || !Arrays.equals(complete.peerAddress, target.address)) {
                throw new HostException(
                        "LE Connection Complete peer does not match selected target");
            }
            return complete;
        }
        throw new HostException("LE connection timed out");
    }

    private HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion) throws Exception {
        return awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                0L,
                null);
    }

    private HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            long peerFeatures) throws Exception {
        return awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                peerFeatures,
                null,
                null);
    }

    private HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            long peerFeatures,
            byte[] retransmitPdu) throws Exception {
        return awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                peerFeatures,
                retransmitPdu,
                null);
    }

    private HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            BtClNormalLinkHandoff normalHandoff)
            throws Exception {
        return awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                0L,
                null,
                normalHandoff);
    }

    private HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            long peerFeatures,
            byte[] retransmitPdu,
            BtClNormalLinkHandoff normalHandoff)
            throws Exception {
        long now = BridgeClock.elapsedRealtime();
        long deadline = normalHandoff == null
                ? now + BT_CL_TIMEOUT_MS
                : Math.min(
                        now + BT_CL_TIMEOUT_MS,
                        modernRegistrationDeadlineMs);
        long nextRetransmitTime = now + 800;
        if (normalHandoff != null) {
            byte[] deferred;
            while ((deferred = deferredSmpPdus.poll()) != null) {
                try {
                    failOnPostSmpPdu(
                            deferred,
                            "deferred before normal BT_CL handoff");
                } finally {
                    wipe(deferred);
                }
            }
        }
        while (BridgeClock.elapsedRealtime() < deadline) {
            long currentNow = BridgeClock.elapsedRealtime();
            if (retransmitPdu != null && currentNow >= nextRetransmitTime) {
                sendL2cap(destinationCid, retransmitPdu, "BT_CL RETRANSMIT");
                nextRetransmitTime = currentNow + 800;
            }
            Packet packet;
            try {
                long waitLimit = retransmitPdu != null ? Math.min(deadline, nextRetransmitTime) : deadline;
                packet = nextPacket(waitLimit);
            } catch (TimeoutException elapsed) {
                continue;
            }
            if (packet.kind == PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == connectionHandle) {
                    connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "%s: Watch disconnected while awaiting "
                                    + "BT_CL opcode 0x%02X; "
                                    + "status=0x%02X reason=0x%02X",
                            normalHandoff == null
                                    ? "BT_CL_LINK_LOST"
                                    : "WATCH_PAIRING_LINK_LOST",
                            opcode,
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log("ACL DROP: " + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (l2cap == null) {
                continue;
            }
            if (l2cap.connectionHandle != connectionHandle) {
                wipe(l2cap.payload);
                continue;
            }
            if (normalHandoff != null
                    && l2cap.destinationCid
                    == BluetoothSmpCodec.FIXED_CID) {
                try {
                    failOnPostSmpPdu(
                            l2cap.payload,
                            "normal BT_CL handoff");
                } finally {
                    wipe(l2cap.payload);
                }
                continue;
            }
            if (l2cap.destinationCid == 0x0004) {
                byte[] attReply = handleAttRequest(l2cap.payload);
                if (attReply != null) {
                    sendL2cap(0x0004, attReply, "ATT RESPONSE");
                }
                wipe(l2cap.payload);
                if (retransmitPdu != null) {
                    sendL2cap(destinationCid, retransmitPdu, "BT_CL POST_ATT_RETRANSMIT");
                    nextRetransmitTime = BridgeClock.elapsedRealtime() + 800;
                }
                continue;
            }
            if (l2cap.destinationCid != destinationCid) {
                log(String.format(
                        Locale.US,
                        "BT_CL AWAIT OTHER CID RX: cid=0x%04X bytes=%s",
                        l2cap.destinationCid,
                        HciCodec.toHex(l2cap.payload)));
                wipe(l2cap.payload);
                continue;
            }
            try {
                HciCodec.BtClPdu pdu =
                        HciCodec.parseBtCl(
                                peerVersion,
                                peerFeatures,
                                l2cap.payload);
                log(String.format(
                        Locale.US,
                        "BT_CL RX: cid=0x%04X opcode=0x%02X bytes=%s",
                        destinationCid,
                        pdu.opcode,
                        HciCodec.toHex(l2cap.payload)));
                if (pdu.opcode
                        == HciCodec.BT_CL_SERVICE_REMOVED) {
                    int removedService =
                            HciCodec.parseServiceRemoved(pdu);
                    boolean postSmpPairingRemoval =
                            activePairingSessionRecord != null
                                    && activePairingSessionRecord
                                            .state()
                                            .wireValue()
                                    >= PairingSessionRecord
                                            .DurableState
                                            .SMP_BONDED_RAW
                                            .wireValue()
                                    && removedService
                                    == HciCodec
                                            .TERMINUS_PAIRING_SERVICE_ID;
                    if (!postSmpPairingRemoval) {
                        throw new HostException(String.format(
                                Locale.US,
                                "Unexpected BT_CL SERVICE_REMOVED "
                                        + "id=0x%04X",
                                removedService));
                    }
                    byte[] confirmation =
                            HciCodec.buildRemoveConfirmationPdu(
                                    peerVersion,
                                    removedService,
                                    0);
                    try {
                        sendL2cap(
                                destinationCid,
                                confirmation,
                                "BT_CL REMOVE_CONFIRMATION "
                                        + "terminusPairing status=0");
                    } finally {
                        wipe(confirmation);
                    }
                    log("BT_CL PAIRING SERVICE REMOVAL ACK PASS: "
                            + "serviceID=0x0001 status=0; the single "
                            + "normal advertisement remains recorded.");
                    continue;
                }
                if (pdu.opcode == HciCodec.BT_CL_REMOVE_CONFIRMATION) {
                    log("BT_CL REMOVE_CONFIRMATION RX: " + HciCodec.toHex(l2cap.payload));
                    if (retransmitPdu != null) {
                        sendL2cap(destinationCid, retransmitPdu, "BT_CL POST_REMOVE_RETRANSMIT");
                        nextRetransmitTime = BridgeClock.elapsedRealtime() + 800;
                    }
                    continue;
                }
                if (normalHandoff != null
                        && pdu.opcode
                        == HciCodec.BT_CL_COMMON_SERVICES) {
                    List<Integer> common =
                            HciCodec.parseCommonServices(pdu);
                    if (common.contains(
                            HciCodec.TERMINUS_PAIRING_SERVICE_ID)
                            && !common.contains(
                            HciCodec.TERMINUS_LINK_SERVICE_ID)) {
                        throw new HostException(
                                "WATCH_PAIRING_RESET: Watch "
                                        + "re-published pairing service "
                                        + "0x0001 instead of normal "
                                        + "terminusLink service 0x0002");
                    }
                }
                if (pdu.opcode == HciCodec.BT_CL_SERVICE_ADDED) {
                    try {
                        HciCodec.BtClServiceRecord remote = HciCodec.parseServiceAdded(pdu);
                        log("BT_CL SERVICE_ADDED RX: id=0x" + Integer.toHexString(remote.serviceId));
                        byte[] commonReply = HciCodec.buildCommonServicesPdu(
                                peerVersion,
                                peerFeatures,
                                List.of(remote.serviceId));
                        sendL2cap(destinationCid, commonReply, "BT_CL COMMON_SERVICES");
                        if (opcode == HciCodec.BT_CL_COMMON_SERVICES) {
                            return new HciCodec.BtClPdu(
                                    HciCodec.BT_CL_COMMON_SERVICES,
                                    new byte[]{0x01, (byte) (remote.serviceId & 0xFF), (byte) ((remote.serviceId >> 8) & 0xFF)});
                        }
                    } catch (Exception err) {
                        log("BT_CL SERVICE_ADDED parse error: " + safeMessage(err));
                    }
                }
                if (pdu.opcode == HciCodec.BT_CL_REMOTE_SERVICES) {
                    try {
                        List<HciCodec.BtClServiceRecord> remoteList = HciCodec.parseRemoteServices(pdu);
                        List<Integer> ids = new ArrayList<>();
                        for (HciCodec.BtClServiceRecord r : remoteList) {
                            ids.add(r.serviceId);
                        }
                        log("BT_CL REMOTE_SERVICES RX: ids=" + ids);
                        byte[] commonReply = HciCodec.buildCommonServicesPdu(
                                peerVersion,
                                peerFeatures,
                                ids);
                        sendL2cap(destinationCid, commonReply, "BT_CL COMMON_SERVICES");
                        if (opcode == HciCodec.BT_CL_COMMON_SERVICES) {
                            byte[] commonPayload = HciCodec.parseBtCl(peerVersion, peerFeatures, commonReply).payload;
                            return new HciCodec.BtClPdu(
                                    HciCodec.BT_CL_COMMON_SERVICES,
                                    commonPayload);
                        }
                    } catch (Exception err) {
                        log("BT_CL REMOTE_SERVICES parse error: " + safeMessage(err));
                    }
                }
                if (pdu.opcode == HciCodec.BT_CL_CREATE_CHANNEL) {
                    try {
                        HciCodec.CreateChannel req = HciCodec.parseCreateChannel(pdu);
                        log(String.format(
                                Locale.US,
                                "BT_CL CREATE_CHANNEL RX: reqCID=0x%04X service=0x%04X",
                                req.requesterLocalCid,
                                req.serviceId));
                        byte[] acceptReply = HciCodec.buildAcceptChannelPdu(
                                peerVersion,
                                0, // status = 0 (Success)
                                req.serviceId,
                                HciCodec.TERMINUS_LOCAL_CID);
                        sendL2cap(destinationCid, acceptReply, "BT_CL ACCEPT_CHANNEL (reply to Watch CREATE_CHANNEL)");
                        if (opcode == HciCodec.BT_CL_ACCEPT_CHANNEL) {
                            return new HciCodec.BtClPdu(
                                    HciCodec.BT_CL_ACCEPT_CHANNEL,
                                    new byte[]{0x00, (byte) (req.serviceId & 0xFF), (byte) ((req.serviceId >> 8) & 0xFF), (byte) (req.requesterLocalCid & 0xFF), (byte) ((req.requesterLocalCid >> 8) & 0xFF)});
                        }
                    } catch (Exception err) {
                        log("BT_CL CREATE_CHANNEL parse error: " + safeMessage(err));
                    }
                }
                if (pdu.opcode == opcode) {
                    return pdu;
                }
            } finally {
                wipe(l2cap.payload);
            }
        }
        if (normalHandoff != null) {
            throw new HostException(
                    "NORMAL_HANDOFF_NO_ENDPOINT: Watch did not "
                            + "publish terminusLink service 0x0002 "
                            + "within "
                            + BT_CL_TIMEOUT_MS
                            + " ms after SMP; no explicit SMP failure "
                            + "or HCI disconnect was observed");
        }
        throw new HostException(String.format(
                Locale.US,
                "Timed out waiting for BT_CL opcode 0x%02X",
                opcode));
    }

    private byte[] handleAttRequest(byte[] request) throws Exception {
        if (request == null || request.length == 0) {
            return null;
        }
        int opcode = request[0] & 0xFF;
        if (opcode == 0x02) { // ATT_EXCHANGE_MTU_REQ
            int clientMtu = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 23;
            int serverMtu = Math.max(23, Math.min(clientMtu, 251));
            return new byte[]{0x03, (byte) (serverMtu & 0xFF), (byte) ((serverMtu >> 8) & 0xFF)};
        }
        if (opcode == 0x10 && request.length >= 5) { // ATT_READ_BY_GROUP_TYPE_REQ
            int startHandle = (request[1] & 0xFF) | ((request[2] & 0xFF) << 8);
            int endHandle = (request[3] & 0xFF) | ((request[4] & 0xFF) << 8);
            int uuid = request.length >= 7 ? ((request[5] & 0xFF) | ((request[6] & 0xFF) << 8)) : 0x2800;
            if (uuid == 0x2800) {
                if (startHandle <= 1 && endHandle >= 1) {
                    return new byte[]{
                            0x11, 0x06,
                            0x01, 0x00, 0x05, 0x00, 0x00, 0x18, // GAP handles 1..5
                            0x06, 0x00, 0x09, 0x00, 0x01, 0x18, // GATT handles 6..9
                            0x0A, 0x00, 0x10, 0x00, 0x0A, 0x18, // DIS handles 10..16
                            0x11, 0x00, 0x14, 0x00, 0x25, (byte) 0xFE  // Continuity handles 17..20
                    };
                }
                if (startHandle <= 6 && endHandle >= 6) {
                    return new byte[]{
                            0x11, 0x06,
                            0x06, 0x00, 0x09, 0x00, 0x01, 0x18, // GATT handles 6..9
                            0x0A, 0x00, 0x10, 0x00, 0x0A, 0x18, // DIS handles 10..16
                            0x11, 0x00, 0x14, 0x00, 0x25, (byte) 0xFE  // Continuity handles 17..20
                    };
                }
                if (startHandle <= 10 && endHandle >= 10) {
                    return new byte[]{
                            0x11, 0x06,
                            0x0A, 0x00, 0x10, 0x00, 0x0A, 0x18, // DIS handles 10..16
                            0x11, 0x00, 0x14, 0x00, 0x25, (byte) 0xFE  // Continuity handles 17..20
                    };
                }
                if (startHandle <= 17 && endHandle >= 17) {
                    return new byte[]{
                            0x11, 0x06,
                            0x11, 0x00, 0x14, 0x00, 0x25, (byte) 0xFE  // Continuity handles 17..20
                    };
                }
            }
            return new byte[]{0x01, 0x10, (byte) (startHandle & 0xFF), (byte) ((startHandle >> 8) & 0xFF), 0x0A};
        }
        if (opcode == 0x08 && request.length >= 5) { // ATT_READ_BY_TYPE_REQ
            int startHandle = (request[1] & 0xFF) | ((request[2] & 0xFF) << 8);
            int endHandle = (request[3] & 0xFF) | ((request[4] & 0xFF) << 8);
            int uuid = request.length >= 7 ? ((request[5] & 0xFF) | ((request[6] & 0xFF) << 8)) : 0;
            if (uuid == 0x2803) { // Characteristic declaration
                if (startHandle <= 2 && endHandle >= 5) {
                    return new byte[]{
                            0x09, 0x07,
                            0x02, 0x00, 0x02, 0x03, 0x00, 0x00, 0x2A, // Device Name (handle 2, val 3)
                            0x04, 0x00, 0x02, 0x05, 0x00, 0x01, 0x2A  // Appearance (handle 4, val 5)
                    };
                }
                if (startHandle <= 7 && endHandle >= 8) {
                    return new byte[]{
                            0x09, 0x07,
                            0x07, 0x00, 0x20, 0x08, 0x00, 0x05, 0x2A  // Service Changed (handle 7, val 8)
                    };
                }
                if (startHandle <= 11 && endHandle >= 16) {
                    return new byte[]{
                            0x09, 0x07,
                            0x0B, 0x00, 0x02, 0x0C, 0x00, 0x29, 0x2A, // Manufacturer Name (11, val 12)
                            0x0D, 0x00, 0x02, 0x0E, 0x00, 0x24, 0x2A, // Model Number (13, val 14)
                            0x0F, 0x00, 0x02, 0x10, 0x00, 0x26, 0x2A  // Firmware Revision (15, val 16)
                    };
                }
                if (startHandle <= 18 && endHandle >= 20) {
                    return new byte[]{
                            0x09, 0x07,
                            0x12, 0x00, 0x12, 0x13, 0x00, 0x25, (byte) 0xFE  // Continuity 0xFE25 (18, val 19)
                    };
                }
            } else if (uuid == 0x2A00 && startHandle <= 3 && endHandle >= 3) { // Device Name
                return new byte[]{0x09, 0x08, 0x03, 0x00, 'i', 'P', 'h', 'o', 'n', 'e'};
            } else if (uuid == 0x2A01 && startHandle <= 5 && endHandle >= 5) { // Appearance
                return new byte[]{0x09, 0x04, 0x05, 0x00, 0x40, 0x00};
            } else if (uuid == 0x2A29 && startHandle <= 12 && endHandle >= 12) { // Manufacturer Name
                return new byte[]{0x09, 0x0C, 0x0C, 0x00, 'A', 'p', 'p', 'l', 'e', ' ', 'I', 'n', 'c', '.'};
            } else if (uuid == 0x2A24 && startHandle <= 14 && endHandle >= 14) { // Model Number
                return new byte[]{0x09, 0x0C, 0x0E, 0x00, 'i', 'P', 'h', 'o', 'n', 'e', '1', '6', ',', '2'};
            } else if (uuid == 0x2A26 && startHandle <= 16 && endHandle >= 16) { // Firmware Revision
                return new byte[]{0x09, 0x06, 0x10, 0x00, '2', '1', '.', '6'};
            } else if (uuid == 0xFE25 && startHandle <= 19 && endHandle >= 19) { // Continuity Setup
                return new byte[]{0x09, 0x07, 0x13, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00};
            }
            return new byte[]{0x01, 0x08, (byte) (startHandle & 0xFF), (byte) ((startHandle >> 8) & 0xFF), 0x0A};
        }
        if (opcode == 0x0A && request.length >= 3) { // ATT_READ_REQ
            int handle = (request[1] & 0xFF) | ((request[2] & 0xFF) << 8);
            if (handle == 3) {
                return new byte[]{0x0B, 'i', 'P', 'h', 'o', 'n', 'e'};
            }
            if (handle == 5) {
                return new byte[]{0x0B, 0x40, 0x00};
            }
            if (handle == 12) {
                return new byte[]{0x0B, 'A', 'p', 'p', 'l', 'e', ' ', 'I', 'n', 'c', '.'};
            }
            if (handle == 14) {
                return new byte[]{0x0B, 'i', 'P', 'h', 'o', 'n', 'e', '1', '6', ',', '2'};
            }
            if (handle == 16) {
                return new byte[]{0x0B, '2', '1', '.', '6'};
            }
            if (handle == 19) {
                return new byte[]{0x0B, 0x20, 0x00, 0x00, 0x00, 0x00};
            }
            return new byte[]{0x01, 0x0A, (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF), 0x0A};
        }
        if (opcode == 0x04 && request.length >= 5) { // ATT_FIND_INFO_REQ
            int startHandle = (request[1] & 0xFF) | ((request[2] & 0xFF) << 8);
            int endHandle = (request[3] & 0xFF) | ((request[4] & 0xFF) << 8);
            if (startHandle <= 9 && endHandle >= 9) {
                return new byte[]{0x05, 0x01, 0x09, 0x00, 0x02, 0x29}; // Handle 9: CCCD 0x2902
            }
            if (startHandle <= 20 && endHandle >= 20) {
                return new byte[]{0x05, 0x01, 0x14, 0x00, 0x02, 0x29}; // Handle 20: CCCD 0x2902
            }
            return new byte[]{0x01, 0x04, (byte) (startHandle & 0xFF), (byte) ((startHandle >> 8) & 0xFF), 0x0A};
        }
        if (opcode == 0x11 && request.length >= 2) { // ATT_READ_BY_GROUP_TYPE_RSP
            int entryLen = request[1] & 0xFF;
            int maxEndHandle = 0;
            StringBuilder sb = new StringBuilder("WATCH GATT SERVICES: ");
            for (int offset = 2; offset + entryLen <= request.length; offset += entryLen) {
                int startH = (request[offset] & 0xFF) | ((request[offset + 1] & 0xFF) << 8);
                int endH = (request[offset + 2] & 0xFF) | ((request[offset + 3] & 0xFF) << 8);
                maxEndHandle = Math.max(maxEndHandle, endH);
                byte[] uuidBytes = Arrays.copyOfRange(request, offset + 4, offset + entryLen);
                sb.append(String.format(Locale.US, "[handles %d..%d UUID=%s] ", startH, endH, HciCodec.toHex(uuidBytes)));
            }
            log(sb.toString());
            if (maxEndHandle > 0 && maxEndHandle < 0xFFFF) {
                byte[] nextReq = new byte[]{
                        0x10,
                        (byte) ((maxEndHandle + 1) & 0xFF), (byte) (((maxEndHandle + 1) >> 8) & 0xFF),
                        (byte) 0xFF, (byte) 0xFF,
                        0x00, 0x28
                };
                sendL2cap(0x0004, nextReq, "ATT DISCOVER NEXT SERVICES (from " + (maxEndHandle + 1) + ")");
            } else {
                // Start characteristic discovery on all handles
                byte[] charReq = new byte[]{
                        0x08,
                        0x01, 0x00,
                        (byte) 0xFF, (byte) 0xFF,
                        0x03, 0x28
                };
                sendL2cap(0x0004, charReq, "ATT DISCOVER CHARACTERISTICS (1..FFFF)");
            }
            return null;
        }
        if (opcode == 0x09 && request.length >= 2) { // ATT_READ_BY_TYPE_RSP
            int entryLen = request[1] & 0xFF;
            int maxHandle = 0;
            StringBuilder sb = new StringBuilder("WATCH GATT CHARACTERISTICS: ");
            for (int offset = 2; offset + entryLen <= request.length; offset += entryLen) {
                int declHandle = (request[offset] & 0xFF) | ((request[offset + 1] & 0xFF) << 8);
                int props = entryLen >= 3 ? (request[offset + 2] & 0xFF) : 0;
                int valHandle = entryLen >= 5 ? ((request[offset + 3] & 0xFF) | ((request[offset + 4] & 0xFF) << 8)) : 0;
                maxHandle = Math.max(maxHandle, Math.max(declHandle, valHandle));
                byte[] uuidBytes = entryLen > 5 ? Arrays.copyOfRange(request, offset + 5, offset + entryLen) : new byte[0];
                sb.append(String.format(Locale.US, "[decl=%d val=%d props=0x%02X UUID=%s] ", declHandle, valHandle, props, HciCodec.toHex(uuidBytes)));
            }
            log(sb.toString());
            if (maxHandle > 0 && maxHandle < 0xFFFF) {
                byte[] nextCharReq = new byte[]{
                        0x08,
                        (byte) ((maxHandle + 1) & 0xFF), (byte) (((maxHandle + 1) >> 8) & 0xFF),
                        (byte) 0xFF, (byte) 0xFF,
                        0x03, 0x28
                };
                sendL2cap(0x0004, nextCharReq, "ATT DISCOVER NEXT CHARACTERISTICS (from " + (maxHandle + 1) + ")");
            }
            return null;
        }
        if (opcode == 0x01) { // ATT_ERROR_RSP from Watch
            int reqOpcode = request.length >= 2 ? (request[1] & 0xFF) : 0;
            int handle = request.length >= 4 ? ((request[2] & 0xFF) | ((request[3] & 0xFF) << 8)) : 0;
            int err = request.length >= 5 ? (request[4] & 0xFF) : 0;
            log(String.format(Locale.US, "ATT ERROR RSP from Watch: inOpcode=0x%02X handle=%d err=0x%02X", reqOpcode, handle, err));
            if (reqOpcode == 0x10 && handle > 1) {
                byte[] charReq = new byte[]{
                        0x08,
                        0x01, 0x00,
                        (byte) 0xFF, (byte) 0xFF,
                        0x03, 0x28
                };
                sendL2cap(0x0004, charReq, "ATT DISCOVER CHARACTERISTICS (1..FFFF)");
            } else if (reqOpcode == 0x08) {
                // Characteristic discovery finished; discover all descriptors on handles 1..56
                byte[] descReq = new byte[]{
                        0x04, // ATT_FIND_INFO_REQ
                        0x01, 0x00, // handle 1
                        0x38, 0x00  // handle 56
                };
                sendL2cap(0x0004, descReq, "ATT DISCOVER DESCRIPTORS (1..56)");
            } else if (reqOpcode == 0x0A) {
                int[] handlesToRead = new int[]{3, 12, 14, 16, 17, 18, 21, 22, 23, 24, 51, 55};
                int readIdx = attSetupStep - 3;
                if (readIdx >= 0 && readIdx < handlesToRead.length - 1) {
                    attSetupStep++;
                    int nextHandle = handlesToRead[readIdx + 1];
                    byte[] readReq = new byte[]{0x0A, (byte) (nextHandle & 0xFF), (byte) ((nextHandle >> 8) & 0xFF)};
                    sendL2cap(0x0004, readReq, "ATT READ HANDLE " + nextHandle);
                }
            }
            return null;
        }
        if (opcode == 0x05 && request.length >= 2) { // ATT_FIND_INFO_RSP from Watch
            int fmt = request[1] & 0xFF;
            int maxH = 0;
            StringBuilder sb = new StringBuilder("WATCH DESCRIPTORS: ");
            int step = (fmt == 1) ? 4 : 18;
            for (int offset = 2; offset + step <= request.length; offset += step) {
                int h = (request[offset] & 0xFF) | ((request[offset + 1] & 0xFF) << 8);
                maxH = Math.max(maxH, h);
                byte[] uuidBytes = Arrays.copyOfRange(request, offset + 2, offset + step);
                sb.append(String.format(Locale.US, "[handle=%d UUID=%s] ", h, HciCodec.toHex(uuidBytes)));
                // If CCCD (0x2902)
                if (fmt == 1 && uuidBytes.length == 2 && uuidBytes[0] == 0x02 && uuidBytes[1] == 0x29) {
                    log("FOUND CCCD AT HANDLE " + h + " — SUBSCRIBING NOTIFY+INDICATE");
                    byte[] enableNtf = new byte[]{
                            0x12, // ATT_WRITE_REQ
                            (byte) (h & 0xFF), (byte) ((h >> 8) & 0xFF),
                            0x03, 0x00
                    };
                    sendL2cap(0x0004, enableNtf, "ATT SUBSCRIBE CCCD " + h);
                }
            }
            log(sb.toString());
            if (maxH > 0 && maxH < 56) {
                byte[] nextDescReq = new byte[]{
                        0x04,
                        (byte) ((maxH + 1) & 0xFF), (byte) (((maxH + 1) >> 8) & 0xFF),
                        0x38, 0x00
                };
                sendL2cap(0x0004, nextDescReq, "ATT DISCOVER NEXT DESCRIPTORS (from " + (maxH + 1) + ")");
            }
            return null;
        }
        if (opcode == 0x0B && request.length > 1) { // ATT_READ_RSP from Watch
            byte[] val = Arrays.copyOfRange(request, 1, request.length);
            log("WATCH CHAR VALUE READ: step=" + attSetupStep + " bytes=" + val.length);
            int[] handlesToRead = new int[]{3, 12, 14, 16, 17, 18, 21, 22, 23, 24, 51, 55};
            int readIdx = attSetupStep - 3;
            if (readIdx >= 0 && readIdx < handlesToRead.length - 1) {
                attSetupStep++;
                int nextHandle = handlesToRead[readIdx + 1];
                byte[] readReq = new byte[]{0x0A, (byte) (nextHandle & 0xFF), (byte) ((nextHandle >> 8) & 0xFF)};
                sendL2cap(0x0004, readReq, "ATT READ HANDLE " + nextHandle);
            }
            return null;
        }
        if (opcode == 0x13) { // ATT_WRITE_RSP from Watch
            log("WATCH CONFIRMED ATT WRITE: step=" + attSetupStep);
            if (activeLocalOob == null) {
                activeLocalOob = BleSecureConnectionsCrypto.generateLocalOob(new java.security.SecureRandom());
            }
            if (attSetupStep == 0) {
                attSetupStep = 1;
                byte[] token = extractBeaconToken(lastMatchedReportData);
                if (token == null) {
                    token = new byte[]{0x00, 0x1D, 0x6B, (byte) 0xA6, 0x44, 0x68};
                }
                byte[] pubKey = activeLocalOob.publicKeyForSmp();
                byte[] oob = activeLocalOob.appleOobData();

                // Step 1a: Unified Apple Setup Packet on Handle 22 (Remote Watch Characteristic)
                byte[] packet1 = new byte[3 + 1 + 1 + 2 + token.length + pubKey.length + oob.length];
                packet1[0] = 0x12; // ATT_WRITE_REQ
                packet1[1] = 0x16; // handle 22
                packet1[2] = 0x00;
                packet1[3] = 0x01; // Opcode 1 (Session Start)
                packet1[4] = 0x19; // Version 25
                packet1[5] = 0x00; packet1[6] = 0x00; // Flags
                int offset = 7;
                System.arraycopy(token, 0, packet1, offset, token.length);
                offset += token.length;
                System.arraycopy(pubKey, 0, packet1, offset, pubKey.length);
                offset += pubKey.length;
                System.arraycopy(oob, 0, packet1, offset, oob.length);
                log("ATT WRITE UNIFIED SETUP PACKET TO HANDLE 22: length=" + packet1.length);
                sendL2cap(0x0004, packet1, "ATT WRITE UNIFIED SETUP PACKET TO HANDLE 22 (step 1)");

                // Step 1b: Write to Handle 17 (Remote Watch Characteristic 1)
                byte[] packet1_17 = packet1.clone();
                packet1_17[0] = 0x52; // ATT_WRITE_CMD
                packet1_17[1] = 0x11; // handle 17
                packet1_17[2] = 0x00;
                sendL2cap(0x0004, packet1_17, "ATT WRITE CMD TO HANDLE 17 (step 1)");

                // Step 1c: Notify on Handle 19 (Local Phone Continuity Setup Characteristic)
                byte[] ntf1 = new byte[3 + 1 + 1 + 2 + token.length + pubKey.length + oob.length];
                ntf1[0] = 0x1B; // ATT_HANDLE_VALUE_NTF
                ntf1[1] = 0x13; // handle 19
                ntf1[2] = 0x00;
                System.arraycopy(packet1, 3, ntf1, 3, packet1.length - 3);
                sendL2cap(0x0004, ntf1, "ATT NOTIFY ON LOCAL HANDLE 19 (step 1)");
            } else if (attSetupStep == 1) {
                attSetupStep = 2;
                // Step 2a: Write Pair Trigger (0x04) to Remote Handle 22
                byte[] pairAction = new byte[]{
                        0x12, // ATT_WRITE_REQ
                        0x16, 0x00, // handle 22
                        0x04, 0x01, 0x01 // Opcode 4 (Pair Action Complete)
                };
                log("ATT WRITE PAIR TRIGGER TO HANDLE 22 (step 2)");
                sendL2cap(0x0004, pairAction, "ATT WRITE PAIR TRIGGER TO HANDLE 22 (step 2)");

                // Step 2b: Write Pair Trigger to Remote Handle 17
                byte[] pairAction17 = new byte[]{
                        0x52, // ATT_WRITE_CMD
                        0x11, 0x00, // handle 17
                        0x04, 0x01, 0x01
                };
                sendL2cap(0x0004, pairAction17, "ATT WRITE CMD PAIR TRIGGER TO HANDLE 17 (step 2)");

                // Step 2c: Notify Pair Trigger on Local Handle 19
                byte[] pairNtf = new byte[]{
                        0x1B, // ATT_HANDLE_VALUE_NTF
                        0x13, 0x00, // handle 19
                        0x04, 0x01, 0x01
                };
                sendL2cap(0x0004, pairNtf, "ATT NOTIFY PAIR TRIGGER ON LOCAL HANDLE 19 (step 2)");
            } else if (attSetupStep == 2) {
                attSetupStep = 3;
                log("STEP 2 COMPLETED: Sending immediate BT_CL CREATE_CHANNEL and SMP requests...");
                byte[] createPairingPdu = HciCodec.buildCreateChannelPdu(
                        25,
                        0L,
                        HciCodec.TERMINUS_PAIRING_LOCAL_CID,
                        HciCodec.TERMINUS_PAIRING_SERVICE_ID);
                sendL2cap(HciCodec.BT_CL_SIGNALING_CID, createPairingPdu, "BT_CL CREATE_CHANNEL pairing (immediate)");

                byte[] createLinkPdu = HciCodec.buildCreateChannelPdu(
                        25,
                        0L,
                        HciCodec.TERMINUS_LINK_LOCAL_CID,
                        HciCodec.TERMINUS_LINK_SERVICE_ID);
                sendL2cap(HciCodec.BT_CL_SIGNALING_CID, createLinkPdu, "BT_CL CREATE_CHANNEL link (immediate)");

                // Also send SMP pairing request on CID 0x0006
                byte[] smpReq = new byte[]{0x01, 0x01, 0x01, 0x0D, 0x10, 0x07, 0x07};
                sendL2cap(BluetoothSmpCodec.FIXED_CID, smpReq, "SMP PAIRING REQ (immediate)");

                // Step 3: Read Handle 3 value (Device Name)
                byte[] read3 = new byte[]{0x0A, 0x03, 0x00};
                log("ATT READ HANDLE 3 (step 3)");
                sendL2cap(0x0004, read3, "ATT READ HANDLE 3 (step 3)");
            }
            return null;
        }
        if (opcode == 0x1B) { // ATT_HANDLE_VALUE_NTF from Watch
            int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 0;
            byte[] val = request.length > 3 ? Arrays.copyOfRange(request, 3, request.length) : new byte[0];
            log("ATT NOTIFICATION from Watch: handle=" + handle + " bytes=" + val.length);
            return null;
        }
        if (opcode == 0x1D) { // ATT_HANDLE_VALUE_IND from Watch
            int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 0;
            byte[] val = request.length > 3 ? Arrays.copyOfRange(request, 3, request.length) : new byte[0];
            log("ATT INDICATION from Watch: handle=" + handle + " bytes=" + val.length);
            return new byte[]{0x1E}; // ATT_HANDLE_VALUE_CFM
        }
        if (opcode == 0x12) { // ATT_WRITE_REQ
            int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 0;
            log("ATT WRITE REQ from Watch: handle=" + handle + " bytes=" + request.length);
            return new byte[]{0x13};
        }
        if (opcode == 0x52) { // ATT_WRITE_CMD
            int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 0;
            log("ATT WRITE CMD from Watch: handle=" + handle + " bytes=" + request.length);
            return null;
        }
        int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 1;
        // ATT_ERROR_RSP: opcode=0x01, inOpcode=opcode, handle=handle, error=0x0A (Attribute Not Found)
        return new byte[]{0x01, (byte) opcode, (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF), 0x0A};
    }

    private static byte[] extractBeaconToken(byte[] data) {
        if (data == null) return null;
        for (int i = 0; i + 9 <= data.length; i++) {
            int len = data[i] & 0xFF;
            if (len >= 9 && i + 1 + len <= data.length) {
                int type = data[i + 1] & 0xFF;
                if (type == 0xFF) { // Manufacturer Specific
                    int compId = (data[i + 2] & 0xFF) | ((data[i + 3] & 0xFF) << 8);
                    if (compId == 0x004C) { // Apple
                        int subType = data[i + 4] & 0xFF;
                        int subLen = data[i + 5] & 0xFF;
                        if (subType == 0x10 && subLen == 6 && i + 6 + 6 <= data.length) {
                            return Arrays.copyOfRange(data, i + 6, i + 12);
                        }
                    }
                }
            }
        }
        return null;
    }

    private void failOnPostSmpPdu(
            byte[] payload,
            String phase)
            throws HostException {
        final int smpOpcode;
        try {
            smpOpcode = BluetoothSmpCodec.opcode(payload);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "POST_SMP_MALFORMED: malformed SMP PDU during "
                            + phase
                            + "; payload bytes logged=false",
                    error);
        }
        if (smpOpcode == BluetoothSmpCodec.PAIRING_FAILED) {
            final int reason;
            try {
                reason = BluetoothSmpCodec.parsePairingFailure(
                        payload);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "POST_SMP_MALFORMED: malformed Pairing Failed "
                                + "during "
                                + phase
                                + "; payload bytes logged=false",
                        error);
            }
            throw new HostException(String.format(
                    Locale.US,
                    "WATCH_SMP_PAIRING_FAILED: reason=0x%02X (%s) "
                            + "during %s; payload bytes logged=false",
                    reason,
                    BluetoothSmpCodec.pairingFailureReasonName(
                            reason),
                    phase));
        }
        throw new HostException(String.format(
                Locale.US,
                "POST_SMP_UNEXPECTED_PDU: opcode=0x%02X bytes=%d "
                        + "during %s; payload bytes logged=false",
                smpOpcode,
                payload == null ? 0 : payload.length,
                phase));
    }

    private synchronized void sendL2cap(int cid, byte[] payload, String label)
            throws IOException, StopRequested {
        checkStop();
        int handle = connectionHandle;
        List<byte[]> fragments = HciCodec.buildLeAclL2capFragments(
                handle,
                cid,
                payload,
                maximumAclDataLength);
        try {
            for (int index = 0; index < fragments.size(); index++) {
                long deadline = BridgeClock.elapsedRealtime() + 10_000;
                boolean waited = false;
                while (true) {
                    checkStop();
                    Throwable failure = callbackFailure.get();
                    if (failure != null) throw new IllegalStateException("HCI callback failed", failure);
                    if (aclCredits.tryReserve(handle)) break;
                    if (BridgeClock.elapsedRealtime() >= deadline) {
                        throw new IllegalStateException("Controller ACL credit timeout: " + aclCredits.summary());
                    }
                    if (!waited) log("HCI ACL CREDIT WAIT: " + aclCredits.summary());
                    waited = true;
                    try { aclCredits.awaitCredit(250); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("ACL credit wait interrupted", interrupted);
                    }
                }
                if (waited) log("HCI ACL CREDIT RESUMED: " + aclCredits.summary());
                byte[] acl = fragments.get(index);
                if (suppressPacketBytes(label)) {
                    log(String.format(
                            Locale.US,
                            "%s TX: cid=0x%04X fragment=%d/%d "
                                    + "aclBytes=%d; bytes logged=false",
                            label,
                            cid,
                            index + 1,
                            fragments.size(),
                            acl.length));
                } else {
                    log(String.format(
                            Locale.US,
                            "%s TX: cid=0x%04X fragment=%d/%d acl=%s",
                            label,
                            cid,
                            index + 1,
                            fragments.size(),
                            HciCodec.toHex(acl)));
                }
                // Reserve before Binder: a completion callback can arrive before this returns.
                // A failed Binder call has ambiguous delivery; fail the session, never refund
                // a possibly queued packet and permit an overflow.
                try { hci.sendAclData(acl); }
                catch (IOException failure) {
                    callbackFailure.compareAndSet(null, failure);
                    throw failure;
                }
            }

        } finally { fragments.forEach(HalTransportSession::wipe); }
        long now = BridgeClock.elapsedRealtime();
        if (now - lastAclCreditStatusMs >= 10_000) {
            lastAclCreditStatusMs = now;
            log("HCI ACL CREDIT STATUS: " + aclCredits.summary());
        }
    }

    private static boolean suppressPacketBytes(
            String label) {
        return label != null
                && (label.contains("PRIVATE-NOTIFIES")
                || label.contains("SMP")
                || label.contains("NORMAL"));
    }

    private static boolean hasInitiatorSpi(
            byte[] ikePacket,
            byte[] expectedInitiatorSpi) {
        if (ikePacket == null
                || expectedInitiatorSpi == null
                || expectedInitiatorSpi.length != 8
                || ikePacket.length < 8) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < 8; index++) {
            difference |= ikePacket[index]
                    ^ expectedInitiatorSpi[index];
        }
        return difference == 0;
    }

    private HciCodec.CommandComplete sendCommandComplete(
            int opcode,
            byte[] parameters)
            throws Exception {
        checkStop();
        hci.sendHciCommand(HciCodec.buildCommand(opcode, parameters));
        long deadline = BridgeClock.elapsedRealtime() + COMMAND_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet = nextPacket(deadline);
            if (packet.kind != PacketKind.EVENT) {
                continue;
            }
            HciCodec.CommandComplete complete =
                    HciCodec.parseCommandComplete(packet.bytes);
            if (complete != null && complete.opcode == opcode) {
                if (complete.status != 0) {
                    throw new HostException(String.format(
                            Locale.US,
                            "HCI command 0x%04X status=0x%02X",
                            opcode,
                            complete.status));
                }
                log(String.format(
                        Locale.US,
                        "HCI COMMAND COMPLETE: opcode=0x%04X.",
                        opcode));
                return complete;
            }
            HciCodec.CommandStatus status =
                    HciCodec.parseCommandStatus(packet.bytes);
            if (status != null
                    && status.opcode == opcode
                    && status.status != 0) {
                throw new HostException(String.format(
                        Locale.US,
                        "HCI command 0x%04X early status=0x%02X",
                        opcode,
                        status.status));
            }
        }
        throw new HostException(String.format(
                Locale.US,
                "HCI command 0x%04X timed out",
                opcode));
    }

    private void sendCommandStatus(int opcode, byte[] parameters)
            throws Exception {
        checkStop();
        hci.sendHciCommand(HciCodec.buildCommand(opcode, parameters));
        long deadline = BridgeClock.elapsedRealtime() + COMMAND_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet = nextPacket(deadline);
            if (packet.kind != PacketKind.EVENT) {
                continue;
            }
            HciCodec.CommandStatus status =
                    HciCodec.parseCommandStatus(packet.bytes);
            if (status != null && status.opcode == opcode) {
                if (status.status != 0) {
                    throw new HostException(String.format(
                            Locale.US,
                            "HCI async command 0x%04X status=0x%02X",
                            opcode,
                            status.status));
                }
                log(String.format(
                        Locale.US,
                        "HCI COMMAND STATUS: opcode=0x%04X accepted.",
                        opcode));
                return;
            }
        }
        throw new HostException(String.format(
                Locale.US,
                "HCI async command 0x%04X status timed out",
                opcode));
    }

    private Packet nextPacket(long deadline) throws Exception {
        while (true) {
            checkStop();
            Throwable callbackError = callbackFailure.get();
            if (callbackError != null) {
                throw new HostException(
                        "Bluetooth HAL callback queue failed",
                        callbackError);
            }
            long remaining = deadline - BridgeClock.elapsedRealtime();
            if (remaining <= 0) {
                throw new TimeoutException("packet deadline elapsed");
            }
            Packet packet = deferredControllerPackets.pollFirst();
            if (packet == null) {
                packet = packets.poll(Math.min(remaining, 250), TimeUnit.MILLISECONDS);
            }
            if (packet != null) {
                logControllerDiagnostics(packet);
                if (packet.kind == PacketKind.ACL && fixedChannelRouter.accept(
                        packet.bytes, connectionHandle, this::serviceNormalFixedChannel)) {
                    wipe(packet.bytes);
                    continue;
                }
                if (packet.kind == PacketKind.EVENT) {
                    byte[] parameterReply = BluetoothLinkMaintenance.remoteParameterRequestRejection(
                            packet.bytes, connectionHandle);
                    if (parameterReply != null) {
                        try {
                            hci.sendHciCommand(parameterReply);
                            log("LE PARAMETERS: peer update declined explicitly; current link parameters retained.");
                        } finally { wipe(parameterReply); }
                        continue;
                    }
                    HciCodec.DisconnectionComplete disconnected = HciCodec.parseDisconnectionComplete(packet.bytes);
                    if (disconnected != null && disconnected.connectionHandle == connectionHandle) {
                        fixedChannelRouter.reset();
                        normalAttServer.reset();
                    }
                }
                return packet;
            }
        }
    }

    private void disconnectBeforeFreshPairing() throws Exception {
        if (connectionHandle < 0) return;
        int oldHandle = connectionHandle;
        sendCommandStatus(HciCodec.OPCODE_DISCONNECT, HciCodec.buildDisconnectParameters(oldHandle));
        long deadline = BridgeClock.elapsedRealtime() + 3_000;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet = nextPacket(deadline);
            if (packet.kind != PacketKind.EVENT) continue;
            HciCodec.DisconnectionComplete disconnected = HciCodec.parseDisconnectionComplete(packet.bytes);
            if (disconnected != null && disconnected.connectionHandle == oldHandle) {
                if (disconnected.status != 0) throw new HostException("Controller did not close the previous Watch connection");
                connectionHandle = -1;
                aclReassembler.reset();
                fixedChannelRouter.reset();
                normalAttServer.reset();
                log("PAIRED TO FRESH: previous HCI connection is closed; new connection may start.");
                return;
            }
        }
        throw new HostException("Previous Watch connection did not close before fresh pairing");
    }

    private void logControllerDiagnostics(Packet packet) {
        if (packet.kind != PacketKind.EVENT) {
            return;
        }
        Integer hardwareError =
                HciCodec.parseHardwareError(packet.bytes);
        if (hardwareError != null) {
            log(String.format(
                    Locale.US,
                    "HCI HARDWARE ERROR: code=0x%02X raw=%s.",
                    hardwareError,
                    HciCodec.toHex(packet.bytes)));
        }
        Integer overflow =
                HciCodec.parseDataBufferOverflow(packet.bytes);
        if (overflow != null) {
            log(String.format(
                    Locale.US,
                    "HCI DATA BUFFER OVERFLOW: linkType=0x%02X raw=%s.",
                    overflow,
                    HciCodec.toHex(packet.bytes)));
        }
    }

    private static String formatErtmFcs(
            L2capErtmCodec.Frame frame) {
        return frame.fcsPresent
                ? String.format(Locale.US, "0x%04X", frame.fcs)
                : "none";
    }

    private void enqueue(Packet packet) {
        if (!packets.offer(packet)) {
            wipe(packet.bytes);
            callbackFailure.compareAndSet(
                    null,
                    new IllegalStateException("HCI callback queue overflow"));
        }
    }

    private void receive(PacketKind kind, byte[] bytes) {
        if (bytes == null) {
            callbackFailure.compareAndSet(
                    null,
                    new IllegalArgumentException(
                            "Bluetooth HAL returned a null " + kind + " packet"));
            return;
        }
        if (kind == PacketKind.EVENT) {
            // Credits must be released on the callback thread. The sender can be
            // waiting mid-fragment train, where draining nextPacket would both
            // deadlock and allow fixed-channel replies to interleave fragments.
            try {
                HciCodec.LeConnectionComplete connected = HciCodec.parseLeConnectionComplete(bytes);
                if (connected != null && connected.status == 0) {
                    try { aclCredits.connected(connected.connectionHandle); }
                    finally { wipe(connected.peerAddress); }
                }
                for (HciCodec.CompletedPackets completed : HciCodec.parseNumberOfCompletedPackets(bytes)) {
                    aclCredits.complete(completed.connectionHandle, completed.completedPackets);
                }
                HciCodec.DisconnectionComplete disconnected = HciCodec.parseDisconnectionComplete(bytes);
                if (disconnected != null && disconnected.status == 0) {
                    aclCredits.disconnected(disconnected.connectionHandle);
                    if (disconnected.connectionHandle == connectionHandle) {
                        OPERATIONAL_REQUESTS.disconnect();
                        System.out.println("BRIDGE_TRANSPORT_V1:DOWN");
                        System.out.flush();
                    }
                }
            } catch (RuntimeException invalid) {
                callbackFailure.compareAndSet(null, invalid);
            }
        }
        enqueue(new Packet(kind, bytes.clone()));
    }

    private void checkStop() throws StopRequested {
        if (stopRequested.get()) {
            throw new StopRequested();
        }
    }

    synchronized void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        initialWifiSync.close();
        if (opticalPairing != null) { opticalPairing.close(); opticalPairing = null; }
        OPERATIONAL_REQUESTS.disconnect();
        findMyPhone.reset(null);
        pendingPhoneReplies.clear();
        FIND_MY_PHONE_RESULTS.clear();
        log("HCI ACL CREDIT FINAL: " + aclCredits.summary());
        OutboundAppMessage queuedApplication;
        while ((queuedApplication = outboundAppMessages.poll()) != null) wipe(queuedApplication.payload);
        byte[] pendingPreference;
        while ((pendingPreference = pendingTwoWayPreferences.pollFirst()) != null) {
            wipe(pendingPreference);
        }
        log("NANOREGISTRY PROBE FINAL: " + nanoRegistryProbe.status()
                + "; initial Watch properties observed=" + initialPropertiesReceived);
        if (idsDeviceInfoExchange != null) {
            log("IDS DEVICE INFO FINAL: " + idsDeviceInfoExchange.summary());
            idsDeviceInfoExchange.close();
        }
        wipe(localIdsPublicBundle);
        localIdsPublicBundle = null;
        deferredControllerPackets.forEach(p -> wipe(p.bytes));
        deferredControllerPackets.clear();
        char[] queuedPin;
        while ((queuedPin = pinInputs.poll()) != null) {
            Arrays.fill(queuedPin, '\0');
        }
        bondStoreResults.clear();
        pairingSessionStoreResults.clear();
        if (activeInitialSetup != null) activeInitialSetup.close();
        activeInitialSetup = null;
        earlyInitialEvents.forEach(IdsModernSessionCoordinator.SessionEvent::close);
        earlyInitialEvents.clear();
        pendingPostCommitSends.close();
        if (activePostCommitAdapter != null) {
            try {
                activePostCommitAdapter.close();
            } catch (RuntimeException error) {
                log("CLEANUP POST-COMMIT WARNING: "
                        + safeMessage(
                                error));
            }
            activePostCommitAdapter = null;
        }
        if (pendingActivationPayload != null) {
            wipe(pendingActivationPayload);
            pendingActivationPayload = null;
        }
        if (pendingActivationResponse != null) {
            pendingActivationResponse.destroy();
            pendingActivationResponse = null;
        }
        if (activationProxyWorker != null) activationProxyWorker.close();
        clearClockFaceDeltaOnDisconnect();
        activationProxyWorker = null;
        activeActivationProxy = null;
        if (activeIdsBootstrap != null) {
            activeIdsBootstrap.close();
            activeIdsBootstrap = null;
        }
        if (activeIdsBridge != null) {
            try {
                long lastSeq = activeIdsBridge.ids().sequenceAllocator().peek();
                NwServiceConnectorSequenceStore.save(NwServiceConnectorSequenceStore.reserveAfter(lastSeq));
            } catch (RuntimeException ignored) {
            }
            try {
                activeIdsBridge.close();
            } catch (RuntimeException error) {
                log("CLEANUP IDS WARNING: "
                        + safeMessage(
                                error));
            }
            activeIdsBridge = null;
        }
        if (activePairingSessionRecord != null) {
            activePairingSessionRecord.destroy();
            activePairingSessionRecord = null;
        }
        activeWatchSetupIdentifier = null;
        activeWatchSetupMetadata = null;
        modernIdsReady = false;
        initialPropertiesReceived = false;
        readyToCommitIsPaired = false;
        watchProxyActivationObserved = false;
        if (pendingWatchProxyActivation != null) {
            pendingWatchProxyActivation.close();
            pendingWatchProxyActivation = null;
        }
        modernRegistrationDeadlineMs = -1;
        log("CLEANUP START: scan="
                + scanEnabled
                + " createPending="
                + createPending
                + " handle="
                + (connectionHandle < 0
                ? "none"
                : String.format(Locale.US, "0x%04X", connectionHandle)));
        if (!initializedByUs) {
            log("CLEANUP: HAL was not initialized by this helper; close skipped.");
            return;
        }
        try {
            if (scanEnabled) {
                hci.sendHciCommand(HciCodec.buildCommand(
                        HciCodec.OPCODE_LE_SET_SCAN_ENABLE,
                        HciCodec.scanEnableParameters(false)));
                scanEnabled = false;
                log("CLEANUP: LE Scan Disable sent.");
            }
            if (createPending && connectionHandle < 0) {
                hci.sendHciCommand(HciCodec.buildCommand(
                        HciCodec.OPCODE_LE_CREATE_CONNECTION_CANCEL,
                        new byte[0]));
                createPending = false;
                log("CLEANUP: LE Create Connection Cancel sent.");
            }
            if (connectionHandle >= 0) {
                int handle = connectionHandle;
                hci.sendHciCommand(HciCodec.buildCommand(
                        HciCodec.OPCODE_DISCONNECT,
                        HciCodec.buildDisconnectParameters(handle)));
                log(String.format(
                        Locale.US,
                        "CLEANUP: HCI Disconnect sent for handle=0x%04X.",
                        handle));
                awaitDisconnectionForCleanup(handle);
                connectionHandle = -1;
            }
        } catch (Throwable error) {
            log("CLEANUP WARNING: " + safeMessage(error));
        }
        try {
            hci.close();
            log("CLEANUP: Bluetooth HCI HAL closed.");
        } catch (Throwable error) {
            log("CLEANUP CLOSE WARNING: " + safeMessage(error));
        } finally {
            initializedByUs = false;
            packets.clear();
            aclReassembler.reset();
            byte[] deferredSmp;
            while ((deferredSmp =
                    deferredSmpPdus.poll()) != null) {
                Arrays.fill(
                        deferredSmp,
                        (byte) 0);
            }
            if (localIdentity != null) {
                localIdentity.destroy();
                localIdentity = null;
            }
            if (peerConnectionAddress != null) {
                Arrays.fill(
                        peerConnectionAddress,
                        (byte) 0);
                peerConnectionAddress = null;
            }
            peerConnectionAddressType = -1;
        }
    }

    private void awaitDisconnectionForCleanup(int handle)
            throws InterruptedException {
        long deadline = BridgeClock.elapsedRealtime() + DISCONNECT_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime() < deadline) {
            Packet packet = packets.poll(200, TimeUnit.MILLISECONDS);
            if (packet == null || packet.kind != PacketKind.EVENT) {
                continue;
            }
            HciCodec.DisconnectionComplete complete =
                    HciCodec.parseDisconnectionComplete(packet.bytes);
            if (complete != null && complete.connectionHandle == handle) {
                log(String.format(
                        Locale.US,
                        "CLEANUP: Disconnection Complete status=0x%02X reason=0x%02X.",
                        complete.status,
                        complete.reason));
                return;
            }
        }
        log("CLEANUP WARNING: Disconnection Complete timed out.");
    }
    private enum PacketKind {
        EVENT,
        ACL
    }

    private static final class Packet {
        final PacketKind kind;
        final byte[] bytes;

        private Packet(PacketKind kind, byte[] bytes) {
            this.kind = kind;
            this.bytes = bytes;
        }

    }

    private interface PairingIkeProcessor<T> {
        T accept(byte[] packet);
    }

    private static final class PairingTransportCursor {
        final int nextOutboundTxSequence;
        final int nextExpectedWatchTxSequence;

        PairingTransportCursor(
                int nextOutboundTxSequence,
                int nextExpectedWatchTxSequence) {
            this.nextOutboundTxSequence =
                    nextOutboundTxSequence;
            this.nextExpectedWatchTxSequence =
                    nextExpectedWatchTxSequence;
        }
    }

    private static final class PairingTransportResult<T> {
        final T value;
        final PairingTransportCursor cursor;

        PairingTransportResult(
                T value,
                PairingTransportCursor cursor) {
            this.value = value;
            this.cursor = cursor;
        }
    }

    private static final class IntermediateTransportState {
        final IkeV2SessionCrypto.AdditionalKeResult
                additionalKeResult;
        final byte[] initiatorIntAuth;
        final byte[] responderIntAuth;
        final int nextOutboundTxSequence;
        final int nextExpectedWatchTxSequence;

        IntermediateTransportState(
                IkeV2SessionCrypto.AdditionalKeResult
                        additionalKeResult,
                byte[] initiatorIntAuth,
                byte[] responderIntAuth,
                int nextOutboundTxSequence,
                int nextExpectedWatchTxSequence) {
            this.additionalKeResult = additionalKeResult;
            this.initiatorIntAuth =
                    initiatorIntAuth.clone();
            this.responderIntAuth =
                    responderIntAuth.clone();
            this.nextOutboundTxSequence =
                    nextOutboundTxSequence;
            this.nextExpectedWatchTxSequence =
                    nextExpectedWatchTxSequence;
        }
    }

    private static final class AuthenticatedTransportState {
        final IntermediateTransportState intermediate;
        final int nextOutboundTxSequence;
        final int nextExpectedWatchTxSequence;

        AuthenticatedTransportState(
                IntermediateTransportState intermediate,
                int nextOutboundTxSequence,
                int nextExpectedWatchTxSequence) {
            this.intermediate = intermediate;
            this.nextOutboundTxSequence =
                    nextOutboundTxSequence;
            this.nextExpectedWatchTxSequence =
                    nextExpectedWatchTxSequence;
        }
    }

    private static final class PinTransportState {
        final byte[] pinSalt;
        final int nextOutboundTxSequence;
        final int nextExpectedWatchTxSequence;

        PinTransportState(
                byte[] pinSalt,
                int nextOutboundTxSequence,
                int nextExpectedWatchTxSequence) {
            this.pinSalt = pinSalt.clone();
            this.nextOutboundTxSequence =
                    nextOutboundTxSequence;
            this.nextExpectedWatchTxSequence =
                    nextExpectedWatchTxSequence;
        }

        void destroy() {
            Arrays.fill(pinSalt, (byte) 0);
        }
    }

    static final class StopRequested extends Exception {
    }
    boolean setupCompleted() {
        return activePostCommitCoordinator != null
                && activePostCommitCoordinator.snapshot().phase == AppleWatchPostCommitCoordinator.Phase.COMPLETE;
    }
}
