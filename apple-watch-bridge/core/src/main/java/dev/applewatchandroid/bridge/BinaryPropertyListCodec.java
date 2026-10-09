package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict binary-property-list codec for setup-state dictionaries.
 *
 * <p>The encoder supports the scalar types and string arrays used by the
 * PairedSync lifecycle: strings, signed integers, and booleans.
 * Keeping this surface small prevents Java objects from silently acquiring
 * an Apple wire representation that has not been verified.</p>
 */
final class BinaryPropertyListCodec {
    private static final byte[] HEADER =
            "bplist00".getBytes(
                    StandardCharsets.US_ASCII);
    private static final int TRAILER_LENGTH = 32;
    private static final int MAX_LENGTH = 1024 * 1024;
    private static final int MAX_OBJECTS = 1024;

    private BinaryPropertyListCodec() {
    }

    /** A preference value is the plist root itself, not a dictionary wrapper. */
    static byte[] encodeBoolean(boolean value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(42);
        output.writeBytes(HEADER);
        output.write(value ? 0x09 : 0x08);
        output.write(8); // One object offset.
        output.writeBytes(new byte[6]);
        output.write(1); // Offset size.
        output.write(1); // Object reference size.
        writeUnsigned(output, 1, 8);
        writeUnsigned(output, 0, 8);
        writeUnsigned(output, 9, 8);
        return output.toByteArray();
    }

    static boolean decodeBoolean(byte[] encoded) {
        Object root = new Parser(encoded).parseRoot();
        if (!(root instanceof Boolean value)) {
            throw new IllegalArgumentException("Binary plist root is not a boolean");
        }
        return value;
    }

    /** A native scalar preference is a string root, without a dictionary wrapper. */
    static byte[] encodeStringRoot(String value) {
        if (value == null) throw new IllegalArgumentException("Binary plist string is absent");
        return encodeObjectTable(List.of(encodeString(value)), 1);
    }

    static String decodeStringRoot(byte[] encoded) {
        Object root = new Parser(encoded).parseRoot();
        if (!(root instanceof String value)) {
            throw new IllegalArgumentException("Binary plist root is not a string");
        }
        return value;
    }

    /** SelectedPigmentList is an array at the root, without a dictionary wrapper. */
    static List<String> decodeStringArray(byte[] encoded) {
        Object root = new Parser(encoded).parseRoot();
        if (!(root instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof String))) {
            throw new IllegalArgumentException("Binary plist root is not a string array");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) result.add((String) value);
        return List.copyOf(result);
    }

    static byte[] encodeStringArray(List<String> values) {
        if (values == null || values.size() >= MAX_OBJECTS) {
            throw new IllegalArgumentException("Binary plist array is invalid");
        }
        int referenceSize = bytesRequired(values.size());
        List<byte[]> objects = new ArrayList<>(values.size() + 1);
        ByteArrayOutputStream array = new ByteArrayOutputStream();
        writeCountMarker(array, 0xa0, values.size());
        for (int i = 0; i < values.size(); i++) writeUnsigned(array, i + 1, referenceSize);
        objects.add(array.toByteArray());
        for (String value : values) {
            if (value == null) throw new IllegalArgumentException("Binary plist array must contain strings");
            objects.add(encodeString(value));
        }
        return encodeObjectTable(objects, referenceSize);
    }

    static byte[] encodeDictionary(
            Map<String, ?> dictionary) {
        if (dictionary == null
                || dictionary.size() > MAX_OBJECTS / 2) {
            throw new IllegalArgumentException(
                    "Binary plist dictionary is invalid");
        }

        int objectCount =
                1 + dictionary.size() * 2;
        for (Object value : dictionary.values()) {
            if (value instanceof List<?> labels) {
                if (labels.size() > MAX_OBJECTS - objectCount) {
                    throw new IllegalArgumentException("Binary plist has too many objects");
                }
                objectCount += labels.size();
            }
        }
        if (objectCount > MAX_OBJECTS) {
            throw new IllegalArgumentException("Binary plist has too many objects");
        }
        int referenceSize =
                bytesRequired(
                        objectCount - 1L);
        List<byte[]> objects =
                new ArrayList<>(
                        objectCount);
        objects.add(
                null);
        int[] keys =
                new int[dictionary.size()];
        int[] values =
                new int[dictionary.size()];
        int entry = 0;
        for (Map.Entry<String, ?> item :
                dictionary.entrySet()) {
            String key =
                    item.getKey();
            if (key == null
                    || key.isEmpty()) {
                throw new IllegalArgumentException(
                        "Binary plist dictionary key is invalid");
            }
            keys[entry] =
                    objects.size();
            objects.add(
                    encodeString(
                            key));
            values[entry] =
                    objects.size();
            if (item.getValue() instanceof List<?> labels) {
                int arrayIndex = objects.size();
                objects.add(null);
                ByteArrayOutputStream array = new ByteArrayOutputStream();
                writeCountMarker(array, 0xa0, labels.size());
                for (Object label : labels) {
                    if (!(label instanceof String text)) {
                        throw new IllegalArgumentException("Binary plist array must contain strings");
                    }
                    writeUnsigned(array, objects.size(), referenceSize);
                    objects.add(encodeString(text));
                }
                objects.set(arrayIndex, array.toByteArray());
            } else {
                objects.add(encodeScalar(item.getValue()));
            }
            entry++;
        }
        objects.set(
                0,
                encodeDictionaryObject(
                        keys,
                        values,
                        referenceSize));

        return encodeObjectTable(objects, referenceSize);
    }

    private static byte[] encodeObjectTable(List<byte[]> objects, int referenceSize) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        output.writeBytes(
                HEADER);
        int[] offsets =
                new int[objects.size()];
        for (int index = 0;
                index < objects.size();
                index++) {
            offsets[index] =
                    output.size();
            output.writeBytes(
                    objects.get(
                            index));
            if (output.size() > MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "Binary plist object table is too large");
            }
        }

        int offsetTableStart =
                output.size();
        int offsetSize =
                bytesRequired(
                        offsetTableStart);
        for (int offset :
                offsets) {
            writeUnsigned(
                    output,
                    offset,
                    offsetSize);
        }
        for (int index = 0;
                index < 6;
                index++) {
            output.write(
                    0);
        }
        output.write(
                offsetSize);
        output.write(
                referenceSize);
        writeUnsigned(
                output,
                objects.size(),
                8);
        writeUnsigned(
                output,
                0,
                8);
        writeUnsigned(
                output,
                offsetTableStart,
                8);

        byte[] encoded =
                output.toByteArray();
        if (encoded.length > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Binary plist is too large");
        }
        return encoded;
    }

    static Map<String, Object> decodeDictionary(
            byte[] encoded) {
        Parser parser =
                new Parser(
                        encoded);
        Object root =
                parser.parseRoot();
        if (!(root instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(
                    "Binary plist root is not a dictionary");
        }
        LinkedHashMap<String, Object> result =
                new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry :
                raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || result.put(
                    key,
                    entry.getValue()) != null) {
                throw new IllegalArgumentException(
                        "Binary plist dictionary key is invalid");
            }
        }
        return Collections.unmodifiableMap(
                result);
    }

    private static byte[] encodeDictionaryObject(
            int[] keys,
            int[] values,
            int referenceSize) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeCountMarker(
                output,
                0xd0,
                keys.length);
        for (int key :
                keys) {
            writeUnsigned(
                    output,
                    key,
                    referenceSize);
        }
        for (int value :
                values) {
            writeUnsigned(
                    output,
                    value,
                    referenceSize);
        }
        return output.toByteArray();
    }

    private static byte[] encodeScalar(
            Object value) {
        if (value instanceof Boolean booleanValue) {
            return new byte[] {
                    (byte) (booleanValue
                            ? 0x09
                            : 0x08)
            };
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return encodeInteger(
                    ((Number) value).longValue());
        }
        if (value instanceof String stringValue) {
            return encodeString(
                    stringValue);
        }
        throw new IllegalArgumentException(
                "Unsupported binary plist scalar type");
    }

    private static byte[] encodeInteger(
            long value) {
        int width;
        if (value >= -128
                && value <= 127) {
            width = 1;
        } else if (value >= -32768
                && value <= 32767) {
            width = 2;
        } else if (value >= Integer.MIN_VALUE
                && value <= Integer.MAX_VALUE) {
            width = 4;
        } else {
            width = 8;
        }
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        output.write(
                0x10
                        | Integer.numberOfTrailingZeros(
                        width));
        writeUnsigned(
                output,
                value,
                width);
        return output.toByteArray();
    }

    private static byte[] encodeString(
            String value) {
        boolean ascii =
                value.chars()
                        .allMatch(
                                character -> character <= 0x7f);
        byte[] bytes =
                value.getBytes(
                        ascii
                                ? StandardCharsets.US_ASCII
                                : StandardCharsets.UTF_16BE);
        int characterCount =
                ascii
                        ? bytes.length
                        : bytes.length / 2;
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeCountMarker(
                output,
                ascii
                        ? 0x50
                        : 0x60,
                characterCount);
        output.writeBytes(
                bytes);
        return output.toByteArray();
    }

    private static void writeCountMarker(
            ByteArrayOutputStream output,
            int family,
            int count) {
        if (count < 0) {
            throw new IllegalArgumentException(
                    "Binary plist object count is negative");
        }
        if (count < 15) {
            output.write(
                    family | count);
            return;
        }
        output.write(
                family | 0x0f);
        output.writeBytes(
                encodeInteger(
                        count));
    }

    private static int bytesRequired(
            long value) {
        if (value < 0) {
            return 8;
        }
        int bytes = 1;
        while (bytes < 8
                && (value >>> (bytes * 8)) != 0) {
            bytes++;
        }
        return bytes;
    }

    private static void writeUnsigned(
            ByteArrayOutputStream output,
            long value,
            int width) {
        if (width < 1
                || width > 8) {
            throw new IllegalArgumentException(
                    "Binary plist integer width is invalid");
        }
        for (int shift = (width - 1) * 8;
                shift >= 0;
                shift -= 8) {
            output.write(
                    (int) (value >>> shift)
                            & 0xff);
        }
    }

    private static final class Parser {
        private final byte[] data;
        private final int trailerOffset;
        private final int offsetSize;
        private final int referenceSize;
        private final int objectCount;
        private final int topObject;
        private final int offsetTableStart;
        private final int[] offsets;
        private final Object[] cache;
        private final boolean[] active;

        private Parser(
                byte[] data) {
            if (data == null
                    || data.length < HEADER.length + TRAILER_LENGTH
                    || data.length > MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "Binary plist length is invalid");
            }
            this.data = data;
            for (int index = 0;
                    index < HEADER.length;
                    index++) {
                if (data[index] != HEADER[index]) {
                    throw new IllegalArgumentException(
                            "Binary plist header is invalid");
                }
            }
            trailerOffset =
                    data.length - TRAILER_LENGTH;
            offsetSize =
                    data[trailerOffset + 6] & 0xff;
            referenceSize =
                    data[trailerOffset + 7] & 0xff;
            long count =
                    readUnsigned(
                            trailerOffset + 8,
                            8);
            long top =
                    readUnsigned(
                            trailerOffset + 16,
                            8);
            long table =
                    readUnsigned(
                            trailerOffset + 24,
                            8);
            if (offsetSize < 1
                    || offsetSize > 8
                    || referenceSize < 1
                    || referenceSize > 8
                    || count < 1
                    || count > MAX_OBJECTS
                    || top < 0
                    || top >= count
                    || table < HEADER.length
                    || table > trailerOffset) {
                throw new IllegalArgumentException(
                        "Binary plist trailer is invalid");
            }
            objectCount =
                    (int) count;
            topObject =
                    (int) top;
            offsetTableStart =
                    (int) table;
            long tableLength =
                    (long) objectCount
                            * offsetSize;
            if (offsetTableStart + tableLength
                    != trailerOffset) {
                throw new IllegalArgumentException(
                        "Binary plist offset table is invalid");
            }
            offsets =
                    new int[objectCount];
            for (int index = 0;
                    index < objectCount;
                    index++) {
                long offset =
                        readUnsigned(
                                offsetTableStart
                                        + index * offsetSize,
                                offsetSize);
                if (offset < HEADER.length
                        || offset >= offsetTableStart) {
                    throw new IllegalArgumentException(
                            "Binary plist object offset is invalid");
                }
                offsets[index] =
                        (int) offset;
            }
            cache =
                    new Object[objectCount];
            active =
                    new boolean[objectCount];
        }

        private Object parseRoot() {
            return parseObject(
                    topObject);
        }

        private Object parseObject(
                int index) {
            if (index < 0
                    || index >= objectCount) {
                throw new IllegalArgumentException(
                        "Binary plist object reference is invalid");
            }
            if (cache[index] != null) {
                return cache[index];
            }
            if (active[index]) {
                throw new IllegalArgumentException(
                        "Binary plist contains an object cycle");
            }
            active[index] =
                    true;
            try {
                int offset =
                        offsets[index];
                int marker =
                        data[offset] & 0xff;
                Object value;
                if (marker == 0x08) {
                    value =
                            Boolean.FALSE;
                } else if (marker == 0x09) {
                    value =
                            Boolean.TRUE;
                } else if ((marker & 0xf0) == 0x10) {
                    int width =
                            1 << (marker & 0x0f);
                    if (width > 8) {
                        throw new IllegalArgumentException(
                                "Binary plist integer is too wide");
                    }
                    requireObjectBytes(
                            offset,
                            1 + width);
                    long raw =
                            readUnsigned(
                                    offset + 1,
                                    width);
                    if (width < 8
                            && (data[offset + 1] & 0x80) != 0) {
                        raw |=
                                -1L << (width * 8);
                    }
                    value =
                            raw;
                } else if (marker == 0x22 || marker == 0x23) {
                    int width = marker == 0x22 ? 4 : 8;
                    requireObjectBytes(offset, 1 + width);
                    long bits = readUnsigned(offset + 1, width);
                    value = width == 4
                            ? (double) Float.intBitsToFloat((int) bits)
                            : Double.longBitsToDouble(bits);
                } else if ((marker & 0xf0) == 0x40) {
                    Length length = readLength(offset, marker);
                    requireObjectBytes(length.dataOffset, length.count);
                    value = java.util.Arrays.copyOfRange(
                            data, length.dataOffset, length.dataOffset + length.count);
                } else if ((marker & 0xf0) == 0x50
                        || (marker & 0xf0) == 0x60) {
                    Length length =
                            readLength(
                                    offset,
                                    marker);
                    boolean ascii =
                            (marker & 0xf0) == 0x50;
                    int byteLength =
                            Math.multiplyExact(
                                    length.count,
                                    ascii
                                            ? 1
                                            : 2);
                    requireObjectBytes(
                            length.dataOffset,
                            byteLength);
                    value =
                            new String(
                                    data,
                                    length.dataOffset,
                                    byteLength,
                                    ascii
                                            ? StandardCharsets.US_ASCII
                                            : StandardCharsets.UTF_16BE);
                } else if ((marker & 0xf0) == 0xa0) {
                    Length length = readLength(offset, marker);
                    if (length.count > MAX_OBJECTS) {
                        throw new IllegalArgumentException("Binary plist array is too large");
                    }
                    requireObjectBytes(length.dataOffset,
                            Math.multiplyExact(length.count, referenceSize));
                    List<Object> items = new ArrayList<>(length.count);
                    for (int itemIndex = 0; itemIndex < length.count; itemIndex++) {
                        items.add(parseObject(readReference(
                                length.dataOffset + itemIndex * referenceSize)));
                    }
                    value = Collections.unmodifiableList(items);
                } else if ((marker & 0xf0) == 0xd0) {
                    Length length =
                            readLength(
                                    offset,
                                    marker);
                    long referencesLength =
                            (long) length.count
                                    * referenceSize
                                    * 2;
                    if (referencesLength > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(
                                "Binary plist dictionary is too large");
                    }
                    requireObjectBytes(
                            length.dataOffset,
                            (int) referencesLength);
                    LinkedHashMap<String, Object> dictionary =
                            new LinkedHashMap<>();
                    int valuesOffset =
                            length.dataOffset
                                    + length.count * referenceSize;
                    for (int entry = 0;
                            entry < length.count;
                            entry++) {
                        int keyReference =
                                readReference(
                                        length.dataOffset
                                                + entry * referenceSize);
                        int valueReference =
                                readReference(
                                        valuesOffset
                                                + entry * referenceSize);
                        Object keyObject =
                                parseObject(
                                        keyReference);
                        if (!(keyObject instanceof String key)
                                || dictionary.containsKey(
                                key)) {
                            throw new IllegalArgumentException(
                                    "Binary plist dictionary key is invalid");
                        }
                        Object item =
                                parseObject(
                                        valueReference);
                        if (!(item instanceof String)
                                && !(item instanceof Long)
                                && !(item instanceof Boolean)
                                && !(item instanceof Double)
                                && !(item instanceof byte[])
                                && !(item instanceof List)
                                && !(item instanceof Map)) {
                            throw new IllegalArgumentException(
                                    "Binary plist dictionary value is unsupported");
                        }
                        dictionary.put(
                                key,
                                item);
                    }
                    value =
                            Collections.unmodifiableMap(
                                    dictionary);
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported binary plist object type");
                }
                cache[index] =
                        value;
                return value;
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(
                        "Binary plist object length overflows",
                        overflow);
            } finally {
                active[index] =
                        false;
            }
        }

        private Length readLength(
                int objectOffset,
                int marker) {
            int nibble =
                    marker & 0x0f;
            if (nibble < 15) {
                return new Length(
                        nibble,
                        objectOffset + 1);
            }
            requireObjectBytes(
                    objectOffset,
                    2);
            int integerMarker =
                    data[objectOffset + 1] & 0xff;
            if ((integerMarker & 0xf0) != 0x10) {
                throw new IllegalArgumentException(
                        "Binary plist extended length is not an integer");
            }
            int width =
                    1 << (integerMarker & 0x0f);
            if (width > 8) {
                throw new IllegalArgumentException(
                        "Binary plist extended length is too wide");
            }
            requireObjectBytes(
                    objectOffset,
                    2 + width);
            long count =
                    readUnsigned(
                            objectOffset + 2,
                            width);
            if (count < 0
                    || count > MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "Binary plist extended length is invalid");
            }
            return new Length(
                    (int) count,
                    objectOffset + 2 + width);
        }

        private int readReference(
                int offset) {
            long reference =
                    readUnsigned(
                            offset,
                            referenceSize);
            if (reference < 0
                    || reference >= objectCount) {
                throw new IllegalArgumentException(
                        "Binary plist object reference is invalid");
            }
            return (int) reference;
        }

        private void requireObjectBytes(
                int offset,
                int length) {
            if (offset < HEADER.length
                    || length < 0
                    || offset > offsetTableStart - length) {
                throw new IllegalArgumentException(
                        "Binary plist object is truncated");
            }
        }

        private long readUnsigned(
                int offset,
                int width) {
            if (width < 1
                    || width > 8
                    || offset < 0
                    || offset > data.length - width) {
                throw new IllegalArgumentException(
                        "Binary plist integer is truncated");
            }
            long value = 0;
            for (int index = 0;
                    index < width;
                    index++) {
                value =
                        (value << 8)
                                | (data[offset + index] & 0xffL);
            }
            return value;
        }
    }

    private static final class Length {
        final int count;
        final int dataOffset;

        private Length(
                int count,
                int dataOffset) {
            this.count = count;
            this.dataOffset = dataOffset;
        }
    }
}
