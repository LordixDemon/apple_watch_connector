package dev.applewatchandroid.bridge;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * One Bluetooth HCI controller lease: shared transport primitives, delegation to
 * pairing/normal-link/IDS/post-commit runners, and deterministic teardown.
 */
final class HalTransportSession {
    final BluetoothController hci;
    final AtomicBoolean stopRequested;
    final BlockingQueue<char[]> pinInputs;
    final BlockingQueue<Boolean> bondStoreResults;
    final BlockingQueue<Boolean> pairingSessionStoreResults;
    final BlockingQueue<byte[]> localIdentityRecords;
    final BlockingQueue<byte[]> localIdsPublicRecords;
    byte[] localIdsPublicBundle;
    final BlockingQueue<byte[]> restoredPairingSessionRecords;
    final BlockingQueue<OutboundAppMessage> outboundAppMessages;
    final BlockingQueue<Packet> packets = new LinkedBlockingQueue<>(4096);
    final ArrayDeque<Packet> deferredControllerPackets = new ArrayDeque<>();
    final BlockingQueue<byte[]> deferredSmpPdus = new LinkedBlockingQueue<>(16);
    static final long LE_LINK_NEGOTIATION_TIMEOUT_MS = 1_500L;
    long finalPairingAckAtMs = -1;
    long smpRequestSubmittedAtMs = -1;
    int pairingSmpReceived;
    boolean localLeDataLengthSupported;
    boolean localLe2mPhySupported;
    int preferredLeTxOctets = HciCodec.LE_MAX_DATA_OCTETS;
    int preferredLeTxTimeUs = HciCodec.LE_MAX_UNCODED_DATA_TIME_US;
    int leObservedHandle = -1;
    boolean lePhyUpdateObserved;
    int leObservedPhyStatus = -1;
    int leObservedTxPhy = -1;
    int leObservedRxPhy = -1;
    boolean leDataLengthChangeObserved;
    int leObservedMaxTxOctets = -1;
    int leObservedMaxRxOctets = -1;
    volatile CountDownLatch initializationLatch = new CountDownLatch(1);
    final AtomicInteger initializationStatus = new AtomicInteger(-1);
    final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    final HciAclCreditWindow aclCredits = new HciAclCreditWindow();
    final HciCodec.AclReassembler aclReassembler = new HciCodec.AclReassembler();
    final BluetoothController.Callbacks callbacks = new BluetoothController.Callbacks() {
        @Override public void aclDataReceived(byte[] data) { receive(PacketKind.ACL, data); }
        @Override public void hciEventReceived(byte[] event) { receive(PacketKind.EVENT, event); }
        @Override public void initializationComplete(int status) {
            initializationStatus.set(status);
            initializationLatch.countDown();
        }
        @Override public void transportFailed(IOException failure) {
            callbackFailure.compareAndSet(null, failure);
            initializationComplete(-1);
        }
        @Override public void isoDataReceived(byte[] data) { }
        @Override public void scoDataReceived(byte[] data) { }
    };
    volatile boolean initializedByUs;
    volatile boolean scanEnabled;
    volatile boolean createPending;
    volatile int connectionHandle = -1;
    volatile int maximumAclDataLength = 0xFFFF;
    long lastAclCreditStatusMs;
    BluetoothLocalIdentity localIdentity;
    int peerConnectionAddressType = -1;
    byte[] peerConnectionAddress;
    byte[] activeLongTermKey;
    PairingSessionRecord activePairingSessionRecord;
    WatchSetupMetadataCodec.Identifier activeWatchSetupIdentifier;
    WatchSetupMetadataCodec.ExtendedMetadata activeWatchSetupMetadata;
    volatile boolean cleaned;
    volatile boolean reconnectRequested;
    final boolean commitIsPairedAuthorized;
    final String operationalPairingId;
    boolean freshPairingPerformed;

    final ModernPairingIkeRunner pairingIke = new ModernPairingIkeRunner(this);
    final ModernSmpBondingRunner smpBonding = new ModernSmpBondingRunner(this);
    final NormalLinkLifecycleManager normalLink = new NormalLinkLifecycleManager(this);
    final ModernIdsSessionRunner idsSession = new ModernIdsSessionRunner(this);
    final PostCommitActivationRunner postCommit = new PostCommitActivationRunner(this);
    final HalLeLinkConfigurator leConfig = new HalLeLinkConfigurator(this);
    final HalBtClTransportHelper btClTransport = new HalBtClTransportHelper(this);

    CompanionProtocolProfile companionProfile() {
        if (idsSession.activeIdsBridge != null) return idsSession.activeIdsBridge.ids().profile();
        Integer maximum = activeWatchSetupMetadata != null ? activeWatchSetupMetadata.pairingVersion
                : activePairingSessionRecord == null ? null : activePairingSessionRecord.observedPeerMaximum();
        if (maximum != null) return CompanionProtocolProfile.forPeerMaximum(maximum);
        String build = activePairingSessionRecord == null ? null : activePairingSessionRecord.peerBuildVersion();
        return build != null && build.startsWith("24") ? CompanionProtocolProfile.IOS_27_0_1 : CompanionProtocolProfile.IOS_26_6;
    }

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
        configureLocalLeCapabilities();
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
                            reconnected = normalLink.reconnectPairedWatch();
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
                + "target=any Watch model/NetworkRelay strategy 4, "
                + "model whitelist=false; compatibility checked during setup, "
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
        target = normalLink.awaitTarget();
        boolean legacyTargetSelected =
                EXPERIMENTAL_LEGACY_PAIRING.get()
                        && !target.isExpectedTarget()
                        && target.legacySetup != null;
        if (!target.isExpectedTarget() && !legacyTargetSelected) {
            throw new HostException("Selected Watch lacks verified setup metadata");
        }
        if (legacyTargetSelected) {
            log("[EXPERIMENTAL LEGACY] WATCH TARGET LOCKED: address logged=false; type="
                    + target.addressType
                    + " RSSI="
                    + target.rssi
                    + " strategy="
                    + target.legacySetup.effectiveStrategy()
                    + " hasUuid9AA4730F="
                    + target.legacySetup.hasExtensiblePairServiceUuid
                    + " rawLogged=false.");
        } else {
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

        resetObservedLeLinkState();
        createPending = true;
        sendCommandStatus(
                HciCodec.OPCODE_LE_CREATE_CONNECTION,
                HciCodec.buildLeCreateConnectionParameters(
                        target,
                        localIdentity.addressType()));
        HciCodec.LeConnectionComplete connection =
                normalLink.awaitConnectionComplete(target);
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

        negotiateLeLinkParameters(connectionHandle);
        if (legacyTargetSelected) {
            runExperimentalLegacyPairing(target);
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
        normalLink.normalBtClVersion = peerVersion.version;
        normalLink.normalBtClFeatures = peerVersion.features;
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
                normalLink.rejectSetupCandidate(
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
            normalLink.rejectSetupCandidate(
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
            pairingIke.probeControlSaInit(accept.responderLocalCid, setupData);
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
                                byte[] attReply = HalGattServer.handleRequest(l2cap.payload);
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
                                        pairingIke.probeControlSaInit(retryAccept.responderLocalCid, setupData);
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
                                    pairingIke.probeControlSaInit(cc.requesterLocalCid, setupData);
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

    void persistAndReplaceActivePairingSession(
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

    static byte[] typedAddress(
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

    void persistBondSecretOrFail(
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

    void persistPairingSessionOrFail(
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

    void startLeEncryption(byte[] longTermKey) throws Exception {
        startLeEncryption(new byte[8], 0, longTermKey);
    }

    void startLeEncryption(
            byte[] randomNumber,
            int ediv,
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
                                        randomNumber,
                                        ediv,
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
                    try { deferPairingSmp(l2cap.payload, "SMP encryption change"); }
                    finally { wipe(l2cap.payload); }
                } else if (l2cap != null) {
                    wipe(l2cap.payload);
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

    static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    void recordPairingSmpRx(byte[] payload, String phase) {
        pairingSmpReceived++;
        log(String.format(Locale.US,
                "SMP RX: phase=%s cid=0x%04X opcode=0x%02X bytes=%d received=%d; payload bytes logged=false.",
                phase, BluetoothSmpCodec.FIXED_CID,
                payload.length == 0 ? -1 : payload[0] & 255,
                payload.length, pairingSmpReceived));
    }

    void deferPairingSmp(byte[] payload, String phase) throws HostException {
        recordPairingSmpRx(payload, phase);
        byte[] copy = payload.clone();
        if (!deferredSmpPdus.offer(copy)) {
            wipe(copy);
            throw new HostException("SMP deferred queue overflow during " + phase
                    + "; " + pairingSmpDiagnostics());
        }
        log("SMP RX DEFERRED: phase=" + phase + " queued=" + deferredSmpPdus.size()
                + "; retained until SMP waiter; payload bytes logged=false.");
    }

    void clearDeferredSmpPdus() {
        byte[] payload;
        while ((payload = deferredSmpPdus.poll()) != null) wipe(payload);
    }

    boolean retainPairingTerminusPdu(HciCodec.L2capPdu l2cap, String phase) throws HostException {
        if (l2cap == null) return false;
        if (l2cap.connectionHandle == connectionHandle
                && l2cap.destinationCid == HciCodec.TERMINUS_LOCAL_CID) return true;
        try {
            if (l2cap.connectionHandle == connectionHandle
                    && l2cap.destinationCid == BluetoothSmpCodec.FIXED_CID) {
                deferPairingSmp(l2cap.payload, phase);
            }
        } finally { wipe(l2cap.payload); }
        return false;
    }

    String pairingSmpDiagnostics() {
        long now = BridgeClock.elapsedRealtime();
        boolean phyForHandle = connectionHandle >= 0 && leObservedHandle == connectionHandle && lePhyUpdateObserved;
        boolean dleForHandle = connectionHandle >= 0 && leObservedHandle == connectionHandle && leDataLengthChangeObserved;
        return "finalIkeAckAgeMs=" + (finalPairingAckAtMs < 0 ? -1 : now - finalPairingAckAtMs)
                + " requestSubmittedAgeMs=" + (smpRequestSubmittedAtMs < 0 ? -1 : now - smpRequestSubmittedAtMs)
                + " received=" + pairingSmpReceived + " deferred=" + deferredSmpPdus.size()
                + " controllerAcl={" + aclCredits.summary() + "}"
                + " lePhy={observed=" + phyForHandle
                + ",status=" + (phyForHandle ? String.format(Locale.US, "0x%02X", leObservedPhyStatus) : "none")
                + ",tx=" + (phyForHandle ? leObservedTxPhy : -1)
                + ",rx=" + (phyForHandle ? leObservedRxPhy : -1) + "}"
                + " leDle={observed=" + dleForHandle
                + ",tx=" + (dleForHandle ? leObservedMaxTxOctets : -1)
                + ",rx=" + (dleForHandle ? leObservedMaxRxOctets : -1) + "};"
                + " payload bytes logged=false";
    }

    void failIfPairingDisconnected(Packet packet, String phase) throws HostException {
        HciCodec.DisconnectionComplete disconnected = HciCodec.parseDisconnectionComplete(packet.bytes);
        if (disconnected != null && disconnected.connectionHandle == connectionHandle) {
            String diagnostics = pairingSmpDiagnostics();
            connectionHandle = -1;
            clearDeferredSmpPdus();
            throw new HostException(String.format(Locale.US,
                    "Watch disconnected during %s; status=0x%02X reason=0x%02X; %s",
                    phase, disconnected.status, disconnected.reason, diagnostics));
        }
    }

    void runExperimentalLegacyPairing(HciCodec.AdvertisingReport target) throws Exception {
        btClTransport.runExperimentalLegacyPairing(target);
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion) throws Exception {
        return btClTransport.awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion);
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            long peerFeatures) throws Exception {
        return btClTransport.awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                peerFeatures);
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            long peerFeatures,
            byte[] retransmitPdu) throws Exception {
        return btClTransport.awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                peerFeatures,
                retransmitPdu);
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            BtClNormalLinkHandoff normalHandoff)
            throws Exception {
        return btClTransport.awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                normalHandoff);
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            long peerFeatures,
            byte[] retransmitPdu,
            BtClNormalLinkHandoff normalHandoff)
            throws Exception {
        return btClTransport.awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                peerFeatures,
                retransmitPdu,
                normalHandoff);
    }

    void failOnPostSmpPdu(
            byte[] payload,
            String phase)
            throws HostException {
        btClTransport.failOnPostSmpPdu(payload, phase);
    }

    synchronized void sendL2cap(int cid, byte[] payload, String label)
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

            if (cid == BluetoothSmpCodec.FIXED_CID && payload.length > 0
                    && (payload[0] & 255) == BluetoothSmpCodec.PAIRING_REQUEST) {
                smpRequestSubmittedAtMs = BridgeClock.elapsedRealtime();
                log("SMP REQUEST SUBMITTED: transport write returned; peer receipt unconfirmed; "
                        + pairingSmpDiagnostics());
            }

        } finally { fragments.forEach(HalTransportSession::wipe); }
        long now = BridgeClock.elapsedRealtime();
        if (now - lastAclCreditStatusMs >= 10_000) {
            lastAclCreditStatusMs = now;
            log("HCI ACL CREDIT STATUS: " + aclCredits.summary());
        }
    }

    static boolean suppressPacketBytes(
            String label) {
        return label != null
                && (label.contains("PRIVATE-NOTIFIES")
                || label.contains("SMP")
                || label.contains("NORMAL"));
    }

    HciCodec.CommandComplete sendCommandComplete(
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

    void sendCommandStatus(int opcode, byte[] parameters)
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

    Packet nextPacket(long deadline) throws Exception {
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
                if (packet.kind == PacketKind.ACL && normalLink.fixedChannelRouter.accept(
                        packet.bytes, connectionHandle, normalLink::serviceNormalFixedChannel)) {
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
                        normalLink.fixedChannelRouter.reset();
                        normalLink.normalAttServer.reset();
                        clearDeferredSmpPdus();
                    }
                }
                return packet;
            }
        }
    }

    void disconnectBeforeFreshPairing() throws Exception {
        leConfig.disconnectBeforeFreshPairing();
    }

    void configureLocalLeCapabilities() throws Exception {
        leConfig.configureLocalLeCapabilities();
    }

    void negotiateLeLinkParameters(int handle) throws Exception {
        leConfig.negotiateLeLinkParameters(handle);
    }

    boolean awaitObservedLeEvent(int handle, boolean waitForPhy) throws Exception {
        return leConfig.awaitObservedLeEvent(handle, waitForPhy);
    }

    HciCodec.CommandComplete sendOptionalCommandComplete(
            int opcode,
            byte[] parameters,
            int handle)
            throws Exception {
        return leConfig.sendOptionalCommandComplete(opcode, parameters, handle);
    }

    Integer sendOptionalCommandStatus(
            int opcode,
            byte[] parameters,
            int handle)
            throws Exception {
        return leConfig.sendOptionalCommandStatus(opcode, parameters, handle);
    }

    void restoreDeferredControllerPackets(List<Packet> deferred) {
        leConfig.restoreDeferredControllerPackets(deferred);
    }

    void resetObservedLeLinkState() {
        leConfig.resetObservedLeLinkState();
    }

    void logControllerDiagnostics(Packet packet) {
        leConfig.logControllerDiagnostics(packet);
    }

    static String formatErtmFcs(
            L2capErtmCodec.Frame frame) {
        return frame.fcsPresent
                ? String.format(Locale.US, "0x%04X", frame.fcs)
                : "none";
    }

    void enqueue(Packet packet) {
        if (!packets.offer(packet)) {
            wipe(packet.bytes);
            callbackFailure.compareAndSet(
                    null,
                    new IllegalStateException("HCI callback queue overflow"));
        }
    }

    void receive(PacketKind kind, byte[] bytes) {
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

    void checkStop() throws StopRequested {
        if (stopRequested.get()) {
            throw new StopRequested();
        }
    }

    synchronized void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        idsSession.cleanup();
        pairingIke.closeOpticalCodes();
        OPERATIONAL_REQUESTS.disconnect();
        FIND_MY_PHONE_RESULTS.clear();
        log("HCI ACL CREDIT FINAL: " + aclCredits.summary());
        OutboundAppMessage queuedApplication;
        while ((queuedApplication = outboundAppMessages.poll()) != null) {
            wipe(queuedApplication.payload);
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
        postCommit.cleanup();
        if (activePairingSessionRecord != null) {
            activePairingSessionRecord.destroy();
            activePairingSessionRecord = null;
        }
        activeWatchSetupIdentifier = null;
        activeWatchSetupMetadata = null;
        normalLink.modernRegistrationDeadlineMs = -1;
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
            clearDeferredSmpPdus();
            if (localIdentity != null) {
                localIdentity.destroy();
                localIdentity = null;
            }
            if (peerConnectionAddress != null) {
                Arrays.fill(peerConnectionAddress, (byte) 0);
                peerConnectionAddress = null;
            }
            peerConnectionAddressType = -1;
        }
    }

    void awaitDisconnectionForCleanup(int handle)
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

    enum PacketKind {
        EVENT,
        ACL
    }

    static final class Packet {
        final PacketKind kind;
        final byte[] bytes;

        Packet(PacketKind kind, byte[] bytes) {
            this.kind = kind;
            this.bytes = bytes;
        }

    }

    static final class PairingTransportCursor {
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

    static final class StopRequested extends Exception {
    }

    void configureOpticalPairing(byte[] payload) {
        pairingIke.configureOpticalPairing(payload);
    }

    boolean readyForCleanStop() {
        return postCommit.readyForCleanStop();
    }

    boolean setupCompleted() {
        return postCommit.setupCompleted();
    }

    HciCodec.AdvertisingReport awaitTarget() throws Exception {
        return normalLink.awaitTarget();
    }

    byte[] awaitSmpPdu(int expectedOpcode, long timeoutMs, String label) throws Exception {
        return smpBonding.awaitSmpPdu(expectedOpcode, timeoutMs, label);
    }

    PairingTransportCursor sendPairingIkeOneWayAndAwaitAck(
            int responderLocalCid,
            PairingTransportCursor initialCursor,
            List<byte[]> packetsToSend,
            String label,
            long timeoutMs) throws Exception {
        return pairingIke.sendPairingIkeOneWayAndAwaitAck(
                responderLocalCid, initialCursor, packetsToSend, label, timeoutMs);
    }

    PairingTransportCursor sendPairingIkeOneWayAndAwaitAck(
            int responderLocalCid,
            PairingTransportCursor initialCursor,
            List<byte[]> packetsToSend,
            String label,
            long timeoutMs,
            IkeV2SessionCrypto.WatchRequestRetryMatcher retryMatcher) throws Exception {
        return pairingIke.sendPairingIkeOneWayAndAwaitAck(
                responderLocalCid, initialCursor, packetsToSend, label, timeoutMs, retryMatcher);
    }
}
