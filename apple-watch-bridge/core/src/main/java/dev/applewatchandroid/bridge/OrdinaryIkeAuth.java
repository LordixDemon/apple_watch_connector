package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

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

    static final int CURRENT_TERMINUS_VERSION = 24;
    static final int NOTIFY_ALWAYS_ON_WIFI = 0xc8c9;
    static final int CONFIG_ATTRIBUTE_INITIATOR_IPV6 = 0x6566;
    static final int CONFIG_ATTRIBUTE_RESPONDER_IPV6 = 0x6567;
    static final int CHILD_SPI_LENGTH = 4;
    static final int INTERMEDIATE_AUTH_LENGTH = 64;

    static final byte[] TRAFFIC_SELECTORS_BODY =
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
        OrdinaryIkeAuthWireCodec.requireCommonState(
                dataClass,
                random,
                localMaterial,
                peerMaterial,
                keys,
                initiatorIntAuth,
                responderIntAuth);
        OrdinaryIkeAuthWireCodec.validateInitiatorSaInit(
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
            signature = OrdinaryIkeAuthWireCodec.sign(
                    localMaterial,
                    dataClass,
                    signedOctets);
            authBody =
                    AppleIkeEd25519Auth.buildAuthBody(
                            signature);
            childSpi = OrdinaryIkeAuthWireCodec.randomNonzeroChildSpi(random);
            plaintext =
                    OrdinaryIkeAuthWireCodec.buildInitiatorPlaintext(
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
            OrdinaryIkeAuthWireCodec.wipe(identityUuid);
            OrdinaryIkeAuthWireCodec.wipe(identifierPlaintext);
            OrdinaryIkeAuthWireCodec.wipe(encryptedIdentifier);
            OrdinaryIkeAuthWireCodec.wipe(idiBody);
            OrdinaryIkeAuthWireCodec.wipe(signedOctets);
            OrdinaryIkeAuthWireCodec.wipe(signature);
            OrdinaryIkeAuthWireCodec.wipe(authBody);
            OrdinaryIkeAuthWireCodec.wipe(childSpi);
            OrdinaryIkeAuthWireCodec.wipe(plaintext);
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
        OrdinaryIkeAuthWireCodec.requireCommonState(
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
        OrdinaryIkeAuthWireCodec.validateSaInitTranscript(
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
                    OrdinaryIkeAuthWireCodec.randomNonzeroChildSpi(random);
            idrBody = OrdinaryIkeAuthWireCodec.serviceIdentifierBody(
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
            signature = OrdinaryIkeAuthWireCodec.sign(
                    localMaterial,
                    dataClass,
                    signedOctets);
            authBody =
                    AppleIkeEd25519Auth.buildAuthBody(
                            signature);
            plaintext =
                    OrdinaryIkeAuthWireCodec.buildResponderPlaintext(
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
            OrdinaryIkeAuthWireCodec.wipe(responderChildSpi);
            OrdinaryIkeAuthWireCodec.wipe(idrBody);
            OrdinaryIkeAuthWireCodec.wipe(signedOctets);
            OrdinaryIkeAuthWireCodec.wipe(signature);
            OrdinaryIkeAuthWireCodec.wipe(authBody);
            OrdinaryIkeAuthWireCodec.wipe(plaintext);
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
        OrdinaryIkeAuthWireCodec.requireLength(
                "expected remote prelude",
                expectedRemotePrelude,
                NrLinkBluetoothPrelude.EXACT_ENCODED_LENGTH);
        NrLinkBluetoothPrelude.parse(expectedRemotePrelude);
        OrdinaryIkeAuthWireCodec.validateSaInitTranscript(
                initiatorSaInitPacket,
                responderSaInitPacket,
                keys);
        OrdinaryIkeAuthWireCodec.requireIntermediateAuth(
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
        OrdinaryIkeAuthWireCodec.requireUnfragmented(
                decrypted,
                IkeV2Codec.PAYLOAD_NOTIFY,
                "response");
        List<OrdinaryIkeAuthWireCodec.Payload> payloads =
                OrdinaryIkeAuthWireCodec.parsePayloadChain(
                        decrypted.firstInnerPayload,
                        decrypted.plaintext);
        OrdinaryIkeAuthWireCodec.validateResponderPayloadTypes(payloads);

        byte[] idrBody = payloads.get(1).body.clone();
        byte[] signedOctets = null;
        byte[] responderChildSpi = null;
        AppleNetworkRelayInnerAddresses addresses = null;
        ResponderMetadata metadata = null;
        try {
            OrdinaryIkeAuthWireCodec.validateServiceIdentifier(
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

            OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                    payloads.get(0),
                    NOTIFY_INITIAL_CONTACT);
            byte[][] cpAddresses =
                    OrdinaryIkeAuthWireCodec.parseConfigurationReply(
                            payloads.get(3).body);
            try {
                OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                        payloads.get(4),
                        NOTIFY_USE_TRANSPORT_MODE);
                OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                        payloads.get(5),
                        NOTIFY_ESP_TFC_PADDING_NOT_SUPPORTED);
                OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                        payloads.get(6),
                        NOTIFY_NON_FIRST_FRAGMENTS_ALSO);
                responderChildSpi =
                        OrdinaryIkeAuthWireCodec.parseResponderSa(
                                payloads.get(7).body);
                OrdinaryIkeAuthWireCodec.validateTrafficSelectors(
                        payloads.get(8));
                OrdinaryIkeAuthWireCodec.validateTrafficSelectors(
                        payloads.get(9));
                OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                        payloads.get(10),
                        NOTIFY_SEQUENCE_PER_TRAFFIC_CLASS);

                OrdinaryIkeAuthWireCodec.ParsedPrivateBatch privateBatch =
                        OrdinaryIkeAuthWireCodec.parsePrivateBatch(
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
                        OrdinaryIkeAuthWireCodec.addressFor(
                                addresses,
                                dataClass,
                                true);
                byte[] responderAddress =
                        OrdinaryIkeAuthWireCodec.addressFor(
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
                    OrdinaryIkeAuthWireCodec.wipe(initiatorAddress);
                    OrdinaryIkeAuthWireCodec.wipe(responderAddress);
                }
            } finally {
                OrdinaryIkeAuthWireCodec.wipe(cpAddresses[0]);
                OrdinaryIkeAuthWireCodec.wipe(cpAddresses[1]);
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
            OrdinaryIkeAuthWireCodec.wipe(idrBody);
            OrdinaryIkeAuthWireCodec.wipe(signedOctets);
            OrdinaryIkeAuthWireCodec.wipe(responderChildSpi);
            if (addresses != null) {
                addresses.destroy();
            }
            if (metadata != null) {
                metadata.destroy();
            }
            OrdinaryIkeAuthWireCodec.destroyPayloads(payloads);
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
        OrdinaryIkeAuthWireCodec.requireUnfragmented(
                decrypted,
                PAYLOAD_ID_INITIATOR,
                "request");
        if (decrypted.plaintext.length
                != REQUEST_INNER_LENGTH) {
            throw new IllegalArgumentException(
                    "Ordinary IKE_AUTH request length is invalid");
        }
        List<OrdinaryIkeAuthWireCodec.Payload> payloads =
                OrdinaryIkeAuthWireCodec.parsePayloadChain(
                        decrypted.firstInnerPayload,
                        decrypted.plaintext);
        OrdinaryIkeAuthWireCodec.validateInitiatorPayloadTypes(payloads);

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
            OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                    payloads.get(1),
                    NOTIFY_INITIAL_CONTACT);
            OrdinaryIkeAuthWireCodec.validateServiceIdentifier(
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
            OrdinaryIkeAuthWireCodec.validateConfigurationRequest(
                    payloads.get(4).body);
            OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                    payloads.get(5),
                    NOTIFY_USE_TRANSPORT_MODE);
            OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                    payloads.get(6),
                    NOTIFY_ESP_TFC_PADDING_NOT_SUPPORTED);
            OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                    payloads.get(7),
                    NOTIFY_NON_FIRST_FRAGMENTS_ALSO);
            initiatorChildSpi =
                    OrdinaryIkeAuthWireCodec.parseInitiatorSa(
                            payloads.get(8).body);
            OrdinaryIkeAuthWireCodec.validateTrafficSelectors(
                    payloads.get(9));
            OrdinaryIkeAuthWireCodec.validateTrafficSelectors(
                    payloads.get(10));
            OrdinaryIkeAuthWireCodec.validateEmptyNotify(
                    payloads.get(11),
                    NOTIFY_SEQUENCE_PER_TRAFFIC_CLASS);
            return new ParsedRequest(
                    initiatorChildSpi);
        } finally {
            OrdinaryIkeAuthWireCodec.wipe(encryptedIdentifier);
            OrdinaryIkeAuthWireCodec.wipe(identifierPlaintext);
            OrdinaryIkeAuthWireCodec.wipe(expectedLocalIdentityUuid);
            OrdinaryIkeAuthWireCodec.wipe(identityUuid);
            OrdinaryIkeAuthWireCodec.wipe(signedOctets);
            OrdinaryIkeAuthWireCodec.wipe(initiatorChildSpi);
            OrdinaryIkeAuthWireCodec.destroyPayloads(payloads);
        }
    }

    static String describeResponderPayloadStructure(byte[] plaintext) {
        return OrdinaryIkeAuthWireCodec.describeResponderPayloadStructure(plaintext);
    }













































    static final class PeerMaterial {
        private final byte[] identityUuid;
        private final byte[] identityPublicX25519;
        private final byte[] classDPublicEd25519;
        private final byte[] classDPublicX25519;
        private final byte[] classCPublicEd25519;
        private final byte[] classCPublicX25519;
        private boolean destroyed;

        PeerMaterial(
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
            OrdinaryIkeAuthWireCodec.requireLength(
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
                OrdinaryIkeAuthWireCodec.wipe(identityUuid);
                OrdinaryIkeAuthWireCodec.wipe(identityPublic);
                OrdinaryIkeAuthWireCodec.wipe(classD[0]);
                OrdinaryIkeAuthWireCodec.wipe(classD[1]);
                if (classC != null) {
                    OrdinaryIkeAuthWireCodec.wipe(classC[0]);
                    OrdinaryIkeAuthWireCodec.wipe(classC[1]);
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

        byte[] identityUuid() {
            requireLive();
            return identityUuid.clone();
        }

        byte[] publicEd25519(
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
            OrdinaryIkeAuthWireCodec.requireLength(
                    "peer " + label + " public-key payload",
                    payload,
                    AppleNetworkRelayPairingMaterial
                            .MODERN_PUBLIC_KEYS_PAYLOAD_LENGTH);
            if (payload[0] != 1
                    || OrdinaryIkeAuthWireCodec.be16(payload, 1) != 32
                    || payload[35] != 2
                    || OrdinaryIkeAuthWireCodec.be16(payload, 36) != 32) {
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
            OrdinaryIkeAuthWireCodec.wipe(identityUuid);
            OrdinaryIkeAuthWireCodec.wipe(identityPublicX25519);
            OrdinaryIkeAuthWireCodec.wipe(classDPublicEd25519);
            OrdinaryIkeAuthWireCodec.wipe(classDPublicX25519);
            OrdinaryIkeAuthWireCodec.wipe(classCPublicEd25519);
            OrdinaryIkeAuthWireCodec.wipe(classCPublicX25519);
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
        final AppleNetworkRelayInnerAddresses addresses;
        final byte[] localPrelude;
        final byte[] deviceName;
        final byte[] buildVersion;
        final byte[] idsDeviceId;
        final int deviceType;
        final boolean keysAfterFirstUnlock;
        final boolean alwaysOnWifi;
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
            OrdinaryIkeAuthWireCodec.requireLength(
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
                OrdinaryIkeAuthWireCodec.wipe(initiatorD);
                OrdinaryIkeAuthWireCodec.wipe(responderD);
                OrdinaryIkeAuthWireCodec.wipe(initiatorC);
                OrdinaryIkeAuthWireCodec.wipe(responderC);
            }
            this.localPrelude = localPrelude.clone();
            this.deviceName =
                    OrdinaryIkeAuthWireCodec.encodeRequiredUtf8(
                            "responder device name",
                            deviceName);
            this.buildVersion =
                    OrdinaryIkeAuthWireCodec.encodeRequiredUtf8(
                            "responder build version",
                            buildVersion);
            this.idsDeviceId = idsDeviceId == null
                    || idsDeviceId.isEmpty()
                    ? null
                    : OrdinaryIkeAuthWireCodec.encodeRequiredUtf8(
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
                OrdinaryIkeAuthWireCodec.wipe(initiatorD);
                OrdinaryIkeAuthWireCodec.wipe(responderD);
                OrdinaryIkeAuthWireCodec.wipe(initiatorC);
                OrdinaryIkeAuthWireCodec.wipe(responderC);
            }
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            addresses.destroy();
            OrdinaryIkeAuthWireCodec.wipe(localPrelude);
            OrdinaryIkeAuthWireCodec.wipe(deviceName);
            OrdinaryIkeAuthWireCodec.wipe(buildVersion);
            OrdinaryIkeAuthWireCodec.wipe(idsDeviceId);
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
            OrdinaryIkeAuthWireCodec.wipe(packet);
            OrdinaryIkeAuthWireCodec.wipe(plaintext);
            OrdinaryIkeAuthWireCodec.wipe(initiatorChildSpi);
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
            OrdinaryIkeAuthWireCodec.wipe(packet);
            OrdinaryIkeAuthWireCodec.wipe(plaintext);
            OrdinaryIkeAuthWireCodec.wipe(initiatorChildSpi);
            OrdinaryIkeAuthWireCodec.wipe(responderChildSpi);
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
            OrdinaryIkeAuthWireCodec.wipe(initiatorChildSpi);
            OrdinaryIkeAuthWireCodec.wipe(responderChildSpi);
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
            OrdinaryIkeAuthWireCodec.wipe(initiatorChildSpi);
        }
    }




}
