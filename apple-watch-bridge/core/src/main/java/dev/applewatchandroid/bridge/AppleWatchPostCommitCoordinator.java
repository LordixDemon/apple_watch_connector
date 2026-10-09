package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Event-driven Ultra 2 setup coordinator from the IsPaired boundary to Clock.
 *
 * <p>The coordinator never owns a PIN, activation archive, HTTP body, IDS
 * identifier, or pairing key. It emits commands and consumes only bounded
 * success evidence. The caller must persist every requested
 * {@link PairingSessionRecord} transition before reporting it here.</p>
 *
 * <p>IsPaired is represented by one atomic local pairing-record transition,
 * matching the phone-local NanoRegistry write in Apple's implementation. A
 * write-ahead intent is persisted first. If a process dies while only that
 * intent is durable, recovery requires another explicit local authorization;
 * there is no invented Watch-side IsPaired readback.</p>
 *
 * <p>After the local commit is durable, the coordinator sends the separate,
 * replayable Class-D PairingMode Normal request before any later PBBridge
 * setup work. That request has no response callback in the Apple chain, so a
 * successful transport delivery is not treated as Watch-local apply proof.</p>
 */
final class AppleWatchPostCommitCoordinator {
    static final int COMPATIBILITY_STATE_NORMAL = 4;
    static final int PAIRED_SYNC_PROGRESS_COMPLETE = 3;
    static final int PAIRED_SYNC_GLOBAL_PROGRESS_COMPLETE = 100;
    static final int WATCH_PAIRED_SYNC_COMPLETE_STATE = 1;
    static final int CAROUSEL_CLOCK_STATE = 0;
    static final int CAROUSEL_BUDDY_COMPLETED_EVENT = 6;
    static final int CAROUSEL_BUDDY_TRANSITION_REASON = 10;

    enum Phase {
        IDLE,
        SAFE_STOP_BEFORE_COMMIT,
        COMMIT_INTENT_PENDING,
        COMMIT_FINALIZATION_RETRY_REQUIRED,
        FORWARD_SETUP,
        CHECKPOINT_PENDING,
        WAITING_FOR_CLOCK,
        VERIFYING_OPERATIONAL_HEALTH,
        COMPLETE,
        FORWARD_RECOVERY_REQUIRED
    }

    enum ActionType {
        REQUEST_EXPLICIT_IS_PAIRED_CONFIRMATION,
        PERSIST_IS_PAIRED_COMMIT_INTENT,
        REQUEST_EXPLICIT_IS_PAIRED_RETRY,
        PERSIST_DURABLE_STATE,
        PERSIST_ACTIVATION_REDRIVE,
        SEND_PAIRING_MODE_NORMAL,
        SEND_COMPUTED_TIME_ZONE,
        SEND_LANGUAGE_AND_LOCALE,
        SEND_ACTIVATION_PERMIT,
        SEND_ACTIVATION_RETRY,
        EXECUTE_ACTIVATION_HTTPS,
        SEND_ACTIVATION_DATA,
        SEND_NORMAL_AFTER_LANGUAGE_RELAUNCH,
        SEND_PREPARE_INITIAL_SYNC,
        WAIT_FOR_PB_BRIDGE_NORMAL_PREREQUISITES,
        SEND_PB_BRIDGE_NORMAL,
        SEND_PAIRED_SYNC_COMPLETION,
        SEND_IDS_LOCAL_PAIRING_SETUP_COMPLETED,
        VERIFY_CLOCK_VISIBLE,
        VERIFY_OPERATIONAL_RECONNECT,
        REPORT_FAILURE
    }

    enum Failure {
        LOCAL_CHECKPOINT,
        TRANSPORT_SEND,
        ACTIVATION_PROXY,
        ACTIVATION_REJECTED,
        UNKNOWN_LANGUAGE_STATUS,
        INVALID_PAIRED_SYNC_EVIDENCE,
        INVALID_CLOCK_EVIDENCE,
        INVALID_OPERATIONAL_HEALTH_EVIDENCE,
        UNEXPECTED_EVENT
    }

    private enum ActivationStage {
        NOT_STARTED,
        PERMIT_SEND_PENDING,
        WAITING_SESSION_REQUEST,
        SESSION_PROXY_PENDING,
        SESSION_DATA_SEND_PENDING,
        WAITING_ACTIVATION_REQUEST,
        ACTIVATION_PROXY_PENDING,
        ACTIVATION_DATA_SEND_PENDING,
        WAITING_OUTCOME,
        RELAUNCH_NORMAL_SEND_PENDING,
        CONFIRMED
    }

    private long generation;
    private Phase phase =
            Phase.IDLE;
    private PairingSessionRecord.DurableState durableState;
    private PairingSessionRecord.DurableState checkpointPending;
    private boolean commitIntentPersisted;
    private boolean irreversibleCommitObserved;
    private int runtimeWatchPairingVersion;
    private boolean pairingModeNormalSendInFlight;
    private boolean pairingModeNormalSent;

    private boolean computedTimeZoneSent;
    private boolean languageAndLocaleSent;
    private boolean languageAndLocaleComplete;
    private boolean initialSyncRequestInFlight;
    private String initialSyncRequestIdentifier;
    private boolean initialSyncPrepared;
    private boolean pbBridgeNormalPrerequisitesRequested;
    private boolean pbBridgeNormalSendInFlight;
    private boolean pbBridgeNormalSent;
    private boolean watchCompatibilityNormalObserved;
    private boolean isSetupObserved;
    private PairedSyncCapabilityState.Status pairedSyncCapability =
            PairedSyncCapabilityState.Status.UNKNOWN;
    private boolean pairedSyncSendInFlight;
    private boolean pairedSyncSent;
    private boolean pairedSyncObserved;
    private boolean idsSetupCompletedSendInFlight;

    private int activationAttempt;
    private ActivationStage activationStage =
            ActivationStage.NOT_STARTED;
    private boolean activationOutcomeObserved;
    private boolean activationRedrivePending;
    /**
     * Empty protobuf 4 arrived while activation was still unconfirmed.
     * watchOS answers CanBeginActivation that way when the Gizmo is already
     * activated and will not start a session. It is not confirmation by
     * itself: live 0.2.197 also saw this message before a real session.
     */
    private boolean gizmoFinishedBeforeSession;
    synchronized List<Action> begin(
            long newGeneration,
            PairingSessionRecord.DurableState restoredState,
            boolean restoredCommitIntentPersisted,
            int restoredRuntimeWatchPairingVersion) {
        if (newGeneration <= 0) {
            throw new IllegalArgumentException(
                    "Post-commit generation must be positive");
        }
        requireSupportedState(
                restoredState);
        requireRuntimeWatchPairingVersion(
                restoredRuntimeWatchPairingVersion);
        if (phase != Phase.IDLE) {
            if (generation == newGeneration
                    && durableState == restoredState
                    && commitIntentPersisted
                    == restoredCommitIntentPersisted
                    && runtimeWatchPairingVersion
                    == restoredRuntimeWatchPairingVersion) {
                return List.of();
            }
            throw new IllegalStateException(
                    "Post-commit coordinator already owns a generation");
        }
        if (restoredCommitIntentPersisted
                && restoredState
                != PairingSessionRecord.DurableState
                .READY_TO_COMMIT_IS_PAIRED) {
            throw new IllegalArgumentException(
                    "Commit intent is attached to an invalid durable state");
        }

        generation =
                newGeneration;
        durableState =
                restoredState;
        commitIntentPersisted =
                restoredCommitIntentPersisted;
        runtimeWatchPairingVersion =
                restoredRuntimeWatchPairingVersion;
        inferDurableEvidence();

        if (restoredState
                == PairingSessionRecord.DurableState
                .READY_TO_COMMIT_IS_PAIRED) {
            if (restoredCommitIntentPersisted) {
                phase =
                        Phase.COMMIT_FINALIZATION_RETRY_REQUIRED;
                return List.of(
                        Action.simple(
                                generation,
                                ActionType
                                        .REQUEST_EXPLICIT_IS_PAIRED_RETRY));
            }
            phase =
                    Phase.SAFE_STOP_BEFORE_COMMIT;
            return List.of(
                    Action.simple(
                            generation,
                            ActionType
                                    .REQUEST_EXPLICIT_IS_PAIRED_CONFIRMATION));
        }

        irreversibleCommitObserved = true;
        return resumeForwardSetup();
    }

    synchronized List<Action> onExplicitCommitDecision(
            long eventGeneration,
            boolean authorized) {
        if (!accepts(eventGeneration)
                || phase
                != Phase.SAFE_STOP_BEFORE_COMMIT) {
            return List.of();
        }
        if (!authorized) {
            return List.of();
        }
        phase =
                Phase.COMMIT_INTENT_PENDING;
        return List.of(
                Action.simple(
                        generation,
                        ActionType.PERSIST_IS_PAIRED_COMMIT_INTENT));
    }

    synchronized List<Action> onCommitIntentPersisted(
            long eventGeneration,
            boolean success) {
        if (!accepts(eventGeneration)
                || phase
                != Phase.COMMIT_INTENT_PENDING) {
            return List.of();
        }
        if (!success) {
            return fail(
                    Failure.LOCAL_CHECKPOINT);
        }
        commitIntentPersisted = true;
        return requestCheckpoint(
                PairingSessionRecord.DurableState
                        .IS_PAIRED_COMMITTED);
    }

    synchronized List<Action> onExplicitCommitRetryDecision(
            long eventGeneration,
            boolean authorized) {
        if (!accepts(eventGeneration)
                || phase
                != Phase.COMMIT_FINALIZATION_RETRY_REQUIRED
                || !commitIntentPersisted
                || !authorized) {
            return List.of();
        }
        return requestCheckpoint(
                PairingSessionRecord.DurableState
                        .IS_PAIRED_COMMITTED);
    }

    synchronized List<Action> onDurableStatePersisted(
            long eventGeneration,
            PairingSessionRecord.DurableState persistedState,
            boolean success) {
        if (!accepts(eventGeneration)
                || phase
                != Phase.CHECKPOINT_PENDING
                || checkpointPending == null
                || checkpointPending != persistedState) {
            return List.of();
        }
        if (!success) {
            checkpointPending = null;
            return fail(
                    Failure.LOCAL_CHECKPOINT);
        }

        durableState =
                persistedState;
        checkpointPending = null;
        if (persistedState
                == PairingSessionRecord.DurableState
                .IS_PAIRED_COMMITTED) {
            commitIntentPersisted = false;
            irreversibleCommitObserved = true;
        }
        inferDurableEvidence();
        return resumeForwardSetup();
    }

    synchronized List<Action> onPairingModeNormalSendResult(
            long eventGeneration,
            boolean success) {
        if (!accepts(eventGeneration)
                || phase != Phase.FORWARD_SETUP
                || !pairingModeNormalSendInFlight) {
            return List.of();
        }
        pairingModeNormalSendInFlight = false;
        if (!success) {
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        pairingModeNormalSent = true;
        return appendProgressActions();
    }

    synchronized List<Action> onComputedTimeZoneSendResult(
            long eventGeneration,
            boolean success) {
        if (!acceptsForward(eventGeneration)
                || computedTimeZoneSent) {
            return List.of();
        }
        if (!success) {
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        computedTimeZoneSent = true;
        return appendProgressActions();
    }

    synchronized List<Action> onLanguageAndLocaleSendResult(
            long eventGeneration,
            boolean success) {
        if (!acceptsForward(eventGeneration)
                || languageAndLocaleSent) {
            return List.of();
        }
        if (!success) {
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        languageAndLocaleSent = true;
        return List.of();
    }

    synchronized List<Action> onLanguageAndLocaleStatus(
            long eventGeneration,
            PbBridgeCodec.LanguageAndLocaleStatus status) {
        if (!acceptsForward(eventGeneration)
                || status == null
                || languageAndLocaleComplete) {
            return List.of();
        }
        if (!status.recognizedCompletion()) {
            return fail(
                    Failure.UNKNOWN_LANGUAGE_STATUS);
        }
        languageAndLocaleComplete = true;
        if (status.completedAfterRelaunch()
                && !activationOutcomeObserved
                && durableState.wireValue()
                < PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            activationAttempt++;
            activationStage =
                    ActivationStage.RELAUNCH_NORMAL_SEND_PENDING;
            return List.of(
                    Action.activation(
                            generation,
                            ActionType
                                    .SEND_NORMAL_AFTER_LANGUAGE_RELAUNCH,
                            null,
                            activationAttempt));
        }
        return appendProgressActions();
    }

    synchronized List<Action> onRelaunchNormalSendResult(
            long eventGeneration,
            int eventActivationAttempt,
            boolean success) {
        if (!acceptsForward(eventGeneration)
                || activationStage
                != ActivationStage.RELAUNCH_NORMAL_SEND_PENDING
                || activationAttempt != eventActivationAttempt) {
            return List.of();
        }
        if (!success) {
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        activationStage =
                ActivationStage.PERMIT_SEND_PENDING;
        return List.of(
                Action.activation(
                        generation,
                        ActionType.SEND_ACTIVATION_PERMIT,
                        null,
                        activationAttempt));
    }

    synchronized List<Action> onActivationPermitSendResult(
            long eventGeneration,
            int eventActivationAttempt,
            boolean success) {
        if (!acceptsForward(eventGeneration)
                || activationStage
                != ActivationStage.PERMIT_SEND_PENDING
                || activationAttempt != eventActivationAttempt) {
            return List.of();
        }
        if (!success) {
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        activationStage =
                ActivationStage.WAITING_SESSION_REQUEST;
        return List.of();
    }

    synchronized List<Action> onActivationRequest(
            long eventGeneration) {
        if (!acceptsForward(eventGeneration)) {
            // Live 0.2.300: ProxyActivation is a service request from the
            // Watch, not a forward checkpoint step. After a synthetic
            // ACTIVATION_CONFIRMED latch the pipeline parks in
            // VERIFYING_OPERATIONAL_HEALTH or COMPLETE while an erased Watch
            // still needs Albert; the owner re-arms the drive with
            // RETRY_ACTIVATION. Answer whenever the drive is armed; ignore
            // strays (a proxy is already in flight or the drive was never
            // armed) and never fail a finished pipeline.
            if (!acceptsActivationService(eventGeneration)) {
                return List.of();
            }
            if (activationStage
                    == ActivationStage.WAITING_SESSION_REQUEST) {
                return onActivationRequest(
                        eventGeneration,
                        activationAttempt,
                        MobileActivationHttpProxy.RequestKind.SESSION);
            }
            if (activationStage
                    == ActivationStage.WAITING_ACTIVATION_REQUEST) {
                return onActivationRequest(
                        eventGeneration,
                        activationAttempt,
                        MobileActivationHttpProxy.RequestKind.ACTIVATION);
            }
            return List.of();
        }
        MobileActivationHttpProxy.RequestKind kind;
        if (activationStage
                == ActivationStage.WAITING_SESSION_REQUEST) {
            kind =
                    MobileActivationHttpProxy.RequestKind.SESSION;
        } else if (activationStage
                == ActivationStage.WAITING_ACTIVATION_REQUEST) {
            kind =
                    MobileActivationHttpProxy.RequestKind.ACTIVATION;
        } else {
            return fail(
                    Failure.UNEXPECTED_EVENT);
        }
        return onActivationRequest(
                eventGeneration,
                activationAttempt,
                kind);
    }

    synchronized List<Action> onActivationRequest(
            long eventGeneration,
            int eventActivationAttempt,
            MobileActivationHttpProxy.RequestKind kind) {
        if ((!acceptsForward(eventGeneration)
                        && !acceptsActivationService(eventGeneration))
                || activationAttempt != eventActivationAttempt
                || kind == null) {
            return List.of();
        }
        if (kind == MobileActivationHttpProxy.RequestKind.SESSION) {
            activationStage =
                    ActivationStage.SESSION_PROXY_PENDING;
            return List.of(
                    Action.activation(
                            generation,
                            ActionType.EXECUTE_ACTIVATION_HTTPS,
                            kind,
                            activationAttempt));
        }
        if (kind == MobileActivationHttpProxy.RequestKind.ACTIVATION) {
            activationStage =
                    ActivationStage.ACTIVATION_PROXY_PENDING;
            return List.of(
                    Action.activation(
                            generation,
                            ActionType.EXECUTE_ACTIVATION_HTTPS,
                            kind,
                            activationAttempt));
        }
        return fail(
                Failure.UNEXPECTED_EVENT);
    }

    synchronized List<Action> onActivationProxyResult(
            long eventGeneration,
            int eventActivationAttempt,
            MobileActivationHttpProxy.RequestKind kind,
            boolean success) {
        if ((!acceptsForward(eventGeneration)
                        && !acceptsActivationService(eventGeneration))
                || activationAttempt != eventActivationAttempt
                || kind == null) {
            return List.of();
        }
        ActivationStage expected =
                kind == MobileActivationHttpProxy.RequestKind.SESSION
                        ? ActivationStage.SESSION_PROXY_PENDING
                        : ActivationStage.ACTIVATION_PROXY_PENDING;
        if (activationStage != expected) {
            if (!acceptsForward(eventGeneration)) {
                // Late drive: a stray result must not fail a finished
                // pipeline.
                return List.of();
            }
            return fail(
                    Failure.UNEXPECTED_EVENT);
        }
        if (!success) {
            if (!acceptsForward(eventGeneration)) {
                // Late drive: re-arm so the Watch's next ProxyActivation is
                // serviced instead of failing the completed pipeline.
                activationStage =
                        kind == MobileActivationHttpProxy.RequestKind.SESSION
                                ? ActivationStage.WAITING_SESSION_REQUEST
                                : ActivationStage.WAITING_ACTIVATION_REQUEST;
                return List.of();
            }
            return fail(
                    Failure.ACTIVATION_PROXY);
        }
        activationStage =
                kind == MobileActivationHttpProxy.RequestKind.SESSION
                        ? ActivationStage.SESSION_DATA_SEND_PENDING
                        : ActivationStage.ACTIVATION_DATA_SEND_PENDING;
        return List.of(
                Action.activation(
                        generation,
                        ActionType.SEND_ACTIVATION_DATA,
                        kind,
                        activationAttempt));
    }

    synchronized List<Action> onActivationDataSendResult(
            long eventGeneration,
            int eventActivationAttempt,
            MobileActivationHttpProxy.RequestKind kind,
            boolean success) {
        if ((!acceptsForward(eventGeneration)
                        && !acceptsActivationService(eventGeneration))
                || activationAttempt != eventActivationAttempt
                || kind == null) {
            return List.of();
        }
        ActivationStage expected =
                kind == MobileActivationHttpProxy.RequestKind.SESSION
                        ? ActivationStage.SESSION_DATA_SEND_PENDING
                        : ActivationStage.ACTIVATION_DATA_SEND_PENDING;
        if (activationStage != expected) {
            if (!acceptsForward(eventGeneration)) {
                return List.of();
            }
            return fail(
                    Failure.UNEXPECTED_EVENT);
        }
        if (!success) {
            if (!acceptsForward(eventGeneration)) {
                // Late drive: the Watch never got its data and will
                // re-request; re-arm instead of failing the pipeline.
                activationStage =
                        kind == MobileActivationHttpProxy.RequestKind.SESSION
                                ? ActivationStage.WAITING_SESSION_REQUEST
                                : ActivationStage.WAITING_ACTIVATION_REQUEST;
                return List.of();
            }
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        if (kind == MobileActivationHttpProxy.RequestKind.SESSION) {
            activationStage =
                    ActivationStage.WAITING_ACTIVATION_REQUEST;
            // Live 0.2.302: on the Watch, beganAwaitingAbilityToActivate only
            // parks PBBridge in state 2 — the ability gate (CanBeginActivation,
            // message 11) is phone-side orchestration and must be re-asserted
            // after the drmHandshake session data lands. Without it the Watch
            // never proceeds to the deviceActivation ProxyActivation (live
            // stalls 03:39/03:45). The send-result of this permit is ignored
            // (stage is already past PERMIT_SEND_PENDING).
            return List.of(
                    Action.activation(
                            generation,
                            ActionType.SEND_ACTIVATION_PERMIT,
                            null,
                            activationAttempt));
        }
        activationStage =
                ActivationStage.WAITING_OUTCOME;
        return List.of();
    }

    /**
     * Owner-driven activation redrive. The durable ACTIVATION_CONFIRMED
     * checkpoint can be synthetic (owner-forced on a previous Watch erase
     * cycle): the current Watch then sits unactivated while the pipeline
     * parks and never re-asserts CanBeginActivation. The redrive downgrades
     * the durable checkpoint to IS_PAIRED_COMMITTED (keys, bond and commit
     * are preserved by the record) and, once persisted, replays the
     * post-activation drive: a fresh permit followed by the NanoRegistry-
     * normal and initial-sync preparation sends. Live 0.2.300: accepted in
     * every live phase (VERIFYING_OPERATIONAL_HEALTH/COMPLETE included) —
     * that is exactly where a synthetically-latched pipeline parks.
     */
    synchronized List<Action> onActivationRedriveRequested(
            long eventGeneration) {
        if (!acceptsActivationService(eventGeneration)
                || activationRedrivePending) {
            return List.of();
        }
        if (durableState.wireValue()
                < PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            // Nothing persisted to clear; just re-assert the permit when the
            // activation drive has not started yet.
            if (phase == Phase.FORWARD_SETUP
                    && activationStage
                    == ActivationStage.NOT_STARTED) {
                activationAttempt++;
                activationStage =
                        ActivationStage.PERMIT_SEND_PENDING;
                return List.of(
                        Action.activation(
                                generation,
                                ActionType.SEND_ACTIVATION_PERMIT,
                                null,
                                activationAttempt));
            }
            return List.of();
        }
        activationRedrivePending =
                true;
        return List.of(
                Action.redriveActivation(
                        generation));
    }

    synchronized List<Action> onActivationRedrivePersisted(
            long eventGeneration,
            boolean success) {
        if (!acceptsActivationService(eventGeneration)
                || !activationRedrivePending) {
            return List.of();
        }
        activationRedrivePending =
                false;
        if (!success) {
            if (!acceptsForward(eventGeneration)) {
                // Late drive: a persist failure must not kick a finished
                // pipeline into recovery.
                return List.of();
            }
            return fail(
                    Failure.LOCAL_CHECKPOINT);
        }
        durableState =
                PairingSessionRecord.DurableState
                        .IS_PAIRED_COMMITTED;
        activationStage =
                ActivationStage.NOT_STARTED;
        activationOutcomeObserved =
                false;
        gizmoFinishedBeforeSession =
                false;
        // The earlier NanoRegistry-normal (36) and initial-sync preparation
        // (21) crossed the wire while the Watch was still unactivated and
        // could not act on them; replay both after the fresh activation
        // confirms, exactly as the iPhone drive does.
        pbBridgeNormalSent =
                false;
        pbBridgeNormalSendInFlight =
                false;
        initialSyncPrepared =
                false;
        initialSyncRequestInFlight =
                false;
        initialSyncRequestIdentifier =
                null;
        pairedSyncSent =
                false;
        pairedSyncSendInFlight =
                false;
        pairedSyncObserved =
                false;
        return resumeForwardSetup();
    }

    /**
     * Owner-driven PBBridge RetryActivation (message 15). watchOS 26.6
     * -[ActivationController retryActivation:] performs _cleanup, forces the
     * activation state machine back to Idle and re-runs _startActivation
     * unconditionally — unlike CanBeginActivation (11), which only reaches
     * the controller while the Setup UI is still observing the
     * activation-ability notification. After the send we must be in
     * WAITING_SESSION_REQUEST so the Watch-driven ProxyActivation fetch is
     * accepted.
     */
    synchronized List<Action> onActivationRetryRequested(
            long eventGeneration) {
        // Live 0.2.300: owner override — accepted in every live phase. The
        // whole point of the override is to recover a Watch that stayed
        // unactivated behind a synthetic ACTIVATION_CONFIRMED latch, so the
        // durable-state and outcome gates must not block it.
        if (!acceptsActivationService(eventGeneration)) {
            return List.of();
        }
        activationAttempt++;
        activationStage =
                ActivationStage.WAITING_SESSION_REQUEST;
        activationOutcomeObserved =
                false;
        return List.of(
                Action.retryActivation(
                        generation,
                        activationAttempt));
    }

    synchronized List<Action> onActivationRetrySendResult(
            long eventGeneration,
            int eventActivationAttempt,
            boolean success) {
        if (!acceptsForward(eventGeneration)
                || activationAttempt != eventActivationAttempt) {
            return List.of();
        }
        // The retry is opportunistic: a transport failure must not fail the
        // pipeline; the Watch may still answer an earlier permit.
        return List.of();
    }

    synchronized List<Action> onActivationSucceeded(
            long eventGeneration) {
        if (!acceptsForward(eventGeneration)
                || activationOutcomeObserved
                || durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            return List.of();
        }
        if (activationStage == ActivationStage.NOT_STARTED) {
            return fail(
                    Failure.UNEXPECTED_EVENT);
        }
        // Live 0.2.197: the Watch emitted empty protobuf 4 about a second
        // after CanBeginActivation, before any ProxyActivation. That message
        // is gizmoDidFinishActivating, not an Albert result. Confirming
        // activation from it skipped the real activation exchange.
        if (activationStage != ActivationStage.WAITING_OUTCOME
                && activationStage
                != ActivationStage.WAITING_ACTIVATION_REQUEST) {
            return List.of();
        }
        // Live 0.2.303: an already-activated Watch driven through
        // RetryActivation answers the re-asserted CanBeginActivation with
        // protobuf 4 right after the drmHandshake session data lands — it
        // never opens the deviceActivation ProxyActivation, so the stage is
        // WAITING_ACTIVATION_REQUEST rather than WAITING_OUTCOME. Unlike
        // 0.2.197 the full authenticated SESSION exchange did happen in
        // this session, so the finished report is a real activation verdict
        // and the ACTIVATION_CONFIRMED checkpoint must latch; otherwise the
        // pipeline parks here forever with the Watch waiting in Buddy.
        activationOutcomeObserved = true;
        activationStage =
                ActivationStage.CONFIRMED;
        return requestCheckpoint(
                PairingSessionRecord.DurableState
                        .ACTIVATION_CONFIRMED);
    }

    synchronized List<Action> onActivationFailed(
            long eventGeneration) {
        if (!acceptsForward(eventGeneration)) {
            return List.of();
        }
        return fail(
                Failure.ACTIVATION_REJECTED);
    }

    /**
     * Watch-reported activation failure after our activation data went out.
     * Live 0.2.167: tearing the link down here strands the Watch in a
     * bonded-but-unactivated limbo where it never re-requests activation
     * (verified live: reconnect sessions got no ProxyActivation within 20 s
     * even after a full NanoRegistry initial-setup replay). iOS keeps the
     * link and lets the Watch retry ProxyActivation in-session, so a
     * WAITING_OUTCOME failure rewinds to WAITING_SESSION_REQUEST instead of
     * failing the session. Failures at any other stage remain terminal.
     */
    synchronized List<Action> onActivationFailedRetryable(
            long eventGeneration) {
        if ((!acceptsForward(eventGeneration)
                        && !acceptsActivationService(eventGeneration))
                || activationOutcomeObserved) {
            return List.of();
        }
        if (activationStage
                != ActivationStage.WAITING_OUTCOME) {
            if (!acceptsForward(eventGeneration)) {
                // Late drive: ignore strays, never fail a finished pipeline.
                return List.of();
            }
            return fail(
                    Failure.ACTIVATION_REJECTED);
        }
        activationAttempt++;
        activationStage =
                ActivationStage.WAITING_SESSION_REQUEST;
        return List.of();
    }

    synchronized List<Action> onPrepareInitialSyncSent(
            long eventGeneration,
            boolean success,
            String requestIdentifier) {
        if (!acceptsForward(eventGeneration)
                || !initialSyncRequestInFlight) {
            return List.of();
        }
        if (!success) {
            initialSyncRequestInFlight = false;
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        initialSyncRequestIdentifier =
                canonicalUuid(
                        requestIdentifier);
        return List.of();
    }

    synchronized List<Action> onPrepareInitialSyncResponse(
            long eventGeneration,
            String peerResponseIdentifier) {
        if (!acceptsForward(eventGeneration)
                || !initialSyncRequestInFlight
                || initialSyncRequestIdentifier == null
                || initialSyncPrepared) {
            return List.of();
        }
        String canonical =
                canonicalUuid(
                        peerResponseIdentifier);
        if (!initialSyncRequestIdentifier.equals(
                canonical)) {
            return List.of();
        }
        initialSyncPrepared = true;
        initialSyncRequestInFlight = false;
        initialSyncRequestIdentifier = null;
        return appendProgressActions();
    }

    synchronized List<Action> onNormalTransitionPrerequisitesSatisfied(
            long eventGeneration) {
        if (!acceptsForward(eventGeneration)
                || !activationDurableOrPending()
                || !languageAndLocaleComplete
                || !initialSyncPrepared
                || !pbBridgeNormalPrerequisitesRequested
                || pbBridgeNormalSent
                || pbBridgeNormalSendInFlight) {
            return List.of();
        }
        pbBridgeNormalSendInFlight = true;
        return List.of(
                Action.simple(
                        generation,
                        ActionType.SEND_PB_BRIDGE_NORMAL));
    }

    synchronized List<Action> onNormalStateSendResult(
            long eventGeneration,
            boolean success) {
        if (!acceptsForward(eventGeneration)
                || !pbBridgeNormalSendInFlight) {
            return List.of();
        }
        pbBridgeNormalSendInFlight = false;
        if (!success) {
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        pbBridgeNormalSent = true;
        // Normal-state (36) is now on the wire: PrepareWatchForInitialSync
        // (21) becomes eligible in the same pass instead of waiting for an
        // unrelated future coordinator event.
        return appendProgressActions();
    }

    synchronized List<Action> onCompatibilityStateObserved(
            long eventGeneration,
            int compatibilityState) {
        if (!acceptsForward(eventGeneration)) {
            return List.of();
        }
        if (compatibilityState
                == COMPATIBILITY_STATE_NORMAL) {
            watchCompatibilityNormalObserved = true;
            return appendProgressActions();
        }
        return List.of();
    }

    synchronized List<Action> onIsSetupObserved(
            long eventGeneration,
            boolean isSetup) {
        if (!acceptsForward(eventGeneration)
                || !isSetup
                || isSetupObserved) {
            return List.of();
        }
        isSetupObserved = true;
        return appendProgressActions();
    }

    synchronized List<Action> onPairedSyncCapabilityObserved(
            long eventGeneration,
            PairedSyncCapabilityState.Status status) {
        if (!acceptsForward(eventGeneration)
                || status == null
                || status == PairedSyncCapabilityState.Status.UNKNOWN) {
            return List.of();
        }
        pairedSyncCapability =
                status;
        return appendProgressActions();
    }

    synchronized List<Action> onPairedSyncSendResult(
            long eventGeneration,
            boolean success) {
        if (!acceptsForward(eventGeneration)
                || !pairedSyncSendInFlight) {
            return List.of();
        }
        pairedSyncSendInFlight = false;
        if (!success) {
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        pairedSyncSent = true;
        return appendProgressActions();
    }

    synchronized List<Action> onWatchPairedSyncCompletionObserved(
            long eventGeneration,
            int watchSyncProgressState,
            int watchGlobalProgress,
            int watchClientSyncProgressState,
            int watchPairedSyncCompleteState) {
        if (!acceptsForward(eventGeneration)
                || pairedSyncObserved) {
            return List.of();
        }
        if (!pairedSyncSent
                || watchSyncProgressState
                != PAIRED_SYNC_PROGRESS_COMPLETE
                || watchGlobalProgress
                != PAIRED_SYNC_GLOBAL_PROGRESS_COMPLETE
                || watchClientSyncProgressState
                != PAIRED_SYNC_PROGRESS_COMPLETE
                || watchPairedSyncCompleteState
                != WATCH_PAIRED_SYNC_COMPLETE_STATE) {
            return fail(
                    Failure.INVALID_PAIRED_SYNC_EVIDENCE);
        }
        pairedSyncObserved = true;
        return requestCheckpoint(
                PairingSessionRecord.DurableState
                        .PAIRED_SYNC_COMPLETE);
    }

    /** The peer published complete PSYWatchSyncState and PSYWatchSyncClientState. */
    synchronized List<Action> onPeerPairedSyncCompletionObserved(long eventGeneration) {
        if (!acceptsForward(eventGeneration) || !pairedSyncSent || pairedSyncObserved) {
            return List.of();
        }
        pairedSyncObserved = true;
        // DurableState may still be ACTIVATION_CONFIRMED: checkpoints advance
        // by exactly one, so PAIRED_SYNC_COMPLETE is requested from
        // appendProgressActions once IS_SETUP_CONFIRMED has been applied.
        return appendProgressActions();
    }

    /** PBBridge type 4 reports activation only, even if received after sync publication. */
    synchronized List<Action> onGizmoDidFinishActivating(long eventGeneration) {
        // Live 0.2.303: the durable record can claim SETUP_COMPLETE or
        // beyond while the physical Watch was erased afterwards and just
        // re-ran a fresh authenticated activation in THIS session (the
        // stage only reaches WAITING_ACTIVATION_REQUEST/WAITING_OUTCOME by
        // delivering real Albert session/activation data now). Resuming at
        // the stale terminal state parks the coordinator in COMPLETE and
        // the Buddy chain (language, NanoRegistry-normal 36, initial-sync
        // 21, paired-sync completion) never crosses the wire again — the
        // Watch sits in Setup forever (live 2026-10-05 04:19). Reuse the
        // proven activation-redrive: it downgrades the persisted record to
        // IS_PAIRED_COMMITTED and replays the full forward setup, exactly
        // what an erased-then-reactivated Watch needs.
        if (generation == eventGeneration
                && !activationOutcomeObserved
                && (activationStage
                        == ActivationStage.WAITING_ACTIVATION_REQUEST
                || activationStage
                        == ActivationStage.WAITING_OUTCOME)
                && durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .SETUP_COMPLETE.wireValue()
                && !activationRedrivePending) {
            activationRedrivePending =
                    true;
            return List.of(
                    Action.redriveActivation(
                            generation));
        }
        if (!acceptsForward(eventGeneration)) {
            return List.of();
        }
        if (!activationOutcomeObserved
                && durableState.wireValue()
                < PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            // Live 0.2.208: each flushed CanBeginActivation was answered in
            // ~120 ms by empty protobuf 4 and no ProxyActivation. That is
            // setCanBeginActivating:'s already-activated finished report.
            // Latch it and keep waiting: a real session still wins if it
            // arrives inside the permit grace (live 0.2.197).
            gizmoFinishedBeforeSession = true;
            return List.of();
        }
        // Setup::finishedActivating and setCanBeginActivating's already-activated
        // branch send this message before Buddy/PSY completes (watchOS 26.6).
        // Only a real NanoRegistry IsSetup report and peer PSY state can advance
        // those barriers; neither a type 4 nor our send receipt proves them.
        return List.of();
    }

    /**
     * The flushed CanBeginActivation grace ended with no ProxyActivation,
     * and the only activation message was empty protobuf 4.
     *
     * <p>This records that the Watch already finished activation and will
     * not open another Albert session. It does not claim a new Albert
     * exchange and does not confirm Buddy, PairedSync or a visible Clock.</p>
     */
    synchronized List<Action> onAlreadyActivatedWatchAccepted(
            long eventGeneration) {
        if (!acceptsForward(eventGeneration)
                || !gizmoFinishedBeforeSession
                || activationOutcomeObserved
                || activationStage
                != ActivationStage.WAITING_SESSION_REQUEST
                || durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            return List.of();
        }
        activationOutcomeObserved = true;
        activationStage =
                ActivationStage.CONFIRMED;
        return requestCheckpoint(
                PairingSessionRecord.DurableState
                        .ACTIVATION_CONFIRMED);
    }

    synchronized List<Action> onIdsSetupCompletedDispatchResult(
            long eventGeneration,
            boolean success) {
        if (!acceptsForward(eventGeneration)
                || !idsSetupCompletedSendInFlight) {
            return List.of();
        }
        idsSetupCompletedSendInFlight = false;
        if (!success) {
            return fail(
                    Failure.TRANSPORT_SEND);
        }
        return requestCheckpoint(
                PairingSessionRecord.DurableState.SETUP_COMPLETE);
    }

    synchronized List<Action> onClockVisibleObserved(
            long eventGeneration,
            int mainUiState,
            int mainUiEvent,
            int transitionReason,
            boolean setupProcessTerminated) {
        if (!accepts(eventGeneration)
                || phase != Phase.WAITING_FOR_CLOCK) {
            return List.of();
        }
        if (mainUiState != CAROUSEL_CLOCK_STATE
                || mainUiEvent != CAROUSEL_BUDDY_COMPLETED_EVENT
                || transitionReason != CAROUSEL_BUDDY_TRANSITION_REASON
                || !setupProcessTerminated) {
            return fail(
                    Failure.INVALID_CLOCK_EVIDENCE);
        }
        return requestCheckpoint(
                PairingSessionRecord.DurableState
                        .CLOCK_VISIBLE_CONFIRMED);
    }

    synchronized List<Action> onOperationalHealthObserved(
            long eventGeneration,
            OperationalHealthEvidence evidence) {
        if (!accepts(eventGeneration)
                || phase
                != Phase.VERIFYING_OPERATIONAL_HEALTH) {
            return List.of();
        }
        if (evidence == null
                || !evidence.complete()) {
            return fail(
                    Failure.INVALID_OPERATIONAL_HEALTH_EVIDENCE);
        }
        return requestCheckpoint(
                PairingSessionRecord.DurableState
                        .OPERATIONAL_HEALTH_CONFIRMED);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                generation,
                phase,
                durableState,
                checkpointPending,
                phase == Phase.SAFE_STOP_BEFORE_COMMIT
                        && !commitIntentPersisted,
                irreversibleCommitObserved,
                commitIntentPersisted
                        && durableState
                        == PairingSessionRecord.DurableState
                        .READY_TO_COMMIT_IS_PAIRED,
                pairingModeNormalSent,
                activationAttempt,
                activationOutcomeObserved,
                languageAndLocaleComplete,
                initialSyncPrepared,
                pbBridgeNormalSent,
                watchCompatibilityNormalObserved,
                isSetupObserved,
                pairedSyncCapability,
                pairedSyncSent,
                pairedSyncObserved,
                gizmoFinishedBeforeSession);
    }

    private List<Action> resumeForwardSetup() {
        if (durableState
                == PairingSessionRecord.DurableState
                .OPERATIONAL_HEALTH_CONFIRMED) {
            phase =
                    Phase.COMPLETE;
            return List.of();
        }

        if (durableState
                == PairingSessionRecord.DurableState
                .CLOCK_VISIBLE_CONFIRMED) {
            phase =
                    Phase.VERIFYING_OPERATIONAL_HEALTH;
            return List.of(
                    Action.simple(
                            generation,
                            ActionType.VERIFY_OPERATIONAL_RECONNECT));
        }
        if (durableState
                == PairingSessionRecord.DurableState.SETUP_COMPLETE) {
            phase =
                    Phase.WAITING_FOR_CLOCK;
            return List.of(
                    Action.simple(
                            generation,
                            ActionType.VERIFY_CLOCK_VISIBLE));
        }

        phase =
                Phase.FORWARD_SETUP;
        List<Action> actions =
                new ArrayList<>();
        if (!pairingModeNormalSent && !pairingModeNormalSendInFlight) {
            pairingModeNormalSendInFlight = true;
            actions.add(
                    Action.pairingModeNormal(
                            generation,
                            runtimeWatchPairingVersion));
        }
        if (!computedTimeZoneSent) {
            actions.add(
                    Action.simple(
                            generation,
                            ActionType.SEND_COMPUTED_TIME_ZONE));
        }
        if (!languageAndLocaleSent) {
            actions.add(
                    Action.simple(
                            generation,
                            ActionType.SEND_LANGUAGE_AND_LOCALE));
        }
        if (durableState.wireValue()
                < PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()
                && activationStage
                == ActivationStage.NOT_STARTED) {
            activationAttempt++;
            activationStage =
                    ActivationStage.PERMIT_SEND_PENDING;
            actions.add(
                    Action.activation(
                            generation,
                            ActionType.SEND_ACTIVATION_PERMIT,
                            null,
                            activationAttempt));
        }
        actions.addAll(
                appendProgressActionsMutable());
        return immutable(
                actions);
    }

    private List<Action> appendProgressActions() {
        if (phase != Phase.FORWARD_SETUP) {
            return List.of();
        }
        return immutable(
                appendProgressActionsMutable());
    }

    private List<Action> appendProgressActionsMutable() {
        List<Action> actions =
                new ArrayList<>();
        if (durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()
                && !languageAndLocaleComplete
                && !languageAndLocaleSent) {
            actions.add(
                    Action.simple(
                            generation,
                            ActionType.SEND_LANGUAGE_AND_LOCALE));
        }
        if (durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()
                && languageAndLocaleComplete
                && !pbBridgeNormalSent
                && !pbBridgeNormalSendInFlight) {
            // watchOS 26.6 SetupController.updateNanoRegisryToNormalState:
            // message 36 is the Watch-side activation COMMIT
            // (NRPairedDeviceRegistry.notifyActivationCompleted:1). The
            // iPhone sends it before PrepareWatchForInitialSync; sending 21
            // first left the Watch setup pipeline parked and the ERTM
            // channel application-gated into a one-way freeze.
            pbBridgeNormalSendInFlight = true;
            actions.add(
                    Action.simple(
                            generation,
                            ActionType.SEND_PB_BRIDGE_NORMAL));
        }
        if (durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()
                && languageAndLocaleComplete
                && pbBridgeNormalSent
                && !initialSyncPrepared
                && !initialSyncRequestInFlight) {
            initialSyncRequestInFlight = true;
            actions.add(
                    Action.simple(
                            generation,
                            ActionType.SEND_PREPARE_INITIAL_SYNC));
        }
        if (durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()
                && durableState.wireValue()
                < PairingSessionRecord.DurableState
                .SETUP_COMPLETE.wireValue()
                && !pbBridgeNormalPrerequisitesRequested
                && !pbBridgeNormalSent
                && !pbBridgeNormalSendInFlight) {
            pbBridgeNormalPrerequisitesRequested = true;
            actions.add(
                    Action.simple(
                            generation,
                            ActionType
                                    .WAIT_FOR_PB_BRIDGE_NORMAL_PREREQUISITES));
        }

        if (durableState
                == PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED
                && isSetupObserved
                && checkpointPending == null) {
            actions.addAll(
                    requestCheckpointMutable(
                            PairingSessionRecord.DurableState
                                    .IS_SETUP_CONFIRMED));
            return actions;
        }

        if (durableState
                == PairingSessionRecord.DurableState
                .IS_SETUP_CONFIRMED
                && pairedSyncObserved
                && checkpointPending == null) {
            actions.addAll(
                    requestCheckpointMutable(
                            PairingSessionRecord.DurableState
                                    .PAIRED_SYNC_COMPLETE));
            return actions;
        }

        // watchOS 26.6 ordering, verified against the Setup/PBBridgeSupport
        // binaries: the phone PUBLISHES the PairedSync completion state
        // (PSYWatchSyncState syncProgressState=3) to make the Watch finish;
        // a Watch isSetup observation is separate from the activation-only
        // gizmoDidFinishActivating report (protobuf 4). Gating the publication on isSetup
        // deadlocked the pipeline: the Watch waited for our completion state
        // while we waited for its isSetup. The pairing-mode response
        // (watchCompatibilityNormalObserved) is not used as a prerequisite
        // here. Response 18 confirms processing of the prepare request;
        // native doInitialSyncPrep sends it even if its delegate cannot
        // prepare, so it does not prove that Normal (36) was applied.
        // The Class-C capabilities property is not required either: it only
        // reaches us with a full property dump, which a direct reconnect of
        // an already-committed session never requests — while the correlated
        // protobuf 18 response proves the Watch runs the initial-sync /
        // paired-sync pipeline (live 0.2.185: capability stayed UNKNOWN with
        // the link otherwise ready, and the publication never fired).
        if (durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()
                && durableState.wireValue()
                < PairingSessionRecord.DurableState
                .PAIRED_SYNC_COMPLETE.wireValue()
                && languageAndLocaleComplete
                && initialSyncPrepared
                && pbBridgeNormalSent
                && computedTimeZoneSent
                && !pairedSyncSent
                && !pairedSyncSendInFlight) {
            pairedSyncSendInFlight = true;
            actions.add(
                    Action.simple(
                            generation,
                            ActionType.SEND_PAIRED_SYNC_COMPLETION));
        }

        if (durableState
                == PairingSessionRecord.DurableState
                .PAIRED_SYNC_COMPLETE
                && !idsSetupCompletedSendInFlight) {
            idsSetupCompletedSendInFlight = true;
            actions.add(
                    Action.simple(
                            generation,
                            ActionType
                                    .SEND_IDS_LOCAL_PAIRING_SETUP_COMPLETED));
        }
        return actions;
    }

    private List<Action> requestCheckpoint(
            PairingSessionRecord.DurableState next) {
        return immutable(
                requestCheckpointMutable(
                        next));
    }

    private List<Action> requestCheckpointMutable(
            PairingSessionRecord.DurableState next) {
        if (checkpointPending != null) {
            return List.of();
        }
        if (!isValidCheckpointSuccessor(
                durableState,
                next)) {
            return failMutable(
                    Failure.UNEXPECTED_EVENT);
        }
        checkpointPending =
                next;
        phase =
                Phase.CHECKPOINT_PENDING;
        return List.of(
                Action.checkpoint(
                        generation,
                        next));
    }

    private List<Action> fail(
            Failure failure) {
        return immutable(
                failMutable(
                        failure));
    }

    private List<Action> failMutable(
            Failure failure) {
        phase =
                Phase.FORWARD_RECOVERY_REQUIRED;
        return List.of(
                Action.failure(
                        generation,
                        failure));
    }

    private void inferDurableEvidence() {
        if (durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            activationOutcomeObserved = true;
            activationStage =
                    ActivationStage.CONFIRMED;
        }
        if (durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .IS_SETUP_CONFIRMED.wireValue()) {
            isSetupObserved = true;
        }
        if (durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .PAIRED_SYNC_COMPLETE.wireValue()) {
            pairedSyncObserved = true;
            pairedSyncSent = true;
            languageAndLocaleComplete = true;
            initialSyncPrepared = true;
            pbBridgeNormalSent = true;
            watchCompatibilityNormalObserved = true;
            computedTimeZoneSent = true;
        }
    }

    private boolean activationDurableOrPending() {
        return activationOutcomeObserved
                || durableState.wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()
                || checkpointPending
                == PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED;
    }

    private boolean accepts(
            long eventGeneration) {
        return phase != Phase.IDLE
                && phase != Phase.COMPLETE
                && phase != Phase.FORWARD_RECOVERY_REQUIRED
                && generation == eventGeneration;
    }

    private boolean acceptsForward(
            long eventGeneration) {
        return accepts(eventGeneration)
                && (phase == Phase.FORWARD_SETUP
                || phase == Phase.CHECKPOINT_PENDING);
    }

    /**
     * Activation-service events (the owner RETRY_ACTIVATION /
     * REDRIVE_ACTIVATION overrides, Watch-driven ProxyActivation and its
     * proxy/data results) are a service to the Watch, not forward checkpoint
     * steps. Live 0.2.300: after a synthetic ACTIVATION_CONFIRMED latch the
     * pipeline parks in VERIFYING_OPERATIONAL_HEALTH or COMPLETE while an
     * erased Watch still needs Albert, and gating the drive to forward
     * phases stranded the Watch unactivated with no way back. Accepted in
     * every live phase, COMPLETE included; the generation must still match.
     */
    private boolean acceptsActivationService(
            long eventGeneration) {
        return phase != Phase.IDLE
                && phase != Phase.FORWARD_RECOVERY_REQUIRED
                && generation == eventGeneration;
    }

    private static boolean isValidCheckpointSuccessor(
            PairingSessionRecord.DurableState current,
            PairingSessionRecord.DurableState next) {
        if (current
                == PairingSessionRecord.DurableState
                .READY_TO_COMMIT_IS_PAIRED) {
            return next
                    == PairingSessionRecord.DurableState
                    .IS_PAIRED_COMMITTED;
        }
        return next.wireValue()
                == current.wireValue() + 1;
    }

    private static void requireSupportedState(
            PairingSessionRecord.DurableState state) {
        if (state == null
                || state.wireValue()
                < PairingSessionRecord.DurableState
                .READY_TO_COMMIT_IS_PAIRED.wireValue()
                || state.wireValue()
                > PairingSessionRecord.DurableState
                .OPERATIONAL_HEALTH_CONFIRMED.wireValue()) {
            throw new IllegalArgumentException(
                    "Post-commit durable state is unsupported");
        }
    }

    private static void requireRuntimeWatchPairingVersion(
            int value) {
        if (value
                < NanoRegistryClassDCodec
                .IOS_26_6_ULTRA2_PHONE_MIN_VERSION
                || value
                > NanoRegistryClassDCodec
                .IOS_26_6_PHONE_MAX_VERSION) {
            throw new IllegalArgumentException(
                    "Ultra 2 runtime pairing version is incompatible");
        }
    }

    private static String canonicalUuid(
            String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "IDS response identifier is absent");
        }
        UUID parsed;
        try {
            parsed =
                    UUID.fromString(
                            value);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(
                    "IDS response identifier is malformed",
                    malformed);
        }
        String canonical =
                parsed.toString();
        if (!canonical.equalsIgnoreCase(
                value)) {
            throw new IllegalArgumentException(
                    "IDS response identifier is not canonical");
        }
        return canonical;
    }

    private static List<Action> immutable(
            List<Action> actions) {
        if (actions.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(
                new ArrayList<>(
                        actions));
    }

    static final class Action {
        final long generation;
        final ActionType type;
        final PairingSessionRecord.DurableState durableState;
        final MobileActivationHttpProxy.RequestKind activationKind;
        final int activationAttempt;
        final Integer runtimeWatchPairingVersion;
        final Failure failure;

        private Action(
                long generation,
                ActionType type,
                PairingSessionRecord.DurableState durableState,
                MobileActivationHttpProxy.RequestKind activationKind,
                int activationAttempt,
                Integer runtimeWatchPairingVersion,
                Failure failure) {
            this.generation =
                    generation;
            this.type =
                    type;
            this.durableState =
                    durableState;
            this.activationKind =
                    activationKind;
            this.activationAttempt =
                    activationAttempt;
            this.runtimeWatchPairingVersion =
                    runtimeWatchPairingVersion;
            this.failure =
                    failure;
        }

        static Action simple(
                long generation,
                ActionType type) {
            return new Action(
                    generation,
                    type,
                    null,
                    null,
                    0,
                    null,
                    null);
        }

        static Action checkpoint(
                long generation,
                PairingSessionRecord.DurableState durableState) {
            return new Action(
                    generation,
                    ActionType.PERSIST_DURABLE_STATE,
                    durableState,
                    null,
                    0,
                    null,
                    null);
        }

        static Action redriveActivation(
                long generation) {
            return new Action(
                    generation,
                    ActionType.PERSIST_ACTIVATION_REDRIVE,
                    null,
                    null,
                    0,
                    null,
                    null);
        }

        static Action retryActivation(
                long generation,
                int activationAttempt) {
            return new Action(
                    generation,
                    ActionType.SEND_ACTIVATION_RETRY,
                    null,
                    null,
                    activationAttempt,
                    null,
                    null);
        }

        static Action activation(
                long generation,
                ActionType type,
                MobileActivationHttpProxy.RequestKind activationKind,
                int activationAttempt) {
            return new Action(
                    generation,
                    type,
                    null,
                    activationKind,
                    activationAttempt,
                    null,
                    null);
        }

        static Action pairingModeNormal(
                long generation,
                int runtimeWatchPairingVersion) {
            requireRuntimeWatchPairingVersion(
                    runtimeWatchPairingVersion);
            return new Action(
                    generation,
                    ActionType.SEND_PAIRING_MODE_NORMAL,
                    null,
                    null,
                    0,
                    runtimeWatchPairingVersion,
                    null);
        }

        static Action failure(
                long generation,
                Failure failure) {
            return new Action(
                    generation,
                    ActionType.REPORT_FAILURE,
                    null,
                    null,
                    0,
                    null,
                    failure);
        }

        NanoRegistryClassDCodec.PairingModeRequest
                pairingModeNormalMessage() {
            if (type != ActionType.SEND_PAIRING_MODE_NORMAL
                    || runtimeWatchPairingVersion == null) {
                throw new IllegalStateException(
                        "Action is not a Class-D PairingMode Normal send");
            }
            return NanoRegistryClassDCodec.PairingModeRequest
                    .modernIos26_6Ultra2(
                            NanoRegistryClassDCodec
                                    .COMPATIBILITY_STATE_NORMAL,
                            runtimeWatchPairingVersion);
        }

        PairingSessionRecord createCheckpoint(
                PairingSessionRecord current) {
            if (current == null) {
                throw new IllegalArgumentException(
                        "Current pairing checkpoint is absent");
            }
            if (type
                    == ActionType.PERSIST_IS_PAIRED_COMMIT_INTENT) {
                return current.prepareIsPairedCommit(
                        true);
            }
            if (type != ActionType.PERSIST_DURABLE_STATE
                    || durableState == null) {
                throw new IllegalStateException(
                        "Action does not contain a durable checkpoint");
            }
            if (durableState
                    == PairingSessionRecord.DurableState
                    .IS_PAIRED_COMMITTED) {
                return current.confirmIsPairedCommit(
                        true);
            }
            return current.advanceTo(
                    durableState);
        }
    }

    static final class OperationalHealthEvidence {
        final boolean stockBondPresent;
        final boolean encryptedReconnectAfterControlledRestart;
        final boolean classDStable;
        final boolean classCStable;
        final boolean idsControlReady;
        final boolean idsDataReady;
        final boolean normalAndIsSetupObserved;
        final boolean unrelatedBondBaselinePreserved;

        OperationalHealthEvidence(
                boolean stockBondPresent,
                boolean encryptedReconnectAfterControlledRestart,
                boolean classDStable,
                boolean classCStable,
                boolean idsControlReady,
                boolean idsDataReady,
                boolean normalAndIsSetupObserved,
                boolean unrelatedBondBaselinePreserved) {
            this.stockBondPresent =
                    stockBondPresent;
            this.encryptedReconnectAfterControlledRestart =
                    encryptedReconnectAfterControlledRestart;
            this.classDStable =
                    classDStable;
            this.classCStable =
                    classCStable;
            this.idsControlReady =
                    idsControlReady;
            this.idsDataReady =
                    idsDataReady;
            this.normalAndIsSetupObserved =
                    normalAndIsSetupObserved;
            this.unrelatedBondBaselinePreserved =
                    unrelatedBondBaselinePreserved;
        }

        boolean complete() {
            return stockBondPresent
                    && encryptedReconnectAfterControlledRestart
                    && classDStable
                    && classCStable
                    && idsControlReady
                    && idsDataReady
                    && normalAndIsSetupObserved
                    && unrelatedBondBaselinePreserved;
        }
    }

    static final class Snapshot {
        final long generation;
        final Phase phase;
        final PairingSessionRecord.DurableState durableState;
        final PairingSessionRecord.DurableState checkpointPending;
        final boolean safeToStopBeforeCommit;
        final boolean irreversibleCommitObserved;
        final boolean commitFinalizationPending;
        final boolean pairingModeNormalSent;
        final int activationAttempt;
        final boolean activationConfirmed;
        final boolean languageAndLocaleComplete;
        final boolean initialSyncPrepared;
        final boolean pbBridgeNormalSent;
        final boolean watchCompatibilityNormalObserved;
        final boolean isSetupObserved;
        final PairedSyncCapabilityState.Status pairedSyncCapability;
        final boolean pairedSyncSent;
        final boolean pairedSyncObserved;
        final boolean gizmoFinishedBeforeSession;

        private Snapshot(
                long generation,
                Phase phase,
                PairingSessionRecord.DurableState durableState,
                PairingSessionRecord.DurableState checkpointPending,
                boolean safeToStopBeforeCommit,
                boolean irreversibleCommitObserved,
                boolean commitFinalizationPending,
                boolean pairingModeNormalSent,
                int activationAttempt,
                boolean activationConfirmed,
                boolean languageAndLocaleComplete,
                boolean initialSyncPrepared,
                boolean pbBridgeNormalSent,
                boolean watchCompatibilityNormalObserved,
                boolean isSetupObserved,
                PairedSyncCapabilityState.Status pairedSyncCapability,
                boolean pairedSyncSent,
                boolean pairedSyncObserved,
                boolean gizmoFinishedBeforeSession) {
            this.generation =
                    generation;
            this.phase =
                    phase;
            this.durableState =
                    durableState;
            this.checkpointPending =
                    checkpointPending;
            this.safeToStopBeforeCommit =
                    safeToStopBeforeCommit;
            this.irreversibleCommitObserved =
                    irreversibleCommitObserved;
            this.commitFinalizationPending =
                    commitFinalizationPending;
            this.pairingModeNormalSent =
                    pairingModeNormalSent;
            this.activationAttempt =
                    activationAttempt;
            this.activationConfirmed =
                    activationConfirmed;
            this.languageAndLocaleComplete =
                    languageAndLocaleComplete;
            this.initialSyncPrepared =
                    initialSyncPrepared;
            this.pbBridgeNormalSent =
                    pbBridgeNormalSent;
            this.watchCompatibilityNormalObserved =
                    watchCompatibilityNormalObserved;
            this.isSetupObserved =
                    isSetupObserved;
            this.pairedSyncCapability =
                    pairedSyncCapability;
            this.pairedSyncSent = pairedSyncSent;
            this.pairedSyncObserved =
                    pairedSyncObserved;
            this.gizmoFinishedBeforeSession =
                    gizmoFinishedBeforeSession;
        }
    }
}
