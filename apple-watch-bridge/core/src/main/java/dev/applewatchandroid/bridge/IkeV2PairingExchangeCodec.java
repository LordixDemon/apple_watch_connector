package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;


/** Pairing-channel SPAKE2+ GSPM and private-notify IKEv2 codecs. */
final class IkeV2PairingExchangeCodec {
    private IkeV2PairingExchangeCodec() {}

    /**
     * Creates the Apple secure-password initiator after the pairing
     * IKE_INTERMEDIATE exchange and mandatory PPK key transform.
     */
    static IkeV2SessionCrypto.PairingGspmInitiator createPairingGspmInitiator(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            byte[] sharedSecret) {
        IkeV2SaInitCodec.validateIkeAuthState(
                random,
                initiator,
                response,
                ppkKeys,
                intAuthI,
                intAuthR);
        if (sharedSecret == null
                || sharedSecret.length
                < AppleSpake2PlusProver.MINIMUM_SEED_LENGTH) {
            throw new IllegalArgumentException(
                    "Pairing SPAKE2+ shared secret is required");
        }

        byte[] spakeSalt = IkeV2SessionCrypto.concatenate(
                initiator.nonce,
                response.responderNonce);
        byte[] spakeContext = IkeV2SessionCrypto.concatenate(
                initiator.initiatorSpi,
                response.responderSpi);
        byte[] initiatorIdBody =
                AppleWatchPairingCrypto
                        .physicalInitiatorIdPayloadBody();
        byte[] responderIdBody =
                AppleWatchPairingCrypto.saltedPinIdPayloadBody();
        try {
            AppleSpake2PlusProver prover =
                    AppleSpake2PlusProver.create(
                            random,
                            sharedSecret,
                            spakeSalt,
                            spakeContext,
                            initiatorIdBody,
                            responderIdBody);
            return new IkeV2SessionCrypto.PairingGspmInitiator(
                    initiator,
                    response,
                    ppkKeys,
                    intAuthI,
                    intAuthR,
                    prover,
                    initiatorIdBody,
                    responderIdBody);
        } finally {
            IkeV2SessionCrypto.wipe(spakeSalt);
            IkeV2SessionCrypto.wipe(spakeContext);
            IkeV2SessionCrypto.wipe(initiatorIdBody);
            IkeV2SessionCrypto.wipe(responderIdBody);
        }
    }

    /**
     * Injects a published-vector Prover without changing the live factory.
     */
    static IkeV2SessionCrypto.PairingGspmInitiator
            createPairingGspmInitiatorForTest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            AppleSpake2PlusProver prover) {
        IkeV2SaInitCodec.validateIkeAuthState(
                random,
                initiator,
                response,
                ppkKeys,
                intAuthI,
                intAuthR);
        if (prover == null) {
            throw new IllegalArgumentException(
                    "Test SPAKE2+ Prover is required");
        }
        byte[] initiatorIdBody =
                AppleWatchPairingCrypto
                        .physicalInitiatorIdPayloadBody();
        byte[] responderIdBody =
                AppleWatchPairingCrypto.saltedPinIdPayloadBody();
        try {
            return new IkeV2SessionCrypto.PairingGspmInitiator(
                    initiator,
                    response,
                    ppkKeys,
                    intAuthI,
                    intAuthR,
                    prover,
                    initiatorIdBody,
                    responderIdBody);
        } finally {
            IkeV2SessionCrypto.wipe(initiatorIdBody);
            IkeV2SessionCrypto.wipe(responderIdBody);
        }
    }

    static List<byte[]> createPairingFinalAuthResponseForTest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            byte[] firstInitiatorGspmMessage,
            byte[] firstResponderGspmMessage,
            byte[] gspmSessionKey) {
        IkeV2SaInitCodec.validateIkeAuthState(
                random,
                initiator,
                response,
                ppkKeys,
                intAuthI,
                intAuthR);
        byte[] idBody =
                AppleWatchPairingCrypto.saltedPinIdPayloadBody();
        byte[] authData = createPairingAuthenticationData(
                true,
                initiator,
                response,
                ppkKeys,
                intAuthI,
                intAuthR,
                idBody,
                firstInitiatorGspmMessage,
                firstResponderGspmMessage,
                gspmSessionKey);
        try {
            byte[] plaintext = IkeV2SessionCrypto.concatenate(
                    IkeV2SaInitCodec.genericPayload(
                            IkeV2SessionCrypto.PAYLOAD_AUTH,
                            IkeV2SaInitCodec.buildNotifyBody(
                                    0,
                                    IkeV2SessionCrypto.NOTIFY_INITIAL_CONTACT,
                                    new byte[0])),
                    IkeV2SaInitCodec.genericPayload(
                            IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                            IkeV2SaInitCodec.buildAuthenticationBody(
                                    IkeV2SessionCrypto.AUTH_METHOD_GENERIC_SECURE_PASSWORD,
                                    authData)),
                    IkeV2SaInitCodec.genericPayload(
                            IkeV2Codec.PAYLOAD_NONE,
                            IkeV2SaInitCodec.buildNotifyBody(
                                    0,
                                    IkeV2SessionCrypto.NOTIFY_PPK_IDENTITY,
                                    new byte[0])));
            return IkeV2SessionCrypto.encryptProtectedPayload(
                    random,
                    ppkKeys.initiatorSpi,
                    ppkKeys.responderSpi,
                    ppkKeys.skEr,
                    true,
                    IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                    IkeV2SessionCrypto.PAIRING_FINAL_AUTH_MESSAGE_ID,
                    plaintext,
                    IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                    IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
        } finally {
            IkeV2SessionCrypto.wipe(idBody);
            IkeV2SessionCrypto.wipe(authData);
        }
    }

    static byte[] buildInitialPairingGspmPlaintext(
            byte[] initiatorIdBody,
            byte[] responderIdBody,
            byte[] proverShare) {
        /*
         * NetworkExtension checks PPK_IDENTITY before dispatching the first
         * GSPM message. NO_PPK_AUTH is intentionally absent: Apple skips it
         * when ppkMandatory is true.
         */
        return IkeV2SessionCrypto.concatenate(
                IkeV2SaInitCodec.genericPayload(
                        IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                        initiatorIdBody),
                IkeV2SaInitCodec.genericPayload(
                        IkeV2SessionCrypto.PAYLOAD_ID_RESPONDER,
                        IkeV2SaInitCodec.buildNotifyBody(
                                0,
                                IkeV2SessionCrypto.NOTIFY_INITIAL_CONTACT,
                                new byte[0])),
                IkeV2SaInitCodec.genericPayload(
                        IkeV2SessionCrypto.PAYLOAD_GSPM,
                        responderIdBody),
                IkeV2SaInitCodec.genericPayload(
                        IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                        proverShare),
                IkeV2SaInitCodec.genericPayload(
                        IkeV2Codec.PAYLOAD_NONE,
                        IkeV2SaInitCodec.buildNotifyBody(
                                0,
                                IkeV2SessionCrypto.NOTIFY_PPK_IDENTITY,
                                AppleWatchPairingCrypto
                                        .fixedPpkIdentityData())));
    }

    static byte[] buildSingleGspmPlaintext(
            byte[] gspmData) {
        if (gspmData == null || gspmData.length == 0) {
            throw new IllegalArgumentException(
                    "GSPM message is required");
        }
        return IkeV2SaInitCodec.genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                gspmData);
    }

    static byte[] createPairingAuthenticationData(
            boolean responder,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            byte[] idBody,
            byte[] firstInitiatorGspmMessage,
            byte[] firstResponderGspmMessage,
            byte[] gspmSessionKey) {
        IkeV2SessionCrypto.requireLength(
                "initiator IntAuth",
                intAuthI,
                IkeV2SessionCrypto.PRF_SHA512_LENGTH);
        IkeV2SessionCrypto.requireLength(
                "responder IntAuth",
                intAuthR,
                IkeV2SessionCrypto.PRF_SHA512_LENGTH);
        IkeV2SessionCrypto.requireLength(
                "GSPM session key",
                gspmSessionKey,
                AppleSpake2PlusProver.SHARED_KEY_LENGTH);
        IkeV2SessionCrypto.requireLength(
                "initiator SPAKE2+ share",
                firstInitiatorGspmMessage,
                AppleSpake2PlusProver.SHARE_LENGTH);
        IkeV2SessionCrypto.requireLength(
                "responder SPAKE2+ share",
                firstResponderGspmMessage,
                AppleSpake2PlusProver.SHARE_LENGTH);
        byte[] primeKey =
                responder ? ppkKeys.skPr : ppkKeys.skPi;
        byte[] signedOctets = IkeV2SessionCrypto.concatenate(
                responder ? response.packet : initiator.ikePacket,
                responder
                        ? initiator.nonce
                        : response.responderNonce,
                IkeV2SessionCrypto.prf(primeKey, idBody),
                IkeV2SaInitCodec.buildIkeAuthIntAuth(
                        intAuthI,
                        intAuthR,
                        /*
                         * NetworkExtension signs firstAuthMessageID, not the
                         * message ID of the later packet carrying AUTH.
                         */
                        IkeV2SessionCrypto.PAIRING_FIRST_GSPM_MESSAGE_ID));
        byte[] signedGspmMessages = responder
                ? IkeV2SessionCrypto.concatenate(
                        firstResponderGspmMessage,
                        firstInitiatorGspmMessage)
                : IkeV2SessionCrypto.concatenate(
                        firstInitiatorGspmMessage,
                        firstResponderGspmMessage);
        // NetworkExtension authenticates the ordered GSPM shares with the
        // active post-PPK SK_p{I,R} and uses the SPAKE2+ session key for the
        // outer AUTH MAC.
        byte[] gspmAuthentication = IkeV2SessionCrypto.prf(
                primeKey,
                signedGspmMessages);
        try {
            return IkeV2SessionCrypto.prf(
                    gspmSessionKey,
                    IkeV2SessionCrypto.concatenate(
                            signedOctets,
                            gspmAuthentication));
        } finally {
            IkeV2SessionCrypto.wipe(signedOctets);
            IkeV2SessionCrypto.wipe(signedGspmMessages);
            IkeV2SessionCrypto.wipe(gspmAuthentication);
        }
    }

    static byte[] parsePairingGspmResponse(
            IkeV2SessionCrypto.DecryptedIntermediatePart part,
            boolean requireResponderId,
            int expectedGspmLength) {
        if (part.fragmented
                || part.plaintext == null
                || part.plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "Pairing GSPM response must be unfragmented");
        }
        int expectedFirstPayload = requireResponderId
                ? IkeV2SessionCrypto.PAYLOAD_ID_RESPONDER
                : IkeV2SessionCrypto.PAYLOAD_GSPM;
        if (part.firstInnerPayload != expectedFirstPayload) {
            throw new IllegalArgumentException(
                    describePairingProtectedRejection(
                            part,
                            "GSPM"));
        }

        byte[] expectedIdBody =
                AppleWatchPairingCrypto.saltedPinIdPayloadBody();
        byte[] expectedPpkIdentityBody =
                IkeV2SaInitCodec.buildNotifyBody(
                        0,
                        IkeV2SessionCrypto.NOTIFY_PPK_IDENTITY,
                        AppleWatchPairingCrypto
                                .fixedPpkIdentityData());
        byte[] gspmData = null;
        boolean ppkIdentitySeen = false;
        int payloadType = part.firstInnerPayload;
        int offset = 0;
        int payloadIndex = 0;
        try {
            while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
                if (offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                        > part.plaintext.length) {
                    throw new IllegalArgumentException(
                            "Pairing GSPM payload header is truncated");
                }
                int nextPayload =
                        IkeV2SessionCrypto.unsigned(part.plaintext[offset]);
                int payloadFlags =
                        IkeV2SessionCrypto.unsigned(part.plaintext[offset + 1]);
                int payloadLength =
                        IkeV2SessionCrypto.be16(part.plaintext, offset + 2);
                if (payloadLength < IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                        || offset + payloadLength
                        > part.plaintext.length
                        || payloadFlags != 0) {
                    throw new IllegalArgumentException(
                            "Pairing GSPM payload is malformed");
                }
                int bodyOffset =
                        offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
                int bodyLength =
                        payloadLength
                                - IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
                if (requireResponderId && payloadIndex == 0) {
                    if (payloadType != IkeV2SessionCrypto.PAYLOAD_ID_RESPONDER
                            || !Arrays.equals(
                            expectedIdBody,
                            Arrays.copyOfRange(
                                    part.plaintext,
                                    bodyOffset,
                                    offset + payloadLength))) {
                        throw new IllegalArgumentException(
                                "Unexpected salted-PIN responder ID");
                    }
                } else if (payloadIndex
                        == (requireResponderId ? 1 : 0)
                        && gspmData == null
                        && payloadType == IkeV2SessionCrypto.PAYLOAD_GSPM
                        && bodyLength == expectedGspmLength) {
                    gspmData = Arrays.copyOfRange(
                            part.plaintext,
                            bodyOffset,
                            offset + payloadLength);
                } else if (requireResponderId
                        && payloadIndex == 2
                        && payloadType == IkeV2SessionCrypto.PAYLOAD_NOTIFY
                        && Arrays.equals(
                        expectedPpkIdentityBody,
                        Arrays.copyOfRange(
                                part.plaintext,
                                bodyOffset,
                                offset + payloadLength))) {
                    ppkIdentitySeen = true;
                } else {
                    throw new IllegalArgumentException(
                            "Unexpected pairing GSPM payload chain");
                }
                offset += payloadLength;
                payloadType = nextPayload;
                payloadIndex++;
            }
            int minimumPayloadCount =
                    requireResponderId ? 2 : 1;
            int maximumPayloadCount =
                    requireResponderId ? 3 : 1;
            if (offset != part.plaintext.length
                    || payloadIndex < minimumPayloadCount
                    || payloadIndex > maximumPayloadCount
                    || gspmData == null
                    || (!requireResponderId
                    && ppkIdentitySeen)) {
                throw new IllegalArgumentException(
                        "Incomplete pairing GSPM payload chain");
            }
            return gspmData;
        } finally {
            IkeV2SessionCrypto.wipe(expectedIdBody);
            IkeV2SessionCrypto.wipe(expectedPpkIdentityBody);
        }
    }

    /**
     * Describes a short responder error without ever copying or formatting
     * notify data. Apple sends these protected Notify-only responses when it
     * rejects a GSPM state transition, and the numeric type is sufficient to
     * distinguish protocol syntax, PPK, and authentication failures.
     */
    static String describePairingProtectedRejection(
            IkeV2SessionCrypto.DecryptedIntermediatePart part,
            String stage) {
        if (part.firstInnerPayload != IkeV2SessionCrypto.PAYLOAD_NOTIFY) {
            return describePairingProtectedPayloadStructure(
                    part,
                    stage);
        }
        byte[] plaintext = part.plaintext;
        if (plaintext == null
                || plaintext.length
                < IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH + 4) {
            return describePairingProtectedPayloadStructure(
                    part,
                    stage);
        }
        int nextPayload = IkeV2SessionCrypto.unsigned(plaintext[0]);
        int payloadFlags = IkeV2SessionCrypto.unsigned(plaintext[1]);
        int payloadLength = IkeV2SessionCrypto.be16(plaintext, 2);
        int protocolId = IkeV2SessionCrypto.unsigned(plaintext[4]);
        int spiSize = IkeV2SessionCrypto.unsigned(plaintext[5]);
        if (nextPayload != IkeV2Codec.PAYLOAD_NONE
                || payloadFlags != 0
                || payloadLength != plaintext.length
                || payloadLength
                < IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH + 4 + spiSize) {
            return describePairingProtectedPayloadStructure(
                    part,
                    stage);
        }
        int notifyType = IkeV2SessionCrypto.be16(plaintext, 6);
        int dataLength = payloadLength
                - IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                - 4
                - spiSize;
        return String.format(
                Locale.ROOT,
                "Pairing %s rejected by Watch: "
                        + "Notify=%d (0x%04X), protocolId=%d, "
                        + "spiSize=%d, dataLength=%d",
                stage,
                notifyType,
                notifyType,
                protocolId,
                spiSize,
                dataLength);
    }

    /**
     * Formats only structural metadata from an unexpected protected response.
     * Authentication bytes, identities, Notify data, and every other payload
     * body remain deliberately unavailable to logs.
     */
    static String describePairingProtectedPayloadStructure(
            IkeV2SessionCrypto.DecryptedIntermediatePart part,
            String stage) {
        byte[] plaintext = part == null ? null : part.plaintext;
        int firstPayload = part == null
                ? -1
                : part.firstInnerPayload;
        int totalLength = plaintext == null
                ? -1
                : plaintext.length;
        String prefix = String.format(
                Locale.ROOT,
                "Unexpected pairing %s protected payload chain: "
                        + "first=%d, totalLength=%d, payloads=",
                stage,
                firstPayload,
                totalLength);
        if (plaintext == null) {
            return prefix + "[unavailable]";
        }

        List<String> payloads = new ArrayList<>();
        int payloadType = firstPayload;
        int offset = 0;
        while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
            if (offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                    > plaintext.length) {
                payloads.add(String.format(
                        Locale.ROOT,
                        "type=%d(truncatedHeader=%d)",
                        payloadType,
                        plaintext.length - offset));
                return prefix + payloads;
            }
            int nextPayload = IkeV2SessionCrypto.unsigned(plaintext[offset]);
            int flags = IkeV2SessionCrypto.unsigned(plaintext[offset + 1]);
            int payloadLength = IkeV2SessionCrypto.be16(plaintext, offset + 2);
            if (payloadLength < IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                    || offset + payloadLength > plaintext.length) {
                payloads.add(String.format(
                        Locale.ROOT,
                        "type=%d(invalidLength=%d,remaining=%d,"
                                + "flags=%d,next=%d)",
                        payloadType,
                        payloadLength,
                        plaintext.length - offset,
                        flags,
                        nextPayload));
                return prefix + payloads;
            }

            int bodyOffset =
                    offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
            int bodyLength =
                    payloadLength - IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
            if (payloadType == IkeV2SessionCrypto.PAYLOAD_NOTIFY) {
                if (bodyLength < 4) {
                    payloads.add(String.format(
                            Locale.ROOT,
                            "Notify(length=%d,bodyLength=%d,"
                                    + "flags=%d,next=%d)",
                            payloadLength,
                            bodyLength,
                            flags,
                            nextPayload));
                } else {
                    int protocolId = IkeV2SessionCrypto.unsigned(plaintext[bodyOffset]);
                    int spiSize = IkeV2SessionCrypto.unsigned(plaintext[bodyOffset + 1]);
                    int notifyType = IkeV2SessionCrypto.be16(
                            plaintext,
                            bodyOffset + 2);
                    int dataLength = bodyLength - 4 - spiSize;
                    payloads.add(String.format(
                            Locale.ROOT,
                            "Notify(type=%d,protocolId=%d,"
                                    + "spiSize=%d,dataLength=%d,"
                                    + "length=%d,flags=%d,next=%d)",
                            notifyType,
                            protocolId,
                            spiSize,
                            dataLength,
                            payloadLength,
                            flags,
                            nextPayload));
                }
            } else if (payloadType == IkeV2SessionCrypto.PAYLOAD_AUTH) {
                int authMethod = bodyLength < 1
                        ? -1
                        : IkeV2SessionCrypto.unsigned(plaintext[bodyOffset]);
                int authDataLength = Math.max(0, bodyLength - 4);
                payloads.add(String.format(
                        Locale.ROOT,
                        "AUTH(method=%d,dataLength=%d,length=%d,"
                                + "flags=%d,next=%d)",
                        authMethod,
                        authDataLength,
                        payloadLength,
                        flags,
                        nextPayload));
            } else {
                payloads.add(String.format(
                        Locale.ROOT,
                        "type=%d(bodyLength=%d,length=%d,"
                                + "flags=%d,next=%d)",
                        payloadType,
                        bodyLength,
                        payloadLength,
                        flags,
                        nextPayload));
            }
            offset += payloadLength;
            payloadType = nextPayload;
        }
        if (offset != plaintext.length) {
            payloads.add(String.format(
                    Locale.ROOT,
                    "trailingBytes=%d",
                    plaintext.length - offset));
        }
        return prefix + payloads;
    }

    static IkeV2SessionCrypto.ParsedPairingFinalAuth
            parsePairingFinalAuthResponse(
            IkeV2SessionCrypto.DecryptedIntermediatePart part) {
        if (part.fragmented
                || part.plaintext == null
                || part.plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "Pairing AUTH response must be unfragmented");
        }
        if (part.firstInnerPayload != IkeV2SessionCrypto.PAYLOAD_NOTIFY) {
            throw new IllegalArgumentException(
                    describePairingProtectedRejection(
                            part,
                            "AUTH"));
        }
        byte[] authenticationData = null;
        boolean initialContactSeen = false;
        boolean ppkIdentitySeen = false;
        List<Integer> payloadTypes = new ArrayList<>();
        List<Integer> notifyTypes = new ArrayList<>();
        int payloadType = part.firstInnerPayload;
        int offset = 0;
        while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
            if (offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                    > part.plaintext.length) {
                throw new IllegalArgumentException(
                        "Pairing AUTH payload header is truncated");
            }
            int nextPayload =
                    IkeV2SessionCrypto.unsigned(part.plaintext[offset]);
            int payloadFlags =
                    IkeV2SessionCrypto.unsigned(part.plaintext[offset + 1]);
            int payloadLength =
                    IkeV2SessionCrypto.be16(part.plaintext, offset + 2);
            if (payloadLength < IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                    || offset + payloadLength
                    > part.plaintext.length
                    || payloadFlags != 0) {
                throw new IllegalArgumentException(
                        "Pairing AUTH payload is malformed");
            }
            payloadTypes.add(payloadType);
            int bodyOffset =
                    offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
            int bodyLength =
                    payloadLength - IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
            if (payloadType == IkeV2SessionCrypto.PAYLOAD_NOTIFY
                    && !initialContactSeen
                    && payloadTypes.size() == 1
                    && bodyLength == 4
                    && part.plaintext[bodyOffset] == 0
                    && part.plaintext[bodyOffset + 1] == 0
                    && IkeV2SessionCrypto.be16(part.plaintext, bodyOffset + 2)
                    == IkeV2SessionCrypto.NOTIFY_INITIAL_CONTACT) {
                initialContactSeen = true;
                notifyTypes.add(IkeV2SessionCrypto.NOTIFY_INITIAL_CONTACT);
            } else if (payloadType == IkeV2SessionCrypto.PAYLOAD_AUTH
                    && authenticationData == null
                    && bodyLength == 4 + IkeV2SessionCrypto.PRF_SHA512_LENGTH
                    && IkeV2SessionCrypto.unsigned(part.plaintext[bodyOffset])
                    == IkeV2SessionCrypto.AUTH_METHOD_GENERIC_SECURE_PASSWORD
                    && part.plaintext[bodyOffset + 1] == 0
                    && part.plaintext[bodyOffset + 2] == 0
                    && part.plaintext[bodyOffset + 3] == 0) {
                authenticationData = Arrays.copyOfRange(
                        part.plaintext,
                        bodyOffset + 4,
                        offset + payloadLength);
            } else if (payloadType == IkeV2SessionCrypto.PAYLOAD_NOTIFY
                    && !ppkIdentitySeen
                    && bodyLength == 4
                    && part.plaintext[bodyOffset] == 0
                    && part.plaintext[bodyOffset + 1] == 0
                    && IkeV2SessionCrypto.be16(part.plaintext, bodyOffset + 2)
                    == IkeV2SessionCrypto.NOTIFY_PPK_IDENTITY) {
                ppkIdentitySeen = true;
                notifyTypes.add(IkeV2SessionCrypto.NOTIFY_PPK_IDENTITY);
            } else {
                throw new IllegalArgumentException(
                        "Unexpected pairing AUTH payload chain");
            }
            offset += payloadLength;
            payloadType = nextPayload;
        }
        if (offset != part.plaintext.length
                || !payloadTypes.equals(
                List.of(
                        IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                        IkeV2SessionCrypto.PAYLOAD_AUTH,
                        IkeV2SessionCrypto.PAYLOAD_NOTIFY))
                || authenticationData == null
                || !initialContactSeen
                || !ppkIdentitySeen) {
            IkeV2SessionCrypto.wipe(authenticationData);
            throw new IllegalArgumentException(
                    "Pairing AUTH lacks secure-password AUTH "
                            + "or mandatory PPK identity");
        }
        return new IkeV2SessionCrypto.ParsedPairingFinalAuth(
                authenticationData,
                payloadTypes,
                notifyTypes);
    }

    static IkeV2SessionCrypto.PairingPrivateNotifyRequest
            createPairingPrivateNotifyRequest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        return createPairingPrivateNotifyRequest(random, updatedKeys, notifies,
                IkeV2SessionCrypto.PAIRING_NOTIFIES_MESSAGE_ID);
    }

    static IkeV2SessionCrypto.PairingPrivateNotifyRequest createPairingPrivateNotifyRequest(
            SecureRandom random, IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify> notifies,
            int messageId) {
        if (random == null
                || updatedKeys == null
                || notifies == null
                || notifies.isEmpty()
                || (messageId != IkeV2SessionCrypto.PAIRING_NOTIFIES_MESSAGE_ID
                && messageId != OpticalPskSession.NEXT_INFORMATIONAL_MESSAGE_ID)) {
            throw new IllegalArgumentException(
                    "Pairing private-notify request state is required");
        }
        byte[] plaintext =
                buildPrivateNotifyPlaintext(notifies);
        try {
            List<byte[]> packets =
                    IkeV2SessionCrypto.encryptProtectedPayload(
                            random,
                            updatedKeys.initiatorSpi,
                            updatedKeys.responderSpi,
                            updatedKeys.skEi,
                            false,
                            IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                            messageId,
                            plaintext,
                            IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                            IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
            List<Integer> types =
                    new ArrayList<>(notifies.size());
            List<Integer> lengths =
                    new ArrayList<>(notifies.size());
            for (ApplePairingNotifyPayloads.PrivateNotify notify
                    : notifies) {
                types.add(notify.type());
                byte[] data = notify.data();
                try {
                    lengths.add(data.length);
                } finally {
                    IkeV2SessionCrypto.wipe(data);
                }
            }
            return new IkeV2SessionCrypto.PairingPrivateNotifyRequest(
                    packets,
                    types,
                    lengths);
        } finally {
            IkeV2SessionCrypto.wipe(plaintext);
        }
    }

    static List<byte[]>
            createPairingPrivateNotifyEmptyResponseForTest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys) {
        if (random == null || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "Pairing private-notify acknowledgement "
                            + "state is required");
        }
        return List.of(IkeV2ControlAuthCodec.encryptEmptyProtectedInformational(
                random,
                updatedKeys,
                updatedKeys.skEr,
                IkeV2SessionCrypto.IKE_FLAG_RESPONSE,
                IkeV2SessionCrypto.PAIRING_NOTIFIES_MESSAGE_ID));
    }

    static List<byte[]>
            createPairingPrivateNotifyWatchRequestForTest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        return createPairingPrivateNotifyWatchRequestForTest(
                random,
                updatedKeys,
                notifies,
                IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
    }

    static List<byte[]>
            createPairingPrivateNotifyWatchRequestForTest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies,
            int maxIkePacketSize) {
        if (random == null
                || updatedKeys == null
                || notifies == null
                || notifies.isEmpty()) {
            throw new IllegalArgumentException(
                    "Watch private-notify request state is required");
        }
        byte[] plaintext =
                buildPrivateNotifyPlaintext(notifies);
        try {
            return IkeV2SessionCrypto.encryptProtectedPayloadWithDirectionFlags(
                    random,
                    updatedKeys.initiatorSpi,
                    updatedKeys.responderSpi,
                    updatedKeys.skEr,
                    0,
                    IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                    IkeV2SessionCrypto.PAIRING_NOTIFIES_WATCH_MESSAGE_ID,
                    plaintext,
                    IkeV2SessionCrypto.PAYLOAD_NOTIFY,
                    maxIkePacketSize);
        } finally {
            IkeV2SessionCrypto.wipe(plaintext);
        }
    }

    static byte[] buildPrivateNotifyPlaintext(
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        ByteArrayOutputStream plaintext =
                new ByteArrayOutputStream();
        for (int index = 0;
                index < notifies.size();
                index++) {
            ApplePairingNotifyPayloads.PrivateNotify notify =
                    notifies.get(index);
            if (notify == null) {
                throw new IllegalArgumentException(
                        "Private-notify list contains null");
            }
            byte[] data = notify.data();
            byte[] body = null;
            byte[] payload = null;
            try {
                body = IkeV2SaInitCodec.buildNotifyBody(
                        0,
                        notify.type(),
                        data);
                payload = IkeV2SaInitCodec.genericPayload(
                        index + 1 < notifies.size()
                                ? IkeV2SessionCrypto.PAYLOAD_NOTIFY
                                : IkeV2Codec.PAYLOAD_NONE,
                        body);
                plaintext.writeBytes(payload);
            } finally {
                IkeV2SessionCrypto.wipe(data);
                IkeV2SessionCrypto.wipe(body);
                IkeV2SessionCrypto.wipe(payload);
            }
        }
        return plaintext.toByteArray();
    }

    static IkeV2SessionCrypto.PairingPrivateNotifyResponse
            parsePrivateNotifyPlaintext(
            int firstInnerPayload,
            byte[] plaintext,
            int peerRequestMessageId) {
        return parsePrivateNotifyPlaintext(
                firstInnerPayload,
                plaintext,
                peerRequestMessageId,
                new byte[0][]);
    }

    static IkeV2SessionCrypto.PairingPrivateNotifyResponse
            parsePrivateNotifyPlaintext(
            int firstInnerPayload,
            byte[] plaintext,
            int peerRequestMessageId,
            byte[][] peerRequestWirePackets) {
        if (firstInnerPayload != IkeV2SessionCrypto.PAYLOAD_NOTIFY
                || plaintext == null
                || plaintext.length == 0
                || peerRequestMessageId < 0) {
            throw new IllegalArgumentException(
                    "Watch private-notify plaintext is invalid");
        }
        List<ApplePairingNotifyPayloads.PrivateNotify>
                notifies = new ArrayList<>();
        int payloadType = firstInnerPayload;
        int offset = 0;
        try {
            while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
                if (payloadType != IkeV2SessionCrypto.PAYLOAD_NOTIFY
                        || offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                        > plaintext.length) {
                    throw new IllegalArgumentException(
                            "Watch private-notify payload "
                                    + "chain is malformed");
                }
                int nextPayload =
                        IkeV2SessionCrypto.unsigned(plaintext[offset]);
                int payloadFlags =
                        IkeV2SessionCrypto.unsigned(plaintext[offset + 1]);
                int payloadLength =
                        IkeV2SessionCrypto.be16(plaintext, offset + 2);
                if (payloadFlags != 0
                        || payloadLength
                        < IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH + 4
                        || offset + payloadLength
                        > plaintext.length) {
                    throw new IllegalArgumentException(
                            "Watch private notify is truncated");
                }
                int bodyOffset =
                        offset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
                if (plaintext[bodyOffset] != 0
                        || plaintext[bodyOffset + 1] != 0) {
                    throw new IllegalArgumentException(
                            "Watch private notify has protocol/SPI data");
                }
                int notifyType =
                        IkeV2SessionCrypto.be16(plaintext, bodyOffset + 2);
                byte[] data = Arrays.copyOfRange(
                        plaintext,
                        bodyOffset + 4,
                        offset + payloadLength);
                try {
                    notifies.add(
                            new ApplePairingNotifyPayloads
                                    .PrivateNotify(
                                    notifyType,
                                    data));
                } finally {
                    IkeV2SessionCrypto.wipe(data);
                }
                offset += payloadLength;
                payloadType = nextPayload;
            }
            if (offset != plaintext.length) {
                throw new IllegalArgumentException(
                        "Watch private-notify plaintext has trailing data");
            }
            return new IkeV2SessionCrypto.PairingPrivateNotifyResponse(
                    notifies,
                    peerRequestMessageId,
                    peerRequestWirePackets);
        } finally {
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                notify.destroy();
            }
        }
    }
}
