package dev.applewatchandroid.bridge;

import java.util.ArrayList;
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

    static final int WIRE_VARINT = 0;
    static final int WIRE_FIXED64 = 1;
    static final int WIRE_LENGTH_DELIMITED = 2;
    static final int WIRE_FIXED32 = 5;

    static final int UUID_LENGTH = 16;
    static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;
    static final int MAX_STRING_LENGTH = 64 * 1024;
    static final int MAX_PROPERTY_NAME_LENGTH = 1024;
    static final int MAX_PROPERTIES = 512;
    static final int MAX_VALUE_NODES = 4096;
    static final int MAX_RECURSION_DEPTH = 32;

    private NanoRegistryPropertyCodec() {
    }

    static byte[] encode(
            ApplicationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry message is absent");
        }
        if (message instanceof PropertiesChanged changed) {
            return NanoRegistryProtobufWire.encodePropertiesChanged(
                    changed);
        }
        if (message instanceof PropertyRequest) {
            return NanoRegistryProtobufWire.encodePropertyRequest();
        }
        if (message instanceof PropertyResponse response) {
            return NanoRegistryProtobufWire.encodePropertyResponse(
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
            return NanoRegistryProtobufWire.decodePropertiesChanged(
                    envelope.payload);
        }
        if (envelope.protobufType
                == TYPE_PROPERTY_REQUEST) {
            if (envelope.response) {
                return NanoRegistryProtobufWire.decodePropertyResponse(
                        envelope.payload);
            }
            return NanoRegistryProtobufWire.decodePropertyRequest(
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
            NanoRegistryProtobufWire.wipe(
                    payload);
        }
    }


    static byte[] encodePropertiesChanged(PropertiesChanged changed) {
        return NanoRegistryProtobufWire.encodePropertiesChanged(changed);
    }

    static PropertiesChanged decodePropertiesChanged(byte[] payload) {
        return NanoRegistryProtobufWire.decodePropertiesChanged(payload);
    }

    static byte[] encodePropertyRequest() {
        return NanoRegistryProtobufWire.encodePropertyRequest();
    }

    static PropertyRequest decodePropertyRequest(byte[] payload) {
        return NanoRegistryProtobufWire.decodePropertyRequest(payload);
    }

    static byte[] encodePropertyResponse(PropertyResponse response) {
        return NanoRegistryProtobufWire.encodePropertyResponse(response);
    }

    static PropertyResponse decodePropertyResponse(byte[] payload) {
        return NanoRegistryProtobufWire.decodePropertyResponse(payload);
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
            byte[] encoded = NanoRegistryProtobufWire.encodeProperty(this);
            try {
                return NanoRegistryProtobufWire.decodeProperty(NanoRegistryProtobufWire.rootReader(encoded), new NanoRegistryProtobufWire.ParseBudget(), 0);
            } finally {
                NanoRegistryProtobufWire.wipe(encoded);
            }
        }

        void requireValid() {
            byte[] encoded =
                    NanoRegistryProtobufWire.encodeUtf8(
                            name,
                            MAX_PROPERTY_NAME_LENGTH,
                            "NanoRegistry property name");
            try {
                if (encoded.length == 0) {
                    throw new IllegalArgumentException(
                            "NanoRegistry property name is empty");
                }
            } finally {
                NanoRegistryProtobufWire.wipe(
                        encoded);
            }
            if (value != null) {
                value.requireValid(
                        0,
                        new NanoRegistryProtobufWire.ValidationBudget());
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

        PropertyValue(
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
                    new NanoRegistryProtobufWire.ValidationBudget());
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

        static PropertyValue collection(
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

        PropertyValue withDictionaryKey(
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
                NanoRegistryProtobufWire.ValidationBudget budget) {
            budget.addValue(
                    depth);
            if (stringValue != null) {
                byte[] encoded =
                        NanoRegistryProtobufWire.encodeUtf8(
                                stringValue,
                                MAX_STRING_LENGTH,
                                "NanoRegistry string value");
                NanoRegistryProtobufWire.wipe(
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
            NanoRegistryProtobufWire.wipe(
                    uuidValue);
            NanoRegistryProtobufWire.wipe(
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

        NumberValue(
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



}
