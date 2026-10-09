package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public final class AppleIkeEncryptedKeyIdTest {
    private static final String CLASS_D_KEY_ID =
            "com.apple.networkrelay.companionlink.classD";

    @Test
    public void plaintextHasExactVersionUuidAndRandomPaddingLayout() {
        byte[] uuid = sequence(0x10, 16);
        byte[] padding = sequence(
                0x40,
                AppleIkeEncryptedKeyId.RANDOM_PADDING_LENGTH);
        byte[] plaintext =
                AppleIkeEncryptedKeyId.createPlaintext(
                        uuid,
                        new FixedSecureRandom(padding));

        assertEquals(
                AppleIkeEncryptedKeyId.PLAINTEXT_LENGTH,
                plaintext.length);
        assertEquals(1, plaintext[0] & 0xff);
        assertArrayEquals(
                uuid,
                Arrays.copyOfRange(plaintext, 1, 17));
        assertArrayEquals(
                padding,
                Arrays.copyOfRange(
                        plaintext,
                        17,
                        plaintext.length));
        assertArrayEquals(
                uuid,
                AppleIkeEncryptedKeyId
                        .extractAndValidateIdentityUuid(
                                plaintext,
                                uuid));
    }

    @Test
    public void pskAndPskIdPreserveAppleConcatenationOrder() {
        byte[] initiatorNonce = sequence(0x20, 32);
        byte[] responderNonce = sequence(0x60, 32);
        byte[] initiatorSpi = sequence(0x01, 8);
        byte[] responderSpi = sequence(0x11, 8);

        assertArrayEquals(
                concatenate(
                        initiatorNonce,
                        responderNonce),
                AppleIkeEncryptedKeyId.createPsk(
                        initiatorNonce,
                        responderNonce));
        assertArrayEquals(
                new byte[]{
                        0x01, 0x02, 0x03, 0x04,
                        0x05, 0x06, 0x07, 0x08,
                        0x11, 0x12, 0x13, 0x14,
                        0x15, 0x16, 0x17, 0x18
                },
                AppleIkeEncryptedKeyId.createPskId(
                        initiatorSpi,
                        responderSpi));
    }

    @Test
    public void ikeIdPayloadHasExact152ByteWireLayout() {
        byte[] encrypted = sequence(
                0x20,
                AppleIkeEncryptedKeyId
                        .ENCRYPTED_IDENTIFIER_LENGTH);
        byte[] body =
                AppleIkeEncryptedKeyId
                        .buildIdPayloadBody(encrypted);
        byte[] payload =
                AppleIkeEncryptedKeyId
                        .buildIkeIdPayload(
                                39,
                                encrypted);

        assertEquals(
                AppleIkeEncryptedKeyId
                        .ID_PAYLOAD_BODY_LENGTH,
                body.length);
        assertEquals(11, body[0] & 0xff);
        assertEquals(0, body[1]);
        assertEquals(0, body[2]);
        assertEquals(0, body[3]);
        assertArrayEquals(
                encrypted,
                AppleIkeEncryptedKeyId
                        .parseIdPayloadBody(body));

        assertEquals(
                AppleIkeEncryptedKeyId
                        .IKE_ID_PAYLOAD_LENGTH,
                payload.length);
        assertEquals(39, payload[0] & 0xff);
        assertEquals(0, payload[1] & 0xff);
        assertEquals(0, payload[2] & 0xff);
        assertEquals(152, payload[3] & 0xff);
        assertArrayEquals(
                body,
                Arrays.copyOfRange(
                        payload,
                        4,
                        payload.length));
    }

    @Test
    public void deterministicHpkeRoundTripMatchesAppleWireLayout() {
        byte[] recipientPrivate = sequence(0x80, 32);
        byte[] recipientPublic =
                new X25519PrivateKeyParameters(
                        recipientPrivate,
                        0)
                        .generatePublicKey()
                        .getEncoded();
        byte[] ephemeralPrivate = sequence(0x30, 32);
        byte[] initiatorNonce = sequence(0x20, 32);
        byte[] responderNonce = sequence(0x60, 32);
        byte[] initiatorSpi = sequence(0x01, 8);
        byte[] responderSpi = sequence(0x11, 8);
        byte[] plaintext = sequence(
                0x01,
                AppleIkeEncryptedKeyId.PLAINTEXT_LENGTH);

        byte[] encrypted =
                AppleIkeEncryptedKeyId
                        .sealWithEphemeralPrivateKey(
                                recipientPublic,
                                CLASS_D_KEY_ID,
                                initiatorNonce,
                                responderNonce,
                                initiatorSpi,
                                responderSpi,
                                plaintext,
                                ephemeralPrivate);

        assertEquals(
                AppleIkeEncryptedKeyId
                        .ENCRYPTED_IDENTIFIER_LENGTH,
                encrypted.length);
        assertArrayEquals(
                new X25519PrivateKeyParameters(
                        ephemeralPrivate,
                        0)
                        .generatePublicKey()
                        .getEncoded(),
                Arrays.copyOfRange(
                        encrypted,
                        0,
                        AppleIkeEncryptedKeyId
                                .ENCAPSULATED_KEY_LENGTH));
        assertArrayEquals(
                plaintext,
                AppleIkeEncryptedKeyId.open(
                        recipientPrivate,
                        CLASS_D_KEY_ID,
                        initiatorNonce,
                        responderNonce,
                        initiatorSpi,
                        responderSpi,
                        encrypted));
    }

    @Test
    public void hpkeAuthenticatesEveryAppleContextField() {
        byte[] recipientPrivate = sequence(0x80, 32);
        byte[] recipientPublic =
                new X25519PrivateKeyParameters(
                        recipientPrivate,
                        0)
                        .generatePublicKey()
                        .getEncoded();
        byte[] initiatorNonce = sequence(0x20, 32);
        byte[] responderNonce = sequence(0x60, 32);
        byte[] initiatorSpi = sequence(0x01, 8);
        byte[] responderSpi = sequence(0x11, 8);
        byte[] plaintext = sequence(
                0x01,
                AppleIkeEncryptedKeyId.PLAINTEXT_LENGTH);
        byte[] encrypted =
                AppleIkeEncryptedKeyId
                        .sealWithEphemeralPrivateKey(
                                recipientPublic,
                                CLASS_D_KEY_ID,
                                initiatorNonce,
                                responderNonce,
                                initiatorSpi,
                                responderSpi,
                                plaintext,
                                sequence(0x30, 32));

        assertOpenFails(
                recipientPrivate,
                "com.apple.networkrelay.companionlink.classC",
                initiatorNonce,
                responderNonce,
                initiatorSpi,
                responderSpi,
                encrypted);

        byte[] wrongNonce = responderNonce.clone();
        wrongNonce[0] ^= 1;
        assertOpenFails(
                recipientPrivate,
                CLASS_D_KEY_ID,
                initiatorNonce,
                wrongNonce,
                initiatorSpi,
                responderSpi,
                encrypted);

        byte[] wrongSpi = responderSpi.clone();
        wrongSpi[0] ^= 1;
        assertOpenFails(
                recipientPrivate,
                CLASS_D_KEY_ID,
                initiatorNonce,
                responderNonce,
                initiatorSpi,
                wrongSpi,
                encrypted);

        byte[] wrongPrivate = recipientPrivate.clone();
        // X25519 clamps some edge bits of byte 0/31, so mutate a
        // non-clamped scalar byte.
        wrongPrivate[10] ^= 1;
        assertOpenFails(
                wrongPrivate,
                CLASS_D_KEY_ID,
                initiatorNonce,
                responderNonce,
                initiatorSpi,
                responderSpi,
                encrypted);
    }

    @Test
    public void deterministicAppleSealOpensWithBouncyCastleHpke()
            throws Exception {
        byte[] recipientPrivate = sequence(0x80, 32);
        X25519PrivateKeyParameters privateParameters =
                new X25519PrivateKeyParameters(
                        recipientPrivate,
                        0);
        byte[] recipientPublic =
                privateParameters
                        .generatePublicKey()
                        .getEncoded();
        byte[] initiatorNonce = sequence(0x20, 32);
        byte[] responderNonce = sequence(0x60, 32);
        byte[] initiatorSpi = sequence(0x01, 8);
        byte[] responderSpi = sequence(0x11, 8);
        byte[] plaintext = sequence(
                0x01,
                AppleIkeEncryptedKeyId.PLAINTEXT_LENGTH);
        byte[] encrypted =
                AppleIkeEncryptedKeyId
                        .sealWithEphemeralPrivateKey(
                                recipientPublic,
                                CLASS_D_KEY_ID,
                                initiatorNonce,
                                responderNonce,
                                initiatorSpi,
                                responderSpi,
                                plaintext,
                                sequence(0x30, 32));

        byte[] opened = newHpke().open(
                Arrays.copyOfRange(
                        encrypted,
                        0,
                        AppleIkeEncryptedKeyId
                                .ENCAPSULATED_KEY_LENGTH),
                new AsymmetricCipherKeyPair(
                        privateParameters.generatePublicKey(),
                        privateParameters),
                AppleIkeEncryptedKeyId.INFO_STRING.getBytes(
                        StandardCharsets.US_ASCII),
                CLASS_D_KEY_ID.getBytes(
                        StandardCharsets.UTF_8),
                Arrays.copyOfRange(
                        encrypted,
                        AppleIkeEncryptedKeyId
                                .ENCAPSULATED_KEY_LENGTH,
                        encrypted.length),
                AppleIkeEncryptedKeyId.createPsk(
                        initiatorNonce,
                        responderNonce),
                AppleIkeEncryptedKeyId.createPskId(
                        initiatorSpi,
                        responderSpi),
                null);
        assertArrayEquals(plaintext, opened);
    }

    @Test
    public void bouncyCastleSealOpensWithAppleCodec()
            throws Exception {
        byte[] recipientPrivate = sequence(0x80, 32);
        X25519PrivateKeyParameters privateParameters =
                new X25519PrivateKeyParameters(
                        recipientPrivate,
                        0);
        X25519PublicKeyParameters publicParameters =
                privateParameters.generatePublicKey();
        byte[] initiatorNonce = sequence(0x20, 32);
        byte[] responderNonce = sequence(0x60, 32);
        byte[] initiatorSpi = sequence(0x01, 8);
        byte[] responderSpi = sequence(0x11, 8);
        byte[] plaintext = sequence(
                0x01,
                AppleIkeEncryptedKeyId.PLAINTEXT_LENGTH);
        byte[][] sealed = newHpke().seal(
                publicParameters,
                AppleIkeEncryptedKeyId.INFO_STRING.getBytes(
                        StandardCharsets.US_ASCII),
                CLASS_D_KEY_ID.getBytes(
                        StandardCharsets.UTF_8),
                plaintext,
                AppleIkeEncryptedKeyId.createPsk(
                        initiatorNonce,
                        responderNonce),
                AppleIkeEncryptedKeyId.createPskId(
                        initiatorSpi,
                        responderSpi),
                null);
        byte[] encrypted = concatenate(
                sealed[1],
                sealed[0]);

        assertArrayEquals(
                plaintext,
                AppleIkeEncryptedKeyId.open(
                        recipientPrivate,
                        CLASS_D_KEY_ID,
                        initiatorNonce,
                        responderNonce,
                        initiatorSpi,
                        responderSpi,
                        encrypted));
    }

    @Test
    public void rejectsMalformedLengthsAndIdentity() {
        byte[] uuid = sequence(0x10, 16);
        byte[] plaintext =
                AppleIkeEncryptedKeyId.createPlaintext(
                        uuid,
                        new FixedSecureRandom(
                                new byte[
                                        AppleIkeEncryptedKeyId
                                                .RANDOM_PADDING_LENGTH]));
        byte[] wrongUuid = uuid.clone();
        wrongUuid[0] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleIkeEncryptedKeyId
                        .extractAndValidateIdentityUuid(
                                plaintext,
                                wrongUuid));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleIkeEncryptedKeyId.createPskId(
                        new byte[7],
                        new byte[8]));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleIkeEncryptedKeyId.open(
                        new byte[32],
                        CLASS_D_KEY_ID,
                        new byte[32],
                        new byte[32],
                        new byte[8],
                        new byte[8],
                        new byte[
                                AppleIkeEncryptedKeyId
                                        .ENCRYPTED_IDENTIFIER_LENGTH
                                        - 1]));
    }

    private static void assertOpenFails(
            byte[] recipientPrivate,
            String keyId,
            byte[] initiatorNonce,
            byte[] responderNonce,
            byte[] initiatorSpi,
            byte[] responderSpi,
            byte[] encrypted) {
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleIkeEncryptedKeyId.open(
                        recipientPrivate,
                        keyId,
                        initiatorNonce,
                        responderNonce,
                        initiatorSpi,
                        responderSpi,
                        encrypted));
    }

    private static byte[] sequence(
            int first,
            int length) {
        byte[] output = new byte[length];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) (first + index);
        }
        return output;
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

    private static HPKE newHpke() {
        return new HPKE(
                HPKE.mode_psk,
                HPKE.kem_X25519_SHA256,
                HPKE.kdf_HKDF_SHA512,
                HPKE.aead_AES_GCM256);
    }

    private static final class FixedSecureRandom
            extends SecureRandom {
        private final byte[] bytes;

        FixedSecureRandom(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public void nextBytes(byte[] output) {
            if (output.length != bytes.length) {
                throw new IllegalArgumentException(
                        "Unexpected random request length");
            }
            System.arraycopy(
                    bytes,
                    0,
                    output,
                    0,
                    output.length);
        }
    }
}
