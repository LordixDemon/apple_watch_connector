package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.HexFormat;
import org.junit.Test;

public final class OpticalLocalContrastTest {
    @Test public void matchesOriginalNativeFilterIncludingTiesEdgesAndStridePadding() {
        // Synthetic 7x6, stride 11, native 23G71 octaxis_O at 0x29acc1054.
        // Padding is 255: incorrectly treating it as image pixels changes the result.
        byte[] input = HexFormat.of().parseHex(
                "5a5a5a5a5a5a5affffffff5a00141446dc5affffffff5a2814dc46dc5affffffff"
                + "5a28282846465affffffff5a00ffffff005affffffff5a5a5a5a5a5a5affffffff");
        byte[] expected = HexFormat.of().parseHex(
                "010203030100ff02f8fcfafb07fe0300fc08ff07ff03fefffbfefc0102f8070607f8020100fffdff0001");
        assertArrayEquals(expected, OpticalLocalContrast.convert(input, 7, 6, 11));
        assertThrows(IllegalArgumentException.class, () -> OpticalLocalContrast.convert(input, 7, 6, 6));
        assertThrows(IllegalArgumentException.class, () -> OpticalLocalContrast.convert(input, 7, 7, 11));
        assertThrows(IllegalArgumentException.class, () -> OpticalLocalContrast.convert(input, Integer.MAX_VALUE, 6, 11));
    }
}
