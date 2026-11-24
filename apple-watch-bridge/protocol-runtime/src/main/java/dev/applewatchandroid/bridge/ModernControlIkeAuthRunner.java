package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Control-channel IKE_AUTH, PIN auth-method negotiation (0xC545/0xC546), and
 * asynchronous PIN input polling for {@link ModernControlIkeChannel}.
 */
final class ModernControlIkeAuthRunner {
    private final HalTransportSession session;
    private final ModernPairingIkeRunner pairing;

    ModernControlIkeAuthRunner(HalTransportSession session, ModernPairingIkeRunner pairing) {
        this.session = session;
        this.pairing = pairing;
    }

    ModernPairingIkeRunner.AuthenticatedTransportState probeControlIkeAuth(
            int responderLocalCid,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse controlResponse,
            ModernPairingIkeRunner.IntermediateTransportState intermediate)
            throws Exception {
        IkeV2SessionCrypto.IkeAuthRequest request;
        try {
            request =
                    IkeV2SessionCrypto
                            .createControlIkeAuthRequest(
                                    new SecureRandom(),
                                    initiator,
                                    controlResponse,
                                    intermediate
                                            .additionalKeResult
                                            .updatedKeys,
                                    intermediate.initiatorIntAuth,
                                    intermediate.responderIntAuth);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Cannot create control IKE_AUTH request: "
                            + error.getMessage(),
                    error);
        }
        log("IKE_AUTH CONFIG: exchange=35 messageID=2 childless=true "
                + "IDi=ID_NULL IDr=ID_KEY_ID(\""
                + IkeV2SessionCrypto.CONTROL_PAIRING_KEY_ID
                + "\") AUTH=NULL/13 INITIAL_CONTACT=true "
                + "SA/TS/CP/EAP/privateNotify=false "
                + "RFC9242 IntAuth=true authData="
                + request.authenticationDataLength
                + " bytes encryptedPackets="
                + request.ikePackets.size()
                + " secret/key/auth bytes logged=false.");

        int outboundTxSequence =
                intermediate.nextOutboundTxSequence;
        int requestSequence =
                intermediate.nextExpectedWatchTxSequence;
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
            log(String.format(
                    Locale.US,
                    "IKE_AUTH TX packet=%d/%d IKE=%d uIKE=%d "
                            + "ERTM=%d TxSeq=%d ReqSeq=%d "
                            + "rawEncryptedIKE=%s",
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
                    "TERMINUS ERTM IKE_AUTH "
                            + (index + 1)
                            + "/"
                            + request.ikePackets.size());
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }
        int expectedRequestSequence =
                outboundTxSequence;
        log("IKE_AUTH SAFETY BOUNDARY: encrypted standard IKEv2 "
                + "IDi/INITIAL_CONTACT/IDr/NULL-AUTH only; "
                + "Apple private notify/PIN/SMP/bond/activation/"
                + "setup-commit bytes sent=0.");

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        IkeV2SessionCrypto.IkeAuthResponseAccumulator accumulator =
                new IkeV2SessionCrypto
                        .IkeAuthResponseAccumulator(
                                initiator,
                                controlResponse,
                                intermediate
                                        .additionalKeResult
                                        .updatedKeys,
                                intermediate.initiatorIntAuth,
                                intermediate.responderIntAuth);
        long deadline =
                BridgeClock.elapsedRealtime()
                        + IKE_AUTH_TIMEOUT_MS;
        int expectedWatchTxSequence =
                intermediate.nextExpectedWatchTxSequence;
        int highestWatchAck =
                intermediate.nextOutboundTxSequence;
        int l2capFrames = 0;
        int ikePackets = 0;
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
                            "Watch disconnected during IKE_AUTH; "
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
                log("IKE_AUTH ACL DROP: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (!session.retainPairingTerminusPdu(l2cap, "CONTROL IKE_AUTH")) continue;
            l2capFrames++;
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed IKE_AUTH ERTM frame: "
                                + error.getMessage(),
                        error);
            }
            if (ertm.requestSequence
                    > expectedRequestSequence) {
                throw new HostException(
                        "Watch acknowledged unsent IKE_AUTH "
                                + "ERTM sequence "
                                + ertm.requestSequence);
            }
            highestWatchAck = Math.max(
                    highestWatchAck,
                    ertm.requestSequence);
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "IKE_AUTH ERTM S-FRAME RX: function=%d "
                                + "ReqSeq=%d P=%d F=%d.",
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
                            "TERMINUS ERTM RR FINAL IKE_AUTH");
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    throw new HostException(
                            "Watch rejected the IKE_AUTH "
                                    + "ERTM frame");
                }
                continue;
            }
            if (ertm.sar
                    != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented IKE_AUTH ERTM I-frame "
                                + "is not expected; SAR="
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
                        "TERMINUS ERTM RR DUPLICATE IKE_AUTH");
                continue;
            }
            if (ertm.txSequence
                    != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected IKE_AUTH Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            log(String.format(
                    Locale.US,
                    "IKE_AUTH ERTM I-FRAME RX: TxSeq=%d "
                            + "ReqSeq=%d informationBytes=%d.",
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
                    "TERMINUS ERTM RR ACK IKE_AUTH");

            List<byte[]> decodedPackets;
            try {
                decodedPackets =
                        decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed IKE_AUTH uIKE frame: "
                                + error.getMessage(),
                        error);
            }
            for (byte[] ikePacket : decodedPackets) {
                ikePackets++;
                log("IKE_AUTH IKE RX #"
                        + ikePackets
                        + ": bytes="
                        + ikePacket.length
                        + " rawEncryptedIKE="
                        + HciCodec.toHex(ikePacket));
                IkeV2SessionCrypto.IkeAuthResponse authResponse;
                try {
                    authResponse =
                            accumulator.accept(ikePacket);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Cannot authenticate/decrypt "
                                    + "IKE_AUTH response: "
                                    + error.getMessage(),
                            error);
                }
                if (authResponse == null) {
                    continue;
                }
                if (highestWatchAck
                        != expectedRequestSequence) {
                    throw new HostException(
                            "Watch IKE_AUTH response did not "
                                    + "acknowledge all request "
                                    + "I-frames; highest="
                                    + highestWatchAck
                                    + " expected="
                                    + expectedRequestSequence);
                }
                log("IKE_AUTH PASS: responder ID_KEY_ID matched; "
                        + "NULL-AUTH verified with updated SK_pr; "
                        + "payloads="
                        + formatHexList(
                                authResponse.payloadTypes)
                        + " notifies="
                        + formatHexList(
                                authResponse.notifyTypes)
                        + " authData="
                        + authResponse.authenticationDataLength
                        + " bytes; childless IKE SA authenticated; "
                        + "secret/key/auth bytes logged=false.");
                return new ModernPairingIkeRunner.AuthenticatedTransportState(
                        intermediate,
                        expectedRequestSequence,
                        expectedWatchTxSequence);
            }
        }
        throw new HostException(
                "IKE_AUTH response timed out after "
                        + IKE_AUTH_TIMEOUT_MS
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

    ModernPairingIkeRunner.PinTransportState probePinAuthMethod(
            int responderLocalCid,
            ModernPairingIkeRunner.AuthenticatedTransportState authenticated)
            throws Exception {
        IkeV2SessionCrypto.IkeSaKeys updatedKeys =
                authenticated.intermediate
                        .additionalKeResult.updatedKeys;
        IkeV2SessionCrypto.PinAuthMethodRequest request;
        try {
            request =
                    IkeV2SessionCrypto
                            .createPinAuthMethodRequest(
                                    new SecureRandom(),
                                    updatedKeys);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Cannot create PIN auth-method request: "
                            + error.getMessage(),
                    error);
        }
        log("PIN_METHOD CONFIG: exchange=37 messageID=3 "
                + "privateNotify=0xC545 protocol=0 spiSize=0 "
                + "NRTLV(type=1,length=1,value=PIN/2) "
                + "plaintextBytes="
                + request.plaintext.length
                + " encryptedPackets="
                + request.ikePackets.size()
                + " secret/key bytes logged=false.");

        int outboundTxSequence =
                authenticated.nextOutboundTxSequence;
        int requestSequence =
                authenticated.nextExpectedWatchTxSequence;
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
            log(String.format(
                    Locale.US,
                    "PIN_METHOD TX packet=%d/%d IKE=%d uIKE=%d "
                            + "ERTM=%d TxSeq=%d ReqSeq=%d "
                            + "rawEncryptedIKE=%s",
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
                    "TERMINUS ERTM PIN_METHOD "
                            + (index + 1)
                            + "/"
                            + request.ikePackets.size());
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }
        int expectedRequestSequence =
                outboundTxSequence;
        log("PIN_METHOD SAFETY BOUNDARY: encrypted Apple private "
                + "notify 0xC545 requesting PIN/2 only; PIN value/"
                + "salt bytes/PSK/previous-pairing/IKE pairing "
                + "session/SMP/bond/activation/setup-commit bytes "
                + "sent or logged=0.");

        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        IkeV2SessionCrypto.PinAuthMethodResponseAccumulator
                accumulator =
                new IkeV2SessionCrypto
                        .PinAuthMethodResponseAccumulator(
                                updatedKeys);
        long deadline =
                BridgeClock.elapsedRealtime()
                        + PIN_AUTH_METHOD_TIMEOUT_MS;
        int expectedWatchTxSequence =
                authenticated.nextExpectedWatchTxSequence;
        int highestWatchAck =
                authenticated.nextOutboundTxSequence;
        int l2capFrames = 0;
        int ikePackets = 0;
        boolean emptyResponseLogged = false;
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
                            "Watch disconnected during PIN_METHOD; "
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
                log("PIN_METHOD ACL DROP: "
                        + error.getMessage()
                        + " raw="
                        + HciCodec.toHex(packet.bytes));
                continue;
            }
            if (!session.retainPairingTerminusPdu(l2cap, "PIN_METHOD")) continue;
            l2capFrames++;
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed PIN_METHOD ERTM frame: "
                                + error.getMessage(),
                        error);
            }
            if (ertm.requestSequence
                    > expectedRequestSequence) {
                throw new HostException(
                        "Watch acknowledged unsent PIN_METHOD "
                                + "ERTM sequence "
                                + ertm.requestSequence);
            }
            highestWatchAck = Math.max(
                    highestWatchAck,
                    ertm.requestSequence);
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "PIN_METHOD ERTM S-FRAME RX: function=%d "
                                + "ReqSeq=%d P=%d F=%d.",
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
                            "TERMINUS ERTM RR FINAL PIN_METHOD");
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    throw new HostException(
                            "Watch rejected the PIN_METHOD "
                                    + "ERTM frame");
                }
                continue;
            }
            if (ertm.sar
                    != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented PIN_METHOD ERTM I-frame "
                                + "is not expected; SAR="
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
                        "TERMINUS ERTM RR DUPLICATE PIN_METHOD");
                continue;
            }
            if (ertm.txSequence
                    != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected PIN_METHOD Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            log(String.format(
                    Locale.US,
                    "PIN_METHOD ERTM I-FRAME RX: TxSeq=%d "
                            + "ReqSeq=%d informationBytes=%d.",
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
                    "TERMINUS ERTM RR ACK PIN_METHOD");

            List<byte[]> decodedPackets;
            try {
                decodedPackets =
                        decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed PIN_METHOD uIKE frame: "
                                + error.getMessage(),
                        error);
            }
            for (byte[] ikePacket : decodedPackets) {
                ikePackets++;
                log("PIN_METHOD IKE RX #"
                        + ikePackets
                        + ": bytes="
                        + ikePacket.length
                        + " rawEncryptedIKE="
                        + HciCodec.toHex(ikePacket));
                IkeV2SessionCrypto.PinAuthMethodResponse
                        pinResponse;
                try {
                    pinResponse =
                            accumulator.accept(ikePacket);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Cannot authenticate/decrypt "
                                    + "PIN_METHOD response: "
                                    + error.getMessage(),
                            error);
                }
                if (pinResponse == null) {
                    if (accumulator.requestAcknowledged()
                            && !emptyResponseLogged) {
                        emptyResponseLogged = true;
                        log("PIN_METHOD ACK PASS: Watch returned "
                                + "authenticated empty INFORMATIONAL "
                                + "response for initiator MID=3; "
                                + "waiting for responder-originated "
                                + "0xC546 request at responder MID=0.");
                    }
                    continue;
                }
                if (highestWatchAck
                        != expectedRequestSequence) {
                    throw new HostException(
                            "Watch PIN_METHOD response did not "
                                    + "acknowledge all request "
                                    + "I-frames; highest="
                                    + highestWatchAck
                                    + " expected="
                                    + expectedRequestSequence);
                }
                int nextOutboundTxSequence =
                        expectedRequestSequence;
                if (pinResponse.peerRequestMessageId >= 0) {
                    List<byte[]> acknowledgementPackets;
                    try {
                        acknowledgementPackets =
                                IkeV2SessionCrypto
                                        .createWatchInformationalAcknowledgement(
                                                new SecureRandom(),
                                                updatedKeys,
                                                pinResponse
                                                        .peerRequestMessageId);
                    } catch (IllegalArgumentException error) {
                        throw new HostException(
                                "Cannot create Watch INFORMATIONAL "
                                        + "acknowledgement: "
                                        + error.getMessage(),
                                error);
                    }
                    int acknowledgementTxSequence =
                            expectedRequestSequence;
                    for (int index = 0;
                            index < acknowledgementPackets.size();
                            index++) {
                        byte[] acknowledgement =
                                acknowledgementPackets.get(index);
                        byte[] acknowledgementUike =
                                IkeV2Codec.encodeUikeFrame(
                                        acknowledgement);
                        byte[] acknowledgementErtm =
                                L2capErtmCodec
                                        .encodeInformationFrame(
                                                responderLocalCid,
                                                acknowledgementTxSequence,
                                                expectedWatchTxSequence,
                                                acknowledgementUike,
                                                TERMINUS_FCS_ENABLED);
                        log(String.format(
                                Locale.US,
                                "PIN_METHOD WATCH-REQUEST ACK TX "
                                        + "packet=%d/%d IKE=%d "
                                        + "MID=%d flags=0x28 "
                                        + "innerPayload=NONE "
                                        + "TxSeq=%d ReqSeq=%d "
                                        + "rawEncryptedIKE=%s",
                                index + 1,
                                acknowledgementPackets.size(),
                                acknowledgement.length,
                                pinResponse.peerRequestMessageId,
                                acknowledgementTxSequence,
                                expectedWatchTxSequence,
                                HciCodec.toHex(acknowledgement)));
                        session.sendL2cap(
                                responderLocalCid,
                                acknowledgementErtm,
                                "TERMINUS ERTM PIN_METHOD "
                                        + "WATCH-REQUEST ACK "
                                        + (index + 1)
                                        + "/"
                                        + acknowledgementPackets.size());
                        acknowledgementTxSequence =
                                (acknowledgementTxSequence + 1)
                                        % L2capErtmCodec
                                        .SEQUENCE_MODULUS;
                    }
                    nextOutboundTxSequence =
                            acknowledgementTxSequence;
                }
                int saltLength = pinResponse.pinSalt.length;
                log("PIN_METHOD PASS: response notify 0xC546 "
                        + "authenticated; method=PIN/2 payloads="
                        + formatHexList(
                                pinResponse.payloadTypes)
                        + " notifies="
                        + formatHexList(
                                pinResponse.notifyTypes)
                        + " saltLength="
                        + saltLength
                        + " bytes; salt/PIN/key bytes logged=false; "
                        + "salt retained=volatile-until-KDF; Watch request "
                        + "empty-IKE-ACK sent="
                        + (pinResponse.peerRequestMessageId >= 0)
                        + ".");
                ModernPairingIkeRunner.PinTransportState state =
                        new ModernPairingIkeRunner.PinTransportState(
                                pinResponse.pinSalt,
                                nextOutboundTxSequence,
                                expectedWatchTxSequence);
                Arrays.fill(
                        pinResponse.pinSalt,
                        (byte) 0);
                return state;
            }
        }
        throw new HostException(
                "PIN_METHOD response timed out after "
                        + PIN_AUTH_METHOD_TIMEOUT_MS
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


    char[] awaitWatchPin(int responderLocalCid, ModernPairingIkeRunner.PinTransportState pinState) throws Exception {
        log("PIN_REQUIRED: enter the six-digit code shown by the "
                + "Watch in the app. The value will remain only in "
                + "volatile memory and will not be logged.");
        long deadline =
                BridgeClock.elapsedRealtime()
                        + PIN_INPUT_TIMEOUT_MS;
        PinWaitErtmResponder responder = new PinWaitErtmResponder(
                responderLocalCid, pinState.nextExpectedWatchTxSequence, TERMINUS_FCS_ENABLED);
        List<HalTransportSession.Packet> deferred = new ArrayList<>();
        List<HalTransportSession.Packet> currentPdu = new ArrayList<>();
        boolean pinAccepted = false;
        try {
            while (BridgeClock.elapsedRealtime() < deadline) {
                session.checkStop();
                // Finish a fragmented ACL PDU and observe queued disconnects
                // before starting the expensive PIN KDF.
                if (currentPdu.isEmpty() && session.packets.isEmpty()) {
                    char[] pin = session.pinInputs.poll();
                    if (pin != null) {
                        pinAccepted = true;
                        return pin;
                    }
                }
                byte[] heartbeat = responder.heartbeat(BridgeClock.elapsedRealtime());
                if (heartbeat != null) {
                    session.sendL2cap(responderLocalCid, heartbeat, "PIN WAIT NORMAL ERTM KEEPALIVE");
                }
                HalTransportSession.Packet packet;
                try {
                    packet = session.nextPacket(Math.min(deadline, BridgeClock.elapsedRealtime() + 250));
                } catch (TimeoutException idle) {
                    continue;
                }
                if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                    HciCodec.DisconnectionComplete disconnected =
                            HciCodec.parseDisconnectionComplete(packet.bytes);
                    if (disconnected != null && disconnected.connectionHandle == session.connectionHandle) {
                        session.connectionHandle = -1;
                        throw new HostException("Watch disconnected while waiting for PIN; reason=0x"
                                + Integer.toHexString(disconnected.reason));
                    }
                    continue;
                }
                currentPdu.add(packet);
                if (currentPdu.size() + deferred.size() > 256) {
                    throw new HostException("Too many pending ACL packets while waiting for PIN");
                }
                HciCodec.L2capPdu l2cap = session.aclReassembler.accept(packet.bytes);
                if (l2cap == null) continue;
                boolean consumed = l2cap.connectionHandle != session.connectionHandle;
                if (!consumed && l2cap.destinationCid == HciCodec.TERMINUS_LOCAL_CID) {
                    L2capErtmCodec.Frame frame = L2capErtmCodec.decode(
                            HciCodec.TERMINUS_LOCAL_CID, l2cap.payload, TERMINUS_FCS_ENABLED);
                    consumed = responder.consumes(frame);
                    byte[] reply = responder.response(frame);
                    if (reply != null) {
                        session.sendL2cap(responderLocalCid, reply, "PIN WAIT NORMAL ERTM RESPONSE");
                    }
                } else if (!consumed && (l2cap.destinationCid == BluetoothLinkMaintenance.ATT_CID
                        || l2cap.destinationCid == BluetoothLinkMaintenance.LE_SIGNALING_CID)) {
                    session.normalLink.serviceNormalFixedChannel(l2cap);
                    consumed = true;
                }
                if (consumed) currentPdu.forEach(p -> HalTransportSession.wipe(p.bytes));
                else deferred.addAll(currentPdu);
                currentPdu.clear();
            }
        } finally {
            // New IKE/SMP data retains its original ACL framing and order;
            // the normal pairing parser processes it after PIN input.
            if (pinAccepted) session.deferredControllerPackets.addAll(deferred);
            else deferred.forEach(p -> HalTransportSession.wipe(p.bytes));
            currentPdu.forEach(p -> HalTransportSession.wipe(p.bytes));
        }
        throw new HostException(
                "Timed out waiting for the six-digit Watch PIN");
    }
}
