package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.rfc7748.X448;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * Initial/intermediate/protected packet encryption, decryption, ML-KEM-1024 key
 * exchange, and PRF/KDF expansion for {@link IkeV2SessionCrypto}.
 */
final class IkeV2ProtectedPacketCrypto {
    private static final int FRAGMENT_FIELDS_LENGTH = 4;
    private static final int AES_GCM_TAG_LENGTH = 16;
    private static final int AES_256_KEY_LENGTH = 32;
    private static final int AES_GCM_SALT_LENGTH = 4;

    private IkeV2ProtectedPacketCrypto() {
    }

    static byte[] computeIntermediateIntAuthForProfile(
            IkeV2SessionCrypto.IkeSaKeys keys,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        return IkeV2SaInitCodec.computeIntermediateIntAuthForProfile(
                keys, response, firstInnerPayload, protectedPayloadFlags, plaintext);
    }

    static byte[] buildIntermediateIntAuthInput(
            byte[] initiatorSpi,
            byte[] responderSpi,
            boolean response,
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext) {
        return IkeV2SaInitCodec.buildIntermediateIntAuthInput(
                initiatorSpi, responderSpi, response, firstInnerPayload, protectedPayloadFlags, plaintext);
    }

    static byte[] buildKePayload(byte[] keyExchangeData) {
        return IkeV2SaInitCodec.buildKePayload(keyExchangeData);
    }




    static IkeV2SessionCrypto.IkeSaKeys deriveInitialKeys(
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response) {
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

    static IkeV2SessionCrypto.IkeSaKeys deriveInitialX448Keys(
            byte[] localX448PrivateKey,
            byte[] peerX448PublicKey,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] initiatorNonce,
            byte[] responderNonce) {
        IkeV2SessionCrypto.requireLength(
                "local X448 private key",
                localX448PrivateKey,
                56);
        IkeV2SessionCrypto.requireLength(
                "peer X448 public key",
                peerX448PublicKey,
                56);
        IkeV2SessionCrypto.requireLength("initiator SPI", initiatorSpi, 8);
        IkeV2SessionCrypto.requireLength("responder SPI", responderSpi, 8);
        IkeV2SessionCrypto.requireLength("initiator nonce", initiatorNonce, 32);
        IkeV2SessionCrypto.requireLength("responder nonce", responderNonce, 32);
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
            nonces = IkeV2SessionCrypto.concatenate(
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
            IkeV2SessionCrypto.wipe(sharedSecret);
            IkeV2SessionCrypto.wipe(nonces);
            IkeV2SessionCrypto.wipe(skeyseed);
        }
    }

    static IkeV2SessionCrypto.AdditionalKeRequest createAdditionalKeRequest(
            SecureRandom random,
            IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys initialKeys) {
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
        IkeV2SessionCrypto.requireLength(
                "ML-KEM-1024 public key",
                encodedPublicKey,
                IkeV2SessionCrypto.ML_KEM_1024_PUBLIC_KEY_LENGTH);

        byte[] kePayload =
                buildKePayload(encodedPublicKey);
        byte[] intAuthI = IkeV2SaInitCodec.computeIntermediateIntAuth(
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
                IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
        return new IkeV2SessionCrypto.AdditionalKeRequest(
                privateKey,
                encodedPublicKey,
                packets,
                intAuthI);
    }

    static IkeV2SessionCrypto.AdditionalKeResult completeAdditionalKeyExchange(
            IkeV2SessionCrypto.AdditionalKeRequest request,
            byte[] responderCiphertext,
            IkeV2SessionCrypto.IkeSaKeys initialKeys) {
        if (request == null || initialKeys == null) {
            throw new IllegalArgumentException(
                    "Additional-KE state is required");
        }
        IkeV2SessionCrypto.requireLength(
                "ML-KEM-1024 ciphertext",
                responderCiphertext,
                IkeV2SessionCrypto.ML_KEM_1024_CIPHERTEXT_LENGTH);
        MLKEMExtractor extractor =
                new MLKEMExtractor(request.privateKey);
        byte[] sharedSecret =
                extractor.extractSecret(responderCiphertext);
        IkeV2SessionCrypto.requireLength(
                "ML-KEM-1024 shared secret",
                sharedSecret,
                IkeV2SessionCrypto.ML_KEM_SHARED_SECRET_LENGTH);

        IkeV2SessionCrypto.IkeSaKeys updatedKeys =
                updateKeysWithAdditionalSecret(
                        initialKeys,
                        sharedSecret);
        return new IkeV2SessionCrypto.AdditionalKeResult(
                sharedSecret,
                updatedKeys);
    }

    static IkeV2SessionCrypto.IkeSaKeys updateKeysWithAdditionalSecret(
            IkeV2SessionCrypto.IkeSaKeys initialKeys,
            byte[] additionalSharedSecret) {
        if (initialKeys == null) {
            throw new IllegalArgumentException(
                    "Initial IKE keys are required");
        }
        IkeV2SessionCrypto.requireLength(
                "additional shared secret",
                additionalSharedSecret,
                IkeV2SessionCrypto.ML_KEM_SHARED_SECRET_LENGTH);
        byte[] updateInput = null;
        byte[] updatedSkeyseed = null;
        try {
            updateInput = IkeV2SessionCrypto.concatenate(
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
            IkeV2SessionCrypto.wipe(updateInput);
            IkeV2SessionCrypto.wipe(updatedSkeyseed);
        }
    }

    /**
     * Applies the mandatory salted-PIN PPK after the additional key exchange.
     * Encryption keys are unchanged; only SK_d, SK_pi, and SK_pr are replaced.
     */
    static IkeV2SessionCrypto.IkeSaKeys applyMandatoryPairingPpk(
            IkeV2SessionCrypto.IkeSaKeys primeKeys,
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
            return new IkeV2SessionCrypto.IkeSaKeys(
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
            IkeV2SessionCrypto.wipe(skD);
            IkeV2SessionCrypto.wipe(skPi);
            IkeV2SessionCrypto.wipe(skPr);
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
                IkeV2SessionCrypto.EXCHANGE_IKE_INTERMEDIATE,
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
                        ? IkeV2SessionCrypto.IKE_FLAG_RESPONSE
                        : IkeV2SessionCrypto.IKE_FLAG_INITIATOR,
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
                        ? IkeV2SessionCrypto.IKE_FLAG_RESPONSE
                        : IkeV2SessionCrypto.IKE_FLAG_INITIATOR,
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
        IkeV2SessionCrypto.requireLength("initiator SPI", initiatorSpi, 8);
        IkeV2SessionCrypto.requireLength("responder SPI", responderSpi, 8);
        IkeV2SessionCrypto.requireLength(
                "AES-GCM-256 key material",
                encryptionKeyMaterial,
                AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH);
        if (random == null
                || exchangeType <= 0
                || messageId < 0
                || (directionFlags
                & ~(IkeV2SessionCrypto.IKE_FLAG_INITIATOR | IkeV2SessionCrypto.IKE_FLAG_RESPONSE)) != 0) {
            throw new IllegalArgumentException(
                    "Empty protected exchange state is invalid");
        }
        byte[] iv = new byte[IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH];
        random.nextBytes(iv);
        return encryptProtectedPacket(
                initiatorSpi,
                responderSpi,
                directionFlags,
                exchangeType,
                messageId,
                IkeV2SessionCrypto.PAYLOAD_SK,
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
        IkeV2SessionCrypto.requireLength("initiator SPI", initiatorSpi, 8);
        IkeV2SessionCrypto.requireLength("responder SPI", responderSpi, 8);
        IkeV2SessionCrypto.requireLength(
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
                & ~(IkeV2SessionCrypto.IKE_FLAG_INITIATOR | IkeV2SessionCrypto.IKE_FLAG_RESPONSE)) != 0) {
            throw new IllegalArgumentException(
                    "Protected plaintext, exchange, message ID, "
                            + "direction, and randomness are required");
        }
        int unfragmentedLength =
                IkeV2SessionCrypto.IKE_HEADER_LENGTH
                        + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                        + IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH
                        + innerPayloadBytes.length
                        + 1
                        + AES_GCM_TAG_LENGTH;
        if (unfragmentedLength <= maximumPacketSize) {
            byte[] iv = new byte[IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH];
            random.nextBytes(iv);
            return List.of(encryptProtectedPacket(
                    initiatorSpi,
                    responderSpi,
                    directionFlags,
                    exchangeType,
                    messageId,
                    IkeV2SessionCrypto.PAYLOAD_SK,
                    firstInnerPayload,
                    0,
                    0,
                    innerPayloadBytes,
                    encryptionKeyMaterial,
                    iv));
        }

        int fragmentContentLimit =
                maximumPacketSize
                        - IkeV2SessionCrypto.IKE_HEADER_LENGTH
                        - IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                        - FRAGMENT_FIELDS_LENGTH
                        - IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH
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
            byte[] iv = new byte[IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH];
            random.nextBytes(iv);
            packets.add(encryptProtectedPacket(
                    initiatorSpi,
                    responderSpi,
                    directionFlags,
                    exchangeType,
                    messageId,
                    IkeV2SessionCrypto.PAYLOAD_SKF,
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

    static byte[] encryptProtectedPacket(
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
                IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                        + (protectedPayloadType == IkeV2SessionCrypto.PAYLOAD_SKF
                        ? FRAGMENT_FIELDS_LENGTH
                        : 0);
        byte[] paddedPlaintext =
                Arrays.copyOf(plaintextChunk, plaintextChunk.length + 1);
        paddedPlaintext[paddedPlaintext.length - 1] = 0;
        int protectedPayloadLength =
                subHeaderLength
                        + IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH
                        + paddedPlaintext.length
                        + AES_GCM_TAG_LENGTH;
        int packetLength =
                IkeV2SessionCrypto.IKE_HEADER_LENGTH + protectedPayloadLength;

        ByteArrayOutputStream authenticatedHeaders =
                new ByteArrayOutputStream(
                        IkeV2SessionCrypto.IKE_HEADER_LENGTH + subHeaderLength);
        authenticatedHeaders.writeBytes(initiatorSpi);
        authenticatedHeaders.writeBytes(responderSpi);
        authenticatedHeaders.write(protectedPayloadType);
        authenticatedHeaders.write(0x20);
        authenticatedHeaders.write(exchangeType);
        authenticatedHeaders.write(directionFlags);
        IkeV2SessionCrypto.writeBe32(authenticatedHeaders, messageId);
        IkeV2SessionCrypto.writeBe32(authenticatedHeaders, packetLength);
        authenticatedHeaders.write(firstInnerPayload);
        authenticatedHeaders.write(0);
        IkeV2SessionCrypto.writeBe16(authenticatedHeaders, protectedPayloadLength);
        if (protectedPayloadType == IkeV2SessionCrypto.PAYLOAD_SKF) {
            IkeV2SessionCrypto.writeBe16(authenticatedHeaders, fragmentNumber);
            IkeV2SessionCrypto.writeBe16(authenticatedHeaders, totalFragments);
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

    static IkeV2SessionCrypto.DecryptedIntermediatePart decryptProtectedIntermediate(
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
                IkeV2SessionCrypto.EXCHANGE_IKE_INTERMEDIATE,
                1);
    }

    static IkeV2SessionCrypto.DecryptedIntermediatePart decryptProtectedPacket(
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
                        ? IkeV2SessionCrypto.IKE_FLAG_RESPONSE
                        : IkeV2SessionCrypto.IKE_FLAG_INITIATOR,
                expectedExchangeType,
                expectedMessageId);
    }

    static IkeV2SessionCrypto.DecryptedIntermediatePart
            decryptProtectedPacketWithDirectionFlags(
            byte[] packet,
            byte[] expectedInitiatorSpi,
            byte[] expectedResponderSpi,
            byte[] encryptionKeyMaterial,
            int expectedDirectionFlags,
            int expectedExchangeType,
            int expectedMessageId) {
        IkeV2SessionCrypto.requireLength("initiator SPI", expectedInitiatorSpi, 8);
        IkeV2SessionCrypto.requireLength("responder SPI", expectedResponderSpi, 8);
        IkeV2SessionCrypto.requireLength(
                "AES-GCM-256 key material",
                encryptionKeyMaterial,
                AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH);
        if (packet == null || packet.length < IkeV2SessionCrypto.IKE_HEADER_LENGTH + 4) {
            throw new IllegalArgumentException(
                    "Protected IKE packet is truncated");
        }
        boolean initiatorSpiMatches =
                IkeV2SessionCrypto.matchesRange(
                        packet,
                        0,
                        expectedInitiatorSpi);
        boolean responderSpiMatches =
                IkeV2SessionCrypto.matchesRange(
                        packet,
                        8,
                        expectedResponderSpi);
        int version = IkeV2SessionCrypto.unsigned(packet[17]);
        int exchangeType = IkeV2SessionCrypto.unsigned(packet[18]);
        int messageId = IkeV2SessionCrypto.be32(packet, 20);
        int declaredLength = IkeV2SessionCrypto.be32(packet, 24);
        if (!initiatorSpiMatches
                || !responderSpiMatches
                || version != 0x20
                || exchangeType != expectedExchangeType
                || messageId != expectedMessageId
                || declaredLength != packet.length
                || (expectedDirectionFlags
                & ~(IkeV2SessionCrypto.IKE_FLAG_INITIATOR | IkeV2SessionCrypto.IKE_FLAG_RESPONSE)) != 0) {
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
        int flags = IkeV2SessionCrypto.unsigned(packet[19]);
        if ((flags & (IkeV2SessionCrypto.IKE_FLAG_INITIATOR | IkeV2SessionCrypto.IKE_FLAG_RESPONSE))
                != expectedDirectionFlags) {
            throw new IllegalArgumentException(
                    "Unexpected protected IKE direction flags");
        }

        int protectedPayloadType = IkeV2SessionCrypto.unsigned(packet[16]);
        if (protectedPayloadType != IkeV2SessionCrypto.PAYLOAD_SK
                && protectedPayloadType != IkeV2SessionCrypto.PAYLOAD_SKF) {
            throw new IllegalArgumentException(
                    "IKE packet is not encrypted");
        }
        int payloadOffset = IkeV2SessionCrypto.IKE_HEADER_LENGTH;
        int payloadLength = IkeV2SessionCrypto.be16(packet, payloadOffset + 2);
        if (payloadOffset + payloadLength != packet.length) {
            throw new IllegalArgumentException(
                    "Protected payload length mismatch");
        }
        int firstInnerPayload = IkeV2SessionCrypto.unsigned(packet[payloadOffset]);
        int protectedPayloadFlags = IkeV2SessionCrypto.unsigned(packet[payloadOffset + 1]);
        int fragmentNumber = 0;
        int totalFragments = 0;
        int subHeaderLength = IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH;
        if (protectedPayloadType == IkeV2SessionCrypto.PAYLOAD_SKF) {
            if (payloadLength
                    < IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
                    + FRAGMENT_FIELDS_LENGTH
                    + IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH
                    + 1
                    + AES_GCM_TAG_LENGTH) {
                throw new IllegalArgumentException(
                        "Encrypted fragment is truncated");
            }
            fragmentNumber = IkeV2SessionCrypto.be16(
                    packet,
                    payloadOffset + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH);
            totalFragments = IkeV2SessionCrypto.be16(
                    packet,
                    payloadOffset
                            + IkeV2SessionCrypto.GENERIC_PAYLOAD_HEADER_LENGTH
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
                + IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH
                + 1
                + AES_GCM_TAG_LENGTH > packet.length) {
            throw new IllegalArgumentException(
                    "Encrypted payload body is truncated");
        }
        byte[] aad = Arrays.copyOfRange(packet, 0, ivOffset);
        byte[] explicitIv = Arrays.copyOfRange(
                packet,
                ivOffset,
                ivOffset + IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH);
        byte[] ciphertextAndTag = Arrays.copyOfRange(
                packet,
                ivOffset + IkeV2SessionCrypto.AES_GCM_EXPLICIT_IV_LENGTH,
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
                IkeV2SessionCrypto.unsigned(paddedPlaintext[paddedPlaintext.length - 1]);
        if (padLength + 1 > paddedPlaintext.length) {
            throw new IllegalArgumentException(
                    "Encrypted payload padding exceeds plaintext");
        }
        byte[] plaintext = Arrays.copyOf(
                paddedPlaintext,
                paddedPlaintext.length - padLength - 1);
        return new IkeV2SessionCrypto.DecryptedIntermediatePart(
                protectedPayloadType == IkeV2SessionCrypto.PAYLOAD_SKF,
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
        byte[] nonce = IkeV2SessionCrypto.concatenate(
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

    private static IkeV2SessionCrypto.IkeSaKeys expandKeys(
            byte[] skeyseed,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] initiatorNonce,
            byte[] responderNonce) {
        byte[] seed = IkeV2SessionCrypto.concatenate(
                initiatorNonce,
                responderNonce,
                initiatorSpi,
                responderSpi);
        int totalLength =
                IkeV2SessionCrypto.PRF_SHA512_LENGTH
                        + 2 * (AES_256_KEY_LENGTH + AES_GCM_SALT_LENGTH)
                        + 2 * IkeV2SessionCrypto.PRF_SHA512_LENGTH;
        byte[] keyMaterial =
                prfPlus(skeyseed, seed, totalLength);
        int offset = 0;
        byte[] skD = Arrays.copyOfRange(
                keyMaterial,
                offset,
                offset + IkeV2SessionCrypto.PRF_SHA512_LENGTH);
        offset += IkeV2SessionCrypto.PRF_SHA512_LENGTH;
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
                offset + IkeV2SessionCrypto.PRF_SHA512_LENGTH);
        offset += IkeV2SessionCrypto.PRF_SHA512_LENGTH;
        byte[] skPr = Arrays.copyOfRange(
                keyMaterial,
                offset,
                offset + IkeV2SessionCrypto.PRF_SHA512_LENGTH);
        offset += IkeV2SessionCrypto.PRF_SHA512_LENGTH;
        if (offset != keyMaterial.length) {
            throw new IllegalStateException(
                    "IKE key material slicing failed");
        }
        return new IkeV2SessionCrypto.IkeSaKeys(
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
                    IkeV2SessionCrypto.concatenate(
                            previous,
                            seed,
                            new byte[]{(byte) counter}));
            output.writeBytes(previous);
            counter++;
        }
        return Arrays.copyOf(output.toByteArray(), outputLength);
    }

    static byte[] prf(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] output = new byte[hmac.getMacSize()];
        hmac.doFinal(output, 0);
        return output;
    }









































}
