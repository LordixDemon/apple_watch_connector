package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class NrLinkBluetoothPreludeTest {
    private static final byte[] SYNTHETIC_UUID = hex(
            "00 01 02 03 04 05 06 07 "
                    + "08 09 0a 0b 0c 0d 0e 0f");

    @Test
    public void emitsRecoveredFreshModernKnownAnswerVector() {
        byte[] encoded = NrLinkBluetoothPrelude.encodeFreshModern(
                SYNTHETIC_UUID,
                false,
                false);

        assertArrayEquals(
                hex("54 45 52 4d 49 4e 55 53 "
                        + "01 0d 00 18 "
                        + "04 00 10 "
                        + "00 01 02 03 04 05 06 07 "
                        + "08 09 0a 0b 0c 0d 0e 0f "
                        + "05 00 02 00 00 "
                        + "65 67"),
                encoded);

        NrLinkBluetoothPrelude.Parsed parsed =
                NrLinkBluetoothPrelude.parse(encoded);
        assertEquals(1, parsed.version);
        assertEquals(
                NrLinkBluetoothPrelude.PairingState
                        .MODERN_PAIRING_KEY_CONFIRMATION,
                parsed.state);
        assertEquals(0, parsed.flags);
        assertArrayEquals(SYNTHETIC_UUID, parsed.uuid());
        assertFalse(parsed.companionApl());
        assertFalse(parsed.usesTls());
    }

    @Test
    public void emitsRecoveredChecksumsForAllKnownFlagCombinations() {
        int[] expected = {0x6567, 0x6566, 0x6565, 0x6564};
        for (int flags = 0; flags < expected.length; flags++) {
            byte[] encoded = NrLinkBluetoothPrelude.encode(
                    NrLinkBluetoothPrelude.PairingState
                            .MODERN_PAIRING_KEY_CONFIRMATION,
                    SYNTHETIC_UUID,
                    flags);
            assertEquals(
                    expected[flags],
                    readU16Be(encoded, encoded.length - 2));
            assertEquals(
                    flags,
                    NrLinkBluetoothPrelude.parse(encoded).flags);
        }
    }

    @Test
    public void parsesUnknownWellFormedTlvWithoutChangingRequiredFields() {
        byte[] standard = NrLinkBluetoothPrelude.encodeFreshModern(
                SYNTHETIC_UUID,
                true,
                true);
        byte[] extended = new byte[standard.length + 4];
        System.arraycopy(standard, 0, extended, 0, 36);
        extended[10] = 0;
        extended[11] = 28;
        extended[36] = 9;
        extended[37] = 0;
        extended[38] = 1;
        extended[39] = 0x55;
        int checksum =
                NrLinkBluetoothPrelude.internetChecksum(
                        extended,
                        extended.length - 2);
        extended[40] = (byte) (checksum >>> 8);
        extended[41] = (byte) checksum;

        NrLinkBluetoothPrelude.Parsed parsed =
                NrLinkBluetoothPrelude.parse(extended);
        assertArrayEquals(SYNTHETIC_UUID, parsed.uuid());
        assertTrue(parsed.companionApl());
        assertTrue(parsed.usesTls());
    }

    @Test
    public void rejectsCorruptOrAmbiguousPreludes() {
        byte[] valid = NrLinkBluetoothPrelude.encodeFreshModern(
                SYNTHETIC_UUID,
                false,
                false);

        byte[] corruptMagic = valid.clone();
        corruptMagic[0] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> NrLinkBluetoothPrelude.parse(corruptMagic));

        byte[] corruptChecksum = valid.clone();
        corruptChecksum[20] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> NrLinkBluetoothPrelude.parse(corruptChecksum));

        byte[] corruptLength = valid.clone();
        corruptLength[11] = 23;
        assertThrows(
                IllegalArgumentException.class,
                () -> NrLinkBluetoothPrelude.parse(corruptLength));

        byte[] unknownState = valid.clone();
        unknownState[9] = 99;
        rewriteChecksum(unknownState);
        assertThrows(
                IllegalArgumentException.class,
                () -> NrLinkBluetoothPrelude.parse(unknownState));
    }

    @Test
    public void electsLargerUnsignedUuidAsResponder() {
        byte[] smaller = new byte[16];
        byte[] larger = new byte[16];
        smaller[0] = 0x7f;
        larger[0] = (byte) 0x80;

        assertEquals(
                NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                NrLinkBluetoothPrelude.electLocalRole(
                        smaller,
                        larger));
        assertEquals(
                NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                NrLinkBluetoothPrelude.electLocalRole(
                        larger,
                        smaller));
        assertThrows(
                IllegalArgumentException.class,
                () -> NrLinkBluetoothPrelude.electLocalRole(
                        smaller,
                        smaller.clone()));
    }

    @Test
    public void implementsRecoveredDirectionalReconciliationTable() {
        NrLinkBluetoothPrelude.PairingState[] states = {
                state(0), state(10), state(11),
                state(12), state(13), state(20)
        };
        int[][] expected = {
                {0, -1, 0, 0, -1, 0},
                {-1, 10, -1, 10, -1, -1},
                {-1, -1, 11, 11, -1, 11},
                {-1, 10, 11, 12, -1, 12},
                {-1, -1, 11, 12, 13, 13},
                {-1, -1, 11, 12, -1, 20}
        };

        for (int local = 0; local < states.length; local++) {
            for (int remote = 0;
                    remote < states.length;
                    remote++) {
                int expectedWire = expected[local][remote];
                if (expectedWire < 0) {
                    final int localIndex = local;
                    final int remoteIndex = remote;
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> NrLinkBluetoothPrelude.reconcile(
                                    states[localIndex],
                                    states[remoteIndex]));
                } else {
                    assertEquals(
                            expectedWire,
                            NrLinkBluetoothPrelude.reconcile(
                                    states[local],
                                    states[remote])
                                    .wireValue());
                }
            }
        }

        assertFalse(
                NrLinkBluetoothPrelude.isBilaterallyCompatible(
                        state(13),
                        state(20)));
        assertTrue(
                NrLinkBluetoothPrelude.isBilaterallyCompatible(
                        state(13),
                        state(13)));
    }

    @Test
    public void jointUuidHashIsOrderIndependentAndSixCharacters() {
        byte[] first = SYNTHETIC_UUID.clone();
        byte[] second = SYNTHETIC_UUID.clone();
        for (int index = 0; index < second.length; index++) {
            second[index] ^= (byte) (0xf0 + index);
        }
        String forward =
                NrLinkBluetoothPrelude.jointUuidHash(
                        first,
                        second);
        String reverse =
                NrLinkBluetoothPrelude.jointUuidHash(
                        second,
                        first);

        assertEquals(6, forward.length());
        assertEquals(forward, reverse);
        assertArrayEquals(SYNTHETIC_UUID, first);
    }

    private static NrLinkBluetoothPrelude.PairingState state(
            int wireValue) {
        return NrLinkBluetoothPrelude.PairingState
                .fromWireValue(wireValue);
    }

    private static void rewriteChecksum(byte[] encoded) {
        int checksum =
                NrLinkBluetoothPrelude.internetChecksum(
                        encoded,
                        encoded.length - 2);
        encoded[encoded.length - 2] =
                (byte) (checksum >>> 8);
        encoded[encoded.length - 1] =
                (byte) checksum;
    }

    private static int readU16Be(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private static byte[] hex(String text) {
        String compact = text.replaceAll("\\s+", "");
        byte[] output = new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    compact.substring(
                            index * 2,
                            index * 2 + 2),
                    16);
        }
        return output;
    }
}
