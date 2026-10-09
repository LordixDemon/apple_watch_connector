package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class BinaryPropertyListCodecTest {
    @Test public void independentPlistlibNullValuesSurviveSnapshotsWithoutLooseningWireDictionary() throws Exception {
        byte[] independent = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/plist-399-null-values.bin"));
        var expected = new LinkedHashMap<String, Object>();
        expected.put("nullable", null);
        expected.put("items", java.util.Arrays.asList(null, 3L, Map.of("enabled", true)));
        assertEquals(expected, AppleBinaryPropertyList.decode(independent));
        assertEquals(expected, AppleBinaryPropertyList.decode(AppleBinaryPropertyList.encode(expected)));
        assertArrayEquals(new byte[] {0x00}, java.util.Arrays.copyOfRange(
                AppleBinaryPropertyList.encode(null), 8, 9));
        org.junit.Assert.assertNull(AppleBinaryPropertyList.decode(AppleBinaryPropertyList.encode(null)));
        // Setup/sync wire dictionaries have their own narrower contract.
        assertThrows(IllegalArgumentException.class, () -> BinaryPropertyListCodec.encodeDictionary(expected));
        var cycle = new java.util.ArrayList<Object>();
        cycle.add(null);
        cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> AppleBinaryPropertyList.encode(cycle));
    }
    @Test public void independentPlistlibUnsignedWidthsAndSignedEightByteIntegersRoundTrip() throws Exception {
        List<Long> expected = List.of(0L, 127L, 128L, 255L, 256L, 32768L, 65535L, 65536L,
                2147483648L, 4294967295L, -1L, Long.MIN_VALUE, Long.MAX_VALUE);
        byte[] independent = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/plist-355-integer-widths.bin"));
        assertEquals(expected, AppleBinaryPropertyList.decode(independent));
        assertArrayEquals(independent, AppleBinaryPropertyList.encode(expected));
        assertEquals(expected, AppleBinaryPropertyList.decode(AppleBinaryPropertyList.encode(expected)));
    }
    @Test
    public void stringArraysUseExtendedCountsAndWideObjectReferences() {
        List<String> labels = java.util.stream.IntStream.range(0, 260)
                .mapToObj(index -> "activity-" + index).toList();
        Map<String, Object> values = Map.of("activeActivityLabels", labels,
                "completedActivityLabels", List.of());
        assertEquals(values, BinaryPropertyListCodec.decodeDictionary(
                BinaryPropertyListCodec.encodeDictionary(values)));
        assertThrows(IllegalArgumentException.class, () -> BinaryPropertyListCodec.encodeDictionary(
                Map.of("activeActivityLabels", List.of(1L))));
        assertThrows(IllegalArgumentException.class, () -> BinaryPropertyListCodec.encodeDictionary(
                Map.of("activeActivityLabels", java.util.Collections.nCopies(1024, "activity"))));
    }

    @Test
    public void deterministicDictionaryRoundTripsSupportedScalars() {
        LinkedHashMap<String, Object> source =
                new LinkedHashMap<>();
        source.put(
                "version",
                1L);
        source.put(
                "enabled",
                Boolean.FALSE);
        source.put(
                "label",
                "Ultra 2");

        byte[] encoded =
                BinaryPropertyListCodec.encodeDictionary(
                        source);
        Map<String, Object> decoded =
                BinaryPropertyListCodec.decodeDictionary(
                        encoded);

        assertArrayEquals(
                "bplist00".getBytes(
                        StandardCharsets.US_ASCII),
                java.util.Arrays.copyOf(
                        encoded,
                        8));
        assertEquals(
                source,
                decoded);
    }

    @Test
    public void decodesIndependentPythonPlistlibCompletionVectors() {
        byte[] watchState =
                hex(
                        "62706c6973743030d30102030405065776657273696f6e"
                                + "5f101173796e6350726f677265737353746174655e676c"
                                + "6f62616c50726f6772657373100110031064080f172b3a"
                                + "3c3e000000000000010100000000000000070000000000"
                                + "0000000000000000000040");
        byte[] clientState =
                hex(
                        "62706c6973743030d401020304050607085776657273696f"
                                + "6e5f101173796e6350726f677265737353746174655f10"
                                + "0f73796e6353657373696f6e547970655d6d6967726174"
                                + "696f6e53796e63100110031000080811192d3f4d4f5153"
                                + "0000000000000101000000000000000900000000000000"
                                + "000000000000000054");

        assertEquals(
                Map.of(
                        "version",
                        1L,
                        "syncProgressState",
                        3L,
                        "globalProgress",
                        100L),
                BinaryPropertyListCodec.decodeDictionary(
                        watchState));
        assertEquals(
                Map.of(
                        "version",
                        1L,
                        "syncProgressState",
                        3L,
                        "syncSessionType",
                        0L,
                        "migrationSync",
                        Boolean.FALSE),
                BinaryPropertyListCodec.decodeDictionary(
                        clientState));
    }

    @Test
    public void malformedOrUnsupportedPlistsFailClosed() {
        byte[] invalidHeader =
                new byte[40];
        assertThrows(
                IllegalArgumentException.class,
                () -> BinaryPropertyListCodec.decodeDictionary(
                        invalidHeader));
        assertThrows(
                IllegalArgumentException.class,
                () -> BinaryPropertyListCodec.encodeDictionary(
                        Map.of(
                                "unsupported",
                                1.5)));

        LinkedHashMap<String, Object> emptyKey =
                new LinkedHashMap<>();
        emptyKey.put(
                "",
                1L);
        assertThrows(
                IllegalArgumentException.class,
                () -> BinaryPropertyListCodec.encodeDictionary(
                        emptyKey));
        assertTrue(
                BinaryPropertyListCodec.decodeDictionary(
                        BinaryPropertyListCodec.encodeDictionary(
                                Map.of()))
                        .isEmpty());
    }

    private static byte[] hex(
            String value) {
        byte[] result =
                new byte[value.length() / 2];
        for (int index = 0;
                index < result.length;
                index++) {
            result[index] =
                    (byte) Integer.parseInt(
                            value.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return result;
    }
}
