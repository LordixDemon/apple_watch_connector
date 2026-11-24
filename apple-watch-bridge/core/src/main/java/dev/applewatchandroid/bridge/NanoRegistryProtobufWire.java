package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Protobuf wire encoding/decoding, varint/reader primitives, and decode budgets for {@link NanoRegistryPropertyCodec}. */
final class NanoRegistryProtobufWire {
    private NanoRegistryProtobufWire() {}

    static byte[] encodePropertiesChanged(
            NanoRegistryPropertyCodec.PropertiesChanged changed) {
        if (changed == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry property change is absent");
        }
        changed.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeBoolField(
                output,
                1,
                changed.thisIsAllOfThem);
        for (NanoRegistryPropertyCodec.Property property : changed.properties) {
            writeMessageField(
                    output,
                    2,
                    encodeProperty(
                            property));
        }
        if (changed.bornOn != null) {
            writeDoubleField(
                    output,
                    3,
                    changed.bornOn);
        }
        return requireEncodedSize(
                output.toByteArray());
    }

    static NanoRegistryPropertyCodec.PropertiesChanged decodePropertiesChanged(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        boolean full = false;
        boolean sawFull = false;
        Double bornOn = null;
        List<NanoRegistryPropertyCodec.Property> properties =
                new ArrayList<>();
        ParseBudget budget =
                new ParseBudget();
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            int field =
                    fieldNumber(
                            tag);
            switch (field) {
                case 1 -> {
                    rejectDuplicate(
                            sawFull,
                            "thisIsAllOfThem");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    full =
                            reader.readBool();
                    sawFull = true;
                }
                case 2 -> {
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    requireCollectionRoom(
                            properties.size(),
                            NanoRegistryPropertyCodec.MAX_PROPERTIES,
                            "NanoRegistry properties");
                    properties.add(
                            decodeProperty(
                                    reader.readSubReader(),
                                    budget,
                                    0));
                }
                case 3 -> {
                    rejectDuplicate(
                            bornOn != null,
                            "bornOn");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_FIXED64);
                    bornOn =
                            reader.readDouble();
                }
                default -> reader.skipField(
                        tag);
            }
        }
        return new NanoRegistryPropertyCodec.PropertiesChanged(
                full,
                properties,
                bornOn);
    }

    static byte[] encodePropertyRequest() {
        return new byte[0];
    }

    static NanoRegistryPropertyCodec.PropertyRequest decodePropertyRequest(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        while (reader.hasRemaining()) {
            reader.skipField(
                    reader.readTag());
        }
        return new NanoRegistryPropertyCodec.PropertyRequest();
    }

    static byte[] encodePropertyResponse(
            NanoRegistryPropertyCodec.PropertyResponse response) {
        if (response == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry property response is absent");
        }
        response.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        for (NanoRegistryPropertyCodec.Property property : response.properties) {
            writeMessageField(
                    output,
                    1,
                    encodeProperty(
                            property));
        }
        return requireEncodedSize(
                output.toByteArray());
    }

    static NanoRegistryPropertyCodec.PropertyResponse decodePropertyResponse(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        List<NanoRegistryPropertyCodec.Property> properties =
                new ArrayList<>();
        ParseBudget budget =
                new ParseBudget();
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            if (fieldNumber(
                    tag) == 1) {
                requireWire(
                        tag,
                        NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                requireCollectionRoom(
                        properties.size(),
                        NanoRegistryPropertyCodec.MAX_PROPERTIES,
                        "NanoRegistry properties");
                properties.add(
                        decodeProperty(
                                reader.readSubReader(),
                                budget,
                                0));
            } else {
                reader.skipField(
                        tag);
            }
        }
        return new NanoRegistryPropertyCodec.PropertyResponse(
                properties);
    }

    static byte[] encodeProperty(
            NanoRegistryPropertyCodec.Property property) {
        property.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeStringField(
                output,
                1,
                property.name,
                NanoRegistryPropertyCodec.MAX_PROPERTY_NAME_LENGTH,
                "NanoRegistry property name");
        if (property.value != null) {
            writeMessageField(
                    output,
                    2,
                    encodePropertyValue(
                            property.value));
        }
        return output.toByteArray();
    }

    static NanoRegistryPropertyCodec.Property decodeProperty(
            Reader reader,
            ParseBudget budget,
            int depth) {
        budget.addProperty();
        String name = null;
        NanoRegistryPropertyCodec.PropertyValue value = null;
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            int field =
                    fieldNumber(
                            tag);
            switch (field) {
                case 1 -> {
                    rejectDuplicate(
                            name != null,
                            "property name");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    name =
                            reader.readUtf8(
                                    NanoRegistryPropertyCodec.MAX_PROPERTY_NAME_LENGTH,
                                    "NanoRegistry property name");
                }
                case 2 -> {
                    rejectDuplicate(
                            value != null,
                            "property value");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    value =
                            decodePropertyValue(
                                    reader.readSubReader(),
                                    budget,
                                    depth + 1);
                }
                default -> reader.skipField(
                        tag);
            }
        }
        return new NanoRegistryPropertyCodec.Property(
                name,
                value);
    }

    static byte[] encodePropertyValue(
            NanoRegistryPropertyCodec.PropertyValue value) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        if (value.stringValue != null) {
            writeStringField(
                    output,
                    1,
                    value.stringValue,
                    NanoRegistryPropertyCodec.MAX_STRING_LENGTH,
                    "NanoRegistry string value");
        }
        if (value.numberValue != null) {
            writeMessageField(
                    output,
                    2,
                    encodeNumber(
                            value.numberValue));
        }
        if (value.uuidValue != null) {
            writeBytesField(
                    output,
                    3,
                    value.uuidValue);
        }
        if (value.dataValue != null) {
            writeBytesField(
                    output,
                    4,
                    value.dataValue);
        }
        if (value.sizeValue != null) {
            writeMessageField(
                    output,
                    5,
                    encodeSize(
                            value.sizeValue));
        }
        if (value.dictionaryKey != null) {
            writeMessageField(
                    output,
                    6,
                    encodePropertyValue(
                            value.dictionaryKey));
        }
        if (value.arrayValues != null) {
            for (NanoRegistryPropertyCodec.PropertyValue element : value.arrayValues) {
                writeMessageField(
                        output,
                        7,
                        encodePropertyValue(
                                element));
            }
        }
        if (value.hasIsSet) {
            writeBoolField(
                    output,
                    8,
                    value.isSet);
        }
        if (value.hasIsSecurePropertyValue) {
            writeBoolField(
                    output,
                    9,
                    value.isSecurePropertyValue);
        }
        if (value.hasIsDate) {
            writeBoolField(
                    output,
                    10,
                    value.isDate);
        }
        if (value.hasIsError) {
            writeBoolField(
                    output,
                    11,
                    value.isError);
        }
        if (value.hasIsMiniUuidSet) {
            writeBoolField(
                    output,
                    12,
                    value.isMiniUuidSet);
        }
        return output.toByteArray();
    }

    static NanoRegistryPropertyCodec.PropertyValue decodePropertyValue(
            Reader reader,
            ParseBudget budget,
            int depth) {
        budget.addValue(
                depth);
        String stringValue = null;
        NanoRegistryPropertyCodec.NumberValue numberValue = null;
        byte[] uuidValue = null;
        byte[] dataValue = null;
        NanoRegistryPropertyCodec.SizeValue sizeValue = null;
        NanoRegistryPropertyCodec.PropertyValue dictionaryKey = null;
        List<NanoRegistryPropertyCodec.PropertyValue> arrayValues = null;
        boolean isSet = false;
        boolean isSecure = false;
        boolean isDate = false;
        boolean isError = false;
        boolean isMiniUuidSet = false;
        boolean sawIsSet = false;
        boolean sawIsSecure = false;
        boolean sawIsDate = false;
        boolean sawIsError = false;
        boolean sawIsMiniUuidSet = false;

        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            int field =
                    fieldNumber(
                            tag);
            switch (field) {
                case 1 -> {
                    rejectDuplicate(
                            stringValue != null,
                            "stringValue");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    stringValue =
                            reader.readUtf8(
                                    NanoRegistryPropertyCodec.MAX_STRING_LENGTH,
                                    "NanoRegistry string value");
                }
                case 2 -> {
                    rejectDuplicate(
                            numberValue != null,
                            "numberValue");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    numberValue =
                            decodeNumber(
                                    reader.readSubReader());
                }
                case 3 -> {
                    rejectDuplicate(
                            uuidValue != null,
                            "uUIDValue");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    uuidValue =
                            reader.readBytes(
                                    NanoRegistryPropertyCodec.UUID_LENGTH,
                                    "NanoRegistry UUID");
                    if (uuidValue.length != NanoRegistryPropertyCodec.UUID_LENGTH) {
                        throw new IllegalArgumentException(
                                "NanoRegistry UUID must contain 16 bytes");
                    }
                }
                case 4 -> {
                    rejectDuplicate(
                            dataValue != null,
                            "dataValue");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    dataValue =
                            reader.readBytes(
                                    NanoRegistryPropertyCodec.MAX_PAYLOAD_LENGTH,
                                    "NanoRegistry data value");
                }
                case 5 -> {
                    rejectDuplicate(
                            sizeValue != null,
                            "sizeValue");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    sizeValue =
                            decodeSize(
                                    reader.readSubReader());
                }
                case 6 -> {
                    rejectDuplicate(
                            dictionaryKey != null,
                            "dictionaryKey");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    dictionaryKey =
                            decodePropertyValue(
                                    reader.readSubReader(),
                                    budget,
                                    depth + 1);
                }
                case 7 -> {
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
                    if (arrayValues == null) {
                        arrayValues =
                                new ArrayList<>();
                    }
                    requireCollectionRoom(
                            arrayValues.size(),
                            NanoRegistryPropertyCodec.MAX_VALUE_NODES,
                            "NanoRegistry array");
                    arrayValues.add(
                            decodePropertyValue(
                                    reader.readSubReader(),
                                    budget,
                                    depth + 1));
                }
                case 8 -> {
                    rejectDuplicate(
                            sawIsSet,
                            "isSet");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    isSet =
                            reader.readBool();
                    sawIsSet = true;
                }
                case 9 -> {
                    rejectDuplicate(
                            sawIsSecure,
                            "isSecurePropertyValue");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    isSecure =
                            reader.readBool();
                    sawIsSecure = true;
                }
                case 10 -> {
                    rejectDuplicate(
                            sawIsDate,
                            "isDate");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    isDate =
                            reader.readBool();
                    sawIsDate = true;
                }
                case 11 -> {
                    rejectDuplicate(
                            sawIsError,
                            "isError");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    isError =
                            reader.readBool();
                    sawIsError = true;
                }
                case 12 -> {
                    rejectDuplicate(
                            sawIsMiniUuidSet,
                            "isMiniUUIDSet");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    isMiniUuidSet =
                            reader.readBool();
                    sawIsMiniUuidSet = true;
                }
                default -> reader.skipField(
                        tag);
            }
        }
        return new NanoRegistryPropertyCodec.PropertyValue(
                stringValue,
                numberValue,
                uuidValue,
                dataValue,
                sizeValue,
                dictionaryKey,
                arrayValues,
                sawIsSet
                        ? isSet
                        : null,
                sawIsSecure
                        ? isSecure
                        : null,
                sawIsDate
                        ? isDate
                        : null,
                sawIsError
                        ? isError
                        : null,
                sawIsMiniUuidSet
                        ? isMiniUuidSet
                        : null);
    }

    static byte[] encodeNumber(
            NanoRegistryPropertyCodec.NumberValue number) {
        number.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        if (number.int32Value != null) {
            writeTag(
                    output,
                    1,
                    NanoRegistryPropertyCodec.WIRE_VARINT);
            writeVarint(
                    output,
                    number.int32Value.longValue());
        }
        if (number.floatValue != null) {
            writeFloatField(
                    output,
                    2,
                    number.floatValue);
        }
        if (number.doubleValue != null) {
            writeDoubleField(
                    output,
                    3,
                    number.doubleValue);
        }
        if (number.boolValue != null) {
            writeBoolField(
                    output,
                    4,
                    number.boolValue);
        }
        if (number.int64Value != null) {
            writeTag(
                    output,
                    5,
                    NanoRegistryPropertyCodec.WIRE_VARINT);
            writeVarint(
                    output,
                    number.int64Value);
        }
        if (number.hasIsUnsigned) {
            writeBoolField(
                    output,
                    6,
                    number.isUnsigned);
        }
        if (number.hasIsShortOrChar) {
            writeBoolField(
                    output,
                    7,
                    number.isShortOrChar);
        }
        return output.toByteArray();
    }

    static NanoRegistryPropertyCodec.NumberValue decodeNumber(
            Reader reader) {
        Integer int32Value = null;
        Float floatValue = null;
        Double doubleValue = null;
        Boolean boolValue = null;
        Long int64Value = null;
        boolean isUnsigned = false;
        boolean isShortOrChar = false;
        boolean sawUnsigned = false;
        boolean sawShortOrChar = false;

        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            int field =
                    fieldNumber(
                            tag);
            switch (field) {
                case 1 -> {
                    rejectDuplicate(
                            int32Value != null,
                            "int32Value");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    int32Value =
                            (int) reader.readVarint();
                }
                case 2 -> {
                    rejectDuplicate(
                            floatValue != null,
                            "floatValue");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_FIXED32);
                    floatValue =
                            reader.readFloat();
                }
                case 3 -> {
                    rejectDuplicate(
                            doubleValue != null,
                            "doubleValue");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_FIXED64);
                    doubleValue =
                            reader.readDouble();
                }
                case 4 -> {
                    rejectDuplicate(
                            boolValue != null,
                            "boolValue");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    boolValue =
                            reader.readBool();
                }
                case 5 -> {
                    rejectDuplicate(
                            int64Value != null,
                            "int64Value");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    int64Value =
                            reader.readVarint();
                }
                case 6 -> {
                    rejectDuplicate(
                            sawUnsigned,
                            "isUnsigned");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    isUnsigned =
                            reader.readBool();
                    sawUnsigned = true;
                }
                case 7 -> {
                    rejectDuplicate(
                            sawShortOrChar,
                            "isShortOrChar");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_VARINT);
                    isShortOrChar =
                            reader.readBool();
                    sawShortOrChar = true;
                }
                default -> reader.skipField(
                        tag);
            }
        }
        return new NanoRegistryPropertyCodec.NumberValue(
                int32Value,
                floatValue,
                doubleValue,
                boolValue,
                int64Value,
                sawUnsigned
                        ? isUnsigned
                        : null,
                sawShortOrChar
                        ? isShortOrChar
                        : null);
    }

    static byte[] encodeSize(
            NanoRegistryPropertyCodec.SizeValue size) {
        size.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeFloatField(
                output,
                1,
                size.width);
        writeFloatField(
                output,
                2,
                size.height);
        return output.toByteArray();
    }

    static NanoRegistryPropertyCodec.SizeValue decodeSize(
            Reader reader) {
        Float width = null;
        Float height = null;
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            int field =
                    fieldNumber(
                            tag);
            switch (field) {
                case 1 -> {
                    rejectDuplicate(
                            width != null,
                            "size width");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_FIXED32);
                    width =
                            reader.readFloat();
                }
                case 2 -> {
                    rejectDuplicate(
                            height != null,
                            "size height");
                    requireWire(
                            tag,
                            NanoRegistryPropertyCodec.WIRE_FIXED32);
                    height =
                            reader.readFloat();
                }
                default -> reader.skipField(
                        tag);
            }
        }
        if (width == null
                || height == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry size is incomplete");
        }
        return new NanoRegistryPropertyCodec.SizeValue(
                width,
                height);
    }

    static Reader rootReader(
            byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry protobuf payload is absent");
        }
        if (payload.length > NanoRegistryPropertyCodec.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "NanoRegistry protobuf payload is too large");
        }
        return new Reader(
                payload,
                0,
                payload.length);
    }

    static void writeMessageField(
            ByteArrayOutputStream output,
            int field,
            byte[] message) {
        writeBytesField(
                output,
                field,
                message);
        wipe(
                message);
    }

    static void writeStringField(
            ByteArrayOutputStream output,
            int field,
            String value,
            int maximumLength,
            String label) {
        byte[] bytes =
                encodeUtf8(
                        value,
                        maximumLength,
                        label);
        try {
            writeBytesField(
                    output,
                    field,
                    bytes);
        } finally {
            wipe(
                    bytes);
        }
    }

    static void writeBytesField(
            ByteArrayOutputStream output,
            int field,
            byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry bytes field is absent");
        }
        writeTag(
                output,
                field,
                NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED);
        writeVarint(
                output,
                bytes.length);
        output.writeBytes(
                bytes);
    }

    static void writeBoolField(
            ByteArrayOutputStream output,
            int field,
            boolean value) {
        writeTag(
                output,
                field,
                NanoRegistryPropertyCodec.WIRE_VARINT);
        writeVarint(
                output,
                value
                        ? 1
                        : 0);
    }

    static void writeFloatField(
            ByteArrayOutputStream output,
            int field,
            float value) {
        writeTag(
                output,
                field,
                NanoRegistryPropertyCodec.WIRE_FIXED32);
        writeFixed32(
                output,
                Float.floatToRawIntBits(
                        value));
    }

    static void writeDoubleField(
            ByteArrayOutputStream output,
            int field,
            double value) {
        writeTag(
                output,
                field,
                NanoRegistryPropertyCodec.WIRE_FIXED64);
        writeFixed64(
                output,
                Double.doubleToRawLongBits(
                        value));
    }

    static void writeTag(
            ByteArrayOutputStream output,
            int field,
            int wire) {
        if (field <= 0
                || field > 0x1fff_ffff) {
            throw new IllegalArgumentException(
                    "Invalid protobuf field number");
        }
        writeVarint(
                output,
                ((long) field << 3)
                        | wire);
    }

    static void writeVarint(
            ByteArrayOutputStream output,
            long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write(
                    ((int) remaining & 0x7f)
                            | 0x80);
            remaining >>>= 7;
        }
        output.write(
                (int) remaining);
    }

    static void writeFixed32(
            ByteArrayOutputStream output,
            int value) {
        output.write(
                value & 0xff);
        output.write(
                value >>> 8
                        & 0xff);
        output.write(
                value >>> 16
                        & 0xff);
        output.write(
                value >>> 24
                        & 0xff);
    }

    static void writeFixed64(
            ByteArrayOutputStream output,
            long value) {
        for (int shift = 0; shift < 64; shift += 8) {
            output.write(
                    (int) (value >>> shift)
                            & 0xff);
        }
    }

    static byte[] encodeUtf8(
            String value,
            int maximumLength,
            String label) {
        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is absent");
        }
        try {
            ByteBuffer encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(
                                    CodingErrorAction.REPORT)
                            .onUnmappableCharacter(
                                    CodingErrorAction.REPORT)
                            .encode(
                                    CharBuffer.wrap(
                                            value));
            if (encoded.remaining() > maximumLength) {
                throw new IllegalArgumentException(
                        label + " is too long");
            }
            byte[] bytes =
                    new byte[encoded.remaining()];
            encoded.get(
                    bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                    label + " is not valid UTF-8",
                    failure);
        }
    }

    static byte[] requireEncodedSize(
            byte[] payload) {
        if (payload.length > NanoRegistryPropertyCodec.MAX_PAYLOAD_LENGTH) {
            wipe(
                    payload);
            throw new IllegalArgumentException(
                    "NanoRegistry protobuf payload is too large");
        }
        return payload;
    }

    static int fieldNumber(
            int tag) {
        return tag >>> 3;
    }

    static void requireWire(
            int tag,
            int expected) {
        if ((tag & 7) != expected) {
            throw new IllegalArgumentException(
                    "NanoRegistry protobuf wire type mismatch");
        }
    }

    static void rejectDuplicate(
            boolean duplicate,
            String label) {
        if (duplicate) {
            throw new IllegalArgumentException(
                    "Duplicate NanoRegistry " + label);
        }
    }

    static void requireCollectionRoom(
            int currentSize,
            int maximum,
            String label) {
        if (currentSize >= maximum) {
            throw new IllegalArgumentException(
                    label + " has too many entries");
        }
    }

    static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static final class ValidationBudget {
        private int valueNodes;

        void addValue(
                int depth) {
            if (depth > NanoRegistryPropertyCodec.MAX_RECURSION_DEPTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry property value nesting is too deep");
            }
            valueNodes++;
            if (valueNodes > NanoRegistryPropertyCodec.MAX_VALUE_NODES) {
                throw new IllegalArgumentException(
                        "NanoRegistry property graph is too large");
            }
        }
    }

    static final class ParseBudget {
        private int properties;
        private int valueNodes;

        void addProperty() {
            properties++;
            if (properties > NanoRegistryPropertyCodec.MAX_PROPERTIES) {
                throw new IllegalArgumentException(
                        "NanoRegistry has too many properties");
            }
        }

        void addValue(
                int depth) {
            if (depth > NanoRegistryPropertyCodec.MAX_RECURSION_DEPTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry property value nesting is too deep");
            }
            valueNodes++;
            if (valueNodes > NanoRegistryPropertyCodec.MAX_VALUE_NODES) {
                throw new IllegalArgumentException(
                        "NanoRegistry property graph is too large");
            }
        }
    }

    static final class Reader {
        private final byte[] data;
        private final int limit;
        private int offset;

        Reader(
                byte[] data,
                int offset,
                int limit) {
            this.data = data;
            this.offset = offset;
            this.limit = limit;
        }

        boolean hasRemaining() {
            return offset < limit;
        }

        int readTag() {
            long raw =
                    readVarint();
            if (raw <= 0
                    || raw > 0xffff_ffffL) {
                throw new IllegalArgumentException(
                        "Invalid NanoRegistry protobuf tag");
            }
            int tag =
                    (int) raw;
            int field =
                    fieldNumber(
                            tag);
            int wire =
                    tag & 7;
            if (field <= 0
                    || wire == 3
                    || wire == 4
                    || wire > NanoRegistryPropertyCodec.WIRE_FIXED32) {
                throw new IllegalArgumentException(
                        "Unsupported NanoRegistry protobuf tag");
            }
            return tag;
        }

        long readVarint() {
            long result = 0;
            for (int index = 0; index < 10; index++) {
                requireRemaining(
                        1);
                int next =
                        data[offset++] & 0xff;
                if (index == 9
                        && (next & 0xfe) != 0) {
                    throw new IllegalArgumentException(
                            "NanoRegistry protobuf varint overflow");
                }
                result |=
                        (long) (next & 0x7f)
                                << index * 7;
                if ((next & 0x80) == 0) {
                    return result;
                }
            }
            throw new IllegalArgumentException(
                    "NanoRegistry protobuf varint is unterminated");
        }

        boolean readBool() {
            long value =
                    readVarint();
            if (value != 0
                    && value != 1) {
                throw new IllegalArgumentException(
                        "NanoRegistry protobuf boolean is not canonical");
            }
            return value == 1;
        }

        float readFloat() {
            return Float.intBitsToFloat(
                    readFixed32());
        }

        double readDouble() {
            return Double.longBitsToDouble(
                    readFixed64());
        }

        int readFixed32() {
            requireRemaining(
                    4);
            int value =
                    data[offset] & 0xff
                            | (data[offset + 1] & 0xff) << 8
                            | (data[offset + 2] & 0xff) << 16
                            | (data[offset + 3] & 0xff) << 24;
            offset += 4;
            return value;
        }

        long readFixed64() {
            requireRemaining(
                    8);
            long value = 0;
            for (int index = 0; index < 8; index++) {
                value |=
                        (long) (data[offset + index] & 0xff)
                                << index * 8;
            }
            offset += 8;
            return value;
        }

        Reader readSubReader() {
            int length =
                    readLength(
                            NanoRegistryPropertyCodec.MAX_PAYLOAD_LENGTH,
                            "NanoRegistry nested message");
            int start = offset;
            offset += length;
            return new Reader(
                    data,
                    start,
                    start + length);
        }

        byte[] readBytes(
                int maximumLength,
                String label) {
            int length =
                    readLength(
                            maximumLength,
                            label);
            byte[] value =
                    Arrays.copyOfRange(
                            data,
                            offset,
                            offset + length);
            offset += length;
            return value;
        }

        String readUtf8(
                int maximumLength,
                String label) {
            int length =
                    readLength(
                            maximumLength,
                            label);
            try {
                String value =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(
                                        CodingErrorAction.REPORT)
                                .onUnmappableCharacter(
                                        CodingErrorAction.REPORT)
                                .decode(
                                        ByteBuffer.wrap(
                                                data,
                                                offset,
                                                length))
                                .toString();
                offset += length;
                return value;
            } catch (CharacterCodingException failure) {
                throw new IllegalArgumentException(
                        label + " is not valid UTF-8",
                        failure);
            }
        }

        int readLength(
                int maximumLength,
                String label) {
            long raw =
                    readVarint();
            if (raw > maximumLength
                    || raw > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        label + " is too large");
            }
            int length =
                    (int) raw;
            requireRemaining(
                    length);
            return length;
        }

        void skipField(
                int tag) {
            switch (tag & 7) {
                case NanoRegistryPropertyCodec.WIRE_VARINT -> readVarint();
                case NanoRegistryPropertyCodec.WIRE_FIXED64 -> requireAndAdvance(
                        8);
                case NanoRegistryPropertyCodec.WIRE_LENGTH_DELIMITED -> {
                    int length =
                            readLength(
                                    NanoRegistryPropertyCodec.MAX_PAYLOAD_LENGTH,
                                    "NanoRegistry unknown field");
                    offset += length;
                }
                case NanoRegistryPropertyCodec.WIRE_FIXED32 -> requireAndAdvance(
                        4);
                default -> throw new IllegalArgumentException(
                        "Unsupported NanoRegistry protobuf wire type");
            }
        }

        private void requireAndAdvance(
                int length) {
            requireRemaining(
                    length);
            offset += length;
        }

        private void requireRemaining(
                int length) {
            if (length < 0
                    || offset > limit - length) {
                throw new IllegalArgumentException(
                        "NanoRegistry protobuf payload is truncated");
            }
        }
    }
}
