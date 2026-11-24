package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Durable pairing/IDS/property setup checkpoint persistence and operational
 * health/address diagnostics for {@link PostCommitActivationRunner}.
 */
final class PostCommitCheckpointer {
    private final HalTransportSession session;
    private final PostCommitActivationRunner runner;

    PostCommitCheckpointer(
            HalTransportSession session,
            PostCommitActivationRunner runner) {
        this.session = session;
        this.runner = runner;
    }

    void checkpointOrdinaryRegistration(
            NormalLinkPipeSession pipe)
            throws Exception {
        PairingSessionRecord.DurableState state =
                session.activePairingSessionRecord.state();
        if (state
                == PairingSessionRecord.DurableState
                .NETWORK_RELAY_PRELUDE_NEGOTIATED
                && pipe.classDEstablished()) {
            AppleNetworkRelayInnerAddresses authoritative =
                    null;
            PairingSessionRecord next = null;
            try {
                if (session.activePairingSessionRecord.localRole()
                        == NrLinkBluetoothPrelude.LocalRole
                        .INITIATOR) {
                    authoritative =
                            pipe.copyAuthoritativeAddresses();
                }
                next =
                        session.activePairingSessionRecord
                                .withClassDEstablished(
                                        authoritative);
                session.persistAndReplaceActivePairingSession(
                        next);
                next = null;
                log("NORMAL CLASS-D CHECKPOINT PASS: "
                        + "authenticated address authority="
                        + session.activePairingSessionRecord
                                .addressesConfirmed()
                        + "; address/key bytes logged=false.");
            } finally {
                if (next != null) {
                    next.destroy();
                }
                if (authoritative != null) {
                    authoritative.destroy();
                }
            }
            state =
                    session.activePairingSessionRecord.state();
        }
        if (state
                == PairingSessionRecord.DurableState
                .CLASS_D_ESTABLISHED
                && pipe.classCEstablished()) {
            PairingSessionRecord classCEstablished = null;
            PairingSessionRecord next =
                    null;
            try {
                classCEstablished =
                        session.activePairingSessionRecord.advanceTo(
                                PairingSessionRecord.DurableState
                                        .CLASS_C_ESTABLISHED);
                next =
                        classCEstablished
                                .markIdsAuthenticationAccepted();
                session.persistAndReplaceActivePairingSession(
                        next);
                next = null;
                log("NORMAL CLASS-C CHECKPOINT PASS: "
                        + "registration key-confirmation barrier "
                        + "is durable; local IDS paired-device "
                        + "record accepted and pending authData "
                        + "removed; secret bytes logged=false.");
            } finally {
                if (next != null) {
                    next.destroy();
                }
                if (classCEstablished != null) {
                    classCEstablished.destroy();
                }
            }
        }
    }

    void checkpointIdsControlReady()
            throws Exception {
        PairingSessionRecord.DurableState state =
                session.activePairingSessionRecord.state();
        if (state.wireValue()
                >= PairingSessionRecord.DurableState
                .IDS_CONTROL_READY.wireValue()) {
            return;
        }
        if (state
                != PairingSessionRecord.DurableState
                .CLASS_C_ESTABLISHED
                || !session.activePairingSessionRecord
                .idsAuthenticationAccepted()) {
            throw new HostException(
                    "IDS control Hello arrived before local pairing "
                            + "registration");
        }
        PairingSessionRecord next =
                session.activePairingSessionRecord.advanceTo(
                        PairingSessionRecord.DurableState
                                .IDS_CONTROL_READY);
        try {
            session.persistAndReplaceActivePairingSession(
                    next);
            next = null;
            log("IDS CONTROL CHECKPOINT PASS: peer Hello and "
                    + "compatibility accepted; identifiers/sequence "
                    + "logged=false.");
        } finally {
            if (next != null) {
                next.destroy();
            }
        }
    }

    void checkpointIdsDataReady()
            throws Exception {
        PairingSessionRecord.DurableState state =
                session.activePairingSessionRecord.state();
        if (state.wireValue()
                >= PairingSessionRecord.DurableState
                .IDS_DATA_READY.wireValue()) {
            return;
        }
        if (state
                != PairingSessionRecord.DurableState
                .IDS_CONTROL_READY) {
            if (state == PairingSessionRecord.DurableState.CLASS_C_ESTABLISHED
                    && session.activePairingSessionRecord.idsAuthenticationAccepted()) {
                checkpointIdsControlReady();
                state = session.activePairingSessionRecord.state();
            } else {
                throw new HostException(
                        "IDS data lanes joined before the durable "
                                + "control Hello checkpoint");
            }
        }
        PairingSessionRecord next =
                session.activePairingSessionRecord.advanceTo(
                        PairingSessionRecord.DurableState
                                .IDS_DATA_READY);
        try {
            session.persistAndReplaceActivePairingSession(
                    next);
            next = null;
            log("IDS DATA CHECKPOINT PASS: initial Class-D and "
                    + "Class-C lanes joined; application setup "
                    + "commit attempted=false.");
        } finally {
            if (next != null) {
                next.destroy();
            }
        }
    }

    void checkpointInitialPropertiesReceived(
            int remoteCid)
            throws Exception {
        if (runner.activeInitialSetup == null || !runner.activeInitialSetup.snapshot().identityValidated
                || !runner.activeInitialSetup.snapshot().transportCompletionIssued) {
            throw new HostException("Initial properties require this-session Watch identity evidence");
        }
        // Properties delivered as DataMessage take the initial adapter path
        // rather than the coordinator's protobuf validator. Both must
        // publish the same observed capability state before post-commit.
        session.idsSession.activeIdsBridge.setPairedSyncCapability(runner.activeInitialSetup.pairedSyncCapabilityStatus());
        PairingSessionRecord.DurableState state =
                session.activePairingSessionRecord.state();
        if (state.wireValue()
                >= PairingSessionRecord.DurableState
                .READY_TO_COMMIT_IS_PAIRED.wireValue()) {
            runner.initialPropertiesReceived = true;
            runner.readyToCommitIsPaired = true;
            checkpointReadyToCommitIsPaired(
                    remoteCid);
            return;
        }
        if (state
                == PairingSessionRecord.DurableState
                .INITIAL_PROPERTIES_RECEIVED) {
            runner.initialPropertiesReceived = true;
            checkpointReadyToCommitIsPaired(
                    remoteCid);
            return;
        }
        if (state
                == PairingSessionRecord.DurableState
                .IDS_CONTROL_READY) {
            checkpointIdsDataReady();
            state = session.activePairingSessionRecord.state();
        }
        if (state
                != PairingSessionRecord.DurableState
                .IDS_DATA_READY
                || runner.activeInitialSetup == null
                || !runner.activeInitialSetup.snapshot()
                .transportCompletionIssued
                || !runner.activeInitialSetup.snapshot()
                .identityValidated) {
            throw new HostException(
                    "Initial properties cannot become durable before "
                            + "the exact Watch identity barrier");
        }
        // Resolve the Watch's own choices before the IsPaired/activation boundary.
        // Missing values must never become the Android phone's locale defaults.
        runner.activeInitialSetup.watchLocaleSnapshot();
        PairingSessionRecord next =
                session.activePairingSessionRecord.advanceTo(
                        PairingSessionRecord.DurableState
                                .INITIAL_PROPERTIES_RECEIVED);
        try {
            session.persistAndReplaceActivePairingSession(
                    next);
            next = null;
            runner.initialPropertiesReceived = true;
            log("INITIAL PROPERTIES CHECKPOINT PASS: full Watch "
                    + "Class-C snapshot atomically validated "
                    + "ProductType matches selected advertisement, "
                    + "authenticated ModelNumber/ChipID and PairingSessionID capability; "
                    + "Configure sent; IsPaired/activation attempted=false; "
                    + "property values/UUIDs logged=false.");
        } finally {
            if (next != null) {
                next.destroy();
            }
        }
        checkpointReadyToCommitIsPaired(
                remoteCid);
    }

    void checkpointReadyToCommitIsPaired(
            int remoteCid)
            throws Exception {
        PairingSessionRecord.DurableState state =
                session.activePairingSessionRecord.state();
        if (state.wireValue()
                >= PairingSessionRecord.DurableState
                .READY_TO_COMMIT_IS_PAIRED.wireValue()) {
            runner.readyToCommitIsPaired = true;
            runner.startPostCommitIfAuthorized(
                    remoteCid);
            return;
        }
        if (state
                != PairingSessionRecord.DurableState
                .INITIAL_PROPERTIES_RECEIVED
                || !runner.initialPropertiesReceived
                || !session.idsSession.modernIdsReady
                || session.idsSession.activeIdsBridge == null
                || runner.activeInitialSetup == null) {
            throw new HostException(
                    "Commit gate cannot become durable before the local "
                            + "Bridge/daemon-equivalent readiness barrier");
        }
        PairingSessionRecord next =
                session.activePairingSessionRecord.advanceTo(
                        PairingSessionRecord.DurableState
                                .READY_TO_COMMIT_IS_PAIRED);
        try {
            session.persistAndReplaceActivePairingSession(
                    next);
            next = null;
            runner.readyToCommitIsPaired = true;
            log("READY-TO-COMMIT CHECKPOINT PASS: iOS-only app "
                    + "removability, Bridge notification, NanoLaunchDaemon "
                    + "XPC and launch-delay entries require no Watch wire "
                    + "message on Android; exact IDS D/C and full-property "
                    + "barriers remain live; IsPaired/activation "
                    + "attempted=false; explicit commit authorization="
                    + session.commitIsPairedAuthorized
                    + ".");
        } finally {
            if (next != null) {
                next.destroy();
            }
        }

        runner.startPostCommitIfAuthorized(
                remoteCid);
    }

    /**
     * Builds the operational-health evidence strictly from facts of this
     * live session and the durable record. Nothing is assumed: each field
     * is read from the bond record, the IDS lane state, the control-Hello
     * observation and the post-commit coordinator latches.
     */
    AppleWatchPostCommitCoordinator.OperationalHealthEvidence evaluateOperationalHealthEvidence() {
        if (session.activePairingSessionRecord == null
                || session.idsSession.activeIdsBridge == null
                || runner.activePostCommitCoordinator == null) {
            return null;
        }
        IdsModernSessionCoordinator.Snapshot ids =
                session.idsSession.activeIdsBridge.idsSnapshot();
        AppleWatchPostCommitCoordinator.Snapshot post =
                runner.activePostCommitCoordinator.snapshot();
        boolean stockBond =
                session.activePairingSessionRecord.hasBluetoothBond();
        return new AppleWatchPostCommitCoordinator.OperationalHealthEvidence(
                stockBond,
                stockBond && !session.freshPairingPerformed,
                session.idsSession.activeIdsBridge.classDLaneStableForApplication(),
                session.idsSession.activeIdsBridge.classCLaneStableForApplication(),
                session.idsSession.idsControlReadyObserved || ids.peerHelloReceived,
                session.idsSession.activeIdsBridge.dataLanesReadyForApplication(),
                post.watchCompatibilityNormalObserved && post.isSetupObserved,
                stockBond);
    }

    String operationalHealthGaps(
            AppleWatchPostCommitCoordinator.OperationalHealthEvidence evidence) {
        if (evidence == null) {
            return "no pairing record, IDS bridge or post-commit coordinator";
        }
        List<String> gaps = new ArrayList<>();
        if (!evidence.stockBondPresent) gaps.add("stockBond");
        if (!evidence.encryptedReconnectAfterControlledRestart) gaps.add("encryptedReconnectAfterControlledRestart");
        if (!evidence.classDStable) gaps.add("classD");
        if (!evidence.classCStable) gaps.add("classC");
        if (!evidence.idsControlReady) gaps.add("idsControl");
        if (!evidence.idsDataReady) gaps.add("idsData");
        if (!evidence.normalAndIsSetupObserved) gaps.add("nanoRegistryNormal/isSetup");
        if (!evidence.unrelatedBondBaselinePreserved) gaps.add("bondBaseline");
        return gaps.isEmpty() ? "none" : String.join(", ", gaps);
    }

    String addressProjection(
            String phase,
            NormalLinkPipeSession pipe) {
        byte[] initiator = null;
        byte[] responder = null;
        byte[] pipeLocal = null;
        AppleNetworkRelayInnerAddresses quartet = null;
        try {
            quartet = session.activePairingSessionRecord.innerAddresses();
            initiator = quartet.initiatorClassD();
            responder = quartet.responderClassD();
            pipeLocal = pipe.localClassD();
            return "ADDRESS PROJECTION "
                    + phase
                    + ": pipeRole="
                    + pipe.localRole()
                    + " recordRole="
                    + session.activePairingSessionRecord.localRole()
                    + " initiatorHost="
                    + hostSuffix(initiator)
                    + " responderHost="
                    + hostSuffix(responder)
                    + " pipeLocalHost="
                    + hostSuffix(pipeLocal);
        } finally {
            if (quartet != null) {
                quartet.destroy();
            }
            HalTransportSession.wipe(initiator);
            HalTransportSession.wipe(responder);
            HalTransportSession.wipe(pipeLocal);
        }
    }

    static String hostPair(byte[] local, byte[] remote) {
        return hostSuffix(local) + "->" + hostSuffix(remote);
    }

    static String hostSuffix(byte[] address) {
        if (address == null || address.length < 12) {
            return "absent";
        }
        return String.format(
                Locale.US,
                "%02x%02x",
                address[10] & 0xff,
                address[11] & 0xff);
    }

}
