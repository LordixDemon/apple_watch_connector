package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

public final class BleSecureConnectionsCryptoTest {
    @Test
    public void f4MatchesBluetoothCoreSampleD2InSmpByteOrder() {
        byte[] u = reverse(hex(
                "20b003d2f297be2c5e2c83a7e9f9a5b9"
                        + "eff49111acf4fddbcc0301480e359de6"));
        byte[] v = reverse(hex(
                "55188b3d32f6bb9a900afcfbeed4e72a"
                        + "59cb9ac2f19d7cfb6b4fdd49f47fc5fd"));
        byte[] x = reverse(hex(
                "d5cb8454d177733effffb2ec712baeab"));

        assertArrayEquals(
                reverse(hex(
                        "f2c916f107a9bd1cf1eda1bea974872d")),
                BleSecureConnectionsCrypto.f4(
                        u,
                        v,
                        x,
                        0));
    }

    @Test
    public void generatedOobUsesSameKeyPairForCommitmentAndDhKey() {
        BleSecureConnectionsCrypto.LocalOobMaterial first =
                BleSecureConnectionsCrypto.generateLocalOob(
                        new SecureRandom());
        BleSecureConnectionsCrypto.LocalOobMaterial second =
                BleSecureConnectionsCrypto.generateLocalOob(
                        new SecureRandom());
        try {
            byte[] firstPublic =
                    first.publicKeyForSmp();
            byte[] secondPublic =
                    second.publicKeyForSmp();
            assertFalse(allZero(firstPublic));
            assertFalse(allZero(secondPublic));
            assertArrayEquals(
                    first.calculateDhKey(secondPublic),
                    second.calculateDhKey(firstPublic));

            byte[] serialized = first.appleOobData();
            AppleLeScOobData parsed =
                    AppleLeScOobData.parse(serialized);
            try {
                byte[] publicX = Arrays.copyOfRange(
                        firstPublic,
                        0,
                        BleSecureConnectionsCrypto
                                .P256_VALUE_LENGTH);
                assertArrayEquals(
                        BleSecureConnectionsCrypto.f4(
                                publicX,
                                publicX,
                                parsed.randomizerForAndroid(),
                                0),
                        parsed.confirmationForAndroid());
            } finally {
                parsed.destroy();
            }
        } finally {
            first.destroy();
            second.destroy();
        }
        assertTrue(first.isDestroyed());
        assertTrue(second.isDestroyed());
        assertThrows(
                IllegalStateException.class,
                first::appleOobData);
    }

    @Test
    public void f5MatchesBluetoothCoreSampleD3InSmpByteOrder() {
        byte[] dhKey = reverse(hex(
                "ec0234a357c8ad05341010a60a397d9b"
                        + "99796b13b4f866f1868d34f373bfa698"));
        byte[] n1 = reverse(hex(
                "d5cb8454d177733effffb2ec712baeab"));
        byte[] n2 = reverse(hex(
                "a6e8e7cc25a75f6e216583f7ff3dc4cf"));
        byte[] a1 = reverse(hex("0056123737bfce"));
        byte[] a2 = reverse(hex("00a713702dcfc1"));

        BleSecureConnectionsCrypto.F5Result result =
                BleSecureConnectionsCrypto.f5(
                        dhKey,
                        n1,
                        n2,
                        a1,
                        a2);
        try {
            assertArrayEquals(
                    reverse(hex(
                            "2965f176a1084a02fd3f6a20ce636e20")),
                    result.macKey());
            assertArrayEquals(
                    reverse(hex(
                            "6986791169d7cd23980522b594750a38")),
                    result.longTermKey());
        } finally {
            result.destroy();
        }
        assertThrows(
                IllegalStateException.class,
                result::macKey);
    }

    @Test
    public void f6MatchesBluetoothCoreSampleD4InSmpByteOrder() {
        assertArrayEquals(
                reverse(hex(
                        "e3c473989cd0e8c5d26c0b09da958f61")),
                BleSecureConnectionsCrypto.f6(
                        reverse(hex(
                                "2965f176a1084a02fd3f6a20ce636e20")),
                        reverse(hex(
                                "d5cb8454d177733effffb2ec712baeab")),
                        reverse(hex(
                                "a6e8e7cc25a75f6e216583f7ff3dc4cf")),
                        reverse(hex(
                                "12a3343bb453bb5408da42d20c2d0fc8")),
                        reverse(hex("010102")),
                        reverse(hex("0056123737bfce")),
                        reverse(hex("00a713702dcfc1"))));
    }

    @Test
    public void rejectsInvalidPeerPoint() {
        BleSecureConnectionsCrypto.LocalOobMaterial local =
                BleSecureConnectionsCrypto.generateLocalOob(
                        new SecureRandom());
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> local.calculateDhKey(
                            new byte[
                                    BleSecureConnectionsCrypto
                                            .PUBLIC_KEY_LENGTH]));
        } finally {
            local.destroy();
        }
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] reverse(byte[] value) {
        byte[] output = value.clone();
        for (int left = 0, right = output.length - 1;
                left < right;
                left++, right--) {
            byte swap = output[left];
            output[left] = output[right];
            output[right] = swap;
        }
        return output;
    }

    private static byte[] hex(String value) {
        byte[] output = new byte[value.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    value.substring(index * 2, index * 2 + 2),
                    16);
        }
        return output;
    }
}
