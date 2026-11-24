package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Paired Watch direct/scanned LE reconnection, BT_CL negotiation, and
 * link-layer setup recovery for {@link NormalLinkLifecycleManager}.
 */
final class PairedWatchReconnectFlow {
    private final HalTransportSession session;
    private final NormalLinkLifecycleManager normalLink;

    PairedWatchReconnectFlow(HalTransportSession session, NormalLinkLifecycleManager normalLink) {
        this.session = session;
        this.normalLink = normalLink;
    }

    /**
     * Reconnects only after a completed Hello on an already-committed
     * pairing. POLICY (0x40) and unpaired identity {@code 0x13} must not
     * open a new control SYN.
     */
    boolean tryReconnectAfterWatchDisconnect(
            String context,
            HostException disconnectError)
            throws Exception {
        if (disconnectError.getMessage() == null
                || !disconnectError.getMessage().contains("Watch disconnected")
                || session.activePairingSessionRecord == null
                || session.activePairingSessionRecord.state().wireValue()
                        < PairingSessionRecord.DurableState.CLASS_C_ESTABLISHED.wireValue()) {
            return false;
        }
        if (!IdsPairedReconnectPolicy.allowReconnectAfterDisconnect(
                session.idsSession.idsControlRejectedByPolicy,
                session.idsSession.idsControlReadyObserved,
                session.activePairingSessionRecord.state().wireValue()
                        >= PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED.wireValue())) {
            log("[WatchHal] SKIP PAIRED RECONNECT after "
                    + context
                    + ": policy="
                    + session.idsSession.idsControlRejectedByPolicy
                    + " hello="
                    + session.idsSession.idsControlReadyObserved
                    + " pairedCommit="
                    + (session.activePairingSessionRecord.state().wireValue()
                        >= PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED.wireValue())
                    + "; "
                    + disconnectError.getMessage());
            return false;
        }
        if (session.activePairingSessionRecord != null
                && session.activePairingSessionRecord.state()
                        == PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED
                && session.postCommit.watchDrivenActivationObserved) {
            log("[WatchHal] ACTIVATION DELIVERED BEFORE DISCONNECT: advancing state to ACTIVATION_CONFIRMED.");
            try {
                PairingSessionRecord confirmed =
                        session.activePairingSessionRecord.advanceTo(
                                PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED);
                session.persistAndReplaceActivePairingSession(confirmed);
            } catch (Exception advErr) {
                log("[WatchHal] Failed to persist ACTIVATION_CONFIRMED checkpoint: "
                        + safeMessage(advErr));
            }
        }
        log("[WatchHal] PAIRED RECONNECT TRIGGERED: Watch disconnected during "
                + context
                + ": "
                + disconnectError.getMessage()
                + "; requesting paired reconnection...");
        session.reconnectRequested = true;
        return true;
    }

    boolean reconnectPairedWatch() throws Exception {
        while (true) {
            if (session.activePairingSessionRecord == null) {
                return false;
            }
        byte[] targetAddress = session.peerConnectionAddress != null ? session.peerConnectionAddress.clone() : null;
        int targetAddressType = session.peerConnectionAddressType;
        session.connectionHandle = -1;

        int ownAddressType = session.localIdentity.addressType();

        if (targetAddress != null && (targetAddressType == 0 || targetAddressType == 1)) {
            log(String.format(
                    Locale.US,
                    "[WatchHal] PAIRED RECONNECT: initiating direct LE connection to Watch (type=%d ownType=%d)...",
                    targetAddressType, ownAddressType));
            session.resetObservedLeLinkState();
            session.createPending = true;
            session.sendCommandStatus(
                    HciCodec.OPCODE_LE_CREATE_CONNECTION,
                    HciCodec.buildLeCreateConnectionParameters(
                            targetAddress,
                            targetAddressType,
                            ownAddressType));
            try {
                HciCodec.LeConnectionComplete connection =
                        normalLink.awaitConnectionComplete(targetAddress, targetAddressType);
                session.createPending = false;
                session.connectionHandle = connection.connectionHandle;
                session.peerConnectionAddressType = connection.peerAddressType;
                session.peerConnectionAddress = connection.peerAddress.clone();
                log(String.format(
                        Locale.US,
                        "[WatchHal] PAIRED DIRECT LE ACL CONNECTED: handle=0x%04X",
                        session.connectionHandle));
            } catch (HalTransportSession.StopRequested stopped) {
                throw stopped;
            } catch (Exception directFailed) {
                session.createPending = false;
                log("[WatchHal] Direct LE connection failed: "
                        + safeMessage(directFailed)
                        + "; scanning for Watch...");
                try {
                    session.sendCommandComplete(
                            HciCodec.OPCODE_LE_CREATE_CONNECTION_CANCEL,
                            new byte[0]);
                } catch (Exception ignored) {
                }
            }
        }

        if (session.connectionHandle == -1) {
            log("[WatchHal] PAIRED RECONNECT: scanning for Watch advertisement after respring/restart...");
            session.scanEnabled = true;
            session.sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_SCAN_ENABLE,
                    HciCodec.scanEnableParameters(true));

            HciCodec.AdvertisingReport target = normalLink.awaitPairedTarget();
            session.sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_SCAN_ENABLE,
                    HciCodec.scanEnableParameters(false));
            session.scanEnabled = false;

            if (target != null && session.connectionHandle == -1) {
                if (target.setup != null) {
                    if (target.setup.decodedIdentifier != null) {
                        session.activeWatchSetupIdentifier = target.setup.decodedIdentifier;
                    }
                    if (target.setup.decodedMetadata != null) {
                        session.activeWatchSetupMetadata = target.setup.decodedMetadata;
                    }
                }
                session.resetObservedLeLinkState();
                session.createPending = true;
                session.sendCommandStatus(
                        HciCodec.OPCODE_LE_CREATE_CONNECTION,
                        HciCodec.buildLeCreateConnectionParameters(
                                target,
                                ownAddressType));
                HciCodec.LeConnectionComplete connection =
                        normalLink.awaitConnectionComplete(target);
                session.createPending = false;
                session.connectionHandle = connection.connectionHandle;
                session.peerConnectionAddressType = connection.peerAddressType;
                session.peerConnectionAddress = connection.peerAddress.clone();
                log(String.format(
                        Locale.US,
                        "[WatchHal] PAIRED LE ACL CONNECTED: handle=0x%04X",
                        session.connectionHandle));
            }
        }

        byte[] ltk = session.activeLongTermKey;
        if (ltk == null && session.activePairingSessionRecord != null && session.activePairingSessionRecord.bluetoothBond() != null) {
            try {
                ltk = BluetoothBondSecretRecord.extractLongTermKey(session.activePairingSessionRecord.bluetoothBond());
            } catch (Exception extractErr) {
                log("[WatchHal] Could not extract LTK from bond record: " + safeMessage(extractErr));
            }
        }

        if (ltk != null) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {}
            log("[WatchHal] PAIRED RECONNECT: starting LE link encryption with stored LTK...");
            try {
                session.startLeEncryption(ltk);
                log("[WatchHal] PAIRED RECONNECT: LE link encryption active!");
            } catch (HostException encErr) {
                log("[WatchHal] PAIRED RECONNECT: Encryption failed on candidate ("
                        + safeMessage(encErr)
                        + "); candidate was not the bonded Watch, retrying scan...");
                if (session.connectionHandle != -1) {
                    try {
                        session.sendCommandStatus(HciCodec.OPCODE_DISCONNECT, HciCodec.buildDisconnectParameters(session.connectionHandle));
                        long disconnDeadline = BridgeClock.elapsedRealtime() + 1500;
                        while (BridgeClock.elapsedRealtime() < disconnDeadline) {
                            try {
                                HalTransportSession.Packet p = session.nextPacket(disconnDeadline);
                                if (p.kind == HalTransportSession.PacketKind.EVENT) {
                                    HciCodec.DisconnectionComplete dc = HciCodec.parseDisconnectionComplete(p.bytes);
                                    if (dc != null && dc.connectionHandle == session.connectionHandle) {
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                    session.connectionHandle = -1;
                }
                session.peerConnectionAddress = null;
                return false;
            }
        }

        session.negotiateLeLinkParameters(session.connectionHandle);

        session.sendL2cap(
                HciCodec.BT_CL_SIGNALING_CID,
                HciCodec.buildBtClVersionPdu(),
                "BT_CL VERSION");
        HciCodec.BtClPdu versionPdu =
                session.awaitBtClOpcode(
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.BT_CL_VERSION_INFO,
                        HciCodec.BT_CL_CURRENT_VERSION);
        HciCodec.VersionInfo peerVersion =
                HciCodec.parseVersionInfo(versionPdu);
        normalLink.normalBtClVersion = peerVersion.version;
        normalLink.normalBtClFeatures = peerVersion.features;
        log(String.format(
                Locale.US,
                "[WatchHal] PAIRED BT_CL VERSION RX: version=%d features=0x%08X.",
                peerVersion.version,
                peerVersion.features));

        session.sendL2cap(
                HciCodec.BT_CL_SIGNALING_CID,
                HciCodec.buildRemoteServicesPdu(
                        peerVersion.version,
                        peerVersion.features,
                        List.of(
                                HciCodec.terminusLinkService(),
                                HciCodec.terminusPairingService())),
                "BT_CL REMOTE_SERVICES com.apple.terminusLink / terminusPairing");
        HciCodec.BtClPdu commonPdu =
                session.awaitBtClOpcode(
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.BT_CL_COMMON_SERVICES,
                        peerVersion.version,
                        peerVersion.features);
        List<Integer> services = HciCodec.parseCommonServices(commonPdu);
        IdsPairedReconnectPolicy.AdvertisedMode mode =
                IdsPairedReconnectPolicy.advertisedMode(services);
        if (mode == IdsPairedReconnectPolicy.AdvertisedMode.SETUP_ONLY) {
            if (session.operationalPairingId != null) {
                throw new HostException("Confirmed pair advertises setup only; preserving keys and refusing automatic fresh PIN pairing");
            }
            log("PAIRED RECONNECT: Watch advertises setup only; fresh PIN pairing required.");
            session.disconnectBeforeFreshPairing();
            session.activePairingSessionRecord.destroy();
            session.activePairingSessionRecord = null;
            return false;
        }
        if (mode != IdsPairedReconnectPolicy.AdvertisedMode.NORMAL) {
            throw new HostException("Watch did not advertise terminusLink on reconnect; common=" + services);
        }
        int selectedServiceId = HciCodec.TERMINUS_LINK_SERVICE_ID;

        session.sendL2cap(
                HciCodec.BT_CL_SIGNALING_CID,
                HciCodec.buildCreateChannelPdu(
                        peerVersion.version,
                        peerVersion.features,
                        HciCodec.TERMINUS_LOCAL_CID,
                        selectedServiceId),
                "BT_CL CREATE_CHANNEL localCID=0x0040 service=0x"
                        + Integer.toHexString(selectedServiceId));

        normalLink.sendNormalTimeSync();
        HciCodec.BtClPdu acceptPdu =
                session.awaitBtClOpcode(
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.BT_CL_ACCEPT_CHANNEL,
                        peerVersion.version,
                        peerVersion.features);
        HciCodec.AcceptChannel accept =
                HciCodec.parseAcceptChannel(acceptPdu);
        log(String.format(
                Locale.US,
                "[WatchHal] PAIRED TERMINUS PIPE REOPENED: localCID=0x%04X remoteCID=0x%04X service=0x%04X.",
                HciCodec.TERMINUS_LOCAL_CID,
                accept.responderLocalCid,
                selectedServiceId));

        BtClNormalLinkHandoff normalHandoff =
                BtClNormalLinkHandoff.fromReconnectedChannel(
                        peerVersion.version,
                        HciCodec.TERMINUS_LOCAL_CID,
                        accept.responderLocalCid,
                        true);

        if (session.activePairingSessionRecord != null
                && session.activePairingSessionRecord.addressesConfirmed()
                && session.activePairingSessionRecord.localRole()
                == NrLinkBluetoothPrelude.LocalRole.RESPONDER
                && session.activePairingSessionRecord.state().wireValue()
                >= PairingSessionRecord.DurableState
                .ACTIVATION_CONFIRMED.wireValue()) {
            PairingSessionRecord pinned =
                    session.activePairingSessionRecord.pinLocalRole(
                            NrLinkBluetoothPrelude.LocalRole.INITIATOR);
            try {
                session.persistAndReplaceActivePairingSession(pinned);
                pinned = null;
                log("NORMAL PRELUDE: pinned stored role to INITIATOR. "
                        + "The responder projection answered the setup "
                        + "control socket from the Watch address, and "
                        + "the Watch kernel reset that acknowledgement.");
            } finally {
                if (pinned != null) {
                    pinned.destroy();
                }
            }
        }
        NrLinkBluetoothPrelude.PairingState preludeState =
                NrLinkBluetoothPrelude.PairingState.HAS_COMPLETED_PAIRING;
        NrLinkBluetoothPipeBootstrap bootstrap =
                NrLinkBluetoothPipeBootstrap.beginPaired(
                        normalHandoff,
                        session.activePairingSessionRecord != null
                                ? session.activePairingSessionRecord.localRole()
                                : NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                        preludeState,
                        new SecureRandom());
        byte[] localPrelude =
                bootstrap.buildOutboundPreludeFrame();
        try {
            session.sendL2cap(
                    accept.responderLocalCid,
                    localPrelude,
                    "PAIRED NORMAL ERTM LOCAL PRELUDE");
        } finally {
            HalTransportSession.wipe(localPrelude);
        }

        NrLinkBluetoothSession.Negotiated negotiated = null;
        long preludeDeadline = BridgeClock.elapsedRealtime() + 10_000;
        while (negotiated == null) {
            HciCodec.L2capPdu l2cap =
                    normalLink.awaitNormalL2cap(
                            HciCodec.TERMINUS_LOCAL_CID,
                            preludeDeadline,
                            "paired normal prelude");
            L2capErtmCodec.Frame decoded = null;
            try {
                decoded =
                        L2capErtmCodec.decode(
                                HciCodec.TERMINUS_LOCAL_CID,
                                l2cap.payload,
                                normalHandoff.fcsEnabled());
                if (decoded.supervisory) {
                    try (L2capErtmSession.InboundResult control =
                                 bootstrap.acceptPeerControlFrame(l2cap.payload)) {
                        List<byte[]> immediate = control.immediateOutboundFrames();
                        normalLink.sendNormalFrames(
                                accept.responderLocalCid,
                                immediate,
                                "PAIRED NORMAL ERTM PRELUDE CONTROL");
                    }
                    continue;
                }
                negotiated =
                        bootstrap.acceptInboundPreludeFrame(l2cap.payload);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Paired normal prelude ERTM validation failed: "
                                + error.getMessage(),
                        error);
            } finally {
                if (decoded != null) {
                    HalTransportSession.wipe(decoded.information);
                }
                HalTransportSession.wipe(l2cap.payload);
            }
        }

        byte[] remotePreludeAck =
                bootstrap.buildRemotePreludeAcknowledgementFrame();
        try {
            session.sendL2cap(
                    accept.responderLocalCid,
                    remotePreludeAck,
                    "PAIRED NORMAL ERTM REMOTE PRELUDE RR");
        } finally {
            HalTransportSession.wipe(remotePreludeAck);
        }

        if (negotiated.localRole != session.activePairingSessionRecord.localRole()) {
            log("NORMAL PRELUDE: elected role="
                    + negotiated.localRole
                    + " stored role="
                    + session.activePairingSessionRecord.localRole()
                    + "; a confirmed quartet keeps the stored role.");
        } else {
            log("NORMAL PRELUDE: role="
                    + negotiated.localRole
                    + " matches the stored quartet projection.");
        }
        PairingSessionRecord negotiatedRecord =
                session.activePairingSessionRecord
                        .withPreludeNegotiated(
                                negotiated.localRole);
        try {
            session.persistAndReplaceActivePairingSession(
                    negotiatedRecord);
            negotiatedRecord = null;
        } finally {
            if (negotiatedRecord != null) {
                negotiatedRecord.destroy();
            }
        }

        NrLinkBluetoothPipeBootstrap.PreludeHandoff preludeHandoff =
                bootstrap.detach();
        NormalLinkPipeSession.diagnosticLogger =
                ProtocolHost::log;
        NormalLinkPipeSession pipe =
                NormalLinkPipeSession.resumeFromPairingRecord(
                        new SecureRandom(),
                        preludeHandoff,
                        session.activePairingSessionRecord,
                        session.companionProfile().normalLink(
                                session.activePairingSessionRecord.localIdsDeviceUuid()));
        log(session.postCommit.addressProjection("provisional", pipe));
        pipe.enableLinkDirectorAnnouncements(LinkDirectorMessageCodec.sequenceSeed(
                System.currentTimeMillis(), BridgeClock.elapsedRealtimeNanos()), ProtocolHost::log);
        List<byte[]> initialOrdinary =
                pipe.continueAfterPrelude();
        normalLink.sendNormalFrames(
                accept.responderLocalCid,
                initialOrdinary,
                "PAIRED NORMAL CLASS-D START");

        long modernRegistrationDeadlineMs =
                BridgeClock.elapsedRealtime() + 15_000;
        while (BridgeClock.elapsedRealtime()
                < modernRegistrationDeadlineMs) {
            session.postCommit.checkpointOrdinaryRegistration(
                    pipe);
            if (pipe.registrationBarrierSatisfied()) {
                log(session.postCommit.addressProjection("after-ike", pipe));
                log("PAIRED NORMAL REGISTRATION BARRIER PASS: "
                        + "Class D connected=true, "
                        + "Class C connected=true; physical "
                        + "Watch confirmKeysForClass(D/C) "
                        + "prerequisites delivered.");
                session.idsSession.attachModernIdsSession(
                        pipe);
                pipe = null;
                log("PAIRED NORMAL PIPE RESUMED: ready for modern IDS hold and proxy activation!");
                // Post-commit starts inside the bootstrap below, once the IDS
                // control channel is up. Sending here would race it: the Watch
                // rejects every service connector opened before its control
                // Hello, so the setup messages would only queue.
                session.idsSession.fireProactiveStaleFlowResets(
                        accept.responderLocalCid);
                session.reconnectRequested = false;
                session.idsSession.runModernIdsBootstrapAndHold(
                        HciCodec.TERMINUS_LOCAL_CID,
                        accept.responderLocalCid);
                if (!session.reconnectRequested) {
                    return true;
                }
                log("[WatchHal] PAIRED RECONNECT LOOP: re-establishing connection without call stack recursion...");
                continue;
            }

            HciCodec.L2capPdu l2cap =
                    normalLink.awaitNormalL2cap(
                            HciCodec.TERMINUS_LOCAL_CID,
                            modernRegistrationDeadlineMs,
                            "paired ordinary Class D/Class C");
            try (NormalLinkPipeSession.InboundResult
                    result =
                    pipe.acceptErtmFrame(
                            l2cap.payload)) {
                if (result.ertmSupervisory()) {
                    log(String.format(
                            Locale.US,
                            "PAIRED NORMAL ERTM RX: kind=S function=%d "
                                    + "reqSeq=%d informationBytes=%d; "
                                    + "packet bytes logged=false.",
                            result.ertmSupervisoryFunction(),
                            result.ertmRequestSequence(),
                            result.ertmInformationLength()));
                } else {
                    log(String.format(
                            Locale.US,
                            "PAIRED NORMAL ERTM RX: kind=I txSeq=%d "
                                    + "reqSeq=%d informationBytes=%d; "
                                    + "packet bytes logged=false.",
                            result.ertmTxSequence(),
                            result.ertmRequestSequence(),
                            result.ertmInformationLength()));
                }
                List<byte[]> immediate =
                        result.immediateErtmFrames();
                normalLink.sendNormalFrames(
                        accept.responderLocalCid,
                        immediate,
                        "PAIRED NORMAL ORDINARY IKE/ERTM");

                List<NormalLinkPipeSession.DeliveredIp>
                        delivered =
                        result.deliveredIp();
                try {
                    if (!delivered.isEmpty()) {
                        boolean registrationBarrier =
                                pipe.registrationBarrierSatisfied();
                        if (registrationBarrier) {
                            log("PAIRED NORMAL REGISTRATION BARRIER PASS: "
                                    + "Class D connected=true, "
                                    + "Class C connected=true; physical "
                                    + "Watch confirmKeysForClass(D/C) "
                                    + "prerequisites delivered (early IP).");
                            session.idsSession.attachModernIdsSession(
                                    pipe);
                            pipe = null;
                            session.reconnectRequested = false;
                            session.idsSession.runModernIdsBootstrapWithInitialIp(
                                    HciCodec.TERMINUS_LOCAL_CID,
                                    accept.responderLocalCid,
                                    delivered);
                            if (!session.reconnectRequested) {
                                return true;
                            }
                            log("[WatchHal] PAIRED RECONNECT LOOP: re-establishing connection without call stack recursion (early IP)...");
                            continue;
                        }
                        if (pipe.classDEstablished()) {
                            log("PAIRED NORMAL CLASS-D EARLY IP: "
                                    + delivered.size()
                                    + " packet(s) received before Class C settled; safe early drop/settle.");
                        } else if (!NormalLinkPipeSession
                                .canDiscardBeforeIdsAttachment(
                                        delivered)) {
                            throw new HostException(
                                    "Unexpected unicast IP before ordinary "
                                            + "registration barrier satisfied");
                        }
                    }
                } finally {
                    if (pipe != null) {
                        for (NormalLinkPipeSession.DeliveredIp
                                packet : delivered) {
                            packet.destroy();
                        }
                        delivered.clear();
                    }
                }
            } finally {
                HalTransportSession.wipe(l2cap.payload);
            }
        }
        throw new HostException(
                "Timed out waiting for paired ordinary Class-D/Class-C registration");
        }
    }

}
