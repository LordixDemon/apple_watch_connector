package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.rfc7748.X448;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Cryptographic state between the proven control IKE_SA_INIT exchange and the
 * first RFC 9370 ML-KEM-1024 IKE_INTERMEDIATE exchange.
 */
final class IkeV2SessionCrypto {
    static final int PAYLOAD_SK = 46;
    static final int PAYLOAD_SKF = 53;
    static final int EXCHANGE_IKE_INTERMEDIATE = 43;
    static final int EXCHANGE_IKE_AUTH = 35;
    static final int EXCHANGE_INFORMATIONAL = 37;
    static final int ML_KEM_1024_METHOD = 37;
    static final int ML_KEM_1024_PUBLIC_KEY_LENGTH = 1568;
    static final int ML_KEM_1024_CIPHERTEXT_LENGTH = 1568;
    static final int ML_KEM_SHARED_SECRET_LENGTH = 32;
    static final int CONTROL_MAX_IKE_PACKET_SIZE = 1280;
    static final int IKE_AUTH_MESSAGE_ID = 2;
    static final int PAIRING_FIRST_GSPM_MESSAGE_ID = 2;
    static final int PAIRING_SECOND_GSPM_MESSAGE_ID = 3;
    static final int PAIRING_FINAL_AUTH_MESSAGE_ID = 4;
    static final int PAIRING_NOTIFIES_MESSAGE_ID = 5;
    static final int PAIRING_NOTIFIES_WATCH_MESSAGE_ID = 0;
    static final int PIN_METHOD_MESSAGE_ID = 3;
    static final int PIN_METHOD_WATCH_MESSAGE_ID = 0;
    static final int ID_TYPE_KEY_ID = 11;
    static final int ID_TYPE_NULL = 13;
    static final int AUTH_METHOD_NULL = 13;
    static final int AUTH_METHOD_GENERIC_SECURE_PASSWORD = 12;
    static final int NOTIFY_INITIAL_CONTACT = 0x4000;
    static final int NOTIFY_PPK_IDENTITY = 0x4034;
    static final int NOTIFY_NO_PPK_AUTH = 0x4035;
    static final int PRIVATE_NOTIFY_AUTH_METHOD_REQUEST = 0xC545;
    static final int PRIVATE_NOTIFY_AUTH_METHOD_RESPONSE = 0xC546;
    static final int PAIRING_AUTH_METHOD_PIN = 2;
    static final int PAYLOAD_GSPM = 49;
    static final String CONTROL_PAIRING_KEY_ID =
            "com.apple.networkrelay.companionlink.pairing.control";

    private static final int IKE_HEADER_LENGTH = 28;
    private static final int GENERIC_PAYLOAD_HEADER_LENGTH = 4;
    private static final int FRAGMENT_FIELDS_LENGTH = 4;
    private static final int AES_GCM_EXPLICIT_IV_LENGTH = 8;
    private static final int AES_GCM_TAG_LENGTH = 16;
    private static final int AES_256_KEY_LENGTH = 32;
    private static final int AES_GCM_SALT_LENGTH = 4;
    private static final int PRF_SHA512_LENGTH = 64;
    private static final int IKE_FLAG_INITIATOR = 0x08;
    private static final int IKE_FLAG_RESPONSE = 0x20;
    private static final int PAYLOAD_ID_INITIATOR = 35;
    private static final int PAYLOAD_ID_RESPONDER = 36;
    private static final int PAYLOAD_AUTH = 39;
    private static final int PAYLOAD_NOTIFY = 41;
    private static final int PAYLOAD_SA = 33;
    private static final int PAYLOAD_TS_INITIATOR = 44;
    private static final int PAYLOAD_TS_RESPONDER = 45;
    private static final int NRTLV_AUTH_METHOD = 1;
    private static final int NRTLV_PIN_SALT = 2;
    private static final int MINIMUM_PIN_SALT_LENGTH = 32;
    private static final byte[] NULL_AUTH_PAD =
            "Key Pad for IKEv2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTROL_PAIRING_KEY_ID_BYTES =
            CONTROL_PAIRING_KEY_ID.getBytes(StandardCharsets.UTF_8);

    private IkeV2SessionCrypto() {
    }

    static ControlSaInitResponse parseControlSaInitResponse(
            byte[] packet,
            byte[] expectedInitiatorSpi) {
        return parseSaInitResponse(
                packet,
                expectedInitiatorSpi,
                false);
    }

    static ControlSaInitResponse parsePairingSaInitResponse(
            byte[] packet,
            byte[] expectedInitiatorSpi) {
        return parseSaInitResponse(
                packet,
                expectedInitiatorSpi,
                true);
    }

    static ControlSaInitResponse parseOpticalPairingSaInitResponse(
            byte[] packet, byte[] expectedInitiatorSpi) {
        ControlSaInitResponse response = parseSaInitResponse(packet, expectedInitiatorSpi, false);
        IkeV2Codec.IkePacketSummary summary =
                IkeV2Codec.parseSaInitResponse(packet, expectedInitiatorSpi);
        if (summary.notifyTypes.contains(IkeV2Codec.NOTIFY_SECURE_PASSWORD_METHODS)
                || summary.notifyTypes.contains(IkeV2Codec.NOTIFY_USE_PPK)) {
            throw new IllegalArgumentException("Watch selected a PIN profile for optical PSK pairing");
        }
        return response;
    }

    private static ControlSaInitResponse parseSaInitResponse(
            byte[] packet,
            byte[] expectedInitiatorSpi,
            boolean pairing) {
        IkeV2Codec.IkePacketSummary summary =
                IkeV2Codec.parseSaInitResponse(
                        packet,
                        expectedInitiatorSpi);

        byte[] responderNonce = null;
        byte[] responderX448 = null;
        boolean selectedProposalSeen = false;
        boolean securePasswordSelected = false;
        boolean ppkSupported = false;
        int payloadType = unsigned(packet[16]);
        int offset = IKE_HEADER_LENGTH;
        while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
            int nextPayload = unsigned(packet[offset]);
            int payloadLength = be16(packet, offset + 2);
            if (payloadType == IkeV2Codec.PAYLOAD_SA) {
                parseSelectedControlProposal(
                        packet,
                        offset,
                        payloadLength);
                selectedProposalSeen = true;
            } else if (payloadType == IkeV2Codec.PAYLOAD_KE) {
                if (payloadLength != 8 + 56
                        || be16(packet, offset + 4)
                        != IkeV2Codec.DH_GROUP_CURVE_448
                        || be16(packet, offset + 6) != 0) {
                    throw new IllegalArgumentException(
                            "Unexpected control response KE payload");
                }
                responderX448 = Arrays.copyOfRange(
                        packet,
                        offset + 8,
                        offset + payloadLength);
            } else if (payloadType == IkeV2Codec.PAYLOAD_NONCE) {
                if (payloadLength != 4 + 32) {
                    throw new IllegalArgumentException(
                            "Unexpected control response nonce length");
                }
                responderNonce = Arrays.copyOfRange(
                        packet,
                        offset + 4,
                        offset + payloadLength);
            } else if (payloadType == IkeV2Codec.PAYLOAD_NOTIFY) {
                int protocolId = unsigned(packet[offset + 4]);
                int spiSize = unsigned(packet[offset + 5]);
                int notifyType = be16(packet, offset + 6);
                boolean physicalWatchChildlessQuirk =
                        notifyType
                                == IkeV2Codec
                                .NOTIFY_CHILDLESS_IKEV2_SUPPORTED
                                && protocolId == 1;
                if ((!physicalWatchChildlessQuirk
                        && protocolId != 0)
                        || spiSize != 0) {
                    throw new IllegalArgumentException(
                            "Unexpected IKE_SA_INIT notify SPI");
                }
                if (notifyType
                        == IkeV2Codec.NOTIFY_SECURE_PASSWORD_METHODS) {
                    if (protocolId != 0
                            || payloadLength != 10
                            || be16(packet, offset + 8)
                            != IkeV2Codec.SECURE_PASSWORD_SPAKE2_PLUS) {
                        throw new IllegalArgumentException(
                                "Watch selected an unexpected "
                                        + "secure-password method");
                    }
                    securePasswordSelected = true;
                } else if (notifyType
                        == IkeV2Codec.NOTIFY_USE_PPK) {
                    if (protocolId != 0
                            || payloadLength != 8) {
                        throw new IllegalArgumentException(
                                "Watch returned malformed USE_PPK");
                    }
                    ppkSupported = true;
                }
            }
            offset += payloadLength;
            payloadType = nextPayload;
        }
        if (!selectedProposalSeen
                || responderX448 == null
                || responderNonce == null
                || (pairing && !securePasswordSelected)
                || (pairing && !ppkSupported)
                || !summary.notifyTypes.contains(
                IkeV2Codec.NOTIFY_CHILDLESS_IKEV2_SUPPORTED)
                || !summary.notifyTypes.contains(
                IkeV2Codec.NOTIFY_INTERMEDIATE_EXCHANGE_SUPPORTED)) {
            throw new IllegalArgumentException(
                    (pairing ? "Pairing" : "Control")
                            + " response lacks selected SA, X448, nonce, "
                            + (pairing
                            ? "SPAKE2+, PPK, "
                            : "")
                            + "childless-IKEv2 support, or "
                            + "intermediate-exchange support");
        }
        return new ControlSaInitResponse(
                summary.responderSpi,
                responderNonce,
                responderX448,
                packet);
    }

    static IkeSaKeys deriveInitialKeys(
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response) {
        if (initiator == null || response == null) {
            throw new IllegalArgumentException(
                    "IKE initiator and response are required");
        }
        return deriveInitialX448Keys(
                initiator.x448PrivateKey,
                response.responderX448PublicKey,
                initiator.initiatorSpi,
                response.responderSpi,
                initiator.nonce,
                response.responderNonce);
    }

    static IkeSaKeys deriveInitialX448Keys(
            byte[] localX448PrivateKey,
            byte[] peerX448PublicKey,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] initiatorNonce,
            byte[] responderNonce) {
        requireLength(
                "local X448 private key",
                localX448PrivateKey,
                56);
        requireLength(
                "peer X448 public key",
                peerX448PublicKey,
                56);
        requireLength("initiator SPI", initiatorSpi, 8);
        requireLength("responder SPI", responderSpi, 8);
        requireLength("initiator nonce", initiatorNonce, 32);
        requireLength("responder nonce", responderNonce, 32);
        byte[] sharedSecret = new byte[56];
        byte[] nonces = null;
        byte[] skeyseed = null;
        try {
            if (!X448.calculateAgreement(
                    localX448PrivateKey,
                    0,
                    peerX448PublicKey,
                    0,
                    sharedSecret,
                    0)) {
                throw new IllegalArgumentException(
                        "Peer supplied an invalid X448 public key");
            }
            nonces = concatenate(
                    initiatorNonce,
                    responderNonce);
            skeyseed = prf(nonces, sharedSecret);
            return expandKeys(
                    skeyseed,
                    initiatorSpi,
                    responderSpi,
                    initiatorNonce,
                    responderNonce);
        } finally {
            wipe(sharedSecret);
            wipe(nonces);
            wipe(skeyseed);
        }
    }

    static AdditionalKeRequest createAdditionalKeRequest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys initialKeys) {
        if (random == null
                || initiator == null
                || response == null
                || initialKeys == null) {
            throw new IllegalArgumentException(
                    "Complete IKE state is required");
        }
        MLKEMKeyPairGenerator generator =
                new MLKEMKeyPairGenerator();
        generator.init(new MLKEMKeyGenerationParameters(
                random,
                MLKEMParameters.ml_kem_1024));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        MLKEMPublicKeyParameters publicKey =
                (MLKEMPublicKeyParameters) pair.getPublic();
        MLKEMPrivateKeyParameters privateKey =
                (MLKEMPrivateKeyParameters) pair.getPrivate();
        byte[] encodedPublicKey = publicKey.getEncoded();
        requireLength(
                "ML-KEM-1024 public key",
                encodedPublicKey,
                ML_KEM_1024_PUBLIC_KEY_LENGTH);

        byte[] kePayload =
                buildKePayload(encodedPublicKey);
        byte[] intAuthI = computeIntermediateIntAuth(
                initialKeys,
                false,
                IkeV2Codec.PAYLOAD_KE,
                0,
                kePayload);
        List<byte[]> packets = encryptIntermediatePayload(
                random,
                initiator.initiatorSpi,
                response.responderSpi,
                initialKeys.skEi,
                false,
                kePayload,
                IkeV2Codec.PAYLOAD_KE,
                CONTROL_MAX_IKE_PACKET_SIZE);
        return new AdditionalKeRequest(
                privateKey,
                encodedPublicKey,
                packets,
                intAuthI);
    }

    static AdditionalKeResult completeAdditionalKeyExchange(
            AdditionalKeRequest request,
            byte[] responderCiphertext,
            IkeSaKeys initialKeys) {
        if (request == null || initialKeys == null) {
            throw new IllegalArgumentException(
                    "Additional-KE state is required");
        }
        requireLength(
                "ML-KEM-1024 ciphertext",
                responderCiphertext,
                ML_KEM_1024_CIPHERTEXT_LENGTH);
        MLKEMExtractor extractor =
                new MLKEMExtractor(request.privateKey);
        byte[] sharedSecret =
                extractor.extractSecret(responderCiphertext);
        requireLength(
                "ML-KEM-1024 shared secret",
                sharedSecret,
                ML_KEM_SHARED_SECRET_LENGTH);

        IkeSaKeys updatedKeys =
                updateKeysWithAdditionalSecret(
                        initialKeys,
                        sharedSecret);
        return new AdditionalKeResult(
                sharedSecret,
                updatedKeys);
    }

    static IkeSaKeys updateKeysWithAdditionalSecret(
            IkeSaKeys initialKeys,
            byte[] additionalSharedSecret) {
        if (initialKeys == null) {
            throw new IllegalArgumentException(
                    "Initial IKE keys are required");
        }
        requireLength(
                "additional shared secret",
                additionalSharedSecret,
                ML_KEM_SHARED_SECRET_LENGTH);
        byte[] updateInput = null;
        byte[] updatedSkeyseed = null;
        try {
            updateInput = concatenate(
                    additionalSharedSecret,
                    initialKeys.initiatorNonce,
                    initialKeys.responderNonce);
            updatedSkeyseed = prf(
                    initialKeys.skD,
                    updateInput);
            return expandKeys(
                    updatedSkeyseed,
                    initialKeys.initiatorSpi,
                    initialKeys.responderSpi,
                    initialKeys.initiatorNonce,
                    initialKeys.responderNonce);
        } finally {
            wipe(updateInput);
            wipe(updatedSkeyseed);
        }
    }

    /**
     * Applies the mandatory salted-PIN PPK after the additional key exchange.
     * Encryption keys are unchanged; only SK_d, SK_pi, and SK_pr are replaced.
     */
    static IkeSaKeys applyMandatoryPairingPpk(
            IkeSaKeys primeKeys,
            byte[] ppk) {
        if (primeKeys == null) {
            throw new IllegalArgumentException(
                    "Prime IKE SA keys are required");
        }
        byte[] skD = null;
        byte[] skPi = null;
        byte[] skPr = null;
        try {
            skD = AppleWatchPairingCrypto.deriveKeyFromPrimeKey(
                    ppk,
                    primeKeys.skD);
            skPi = AppleWatchPairingCrypto.deriveKeyFromPrimeKey(
                    ppk,
                    primeKeys.skPi);
            skPr = AppleWatchPairingCrypto.deriveKeyFromPrimeKey(
                    ppk,
                    primeKeys.skPr);
            return new IkeSaKeys(
                    skD,
                    primeKeys.skEi,
                    primeKeys.skEr,
                    skPi,
                    skPr,
                    primeKeys.initiatorSpi,
                    primeKeys.responderSpi,
                    primeKeys.initiatorNonce,
                    primeKeys.responderNonce);
        } finally {
            wipe(skD);
            wipe(skPi);
            wipe(skPr);
        }
    }

    static List<byte[]> encryptIntermediatePayload(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            boolean response,
            byte[] innerPayloadBytes,
            int firstInnerPayload,
            int maximumPacketSize) {
        return encryptProtectedPayload(
                random,
                initiatorSpi,
                responderSpi,
                encryptionKeyMaterial,
                response,
                EXCHANGE_IKE_INTERMEDIATE,
                1,
                innerPayloadBytes,
                firstInnerPayload,
                maximumPacketSize);
    }

    static List<byte[]> encryptProtectedPayload(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            boolean response,
            int exchangeType,
            int messageId,
            byte[] innerPayloadBytes,
            int firstInnerPayload,
            int maximumPacketSize) {
        return encryptProtectedPayloadWithDirectionFlags(
                random,
                initiatorSpi,
                responderSpi,
                encryptionKeyMaterial,
                response
                        ? IKE_FLAG_RESPONSE
                        : IKE_FLAG_INITIATOR,
                exchangeType,
                messageId,
                innerPayloadBytes,
                firstInnerPayload,
                maximumPacketSize);
    }

    static byte[] encryptEmptyProtectedPayload(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            boolean response,
            int exchangeType,
            int messageId) {
        return encryptEmptyProtectedPayloadWithDirectionFlags(
                random,
                initiatorSpi,
                responderSpi,
                encryptionKeyMaterial,
                response
                        ? IKE_FLAG_RESPONSE
                        : IKE_FLAG_INITIATOR,
                exchangeType,
                messageId);
    }

    static byte[] encryptEmptyProtectedPayloadWithDirectionFlags(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            int directionFlags,
            int exchangeType,
            int messageId) {
        requireLength("initiator SPI", initiatorSpi, 8);
        requireLength("responder SPI", responderSpi, 8);
        requireLength(
                "AES-GCM-256 key material",
                encryptionKeyMaterial,
                AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH);
        if (random == null
                || exchangeType <= 0
                || messageId < 0
                || (directionFlags
                & ~(IKE_FLAG_INITIATOR | IKE_FLAG_RESPONSE)) != 0) {
            throw new IllegalArgumentException(
                    "Empty protected exchange state is invalid");
        }
        byte[] iv = new byte[AES_GCM_EXPLICIT_IV_LENGTH];
        random.nextBytes(iv);
        return encryptProtectedPacket(
                initiatorSpi,
                responderSpi,
                directionFlags,
                exchangeType,
                messageId,
                PAYLOAD_SK,
                IkeV2Codec.PAYLOAD_NONE,
                0,
                0,
                new byte[0],
                encryptionKeyMaterial,
                iv);
    }

    static List<byte[]>
            encryptProtectedPayloadWithDirectionFlags(
            SecureRandom random,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptionKeyMaterial,
            int directionFlags,
            int exchangeType,
            int messageId,
            byte[] innerPayloadBytes,
            int firstInnerPayload,
            int maximumPacketSize) {
        requireLength("initiator SPI", initiatorSpi, 8);
        requireLength("responder SPI", responderSpi, 8);
        requireLength(
                "AES-GCM-256 key material",
                encryptionKeyMaterial,
                AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH);
        if (random == null
                || innerPayloadBytes == null
                || innerPayloadBytes.length == 0
                || firstInnerPayload == IkeV2Codec.PAYLOAD_NONE
                || exchangeType <= 0
                || messageId < 0
                || (directionFlags
                & ~(IKE_FLAG_INITIATOR | IKE_FLAG_RESPONSE)) != 0) {
            throw new IllegalArgumentException(
                    "Protected plaintext, exchange, message ID, "
                            + "direction, and randomness are required");
        }
        int unfragmentedLength =
                IKE_HEADER_LENGTH
                        + GENERIC_PAYLOAD_HEADER_LENGTH
                        + AES_GCM_EXPLICIT_IV_LENGTH
                        + innerPayloadBytes.length
                        + 1
                        + AES_GCM_TAG_LENGTH;
        if (unfragmentedLength <= maximumPacketSize) {
            byte[] iv = new byte[AES_GCM_EXPLICIT_IV_LENGTH];
            random.nextBytes(iv);
            return List.of(encryptProtectedPacket(
                    initiatorSpi,
                    responderSpi,
                    directionFlags,
                    exchangeType,
                    messageId,
                    PAYLOAD_SK,
                    firstInnerPayload,
                    0,
                    0,
                    innerPayloadBytes,
                    encryptionKeyMaterial,
                    iv));
        }

        int fragmentContentLimit =
                maximumPacketSize
                        - IKE_HEADER_LENGTH
                        - GENERIC_PAYLOAD_HEADER_LENGTH
                        - FRAGMENT_FIELDS_LENGTH
                        - AES_GCM_EXPLICIT_IV_LENGTH
                        - 1
                        - AES_GCM_TAG_LENGTH;
        if (fragmentContentLimit <= 0) {
            throw new IllegalArgumentException(
                    "IKE maximum packet size is too small");
        }
        int totalFragments =
                (innerPayloadBytes.length
                        + fragmentContentLimit
                        - 1)
                        / fragmentContentLimit;
        List<byte[]> packets = new ArrayList<>(totalFragments);
        int offset = 0;
        for (int fragmentNumber = 1;
                fragmentNumber <= totalFragments;
                fragmentNumber++) {
            int end = Math.min(
                    innerPayloadBytes.length,
                    offset + fragmentContentLimit);
            byte[] chunk = Arrays.copyOfRange(
                    innerPayloadBytes,
                    offset,
                    end);
            byte[] iv = new byte[AES_GCM_EXPLICIT_IV_LENGTH];
            random.nextBytes(iv);
            packets.add(encryptProtectedPacket(
                    initiatorSpi,
                    responderSpi,
                    directionFlags,
                    exchangeType,
                    messageId,
                    PAYLOAD_SKF,
                    fragmentNumber == 1
                            ? firstInnerPayload
                            : IkeV2Codec.PAYLOAD_NONE,
                    fragmentNumber,
                    totalFragments,
                    chunk,
                    encryptionKeyMaterial,
                    iv));
            offset = end;
        }
        return List.copyOf(packets);
    }

    private static byte[] encryptProtectedPacket(
            byte[] initiatorSpi,
            byte[] responderSpi,
            int directionFlags,
            int exchangeType,
            int messageId,
            int protectedPayloadType,
            int firstInnerPayload,
            int fragmentNumber,
            int totalFragments,
            byte[] plaintextChunk,
            byte[] encryptionKeyMaterial,
            byte[] explicitIv) {
        int subHeaderLength =
                GENERIC_PAYLOAD_HEADER_LENGTH
                        + (protectedPayloadType == PAYLOAD_SKF
                        ? FRAGMENT_FIELDS_LENGTH
                        : 0);
        byte[] paddedPlaintext =
                Arrays.copyOf(plaintextChunk, plaintextChunk.length + 1);
        paddedPlaintext[paddedPlaintext.length - 1] = 0;
        int protectedPayloadLength =
                subHeaderLength
                        + AES_GCM_EXPLICIT_IV_LENGTH
                        + paddedPlaintext.length
                        + AES_GCM_TAG_LENGTH;
        int packetLength =
                IKE_HEADER_LENGTH + protectedPayloadLength;

        ByteArrayOutputStream authenticatedHeaders =
                new ByteArrayOutputStream(
                        IKE_HEADER_LENGTH + subHeaderLength);
        authenticatedHeaders.writeBytes(initiatorSpi);
        authenticatedHeaders.writeBytes(responderSpi);
        authenticatedHeaders.write(protectedPayloadType);
        authenticatedHeaders.write(0x20);
        authenticatedHeaders.write(exchangeType);
        authenticatedHeaders.write(directionFlags);
        writeBe32(authenticatedHeaders, messageId);
        writeBe32(authenticatedHeaders, packetLength);
        authenticatedHeaders.write(firstInnerPayload);
        authenticatedHeaders.write(0);
        writeBe16(authenticatedHeaders, protectedPayloadLength);
        if (protectedPayloadType == PAYLOAD_SKF) {
            writeBe16(authenticatedHeaders, fragmentNumber);
            writeBe16(authenticatedHeaders, totalFragments);
        }

        byte[] aad = authenticatedHeaders.toByteArray();
        byte[] ciphertextAndTag = aesGcm(
                true,
                encryptionKeyMaterial,
                explicitIv,
                aad,
                paddedPlaintext);
        ByteArrayOutputStream packet =
                new ByteArrayOutputStream(packetLength);
        packet.writeBytes(aad);
        packet.writeBytes(explicitIv);
        packet.writeBytes(ciphertextAndTag);
        if (packet.size() != packetLength) {
            throw new IllegalStateException(
                    "Encrypted IKE packet length mismatch");
        }
        return packet.toByteArray();
    }

    static DecryptedIntermediatePart decryptProtectedIntermediate(
            byte[] packet,
            byte[] expectedInitiatorSpi,
            byte[] expectedResponderSpi,
            byte[] encryptionKeyMaterial,
            boolean response) {
        return decryptProtectedPacket(
                packet,
                expectedInitiatorSpi,
                expectedResponderSpi,
                encryptionKeyMaterial,
                response,
                EXCHANGE_IKE_INTERMEDIATE,
                1);
    }

    static DecryptedIntermediatePart decryptProtectedPacket(
            byte[] packet,
            byte[] expectedInitiatorSpi,
            byte[] expectedResponderSpi,
            byte[] encryptionKeyMaterial,
            boolean response,
            int expectedExchangeType,
            int expectedMessageId) {
        return decryptProtectedPacketWithDirectionFlags(
                packet,
                expectedInitiatorSpi,
                expectedResponderSpi,
                encryptionKeyMaterial,
                response
                        ? IKE_FLAG_RESPONSE
                        : IKE_FLAG_INITIATOR,
                expectedExchangeType,
                expectedMessageId);
    }

    static DecryptedIntermediatePart
            decryptProtectedPacketWithDirectionFlags(
            byte[] packet,
            byte[] expectedInitiatorSpi,
            byte[] expectedResponderSpi,
            byte[] encryptionKeyMaterial,
            int expectedDirectionFlags,
            int expectedExchangeType,
            int expectedMessageId) {
        requireLength("initiator SPI", expectedInitiatorSpi, 8);
        requireLength("responder SPI", expectedResponderSpi, 8);
        requireLength(
                "AES-GCM-256 key material",
                encryptionKeyMaterial,
                AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH);
        if (packet == null || packet.length < IKE_HEADER_LENGTH + 4) {
            throw new IllegalArgumentException(
                    "Protected IKE packet is truncated");
        }
        boolean initiatorSpiMatches =
                matchesRange(
                        packet,
                        0,
                        expectedInitiatorSpi);
        boolean responderSpiMatches =
                matchesRange(
                        packet,
                        8,
                        expectedResponderSpi);
        int version = unsigned(packet[17]);
        int exchangeType = unsigned(packet[18]);
        int messageId = be32(packet, 20);
        int declaredLength = be32(packet, 24);
        if (!initiatorSpiMatches
                || !responderSpiMatches
                || version != 0x20
                || exchangeType != expectedExchangeType
                || messageId != expectedMessageId
                || declaredLength != packet.length
                || (expectedDirectionFlags
                & ~(IKE_FLAG_INITIATOR | IKE_FLAG_RESPONSE)) != 0) {
            throw new IllegalArgumentException(
                    "Unexpected protected IKE header: "
                            + "initiatorSpiMatch="
                            + initiatorSpiMatches
                            + " responderSpiMatch="
                            + responderSpiMatches
                            + " version="
                            + version
                            + " exchange="
                            + exchangeType
                            + " expectedExchange="
                            + expectedExchangeType
                            + " messageId="
                            + messageId
                            + " expectedMessageId="
                            + expectedMessageId
                            + " declaredLength="
                            + declaredLength
                            + " actualLength="
                            + packet.length
                            + "; SPI values and packet bytes logged=false");
        }
        int flags = unsigned(packet[19]);
        if ((flags & (IKE_FLAG_INITIATOR | IKE_FLAG_RESPONSE))
                != expectedDirectionFlags) {
            throw new IllegalArgumentException(
                    "Unexpected protected IKE direction flags");
        }

        int protectedPayloadType = unsigned(packet[16]);
        if (protectedPayloadType != PAYLOAD_SK
                && protectedPayloadType != PAYLOAD_SKF) {
            throw new IllegalArgumentException(
                    "IKE packet is not encrypted");
        }
        int payloadOffset = IKE_HEADER_LENGTH;
        int payloadLength = be16(packet, payloadOffset + 2);
        if (payloadOffset + payloadLength != packet.length) {
            throw new IllegalArgumentException(
                    "Protected payload length mismatch");
        }
        int firstInnerPayload = unsigned(packet[payloadOffset]);
        int protectedPayloadFlags = unsigned(packet[payloadOffset + 1]);
        int fragmentNumber = 0;
        int totalFragments = 0;
        int subHeaderLength = GENERIC_PAYLOAD_HEADER_LENGTH;
        if (protectedPayloadType == PAYLOAD_SKF) {
            if (payloadLength
                    < GENERIC_PAYLOAD_HEADER_LENGTH
                    + FRAGMENT_FIELDS_LENGTH
                    + AES_GCM_EXPLICIT_IV_LENGTH
                    + 1
                    + AES_GCM_TAG_LENGTH) {
                throw new IllegalArgumentException(
                        "Encrypted fragment is truncated");
            }
            fragmentNumber = be16(
                    packet,
                    payloadOffset + GENERIC_PAYLOAD_HEADER_LENGTH);
            totalFragments = be16(
                    packet,
                    payloadOffset
                            + GENERIC_PAYLOAD_HEADER_LENGTH
                            + 2);
            if (fragmentNumber == 0
                    || totalFragments == 0
                    || fragmentNumber > totalFragments
                    || (fragmentNumber == 1
                    && firstInnerPayload
                    == IkeV2Codec.PAYLOAD_NONE)
                    || (fragmentNumber > 1
                    && firstInnerPayload
                    != IkeV2Codec.PAYLOAD_NONE)) {
                throw new IllegalArgumentException(
                        "Invalid IKE fragment numbering or next payload");
            }
            subHeaderLength += FRAGMENT_FIELDS_LENGTH;
        }
        int ivOffset = payloadOffset + subHeaderLength;
        if (ivOffset
                + AES_GCM_EXPLICIT_IV_LENGTH
                + 1
                + AES_GCM_TAG_LENGTH > packet.length) {
            throw new IllegalArgumentException(
                    "Encrypted payload body is truncated");
        }
        byte[] aad = Arrays.copyOfRange(packet, 0, ivOffset);
        byte[] explicitIv = Arrays.copyOfRange(
                packet,
                ivOffset,
                ivOffset + AES_GCM_EXPLICIT_IV_LENGTH);
        byte[] ciphertextAndTag = Arrays.copyOfRange(
                packet,
                ivOffset + AES_GCM_EXPLICIT_IV_LENGTH,
                packet.length);
        byte[] paddedPlaintext = aesGcm(
                false,
                encryptionKeyMaterial,
                explicitIv,
                aad,
                ciphertextAndTag);
        if (paddedPlaintext.length < 1) {
            throw new IllegalArgumentException(
                    "Encrypted payload has no pad-length byte");
        }
        int padLength =
                unsigned(paddedPlaintext[paddedPlaintext.length - 1]);
        if (padLength + 1 > paddedPlaintext.length) {
            throw new IllegalArgumentException(
                    "Encrypted payload padding exceeds plaintext");
        }
        byte[] plaintext = Arrays.copyOf(
                paddedPlaintext,
                paddedPlaintext.length - padLength - 1);
        return new DecryptedIntermediatePart(
                protectedPayloadType == PAYLOAD_SKF,
                fragmentNumber,
                totalFragments,
                firstInnerPayload,
                protectedPayloadFlags,
                plaintext);
    }

    private static byte[] aesGcm(
            boolean encrypt,
            byte[] encryptionKeyMaterial,
            byte[] explicitIv,
            byte[] aad,
            byte[] input) {
        byte[] cipherKey = Arrays.copyOf(
                encryptionKeyMaterial,
                AES_256_KEY_LENGTH);
        byte[] nonce = concatenate(
                Arrays.copyOfRange(
                        encryptionKeyMaterial,
                        AES_256_KEY_LENGTH,
                        AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH),
                explicitIv);
        GCMBlockCipher cipher =
                new GCMBlockCipher(AESEngine.newInstance());
        cipher.init(
                encrypt,
                new AEADParameters(
                        new KeyParameter(cipherKey),
                        AES_GCM_TAG_LENGTH * 8,
                        nonce,
                        aad));
        byte[] output = new byte[cipher.getOutputSize(input.length)];
        int length = cipher.processBytes(
                input,
                0,
                input.length,
                output,
                0);
        try {
            length += cipher.doFinal(output, length);
        } catch (InvalidCipherTextException error) {
            throw new IllegalArgumentException(
                    "IKE AES-GCM authentication failed",
                    error);
        }
        return length == output.length
                ? output
                : Arrays.copyOf(output, length);
    }

    private static IkeSaKeys expandKeys(
            byte[] skeyseed,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] initiatorNonce,
            byte[] responderNonce) {
        byte[] seed = concatenate(
                initiatorNonce,
                responderNonce,
                initiatorSpi,
                responderSpi);
        int totalLength =
                PRF_SHA512_LENGTH
                        + 2 * (AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH)
                        + 2 * PRF_SHA512_LENGTH;
        byte[] keyMaterial =
                prfPlus(skeyseed, seed, totalLength);
        int offset = 0;
        byte[] skD = Arrays.copyOfRange(
                keyMaterial,
                offset,
                offset + PRF_SHA512_LENGTH);
        offset += PRF_SHA512_LENGTH;
        byte[] skEi = Arrays.copyOfRange(
                keyMaterial,
                offset,
                offset + AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH);
        offset += AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH;
        byte[] skEr = Arrays.copyOfRange(
                keyMaterial,
                offset,
                offset + AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH);
        offset += AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH;
        byte[] skPi = Arrays.copyOfRange(
                keyMaterial,
                offset,
                offset + PRF_SHA512_LENGTH);
        offset += PRF_SHA512_LENGTH;
        byte[] skPr = Arrays.copyOfRange(
                keyMaterial,
                offset,
                offset + PRF_SHA512_LENGTH);
        offset += PRF_SHA512_LENGTH;
        if (offset != keyMaterial.length) {
            throw new IllegalStateException(
                    "IKE key material slicing failed");
        }
        return new IkeSaKeys(
                skD,
                skEi,
                skEr,
                skPi,
                skPr,
                initiatorSpi,
                responderSpi,
                initiatorNonce,
                responderNonce);
    }

    private static byte[] prfPlus(
            byte[] key,
            byte[] seed,
            int outputLength) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(outputLength);
        byte[] previous = new byte[0];
        int counter = 1;
        while (output.size() < outputLength) {
            if (counter > 0xFF) {
                throw new IllegalArgumentException(
                        "IKE prf+ output is too long");
            }
            previous = prf(
                    key,
                    concatenate(
                            previous,
                            seed,
                            new byte[]{(byte) counter}));
            output.writeBytes(previous);
            counter++;
        }
        return Arrays.copyOf(output.toByteArray(), outputLength);
    }

    private static byte[] prf(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] output = new byte[hmac.getMacSize()];
        hmac.doFinal(output, 0);
        return output;
    }

    static IkeAuthRequest createControlIkeAuthRequest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        validateIkeAuthState(
                random,
                initiator,
                response,
                updatedKeys,
                intAuthI,
                intAuthR);

        byte[] idiBody = buildIdentificationBody(
                ID_TYPE_NULL,
                new byte[0]);
        byte[] idrBody = buildIdentificationBody(
                ID_TYPE_KEY_ID,
                CONTROL_PAIRING_KEY_ID_BYTES);
        byte[] initiatorSignedOctets = concatenate(
                initiator.ikePacket,
                response.responderNonce,
                prf(updatedKeys.skPi, idiBody),
                buildIkeAuthIntAuth(
                        intAuthI,
                        intAuthR,
                        IKE_AUTH_MESSAGE_ID));
        byte[] authData = computeNullAuthenticationData(
                updatedKeys.skPi,
                initiatorSignedOctets);

        ByteArrayOutputStream plaintext =
                new ByteArrayOutputStream();
        plaintext.writeBytes(genericPayload(
                PAYLOAD_NOTIFY,
                idiBody));
        plaintext.writeBytes(genericPayload(
                PAYLOAD_ID_RESPONDER,
                buildNotifyBody(
                        0,
                        NOTIFY_INITIAL_CONTACT,
                        new byte[0])));
        plaintext.writeBytes(genericPayload(
                PAYLOAD_AUTH,
                idrBody));
        plaintext.writeBytes(genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                buildAuthenticationBody(
                        AUTH_METHOD_NULL,
                        authData)));

        List<byte[]> packets = encryptProtectedPayload(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEi,
                false,
                EXCHANGE_IKE_AUTH,
                IKE_AUTH_MESSAGE_ID,
                plaintext.toByteArray(),
                PAYLOAD_ID_INITIATOR,
                CONTROL_MAX_IKE_PACKET_SIZE);
        return new IkeAuthRequest(
                packets,
                plaintext.toByteArray(),
                authData);
    }

    static List<byte[]> createControlIkeAuthResponseForTest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        validateIkeAuthState(
                random,
                initiator,
                response,
                updatedKeys,
                intAuthI,
                intAuthR);
        byte[] idrBody = buildIdentificationBody(
                ID_TYPE_KEY_ID,
                CONTROL_PAIRING_KEY_ID_BYTES);
        byte[] responderSignedOctets = concatenate(
                response.packet,
                initiator.nonce,
                prf(updatedKeys.skPr, idrBody),
                buildIkeAuthIntAuth(
                        intAuthI,
                        intAuthR,
                        IKE_AUTH_MESSAGE_ID));
        byte[] authData = computeNullAuthenticationData(
                updatedKeys.skPr,
                responderSignedOctets);
        ByteArrayOutputStream plaintext =
                new ByteArrayOutputStream();
        plaintext.writeBytes(genericPayload(
                PAYLOAD_AUTH,
                idrBody));
        plaintext.writeBytes(genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                buildAuthenticationBody(
                        AUTH_METHOD_NULL,
                        authData)));
        return encryptProtectedPayload(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEr,
                true,
                EXCHANGE_IKE_AUTH,
                IKE_AUTH_MESSAGE_ID,
                plaintext.toByteArray(),
                PAYLOAD_ID_RESPONDER,
                CONTROL_MAX_IKE_PACKET_SIZE);
    }

    /**
     * Creates the Apple secure-password initiator after the pairing
     * IKE_INTERMEDIATE exchange and mandatory PPK key transform.
     */
    static PairingGspmInitiator createPairingGspmInitiator(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            byte[] sharedSecret) {
        validateIkeAuthState(
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

        byte[] spakeSalt = concatenate(
                initiator.nonce,
                response.responderNonce);
        byte[] spakeContext = concatenate(
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
            return new PairingGspmInitiator(
                    initiator,
                    response,
                    ppkKeys,
                    intAuthI,
                    intAuthR,
                    prover,
                    initiatorIdBody,
                    responderIdBody);
        } finally {
            wipe(spakeSalt);
            wipe(spakeContext);
            wipe(initiatorIdBody);
            wipe(responderIdBody);
        }
    }

    /**
     * Injects a published-vector Prover without changing the live factory.
     */
    static PairingGspmInitiator
            createPairingGspmInitiatorForTest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            AppleSpake2PlusProver prover) {
        validateIkeAuthState(
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
            return new PairingGspmInitiator(
                    initiator,
                    response,
                    ppkKeys,
                    intAuthI,
                    intAuthR,
                    prover,
                    initiatorIdBody,
                    responderIdBody);
        } finally {
            wipe(initiatorIdBody);
            wipe(responderIdBody);
        }
    }

    static List<byte[]> createPairingFinalAuthResponseForTest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            byte[] firstInitiatorGspmMessage,
            byte[] firstResponderGspmMessage,
            byte[] gspmSessionKey) {
        validateIkeAuthState(
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
            byte[] plaintext = concatenate(
                    genericPayload(
                            PAYLOAD_AUTH,
                            buildNotifyBody(
                                    0,
                                    NOTIFY_INITIAL_CONTACT,
                                    new byte[0])),
                    genericPayload(
                            PAYLOAD_NOTIFY,
                            buildAuthenticationBody(
                                    AUTH_METHOD_GENERIC_SECURE_PASSWORD,
                                    authData)),
                    genericPayload(
                            IkeV2Codec.PAYLOAD_NONE,
                            buildNotifyBody(
                                    0,
                                    NOTIFY_PPK_IDENTITY,
                                    new byte[0])));
            return encryptProtectedPayload(
                    random,
                    ppkKeys.initiatorSpi,
                    ppkKeys.responderSpi,
                    ppkKeys.skEr,
                    true,
                    EXCHANGE_IKE_AUTH,
                    PAIRING_FINAL_AUTH_MESSAGE_ID,
                    plaintext,
                    PAYLOAD_NOTIFY,
                    CONTROL_MAX_IKE_PACKET_SIZE);
        } finally {
            wipe(idBody);
            wipe(authData);
        }
    }

    private static byte[] buildInitialPairingGspmPlaintext(
            byte[] initiatorIdBody,
            byte[] responderIdBody,
            byte[] proverShare) {
        /*
         * NetworkExtension checks PPK_IDENTITY before dispatching the first
         * GSPM message. NO_PPK_AUTH is intentionally absent: Apple skips it
         * when ppkMandatory is true.
         */
        return concatenate(
                genericPayload(
                        PAYLOAD_NOTIFY,
                        initiatorIdBody),
                genericPayload(
                        PAYLOAD_ID_RESPONDER,
                        buildNotifyBody(
                                0,
                                NOTIFY_INITIAL_CONTACT,
                                new byte[0])),
                genericPayload(
                        PAYLOAD_GSPM,
                        responderIdBody),
                genericPayload(
                        PAYLOAD_NOTIFY,
                        proverShare),
                genericPayload(
                        IkeV2Codec.PAYLOAD_NONE,
                        buildNotifyBody(
                                0,
                                NOTIFY_PPK_IDENTITY,
                                AppleWatchPairingCrypto
                                        .fixedPpkIdentityData())));
    }

    private static byte[] buildSingleGspmPlaintext(
            byte[] gspmData) {
        if (gspmData == null || gspmData.length == 0) {
            throw new IllegalArgumentException(
                    "GSPM message is required");
        }
        return genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                gspmData);
    }

    private static byte[] createPairingAuthenticationData(
            boolean responder,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys ppkKeys,
            byte[] intAuthI,
            byte[] intAuthR,
            byte[] idBody,
            byte[] firstInitiatorGspmMessage,
            byte[] firstResponderGspmMessage,
            byte[] gspmSessionKey) {
        requireLength(
                "initiator IntAuth",
                intAuthI,
                PRF_SHA512_LENGTH);
        requireLength(
                "responder IntAuth",
                intAuthR,
                PRF_SHA512_LENGTH);
        requireLength(
                "GSPM session key",
                gspmSessionKey,
                AppleSpake2PlusProver.SHARED_KEY_LENGTH);
        requireLength(
                "initiator SPAKE2+ share",
                firstInitiatorGspmMessage,
                AppleSpake2PlusProver.SHARE_LENGTH);
        requireLength(
                "responder SPAKE2+ share",
                firstResponderGspmMessage,
                AppleSpake2PlusProver.SHARE_LENGTH);
        byte[] primeKey =
                responder ? ppkKeys.skPr : ppkKeys.skPi;
        byte[] signedOctets = concatenate(
                responder ? response.packet : initiator.ikePacket,
                responder
                        ? initiator.nonce
                        : response.responderNonce,
                prf(primeKey, idBody),
                buildIkeAuthIntAuth(
                        intAuthI,
                        intAuthR,
                        /*
                         * NetworkExtension signs firstAuthMessageID, not the
                         * message ID of the later packet carrying AUTH.
                         */
                        PAIRING_FIRST_GSPM_MESSAGE_ID));
        byte[] signedGspmMessages = responder
                ? concatenate(
                        firstResponderGspmMessage,
                        firstInitiatorGspmMessage)
                : concatenate(
                        firstInitiatorGspmMessage,
                        firstResponderGspmMessage);
        // NetworkExtension authenticates the ordered GSPM shares with the
        // active post-PPK SK_p{I,R} and uses the SPAKE2+ session key for the
        // outer AUTH MAC.
        byte[] gspmAuthentication = prf(
                primeKey,
                signedGspmMessages);
        try {
            return prf(
                    gspmSessionKey,
                    concatenate(
                            signedOctets,
                            gspmAuthentication));
        } finally {
            wipe(signedOctets);
            wipe(signedGspmMessages);
            wipe(gspmAuthentication);
        }
    }

    private static byte[] parsePairingGspmResponse(
            DecryptedIntermediatePart part,
            boolean requireResponderId,
            int expectedGspmLength) {
        if (part.fragmented
                || part.plaintext == null
                || part.plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "Pairing GSPM response must be unfragmented");
        }
        int expectedFirstPayload = requireResponderId
                ? PAYLOAD_ID_RESPONDER
                : PAYLOAD_GSPM;
        if (part.firstInnerPayload != expectedFirstPayload) {
            throw new IllegalArgumentException(
                    describePairingProtectedRejection(
                            part,
                            "GSPM"));
        }

        byte[] expectedIdBody =
                AppleWatchPairingCrypto.saltedPinIdPayloadBody();
        byte[] expectedPpkIdentityBody =
                buildNotifyBody(
                        0,
                        NOTIFY_PPK_IDENTITY,
                        AppleWatchPairingCrypto
                                .fixedPpkIdentityData());
        byte[] gspmData = null;
        boolean ppkIdentitySeen = false;
        int payloadType = part.firstInnerPayload;
        int offset = 0;
        int payloadIndex = 0;
        try {
            while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
                if (offset + GENERIC_PAYLOAD_HEADER_LENGTH
                        > part.plaintext.length) {
                    throw new IllegalArgumentException(
                            "Pairing GSPM payload header is truncated");
                }
                int nextPayload =
                        unsigned(part.plaintext[offset]);
                int payloadFlags =
                        unsigned(part.plaintext[offset + 1]);
                int payloadLength =
                        be16(part.plaintext, offset + 2);
                if (payloadLength < GENERIC_PAYLOAD_HEADER_LENGTH
                        || offset + payloadLength
                        > part.plaintext.length
                        || payloadFlags != 0) {
                    throw new IllegalArgumentException(
                            "Pairing GSPM payload is malformed");
                }
                int bodyOffset =
                        offset + GENERIC_PAYLOAD_HEADER_LENGTH;
                int bodyLength =
                        payloadLength
                                - GENERIC_PAYLOAD_HEADER_LENGTH;
                if (requireResponderId && payloadIndex == 0) {
                    if (payloadType != PAYLOAD_ID_RESPONDER
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
                        && payloadType == PAYLOAD_GSPM
                        && bodyLength == expectedGspmLength) {
                    gspmData = Arrays.copyOfRange(
                            part.plaintext,
                            bodyOffset,
                            offset + payloadLength);
                } else if (requireResponderId
                        && payloadIndex == 2
                        && payloadType == PAYLOAD_NOTIFY
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
            wipe(expectedIdBody);
            wipe(expectedPpkIdentityBody);
        }
    }

    /**
     * Describes a short responder error without ever copying or formatting
     * notify data. Apple sends these protected Notify-only responses when it
     * rejects a GSPM state transition, and the numeric type is sufficient to
     * distinguish protocol syntax, PPK, and authentication failures.
     */
    private static String describePairingProtectedRejection(
            DecryptedIntermediatePart part,
            String stage) {
        if (part.firstInnerPayload != PAYLOAD_NOTIFY) {
            return describePairingProtectedPayloadStructure(
                    part,
                    stage);
        }
        byte[] plaintext = part.plaintext;
        if (plaintext == null
                || plaintext.length
                < GENERIC_PAYLOAD_HEADER_LENGTH + 4) {
            return describePairingProtectedPayloadStructure(
                    part,
                    stage);
        }
        int nextPayload = unsigned(plaintext[0]);
        int payloadFlags = unsigned(plaintext[1]);
        int payloadLength = be16(plaintext, 2);
        int protocolId = unsigned(plaintext[4]);
        int spiSize = unsigned(plaintext[5]);
        if (nextPayload != IkeV2Codec.PAYLOAD_NONE
                || payloadFlags != 0
                || payloadLength != plaintext.length
                || payloadLength
                < GENERIC_PAYLOAD_HEADER_LENGTH + 4 + spiSize) {
            return describePairingProtectedPayloadStructure(
                    part,
                    stage);
        }
        int notifyType = be16(plaintext, 6);
        int dataLength = payloadLength
                - GENERIC_PAYLOAD_HEADER_LENGTH
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
            DecryptedIntermediatePart part,
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
            if (offset + GENERIC_PAYLOAD_HEADER_LENGTH
                    > plaintext.length) {
                payloads.add(String.format(
                        Locale.ROOT,
                        "type=%d(truncatedHeader=%d)",
                        payloadType,
                        plaintext.length - offset));
                return prefix + payloads;
            }
            int nextPayload = unsigned(plaintext[offset]);
            int flags = unsigned(plaintext[offset + 1]);
            int payloadLength = be16(plaintext, offset + 2);
            if (payloadLength < GENERIC_PAYLOAD_HEADER_LENGTH
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
                    offset + GENERIC_PAYLOAD_HEADER_LENGTH;
            int bodyLength =
                    payloadLength - GENERIC_PAYLOAD_HEADER_LENGTH;
            if (payloadType == PAYLOAD_NOTIFY) {
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
                    int protocolId = unsigned(plaintext[bodyOffset]);
                    int spiSize = unsigned(plaintext[bodyOffset + 1]);
                    int notifyType = be16(
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
            } else if (payloadType == PAYLOAD_AUTH) {
                int authMethod = bodyLength < 1
                        ? -1
                        : unsigned(plaintext[bodyOffset]);
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

    private static ParsedPairingFinalAuth
            parsePairingFinalAuthResponse(
            DecryptedIntermediatePart part) {
        if (part.fragmented
                || part.plaintext == null
                || part.plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "Pairing AUTH response must be unfragmented");
        }
        if (part.firstInnerPayload != PAYLOAD_NOTIFY) {
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
            if (offset + GENERIC_PAYLOAD_HEADER_LENGTH
                    > part.plaintext.length) {
                throw new IllegalArgumentException(
                        "Pairing AUTH payload header is truncated");
            }
            int nextPayload =
                    unsigned(part.plaintext[offset]);
            int payloadFlags =
                    unsigned(part.plaintext[offset + 1]);
            int payloadLength =
                    be16(part.plaintext, offset + 2);
            if (payloadLength < GENERIC_PAYLOAD_HEADER_LENGTH
                    || offset + payloadLength
                    > part.plaintext.length
                    || payloadFlags != 0) {
                throw new IllegalArgumentException(
                        "Pairing AUTH payload is malformed");
            }
            payloadTypes.add(payloadType);
            int bodyOffset =
                    offset + GENERIC_PAYLOAD_HEADER_LENGTH;
            int bodyLength =
                    payloadLength - GENERIC_PAYLOAD_HEADER_LENGTH;
            if (payloadType == PAYLOAD_NOTIFY
                    && !initialContactSeen
                    && payloadTypes.size() == 1
                    && bodyLength == 4
                    && part.plaintext[bodyOffset] == 0
                    && part.plaintext[bodyOffset + 1] == 0
                    && be16(part.plaintext, bodyOffset + 2)
                    == NOTIFY_INITIAL_CONTACT) {
                initialContactSeen = true;
                notifyTypes.add(NOTIFY_INITIAL_CONTACT);
            } else if (payloadType == PAYLOAD_AUTH
                    && authenticationData == null
                    && bodyLength == 4 + PRF_SHA512_LENGTH
                    && unsigned(part.plaintext[bodyOffset])
                    == AUTH_METHOD_GENERIC_SECURE_PASSWORD
                    && part.plaintext[bodyOffset + 1] == 0
                    && part.plaintext[bodyOffset + 2] == 0
                    && part.plaintext[bodyOffset + 3] == 0) {
                authenticationData = Arrays.copyOfRange(
                        part.plaintext,
                        bodyOffset + 4,
                        offset + payloadLength);
            } else if (payloadType == PAYLOAD_NOTIFY
                    && !ppkIdentitySeen
                    && bodyLength == 4
                    && part.plaintext[bodyOffset] == 0
                    && part.plaintext[bodyOffset + 1] == 0
                    && be16(part.plaintext, bodyOffset + 2)
                    == NOTIFY_PPK_IDENTITY) {
                ppkIdentitySeen = true;
                notifyTypes.add(NOTIFY_PPK_IDENTITY);
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
                        PAYLOAD_NOTIFY,
                        PAYLOAD_AUTH,
                        PAYLOAD_NOTIFY))
                || authenticationData == null
                || !initialContactSeen
                || !ppkIdentitySeen) {
            wipe(authenticationData);
            throw new IllegalArgumentException(
                    "Pairing AUTH lacks secure-password AUTH "
                            + "or mandatory PPK identity");
        }
        return new ParsedPairingFinalAuth(
                authenticationData,
                payloadTypes,
                notifyTypes);
    }

    static PinAuthMethodRequest createPinAuthMethodRequest(
            SecureRandom random,
            IkeSaKeys updatedKeys) {
        if (random == null || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "PIN auth-method request state is required");
        }
        byte[] notifyData = new byte[]{
                NRTLV_AUTH_METHOD,
                0,
                1,
                PAIRING_AUTH_METHOD_PIN
        };
        byte[] plaintext = genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                buildNotifyBody(
                        0,
                        PRIVATE_NOTIFY_AUTH_METHOD_REQUEST,
                        notifyData));
        List<byte[]> packets = encryptProtectedPayload(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEi,
                false,
                EXCHANGE_INFORMATIONAL,
                PIN_METHOD_MESSAGE_ID,
                plaintext,
                PAYLOAD_NOTIFY,
                CONTROL_MAX_IKE_PACKET_SIZE);
        return new PinAuthMethodRequest(
                packets,
                plaintext);
    }

    static List<byte[]> createPinAuthMethodResponseForTest(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            byte[] pinSalt) {
        if (random == null
                || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "PIN auth-method response state is required");
        }
        byte[] plaintext =
                buildPinAuthMethodResponsePlaintext(pinSalt);
        return encryptProtectedPayload(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEr,
                true,
                EXCHANGE_INFORMATIONAL,
                PIN_METHOD_MESSAGE_ID,
                plaintext,
                PAYLOAD_NOTIFY,
                CONTROL_MAX_IKE_PACKET_SIZE);
    }

    static List<byte[]> createPinAuthMethodEmptyResponseForTest(
            SecureRandom random,
            IkeSaKeys updatedKeys) {
        if (random == null || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "PIN auth-method acknowledgement state is required");
        }
        return List.of(encryptEmptyProtectedInformational(
                random,
                updatedKeys,
                updatedKeys.skEr,
                IKE_FLAG_RESPONSE,
                PIN_METHOD_MESSAGE_ID));
    }

    static List<byte[]> createPinAuthMethodWatchRequestForTest(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            byte[] pinSalt) {
        if (random == null || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "Watch PIN auth-method request state is required");
        }
        byte[] plaintext =
                buildPinAuthMethodResponsePlaintext(pinSalt);
        return encryptProtectedPayloadWithDirectionFlags(
                random,
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                updatedKeys.skEr,
                0,
                EXCHANGE_INFORMATIONAL,
                PIN_METHOD_WATCH_MESSAGE_ID,
                plaintext,
                PAYLOAD_NOTIFY,
                CONTROL_MAX_IKE_PACKET_SIZE);
    }

    static List<byte[]> createWatchInformationalAcknowledgement(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            int watchMessageId) {
        if (random == null
                || updatedKeys == null
                || watchMessageId < 0) {
            throw new IllegalArgumentException(
                    "Watch INFORMATIONAL acknowledgement state "
                            + "is required");
        }
        return List.of(encryptEmptyProtectedInformational(
                random,
                updatedKeys,
                updatedKeys.skEi,
                IKE_FLAG_INITIATOR | IKE_FLAG_RESPONSE,
                watchMessageId));
    }

    static PairingPrivateNotifyRequest
            createPairingPrivateNotifyRequest(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        return createPairingPrivateNotifyRequest(random, updatedKeys, notifies,
                PAIRING_NOTIFIES_MESSAGE_ID);
    }

    static PairingPrivateNotifyRequest createPairingPrivateNotifyRequest(
            SecureRandom random, IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify> notifies,
            int messageId) {
        if (random == null
                || updatedKeys == null
                || notifies == null
                || notifies.isEmpty()
                || (messageId != PAIRING_NOTIFIES_MESSAGE_ID
                && messageId != OpticalPskSession.NEXT_INFORMATIONAL_MESSAGE_ID)) {
            throw new IllegalArgumentException(
                    "Pairing private-notify request state is required");
        }
        byte[] plaintext =
                buildPrivateNotifyPlaintext(notifies);
        try {
            List<byte[]> packets =
                    encryptProtectedPayload(
                            random,
                            updatedKeys.initiatorSpi,
                            updatedKeys.responderSpi,
                            updatedKeys.skEi,
                            false,
                            EXCHANGE_INFORMATIONAL,
                            messageId,
                            plaintext,
                            PAYLOAD_NOTIFY,
                            CONTROL_MAX_IKE_PACKET_SIZE);
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
                    wipe(data);
                }
            }
            return new PairingPrivateNotifyRequest(
                    packets,
                    types,
                    lengths);
        } finally {
            wipe(plaintext);
        }
    }

    static List<byte[]>
            createPairingPrivateNotifyEmptyResponseForTest(
            SecureRandom random,
            IkeSaKeys updatedKeys) {
        if (random == null || updatedKeys == null) {
            throw new IllegalArgumentException(
                    "Pairing private-notify acknowledgement "
                            + "state is required");
        }
        return List.of(encryptEmptyProtectedInformational(
                random,
                updatedKeys,
                updatedKeys.skEr,
                IKE_FLAG_RESPONSE,
                PAIRING_NOTIFIES_MESSAGE_ID));
    }

    static List<byte[]>
            createPairingPrivateNotifyWatchRequestForTest(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
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
            return encryptProtectedPayloadWithDirectionFlags(
                    random,
                    updatedKeys.initiatorSpi,
                    updatedKeys.responderSpi,
                    updatedKeys.skEr,
                    0,
                    EXCHANGE_INFORMATIONAL,
                    PAIRING_NOTIFIES_WATCH_MESSAGE_ID,
                    plaintext,
                    PAYLOAD_NOTIFY,
                    CONTROL_MAX_IKE_PACKET_SIZE);
        } finally {
            wipe(plaintext);
        }
    }

    private static byte[] buildPrivateNotifyPlaintext(
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
                body = buildNotifyBody(
                        0,
                        notify.type(),
                        data);
                payload = genericPayload(
                        index + 1 < notifies.size()
                                ? PAYLOAD_NOTIFY
                                : IkeV2Codec.PAYLOAD_NONE,
                        body);
                plaintext.writeBytes(payload);
            } finally {
                wipe(data);
                wipe(body);
                wipe(payload);
            }
        }
        return plaintext.toByteArray();
    }

    private static PairingPrivateNotifyResponse
            parsePrivateNotifyPlaintext(
            int firstInnerPayload,
            byte[] plaintext,
            int peerRequestMessageId) {
        if (firstInnerPayload != PAYLOAD_NOTIFY
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
                if (payloadType != PAYLOAD_NOTIFY
                        || offset + GENERIC_PAYLOAD_HEADER_LENGTH
                        > plaintext.length) {
                    throw new IllegalArgumentException(
                            "Watch private-notify payload "
                                    + "chain is malformed");
                }
                int nextPayload =
                        unsigned(plaintext[offset]);
                int payloadFlags =
                        unsigned(plaintext[offset + 1]);
                int payloadLength =
                        be16(plaintext, offset + 2);
                if (payloadFlags != 0
                        || payloadLength
                        < GENERIC_PAYLOAD_HEADER_LENGTH + 4
                        || offset + payloadLength
                        > plaintext.length) {
                    throw new IllegalArgumentException(
                            "Watch private notify is truncated");
                }
                int bodyOffset =
                        offset + GENERIC_PAYLOAD_HEADER_LENGTH;
                if (plaintext[bodyOffset] != 0
                        || plaintext[bodyOffset + 1] != 0) {
                    throw new IllegalArgumentException(
                            "Watch private notify has protocol/SPI data");
                }
                int notifyType =
                        be16(plaintext, bodyOffset + 2);
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
                    wipe(data);
                }
                offset += payloadLength;
                payloadType = nextPayload;
            }
            if (offset != plaintext.length) {
                throw new IllegalArgumentException(
                        "Watch private-notify plaintext has trailing data");
            }
            return new PairingPrivateNotifyResponse(
                    notifies,
                    peerRequestMessageId);
        } finally {
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                notify.destroy();
            }
        }
    }

    private static byte[] buildPinAuthMethodResponsePlaintext(
            byte[] pinSalt) {
        if (pinSalt == null
                || pinSalt.length < MINIMUM_PIN_SALT_LENGTH) {
            throw new IllegalArgumentException(
                    "PIN auth-method response requires a 32-byte salt");
        }
        ByteArrayOutputStream notifyData =
                new ByteArrayOutputStream(7 + pinSalt.length);
        notifyData.write(NRTLV_AUTH_METHOD);
        writeBe16(notifyData, 1);
        notifyData.write(PAIRING_AUTH_METHOD_PIN);
        notifyData.write(NRTLV_PIN_SALT);
        writeBe16(notifyData, pinSalt.length);
        notifyData.writeBytes(pinSalt);
        byte[] plaintext = genericPayload(
                IkeV2Codec.PAYLOAD_NONE,
                buildNotifyBody(
                        0,
                        PRIVATE_NOTIFY_AUTH_METHOD_RESPONSE,
                        notifyData.toByteArray()));
        return plaintext;
    }

    private static byte[] encryptEmptyProtectedInformational(
            SecureRandom random,
            IkeSaKeys updatedKeys,
            byte[] encryptionKeyMaterial,
            int directionFlags,
            int messageId) {
        byte[] iv = new byte[AES_GCM_EXPLICIT_IV_LENGTH];
        random.nextBytes(iv);
        return encryptProtectedPacket(
                updatedKeys.initiatorSpi,
                updatedKeys.responderSpi,
                directionFlags,
                EXCHANGE_INFORMATIONAL,
                messageId,
                PAYLOAD_SK,
                IkeV2Codec.PAYLOAD_NONE,
                0,
                0,
                new byte[0],
                encryptionKeyMaterial,
                iv);
    }

    private static void validateIkeAuthState(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        if (random == null
                || initiator == null
                || response == null
                || updatedKeys == null
                || response.packet.length == 0) {
            throw new IllegalArgumentException(
                    "Complete IKE_AUTH state is required");
        }
        requireLength("initiator IntAuth", intAuthI, PRF_SHA512_LENGTH);
        requireLength("responder IntAuth", intAuthR, PRF_SHA512_LENGTH);
        if (!Arrays.equals(
                initiator.initiatorSpi,
                updatedKeys.initiatorSpi)
                || !Arrays.equals(
                response.responderSpi,
                updatedKeys.responderSpi)
                || !Arrays.equals(
                initiator.nonce,
                updatedKeys.initiatorNonce)
                || !Arrays.equals(
                response.responderNonce,
                updatedKeys.responderNonce)) {
            throw new IllegalArgumentException(
                    "IKE_AUTH transcript and key schedule do not match");
        }
    }

    private static byte[] computeIntermediateIntAuth(
            IkeSaKeys keys,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        byte[] input = buildIntermediateIntAuthInput(
                keys.initiatorSpi,
                keys.responderSpi,
                response,
                firstInnerPayload,
                protectedPayloadFlags,
                plaintext);
        return prf(
                response ? keys.skPr : keys.skPi,
                input);
    }

    static byte[] computeIntermediateIntAuthForProfile(
            IkeSaKeys keys,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        if (keys == null) {
            throw new IllegalArgumentException(
                    "IKE keys are required");
        }
        return computeIntermediateIntAuth(
                keys,
                response,
                firstInnerPayload,
                protectedPayloadFlags,
                plaintext);
    }

    static byte[] buildIntermediateIntAuthInput(
            byte[] initiatorSpi,
            byte[] responderSpi,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        requireLength("initiator SPI", initiatorSpi, 8);
        requireLength("responder SPI", responderSpi, 8);
        if (plaintext == null
                || firstInnerPayload == IkeV2Codec.PAYLOAD_NONE
                || (protectedPayloadFlags & ~0xFF) != 0) {
            throw new IllegalArgumentException(
                    "Invalid IKE_INTERMEDIATE IntAuth input");
        }
        int adjustedPayloadLength =
                GENERIC_PAYLOAD_HEADER_LENGTH + plaintext.length;
        int adjustedPacketLength =
                IKE_HEADER_LENGTH + adjustedPayloadLength;
        ByteArrayOutputStream input =
                new ByteArrayOutputStream(
                        adjustedPacketLength);
        input.writeBytes(initiatorSpi);
        input.writeBytes(responderSpi);
        input.write(PAYLOAD_SK);
        input.write(0x20);
        input.write(EXCHANGE_IKE_INTERMEDIATE);
        input.write(response
                ? IKE_FLAG_RESPONSE
                : IKE_FLAG_INITIATOR);
        writeBe32(input, 1);
        writeBe32(input, adjustedPacketLength);
        input.write(firstInnerPayload);
        input.write(protectedPayloadFlags);
        writeBe16(input, adjustedPayloadLength);
        input.writeBytes(plaintext);
        return input.toByteArray();
    }

    private static byte[] buildIkeAuthIntAuth(
            byte[] intAuthI,
            byte[] intAuthR,
            int messageIdValue) {
        if (messageIdValue < 0) {
            throw new IllegalArgumentException(
                    "IKE_AUTH message ID must be nonnegative");
        }
        ByteArrayOutputStream messageId =
                new ByteArrayOutputStream(4);
        writeBe32(messageId, messageIdValue);
        return concatenate(
                intAuthI,
                intAuthR,
                messageId.toByteArray());
    }

    private static byte[] computeNullAuthenticationData(
            byte[] primeKey,
            byte[] signedOctets) {
        return prf(
                prf(primeKey, NULL_AUTH_PAD),
                signedOctets);
    }

    private static byte[] buildIdentificationBody(
            int identificationType,
            byte[] identificationData) {
        if (identificationData == null
                || identificationType <= 0
                || identificationType > 0xFF) {
            throw new IllegalArgumentException(
                    "Invalid IKE identification");
        }
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(
                        4 + identificationData.length);
        body.write(identificationType);
        body.write(0);
        body.write(0);
        body.write(0);
        body.writeBytes(identificationData);
        return body.toByteArray();
    }

    private static byte[] buildAuthenticationBody(
            int authenticationMethod,
            byte[] authenticationData) {
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(
                        4 + authenticationData.length);
        body.write(authenticationMethod);
        body.write(0);
        body.write(0);
        body.write(0);
        body.writeBytes(authenticationData);
        return body.toByteArray();
    }

    private static byte[] buildNotifyBody(
            int protocolId,
            int notifyType,
            byte[] data) {
        if (protocolId < 0
                || protocolId > 0xFF
                || notifyType < 0
                || notifyType > 0xFFFF
                || data == null) {
            throw new IllegalArgumentException(
                    "Invalid IKE notification");
        }
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(4 + data.length);
        body.write(protocolId);
        body.write(0);
        writeBe16(body, notifyType);
        body.writeBytes(data);
        return body.toByteArray();
    }

    private static byte[] genericPayload(
            int nextPayload,
            byte[] body) {
        int length = GENERIC_PAYLOAD_HEADER_LENGTH + body.length;
        ByteArrayOutputStream payload =
                new ByteArrayOutputStream(length);
        payload.write(nextPayload);
        payload.write(0);
        writeBe16(payload, length);
        payload.writeBytes(body);
        return payload.toByteArray();
    }

    static byte[] buildKePayload(byte[] keyExchangeData) {
        int length = 8 + keyExchangeData.length;
        ByteArrayOutputStream payload =
                new ByteArrayOutputStream(length);
        payload.write(IkeV2Codec.PAYLOAD_NONE);
        payload.write(0);
        writeBe16(payload, length);
        writeBe16(payload, ML_KEM_1024_METHOD);
        writeBe16(payload, 0);
        payload.writeBytes(keyExchangeData);
        return payload.toByteArray();
    }

    private static byte[] parseMlKemKePayload(
            int firstPayload,
            byte[] plaintext) {
        if (firstPayload != IkeV2Codec.PAYLOAD_KE
                || plaintext.length
                != 8 + ML_KEM_1024_CIPHERTEXT_LENGTH
                || unsigned(plaintext[0])
                != IkeV2Codec.PAYLOAD_NONE
                || be16(plaintext, 2) != plaintext.length
                || be16(plaintext, 4)
                != ML_KEM_1024_METHOD
                || be16(plaintext, 6) != 0) {
            throw new IllegalArgumentException(
                    "Unexpected ML-KEM-1024 KE response payload");
        }
        return Arrays.copyOfRange(plaintext, 8, plaintext.length);
    }

    private static IkeAuthResponse parseControlIkeAuthPlaintext(
            int firstPayload,
            byte[] plaintext,
            IkeV2Codec.InitiatorState initiator,
            ControlSaInitResponse response,
            IkeSaKeys updatedKeys,
            byte[] intAuthI,
            byte[] intAuthR) {
        requireLength("initiator IntAuth", intAuthI, PRF_SHA512_LENGTH);
        requireLength("responder IntAuth", intAuthR, PRF_SHA512_LENGTH);
        if (firstPayload == IkeV2Codec.PAYLOAD_NONE
                || plaintext == null
                || plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "IKE_AUTH response plaintext is empty");
        }

        byte[] idrBody = null;
        byte[] authData = null;
        List<Integer> payloadTypes = new ArrayList<>();
        List<Integer> notifyTypes = new ArrayList<>();
        int payloadType = firstPayload;
        int offset = 0;
        while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
            if (offset + GENERIC_PAYLOAD_HEADER_LENGTH
                    > plaintext.length) {
                throw new IllegalArgumentException(
                        "IKE_AUTH payload header is truncated");
            }
            int nextPayload = unsigned(plaintext[offset]);
            int payloadFlags = unsigned(plaintext[offset + 1]);
            int payloadLength = be16(plaintext, offset + 2);
            if (payloadLength < GENERIC_PAYLOAD_HEADER_LENGTH
                    || offset + payloadLength > plaintext.length) {
                throw new IllegalArgumentException(
                        "IKE_AUTH payload length is invalid");
            }
            payloadTypes.add(payloadType);
            int bodyOffset = offset + GENERIC_PAYLOAD_HEADER_LENGTH;
            int bodyLength =
                    payloadLength - GENERIC_PAYLOAD_HEADER_LENGTH;
            if (payloadType == PAYLOAD_ID_RESPONDER) {
                if (idrBody != null
                        || bodyLength
                        != 4 + CONTROL_PAIRING_KEY_ID_BYTES.length
                        || unsigned(plaintext[bodyOffset])
                        != ID_TYPE_KEY_ID
                        || plaintext[bodyOffset + 1] != 0
                        || plaintext[bodyOffset + 2] != 0
                        || plaintext[bodyOffset + 3] != 0
                        || !Arrays.equals(
                        CONTROL_PAIRING_KEY_ID_BYTES,
                        Arrays.copyOfRange(
                                plaintext,
                                bodyOffset + 4,
                                offset + payloadLength))) {
                    throw new IllegalArgumentException(
                            "Unexpected control IKE_AUTH responder ID");
                }
                idrBody = Arrays.copyOfRange(
                        plaintext,
                        bodyOffset,
                        offset + payloadLength);
            } else if (payloadType == PAYLOAD_AUTH) {
                if (authData != null
                        || bodyLength
                        != 4 + PRF_SHA512_LENGTH
                        || unsigned(plaintext[bodyOffset])
                        != AUTH_METHOD_NULL
                        || plaintext[bodyOffset + 1] != 0
                        || plaintext[bodyOffset + 2] != 0
                        || plaintext[bodyOffset + 3] != 0) {
                    throw new IllegalArgumentException(
                            "Unexpected control IKE_AUTH authentication");
                }
                authData = Arrays.copyOfRange(
                        plaintext,
                        bodyOffset + 4,
                        offset + payloadLength);
            } else if (payloadType == PAYLOAD_NOTIFY) {
                if (bodyLength < 4) {
                    throw new IllegalArgumentException(
                            "IKE_AUTH notify payload is truncated");
                }
                int spiSize = unsigned(plaintext[bodyOffset + 1]);
                if (bodyLength < 4 + spiSize) {
                    throw new IllegalArgumentException(
                            "IKE_AUTH notify SPI exceeds payload");
                }
                int notifyType = be16(plaintext, bodyOffset + 2);
                notifyTypes.add(notifyType);
                if (notifyType < 0x4000) {
                    throw new IllegalArgumentException(
                            String.format(
                                    Locale.US,
                                    "Watch returned IKE_AUTH error "
                                            + "notify 0x%04X",
                                    notifyType));
                }
            } else if (payloadType == PAYLOAD_SA
                    || payloadType == PAYLOAD_TS_INITIATOR
                    || payloadType == PAYLOAD_TS_RESPONDER) {
                throw new IllegalArgumentException(
                        "Watch returned a Child-SA payload to childless "
                                + "IKE_AUTH");
            } else if ((payloadFlags & 0x80) != 0) {
                throw new IllegalArgumentException(
                        "Unsupported critical IKE_AUTH payload "
                                + payloadType);
            }
            offset += payloadLength;
            payloadType = nextPayload;
        }
        if (offset != plaintext.length) {
            throw new IllegalArgumentException(
                    "Trailing bytes follow IKE_AUTH payload chain");
        }
        if (idrBody == null || authData == null) {
            throw new IllegalArgumentException(
                    "IKE_AUTH response lacks IDr or AUTH");
        }

        byte[] responderSignedOctets = concatenate(
                response.packet,
                initiator.nonce,
                prf(updatedKeys.skPr, idrBody),
                buildIkeAuthIntAuth(
                        intAuthI,
                        intAuthR,
                        IKE_AUTH_MESSAGE_ID));
        byte[] expectedAuthData =
                computeNullAuthenticationData(
                        updatedKeys.skPr,
                        responderSignedOctets);
        if (!MessageDigest.isEqual(
                expectedAuthData,
                authData)) {
            throw new IllegalArgumentException(
                    "IKE_AUTH responder NULL-AUTH verification failed");
        }
        return new IkeAuthResponse(
                payloadTypes,
                notifyTypes,
                authData.length);
    }

    private static PinAuthMethodResponse parsePinAuthMethodPlaintext(
            int firstPayload,
            byte[] plaintext,
            int peerRequestMessageId) {
        if (firstPayload == IkeV2Codec.PAYLOAD_NONE
                || plaintext == null
                || plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "PIN auth-method response plaintext is empty");
        }

        List<Integer> payloadTypes = new ArrayList<>();
        List<Integer> notifyTypes = new ArrayList<>();
        int authMethod = -1;
        byte[] pinSalt = null;
        boolean responseNotifySeen = false;
        int payloadType = firstPayload;
        int offset = 0;
        while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
            if (offset + GENERIC_PAYLOAD_HEADER_LENGTH
                    > plaintext.length) {
                throw new IllegalArgumentException(
                        "PIN auth-method payload header is truncated");
            }
            int nextPayload = unsigned(plaintext[offset]);
            int payloadFlags = unsigned(plaintext[offset + 1]);
            int payloadLength = be16(plaintext, offset + 2);
            if (payloadLength < GENERIC_PAYLOAD_HEADER_LENGTH
                    || offset + payloadLength > plaintext.length) {
                throw new IllegalArgumentException(
                        "PIN auth-method payload length is invalid");
            }
            payloadTypes.add(payloadType);
            int bodyOffset = offset + GENERIC_PAYLOAD_HEADER_LENGTH;
            int bodyLength =
                    payloadLength - GENERIC_PAYLOAD_HEADER_LENGTH;
            if (payloadType == PAYLOAD_NOTIFY) {
                if (bodyLength < 4) {
                    throw new IllegalArgumentException(
                            "PIN auth-method notify is truncated");
                }
                int protocolId = unsigned(plaintext[bodyOffset]);
                int spiSize = unsigned(plaintext[bodyOffset + 1]);
                if (bodyLength < 4 + spiSize) {
                    throw new IllegalArgumentException(
                            "PIN auth-method notify SPI exceeds payload");
                }
                int notifyType = be16(plaintext, bodyOffset + 2);
                notifyTypes.add(notifyType);
                if (notifyType < 0x4000) {
                    throw new IllegalArgumentException(
                            String.format(
                                    Locale.US,
                                    "Watch returned INFORMATIONAL error "
                                            + "notify 0x%04X",
                                    notifyType));
                }
                if (notifyType
                        == PRIVATE_NOTIFY_AUTH_METHOD_RESPONSE) {
                    if (responseNotifySeen
                            || protocolId != 0
                            || spiSize != 0) {
                        throw new IllegalArgumentException(
                                "Unexpected PIN auth-method response notify");
                    }
                    responseNotifySeen = true;
                    int tlvOffset = bodyOffset + 4;
                    int payloadEnd = offset + payloadLength;
                    while (tlvOffset < payloadEnd) {
                        if (tlvOffset + 3 > payloadEnd) {
                            throw new IllegalArgumentException(
                                    "PIN auth-method NRTLV header "
                                            + "is truncated");
                        }
                        int tlvType = unsigned(plaintext[tlvOffset]);
                        int tlvLength = be16(
                                plaintext,
                                tlvOffset + 1);
                        tlvOffset += 3;
                        if (tlvOffset + tlvLength > payloadEnd) {
                            throw new IllegalArgumentException(
                                    "PIN auth-method NRTLV value "
                                            + "is truncated");
                        }
                        if (tlvType == NRTLV_AUTH_METHOD) {
                            if (authMethod != -1 || tlvLength != 1) {
                                throw new IllegalArgumentException(
                                        "Duplicate or malformed PIN "
                                                + "auth-method NRTLV");
                            }
                            authMethod =
                                    unsigned(plaintext[tlvOffset]);
                        } else if (tlvType == NRTLV_PIN_SALT) {
                            if (pinSalt != null
                                    || tlvLength
                                    < MINIMUM_PIN_SALT_LENGTH) {
                                throw new IllegalArgumentException(
                                        "Duplicate or short PIN salt "
                                                + "NRTLV");
                            }
                            pinSalt = Arrays.copyOfRange(
                                    plaintext,
                                    tlvOffset,
                                    tlvOffset + tlvLength);
                        }
                        tlvOffset += tlvLength;
                    }
                }
            } else if ((payloadFlags & 0x80) != 0) {
                throw new IllegalArgumentException(
                        "Unsupported critical INFORMATIONAL payload "
                                + payloadType);
            }
            offset += payloadLength;
            payloadType = nextPayload;
        }
        if (offset != plaintext.length) {
            throw new IllegalArgumentException(
                    "Trailing bytes follow PIN auth-method payload chain");
        }
        if (!responseNotifySeen
                || authMethod != PAIRING_AUTH_METHOD_PIN
                || pinSalt == null) {
            throw new IllegalArgumentException(
                    "Watch did not select PIN auth with a salt");
        }
        return new PinAuthMethodResponse(
                payloadTypes,
                notifyTypes,
                authMethod,
                pinSalt,
                peerRequestMessageId);
    }

    private static void parseSelectedControlProposal(
            byte[] packet,
            int payloadOffset,
            int payloadLength) {
        if (payloadLength < 4 + 8) {
            throw new IllegalArgumentException(
                    "Selected control SA proposal is truncated");
        }
        int proposalOffset =
                payloadOffset + GENERIC_PAYLOAD_HEADER_LENGTH;
        int proposalLength = be16(packet, proposalOffset + 2);
        if (proposalOffset + proposalLength
                != payloadOffset + payloadLength
                || unsigned(packet[proposalOffset]) != 0
                || unsigned(packet[proposalOffset + 4]) != 1
                || unsigned(packet[proposalOffset + 5]) != 1
                || unsigned(packet[proposalOffset + 6]) != 0) {
            throw new IllegalArgumentException(
                    "Unexpected selected control SA proposal header");
        }
        int declaredTransforms =
                unsigned(packet[proposalOffset + 7]);
        int transformOffset = proposalOffset + 8;
        int transforms = 0;
        boolean aesGcm256 = false;
        boolean prfSha512 = false;
        boolean additionalMlKem1024 = false;
        boolean x448 = false;
        while (transformOffset
                < proposalOffset + proposalLength) {
            if (transformOffset + 8
                    > proposalOffset + proposalLength) {
                throw new IllegalArgumentException(
                        "Selected SA transform is truncated");
            }
            int nextTransform =
                    unsigned(packet[transformOffset]);
            int transformLength =
                    be16(packet, transformOffset + 2);
            if (transformLength < 8
                    || transformOffset + transformLength
                    > proposalOffset + proposalLength) {
                throw new IllegalArgumentException(
                        "Selected SA transform length is invalid");
            }
            int type = unsigned(packet[transformOffset + 4]);
            int id = be16(packet, transformOffset + 6);
            byte[] attributes = Arrays.copyOfRange(
                    packet,
                    transformOffset + 8,
                    transformOffset + transformLength);
            if (type == 1
                    && id == 20
                    && Arrays.equals(
                    attributes,
                    new byte[]{
                            (byte) 0x80,
                            0x0E,
                            0x01,
                            0x00
                    })) {
                aesGcm256 = true;
            } else if (type == 2
                    && id == 7
                    && attributes.length == 0) {
                prfSha512 = true;
            } else if (type == 6
                    && id == ML_KEM_1024_METHOD
                    && attributes.length == 0) {
                additionalMlKem1024 = true;
            } else if (type == 4
                    && id == IkeV2Codec.DH_GROUP_CURVE_448
                    && attributes.length == 0) {
                x448 = true;
            } else {
                throw new IllegalArgumentException(
                        "Unexpected selected control SA transform "
                                + type
                                + "/"
                                + id);
            }
            transforms++;
            transformOffset += transformLength;
            if (transformOffset
                    < proposalOffset + proposalLength
                    && nextTransform != 3) {
                throw new IllegalArgumentException(
                        "Selected SA transform chain ended early");
            }
            if (transformOffset
                    == proposalOffset + proposalLength
                    && nextTransform != 0) {
                throw new IllegalArgumentException(
                        "Selected SA transform chain has no end");
            }
        }
        if (transforms != declaredTransforms
                || transforms != 4
                || !aesGcm256
                || !prfSha512
                || !additionalMlKem1024
                || !x448) {
            throw new IllegalArgumentException(
                    "Watch selected an unexpected control proposal");
        }
    }

    private static byte[] concatenate(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length = Math.addExact(length, array.length);
        }
        byte[] output = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(
                    array,
                    0,
                    output,
                    offset,
                    array.length);
            offset += array.length;
        }
        return output;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static int be16(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 8)
                | unsigned(bytes[offset + 1]);
    }

    private static int be32(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 24)
                | (unsigned(bytes[offset + 1]) << 16)
                | (unsigned(bytes[offset + 2]) << 8)
                | unsigned(bytes[offset + 3]);
    }

    private static boolean matchesRange(
            byte[] packet,
            int offset,
            byte[] expected) {
        if (offset < 0
                || expected == null
                || offset + expected.length > packet.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < expected.length; index++) {
            difference |= packet[offset + index]
                    ^ expected[index];
        }
        return difference == 0;
    }

    private static void writeBe16(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writeBe32(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void requireLength(
            String label,
            byte[] bytes,
            int expectedLength) {
        if (bytes == null || bytes.length != expectedLength) {
            throw new IllegalArgumentException(
                    label + " must be " + expectedLength + " bytes");
        }
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static final class ControlSaInitResponse {
        final byte[] responderSpi;
        final byte[] responderNonce;
        final byte[] responderX448PublicKey;
        final byte[] packet;

        ControlSaInitResponse(
                byte[] responderSpi,
                byte[] responderNonce,
                byte[] responderX448PublicKey,
                byte[] packet) {
            this.responderSpi =
                    Arrays.copyOf(responderSpi, responderSpi.length);
            this.responderNonce =
                    Arrays.copyOf(responderNonce, responderNonce.length);
            this.responderX448PublicKey = Arrays.copyOf(
                    responderX448PublicKey,
                    responderX448PublicKey.length);
            this.packet = Arrays.copyOf(packet, packet.length);
        }
    }

    static final class IkeSaKeys {
        final byte[] skD;
        final byte[] skEi;
        final byte[] skEr;
        final byte[] skPi;
        final byte[] skPr;
        final byte[] initiatorSpi;
        final byte[] responderSpi;
        final byte[] initiatorNonce;
        final byte[] responderNonce;

        IkeSaKeys(
                byte[] skD,
                byte[] skEi,
                byte[] skEr,
                byte[] skPi,
                byte[] skPr,
                byte[] initiatorSpi,
                byte[] responderSpi,
                byte[] initiatorNonce,
                byte[] responderNonce) {
            this.skD = Arrays.copyOf(skD, skD.length);
            this.skEi = Arrays.copyOf(skEi, skEi.length);
            this.skEr = Arrays.copyOf(skEr, skEr.length);
            this.skPi = Arrays.copyOf(skPi, skPi.length);
            this.skPr = Arrays.copyOf(skPr, skPr.length);
            this.initiatorSpi =
                    Arrays.copyOf(initiatorSpi, initiatorSpi.length);
            this.responderSpi =
                    Arrays.copyOf(responderSpi, responderSpi.length);
            this.initiatorNonce =
                    Arrays.copyOf(initiatorNonce, initiatorNonce.length);
            this.responderNonce =
                    Arrays.copyOf(responderNonce, responderNonce.length);
        }

        void destroy() {
            wipe(skD);
            wipe(skEi);
            wipe(skEr);
            wipe(skPi);
            wipe(skPr);
            wipe(initiatorSpi);
            wipe(responderSpi);
            wipe(initiatorNonce);
            wipe(responderNonce);
        }
    }

    static final class AdditionalKeRequest {
        final MLKEMPrivateKeyParameters privateKey;
        final byte[] publicKey;
        final List<byte[]> ikePackets;
        final byte[] initiatorIntAuth;

        AdditionalKeRequest(
                MLKEMPrivateKeyParameters privateKey,
                byte[] publicKey,
                List<byte[]> ikePackets,
                byte[] initiatorIntAuth) {
            this.privateKey = privateKey;
            this.publicKey =
                    Arrays.copyOf(publicKey, publicKey.length);
            this.ikePackets = List.copyOf(ikePackets);
            this.initiatorIntAuth = Arrays.copyOf(
                    initiatorIntAuth,
                    initiatorIntAuth.length);
        }
    }

    static final class AdditionalKeResult {
        final byte[] sharedSecret;
        final IkeSaKeys updatedKeys;

        AdditionalKeResult(
                byte[] sharedSecret,
                IkeSaKeys updatedKeys) {
            this.sharedSecret =
                    Arrays.copyOf(sharedSecret, sharedSecret.length);
            this.updatedKeys = updatedKeys;
        }
    }

    static final class DecryptedIntermediatePart {
        final boolean fragmented;
        final int fragmentNumber;
        final int totalFragments;
        final int firstInnerPayload;
        final int protectedPayloadFlags;
        final byte[] plaintext;

        DecryptedIntermediatePart(
                boolean fragmented,
                int fragmentNumber,
                int totalFragments,
                int firstInnerPayload,
                int protectedPayloadFlags,
                byte[] plaintext) {
            this.fragmented = fragmented;
            this.fragmentNumber = fragmentNumber;
            this.totalFragments = totalFragments;
            this.firstInnerPayload = firstInnerPayload;
            this.protectedPayloadFlags = protectedPayloadFlags;
            this.plaintext =
                    Arrays.copyOf(plaintext, plaintext.length);
        }
    }

    static final class IntermediateResponseAccumulator {
        private final IkeSaKeys keys;
        private final byte[] initiatorSpi;
        private final byte[] responderSpi;
        private final byte[] skEr;
        private byte[][] fragments;
        private int firstInnerPayload;
        private int firstProtectedPayloadFlags;
        private int receivedFragments;
        private byte[] responderIntAuth;

        IntermediateResponseAccumulator(IkeSaKeys keys) {
            this.keys = keys;
            this.initiatorSpi =
                    Arrays.copyOf(
                            keys.initiatorSpi,
                            keys.initiatorSpi.length);
            this.responderSpi =
                    Arrays.copyOf(
                            keys.responderSpi,
                            keys.responderSpi.length);
            this.skEr =
                    Arrays.copyOf(keys.skEr, keys.skEr.length);
        }

        byte[] accept(byte[] packet) {
            DecryptedIntermediatePart part =
                    decryptProtectedIntermediate(
                            packet,
                            initiatorSpi,
                            responderSpi,
                            skEr,
                            true);
            if (!part.fragmented) {
                if (fragments != null) {
                    throw new IllegalArgumentException(
                            "Mixed fragmented and unfragmented response");
                }
                responderIntAuth = computeIntermediateIntAuth(
                        keys,
                        true,
                        part.firstInnerPayload,
                        part.protectedPayloadFlags,
                        part.plaintext);
                return parseMlKemKePayload(
                        part.firstInnerPayload,
                        part.plaintext);
            }
            if (fragments == null) {
                fragments = new byte[part.totalFragments][];
            } else if (fragments.length
                    != part.totalFragments) {
                throw new IllegalArgumentException(
                        "IKE fragment total changed");
            }
            int index = part.fragmentNumber - 1;
            if (fragments[index] != null) {
                if (!Arrays.equals(
                        fragments[index],
                        part.plaintext)) {
                    throw new IllegalArgumentException(
                            "IKE fragment retransmission differs");
                }
                return null;
            }
            fragments[index] = part.plaintext;
            receivedFragments++;
            if (part.fragmentNumber == 1) {
                firstInnerPayload =
                        part.firstInnerPayload;
                firstProtectedPayloadFlags =
                        part.protectedPayloadFlags;
            }
            if (receivedFragments != fragments.length) {
                return null;
            }
            ByteArrayOutputStream plaintext =
                    new ByteArrayOutputStream();
            for (byte[] fragment : fragments) {
                if (fragment == null) {
                    return null;
                }
                plaintext.writeBytes(fragment);
            }
            byte[] completePlaintext = plaintext.toByteArray();
            responderIntAuth = computeIntermediateIntAuth(
                    keys,
                    true,
                    firstInnerPayload,
                    firstProtectedPayloadFlags,
                    completePlaintext);
            return parseMlKemKePayload(
                    firstInnerPayload,
                    completePlaintext);
        }

        byte[] responderIntAuth() {
            if (responderIntAuth == null) {
                throw new IllegalStateException(
                        "IKE_INTERMEDIATE response is incomplete");
            }
            return Arrays.copyOf(
                    responderIntAuth,
                    responderIntAuth.length);
        }
    }

    static final class IkeAuthRequest {
        final List<byte[]> ikePackets;
        final byte[] plaintext;
        final int authenticationDataLength;

        IkeAuthRequest(
                List<byte[]> ikePackets,
                byte[] plaintext,
                byte[] authenticationData) {
            this.ikePackets = List.copyOf(ikePackets);
            this.plaintext =
                    Arrays.copyOf(plaintext, plaintext.length);
            this.authenticationDataLength =
                    authenticationData.length;
        }
    }

    static final class IkeAuthResponse {
        final List<Integer> payloadTypes;
        final List<Integer> notifyTypes;
        final int authenticationDataLength;

        IkeAuthResponse(
                List<Integer> payloadTypes,
                List<Integer> notifyTypes,
                int authenticationDataLength) {
            this.payloadTypes = List.copyOf(payloadTypes);
            this.notifyTypes = List.copyOf(notifyTypes);
            this.authenticationDataLength =
                    authenticationDataLength;
        }
    }

    static final class IkeAuthResponseAccumulator {
        private final IkeV2Codec.InitiatorState initiator;
        private final ControlSaInitResponse response;
        private final IkeSaKeys updatedKeys;
        private final byte[] intAuthI;
        private final byte[] intAuthR;
        private byte[][] fragments;
        private int firstInnerPayload;
        private int receivedFragments;

        IkeAuthResponseAccumulator(
                IkeV2Codec.InitiatorState initiator,
                ControlSaInitResponse response,
                IkeSaKeys updatedKeys,
                byte[] intAuthI,
                byte[] intAuthR) {
            this.initiator = initiator;
            this.response = response;
            this.updatedKeys = updatedKeys;
            this.intAuthI =
                    Arrays.copyOf(intAuthI, intAuthI.length);
            this.intAuthR =
                    Arrays.copyOf(intAuthR, intAuthR.length);
        }

        IkeAuthResponse accept(byte[] packet) {
            DecryptedIntermediatePart part =
                    decryptProtectedPacket(
                            packet,
                            updatedKeys.initiatorSpi,
                            updatedKeys.responderSpi,
                            updatedKeys.skEr,
                            true,
                            EXCHANGE_IKE_AUTH,
                            IKE_AUTH_MESSAGE_ID);
            if (!part.fragmented) {
                if (fragments != null) {
                    throw new IllegalArgumentException(
                            "Mixed fragmented and unfragmented "
                                    + "IKE_AUTH response");
                }
                return parseControlIkeAuthPlaintext(
                        part.firstInnerPayload,
                        part.plaintext,
                        initiator,
                        response,
                        updatedKeys,
                        intAuthI,
                        intAuthR);
            }
            if (fragments == null) {
                fragments = new byte[part.totalFragments][];
            } else if (fragments.length
                    != part.totalFragments) {
                throw new IllegalArgumentException(
                        "IKE_AUTH fragment total changed");
            }
            int index = part.fragmentNumber - 1;
            if (fragments[index] != null) {
                if (!Arrays.equals(
                        fragments[index],
                        part.plaintext)) {
                    throw new IllegalArgumentException(
                            "IKE_AUTH fragment retransmission differs");
                }
                return null;
            }
            fragments[index] = part.plaintext;
            receivedFragments++;
            if (part.fragmentNumber == 1) {
                firstInnerPayload =
                        part.firstInnerPayload;
            }
            if (receivedFragments != fragments.length) {
                return null;
            }
            ByteArrayOutputStream plaintext =
                    new ByteArrayOutputStream();
            for (byte[] fragment : fragments) {
                if (fragment == null) {
                    return null;
                }
                plaintext.writeBytes(fragment);
            }
            return parseControlIkeAuthPlaintext(
                    firstInnerPayload,
                    plaintext.toByteArray(),
                    initiator,
                    response,
                    updatedKeys,
                    intAuthI,
                    intAuthR);
        }
    }

    static final class PairingExchangeRequest {
        final List<byte[]> ikePackets;
        final byte[] plaintext;
        final int messageId;
        final int gspmDataLength;
        final int authenticationDataLength;

        PairingExchangeRequest(
                List<byte[]> ikePackets,
                byte[] plaintext,
                int messageId,
                int gspmDataLength,
                int authenticationDataLength) {
            this.ikePackets = List.copyOf(ikePackets);
            this.plaintext =
                    Arrays.copyOf(plaintext, plaintext.length);
            this.messageId = messageId;
            this.gspmDataLength = gspmDataLength;
            this.authenticationDataLength =
                    authenticationDataLength;
        }
    }

    static final class PairingAuthResponse {
        final List<Integer> payloadTypes;
        final List<Integer> notifyTypes;
        final int authenticationDataLength;

        PairingAuthResponse(
                List<Integer> payloadTypes,
                List<Integer> notifyTypes,
                int authenticationDataLength) {
            this.payloadTypes = List.copyOf(payloadTypes);
            this.notifyTypes = List.copyOf(notifyTypes);
            this.authenticationDataLength =
                    authenticationDataLength;
        }
    }

    /**
     * Stateful three-request Apple GSPM initiator:
     * X/Y, confirmation tags, then secure-password AUTH.
     */
    static final class PairingGspmInitiator {
        private static final int READY_FIRST_REQUEST = 0;
        private static final int AWAITING_FIRST_RESPONSE = 1;
        private static final int AWAITING_SECOND_RESPONSE = 2;
        private static final int AWAITING_FINAL_RESPONSE = 3;
        private static final int COMPLETE = 4;
        private static final int DESTROYED = 5;

        private final IkeV2Codec.InitiatorState initiator;
        private final ControlSaInitResponse response;
        private final IkeSaKeys ppkKeys;
        private final byte[] intAuthI;
        private final byte[] intAuthR;
        private final AppleSpake2PlusProver prover;
        private final byte[] initiatorIdBody;
        private final byte[] responderIdBody;
        private final byte[] firstInitiatorGspmMessage;

        private byte[] firstResponderGspmMessage;
        private byte[] gspmSessionKey;
        private int stage = READY_FIRST_REQUEST;

        PairingGspmInitiator(
                IkeV2Codec.InitiatorState initiator,
                ControlSaInitResponse response,
                IkeSaKeys ppkKeys,
                byte[] intAuthI,
                byte[] intAuthR,
                AppleSpake2PlusProver prover,
                byte[] initiatorIdBody,
                byte[] responderIdBody) {
            this.initiator = initiator;
            this.response = response;
            this.ppkKeys = ppkKeys;
            this.intAuthI =
                    Arrays.copyOf(intAuthI, intAuthI.length);
            this.intAuthR =
                    Arrays.copyOf(intAuthR, intAuthR.length);
            this.prover = prover;
            this.initiatorIdBody =
                    Arrays.copyOf(
                            initiatorIdBody,
                            initiatorIdBody.length);
            this.responderIdBody =
                    Arrays.copyOf(
                            responderIdBody,
                            responderIdBody.length);
            this.firstInitiatorGspmMessage =
                    prover.getShare();
        }

        PairingExchangeRequest createFirstRequest(
                SecureRandom random) {
            requireStage(
                    READY_FIRST_REQUEST,
                    "first GSPM request");
            byte[] plaintext =
                    buildInitialPairingGspmPlaintext(
                            initiatorIdBody,
                            responderIdBody,
                            firstInitiatorGspmMessage);
            List<byte[]> packets = encryptProtectedPayload(
                    random,
                    ppkKeys.initiatorSpi,
                    ppkKeys.responderSpi,
                    ppkKeys.skEi,
                    false,
                    EXCHANGE_IKE_AUTH,
                    PAIRING_FIRST_GSPM_MESSAGE_ID,
                    plaintext,
                    PAYLOAD_ID_INITIATOR,
                    CONTROL_MAX_IKE_PACKET_SIZE);
            stage = AWAITING_FIRST_RESPONSE;
            return new PairingExchangeRequest(
                    packets,
                    plaintext,
                    PAIRING_FIRST_GSPM_MESSAGE_ID,
                    firstInitiatorGspmMessage.length,
                    0);
        }

        PairingExchangeRequest
                acceptFirstResponseAndCreateSecondRequest(
                SecureRandom random,
                byte[] packet) {
            requireStage(
                    AWAITING_FIRST_RESPONSE,
                    "first GSPM response");
            DecryptedIntermediatePart part =
                    decryptProtectedPacket(
                            packet,
                            ppkKeys.initiatorSpi,
                            ppkKeys.responderSpi,
                            ppkKeys.skEr,
                            true,
                            EXCHANGE_IKE_AUTH,
                            PAIRING_FIRST_GSPM_MESSAGE_ID);
            byte[] responderShare =
                    parsePairingGspmResponse(
                            part,
                            true,
                            AppleSpake2PlusProver.SHARE_LENGTH);
            byte[] proverConfirmation = null;
            try {
                proverConfirmation =
                        prover.processPeerShareAndGenerateVerificationTag(
                                responderShare);
                firstResponderGspmMessage =
                        Arrays.copyOf(
                                responderShare,
                                responderShare.length);
                byte[] plaintext =
                        buildSingleGspmPlaintext(
                                proverConfirmation);
                List<byte[]> packets =
                        encryptProtectedPayload(
                                random,
                                ppkKeys.initiatorSpi,
                                ppkKeys.responderSpi,
                                ppkKeys.skEi,
                                false,
                                EXCHANGE_IKE_AUTH,
                                PAIRING_SECOND_GSPM_MESSAGE_ID,
                                plaintext,
                                PAYLOAD_GSPM,
                                CONTROL_MAX_IKE_PACKET_SIZE);
                stage = AWAITING_SECOND_RESPONSE;
                return new PairingExchangeRequest(
                        packets,
                        plaintext,
                        PAIRING_SECOND_GSPM_MESSAGE_ID,
                        proverConfirmation.length,
                        0);
            } catch (RuntimeException error) {
                destroy();
                throw error;
            } finally {
                wipe(responderShare);
                wipe(proverConfirmation);
            }
        }

        PairingExchangeRequest
                acceptSecondResponseAndCreateFinalAuthRequest(
                SecureRandom random,
                byte[] packet) {
            requireStage(
                    AWAITING_SECOND_RESPONSE,
                    "second GSPM response");
            DecryptedIntermediatePart part =
                    decryptProtectedPacket(
                            packet,
                            ppkKeys.initiatorSpi,
                            ppkKeys.responderSpi,
                            ppkKeys.skEr,
                            true,
                            EXCHANGE_IKE_AUTH,
                            PAIRING_SECOND_GSPM_MESSAGE_ID);
            byte[] verifierConfirmation =
                    parsePairingGspmResponse(
                            part,
                            false,
                            AppleSpake2PlusProver
                                    .CONFIRMATION_TAG_LENGTH);
            byte[] authData = null;
            try {
                gspmSessionKey =
                        prover.processPeerVerificationTagAndGenerateKey(
                                verifierConfirmation);
                authData = createPairingAuthenticationData(
                        false,
                        initiator,
                        response,
                        ppkKeys,
                        intAuthI,
                        intAuthR,
                        initiatorIdBody,
                        firstInitiatorGspmMessage,
                        firstResponderGspmMessage,
                        gspmSessionKey);
                byte[] plaintext = concatenate(
                        genericPayload(
                                PAYLOAD_NOTIFY,
                                buildAuthenticationBody(
                                        AUTH_METHOD_GENERIC_SECURE_PASSWORD,
                                        authData)),
                        genericPayload(
                                IkeV2Codec.PAYLOAD_NONE,
                                buildNotifyBody(
                                        0,
                                        NOTIFY_PPK_IDENTITY,
                                        AppleWatchPairingCrypto
                                                .fixedPpkIdentityData())));
                List<byte[]> packets =
                        encryptProtectedPayload(
                                random,
                                ppkKeys.initiatorSpi,
                                ppkKeys.responderSpi,
                                ppkKeys.skEi,
                                false,
                                EXCHANGE_IKE_AUTH,
                                PAIRING_FINAL_AUTH_MESSAGE_ID,
                                plaintext,
                                PAYLOAD_AUTH,
                                CONTROL_MAX_IKE_PACKET_SIZE);
                stage = AWAITING_FINAL_RESPONSE;
                return new PairingExchangeRequest(
                        packets,
                        plaintext,
                        PAIRING_FINAL_AUTH_MESSAGE_ID,
                        0,
                        authData.length);
            } catch (RuntimeException error) {
                destroy();
                throw error;
            } finally {
                wipe(verifierConfirmation);
                wipe(authData);
            }
        }

        PairingAuthResponse acceptFinalAuthResponse(
                byte[] packet) {
            requireStage(
                    AWAITING_FINAL_RESPONSE,
                    "final pairing AUTH response");
            DecryptedIntermediatePart part =
                    decryptProtectedPacket(
                            packet,
                            ppkKeys.initiatorSpi,
                            ppkKeys.responderSpi,
                            ppkKeys.skEr,
                            true,
                            EXCHANGE_IKE_AUTH,
                            PAIRING_FINAL_AUTH_MESSAGE_ID);
            ParsedPairingFinalAuth parsed =
                    parsePairingFinalAuthResponse(part);
            byte[] expectedAuthenticationData = null;
            try {
                expectedAuthenticationData =
                        createPairingAuthenticationData(
                                true,
                                initiator,
                                response,
                                ppkKeys,
                                intAuthI,
                                intAuthR,
                                responderIdBody,
                                firstInitiatorGspmMessage,
                                firstResponderGspmMessage,
                                gspmSessionKey);
                if (!MessageDigest.isEqual(
                        expectedAuthenticationData,
                        parsed.authenticationData)) {
                    throw new SecurityException(
                            "Watch GSPM AUTH verification failed");
                }
                stage = COMPLETE;
                wipe(gspmSessionKey);
                gspmSessionKey = null;
                return new PairingAuthResponse(
                        parsed.payloadTypes,
                        parsed.notifyTypes,
                        parsed.authenticationData.length);
            } catch (RuntimeException error) {
                destroy();
                throw error;
            } finally {
                wipe(expectedAuthenticationData);
                parsed.destroy();
            }
        }

        boolean isComplete() {
            return stage == COMPLETE;
        }

        void destroy() {
            if (stage == DESTROYED) {
                return;
            }
            prover.destroy();
            wipe(intAuthI);
            wipe(intAuthR);
            wipe(initiatorIdBody);
            wipe(responderIdBody);
            wipe(firstInitiatorGspmMessage);
            wipe(firstResponderGspmMessage);
            wipe(gspmSessionKey);
            firstResponderGspmMessage = null;
            gspmSessionKey = null;
            stage = DESTROYED;
        }

        private void requireStage(
                int expectedStage,
                String operation) {
            if (stage != expectedStage) {
                throw new IllegalStateException(
                        "Cannot process "
                                + operation
                                + " in pairing stage "
                                + stage);
            }
        }
    }

    private static final class ParsedPairingFinalAuth {
        final byte[] authenticationData;
        final List<Integer> payloadTypes;
        final List<Integer> notifyTypes;

        ParsedPairingFinalAuth(
                byte[] authenticationData,
                List<Integer> payloadTypes,
                List<Integer> notifyTypes) {
            this.authenticationData = Arrays.copyOf(
                    authenticationData,
                    authenticationData.length);
            this.payloadTypes = List.copyOf(payloadTypes);
            this.notifyTypes = List.copyOf(notifyTypes);
        }

        void destroy() {
            wipe(authenticationData);
        }
    }

    static final class PinAuthMethodRequest {
        final List<byte[]> ikePackets;
        final byte[] plaintext;

        PinAuthMethodRequest(
                List<byte[]> ikePackets,
                byte[] plaintext) {
            this.ikePackets = List.copyOf(ikePackets);
            this.plaintext =
                    Arrays.copyOf(plaintext, plaintext.length);
        }
    }

    static final class PinAuthMethodResponse {
        final List<Integer> payloadTypes;
        final List<Integer> notifyTypes;
        final int authMethod;
        final byte[] pinSalt;
        final int peerRequestMessageId;

        PinAuthMethodResponse(
                List<Integer> payloadTypes,
                List<Integer> notifyTypes,
                int authMethod,
                byte[] pinSalt,
                int peerRequestMessageId) {
            this.payloadTypes = List.copyOf(payloadTypes);
            this.notifyTypes = List.copyOf(notifyTypes);
            this.authMethod = authMethod;
            this.pinSalt = Arrays.copyOf(pinSalt, pinSalt.length);
            this.peerRequestMessageId = peerRequestMessageId;
        }
    }

    static final class PinAuthMethodResponseAccumulator {
        private final IkeSaKeys updatedKeys;
        private byte[][] fragments;
        private int firstInnerPayload;
        private int receivedFragments;
        private int fragmentedPeerRequestMessageId = -1;
        private boolean requestAcknowledged;

        PinAuthMethodResponseAccumulator(IkeSaKeys updatedKeys) {
            if (updatedKeys == null) {
                throw new IllegalArgumentException(
                        "Updated IKE keys are required");
            }
            this.updatedKeys = updatedKeys;
        }

        PinAuthMethodResponse accept(byte[] packet) {
            DecryptedIntermediatePart part;
            int peerRequestMessageId;
            boolean directResponseHeader =
                    packet != null
                            && packet.length >= IKE_HEADER_LENGTH
                            && unsigned(packet[18])
                            == EXCHANGE_INFORMATIONAL
                            && (unsigned(packet[19])
                            & (IKE_FLAG_INITIATOR | IKE_FLAG_RESPONSE))
                            == IKE_FLAG_RESPONSE
                            && be32(packet, 20)
                            == PIN_METHOD_MESSAGE_ID;
            if (!requestAcknowledged || directResponseHeader) {
                part = decryptProtectedPacket(
                        packet,
                        updatedKeys.initiatorSpi,
                        updatedKeys.responderSpi,
                        updatedKeys.skEr,
                        true,
                        EXCHANGE_INFORMATIONAL,
                        PIN_METHOD_MESSAGE_ID);
                if (!part.fragmented
                        && part.firstInnerPayload
                        == IkeV2Codec.PAYLOAD_NONE
                        && part.plaintext.length == 0) {
                    requestAcknowledged = true;
                    return null;
                }
                if (requestAcknowledged) {
                    throw new IllegalArgumentException(
                            "Retransmitted PIN auth-method ACK "
                                    + "was not empty");
                }
                peerRequestMessageId = -1;
            } else {
                part = decryptProtectedPacketWithDirectionFlags(
                        packet,
                        updatedKeys.initiatorSpi,
                        updatedKeys.responderSpi,
                        updatedKeys.skEr,
                        0,
                        EXCHANGE_INFORMATIONAL,
                        PIN_METHOD_WATCH_MESSAGE_ID);
                peerRequestMessageId =
                        PIN_METHOD_WATCH_MESSAGE_ID;
            }
            if (!part.fragmented) {
                if (fragments != null) {
                    throw new IllegalArgumentException(
                            "Mixed fragmented and unfragmented "
                                    + "PIN auth-method response");
                }
                return parsePinAuthMethodPlaintext(
                        part.firstInnerPayload,
                        part.plaintext,
                        peerRequestMessageId);
            }
            if (fragments == null) {
                fragments = new byte[part.totalFragments][];
                fragmentedPeerRequestMessageId =
                        peerRequestMessageId;
            } else if (fragments.length != part.totalFragments) {
                throw new IllegalArgumentException(
                        "PIN auth-method fragment total changed");
            } else if (fragmentedPeerRequestMessageId
                    != peerRequestMessageId) {
                throw new IllegalArgumentException(
                        "PIN auth-method fragment direction changed");
            }
            int index = part.fragmentNumber - 1;
            if (fragments[index] != null) {
                if (!Arrays.equals(
                        fragments[index],
                        part.plaintext)) {
                    throw new IllegalArgumentException(
                            "PIN auth-method fragment "
                                    + "retransmission differs");
                }
                return null;
            }
            fragments[index] = part.plaintext;
            receivedFragments++;
            if (part.fragmentNumber == 1) {
                firstInnerPayload = part.firstInnerPayload;
            }
            if (receivedFragments != fragments.length) {
                return null;
            }
            ByteArrayOutputStream plaintext =
                    new ByteArrayOutputStream();
            for (byte[] fragment : fragments) {
                if (fragment == null) {
                    return null;
                }
                plaintext.writeBytes(fragment);
            }
            return parsePinAuthMethodPlaintext(
                    firstInnerPayload,
                    plaintext.toByteArray(),
                    fragmentedPeerRequestMessageId);
        }

        boolean requestAcknowledged() {
            return requestAcknowledged;
        }
    }

    static final class PairingPrivateNotifyRequest {
        final List<byte[]> ikePackets;
        final List<Integer> notifyTypes;
        final List<Integer> dataLengths;

        PairingPrivateNotifyRequest(
                List<byte[]> ikePackets,
                List<Integer> notifyTypes,
                List<Integer> dataLengths) {
            this.ikePackets = List.copyOf(ikePackets);
            this.notifyTypes = List.copyOf(notifyTypes);
            this.dataLengths = List.copyOf(dataLengths);
        }
    }

    static final class PairingPrivateNotifyResponse {
        private final List<
                ApplePairingNotifyPayloads.PrivateNotify>
                notifies;
        final int peerRequestMessageId;
        private boolean destroyed;

        PairingPrivateNotifyResponse(
                List<ApplePairingNotifyPayloads.PrivateNotify>
                        source,
                int peerRequestMessageId) {
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    copied = new ArrayList<>(source.size());
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : source) {
                copied.add(
                        new ApplePairingNotifyPayloads
                                .PrivateNotify(
                                notify.type(),
                                notify.data()));
            }
            this.notifies = List.copyOf(copied);
            this.peerRequestMessageId =
                    peerRequestMessageId;
        }

        List<ApplePairingNotifyPayloads.PrivateNotify>
                notifies() {
            requireLive();
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    copied = new ArrayList<>(notifies.size());
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                copied.add(
                        new ApplePairingNotifyPayloads
                                .PrivateNotify(
                                notify.type(),
                                notify.data()));
            }
            return List.copyOf(copied);
        }

        List<Integer> notifyTypes() {
            requireLive();
            List<Integer> types =
                    new ArrayList<>(notifies.size());
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                types.add(notify.type());
            }
            return List.copyOf(types);
        }

        List<Integer> dataLengths() {
            requireLive();
            List<Integer> lengths =
                    new ArrayList<>(notifies.size());
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                byte[] data = notify.data();
                try {
                    lengths.add(data.length);
                } finally {
                    wipe(data);
                }
            }
            return List.copyOf(lengths);
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                notify.destroy();
            }
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Watch private-notify response "
                                + "has been destroyed");
            }
        }
    }

    static final class PairingPrivateNotifyResponseAccumulator {
        private final IkeSaKeys updatedKeys;
        private final int requestMessageId;
        private byte[][] fragments;
        private int firstInnerPayload;
        private int receivedFragments;
        private boolean requestAcknowledged;
        private PairingPrivateNotifyResponse
                completedPeerRequest;

        PairingPrivateNotifyResponseAccumulator(
                IkeSaKeys updatedKeys) {
            this(updatedKeys, PAIRING_NOTIFIES_MESSAGE_ID);
        }

        PairingPrivateNotifyResponseAccumulator(IkeSaKeys updatedKeys, int requestMessageId) {
            if (updatedKeys == null || (requestMessageId != PAIRING_NOTIFIES_MESSAGE_ID
                    && requestMessageId != OpticalPskSession.NEXT_INFORMATIONAL_MESSAGE_ID)) {
                throw new IllegalArgumentException(
                        "Updated IKE keys are required");
            }
            this.updatedKeys = updatedKeys;
            this.requestMessageId = requestMessageId;
        }

        PairingPrivateNotifyResponse accept(
                byte[] packet) {
            if (isProtectedInformationalHeader(
                    packet,
                    IKE_FLAG_RESPONSE,
                    requestMessageId)) {
                DecryptedIntermediatePart acknowledgement =
                        decryptProtectedPacket(
                                packet,
                                updatedKeys.initiatorSpi,
                                updatedKeys.responderSpi,
                                updatedKeys.skEr,
                                true,
                                EXCHANGE_INFORMATIONAL,
                                requestMessageId);
                if (acknowledgement.fragmented
                        || acknowledgement.firstInnerPayload
                        != IkeV2Codec.PAYLOAD_NONE
                        || acknowledgement.plaintext.length != 0) {
                    throw new IllegalArgumentException(
                            "Pairing private-notify ACK is not empty");
                }
                requestAcknowledged = true;
                return completedPeerRequest;
            }
            if (!isProtectedInformationalHeader(
                    packet,
                    0,
                    PAIRING_NOTIFIES_WATCH_MESSAGE_ID)) {
                throw new IllegalArgumentException(
                        "Unexpected pairing private-notify "
                                + "INFORMATIONAL header");
            }
            DecryptedIntermediatePart part =
                    decryptProtectedPacketWithDirectionFlags(
                            packet,
                            updatedKeys.initiatorSpi,
                            updatedKeys.responderSpi,
                            updatedKeys.skEr,
                            0,
                            EXCHANGE_INFORMATIONAL,
                            PAIRING_NOTIFIES_WATCH_MESSAGE_ID);
            PairingPrivateNotifyResponse response;
            if (!part.fragmented) {
                if (fragments != null) {
                    throw new IllegalArgumentException(
                            "Mixed fragmented and unfragmented "
                                    + "Watch private-notify request");
                }
                response = parsePrivateNotifyPlaintext(
                        part.firstInnerPayload,
                        part.plaintext,
                        PAIRING_NOTIFIES_WATCH_MESSAGE_ID);
            } else {
                if (fragments == null) {
                    fragments =
                            new byte[part.totalFragments][];
                } else if (fragments.length
                        != part.totalFragments) {
                    throw new IllegalArgumentException(
                            "Watch private-notify fragment "
                                    + "total changed");
                }
                int index = part.fragmentNumber - 1;
                if (fragments[index] != null) {
                    if (!Arrays.equals(
                            fragments[index],
                            part.plaintext)) {
                        throw new IllegalArgumentException(
                                "Watch private-notify fragment "
                                        + "retransmission differs");
                    }
                    return requestAcknowledged
                            ? completedPeerRequest
                            : null;
                }
                fragments[index] = part.plaintext;
                receivedFragments++;
                if (part.fragmentNumber == 1) {
                    firstInnerPayload =
                            part.firstInnerPayload;
                }
                if (receivedFragments
                        != fragments.length) {
                    return null;
                }
                ByteArrayOutputStream plaintext =
                        new ByteArrayOutputStream();
                for (byte[] fragment : fragments) {
                    if (fragment == null) {
                        return null;
                    }
                    plaintext.writeBytes(fragment);
                }
                response = parsePrivateNotifyPlaintext(
                        firstInnerPayload,
                        plaintext.toByteArray(),
                        PAIRING_NOTIFIES_WATCH_MESSAGE_ID);
            }
            if (completedPeerRequest != null) {
                response.destroy();
                throw new IllegalArgumentException(
                        "Watch sent duplicate private-notify request");
            }
            completedPeerRequest = response;
            return requestAcknowledged
                    ? completedPeerRequest
                    : null;
        }

        boolean requestAcknowledged() {
            return requestAcknowledged;
        }

        private static boolean
                isProtectedInformationalHeader(
                byte[] packet,
                int directionFlags,
                int messageId) {
            return packet != null
                    && packet.length >= IKE_HEADER_LENGTH
                    && unsigned(packet[18])
                    == EXCHANGE_INFORMATIONAL
                    && (unsigned(packet[19])
                    & (IKE_FLAG_INITIATOR | IKE_FLAG_RESPONSE))
                    == directionFlags
                    && be32(packet, 20) == messageId;
        }
    }

    static SecretWithEncapsulation encapsulateForTest(
            SecureRandom random,
            byte[] publicKey) {
        MLKEMPublicKeyParameters parameters =
                new MLKEMPublicKeyParameters(
                        MLKEMParameters.ml_kem_1024,
                        publicKey);
        return new MLKEMGenerator(random)
                .generateEncapsulated(parameters);
    }
}
