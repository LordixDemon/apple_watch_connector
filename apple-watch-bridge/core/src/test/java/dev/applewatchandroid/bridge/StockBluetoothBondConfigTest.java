package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class StockBluetoothBondConfigTest {
    @Test
    public void appendixMatchesAndroidLeKeyLayouts() {
        byte[] localAddress =
                sequence(0x01, 6);
        byte[] peerConnectionAddress =
                sequence(0x11, 6);
        byte[] peerIdentityAddress =
                new byte[]{
                        0x21, 0x22, 0x23,
                        0x24, 0x25, 0x26
                };
        byte[] ltk = sequence(0x31, 16);
        byte[] localIrk = sequence(0x51, 16);
        byte[] peerIrk = sequence(0x71, 16);
        byte[] record =
                BluetoothBondSecretRecord.encode(
                        0,
                        localAddress,
                        1,
                        peerConnectionAddress,
                        0,
                        peerIdentityAddress,
                        ltk,
                        localIrk,
                        peerIrk,
                        16);

        byte[] appendix =
                StockBluetoothBondConfig
                        .buildAppendix(
                                record,
                                123456789L);
        String text =
                new String(
                        appendix,
                        StandardCharsets.US_ASCII);

        assertTrue(text.contains(
                "[26:25:24:23:22:21]"));
        assertTrue(text.contains(
                "Timestamp = 123456789"));
        assertTrue(text.contains(
                "DevType = 2"));
        assertTrue(text.contains(
                "AddrType = 0"));
        assertTrue(text.contains(
                "LE_KEY_PENC = "
                        + hexLower(ltk)
                        + "00000000000000000000"
                        + "0410"));
        assertTrue(text.contains(
                "LE_KEY_PID = "
                        + hex(peerIrk)
                        + "00"
                        + "262524232221"));
        assertTrue(text.contains(
                "LE_KEY_LENC = "
                        + hexLower(ltk)
                        + "00001004"));
        assertTrue(text.contains(
                "LE_KEY_LID = "
                        + hexLower(localIrk)
                        + "00"
                        + reverseHexLower(localAddress)));
        assertTrue(text.contains(
                "BondState = TRUE"));

        Arrays.fill(record, (byte) 0);
        Arrays.fill(appendix, (byte) 0);
    }

    @Test
    public void sectionHeaderUsesStablePeerIdentity() {
        byte[] record =
                BluetoothBondSecretRecord.encode(
                        0,
                        sequence(1, 6),
                        1,
                        sequence(11, 6),
                        1,
                        new byte[]{
                                1, 2, 3, 4, 5, 6
                        },
                        sequence(21, 16),
                        sequence(41, 16),
                        sequence(61, 16),
                        16);

        assertArrayEquals(
                "[06:05:04:03:02:01]"
                        .getBytes(
                                StandardCharsets.US_ASCII),
                StockBluetoothBondConfig
                        .identitySectionHeader(
                                record));
        Arrays.fill(record, (byte) 0);
    }

    @Test
    public void sectionHeaderCanonicalizesHexLettersToLowercase() {
        byte[] record =
                BluetoothBondSecretRecord.encode(
                        0,
                        sequence(1, 6),
                        1,
                        sequence(11, 6),
                        0,
                        new byte[]{
                                (byte) 0xaa,
                                (byte) 0xbb,
                                (byte) 0xcc,
                                (byte) 0xdd,
                                (byte) 0xee,
                                (byte) 0xff
                        },
                        sequence(21, 16),
                        sequence(41, 16),
                        sequence(61, 16),
                        16);

        assertArrayEquals(
                "[ff:ee:dd:cc:bb:aa]"
                        .getBytes(
                                StandardCharsets.US_ASCII),
                StockBluetoothBondConfig
                        .identitySectionHeader(
                                record));
        Arrays.fill(record, (byte) 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAllZeroLongTermKey() {
        byte[] record =
                BluetoothBondSecretRecord.encode(
                        0,
                        sequence(1, 6),
                        1,
                        sequence(11, 6),
                        0,
                        sequence(21, 6),
                        new byte[16],
                        sequence(31, 16),
                        sequence(51, 16),
                        16);
        try {
            StockBluetoothBondConfig
                    .validateRecordForStockImport(
                            record);
        } finally {
            Arrays.fill(record, (byte) 0);
        }
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

    private static String hex(byte[] value) {
        StringBuilder output =
                new StringBuilder(
                        value.length * 2);
        for (byte current : value) {
            output.append(String.format(
                    "%02X",
                    current & 0xff));
        }
        return output.toString();
    }

    private static String reverseHex(
            byte[] value) {
        byte[] reversed =
                new byte[value.length];
        for (int index = 0;
                index < value.length;
                index++) {
            reversed[index] =
                    value[value.length - 1 - index];
        }
        try {
            return hex(reversed);
        } finally {
            Arrays.fill(reversed, (byte) 0);
        }
    }

    private static String hexLower(
            byte[] value) {
        return hex(value).toLowerCase(
                java.util.Locale.ROOT);
    }

    private static String reverseHexLower(
            byte[] value) {
        return reverseHex(value).toLowerCase(
                java.util.Locale.ROOT);
    }
}
