package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Stale-flow reset/persistence, pending IDS frame flushing, NanoRegistry/SPS/
 * DeviceInfo progression, and bootstrap-action dispatch for
 * {@link ModernIdsSessionRunner}.
 */
final class ModernIdsMaintenanceController {
    private final HalTransportSession session;
    private final ModernIdsSessionRunner runner;

    ModernIdsMaintenanceController(
            HalTransportSession session,
            ModernIdsSessionRunner runner) {
        this.session = session;
        this.runner = runner;
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
    void fireProactiveStaleFlowResets(
            int remoteCid)
            throws Exception {
        if (runner.activeIdsBridge == null) {
            return;
        }
        boolean any = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            NormalLinkIdsSessionBridge.Output resets =
                    runner.activeIdsBridge.resetStaleFlows();
            if (resets.ertmFrames().isEmpty()
                    && attempt == 1) {
                resets.close();
                return;
            }
            any = true;
            runner.processModernIdsOutput(
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
    void drainStaleFlowSeeds(
            int remoteCid)
            throws Exception {
        NativeApplicationServiceDiscovery discovery = PENDING_NATIVE_SNAPSHOT_DISCOVERY.getAndSet(null);
        if (discovery != null) {
            try {
                if (runner.activeIdsBridge == null) throw new IllegalStateException("No active normal session");
                runner.activeIdsBridge.discoverNativeSnapshotService(discovery, ProtocolHost::log);
                session.normalLink.sendNormalFrames(remoteCid, runner.activeIdsBridge.pollLinkDirectorAnnouncements(
                        System.nanoTime() / 1_000_000L), "NATIVE SNAPSHOT SERVICE DISCOVERY");
            } catch (IllegalStateException unavailable) {
                log("APPLICATION SERVICE DISCOVERY: unavailable on current session; no pairing or setup changes.");
            }
        }
        if (runner.activeIdsBridge != null && PENDING_IDS_KEY_PROBE.getAndSet(false)) {
            runner.processModernIdsOutput(runner.activeIdsBridge.startPairedClassDKeyProbe(),
                    remoteCid, "DIAGNOSTIC NWSC KEY PROBE");
            flushPendingIdsFrames(remoteCid);
        }
        if (runner.activeIdsBridge != null && PENDING_IDS_CONTROL_OPEN.getAndSet(false)) {
            try {
                runner.processModernIdsOutput(runner.activeIdsBridge.startControl(),
                        remoteCid, "DIAGNOSTIC IDS CONTROL OPEN");
                flushPendingIdsFrames(remoteCid);
            } catch (IllegalStateException unavailable) {
                log("DIAGNOSTIC IDS CONTROL OPEN: " + unavailable.getMessage());
            }
        }
        if (PENDING_CLOSE_IKE_SESSION.getAndSet(false) && runner.activeIdsBridge != null) {
            List<byte[]> frames = runner.activeIdsBridge.requestIkeDeletes();
            try {
                session.normalLink.sendNormalFrames(remoteCid, frames, "AUTHENTICATED IKE DELETE");
                log("IKE DELETE SENT: Class-C/D transient session teardown requested; bond and activation retained.");
            } finally { for (byte[] frame : frames) HalTransportSession.wipe(frame); }
        }
        PendingStaleFlowSeed seed;
        while ((seed = PENDING_STALE_FLOW_SEEDS.poll()) != null) {
            try {
                boolean classD =
                        seed.dataClass
                                == OrdinaryIkeAuth.DataClass.CLASS_D;
                byte[] localAddress =
                        session.activePairingSessionRecord
                                .copyConfirmedLocalAddress(classD);
                byte[] remoteAddress =
                        session.activePairingSessionRecord
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
                    if (runner.activeIdsBridge != null) {
                        runner.activeIdsBridge.seedStaleFlows(
                                List.of(record));
                        log("STALE FLOW SEEDED: "
                                + record.dataClass + " "
                                + record.remotePort + "->"
                                + record.localPort
                                + " seq=" + record.peerAck
                                + "; firing proactive resets.");
                        runner.processModernIdsOutput(
                                runner.activeIdsBridge.resetStaleFlows(),
                                remoteCid,
                                "STALE FLOW RESET (seeded)");
                        flushPendingIdsFrames(remoteCid);
                    }
                } finally {
                    HalTransportSession.wipe(localAddress);
                    HalTransportSession.wipe(remoteAddress);
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
    void persistStaleFlowObservations(
            String reason) {
        if (runner.activeIdsBridge == null) {
            return;
        }
        try {
            List<IdsStaleFlowRecord> observed =
                    runner.activeIdsBridge.staleFlowObservations();
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
    void flushPendingIdsFrames(
            int remoteCid)
            throws Exception {
        if (runner.activeIdsBridge == null) {
            return;
        }
        if (runner.activeIdsBridge.hasDeferredIpv6()) {
            int held = runner.activeIdsBridge.deferredIpv6Count();
            NormalLinkIdsSessionBridge.Output resumed =
                    runner.activeIdsBridge.drainDeferredIpv6();
            try (resumed) {
                if (!resumed.ertmFrames().isEmpty()) {
                    if (session.postCommit.activationPermitQueuedAtMs >= 0
                            && session.postCommit.activationPermitSentAtMs < 0) {
                        session.postCommit.activationPermitL2capFrames +=
                                resumed.ertmFrames().size();
                        session.postCommit.activationPermitAwaitingFlush = true;
                    }
                    log("IDS DEFERRED IPV6 RESUMED: frames="
                            + resumed.ertmFrames().size()
                            + " held=" + held);
                    session.normalLink.sendNormalFrames(
                            remoteCid,
                            resumed.ertmFrames(),
                            "IDS DEFERRED IPV6");
                }
            }
        }
        if (!runner.activeIdsBridge.hasPendingApplicationFrames()) {
            session.postCommit.completePendingPostCommitSends(remoteCid);
            return;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            NormalLinkIdsSessionBridge.Output flushed =
                    runner.activeIdsBridge.flushPendingApplicationFrames();
            try (flushed) {
                if (flushed.ertmFrames().isEmpty()) {
                    long now = BridgeClock.elapsedRealtime();
                    if (now - runner.lastPendingStallLogMs >= 2_000) {
                        runner.lastPendingStallLogMs = now;
                        log("POST-COMMIT PENDING STALLED: "
                                + runner.activeIdsBridge.pendingApplicationFramesSummary());
                    }
                    return;
                }
                if (session.postCommit.activationPermitQueuedAtMs >= 0
                        && session.postCommit.activationPermitSentAtMs < 0
                        && !flushed.ertmFrames().isEmpty()) {
                    session.postCommit.activationPermitL2capFrames +=
                            flushed.ertmFrames().size();
                    session.postCommit.activationPermitAwaitingFlush = true;
                }
                log("POST-COMMIT PENDING FLUSH: frames="
                        + flushed.ertmFrames().size());
                session.normalLink.sendNormalFrames(
                        remoteCid,
                        flushed.ertmFrames(),
                        "POST-COMMIT PENDING FLUSH");
            }
            if (!runner.activeIdsBridge.hasPendingApplicationFrames()) {
                session.postCommit.completePendingPostCommitSends(remoteCid);
                return;
            }
        }
    }

    void markActivationPermitOnWireIfFlushed() {
        if (!session.postCommit.activationPermitAwaitingFlush
                || session.postCommit.activationPermitL2capFrames <= 0
                || runner.activeIdsBridge == null
                || runner.activeIdsBridge.hasPendingApplicationFrames()
                || runner.activeIdsBridge.hasDeferredIpv6()
                || session.postCommit.pendingPostCommitSends.size() > 0) {
            return;
        }
        session.postCommit.activationPermitAwaitingFlush = false;
        session.postCommit.activationPermitSentAtMs = BridgeClock.elapsedRealtime();
        log("POST-COMMIT: activation permit flushed after Handshake; awaiting Watch-driven "
                + "ProxyActivation request for Albert HTTPS activation.");
    }


    void maybeStartNanoRegistryProbe(int remoteCid) throws Exception {
        if (session.postCommit.activeInitialSetup == null || runner.activeIdsBridge == null
                || !session.postCommit.activeInitialSetup.phoneSnapshotAckObserved()
                || session.postCommit.initialPropertiesReceived || !runner.nanoRegistryProbe.start()) return;
        NanoRegistryClassDCodec.PingRequest request = runner.nanoRegistryProbe.request();
        try (NormalLinkIdsSessionBridge.Output probe = runner.activeIdsBridge.sendClassDRequestWithClientAcknowledgement(request)) {
            for (IdsModernSessionCoordinator.SessionEvent event : probe.idsEvents()) {
                if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT
                        && event.protobufType == NanoRegistryClassDCodec.TYPE_PING) {
                    runner.nanoRegistryProbe.onQueued(event.sequence, event.messageUuid);
                }
            }
            log("NANOREGISTRY PROBE: type-5 Ping queued with explicit peer-response and client-ACK flags; awaiting a correlated daemon response.");
            runner.processModernIdsOutput(probe, remoteCid, "NORMAL NANOREGISTRY PROBE");
        } finally {
            request.destroy();
        }
    }

    String requireAppIdsDeviceIdentifier() throws HostException {
        try {
            if (session.localIdsPublicBundle == null) {
                session.localIdsPublicBundle = session.localIdsPublicRecords.poll(LOCAL_IDENTITY_INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            if (session.localIdsPublicBundle == null) {
                throw new HostException("Local IDS public identities were not provided by the app");
            }
            return IdsMessageProtectionIdentity.publicBundleIdentifier(session.localIdsPublicBundle);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HostException("Interrupted while loading IDS installation identity", interrupted);
        } catch (IllegalArgumentException invalid) {
            throw new HostException("Invalid IDS installation identity from app", invalid);
        }
    }

    void persistPeerSpsMetadata(IdsSpsCompanionInfo info) throws Exception {
        byte[] metadata = info.serialize();
        byte[] previous = session.activePairingSessionRecord.peerSpsMetadata();
        try {
            if (Arrays.equals(metadata, previous)) {
                log("IDS SPS METADATA: identical authenticated metadata already stored; values logged=false.");
                return;
            }
            PairingSessionRecord checkpoint = session.activePairingSessionRecord.withPeerSpsMetadata(metadata);
            boolean stored = false;
            try {
                session.persistAndReplaceActivePairingSession(checkpoint);
                stored = true;
                log("IDS SPS METADATA CHECKPOINT PASS: " + info.summary()
                        + "; pairing/setup state unchanged; values logged=false.");
            } finally { if (!stored) checkpoint.destroy(); }
        } finally { HalTransportSession.wipe(metadata); HalTransportSession.wipe(previous); }
    }

    void sendIdsDeviceInfo(byte[] payload, boolean response, int remoteCid) throws Exception {
        try (NormalLinkIdsSessionBridge.Output sent = runner.activeIdsBridge.sendApplicationData(IdsDeviceInfoExchange.TOPIC, payload)) {
            if (response) {
                for (IdsModernSessionCoordinator.SessionEvent event : sent.idsEvents()) {
                    if (event.type == IdsModernSessionCoordinator.EventType.DATA_SENT) {
                        runner.idsDeviceInfoExchange.onResponseQueued(event.sequence);
                    }
                }
            }
            log("IDS DEVICE INFO TX: command=" + (response ? 12 : 11)
                    + "; public identities and correlation values logged=false.");
            runner.processModernIdsOutput(sent, remoteCid, "NORMAL IDS DEVICE INFO");
        }
    }

    void maybeProgressIdsDeviceInfo(int remoteCid) throws Exception {
        if (runner.idsDeviceInfoExchange == null || runner.activeIdsBridge == null || runner.activeIdsBootstrap == null
                || !runner.activeIdsBootstrap.ready() || !runner.activeIdsBridge.dataLanesReadyForApplication()) return;
        runner.publishHealthPeerIdentity();
        if (IdsNanoSetupPacer.isTransportQuiet(runner.activeIdsBridge.ertmOutstandingCount(), runner.activeIdsBridge.tcpUnacknowledgedSendBytes())) {
            byte[] request = runner.idsDeviceInfoExchange.begin();
            if (request != null) {
                try { sendIdsDeviceInfo(request, false, remoteCid); }
                finally { HalTransportSession.wipe(request); }
            }
        }
        if (runner.idsDeviceInfoExchange.ready() && !session.activePairingSessionRecord.hasExchangedIdsDeviceInfo()) {
            PairingSessionRecord checkpoint = session.activePairingSessionRecord.markIdsDeviceInfoExchanged();
            session.persistAndReplaceActivePairingSession(checkpoint);
            log("IDS DEVICE INFO CHECKPOINT PASS: authenticated peer identities received; local identities delivered. Initial Check may start when the transport is quiet.");
        }
        if (deviceInfoAllowsPostCommitResume()) {
            session.postCommit.startPostCommitIfAuthorized(remoteCid);
        }
    }

    boolean deviceInfoAllowsPostCommitResume() {
        if (runner.idsDeviceInfoExchange == null) return false;
        if (runner.idsDeviceInfoExchange.ready()) return true;
        if (runner.activeIdsBridge == null || session.activePairingSessionRecord == null) return false;
        return IdsPairedReconnectPolicy.allowSavedDeviceInfoForPostCommit(
                session.activePairingSessionRecord.state(),
                session.activePairingSessionRecord.hasExchangedIdsDeviceInfo(),
                session.freshPairingPerformed,
                runner.activeIdsBridge.ids().peerHelloMatchesIdentity(session.activePairingSessionRecord.peerIdsDeviceId()),
                runner.activeIdsBridge.dataLanesReadyForApplication());
    }

    void maybeFlushPendingNanoSetup(
            int remoteCid)
            throws Exception {
        if (!runner.pendingNanoSetup
                || session.postCommit.activeInitialSetup != null
                || runner.activeIdsBridge == null) {
            return;
        }
        maybeProgressIdsDeviceInfo(remoteCid);
        // Ping is a diagnostic, not a prerequisite for sending Check. The
        // 0.2.146 gate disconnected healthy IDS links before setup began.
        if (runner.idsDeviceInfoExchange == null || !runner.idsDeviceInfoExchange.ready()) return;
        int outstanding =
                runner.activeIdsBridge.ertmOutstandingCount();
        long tcpUna =
                runner.activeIdsBridge.tcpUnacknowledgedSendBytes();
        long elapsedMs =
                runner.pendingNanoSetupAtMs < 0
                        ? 0L
                        : BridgeClock.elapsedRealtime()
                                - runner.pendingNanoSetupAtMs;
        boolean ertmQuiet =
                IdsNanoSetupPacer.isErtmQuiet(
                        outstanding);
        runner.activeIdsBridge.setPayloadFastRetransmitEnabled(
                ertmQuiet);
        boolean quiet =
                IdsNanoSetupPacer.isTransportQuiet(
                        outstanding,
                        tcpUna);
        if (!IdsNanoSetupPacer.shouldSendCheck(
                quiet,
                elapsedMs)) {
            if (elapsedMs >= 1_000L
                    && (runner.lastNanoSetupWaitLogMs < 0
                    || elapsedMs - runner.lastNanoSetupWaitLogMs >= 1_000L)) {
                runner.lastNanoSetupWaitLogMs = elapsedMs;
                log("INITIAL NANO SETUP WAIT: still holding Check; "
                        + "ERTM outstanding="
                        + outstanding
                        + " tcpUna="
                        + tcpUna
                        + " streams="
                        + runner.activeIdsBridge.tcpUnacknowledgedSendSummary()
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
        runner.pendingNanoSetup = false;
        runner.activeIdsBridge.setPayloadFastRetransmitEnabled(
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
                session.postCommit.activateInitialSetup();
        for (AppleWatchInitialSetupIdsAdapter.Action action : actions) {
            session.postCommit.executeInitialSetupAction(
                    action,
                    remoteCid);
        }
        if (!session.postCommit.earlyInitialEvents.isEmpty()) {
            List<IdsModernSessionCoordinator.SessionEvent> early = new ArrayList<>(session.postCommit.earlyInitialEvents);
            session.postCommit.earlyInitialEvents.clear();
            log("[WatchHal] replaying " + early.size() + " owned early setup events");
            try {
                for (IdsModernSessionCoordinator.SessionEvent event : early) {
                    for (AppleWatchInitialSetupIdsAdapter.Action action : session.postCommit.activeInitialSetup.accept(event)) {
                        session.postCommit.executeInitialSetupAction(action, remoteCid);
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
    void holdControlSynForStaleTcp(
            int localCid,
            int remoteCid)
            throws Exception {
        log("IDS: holding the control SYN 5s to acknowledge and finish a stale Watch TCP flow.");
        long deadline = BridgeClock.elapsedRealtime() + 5_000L;
        while (BridgeClock.elapsedRealtime() < deadline) {
            try {
                runner.acceptModernIdsFrame(
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

    void executeIdsBootstrapAction(
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
                        runner.activeIdsBridge);
        runner.processModernIdsOutput(
                output,
                remoteCid,
                "NORMAL IDS TX");
    }


}
