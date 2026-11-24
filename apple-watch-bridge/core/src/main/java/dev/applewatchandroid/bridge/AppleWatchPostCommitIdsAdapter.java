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
    private final PostCommitIdsInboundHandler inbound;
    private boolean closed;
    private CompanionProtocolProfile profile = CompanionProtocolProfile.IOS_26_6;

    AppleWatchPostCommitIdsAdapter(
            AppleWatchPostCommitCoordinator coordinator,
            NormalLinkIdsSessionBridge bridge) {
        this(
                coordinator,
                PostCommitIdsLiveTransport.liveTransport(
                        bridge));
        profile = bridge.ids().profile();
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
        this.inbound =
                new PostCommitIdsInboundHandler(
                        coordinator,
                        generation);
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
                    checked.pairingModeNormalMessage(profile);
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
                inbound.consumeActivationFetchReplyTo();
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
            return PostCommitIdsLiveTransport.completionOutput(primary, initialSync);
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
        return inbound.accept(
                event,
                transport);
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
        return inbound.acceptAppAck(
                event);
    }

    synchronized boolean pairedSyncAppAckObserved() {
        requireUsable();
        requireCurrentGeneration();
        return inbound.pairedSyncAppAckObserved();
    }

    /** Package-visible typed entry point used by deterministic offline tests. */
    synchronized InboundResult acceptPbBridge(
            InboundMessage incoming) {
        requireUsable();
        requireCurrentGeneration();
        return inbound.acceptPbBridge(
                incoming);
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
        return inbound.observePairedSyncCapability(
                transport);
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
                    output = PostCommitIdsLiveTransport.initialSyncOutput(buddy, start, prepare);
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
                    inbound.recordPairingModeNormalSent(sent.messageUuid);
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
                    inbound.recordPairedSyncSent(
                            sent.topic,
                            sent.messageUuid);
                }
                List<AppleWatchPostCommitCoordinator.Action> actions = new ArrayList<>(
                        coordinator.onPairedSyncSendResult(generation, success));
                if (success && inbound.peerSyncComplete()) {
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
        inbound.resetOnClose();
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    static void closeQuietly(
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

    static void wipe(
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
        byte[] payload;
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

        InboundResult(
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

}
