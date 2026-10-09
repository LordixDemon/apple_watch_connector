package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OpticalConvolutionTest {
    private static byte[] fixture(String name) throws Exception {
        try (var stream = OpticalConvolutionTest.class.getResourceAsStream("/optical/convolution-oracle.json")) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = Pattern.compile("\"" + name + "\": \"([0-9a-f]+)\"").matcher(json);
            assertTrue(matcher.find());
            return HexFormat.of().parseHex(matcher.group(1));
        }
    }
    @Test public void decodesOriginalEncoderIncludingTailBitingAndRepeatedSymbols() throws Exception {
        byte[] coded = fixture("codedBits");
        int[] soft = new int[coded.length];
        for (int i = 0; i < coded.length; i++) soft[i] = coded[i] == 1 ? 200 : -200;
        assertArrayEquals(fixture("packetBits"), OpticalConvolution.decode(soft));
        for (int i = 0; i < soft.length; i += 41) soft[i] *= -1;
        assertArrayEquals(fixture("packetBits"), OpticalConvolution.decode(soft));
    }
    @Test public void rejectsUnrecognizableAndMalformedSignals() {
        int[] signal = new int[924];
        assertNull(OpticalConvolution.decode(signal));
        java.util.Random random = new java.util.Random(71823);
        for (int i = 0; i < signal.length; i++) signal[i] = random.nextBoolean() ? 200 : -200;
        assertNull(OpticalConvolution.decode(signal));
        signal[0] = Integer.MIN_VALUE;
        assertNull(OpticalConvolution.decode(signal));
        assertThrows(IllegalArgumentException.class, () -> OpticalConvolution.decode(new int[923]));
    }
}
