package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class BluetoothLocalIdentityTest {
    @Test
    public void roundTripsStaticRandomIdentityWithoutFormatting() {
        byte[] address = hex("11 22 33 44 55 C6");
        byte[] irk = sequence(0x20, 16);
        BluetoothLocalIdentity identity =
                BluetoothLocalIdentity.create(address, irk);
        byte[] serialized = identity.serialize();
        assertEquals(28, serialized.length);

        BluetoothLocalIdentity parsed =
                BluetoothLocalIdentity.parse(serialized);
        assertEquals(1, parsed.addressType());
        assertArrayEquals(address, parsed.address());
        assertArrayEquals(irk, parsed.irk());

        parsed.destroy();
        identity.destroy();
        assertThrows(
                IllegalStateException.class,
                identity::address);
    }

    @Test
    public void rejectsNonStaticAndDegenerateRandomAddresses() {
        byte[] irk = sequence(0x40, 16);
        assertThrows(
                IllegalArgumentException.class,
                () -> BluetoothLocalIdentity.create(
                        hex("11 22 33 44 55 86"),
                        irk));
        assertThrows(
                IllegalArgumentException.class,
                () -> BluetoothLocalIdentity.create(
                        hex("00 00 00 00 00 C0"),
                        irk));
        assertThrows(
                IllegalArgumentException.class,
                () -> BluetoothLocalIdentity.create(
                        hex("FF FF FF FF FF FF"),
                        irk));
    }

    private static byte[] sequence(int start, int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) (start + index);
        }
        return output;
    }

    private static byte[] hex(String text) {
        String compact = text.replaceAll("\\s+", "");
        byte[] output = new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    compact.substring(index * 2, index * 2 + 2),
                    16);
        }
        return output;
    }
}
