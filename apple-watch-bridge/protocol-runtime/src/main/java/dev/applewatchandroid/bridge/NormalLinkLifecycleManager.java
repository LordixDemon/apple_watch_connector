package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Watch BLE setup/paired scanning, LE connection establishment, {@code BT_CL} normal handoff,
 * and paired reconnect lifecycle.
 */
final class NormalLinkLifecycleManager {
    private final HalTransportSession session;
    /** Peer addresses that answered BT_CL but published no terminusPairing. */
    final java.util.Set<String> rejectedSetupCandidates = new java.util.HashSet<>();
    long modernRegistrationDeadlineMs = -1;
    int normalBtClVersion = HciCodec.BT_CL_CURRENT_VERSION;
    long normalBtClFeatures;
    final BluetoothLinkMaintenance.AttServer normalAttServer =
            new BluetoothLinkMaintenance.AttServer(HalGattServer::handleRequest);
    final BluetoothFixedChannelRouter fixedChannelRouter = new BluetoothFixedChannelRouter();

    private final PairedWatchReconnectFlow reconnectFlow;

    NormalLinkLifecycleManager(HalTransportSession session) {
        this.session = session;
        this.reconnectFlow = new PairedWatchReconnectFlow(session, this);
    }
    void completeModernNormalRegistration(
            int peerVersion)
            throws Exception {
        if (session.activePairingSessionRecord == null
                || session.activePairingSessionRecord.state()
                != PairingSessionRecord.DurableState
                .SMP_BONDED_RAW
                || modernRegistrationDeadlineMs <= 0) {
            throw new HostException(
                    "Bonded pairing generation is unavailable for "
                            + "modern registration");
        }
        requireModernRegistrationTime(
                "BT_CL normal handoff");

        BtClNormalLinkHandoff normalHandoff =
                BtClNormalLinkHandoff.begin(
                        peerVersion,
                        true);
        NrLinkBluetoothPipeBootstrap bootstrap = null;
        NrLinkBluetoothPipeBootstrap.PreludeHandoff
                preludeHandoff = null;
        NormalLinkPipeSession pipe = null;
        try {
            byte[] serviceAdded =
                    normalHandoff.advertise();
            try {
                session.sendL2cap(
                        HciCodec.BT_CL_SIGNALING_CID,
                        serviceAdded,
                        "NORMAL BT_CL SERVICE_ADDED terminusLink");
            } finally {
                HalTransportSession.wipe(
                        serviceAdded);
            }

            HciCodec.BtClPdu commonServices =
                    session.awaitBtClOpcode(
                            HciCodec.BT_CL_SIGNALING_CID,
                            HciCodec.BT_CL_COMMON_SERVICES,
                            peerVersion,
                            normalHandoff);
            byte[] createChannel =
                    normalHandoff.acceptCommonServices(
                            commonServices);
            try {
                session.sendL2cap(
                        HciCodec.BT_CL_SIGNALING_CID,
                        createChannel,
                        "NORMAL BT_CL CREATE_CHANNEL "
                                + "localCID=0x0041 service=0x0002");
            } finally {
                HalTransportSession.wipe(
                        createChannel);
            }

            sendNormalTimeSync();

            HciCodec.BtClPdu acceptedChannel =
                    session.awaitBtClOpcode(
                            HciCodec.BT_CL_SIGNALING_CID,
                            HciCodec.BT_CL_ACCEPT_CHANNEL,
                            peerVersion);
            normalHandoff.acceptChannel(
                    acceptedChannel);
            int localCid =
                    normalHandoff.requesterLocalCid();
            int remoteCid =
                    normalHandoff.responderLocalCid();
            log(String.format(
                    Locale.US,
                    "NORMAL PIPE OPEN: localCID=0x%04X "
                            + "remoteCID=0x%04X ERTM=true FCS=%s; "
                            + "same encrypted ACL=true.",
                    localCid,
                    remoteCid,
                    normalHandoff.fcsEnabled()));

            bootstrap =
                    NrLinkBluetoothPipeBootstrap.begin(
                            normalHandoff,
                            new SecureRandom());
            byte[] localPrelude =
                    bootstrap.buildOutboundPreludeFrame();
            try {
                session.sendL2cap(
                        remoteCid,
                        localPrelude,
                        "NORMAL ERTM LOCAL PRELUDE");
            } finally {
                HalTransportSession.wipe(
                        localPrelude);
            }

            NrLinkBluetoothSession.Negotiated negotiated =
                    null;
            while (negotiated == null) {
                HciCodec.L2capPdu l2cap =
                        awaitNormalL2cap(
                                localCid,
                                modernRegistrationDeadlineMs,
                                "normal prelude");
                L2capErtmCodec.Frame decoded = null;
                try {
                    decoded =
                            L2capErtmCodec.decode(
                                    localCid,
                                    l2cap.payload,
                                    normalHandoff
                                            .fcsEnabled());
                    if (decoded.supervisory) {
                        try (L2capErtmSession.InboundResult
                                control =
                                bootstrap
                                        .acceptPeerControlFrame(
                                                l2cap.payload)) {
                            List<byte[]> immediate =
                                    control
                                            .immediateOutboundFrames();
                            sendNormalFrames(
                                    remoteCid,
                                    immediate,
                                    "NORMAL ERTM PRELUDE CONTROL");
                        }
                        continue;
                    }
                    negotiated =
                            bootstrap
                                    .acceptInboundPreludeFrame(
                                            l2cap.payload);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Normal prelude ERTM validation failed: "
                                    + error.getMessage(),
                            error);
                } finally {
                    if (decoded != null) {
                        HalTransportSession.wipe(
                                decoded.information);
                    }
                    HalTransportSession.wipe(
                            l2cap.payload);
                }
            }

            byte[] remotePreludeAck =
                    bootstrap
                            .buildRemotePreludeAcknowledgementFrame();
            try {
                session.sendL2cap(
                        remoteCid,
                        remotePreludeAck,
                        "NORMAL ERTM REMOTE PRELUDE RR");
            } finally {
                HalTransportSession.wipe(
                        remotePreludeAck);
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
            log("NORMAL PRELUDE PASS: state=13, flags accepted, "
                    + "ordinary role="
                    + negotiated.localRole
                    + "; UUID bytes logged=false; durable role "
                    + "checkpointed.");

            preludeHandoff =
                    bootstrap.detach();
            pipe =
                    NormalLinkPipeSession
                            .resumeFromPairingRecord(
                                    new SecureRandom(),
                                    preludeHandoff,
                                    session.activePairingSessionRecord,
                                    session.companionProfile().normalLink(
                                                    session.activePairingSessionRecord
                                                            .localIdsDeviceUuid()));
            preludeHandoff = null;

            pipe.enableLinkDirectorAnnouncements(LinkDirectorMessageCodec.sequenceSeed(
                    System.currentTimeMillis(), BridgeClock.elapsedRealtimeNanos()), ProtocolHost::log);

            List<byte[]> initialOrdinary =
                    pipe.continueAfterPrelude();
            sendNormalFrames(
                    remoteCid,
                    initialOrdinary,
                    "NORMAL CLASS-D START");

            while (BridgeClock.elapsedRealtime()
                    < modernRegistrationDeadlineMs) {
                session.postCommit.checkpointOrdinaryRegistration(
                        pipe);
                if (pipe.registrationBarrierSatisfied()) {
                    log("NORMAL REGISTRATION BARRIER PASS: "
                            + "Class D connected=true, "
                            + "Class C connected=true; physical "
                            + "Watch confirmKeysForClass(D/C) "
                            + "prerequisites delivered.");
                    session.idsSession.attachModernIdsSession(
                            pipe);
                    pipe = null;
                    session.reconnectRequested = false;
                    session.idsSession.runModernIdsBootstrapAndHold(
                            localCid,
                            remoteCid);
                    if (session.reconnectRequested) {
                        reconnectPairedWatch();
                    }
                    return;
                }

                HciCodec.L2capPdu l2cap =
                        awaitNormalL2cap(
                                localCid,
                                modernRegistrationDeadlineMs,
                                "ordinary Class D/Class C");
                try (NormalLinkPipeSession.InboundResult
                        result =
                        pipe.acceptErtmFrame(
                                l2cap.payload)) {
                    if (result.ertmSupervisory()) {
                        log(String.format(
                                Locale.US,
                                "NORMAL ERTM RX: kind=S function=%d "
                                        + "reqSeq=%d informationBytes=%d; "
                                        + "packet bytes logged=false.",
                                result.ertmSupervisoryFunction(),
                                result.ertmRequestSequence(),
                                result.ertmInformationLength()));
                    } else {
                        log(String.format(
                                Locale.US,
                                "NORMAL ERTM RX: kind=I txSeq=%d "
                                        + "reqSeq=%d informationBytes=%d; "
                                        + "packet bytes logged=false.",
                                result.ertmTxSequence(),
                                result.ertmRequestSequence(),
                                result.ertmInformationLength()));
                    }
                    if (result.ikeRetransmissions() > 0) {
                        log("NORMAL IKE RETRANSMISSION: count="
                                + result.ikeRetransmissions()
                                + "; exact prior request matched and "
                                + "cached byte-identical response reused; "
                                + "packet bytes logged=false.");
                    }
                    if (result.ikeSaInitCapabilityFlags() != 0) {
                        int capabilities =
                                result.ikeSaInitCapabilityFlags();
                        log("NORMAL IKE_SA_INIT PEER CAPABILITIES: "
                                + "childless="
                                + ((capabilities
                                & OrdinaryIkeSaInit
                                .CAPABILITY_CHILDLESS) != 0)
                                + " usePpk="
                                + ((capabilities
                                & OrdinaryIkeSaInit
                                .CAPABILITY_USE_PPK) != 0)
                                + "; capability booleans only; "
                                + "packet/key bytes logged=false; "
                                + "pairing PPK reused=false.");
                    }
                    if (result.preSaStaleEspDrops() > 0) {
                        log("NORMAL PRE-SA STALE ESP DROP: count="
                                + result.preSaStaleEspDrops()
                                + "; responder has no fresh Child SA; "
                                + "link-local multicast "
                                + "packet authenticated=false, "
                                + "delivered=false; address/SPI/packet "
                                + "bytes logged=false.");
                    }
                    for (String observation
                            : result.linkDirectorObservations()) {
                        log("NORMAL LINK DIRECTOR RX: "
                                + observation
                                + "; protected request authenticated=true, "
                                + "empty INFORMATIONAL ACK queued=true, "
                                + "Class-C unlock changed=false.");
                    }
                    List<byte[]> immediate =
                            result.immediateErtmFrames();
                    sendNormalFrames(
                            remoteCid,
                            immediate,
                            "NORMAL ORDINARY IKE/ERTM");

                    List<NormalLinkPipeSession.DeliveredIp>
                            delivered =
                            result.deliveredIp();
                    try {
                        if (!delivered.isEmpty()) {
                            boolean registrationBarrier =
                                    pipe.registrationBarrierSatisfied();
                            if (registrationBarrier) {
                                log("NORMAL REGISTRATION BARRIER PASS: "
                                        + "Class D connected=true, "
                                        + "Class C connected=true; physical "
                                        + "Watch confirmKeysForClass(D/C) "
                                        + "prerequisites delivered (early IP).");
                                session.idsSession.attachModernIdsSession(
                                        pipe);
                                pipe = null;
                                session.idsSession.runModernIdsBootstrapWithInitialIp(
                                        localCid,
                                        remoteCid,
                                        delivered);
                                return;
                            }
                            if (!NormalLinkPipeSession
                                    .canDiscardBeforeIdsAttachment(
                                            delivered)) {
                                throw new HostException(
                                        "Normal IP payload is not eligible "
                                                + "for pre-IDS discard: "
                                                + "count="
                                                + delivered.size()
                                                + " first={"
                                                + delivered.get(0)
                                                .safeStructure()
                                                + "}");
                            }
                            log("NORMAL PRE-IDS CLASS-D LINK-LOCAL "
                                    + "MULTICAST DROP: "
                                    + "count="
                                    + delivered.size()
                                    + " first={"
                                    + delivered.get(0)
                                    .safeStructure()
                                    + "}; registration barrier="
                                    + registrationBarrier
                                    + "; ERTM acknowledged=true; "
                                    + "consumer attached=false; "
                                    + "payload destroyed=true.");
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

                    List<byte[]> controls =
                            result.controlMessages();
                    try {
                        if (!controls.isEmpty()) {
                            log("NORMAL CONTROL RX: count="
                                    + controls.size()
                                    + "; bytes logged=false.");
                        }
                    } finally {
                        for (byte[] control : controls) {
                            HalTransportSession.wipe(
                                    control);
                        }
                        controls.clear();
                    }
                } catch (IllegalArgumentException
                        | IllegalStateException error) {
                    throw new HostException(
                            "Ordinary normal-link validation failed: "
                                    + error.getMessage(),
                            error);
                } finally {
                    HalTransportSession.wipe(
                            l2cap.payload);
                }
            }
            throw new HostException(
                    "Modern registration exceeded the "
                            + MODERN_REGISTRATION_TIMEOUT_MS
                            + " ms post-SMP local deadline");
        } finally {
            if (pipe != null) {
                pipe.close();
            }
            if (preludeHandoff != null) {
                preludeHandoff.close();
            }
            if (bootstrap != null) {
                bootstrap.close();
            }
        }
    }

    boolean tryReconnectAfterWatchDisconnect(
            String context,
            HostException disconnectError)
            throws Exception {
        return reconnectFlow.tryReconnectAfterWatchDisconnect(
                context,
                disconnectError);
    }

    boolean reconnectPairedWatch() throws Exception {
        return reconnectFlow.reconnectPairedWatch();
    }

    HciCodec.L2capPdu awaitNormalL2cap(
            int destinationCid,
            long deadline,
            String label)
            throws Exception {
        while (BridgeClock.elapsedRealtime() < deadline) {
            HalTransportSession.Packet packet;
            try {
                packet =
                        session.nextPacket(
                                deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(
                                packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == session.connectionHandle) {
                    normalAttServer.reset();
                    session.connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected during %s; "
                                    + "status=0x%02X reason=0x%02X",
                            label,
                            disconnected.status,
                            disconnected.reason));
                }
                if (packet.bytes.length >= 2 && (packet.bytes[0] & 255) != 0x13) {
                    log(String.format(Locale.US, "NORMAL HCI EVENT RX: event=0x%02X subevent=0x%02X bytes=%d; values logged=false.",
                            packet.bytes[0] & 255,
                            packet.bytes[0] == 0x3e && packet.bytes.length > 2 ? packet.bytes[2] & 255 : 0,
                            packet.bytes.length));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap =
                        session.aclReassembler.accept(
                                packet.bytes);
            } catch (IllegalArgumentException error) {
                log("NORMAL ACL DROP: "
                        + error.getMessage()
                        + "; bytes logged=false.");
                continue;
            }
            if (l2cap == null) {
                continue;
            }
            if (l2cap.connectionHandle != session.connectionHandle) {
                HalTransportSession.wipe(
                        l2cap.payload);
                continue;
            }
            if (l2cap.destinationCid != destinationCid) {
                try { serviceNormalFixedChannel(l2cap); }
                finally { HalTransportSession.wipe(l2cap.payload); }
                continue;
            }
            log(String.format(
                    Locale.US,
                    "NORMAL RX: cid=0x%04X payloadBytes=%d; "
                            + "bytes logged=false.",
                    destinationCid,
                    l2cap.payload.length));
            return l2cap;
        }
        throw new HostException(
                "Timed out awaiting "
                        + label
                        + " on normal CID");
    }

    void sendNormalTimeSync() throws Exception {
        byte[] information = HciCodec.buildTimeSyncInformation(
                normalBtClVersion, normalBtClFeatures);
        try {
            session.sendL2cap(HciCodec.BT_CL_SIGNALING_CID, information,
                    "NORMAL BT_CL TIME_SYNC_INFO unixNanos");
        } finally { HalTransportSession.wipe(information); }
        byte[] correction = HciCodec.buildTimeSyncCorrection(
                normalBtClVersion, normalBtClFeatures, System.nanoTime());
        try {
            session.sendL2cap(HciCodec.BT_CL_SIGNALING_CID, correction,
                    "NORMAL BT_CL TIME_SYNC_CORRECTION timebase=1/1");
        } finally { HalTransportSession.wipe(correction); }
    }

    void serviceNormalFixedChannel(HciCodec.L2capPdu l2cap) throws Exception {
        int cid = l2cap.destinationCid;
        int opcode = l2cap.payload.length == 0 ? -1 : l2cap.payload[0] & 255;
        log(String.format(Locale.US, "NORMAL OTHER CID RX: cid=0x%04X opcode=0x%02X bytes=%d; values logged=false.",
                cid, opcode, l2cap.payload.length));
        if (cid == HciCodec.BT_CL_SIGNALING_CID && opcode == HciCodec.BT_CL_TIME_SYNC_REQUEST) {
            HciCodec.BtClPdu request = HciCodec.parseBtCl(
                    normalBtClVersion, normalBtClFeatures, l2cap.payload);
            if (request.payload.length != 0) {
                log("NORMAL BT_CL TIME_SYNC_REQUEST rejected: nonempty payload.");
                return;
            }
            sendNormalTimeSync();
            log("NORMAL BT_CL TIME_SYNC_REQUEST answered with information and correction.");
        } else if (cid == BluetoothLinkMaintenance.ATT_CID) {
            byte[] response = normalAttServer.accept(session.connectionHandle, l2cap.payload);
            try {
                if (response != null) {
                    session.sendL2cap(cid, response, "NORMAL ATT RESPONSE");
                    log("NORMAL ATT TRANSACTION REPLIED: requestOpcode=" + opcode
                            + " responseOpcode=" + (response[0] & 255));
                }
            } finally { HalTransportSession.wipe(response); }
        } else if (cid == BluetoothLinkMaintenance.LE_SIGNALING_CID) {
            List<byte[]> responses = BluetoothLinkMaintenance.leSignalingReplies(l2cap.payload);
            try {
                for (byte[] response : responses) session.sendL2cap(cid, response, "NORMAL LE SIGNALING RESPONSE");
            } finally { responses.forEach(response -> HalTransportSession.wipe(response)); }
        } else if (cid == HciCodec.BT_CL_SIGNALING_CID && opcode == HciCodec.BT_CL_SERVICE_REMOVED) {
            HciCodec.BtClPdu removed = HciCodec.parseBtCl(HciCodec.BT_CL_CURRENT_VERSION, l2cap.payload);
            int service = HciCodec.parseServiceRemoved(removed);
            byte[] confirmation = HciCodec.buildRemoveConfirmationPdu(HciCodec.BT_CL_CURRENT_VERSION, service, 0);
            try { session.sendL2cap(cid, confirmation, "BT_CL BACKGROUND REMOVE_CONFIRMATION"); }
            finally { HalTransportSession.wipe(confirmation); }
            log("BT_CL BACKGROUND SERVICE REMOVAL ACK: service=" + service);
        } else if (cid == BluetoothSmpCodec.FIXED_CID && opcode == BluetoothSmpCodec.PAIRING_FAILED) {
            session.failOnPostSmpPdu(l2cap.payload, "normal-link fixed channel");
        }
    }

    void sendNormalFrames(
            int remoteCid,
            List<byte[]> frames,
            String label)
            throws Exception {
        if (frames == null) {
            return;
        }
        try {
            for (int index = 0;
                    index < frames.size();
                    index++) {
                if (index > 0) {
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                session.sendL2cap(
                        remoteCid,
                        frames.get(index),
                        label
                                + " "
                                + (index + 1)
                                + "/"
                                + frames.size());
            }
        } finally {
            for (byte[] frame : frames) {
                HalTransportSession.wipe(
                        frame);
            }
        }
    }

    void requireModernRegistrationTime(
            String label)
            throws HostException {
        if (BridgeClock.elapsedRealtime()
                >= modernRegistrationDeadlineMs) {
            throw new HostException(
                    "Modern registration deadline elapsed before "
                            + label);
        }
    }

    /**
     * Separates "no advertising reports reach the host" from "the Watch is not
     * advertising setup data" when a scan finds no target; logs counts only.
     * Contributed by Muggles001.
     */
    private static final class ScanDiagnostics {
        private static final long INTERVAL_MS = 10_000;
        private final java.util.TreeMap<Integer, Integer> leSubevents = new java.util.TreeMap<>();
        private long nextLog;
        private int events;
        private int legacyReports;
        private int setupPayloads;
        private int bestSetupRssi = Integer.MIN_VALUE;
        private final java.util.TreeMap<Integer, Integer> fe25Subtypes = new java.util.TreeMap<>();
        private final java.util.TreeMap<Integer, Integer> appleTypes = new java.util.TreeMap<>();
        private final java.util.LinkedHashMap<String, Integer> fe25Samples = new java.util.LinkedHashMap<>();

        ScanDiagnostics(long now) {
            nextLog = now + INTERVAL_MS;
        }

        void observe(byte[] event, List<HciCodec.AdvertisingReport> reports) {
            events++;
            if (event.length > 2 && (event[0] & 0xff) == 0x3E) {
                leSubevents.merge(event[2] & 0xff, 1, Integer::sum);
            }
            legacyReports += reports.size();
            for (HciCodec.AdvertisingReport report : reports) {
                inspectAppleFields(report);
                if (report.setup == null) continue;
                setupPayloads++;
                bestSetupRssi = Math.max(bestSetupRssi, report.rssi);
            }
        }

        // Walks AD structures directly so FE25 subtypes and Apple manufacturer
        // types the setup parser rejects are still counted.
        private void inspectAppleFields(HciCodec.AdvertisingReport report) {
            byte[] data = report.data;
            if (data == null) return;
            int offset = 0;
            while (offset < data.length) {
                int length = data[offset] & 0xff;
                if (length == 0 || offset + 1 + length > data.length) return;
                int type = data[offset + 1] & 0xff;
                int value = offset + 2;
                int end = offset + 1 + length;
                if (type == 0x16 && end - value >= 3
                        && (data[value] & 0xff) == 0x25 && (data[value + 1] & 0xff) == 0xFE) {
                    fe25Subtypes.merge(data[value + 2] & 0x7F, 1, Integer::sum);
                    String field = HciCodec.toHex(java.util.Arrays.copyOfRange(data, offset, end));
                    if (fe25Samples.size() < 4 && !fe25Samples.containsKey(field)) {
                        fe25Samples.put(field, report.rssi);
                    }
                } else if (type == 0xFF && end - value >= 3
                        && (data[value] & 0xff) == 0x4C && data[value + 1] == 0) {
                    appleTypes.merge(data[value + 2] & 0xff, 1, Integer::sum);
                }
                offset = end;
            }
        }

        void maybeLog(long now) {
            if (now < nextLog) return;
            nextLog = now + INTERVAL_MS;
            write("SCAN SUMMARY");
        }

        void write(String label) {
            ProtocolHost.log(label + ": events=" + events + " leSubevents=" + leSubevents
                    + " legacyReports=" + legacyReports + " fe25SetupPayloads=" + setupPayloads
                    + (setupPayloads > 0 ? " bestSetupRssi=" + bestSetupRssi : "")
                    + " fe25Subtypes=" + fe25Subtypes + " appleMfgTypes=" + appleTypes
                    + "; addresses logged=false.");
            if (label.endsWith("FINAL")) {
                for (java.util.Map.Entry<String, Integer> sample : fe25Samples.entrySet()) {
                    ProtocolHost.log("SCAN FE25 SAMPLE: field=" + sample.getKey() + " rssi=" + sample.getValue());
                }
            }
        }
    }

    private static final int OPCODE_LE_SET_EXTENDED_SCAN_PARAMETERS = 0x2041;
    private static final int OPCODE_LE_SET_EXTENDED_SCAN_ENABLE = 0x2042;
    private static final long EXTENDED_SCAN_PROBE_MS = 15_000;

    /**
     * Diagnostic only, run after the legacy scan has already failed: Watch
     * setup advertisements sent as Bluetooth 5 extended PDUs never reach a
     * legacy scan. Extended scan commands make later legacy scan/connect
     * commands invalid until reset, so the session must end after this probe.
     */
    private void probeExtendedScan() throws HalTransportSession.StopRequested {
        if (session.stopRequested.get() || session.localIdentity == null) {
            return;
        }
        byte[] address = null;
        try {
            // Controllers reject extended scan commands (status 0x0C) once
            // legacy scanning has been used, until the next HCI Reset.
            session.sendCommandComplete(HciCodec.OPCODE_RESET, new byte[0]);
            session.scanEnabled = false;
            session.sendCommandComplete(HciCodec.OPCODE_SET_EVENT_MASK, HciCodec.classicEventMask());
            // LE event mask bits 0, 1 and 12: connection complete, advertising
            // report and extended advertising report.
            session.sendCommandComplete(HciCodec.OPCODE_LE_SET_EVENT_MASK,
                    new byte[]{0x03, 0x10, 0, 0, 0, 0, 0, 0});
            address = session.localIdentity.address();
            session.sendCommandComplete(HciCodec.OPCODE_LE_SET_RANDOM_ADDRESS,
                    HciCodec.buildLeSetRandomAddressParameters(address));
            session.sendCommandComplete(OPCODE_LE_SET_EXTENDED_SCAN_PARAMETERS, new byte[]{
                    (byte) session.localIdentity.addressType(), 0x00, 0x01,
                    0x01, 0x60, 0x00, 0x60, 0x00});
            session.sendCommandComplete(OPCODE_LE_SET_EXTENDED_SCAN_ENABLE,
                    new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00});
        } catch (HalTransportSession.StopRequested stopped) {
            throw stopped;
        } catch (Exception unsupported) {
            log("EXTENDED SCAN PROBE: could not start: " + unsupported.getMessage());
            return;
        } finally {
            HalTransportSession.wipe(address);
        }
        log("EXTENDED SCAN PROBE: active on LE 1M for " + EXTENDED_SCAN_PROBE_MS + " ms.");
        ScanDiagnostics diagnostics = new ScanDiagnostics(BridgeClock.elapsedRealtime());
        int nonLegacy = 0;
        long deadline = BridgeClock.elapsedRealtime() + EXTENDED_SCAN_PROBE_MS;
        try {
            while (BridgeClock.elapsedRealtime() < deadline) {
                session.checkStop();
                diagnostics.maybeLog(BridgeClock.elapsedRealtime());
                HalTransportSession.Packet packet;
                try { packet = session.nextPacket(Math.min(deadline, BridgeClock.elapsedRealtime() + 250)); }
                catch (TimeoutException elapsed) { continue; }
                if (packet.kind != HalTransportSession.PacketKind.EVENT) continue;
                List<HciCodec.AdvertisingReport> reports = parseExtendedAdvertisingReports(packet.bytes);
                for (HciCodec.AdvertisingReport report : reports) {
                    if ((report.eventType & 0x10) == 0) nonLegacy++;
                }
                diagnostics.observe(packet.bytes, reports);
            }
        } catch (HalTransportSession.StopRequested stopped) {
            throw stopped;
        } catch (Exception interrupted) {
            log("EXTENDED SCAN PROBE: stopped early: " + interrupted.getMessage());
        } finally {
            log("EXTENDED SCAN PROBE: nonLegacyReports=" + nonLegacy + ".");
            diagnostics.write("EXTENDED SCAN SUMMARY FINAL");
            try {
                if (!session.stopRequested.get()) {
                    session.sendCommandComplete(OPCODE_LE_SET_EXTENDED_SCAN_ENABLE,
                            new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
                }
            } catch (Exception ignored) {
                log("EXTENDED SCAN PROBE: disable was not confirmed; HAL close follows.");
            }
        }
    }

    // LE Extended Advertising Report (subevent 0x0D); the setup payload field is
    // filled by the same AD parser used for legacy reports.
    static List<HciCodec.AdvertisingReport> parseExtendedAdvertisingReports(byte[] event) {
        List<HciCodec.AdvertisingReport> reports = new java.util.ArrayList<>();
        if (event.length < 4 || (event[0] & 0xff) != 0x3E || (event[2] & 0xff) != 0x0D) return reports;
        int end = Math.min(event.length, (event[1] & 0xff) + 2);
        int count = event[3] & 0xff;
        int offset = 4;
        for (int index = 0; index < count && offset + 24 <= end; index++) {
            int eventType = (event[offset] & 0xff) | ((event[offset + 1] & 0xff) << 8);
            int addressType = event[offset + 2] & 0xff;
            byte[] address = java.util.Arrays.copyOfRange(event, offset + 3, offset + 9);
            int rssi = event[offset + 13];
            int dataLength = event[offset + 23] & 0xff;
            int dataStart = offset + 24;
            if (dataStart + dataLength > end) break;
            byte[] data = java.util.Arrays.copyOfRange(event, dataStart, dataStart + dataLength);
            reports.add(new HciCodec.AdvertisingReport(eventType, addressType, address, rssi, data,
                    HciCodec.parseWatchSetupPayload(data)));
            offset = dataStart + dataLength;
        }
        return reports;
    }

    HciCodec.AdvertisingReport awaitTarget() throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + SCAN_TIMEOUT_MS;
        WatchSetupTargetSelector selector = new WatchSetupTargetSelector();
        LegacyWatchPairingCodec.LegacyTargetSelector legacySelector =
                EXPERIMENTAL_LEGACY_PAIRING.get()
                        ? new LegacyWatchPairingCodec.LegacyTargetSelector()
                        : null;
        WatchDiscoverySelection<HciCodec.AdvertisingReport> choices = new WatchDiscoverySelection<>();
        java.util.Map<String, Long> announced = new java.util.HashMap<>();
        int incomplete = 0;
        ScanDiagnostics diagnostics = new ScanDiagnostics(BridgeClock.elapsedRealtime());
        while (BridgeClock.elapsedRealtime() < deadline) {
            diagnostics.maybeLog(BridgeClock.elapsedRealtime());
            if (COMPANION_DISCOVERY.get()) {
                HciCodec.AdvertisingReport selected = choices.select(COMPANION_WATCH_SELECTIONS.poll(), BridgeClock.elapsedRealtime());
                if (selected != null) {
                    return selected;
                }
            }
            if (legacySelector != null) {
                HciCodec.AdvertisingReport readyLegacy =
                        legacySelector.pollReady(BridgeClock.elapsedRealtime());
                if (readyLegacy != null) {
                    HciCodec.AdvertisingReport acceptedLegacy =
                            handleDiscoveredLegacyTarget(readyLegacy, choices, announced);
                    if (acceptedLegacy != null) {
                        return acceptedLegacy;
                    }
                }
            }
            HalTransportSession.Packet packet;
            try { packet = session.nextPacket(Math.min(deadline, BridgeClock.elapsedRealtime() + 250)); }
            catch (TimeoutException elapsed) { continue; }
            if (packet.kind != HalTransportSession.PacketKind.EVENT) continue;
            List<HciCodec.AdvertisingReport> reports = HciCodec.parseLegacyAdvertisingReports(packet.bytes);
            diagnostics.observe(packet.bytes, reports);
            for (HciCodec.AdvertisingReport report : reports) {
                if (report.address != null && rejectedSetupCandidates.contains(HciCodec.toHex(report.address))) continue;
                HciCodec.AdvertisingReport target = selector.accept(report, BridgeClock.elapsedRealtime());
                if (target != null) {
                    if (session.pairingIke.opticalPairing != null) {
                        if (!session.pairingIke.opticalPairing.matches(target.setup.decodedIdentifier, target.setup.decodedMetadata)) continue;
                        log("OPTICAL TARGET MATCHED: fresh setup advertisement; name/key/address logged=false.");
                        return target;
                    }
                    if (session.pairingIke.legacyOpticalPairing != null) continue;
                    if (COMPANION_DISCOVERY.get()) {
                        long now = BridgeClock.elapsedRealtime();
                        String token = choices.observe(HciCodec.toHex(target.address), target, now);
                        if (now - announced.getOrDefault(token, -2_000L) >= 1_000) {
                            announced.put(token, now);
                            log("WATCH_DISCOVERED_V1:" + token + ":" + target.setup.decodedMetadata.productType()
                                    + ":" + target.setup.decodedMetadata.systemVersionString() + ":" + target.rssi);
                        }
                        continue;
                    }
                    log("WATCH SETUP TARGET MATCHED: complete 12-byte setup data; scanResponse="
                            + (report.eventType == 4) + " rssi=" + report.rssi + "; identifiers logged=false.");
                    return target;
                }
                if (legacySelector != null) {
                    HciCodec.AdvertisingReport legacyTarget =
                            legacySelector.accept(report, BridgeClock.elapsedRealtime());
                    if (legacyTarget != null) {
                        HciCodec.AdvertisingReport acceptedLegacy =
                                handleDiscoveredLegacyTarget(legacyTarget, choices, announced);
                        if (acceptedLegacy != null) {
                            return acceptedLegacy;
                        }
                        continue;
                    }
                }
                if (report.setup != null && incomplete++ < 4) {
                    log("WATCH SETUP SKIP: incomplete or non-target advertisement; setupBytes="
                            + report.setup.watchSetupData.length + " eventType=" + report.eventType
                            + "; identifiers logged=false.");
                }
            }
        }
        diagnostics.write("SCAN SUMMARY FINAL");
        probeExtendedScan();
        throw new HostException("Complete target Watch setup advertisement not found in "
                + SCAN_TIMEOUT_MS + " ms; return the Watch to new-pair animation");
    }

    HciCodec.AdvertisingReport handleDiscoveredLegacyTarget(
            HciCodec.AdvertisingReport legacyTarget,
            WatchDiscoverySelection<HciCodec.AdvertisingReport> choices,
            java.util.Map<String, Long> announced) {
        if (session.pairingIke.legacyOpticalPairing != null) {
            if (!legacyTarget.legacySetup.matchesOpticalCode(session.pairingIke.legacyOpticalPairing)) {
                return null;
            }
            log("[EXPERIMENTAL LEGACY] OPTICAL TARGET MATCHED: strategy="
                    + legacyTarget.legacySetup.effectiveStrategy() + "; name/key/address logged=false.");
            return legacyTarget;
        }
        if (session.pairingIke.opticalPairing != null) {
            return null;
        }
        if (COMPANION_DISCOVERY.get()) {
            long now = BridgeClock.elapsedRealtime();
            String token = choices.observe(HciCodec.toHex(legacyTarget.address), legacyTarget, now);
            if (now - announced.getOrDefault(token, -2_000L) >= 1_000) {
                announced.put(token, now);
                String product = legacyTarget.legacySetup.productType != null
                        ? legacyTarget.legacySetup.productType
                        : "Apple Watch [EXPERIMENTAL-LEGACY-S"
                                + legacyTarget.legacySetup.effectiveStrategy() + "]";
                String version = legacyTarget.legacySetup.systemVersion != null
                        ? legacyTarget.legacySetup.systemVersion
                        : "watchOS 1-9 (exp)";
                log("WATCH_DISCOVERED_V1:" + token + ":" + product + ":" + version + ":" + legacyTarget.rssi);
            }
            return null;
        }
        log("[EXPERIMENTAL LEGACY] WATCH TARGET MATCHED: strategy="
                + legacyTarget.legacySetup.effectiveStrategy()
                + " hasUuid9AA4730F=" + legacyTarget.legacySetup.hasExtensiblePairServiceUuid
                + " rssi=" + legacyTarget.rssi + "; identifiers logged=false.");
        return legacyTarget;
    }

    /**
     * Records a peer that is reachable over BT_CL but is not the Watch, then
     * drops the link so the scan can move to the next Apple device.
     */
    void rejectSetupCandidate(
            HciCodec.AdvertisingReport candidate,
            String reason)
            throws Exception {
        if (candidate != null && candidate.address != null) {
            rejectedSetupCandidates.add(
                    HciCodec.toHex(candidate.address));
        }
        log("SETUP CANDIDATE REJECTED: "
                + reason
                + "; rssi="
                + (candidate != null ? candidate.rssi : 0)
                + "; probing the next Apple device.");
        session.createPending = false;
        if (session.connectionHandle != -1) {
            try {
                session.sendCommandStatus(
                        HciCodec.OPCODE_DISCONNECT,
                        HciCodec.buildDisconnectParameters(session.connectionHandle));
                long disconnectDeadline =
                        BridgeClock.elapsedRealtime() + 1_500;
                while (BridgeClock.elapsedRealtime() < disconnectDeadline) {
                    HalTransportSession.Packet packet = session.nextPacket(disconnectDeadline);
                    if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                        HciCodec.DisconnectionComplete done =
                                HciCodec.parseDisconnectionComplete(
                                        packet.bytes);
                        if (done != null
                                && done.connectionHandle == session.connectionHandle) {
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // A lost Disconnection Complete must not abort the probe.
            }
            session.connectionHandle = -1;
        }
        session.aclReassembler.reset();
        session.peerConnectionAddress = null;
    }

    HciCodec.AdvertisingReport awaitPairedTarget() throws Exception {
        byte[] peerIrk = null;
        byte[] peerIdentityAddr = null;
        if (session.activePairingSessionRecord != null && session.activePairingSessionRecord.bluetoothBond() != null) {
            try {
                peerIrk = BluetoothBondSecretRecord.extractPeerIdentityResolvingKey(session.activePairingSessionRecord.bluetoothBond());
                peerIdentityAddr = BluetoothBondSecretRecord.extractPeerIdentityAddress(session.activePairingSessionRecord.bluetoothBond());
            } catch (Exception ignored) {
            }
        }

        long deadline = BridgeClock.elapsedRealtime() + 30_000;
        while (BridgeClock.elapsedRealtime() < deadline) {
            HalTransportSession.Packet packet = session.nextPacket(deadline);
            if (packet.kind != HalTransportSession.PacketKind.EVENT) {
                continue;
            }
            HciCodec.LeConnectionComplete incoming =
                    HciCodec.parseLeConnectionComplete(packet.bytes);
            if (incoming != null && incoming.status == 0) {
                session.connectionHandle = incoming.connectionHandle;
                session.peerConnectionAddressType = incoming.peerAddressType;
                session.peerConnectionAddress = incoming.peerAddress.clone();
                log(String.format(
                        Locale.US,
                        "[WatchHal] PAIRED INCOMING LE ACL CONNECTED: handle=0x%04X",
                        session.connectionHandle));
                return null;
            }
            for (HciCodec.AdvertisingReport report
                    : HciCodec.parseLegacyAdvertisingReports(packet.bytes)) {
                if (report.eventType != 0 && report.eventType != 1) {
                    continue;
                }
                if (session.peerConnectionAddress != null
                        && Arrays.equals(report.address, session.peerConnectionAddress)) {
                    log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by peer address.");
                    return report;
                }
                if (peerIdentityAddr != null
                        && Arrays.equals(report.address, peerIdentityAddr)) {
                    log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by peer identity address.");
                    return report;
                }
                if (peerIrk != null) {
                    if (BleSecureConnectionsCrypto.matchesRpa(report.address, peerIrk)) {
                        log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by IRK-resolved RPA (rssi=" + report.rssi + ").");
                        return report;
                    }
                } else if (hasAppleManufacturerData(report.data) && report.rssi >= -50) {
                    log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by Apple 0x004C connectable beacon (rssi=" + report.rssi + ").");
                    return report;
                }
                if (report.setup != null && report.isExpectedTarget()) {
                    log("[WatchHal] PAIRED WATCH ADVERTISEMENT MATCHED by setup target.");
                    return report;
                }
            }
        }
        throw new HostException(
                "Paired Watch advertisement not found in 30000 ms");
    }

    static boolean hasAppleManufacturerData(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        int offset = 0;
        while (offset < data.length) {
            int len = data[offset] & 0xff;
            if (len == 0 || offset + 1 + len > data.length) {
                break;
            }
            int type = data[offset + 1] & 0xff;
            if (type == 0xff && len >= 3) {
                int companyId = ((data[offset + 3] & 0xff) << 8) | (data[offset + 2] & 0xff);
                if (companyId == 0x004c) {
                    return true;
                }
            }
            offset += 1 + len;
        }
        return false;
    }

    static boolean isNearbyWatchBeacon(byte[] data) {
        if (data == null || data.length < 5) {
            return false;
        }
        int offset = 0;
        while (offset < data.length) {
            int len = data[offset] & 0xff;
            if (len == 0 || offset + 1 + len > data.length) {
                break;
            }
            int type = data[offset + 1] & 0xff;
            if (type == 0xff && len >= 4) {
                int companyId = ((data[offset + 3] & 0xff) << 8) | (data[offset + 2] & 0xff);
                if (companyId == 0x004c) {
                    int appleType = data[offset + 4] & 0xff;
                    if (appleType == 0x10 || appleType == 0x0B || appleType == 0x01) {
                        return true;
                    }
                }
            }
            offset += 1 + len;
        }
        return false;
    }

    HciCodec.LeConnectionComplete awaitConnectionComplete(
            byte[] expectedAddress,
            int expectedAddressType) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime() < deadline) {
            HalTransportSession.Packet packet = session.nextPacket(deadline);
            if (packet.kind != HalTransportSession.PacketKind.EVENT) {
                continue;
            }
            HciCodec.LeConnectionComplete complete =
                    HciCodec.parseLeConnectionComplete(packet.bytes);
            if (complete == null) {
                continue;
            }
            session.createPending = false;
            if (complete.status != 0) {
                throw new HostException(String.format(
                        Locale.US,
                        "LE Connection Complete status=0x%02X",
                        complete.status));
            }
            session.connectionHandle = complete.connectionHandle;
            if (expectedAddress != null && expectedAddress.length == 6) {
                if (!Arrays.equals(complete.peerAddress, expectedAddress)) {
                    continue;
                }
            }
            return complete;
        }
        throw new HostException("LE direct connection timed out");
    }

    HciCodec.LeConnectionComplete awaitConnectionComplete(
            HciCodec.AdvertisingReport target) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime() < deadline) {
            HalTransportSession.Packet packet = session.nextPacket(deadline);
            if (packet.kind != HalTransportSession.PacketKind.EVENT) {
                continue;
            }
            HciCodec.LeConnectionComplete complete =
                    HciCodec.parseLeConnectionComplete(packet.bytes);
            if (complete == null) {
                continue;
            }
            session.createPending = false;
            if (complete.status != 0) {
                throw new HostException(String.format(
                        Locale.US,
                        "LE Connection Complete status=0x%02X",
                        complete.status));
            }
            // Record every successful live handle before validating its
            // peer, so even a surprising connection is disconnected.
            session.connectionHandle = complete.connectionHandle;
            if (complete.peerAddressType != target.addressType
                    || !Arrays.equals(complete.peerAddress, target.address)) {
                throw new HostException(
                        "LE Connection Complete peer does not match selected target");
            }
            return complete;
        }
        throw new HostException("LE connection timed out");
    }
}
