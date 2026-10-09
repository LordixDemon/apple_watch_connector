package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.Test;

public final class OpticalAffinePatchTest {
    @Test public void matchesNativeRotatedScaledPatchWithExactRounding() throws Exception {
        byte[] source = new byte[64 * 68];
        for (int y = 0; y < 64; y++) for (int x = 0; x < 68; x++) {
            source[y * 68 + x] = (byte) (x < 64 ? x * 17 + y * 31 + x * y * 7 : 255);
        }
        byte[] patch = OpticalAffinePatch.sample(source, 64, 64, 68,
                new int[]{991231, 183201, -183201, 991231},
                (12 << 20) + (1 << 18), (14 << 20) + (3 << 18));
        // SHA256 of all 1296 pixels and stride padding from original native
        // 23G71 InvertLTBilinear36x36Stride48_G, 0x29acbf8f8. Synthetic input.
        assertEquals("0754363a4ca4b16678f96df0fc70e6260cc82d27d01649da525a08ea9c3a63e3",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(patch)));
        assertEquals(36 * 48, patch.length);
        assertEquals(36 * 36, OpticalLocalContrast.convert(patch, 36, 36, 48).length);
    }

    @Test public void refusesIncompletePatchesAndInvalidShapes() {
        byte[] image = new byte[64 * 64];
        int[] identity = {1 << 20, 0, 0, 1 << 20};
        assertNull(OpticalAffinePatch.sample(image, 64, 64, 64, identity, -1 << 20, 0));
        assertNull(OpticalAffinePatch.sample(image, 64, 64, 64, identity, 28 << 20, 0));
        assertNotNull(OpticalAffinePatch.sample(image, 64, 64, 64, identity, 27 << 20, 0));
        assertThrows(IllegalArgumentException.class, () -> OpticalAffinePatch.sample(
                image, 64, 64, 64, new int[]{0, 0, 0, Integer.MIN_VALUE}, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> OpticalAffinePatch.sample(
                image, 64, 64, 63, identity, 0, 0));
    }
}
