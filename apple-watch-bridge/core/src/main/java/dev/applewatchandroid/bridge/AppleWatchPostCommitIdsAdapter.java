package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Binds post-commit setup actions to one live IDS/normal-link lifetime.
 *
 * <p>Preparing a send only produces encrypted ERTM frames. The caller must
 * deliver every returned frame and then explicitly call
 * {@link PreparedSend#complete(boolean)}. Merely constructing a socket-pair
 * message is never reported to the setup coordinator as transport success.</p>
 *
 * <p>The owned transport, and therefore its controller-scoped application
 * sequence allocator, is closed with this adapter. Watch-local IsSetup,
 * paired-sync, Carousel, and reconnect evidence remain external observations;
 * this class does not infer them from a send callback.</p>
 */
final class AppleWatchPostCommitIdsAdapter
        implements AutoCloseable {
    private final AppleWatchPostCommitCoordinator coordinator;
    private final long generation;
    private final List<PreparedSend> pending =
            new ArrayList<>();
    private final Set<DispatchKey> dispatched =
            new HashSet<>();

    private Transport transport;
    private String expectedPairedSyncAppAckTopic;
    private String expectedPairedSyncAppAckIdentifier;
    private boolean pairedSyncAppAckObserved;
    private String pairingModeNormalRequestIdentifier;
    /** Watch ProxyActivation message UUID when that fetch expects an IDS reply. */
    private String activationFetchReplyTo;
    private final PairedSyncPeerProgress peerSyncProgress = new PairedSyncPeerProgress();
    private boolean closed;

    AppleWatchPostCommitIdsAdapter(
            AppleWatchPostCommitCoordinator coordinator,
            NormalLinkIdsSessionBridge bridge) {
        this(
                coordinator,
                new LiveTransport(
                        bridge));
    }

    AppleWatchPostCommitIdsAdapter(
            AppleWatchPostCommitCoordinator coordinator,
            Transport transport) {
        if (coordinator == null
                || transport == null) {
            throw new IllegalArgumentException(
                    "Post-commit coordinator and IDS transport are required");
        }
        AppleWatchPostCommitCoordinator.Snapshot snapshot =
                coordinator.snapshot();
        if (snapshot.generation <= 0
                || snapshot.phase
                == AppleWatchPostCommitCoordinator.Phase.IDLE) {
            throw new IllegalArgumentException(
                    "Post-commit generation has not started");
        }
        this.coordinator =
                coordinator;
        this.transport =
                transport;
        generation =
                snapshot.generation;
    }

    synchronized PreparedSend prepareSimple(
            AppleWatchPostCommitCoordinator.Action action) {
        requireUsable();
        AppleWatchPostCommitCoordinator.Action checked =
                requireAction(
                        action);
        if (checked.type
                == AppleWatchPostCommitCoordinator.ActionType
                .SEND_PAIRING_MODE_NORMAL) {
            NanoRegistryClassDCodec.PairingModeRequest message =
                    checked.pairingModeNormalMessage();
            try {
                return dispatchClassD(
                        checked,
                        message);
            } finally {
                message.destroy();
            }
        }
        PbBridgeCodec.ApplicationMessage message;
        switch (checked.type) {
            case SEND_ACTIVATION_PERMIT ->
                    message =
                            new PbBridgeCodec.CanBeginActivation();
            case SEND_ACTIVATION_RETRY ->
                    message =
                            new PbBridgeCodec.RetryActivation();
            case SEND_NORMAL_AFTER_LANGUAGE_RELAUNCH,
                 SEND_PB_BRIDGE_NORMAL ->
                    message =
                            new PbBridgeCodec.UpdateNanoRegistryNormal();
            case SEND_PREPARE_INITIAL_SYNC ->
                    message =
                            new PbBridgeCodec.PrepareInitialSync();
            default -> throw new IllegalArgumentException(
                    "Post-commit action is not a simple IDS send");
        }
        return dispatchPbBridge(
                checked,
                message);
    }

    synchronized PreparedSend prepareComputedTimeZone(
            AppleWatchPostCommitCoordinator.Action action,
            String computedTimeZone) {
        requireType(
                action,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_COMPUTED_TIME_ZONE);
        return dispatchPbBridge(
                action,
                new PbBridgeCodec.ComputedTimeZone(
                        computedTimeZone));
    }

    synchronized PreparedSend prepareLanguageAndLocale(
            AppleWatchPostCommitCoordinator.Action action,
            List<String> appleLanguages,
            String appleLocale,
            byte[] archivedPreferences) {
        requireType(
                action,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_LANGUAGE_AND_LOCALE);
        PbBridgeCodec.LanguageAndLocale message =
                new PbBridgeCodec.LanguageAndLocale(
                        appleLanguages,
                        appleLocale,
                        archivedPreferences);
        try {
            return dispatchPbBridge(
                    action,
                    message);
        } finally {
            message.destroy();
        }
    }

    synchronized PreparedSend prepareActivationData(
            AppleWatchPostCommitCoordinator.Action action,
            MobileActivationHttpProxy.ProxyResponse response) {
        if (response == null) {
            throw new IllegalArgumentException(
                    "Activation proxy response is absent");
        }
        requireType(
                action,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_DATA);
        PbBridgeCodec.ActivationData message =
                response.toPbBridgeMessage();
        try {
            return dispatchActivationData(
                    action,
                    message);
        } finally {
            message.destroy();
        }
    }

    synchronized PreparedSend prepareActivationData(
            AppleWatchPostCommitCoordinator.Action action,
            byte[] activationData,
            byte[] archivedResponseHeaders) {
        requireType(
                action,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_DATA);
        PbBridgeCodec.ActivationData message =
                new PbBridgeCodec.ActivationData(
                        activationData,
                        archivedResponseHeaders);
        try {
            return dispatchActivationData(
                    action,
                    message);
        } finally {
            message.destroy();
        }
    }

    private PreparedSend dispatchActivationData(
            AppleWatchPostCommitCoordinator.Action action,
            PbBridgeCodec.ActivationData message) {
        String replyTo =
                activationFetchReplyTo;
        activationFetchReplyTo =
                null;
        if (replyTo == null) {
            return dispatchPbBridge(
                    action,
                    message);
        }
        DispatchKey key =
                beginDispatch(
                        action);
        DispatchOutput output =
                null;
        try {
            output =
                    transport.sendPbBridgeReplyToFetch(
                            message,
                            replyTo);
            return register(
                    action,
                    key,
                    output,
                    PbBridgeCodec.SERVICE,
                    message.protobufType(),
                    message.response());
        } catch (RuntimeException failure) {
            closeQuietly(
                    output);
            throw dispatchFailed(
                    action,
                    key,
                    failure);
        }
    }

    synchronized PreparedSend preparePairedSyncCompletion(
            AppleWatchPostCommitCoordinator.Action action,
            double appleReferenceTimestamp) {
        requireType(
                action,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PAIRED_SYNC_COMPLETION);
        PairedSyncCodec.UserDefaultsMessage message =
                PairedSyncCodec.initialSyncCompletion(
                        appleReferenceTimestamp);
        try {
            return dispatchPairedSync(action, message, appleReferenceTimestamp);
        } finally {
            message.destroy();
        }
    }

    /** Prepare the second completion message; its frames must travel with PSY. */
    private DispatchOutput prepareNanoPrefSyncInitialSyncKey(double timestamp) {
        PairedSyncCodec.UserDefaultsMessage message =
                PairedSyncCodec.nanoPrefSyncInitialSyncCompletion(timestamp);
        try {
            return transport.sendPairedSync(message);
        } finally {
            message.destroy();
        }
    }

    private DispatchOutput completionOutputs(DispatchOutput primary, double timestamp) {
        DispatchOutput initialSync = null;
        try {
            initialSync = prepareNanoPrefSyncInitialSyncKey(timestamp);
            // Buddy was opened before the beginning/prepare exchange. Reopening
            // it after PSY completion can lose the notification: 23S303's PSY
            // observer refreshes before installing its delegate, and the progress
            // controller's addObserver does not replay the cached state.
            return new CompletionDispatchOutput(primary, initialSync);
        } catch (RuntimeException failure) {
            closeQuietly(primary);
            closeQuietly(initialSync);
            throw failure;
        }
    }

    /**
     * Diagnostic republish: identical wire payload as
     * {@link #preparePairedSyncCompletion}, but bypasses the one-shot dispatch
     * guard and the coordinator bookkeeping. Used to probe whether the Watch
     * applies a repeated PairedSync completion on the live session; its AppAck
     * will surface as an unmatched/duplicate receipt in the log, which is the
     * intended signal.
     */
    synchronized DiagnosticSend prepareDiagnosticPairedSyncRepublish(
            double appleReferenceTimestamp) {
        requireUsable();
        requireCurrentGeneration();
        PairedSyncCodec.UserDefaultsMessage message =
                PairedSyncCodec.initialSyncCompletion(
                        appleReferenceTimestamp);
        DispatchOutput output =
                null;
        try {
            output =
                    transport.sendPairedSync(
                            message);
            SentMessage sent =
                    output.sentMessage();
            if (sent == null
                    || !PairedSyncCodec.isService(
                    sent.topic)
                    || (sent.flags
                    & IdsSocketPairCodec.FLAG_WANTS_APP_ACK) == 0) {
                throw new IllegalStateException(
                        "Diagnostic PairedSync republish must request an IDS AppAck");
            }
            output = completionOutputs(output, appleReferenceTimestamp);
            return new DiagnosticSend(
                    output,
                    sent.topic,
                    sent.sequence);
        } catch (RuntimeException failure) {
            closeQuietly(
                    output);
            throw failure;
        } finally {
            message.destroy();
        }
    }

    /**
     * Emits one phone-to-Watch PBBridge type-19 initial-sync progress/state
     * update outside the coordinator state machine.
     */
    synchronized DiagnosticSend prepareDiagnosticSyncProgress(
            double progress,
            int state) {
        requireUsable();
        requireCurrentGeneration();
        PbBridgeCodec.UpdateSyncProgress message =
                new PbBridgeCodec.UpdateSyncProgress(
                        progress,
                        state);
        DispatchOutput output =
                null;
        try {
            output = transport.sendPbBridge(message);
            SentMessage sent =
                    output.sentMessage();
            if (sent == null) {
                throw new IllegalStateException(
                        "Sync progress update was not dispatched");
            }
            return new DiagnosticSend(
                    output,
                    sent.topic,
                    sent.sequence);
        } catch (RuntimeException failure) {
            closeQuietly(
                    output);
            throw failure;
        }
    }

    /**
     * Consumes one received PBBridge protobuf event from the live IDS output.
     */
    synchronized InboundResult accept(
            IdsModernSessionCoordinator.SessionEvent event) {
        requireUsable();
        if (event == null) {
            return new InboundResult(
                    List.of(),
                    null,
                    0,
                    null);
        }
        if (event.type != IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                && event.type != IdsModernSessionCoordinator.EventType.DATA_RECEIVED) {
            return new InboundResult(
                    List.of(),
                    null,
                    0,
                    null);
        }
        byte[] payload =
                event.payload();
        if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                && (NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                || NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(event.topic)
                || PairedSyncCodec.isService(event.topic))) {
            try {
                return new InboundResult(consumeSetupObservation(event, payload), null, 0, null);
            } finally {
                wipe(payload);
            }
        }
        try (InboundMessage incoming =
                     new InboundMessage(
                             event.sequence,
                             event.streamId,
                             event.flags,
                             event.peerResponseIdentifier,
                             event.messageUuid,
                             event.topic,
                             event.protobufType,
                             event.response,
                             payload)) {
            return acceptPbBridge(
                    incoming);
        } catch (RuntimeException ignored) {
            return new InboundResult(
                    List.of(),
                    null,
                    0,
                    null);
        } finally {
            wipe(
                    payload);
        }
    }

    /**
     * Classifies an IDS AppAck without treating it as Watch-local apply
     * evidence. Apple gives the receipt a fresh sequence, so correlation is
     * by topic plus exact {@code peerResponseIdentifier} only.
     */
    synchronized AppAckReceipt acceptAppAck(
            IdsModernSessionCoordinator.SessionEvent event) {
        requireUsable();
        requireCurrentGeneration();
        if (event == null
                || event.type
                != IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED) {
            throw new IllegalArgumentException(
                    "Incoming IDS event is not an AppAck receipt");
        }
        if (expectedPairedSyncAppAckTopic == null
                || expectedPairedSyncAppAckIdentifier == null
                || !expectedPairedSyncAppAckTopic.equals(
                event.topic)
                || !expectedPairedSyncAppAckIdentifier.equals(
                event.peerResponseIdentifier)) {
            return AppAckReceipt.UNRELATED;
        }
        if (pairedSyncAppAckObserved) {
            return AppAckReceipt.PAIRED_SYNC_DUPLICATE;
        }
        pairedSyncAppAckObserved =
                true;
        return AppAckReceipt.PAIRED_SYNC_MATCHED;
    }

    synchronized boolean pairedSyncAppAckObserved() {
        requireUsable();
        requireCurrentGeneration();
        return pairedSyncAppAckObserved;
    }

    private List<AppleWatchPostCommitCoordinator.Action> consumeSetupObservation(
            IdsModernSessionCoordinator.SessionEvent event, byte[] payload) {
        List<AppleWatchPostCommitCoordinator.Action> actions = new ArrayList<>();
        if (NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(event.topic)
                && event.protobufType == NanoRegistryClassDCodec.TYPE_PAIRING_MODE
                && event.response
                && pairingModeNormalRequestIdentifier != null
                && pairingModeNormalRequestIdentifier.equals(event.peerResponseIdentifier)) {
            IdsSocketPairCodec.ProtobufMessage envelope = new IdsSocketPairCodec.ProtobufMessage(
                    event.sequence, event.streamId, event.flags, event.peerResponseIdentifier,
                    event.messageUuid, (event.flags & IdsSocketPairCodec.FLAG_HAS_TOPIC) != 0
                    ? event.topic : null, event.protobufType, true, payload, null);
            try {
                NanoRegistryClassDCodec.ApplicationMessage decoded = NanoRegistryClassDCodec.decode(envelope);
                try {
                    if (decoded instanceof NanoRegistryClassDCodec.PairingModeResponse response
                            && response.success) {
                        actions.addAll(coordinator.onCompatibilityStateObserved(generation,
                                AppleWatchPostCommitCoordinator.COMPATIBILITY_STATE_NORMAL));
                    }
                } finally {
                    decoded.destroy();
                }
            } finally {
                envelope.destroy();
            }
        } else if ((NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                || NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(event.topic))
                && (event.protobufType == NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                || (event.protobufType == NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST
                && event.response))) {
            NanoRegistryPropertyCodec.ApplicationMessage decoded =
                    event.protobufType == NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                            ? NanoRegistryPropertyCodec.decodePropertiesChanged(payload)
                            : NanoRegistryPropertyCodec.decodePropertyResponse(payload);
            try {
                List<NanoRegistryPropertyCodec.Property> properties =
                        decoded instanceof NanoRegistryPropertyCodec.PropertiesChanged changed
                                ? changed.properties
                                : ((NanoRegistryPropertyCodec.PropertyResponse) decoded).properties;
                for (NanoRegistryPropertyCodec.Property property : properties) {
                    // _NRDevicePropertyIsSetup in NanoRegistry 23G71 resolves to
                    // the CFString "isSetup", not "IsSetup".
                    if ("isSetup".equals(property.name) && property.value != null
                            && !property.value.isError
                            && (!property.value.hasIsSet || property.value.isSet)
                            && property.value.numberValue != null
                            && Boolean.TRUE.equals(property.value.numberValue.boolValue)) {
                        actions.addAll(coordinator.onIsSetupObserved(generation, true));
                    }
                }
            } finally {
                decoded.destroy();
            }
        } else if (PairedSyncCodec.isService(event.topic)
                && PairedSyncCodec.isInboundUserDefaults(event.protobufType)
                && !event.response) {
            // An inbound message that fails our schema check is telemetry,
            // not a reason to drop the whole link: log its shape and skip
            // it. Live 0.2.164: the first real Watch user-defaults frame did
            // not match the completion schema and killed the session at
            // "IDS normal-link validation failed". Live 0.2.167: inbound
            // decode is lax — any domain/keys are accepted and tracked; only
            // completion-domain keys advance peerSyncProgress.
            PairedSyncCodec.UserDefaultsMessage message = null;
            try {
                message = PairedSyncCodec.decodeInbound(payload);
            } catch (IllegalArgumentException rejected) {
                java.util.function.Consumer<String> logger =
                        PairedSyncCodec.diagnosticLogger;
                if (logger != null) {
                    logger.accept(
                            "PAIREDSYNC RX REJECTED: "
                                    + rejected.getMessage()
                                    + "; "
                                    + PairedSyncCodec.summarizeForLog(
                                            payload));
                }
            }
            if (message != null) {
                java.util.function.Consumer<String> logger =
                        PairedSyncCodec.diagnosticLogger;
                if (logger != null) {
                    // Completion-domain traffic must stay visible too: it is the
                    // only in-band proof of what the Watch believes about the
                    // PairedSync state machine.
                    logger.accept(
                            "PAIREDSYNC RX: "
                                    + PairedSyncCodec.summarizeForLog(
                                            payload));
                }
                try {
                    peerSyncProgress.accept(message);
                    if (peerSyncProgress.complete()) {
                        actions.addAll(coordinator.onPeerPairedSyncCompletionObserved(generation));
                    }
                    try {
                        PairedSyncPreferenceStore.Result stored =
                                PairedSyncPreferenceStore.merge(message);
                        if (logger != null) {
                            logger.accept(
                                    "PAIREDSYNC STORED: domain="
                                            + message.domain
                                            + " keys="
                                            + stored.accepted
                                            + " entries="
                                            + stored.entries
                                            + " domains="
                                            + stored.domainCount);
                        }
                    } catch (RuntimeException storeFailed) {
                        if (logger != null) {
                            logger.accept(
                                    "PAIREDSYNC STORE FAILED: "
                                            + storeFailed.getMessage());
                        }
                    }
                } finally {
                    message.destroy();
                }
            }
        }
        actions.addAll(observePairedSyncCapability());
        return actions;
    }

    /** Package-visible typed entry point used by deterministic offline tests. */
    synchronized InboundResult acceptPbBridge(
            InboundMessage incoming) {
        requireUsable();
        requireCurrentGeneration();
        boolean matchesTopic = incoming != null
                && PbBridgeCodec.SERVICE.equals(incoming.topic);
        if (!matchesTopic) {
            return new InboundResult(
                    List.of(),
                    null,
                    0,
                    null);
        }

        String wireTopic =
                (incoming.flags
                        & IdsSocketPairCodec.FLAG_HAS_TOPIC) != 0
                        ? incoming.topic
                        : null;
        IdsSocketPairCodec.ProtobufMessage envelope =
                new IdsSocketPairCodec.ProtobufMessage(
                        incoming.sequence,
                        incoming.streamId,
                        incoming.flags,
                        incoming.peerResponseIdentifier,
                        incoming.messageUuid,
                        wireTopic,
                        incoming.protobufType,
                        incoming.response,
                        incoming.payload,
                        null);
        try {
            try {
                PbBridgeCodec.validateEnvelope(
                        envelope);
                return consumePbBridge(
                        incoming);
            } catch (RuntimeException unhandled) {
                return new InboundResult(
                        List.of(),
                        null,
                        0,
                        null);
            }
        } finally {
            envelope.destroy();
        }
    }

    synchronized List<AppleWatchPostCommitCoordinator.Action>
            onActivationProxyResult(
                    AppleWatchPostCommitCoordinator.Action action,
                    boolean success) {
        requireUsable();
        requireType(
                action,
                AppleWatchPostCommitCoordinator.ActionType
                        .EXECUTE_ACTIVATION_HTTPS);
        return coordinator.onActivationProxyResult(
                generation,
                action.activationAttempt,
                action.activationKind,
                success);
    }

    /**
     * Samples the capability already parsed from the live Class-C snapshot.
     */
    synchronized List<AppleWatchPostCommitCoordinator.Action>
            observePairedSyncCapability() {
        requireUsable();
        PairedSyncCapabilityState.Status status =
                transport.pairedSyncCapabilityStatus();
        List<AppleWatchPostCommitCoordinator.Action> result =
                coordinator.onPairedSyncCapabilityObserved(
                        generation,
                        status);
        java.util.function.Consumer<String> logger =
                PairedSyncCodec.diagnosticLogger;
        if (logger != null) {
            AppleWatchPostCommitCoordinator.Snapshot snapshot =
                    coordinator.snapshot();
            if (snapshot.durableState.wireValue()
                    >= PairingSessionRecord.DurableState
                    .ACTIVATION_CONFIRMED.wireValue()) {
                logger.accept(
                        "PAIREDSYNC GATE: capability="
                                + status
                                + " phase="
                                + snapshot.phase
                                + " durable="
                                + snapshot.durableState
                                + " checkpointPending="
                                + snapshot.checkpointPending
                                + " langComplete="
                                + snapshot.languageAndLocaleComplete
                                + " initialSyncPrepared="
                                + snapshot.initialSyncPrepared
                                + " pbBridgeNormalSent="
                                + snapshot.pbBridgeNormalSent
                                + " isSetupObserved="
                                + snapshot.isSetupObserved
                                + " pairedSyncObserved="
                                + snapshot.pairedSyncObserved
                                + " actions="
                                + result.size());
            }
        }
        return result;
    }

    private InboundResult consumePbBridge(
            InboundMessage incoming) {
        List<AppleWatchPostCommitCoordinator.Action> actions;
        byte[] archivedActivationRequest =
                null;
        MobileActivationHttpProxy.RequestKind activationKind =
                null;
        int activationAttempt =
                0;
        switch (incoming.protobufType) {
            case PbBridgeCodec.TYPE_PROXY_ACTIVATION -> {
                if ((incoming.flags
                        & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE) != 0
                        && incoming.messageUuid != null
                        && !incoming.messageUuid.isEmpty()) {
                    activationFetchReplyTo =
                            incoming.messageUuid;
                } else {
                    activationFetchReplyTo =
                            null;
                }
                PbBridgeCodec.ActivationFetchRequest request =
                        PbBridgeCodec.decodeActivationFetch(
                                incoming.payload);
                try {
                    actions =
                            coordinator.onActivationRequest(
                                    generation);
                    AppleWatchPostCommitCoordinator.Action execute =
                            findAction(
                                    actions,
                                    AppleWatchPostCommitCoordinator.ActionType
                                            .EXECUTE_ACTIVATION_HTTPS);
                    if (execute != null) {
                        archivedActivationRequest =
                                request.archivedRequest();
                        activationKind =
                                execute.activationKind;
                        activationAttempt =
                                execute.activationAttempt;
                    }
                } finally {
                    request.destroy();
                }
            }
            case PbBridgeCodec.TYPE_BEGAN_ACTIVATING ->
                    actions =
                            List.of();
            case PbBridgeCodec.TYPE_ACTIVATION_SUCCEEDED -> {
                actions = new ArrayList<>(
                        coordinator.onActivationSucceeded(
                                generation));
                // Type 4 is activation evidence. It does not report Buddy or Clock.
                actions.addAll(
                        coordinator.onGizmoDidFinishActivating(
                                generation));
            }
            case PbBridgeCodec.TYPE_ACTIVATION_FAILED ->
                    actions =
                            coordinator.onActivationFailedRetryable(
                                    generation);
            case PbBridgeCodec.TYPE_LANGUAGE_AND_LOCALE_STATUS -> {
                PbBridgeCodec.LanguageAndLocaleStatus status =
                        PbBridgeCodec.decodeLanguageAndLocaleStatus(
                                incoming.payload);
                actions =
                        coordinator.onLanguageAndLocaleStatus(
                                generation,
                                status);
            }
            case PbBridgeCodec.TYPE_PREPARE_INITIAL_SYNC_RESPONSE -> {
                actions = new ArrayList<>(coordinator.onPrepareInitialSyncResponse(
                        generation, incoming.peerResponseIdentifier));
                actions.addAll(coordinator.onNormalTransitionPrerequisitesSatisfied(generation));
            }
            default ->
                    actions =
                            List.of();
        }
        return new InboundResult(
                actions,
                activationKind,
                activationAttempt,
                archivedActivationRequest);
    }

    private PreparedSend dispatchPbBridge(
            AppleWatchPostCommitCoordinator.Action action,
            PbBridgeCodec.ApplicationMessage message) {
        DispatchKey key =
                beginDispatch(
                        action);
        DispatchOutput output =
                null;
        try {
            if (action.type == AppleWatchPostCommitCoordinator.ActionType.SEND_PREPARE_INITIAL_SYNC) {
                DispatchOutput buddy = null;
                DispatchOutput start = null;
                DispatchOutput prepare = null;
                PairedSyncCodec.UserDefaultsMessage started = PairedSyncCodec.initialSyncStarted(
                        System.currentTimeMillis() / 1000.0 - 978307200.0);
                try {
                    buddy = transport.sendPbBridge(new PbBridgeCodec.PushBuddyFinished());
                    start = transport.sendPairedSync(started);
                    prepare = transport.sendPbBridge(message);
                    output = new InitialSyncDispatchOutput(buddy, start, prepare);
                } catch (RuntimeException failure) {
                    closeQuietly(buddy);
                    closeQuietly(start);
                    closeQuietly(prepare);
                    throw failure;
                } finally {
                    started.destroy();
                }
            } else {
                output = transport.sendPbBridge(message);
            }
            return register(
                    action,
                    key,
                    output,
                    PbBridgeCodec.SERVICE,
                    message.protobufType(),
                    message.response());
        } catch (RuntimeException failure) {
            closeQuietly(
                    output);
            throw dispatchFailed(
                    action,
                    key,
                    failure);
        }
    }

    private PreparedSend dispatchClassD(
            AppleWatchPostCommitCoordinator.Action action,
            NanoRegistryClassDCodec.ApplicationMessage message) {
        DispatchKey key =
                beginDispatch(
                        action);
        DispatchOutput output =
                null;
        try {
            output =
                    transport.sendClassD(
                            message);
            return register(
                    action,
                    key,
                    output,
                    NanoRegistryClassDCodec.SERVICE,
                    message.protobufType(),
                    message.response());
        } catch (RuntimeException failure) {
            closeQuietly(
                    output);
            throw dispatchFailed(
                    action,
                    key,
                    failure);
        }
    }

    private PreparedSend dispatchPairedSync(
            AppleWatchPostCommitCoordinator.Action action,
            PairedSyncCodec.UserDefaultsMessage message,
            double timestamp) {
        DispatchKey key = beginDispatch(action);
        DispatchOutput output = null;
        try {
            output = completionOutputs(transport.sendPairedSync(message), timestamp);
            return register(
                    action,
                    key,
                    output,
                    null,
                    message.protobufType(),
                    message.response());
        } catch (RuntimeException failure) {
            closeQuietly(
                    output);
            throw dispatchFailed(
                    action,
                    key,
                    failure);
        }
    }

    private PreparedSend register(
            AppleWatchPostCommitCoordinator.Action action,
            DispatchKey key,
            DispatchOutput output,
            String exactTopic,
            int protobufType,
            boolean response) {
        if (output == null) {
            throw new IllegalStateException(
                    "IDS dispatch output is absent");
        }
        SentMessage sent =
                output.sentMessage();
        if (sent == null
                || (exactTopic != null
                && !exactTopic.equals(
                sent.topic))
                || sent.protobufType != protobufType
                || sent.response != response) {
            throw new IllegalStateException(
                    "IDS dispatch output does not match its action");
        }
        if (action.type
                == AppleWatchPostCommitCoordinator.ActionType
                .SEND_PAIRED_SYNC_COMPLETION
                && (!PairedSyncCodec.isService(
                sent.topic)
                || (sent.flags
                & IdsSocketPairCodec.FLAG_WANTS_APP_ACK) == 0)) {
            throw new IllegalStateException(
                    "PairedSync completion must request an IDS AppAck");
        }
        PreparedSend prepared =
                new PreparedSend(
                        this,
                        action,
                        key,
                        output,
                        sent);
        pending.add(
                prepared);
        return prepared;
    }

    private DispatchException dispatchFailed(
            AppleWatchPostCommitCoordinator.Action action,
            DispatchKey key,
            RuntimeException cause) {
        dispatched.remove(
                key);
        List<AppleWatchPostCommitCoordinator.Action> followUp =
                reportSendResult(
                        action,
                        false,
                        null);
        return new DispatchException(
                "IDS send could not be prepared: " + (cause != null ? (cause.getClass().getSimpleName() + ": " + cause.getMessage()) : "unknown"),
                cause,
                followUp);
    }

    private synchronized List<AppleWatchPostCommitCoordinator.Action>
            finish(
                    PreparedSend prepared,
                    boolean success) {
        requireUsable();
        if (prepared == null
                || prepared.owner != this
                || prepared.finished
                || !pending.remove(
                prepared)) {
            throw new IllegalStateException(
                    "Prepared IDS send is not pending");
        }
        prepared.finished =
                true;
        closeQuietly(
                prepared.output);
        return reportSendResult(
                prepared.action,
                success,
                prepared.sent);
    }

    private List<AppleWatchPostCommitCoordinator.Action> reportSendResult(
            AppleWatchPostCommitCoordinator.Action action,
            boolean success,
            SentMessage sent) {
        return switch (action.type) {
            case SEND_PAIRING_MODE_NORMAL -> {
                if (success && sent != null) {
                    pairingModeNormalRequestIdentifier = sent.messageUuid;
                }
                yield coordinator.onPairingModeNormalSendResult(generation, success);
            }
            case SEND_COMPUTED_TIME_ZONE ->
                    coordinator.onComputedTimeZoneSendResult(
                            generation,
                            success);
            case SEND_LANGUAGE_AND_LOCALE ->
                    coordinator.onLanguageAndLocaleSendResult(
                            generation,
                            success);
            case SEND_ACTIVATION_PERMIT ->
                    coordinator.onActivationPermitSendResult(
                            generation,
                            action.activationAttempt,
                            success);
            case SEND_ACTIVATION_RETRY ->
                    coordinator.onActivationRetrySendResult(
                            generation,
                            action.activationAttempt,
                            success);
            case SEND_ACTIVATION_DATA ->
                    coordinator.onActivationDataSendResult(
                            generation,
                            action.activationAttempt,
                            action.activationKind,
                            success);
            case SEND_NORMAL_AFTER_LANGUAGE_RELAUNCH ->
                    coordinator.onRelaunchNormalSendResult(
                            generation,
                            action.activationAttempt,
                            success);
            case SEND_PREPARE_INITIAL_SYNC ->
                    coordinator.onPrepareInitialSyncSent(
                            generation,
                            success,
                            success && sent != null
                                    ? sent.messageUuid
                                    : null);
            case SEND_PB_BRIDGE_NORMAL ->
                    coordinator.onNormalStateSendResult(
                            generation,
                            success);
            case SEND_PAIRED_SYNC_COMPLETION -> {
                if (success) {
                    if (sent == null) {
                        throw new IllegalStateException(
                                "Successful PairedSync send has no metadata");
                    }
                    expectedPairedSyncAppAckTopic =
                            sent.topic;
                    expectedPairedSyncAppAckIdentifier =
                            sent.messageUuid;
                    pairedSyncAppAckObserved =
                            false;
                }
                List<AppleWatchPostCommitCoordinator.Action> actions = new ArrayList<>(
                        coordinator.onPairedSyncSendResult(generation, success));
                if (success && peerSyncProgress.complete()) {
                    actions.addAll(coordinator.onPeerPairedSyncCompletionObserved(generation));
                }
                yield actions;
            }
            default -> throw new IllegalArgumentException(
                    "Post-commit action has no IDS send callback");
        };
    }

    boolean isDispatched(
            AppleWatchPostCommitCoordinator.Action action) {
        if (action == null) {
            return false;
        }
        return dispatched.contains(
                new DispatchKey(action));
    }

    private DispatchKey beginDispatch(
            AppleWatchPostCommitCoordinator.Action action) {
        requireUsable();
        requireCurrentGeneration();
        AppleWatchPostCommitCoordinator.Action checked =
                requireAction(
                        action);
        DispatchKey key =
                new DispatchKey(
                        checked);
        if (!dispatched.add(
                key)) {
            throw new IllegalStateException(
                    "Post-commit IDS action was already dispatched");
        }
        return key;
    }

    private AppleWatchPostCommitCoordinator.Action requireAction(
            AppleWatchPostCommitCoordinator.Action action) {
        if (action == null
                || action.generation != generation) {
            throw new IllegalArgumentException(
                    "Post-commit action belongs to another generation");
        }
        return action;
    }

    private void requireType(
            AppleWatchPostCommitCoordinator.Action action,
            AppleWatchPostCommitCoordinator.ActionType expected) {
        requireUsable();
        if (requireAction(
                action).type != expected) {
            throw new IllegalArgumentException(
                    "Post-commit action type does not match IDS payload");
        }
    }

    private void requireCurrentGeneration() {
        AppleWatchPostCommitCoordinator.Snapshot snapshot =
                coordinator.snapshot();
        if (snapshot.generation != generation) {
            throw new IllegalStateException(
                    "Post-commit generation changed under IDS adapter");
        }
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "Post-commit IDS adapter is closed");
        }
    }

    private static AppleWatchPostCommitCoordinator.Action findAction(
            List<AppleWatchPostCommitCoordinator.Action> actions,
            AppleWatchPostCommitCoordinator.ActionType type) {
        for (AppleWatchPostCommitCoordinator.Action action :
                actions) {
            if (action.type == type) {
                return action;
            }
        }
        return null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (PreparedSend prepared :
                new ArrayList<>(
                        pending)) {
            prepared.finished =
                    true;
            closeQuietly(
                    prepared.output);
        }
        pending.clear();
        dispatched.clear();
        expectedPairedSyncAppAckTopic =
                null;
        expectedPairedSyncAppAckIdentifier =
                null;
        pairedSyncAppAckObserved =
                false;
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    private static void closeQuietly(
            AutoCloseable value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception ignored) {
            // The coordinator is driven by the explicit transport outcome.
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    interface Transport
            extends AutoCloseable {
        DispatchOutput sendClassD(
                NanoRegistryClassDCodec.ApplicationMessage message);

        DispatchOutput sendPbBridge(
                PbBridgeCodec.ApplicationMessage message);

        default DispatchOutput sendPbBridgeReplyToFetch(
                PbBridgeCodec.ApplicationMessage message,
                String watchMessageUuid) {
            return sendPbBridge(
                    message);
        }

        DispatchOutput sendPairedSync(
                PairedSyncCodec.UserDefaultsMessage message);

        PairedSyncCapabilityState.Status
                pairedSyncCapabilityStatus();

        @Override
        void close();
    }

    enum AppAckReceipt {
        UNRELATED,
        PAIRED_SYNC_MATCHED,
        PAIRED_SYNC_DUPLICATE
    }

    /** Minimal holder for diagnostic sends that skip coordinator bookkeeping. */
    static final class DiagnosticSend
            implements AutoCloseable {
        private DispatchOutput output;
        final String topic;
        final long sequence;

        private DiagnosticSend(
                DispatchOutput output,
                String topic,
                long sequence) {
            this.output =
                    output;
            this.topic =
                    topic;
            this.sequence =
                    sequence;
        }

        List<byte[]> outboundFrames() {
            DispatchOutput checked =
                    output;
            if (checked == null) {
                throw new IllegalStateException(
                        "Diagnostic send is already closed");
            }
            return checked.outboundFrames();
        }

        @Override
        public void close() {
            DispatchOutput checked =
                    output;
            output =
                    null;
            if (checked != null) {
                try {
                    checked.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    interface DispatchOutput
            extends AutoCloseable {
        List<byte[]> outboundFrames();

        SentMessage sentMessage();

        @Override
        void close();
    }

    /** Open Buddy before PSY changes, retaining PB prepare reply correlation. */
    private static final class InitialSyncDispatchOutput implements DispatchOutput {
        private DispatchOutput buddy;
        private DispatchOutput start;
        private DispatchOutput prepare;

        InitialSyncDispatchOutput(DispatchOutput buddy, DispatchOutput start, DispatchOutput prepare) {
            this.buddy = Objects.requireNonNull(buddy);
            this.start = Objects.requireNonNull(start);
            this.prepare = Objects.requireNonNull(prepare);
        }

        @Override
        public List<byte[]> outboundFrames() {
            requireOpen();
            List<byte[]> frames = new ArrayList<>(buddy.outboundFrames());
            frames.addAll(start.outboundFrames());
            frames.addAll(prepare.outboundFrames());
            return Collections.unmodifiableList(frames);
        }

        @Override
        public SentMessage sentMessage() {
            requireOpen();
            return prepare.sentMessage();
        }

        private void requireOpen() {
            if (prepare == null) throw new IllegalStateException("Initial sync output is closed");
        }

        @Override
        public void close() {
            closeQuietly(buddy);
            closeQuietly(start);
            closeQuietly(prepare);
            buddy = null;
            start = null;
            prepare = null;
        }
    }

    /** Own both completion domains until the HAL submits every frame. */
    private static final class CompletionDispatchOutput implements DispatchOutput {
        private DispatchOutput primary;
        private DispatchOutput initialSync;

        CompletionDispatchOutput(DispatchOutput primary, DispatchOutput initialSync) {
            this.primary = Objects.requireNonNull(primary);
            this.initialSync = Objects.requireNonNull(initialSync);
        }

        @Override
        public List<byte[]> outboundFrames() {
            requireOpen();
            List<byte[]> frames = new ArrayList<>(primary.outboundFrames());
            frames.addAll(initialSync.outboundFrames());
            return Collections.unmodifiableList(frames);
        }

        @Override
        public SentMessage sentMessage() {
            requireOpen();
            return primary.sentMessage();
        }

        private void requireOpen() {
            if (primary == null) throw new IllegalStateException("Completion output is closed");
        }

        @Override
        public void close() {
            closeQuietly(primary);
            closeQuietly(initialSync);
            primary = null;
            initialSync = null;
        }
    }

    static final class SentMessage {
        final long sequence;
        final String messageUuid;
        final String topic;
        final int flags;
        final int protobufType;
        final boolean response;

        SentMessage(
                long sequence,
                String messageUuid,
                String topic,
                int flags,
                int protobufType,
                boolean response) {
            this.sequence =
                    sequence;
            this.messageUuid =
                    messageUuid;
            this.topic =
                    topic;
            this.flags =
                    flags;
            this.protobufType =
                    protobufType;
            this.response =
                    response;
            new IdsModernSessionCoordinator.MessageMetadata(
                    sequence,
                    messageUuid,
                    flags & ~IdsSocketPairCodec.FLAG_HAS_TOPIC,
                    null,
                    null,
                    null);
        }
    }

    static final class PreparedSend
            implements AutoCloseable {
        private final AppleWatchPostCommitIdsAdapter owner;
        private final AppleWatchPostCommitCoordinator.Action action;
        private final DispatchKey key;
        private final DispatchOutput output;
        private final SentMessage sent;
        private boolean finished;

        private PreparedSend(
                AppleWatchPostCommitIdsAdapter owner,
                AppleWatchPostCommitCoordinator.Action action,
                DispatchKey key,
                DispatchOutput output,
                SentMessage sent) {
            this.owner = owner;
            this.action = action;
            this.key = key;
            this.output = output;
            this.sent = sent;
        }

        List<byte[]> outboundFrames() {
            requirePending();
            return output.outboundFrames();
        }

        long sequence() {
            return sent.sequence;
        }

        String messageUuid() {
            return sent.messageUuid;
        }

        String topic() {
            return sent.topic;
        }

        int protobufType() {
            return sent.protobufType;
        }

        List<AppleWatchPostCommitCoordinator.Action> complete(
                boolean allFramesDelivered) {
            requirePending();
            return owner.finish(
                    this,
                    allFramesDelivered);
        }

        private void requirePending() {
            if (finished) {
                throw new IllegalStateException(
                        "Prepared IDS send is already complete");
            }
        }

        @Override
        public void close() {
            if (!finished
                    && !owner.closed) {
                owner.finish(
                        this,
                        false);
            }
        }
    }

    static final class InboundMessage
            implements AutoCloseable {
        final long sequence;
        final int streamId;
        final int flags;
        final String peerResponseIdentifier;
        final String messageUuid;
        final String topic;
        final int protobufType;
        final boolean response;
        private byte[] payload;
        private boolean closed;

        InboundMessage(
                long sequence,
                int streamId,
                int flags,
                String peerResponseIdentifier,
                String messageUuid,
                String topic,
                int protobufType,
                boolean response,
                byte[] payload) {
            this.sequence =
                    sequence;
            this.streamId =
                    streamId;
            this.flags =
                    flags;
            this.peerResponseIdentifier =
                    peerResponseIdentifier;
            this.messageUuid =
                    messageUuid;
            this.topic =
                    topic;
            this.protobufType =
                    protobufType;
            this.response =
                    response;
            this.payload =
                    payload == null
                            ? null
                            : payload.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    payload);
            payload =
                    new byte[0];
        }
    }

    static final class InboundResult
            implements AutoCloseable {
        private final List<AppleWatchPostCommitCoordinator.Action> actions;
        final MobileActivationHttpProxy.RequestKind activationKind;
        final int activationAttempt;
        private byte[] archivedActivationRequest;
        private boolean closed;

        private InboundResult(
                List<AppleWatchPostCommitCoordinator.Action> actions,
                MobileActivationHttpProxy.RequestKind activationKind,
                int activationAttempt,
                byte[] archivedActivationRequest) {
            this.actions =
                    actions.isEmpty()
                            ? List.of()
                            : Collections.unmodifiableList(
                                    new ArrayList<>(
                                            actions));
            this.activationKind =
                    activationKind;
            this.activationAttempt =
                    activationAttempt;
            this.archivedActivationRequest =
                    archivedActivationRequest == null
                            ? null
                            : archivedActivationRequest.clone();
            wipe(
                    archivedActivationRequest);
        }

        List<AppleWatchPostCommitCoordinator.Action> actions() {
            requireOpen();
            return actions;
        }

        boolean hasActivationRequest() {
            requireOpen();
            return archivedActivationRequest != null;
        }

        byte[] archivedActivationRequest() {
            requireOpen();
            if (archivedActivationRequest == null) {
                throw new IllegalStateException(
                        "Inbound PBBridge message is not an activation request");
            }
            return archivedActivationRequest.clone();
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Inbound post-commit result is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    archivedActivationRequest);
            archivedActivationRequest =
                    null;
        }
    }

    static final class DispatchException
            extends IllegalStateException {
        private final List<AppleWatchPostCommitCoordinator.Action>
                followUpActions;

        private DispatchException(
                String message,
                Throwable cause,
                List<AppleWatchPostCommitCoordinator.Action>
                        followUpActions) {
            super(
                    message,
                    cause);
            this.followUpActions =
                    followUpActions.isEmpty()
                            ? List.of()
                            : Collections.unmodifiableList(
                                    new ArrayList<>(
                                            followUpActions));
        }

        List<AppleWatchPostCommitCoordinator.Action> followUpActions() {
            return followUpActions;
        }
    }

    private static final class DispatchKey {
        private final long generation;
        private final AppleWatchPostCommitCoordinator.ActionType type;
        private final MobileActivationHttpProxy.RequestKind activationKind;
        private final int activationAttempt;

        private DispatchKey(
                AppleWatchPostCommitCoordinator.Action action) {
            generation =
                    action.generation;
            type =
                    action.type;
            activationKind =
                    action.activationKind;
            activationAttempt =
                    action.activationAttempt;
        }

        @Override
        public boolean equals(
                Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DispatchKey that)) {
                return false;
            }
            return generation == that.generation
                    && activationAttempt == that.activationAttempt
                    && type == that.type
                    && activationKind == that.activationKind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    generation,
                    type,
                    activationKind,
                    activationAttempt);
        }
    }

    private static final class LiveTransport
            implements Transport {
        private NormalLinkIdsSessionBridge bridge;

        private LiveTransport(
                NormalLinkIdsSessionBridge bridge) {
            if (bridge == null) {
                throw new IllegalArgumentException(
                        "Normal-link IDS bridge is absent");
            }
            this.bridge =
                    bridge;
        }

        @Override
        public DispatchOutput sendClassD(
                NanoRegistryClassDCodec.ApplicationMessage message) {
            requireOpen();
            return new LiveDispatchOutput(
                    bridge.sendClassD(
                            message));
        }

        @Override
        public DispatchOutput sendPbBridge(
                PbBridgeCodec.ApplicationMessage message) {
            requireOpen();
            return new LiveDispatchOutput(
                    bridge.sendPbBridge(
                            message));
        }

        @Override
        public DispatchOutput sendPbBridgeReplyToFetch(
                PbBridgeCodec.ApplicationMessage message,
                String watchMessageUuid) {
            requireOpen();
            return new LiveDispatchOutput(
                    bridge.sendPbBridgeReplyToFetch(
                            message,
                            watchMessageUuid));
        }

        @Override
        public DispatchOutput sendPairedSync(
                PairedSyncCodec.UserDefaultsMessage message) {
            requireOpen();
            return new LiveDispatchOutput(
                    bridge.sendPairedSync(
                            message));
        }

        @Override
        public PairedSyncCapabilityState.Status
                pairedSyncCapabilityStatus() {
            requireOpen();
            return bridge.idsSnapshot()
                    .pairedSyncCapabilityStatus;
        }

        private void requireOpen() {
            if (bridge == null) {
                throw new IllegalStateException(
                        "Live post-commit IDS transport is closed");
            }
        }

        @Override
        public void close() {
            if (bridge == null) {
                return;
            }
            NormalLinkIdsSessionBridge owned =
                    bridge;
            bridge =
                    null;
            owned.close();
        }
    }

    private static final class LiveDispatchOutput
            implements DispatchOutput {
        private NormalLinkIdsSessionBridge.Output output;
        private final SentMessage sent;

        private LiveDispatchOutput(
                NormalLinkIdsSessionBridge.Output output) {
            if (output == null) {
                throw new IllegalArgumentException(
                        "Normal-link IDS output is absent");
            }
            this.output =
                    output;
            SentMessage found =
                    null;
            for (IdsModernSessionCoordinator.SessionEvent event :
                    output.idsEvents()) {
                if (event.type
                        != IdsModernSessionCoordinator.EventType
                        .PROTOBUF_SENT) {
                    continue;
                }
                if (found != null) {
                    output.close();
                    this.output =
                            null;
                    throw new IllegalStateException(
                            "One IDS action emitted multiple protobufs");
                }
                found =
                        new SentMessage(
                                event.sequence,
                                event.messageUuid,
                                event.topic,
                                event.flags,
                                event.protobufType,
                                event.response);
            }
            if (found == null) {
                output.close();
                this.output =
                        null;
                throw new IllegalStateException(
                        "IDS action emitted no protobuf event");
            }
            sent =
                    found;
        }

        @Override
        public List<byte[]> outboundFrames() {
            requireOpen();
            return output.ertmFrames();
        }

        @Override
        public SentMessage sentMessage() {
            requireOpen();
            return sent;
        }

        private void requireOpen() {
            if (output == null) {
                throw new IllegalStateException(
                        "Live IDS dispatch output is closed");
            }
        }

        @Override
        public void close() {
            if (output == null) {
                return;
            }
            output.close();
            output =
                    null;
        }
    }
}
