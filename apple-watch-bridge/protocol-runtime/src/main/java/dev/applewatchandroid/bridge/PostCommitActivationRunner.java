package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Initial setup, MiniStore registry sync, two-way preferences, activation proxy/Albert flow,
 * durable setup checkpoints, and post-commit PairedSync/Clock orchestration.
 */
final class PostCommitActivationRunner {
    private final HalTransportSession session;
    AppleWatchInitialSetupIdsAdapter activeInitialSetup;
    final List<IdsModernSessionCoordinator.SessionEvent> earlyInitialEvents = new ArrayList<>();
    AppleWatchPostCommitCoordinator activePostCommitCoordinator;
    AppleWatchPostCommitIdsAdapter activePostCommitAdapter;
    final PostCommitDeliveryQueue pendingPostCommitSends = new PostCommitDeliveryQueue();
    MobileActivationHttpProxy activeActivationProxy;
    ActivationProxyWorker activationProxyWorker;
    long activationPermitSentAtMs = -1L;
    long activationPermitQueuedAtMs = -1L;
    int activationPermitL2capFrames;
    boolean activationPermitAwaitingFlush;
    int activationPermitResendCount;
    boolean watchDrivenActivationObserved;
    boolean watchProxyActivationObserved;
    IdsModernSessionCoordinator.SessionEvent pendingWatchProxyActivation;
    byte[] pendingActivationPayload;
    MobileActivationHttpProxy.ProxyResponse pendingActivationResponse;
    boolean postCommitCompleteLogged;
    long postCommitDeferredLogAtMs = -1L;
    boolean resumedPhoneRegistrySnapshotSent;
    final ArrayDeque<byte[]> pendingTwoWayPreferences = new ArrayDeque<>();
    boolean preferenceMirrorDeferredLogged;
    boolean readyToCommitIsPaired;
    boolean initialPropertiesReceived;
    boolean syncProgressAutoSent;
    SetupProgressState.Phase publishedSetupPhase;

    private final PostCommitCheckpointer checkpointer;
    private final PostCommitOperatorPoller operatorPoller;

    PostCommitActivationRunner(HalTransportSession session) {
        this.session = session;
        this.checkpointer = new PostCommitCheckpointer(session, this);
        this.operatorPoller = new PostCommitOperatorPoller(session, this);
    }

    void cleanup() {
        byte[] pendingPreference;
        while ((pendingPreference = pendingTwoWayPreferences.pollFirst()) != null) {
            HalTransportSession.wipe(pendingPreference);
        }
        if (activeInitialSetup != null) {
            activeInitialSetup.close();
            activeInitialSetup = null;
        }
        earlyInitialEvents.forEach(IdsModernSessionCoordinator.SessionEvent::close);
        earlyInitialEvents.clear();
        pendingPostCommitSends.close();
        if (activePostCommitAdapter != null) {
            try {
                activePostCommitAdapter.close();
            } catch (RuntimeException error) {
                log("CLEANUP POST-COMMIT WARNING: " + safeMessage(error));
            }
            activePostCommitAdapter = null;
        }
        if (pendingActivationPayload != null) {
            HalTransportSession.wipe(pendingActivationPayload);
            pendingActivationPayload = null;
        }
        if (pendingActivationResponse != null) {
            pendingActivationResponse.destroy();
            pendingActivationResponse = null;
        }
        if (activationProxyWorker != null) {
            activationProxyWorker.close();
            activationProxyWorker = null;
        }
        activeActivationProxy = null;
        initialPropertiesReceived = false;
        readyToCommitIsPaired = false;
        watchProxyActivationObserved = false;
        if (pendingWatchProxyActivation != null) {
            pendingWatchProxyActivation.close();
            pendingWatchProxyActivation = null;
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
    void startPostCommitIfAuthorized(
            int remoteCid)
            throws Exception {
        if (session.operationalPairingId != null) {
            if (!session.idsSession.operationalSnapshotPublished && session.idsSession.activeIdsBridge != null
                    && session.idsSession.idsControlReadyObserved && session.idsSession.activeIdsBridge.dataLanesReadyForApplication()) {
                OperationalSessionPolicy.requireMatchingActivatedPair(session.activePairingSessionRecord, session.operationalPairingId);
                session.idsSession.operationalSnapshotPublished = true;
                publishResumedPhoneRegistrySnapshot(remoteCid);
                UUID epoch = OPERATIONAL_REQUESTS.newEpoch();
                session.idsSession.clockFaceObservationEpoch = epoch;
                session.idsSession.findMyPhone.reset(epoch);
                session.idsSession.pendingPhoneReplies.clear();
                FIND_MY_PHONE_RESULTS.clear();
                System.out.println("BRIDGE_TRANSPORT_V1:READY:" + epoch);
                System.out.flush();
                session.idsSession.publishClockFaceObservation(epoch);
                try { session.idsSession.publishPigmentMirror(); }
                catch (IOException invalidMirror) { log("PIGMENT MIRROR unavailable; no baseline seeded; values logged=false."); }
                try { session.idsSession.publishMonogramMirror(); }
                catch (IOException invalidMirror) { log("MONOGRAM MIRROR unavailable; values logged=false."); }
                log("OPERATIONAL IDS READY: same activated pair; Setup/Albert/Buddy replay disabled.");
                try {
                    session.idsSession.softwareUpdate = new SoftwareUpdateSession(UUID.fromString(session.operationalPairingId), epoch,
                            session.idsSession.activeIdsBridge.observedPeerProductVersion(), session.idsSession.activeIdsBridge.observedPeerBuildVersion(), BridgePaths.files());
                    session.idsSession.publishSoftwareUpdate();
                    offerOutbound(session.outboundAppMessages, new OutboundAppMessage(SoftwareUpdateCodec.TOPIC, 0,
                            SoftwareUpdateSession.QUERY_COMMAND.getBytes(java.nio.charset.StandardCharsets.US_ASCII), true));
                } catch (IOException | IllegalArgumentException unavailable) {
                    log("SOFTWARE UPDATE unavailable: authenticated system version/checkpoint required.");
                }
            }
            return;
        }
        if (!session.commitIsPairedAuthorized
                || activePostCommitCoordinator != null
                || session.idsSession.activeIdsBridge == null
                || session.activePairingSessionRecord == null) {
            return;
        }
        if (!session.idsSession.deviceInfoAllowsPostCommitResume()) return;
        if (session.activePairingSessionRecord.state().wireValue()
                < PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED.wireValue()) {
            return;
        }
        if (session.activePairingSessionRecord.state().wireValue()
                >= PairingSessionRecord.DurableState.OPERATIONAL_HEALTH_CONFIRMED.wireValue()) {
            if (!session.commitIsPairedAuthorized) {
                log("POST-COMMIT SKIPPED: Watch is already OPERATIONAL_HEALTH_CONFIRMED; operational hold active.");
                return;
            }
            log("POST-COMMIT RESUME: restoring completed record; use initial-sync redrive if the physical Watch remains in Setup.");
        }
        IdsModernSessionCoordinator.Snapshot ids =
                session.idsSession.activeIdsBridge.idsSnapshot();
        boolean alreadyCommitted =
                session.activePairingSessionRecord.state().wireValue()
                        >= PairingSessionRecord.DurableState
                        .IS_PAIRED_COMMITTED.wireValue();
        boolean lanesReady = session.idsSession.activeIdsBridge.dataLanesReadyForApplication()
                || (alreadyCommitted && ids.joinedDataCount > 0);
        boolean controlReady = session.idsSession.idsControlReadyObserved || ids.peerHelloReceived
                || (session.idsSession.canResumeIdsData() && session.idsSession.idsBootstrapBarrierPassed && lanesReady);
        if ((!session.idsSession.idsBootstrapBarrierPassed && !lanesReady)
                || !controlReady) {
            // The Watch resets any service connector opened before its IDS
            // control Hello, so sending now would only fill the parked queue.
            // The bootstrap barrier alone is not enough: it reports control
            // readiness without waiting for the peer Hello.
            return;
        }
        session.idsSession.idsBootstrapBarrierPassed = true;
        if (!initialPropertiesReceived
                && !watchProxyActivationObserved
                && (!alreadyCommitted
                        || session.activePairingSessionRecord.state().wireValue()
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
                        + session.activePairingSessionRecord.state()
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
                    + session.idsSession.idsControlReadyObserved
                    + " joinedData="
                    + ids.joinedDataCount
                    + " lanesReady="
                    + lanesReady
                    + "; CanBeginActivation stays off the wire until Class-D and Class-C NWSC are active.");
            return;
        }
        if (!session.idsSession.idsDeviceInfoExchange.ready()) {
            log("POST-COMMIT IDS CREDENTIALS RESUME: saved mutual exchange retained; current authenticated Hello matches the same Watch and fresh data lanes are ready. Fresh command-12 response remains unobserved.");
        }
        Integer watchPairingVersion = session.activeWatchSetupMetadata != null
                ? session.activeWatchSetupMetadata.pairingVersion : session.idsSession.activeIdsBridge.observedPeerMaxPairingVersion();
        if (watchPairingVersion == null) return;
        log("COMMIT IS PAIRED AUTHORIZED: activating AppleWatchPostCommitCoordinator "
                + "and MobileActivationHttpProxy; starting post-commit setup & activation.");
        if (session.activePairingSessionRecord.state()
                == PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED) {
            PairingSessionRecord prepared = null;
            PairingSessionRecord committed = null;
            try {
                prepared = session.activePairingSessionRecord.isPairedCommitIntentPersisted()
                        ? null
                        : session.activePairingSessionRecord.prepareIsPairedCommit(true);
                PairingSessionRecord source = prepared != null ? prepared : session.activePairingSessionRecord;
                committed = source.confirmIsPairedCommit(true);
                session.persistAndReplaceActivePairingSession(committed);
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
        PairingSessionRecord.DurableState coordinatorStartState = session.activePairingSessionRecord.state();
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
                        session.activePairingSessionRecord.transitionCounter(),
                        coordinatorStartState,
                        session.activePairingSessionRecord.isPairedCommitIntentPersisted(),
                        watchPairingVersion);
        session.idsSession.activeIdsBridge.setPayloadFastRetransmitEnabled(
                false);
        activePostCommitAdapter =
                new AppleWatchPostCommitIdsAdapter(
                        activePostCommitCoordinator,
                        session.idsSession.activeIdsBridge);
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
        session.idsSession.modernIdsReady = true;
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
    void driveActivationFallbackIfStalled(
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
                && session.idsSession.activeIdsBridge != null) {
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
                session.idsSession.markActivationPermitOnWireIfFlushed();
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

    List<AppleWatchInitialSetupIdsAdapter.Action> activateInitialSetup()
            throws Exception {
        if (activeInitialSetup != null) {
            return List.of();
        }
        checkpointIdsDataReady();
        if (session.idsSession.activeIdsBridge == null
                || session.activePairingSessionRecord == null
                || session.activePairingSessionRecord.state().wireValue()
                < PairingSessionRecord.DurableState
                .IDS_DATA_READY.wireValue()) {
            throw new HostException(
                    "Initial NanoRegistry setup evidence is incomplete");
        }
        try {
            Integer observedVersion = session.idsSession.activeIdsBridge.observedPeerMaxPairingVersion();
            boolean advertised = session.activeWatchSetupIdentifier != null && session.activeWatchSetupMetadata != null;
            if (!advertised && observedVersion == null) return List.of();
            int peerMaxPairingVersion = observedVersion != null
                    ? observedVersion : session.activeWatchSetupMetadata.pairingVersion;
            AppleWatchInitialSetupIdsAdapter adapter = advertised
                    ? new AppleWatchInitialSetupIdsAdapter(session.activePairingSessionRecord.transitionCounter(),
                            session.activeWatchSetupIdentifier, session.activeWatchSetupMetadata, peerMaxPairingVersion)
                    : new AppleWatchInitialSetupIdsAdapter(session.activePairingSessionRecord.transitionCounter(),
                            session.activePairingSessionRecord.peerProductType(), peerMaxPairingVersion);
            adapter.useProfile(session.companionProfile());
            List<AppleWatchInitialSetupIdsAdapter.Action> actions =
                    adapter.startAfterIdsDataReady();
            activeInitialSetup =
                    adapter;
            session.idsSession.modernIdsReady = true;
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
    void answerLivePropertyRequest(
            IdsModernSessionCoordinator.SessionEvent event,
            int remoteCid)
            throws Exception {
        if (event == null
                || activeInitialSetup != null
                || session.idsSession.activeIdsBridge == null
                || session.localIdentity == null
                || event.type != IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                || !NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                || event.protobufType != NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST
                || event.response
                || event.messageUuid == null
                || event.messageUuid.isBlank()) {
            return;
        }
        byte[] bluetoothMacAddress = session.localIdentity.address();
        NanoRegistryPropertyCodec.PropertyResponse message = null;
        try {
            message = AppleWatchInitialSetupIdsAdapter.Action
                    .livePhonePropertyResponse(bluetoothMacAddress, session.companionProfile());
            try (NormalLinkIdsSessionBridge.Output sent =
                         session.idsSession.activeIdsBridge.sendClassCResponse(
                                 message,
                                 event.messageUuid)) {
                session.idsSession.processModernIdsOutput(
                        sent,
                        remoteCid,
                        "LIVE PROPERTY RESPONSE");
            }
            log("LIVE PROPERTY RESPONSE TX: Class-C type=4 echoing the Watch "
                    + "PropertyRequest; identifier logged=false.");
        } finally {
            HalTransportSession.wipe(bluetoothMacAddress);
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
    void publishResumedPhoneRegistrySnapshot(int remoteCid)
            throws Exception {
        if (resumedPhoneRegistrySnapshotSent
                || activeInitialSetup != null
                || session.idsSession.activeIdsBridge == null
                || session.localIdentity == null
                || session.activePairingSessionRecord == null
                || session.activePairingSessionRecord.state().wireValue()
                < PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()) {
            return;
        }
        resumedPhoneRegistrySnapshotSent = true;
        byte[] bluetoothMacAddress = session.localIdentity.address();
        NanoRegistryPropertyCodec.PropertiesChanged snapshot = null;
        try {
            snapshot = AppleWatchInitialSetupIdsAdapter.Action.livePhoneFullSnapshot(
                    System.currentTimeMillis(),
                    bluetoothMacAddress, session.companionProfile());
            try (NormalLinkIdsSessionBridge.Output sent =
                         session.idsSession.activeIdsBridge.sendClassC(snapshot)) {
                session.idsSession.processModernIdsOutput(
                        sent,
                        remoteCid,
                        "RESUMED PHONE SNAPSHOT");
            }
            log("RESUMED PHONE SNAPSHOT TX: Class-C type=2 phone MiniStore "
                    + "republished on the paired link.");
        } finally {
            HalTransportSession.wipe(bluetoothMacAddress);
            if (snapshot != null) {
                snapshot.destroy();
            }
        }
        NanoRegistryPropertyCodec.PropertyRequest request =
                new NanoRegistryPropertyCodec.PropertyRequest();
        try (NormalLinkIdsSessionBridge.Output sent =
                     session.idsSession.activeIdsBridge.sendClassC(request)) {
            session.idsSession.processModernIdsOutput(
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
    void publishStoredTwoWayPreferences(int remoteCid) {
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

    void pumpStoredTwoWayPreferences(int remoteCid) {
        if (pendingTwoWayPreferences.isEmpty()
                || session.idsSession.activeIdsBridge == null) {
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
        if (session.idsSession.activeIdsBridge.hasDeferredIpv6()
                || !IdsNanoSetupPacer.isTransportQuiet(
                session.idsSession.activeIdsBridge.ertmOutstandingCount(),
                session.idsSession.activeIdsBridge.tcpUnacknowledgedSendBytes())) {
            return;
        }
        preferenceMirrorDeferredLogged = false;
        byte[] payload = pendingTwoWayPreferences.pollFirst();
        try {
            try (NormalLinkIdsSessionBridge.Output sentOut =
                         session.idsSession.activeIdsBridge.sendApplicationProtobuf(
                                 IdsApplicationRoute.PREFERENCE_SYNC_SERVICE,
                                 PairedSyncCodec.PROTOBUF_TYPE_USER_DEFAULTS,
                                 payload)) {
                session.idsSession.processModernIdsOutput(
                        sentOut,
                        remoteCid,
                        "PAIREDSYNC MIRROR");
            }
            log("PAIREDSYNC MIRROR TX: remaining="
                    + pendingTwoWayPreferences.size());
        } catch (Exception failure) {
            log("PAIREDSYNC MIRROR FAILED: " + safeMessage(failure));
        } finally {
            HalTransportSession.wipe(payload);
        }
    }

    void executeInitialSetupAction(
            AppleWatchInitialSetupIdsAdapter.Action action,
            int remoteCid)
            throws Exception {
        if (action == null
                || session.idsSession.activeIdsBridge == null
                || activeInitialSetup == null) {
            throw new HostException(
                    "Initial NanoRegistry action lost its live session");
        }
        NormalLinkIdsSessionBridge.Output output;
        String label;
        switch (action.type) {
            case SEND_COMPATIBILITY_STATE -> {
                NanoRegistryClassDCodec.PairingModeRequest message =
                        action.compatibilityMessage(session.companionProfile());
                try {
                    output =
                            session.idsSession.activeIdsBridge.sendClassD(
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
                if (session.localIdentity == null) {
                    throw new HostException(
                            "Phone Class-C snapshot needs the live "
                                    + "static-random Bluetooth address");
                }
                byte[] bluetoothMacAddress =
                        session.localIdentity.address();
                NanoRegistryPropertyCodec.PropertiesChanged message;
                try {
                    message =
                            action.phoneFullSnapshot(
                                    System.currentTimeMillis(),
                                    bluetoothMacAddress, session.companionProfile());
                } finally {
                    HalTransportSession.wipe(bluetoothMacAddress);
                }
                try {
                    output =
                            session.idsSession.activeIdsBridge.sendClassC(
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
                if (session.localIdentity == null) {
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
                        session.localIdentity.address();
                NanoRegistryPropertyCodec.PropertyResponse message;
                try {
                    message =
                            action.propertyResponse(
                                    bluetoothMacAddress, session.companionProfile());
                } finally {
                    HalTransportSession.wipe(bluetoothMacAddress);
                }
                try {
                    output =
                            session.idsSession.activeIdsBridge.sendClassCResponse(
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
                    output = session.idsSession.activeIdsBridge.sendClassC(message);
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
        session.idsSession.processModernIdsOutput(
                output,
                remoteCid,
                label);
    }

    boolean readyForCleanStop() {
        return readyToCommitIsPaired;
    }

    void checkpointOrdinaryRegistration(NormalLinkPipeSession pipe) throws Exception {
        checkpointer.checkpointOrdinaryRegistration(pipe);
    }

    void checkpointIdsControlReady() throws Exception {
        checkpointer.checkpointIdsControlReady();
    }

    void checkpointIdsDataReady() throws Exception {
        checkpointer.checkpointIdsDataReady();
    }

    void checkpointInitialPropertiesReceived(int remoteCid) throws Exception {
        checkpointer.checkpointInitialPropertiesReceived(remoteCid);
    }

    void checkpointReadyToCommitIsPaired(int remoteCid) throws Exception {
        checkpointer.checkpointReadyToCommitIsPaired(remoteCid);
    }

    void submitPostCommitSend(
            AppleWatchPostCommitIdsAdapter.PreparedSend send,
            AppleWatchPostCommitCoordinator.Action action,
            int remoteCid) throws Exception {
        boolean retained = false;
        try {
            int submittedFrames = send.outboundFrames().size();
            session.normalLink.sendNormalFrames(remoteCid, send.outboundFrames(), "POST-COMMIT " + action.type);
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

    void completePendingPostCommitSends(int remoteCid) throws Exception {
        if (session.idsSession.activeIdsBridge == null || activePostCommitAdapter == null) return;
        List<AppleWatchPostCommitCoordinator.Action> actions =
                pendingPostCommitSends.completeDrained(
                        session.idsSession.activeIdsBridge.hasPendingApplicationFrames(),
                        session.idsSession.activeIdsBridge.hasDeferredIpv6());
        session.idsSession.markActivationPermitOnWireIfFlushed();
        executePostCommitActions(actions, remoteCid);
    }

    void pollActivationProxy(int remoteCid) throws Exception {
        operatorPoller.pollActivationProxy(remoteCid);
    }

    void pollSyncProgress(int remoteCid) throws Exception {
        operatorPoller.pollSyncProgress(remoteCid);
    }

    void pollPublishPairedSync(int remoteCid) throws Exception {
        operatorPoller.pollPublishPairedSync(remoteCid);
    }

    void pollForceActivationConfirmed(int remoteCid) throws Exception {
        operatorPoller.pollForceActivationConfirmed(remoteCid);
    }

    void pollForceSetupObserved(int remoteCid) throws Exception {
        operatorPoller.pollForceSetupObserved(remoteCid);
    }

    void pollRedriveActivation(int remoteCid) throws Exception {
        operatorPoller.pollRedriveActivation(remoteCid);
    }

    void pollRetryActivation(int remoteCid) throws Exception {
        operatorPoller.pollRetryActivation(remoteCid);
    }

    void pollActivationChallenge(int remoteCid) throws Exception {
        operatorPoller.pollActivationChallenge(remoteCid);
    }

    void publishSetupProgress() {
        if (session.operationalPairingId != null || activePostCommitCoordinator == null) return;
        SetupProgressState.Phase phase = SetupProgressState.postCommit(activePostCommitCoordinator.snapshot());
        if (phase == publishedSetupPhase) return;
        publishedSetupPhase = phase;
        System.out.println(SetupProgressState.encode(phase));
        System.out.flush();
    }

    void executePostCommitActions(
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
                    if (session.commitIsPairedAuthorized) {
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
                    if (session.activePairingSessionRecord.state() == PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED
                            && !session.activePairingSessionRecord.isPairedCommitIntentPersisted()) {
                        PairingSessionRecord next =
                                session.activePairingSessionRecord.prepareIsPairedCommit(true);
                        session.persistAndReplaceActivePairingSession(next);
                        log("POST-COMMIT: IsPaired write-ahead commit intent persisted atomically.");
                    }
                    queue.addAll(
                            activePostCommitCoordinator.onCommitIntentPersisted(gen, true));
                }
                case PERSIST_DURABLE_STATE -> {
                    if (action.durableState.wireValue() > session.activePairingSessionRecord.state().wireValue()) {
                        PairingSessionRecord next =
                                action.durableState == PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED
                                        ? session.activePairingSessionRecord.confirmIsPairedCommit(true)
                                        : session.activePairingSessionRecord.advanceObservedSetupTo(action.durableState);
                        session.persistAndReplaceActivePairingSession(next);
                    }
                    log("POST-COMMIT CHECKPOINT PASS: state=" + action.durableState);
                    queue.addAll(
                            activePostCommitCoordinator.onDurableStatePersisted(gen, action.durableState, true));
                }
                case PERSIST_ACTIVATION_REDRIVE -> {
                    boolean redrivePersisted = false;
                    try {
                        if (session.activePairingSessionRecord != null
                                && session.activePairingSessionRecord.state().wireValue()
                                        >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()) {
                            PairingSessionRecord next =
                                    session.activePairingSessionRecord.redriveActivationCheckpoint();
                            session.persistAndReplaceActivePairingSession(next);
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
                            session.idsSession.activeIdsBridge.hasPendingApplicationFrames(),
                            session.idsSession.activeIdsBridge.hasDeferredIpv6()));
                    session.idsSession.markActivationPermitOnWireIfFlushed();
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
                        HalTransportSession.wipe(pendingActivationPayload);
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
                                    session.idsSession.activeIdsBridge.hasPendingApplicationFrames(),
                                    session.idsSession.activeIdsBridge.hasDeferredIpv6()));
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
            session.idsSession.persistStaleFlowObservations(
                    "post-commit complete");
            System.out.println("EVENT:SETUP_COMPLETE:operational");
            System.out.flush();
        }
    }

    AppleWatchPostCommitCoordinator.OperationalHealthEvidence evaluateOperationalHealthEvidence() {
        return checkpointer.evaluateOperationalHealthEvidence();
    }

    String operationalHealthGaps(AppleWatchPostCommitCoordinator.OperationalHealthEvidence evidence) {
        return checkpointer.operationalHealthGaps(evidence);
    }

    String addressProjection(String phase, NormalLinkPipeSession pipe) {
        return checkpointer.addressProjection(phase, pipe);
    }

    static String hostPair(byte[] local, byte[] remote) {
        return PostCommitCheckpointer.hostPair(local, remote);
    }

    static String hostSuffix(byte[] address) {
        return PostCommitCheckpointer.hostSuffix(address);
    }

    boolean setupCompleted() {
        return activePostCommitCoordinator != null
                && activePostCommitCoordinator.snapshot().phase == AppleWatchPostCommitCoordinator.Phase.COMPLETE;
    }
}
