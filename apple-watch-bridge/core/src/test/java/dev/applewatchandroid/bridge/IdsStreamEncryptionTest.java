package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import org.junit.Test;

public final class IdsStreamEncryptionTest {
    private static final UUID HIGH_GUID =
            UUID.fromString(
                    "ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID LOW_GUID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001");

    @Test
    public void matchesIndependentIos26KnownAnswerAndFirstSequence() {
        byte[] combined =
                range(
                        0,
                        IdsStreamEncryption.CONTRIBUTION_LENGTH);
        try (IdsStreamEncryption sender =
                IdsStreamEncryption.create(
                        combined,
                        HIGH_GUID,
                        LOW_GUID,
                        0x11223344,
                        0x5566,
                        0x01020304,
                        0x7788)) {
            byte[] frame =
                    sender.encryptTcp(
                            hex("de ad be ef"));
            try {
                // Independently calculated with AES-128-ECB for the
                // master KDF and AES-256-ECB for the packet counter.
                assertArrayEquals(
                        hex(
                                "e0 00 55 66 11 22 33 44 "
                                        + "3d ce 9f b8 8b"),
                        frame);
                assertEquals(
                        0x5567,
                        sender.nextSendSequence());
                assertEquals(
                        0,
                        sender.sendRolloverCounter());
            } finally {
                wipe(frame);
            }
        } finally {
            wipe(combined);
        }
    }

    @Test
    public void guidOrderMakesTwoEndpointsBidirectional() {
        byte[] combined =
                range(
                        0,
                        IdsStreamEncryption.CONTRIBUTION_LENGTH);
        try (IdsStreamEncryption high =
                        IdsStreamEncryption.create(
                                combined,
                                HIGH_GUID,
                                LOW_GUID,
                                0x11223344,
                                0x5566,
                                0x01020304,
                                0x7788);
                IdsStreamEncryption low =
                        IdsStreamEncryption.create(
                                combined,
                                LOW_GUID,
                                HIGH_GUID,
                                0x01020304,
                                0x7788,
                                0x11223344,
                                0x5566)) {
            byte[] tcp =
                    high.encryptTcp(
                            hex("01 02 03 04"));
            IdsStreamEncryption.DecryptedPacket decodedTcp =
                    low.decrypt(
                            tcp);
            try {
                assertEquals(0x5566, decodedTcp.sequence);
                assertEquals(0x11223344, decodedTcp.ssrc);
                assertTrue(decodedTcp.tcp);
                assertFalse(decodedTcp.compressed);
                assertEquals(-1, decodedTcp.compressionContextId);
                assertArrayEquals(
                        hex("01 02 03 04"),
                        decodedTcp.payload);
            } finally {
                decodedTcp.destroy();
                wipe(tcp);
            }

            byte[] udp =
                    low.encryptUdp(
                            hex("aa bb cc"));
            IdsStreamEncryption.DecryptedPacket decodedUdp =
                    high.decrypt(
                            udp);
            try {
                assertEquals(0x7788, decodedUdp.sequence);
                assertEquals(0x01020304, decodedUdp.ssrc);
                assertFalse(decodedUdp.tcp);
                assertFalse(decodedUdp.compressed);
                assertArrayEquals(
                        hex("aa bb cc"),
                        decodedUdp.payload);
            } finally {
                decodedUdp.destroy();
                wipe(udp);
            }
        } finally {
            wipe(combined);
        }
    }

    @Test
    public void handlesSequenceWrapOutOfOrderAndReplayWindow() {
        byte[] combined =
                range(
                        0x20,
                        IdsStreamEncryption.CONTRIBUTION_LENGTH);
        try (IdsStreamEncryption sender =
                        IdsStreamEncryption.create(
                                combined,
                                HIGH_GUID,
                                LOW_GUID,
                                0x10203040,
                                0xffff,
                                0x50607080,
                                10);
                IdsStreamEncryption receiver =
                        IdsStreamEncryption.create(
                                combined,
                                LOW_GUID,
                                HIGH_GUID,
                                0x50607080,
                                10,
                                0x10203040,
                                0xffff)) {
            byte[] lastBeforeWrap =
                    sender.encryptTcp(
                            hex("01"));
            byte[] firstAfterWrap =
                    sender.encryptTcp(
                            hex("02"));
            try {
                assertEquals(
                        0xffff,
                        be16(
                                lastBeforeWrap,
                                2));
                assertEquals(
                        0,
                        be16(
                                firstAfterWrap,
                                2));
                assertEquals(
                        1,
                        sender.sendRolloverCounter());

                IdsStreamEncryption.DecryptedPacket after =
                        receiver.decrypt(
                                firstAfterWrap);
                IdsStreamEncryption.DecryptedPacket before =
                        receiver.decrypt(
                                lastBeforeWrap);
                try {
                    assertArrayEquals(
                            hex("02"),
                            after.payload);
                    assertArrayEquals(
                            hex("01"),
                            before.payload);
                } finally {
                    after.destroy();
                    before.destroy();
                }
                assertThrows(
                        IllegalArgumentException.class,
                        () -> receiver.decrypt(
                                lastBeforeWrap));
            } finally {
                wipe(lastBeforeWrap);
                wipe(firstAfterWrap);
            }
        } finally {
            wipe(combined);
        }
    }

    @Test
    public void parsesCompressedMarkersAndContext() {
        byte[] combined =
                range(
                        0x40,
                        IdsStreamEncryption.CONTRIBUTION_LENGTH);
        try (IdsStreamEncryption high =
                        IdsStreamEncryption.create(
                                combined,
                                HIGH_GUID,
                                LOW_GUID,
                                1,
                                2,
                                3,
                                4);
                IdsStreamEncryption low =
                        IdsStreamEncryption.create(
                                combined,
                                LOW_GUID,
                                HIGH_GUID,
                                3,
                                4,
                                1,
                                2)) {
            byte[] frame =
                    high.encryptCompressedTcp(
                            0xabcd,
                            hex("10 20 30"));
            IdsStreamEncryption.DecryptedPacket decoded =
                    low.decrypt(
                            frame);
            try {
                assertTrue(decoded.tcp);
                assertTrue(decoded.compressed);
                assertEquals(
                        0xabcd,
                        decoded.compressionContextId);
                assertArrayEquals(
                        hex("10 20 30"),
                        decoded.payload);
            } finally {
                decoded.destroy();
                wipe(frame);
            }
        } finally {
            wipe(combined);
        }
    }

    @Test
    public void hasNoInnerTagSoOuterEspMustAuthenticatePayload() {
        byte[] combined =
                range(
                        0x60,
                        IdsStreamEncryption.CONTRIBUTION_LENGTH);
        try (IdsStreamEncryption sender =
                        IdsStreamEncryption.create(
                                combined,
                                HIGH_GUID,
                                LOW_GUID,
                                11,
                                12,
                                13,
                                14);
                IdsStreamEncryption receiver =
                        IdsStreamEncryption.create(
                                combined,
                                LOW_GUID,
                                HIGH_GUID,
                                13,
                                14,
                                11,
                                12)) {
            byte[] frame =
                    sender.encryptTcp(
                            hex("de ad be ef"));
            frame[frame.length - 1] ^= 1;
            IdsStreamEncryption.DecryptedPacket decoded =
                    receiver.decrypt(
                            frame);
            try {
                assertArrayEquals(
                        hex("de ad be ee"),
                        decoded.payload);
            } finally {
                decoded.destroy();
                wipe(frame);
            }
        } finally {
            wipe(combined);
        }
    }

    @Test
    public void combinesContributionsAndRejectsInvalidFramesOrLimits() {
        byte[] first =
                new byte[IdsStreamEncryption.CONTRIBUTION_LENGTH];
        byte[] second =
                new byte[IdsStreamEncryption.CONTRIBUTION_LENGTH];
        Arrays.fill(
                first,
                (byte) 0x55);
        Arrays.fill(
                second,
                (byte) 0xaa);
        byte[] combined =
                IdsStreamEncryption.combineContributions(
                        first,
                        second);
        try {
            byte[] expected =
                    new byte[combined.length];
            Arrays.fill(
                    expected,
                    (byte) 0xff);
            assertArrayEquals(
                    expected,
                    combined);
            wipe(expected);

            try (IdsStreamEncryption encryption =
                    IdsStreamEncryption.create(
                            combined,
                            HIGH_GUID,
                            LOW_GUID,
                            1,
                            2,
                            3,
                            4)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> encryption.encryptTcp(
                                new byte[
                                        IdsStreamEncryption
                                                .MAX_PLAINTEXT_LENGTH]));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> encryption.decrypt(
                                hex("e0 01 00 04 00 00 00 03 00")));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> encryption.decrypt(
                                hex("e0 00 00 04 00 00 00 04 00")));
            }
        } finally {
            wipe(first);
            wipe(second);
            wipe(combined);
        }
    }

    private static int be16(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 8)
                | (value[offset + 1] & 0xff);
    }

    private static byte[] range(
            int first,
            int length) {
        byte[] output =
                new byte[length];
        for (int index = 0;
                index < length;
                index++) {
            output[index] =
                    (byte) (first + index);
        }
        return output;
    }

    private static byte[] hex(
            String value) {
        String compact =
                value.replaceAll(
                        "\\s",
                        "");
        byte[] output =
                new byte[compact.length() / 2];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) Integer.parseInt(
                            compact.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return output;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
