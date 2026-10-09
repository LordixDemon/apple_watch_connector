package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class AppleWatchPairingCryptoTest {
    @Test
    public void matchesRecoveredSaltedPinPpkPipeline() {
        char[] pin = "012345".toCharArray();
        AppleWatchPairingCrypto.PairingSecrets secrets =
                AppleWatchPairingCrypto.derive(
                        pin,
                        sequence(0xa0, 32),
                        hex("20 88 37 D4 CE 64 38 50 00 D0 10 00"));
        try {
            assertArrayEquals(
                    new char[AppleWatchPairingCrypto.PIN_LENGTH],
                    pin);
            assertArrayEquals(
                    hex("00003039"),
                    secrets.authenticationData);
            assertArrayEquals(
                    hex("b8d66ad794eb29d1b50c3e7f3fb7d350"
                            + "2a84a3644e30d47ecdb2119510929070"),
                    secrets.pinKey);
            assertArrayEquals(
                    hex("b8d66ad794eb29d1b50c3e7f3fb7d350"
                            + "2a84a3644e30d47ecdb2119510929070"
                            + "208837d4ce64385000d01000"),
                    secrets.sharedSecret);
            assertArrayEquals(
                    hex("6b5a77e96b100f1c14139afe4b9ccc8e"
                            + "60164d40220df33104879859cc828881"
                            + "aa342997cfba8a2affe94d701ac58529"
                            + "a225b2a5f1f75920382c71be4619d00f"),
                    secrets.ppk);

            assertArrayEquals(
                    hex("e8aeada3ed5bc78aa3295ccd7c77e74e"
                            + "f5f2ae41f3fd1d8fad4ea7b26b2d635c"
                            + "247f85c8f3a4d1d9758b3f5edfc0a1b5"
                            + "2ffd25f3f75c016c220b7d1c846ca2e7"),
                    AppleWatchPairingCrypto.deriveKeyFromPrimeKey(
                            secrets.ppk,
                            sequence(0x00, 64)));
        } finally {
            secrets.destroy();
        }
        assertTrue(secrets.isDestroyed());
        assertTrue(allZero(secrets.authenticationData));
        assertTrue(allZero(secrets.pinKey));
        assertTrue(allZero(secrets.sharedSecret));
        assertTrue(allZero(secrets.ppk));
    }

    @Test
    public void emitsFixedPpkIdentityAndPhysicalPairingIds() {
        assertArrayEquals(
                new byte[]{0x01},
                AppleWatchPairingCrypto.fixedPpkIdentityData());
        assertArrayEquals(
                hex("0d000000"),
                AppleWatchPairingCrypto
                        .physicalInitiatorIdPayloadBody());
        assertArrayEquals(
                hex("0b000000"
                        + "636f6d2e6170706c652e6e6574776f72"
                        + "6b72656c61792e636f6d70616e696f6e"
                        + "6c696e6b2e70616972696e672e617574"
                        + "682e73616c74656450696e"),
                AppleWatchPairingCrypto.saltedPinIdPayloadBody());
    }

    @Test
    public void rejectsAndConsumesMalformedPinsBeforeKdf() {
        char[] nonDigit = "12x456".toCharArray();
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleWatchPairingCrypto.derive(
                        nonDigit,
                        new byte[32],
                        new byte[12]));
        assertArrayEquals(new char[6], nonDigit);

        char[] tooShort = "12345".toCharArray();
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleWatchPairingCrypto.derive(
                        tooShort,
                        new byte[32],
                        new byte[12]));
        assertArrayEquals(new char[5], tooShort);
    }

    private static byte[] sequence(int first, int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) (first + index);
        }
        return output;
    }

    private static boolean allZero(byte[] bytes) {
        byte[] zero = new byte[bytes.length];
        return Arrays.equals(zero, bytes);
    }

    private static byte[] hex(String value) {
        String compact = value.replace(" ", "");
        byte[] output = new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    compact.substring(index * 2, index * 2 + 2),
                    16);
        }
        return output;
    }
}
