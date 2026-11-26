package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.strategy.PairingOutcome;
import dev.applewatchandroid.bridge.strategy.PairingStrategy;
import dev.applewatchandroid.bridge.strategy.StrategyRegistry;
import dev.applewatchandroid.bridge.strategy.StrategySessionContext;
import dev.applewatchandroid.bridge.transport.L2capChannelMux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Legacy BT_CL signaling wait loops, modular strategy pairing dispatch, and
 * post-SMP validation extracted from {@link HalTransportSession}.
 */
final class HalBtClTransportHelper {
    private final HalTransportSession session;

    HalBtClTransportHelper(HalTransportSession session) {
        this.session = session;
    }

    void runExperimentalLegacyPairing(HciCodec.AdvertisingReport target) throws Exception {
        PairingStrategyId strategyId = session.activeSelectedStrategy;
        if (strategyId == null || !strategyId.isExperimental()) {
            int fallbackId = target != null && target.legacySetup != null
                    ? target.legacySetup.effectiveStrategy()
                    : PairingStrategyId.DIRECT_IPSEC_S3.wireId();
            strategyId = PairingStrategyId.fromWireId(fallbackId);
            if (strategyId == null || !strategyId.isExperimental()) {
                strategyId = PairingStrategyId.DIRECT_IPSEC_S3;
            }
        }
        StrategyRegistry registry = HalHciTransportAdapter.createSessionRegistry();
        PairingStrategy strategy = registry.requireEnabled(strategyId, effectiveStrategyPolicy());
        byte[] opticalKey = session.pairingIke.legacyOpticalPairing != null
                ? session.pairingIke.legacyOpticalPairing.oobKey()
                : null;
        String productType = target != null && target.legacySetup != null ? target.legacySetup.productType : null;
        String watchOs = target != null && target.legacySetup != null ? target.legacySetup.systemVersion : null;
        HalHciTransportAdapter hciAdapter = new HalHciTransportAdapter(session);
        try (L2capChannelMux mux = new L2capChannelMux(hciAdapter);
             StrategySessionContext ctx = HalHciTransportAdapter.createContext(
                     session, strategyId, mux, opticalKey, productType, watchOs,
                     LEGACY_POST_SMP_DIAGNOSTIC_WINDOW_MS)) {
            log("PAIRING_STRATEGY_V1:" + strategyId.wireId());
            PairingOutcome outcome = strategy.pair(ctx);
            session.legacyPairingCompleted = outcome.bondPersisted() && outcome.sessionRecordPersisted();
            log("[EXPERIMENTAL LEGACY] STRATEGY COMPLETE: strategy=S" + outcome.strategyId().wireId()
                    + " sc=" + outcome.secureConnections()
                    + " link=" + outcome.idsLinkKind()
                    + " bondPersisted=" + outcome.bondPersisted()
                    + " sessionPersisted=" + outcome.sessionRecordPersisted()
                    + " commonServices=" + outcome.commonServices()
                    + " terminusReconciled=" + outcome.terminusReconciledState()
                    + " idsLinkOpened=" + outcome.idsLinkOpened()
                    + " idsFrames=" + outcome.idsFramesObserved()
                    + "; secret/key/PIN bytes logged=false.");
            if (mux.wasDisconnected()) {
                session.connectionHandle = -1;
            }
        } finally {
            if (opticalKey != null) {
                Arrays.fill(opticalKey, (byte) 0);
            }
            if (session.pairingIke.legacyOpticalPairing != null) {
                session.pairingIke.legacyOpticalPairing.close();
                session.pairingIke.legacyOpticalPairing = null;
            }
        }
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion) throws Exception {
        return awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                0L,
                null);
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            long peerFeatures) throws Exception {
        return awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                peerFeatures,
                null,
                null);
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            long peerFeatures,
            byte[] retransmitPdu) throws Exception {
        return awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                peerFeatures,
                retransmitPdu,
                null);
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            BtClNormalLinkHandoff normalHandoff)
            throws Exception {
        return awaitBtClOpcode(
                destinationCid,
                opcode,
                peerVersion,
                0L,
                null,
                normalHandoff);
    }

    HciCodec.BtClPdu awaitBtClOpcode(
            int destinationCid,
            int opcode,
            int peerVersion,
            long peerFeatures,
            byte[] retransmitPdu,
            BtClNormalLinkHandoff normalHandoff)
            throws Exception {
        long now = BridgeClock.elapsedRealtime();
        long deadline = normalHandoff == null
                ? now + BT_CL_TIMEOUT_MS
                : Math.min(
                        now + BT_CL_TIMEOUT_MS,
                        session.normalLink.modernRegistrationDeadlineMs);
        long nextRetransmitTime = now + 800;
        if (normalHandoff != null) {
            byte[] deferred;
            while ((deferred = session.deferredSmpPdus.poll()) != null) {
                try {
                    failOnPostSmpPdu(
                            deferred,
                            "deferred before normal BT_CL handoff");
                } finally {
                    HalTransportSession.wipe(deferred);
                }
            }
        }
        while (BridgeClock.elapsedRealtime() < deadline) {
            long currentNow = BridgeClock.elapsedRealtime();
            if (retransmitPdu != null && currentNow >= nextRetransmitTime) {
                session.sendL2cap(destinationCid, retransmitPdu, "BT_CL RETRANSMIT");
                nextRetransmitTime = currentNow + 800;
            }
            HalTransportSession.Packet packet;
            try {
                long waitLimit = retransmitPdu != null ? Math.min(deadline, nextRetransmitTime) : deadline;
                packet = session.nextPacket(waitLimit);
            } catch (TimeoutException elapsed) {
                continue;
            }
            if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == session.connectionHandle) {
                    session.connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "%s: Watch disconnected while awaiting "
                                    + "BT_CL opcode 0x%02X; "
                                    + "status=0x%02X reason=0x%02X",
                            normalHandoff == null
                                    ? "BT_CL_LINK_LOST"
                                    : "WATCH_PAIRING_LINK_LOST",
                            opcode,
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = session.aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log("ACL DROP: " + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (l2cap == null) {
                continue;
            }
            if (l2cap.connectionHandle != session.connectionHandle) {
                HalTransportSession.wipe(l2cap.payload);
                continue;
            }
            if (normalHandoff != null
                    && l2cap.destinationCid
                    == BluetoothSmpCodec.FIXED_CID) {
                try {
                    failOnPostSmpPdu(
                            l2cap.payload,
                            "normal BT_CL handoff");
                } finally {
                    HalTransportSession.wipe(l2cap.payload);
                }
                continue;
            }
            if (l2cap.destinationCid == 0x0004) {
                byte[] attReply = HalGattServer.handleRequest(l2cap.payload);
                if (attReply != null) {
                    session.sendL2cap(0x0004, attReply, "ATT RESPONSE");
                }
                HalTransportSession.wipe(l2cap.payload);
                if (retransmitPdu != null) {
                    session.sendL2cap(destinationCid, retransmitPdu, "BT_CL POST_ATT_RETRANSMIT");
                    nextRetransmitTime = BridgeClock.elapsedRealtime() + 800;
                }
                continue;
            }
            if (l2cap.destinationCid != destinationCid) {
                log(String.format(
                        Locale.US,
                        "BT_CL AWAIT OTHER CID RX: cid=0x%04X bytes=%s",
                        l2cap.destinationCid,
                        HciCodec.toHex(l2cap.payload)));
                HalTransportSession.wipe(l2cap.payload);
                continue;
            }
            try {
                HciCodec.BtClPdu pdu =
                        HciCodec.parseBtCl(
                                peerVersion,
                                peerFeatures,
                                l2cap.payload);
                log(String.format(
                        Locale.US,
                        "BT_CL RX: cid=0x%04X opcode=0x%02X bytes=%s",
                        destinationCid,
                        pdu.opcode,
                        HciCodec.toHex(l2cap.payload)));
                if (pdu.opcode
                        == HciCodec.BT_CL_SERVICE_REMOVED) {
                    int removedService =
                            HciCodec.parseServiceRemoved(pdu);
                    boolean postSmpPairingRemoval =
                            session.activePairingSessionRecord != null
                                    && session.activePairingSessionRecord
                                            .state()
                                            .wireValue()
                                    >= PairingSessionRecord
                                            .DurableState
                                            .SMP_BONDED_RAW
                                            .wireValue()
                                    && removedService
                                    == HciCodec
                                            .TERMINUS_PAIRING_SERVICE_ID;
                    if (!postSmpPairingRemoval) {
                        throw new HostException(String.format(
                                Locale.US,
                                "Unexpected BT_CL SERVICE_REMOVED "
                                        + "id=0x%04X",
                                removedService));
                    }
                    byte[] confirmation =
                            HciCodec.buildRemoveConfirmationPdu(
                                    peerVersion,
                                    removedService,
                                    0);
                    try {
                        session.sendL2cap(
                                destinationCid,
                                confirmation,
                                "BT_CL REMOVE_CONFIRMATION "
                                        + "terminusPairing status=0");
                    } finally {
                        HalTransportSession.wipe(confirmation);
                    }
                    log("BT_CL PAIRING SERVICE REMOVAL ACK PASS: "
                            + "serviceID=0x0001 status=0; the single "
                            + "normal advertisement remains recorded.");
                    continue;
                }
                if (pdu.opcode == HciCodec.BT_CL_REMOVE_CONFIRMATION) {
                    log("BT_CL REMOVE_CONFIRMATION RX: " + HciCodec.toHex(l2cap.payload));
                    if (retransmitPdu != null) {
                        session.sendL2cap(destinationCid, retransmitPdu, "BT_CL POST_REMOVE_RETRANSMIT");
                        nextRetransmitTime = BridgeClock.elapsedRealtime() + 800;
                    }
                    continue;
                }
                if (normalHandoff != null
                        && pdu.opcode
                        == HciCodec.BT_CL_COMMON_SERVICES) {
                    List<Integer> common =
                            HciCodec.parseCommonServices(pdu);
                    if (common.contains(
                            HciCodec.TERMINUS_PAIRING_SERVICE_ID)
                            && !common.contains(
                            HciCodec.TERMINUS_LINK_SERVICE_ID)) {
                        throw new HostException(
                                "WATCH_PAIRING_RESET: Watch "
                                        + "re-published pairing service "
                                        + "0x0001 instead of normal "
                                        + "terminusLink service 0x0002");
                    }
                }
                if (pdu.opcode == HciCodec.BT_CL_SERVICE_ADDED) {
                    try {
                        HciCodec.BtClServiceRecord remote = HciCodec.parseServiceAdded(pdu);
                        log("BT_CL SERVICE_ADDED RX: id=0x" + Integer.toHexString(remote.serviceId));
                        byte[] commonReply = HciCodec.buildCommonServicesPdu(
                                peerVersion,
                                peerFeatures,
                                List.of(remote.serviceId));
                        session.sendL2cap(destinationCid, commonReply, "BT_CL COMMON_SERVICES");
                        if (opcode == HciCodec.BT_CL_COMMON_SERVICES) {
                            return new HciCodec.BtClPdu(
                                    HciCodec.BT_CL_COMMON_SERVICES,
                                    new byte[]{0x01, (byte) (remote.serviceId & 0xFF), (byte) ((remote.serviceId >> 8) & 0xFF)});
                        }
                    } catch (Exception err) {
                        log("BT_CL SERVICE_ADDED parse error: " + safeMessage(err));
                    }
                }
                if (pdu.opcode == HciCodec.BT_CL_REMOTE_SERVICES) {
                    try {
                        List<HciCodec.BtClServiceRecord> remoteList = HciCodec.parseRemoteServices(pdu);
                        List<Integer> ids = new ArrayList<>();
                        for (HciCodec.BtClServiceRecord r : remoteList) {
                            ids.add(r.serviceId);
                        }
                        log("BT_CL REMOTE_SERVICES RX: ids=" + ids);
                        byte[] commonReply = HciCodec.buildCommonServicesPdu(
                                peerVersion,
                                peerFeatures,
                                ids);
                        session.sendL2cap(destinationCid, commonReply, "BT_CL COMMON_SERVICES");
                        if (opcode == HciCodec.BT_CL_COMMON_SERVICES) {
                            byte[] commonPayload = HciCodec.parseBtCl(peerVersion, peerFeatures, commonReply).payload;
                            return new HciCodec.BtClPdu(
                                    HciCodec.BT_CL_COMMON_SERVICES,
                                    commonPayload);
                        }
                    } catch (Exception err) {
                        log("BT_CL REMOTE_SERVICES parse error: " + safeMessage(err));
                    }
                }
                if (pdu.opcode == HciCodec.BT_CL_CREATE_CHANNEL) {
                    try {
                        HciCodec.CreateChannel req = HciCodec.parseCreateChannel(pdu);
                        log(String.format(
                                Locale.US,
                                "BT_CL CREATE_CHANNEL RX: reqCID=0x%04X service=0x%04X",
                                req.requesterLocalCid,
                                req.serviceId));
                        byte[] acceptReply = HciCodec.buildAcceptChannelPdu(
                                peerVersion,
                                0, // status = 0 (Success)
                                req.serviceId,
                                HciCodec.TERMINUS_LOCAL_CID);
                        session.sendL2cap(destinationCid, acceptReply, "BT_CL ACCEPT_CHANNEL (reply to Watch CREATE_CHANNEL)");
                        if (opcode == HciCodec.BT_CL_ACCEPT_CHANNEL) {
                            return new HciCodec.BtClPdu(
                                    HciCodec.BT_CL_ACCEPT_CHANNEL,
                                    new byte[]{0x00, (byte) (req.serviceId & 0xFF), (byte) ((req.serviceId >> 8) & 0xFF), (byte) (req.requesterLocalCid & 0xFF), (byte) ((req.requesterLocalCid >> 8) & 0xFF)});
                        }
                    } catch (Exception err) {
                        log("BT_CL CREATE_CHANNEL parse error: " + safeMessage(err));
                    }
                }
                if (pdu.opcode == opcode) {
                    return pdu;
                }
            } finally {
                HalTransportSession.wipe(l2cap.payload);
            }
        }
        if (normalHandoff != null) {
            throw new HostException(
                    "NORMAL_HANDOFF_NO_ENDPOINT: Watch did not "
                            + "publish terminusLink service 0x0002 "
                            + "within "
                            + BT_CL_TIMEOUT_MS
                            + " ms after SMP; no explicit SMP failure "
                            + "or HCI disconnect was observed");
        }
        throw new HostException(String.format(
                Locale.US,
                "Timed out waiting for BT_CL opcode 0x%02X",
                opcode));
    }

    void failOnPostSmpPdu(
            byte[] payload,
            String phase)
            throws HostException {
        final int smpOpcode;
        try {
            smpOpcode = BluetoothSmpCodec.opcode(payload);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "POST_SMP_MALFORMED: malformed SMP PDU during "
                            + phase
                            + "; payload bytes logged=false",
                    error);
        }
        if (smpOpcode == BluetoothSmpCodec.PAIRING_FAILED) {
            final int reason;
            try {
                reason = BluetoothSmpCodec.parsePairingFailure(
                        payload);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "POST_SMP_MALFORMED: malformed Pairing Failed "
                                + "during "
                                + phase
                                + "; payload bytes logged=false",
                        error);
            }
            throw new HostException(String.format(
                    Locale.US,
                    "WATCH_SMP_PAIRING_FAILED: reason=0x%02X (%s) "
                            + "during %s; payload bytes logged=false",
                    reason,
                    BluetoothSmpCodec.pairingFailureReasonName(
                            reason),
                    phase));
        }
        throw new HostException(String.format(
                Locale.US,
                "POST_SMP_UNEXPECTED_PDU: opcode=0x%02X bytes=%d "
                        + "during %s; payload bytes logged=false",
                smpOpcode,
                payload == null ? 0 : payload.length,
                phase));
    }

}
