package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class AppleSpake2PlusProverTest {
    @Test
    public void matchesRfc9383P256Sha256Vector() {
        AppleSpake2PlusProver prover =
                AppleSpake2PlusProver.forTest(
                        hex("bb8e1bbcf3c48f62c08db243652ae55d"
                                + "3e5586053fca77102994f23ad95491b3"),
                        hex("7e945f34d78785b8a3ef44d0df5a1a9"
                                + "7d6b3b460409a345ca7830387a74b1dba"),
                        hex("d1232c8e8693d02368976c174e208885"
                                + "1b8365d0d79a9eee709c6a05a2fad539"),
                        ("SPAKE2+-P256-SHA256-HKDF-SHA256-"
                                + "HMAC-SHA256 Test Vectors")
                                .getBytes(StandardCharsets.US_ASCII),
                        "client".getBytes(StandardCharsets.US_ASCII),
                        "server".getBytes(StandardCharsets.US_ASCII));

        assertArrayEquals(
                hex("04ef3bd051bf78a2234ec0df197f782806"
                        + "0fe9856503579bb1733009042c15c0c1d"
                        + "e127727f418b5966afadfdd95a6e4591d"
                        + "171056b333dab97a79c7193e341727"),
                prover.getShare());

        byte[] proverTag =
                prover.processPeerShareAndGenerateVerificationTag(
                        hex("04c0f65da0d11927bdf5d560c69e1d7d9"
                                + "39a05b0e88291887d679fcadea75810f"
                                + "b5cc1ca7494db39e82ff2f50665255d76"
                                + "173e09986ab46742c798a9a68437b048"));
        assertArrayEquals(
                hex("926cc713504b9b4d76c9162ded04b549"
                        + "3e89109f6d89462cd33adc46fda27527"),
                proverTag);

        byte[] sharedKey =
                prover.processPeerVerificationTagAndGenerateKey(
                        hex("9747bcc4f8fe9f63defee53ac9b07876"
                                + "d907d55047e6ff2def2e7529089d3e68"));
        assertArrayEquals(
                hex("0c5f8ccd1413423a54f6c1fb26ff0153"
                        + "4a87f893779c6e68666d772bfd91f3e7"),
                sharedKey);
        assertThrows(
                IllegalStateException.class,
                prover::getShare);
    }

    @Test
    public void matchesRecoveredAppleLegacySeedReduction() {
        AppleSpake2PlusProver.LegacyScalars scalars =
                AppleSpake2PlusProver.deriveLegacyScalars(
                        sequence(0x00, 44),
                        sequence(0xa0, 64));
        try {
            assertArrayEquals(
                    hex("4612a34cbd64364db838190024043d55"
                            + "bc69b5545675a213a5749361a053ea0d"),
                    scalars.w0);
            assertArrayEquals(
                    hex("c234c18f98065a5d03c60b5a8901626b"
                            + "cb0340abaf97f04eda3fcad7c36d308c"),
                    scalars.w1);
        } finally {
            scalars.destroy();
        }
    }

    @Test
    public void matchesAppleCorecryptoSyntheticShare() {
        AppleSpake2PlusProver prover =
                AppleSpake2PlusProver.forTest(
                        hex("0c0e101208070605101155b315cb1c6f"
                                + "2586bfe1f3ca45251f4197ca0f3b3108"),
                        hex("5c5e6061dfdedddd42bfed1f6a80096c"
                                + "255e0d122ab40af1805d44ffb3beb030"),
                        hex("00000000000000000000000000000000"
                                + "00000000000000000000000000000001"),
                        sequence(0xa0, 16),
                        hex("0b00000010111213"),
                        hex("0b00000020212223"));

        assertArrayEquals(
                hex("04be3d37a3b6c2c407ba4c058956d4ef"
                        + "bcef86b0199b63af0b2e3c44963a1218"
                        + "ae18e3a4bad9280c8558cecbe144b258"
                        + "99f2eb6657c5c92e8b6fce0e1f27c7"
                        + "5454"),
                prover.getShare());
    }

    @Test
    public void rejectsBadWatchPointAndConfirmationTag() {
        AppleSpake2PlusProver invalidPointProver =
                vectorProver();
        byte[] invalidPoint =
                new byte[AppleSpake2PlusProver.SHARE_LENGTH];
        invalidPoint[0] = 0x04;
        assertThrows(
                IllegalArgumentException.class,
                () -> invalidPointProver
                        .processPeerShareAndGenerateVerificationTag(
                                invalidPoint));
        invalidPointProver.destroy();

        AppleSpake2PlusProver badTagProver = vectorProver();
        badTagProver.processPeerShareAndGenerateVerificationTag(
                vectorVerifierShare());
        assertThrows(
                SecurityException.class,
                () -> badTagProver
                        .processPeerVerificationTagAndGenerateKey(
                                new byte[32]));
        assertThrows(
                IllegalStateException.class,
                badTagProver::getShare);
    }

    private static AppleSpake2PlusProver vectorProver() {
        return AppleSpake2PlusProver.forTest(
                hex("bb8e1bbcf3c48f62c08db243652ae55d"
                        + "3e5586053fca77102994f23ad95491b3"),
                hex("7e945f34d78785b8a3ef44d0df5a1a9"
                        + "7d6b3b460409a345ca7830387a74b1dba"),
                hex("d1232c8e8693d02368976c174e208885"
                        + "1b8365d0d79a9eee709c6a05a2fad539"),
                ("SPAKE2+-P256-SHA256-HKDF-SHA256-"
                        + "HMAC-SHA256 Test Vectors")
                        .getBytes(StandardCharsets.US_ASCII),
                "client".getBytes(StandardCharsets.US_ASCII),
                "server".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] vectorVerifierShare() {
        return hex("04c0f65da0d11927bdf5d560c69e1d7d9"
                + "39a05b0e88291887d679fcadea75810f"
                + "b5cc1ca7494db39e82ff2f50665255d76"
                + "173e09986ab46742c798a9a68437b048");
    }

    private static byte[] sequence(int first, int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) (first + index);
        }
        return output;
    }

    private static byte[] hex(String value) {
        byte[] output = new byte[value.length() / 2];
        for (int index = 0; index < output.length; index++) {
            int high = Character.digit(
                    value.charAt(index * 2),
                    16);
            int low = Character.digit(
                    value.charAt(index * 2 + 1),
                    16);
            output[index] = (byte) ((high << 4) | low);
        }
        return output;
    }
}
