package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NanoRegistry Class C property protobufs used by iOS/watchOS 26.6.
 *
 * <p>Both peers publish a type-2 full snapshot after Class C and Class D
 * become connected to the same IDS Bluetooth UUID. The modern
 * Network.framework path does not prepend an IDS socket-pair Handshake.</p>
 */
final class NanoRegistryPropertyCodec {
    static final String CLASS_D_SERVICE =
            "com.apple.private.alloy.bluetoothregistry";
    static final String CLASS_C_SERVICE =
            "com.apple.private.alloy.bluetoothregistryclassc";

    static final int TYPE_PROPERTIES_CHANGED = 2;
    static final int TYPE_PROPERTY_REQUEST = 4;
    static final int MESSAGE_PRIORITY = 300;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED32 = 5;

    private static final int UUID_LENGTH = 16;
    private static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;
    private static final int MAX_STRING_LENGTH = 64 * 1024;
    private static final int MAX_PROPERTY_NAME_LENGTH = 1024;
    private static final int MAX_PROPERTIES = 512;
    private static final int MAX_VALUE_NODES = 4096;
    private static final int MAX_RECURSION_DEPTH = 32;

    private NanoRegistryPropertyCodec() {
    }

    static byte[] encode(
            ApplicationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry message is absent");
        }
        if (message instanceof PropertiesChanged changed) {
            return encodePropertiesChanged(
                    changed);
        }
        if (message instanceof PropertyRequest) {
            return encodePropertyRequest();
        }
        if (message instanceof PropertyResponse response) {
            return encodePropertyResponse(
                    response);
        }
        throw new IllegalArgumentException(
                "Unknown NanoRegistry message");
    }

    static ApplicationMessage decode(
            IdsSocketPairCodec.ProtobufMessage envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException(
                    "IDS protobuf envelope is absent");
        }
        if (envelope.protobufType
                == TYPE_PROPERTIES_CHANGED) {
            // Live Watch may answer a type-4 PropertyRequest with a type-2
            // snapshot that still has the IDS response bit set. Treating
            // that as illegal poisoned the IDS session before the adapter
            // ever saw PROTOBUF_RECEIVED.
            return decodePropertiesChanged(
                    envelope.payload);
        }
        if (envelope.protobufType
                == TYPE_PROPERTY_REQUEST) {
            if (envelope.response) {
                return decodePropertyResponse(
                        envelope.payload);
            }
            return decodePropertyRequest(
                    envelope.payload);
        }
        throw new IllegalArgumentException(
                "Unsupported NanoRegistry protobuf type");
    }

    static IdsSocketPairCodec.ProtobufMessage envelope(
            long sequence,
            int streamId,
            int flags,
            String peerResponseIdentifier,
            String messageUuid,
            String topic,
            ApplicationMessage message,
            Long expirySeconds) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry message is absent");
        }
        byte[] payload =
                encode(
                        message);
        try {
            return new IdsSocketPairCodec.ProtobufMessage(
                    sequence,
                    streamId,
                    flags,
                    peerResponseIdentifier,
                    messageUuid,
                    topic,
                    message.protobufType(),
                    message.response(),
                    payload,
                    expirySeconds);
        } finally {
            wipe(
                    payload);
        }
    }

    static byte[] encodePropertiesChanged(
            PropertiesChanged changed) {
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
        for (Property property : changed.properties) {
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

    static PropertiesChanged decodePropertiesChanged(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        boolean full = false;
        boolean sawFull = false;
        Double bornOn = null;
        List<Property> properties =
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
                            WIRE_VARINT);
                    full =
                            reader.readBool();
                    sawFull = true;
                }
                case 2 -> {
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    requireCollectionRoom(
                            properties.size(),
                            MAX_PROPERTIES,
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
                            WIRE_FIXED64);
                    bornOn =
                            reader.readDouble();
                }
                default -> reader.skipField(
                        tag);
            }
        }
        return new PropertiesChanged(
                full,
                properties,
                bornOn);
    }

    static byte[] encodePropertyRequest() {
        return new byte[0];
    }

    static PropertyRequest decodePropertyRequest(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        while (reader.hasRemaining()) {
            reader.skipField(
                    reader.readTag());
        }
        return new PropertyRequest();
    }

    static byte[] encodePropertyResponse(
            PropertyResponse response) {
        if (response == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry property response is absent");
        }
        response.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        for (Property property : response.properties) {
            writeMessageField(
                    output,
                    1,
                    encodeProperty(
                            property));
        }
        return requireEncodedSize(
                output.toByteArray());
    }

    static PropertyResponse decodePropertyResponse(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        List<Property> properties =
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
                        WIRE_LENGTH_DELIMITED);
                requireCollectionRoom(
                        properties.size(),
                        MAX_PROPERTIES,
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
        return new PropertyResponse(
                properties);
    }

    private static byte[] encodeProperty(
            Property property) {
        property.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeStringField(
                output,
                1,
                property.name,
                MAX_PROPERTY_NAME_LENGTH,
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

    private static Property decodeProperty(
            Reader reader,
            ParseBudget budget,
            int depth) {
        budget.addProperty();
        String name = null;
        PropertyValue value = null;
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
                            WIRE_LENGTH_DELIMITED);
                    name =
                            reader.readUtf8(
                                    MAX_PROPERTY_NAME_LENGTH,
                                    "NanoRegistry property name");
                }
                case 2 -> {
                    rejectDuplicate(
                            value != null,
                            "property value");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
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
        return new Property(
                name,
                value);
    }

    private static byte[] encodePropertyValue(
            PropertyValue value) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        if (value.stringValue != null) {
            writeStringField(
                    output,
                    1,
                    value.stringValue,
                    MAX_STRING_LENGTH,
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
            for (PropertyValue element : value.arrayValues) {
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

    private static PropertyValue decodePropertyValue(
            Reader reader,
            ParseBudget budget,
            int depth) {
        budget.addValue(
                depth);
        String stringValue = null;
        NumberValue numberValue = null;
        byte[] uuidValue = null;
        byte[] dataValue = null;
        SizeValue sizeValue = null;
        PropertyValue dictionaryKey = null;
        List<PropertyValue> arrayValues = null;
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
                            WIRE_LENGTH_DELIMITED);
                    stringValue =
                            reader.readUtf8(
                                    MAX_STRING_LENGTH,
                                    "NanoRegistry string value");
                }
                case 2 -> {
                    rejectDuplicate(
                            numberValue != null,
                            "numberValue");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
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
                            WIRE_LENGTH_DELIMITED);
                    uuidValue =
                            reader.readBytes(
                                    UUID_LENGTH,
                                    "NanoRegistry UUID");
                    if (uuidValue.length != UUID_LENGTH) {
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
                            WIRE_LENGTH_DELIMITED);
                    dataValue =
                            reader.readBytes(
                                    MAX_PAYLOAD_LENGTH,
                                    "NanoRegistry data value");
                }
                case 5 -> {
                    rejectDuplicate(
                            sizeValue != null,
                            "sizeValue");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
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
                            WIRE_LENGTH_DELIMITED);
                    dictionaryKey =
                            decodePropertyValue(
                                    reader.readSubReader(),
                                    budget,
                                    depth + 1);
                }
                case 7 -> {
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    if (arrayValues == null) {
                        arrayValues =
                                new ArrayList<>();
                    }
                    requireCollectionRoom(
                            arrayValues.size(),
                            MAX_VALUE_NODES,
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
                            WIRE_VARINT);
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
                            WIRE_VARINT);
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
                            WIRE_VARINT);
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
                            WIRE_VARINT);
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
                            WIRE_VARINT);
                    isMiniUuidSet =
                            reader.readBool();
                    sawIsMiniUuidSet = true;
                }
                default -> reader.skipField(
                        tag);
            }
        }
        return new PropertyValue(
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

    private static byte[] encodeNumber(
            NumberValue number) {
        number.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        if (number.int32Value != null) {
            writeTag(
                    output,
                    1,
                    WIRE_VARINT);
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
                    WIRE_VARINT);
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

    private static NumberValue decodeNumber(
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
                            WIRE_VARINT);
                    int32Value =
                            (int) reader.readVarint();
                }
                case 2 -> {
                    rejectDuplicate(
                            floatValue != null,
                            "floatValue");
                    requireWire(
                            tag,
                            WIRE_FIXED32);
                    floatValue =
                            reader.readFloat();
                }
                case 3 -> {
                    rejectDuplicate(
                            doubleValue != null,
                            "doubleValue");
                    requireWire(
                            tag,
                            WIRE_FIXED64);
                    doubleValue =
                            reader.readDouble();
                }
                case 4 -> {
                    rejectDuplicate(
                            boolValue != null,
                            "boolValue");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    boolValue =
                            reader.readBool();
                }
                case 5 -> {
                    rejectDuplicate(
                            int64Value != null,
                            "int64Value");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    int64Value =
                            reader.readVarint();
                }
                case 6 -> {
                    rejectDuplicate(
                            sawUnsigned,
                            "isUnsigned");
                    requireWire(
                            tag,
                            WIRE_VARINT);
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
                            WIRE_VARINT);
                    isShortOrChar =
                            reader.readBool();
                    sawShortOrChar = true;
                }
                default -> reader.skipField(
                        tag);
            }
        }
        return new NumberValue(
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

    private static byte[] encodeSize(
            SizeValue size) {
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

    private static SizeValue decodeSize(
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
                            WIRE_FIXED32);
                    width =
                            reader.readFloat();
                }
                case 2 -> {
                    rejectDuplicate(
                            height != null,
                            "size height");
                    requireWire(
                            tag,
                            WIRE_FIXED32);
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
        return new SizeValue(
                width,
                height);
    }

    private static Reader rootReader(
            byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry protobuf payload is absent");
        }
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "NanoRegistry protobuf payload is too large");
        }
        return new Reader(
                payload,
                0,
                payload.length);
    }

    private static void writeMessageField(
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

    private static void writeStringField(
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

    private static void writeBytesField(
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
                WIRE_LENGTH_DELIMITED);
        writeVarint(
                output,
                bytes.length);
        output.writeBytes(
                bytes);
    }

    private static void writeBoolField(
            ByteArrayOutputStream output,
            int field,
            boolean value) {
        writeTag(
                output,
                field,
                WIRE_VARINT);
        writeVarint(
                output,
                value
                        ? 1
                        : 0);
    }

    private static void writeFloatField(
            ByteArrayOutputStream output,
            int field,
            float value) {
        writeTag(
                output,
                field,
                WIRE_FIXED32);
        writeFixed32(
                output,
                Float.floatToRawIntBits(
                        value));
    }

    private static void writeDoubleField(
            ByteArrayOutputStream output,
            int field,
            double value) {
        writeTag(
                output,
                field,
                WIRE_FIXED64);
        writeFixed64(
                output,
                Double.doubleToRawLongBits(
                        value));
    }

    private static void writeTag(
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

    private static void writeVarint(
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

    private static void writeFixed32(
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

    private static void writeFixed64(
            ByteArrayOutputStream output,
            long value) {
        for (int shift = 0; shift < 64; shift += 8) {
            output.write(
                    (int) (value >>> shift)
                            & 0xff);
        }
    }

    private static byte[] encodeUtf8(
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

    private static byte[] requireEncodedSize(
            byte[] payload) {
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            wipe(
                    payload);
            throw new IllegalArgumentException(
                    "NanoRegistry protobuf payload is too large");
        }
        return payload;
    }

    private static int fieldNumber(
            int tag) {
        return tag >>> 3;
    }

    private static void requireWire(
            int tag,
            int expected) {
        if ((tag & 7) != expected) {
            throw new IllegalArgumentException(
                    "NanoRegistry protobuf wire type mismatch");
        }
    }

    private static void rejectDuplicate(
            boolean duplicate,
            String label) {
        if (duplicate) {
            throw new IllegalArgumentException(
                    "Duplicate NanoRegistry " + label);
        }
    }

    private static void requireCollectionRoom(
            int currentSize,
            int maximum,
            String label) {
        if (currentSize >= maximum) {
            throw new IllegalArgumentException(
                    label + " has too many entries");
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    abstract static class ApplicationMessage {
        abstract int protobufType();

        abstract boolean response();

        void destroy() {
        }
    }

    static final class PropertiesChanged
            extends ApplicationMessage {
        final boolean thisIsAllOfThem;
        final List<Property> properties;
        final Double bornOn;

        PropertiesChanged(
                boolean thisIsAllOfThem,
                List<Property> properties,
                Double bornOn) {
            this.thisIsAllOfThem =
                    thisIsAllOfThem;
            this.properties =
                    immutableProperties(
                            properties);
            this.bornOn = bornOn;
            requireValid();
        }

        void requireValid() {
            requireProperties(
                    properties);
        }

        @Override
        int protobufType() {
            return TYPE_PROPERTIES_CHANGED;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void destroy() {
            destroyProperties(
                    properties);
        }
    }

    static final class PropertyRequest
            extends ApplicationMessage {
        @Override
        int protobufType() {
            return TYPE_PROPERTY_REQUEST;
        }

        @Override
        boolean response() {
            return false;
        }
    }

    static final class PropertyResponse
            extends ApplicationMessage {
        final List<Property> properties;

        PropertyResponse(
                List<Property> properties) {
            this.properties =
                    immutableProperties(
                            properties);
            requireValid();
        }

        void requireValid() {
            requireProperties(
                    properties);
        }

        @Override
        int protobufType() {
            return TYPE_PROPERTY_REQUEST;
        }

        @Override
        boolean response() {
            return true;
        }

        @Override
        void destroy() {
            destroyProperties(
                    properties);
        }
    }

    static final class Property {
        final String name;
        final PropertyValue value;

        Property(
                String name,
                PropertyValue value) {
            this.name = name;
            this.value = value;
            requireValid();
        }

        Property copy() {
            byte[] encoded = encodeProperty(this);
            try {
                return decodeProperty(rootReader(encoded), new ParseBudget(), 0);
            } finally {
                wipe(encoded);
            }
        }

        void requireValid() {
            byte[] encoded =
                    encodeUtf8(
                            name,
                            MAX_PROPERTY_NAME_LENGTH,
                            "NanoRegistry property name");
            try {
                if (encoded.length == 0) {
                    throw new IllegalArgumentException(
                            "NanoRegistry property name is empty");
                }
            } finally {
                wipe(
                        encoded);
            }
            if (value != null) {
                value.requireValid(
                        0,
                        new ValidationBudget());
            }
        }

        void destroy() {
            if (value != null) {
                value.destroy();
            }
        }
    }

    static final class PropertyValue {
        final String stringValue;
        final NumberValue numberValue;
        final byte[] uuidValue;
        final byte[] dataValue;
        final SizeValue sizeValue;
        final PropertyValue dictionaryKey;
        final List<PropertyValue> arrayValues;
        final boolean hasIsSet;
        final boolean isSet;
        final boolean hasIsSecurePropertyValue;
        final boolean isSecurePropertyValue;
        final boolean hasIsDate;
        final boolean isDate;
        final boolean hasIsError;
        final boolean isError;
        final boolean hasIsMiniUuidSet;
        final boolean isMiniUuidSet;

        private PropertyValue(
                String stringValue,
                NumberValue numberValue,
                byte[] uuidValue,
                byte[] dataValue,
                SizeValue sizeValue,
                PropertyValue dictionaryKey,
                List<PropertyValue> arrayValues,
                Boolean isSet,
                Boolean isSecurePropertyValue,
                Boolean isDate,
                Boolean isError,
                Boolean isMiniUuidSet) {
            this.stringValue = stringValue;
            this.numberValue = numberValue;
            this.uuidValue =
                    cloneOrNull(
                            uuidValue);
            this.dataValue =
                    cloneOrNull(
                            dataValue);
            this.sizeValue = sizeValue;
            this.dictionaryKey = dictionaryKey;
            this.arrayValues =
                    arrayValues == null
                            ? null
                            : Collections.unmodifiableList(
                                    new ArrayList<>(
                                            arrayValues));
            this.hasIsSet =
                    isSet != null;
            this.isSet =
                    Boolean.TRUE.equals(
                            isSet);
            this.hasIsSecurePropertyValue =
                    isSecurePropertyValue != null;
            this.isSecurePropertyValue =
                    Boolean.TRUE.equals(
                            isSecurePropertyValue);
            this.hasIsDate =
                    isDate != null;
            this.isDate =
                    Boolean.TRUE.equals(
                            isDate);
            this.hasIsError =
                    isError != null;
            this.isError =
                    Boolean.TRUE.equals(
                            isError);
            this.hasIsMiniUuidSet =
                    isMiniUuidSet != null;
            this.isMiniUuidSet =
                    Boolean.TRUE.equals(
                            isMiniUuidSet);
            requireValid(
                    0,
                    new ValidationBudget());
        }

        static PropertyValue string(
                String value) {
            return new PropertyValue(
                    value,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        static PropertyValue number(
                NumberValue value) {
            return new PropertyValue(
                    null,
                    value,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        static PropertyValue uuid(
                byte[] value) {
            return new PropertyValue(
                    null,
                    null,
                    value,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        static PropertyValue data(
                byte[] value) {
            return new PropertyValue(
                    null,
                    null,
                    null,
                    value,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        static PropertyValue size(
                float width,
                float height) {
            return new PropertyValue(
                    null,
                    null,
                    null,
                    null,
                    new SizeValue(
                            width,
                            height),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        static PropertyValue array(
                List<PropertyValue> values) {
            return collection(
                    values,
                    null);
        }

        static PropertyValue set(
                List<PropertyValue> values) {
            return collection(
                    values,
                    true);
        }

        static PropertyValue dictionary(
                List<DictionaryEntry> entries) {
            if (entries == null) {
                throw new IllegalArgumentException(
                        "NanoRegistry dictionary is absent");
            }
            List<PropertyValue> values =
                    new ArrayList<>();
            for (DictionaryEntry entry : entries) {
                if (entry == null
                        || entry.key == null
                        || entry.value == null) {
                    throw new IllegalArgumentException(
                            "NanoRegistry dictionary entry is incomplete");
                }
                values.add(
                        entry.value.withDictionaryKey(
                                entry.key));
            }
            return collection(
                    values,
                    null);
        }

        private static PropertyValue collection(
                List<PropertyValue> values,
                Boolean set) {
            if (values == null) {
                throw new IllegalArgumentException(
                        "NanoRegistry collection is absent");
            }
            return new PropertyValue(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    values,
                    set,
                    null,
                    null,
                    null,
                    null);
        }

        private PropertyValue withDictionaryKey(
                PropertyValue key) {
            if (dictionaryKey != null) {
                throw new IllegalArgumentException(
                        "NanoRegistry value already has a dictionary key");
            }
            return new PropertyValue(
                    stringValue,
                    numberValue,
                    uuidValue,
                    dataValue,
                    sizeValue,
                    key,
                    arrayValues,
                    hasIsSet
                            ? isSet
                            : null,
                    hasIsSecurePropertyValue
                            ? isSecurePropertyValue
                            : null,
                    hasIsDate
                            ? isDate
                            : null,
                    hasIsError
                            ? isError
                            : null,
                    hasIsMiniUuidSet
                            ? isMiniUuidSet
                            : null);
        }

        void requireValid(
                int depth,
                ValidationBudget budget) {
            budget.addValue(
                    depth);
            if (stringValue != null) {
                byte[] encoded =
                        encodeUtf8(
                                stringValue,
                                MAX_STRING_LENGTH,
                                "NanoRegistry string value");
                wipe(
                        encoded);
            }
            if (numberValue != null) {
                numberValue.requireValid();
            }
            if (uuidValue != null
                    && uuidValue.length != UUID_LENGTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry UUID must contain 16 bytes");
            }
            if (dataValue != null
                    && dataValue.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry data value is too large");
            }
            if (sizeValue != null) {
                sizeValue.requireValid();
            }
            if (dictionaryKey != null) {
                dictionaryKey.requireValid(
                        depth + 1,
                        budget);
            }
            if (arrayValues != null) {
                if (arrayValues.size() > MAX_VALUE_NODES) {
                    throw new IllegalArgumentException(
                            "NanoRegistry collection is too large");
                }
                for (PropertyValue element : arrayValues) {
                    if (element == null) {
                        throw new IllegalArgumentException(
                                "NanoRegistry collection contains null");
                    }
                    element.requireValid(
                            depth + 1,
                            budget);
                }
            }
        }

        void destroy() {
            wipe(
                    uuidValue);
            wipe(
                    dataValue);
            if (dictionaryKey != null) {
                dictionaryKey.destroy();
            }
            if (arrayValues != null) {
                for (PropertyValue element : arrayValues) {
                    element.destroy();
                }
            }
        }
    }

    static final class DictionaryEntry {
        final PropertyValue key;
        final PropertyValue value;

        DictionaryEntry(
                PropertyValue key,
                PropertyValue value) {
            this.key = key;
            this.value = value;
        }
    }

    static final class NumberValue {
        final Integer int32Value;
        final Float floatValue;
        final Double doubleValue;
        final Boolean boolValue;
        final Long int64Value;
        final boolean hasIsUnsigned;
        final boolean isUnsigned;
        final boolean hasIsShortOrChar;
        final boolean isShortOrChar;

        private NumberValue(
                Integer int32Value,
                Float floatValue,
                Double doubleValue,
                Boolean boolValue,
                Long int64Value,
                Boolean isUnsigned,
                Boolean isShortOrChar) {
            this.int32Value = int32Value;
            this.floatValue = floatValue;
            this.doubleValue = doubleValue;
            this.boolValue = boolValue;
            this.int64Value = int64Value;
            this.hasIsUnsigned =
                    isUnsigned != null;
            this.isUnsigned =
                    Boolean.TRUE.equals(
                            isUnsigned);
            this.hasIsShortOrChar =
                    isShortOrChar != null;
            this.isShortOrChar =
                    Boolean.TRUE.equals(
                            isShortOrChar);
            requireValid();
        }

        static NumberValue ofInt32(
                int value,
                boolean unsigned,
                Boolean shortOrChar) {
            return new NumberValue(
                    value,
                    null,
                    null,
                    null,
                    null,
                    unsigned
                            ? true
                            : null,
                    shortOrChar);
        }

        static NumberValue ofInt64(
                long value,
                boolean unsigned) {
            return new NumberValue(
                    null,
                    null,
                    null,
                    null,
                    value,
                    unsigned
                            ? true
                            : null,
                    null);
        }

        static NumberValue ofFloat(
                float value) {
            return new NumberValue(
                    null,
                    value,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        static NumberValue ofDouble(
                double value) {
            return new NumberValue(
                    null,
                    null,
                    value,
                    null,
                    null,
                    null,
                    null);
        }

        static NumberValue ofBoolean(
                boolean value) {
            return new NumberValue(
                    null,
                    null,
                    null,
                    value,
                    null,
                    null,
                    null);
        }

        void requireValid() {
            if (hasIsUnsigned
                    && int32Value == null
                    && int64Value == null) {
                throw new IllegalArgumentException(
                        "NanoRegistry unsigned flag requires integer");
            }
            if (hasIsShortOrChar
                    && int32Value == null) {
                throw new IllegalArgumentException(
                        "NanoRegistry short/char flag requires int32");
            }
        }
    }

    static final class SizeValue {
        final float width;
        final float height;

        SizeValue(
                float width,
                float height) {
            this.width = width;
            this.height = height;
            requireValid();
        }

        void requireValid() {
        }
    }

    private static List<Property> immutableProperties(
            List<Property> properties) {
        if (properties == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry properties are absent");
        }
        return Collections.unmodifiableList(
                new ArrayList<>(
                        properties));
    }

    private static void requireProperties(
            List<Property> properties) {
        if (properties.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException(
                    "NanoRegistry has too many properties");
        }
        for (Property property : properties) {
            if (property == null) {
                throw new IllegalArgumentException(
                        "NanoRegistry contains a null property");
            }
            property.requireValid();
        }
    }

    private static void destroyProperties(
            List<Property> properties) {
        for (Property property : properties) {
            property.destroy();
        }
    }

    private static byte[] cloneOrNull(
            byte[] value) {
        return value == null
                ? null
                : value.clone();
    }

    private static final class ValidationBudget {
        private int valueNodes;

        void addValue(
                int depth) {
            if (depth > MAX_RECURSION_DEPTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry property value nesting is too deep");
            }
            valueNodes++;
            if (valueNodes > MAX_VALUE_NODES) {
                throw new IllegalArgumentException(
                        "NanoRegistry property graph is too large");
            }
        }
    }

    private static final class ParseBudget {
        private int properties;
        private int valueNodes;

        void addProperty() {
            properties++;
            if (properties > MAX_PROPERTIES) {
                throw new IllegalArgumentException(
                        "NanoRegistry has too many properties");
            }
        }

        void addValue(
                int depth) {
            if (depth > MAX_RECURSION_DEPTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry property value nesting is too deep");
            }
            valueNodes++;
            if (valueNodes > MAX_VALUE_NODES) {
                throw new IllegalArgumentException(
                        "NanoRegistry property graph is too large");
            }
        }
    }

    private static final class Reader {
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
                    || wire > WIRE_FIXED32) {
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
                            MAX_PAYLOAD_LENGTH,
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
                case WIRE_VARINT -> readVarint();
                case WIRE_FIXED64 -> requireAndAdvance(
                        8);
                case WIRE_LENGTH_DELIMITED -> {
                    int length =
                            readLength(
                                    MAX_PAYLOAD_LENGTH,
                                    "NanoRegistry unknown field");
                    offset += length;
                }
                case WIRE_FIXED32 -> requireAndAdvance(
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
