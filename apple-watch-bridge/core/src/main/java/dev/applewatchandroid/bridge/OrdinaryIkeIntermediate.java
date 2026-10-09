package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import javax.security.auth.DestroyFailedException;

/**
 * One ordinary IKE_INTERMEDIATE round trip for ADDKE1/ML-KEM-768.
 *
 * <p>IntAuth is computed over the canonical plaintext representation with
 * the pre-update SK_pi/SK_pr. The resulting ML-KEM secret then updates the
 * complete IKE key schedule for IKE_AUTH.</p>
 */
final class OrdinaryIkeIntermediate {
    static final int PUBLIC_KEY_LENGTH = 1184;
    static final int PRIVATE_KEY_LENGTH = 2400;
    static final int CIPHERTEXT_LENGTH = 1088;
    static final int SHARED_SECRET_LENGTH = 32;
    static final int REQUEST_PACKET_LENGTH = 1249;
    static final int RESPONSE_PACKET_LENGTH = 1153;
    static final int REQUEST_CANONICAL_LENGTH = 1224;
    static final int RESPONSE_CANONICAL_LENGTH = 1128;

    private static final int MAXIMUM_PACKET_SIZE = 1280;

    private OrdinaryIkeIntermediate() {
    }

    static InitiatorRequest createInitiatorRequest(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys initialKeys) {
        requireState(random, initialKeys);
        MLKEMKeyPairGenerator generator =
                new MLKEMKeyPairGenerator();
        generator.init(
                new MLKEMKeyGenerationParameters(
                        random,
                        MLKEMParameters.ml_kem_768));
        AsymmetricCipherKeyPair pair =
                generator.generateKeyPair();
        MLKEMPrivateKeyParameters privateKey =
                (MLKEMPrivateKeyParameters) pair.getPrivate();
        byte[] publicKey =
                ((MLKEMPublicKeyParameters) pair.getPublic())
                        .getEncoded();
        requireLength(
                "ML-KEM-768 public key",
                publicKey,
                PUBLIC_KEY_LENGTH);
        byte[] plaintext = buildKePayload(publicKey);
        byte[] intAuthI =
                IkeV2SessionCrypto
                        .computeIntermediateIntAuthForProfile(
                                initialKeys,
                                false,
                                IkeV2Codec.PAYLOAD_KE,
                                0,
                                plaintext);
        List<byte[]> packets =
                IkeV2SessionCrypto
                        .encryptIntermediatePayload(
                                random,
                                initialKeys.initiatorSpi,
                                initialKeys.responderSpi,
                                initialKeys.skEi,
                                false,
                                plaintext,
                                IkeV2Codec.PAYLOAD_KE,
                                MAXIMUM_PACKET_SIZE);
        if (packets.size() != 1
                || packets.get(0).length
                != REQUEST_PACKET_LENGTH) {
            throw new IllegalStateException(
                    "Ordinary ML-KEM-768 request unexpectedly fragmented");
        }
        return new InitiatorRequest(
                privateKey,
                publicKey,
                plaintext,
                packets.get(0),
                intAuthI);
    }

    static ResponderResult respond(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys initialKeys,
            byte[] requestPacket) {
        requireState(random, initialKeys);
        IkeV2SessionCrypto.DecryptedIntermediatePart request =
                IkeV2SessionCrypto
                        .decryptProtectedIntermediate(
                                requestPacket,
                                initialKeys.initiatorSpi,
                                initialKeys.responderSpi,
                                initialKeys.skEi,
                                false);
        requireUnfragmented(request, "request");
        byte[] publicKey =
                parseKePayload(
                        request.firstInnerPayload,
                        request.protectedPayloadFlags,
                        request.plaintext,
                        PUBLIC_KEY_LENGTH);
        byte[] intAuthI =
                IkeV2SessionCrypto
                        .computeIntermediateIntAuthForProfile(
                                initialKeys,
                                false,
                                request.firstInnerPayload,
                                request.protectedPayloadFlags,
                                request.plaintext);

        MLKEMPublicKeyParameters peerPublic =
                new MLKEMPublicKeyParameters(
                        MLKEMParameters.ml_kem_768,
                        publicKey);
        SecretWithEncapsulation encapsulated =
                new MLKEMGenerator(random)
                        .generateEncapsulated(peerPublic);
        byte[] ciphertext =
                encapsulated.getEncapsulation();
        byte[] sharedSecret =
                encapsulated.getSecret();
        byte[] responsePlaintext = null;
        byte[] intAuthR = null;
        byte[] responsePacket = null;
        IkeV2SessionCrypto.IkeSaKeys updatedKeys = null;
        try {
            requireLength(
                    "ML-KEM-768 ciphertext",
                    ciphertext,
                    CIPHERTEXT_LENGTH);
            requireLength(
                    "ML-KEM-768 shared secret",
                    sharedSecret,
                    SHARED_SECRET_LENGTH);
            responsePlaintext =
                    buildKePayload(ciphertext);
            intAuthR =
                    IkeV2SessionCrypto
                            .computeIntermediateIntAuthForProfile(
                                    initialKeys,
                                    true,
                                    IkeV2Codec.PAYLOAD_KE,
                                    0,
                                    responsePlaintext);
            List<byte[]> packets =
                    IkeV2SessionCrypto
                            .encryptIntermediatePayload(
                                    random,
                                    initialKeys.initiatorSpi,
                                    initialKeys.responderSpi,
                                    initialKeys.skEr,
                                    true,
                                    responsePlaintext,
                                    IkeV2Codec.PAYLOAD_KE,
                                    MAXIMUM_PACKET_SIZE);
            if (packets.size() != 1
                    || packets.get(0).length
                    != RESPONSE_PACKET_LENGTH) {
                throw new IllegalStateException(
                        "Ordinary ML-KEM-768 response "
                                + "unexpectedly fragmented");
            }
            responsePacket = packets.get(0);
            updatedKeys =
                    IkeV2SessionCrypto
                            .updateKeysWithAdditionalSecret(
                                    initialKeys,
                                    sharedSecret);
            return new ResponderResult(
                    responsePlaintext,
                    responsePacket,
                    intAuthI,
                    intAuthR,
                    updatedKeys);
        } finally {
            destroyEncapsulatedSecret(encapsulated);
            wipe(publicKey);
            wipe(ciphertext);
            wipe(sharedSecret);
            wipe(responsePlaintext);
            wipe(intAuthI);
            wipe(intAuthR);
            wipe(responsePacket);
            // Ownership of updatedKeys transfers only through the result.
        }
    }

    static InitiatorResult completeInitiator(
            InitiatorRequest request,
            IkeV2SessionCrypto.IkeSaKeys initialKeys,
            byte[] responsePacket) {
        if (request == null
                || request.privateKey == null
                || initialKeys == null) {
            throw new IllegalArgumentException(
                    "Complete ordinary intermediate state is required");
        }
        IkeV2SessionCrypto.DecryptedIntermediatePart response =
                IkeV2SessionCrypto
                        .decryptProtectedIntermediate(
                                responsePacket,
                                initialKeys.initiatorSpi,
                                initialKeys.responderSpi,
                                initialKeys.skEr,
                                true);
        requireUnfragmented(response, "response");
        byte[] ciphertext =
                parseKePayload(
                        response.firstInnerPayload,
                        response.protectedPayloadFlags,
                        response.plaintext,
                        CIPHERTEXT_LENGTH);
        byte[] intAuthR =
                IkeV2SessionCrypto
                        .computeIntermediateIntAuthForProfile(
                                initialKeys,
                                true,
                                response.firstInnerPayload,
                                response.protectedPayloadFlags,
                                response.plaintext);
        byte[] sharedSecret = null;
        IkeV2SessionCrypto.IkeSaKeys updatedKeys = null;
        try {
            sharedSecret =
                    new MLKEMExtractor(request.privateKey)
                            .extractSecret(ciphertext);
            requireLength(
                    "ML-KEM-768 shared secret",
                    sharedSecret,
                    SHARED_SECRET_LENGTH);
            updatedKeys =
                    IkeV2SessionCrypto
                            .updateKeysWithAdditionalSecret(
                                    initialKeys,
                                    sharedSecret);
            return new InitiatorResult(
                    request.intAuthI,
                    intAuthR,
                    updatedKeys);
        } finally {
            wipe(ciphertext);
            wipe(intAuthR);
            wipe(sharedSecret);
            // Ownership of updatedKeys transfers only through the result.
        }
    }

    static byte[] buildKePayload(
            byte[] keyExchangeData) {
        if (keyExchangeData == null) {
            throw new IllegalArgumentException(
                    "ML-KEM exchange data is null");
        }
        int length = 8 + keyExchangeData.length;
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(length);
        output.write(IkeV2Codec.PAYLOAD_NONE);
        output.write(0);
        writeBe16(output, length);
        writeBe16(
                output,
                OrdinaryIkeSaInit
                        .ADDITIONAL_KE_METHOD_ML_KEM_768);
        writeBe16(output, 0);
        output.writeBytes(keyExchangeData);
        return output.toByteArray();
    }

    private static byte[] parseKePayload(
            int firstInnerPayload,
            int protectedPayloadFlags,
            byte[] plaintext,
            int expectedDataLength) {
        if (firstInnerPayload
                != IkeV2Codec.PAYLOAD_KE
                || protectedPayloadFlags != 0
                || plaintext == null
                || plaintext.length != 8 + expectedDataLength
                || unsigned(plaintext[0])
                != IkeV2Codec.PAYLOAD_NONE
                || unsigned(plaintext[1]) != 0
                || be16(plaintext, 2) != plaintext.length
                || be16(plaintext, 4)
                != OrdinaryIkeSaInit
                .ADDITIONAL_KE_METHOD_ML_KEM_768
                || be16(plaintext, 6) != 0) {
            throw new IllegalArgumentException(
                    "Ordinary ML-KEM-768 KE payload is invalid");
        }
        return Arrays.copyOfRange(
                plaintext,
                8,
                plaintext.length);
    }

    private static void requireUnfragmented(
            IkeV2SessionCrypto.DecryptedIntermediatePart part,
            String label) {
        if (part == null || part.fragmented) {
            throw new IllegalArgumentException(
                    "Ordinary ML-KEM-768 "
                            + label
                            + " must be unfragmented");
        }
    }

    private static void requireState(
            SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys keys) {
        if (random == null || keys == null) {
            throw new IllegalArgumentException(
                    "SecureRandom and initial IKE keys are required");
        }
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

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void destroyEncapsulatedSecret(
            SecretWithEncapsulation value) {
        try {
            value.destroy();
        } catch (DestroyFailedException error) {
            throw new IllegalStateException(
                    "Unable to destroy the temporary ML-KEM secret",
                    error);
        }
    }

    static final class InitiatorRequest {
        private MLKEMPrivateKeyParameters privateKey;
        final byte[] publicKey;
        final byte[] plaintext;
        final byte[] packet;
        final byte[] intAuthI;

        InitiatorRequest(
                MLKEMPrivateKeyParameters privateKey,
                byte[] publicKey,
                byte[] plaintext,
                byte[] packet,
                byte[] intAuthI) {
            this.privateKey = privateKey;
            this.publicKey = publicKey.clone();
            this.plaintext = plaintext.clone();
            this.packet = packet.clone();
            this.intAuthI = intAuthI.clone();
        }

        void destroy() {
            privateKey = null;
            wipe(publicKey);
            wipe(plaintext);
            wipe(packet);
            wipe(intAuthI);
        }
    }

    static final class ResponderResult {
        final byte[] plaintext;
        final byte[] packet;
        final byte[] intAuthI;
        final byte[] intAuthR;
        final IkeV2SessionCrypto.IkeSaKeys updatedKeys;

        ResponderResult(
                byte[] plaintext,
                byte[] packet,
                byte[] intAuthI,
                byte[] intAuthR,
                IkeV2SessionCrypto.IkeSaKeys updatedKeys) {
            this.plaintext = plaintext.clone();
            this.packet = packet.clone();
            this.intAuthI = intAuthI.clone();
            this.intAuthR = intAuthR.clone();
            this.updatedKeys = updatedKeys;
        }

        void destroy() {
            wipe(plaintext);
            wipe(packet);
            wipe(intAuthI);
            wipe(intAuthR);
            updatedKeys.destroy();
        }
    }

    static final class InitiatorResult {
        final byte[] intAuthI;
        final byte[] intAuthR;
        final IkeV2SessionCrypto.IkeSaKeys updatedKeys;

        InitiatorResult(
                byte[] intAuthI,
                byte[] intAuthR,
                IkeV2SessionCrypto.IkeSaKeys updatedKeys) {
            this.intAuthI = intAuthI.clone();
            this.intAuthR = intAuthR.clone();
            this.updatedKeys = updatedKeys;
        }

        void destroy() {
            wipe(intAuthI);
            wipe(intAuthR);
            updatedKeys.destroy();
        }
    }
}
