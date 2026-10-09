package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OpackDecoderTest {

    @Test
    public void decodesActualNativeReplicatorEncodableWitnesses() throws Exception {
        // Actual watchOS26.2 native types encoded by ReplicatorEngine.OPACKCoder.
        // These are controlled codec samples, not paired-device wire captures.
        Map<?, ?> header = nativeMap("network-header");
        assertEquals(9, header.size());
        assertEquals("00112233-4455-6677-8899-AABBCCDDEEFF", header.get("prefix"));
        assertEquals("11223344-5566-7788-9900-AABBCCDDEEFF", header.get("messageID"));
        assertEquals("22334455-6677-8899-0011-AABBCCDDEEFF", header.get("senderID"));
        assertEquals(100L, header.get("headerLength"));
        assertEquals(432L, header.get("length"));
        assertEquals(0L, header.get("messageType"));
        assertEquals(1L, header.get("sequenceCount"));
        assertEquals(0L, header.get("sequenceIndex"));
        assertEquals(0L, header.get("priority"));

        Map<?, ?> message = nativeMap("message");
        assertEquals(6, message.size());
        assertEquals(header.get("messageID"), message.get("id"));
        assertNull(message.get("responseToID"));
        assertEquals("controlled.codec.probe", message.get("messageType"));
        assertEquals("controlled-simulator", message.get("senderDeviceID"));
        assertEquals(8L, message.get("protocolVersion"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, (byte[]) message.get("encodedBody"));
        assertEquals(Map.of("ack", Map.of("_0", Map.of())), nativeMap("ack"));

        Map<?, ?> advertisement = nativeMap("advertisement");
        assertEquals(Map.of(), advertisement.get("zoneVersions"));
        Map<?, ?> device = (Map<?, ?>) advertisement.get("remoteDevice");
        assertEquals("controlled-simulator", device.get("id"));
        assertEquals(4L, device.get("deviceType"));
        assertEquals(Map.of("current", 8L, "minimum", 8L), device.get("protocolVersion"));
        assertEquals(false, device.get("isSource"));
        assertEquals(List.of(), device.get("zones"));
        assertEquals(List.of(), device.get("messageTypes"));
        assertNull(device.get("idsIdentityBlob"));

        Map<?, ?> handshake = mapPath(nativeMap("handshake"), "handshake", "_0", "request", "_0");
        assertEquals("33445566-7788-9900-1122-AABBCCDDEEFF", handshake.get("sessionID"));
        assertEquals(Map.of("paired", Map.of()), handshake.get("relationshipState"));
        assertEquals(Map.of("controlled-zone", Map.of("empty", Map.of())), handshake.get("zoneVersions"));
        assertEquals(device.get("id"), mapPath(handshake, "device").get("id"));

        Map<?, ?> modern = mapPath(nativeMap("sync"), "sync", "_0");
        Map<?, ?> legacy = mapPath(nativeMap("sync-explicit-v0"), "sync", "_0");
        assertEquals(handshake.get("sessionID"), modern.get("sessionID"));
        assertEquals(modern.get("sessionID"), legacy.get("sessionID"));
        Map<?, ?> record = mapPath(modern, "record");
        assertEquals(6L, record.get("protocolVersion"));
        assertEquals(Map.of("identifier", "controlled-snapshot", "ownership", Map.of("local", Map.of()),
                "zoneIdentifier", Map.of("id", "controlled-zone", "clientID", "controlled-client")), record.get("id"));
        Map<?, ?> data = mapPath(record, "value", "data");
        Map<?, ?> metadata = mapPath(data, "_0");
        assertEquals(record.get("id"), metadata.get("id"));
        assertEquals("44556677-8899-0011-2233-AABBCCDDEEFF", metadata.get("version"));
        assertEquals(Map.of("all", Map.of()), metadata.get("destination"));
        assertEquals(0L, metadata.get("options"));
        assertNull(metadata.get("expiration"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, (byte[]) data.get("_1"));

        Map<?, ?> oldRecord = mapPath(legacy, "record");
        assertTrue(!oldRecord.containsKey("protocolVersion"));
        assertEquals(Map.of("identifier", "controlled-snapshot", "ownership", Map.of("local", Map.of()),
                "zone", "controlled-zone"), oldRecord.get("id"));
        assertEquals(oldRecord.get("id"), mapPath(oldRecord, "value", "data", "_0").get("id"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, (byte[]) mapPath(oldRecord, "value", "data").get("_1"));
    }

    private Map<?, ?> nativeMap(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("opack-39-" + name + ".bin")) {
            assertTrue(stream != null);
            return (Map<?, ?>) OpackDecoder.decode(stream.readAllBytes());
        }
    }

    private static Map<?, ?> mapPath(Map<?, ?> value, String... keys) {
        for (String key : keys) value = (Map<?, ?>) value.get(key);
        return value;
    }

    @Test
    public void decodesActualWatchOsCoreUtilsScalarEncoding() throws Exception {
        byte[] payload;
        try (var stream = getClass().getResourceAsStream("opack-39-scalars.bin")) {
            assertTrue(stream != null);
            payload = stream.readAllBytes();
        }
        // Produced by watchOS26.2 CoreUtils, not our own encoder or a wire record.
        List<?> values = (List<?>) OpackDecoder.decode(payload);
        assertEquals(27, values.size());
        assertEquals(true, values.get(0));
        assertEquals(false, values.get(1));
        assertNull(values.get(2));
        assertEquals(0L, values.get(3));
        assertEquals(39L, values.get(4));
        assertEquals(40L, values.get(5));
        assertEquals(300L, values.get(6));
        assertEquals(70000L, values.get(7));
        assertEquals(1099511627776L, values.get(8));
        assertEquals(-1L, values.get(9));
        assertEquals(1.5, (Double) values.get(10), 0);
        assertEquals(-12.25, (Double) values.get(11), 0);
        assertEquals(750000000.25, (Double) values.get(12), 0);
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", values.get(13));
        assertEquals("", values.get(14));
        assertEquals("a", values.get(15));
        assertEquals("a", values.get(16));
        assertEquals("native snapshot", values.get(17));
        assertEquals("native snapshot", values.get(18));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, (byte[]) values.get(19));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, (byte[]) values.get(20));
        assertEquals("x".repeat(300), values.get(21));
        assertEquals(List.of("nested", "native snapshot"), values.get(22));
        assertEquals(Map.of("id", "native snapshot", "flags", true), values.get(23));
        assertArrayEquals(new byte[0], (byte[]) values.get(24));
        assertEquals("after empty", values.get(25));
        assertEquals("after empty", values.get(26));
    }

    @Test
    public void equalInlineValuesRetainDistinctReferenceIndices() {
        // Independently accepted by the watchOS26.2 native OPACK decoder.
        assertEquals(List.of("a", "a", "a"), OpackDecoder.decode(
                new byte[]{(byte) 0xd3, 0x41, 'a', 0x41, 'a', (byte) 0xa1}));
        assertEquals(List.of("a", "a"), OpackDecoder.decode(
                new byte[]{(byte) 0xd2, 0x41, 'a', (byte) 0xc3, 0, 0, 0, 0}));
        assertEquals(List.of("a", "a"), OpackDecoder.decode(
                new byte[]{(byte) 0xd2, 0x41, 'a', (byte) 0xc4, 0, 0, 0, 0, 0, 0, 0, 0}));
    }

    @Test
    public void decodesNativeUtf8AndFourByteLengthAtStringBound() throws Exception {
        byte[] payload;
        try (var stream = getClass().getResourceAsStream("opack-39-boundary-strings.bin")) {
            assertTrue(stream != null);
            payload = stream.readAllBytes();
        }
        List<?> values = (List<?>) OpackDecoder.decode(payload);
        assertEquals(List.of("x".repeat(65535), "x".repeat(65536),
                "Превью 🌍", "Превью 🌍"), values);
    }

    @Test
    public void refusesAmbiguousKeysInvalidTextAndNonFiniteNumbers() {
        for (byte[] malformed : List.of(
                new byte[]{(byte) 0xe2, 0x41, 'a', 0x08, (byte) 0xa0, 0x09},
                new byte[]{(byte) 0xe1, 0x09, 0x08},
                new byte[]{0x42, (byte) 0xc3, 0x28},
                new byte[]{0x35, 0, 0, (byte) 0x80, 0x7f},
                new byte[]{0x36, 0, 0, 0, 0, 0, 0, (byte) 0xf8, 0x7f},
                new byte[]{0x06, 0, 0, 0, 0, 0, 0, (byte) 0xf0, 0x7f})) {
            assertThrows(IllegalArgumentException.class, () -> OpackDecoder.decode(malformed));
        }
    }

    @Test
    public void rejectsOverflowingWideLengthsBeforeAllocating() {
        for (byte[] malformed : List.of(
                new byte[]{0x64, 1, 0, 0, 0, 0, 0, 0, (byte) 0x80, 'a'},
                new byte[]{(byte) 0x94, 1, 0, 0, 0, 0, 0, 0, (byte) 0x80, 1},
                new byte[]{0x63, 1, 0, 1, 0},
                new byte[]{(byte) 0xd2, 0x41, 'a', (byte) 0xc3, 0, 0, 0})) {
            assertThrows(IllegalArgumentException.class, () -> OpackDecoder.decode(malformed));
        }
    }

    @Test
    public void decodesStateUpdateDictionary() {
        // {"wristState": 1, "charging": true, "lockState": 0} as OPACK:
        // E3 (dict of 3) ; key strings 0x40+len ; small ints 0x08+n ; bool 0x01.
        byte[] opack = new byte[]{
                (byte) 0xE3,
                0x4A, 'w', 'r', 'i', 's', 't', 'S', 't', 'a', 't', 'e',
                0x09, // int 1
                0x48, 'c', 'h', 'a', 'r', 'g', 'i', 'n', 'g',
                0x01, // true
                0x49, 'l', 'o', 'c', 'k', 'S', 't', 'a', 't', 'e',
                0x08, // int 0
        };
        Object decoded = OpackDecoder.decode(opack);
        assertTrue(decoded instanceof Map);
        Map<?, ?> map = (Map<?, ?>) decoded;
        assertEquals(3, map.size());
        assertEquals(1L, map.get("wristState"));
        assertEquals(Boolean.TRUE, map.get("charging"));
        assertEquals(0L, map.get("lockState"));
    }

    @Test
    public void decodesUuidAndSizedInts() {
        // {"id": UUID(00112233-4455-6677-8899-aabbccddeeff), "n": 300 as u16}
        byte[] opack = new byte[]{
                (byte) 0xE2,
                0x42, 'i', 'd',
                0x05, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff,
                0x41, 'n',
                0x31, 0x2C, 0x01, // u16 300 little-endian
        };
        Map<?, ?> map = (Map<?, ?>) OpackDecoder.decode(opack);
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", map.get("id"));
        assertEquals(300L, map.get("n"));
    }

    @Test
    public void decodesListAndObjectReferences() {
        // ["a", "a"] with the second string encoded as object reference 0xA0.
        byte[] opack = new byte[]{
                (byte) 0xD2,
                0x41, 'a',
                (byte) 0xA0,
        };
        Object decoded = OpackDecoder.decode(opack);
        assertTrue(decoded instanceof List);
        List<?> list = (List<?>) decoded;
        assertEquals(List.of("a", "a"), list);
    }

    @Test
    public void rejectsTruncatedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> OpackDecoder.decode(new byte[]{0x4A, 'w', 'r'}));
    }

    @Test
    public void rejectsTrailingBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> OpackDecoder.decode(new byte[]{0x01, 0x01}));
    }

    @Test
    public void rejectsOversized() {
        assertThrows(IllegalArgumentException.class,
                () -> OpackDecoder.decode(new byte[OpackDecoder.MAX_BYTES + 1]));
    }

    @Test
    public void summarizeBoundsBytes() {
        byte[] opack = new byte[]{(byte) 0xE1, 0x41, 'b', 0x73, 1, 2, 3}; // {"b": 010203}
        Map<?, ?> map = (Map<?, ?>) OpackDecoder.decode(opack);
        assertEquals("{\"b\"=bytes(3)}", OpackDecoder.summarize(map));
    }
}
