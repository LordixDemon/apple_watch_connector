package dev.applewatchandroid.bridge;

import java.util.List;

/**
 * Albert/MobileActivation sub-state machine owned by one synchronized
 * {@link AppleWatchPostCommitCoordinator}. Every method must be invoked under
 * {@code coordinator}'s monitor lock.
 */
final class PostCommitActivationMachine {
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

    private final AppleWatchPostCommitCoordinator coordinator;
    private int activationAttempt;
    private ActivationStage activationStage =
            ActivationStage.NOT_STARTED;
    private boolean activationOutcomeObserved;
    private boolean activationRedrivePending;
    private boolean gizmoFinishedBeforeSession;

    PostCommitActivationMachine(AppleWatchPostCommitCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    int attempt() {
        return activationAttempt;
    }

    boolean outcomeObserved() {
        return activationOutcomeObserved;
    }

    boolean gizmoFinishedBeforeSession() {
        return gizmoFinishedBeforeSession;
    }

    void markDurablyConfirmed() {
        activationOutcomeObserved = true;
        activationStage =
                ActivationStage.CONFIRMED;
    }

    AppleWatchPostCommitCoordinator.Action startRelaunchNormalIfUnconfirmed() {
        if (!activationOutcomeObserved
                && coordinator.durableState().wireValue()
                < PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            activationAttempt++;
            activationStage =
                    ActivationStage.RELAUNCH_NORMAL_SEND_PENDING;
            return AppleWatchPostCommitCoordinator.Action.activation(
                    coordinator.generation(),
                    AppleWatchPostCommitCoordinator.ActionType
                            .SEND_NORMAL_AFTER_LANGUAGE_RELAUNCH,
                    null,
                    activationAttempt);
        }
        return null;
    }

    void appendInitialPermitIfNeeded(List<AppleWatchPostCommitCoordinator.Action> actions) {
        if (coordinator.durableState().wireValue()
                < PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()
                && activationStage
                == ActivationStage.NOT_STARTED) {
            activationAttempt++;
            activationStage =
                    ActivationStage.PERMIT_SEND_PENDING;
            actions.add(
                    AppleWatchPostCommitCoordinator.Action.activation(
                            coordinator.generation(),
                            AppleWatchPostCommitCoordinator.ActionType.SEND_ACTIVATION_PERMIT,
                            null,
                            activationAttempt));
        }
    }

    List<AppleWatchPostCommitCoordinator.Action> onRelaunchNormalSendResult(
            long eventGeneration,
            int eventActivationAttempt,
            boolean success) {
        if (!coordinator.acceptsForward(eventGeneration)
                || activationStage
                != ActivationStage.RELAUNCH_NORMAL_SEND_PENDING
                || activationAttempt != eventActivationAttempt) {
            return List.of();
        }
        if (!success) {
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.TRANSPORT_SEND);
        }
        activationStage =
                ActivationStage.PERMIT_SEND_PENDING;
        return List.of(
                AppleWatchPostCommitCoordinator.Action.activation(
                        coordinator.generation(),
                        AppleWatchPostCommitCoordinator.ActionType.SEND_ACTIVATION_PERMIT,
                        null,
                        activationAttempt));
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationPermitSendResult(
            long eventGeneration,
            int eventActivationAttempt,
            boolean success) {
        if (!coordinator.acceptsForward(eventGeneration)
                || activationStage
                != ActivationStage.PERMIT_SEND_PENDING
                || activationAttempt != eventActivationAttempt) {
            return List.of();
        }
        if (!success) {
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.TRANSPORT_SEND);
        }
        activationStage =
                ActivationStage.WAITING_SESSION_REQUEST;
        return List.of();
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationRequest(
            long eventGeneration) {
        if (!coordinator.acceptsForward(eventGeneration)) {
            // Live 0.2.300: ProxyActivation is a service request from the
            // Watch, not a forward checkpoint step. After a synthetic
            // ACTIVATION_CONFIRMED latch the pipeline parks in
            // VERIFYING_OPERATIONAL_HEALTH or COMPLETE while an erased Watch
            // still needs Albert; the owner re-arms the drive with
            // RETRY_ACTIVATION. Answer whenever the drive is armed; ignore
            // strays (a proxy is already in flight or the drive was never
            // armed) and never fail a finished pipeline.
            if (!coordinator.acceptsActivationService(eventGeneration)) {
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
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.UNEXPECTED_EVENT);
        }
        return onActivationRequest(
                eventGeneration,
                activationAttempt,
                kind);
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationRequest(
            long eventGeneration,
            int eventActivationAttempt,
            MobileActivationHttpProxy.RequestKind kind) {
        if ((!coordinator.acceptsForward(eventGeneration)
                        && !coordinator.acceptsActivationService(eventGeneration))
                || activationAttempt != eventActivationAttempt
                || kind == null) {
            return List.of();
        }
        if (kind == MobileActivationHttpProxy.RequestKind.SESSION) {
            activationStage =
                    ActivationStage.SESSION_PROXY_PENDING;
            return List.of(
                    AppleWatchPostCommitCoordinator.Action.activation(
                            coordinator.generation(),
                            AppleWatchPostCommitCoordinator.ActionType.EXECUTE_ACTIVATION_HTTPS,
                            kind,
                            activationAttempt));
        }
        if (kind == MobileActivationHttpProxy.RequestKind.ACTIVATION) {
            activationStage =
                    ActivationStage.ACTIVATION_PROXY_PENDING;
            return List.of(
                    AppleWatchPostCommitCoordinator.Action.activation(
                            coordinator.generation(),
                            AppleWatchPostCommitCoordinator.ActionType.EXECUTE_ACTIVATION_HTTPS,
                            kind,
                            activationAttempt));
        }
        return coordinator.fail(
                AppleWatchPostCommitCoordinator.Failure.UNEXPECTED_EVENT);
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationProxyResult(
            long eventGeneration,
            int eventActivationAttempt,
            MobileActivationHttpProxy.RequestKind kind,
            boolean success) {
        if ((!coordinator.acceptsForward(eventGeneration)
                        && !coordinator.acceptsActivationService(eventGeneration))
                || activationAttempt != eventActivationAttempt
                || kind == null) {
            return List.of();
        }
        ActivationStage expected =
                kind == MobileActivationHttpProxy.RequestKind.SESSION
                        ? ActivationStage.SESSION_PROXY_PENDING
                        : ActivationStage.ACTIVATION_PROXY_PENDING;
        if (activationStage != expected) {
            if (!coordinator.acceptsForward(eventGeneration)) {
                // Late drive: a stray result must not fail a finished
                // pipeline.
                return List.of();
            }
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.UNEXPECTED_EVENT);
        }
        if (!success) {
            if (!coordinator.acceptsForward(eventGeneration)) {
                // Late drive: re-arm so the Watch's next ProxyActivation is
                // serviced instead of failing the completed pipeline.
                activationStage =
                        kind == MobileActivationHttpProxy.RequestKind.SESSION
                                ? ActivationStage.WAITING_SESSION_REQUEST
                                : ActivationStage.WAITING_ACTIVATION_REQUEST;
                return List.of();
            }
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.ACTIVATION_PROXY);
        }
        activationStage =
                kind == MobileActivationHttpProxy.RequestKind.SESSION
                        ? ActivationStage.SESSION_DATA_SEND_PENDING
                        : ActivationStage.ACTIVATION_DATA_SEND_PENDING;
        return List.of(
                AppleWatchPostCommitCoordinator.Action.activation(
                        coordinator.generation(),
                        AppleWatchPostCommitCoordinator.ActionType.SEND_ACTIVATION_DATA,
                        kind,
                        activationAttempt));
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationDataSendResult(
            long eventGeneration,
            int eventActivationAttempt,
            MobileActivationHttpProxy.RequestKind kind,
            boolean success) {
        if ((!coordinator.acceptsForward(eventGeneration)
                        && !coordinator.acceptsActivationService(eventGeneration))
                || activationAttempt != eventActivationAttempt
                || kind == null) {
            return List.of();
        }
        ActivationStage expected =
                kind == MobileActivationHttpProxy.RequestKind.SESSION
                        ? ActivationStage.SESSION_DATA_SEND_PENDING
                        : ActivationStage.ACTIVATION_DATA_SEND_PENDING;
        if (activationStage != expected) {
            if (!coordinator.acceptsForward(eventGeneration)) {
                return List.of();
            }
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.UNEXPECTED_EVENT);
        }
        if (!success) {
            if (!coordinator.acceptsForward(eventGeneration)) {
                // Late drive: the Watch never got its data and will
                // re-request; re-arm instead of failing the pipeline.
                activationStage =
                        kind == MobileActivationHttpProxy.RequestKind.SESSION
                                ? ActivationStage.WAITING_SESSION_REQUEST
                                : ActivationStage.WAITING_ACTIVATION_REQUEST;
                return List.of();
            }
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.TRANSPORT_SEND);
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
                    AppleWatchPostCommitCoordinator.Action.activation(
                            coordinator.generation(),
                            AppleWatchPostCommitCoordinator.ActionType.SEND_ACTIVATION_PERMIT,
                            null,
                            activationAttempt));
        }
        activationStage =
                ActivationStage.WAITING_OUTCOME;
        return List.of();
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationRedriveRequested(
            long eventGeneration) {
        if (!coordinator.acceptsActivationService(eventGeneration)
                || activationRedrivePending) {
            return List.of();
        }
        if (coordinator.durableState().wireValue()
                < PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            // Nothing persisted to clear; just re-assert the permit when the
            // activation drive has not started yet.
            if (coordinator.phase() == AppleWatchPostCommitCoordinator.Phase.FORWARD_SETUP
                    && activationStage
                    == ActivationStage.NOT_STARTED) {
                activationAttempt++;
                activationStage =
                        ActivationStage.PERMIT_SEND_PENDING;
                return List.of(
                        AppleWatchPostCommitCoordinator.Action.activation(
                                coordinator.generation(),
                                AppleWatchPostCommitCoordinator.ActionType.SEND_ACTIVATION_PERMIT,
                                null,
                                activationAttempt));
            }
            return List.of();
        }
        activationRedrivePending =
                true;
        return List.of(
                AppleWatchPostCommitCoordinator.Action.redriveActivation(
                        coordinator.generation()));
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationRedrivePersisted(
            long eventGeneration,
            boolean success) {
        if (!coordinator.acceptsActivationService(eventGeneration)
                || !activationRedrivePending) {
            return List.of();
        }
        activationRedrivePending =
                false;
        if (!success) {
            if (!coordinator.acceptsForward(eventGeneration)) {
                // Late drive: a persist failure must not kick a finished
                // pipeline into recovery.
                return List.of();
            }
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.LOCAL_CHECKPOINT);
        }
        activationStage =
                ActivationStage.NOT_STARTED;
        activationOutcomeObserved =
                false;
        gizmoFinishedBeforeSession =
                false;
        return coordinator.resetPostActivationForRedriveAndResume();
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationRetryRequested(
            long eventGeneration) {
        // Live 0.2.300: owner override — accepted in every live phase. The
        // whole point of the override is to recover a Watch that stayed
        // unactivated behind a synthetic ACTIVATION_CONFIRMED latch, so the
        // durable-state and outcome gates must not block it.
        if (!coordinator.acceptsActivationService(eventGeneration)) {
            return List.of();
        }
        activationAttempt++;
        activationStage =
                ActivationStage.WAITING_SESSION_REQUEST;
        activationOutcomeObserved =
                false;
        return List.of(
                AppleWatchPostCommitCoordinator.Action.retryActivation(
                        coordinator.generation(),
                        activationAttempt));
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationRetrySendResult(
            long eventGeneration,
            int eventActivationAttempt,
            boolean success) {
        if (!coordinator.acceptsForward(eventGeneration)
                || activationAttempt != eventActivationAttempt) {
            return List.of();
        }
        // The retry is opportunistic: a transport failure must not fail the
        // pipeline; the Watch may still answer an earlier permit.
        return List.of();
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationSucceeded(
            long eventGeneration) {
        if (!coordinator.acceptsForward(eventGeneration)
                || activationOutcomeObserved
                || coordinator.durableState().wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            return List.of();
        }
        if (activationStage == ActivationStage.NOT_STARTED) {
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.UNEXPECTED_EVENT);
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
        return coordinator.requestCheckpoint(
                PairingSessionRecord.DurableState
                        .ACTIVATION_CONFIRMED);
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationFailed(
            long eventGeneration) {
        if (!coordinator.acceptsForward(eventGeneration)) {
            return List.of();
        }
        return coordinator.fail(
                AppleWatchPostCommitCoordinator.Failure.ACTIVATION_REJECTED);
    }

    List<AppleWatchPostCommitCoordinator.Action> onActivationFailedRetryable(
            long eventGeneration) {
        if ((!coordinator.acceptsForward(eventGeneration)
                        && !coordinator.acceptsActivationService(eventGeneration))
                || activationOutcomeObserved) {
            return List.of();
        }
        if (activationStage
                != ActivationStage.WAITING_OUTCOME) {
            if (!coordinator.acceptsForward(eventGeneration)) {
                // Late drive: ignore strays, never fail a finished pipeline.
                return List.of();
            }
            return coordinator.fail(
                    AppleWatchPostCommitCoordinator.Failure.ACTIVATION_REJECTED);
        }
        activationAttempt++;
        activationStage =
                ActivationStage.WAITING_SESSION_REQUEST;
        return List.of();
    }

    List<AppleWatchPostCommitCoordinator.Action> onGizmoDidFinishActivating(long eventGeneration) {
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
        if (coordinator.generation() == eventGeneration
                && !activationOutcomeObserved
                && (activationStage
                        == ActivationStage.WAITING_ACTIVATION_REQUEST
                || activationStage
                        == ActivationStage.WAITING_OUTCOME)
                && coordinator.durableState().wireValue()
                >= PairingSessionRecord.DurableState
                .SETUP_COMPLETE.wireValue()
                && !activationRedrivePending) {
            activationRedrivePending =
                    true;
            return List.of(
                    AppleWatchPostCommitCoordinator.Action.redriveActivation(
                            coordinator.generation()));
        }
        if (!coordinator.acceptsForward(eventGeneration)) {
            return List.of();
        }
        if (!activationOutcomeObserved
                && coordinator.durableState().wireValue()
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

    List<AppleWatchPostCommitCoordinator.Action> onAlreadyActivatedWatchAccepted(
            long eventGeneration) {
        if (!coordinator.acceptsForward(eventGeneration)
                || !gizmoFinishedBeforeSession
                || activationOutcomeObserved
                || activationStage
                != ActivationStage.WAITING_SESSION_REQUEST
                || coordinator.durableState().wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            return List.of();
        }
        activationOutcomeObserved = true;
        activationStage =
                ActivationStage.CONFIRMED;
        return coordinator.requestCheckpoint(
                PairingSessionRecord.DurableState
                        .ACTIVATION_CONFIRMED);
    }
}
