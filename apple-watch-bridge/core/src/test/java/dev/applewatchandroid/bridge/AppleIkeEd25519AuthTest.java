package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AppleIkeEd25519AuthTest {
    @Test
    public void signedOctetsUseRawPacketNonceAndPrfOfFullIdBody()
            throws Exception {
        byte[] saInit = sequence(0x01, 28);
        byte[] oppositeNonce = sequence(0x40, 32);
        byte[] prfKey = sequence(0x80, 64);
        byte[] idBody = sequence(0x11, 148);

        byte[] actual =
                AppleIkeEd25519Auth.buildSignedOctets(
                        saInit,
                        oppositeNonce,
                        prfKey,
                        idBody);

        Mac hmac = Mac.getInstance("HmacSHA512");
        hmac.init(
                new SecretKeySpec(
                        prfKey,
                        "HmacSHA512"));
        byte[] expected = concatenate(
                saInit,
                oppositeNonce,
                hmac.doFinal(idBody));
        assertArrayEquals(expected, actual);
        assertEquals(
                saInit.length
                        + oppositeNonce.length
                        + 64,
                actual.length);
    }

    @Test
    public void intermediateAuthTailEndsWithBigEndianMessageId()
            throws Exception {
        byte[] saInit = sequence(0x01, 28);
        byte[] oppositeNonce = sequence(0x40, 32);
        byte[] prfKey = sequence(0x80, 64);
        byte[] idBody = sequence(0x11, 148);
        byte[] intAuthI = sequence(0x20, 17);
        byte[] intAuthR = sequence(0x60, 19);

        byte[] actual =
                AppleIkeEd25519Auth.buildSignedOctets(
                        saInit,
                        oppositeNonce,
                        prfKey,
                        idBody,
                        intAuthI,
                        intAuthR,
                        0x0102_0304L);

        Mac hmac = Mac.getInstance("HmacSHA512");
        hmac.init(
                new SecretKeySpec(
                        prfKey,
                        "HmacSHA512"));
        assertArrayEquals(
                concatenate(
                        saInit,
                        oppositeNonce,
                        hmac.doFinal(idBody),
                        intAuthI,
                        intAuthR,
                        new byte[]{
                                0x01, 0x02, 0x03, 0x04
                        }),
                actual);
    }

    @Test
    public void standardEd25519AuthHasExactAppleWireLayout() {
        byte[] privateKey = sequence(0x20, 32);
        byte[] publicKey =
                new Ed25519PrivateKeyParameters(
                        privateKey,
                        0)
                        .generatePublicKey()
                        .getEncoded();
        byte[] signedOctets =
                "raw IKEv2 signed octets"
                        .getBytes(StandardCharsets.US_ASCII);
        byte[] authBody =
                AppleIkeEd25519Auth.signAuthBody(
                        privateKey,
                        signedOctets);
        byte[] payload =
                AppleIkeEd25519Auth.buildIkeAuthPayload(
                        41,
                        authBody);

        assertEquals(
                AppleIkeEd25519Auth.AUTH_BODY_LENGTH,
                authBody.length);
        assertEquals(14, authBody[0] & 0xff);
        assertEquals(0, authBody[1]);
        assertEquals(0, authBody[2]);
        assertEquals(0, authBody[3]);
        assertArrayEquals(
                new byte[]{
                        0x07,
                        0x30, 0x05,
                        0x06, 0x03,
                        0x2b, 0x65, 0x70
                },
                Arrays.copyOfRange(
                        authBody,
                        4,
                        12));
        assertTrue(
                AppleIkeEd25519Auth.verifyAuthBody(
                        publicKey,
                        signedOctets,
                        authBody));

        assertEquals(
                AppleIkeEd25519Auth
                        .IKE_AUTH_PAYLOAD_LENGTH,
                payload.length);
        assertEquals(41, payload[0] & 0xff);
        assertEquals(0, payload[1] & 0xff);
        assertEquals(0, payload[2] & 0xff);
        assertEquals(80, payload[3] & 0xff);
        assertArrayEquals(
                authBody,
                Arrays.copyOfRange(
                        payload,
                        4,
                        payload.length));
    }

    @Test
    public void ed25519SignsMessageNotExternalSha512Digest() {
        byte[] privateKey = sequence(0x20, 32);
        byte[] publicKey =
                new Ed25519PrivateKeyParameters(
                        privateKey,
                        0)
                        .generatePublicKey()
                        .getEncoded();
        byte[] message =
                "do not prehash this vector"
                        .getBytes(StandardCharsets.US_ASCII);
        byte[] signature =
                AppleIkeEd25519Auth.sign(
                        privateKey,
                        message);
        byte[] wrongMessage = message.clone();
        wrongMessage[0] ^= 1;

        assertTrue(
                AppleIkeEd25519Auth.verify(
                        publicKey,
                        message,
                        signature));
        assertFalse(
                AppleIkeEd25519Auth.verify(
                        publicKey,
                        wrongMessage,
                        signature));
    }

    @Test
    public void rejectsMalformedAuthAndPartialIntermediateState() {
        byte[] authBody =
                AppleIkeEd25519Auth.buildAuthBody(
                        new byte[64]);
        byte[] wrongPrefix = authBody.clone();
        wrongPrefix[8] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleIkeEd25519Auth.extractSignature(
                        wrongPrefix));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleIkeEd25519Auth
                        .buildSignedOctets(
                                new byte[28],
                                new byte[32],
                                new byte[64],
                                new byte[4],
                                new byte[1],
                                null,
                                2));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleIkeEd25519Auth
                        .buildSignedOctets(
                                new byte[28],
                                new byte[32],
                                new byte[64],
                                new byte[4],
                                null,
                                null,
                                0x1_0000_0000L));
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

    private static byte[] concatenate(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] output = new byte[length];
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
}
