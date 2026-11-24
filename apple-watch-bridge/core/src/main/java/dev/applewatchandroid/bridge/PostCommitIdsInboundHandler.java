package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Inbound IDS observation, PBBridge dispatch, and AppAck correlation handler
 * owned by one synchronized {@link AppleWatchPostCommitIdsAdapter}. Every
 * method must run under the adapter's monitor lock.
 */
final class PostCommitIdsInboundHandler {
    private final AppleWatchPostCommitCoordinator coordinator;
    private final long generation;

    private String expectedPairedSyncAppAckTopic;
    private String expectedPairedSyncAppAckIdentifier;
    private boolean pairedSyncAppAckObserved;
    private String pairingModeNormalRequestIdentifier;
    /** Watch ProxyActivation message UUID when that fetch expects an IDS reply. */
    private String activationFetchReplyTo;
    private final PairedSyncPeerProgress peerSyncProgress = new PairedSyncPeerProgress();

    PostCommitIdsInboundHandler(
            AppleWatchPostCommitCoordinator coordinator,
            long generation) {
        this.coordinator = coordinator;
        this.generation = generation;
    }

    AppleWatchPostCommitIdsAdapter.InboundResult accept(
            IdsModernSessionCoordinator.SessionEvent event,
            AppleWatchPostCommitIdsAdapter.Transport transport) {
        if (event == null) {
            return new AppleWatchPostCommitIdsAdapter.InboundResult(
                    List.of(),
                    null,
                    0,
                    null);
        }
        if (event.type != IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                && event.type != IdsModernSessionCoordinator.EventType.DATA_RECEIVED) {
            return new AppleWatchPostCommitIdsAdapter.InboundResult(
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
                return new AppleWatchPostCommitIdsAdapter.InboundResult(
                        consumeSetupObservation(event, payload, transport),
                        null,
                        0,
                        null);
            } finally {
                AppleWatchPostCommitIdsAdapter.wipe(payload);
            }
        }
        try (AppleWatchPostCommitIdsAdapter.InboundMessage incoming =
                     new AppleWatchPostCommitIdsAdapter.InboundMessage(
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
            return new AppleWatchPostCommitIdsAdapter.InboundResult(
                    List.of(),
                    null,
                    0,
                    null);
        } finally {
            AppleWatchPostCommitIdsAdapter.wipe(
                    payload);
        }
    }

    AppleWatchPostCommitIdsAdapter.AppAckReceipt acceptAppAck(
            IdsModernSessionCoordinator.SessionEvent event) {
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
            return AppleWatchPostCommitIdsAdapter.AppAckReceipt.UNRELATED;
        }
        if (pairedSyncAppAckObserved) {
            return AppleWatchPostCommitIdsAdapter.AppAckReceipt.PAIRED_SYNC_DUPLICATE;
        }
        pairedSyncAppAckObserved =
                true;
        return AppleWatchPostCommitIdsAdapter.AppAckReceipt.PAIRED_SYNC_MATCHED;
    }

    boolean pairedSyncAppAckObserved() {
        return pairedSyncAppAckObserved;
    }

    AppleWatchPostCommitIdsAdapter.InboundResult acceptPbBridge(
            AppleWatchPostCommitIdsAdapter.InboundMessage incoming) {
        boolean matchesTopic = incoming != null
                && PbBridgeCodec.SERVICE.equals(incoming.topic);
        if (!matchesTopic) {
            return new AppleWatchPostCommitIdsAdapter.InboundResult(
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
                return new AppleWatchPostCommitIdsAdapter.InboundResult(
                        List.of(),
                        null,
                        0,
                        null);
            }
        } finally {
            envelope.destroy();
        }
    }

    List<AppleWatchPostCommitCoordinator.Action> observePairedSyncCapability(
            AppleWatchPostCommitIdsAdapter.Transport transport) {
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

    void recordPairingModeNormalSent(String messageUuid) {
        pairingModeNormalRequestIdentifier = messageUuid;
    }

    void recordPairedSyncSent(String topic, String messageUuid) {
        expectedPairedSyncAppAckTopic = topic;
        expectedPairedSyncAppAckIdentifier = messageUuid;
        pairedSyncAppAckObserved = false;
    }

    boolean peerSyncComplete() {
        return peerSyncProgress.complete();
    }

    String consumeActivationFetchReplyTo() {
        String replyTo = activationFetchReplyTo;
        activationFetchReplyTo = null;
        return replyTo;
    }

    void resetOnClose() {
        expectedPairedSyncAppAckTopic = null;
        expectedPairedSyncAppAckIdentifier = null;
        pairedSyncAppAckObserved = false;
    }

    private List<AppleWatchPostCommitCoordinator.Action> consumeSetupObservation(
            IdsModernSessionCoordinator.SessionEvent event,
            byte[] payload,
            AppleWatchPostCommitIdsAdapter.Transport transport) {
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
        actions.addAll(observePairedSyncCapability(transport));
        return actions;
    }

    private AppleWatchPostCommitIdsAdapter.InboundResult consumePbBridge(
            AppleWatchPostCommitIdsAdapter.InboundMessage incoming) {
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
        return new AppleWatchPostCommitIdsAdapter.InboundResult(
                actions,
                activationKind,
                activationAttempt,
                archivedActivationRequest);
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
}
