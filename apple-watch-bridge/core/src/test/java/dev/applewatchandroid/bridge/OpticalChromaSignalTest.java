package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OpticalChromaSignalTest {
    @Test public void matchesNativeFixedPointRoundingAndSaturation() throws Exception {
        try (var stream = getClass().getResourceAsStream("/optical/chroma-oracle.json")) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            byte[] uv = value(json, "uv"), expected = value(json, "signal");
            assertArrayEquals(expected, OpticalChromaSignal.convert(uv, 16, 16));
            assertThrows(IllegalArgumentException.class, () -> OpticalChromaSignal.convert(uv, 15, 16));
            assertThrows(IllegalArgumentException.class, () -> OpticalChromaSignal.convert(uv, Integer.MAX_VALUE, 16));
        }
    }
    private static byte[] value(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\": \"([0-9a-f]+)\"").matcher(json);
        assertTrue(matcher.find()); return HexFormat.of().parseHex(matcher.group(1));
    }
}
