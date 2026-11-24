package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import static dev.applewatchandroid.bridge.ProtocolHost.*;


/** Control-channel IKEv2 SA_INIT, ML-KEM-1024 INTERMEDIATE, NULL AUTH, and PIN method selection. */
final class ModernControlIkeChannel {
    private final HalTransportSession session;
    private final ModernPairingIkeRunner pairing;

    private final ModernControlIkeAuthRunner authRunner;

    ModernControlIkeChannel(HalTransportSession session, ModernPairingIkeRunner pairing) {
        this.session = session;
        this.pairing = pairing;
        this.authRunner = new ModernControlIkeAuthRunner(session, pairing);
    }

    void probeControlSaInit(
            int responderLocalCid,
            byte[] watchSetupData)
            throws Exception {
        IkeV2Codec.InitiatorState initiator =
                IkeV2Codec.createControlSaInit(new SecureRandom());
        log("CONTROL IKE CONFIG: auth=NULL; ENCR=[AES-GCM-16/256,"
                + "ChaCha20-Poly1305]; PRF=HMAC-SHA2-512; "
                + "DH=[X448,X25519]; AdditionalKE1=ML-KEM-1024.");
        log("IKE_SA_INIT TX MATERIAL: spi="
                + HciCodec.toHex(initiator.initiatorSpi)
                + " nonce="
                + HciCodec.toHex(initiator.nonce)
                + " x448Public="
                + HciCodec.toHex(initiator.x448PublicKey)
                + " privateKeyLogged=false.");
        log("IKE_SA_INIT IKE TX: bytes="
                + initiator.ikePacket.length
                + " raw="
                + HciCodec.toHex(initiator.ikePacket));
        log("IKE_SA_INIT uIKE TX: bytes="
                + initiator.uikeFrame.length
                + " raw="
                + HciCodec.toHex(initiator.uikeFrame));
        byte[] ertmRequest =
                L2capErtmCodec.encodeInformationFrame(
                        responderLocalCid,
                        0,
                        0,
                        initiator.uikeFrame,
                        TERMINUS_FCS_ENABLED);
        log("TERMINUS ERTM TX: serviceFlags=0x01 mode=3 FCS=none "
                + "TxSeq=0 ReqSeq=0 SAR=unsegmented "
                + "bytes="
                + ertmRequest.length
                + " raw="
                + HciCodec.toHex(ertmRequest));
        session.sendL2cap(
                responderLocalCid,
                ertmRequest,
                "TERMINUS ERTM CONTROL IKE_SA_INIT");
        log("IKE_SA_INIT SAFETY BOUNDARY: exactly one "
                + "unauthenticated IKE_SA_INIT SDU was sent; ERTM "
                + "acknowledgements contain no application bytes. "
                + "PIN/private-notify/SMP/bond/activation/"
                + "setup-commit bytes sent=0.");

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        long deadline =
                BridgeClock.elapsedRealtime() + IKE_SA_INIT_TIMEOUT_MS;
        int l2capFrames = 0;
        int ertmInformationFrames = 0;
        int expectedWatchTxSequence = 0;
        boolean requestAcknowledged = false;
        while (BridgeClock.elapsedRealtime() < deadline) {
            HalTransportSession.Packet packet;
            try {
                packet = session.nextPacket(deadline);
            } catch (TimeoutException complete) {
                break;
            }
            if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                HciCodec.DisconnectionComplete disconnected =
                        HciCodec.parseDisconnectionComplete(packet.bytes);
                if (disconnected != null
                        && disconnected.connectionHandle
                        == session.connectionHandle) {
                    session.connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected before IKE_SA_INIT response; "
                                    + "status=0x%02X reason=0x%02X",
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = session.aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log("IKE_SA_INIT ACL DROP: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (!session.retainPairingTerminusPdu(l2cap, "CONTROL IKE_SA_INIT")) continue;
            l2capFrames++;
            log(String.format(
                    Locale.US,
                    "IKE_SA_INIT L2CAP RX #%d: cid=0x%04X bytes=%s",
                    l2capFrames,
                    l2cap.destinationCid,
                    HciCodec.toHex(l2cap.payload)));
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                log("IKE_SA_INIT ERTM DECODE FAIL: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(l2cap.payload));
                throw new HostException(
                        "Malformed ERTM frame from Watch",
                        error);
            }
            if (ertm.requestSequence > 1) {
                throw new HostException(
                        "Watch ERTM ReqSeq advanced beyond the single "
                                + "outbound I-frame: "
                                + ertm.requestSequence);
            }
            if (ertm.requestSequence == 1) {
                requestAcknowledged = true;
            }
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "TERMINUS ERTM S-FRAME RX: function=%d "
                                + "ReqSeq=%d P=%d F=%d FCS=%s.",
                        ertm.supervisoryFunction,
                        ertm.requestSequence,
                        ertm.poll ? 1 : 0,
                        ertm.finalBit ? 1 : 0,
                        HalTransportSession.formatErtmFcs(ertm)));
                if (ertm.poll) {
                    byte[] finalAck =
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    true,
                                    TERMINUS_FCS_ENABLED);
                    session.sendL2cap(
                            responderLocalCid,
                            finalAck,
                            "TERMINUS ERTM RR FINAL");
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    throw new HostException(
                            "Watch rejected ERTM TxSeq 0; identical "
                                    + "IKE_SA_INIT was not retransmitted");
                }
                continue;
            }
            if (ertm.sar != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented Watch ERTM response is not supported "
                                + "in this bounded probe; SAR="
                                + ertm.sar);
            }
            if (ertm.txSequence
                    == ((expectedWatchTxSequence
                    + L2capErtmCodec.SEQUENCE_MODULUS
                    - 1) % L2capErtmCodec.SEQUENCE_MODULUS)) {
                log("TERMINUS ERTM DUPLICATE I-FRAME: TxSeq="
                        + ertm.txSequence
                        + "; acknowledging without reprocessing.");
                byte[] duplicateAck =
                        L2capErtmCodec.encodeReceiverReady(
                                responderLocalCid,
                                expectedWatchTxSequence,
                                false,
                                TERMINUS_FCS_ENABLED);
                session.sendL2cap(
                        responderLocalCid,
                        duplicateAck,
                        "TERMINUS ERTM RR DUPLICATE");
                continue;
            }
            if (ertm.txSequence != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            ertmInformationFrames++;
            log(String.format(
                    Locale.US,
                    "TERMINUS ERTM I-FRAME RX #%d: TxSeq=%d "
                            + "ReqSeq=%d F=%d FCS=%s information=%s",
                    ertmInformationFrames,
                    ertm.txSequence,
                    ertm.requestSequence,
                    ertm.finalBit ? 1 : 0,
                    HalTransportSession.formatErtmFcs(ertm),
                    HciCodec.toHex(ertm.information)));

            byte[] acknowledgement =
                    L2capErtmCodec.encodeReceiverReady(
                            responderLocalCid,
                            expectedWatchTxSequence,
                            false,
                            TERMINUS_FCS_ENABLED);
            session.sendL2cap(
                    responderLocalCid,
                    acknowledgement,
                    "TERMINUS ERTM RR ACK");

            List<byte[]> ikePackets;
            try {
                ikePackets = decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                log("IKE_SA_INIT uIKE DECODE FAIL: "
                        + error.getMessage()
                        + " buffered="
                        + HciCodec.toHex(decoder.bufferedBytes()));
                throw new HostException(
                        "Malformed uIKE response from Watch",
                        error);
            }
            for (byte[] ikePacket : ikePackets) {
                if (!ModernPairingIkeRunner.hasInitiatorSpi(
                        ikePacket,
                        initiator.initiatorSpi)) {
                    log("IKE_SA_INIT STALE DROP: response initiator "
                            + "SPI does not match current request; "
                            + "bytes="
                            + ikePacket.length
                            + " packet bytes logged=false.");
                    continue;
                }
                log("IKE_SA_INIT IKE RX: bytes="
                        + ikePacket.length
                        + " raw="
                        + HciCodec.toHex(ikePacket));
                IkeV2Codec.IkePacketSummary summary;
                try {
                    summary = IkeV2Codec.parseSaInitResponse(
                            ikePacket,
                            initiator.initiatorSpi);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Invalid IKE_SA_INIT response: "
                                    + error.getMessage(),
                            error);
                }
                log(String.format(
                        Locale.US,
                        "IKE_SA_INIT PARSED: responderSPI=%s "
                                + "version=0x%02X flags=0x%02X "
                                + "payloads=%s notifies=%s.",
                        HciCodec.toHex(summary.responderSpi),
                        summary.version,
                        summary.flags,
                        formatHexList(summary.payloadTypes),
                        formatHexList(summary.notifyTypes)));
                for (int notifyType : summary.notifyTypes) {
                    if (notifyType < 0x4000) {
                        throw new HostException(String.format(
                                Locale.US,
                                "Watch returned IKE error notify 0x%04X",
                                notifyType));
                    }
                }
                if (!summary.payloadTypes.contains(IkeV2Codec.PAYLOAD_SA)
                        || !summary.payloadTypes.contains(
                        IkeV2Codec.PAYLOAD_KE)
                        || !summary.payloadTypes.contains(
                        IkeV2Codec.PAYLOAD_NONCE)) {
                        throw new HostException(
                                "IKE_SA_INIT response lacks SA, KE, or nonce");
                }
                IkeV2SessionCrypto.ControlSaInitResponse
                        controlResponse;
                IkeV2SessionCrypto.IkeSaKeys initialKeys;
                try {
                    controlResponse =
                            IkeV2SessionCrypto
                                    .parseControlSaInitResponse(
                                            ikePacket,
                                            initiator.initiatorSpi);
                    initialKeys =
                            IkeV2SessionCrypto.deriveInitialKeys(
                                    initiator,
                                    controlResponse);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Cannot establish initial IKE key schedule: "
                                    + error.getMessage(),
                            error);
                }
                log("IKE INITIAL KEYS DERIVED: X448 + "
                        + "PRF-HMAC-SHA2-512; SK_d=64 SK_ei=36 "
                        + "SK_er=36 SK_pi=64 SK_pr=64; key bytes "
                        + "and shared secret logged=false.");
                ModernPairingIkeRunner.IntermediateTransportState intermediate =
                        probeAdditionalKeyExchange(
                        responderLocalCid,
                        initiator,
                        controlResponse,
                        initialKeys,
                        expectedWatchTxSequence);
                ModernPairingIkeRunner.AuthenticatedTransportState authenticated =
                        probeControlIkeAuth(
                        responderLocalCid,
                        initiator,
                        controlResponse,
                        intermediate);
                if (pairing.opticalPairing != null) {
                    pairing.probeOpticalPairing(responderLocalCid, authenticated, watchSetupData);
                    return;
                }
                ModernPairingIkeRunner.PinTransportState pinState =
                        probePinAuthMethod(
                                responderLocalCid,
                                authenticated);
                char[] pin = null;
                AppleWatchPairingCrypto.PairingSecrets
                        pairingSecrets = null;
                try {
                    pin = awaitWatchPin(responderLocalCid, pinState);
                    pairingSecrets =
                            AppleWatchPairingCrypto.derive(
                                    pin,
                                    pinState.pinSalt,
                                    watchSetupData);
                    pin = null;
                    log("PAIRING KDF READY: PBKDF2-HMAC-SHA256 "
                            + "iterations=1600000; SPAKE2+ seed="
                            + pairingSecrets.sharedSecret.length
                            + " bytes; mandatory PPK="
                            + pairingSecrets.ppk.length
                            + " bytes; PIN/salt/key bytes "
                            + "logged=false.");
                    pairing.probePairingSession(
                            responderLocalCid,
                            pinState,
                            pairingSecrets);
                } finally {
                    if (pin != null) {
                        Arrays.fill(pin, '\0');
                    }
                    if (pairingSecrets != null) {
                        pairingSecrets.destroy();
                    }
                    pinState.destroy();
                }
                return;
            }
        }
        throw new HostException(
                "IKE_SA_INIT response timed out after "
                + IKE_SA_INIT_TIMEOUT_MS
                + " ms; L2CAP frames="
                + l2capFrames
                + " ERTM information frames="
                + ertmInformationFrames
                + " requestAcknowledged="
                + requestAcknowledged
                + " buffered="
                + HciCodec.toHex(decoder.bufferedBytes()));
    }

    ModernPairingIkeRunner.IntermediateTransportState probeAdditionalKeyExchange(
            int responderLocalCid,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse controlResponse,
            IkeV2SessionCrypto.IkeSaKeys initialKeys,
            int initialExpectedWatchTxSequence)
            throws Exception {
        IkeV2SessionCrypto.AdditionalKeRequest request;
        try {
            request =
                    IkeV2SessionCrypto.createAdditionalKeRequest(
                            new SecureRandom(),
                            initiator,
                            controlResponse,
                            initialKeys);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Cannot create ML-KEM-1024 request: "
                            + error.getMessage(),
                    error);
        }
        log("IKE_INTERMEDIATE CONFIG: exchange=43 messageID=1 "
                + "ADDKE1=ML-KEM-1024 publicKey="
                + request.publicKey.length
                + " bytes maxIKEPacket="
                + IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE
                + " encryptedFragments="
                + request.ikePackets.size()
                + " privateKeyLogged=false.");

        int outboundTxSequence = 1;
        int requestSequence = initialExpectedWatchTxSequence;
        java.util.ArrayList<byte[]> sentIntermediateFrames =
                new java.util.ArrayList<>();
        java.util.ArrayList<Integer> sentIntermediateTxSeq =
                new java.util.ArrayList<>();
        for (int index = 0;
                index < request.ikePackets.size();
                index++) {
            byte[] ikePacket = request.ikePackets.get(index);
            byte[] uikeFrame =
                    IkeV2Codec.encodeUikeFrame(ikePacket);
            byte[] ertmRequest =
                    L2capErtmCodec.encodeInformationFrame(
                            responderLocalCid,
                            outboundTxSequence,
                            requestSequence,
                            uikeFrame,
                            TERMINUS_FCS_ENABLED);
            sentIntermediateFrames.add(ertmRequest);
            sentIntermediateTxSeq.add(outboundTxSequence);
            log(String.format(
                    Locale.US,
                    "IKE_INTERMEDIATE TX fragment=%d/%d "
                            + "IKE=%d uIKE=%d ERTM=%d "
                            + "TxSeq=%d ReqSeq=%d rawIKE=%s",
                    index + 1,
                    request.ikePackets.size(),
                    ikePacket.length,
                    uikeFrame.length,
                    ertmRequest.length,
                    outboundTxSequence,
                    requestSequence,
                    HciCodec.toHex(ikePacket)));
            session.sendL2cap(
                    responderLocalCid,
                    ertmRequest,
                    "TERMINUS ERTM IKE_INTERMEDIATE "
                            + (index + 1)
                            + "/"
                            + request.ikePackets.size());
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }
        int expectedRequestSequence = outboundTxSequence;
        int intermediateRetransmits = 0;
        log("IKE_INTERMEDIATE SAFETY BOUNDARY: only encrypted "
                + "ML-KEM-1024 public-key fragments were sent; "
                + "IKE_AUTH/PIN/SMP/bond/activation/setup-commit "
                + "bytes sent=0.");

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        IkeV2SessionCrypto.IntermediateResponseAccumulator
                accumulator =
                new IkeV2SessionCrypto
                        .IntermediateResponseAccumulator(initialKeys);
        long deadline = BridgeClock.elapsedRealtime()
                + IKE_INTERMEDIATE_TIMEOUT_MS;
        int expectedWatchTxSequence =
                initialExpectedWatchTxSequence;
        int l2capFrames = 0;
        int ikePackets = 0;
        int highestWatchAck = 1;
        while (BridgeClock.elapsedRealtime() < deadline) {
            HalTransportSession.Packet packet;
            try {
                packet = session.nextPacket(deadline);
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
                    session.connectionHandle = -1;
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch disconnected during "
                                    + "IKE_INTERMEDIATE; status=0x%02X "
                                    + "reason=0x%02X",
                            disconnected.status,
                            disconnected.reason));
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = session.aclReassembler.accept(packet.bytes);
            } catch (IllegalArgumentException error) {
                log("IKE_INTERMEDIATE ACL DROP: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (!session.retainPairingTerminusPdu(l2cap, "CONTROL IKE_INTERMEDIATE")) continue;
            l2capFrames++;
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed IKE_INTERMEDIATE ERTM frame: "
                                + error.getMessage(),
                        error);
            }
            if (ertm.requestSequence
                    > expectedRequestSequence) {
                throw new HostException(
                        "Watch acknowledged unsent ERTM sequence "
                                + ertm.requestSequence);
            }
            highestWatchAck = Math.max(
                    highestWatchAck,
                    ertm.requestSequence);
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "IKE_INTERMEDIATE ERTM S-FRAME RX: "
                                + "function=%d ReqSeq=%d P=%d F=%d.",
                        ertm.supervisoryFunction,
                        ertm.requestSequence,
                        ertm.poll ? 1 : 0,
                        ertm.finalBit ? 1 : 0));
                if (ertm.poll) {
                    session.sendL2cap(
                            responderLocalCid,
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    true,
                                    TERMINUS_FCS_ENABLED),
                            "TERMINUS ERTM RR FINAL "
                                    + "IKE_INTERMEDIATE");
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    // Live 0.2.207: the Watch SREJ'd TxSeq=1 of the
                    // ML-KEM fragments and the host aborted. SREJ/REJ
                    // asks for those I-frames again.
                    if (++intermediateRetransmits > 4) {
                        throw new HostException(
                                "Watch rejected an IKE_INTERMEDIATE "
                                        + "ERTM frame");
                    }
                    boolean selective = ertm.supervisoryFunction
                            == L2capErtmCodec.SUPERVISORY_SREJ;
                    for (int index = 0;
                            index < sentIntermediateTxSeq.size();
                            index++) {
                        int txSeq = sentIntermediateTxSeq.get(index);
                        int distance = (txSeq
                                - ertm.requestSequence
                                + L2capErtmCodec.SEQUENCE_MODULUS)
                                % L2capErtmCodec.SEQUENCE_MODULUS;
                        if (selective
                                ? txSeq == ertm.requestSequence
                                : distance < sentIntermediateTxSeq.size()) {
                            session.sendL2cap(
                                    responderLocalCid,
                                    sentIntermediateFrames.get(index),
                                    "TERMINUS ERTM IKE_INTERMEDIATE "
                                            + "RETRANSMIT txSeq="
                                            + txSeq);
                            log("IKE_INTERMEDIATE RETRANSMIT: txSeq="
                                    + txSeq
                                    + " after "
                                    + (selective ? "SREJ" : "REJ")
                                    + " reqSeq="
                                    + ertm.requestSequence);
                        }
                    }
                }
                continue;
            }
            if (ertm.sar
                    != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented ERTM I-frame is not expected; SAR="
                                + ertm.sar);
            }
            int previousWatchTxSequence =
                    (expectedWatchTxSequence
                            + L2capErtmCodec.SEQUENCE_MODULUS
                            - 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            if (ertm.txSequence
                    == previousWatchTxSequence) {
                session.sendL2cap(
                        responderLocalCid,
                        L2capErtmCodec.encodeReceiverReady(
                                responderLocalCid,
                                expectedWatchTxSequence,
                                false,
                                TERMINUS_FCS_ENABLED),
                        "TERMINUS ERTM RR DUPLICATE "
                                + "IKE_INTERMEDIATE");
                continue;
            }
            if (ertm.txSequence
                    != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            log(String.format(
                    Locale.US,
                    "IKE_INTERMEDIATE ERTM I-FRAME RX: "
                            + "TxSeq=%d ReqSeq=%d informationBytes=%d.",
                    ertm.txSequence,
                    ertm.requestSequence,
                    ertm.information.length));
            session.sendL2cap(
                    responderLocalCid,
                    L2capErtmCodec.encodeReceiverReady(
                            responderLocalCid,
                            expectedWatchTxSequence,
                            false,
                            TERMINUS_FCS_ENABLED),
                    "TERMINUS ERTM RR ACK "
                            + "IKE_INTERMEDIATE");

            List<byte[]> decodedPackets;
            try {
                decodedPackets =
                        decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed IKE_INTERMEDIATE uIKE frame: "
                                + error.getMessage(),
                        error);
            }
            for (byte[] ikePacket : decodedPackets) {
                ikePackets++;
                log("IKE_INTERMEDIATE IKE RX #"
                        + ikePackets
                        + ": bytes="
                        + ikePacket.length
                        + " raw="
                        + HciCodec.toHex(ikePacket));
                byte[] ciphertext;
                try {
                    ciphertext =
                            accumulator.accept(ikePacket);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Cannot authenticate/decrypt "
                                    + "IKE_INTERMEDIATE response: "
                                    + error.getMessage(),
                            error);
                }
                if (ciphertext == null) {
                    continue;
                }
                IkeV2SessionCrypto.AdditionalKeResult result;
                try {
                    result =
                            IkeV2SessionCrypto
                                    .completeAdditionalKeyExchange(
                                            request,
                                            ciphertext,
                                            initialKeys);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "ML-KEM-1024 decapsulation failed: "
                                    + error.getMessage(),
                            error);
                }
                log("IKE_INTERMEDIATE PASS: responder ciphertext="
                        + ciphertext.length
                        + " ML-KEM sharedSecret="
                        + result.sharedSecret.length
                        + " bytes; updated SK_d=64 SK_ei=36 "
                        + "SK_er=36 SK_pi=64 SK_pr=64; "
                        + "secret/key bytes logged=false; "
                        + "highestWatchReqSeq="
                        + highestWatchAck
                        + ".");
                return new ModernPairingIkeRunner.IntermediateTransportState(
                        result,
                        request.initiatorIntAuth,
                        accumulator.responderIntAuth(),
                        expectedRequestSequence,
                        expectedWatchTxSequence);
            }
        }
        throw new HostException(
                "IKE_INTERMEDIATE response timed out after "
                        + IKE_INTERMEDIATE_TIMEOUT_MS
                        + " ms; L2CAP frames="
                        + l2capFrames
                        + " IKE packets="
                        + ikePackets
                        + " highestWatchReqSeq="
                        + highestWatchAck
                        + " expectedReqSeq="
                        + expectedRequestSequence
                        + " buffered="
                        + HciCodec.toHex(decoder.bufferedBytes()));
    }

    ModernPairingIkeRunner.AuthenticatedTransportState probeControlIkeAuth(
            int responderLocalCid,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse controlResponse,
            ModernPairingIkeRunner.IntermediateTransportState intermediate)
            throws Exception {
        return authRunner.probeControlIkeAuth(
                responderLocalCid,
                initiator,
                controlResponse,
                intermediate);
    }

    ModernPairingIkeRunner.PinTransportState probePinAuthMethod(
            int responderLocalCid,
            ModernPairingIkeRunner.AuthenticatedTransportState authenticated)
            throws Exception {
        return authRunner.probePinAuthMethod(
                responderLocalCid,
                authenticated);
    }

    char[] awaitWatchPin(int responderLocalCid, ModernPairingIkeRunner.PinTransportState pinState) throws Exception {
        return authRunner.awaitWatchPin(
                responderLocalCid,
                pinState);
    }
}
