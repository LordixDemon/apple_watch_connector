package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.Locale;

/**
 * Versioned plaintext record encrypted by the application before persistence.
 *
 * <p>No field formatter or getter is provided: callers either create the
 * complete record or validate an opaque record immediately before encrypting
 * it with Android Keystore.</p>
 */
final class BluetoothBondSecretRecord {
    static final int SERIALIZED_LENGTH = 75;

    private static final byte[] MAGIC =
            new byte[]{'A', 'W', 'B', '1'};
    private static final int FORMAT_VERSION = 1;
    private static final int ADDRESS_LENGTH = 6;
    private static final int KEY_LENGTH = 16;
    private static final int PEER_IDENTITY_ADDRESS_OFFSET =
            MAGIC.length
                    + 1
                    + (1 + ADDRESS_LENGTH) * 2
                    + 1;

    private BluetoothBondSecretRecord() {
    }

    static byte[] encode(
            int localAddressType,
            byte[] localAddress,
            int peerConnectionAddressType,
            byte[] peerConnectionAddress,
            int peerIdentityAddressType,
            byte[] peerIdentityAddress,
            byte[] longTermKey,
            byte[] localIdentityResolvingKey,
            byte[] peerIdentityResolvingKey,
            int encryptionKeySize) {
        requireAddress(
                "local address",
                localAddressType,
                localAddress);
        requireAddress(
                "peer connection address",
                peerConnectionAddressType,
                peerConnectionAddress);
        requireAddress(
                "peer identity address",
                peerIdentityAddressType,
                peerIdentityAddress);
        requireKey("LTK", longTermKey);
        requireKey(
                "local IRK",
                localIdentityResolvingKey);
        requireKey(
                "peer IRK",
                peerIdentityResolvingKey);
        if (encryptionKeySize < 7
                || encryptionKeySize > 16) {
            throw new IllegalArgumentException(
                    "Bluetooth encryption key size is invalid");
        }

        byte[] output =
                new byte[SERIALIZED_LENGTH];
        int offset = 0;
        System.arraycopy(
                MAGIC,
                0,
                output,
                offset,
                MAGIC.length);
        offset += MAGIC.length;
        output[offset++] =
                (byte) FORMAT_VERSION;
        offset = putAddress(
                output,
                offset,
                localAddressType,
                localAddress);
        offset = putAddress(
                output,
                offset,
                peerConnectionAddressType,
                peerConnectionAddress);
        offset = putAddress(
                output,
                offset,
                peerIdentityAddressType,
                peerIdentityAddress);
        offset = put(
                output,
                offset,
                longTermKey);
        offset = put(
                output,
                offset,
                localIdentityResolvingKey);
        offset = put(
                output,
                offset,
                peerIdentityResolvingKey);
        output[offset++] =
                (byte) encryptionKeySize;
        if (offset != output.length) {
            Arrays.fill(output, (byte) 0);
            throw new IllegalStateException(
                    "Bluetooth bond secret record size mismatch");
        }
        return output;
    }

    static void validateSerialized(byte[] record) {
        if (record == null
                || record.length != SERIALIZED_LENGTH
                || !startsWith(record, MAGIC)
                || unsigned(record[MAGIC.length])
                != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Bluetooth bond secret record is invalid");
        }
        int firstAddressTypeOffset =
                MAGIC.length + 1;
        int secondAddressTypeOffset =
                firstAddressTypeOffset + 7;
        int thirdAddressTypeOffset =
                secondAddressTypeOffset + 7;
        requireAddressType(
                unsigned(record[firstAddressTypeOffset]));
        requireAddressType(
                unsigned(record[secondAddressTypeOffset]));
        requireAddressType(
                unsigned(record[thirdAddressTypeOffset]));
        int keySize =
                unsigned(record[record.length - 1]);
        if (keySize < 7 || keySize > 16) {
            throw new IllegalArgumentException(
                    "Bluetooth bond secret key size is invalid");
        }
    }

    private static final int LTK_OFFSET =
            MAGIC.length
                    + 1
                    + (1 + ADDRESS_LENGTH) * 3;

    static String peerIdentityAddressText(
            byte[] record) {
        validateSerialized(record);
        StringBuilder output =
                new StringBuilder(17);
        for (int index = ADDRESS_LENGTH - 1;
                index >= 0;
                index--) {
            if (output.length() != 0) {
                output.append(':');
            }
            output.append(String.format(
                    Locale.US,
                    "%02X",
                    record[
                            PEER_IDENTITY_ADDRESS_OFFSET
                                    + index]
                            & 0xff));
        }
        return output.toString();
    }

    private static final int LOCAL_IRK_OFFSET =
            LTK_OFFSET + KEY_LENGTH;
    private static final int PEER_IRK_OFFSET =
            LTK_OFFSET + KEY_LENGTH * 2;

    static byte[] extractLongTermKey(byte[] record) {
        validateSerialized(record);
        return Arrays.copyOfRange(
                record,
                LTK_OFFSET,
                LTK_OFFSET + KEY_LENGTH);
    }

    static byte[] extractLocalIdentityResolvingKey(byte[] record) {
        validateSerialized(record);
        return Arrays.copyOfRange(
                record,
                LOCAL_IRK_OFFSET,
                LOCAL_IRK_OFFSET + KEY_LENGTH);
    }

    static byte[] extractPeerIdentityResolvingKey(byte[] record) {
        validateSerialized(record);
        return Arrays.copyOfRange(
                record,
                PEER_IRK_OFFSET,
                PEER_IRK_OFFSET + KEY_LENGTH);
    }

    private static final int PEER_CONNECTION_ADDRESS_TYPE_OFFSET =
            MAGIC.length + 1 + 1 + ADDRESS_LENGTH;
    private static final int PEER_CONNECTION_ADDRESS_OFFSET =
            PEER_CONNECTION_ADDRESS_TYPE_OFFSET + 1;
    private static final int PEER_IDENTITY_ADDRESS_TYPE_OFFSET =
            PEER_IDENTITY_ADDRESS_OFFSET - 1;

    static int extractPeerConnectionAddressType(byte[] record) {
        validateSerialized(record);
        return unsigned(record[PEER_CONNECTION_ADDRESS_TYPE_OFFSET]);
    }

    static byte[] extractPeerConnectionAddress(byte[] record) {
        validateSerialized(record);
        return Arrays.copyOfRange(
                record,
                PEER_CONNECTION_ADDRESS_OFFSET,
                PEER_CONNECTION_ADDRESS_OFFSET + ADDRESS_LENGTH);
    }

    static int extractPeerIdentityAddressType(byte[] record) {
        validateSerialized(record);
        return unsigned(record[PEER_IDENTITY_ADDRESS_TYPE_OFFSET]);
    }

    static byte[] extractPeerIdentityAddress(byte[] record) {
        validateSerialized(record);
        return Arrays.copyOfRange(
                record,
                PEER_IDENTITY_ADDRESS_OFFSET,
                PEER_IDENTITY_ADDRESS_OFFSET + ADDRESS_LENGTH);
    }

    static int extractLocalAddressType(byte[] record) {
        validateSerialized(record);
        return unsigned(record[MAGIC.length + 1]);
    }

    static byte[] extractLocalAddress(byte[] record) {
        validateSerialized(record);
        return Arrays.copyOfRange(
                record,
                MAGIC.length + 2,
                MAGIC.length + 2 + ADDRESS_LENGTH);
    }

    private static int putAddress(
            byte[] output,
            int offset,
            int addressType,
            byte[] address) {
        output[offset++] = (byte) addressType;
        return put(output, offset, address);
    }

    private static int put(
            byte[] output,
            int offset,
            byte[] value) {
        System.arraycopy(
                value,
                0,
                output,
                offset,
                value.length);
        return offset + value.length;
    }

    private static void requireAddress(
            String name,
            int type,
            byte[] address) {
        requireAddressType(type);
        if (address == null
                || address.length != ADDRESS_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must be six bytes");
        }
    }

    private static void requireAddressType(int type) {
        if (type != 0 && type != 1) {
            throw new IllegalArgumentException(
                    "Bluetooth address type must be public or random");
        }
    }

    private static void requireKey(
            String name,
            byte[] key) {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must be exactly 16 bytes");
        }
    }

    private static boolean startsWith(
            byte[] value,
            byte[] prefix) {
        for (int index = 0;
                index < prefix.length;
                index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
