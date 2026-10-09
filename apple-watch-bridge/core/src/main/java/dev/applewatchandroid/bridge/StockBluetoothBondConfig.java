package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Encodes the already-authenticated Watch bond in Android's legacy
 * {@code bt_config.conf} representation.
 *
 * <p>The returned bytes contain secret key material and must never be logged.
 * Callers are responsible for wiping them after use.</p>
 */
final class StockBluetoothBondConfig {
    private static final int LOCAL_ADDRESS_TYPE_OFFSET = 5;
    private static final int LOCAL_ADDRESS_OFFSET = 6;
    private static final int PEER_CONNECTION_ADDRESS_TYPE_OFFSET = 12;
    private static final int PEER_CONNECTION_ADDRESS_OFFSET = 13;
    private static final int PEER_IDENTITY_ADDRESS_TYPE_OFFSET = 19;
    private static final int PEER_IDENTITY_ADDRESS_OFFSET = 20;
    private static final int LONG_TERM_KEY_OFFSET = 26;
    private static final int LOCAL_IRK_OFFSET = 42;
    private static final int PEER_IRK_OFFSET = 58;
    private static final int ENCRYPTION_KEY_SIZE_OFFSET = 74;

    private static final int ADDRESS_LENGTH = 6;
    private static final int KEY_LENGTH = 16;
    // Android's SMP_SEC_AUTHENTICATED is (1 << 2), not the HCI security
    // mode value 2.
    private static final int SMP_SEC_AUTHENTICATED = 4;
    private static final int LEGACY_INCORRECT_SECURITY_LEVEL = 2;

    private StockBluetoothBondConfig() {
    }

    static byte[] buildAppendix(
            byte[] record,
            long timestampSeconds) {
        BluetoothBondSecretRecord.validateSerialized(record);
        if (timestampSeconds <= 0) {
            throw new IllegalArgumentException(
                    "A positive bond timestamp is required");
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream(420);
        writeAscii(output, "\n[");
        writeAddress(
                output,
                record,
                PEER_IDENTITY_ADDRESS_OFFSET);
        writeAscii(output, "]\n");
        writeAscii(output, "Timestamp = ");
        writeAscii(
                output,
                Long.toString(timestampSeconds));
        writeAscii(output, "\n");
        writeAscii(output, "Name = Apple Watch 26943\n");
        writeAscii(output, "DevType = 2\n");
        writeAscii(output, "AddrType = ");
        writeAscii(
                output,
                Integer.toString(unsigned(
                        record[
                                PEER_IDENTITY_ADDRESS_TYPE_OFFSET])));
        writeAscii(output, "\n");

        output.writeBytes(
                pencEntry(
                        record,
                        SMP_SEC_AUTHENTICATED));

        output.writeBytes(pidEntry(record));

        output.writeBytes(
                lencEntry(
                        record,
                        SMP_SEC_AUTHENTICATED));
        output.writeBytes(lidEntry(record));
        writeAscii(output, "BondState = TRUE\n");
        return output.toByteArray();
    }

    static byte[] canonicalPencEntry(
            byte[] record) {
        return pencEntry(
                record,
                SMP_SEC_AUTHENTICATED);
    }

    static byte[] legacyPencEntry(
            byte[] record) {
        return pencEntry(
                record,
                LEGACY_INCORRECT_SECURITY_LEVEL);
    }

    static byte[] canonicalLencEntry(
            byte[] record) {
        return lencEntry(
                record,
                SMP_SEC_AUTHENTICATED);
    }

    static byte[] legacyLencEntry(
            byte[] record) {
        return lencEntry(
                record,
                LEGACY_INCORRECT_SECURITY_LEVEL);
    }

    static byte[] canonicalLidEntry(
            byte[] record) {
        return lidEntry(record);
    }

    static byte[] canonicalPidEntry(
            byte[] record) {
        return pidEntry(record);
    }

    static byte[] canonicalAdapterIrkEntry(
            byte[] record) {
        BluetoothBondSecretRecord.validateSerialized(record);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(56);
        writeAscii(
                output,
                "LE_LOCAL_KEY_IRK = ");
        writeLowerHex(
                output,
                record,
                LOCAL_IRK_OFFSET,
                KEY_LENGTH);
        writeAscii(output, "\n");
        return output.toByteArray();
    }

    static byte[] legacyEmptyLidEntry() {
        // ConfigCache rewrites an empty binary value with a separator space.
        return ascii("LE_KEY_LID = \n");
    }

    static byte[] legacyEmptyLidEntryBeforeConfigRewrite() {
        return ascii("LE_KEY_LID =\n");
    }

    static byte[] identitySectionHeader(
            byte[] record) {
        BluetoothBondSecretRecord.validateSerialized(record);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(20);
        writeAscii(output, "[");
        writeAddress(
                output,
                record,
                PEER_IDENTITY_ADDRESS_OFFSET);
        writeAscii(output, "]");
        return output.toByteArray();
    }

    static void validateRecordForStockImport(
            byte[] record) {
        BluetoothBondSecretRecord.validateSerialized(record);
        int localType =
                unsigned(record[
                        LOCAL_ADDRESS_TYPE_OFFSET]);
        int connectionType =
                unsigned(record[
                        PEER_CONNECTION_ADDRESS_TYPE_OFFSET]);
        int identityType =
                unsigned(record[
                        PEER_IDENTITY_ADDRESS_TYPE_OFFSET]);
        if ((localType != 0 && localType != 1)
                || (connectionType != 0
                && connectionType != 1)
                || (identityType != 0
                && identityType != 1)) {
            throw new IllegalArgumentException(
                    "Bond record contains an unsupported address type");
        }
        requireNonZero(
                record,
                LOCAL_ADDRESS_OFFSET,
                ADDRESS_LENGTH,
                "local address");
        requireNonZero(
                record,
                PEER_CONNECTION_ADDRESS_OFFSET,
                ADDRESS_LENGTH,
                "peer connection address");
        requireNonZero(
                record,
                PEER_IDENTITY_ADDRESS_OFFSET,
                ADDRESS_LENGTH,
                "peer identity address");
        requireNonZero(
                record,
                LONG_TERM_KEY_OFFSET,
                KEY_LENGTH,
                "LTK");
        requireNonZero(
                record,
                LOCAL_IRK_OFFSET,
                KEY_LENGTH,
                "local IRK");
        requireNonZero(
                record,
                PEER_IRK_OFFSET,
                KEY_LENGTH,
                "peer IRK");
    }

    static boolean contains(
            byte[] haystack,
            byte[] needle) {
        if (haystack == null
                || needle == null
                || needle.length == 0
                || needle.length > haystack.length) {
            return false;
        }
        outer:
        for (int offset = 0;
                offset <= haystack.length - needle.length;
                offset++) {
            for (int index = 0;
                    index < needle.length;
                    index++) {
                if (haystack[offset + index]
                        != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static boolean startsWith(
            byte[] value,
            byte[] prefix) {
        if (value == null
                || prefix == null
                || value.length < prefix.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0;
                index < prefix.length;
                index++) {
            difference |= value[index] ^ prefix[index];
        }
        return difference == 0;
    }

    static boolean endsWith(
            byte[] value,
            byte[] suffix) {
        if (value == null
                || suffix == null
                || value.length < suffix.length) {
            return false;
        }
        int difference = 0;
        int offset = value.length - suffix.length;
        for (int index = 0;
                index < suffix.length;
                index++) {
            difference |= value[offset + index]
                    ^ suffix[index];
        }
        return difference == 0;
    }

    static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] pencEntry(
            byte[] record,
            int securityLevel) {
        BluetoothBondSecretRecord.validateSerialized(record);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(72);
        writeAscii(output, "LE_KEY_PENC = ");
        writeLowerHex(
                output,
                record,
                LONG_TERM_KEY_OFFSET,
                KEY_LENGTH);
        writeZeroHex(output, 10);
        writeLowerHexByte(output, securityLevel);
        writeLowerHexByte(
                output,
                unsigned(record[
                        ENCRYPTION_KEY_SIZE_OFFSET]));
        writeAscii(output, "\n");
        return output.toByteArray();
    }

    private static byte[] lencEntry(
            byte[] record,
            int securityLevel) {
        BluetoothBondSecretRecord.validateSerialized(record);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(56);
        writeAscii(output, "LE_KEY_LENC = ");
        writeLowerHex(
                output,
                record,
                LONG_TERM_KEY_OFFSET,
                KEY_LENGTH);
        writeZeroHex(output, 2);
        writeLowerHexByte(
                output,
                unsigned(record[
                        ENCRYPTION_KEY_SIZE_OFFSET]));
        writeLowerHexByte(output, securityLevel);
        writeAscii(output, "\n");
        return output.toByteArray();
    }

    private static byte[] pidEntry(
            byte[] record) {
        BluetoothBondSecretRecord.validateSerialized(record);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(64);
        writeAscii(output, "LE_KEY_PID = ");
        writeHex(
                output,
                record,
                PEER_IRK_OFFSET,
                KEY_LENGTH);
        writeHexByte(
                output,
                unsigned(record[
                        PEER_IDENTITY_ADDRESS_TYPE_OFFSET]));
        writeReversedHex(
                output,
                record,
                PEER_IDENTITY_ADDRESS_OFFSET,
                ADDRESS_LENGTH);
        writeAscii(output, "\n");
        return output.toByteArray();
    }

    private static byte[] lidEntry(
            byte[] record) {
        BluetoothBondSecretRecord.validateSerialized(record);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(64);
        // BTM_LE_KEY_LID is serialized as tBTM_LE_PID_KEYS:
        // local IRK, local identity-address type, local identity address.
        // It is per-peer metadata and does not replace Adapter/LE_LOCAL_KEY_IRK.
        writeAscii(output, "LE_KEY_LID = ");
        writeLowerHex(
                output,
                record,
                LOCAL_IRK_OFFSET,
                KEY_LENGTH);
        writeLowerHexByte(
                output,
                unsigned(record[
                        LOCAL_ADDRESS_TYPE_OFFSET]));
        writeLowerReversedHex(
                output,
                record,
                LOCAL_ADDRESS_OFFSET,
                ADDRESS_LENGTH);
        writeAscii(output, "\n");
        return output.toByteArray();
    }

    private static void requireNonZero(
            byte[] value,
            int offset,
            int length,
            String label) {
        int combined = 0;
        for (int index = 0;
                index < length;
                index++) {
            combined |= unsigned(value[offset + index]);
        }
        if (combined == 0) {
            throw new IllegalArgumentException(
                    "Bond record " + label + " is all zero");
        }
    }

    private static void writeAddress(
            ByteArrayOutputStream output,
            byte[] record,
            int offset) {
        for (int index = ADDRESS_LENGTH - 1;
                index >= 0;
                index--) {
            if (index != ADDRESS_LENGTH - 1) {
                output.write(':');
            }
            writeLowerHexByte(
                    output,
                    unsigned(record[offset + index]));
        }
    }

    private static void writeReversedHex(
            ByteArrayOutputStream output,
            byte[] value,
            int offset,
            int length) {
        for (int index = length - 1;
                index >= 0;
                index--) {
            writeHexByte(
                    output,
                    unsigned(value[offset + index]));
        }
    }

    private static void writeHex(
            ByteArrayOutputStream output,
            byte[] value,
            int offset,
            int length) {
        for (int index = 0;
                index < length;
                index++) {
            writeHexByte(
                    output,
                    unsigned(value[offset + index]));
        }
    }

    private static void writeLowerHex(
            ByteArrayOutputStream output,
            byte[] value,
            int offset,
            int length) {
        for (int index = 0;
                index < length;
                index++) {
            writeLowerHexByte(
                    output,
                    unsigned(value[offset + index]));
        }
    }

    private static void writeLowerReversedHex(
            ByteArrayOutputStream output,
            byte[] value,
            int offset,
            int length) {
        for (int index = length - 1;
                index >= 0;
                index--) {
            writeLowerHexByte(
                    output,
                    unsigned(value[offset + index]));
        }
    }

    private static void writeZeroHex(
            ByteArrayOutputStream output,
            int byteCount) {
        for (int index = 0;
                index < byteCount;
                index++) {
            output.write('0');
            output.write('0');
        }
    }

    private static void writeHexByte(
            ByteArrayOutputStream output,
            int value) {
        final byte[] digits =
                "0123456789ABCDEF"
                        .getBytes(StandardCharsets.US_ASCII);
        output.write(digits[(value >>> 4) & 0x0f]);
        output.write(digits[value & 0x0f]);
        Arrays.fill(digits, (byte) 0);
    }

    private static void writeLowerHexByte(
            ByteArrayOutputStream output,
            int value) {
        final byte[] digits =
                "0123456789abcdef"
                        .getBytes(StandardCharsets.US_ASCII);
        output.write(digits[(value >>> 4) & 0x0f]);
        output.write(digits[value & 0x0f]);
        Arrays.fill(digits, (byte) 0);
    }

    private static void writeAscii(
            ByteArrayOutputStream output,
            String value) {
        byte[] bytes =
                value.getBytes(StandardCharsets.US_ASCII);
        output.writeBytes(bytes);
        Arrays.fill(bytes, (byte) 0);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
