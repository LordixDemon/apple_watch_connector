package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Modern Apple NetworkRelay encrypted IKEv2 Key ID.
 *
 * <p>The exact HPKE suite and context were recovered from iOS 26.6
 * {@code NetworkExtension}. Apple uses PSK-mode RFC 9180 HPKE with
 * X25519/HKDF-SHA256, HKDF-SHA512, and AES-256-GCM. The wire value is
 * the 32-byte encapsulated key followed by the ciphertext and tag.</p>
 */
final class AppleIkeEncryptedKeyId {
    static final int SPI_LENGTH = 8;
    static final int NONCE_LENGTH = 32;
    static final int IDENTITY_UUID_LENGTH = 16;
    static final int PLAINTEXT_LENGTH = 96;
    static final int RANDOM_PADDING_LENGTH =
            PLAINTEXT_LENGTH - 1 - IDENTITY_UUID_LENGTH;
    static final int ENCAPSULATED_KEY_LENGTH = 32;
    static final int AES_GCM_TAG_LENGTH = 16;
    static final int CIPHERTEXT_LENGTH =
            PLAINTEXT_LENGTH + AES_GCM_TAG_LENGTH;
    static final int ENCRYPTED_IDENTIFIER_LENGTH =
            ENCAPSULATED_KEY_LENGTH + CIPHERTEXT_LENGTH;
    static final int ID_TYPE_KEY_ID = 11;
    static final int ID_PAYLOAD_BODY_LENGTH =
            4 + ENCRYPTED_IDENTIFIER_LENGTH;
    static final int IKE_ID_PAYLOAD_LENGTH =
            4 + ID_PAYLOAD_BODY_LENGTH;
    static final int PLAINTEXT_VERSION = 1;

    static final String INFO_STRING =
            "Encrypted Identifier for IKEv2";

    private static final int KEM_ID_X25519_HKDF_SHA256 = 0x0020;
    private static final int KDF_ID_HKDF_SHA512 = 0x0003;
    private static final int AEAD_ID_AES_256_GCM = 0x0002;
    private static final int HPKE_MODE_PSK = 0x01;
    private static final int SHA256_LENGTH = 32;
    private static final int SHA512_LENGTH = 64;
    private static final int AES_256_KEY_LENGTH = 32;
    private static final int AES_GCM_NONCE_LENGTH = 12;
    private static final byte[] HPKE_VERSION_LABEL =
            "HPKE-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEM_SUITE_ID = concatenate(
            "KEM".getBytes(StandardCharsets.US_ASCII),
            i2osp16(KEM_ID_X25519_HKDF_SHA256));
    private static final byte[] HPKE_SUITE_ID = concatenate(
            "HPKE".getBytes(StandardCharsets.US_ASCII),
            i2osp16(KEM_ID_X25519_HKDF_SHA256),
            i2osp16(KDF_ID_HKDF_SHA512),
            i2osp16(AEAD_ID_AES_256_GCM));
    private static final byte[] INFO =
            INFO_STRING.getBytes(StandardCharsets.US_ASCII);

    private AppleIkeEncryptedKeyId() {
    }

    static byte[] createPlaintext(
            byte[] identityUuid,
            SecureRandom random) {
        requireLength(
                "identity UUID",
                identityUuid,
                IDENTITY_UUID_LENGTH);
        if (random == null) {
            throw new IllegalArgumentException(
                    "SecureRandom is required");
        }
        byte[] plaintext = new byte[PLAINTEXT_LENGTH];
        plaintext[0] = PLAINTEXT_VERSION;
        System.arraycopy(
                identityUuid,
                0,
                plaintext,
                1,
                identityUuid.length);
        byte[] padding = new byte[RANDOM_PADDING_LENGTH];
        try {
            random.nextBytes(padding);
            System.arraycopy(
                    padding,
                    0,
                    plaintext,
                    1 + identityUuid.length,
                    padding.length);
            return plaintext;
        } finally {
            wipe(padding);
        }
    }

    static byte[] extractAndValidateIdentityUuid(
            byte[] plaintext,
            byte[] expectedIdentityUuid) {
        requireLength(
                "encrypted identifier plaintext",
                plaintext,
                PLAINTEXT_LENGTH);
        requireLength(
                "expected identity UUID",
                expectedIdentityUuid,
                IDENTITY_UUID_LENGTH);
        if (unsigned(plaintext[0]) != PLAINTEXT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported encrypted identifier version");
        }
        byte[] identityUuid = Arrays.copyOfRange(
                plaintext,
                1,
                1 + IDENTITY_UUID_LENGTH);
        if (!MessageDigest.isEqual(
                identityUuid,
                expectedIdentityUuid)) {
            wipe(identityUuid);
            throw new IllegalArgumentException(
                    "Encrypted identifier identity UUID mismatch");
        }
        return identityUuid;
    }

    static byte[] seal(
            byte[] recipientPublicX25519,
            String serviceKeyId,
            byte[] initiatorNonce,
            byte[] responderNonce,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] plaintext,
            SecureRandom random) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "SecureRandom is required");
        }
        X25519PrivateKeyParameters ephemeralPrivate =
                new X25519PrivateKeyParameters(random);
        byte[] ephemeralPrivateBytes =
                ephemeralPrivate.getEncoded();
        try {
            return sealWithEphemeralPrivateKey(
                    recipientPublicX25519,
                    serviceKeyId,
                    initiatorNonce,
                    responderNonce,
                    initiatorSpi,
                    responderSpi,
                    plaintext,
                    ephemeralPrivateBytes);
        } finally {
            wipe(ephemeralPrivateBytes);
        }
    }

    /**
     * Deterministic entry point used for cross-implementation vectors.
     */
    static byte[] sealWithEphemeralPrivateKey(
            byte[] recipientPublicX25519,
            String serviceKeyId,
            byte[] initiatorNonce,
            byte[] responderNonce,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] plaintext,
            byte[] ephemeralPrivateX25519) {
        requireLength(
                "recipient X25519 public key",
                recipientPublicX25519,
                AppleNetworkRelayPairingMaterial.RAW_KEY_LENGTH);
        requireLength(
                "ephemeral X25519 private key",
                ephemeralPrivateX25519,
                AppleNetworkRelayPairingMaterial.RAW_KEY_LENGTH);
        requireLength(
                "encrypted identifier plaintext",
                plaintext,
                PLAINTEXT_LENGTH);

        byte[] aad = keyIdAad(serviceKeyId);
        byte[] psk = createPsk(
                initiatorNonce,
                responderNonce);
        byte[] pskId = createPskId(
                initiatorSpi,
                responderSpi);
        byte[] encapsulation = null;
        byte[] sharedSecret = null;
        HpkeKeySchedule schedule = null;
        byte[] ciphertext = null;
        try {
            X25519PrivateKeyParameters ephemeralPrivate =
                    new X25519PrivateKeyParameters(
                            ephemeralPrivateX25519,
                            0);
            X25519PublicKeyParameters recipientPublic =
                    new X25519PublicKeyParameters(
                            recipientPublicX25519,
                            0);
            encapsulation =
                    ephemeralPrivate
                            .generatePublicKey()
                            .getEncoded();
            sharedSecret = deriveKemSharedSecretForSender(
                    ephemeralPrivate,
                    recipientPublic,
                    encapsulation,
                    recipientPublicX25519);
            schedule = deriveKeySchedule(
                    sharedSecret,
                    INFO,
                    psk,
                    pskId);
            ciphertext = aesGcm(
                    true,
                    schedule.key,
                    schedule.baseNonce,
                    aad,
                    plaintext);
            if (ciphertext.length != CIPHERTEXT_LENGTH
                    || encapsulation.length
                    != ENCAPSULATED_KEY_LENGTH) {
                throw new IllegalStateException(
                        "HPKE returned an unexpected Apple "
                                + "encrypted identifier layout");
            }
            byte[] output =
                    new byte[ENCRYPTED_IDENTIFIER_LENGTH];
            System.arraycopy(
                    encapsulation,
                    0,
                    output,
                    0,
                    encapsulation.length);
            System.arraycopy(
                    ciphertext,
                    0,
                    output,
                    encapsulation.length,
                    ciphertext.length);
            return output;
        } catch (InvalidCipherTextException error) {
            throw new IllegalStateException(
                    "Unable to seal Apple encrypted identifier",
                    error);
        } finally {
            wipe(aad);
            wipe(psk);
            wipe(pskId);
            wipe(encapsulation);
            wipe(sharedSecret);
            wipe(ciphertext);
            if (schedule != null) {
                schedule.destroy();
            }
        }
    }

    static byte[] open(
            byte[] recipientPrivateX25519,
            String serviceKeyId,
            byte[] initiatorNonce,
            byte[] responderNonce,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encryptedIdentifier) {
        requireLength(
                "recipient X25519 private key",
                recipientPrivateX25519,
                AppleNetworkRelayPairingMaterial.RAW_KEY_LENGTH);
        requireLength(
                "encrypted identifier",
                encryptedIdentifier,
                ENCRYPTED_IDENTIFIER_LENGTH);

        byte[] aad = keyIdAad(serviceKeyId);
        byte[] psk = createPsk(
                initiatorNonce,
                responderNonce);
        byte[] pskId = createPskId(
                initiatorSpi,
                responderSpi);
        byte[] encapsulation = Arrays.copyOfRange(
                encryptedIdentifier,
                0,
                ENCAPSULATED_KEY_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(
                encryptedIdentifier,
                ENCAPSULATED_KEY_LENGTH,
                encryptedIdentifier.length);
        byte[] recipientPublicBytes = null;
        byte[] sharedSecret = null;
        HpkeKeySchedule schedule = null;
        try {
            X25519PrivateKeyParameters recipientPrivate =
                    new X25519PrivateKeyParameters(
                            recipientPrivateX25519,
                            0);
            recipientPublicBytes =
                    recipientPrivate
                            .generatePublicKey()
                            .getEncoded();
            sharedSecret = deriveKemSharedSecretForReceiver(
                    recipientPrivate,
                    encapsulation,
                    recipientPublicBytes);
            schedule = deriveKeySchedule(
                    sharedSecret,
                    INFO,
                    psk,
                    pskId);
            byte[] plaintext = aesGcm(
                    false,
                    schedule.key,
                    schedule.baseNonce,
                    aad,
                    ciphertext);
            if (plaintext == null
                    || plaintext.length != PLAINTEXT_LENGTH) {
                wipe(plaintext);
                throw new IllegalArgumentException(
                        "HPKE returned an unexpected Apple "
                                + "encrypted identifier plaintext");
            }
            return plaintext;
        } catch (InvalidCipherTextException error) {
            throw new IllegalArgumentException(
                    "Apple encrypted identifier authentication failed",
                    error);
        } finally {
            wipe(aad);
            wipe(psk);
            wipe(pskId);
            wipe(encapsulation);
            wipe(ciphertext);
            wipe(recipientPublicBytes);
            wipe(sharedSecret);
            if (schedule != null) {
                schedule.destroy();
            }
        }
    }

    static byte[] createPsk(
            byte[] initiatorNonce,
            byte[] responderNonce) {
        requireLength(
                "initiator nonce",
                initiatorNonce,
                NONCE_LENGTH);
        requireLength(
                "responder nonce",
                responderNonce,
                NONCE_LENGTH);
        return concatenate(
                initiatorNonce,
                responderNonce);
    }

    static byte[] createPskId(
            byte[] initiatorSpi,
            byte[] responderSpi) {
        requireLength(
                "initiator SPI",
                initiatorSpi,
                SPI_LENGTH);
        requireLength(
                "responder SPI",
                responderSpi,
                SPI_LENGTH);
        return concatenate(
                initiatorSpi,
                responderSpi);
    }

    static byte[] buildIdPayloadBody(
            byte[] encryptedIdentifier) {
        requireLength(
                "encrypted identifier",
                encryptedIdentifier,
                ENCRYPTED_IDENTIFIER_LENGTH);
        byte[] output =
                new byte[ID_PAYLOAD_BODY_LENGTH];
        output[0] = ID_TYPE_KEY_ID;
        System.arraycopy(
                encryptedIdentifier,
                0,
                output,
                4,
                encryptedIdentifier.length);
        return output;
    }

    static byte[] parseIdPayloadBody(
            byte[] payloadBody) {
        requireLength(
                "encrypted Key ID payload body",
                payloadBody,
                ID_PAYLOAD_BODY_LENGTH);
        if (unsigned(payloadBody[0]) != ID_TYPE_KEY_ID
                || payloadBody[1] != 0
                || payloadBody[2] != 0
                || payloadBody[3] != 0) {
            throw new IllegalArgumentException(
                    "Malformed encrypted Key ID payload body");
        }
        return Arrays.copyOfRange(
                payloadBody,
                4,
                payloadBody.length);
    }

    static byte[] buildIkeIdPayload(
            int nextPayload,
            byte[] encryptedIdentifier) {
        if (nextPayload < 0 || nextPayload > 0xff) {
            throw new IllegalArgumentException(
                    "next payload type is out of range");
        }
        byte[] body =
                buildIdPayloadBody(encryptedIdentifier);
        byte[] output =
                new byte[IKE_ID_PAYLOAD_LENGTH];
        output[0] = (byte) nextPayload;
        output[1] = 0;
        output[2] =
                (byte) (output.length >>> 8);
        output[3] =
                (byte) output.length;
        System.arraycopy(
                body,
                0,
                output,
                4,
                body.length);
        wipe(body);
        return output;
    }

    private static byte[] deriveKemSharedSecretForSender(
            X25519PrivateKeyParameters ephemeralPrivate,
            X25519PublicKeyParameters recipientPublic,
            byte[] encapsulation,
            byte[] recipientPublicBytes) {
        byte[] dh = new byte[SHA256_LENGTH];
        try {
            ephemeralPrivate.generateSecret(
                    recipientPublic,
                    dh,
                    0);
            return deriveKemSharedSecret(
                    dh,
                    encapsulation,
                    recipientPublicBytes);
        } catch (IllegalStateException error) {
            throw new IllegalArgumentException(
                    "recipient X25519 public key produces "
                            + "an invalid HPKE shared secret",
                    error);
        } finally {
            wipe(dh);
        }
    }

    private static byte[] deriveKemSharedSecretForReceiver(
            X25519PrivateKeyParameters recipientPrivate,
            byte[] encapsulation,
            byte[] recipientPublicBytes) {
        byte[] dh = new byte[SHA256_LENGTH];
        try {
            recipientPrivate.generateSecret(
                    new X25519PublicKeyParameters(
                            encapsulation,
                            0),
                    dh,
                    0);
            return deriveKemSharedSecret(
                    dh,
                    encapsulation,
                    recipientPublicBytes);
        } catch (IllegalStateException error) {
            throw new IllegalArgumentException(
                    "encapsulated X25519 key produces "
                            + "an invalid HPKE shared secret",
                    error);
        } finally {
            wipe(dh);
        }
    }

    private static byte[] deriveKemSharedSecret(
            byte[] dh,
            byte[] encapsulation,
            byte[] recipientPublicBytes) {
        byte[] eaePrk = null;
        byte[] kemContext = null;
        try {
            eaePrk = labeledExtract(
                    false,
                    new byte[0],
                    KEM_SUITE_ID,
                    "eae_prk",
                    dh);
            kemContext = concatenate(
                    encapsulation,
                    recipientPublicBytes);
            return labeledExpand(
                    false,
                    eaePrk,
                    KEM_SUITE_ID,
                    "shared_secret",
                    kemContext,
                    SHA256_LENGTH);
        } finally {
            wipe(eaePrk);
            wipe(kemContext);
        }
    }

    private static HpkeKeySchedule deriveKeySchedule(
            byte[] sharedSecret,
            byte[] info,
            byte[] psk,
            byte[] pskId) {
        byte[] pskIdHash = null;
        byte[] infoHash = null;
        byte[] context = null;
        byte[] secret = null;
        try {
            pskIdHash = labeledExtract(
                    true,
                    new byte[0],
                    HPKE_SUITE_ID,
                    "psk_id_hash",
                    pskId);
            infoHash = labeledExtract(
                    true,
                    new byte[0],
                    HPKE_SUITE_ID,
                    "info_hash",
                    info);
            context = concatenate(
                    new byte[]{HPKE_MODE_PSK},
                    pskIdHash,
                    infoHash);
            secret = labeledExtract(
                    true,
                    sharedSecret,
                    HPKE_SUITE_ID,
                    "secret",
                    psk);
            byte[] key = labeledExpand(
                    true,
                    secret,
                    HPKE_SUITE_ID,
                    "key",
                    context,
                    AES_256_KEY_LENGTH);
            byte[] baseNonce = labeledExpand(
                    true,
                    secret,
                    HPKE_SUITE_ID,
                    "base_nonce",
                    context,
                    AES_GCM_NONCE_LENGTH);
            return new HpkeKeySchedule(
                    key,
                    baseNonce);
        } finally {
            wipe(pskIdHash);
            wipe(infoHash);
            wipe(context);
            wipe(secret);
        }
    }

    private static byte[] labeledExtract(
            boolean sha512,
            byte[] salt,
            byte[] suiteId,
            String label,
            byte[] inputKeyMaterial) {
        byte[] labeledInput = concatenate(
                HPKE_VERSION_LABEL,
                suiteId,
                label.getBytes(StandardCharsets.US_ASCII),
                inputKeyMaterial);
        try {
            return hkdfExtract(
                    sha512,
                    salt,
                    labeledInput);
        } finally {
            wipe(labeledInput);
        }
    }

    private static byte[] labeledExpand(
            boolean sha512,
            byte[] prk,
            byte[] suiteId,
            String label,
            byte[] info,
            int length) {
        byte[] labeledInfo = concatenate(
                i2osp16(length),
                HPKE_VERSION_LABEL,
                suiteId,
                label.getBytes(StandardCharsets.US_ASCII),
                info);
        try {
            return hkdfExpand(
                    sha512,
                    prk,
                    labeledInfo,
                    length);
        } finally {
            wipe(labeledInfo);
        }
    }

    private static byte[] hkdfExtract(
            boolean sha512,
            byte[] salt,
            byte[] inputKeyMaterial) {
        int hashLength =
                sha512 ? SHA512_LENGTH : SHA256_LENGTH;
        byte[] effectiveSalt =
                salt.length == 0
                        ? new byte[hashLength]
                        : salt;
        HMac hmac = sha512
                ? new HMac(new SHA512Digest())
                : new HMac(new SHA256Digest());
        hmac.init(new KeyParameter(effectiveSalt));
        hmac.update(
                inputKeyMaterial,
                0,
                inputKeyMaterial.length);
        byte[] output = new byte[hashLength];
        hmac.doFinal(output, 0);
        if (effectiveSalt != salt) {
            wipe(effectiveSalt);
        }
        return output;
    }

    private static byte[] hkdfExpand(
            boolean sha512,
            byte[] prk,
            byte[] info,
            int length) {
        HKDFBytesGenerator generator = sha512
                ? new HKDFBytesGenerator(new SHA512Digest())
                : new HKDFBytesGenerator(new SHA256Digest());
        generator.init(
                HKDFParameters.skipExtractParameters(
                        prk,
                        info));
        byte[] output = new byte[length];
        generator.generateBytes(
                output,
                0,
                output.length);
        return output;
    }

    private static byte[] aesGcm(
            boolean encrypt,
            byte[] key,
            byte[] nonce,
            byte[] aad,
            byte[] input)
            throws InvalidCipherTextException {
        GCMBlockCipher cipher =
                new GCMBlockCipher(
                        AESEngine.newInstance());
        cipher.init(
                encrypt,
                new AEADParameters(
                        new KeyParameter(key),
                        AES_GCM_TAG_LENGTH * 8,
                        nonce,
                        aad));
        byte[] output =
                new byte[cipher.getOutputSize(input.length)];
        try {
            int outputLength = cipher.processBytes(
                    input,
                    0,
                    input.length,
                    output,
                    0);
            outputLength += cipher.doFinal(
                    output,
                    outputLength);
            if (outputLength != output.length) {
                byte[] exact =
                        Arrays.copyOf(output, outputLength);
                wipe(output);
                return exact;
            }
            return output;
        } catch (InvalidCipherTextException error) {
            wipe(output);
            throw error;
        }
    }

    private static byte[] keyIdAad(String serviceKeyId) {
        if (serviceKeyId == null
                || serviceKeyId.isEmpty()) {
            throw new IllegalArgumentException(
                    "service Key ID is required");
        }
        return serviceKeyId.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concatenate(
            byte[] first,
            byte[] second) {
        byte[] output =
                new byte[first.length + second.length];
        System.arraycopy(
                first,
                0,
                output,
                0,
                first.length);
        System.arraycopy(
                second,
                0,
                output,
                first.length,
                second.length);
        return output;
    }

    private static byte[] concatenate(byte[]... values) {
        int totalLength = 0;
        for (byte[] value : values) {
            totalLength += value.length;
        }
        byte[] output = new byte[totalLength];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(
                    value,
                    0,
                    output,
                    offset,
                    value.length);
            offset += value.length;
        }
        return output;
    }

    private static byte[] i2osp16(int value) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException(
                    "16-bit value is out of range");
        }
        return new byte[]{
                (byte) (value >>> 8),
                (byte) value
        };
    }

    private static void requireLength(
            String label,
            byte[] value,
            int expectedLength) {
        if (value == null
                || value.length != expectedLength) {
            throw new IllegalArgumentException(
                    label
                            + " must be exactly "
                            + expectedLength
                            + " bytes");
        }
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class HpkeKeySchedule {
        final byte[] key;
        final byte[] baseNonce;

        HpkeKeySchedule(
                byte[] key,
                byte[] baseNonce) {
            this.key = key;
            this.baseNonce = baseNonce;
        }

        void destroy() {
            wipe(key);
            wipe(baseNonce);
        }
    }
}
