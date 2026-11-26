package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Operational IDS/NanoRegistry/Health/ClockFace/Bulletin/FindMy/SoftwareUpdate transport loop
 * over the authenticated normal-link ERTM pipe.
 */
final class ModernIdsSessionRunner {
    private final HalTransportSession session;
    NormalLinkIdsSessionBridge activeIdsBridge;
    IdsBootstrapState activeIdsBootstrap;
    InitialWifiSyncWorker initialWifiSync = new InitialWifiSyncWorker(RootWifiNetworkReader::currentArchive);
    String initialWifiMessageUuid;
    boolean initialWifiReceiptLogged;
    boolean initialWifiUnavailableLogged;
    boolean operationalSnapshotPublished;
    SoftwareUpdateSession softwareUpdate;
    boolean healthPeerIdentityPublished;
    boolean appSyncDefaultAppsSent;
    UUID clockFaceObservationEpoch;
    ClockFaceSyncReceiver retainedClockFaceReceiver;
    String retainedClockFacePair;
    PigmentPreferenceMirror pigmentMirror;
    MonogramPreferenceMirror monogramMirror;
    UUID monogramMirrorPair;
    UUID pigmentMirrorPair;
    ClockFaceDeltaSession clockFaceDelta;
    BridgeCommandCodec.Request clockFaceDeltaRequest;
    boolean clockFaceDeltaReadRequested;
    final BulletinTransportSession bulletinTransport = new BulletinTransportSession();
    final BulletinTransportSession bulletinSettingsTransport = new BulletinTransportSession();
    IdsDeviceInfoExchange idsDeviceInfoExchange;
    final NanoSystemSettingsDiagnostics nssDiagnostics = new NanoSystemSettingsDiagnostics();
    final FindMyLocalDeviceSession findMySession = new FindMyLocalDeviceSession();
    final FindMyPhoneSession findMyPhone = new FindMyPhoneSession();
    final ArrayDeque<FindMyPhoneIpcCodec.Result> pendingPhoneReplies = new ArrayDeque<>();
    final SysdiagnoseArchiveInventory sysdiagnoseInventory = new SysdiagnoseArchiveInventory();
    final SysdiagnoseCollection sysdiagnoseCollection = new SysdiagnoseCollection();
    final NativeWatchReboot watchReboot = new NativeWatchReboot();
    NanoRegistryReachabilityProbe nanoRegistryProbe = new NanoRegistryReachabilityProbe();
    boolean idsBootstrapBarrierPassed;
    boolean idsControlReadyObserved;
    boolean neighborAdvertised;
    boolean pairedKeyProbesStarted;
    int idsControlSilenceBounces;
    boolean idsControlRejectedByPolicy;
    boolean pendingNanoSetup;
    long pendingNanoSetupAtMs = -1L;
    long lastNanoSetupWaitLogMs = -1L;
    long lastPendingStallLogMs = -1L;
    long lastKeepalivePollMs = 0L;
    long lastTcpRetransmitMs;
    int ertmStallTxSeq = -1;
    int ertmStallSends;
    long ertmStallNextMs;
    volatile boolean modernIdsReady;

    final ModernIdsAppServiceBridge appServices;
    final ModernIdsOutputProcessor outputProcessor;
    final ModernIdsMaintenanceController maintenance;

    ModernIdsSessionRunner(HalTransportSession session) {
        this.appServices = new ModernIdsAppServiceBridge(session, this);
        this.outputProcessor = new ModernIdsOutputProcessor(session, this);
        this.maintenance = new ModernIdsMaintenanceController(session, this);
        this.session = session;
    }

    void cleanup() {
        initialWifiSync.close();
        findMyPhone.reset(null);
        pendingPhoneReplies.clear();
        log("NANOREGISTRY PROBE FINAL: " + nanoRegistryProbe.status()
                + "; initial Watch properties observed=" + session.postCommit.initialPropertiesReceived);
        if (idsDeviceInfoExchange != null) {
            log("IDS DEVICE INFO FINAL: " + idsDeviceInfoExchange.summary());
            idsDeviceInfoExchange.close();
        }
        clearClockFaceDeltaOnDisconnect();
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
                log("CLEANUP IDS WARNING: " + safeMessage(error));
            }
            activeIdsBridge = null;
        }
        modernIdsReady = false;
    }
    /**
     * Transfers the settled normal-link pipe into the long-lived IDS
     * bridge. Every address copy is wiped after the coordinator has
     * cloned it, and no identifier or key material is logged.
     */
    void attachModernIdsSession(
            NormalLinkPipeSession pipe)
            throws HostException {
        if (pipe == null
                || session.activePairingSessionRecord == null) {
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
        if (session.postCommit.pendingActivationPayload != null) {
            HalTransportSession.wipe(session.postCommit.pendingActivationPayload);
            session.postCommit.pendingActivationPayload = null;
        }
        if (session.postCommit.pendingActivationResponse != null) {
            session.postCommit.pendingActivationResponse.destroy();
            session.postCommit.pendingActivationResponse = null;
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
        if (!installedIdentifier.equalsIgnoreCase(session.activePairingSessionRecord.localIdsDeviceUuid())) {
            throw new HostException("Saved pairing belongs to a different IDS installation identity");
        }
        idsDeviceInfoExchange = new IdsDeviceInfoExchange(session.localIdsPublicBundle,
                installedIdentifier, session.activePairingSessionRecord.peerIdsDeviceId(),
                session.activePairingSessionRecord.hasExchangedIdsDeviceInfo());
        idsDeviceInfoExchange.useProfile(session.companionProfile());
        if (session.postCommit.activeInitialSetup != null) session.postCommit.activeInitialSetup.close();
        session.postCommit.activeInitialSetup = null;
        operationalSnapshotPublished = false;
        if (softwareUpdate != null) softwareUpdate.close();
        softwareUpdate = null;
        healthPeerIdentityPublished = false;
        appSyncDefaultAppsSent = false;
        bulletinTransport.reset();
        bulletinSettingsTransport.reset();
        session.postCommit.earlyInitialEvents.forEach(IdsModernSessionCoordinator.SessionEvent::close);
        session.postCommit.earlyInitialEvents.clear();
        session.postCommit.pendingPostCommitSends.close();
        if (session.postCommit.activationProxyWorker != null) session.postCommit.activationProxyWorker.close();
        session.postCommit.activationProxyWorker = null;
        session.postCommit.activePostCommitCoordinator = null;
        session.postCommit.activePostCommitAdapter = null;
        modernIdsReady = false;
        initialWifiSync.close();
        initialWifiSync = new InitialWifiSyncWorker(RootWifiNetworkReader::currentArchive);
        initialWifiMessageUuid = null;
        initialWifiReceiptLogged = false;
        initialWifiUnavailableLogged = false;
        session.postCommit.readyToCommitIsPaired = false;
        session.postCommit.initialPropertiesReceived = false;
        // Both readiness flags describe the IDS session being replaced, so a
        // stale true would let post-commit send before the new control Hello.
        idsBootstrapBarrierPassed = false;
        idsControlReadyObserved = false;
        neighborAdvertised = false;
        pairedKeyProbesStarted = false;
        idsControlRejectedByPolicy = false;
        session.postCommit.activationPermitSentAtMs = -1L;
        session.postCommit.activationPermitQueuedAtMs = -1L;
        session.postCommit.activationPermitL2capFrames = 0;
        session.postCommit.activationPermitAwaitingFlush = false;
        session.postCommit.activationPermitResendCount = 0;
        session.postCommit.watchDrivenActivationObserved = false;
        session.postCommit.watchProxyActivationObserved = false;
        if (session.postCommit.pendingWatchProxyActivation != null) {
            session.postCommit.pendingWatchProxyActivation.close();
            session.postCommit.pendingWatchProxyActivation = null;
        }

        if (session.activePairingSessionRecord.state().wireValue()
                < PairingSessionRecord.DurableState
                .CLASS_C_ESTABLISHED.wireValue()
                || !session.activePairingSessionRecord
                .addressesConfirmed()
                || !session.activePairingSessionRecord
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
            if (session.activePairingSessionRecord != null) {
                AppleNetworkRelayPairingMaterial localMat =
                        session.activePairingSessionRecord.restoreLocalMaterial();
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
                            session.activePairingSessionRecord
                                    .localIdsDeviceUuid(),
                            true);
            ids.useProfile(session.companionProfile());
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
                storedLocalD = session.activePairingSessionRecord.copyConfirmedLocalAddress(true);
                storedRemoteD = session.activePairingSessionRecord.copyConfirmedRemoteAddress(true);
                storedLocalC = session.activePairingSessionRecord.copyConfirmedLocalAddress(false);
                storedRemoteC = session.activePairingSessionRecord.copyConfirmedRemoteAddress(false);
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
                            + PostCommitActivationRunner.hostPair(storedLocalD, storedRemoteD)
                            + " ike "
                            + PostCommitActivationRunner.hostPair(localClassD, remoteClassD)
                            + "; persisting the Watch assignment.");
                    AppleNetworkRelayInnerAddresses reconciled = null;
                    PairingSessionRecord updated = null;
                    boolean initiator = session.activePairingSessionRecord.localRole()
                            == NrLinkBluetoothPrelude.LocalRole.INITIATOR;
                    try {
                        reconciled = AppleNetworkRelayInnerAddresses.fromAuthoritative(
                                initiator ? localClassD : remoteClassD,
                                initiator ? remoteClassD : localClassD,
                                initiator ? localClassC : remoteClassC,
                                initiator ? remoteClassC : localClassC);
                        updated = session.activePairingSessionRecord.withReconciledQuartet(reconciled);
                        session.persistAndReplaceActivePairingSession(updated);
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
                HalTransportSession.wipe(storedLocalD);
                HalTransportSession.wipe(storedRemoteD);
                HalTransportSession.wipe(storedLocalC);
                HalTransportSession.wipe(storedRemoteC);
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
            if (!"false".equalsIgnoreCase(System.getProperty("watch.internet.relay"))) {
                bridge.enableInternetRelay(ProtocolHost::log);
            }
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
            HalTransportSession.wipe(
                    localClassD);
            HalTransportSession.wipe(
                    remoteClassD);
            HalTransportSession.wipe(
                    localClassC);
            HalTransportSession.wipe(
                    remoteClassC);
            if (addresses != null) {
                addresses.destroy();
            }
        }
    }

    void runModernIdsBootstrapAndHold(
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
                + " durable=" + session.activePairingSessionRecord.state());
        if (isPairedReconnect) {
            log("IDS: Class-D Setup with Hello (0.2.196 wake); NA after LDM; no ids-control SYN.");
        }
        session.normalLink.sendNormalFrames(
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
                if (session.normalLink.tryReconnectAfterWatchDisconnect(
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
            session.postCommit.startPostCommitIfAuthorized(
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
                if (session.normalLink.tryReconnectAfterWatchDisconnect(
                        "live IDS hold",
                        disconnectError)) {
                    return;
                }
                throw disconnectError;
            }
        }
    }

    void runModernIdsBootstrapWithInitialIp(
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
                + " durable=" + session.activePairingSessionRecord.state());
        if (isPairedReconnect) {
            log("IDS: Class-D Setup with Hello (0.2.196 wake); NA after LDM; no ids-control SYN.");
        }
        session.normalLink.sendNormalFrames(
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
                if (session.normalLink.tryReconnectAfterWatchDisconnect(
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
            session.postCommit.startPostCommitIfAuthorized(
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
                if (session.normalLink.tryReconnectAfterWatchDisconnect(
                        "post-commit setup",
                        disconnectError)) {
                    return;
                }
                throw disconnectError;
            }
        }
    }

    boolean durablePairedCommitReady() {
        return session.activePairingSessionRecord != null
                && (session.activePairingSessionRecord.state().wireValue()
                        >= PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED.wireValue()
                        || session.activePairingSessionRecord.isPairedCommitIntentPersisted()
                        || (session.commitIsPairedAuthorized && session.activePairingSessionRecord.state().wireValue()
                                >= PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED.wireValue()));
    }

    boolean canResumeIdsData() {
        return IdsPairedReconnectPolicy.usePairedDataBootstrap(
                session.activePairingSessionRecord != null ? session.activePairingSessionRecord.state() : null);
    }

    void acceptModernIdsFrame(
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
            session.postCommit.pollActivationProxy(remoteCid);
            pollInitialWifiSync(remoteCid);
            session.postCommit.pumpStoredTwoWayPreferences(remoteCid);
            if (activeIdsBridge != null) {
                session.normalLink.sendNormalFrames(remoteCid, activeIdsBridge.pollLinkDirectorAnnouncements(
                        System.nanoTime() / 1_000_000L), "NORMAL LINK DIRECTOR CONTROL");
            }
            drainOutboundAppMessages(remoteCid);
            long sliceMs =
                    pendingNanoSetup
                            ? 80L
                            : (activeIdsBridge != null && activeIdsBridge.hasActiveInternetRelayFlows()
                            ? 25L
                            : (!session.outboundAppMessages.isEmpty() ? 50L : 500L));
            long sliceDeadline = Math.min(deadline, BridgeClock.elapsedRealtime() + sliceMs);
            HciCodec.L2capPdu l2cap = null;
            try {
                l2cap = session.normalLink.awaitNormalL2cap(
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
                                session.sendL2cap(remoteCid, rr, "NORMAL ERTM KEEPALIVE RR");
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
                                            session.sendL2cap(
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
                                    session.sendL2cap(
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
                            session.postCommit.driveActivationFallbackIfStalled(remoteCid);
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
                HalTransportSession.wipe(
                        l2cap.payload);
            }
        }
    }

    /** Incoming traffic must not suppress TCP recovery after ERTM has drained. */
    void retryPendingTcpOnTimer(int remoteCid) throws Exception {
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
    void retryPendingErtmOnTimer(int remoteCid) throws Exception {
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
                session.sendL2cap(remoteCid, frame, "NORMAL ERTM TIMER RETRANSMIT");
            }
        } finally { for (byte[] frame : frames) HalTransportSession.wipe(frame); }
    }

    void maybeStartPairedClassDSetup(int remoteCid) throws Exception {
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

    void maybeAnnounceLocalNeighbor(int remoteCid) throws Exception {
        if (neighborAdvertised || activeIdsBridge == null
                || !activeIdsBridge.linkDirectorStateAcknowledged()) {
            return;
        }
        // Paired resume must still advertise Class-D/C reachability.
        // Skipping NA when CLOCK_VISIBLE (0.2.294) left Watch NDP cold:
        // no phone named UTun SYN, no NS→NA on the ERTM path, and no
        // Watch-initiated ids-control-channel after DeviceLinkState ACK.
        session.normalLink.sendNormalFrames(
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
    void bounceAclForIdsControlSilence() throws Exception {
        idsControlSilenceBounces++;
        log("IDS: no Watch ids-control-channel within "
                + IdsPairedReconnectPolicy.CONTROL_SILENCE_BOUNCE_MS
                + " ms after LDM+NA; ACL bounce "
                + idsControlSilenceBounces
                + "/"
                + IdsPairedReconnectPolicy.CONTROL_SILENCE_BOUNCE_MAX
                + " to clear Watch control connecting zombie.");
        if (session.connectionHandle != -1) {
            session.sendCommandStatus(
                    HciCodec.OPCODE_DISCONNECT,
                    HciCodec.buildDisconnectParameters(session.connectionHandle));
            long disconnDeadline = BridgeClock.elapsedRealtime() + 1500L;
            while (BridgeClock.elapsedRealtime() < disconnDeadline) {
                try {
                    HalTransportSession.Packet packet = session.nextPacket(disconnDeadline);
                    if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                        HciCodec.DisconnectionComplete complete =
                                HciCodec.parseDisconnectionComplete(packet.bytes);
                        if (complete != null
                                && complete.connectionHandle == session.connectionHandle) {
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    break;
                }
            }
            session.connectionHandle = -1;
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
        session.reconnectRequested = true;
    }


    void pollInitialWifiSync(int remoteCid) { appServices.pollInitialWifiSync(remoteCid); }
    void drainOutboundAppMessages(int remoteCid) { appServices.drainOutboundAppMessages(remoteCid); }
    void publishSoftwareUpdate() { appServices.publishSoftwareUpdate(); }
    void publishClockFaceObservation(UUID epoch) { appServices.publishClockFaceObservation(epoch); }
    void beginClockFaceDelta(BridgeCommandCodec.Request request, int remoteCid) throws Exception { appServices.beginClockFaceDelta(request, remoteCid); }
    void advanceClockFaceDelta(int remoteCid) { appServices.advanceClockFaceDelta(remoteCid); }
    void clearClockFaceDeltaOnDisconnect() { appServices.clearClockFaceDeltaOnDisconnect(); }
    ClockFaceSyncClient clockFaceClient() { return appServices.clockFaceClient(); }
    ClockFaceSyncReceiver clockFaceReceiver() { return appServices.clockFaceReceiver(); }
    void forwardEncryptedHealthData(IdsModernSessionCoordinator.SessionEvent event) { appServices.forwardEncryptedHealthData(event); }
    void publishHealthPeerIdentity() { appServices.publishHealthPeerIdentity(); }
    PigmentPreferenceMirror pigmentMirror(UUID pair) throws IOException { return appServices.pigmentMirror(pair); }
    void publishPigmentMirror() throws IOException { appServices.publishPigmentMirror(); }
    MonogramPreferenceMirror monogramMirror(UUID pair) throws IOException { return appServices.monogramMirror(pair); }
    void publishMonogramMirror() throws IOException { appServices.publishMonogramMirror(); }
    void handleIncomingProtobufEvent(IdsModernSessionCoordinator.SessionEvent event) { appServices.handleIncomingProtobufEvent(event); }
    void publishHealthRegistryObservation(IdsModernSessionCoordinator.SessionEvent event) { appServices.publishHealthRegistryObservation(event); }
    void drainPhonePingReplies(int remoteCid) { appServices.drainPhonePingReplies(remoteCid); }
    void processModernIdsOutput(NormalLinkIdsSessionBridge.Output output, int remoteCid, String label) throws Exception { outputProcessor.processModernIdsOutput(output, remoteCid, label); }
    static String idsServiceLogLabel(String service) { return ModernIdsOutputProcessor.idsServiceLogLabel(service); }
    static String idsTopicLogLabel(String topic) { return ModernIdsOutputProcessor.idsTopicLogLabel(topic); }























    void fireProactiveStaleFlowResets(int remoteCid) throws Exception {
        maintenance.fireProactiveStaleFlowResets(remoteCid);
    }

    void drainStaleFlowSeeds(int remoteCid) throws Exception {
        maintenance.drainStaleFlowSeeds(remoteCid);
    }

    void persistStaleFlowObservations(String reason) {
        maintenance.persistStaleFlowObservations(reason);
    }

    void flushPendingIdsFrames(int remoteCid) throws Exception {
        maintenance.flushPendingIdsFrames(remoteCid);
    }

    void markActivationPermitOnWireIfFlushed() {
        maintenance.markActivationPermitOnWireIfFlushed();
    }

    void maybeStartNanoRegistryProbe(int remoteCid) throws Exception {
        maintenance.maybeStartNanoRegistryProbe(remoteCid);
    }

    String requireAppIdsDeviceIdentifier() throws HostException {
        return maintenance.requireAppIdsDeviceIdentifier();
    }

    void persistPeerSpsMetadata(IdsSpsCompanionInfo info) throws Exception {
        maintenance.persistPeerSpsMetadata(info);
    }

    void sendIdsDeviceInfo(byte[] payload, boolean response, int remoteCid) throws Exception {
        maintenance.sendIdsDeviceInfo(payload, response, remoteCid);
    }

    void maybeProgressIdsDeviceInfo(int remoteCid) throws Exception {
        maintenance.maybeProgressIdsDeviceInfo(remoteCid);
    }

    boolean deviceInfoAllowsPostCommitResume() {
        return maintenance.deviceInfoAllowsPostCommitResume();
    }

    void maybeFlushPendingNanoSetup(int remoteCid) throws Exception {
        maintenance.maybeFlushPendingNanoSetup(remoteCid);
    }

    void holdControlSynForStaleTcp(int localCid, int remoteCid) throws Exception {
        maintenance.holdControlSynForStaleTcp(localCid, remoteCid);
    }

    void executeIdsBootstrapAction(IdsBootstrapState.Action action, int remoteCid) throws Exception {
        maintenance.executeIdsBootstrapAction(action, remoteCid);
    }
}
