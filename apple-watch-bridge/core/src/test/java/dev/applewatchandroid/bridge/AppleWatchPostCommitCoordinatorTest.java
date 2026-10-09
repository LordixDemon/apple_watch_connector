package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class AppleWatchPostCommitCoordinatorTest {
    private static final long GENERATION =
            73L;
    private static final int WATCH_PAIRING_VERSION =
            25;
    private static final String REQUEST_ID =
            "00112233-4455-6677-8899-aabbccddeeff";
    private static final String OTHER_REQUEST_ID =
            "10213243-5465-7687-98a9-bacbdcedfe0f";

    @Test
    public void fullFlowStopsBeforeCommitAndRequiresEveryClockGate() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();

        assertTypes(
                coordinator.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .READY_TO_COMMIT_IS_PAIRED,
                        false,
                        WATCH_PAIRING_VERSION),
                AppleWatchPostCommitCoordinator.ActionType
                        .REQUEST_EXPLICIT_IS_PAIRED_CONFIRMATION);
        AppleWatchPostCommitCoordinator.Snapshot safe =
                coordinator.snapshot();
        assertTrue(
                safe.safeToStopBeforeCommit);
        assertFalse(
                safe.irreversibleCommitObserved);
        assertFalse(
                safe.commitFinalizationPending);

        assertTypes(
                coordinator.onExplicitCommitDecision(
                        GENERATION,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_IS_PAIRED_COMMIT_INTENT);
        assertTypes(
                coordinator.onCommitIntentPersisted(
                        GENERATION,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertCheckpoint(
                coordinator.snapshot(),
                PairingSessionRecord.DurableState
                        .IS_PAIRED_COMMITTED);

        List<AppleWatchPostCommitCoordinator.Action> postCommit =
                coordinator.onDurableStatePersisted(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        true);
        assertTypes(
                postCommit,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PAIRING_MODE_NORMAL,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_COMPUTED_TIME_ZONE,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_LANGUAGE_AND_LOCALE,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_PERMIT);
        int attempt =
                action(
                        postCommit,
                        AppleWatchPostCommitCoordinator.ActionType
                                .SEND_ACTIVATION_PERMIT)
                        .activationAttempt;
        assertEquals(
                1,
                attempt);
        assertFalse(coordinator.snapshot().languageAndLocaleComplete);
        assertEquals(SetupProgressState.Phase.ACTIVATING,
                SetupProgressState.postCommit(coordinator.snapshot()));

        assertTrue(
                coordinator.onPairingModeNormalSendResult(
                        GENERATION,
                        true).isEmpty());
        assertTrue(
                coordinator.onComputedTimeZoneSendResult(
                        GENERATION,
                        true).isEmpty());
        assertTrue(
                coordinator.onLanguageAndLocaleSendResult(
                        GENERATION,
                        true).isEmpty());
        assertTrue(
                coordinator.onActivationPermitSendResult(
                        GENERATION,
                        attempt,
                        true).isEmpty());

        assertActivationExchange(
                coordinator,
                attempt,
                MobileActivationHttpProxy.RequestKind.SESSION);
        assertActivationExchange(
                coordinator,
                attempt,
                MobileActivationHttpProxy.RequestKind.ACTIVATION);
        assertTypes(
                coordinator.onActivationSucceeded(
                        GENERATION),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);

        assertTypes(
                coordinator.onDurableStatePersisted(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .ACTIVATION_CONFIRMED,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .WAIT_FOR_PB_BRIDGE_NORMAL_PREREQUISITES);
        assertEquals(SetupProgressState.Phase.ACTIVATED,
                SetupProgressState.postCommit(coordinator.snapshot()));
        assertTypes(
                coordinator.onLanguageAndLocaleStatus(
                        GENERATION,
                        new PbBridgeCodec.LanguageAndLocaleStatus(
                                1)),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PB_BRIDGE_NORMAL);
        assertTypes(
                coordinator.onNormalStateSendResult(
                        GENERATION,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PREPARE_INITIAL_SYNC);
        assertTrue(
                coordinator.onPrepareInitialSyncSent(
                        GENERATION,
                        true,
                        REQUEST_ID).isEmpty());
        assertTrue(
                coordinator.onPrepareInitialSyncResponse(
                        GENERATION,
                        OTHER_REQUEST_ID).isEmpty());
        assertFalse(
                coordinator.snapshot().initialSyncPrepared);
        assertEquals(SetupProgressState.Phase.ACTIVATED,
                SetupProgressState.postCommit(coordinator.snapshot()));
        // watchOS 26.6 order: the PairedSync completion publication is what
        // makes the Watch finish; it must not wait for isSetup, and the
        // correlated protobuf 18 response is itself sufficient proof that
        // the Watch runs the paired-sync pipeline (a direct reconnect never
        // delivers the Class-C capabilities property).
        assertTypes(
                coordinator.onPrepareInitialSyncResponse(
                        GENERATION,
                        REQUEST_ID),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PAIRED_SYNC_COMPLETION);
        assertTrue(
                coordinator.snapshot().initialSyncPrepared);
        assertEquals(SetupProgressState.Phase.SYNCING,
                SetupProgressState.postCommit(coordinator.snapshot()));
        assertTrue(
                coordinator.onNormalTransitionPrerequisitesSatisfied(
                        GENERATION).isEmpty());
        assertTrue(
                coordinator.onCompatibilityStateObserved(
                        GENERATION,
                        AppleWatchPostCommitCoordinator
                                .COMPATIBILITY_STATE_NORMAL).isEmpty());
        // A late capability observation is diagnostic only: the completion
        // publication is already in flight.
        assertTrue(
                coordinator.onPairedSyncCapabilityObserved(
                        GENERATION,
                        PairedSyncCapabilityState.Status.PRESENT).isEmpty());

        assertTrue(
                coordinator.onPairedSyncSendResult(
                        GENERATION,
                        true).isEmpty());
        assertEquals(
                PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                coordinator.snapshot().durableState);
        assertEquals(SetupProgressState.Phase.WAITING_FOR_WATCH,
                SetupProgressState.postCommit(coordinator.snapshot()));

        assertTypes(
                coordinator.onIsSetupObserved(
                        GENERATION,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertCheckpoint(
                coordinator.snapshot(),
                PairingSessionRecord.DurableState.IS_SETUP_CONFIRMED);
        assertTrue(
                coordinator.onDurableStatePersisted(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_SETUP_CONFIRMED,
                        true).isEmpty());
        assertTypes(
                coordinator.onWatchPairedSyncCompletionObserved(
                        GENERATION,
                        3,
                        100,
                        3,
                        1),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertTypes(
                coordinator.onDurableStatePersisted(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .PAIRED_SYNC_COMPLETE,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_IDS_LOCAL_PAIRING_SETUP_COMPLETED);
        assertTypes(
                coordinator.onIdsSetupCompletedDispatchResult(
                        GENERATION,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertTypes(coordinator.onDurableStatePersisted(GENERATION,
                PairingSessionRecord.DurableState.SETUP_COMPLETE, true),
                AppleWatchPostCommitCoordinator.ActionType.VERIFY_CLOCK_VISIBLE);
        assertEquals(AppleWatchPostCommitCoordinator.Phase.WAITING_FOR_CLOCK,
                coordinator.snapshot().phase);
        assertTrue(coordinator.onGizmoDidFinishActivating(GENERATION).isEmpty());
        assertTypes(coordinator.onClockVisibleObserved(GENERATION, 0, 6, 10, true),
                AppleWatchPostCommitCoordinator.ActionType.PERSIST_DURABLE_STATE);
        assertCheckpoint(coordinator.snapshot(), PairingSessionRecord.DurableState.CLOCK_VISIBLE_CONFIRMED);
        assertTypes(
                coordinator.onDurableStatePersisted(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .CLOCK_VISIBLE_CONFIRMED,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .VERIFY_OPERATIONAL_RECONNECT);
        assertEquals(SetupProgressState.Phase.VERIFYING_RECONNECT,
                SetupProgressState.postCommit(coordinator.snapshot()));
        assertTypes(
                coordinator.onOperationalHealthObserved(
                        GENERATION,
                        completeHealth()),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertTrue(
                coordinator.onDurableStatePersisted(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .OPERATIONAL_HEALTH_CONFIRMED,
                        true).isEmpty());

        AppleWatchPostCommitCoordinator.Snapshot complete =
                coordinator.snapshot();
        assertEquals(SetupProgressState.Phase.VERIFIED,
                SetupProgressState.postCommit(complete));
        assertEquals(
                AppleWatchPostCommitCoordinator.Phase.COMPLETE,
                complete.phase);
        assertEquals(
                PairingSessionRecord.DurableState
                        .OPERATIONAL_HEALTH_CONFIRMED,
                complete.durableState);
        assertTrue(
                complete.irreversibleCommitObserved);
        assertFalse(
                complete.safeToStopBeforeCommit);
    }

    @Test
    public void activationReportAfterPublicationStillNeedsSetupAndPeerSyncEvidence() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();

        coordinator.begin(
                GENERATION,
                PairingSessionRecord.DurableState
                        .READY_TO_COMMIT_IS_PAIRED,
                false,
                WATCH_PAIRING_VERSION);
        coordinator.onExplicitCommitDecision(
                GENERATION,
                true);
        coordinator.onCommitIntentPersisted(
                GENERATION,
                true);
        List<AppleWatchPostCommitCoordinator.Action> postCommit =
                coordinator.onDurableStatePersisted(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        true);
        int attempt =
                action(
                        postCommit,
                        AppleWatchPostCommitCoordinator.ActionType
                                .SEND_ACTIVATION_PERMIT)
                        .activationAttempt;
        coordinator.onPairingModeNormalSendResult(
                GENERATION,
                true);
        coordinator.onComputedTimeZoneSendResult(
                GENERATION,
                true);
        coordinator.onLanguageAndLocaleSendResult(
                GENERATION,
                true);
        coordinator.onActivationPermitSendResult(
                GENERATION,
                attempt,
                true);
        assertActivationExchange(
                coordinator,
                attempt,
                MobileActivationHttpProxy.RequestKind.SESSION);
        assertActivationExchange(
                coordinator,
                attempt,
                MobileActivationHttpProxy.RequestKind.ACTIVATION);
        coordinator.onActivationSucceeded(
                GENERATION);
        coordinator.onDurableStatePersisted(
                GENERATION,
                PairingSessionRecord.DurableState
                        .ACTIVATION_CONFIRMED,
                true);
        coordinator.onLanguageAndLocaleStatus(
                GENERATION,
                new PbBridgeCodec.LanguageAndLocaleStatus(
                        1));
        coordinator.onNormalStateSendResult(
                GENERATION,
                true);
        coordinator.onPrepareInitialSyncSent(
                GENERATION,
                true,
                REQUEST_ID);
        assertTypes(
                coordinator.onPrepareInitialSyncResponse(
                        GENERATION,
                        REQUEST_ID),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PAIRED_SYNC_COMPLETION);
        assertTrue(
                coordinator.onPairedSyncCapabilityObserved(
                        GENERATION,
                        PairedSyncCapabilityState.Status.PRESENT).isEmpty());
        assertTrue(
                coordinator.onPairedSyncSendResult(
                        GENERATION,
                        true).isEmpty());

        assertTrue(coordinator.onGizmoDidFinishActivating(GENERATION).isEmpty());
        assertFalse(coordinator.snapshot().isSetupObserved);
        assertFalse(coordinator.snapshot().pairedSyncObserved);
        assertTrue(coordinator.onPeerPairedSyncCompletionObserved(GENERATION).isEmpty());
        assertTypes(coordinator.onIsSetupObserved(GENERATION, true),
                AppleWatchPostCommitCoordinator.ActionType.PERSIST_DURABLE_STATE);
        assertCheckpoint(
                coordinator.snapshot(),
                PairingSessionRecord.DurableState.IS_SETUP_CONFIRMED);

        // Peer PSY state was observed, so applying the
        // IS_SETUP_CONFIRMED checkpoint immediately requests the next one.
        assertTypes(
                coordinator.onDurableStatePersisted(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_SETUP_CONFIRMED,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertCheckpoint(
                coordinator.snapshot(),
                PairingSessionRecord.DurableState.PAIRED_SYNC_COMPLETE);

        assertTypes(
                coordinator.onDurableStatePersisted(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .PAIRED_SYNC_COMPLETE,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_IDS_LOCAL_PAIRING_SETUP_COMPLETED);
        assertTypes(
                coordinator.onIdsSetupCompletedDispatchResult(
                        GENERATION,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertTypes(coordinator.onDurableStatePersisted(GENERATION,
                PairingSessionRecord.DurableState.SETUP_COMPLETE, true),
                AppleWatchPostCommitCoordinator.ActionType.VERIFY_CLOCK_VISIBLE);
        assertEquals(AppleWatchPostCommitCoordinator.Phase.WAITING_FOR_CLOCK,
                coordinator.snapshot().phase);
        assertTrue(coordinator.onGizmoDidFinishActivating(GENERATION).isEmpty());
        assertEquals(AppleWatchPostCommitCoordinator.Phase.WAITING_FOR_CLOCK,
                coordinator.snapshot().phase);
    }

    @Test
    public void restoredWriteAheadIntentRequiresExplicitLocalFinalization() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        assertTypes(
                coordinator.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .READY_TO_COMMIT_IS_PAIRED,
                        true,
                        WATCH_PAIRING_VERSION),
                AppleWatchPostCommitCoordinator.ActionType
                        .REQUEST_EXPLICIT_IS_PAIRED_RETRY);
        assertTrue(
                coordinator.snapshot().commitFinalizationPending);
        assertFalse(
                coordinator.snapshot().safeToStopBeforeCommit);

        assertTrue(
                coordinator.onExplicitCommitRetryDecision(
                        GENERATION,
                        false).isEmpty());
        assertTypes(
                coordinator.onExplicitCommitRetryDecision(
                        GENERATION,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertCheckpoint(
                coordinator.snapshot(),
                PairingSessionRecord.DurableState
                        .IS_PAIRED_COMMITTED);

        AppleWatchPostCommitCoordinator restoredCommitted =
                new AppleWatchPostCommitCoordinator();
        assertTypes(
                restoredCommitted.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        WATCH_PAIRING_VERSION),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PAIRING_MODE_NORMAL,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_COMPUTED_TIME_ZONE,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_LANGUAGE_AND_LOCALE,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_PERMIT);
        assertTrue(
                restoredCommitted.snapshot()
                        .irreversibleCommitObserved);
    }

    @Test
    public void languageRelaunchInvalidatesOldActivationAttempt() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> start =
                coordinator.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        WATCH_PAIRING_VERSION);
        AppleWatchPostCommitCoordinator.Action firstPermit =
                action(
                        start,
                        AppleWatchPostCommitCoordinator.ActionType
                                .SEND_ACTIVATION_PERMIT);
        int firstAttempt =
                firstPermit.activationAttempt;
        assertTrue(
                coordinator.onActivationPermitSendResult(
                        GENERATION,
                        firstAttempt,
                        true).isEmpty());

        List<AppleWatchPostCommitCoordinator.Action> relaunch =
                coordinator.onLanguageAndLocaleStatus(
                        GENERATION,
                        new PbBridgeCodec.LanguageAndLocaleStatus(
                                2));
        assertTypes(
                relaunch,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_NORMAL_AFTER_LANGUAGE_RELAUNCH);
        int secondAttempt =
                relaunch.get(0).activationAttempt;
        assertEquals(
                firstAttempt + 1,
                secondAttempt);
        assertTrue(
                coordinator.onActivationRequest(
                        GENERATION,
                        firstAttempt,
                        MobileActivationHttpProxy.RequestKind.SESSION)
                        .isEmpty());
        assertTypes(
                coordinator.onRelaunchNormalSendResult(
                        GENERATION,
                        secondAttempt,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_PERMIT);
        assertTrue(
                coordinator.onActivationPermitSendResult(
                        GENERATION,
                        secondAttempt,
                        true).isEmpty());
        assertTypes(
                coordinator.onActivationRequest(
                        GENERATION,
                        secondAttempt,
                        MobileActivationHttpProxy.RequestKind.SESSION),
                AppleWatchPostCommitCoordinator.ActionType
                        .EXECUTE_ACTIVATION_HTTPS);
    }

    @Test
    public void emptyFinishedBeforeSessionDoesNotConfirmUntilGraceAcceptsIt() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> start =
                coordinator.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        WATCH_PAIRING_VERSION);
        int attempt =
                action(
                        start,
                        AppleWatchPostCommitCoordinator.ActionType
                                .SEND_ACTIVATION_PERMIT)
                        .activationAttempt;
        assertTrue(
                coordinator.onActivationPermitSendResult(
                        GENERATION,
                        attempt,
                        true).isEmpty());

        assertTrue(
                coordinator.onGizmoDidFinishActivating(
                        GENERATION).isEmpty());
        assertTrue(
                coordinator.onActivationSucceeded(
                        GENERATION).isEmpty());
        assertTrue(
                coordinator.snapshot().gizmoFinishedBeforeSession);
        assertFalse(
                coordinator.snapshot().activationConfirmed);
        assertFalse(
                coordinator.snapshot().isSetupObserved);

        assertTypes(
                coordinator.onActivationRequest(
                        GENERATION,
                        attempt,
                        MobileActivationHttpProxy.RequestKind.SESSION),
                AppleWatchPostCommitCoordinator.ActionType
                        .EXECUTE_ACTIVATION_HTTPS);
        assertTrue(
                coordinator.onAlreadyActivatedWatchAccepted(
                        GENERATION).isEmpty());

        AppleWatchPostCommitCoordinator finished =
                permitSentCoordinator();
        assertTrue(
                finished.onGizmoDidFinishActivating(
                        GENERATION).isEmpty());
        assertTypes(
                finished.onAlreadyActivatedWatchAccepted(
                        GENERATION),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertCheckpoint(
                finished.snapshot(),
                PairingSessionRecord.DurableState
                        .ACTIVATION_CONFIRMED);
        assertFalse(
                finished.snapshot().isSetupObserved);
        assertTrue(
                finished.onAlreadyActivatedWatchAccepted(
                        GENERATION).isEmpty());
    }

    @Test
    public void alreadyActivatedReportNeverBecomesSetupEvidenceAfterPublication() {
        AppleWatchPostCommitCoordinator coordinator =
                permitSentCoordinator();
        assertTrue(
                coordinator.onGizmoDidFinishActivating(
                        GENERATION).isEmpty());
        coordinator.onLanguageAndLocaleSendResult(
                GENERATION,
                true);
        coordinator.onComputedTimeZoneSendResult(
                GENERATION,
                true);
        coordinator.onPairingModeNormalSendResult(
                GENERATION,
                true);
        assertTrue(
                coordinator.onLanguageAndLocaleStatus(
                        GENERATION,
                        new PbBridgeCodec.LanguageAndLocaleStatus(
                                1)).isEmpty());
        assertTypes(
                coordinator.onAlreadyActivatedWatchAccepted(
                        GENERATION),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertFalse(
                coordinator.snapshot().isSetupObserved);
        coordinator.onDurableStatePersisted(
                GENERATION,
                PairingSessionRecord.DurableState
                        .ACTIVATION_CONFIRMED,
                true);
        assertFalse(
                coordinator.snapshot().isSetupObserved);
        List<AppleWatchPostCommitCoordinator.Action> afterNormal =
                coordinator.onNormalStateSendResult(
                        GENERATION,
                        true);
        action(
                afterNormal,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PREPARE_INITIAL_SYNC);
        coordinator.onPrepareInitialSyncSent(
                GENERATION,
                true,
                REQUEST_ID);
        assertTypes(
                coordinator.onPrepareInitialSyncResponse(
                        GENERATION,
                        REQUEST_ID),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PAIRED_SYNC_COMPLETION);
        assertTrue(coordinator.onPairedSyncSendResult(GENERATION, true).isEmpty());
        assertFalse(coordinator.snapshot().isSetupObserved);
        assertFalse(coordinator.snapshot().pairedSyncObserved);
        assertEquals(PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                coordinator.snapshot().durableState);
    }

    @Test
    public void alreadyActivatedWatchLatchesActivationAfterSessionData() {
        // Live 0.2.303 (2026-10-05 10:57): RetryActivation on an
        // already-activated Watch drives the authenticated drmHandshake
        // SESSION exchange, then the Watch answers the re-asserted
        // CanBeginActivation with protobuf 4 instead of opening the
        // deviceActivation ProxyActivation. The stage is
        // WAITING_ACTIVATION_REQUEST; the verdict is real and must latch
        // ACTIVATION_CONFIRMED, otherwise the pipeline parks forever.
        AppleWatchPostCommitCoordinator coordinator =
                permitSentCoordinator();
        int attempt = 1;
        assertActivationExchange(
                coordinator,
                attempt,
                MobileActivationHttpProxy.RequestKind.SESSION);
        assertTypes(
                coordinator.onActivationSucceeded(
                        GENERATION),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_DURABLE_STATE);
        assertCheckpoint(
                coordinator.snapshot(),
                PairingSessionRecord.DurableState
                        .ACTIVATION_CONFIRMED);
        assertTrue(
                coordinator.snapshot().activationConfirmed);
        // The 0.2.197 false-positive shape stays rejected: protobuf 4
        // before any session exchange still latches nothing.
        AppleWatchPostCommitCoordinator early =
                permitSentCoordinator();
        assertTrue(
                early.onGizmoDidFinishActivating(
                        GENERATION).isEmpty());
        assertTrue(
                early.onActivationSucceeded(
                        GENERATION).isEmpty());
        assertFalse(
                early.snapshot().activationConfirmed);
    }

    @Test
    public void staleTerminalRecordRedrivesOnFreshActivationVerdict() {
        // Live 0.2.303 (2026-10-05 04:19): a record at
        // OPERATIONAL_HEALTH_CONFIRMED resumes in COMPLETE, but the erased
        // Watch re-runs a fresh authenticated activation in this session.
        // The finished verdict must downgrade the stale record via the
        // activation redrive and replay the whole forward setup; parking
        // in COMPLETE left the physical Watch in Buddy forever.
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        assertTrue(
                coordinator.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .OPERATIONAL_HEALTH_CONFIRMED,
                        false,
                        WATCH_PAIRING_VERSION).isEmpty());
        assertEquals(
                AppleWatchPostCommitCoordinator.Phase.COMPLETE,
                coordinator.snapshot().phase);
        List<AppleWatchPostCommitCoordinator.Action> retry =
                coordinator.onActivationRetryRequested(
                        GENERATION);
        assertTypes(
                retry,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_RETRY);
        int attempt =
                retry.get(0).activationAttempt;
        assertTypes(
                coordinator.onActivationRequest(
                        GENERATION,
                        attempt,
                        MobileActivationHttpProxy.RequestKind.SESSION),
                AppleWatchPostCommitCoordinator.ActionType
                        .EXECUTE_ACTIVATION_HTTPS);
        assertTypes(
                coordinator.onActivationProxyResult(
                        GENERATION,
                        attempt,
                        MobileActivationHttpProxy.RequestKind.SESSION,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_DATA);
        assertTypes(
                coordinator.onActivationDataSendResult(
                        GENERATION,
                        attempt,
                        MobileActivationHttpProxy.RequestKind.SESSION,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_PERMIT);
        assertTypes(
                coordinator.onGizmoDidFinishActivating(
                        GENERATION),
                AppleWatchPostCommitCoordinator.ActionType
                        .PERSIST_ACTIVATION_REDRIVE);
        List<AppleWatchPostCommitCoordinator.Action> replay =
                coordinator.onActivationRedrivePersisted(
                        GENERATION,
                        true);
        assertEquals(
                PairingSessionRecord.DurableState
                        .IS_PAIRED_COMMITTED,
                coordinator.snapshot().durableState);
        assertEquals(
                AppleWatchPostCommitCoordinator.Phase
                        .FORWARD_SETUP,
                coordinator.snapshot().phase);
        action(
                replay,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_PAIRING_MODE_NORMAL);
        action(
                replay,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_LANGUAGE_AND_LOCALE);
        action(
                replay,
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_PERMIT);
    }

    private static AppleWatchPostCommitCoordinator permitSentCoordinator() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> start =
                coordinator.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        WATCH_PAIRING_VERSION);
        int attempt =
                action(
                        start,
                        AppleWatchPostCommitCoordinator.ActionType
                                .SEND_ACTIVATION_PERMIT)
                        .activationAttempt;
        assertTrue(
                coordinator.onActivationPermitSendResult(
                        GENERATION,
                        attempt,
                        true).isEmpty());
        return coordinator;
    }

    @Test
    public void unknownLanguageAndWeakTerminalEvidenceFailClosed() {
        AppleWatchPostCommitCoordinator unknownLanguage =
                new AppleWatchPostCommitCoordinator();
        unknownLanguage.begin(
                GENERATION,
                PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED,
                false,
                WATCH_PAIRING_VERSION);
        List<AppleWatchPostCommitCoordinator.Action> languageFailure =
                unknownLanguage.onLanguageAndLocaleStatus(
                        GENERATION,
                        new PbBridgeCodec.LanguageAndLocaleStatus(
                                3));
        assertFailure(
                languageFailure,
                AppleWatchPostCommitCoordinator.Failure
                        .UNKNOWN_LANGUAGE_STATUS);
        assertEquals(
                AppleWatchPostCommitCoordinator.Phase
                        .FORWARD_RECOVERY_REQUIRED,
                unknownLanguage.snapshot().phase);

        AppleWatchPostCommitCoordinator badClock =
                new AppleWatchPostCommitCoordinator();
        assertTypes(badClock.begin(GENERATION, PairingSessionRecord.DurableState.SETUP_COMPLETE,
                false, WATCH_PAIRING_VERSION),
                AppleWatchPostCommitCoordinator.ActionType.VERIFY_CLOCK_VISIBLE);
        assertFailure(badClock.onClockVisibleObserved(GENERATION, 0, 6, 10, false),
                AppleWatchPostCommitCoordinator.Failure.INVALID_CLOCK_EVIDENCE);

        AppleWatchPostCommitCoordinator badHealth =
                new AppleWatchPostCommitCoordinator();
        assertTypes(
                badHealth.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .CLOCK_VISIBLE_CONFIRMED,
                        false,
                        WATCH_PAIRING_VERSION),
                AppleWatchPostCommitCoordinator.ActionType
                        .VERIFY_OPERATIONAL_RECONNECT);
        assertFailure(
                badHealth.onOperationalHealthObserved(
                        GENERATION,
                        new AppleWatchPostCommitCoordinator
                                .OperationalHealthEvidence(
                                true,
                                true,
                                true,
                                true,
                                true,
                                false,
                                true,
                                true)),
                AppleWatchPostCommitCoordinator.Failure
                        .INVALID_OPERATIONAL_HEALTH_EVIDENCE);
    }

    @Test
    public void intentPersistenceFailureNeverEmitsCommitCheckpoint() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        coordinator.begin(
                GENERATION,
                PairingSessionRecord.DurableState
                        .READY_TO_COMMIT_IS_PAIRED,
                false,
                WATCH_PAIRING_VERSION);
        coordinator.onExplicitCommitDecision(
                GENERATION,
                true);
        List<AppleWatchPostCommitCoordinator.Action> failure =
                coordinator.onCommitIntentPersisted(
                        GENERATION,
                        false);
        assertFailure(
                failure,
                AppleWatchPostCommitCoordinator.Failure.LOCAL_CHECKPOINT);
        assertNull(
                find(
                        failure,
                        AppleWatchPostCommitCoordinator.ActionType
                                .PERSIST_DURABLE_STATE));
        assertTrue(
                coordinator.onExplicitCommitDecision(
                        GENERATION + 1,
                        true).isEmpty());
    }

    @Test
    public void runtimeVersionFailsClosed() {
        AppleWatchPostCommitCoordinator tooOld =
                new AppleWatchPostCommitCoordinator();
        assertThrows(
                IllegalArgumentException.class,
                () -> tooOld.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        24));
    }

    private static void assertActivationExchange(
            AppleWatchPostCommitCoordinator coordinator,
            int attempt,
            MobileActivationHttpProxy.RequestKind kind) {
        List<AppleWatchPostCommitCoordinator.Action> proxy =
                coordinator.onActivationRequest(
                        GENERATION,
                        attempt,
                        kind);
        assertTypes(
                proxy,
                AppleWatchPostCommitCoordinator.ActionType
                        .EXECUTE_ACTIVATION_HTTPS);
        assertEquals(
                kind,
                proxy.get(0).activationKind);
        assertTypes(
                coordinator.onActivationProxyResult(
                        GENERATION,
                        attempt,
                        kind,
                        true),
                AppleWatchPostCommitCoordinator.ActionType
                        .SEND_ACTIVATION_DATA);
        if (kind == MobileActivationHttpProxy.RequestKind.SESSION) {
            // Live 0.2.302: after the drmHandshake session data lands the
            // coordinator re-asserts CanBeginActivation so the Watch leaves
            // the parked beganAwaitingAbilityToActivate state.
            assertTypes(
                    coordinator.onActivationDataSendResult(
                            GENERATION,
                            attempt,
                            kind,
                            true),
                    AppleWatchPostCommitCoordinator.ActionType
                            .SEND_ACTIVATION_PERMIT);
        } else {
            assertTrue(
                    coordinator.onActivationDataSendResult(
                            GENERATION,
                            attempt,
                            kind,
                            true).isEmpty());
        }
    }

    private static AppleWatchPostCommitCoordinator
            .OperationalHealthEvidence completeHealth() {
        return new AppleWatchPostCommitCoordinator
                .OperationalHealthEvidence(
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true);
    }

    private static void assertCheckpoint(
            AppleWatchPostCommitCoordinator.Snapshot snapshot,
            PairingSessionRecord.DurableState expected) {
        assertEquals(
                AppleWatchPostCommitCoordinator.Phase.CHECKPOINT_PENDING,
                snapshot.phase);
        assertEquals(
                expected,
                snapshot.checkpointPending);
    }

    private static void assertFailure(
            List<AppleWatchPostCommitCoordinator.Action> actions,
            AppleWatchPostCommitCoordinator.Failure failure) {
        assertTypes(
                actions,
                AppleWatchPostCommitCoordinator.ActionType.REPORT_FAILURE);
        assertEquals(
                failure,
                actions.get(0).failure);
    }

    private static void assertTypes(
            List<AppleWatchPostCommitCoordinator.Action> actions,
            AppleWatchPostCommitCoordinator.ActionType... types) {
        assertEquals(
                types.length,
                actions.size());
        for (int index = 0; index < types.length; index++) {
            assertEquals(
                    types[index],
                    actions.get(index).type);
        }
    }

    private static AppleWatchPostCommitCoordinator.Action action(
            List<AppleWatchPostCommitCoordinator.Action> actions,
            AppleWatchPostCommitCoordinator.ActionType type) {
        AppleWatchPostCommitCoordinator.Action found =
                find(
                        actions,
                        type);
        if (found == null) {
            throw new AssertionError(
                    "Missing action " + type);
        }
        return found;
    }

    private static AppleWatchPostCommitCoordinator.Action find(
            List<AppleWatchPostCommitCoordinator.Action> actions,
            AppleWatchPostCommitCoordinator.ActionType type) {
        for (AppleWatchPostCommitCoordinator.Action action : actions) {
            if (action.type == type) {
                return action;
            }
        }
        return null;
    }
}
