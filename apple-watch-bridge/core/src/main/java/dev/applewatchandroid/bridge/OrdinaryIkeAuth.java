package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Exact first ordinary Class-D/Class-C IKE_AUTH exchange used by watchOS 26.6.
 *
 * <p>The NRLinkBluetooth prelude elects the IKE role for every pipe. This
 * codec is therefore endpoint-neutral: Android can create the initiator
 * request or authenticate one from the Watch, and can likewise create or
 * authenticate the responder reply. Long-lived identity and data-class
 * private keys stay inside {@link AppleNetworkRelayPairingMaterial}.</p>
 */
final class OrdinaryIkeAuth {
    static final String CLASS_D_SERVICE_KEY_ID =
            "com.apple.networkrelay.companionlink.classD";
    static final String CLASS_C_SERVICE_KEY_ID =
            "com.apple.networkrelay.companionlink.classC";

    enum DataClass {
        CLASS_D(CLASS_D_SERVICE_KEY_ID),
        CLASS_C(CLASS_C_SERVICE_KEY_ID);

        final String serviceKeyId;
        final byte[] serviceKeyIdBytes;

        DataClass(
                String serviceKeyId) {
            this.serviceKeyId = serviceKeyId;
            this.serviceKeyIdBytes =
                    serviceKeyId.getBytes(
                            StandardCharsets.UTF_8);
        }
    }

    static final int MESSAGE_ID = 2;
    static final int MAXIMUM_PACKET_SIZE = 1280;
    static final int REQUEST_INNER_LENGTH = 543;
    static final int REQUEST_PACKET_LENGTH = 600;

    static final int PAYLOAD_ID_INITIATOR = 35;
    static final int PAYLOAD_ID_RESPONDER = 36;
    static final int PAYLOAD_AUTH = 39;
    static final int PAYLOAD_TRAFFIC_SELECTOR_INITIATOR = 44;
    static final int PAYLOAD_TRAFFIC_SELECTOR_RESPONDER = 45;
    static final int PAYLOAD_CONFIGURATION = 47;

    static final int NOTIFY_INITIAL_CONTACT = 0x4000;
    static final int NOTIFY_USE_TRANSPORT_MODE = 0x4007;
    static final int NOTIFY_ESP_TFC_PADDING_NOT_SUPPORTED = 0x400a;
    static final int NOTIFY_NON_FIRST_FRAGMENTS_ALSO = 0x400b;
    static final int NOTIFY_SEQUENCE_PER_TRAFFIC_CLASS = 0xc350;

    static final int NOTIFY_TERMINUS_VERSION = 0xbdda;
    static final int NOTIFY_DEVICE_NAME = 0xbddb;
    static final int NOTIFY_BUILD_VERSION = 0xbddc;
    static final int NOTIFY_LOCAL_FLAGS = 0xbddd;
    static final int NOTIFY_DEVICE_TYPE = 0xbdde;
    static final int NOTIFY_IDS_DEVICE_ID = 0xbddf;
    static final int NOTIFY_KEYS_AFTER_FIRST_UNLOCK = 0xbe3d;
    static final int NOTIFY_LOCAL_PRELUDE = 0xbdd9;

    private static final int CURRENT_TERMINUS_VERSION = 24;
    private static final int NOTIFY_ALWAYS_ON_WIFI = 0xc8c9;
    private static final int CONFIG_ATTRIBUTE_INITIATOR_IPV6 = 0x6566;
    private static final int CONFIG_ATTRIBUTE_RESPONDER_IPV6 = 0x6567;
    private static final int CHILD_SPI_LENGTH = 4;
    private static final int INTERMEDIATE_AUTH_LENGTH = 64;

    private static final byte[] TRAFFIC_SELECTORS_BODY =
            new byte[]{
                    0x02, 0x00, 0x00, 0x00,
                    0x08, 0x00, 0x00, 0x28,
                    0x00, 0x00, (byte) 0xff, (byte) 0xff,
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff,
                    0x07, 0x00, 0x00, 0x10,
                    0x00, 0x00, (byte) 0xff, (byte) 0xff,
                    0x00, 0x00, 0x00, 0x00,
                    (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff
            };

    private OrdinaryIkeAuth() {
    }

    static InitiatorRequest createInitiatorRequest(
            SecureRandom random,
            AppleNetworkRelayPairingMaterial localMaterial,
            PeerMaterial peerMaterial,
            byte[] initiatorSaInitPacket,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] initiatorIntAuth,
            byte[] responderIntAuth) {
        return createInitiatorRequest(
                DataClass.CLASS_D,
                random,
                localMaterial,
                peerMaterial,
                initiatorSaInitPacket,
                keys,
                initiatorIntAuth,
                responderIntAuth);
    }

    static InitiatorRequest createInitiatorRequest(
            DataClass dataClass,
            SecureRandom random,
            AppleNetworkRelayPairingMaterial localMaterial,
            PeerMaterial peerMaterial,
            byte[] initiatorSaInitPacket,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] initiatorIntAuth,
            byte[] responderIntAuth) {
        requireCommonState(
                dataClass,
                random,
                localMaterial,
                peerMaterial,
                keys,
                initiatorIntAuth,
                responderIntAuth);
        validateInitiatorSaInit(
                initiatorSaInitPacket,
                keys);

        byte[] identityUuid = null;
        byte[] identifierPlaintext = null;
        byte[] encryptedIdentifier = null;
        byte[] idiBody = null;
        byte[] signedOctets = null;
        byte[] signature = null;
        byte[] authBody = null;
        byte[] childSpi = null;
        byte[] plaintext = null;
        try {
            // The encrypted Key ID names the recipient's local identity.
            // terminusd stores that UUID from the peer C4AF payload and the
            // recipient uses it to select its local pairing record after
            // HPKE open. It is not the sender's identity UUID.
            identityUuid = peerMaterial.identityUuid();
            identifierPlaintext =
                    AppleIkeEncryptedKeyId.createPlaintext(
                            identityUuid,
                            random);
            encryptedIdentifier =
                    AppleIkeEncryptedKeyId.seal(
                            peerMaterial.identityPublicX25519,
                            dataClass.serviceKeyId,
                            keys.initiatorNonce,
                            keys.responderNonce,
                            keys.initiatorSpi,
                            keys.responderSpi,
                            identifierPlaintext,
                            random);
            idiBody =
                    AppleIkeEncryptedKeyId.buildIdPayloadBody(
                            encryptedIdentifier);
            signedOctets =
                    AppleIkeEd25519Auth.buildSignedOctets(
                            initiatorSaInitPacket,
                            keys.responderNonce,
                            keys.skPi,
                            idiBody,
                            initiatorIntAuth,
                            responderIntAuth,
                            MESSAGE_ID);
            signature = sign(
                    localMaterial,
                    dataClass,
                    signedOctets);
            authBody =
                    AppleIkeEd25519Auth.buildAuthBody(
                            signature);
            childSpi = randomNonzeroChildSpi(random);
            plaintext =
                    buildInitiatorPlaintext(
                            idiBody,
                            authBody,
                            childSpi,
                            dataClass);
            if (plaintext.length != REQUEST_INNER_LENGTH) {
                throw new IllegalStateException(
                        "Ordinary IKE_AUTH request inner length is "
                                + plaintext.length
                                + ", expected "
                                + REQUEST_INNER_LENGTH);
            }
            List<byte[]> packets =
                    IkeV2SessionCrypto.encryptProtectedPayload(
                            random,
                            keys.initiatorSpi,
                            keys.responderSpi,
                            keys.skEi,
                            false,
                            IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                            MESSAGE_ID,
                            plaintext,
                            PAYLOAD_ID_INITIATOR,
                            MAXIMUM_PACKET_SIZE);
            if (packets.size() != 1
                    || packets.get(0).length
                    != REQUEST_PACKET_LENGTH) {
                throw new IllegalStateException(
                        "Ordinary IKE_AUTH request unexpectedly "
                                + "fragmented or changed length");
            }
            return new InitiatorRequest(
                    packets.get(0),
                    plaintext,
                    childSpi);
        } finally {
            wipe(identityUuid);
            wipe(identifierPlaintext);
            wipe(encryptedIdentifier);
            wipe(idiBody);
            wipe(signedOctets);
            wipe(signature);
            wipe(authBody);
            wipe(childSpi);
            wipe(plaintext);
        }
    }

    static ResponderResult authenticateAndRespond(
            SecureRandom random,
            AppleNetworkRelayPairingMaterial localMaterial,
            PeerMaterial peerMaterial,
            ResponderProfile profile,
            byte[] initiatorSaInitPacket,
            byte[] responderSaInitPacket,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] initiatorIntAuth,
            byte[] responderIntAuth,
            byte[] requestPacket) {
        return authenticateAndRespond(
                DataClass.CLASS_D,
                random,
                localMaterial,
                peerMaterial,
                profile,
                initiatorSaInitPacket,
                responderSaInitPacket,
                keys,
                initiatorIntAuth,
                responderIntAuth,
                requestPacket);
    }

    static ResponderResult authenticateAndRespond(
            DataClass dataClass,
            SecureRandom random,
            AppleNetworkRelayPairingMaterial localMaterial,
            PeerMaterial peerMaterial,
            ResponderProfile profile,
            byte[] initiatorSaInitPacket,
            byte[] responderSaInitPacket,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] initiatorIntAuth,
            byte[] responderIntAuth,
            byte[] requestPacket) {
        requireCommonState(
                dataClass,
                random,
                localMaterial,
                peerMaterial,
                keys,
                initiatorIntAuth,
                responderIntAuth);
        if (profile == null) {
            throw new IllegalArgumentException(
                    "Ordinary responder profile is required");
        }
        validateSaInitTranscript(
                initiatorSaInitPacket,
                responderSaInitPacket,
                keys);

        ParsedRequest request =
                parseAndAuthenticateRequest(
                        dataClass,
                        localMaterial,
                        peerMaterial,
                        initiatorSaInitPacket,
                        keys,
                        initiatorIntAuth,
                        responderIntAuth,
                        requestPacket);
        byte[] responderChildSpi = null;
        byte[] idrBody = null;
        byte[] signedOctets = null;
        byte[] signature = null;
        byte[] authBody = null;
        byte[] plaintext = null;
        try {
            responderChildSpi =
                    randomNonzeroChildSpi(random);
            idrBody = serviceIdentifierBody(
                    dataClass);
            signedOctets =
                    AppleIkeEd25519Auth.buildSignedOctets(
                            responderSaInitPacket,
                            keys.initiatorNonce,
                            keys.skPr,
                            idrBody,
                            initiatorIntAuth,
                            responderIntAuth,
                            MESSAGE_ID);
            signature = sign(
                    localMaterial,
                    dataClass,
                    signedOctets);
            authBody =
                    AppleIkeEd25519Auth.buildAuthBody(
                            signature);
            plaintext =
                    buildResponderPlaintext(
                            idrBody,
                            authBody,
                            responderChildSpi,
                            profile,
                            dataClass);
            List<byte[]> packets =
                    IkeV2SessionCrypto.encryptProtectedPayload(
                            random,
                            keys.initiatorSpi,
                            keys.responderSpi,
                            keys.skEr,
                            true,
                            IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                            MESSAGE_ID,
                            plaintext,
                            IkeV2Codec.PAYLOAD_NOTIFY,
                            MAXIMUM_PACKET_SIZE);
            if (packets.size() != 1) {
                throw new IllegalStateException(
                        "Target ordinary IKE_AUTH response "
                                + "must remain unfragmented");
            }
            return new ResponderResult(
                    packets.get(0),
                    plaintext,
                    request.initiatorChildSpi,
                    responderChildSpi);
        } finally {
            request.destroy();
            wipe(responderChildSpi);
            wipe(idrBody);
            wipe(signedOctets);
            wipe(signature);
            wipe(authBody);
            wipe(plaintext);
        }
    }

    static InitiatorResult authenticateResponder(
            AppleNetworkRelayPairingMaterial localMaterial,
            PeerMaterial peerMaterial,
            byte[] expectedRemotePrelude,
            byte[] initiatorSaInitPacket,
            byte[] responderSaInitPacket,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] initiatorIntAuth,
            byte[] responderIntAuth,
            InitiatorRequest request,
            byte[] responsePacket) {
        return authenticateResponder(
                DataClass.CLASS_D,
                localMaterial,
                peerMaterial,
                expectedRemotePrelude,
                initiatorSaInitPacket,
                responderSaInitPacket,
                keys,
                initiatorIntAuth,
                responderIntAuth,
                request,
                responsePacket);
    }

    static InitiatorResult authenticateResponder(
            DataClass dataClass,
            AppleNetworkRelayPairingMaterial localMaterial,
            PeerMaterial peerMaterial,
            byte[] expectedRemotePrelude,
            byte[] initiatorSaInitPacket,
            byte[] responderSaInitPacket,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] initiatorIntAuth,
            byte[] responderIntAuth,
            InitiatorRequest request,
            byte[] responsePacket) {
        if (localMaterial == null
                || peerMaterial == null
                || request == null
                || dataClass == null) {
            throw new IllegalArgumentException(
                    "Complete ordinary initiator state is required");
        }
        requireLength(
                "expected remote prelude",
                expectedRemotePrelude,
                NrLinkBluetoothPrelude.EXACT_ENCODED_LENGTH);
        NrLinkBluetoothPrelude.parse(expectedRemotePrelude);
        validateSaInitTranscript(
                initiatorSaInitPacket,
                responderSaInitPacket,
                keys);
        requireIntermediateAuth(
                initiatorIntAuth,
                responderIntAuth);

        IkeV2SessionCrypto.DecryptedIntermediatePart decrypted =
                IkeV2SessionCrypto.decryptProtectedPacket(
                        responsePacket,
                        keys.initiatorSpi,
                        keys.responderSpi,
                        keys.skEr,
                        true,
                        IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                        MESSAGE_ID);
        requireUnfragmented(
                decrypted,
                IkeV2Codec.PAYLOAD_NOTIFY,
                "response");
        List<Payload> payloads =
                parsePayloadChain(
                        decrypted.firstInnerPayload,
                        decrypted.plaintext);
        validateResponderPayloadTypes(payloads);

        byte[] idrBody = payloads.get(1).body.clone();
        byte[] signedOctets = null;
        byte[] responderChildSpi = null;
        AppleNetworkRelayInnerAddresses addresses = null;
        ResponderMetadata metadata = null;
        try {
            validateServiceIdentifier(
                    idrBody,
                    dataClass);
            signedOctets =
                    AppleIkeEd25519Auth.buildSignedOctets(
                            responderSaInitPacket,
                            keys.initiatorNonce,
                            keys.skPr,
                            idrBody,
                            initiatorIntAuth,
                            responderIntAuth,
                            MESSAGE_ID);
            if (!AppleIkeEd25519Auth.verifyAuthBody(
                    peerMaterial.publicEd25519(
                            dataClass),
                    signedOctets,
                    payloads.get(2).body)) {
                throw new IllegalArgumentException(
                        "Ordinary responder Ed25519 AUTH failed");
            }

            validateEmptyNotify(
                    payloads.get(0),
                    NOTIFY_INITIAL_CONTACT);
            byte[][] cpAddresses =
                    parseConfigurationReply(
                            payloads.get(3).body);
            try {
                validateEmptyNotify(
                        payloads.get(4),
                        NOTIFY_USE_TRANSPORT_MODE);
                validateEmptyNotify(
                        payloads.get(5),
                        NOTIFY_ESP_TFC_PADDING_NOT_SUPPORTED);
                validateEmptyNotify(
                        payloads.get(6),
                        NOTIFY_NON_FIRST_FRAGMENTS_ALSO);
                responderChildSpi =
                        parseResponderSa(
                                payloads.get(7).body);
                validateTrafficSelectors(
                        payloads.get(8));
                validateTrafficSelectors(
                        payloads.get(9));
                validateEmptyNotify(
                        payloads.get(10),
                        NOTIFY_SEQUENCE_PER_TRAFFIC_CLASS);

                ParsedPrivateBatch privateBatch =
                        parsePrivateBatch(
                                payloads.subList(
                                        11,
                                        payloads.size()),
                                expectedRemotePrelude);
                addresses =
                        privateBatch.addresses;
                metadata =
                        privateBatch.metadata;
                privateBatch.releaseOwnership();

                byte[] initiatorAddress =
                        addressFor(
                                addresses,
                                dataClass,
                                true);
                byte[] responderAddress =
                        addressFor(
                                addresses,
                                dataClass,
                                false);
                try {
                    if (!Arrays.equals(
                            cpAddresses[0],
                            initiatorAddress)
                            || !Arrays.equals(
                            cpAddresses[1],
                            responderAddress)) {
                        throw new IllegalArgumentException(
                                "Ordinary CP reply and private "
                                        + "address notifies disagree");
                    }
                } finally {
                    wipe(initiatorAddress);
                    wipe(responderAddress);
                }
            } finally {
                wipe(cpAddresses[0]);
                wipe(cpAddresses[1]);
            }
            InitiatorResult result =
                    new InitiatorResult(
                            request.initiatorChildSpi,
                            responderChildSpi,
                            addresses,
                            metadata);
            addresses = null;
            metadata = null;
            return result;
        } finally {
            wipe(idrBody);
            wipe(signedOctets);
            wipe(responderChildSpi);
            if (addresses != null) {
                addresses.destroy();
            }
            if (metadata != null) {
                metadata.destroy();
            }
            destroyPayloads(payloads);
        }
    }

    private static ParsedRequest parseAndAuthenticateRequest(
            DataClass dataClass,
            AppleNetworkRelayPairingMaterial localMaterial,
            PeerMaterial peerMaterial,
            byte[] initiatorSaInitPacket,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] initiatorIntAuth,
            byte[] responderIntAuth,
            byte[] requestPacket) {
        IkeV2SessionCrypto.DecryptedIntermediatePart decrypted =
                IkeV2SessionCrypto.decryptProtectedPacket(
                        requestPacket,
                        keys.initiatorSpi,
                        keys.responderSpi,
                        keys.skEi,
                        false,
                        IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                        MESSAGE_ID);
        requireUnfragmented(
                decrypted,
                PAYLOAD_ID_INITIATOR,
                "request");
        if (decrypted.plaintext.length
                != REQUEST_INNER_LENGTH) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_AUTH request length is invalid");
        }
        List<Payload> payloads =
                parsePayloadChain(
                        decrypted.firstInnerPayload,
                        decrypted.plaintext);
        validateInitiatorPayloadTypes(payloads);

        byte[] encryptedIdentifier = null;
        byte[] identifierPlaintext = null;
        byte[] expectedLocalIdentityUuid = null;
        byte[] identityUuid = null;
        byte[] signedOctets = null;
        byte[] initiatorChildSpi = null;
        try {
            encryptedIdentifier =
                    AppleIkeEncryptedKeyId.parseIdPayloadBody(
                            payloads.get(0).body);
            identifierPlaintext =
                    localMaterial.openEncryptedIdentity(
                            dataClass.serviceKeyId,
                            keys.initiatorNonce,
                            keys.responderNonce,
                            keys.initiatorSpi,
                            keys.responderSpi,
                            encryptedIdentifier);
            expectedLocalIdentityUuid =
                    localMaterial.identityUuid();
            identityUuid =
                    AppleIkeEncryptedKeyId
                            .extractAndValidateIdentityUuid(
                                    identifierPlaintext,
                                    expectedLocalIdentityUuid);
            validateEmptyNotify(
                    payloads.get(1),
                    NOTIFY_INITIAL_CONTACT);
            validateServiceIdentifier(
                    payloads.get(2).body,
                    dataClass);
            signedOctets =
                    AppleIkeEd25519Auth.buildSignedOctets(
                            initiatorSaInitPacket,
                            keys.responderNonce,
                            keys.skPi,
                            payloads.get(0).body,
                            initiatorIntAuth,
                            responderIntAuth,
                            MESSAGE_ID);
            if (!AppleIkeEd25519Auth.verifyAuthBody(
                    peerMaterial.publicEd25519(
                            dataClass),
                    signedOctets,
                    payloads.get(3).body)) {
                throw new IllegalArgumentException(
                        "Ordinary initiator Ed25519 AUTH failed");
            }
            validateConfigurationRequest(
                    payloads.get(4).body);
            validateEmptyNotify(
                    payloads.get(5),
                    NOTIFY_USE_TRANSPORT_MODE);
            validateEmptyNotify(
                    payloads.get(6),
                    NOTIFY_ESP_TFC_PADDING_NOT_SUPPORTED);
            validateEmptyNotify(
                    payloads.get(7),
                    NOTIFY_NON_FIRST_FRAGMENTS_ALSO);
            initiatorChildSpi =
                    parseInitiatorSa(
                            payloads.get(8).body);
            validateTrafficSelectors(
                    payloads.get(9));
            validateTrafficSelectors(
                    payloads.get(10));
            validateEmptyNotify(
                    payloads.get(11),
                    NOTIFY_SEQUENCE_PER_TRAFFIC_CLASS);
            return new ParsedRequest(
                    initiatorChildSpi);
        } finally {
            wipe(encryptedIdentifier);
            wipe(identifierPlaintext);
            wipe(expectedLocalIdentityUuid);
            wipe(identityUuid);
            wipe(signedOctets);
            wipe(initiatorChildSpi);
            destroyPayloads(payloads);
        }
    }

    private static byte[] buildInitiatorPlaintext(
            byte[] idiBody,
            byte[] authBody,
            byte[] childSpi,
            DataClass dataClass) {
        List<TypedBody> bodies = new ArrayList<>();
        bodies.add(new TypedBody(
                PAYLOAD_ID_INITIATOR,
                idiBody));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(NOTIFY_INITIAL_CONTACT, new byte[0])));
        bodies.add(new TypedBody(
                PAYLOAD_ID_RESPONDER,
                serviceIdentifierBody(dataClass)));
        bodies.add(new TypedBody(
                PAYLOAD_AUTH,
                authBody));
        bodies.add(new TypedBody(
                PAYLOAD_CONFIGURATION,
                configurationRequestBody()));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        NOTIFY_USE_TRANSPORT_MODE,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        NOTIFY_ESP_TFC_PADDING_NOT_SUPPORTED,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        NOTIFY_NON_FIRST_FRAGMENTS_ALSO,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_SA,
                initiatorSaBody(childSpi)));
        bodies.add(new TypedBody(
                PAYLOAD_TRAFFIC_SELECTOR_INITIATOR,
                TRAFFIC_SELECTORS_BODY));
        bodies.add(new TypedBody(
                PAYLOAD_TRAFFIC_SELECTOR_RESPONDER,
                TRAFFIC_SELECTORS_BODY));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        NOTIFY_SEQUENCE_PER_TRAFFIC_CLASS,
                        new byte[0])));
        return encodePayloadChain(bodies);
    }

    private static byte[] buildResponderPlaintext(
            byte[] idrBody,
            byte[] authBody,
            byte[] childSpi,
            ResponderProfile profile,
            DataClass dataClass) {
        List<TypedBody> bodies = new ArrayList<>();
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(NOTIFY_INITIAL_CONTACT, new byte[0])));
        bodies.add(new TypedBody(
                PAYLOAD_ID_RESPONDER,
                idrBody));
        bodies.add(new TypedBody(
                PAYLOAD_AUTH,
                authBody));
        bodies.add(new TypedBody(
                PAYLOAD_CONFIGURATION,
                configurationReplyBody(
                        profile.addresses,
                        dataClass)));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        NOTIFY_USE_TRANSPORT_MODE,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        NOTIFY_ESP_TFC_PADDING_NOT_SUPPORTED,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        NOTIFY_NON_FIRST_FRAGMENTS_ALSO,
                        new byte[0])));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_SA,
                responderSaBody(childSpi)));
        bodies.add(new TypedBody(
                PAYLOAD_TRAFFIC_SELECTOR_INITIATOR,
                TRAFFIC_SELECTORS_BODY));
        bodies.add(new TypedBody(
                PAYLOAD_TRAFFIC_SELECTOR_RESPONDER,
                TRAFFIC_SELECTORS_BODY));
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(
                        NOTIFY_SEQUENCE_PER_TRAFFIC_CLASS,
                        new byte[0])));
        addPrivateResponderBodies(
                bodies,
                profile);
        return encodePayloadChain(bodies);
    }

    private static void addPrivateResponderBodies(
            List<TypedBody> bodies,
            ResponderProfile profile) {
        byte[] initiatorD = null;
        byte[] responderD = null;
        byte[] initiatorC = null;
        byte[] responderC = null;
        try {
            initiatorD =
                    profile.addresses.initiatorClassD();
            responderD =
                    profile.addresses.responderClassD();
            initiatorC =
                    profile.addresses.initiatorClassC();
            responderC =
                    profile.addresses.responderClassC();
            addNotify(
                    bodies,
                    NOTIFY_TERMINUS_VERSION,
                    new byte[]{0, CURRENT_TERMINUS_VERSION});
            addNotify(
                    bodies,
                    NOTIFY_DEVICE_NAME,
                    profile.deviceName);
            addNotify(
                    bodies,
                    NOTIFY_BUILD_VERSION,
                    profile.buildVersion);
            addNotify(
                    bodies,
                    NOTIFY_DEVICE_TYPE,
                    new byte[]{(byte) profile.deviceType});
            addNotify(
                    bodies,
                    NOTIFY_KEYS_AFTER_FIRST_UNLOCK,
                    new byte[]{
                            profile.keysAfterFirstUnlock
                                    ? (byte) 0x80
                                    : 0
                    });
            addNotify(
                    bodies,
                    NOTIFY_LOCAL_FLAGS,
                    new byte[8]);
            addNotify(
                    bodies,
                    NOTIFY_ALWAYS_ON_WIFI,
                    new byte[]{profile.alwaysOnWifi ? (byte) 1 : 0});
            addNotify(
                    bodies,
                    NOTIFY_LOCAL_PRELUDE,
                    profile.localPrelude);
            addNotify(
                    bodies,
                    ApplePairingNotifyPayloads
                            .INNER_INITIATOR_CLASS_D,
                    initiatorD);
            addNotify(
                    bodies,
                    ApplePairingNotifyPayloads
                            .INNER_RESPONDER_CLASS_D,
                    responderD);
            addNotify(
                    bodies,
                    ApplePairingNotifyPayloads
                            .INNER_INITIATOR_CLASS_C,
                    initiatorC);
            addNotify(
                    bodies,
                    ApplePairingNotifyPayloads
                            .INNER_RESPONDER_CLASS_C,
                    responderC);
            if (profile.idsDeviceId != null) {
                addNotify(
                        bodies,
                        NOTIFY_IDS_DEVICE_ID,
                        profile.idsDeviceId);
            }
        } finally {
            wipe(initiatorD);
            wipe(responderD);
            wipe(initiatorC);
            wipe(responderC);
        }
    }

    private static void addNotify(
            List<TypedBody> bodies,
            int type,
            byte[] data) {
        bodies.add(new TypedBody(
                IkeV2Codec.PAYLOAD_NOTIFY,
                notifyBody(type, data)));
    }

    private static byte[] encodePayloadChain(
            List<TypedBody> bodies) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        for (int index = 0;
                index < bodies.size();
                index++) {
            TypedBody current = bodies.get(index);
            int next = index + 1 < bodies.size()
                    ? bodies.get(index + 1).type
                    : IkeV2Codec.PAYLOAD_NONE;
            output.writeBytes(
                    genericPayload(
                            next,
                            current.body));
        }
        for (TypedBody body : bodies) {
            body.destroy();
        }
        return output.toByteArray();
    }

    private static List<Payload> parsePayloadChain(
            int firstType,
            byte[] plaintext) {
        if (firstType == IkeV2Codec.PAYLOAD_NONE
                || plaintext == null
                || plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_AUTH payload chain is empty");
        }
        List<Payload> output = new ArrayList<>();
        int type = firstType;
        int offset = 0;
        try {
            while (type != IkeV2Codec.PAYLOAD_NONE) {
                if (offset + 4 > plaintext.length) {
                    throw new IllegalArgumentException(
                            "Ordinary IKE_AUTH payload header "
                                    + "is truncated");
                }
                int next = unsigned(plaintext[offset]);
                int flags = unsigned(plaintext[offset + 1]);
                int length = be16(plaintext, offset + 2);
                if (flags != 0
                        || length < 4
                        || offset + length > plaintext.length) {
                    throw new IllegalArgumentException(
                            "Ordinary IKE_AUTH payload layout "
                                    + "is invalid");
                }
                output.add(new Payload(
                        type,
                        Arrays.copyOfRange(
                                plaintext,
                                offset + 4,
                                offset + length)));
                offset += length;
                type = next;
            }
            if (offset != plaintext.length) {
                throw new IllegalArgumentException(
                        "Ordinary IKE_AUTH payload chain "
                                + "has trailing bytes");
            }
            return List.copyOf(output);
        } catch (RuntimeException error) {
            destroyPayloads(output);
            throw error;
        }
    }

    private static void validateInitiatorPayloadTypes(
            List<Payload> payloads) {
        requirePayloadTypes(
                payloads,
                new int[]{
                        PAYLOAD_ID_INITIATOR,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        PAYLOAD_ID_RESPONDER,
                        PAYLOAD_AUTH,
                        PAYLOAD_CONFIGURATION,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        IkeV2Codec.PAYLOAD_SA,
                        PAYLOAD_TRAFFIC_SELECTOR_INITIATOR,
                        PAYLOAD_TRAFFIC_SELECTOR_RESPONDER,
                        IkeV2Codec.PAYLOAD_NOTIFY
                });
    }

    private static void validateResponderPayloadTypes(
            List<Payload> payloads) {
        int requiredCount = 23;
        if (payloads.size() != requiredCount
                && payloads.size() != requiredCount + 1) {
            throw new IllegalArgumentException(
                    "Ordinary responder payload count is invalid: "
                            + "expected=23-or-24 "
                            + describeResponderPayloadStructure(
                            payloads));
        }
        int[] standard = new int[]{
                IkeV2Codec.PAYLOAD_NOTIFY,
                PAYLOAD_ID_RESPONDER,
                PAYLOAD_AUTH,
                PAYLOAD_CONFIGURATION,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_NOTIFY,
                IkeV2Codec.PAYLOAD_SA,
                PAYLOAD_TRAFFIC_SELECTOR_INITIATOR,
                PAYLOAD_TRAFFIC_SELECTOR_RESPONDER,
                IkeV2Codec.PAYLOAD_NOTIFY
        };
        for (int index = 0;
                index < standard.length;
                index++) {
            if (payloads.get(index).type
                    != standard[index]) {
                throw new IllegalArgumentException(
                        String.format(
                                Locale.ROOT,
                                "Ordinary responder standard payload "
                                        + "order is invalid: index=%d "
                                        + "expected=0x%02X actual=0x%02X; "
                                        + "bodies logged=false",
                                index,
                                standard[index],
                                payloads.get(index).type));
            }
        }
        for (int index = standard.length;
                index < payloads.size();
                index++) {
            if (payloads.get(index).type
                    != IkeV2Codec.PAYLOAD_NOTIFY) {
                throw new IllegalArgumentException(
                        String.format(
                                Locale.ROOT,
                                "Ordinary responder private payload "
                                        + "is not a Notify: index=%d "
                                        + "expected=0x%02X actual=0x%02X; "
                                        + "bodies logged=false",
                                index,
                                IkeV2Codec.PAYLOAD_NOTIFY,
                                payloads.get(index).type));
            }
        }
    }

    static String describeResponderPayloadStructure(
            byte[] plaintext) {
        List<Payload> payloads =
                parsePayloadChain(
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        plaintext);
        try {
            return describeResponderPayloadStructure(
                    payloads);
        } finally {
            destroyPayloads(payloads);
        }
    }

    private static String describeResponderPayloadStructure(
            List<Payload> payloads) {
        StringBuilder output = new StringBuilder();
        int limit = Math.min(payloads.size(), 48);
        output.append("count=")
                .append(payloads.size())
                .append(" payloadTypes=[");
        for (int index = 0; index < limit; index++) {
            if (index != 0) {
                output.append(',');
            }
            output.append(String.format(
                    Locale.ROOT,
                    "0x%02X",
                    payloads.get(index).type));
        }
        if (limit != payloads.size()) {
            output.append(",...");
        }
        output.append("] notifyHeaders=[");
        boolean first = true;
        for (int index = 0; index < limit; index++) {
            Payload payload = payloads.get(index);
            if (payload.type != IkeV2Codec.PAYLOAD_NOTIFY) {
                continue;
            }
            if (!first) {
                output.append(',');
            }
            first = false;
            output.append(index).append(':');
            if (payload.body.length < 4) {
                output.append("truncated(length=")
                        .append(payload.body.length)
                        .append(')');
                continue;
            }
            int protocolId = unsigned(payload.body[0]);
            int spiSize = unsigned(payload.body[1]);
            int notifyType = be16(payload.body, 2);
            int dataLength = payload.body.length - 4 - spiSize;
            output.append(String.format(
                    Locale.ROOT,
                    "type=0x%04X/protocol=%d/spi=%d/dataLength=%d",
                    notifyType,
                    protocolId,
                    spiSize,
                    dataLength));
        }
        output.append("]; bodies logged=false");
        return output.toString();
    }

    private static void requirePayloadTypes(
            List<Payload> payloads,
            int[] expected) {
        if (payloads.size() != expected.length) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_AUTH payload count is invalid");
        }
        for (int index = 0;
                index < expected.length;
                index++) {
            if (payloads.get(index).type != expected[index]) {
                throw new IllegalArgumentException(
                        "Ordinary IKE_AUTH payload order is invalid");
            }
        }
    }

    private static ParsedPrivateBatch parsePrivateBatch(
            List<Payload> payloads,
            byte[] expectedRemotePrelude) {
        int[] mandatoryTypes = new int[]{
                NOTIFY_TERMINUS_VERSION,
                NOTIFY_DEVICE_NAME,
                NOTIFY_BUILD_VERSION,
                NOTIFY_DEVICE_TYPE,
                NOTIFY_KEYS_AFTER_FIRST_UNLOCK,
                NOTIFY_LOCAL_FLAGS,
                NOTIFY_ALWAYS_ON_WIFI,
                NOTIFY_LOCAL_PRELUDE,
                ApplePairingNotifyPayloads
                        .INNER_INITIATOR_CLASS_D,
                ApplePairingNotifyPayloads
                        .INNER_RESPONDER_CLASS_D,
                ApplePairingNotifyPayloads
                        .INNER_INITIATOR_CLASS_C,
                ApplePairingNotifyPayloads
                        .INNER_RESPONDER_CLASS_C
        };
        if (payloads.size() != mandatoryTypes.length
                && payloads.size()
                != mandatoryTypes.length + 1) {
            throw new IllegalArgumentException(
                    "Ordinary responder private-notify "
                            + "count is invalid");
        }
        byte[][] data = new byte[payloads.size()][];
        try {
            for (int index = 0;
                    index < payloads.size();
                    index++) {
                Notify notify =
                        parseNotify(payloads.get(index));
                int expectedType = index < mandatoryTypes.length
                        ? mandatoryTypes[index]
                        : NOTIFY_IDS_DEVICE_ID;
                if (notify.type != expectedType) {
                    notify.destroy();
                    throw new IllegalArgumentException(
                            "Ordinary responder private-notify "
                                    + "order is invalid");
                }
                data[index] = notify.data.clone();
                notify.destroy();
            }
            if (!Arrays.equals(
                    data[0],
                    new byte[]{0, CURRENT_TERMINUS_VERSION})
                    || data[3].length != 1
                    || data[4].length != 1
                    || (data[4][0] != 0
                    && data[4][0] != (byte) 0x80)
                    || !Arrays.equals(data[5], new byte[8])
                    || data[6].length != 1
                    || (data[6][0] != 0
                    && data[6][0] != 1)
                    || !Arrays.equals(
                    data[7],
                    expectedRemotePrelude)) {
                throw new IllegalArgumentException(
                        "Ordinary responder private metadata "
                                + "is invalid");
            }
            String name =
                    decodeRequiredUtf8(
                            "responder device name",
                            data[1]);
            String build =
                    decodeRequiredUtf8(
                            "responder build version",
                            data[2]);
            String ids = payloads.size()
                    == mandatoryTypes.length + 1
                    ? decodeRequiredUtf8(
                            "responder IDS device ID",
                            data[mandatoryTypes.length])
                    : null;
            AppleNetworkRelayInnerAddresses addresses =
                    AppleNetworkRelayInnerAddresses.fromAuthoritative(
                            data[8],
                            data[9],
                            data[10],
                            data[11]);
            ResponderMetadata metadata =
                    new ResponderMetadata(
                            name,
                            build,
                            ids,
                            unsigned(data[3][0]),
                            data[4][0] == (byte) 0x80,
                            data[6][0] != 0);
            return new ParsedPrivateBatch(
                    addresses,
                    metadata);
        } finally {
            for (byte[] value : data) {
                wipe(value);
            }
        }
    }

    private static byte[] serviceIdentifierBody(
            DataClass dataClass) {
        if (dataClass == null) {
            throw new IllegalArgumentException(
                    "Ordinary data class is required");
        }
        byte[] output =
                new byte[4 + dataClass.serviceKeyIdBytes.length];
        output[0] =
                (byte) AppleIkeEncryptedKeyId.ID_TYPE_KEY_ID;
        System.arraycopy(
                dataClass.serviceKeyIdBytes,
                0,
                output,
                4,
                dataClass.serviceKeyIdBytes.length);
        return output;
    }

    private static void validateServiceIdentifier(
            byte[] body,
            DataClass dataClass) {
        byte[] expected = serviceIdentifierBody(
                dataClass);
        try {
            if (!Arrays.equals(expected, body)) {
                throw new IllegalArgumentException(
                        "Ordinary "
                                + dataClass
                                + " service identifier "
                                + "is invalid");
            }
        } finally {
            wipe(expected);
        }
    }

    private static byte[] configurationRequestBody() {
        byte[] output = new byte[44];
        output[0] = 1;
        putConfigAttribute(
                output,
                4,
                CONFIG_ATTRIBUTE_INITIATOR_IPV6,
                new byte[16]);
        putConfigAttribute(
                output,
                24,
                CONFIG_ATTRIBUTE_RESPONDER_IPV6,
                new byte[16]);
        return output;
    }

    private static void validateConfigurationRequest(
            byte[] body) {
        byte[] expected = configurationRequestBody();
        try {
            if (!Arrays.equals(expected, body)) {
                throw new IllegalArgumentException(
                        "Ordinary CFG_REQUEST is invalid");
            }
        } finally {
            wipe(expected);
        }
    }

    private static byte[] configurationReplyBody(
            AppleNetworkRelayInnerAddresses addresses,
            DataClass dataClass) {
        byte[] initiator = null;
        byte[] responder = null;
        try {
            initiator = addressFor(
                    addresses,
                    dataClass,
                    true);
            responder = addressFor(
                    addresses,
                    dataClass,
                    false);
            byte[] output = new byte[44];
            output[0] = 2;
            putConfigAttribute(
                    output,
                    4,
                    CONFIG_ATTRIBUTE_INITIATOR_IPV6,
                    initiator);
            putConfigAttribute(
                    output,
                    24,
                    CONFIG_ATTRIBUTE_RESPONDER_IPV6,
                    responder);
            return output;
        } finally {
            wipe(initiator);
            wipe(responder);
        }
    }

    private static byte[][] parseConfigurationReply(
            byte[] body) {
        if (body == null
                || body.length != 44
                || body[0] != 2
                || body[1] != 0
                || body[2] != 0
                || body[3] != 0
                || be16(body, 4)
                != CONFIG_ATTRIBUTE_INITIATOR_IPV6
                || be16(body, 6) != 16
                || be16(body, 24)
                != CONFIG_ATTRIBUTE_RESPONDER_IPV6
                || be16(body, 26) != 16) {
            throw new IllegalArgumentException(
                    "Ordinary CFG_REPLY is invalid");
        }
        return new byte[][]{
                Arrays.copyOfRange(body, 8, 24),
                Arrays.copyOfRange(body, 28, 44)
        };
    }

    private static void putConfigAttribute(
            byte[] output,
            int offset,
            int type,
            byte[] address) {
        requireLength(
                "configuration IPv6 address",
                address,
                16);
        output[offset] = (byte) (type >>> 8);
        output[offset + 1] = (byte) type;
        output[offset + 2] = 0;
        output[offset + 3] = 16;
        System.arraycopy(
                address,
                0,
                output,
                offset + 4,
                address.length);
    }

    private static byte[] initiatorSaBody(
            byte[] childSpi) {
        requireLength(
                "initiator Child SPI",
                childSpi,
                CHILD_SPI_LENGTH);
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(40);
        body.write(0);
        body.write(0);
        writeBe16(body, 40);
        body.write(1);
        body.write(3);
        body.write(4);
        body.write(3);
        body.writeBytes(childSpi);
        body.writeBytes(new byte[]{
                0x03, 0x00, 0x00, 0x0c,
                0x01, 0x00, 0x00, 0x1e,
                (byte) 0x80, 0x0e, 0x01, 0x00,
                0x03, 0x00, 0x00, 0x08,
                0x01, 0x00, 0x00, 0x1f,
                0x00, 0x00, 0x00, 0x08,
                0x05, 0x00, 0x00, 0x00
        });
        return body.toByteArray();
    }

    private static byte[] responderSaBody(
            byte[] childSpi) {
        requireLength(
                "responder Child SPI",
                childSpi,
                CHILD_SPI_LENGTH);
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(32);
        body.write(0);
        body.write(0);
        writeBe16(body, 32);
        body.write(1);
        body.write(3);
        body.write(4);
        body.write(2);
        body.writeBytes(childSpi);
        body.writeBytes(new byte[]{
                0x03, 0x00, 0x00, 0x0c,
                0x01, 0x00, 0x00, 0x1e,
                (byte) 0x80, 0x0e, 0x01, 0x00,
                0x00, 0x00, 0x00, 0x08,
                0x05, 0x00, 0x00, 0x00
        });
        return body.toByteArray();
    }

    private static byte[] parseInitiatorSa(
            byte[] body) {
        if (body == null || body.length != 40) {
            throw new IllegalArgumentException(
                    "Ordinary initiator Child proposal "
                            + "has an invalid length");
        }
        byte[] childSpi =
                Arrays.copyOfRange(body, 8, 12);
        byte[] expected =
                initiatorSaBody(childSpi);
        try {
            if (isAllZero(childSpi)
                    || !Arrays.equals(expected, body)) {
                throw new IllegalArgumentException(
                        "Ordinary initiator Child proposal "
                                + "is invalid");
            }
            return childSpi.clone();
        } finally {
            wipe(expected);
            wipe(childSpi);
        }
    }

    private static byte[] parseResponderSa(
            byte[] body) {
        if (body == null || body.length != 32) {
            throw new IllegalArgumentException(
                    "Ordinary responder Child proposal "
                            + "has an invalid length");
        }
        byte[] childSpi =
                Arrays.copyOfRange(body, 8, 12);
        byte[] expected =
                responderSaBody(childSpi);
        try {
            if (isAllZero(childSpi)
                    || !Arrays.equals(expected, body)) {
                throw new IllegalArgumentException(
                        "Ordinary responder Child proposal "
                                + "is invalid");
            }
            return childSpi.clone();
        } finally {
            wipe(expected);
            wipe(childSpi);
        }
    }

    private static void validateTrafficSelectors(
            Payload payload) {
        if (!Arrays.equals(
                payload.body,
                TRAFFIC_SELECTORS_BODY)) {
            throw new IllegalArgumentException(
                    "Ordinary all-IP traffic selectors "
                            + "are invalid");
        }
    }

    private static byte[] notifyBody(
            int type,
            byte[] data) {
        if (type < 0
                || type > 0xffff
                || data == null) {
            throw new IllegalArgumentException(
                    "Notify type and data are required");
        }
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        4 + data.length);
        output.write(0);
        output.write(0);
        writeBe16(output, type);
        output.writeBytes(data);
        return output.toByteArray();
    }

    private static Notify parseNotify(
            Payload payload) {
        if (payload.type != IkeV2Codec.PAYLOAD_NOTIFY
                || payload.body.length < 4
                || payload.body[0] != 0
                || payload.body[1] != 0) {
            throw new IllegalArgumentException(
                    "Ordinary Notify body is invalid");
        }
        return new Notify(
                be16(payload.body, 2),
                Arrays.copyOfRange(
                        payload.body,
                        4,
                        payload.body.length));
    }

    private static void validateEmptyNotify(
            Payload payload,
            int expectedType) {
        Notify notify = parseNotify(payload);
        try {
            if (notify.type != expectedType
                    || notify.data.length != 0) {
                throw new IllegalArgumentException(
                        "Ordinary IKE notify 0x"
                                + Integer.toHexString(expectedType)
                                + " is invalid");
            }
        } finally {
            notify.destroy();
        }
    }

    private static byte[] genericPayload(
            int next,
            byte[] body) {
        int length = 4 + body.length;
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(length);
        output.write(next);
        output.write(0);
        writeBe16(output, length);
        output.writeBytes(body);
        return output.toByteArray();
    }

    private static void requireCommonState(
            DataClass dataClass,
            SecureRandom random,
            AppleNetworkRelayPairingMaterial localMaterial,
            PeerMaterial peerMaterial,
            IkeV2SessionCrypto.IkeSaKeys keys,
            byte[] initiatorIntAuth,
            byte[] responderIntAuth) {
        if (dataClass == null
                || random == null
                || localMaterial == null
                || peerMaterial == null
                || keys == null) {
            throw new IllegalArgumentException(
                    "Complete ordinary IKE state is required");
        }
        requireIntermediateAuth(
                initiatorIntAuth,
                responderIntAuth);
        peerMaterial.publicEd25519(
                dataClass);
    }

    private static byte[] sign(
            AppleNetworkRelayPairingMaterial localMaterial,
            DataClass dataClass,
            byte[] message) {
        return dataClass == DataClass.CLASS_D
                ? localMaterial.signClassD(message)
                : localMaterial.signClassC(message);
    }

    private static byte[] addressFor(
            AppleNetworkRelayInnerAddresses addresses,
            DataClass dataClass,
            boolean initiator) {
        if (addresses == null || dataClass == null) {
            throw new IllegalArgumentException(
                    "Addresses and ordinary data class are required");
        }
        if (dataClass == DataClass.CLASS_D) {
            return initiator
                    ? addresses.initiatorClassD()
                    : addresses.responderClassD();
        }
        return initiator
                ? addresses.initiatorClassC()
                : addresses.responderClassC();
    }

    private static void requireIntermediateAuth(
            byte[] initiatorIntAuth,
            byte[] responderIntAuth) {
        requireLength(
                "initiator IntAuth",
                initiatorIntAuth,
                INTERMEDIATE_AUTH_LENGTH);
        requireLength(
                "responder IntAuth",
                responderIntAuth,
                INTERMEDIATE_AUTH_LENGTH);
    }

    private static void validateInitiatorSaInit(
            byte[] requestPacket,
            IkeV2SessionCrypto.IkeSaKeys keys) {
        OrdinaryIkeSaInit.Request request =
                OrdinaryIkeSaInit.parseRequest(
                        requestPacket);
        if (!Arrays.equals(
                request.initiatorSpi,
                keys.initiatorSpi)
                || !Arrays.equals(
                request.nonce,
                keys.initiatorNonce)) {
            throw new IllegalArgumentException(
                    "Ordinary initiator IKE_SA_INIT "
                            + "does not match the key schedule");
        }
    }

    private static void validateSaInitTranscript(
            byte[] requestPacket,
            byte[] responsePacket,
            IkeV2SessionCrypto.IkeSaKeys keys) {
        validateInitiatorSaInit(
                requestPacket,
                keys);
        OrdinaryIkeSaInit.Response response =
                OrdinaryIkeSaInit.parseResponse(
                        responsePacket,
                        keys.initiatorSpi);
        if (!Arrays.equals(
                response.responderSpi,
                keys.responderSpi)
                || !Arrays.equals(
                response.nonce,
                keys.responderNonce)) {
            throw new IllegalArgumentException(
                    "Ordinary responder IKE_SA_INIT "
                            + "does not match the key schedule");
        }
    }

    private static void requireUnfragmented(
            IkeV2SessionCrypto.DecryptedIntermediatePart part,
            int expectedFirstPayload,
            String label) {
        if (part == null
                || part.fragmented
                || part.protectedPayloadFlags != 0
                || part.firstInnerPayload
                != expectedFirstPayload) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_AUTH "
                            + label
                            + " has invalid SK framing");
        }
    }

    private static byte[] randomNonzeroChildSpi(
            SecureRandom random) {
        byte[] spi = new byte[CHILD_SPI_LENGTH];
        random.nextBytes(spi);
        if (isAllZero(spi)) {
            spi[spi.length - 1] = 1;
        }
        return spi;
    }

    private static String decodeRequiredUtf8(
            String label,
            byte[] value) {
        if (value == null
                || value.length == 0
                || value.length > 254) {
            throw new IllegalArgumentException(
                    label + " must contain 1..254 bytes");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                            CodingErrorAction.REPORT)
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(
                    label + " is not valid UTF-8",
                    error);
        }
    }

    private static byte[] encodeRequiredUtf8(
            String label,
            String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
        byte[] encoded =
                value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0
                || encoded.length > 254) {
            wipe(encoded);
            throw new IllegalArgumentException(
                    label + " must encode to 1..254 UTF-8 bytes");
        }
        return encoded;
    }

    private static void destroyPayloads(
            List<Payload> payloads) {
        for (Payload payload : payloads) {
            payload.destroy();
        }
    }

    private static boolean isAllZero(
            byte[] value) {
        int combined = 0;
        for (byte current : value) {
            combined |= current & 0xff;
        }
        return combined == 0;
    }

    private static int unsigned(
            byte value) {
        return value & 0xff;
    }

    private static int be16(
            byte[] value,
            int offset) {
        return (unsigned(value[offset]) << 8)
                | unsigned(value[offset + 1]);
    }

    private static void writeBe16(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void requireLength(
            String label,
            byte[] value,
            int length) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(
                    label + " must contain " + length + " bytes");
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class PeerMaterial {
        private final byte[] identityUuid;
        private final byte[] identityPublicX25519;
        private final byte[] classDPublicEd25519;
        private final byte[] classDPublicX25519;
        private final byte[] classCPublicEd25519;
        private final byte[] classCPublicX25519;
        private boolean destroyed;

        private PeerMaterial(
                byte[] identityUuid,
                byte[] identityPublicX25519,
                byte[] classDPublicEd25519,
                byte[] classDPublicX25519,
                byte[] classCPublicEd25519,
                byte[] classCPublicX25519) {
            this.identityUuid = identityUuid.clone();
            this.identityPublicX25519 =
                    identityPublicX25519.clone();
            this.classDPublicEd25519 =
                    classDPublicEd25519.clone();
            this.classDPublicX25519 =
                    classDPublicX25519.clone();
            this.classCPublicEd25519 =
                    classCPublicEd25519 == null
                            ? null
                            : classCPublicEd25519.clone();
            this.classCPublicX25519 =
                    classCPublicX25519 == null
                            ? null
                            : classCPublicX25519.clone();
        }

        static PeerMaterial fromPairingPayloads(
                byte[] identityPayload,
                byte[] classDPublicKeysPayload) {
            return fromPairingPayloads(
                    identityPayload,
                    classDPublicKeysPayload,
                    null);
        }

        static PeerMaterial fromPairingPayloads(
                byte[] identityPayload,
                byte[] classDPublicKeysPayload,
                byte[] classCPublicKeysPayload) {
            requireLength(
                    "peer identity pairing payload",
                    identityPayload,
                    AppleNetworkRelayPairingMaterial
                            .IDENTITY_PAYLOAD_LENGTH);
            byte[][] classD = parsePublicKeysPayload(
                    "Class-D",
                    classDPublicKeysPayload);
            byte[][] classC =
                    classCPublicKeysPayload == null
                            ? null
                            : parsePublicKeysPayload(
                                    "Class-C",
                                    classCPublicKeysPayload);
            byte[] identityUuid = Arrays.copyOfRange(
                    identityPayload,
                    0,
                    16);
            byte[] identityPublic = Arrays.copyOfRange(
                    identityPayload,
                    16,
                    48);
            try {
                return new PeerMaterial(
                        identityUuid,
                        identityPublic,
                        classD[0],
                        classD[1],
                        classC == null ? null : classC[0],
                        classC == null ? null : classC[1]);
            } finally {
                wipe(identityUuid);
                wipe(identityPublic);
                wipe(classD[0]);
                wipe(classD[1]);
                if (classC != null) {
                    wipe(classC[0]);
                    wipe(classC[1]);
                }
            }
        }

        PeerMaterial copy() {
            requireLive();
            return new PeerMaterial(
                    identityUuid,
                    identityPublicX25519,
                    classDPublicEd25519,
                    classDPublicX25519,
                    classCPublicEd25519,
                    classCPublicX25519);
        }

        private byte[] identityUuid() {
            requireLive();
            return identityUuid.clone();
        }

        private byte[] publicEd25519(
                DataClass dataClass) {
            requireLive();
            if (dataClass == DataClass.CLASS_D) {
                return classDPublicEd25519;
            }
            if (classCPublicEd25519 == null) {
                throw new IllegalArgumentException(
                        "Peer Class-C public keys are unavailable");
            }
            return classCPublicEd25519;
        }

        private static byte[][] parsePublicKeysPayload(
                String label,
                byte[] payload) {
            requireLength(
                    "peer " + label + " public-key payload",
                    payload,
                    AppleNetworkRelayPairingMaterial
                            .MODERN_PUBLIC_KEYS_PAYLOAD_LENGTH);
            if (payload[0] != 1
                    || be16(payload, 1) != 32
                    || payload[35] != 2
                    || be16(payload, 36) != 32) {
                throw new IllegalArgumentException(
                        "Peer "
                                + label
                                + " public-key NRTLV layout is invalid");
            }
            return new byte[][]{
                    Arrays.copyOfRange(payload, 3, 35),
                    Arrays.copyOfRange(payload, 38, 70)
            };
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(identityUuid);
            wipe(identityPublicX25519);
            wipe(classDPublicEd25519);
            wipe(classDPublicX25519);
            wipe(classCPublicEd25519);
            wipe(classCPublicX25519);
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Ordinary peer material has been destroyed");
            }
        }
    }

    static final class ResponderProfile {
        private final AppleNetworkRelayInnerAddresses addresses;
        private final byte[] localPrelude;
        private final byte[] deviceName;
        private final byte[] buildVersion;
        private final byte[] idsDeviceId;
        private final int deviceType;
        private final boolean keysAfterFirstUnlock;
        private final boolean alwaysOnWifi;
        private boolean destroyed;

        ResponderProfile(
                AppleNetworkRelayInnerAddresses addresses,
                byte[] localPrelude,
                String deviceName,
                String buildVersion,
                String idsDeviceId,
                int deviceType,
                boolean keysAfterFirstUnlock,
                boolean alwaysOnWifi) {
            if (addresses == null
                    || deviceType < 0
                    || deviceType > 0xff) {
                throw new IllegalArgumentException(
                        "Complete ordinary responder profile "
                                + "is required");
            }
            requireLength(
                    "local responder prelude",
                    localPrelude,
                    NrLinkBluetoothPrelude.EXACT_ENCODED_LENGTH);
            NrLinkBluetoothPrelude.Parsed parsed =
                    NrLinkBluetoothPrelude.parse(localPrelude);
            if ((parsed.state
                    != NrLinkBluetoothPrelude.PairingState
                    .MODERN_PAIRING_KEY_CONFIRMATION
                    && parsed.state
                    != NrLinkBluetoothPrelude.PairingState
                    .HAS_COMPLETED_PAIRING)
                    || parsed.usesTls()) {
                throw new IllegalArgumentException(
                        "Responder prelude does not select "
                                + "ordinary IPsec");
            }
            byte[] initiatorD =
                    addresses.initiatorClassD();
            byte[] responderD =
                    addresses.responderClassD();
            byte[] initiatorC =
                    addresses.initiatorClassC();
            byte[] responderC =
                    addresses.responderClassC();
            try {
                this.addresses =
                        AppleNetworkRelayInnerAddresses
                                .fromAuthoritative(
                                        initiatorD,
                                        responderD,
                                        initiatorC,
                                        responderC);
            } finally {
                wipe(initiatorD);
                wipe(responderD);
                wipe(initiatorC);
                wipe(responderC);
            }
            this.localPrelude = localPrelude.clone();
            this.deviceName =
                    encodeRequiredUtf8(
                            "responder device name",
                            deviceName);
            this.buildVersion =
                    encodeRequiredUtf8(
                            "responder build version",
                            buildVersion);
            this.idsDeviceId = idsDeviceId == null
                    || idsDeviceId.isEmpty()
                    ? null
                    : encodeRequiredUtf8(
                            "responder IDS device ID",
                            idsDeviceId);
            this.deviceType = deviceType;
            this.keysAfterFirstUnlock =
                    keysAfterFirstUnlock;
            this.alwaysOnWifi = alwaysOnWifi;
        }

        byte[] localPrelude() {
            requireLive();
            return localPrelude.clone();
        }

        byte[] deviceNameData() {
            requireLive();
            return deviceName.clone();
        }

        byte[] buildVersionData() {
            requireLive();
            return buildVersion.clone();
        }

        byte[] idsDeviceIdData() {
            requireLive();
            return idsDeviceId == null
                    ? null
                    : idsDeviceId.clone();
        }

        int deviceType() {
            requireLive();
            return deviceType;
        }

        boolean keysAfterFirstUnlock() {
            requireLive();
            return keysAfterFirstUnlock;
        }

        boolean alwaysOnWifi() {
            requireLive();
            return alwaysOnWifi;
        }

        AppleNetworkRelayInnerAddresses copyAddresses() {
            requireLive();
            byte[] initiatorD = addresses.initiatorClassD();
            byte[] responderD = addresses.responderClassD();
            byte[] initiatorC = addresses.initiatorClassC();
            byte[] responderC = addresses.responderClassC();
            try {
                return AppleNetworkRelayInnerAddresses
                        .fromAuthoritative(
                                initiatorD,
                                responderD,
                                initiatorC,
                                responderC);
            } finally {
                wipe(initiatorD);
                wipe(responderD);
                wipe(initiatorC);
                wipe(responderC);
            }
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            addresses.destroy();
            wipe(localPrelude);
            wipe(deviceName);
            wipe(buildVersion);
            wipe(idsDeviceId);
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Ordinary responder profile "
                                + "has been destroyed");
            }
        }
    }

    static final class InitiatorRequest {
        final byte[] packet;
        final byte[] plaintext;
        final byte[] initiatorChildSpi;
        private boolean destroyed;

        InitiatorRequest(
                byte[] packet,
                byte[] plaintext,
                byte[] initiatorChildSpi) {
            this.packet = packet.clone();
            this.plaintext = plaintext.clone();
            this.initiatorChildSpi =
                    initiatorChildSpi.clone();
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(packet);
            wipe(plaintext);
            wipe(initiatorChildSpi);
            destroyed = true;
        }
    }

    static final class ResponderResult {
        final byte[] packet;
        final byte[] plaintext;
        final byte[] initiatorChildSpi;
        final byte[] responderChildSpi;
        private boolean destroyed;

        ResponderResult(
                byte[] packet,
                byte[] plaintext,
                byte[] initiatorChildSpi,
                byte[] responderChildSpi) {
            this.packet = packet.clone();
            this.plaintext = plaintext.clone();
            this.initiatorChildSpi =
                    initiatorChildSpi.clone();
            this.responderChildSpi =
                    responderChildSpi.clone();
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(packet);
            wipe(plaintext);
            wipe(initiatorChildSpi);
            wipe(responderChildSpi);
            destroyed = true;
        }
    }

    static final class InitiatorResult {
        final byte[] initiatorChildSpi;
        final byte[] responderChildSpi;
        final AppleNetworkRelayInnerAddresses addresses;
        final ResponderMetadata metadata;
        private boolean destroyed;

        InitiatorResult(
                byte[] initiatorChildSpi,
                byte[] responderChildSpi,
                AppleNetworkRelayInnerAddresses addresses,
                ResponderMetadata metadata) {
            this.initiatorChildSpi =
                    initiatorChildSpi.clone();
            this.responderChildSpi =
                    responderChildSpi.clone();
            this.addresses = addresses;
            this.metadata = metadata;
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(initiatorChildSpi);
            wipe(responderChildSpi);
            addresses.destroy();
            metadata.destroy();
            destroyed = true;
        }
    }

    static final class ResponderMetadata {
        final String deviceName;
        final String buildVersion;
        final String idsDeviceId;
        final int deviceType;
        final boolean keysAfterFirstUnlock;
        final boolean alwaysOnWifi;
        private boolean destroyed;

        ResponderMetadata(
                String deviceName,
                String buildVersion,
                String idsDeviceId,
                int deviceType,
                boolean keysAfterFirstUnlock,
                boolean alwaysOnWifi) {
            this.deviceName = deviceName;
            this.buildVersion = buildVersion;
            this.idsDeviceId = idsDeviceId;
            this.deviceType = deviceType;
            this.keysAfterFirstUnlock =
                    keysAfterFirstUnlock;
            this.alwaysOnWifi = alwaysOnWifi;
        }

        void destroy() {
            destroyed = true;
        }
    }

    private static final class ParsedRequest {
        final byte[] initiatorChildSpi;

        ParsedRequest(
                byte[] initiatorChildSpi) {
            this.initiatorChildSpi =
                    initiatorChildSpi.clone();
        }

        void destroy() {
            wipe(initiatorChildSpi);
        }
    }

    private static final class ParsedPrivateBatch {
        private AppleNetworkRelayInnerAddresses addresses;
        private ResponderMetadata metadata;

        ParsedPrivateBatch(
                AppleNetworkRelayInnerAddresses addresses,
                ResponderMetadata metadata) {
            this.addresses = addresses;
            this.metadata = metadata;
        }

        void releaseOwnership() {
            addresses = null;
            metadata = null;
        }
    }

    private static final class TypedBody {
        final int type;
        final byte[] body;

        TypedBody(
                int type,
                byte[] body) {
            this.type = type;
            this.body = body.clone();
        }

        void destroy() {
            wipe(body);
        }
    }

    private static final class Payload {
        final int type;
        final byte[] body;

        Payload(
                int type,
                byte[] body) {
            this.type = type;
            this.body = body.clone();
        }

        void destroy() {
            wipe(body);
        }
    }

    private static final class Notify {
        final int type;
        final byte[] data;

        Notify(
                int type,
                byte[] data) {
            this.type = type;
            this.data = data.clone();
        }

        void destroy() {
            wipe(data);
        }
    }
}
