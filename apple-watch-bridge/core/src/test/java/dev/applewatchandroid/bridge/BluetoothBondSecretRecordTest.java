package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class BluetoothBondSecretRecordTest {
    @Test
    public void createsAndValidatesOpaqueVersionedRecord() {
        byte[] record =
                BluetoothBondSecretRecord.encode(
                        0,
                        sequence(0x10, 6),
                        1,
                        sequence(0x20, 6),
                        0,
                        sequence(0x30, 6),
                        sequence(0x40, 16),
                        sequence(0x50, 16),
                        sequence(0x60, 16),
                        16);

        assertEquals(
                BluetoothBondSecretRecord
                        .SERIALIZED_LENGTH,
                record.length);
        BluetoothBondSecretRecord
                .validateSerialized(record);
        assertEquals(
                "35:34:33:32:31:30",
                BluetoothBondSecretRecord
                        .peerIdentityAddressText(
                                record));

        record[0] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> BluetoothBondSecretRecord
                        .validateSerialized(record));
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
}
