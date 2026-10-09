package dev.applewatchandroid.bridge;

import java.util.Arrays;

/**
 * Stable local BLE identity transferred from the app to the root HAL host.
 *
 * <p>The record contains a static-random address and its IRK. It deliberately
 * has no textual formatter. Callers receive defensive copies and must destroy
 * both the record object and any serialized copy after use.</p>
 */
final class BluetoothLocalIdentity implements AutoCloseable {
    static final int ADDRESS_TYPE_RANDOM = 1;
    static final int ADDRESS_LENGTH = 6;
    static final int IRK_LENGTH = 16;
    static final int SERIALIZED_LENGTH = 28;

    private static final byte[] MAGIC =
            new byte[]{'A', 'W', 'I', '1'};
    private static final int FORMAT_VERSION = 1;

    private byte[] address;
    private byte[] irk;

    private BluetoothLocalIdentity(
            byte[] address,
            byte[] irk) {
        requireStaticRandomAddress(address);
        requireIrk(irk);
        this.address = address.clone();
        this.irk = irk.clone();
    }

    static BluetoothLocalIdentity create(
            byte[] address,
            byte[] irk) {
        return new BluetoothLocalIdentity(address, irk);
    }

    static BluetoothLocalIdentity parse(byte[] serialized) {
        if (serialized == null
                || serialized.length != SERIALIZED_LENGTH
                || !startsWith(serialized, MAGIC)
                || unsigned(serialized[MAGIC.length])
                != FORMAT_VERSION
                || unsigned(serialized[MAGIC.length + 1])
                != ADDRESS_TYPE_RANDOM) {
            throw new IllegalArgumentException(
                    "Stable Bluetooth identity record is invalid");
        }
        int addressOffset = MAGIC.length + 2;
        int irkOffset = addressOffset + ADDRESS_LENGTH;
        byte[] address = Arrays.copyOfRange(
                serialized,
                addressOffset,
                irkOffset);
        byte[] irk = Arrays.copyOfRange(
                serialized,
                irkOffset,
                serialized.length);
        try {
            return create(address, irk);
        } finally {
            Arrays.fill(address, (byte) 0);
            Arrays.fill(irk, (byte) 0);
        }
    }

    byte[] serialize() {
        requireLive();
        byte[] output = new byte[SERIALIZED_LENGTH];
        System.arraycopy(MAGIC, 0, output, 0, MAGIC.length);
        output[MAGIC.length] = (byte) FORMAT_VERSION;
        output[MAGIC.length + 1] = (byte) ADDRESS_TYPE_RANDOM;
        int addressOffset = MAGIC.length + 2;
        int irkOffset = addressOffset + ADDRESS_LENGTH;
        System.arraycopy(
                address,
                0,
                output,
                addressOffset,
                address.length);
        System.arraycopy(
                irk,
                0,
                output,
                irkOffset,
                irk.length);
        return output;
    }

    int addressType() {
        requireLive();
        return ADDRESS_TYPE_RANDOM;
    }

    byte[] address() {
        requireLive();
        return address.clone();
    }

    byte[] irk() {
        requireLive();
        return irk.clone();
    }

    @Override
    public void close() {
        destroy();
    }

    void destroy() {
        if (address != null) {
            Arrays.fill(address, (byte) 0);
            address = null;
        }
        if (irk != null) {
            Arrays.fill(irk, (byte) 0);
            irk = null;
        }
    }

    private void requireLive() {
        if (address == null || irk == null) {
            throw new IllegalStateException(
                    "Stable Bluetooth identity was destroyed");
        }
    }

    static void requireStaticRandomAddress(byte[] address) {
        if (address == null || address.length != ADDRESS_LENGTH) {
            throw new IllegalArgumentException(
                    "Static-random address must contain six bytes");
        }
        if ((unsigned(address[ADDRESS_LENGTH - 1]) & 0xc0) != 0xc0) {
            throw new IllegalArgumentException(
                    "Static-random address must have two high bits set");
        }
        boolean randomPartAllZero =
                (unsigned(address[ADDRESS_LENGTH - 1]) & 0x3f) == 0;
        boolean randomPartAllOne =
                (unsigned(address[ADDRESS_LENGTH - 1]) & 0x3f) == 0x3f;
        for (int index = 0; index < ADDRESS_LENGTH - 1; index++) {
            randomPartAllZero &= address[index] == 0;
            randomPartAllOne &= unsigned(address[index]) == 0xff;
        }
        if (randomPartAllZero || randomPartAllOne) {
            throw new IllegalArgumentException(
                    "Static-random address random part is degenerate");
        }
    }

    private static void requireIrk(byte[] irk) {
        if (irk == null || irk.length != IRK_LENGTH) {
            throw new IllegalArgumentException(
                    "Identity Resolving Key must contain 16 bytes");
        }
    }

    private static boolean startsWith(
            byte[] value,
            byte[] prefix) {
        for (int index = 0; index < prefix.length; index++) {
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
