package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class NanoRegistryPropertyCodecTest {
    @Test
    public void fullStringSnapshotMatchesIndependentWireVector() {
        NanoRegistryPropertyCodec.PropertiesChanged message =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        true,
                        List.of(
                                new NanoRegistryPropertyCodec.Property(
                                        "productType",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .string(
                                                        "iPhone18,1"))),
                        1.5);
        byte[] encoded =
                NanoRegistryPropertyCodec
                        .encodePropertiesChanged(
                                message);
        NanoRegistryPropertyCodec.PropertiesChanged decoded =
                NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                encoded);
        try {
            assertArrayEquals(
                    hex(
                            "08 01 "
                                    + "12 1b "
                                    + "0a 0b "
                                    + "70 72 6f 64 75 63 74 54 79 70 65 "
                                    + "12 0c "
                                    + "0a 0a "
                                    + "69 50 68 6f 6e 65 31 38 2c 31 "
                                    + "19 00 00 00 00 00 00 f8 3f"),
                    encoded);
            assertTrue(
                    decoded.thisIsAllOfThem);
            assertEquals(
                    1.5,
                    decoded.bornOn,
                    0);
            assertEquals(
                    1,
                    decoded.properties.size());
            assertEquals(
                    "productType",
                    decoded.properties.get(
                            0).name);
            assertEquals(
                    "iPhone18,1",
                    decoded.properties.get(
                            0).value.stringValue);
        } finally {
            message.destroy();
            decoded.destroy();
            wipe(
                    encoded);
        }
    }

    @Test
    public void negativeInt32UsesTenByteProtobufVarint() {
        NanoRegistryPropertyCodec.PropertiesChanged message =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        false,
                        List.of(
                                new NanoRegistryPropertyCodec.Property(
                                        "N",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .number(
                                                        NanoRegistryPropertyCodec
                                                                .NumberValue
                                                                .ofInt32(
                                                                        -1,
                                                                        false,
                                                                        true)))),
                        null);
        byte[] encoded =
                NanoRegistryPropertyCodec.encode(
                        message);
        NanoRegistryPropertyCodec.PropertiesChanged decoded =
                NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                encoded);
        try {
            assertArrayEquals(
                    hex(
                            "08 00 "
                                    + "12 14 "
                                    + "0a 01 4e "
                                    + "12 0f "
                                    + "12 0d "
                                    + "08 ff ff ff ff ff ff ff ff ff 01 "
                                    + "38 01"),
                    encoded);
            NanoRegistryPropertyCodec.NumberValue number =
                    decoded.properties.get(
                            0).value.numberValue;
            assertEquals(
                    Integer.valueOf(
                            -1),
                    number.int32Value);
            assertFalse(
                    number.isUnsigned);
            assertFalse(
                    number.hasIsUnsigned);
            assertTrue(
                    number.isShortOrChar);
            assertTrue(
                    number.hasIsShortOrChar);
        } finally {
            message.destroy();
            decoded.destroy();
            wipe(
                    encoded);
        }
    }

    @Test
    public void allSupportedPropertyShapesRoundTrip() {
        byte[] uuid =
                sequence(
                        0x10,
                        16);
        byte[] data =
                hex(
                        "de ad be ef");
        NanoRegistryPropertyCodec.PropertyValue dictionary =
                NanoRegistryPropertyCodec.PropertyValue.dictionary(
                        List.of(
                                new NanoRegistryPropertyCodec
                                        .DictionaryEntry(
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .string(
                                                        "key"),
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .number(
                                                        NanoRegistryPropertyCodec
                                                                .NumberValue
                                                                .ofInt64(
                                                                        -1L,
                                                                        true)))));
        NanoRegistryPropertyCodec.PropertiesChanged message =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        true,
                        List.of(
                                property(
                                        "UUID",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .uuid(
                                                        uuid)),
                                property(
                                        "Data",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .data(
                                                        data)),
                                property(
                                        "Size",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .size(
                                                        410,
                                                        502)),
                                property(
                                        "Array",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .array(
                                                        List.of(
                                                                NanoRegistryPropertyCodec
                                                                        .PropertyValue
                                                                        .string(
                                                                                "x"),
                                                                NanoRegistryPropertyCodec
                                                                        .PropertyValue
                                                                        .number(
                                                                                NanoRegistryPropertyCodec
                                                                                        .NumberValue
                                                                                        .ofBoolean(
                                                                                                true))))),
                                property(
                                        "Set",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .set(
                                                        List.of(
                                                                NanoRegistryPropertyCodec
                                                                        .PropertyValue
                                                                        .number(
                                                                                NanoRegistryPropertyCodec
                                                                                        .NumberValue
                                                                                        .ofFloat(
                                                                                                2.5f))))),
                                property(
                                        "Dictionary",
                                        dictionary),
                                property(
                                        "Double",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .number(
                                                        NanoRegistryPropertyCodec
                                                                .NumberValue
                                                                .ofDouble(
                                                                        123.25)))),
                        900_000_000.5);
        byte[] encoded =
                NanoRegistryPropertyCodec.encode(
                        message);
        NanoRegistryPropertyCodec.PropertiesChanged decoded =
                NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                encoded);
        try {
            assertArrayEquals(
                    uuid,
                    decoded.properties.get(
                            0).value.uuidValue);
            assertArrayEquals(
                    data,
                    decoded.properties.get(
                            1).value.dataValue);
            assertEquals(
                    410,
                    decoded.properties.get(
                            2).value.sizeValue.width,
                    0);
            assertEquals(
                    502,
                    decoded.properties.get(
                            2).value.sizeValue.height,
                    0);
            assertEquals(
                    2,
                    decoded.properties.get(
                            3).value.arrayValues.size());
            assertTrue(
                    decoded.properties.get(
                            3).value.arrayValues.get(
                                    1).numberValue.boolValue);
            assertFalse(
                    decoded.properties.get(
                            3).value.hasIsSet);
            assertTrue(
                    decoded.properties.get(
                            4).value.hasIsSet);
            assertTrue(
                    decoded.properties.get(
                            4).value.isSet);
            NanoRegistryPropertyCodec.PropertyValue dictionaryValue =
                    decoded.properties.get(
                            5).value.arrayValues.get(
                                    0);
            assertEquals(
                    "key",
                    dictionaryValue.dictionaryKey.stringValue);
            assertEquals(
                    Long.valueOf(
                            -1L),
                    dictionaryValue.numberValue.int64Value);
            assertTrue(
                    dictionaryValue.numberValue.isUnsigned);
            assertFalse(
                    decoded.properties.get(
                            5).value.hasIsSet);
            assertFalse(
                    decoded.properties.get(
                            6).value.hasIsDate);
            assertEquals(
                    123.25,
                    decoded.properties.get(
                            6).value.numberValue.doubleValue,
                    0);

            decoded.destroy();
            assertArrayEquals(
                    new byte[16],
                    decoded.properties.get(
                            0).value.uuidValue);
            assertArrayEquals(
                    new byte[4],
                    decoded.properties.get(
                            1).value.dataValue);
        } finally {
            message.destroy();
            decoded.destroy();
            wipe(
                    uuid);
            wipe(
                    data);
            wipe(
                    encoded);
        }
    }

    @Test
    public void explicitFalseShortOrCharFlagMeansCharAndStaysOnWire() {
        NanoRegistryPropertyCodec.PropertiesChanged message =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        false,
                        List.of(
                                property(
                                        "C",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .number(
                                                        NanoRegistryPropertyCodec
                                                                .NumberValue
                                                                .ofInt32(
                                                                        65,
                                                                        false,
                                                                        false)))),
                        null);
        byte[] encoded =
                NanoRegistryPropertyCodec.encode(
                        message);
        NanoRegistryPropertyCodec.PropertiesChanged decoded =
                NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                encoded);
        try {
            assertArrayEquals(
                    hex(
                            "08 00 "
                                    + "12 0b "
                                    + "0a 01 43 "
                                    + "12 06 "
                                    + "12 04 "
                                    + "08 41 38 00"),
                    encoded);
            NanoRegistryPropertyCodec.NumberValue number =
                    decoded.properties.get(
                            0).value.numberValue;
            assertEquals(
                    Integer.valueOf(
                            65),
                    number.int32Value);
            assertFalse(
                    number.hasIsUnsigned);
            assertTrue(
                    number.hasIsShortOrChar);
            assertFalse(
                    number.isShortOrChar);
        } finally {
            message.destroy();
            decoded.destroy();
            wipe(
                    encoded);
        }
    }

    @Test
    public void propertyValueIsNotAProtobufOneof() {
        byte[] payload =
                hex(
                        "08 01 "
                                + "12 0b "
                                + "0a 01 58 "
                                + "12 06 "
                                + "0a 01 61 "
                                + "22 01 62");
        NanoRegistryPropertyCodec.PropertiesChanged decoded =
                NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                payload);
        try {
            NanoRegistryPropertyCodec.PropertyValue value =
                    decoded.properties.get(
                            0).value;
            assertEquals(
                    "a",
                    value.stringValue);
            assertArrayEquals(
                    hex(
                            "62"),
                    value.dataValue);
        } finally {
            decoded.destroy();
            wipe(
                    payload);
        }
    }

    @Test
    public void emptyRequestAndNullValueResponseHaveExactForms() {
        NanoRegistryPropertyCodec.PropertyRequest request =
                new NanoRegistryPropertyCodec.PropertyRequest();
        byte[] requestBytes =
                NanoRegistryPropertyCodec.encode(
                        request);
        NanoRegistryPropertyCodec.PropertyRequest decodedRequest =
                NanoRegistryPropertyCodec
                        .decodePropertyRequest(
                                hex(
                                        "08 01 12 01 00"));
        NanoRegistryPropertyCodec.PropertyResponse response =
                new NanoRegistryPropertyCodec.PropertyResponse(
                        List.of(
                                new NanoRegistryPropertyCodec.Property(
                                        "IMEI",
                                        null)));
        byte[] responseBytes =
                NanoRegistryPropertyCodec.encode(
                        response);
        NanoRegistryPropertyCodec.PropertyResponse decodedResponse =
                NanoRegistryPropertyCodec
                        .decodePropertyResponse(
                                responseBytes);
        try {
            assertArrayEquals(
                    new byte[0],
                    requestBytes);
            assertEquals(
                    NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST,
                    decodedRequest.protobufType());
            assertArrayEquals(
                    hex(
                            "0a 06 0a 04 49 4d 45 49"),
                    responseBytes);
            assertEquals(
                    1,
                    decodedResponse.properties.size());
            assertEquals(
                    "IMEI",
                    decodedResponse.properties.get(
                            0).name);
            assertNull(
                    decodedResponse.properties.get(
                            0).value);
        } finally {
            decodedResponse.destroy();
            wipe(
                    requestBytes);
            wipe(
                    responseBytes);
        }
    }

    @Test
    public void idsEnvelopePreservesRuntimeFieldsAndClassifiesPayload() {
        NanoRegistryPropertyCodec.PropertiesChanged change =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        true,
                        List.of(),
                        42.0);
        IdsSocketPairCodec.ProtobufMessage envelope =
                NanoRegistryPropertyCodec.envelope(
                        7,
                        0x2345,
                        IdsSocketPairCodec.FLAG_HAS_TOPIC,
                        null,
                        "00112233-4455-6677-8899-AABBCCDDEEFF",
                        NanoRegistryPropertyCodec.CLASS_C_SERVICE,
                        change,
                        null);
        byte[] frame =
                IdsSocketPairCodec.encodeProtobuf(
                        envelope);
        IdsSocketPairCodec.ProtobufMessage decodedEnvelope =
                (IdsSocketPairCodec.ProtobufMessage)
                        IdsSocketPairCodec.decode(
                                frame);
        NanoRegistryPropertyCodec.ApplicationMessage decoded =
                NanoRegistryPropertyCodec.decode(
                        decodedEnvelope);
        try {
            assertEquals(
                    7,
                    decodedEnvelope.sequence);
            assertEquals(
                    0x2345,
                    decodedEnvelope.streamId);
            assertEquals(
                    NanoRegistryPropertyCodec.CLASS_C_SERVICE,
                    decodedEnvelope.topic);
            assertEquals(
                    NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED,
                    decodedEnvelope.protobufType);
            assertFalse(
                    decodedEnvelope.response);
            assertTrue(
                    ((NanoRegistryPropertyCodec.PropertiesChanged)
                            decoded).thisIsAllOfThem);

            IdsSocketPairCodec.ProtobufMessage responseEnvelope =
                    NanoRegistryPropertyCodec.envelope(
                            8,
                            0x2345,
                            0,
                            "request-id",
                            "11112222-3333-4444-5555-666677778888",
                            null,
                            new NanoRegistryPropertyCodec
                                    .PropertyResponse(
                                    List.of()),
                            null);
            try {
                assertEquals(
                        NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST,
                        responseEnvelope.protobufType);
                assertTrue(
                        responseEnvelope.response);
                assertTrue(
                        NanoRegistryPropertyCodec.decode(
                                responseEnvelope)
                                instanceof NanoRegistryPropertyCodec
                                .PropertyResponse);
            } finally {
                responseEnvelope.destroy();
            }
        } finally {
            change.destroy();
            decoded.destroy();
            envelope.destroy();
            decodedEnvelope.destroy();
            wipe(
                    frame);
        }
    }

    @Test
    public void malformedPayloadsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                hex(
                                        "08 02")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                hex(
                                        "08 01 12 04 0a 02 c3 28")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                hex(
                                        "08 01 "
                                                + "12 16 "
                                                + "0a 01 55 "
                                                + "12 11 "
                                                + "1a 0f "
                                                + "00 01 02 03 04 "
                                                + "05 06 07 08 09 "
                                                + "0a 0b 0c 0d 0e")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                hex(
                                        "08 01 "
                                                + "12 06 "
                                                + "0a 01 58 "
                                                + "0a 01 59")));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryPropertyCodec
                        .decodePropertyResponse(
                                hex(
                                        "0a 05 0a 04 49 4d")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NanoRegistryPropertyCodec.Property(
                        "",
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> NanoRegistryPropertyCodec
                        .PropertyValue
                        .uuid(
                                new byte[15]));
    }

    @Test
    public void envelopeAcceptsType2SnapshotMarkedAsResponse() {
        IdsSocketPairCodec.ProtobufMessage asResponse =
                protobufEnvelope(
                        NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED,
                        true,
                        hex(
                                "08 01"));
        IdsSocketPairCodec.ProtobufMessage unknown =
                protobufEnvelope(
                        99,
                        false,
                        new byte[0]);
        try {
            NanoRegistryPropertyCodec.ApplicationMessage decoded =
                    NanoRegistryPropertyCodec.decode(
                            asResponse);
            try {
                assertTrue(
                        decoded instanceof NanoRegistryPropertyCodec
                                .PropertiesChanged);
                assertTrue(
                        ((NanoRegistryPropertyCodec.PropertiesChanged)
                                decoded).thisIsAllOfThem);
            } finally {
                decoded.destroy();
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NanoRegistryPropertyCodec.decode(
                            unknown));
        } finally {
            asResponse.destroy();
            unknown.destroy();
        }
    }

    private static IdsSocketPairCodec.ProtobufMessage protobufEnvelope(
            int type,
            boolean response,
            byte[] payload) {
        try {
            return new IdsSocketPairCodec.ProtobufMessage(
                    1,
                    1,
                    0,
                    null,
                    "00112233-4455-6677-8899-AABBCCDDEEFF",
                    null,
                    type,
                    response,
                    payload,
                    null);
        } finally {
            wipe(
                    payload);
        }
    }

    private static NanoRegistryPropertyCodec.Property property(
            String name,
            NanoRegistryPropertyCodec.PropertyValue value) {
        return new NanoRegistryPropertyCodec.Property(
                name,
                value);
    }

    private static byte[] sequence(
            int first,
            int length) {
        byte[] output =
                new byte[length];
        for (int index = 0; index < length; index++) {
            output[index] =
                    (byte) (first + index);
        }
        return output;
    }

    private static byte[] hex(
            String value) {
        String compact =
                value.replaceAll(
                        "\\s+",
                        "");
        byte[] output =
                new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] =
                    (byte) Integer.parseInt(
                            compact.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return output;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
