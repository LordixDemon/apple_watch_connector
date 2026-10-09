package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.spec.SecretKeySpec;

public final class PairingSessionEnvelopeTest {
    @Test
    public void matchesDeterministicAesGcmKnownAnswerAndRoundTrips()
            throws Exception {
        byte[] plaintext = sequence(0x20, 96);
        SecretKeySpec key = new SecretKeySpec(
                sequence(0x00, 32),
                "AES");
        byte[] iv = sequence(0xa0, 12);
        byte[] encrypted =
                PairingSessionEnvelope.encryptWithIv(
                        plaintext,
                        key,
                        iv);
        try {
            assertEquals(
                    PairingSessionEnvelope.MAGIC.length
                            + 1
                            + PairingSessionEnvelope.IV_LENGTH
                            + plaintext.length
                            + 16,
                    encrypted.length);
            assertArrayEquals(
                    hex("41575045020ca0a1a2a3a4a5a6a7a8a9aaab"
                            + "c6395e0e61ee24984a4cadf82b57eef1"
                            + "409d6b23a682745ba4371cbd43964b3e"
                            + "923705bceb67157a17d54e834537cdb6"
                            + "174a141b36854c2919075105f82ddbef"
                            + "d4dde70c54c082879c8b9573e3a3a2d"
                            + "5bd7ad9d7b1b4ef0d960c44e1cde733"
                            + "ebbcb15c8b8cad3623b725685b42136d34"),
                    encrypted);
            assertArrayEquals(
                    plaintext,
                    PairingSessionEnvelope.decrypt(
                            encrypted,
                            key));
        } finally {
            wipe(plaintext);
            wipe(iv);
            wipe(encrypted);
        }
    }

    @Test
    public void providerGeneratedIvChangesCiphertextAndTamperingFails()
            throws Exception {
        byte[] plaintext = sequence(1, 128);
        SecretKeySpec key = new SecretKeySpec(
                sequence(9, 32),
                "AES");
        byte[] first =
                PairingSessionEnvelope.encrypt(
                        plaintext,
                        key,
                        new SecureRandom());
        byte[] second =
                PairingSessionEnvelope.encrypt(
                        plaintext,
                        key,
                        new SecureRandom());
        try {
            org.junit.Assert.assertFalse(
                    Arrays.equals(first, second));
            assertEquals(
                    PairingSessionEnvelope.IV_LENGTH,
                    first[PairingSessionEnvelope.MAGIC.length]
                            & 0xff);
            assertEquals(
                    PairingSessionEnvelope.IV_LENGTH,
                    second[PairingSessionEnvelope.MAGIC.length]
                            & 0xff);
            first[first.length - 1] ^= 1;
            assertThrows(
                    Exception.class,
                    () -> PairingSessionEnvelope.decrypt(
                            first,
                            key));
        } finally {
            wipe(plaintext);
            wipe(first);
            wipe(second);
        }
    }

    private static byte[] sequence(
            int first,
            int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) (first + index);
        }
        return output;
    }

    private static byte[] hex(String text) {
        byte[] output = new byte[text.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    text.substring(
                            index * 2,
                            index * 2 + 2),
                    16);
        }
        return output;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
