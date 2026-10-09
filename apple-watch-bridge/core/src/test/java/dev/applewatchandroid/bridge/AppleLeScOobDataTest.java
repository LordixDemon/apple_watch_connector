package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppleLeScOobDataTest {
    @Test
    public void emitsRecoveredAppleCanonicalLayout() {
        byte[] randomizer = sequence(0x10);
        byte[] confirmation = sequence(0x40);
        AppleLeScOobData data =
                AppleLeScOobData.of(
                        randomizer,
                        confirmation);
        try {
            assertArrayEquals(
                    concat(
                            new byte[]{AppleLeScOobData.RANDOMIZER_TAG},
                            randomizer,
                            new byte[]{
                                    AppleLeScOobData.CONFIRMATION_TAG
                            },
                            confirmation),
                    data.serialize());
            assertArrayEquals(
                    randomizer,
                    data.randomizerForAndroid());
            assertArrayEquals(
                    confirmation,
                    data.confirmationForAndroid());
        } finally {
            data.destroy();
        }
        assertTrue(data.isDestroyed());
        assertThrows(
                IllegalStateException.class,
                data::serialize);
    }

    @Test
    public void parsesBothRecordOrdersWithoutAliasingCaller() {
        byte[] randomizer = sequence(0x20);
        byte[] confirmation = sequence(0x60);
        byte[] reversed = concat(
                new byte[]{AppleLeScOobData.CONFIRMATION_TAG},
                confirmation,
                new byte[]{AppleLeScOobData.RANDOMIZER_TAG},
                randomizer);
        AppleLeScOobData data =
                AppleLeScOobData.parse(reversed);
        reversed[1] ^= 0x7f;
        try {
            assertArrayEquals(
                    randomizer,
                    data.randomizerForAndroid());
            assertArrayEquals(
                    confirmation,
                    data.confirmationForAndroid());
        } finally {
            data.destroy();
        }
    }

    @Test
    public void rejectsMalformedOrDuplicateRecords() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleLeScOobData.parse(new byte[33]));

        byte[] unknown = new byte[AppleLeScOobData.SERIALIZED_LENGTH];
        unknown[0] = 0x01;
        unknown[17] = AppleLeScOobData.CONFIRMATION_TAG;
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleLeScOobData.parse(unknown));

        byte[] duplicate = new byte[
                AppleLeScOobData.SERIALIZED_LENGTH];
        duplicate[0] = AppleLeScOobData.RANDOMIZER_TAG;
        duplicate[17] = AppleLeScOobData.RANDOMIZER_TAG;
        assertThrows(
                IllegalArgumentException.class,
                () -> AppleLeScOobData.parse(duplicate));
    }

    private static byte[] sequence(int first) {
        byte[] output = new byte[AppleLeScOobData.VALUE_LENGTH];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) (first + index);
        }
        return output;
    }

    private static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] output = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(
                    value,
                    0,
                    output,
                    offset,
                    value.length);
            offset += value.length;
        }
        return output;
    }
}
