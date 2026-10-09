package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded binary-property-list codec for Apple keyed archives.
 *
 * <p>This is deliberately not a Java object deserializer. It exposes only
 * property-list values and opaque NSKeyedArchiver UID references. Callers
 * must validate every class descriptor and graph edge they consume.</p>
 */
final class AppleBinaryPropertyList {
    private static final byte[] HEADER =
            "bplist00".getBytes(
                    StandardCharsets.US_ASCII);
    private static final int TRAILER_LENGTH = 32;
    private static final int MAX_LENGTH = 1024 * 1024;
    private static final int MAX_OBJECTS = 8192;
    private static final int MAX_DEPTH = 64;

    private AppleBinaryPropertyList() {
    }

    static Object decode(
            byte[] encoded) {
        return new Parser(
                encoded).parseRoot();
    }

    /** Larger native NTK data objects; existing callers keep the 1MiB default. */
    static Object decodeBounded(byte[] encoded, int maximumLength) {
        if (maximumLength <= 0 || maximumLength > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid binary plist caller bound");
        }
        return new Parser(encoded, maximumLength).parseRoot();
    }

    static byte[] encode(
            Object root) {
        return new Encoder().encode(
                root);
    }

    /** Intent metadata contains unsigned 64-bit hashes encoded as 128-bit plist integers. */
    static Object decodeIntentData(byte[] encoded) {
        return new Parser(encoded, 131072, true).parseRoot();
    }

    static final class Uid {
        private final int value;

        Uid(
                int value) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "Binary plist UID is negative");
            }
            this.value = value;
        }

        int value() {
            return value;
        }

        @Override
        public boolean equals(
                Object other) {
            return other instanceof Uid uid
                    && value == uid.value;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(
                    value);
        }

        @Override
        public String toString() {
            return "UID(" + value + ")";
        }
    }

    private interface Node {
        byte[] encode(
                int referenceSize);
    }

    private static final class ScalarNode
            implements Node {
        private final byte[] encoded;

        private ScalarNode(
                byte[] encoded) {
            this.encoded = encoded;
        }

        @Override
        public byte[] encode(
                int referenceSize) {
            return encoded;
        }
    }

    private static final class ArrayNode
            implements Node {
        private final int[] references;

        private ArrayNode(
                int[] references) {
            this.references = references;
        }

        @Override
        public byte[] encode(
                int referenceSize) {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            writeCountMarker(
                    output,
                    0xa0,
                    references.length);
            for (int reference :
                    references) {
                writeUnsigned(
                        output,
                        reference,
                        referenceSize);
            }
            return output.toByteArray();
        }
    }

    private static final class DictionaryNode
            implements Node {
        private final int[] keys;
        private final int[] values;

        private DictionaryNode(
                int[] keys,
                int[] values) {
            this.keys = keys;
            this.values = values;
        }

        @Override
        public byte[] encode(
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
    }

    private static final class Encoder {
        private final List<Node> nodes =
                new ArrayList<>();
        private final IdentityHashMap<Object, Boolean> active =
                new IdentityHashMap<>();

        private byte[] encode(
                Object root) {
            int topObject =
                    addObject(
                            root,
                            0);
            int referenceSize =
                    bytesRequired(
                            nodes.size() - 1L);
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            output.writeBytes(
                    HEADER);
            int[] offsets =
                    new int[nodes.size()];
            for (int index = 0;
                    index < nodes.size();
                    index++) {
                offsets[index] =
                        output.size();
                output.writeBytes(
                        nodes.get(
                                index).encode(
                                referenceSize));
                requireEncodedLength(
                        output.size());
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
                    nodes.size(),
                    8);
            writeUnsigned(
                    output,
                    topObject,
                    8);
            writeUnsigned(
                    output,
                    offsetTableStart,
                    8);
            byte[] result =
                    output.toByteArray();
            requireEncodedLength(
                    result.length);
            return result;
        }

        private int addObject(
                Object value,
                int depth) {
            if (depth > MAX_DEPTH
                    || nodes.size() >= MAX_OBJECTS) {
                throw new IllegalArgumentException(
                        "Binary plist value graph is invalid");
            }
            int index =
                    nodes.size();
            nodes.add(
                    null);
            Node node;
            if (value == null) {
                node = new ScalarNode(new byte[] {0x00});
            } else if (value instanceof String stringValue) {
                node =
                        new ScalarNode(
                                encodeString(
                                        stringValue));
            } else if (value instanceof Boolean booleanValue) {
                node =
                        new ScalarNode(
                                new byte[] {
                                        (byte) (booleanValue
                                                ? 0x09
                                                : 0x08)
                                });
            } else if (value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long) {
                node =
                        new ScalarNode(
                                encodeInteger(
                                        ((Number) value).longValue()));
            } else if (value instanceof Float
                    || value instanceof Double) {
                double doubleValue =
                        ((Number) value).doubleValue();
                if (!Double.isFinite(
                        doubleValue)) {
                    throw new IllegalArgumentException(
                            "Binary plist real is not finite");
                }
                node =
                        new ScalarNode(
                                encodeReal(
                                        doubleValue));
            } else if (value instanceof byte[] dataValue) {
                node =
                        new ScalarNode(
                                encodeData(
                                        dataValue));
            } else if (value instanceof Uid uid) {
                node =
                        new ScalarNode(
                                encodeUid(
                                        uid.value));
            } else if (value instanceof List<?> listValue) {
                requireInactive(
                        listValue);
                try {
                    if (listValue.size() > MAX_OBJECTS) {
                        throw new IllegalArgumentException(
                                "Binary plist array is too large");
                    }
                    int[] references =
                            new int[listValue.size()];
                    for (int item = 0;
                            item < references.length;
                            item++) {
                        references[item] =
                                addObject(
                                        listValue.get(
                                                item),
                                        depth + 1);
                    }
                    node =
                            new ArrayNode(
                                    references);
                } finally {
                    active.remove(
                            listValue);
                }
            } else if (value instanceof Map<?, ?> mapValue) {
                requireInactive(
                        mapValue);
                try {
                    if (mapValue.size() > MAX_OBJECTS / 2) {
                        throw new IllegalArgumentException(
                                "Binary plist dictionary is too large");
                    }
                    int[] keys =
                            new int[mapValue.size()];
                    int[] values =
                            new int[mapValue.size()];
                    // Canonical (Apple/python plistlib) object order: all
                    // keys first, then all values — required for byte-equal
                    // output against Apple's binary writer.
                    int entry = 0;
                    for (Map.Entry<?, ?> item :
                            mapValue.entrySet()) {
                        if (!(item.getKey() instanceof String key)) {
                            throw new IllegalArgumentException(
                                    "Binary plist dictionary key is not a string");
                        }
                        keys[entry] =
                                addObject(
                                        key,
                                        depth + 1);
                        entry++;
                    }
                    entry = 0;
                    for (Map.Entry<?, ?> item :
                            mapValue.entrySet()) {
                        values[entry] =
                                addObject(
                                        item.getValue(),
                                        depth + 1);
                        entry++;
                    }
                    node =
                            new DictionaryNode(
                                    keys,
                                    values);
                } finally {
                    active.remove(
                            mapValue);
                }
            } else {
                throw new IllegalArgumentException(
                        "Unsupported binary plist value");
            }
            nodes.set(
                    index,
                    node);
            return index;
        }

        private void requireInactive(
                Object value) {
            if (active.put(
                    value,
                    Boolean.TRUE) != null) {
                throw new IllegalArgumentException(
                        "Binary plist contains a collection cycle");
            }
        }
    }

    private static final class Parser {
        private final byte[] data;
        private final int maximumLength;
        private final boolean wideIntentIntegers;
        private final int trailerOffset;
        private final int offsetSize;
        private final int referenceSize;
        private final int objectCount;
        private final int topObject;
        private final int offsetTableStart;
        private final int[] offsets;
        private final Object[] cache;
        private final boolean[] cacheReady;
        private final boolean[] active;

        private Parser(
                byte[] encoded) {
            this(encoded, MAX_LENGTH);
        }

        private Parser(byte[] encoded, int maximumLength) {
            this(encoded, maximumLength, false);
        }

        private Parser(byte[] encoded, int maximumLength, boolean wideIntentIntegers) {
            if (encoded == null
                    || encoded.length < HEADER.length + TRAILER_LENGTH
                    || encoded.length > maximumLength) {
                throw new IllegalArgumentException(
                        "Binary plist length is invalid");
            }
            data =
                    encoded;
            this.maximumLength = maximumLength;
            this.wideIntentIntegers = wideIntentIntegers;
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
            cacheReady =
                    new boolean[objectCount];
            active =
                    new boolean[objectCount];
        }

        private Object parseRoot() {
            return parseObject(
                    topObject,
                    0);
        }

        private Object parseObject(
                int index,
                int depth) {
            if (index < 0
                    || index >= objectCount
                    || depth > MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "Binary plist object reference is invalid");
            }
            if (cacheReady[index]) {
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
                int family =
                        marker & 0xf0;
                Object value;
                if (marker == 0x00) {
                    value = null;
                } else if (marker == 0x08) {
                    value =
                            Boolean.FALSE;
                } else if (marker == 0x09) {
                    value =
                            Boolean.TRUE;
                } else if (family == 0x10) {
                    value =
                            parseInteger(
                                    offset,
                                    marker);
                } else if (family == 0x20) {
                    value =
                            parseReal(
                                    offset,
                                    marker);
                } else if (family == 0x40) {
                    Length length =
                            readLength(
                                    offset,
                                    marker);
                    requireObjectBytes(
                            length.dataOffset,
                            length.count);
                    byte[] bytes =
                            new byte[length.count];
                    System.arraycopy(
                            data,
                            length.dataOffset,
                            bytes,
                            0,
                            bytes.length);
                    value =
                            bytes;
                } else if (family == 0x50
                        || family == 0x60
                        || family == 0x70) {
                    value =
                            parseString(
                                    offset,
                                    marker,
                                    family);
                } else if (family == 0x80) {
                    int width =
                            (marker & 0x0f) + 1;
                    requireObjectBytes(
                            offset,
                            1 + width);
                    long uid =
                            readUnsigned(
                                    offset + 1,
                                    width);
                    if (uid < 0
                            || uid > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(
                                "Binary plist UID is invalid");
                    }
                    value =
                            new Uid(
                                    (int) uid);
                } else if (family == 0xa0) {
                    value =
                            parseArray(
                                    offset,
                                    marker,
                                    depth);
                } else if (family == 0xd0) {
                    value =
                            parseDictionary(
                                    offset,
                                    marker,
                                    depth);
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported binary plist object type");
                }
                cache[index] =
                        value;
                cacheReady[index] =
                        true;
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

        private Number parseInteger(
                int offset,
                int marker) {
            int exponent =
                    marker & 0x0f;
            if (exponent == 4 && wideIntentIntegers) {
                requireObjectBytes(offset, 17);
                var integer = new java.math.BigInteger(java.util.Arrays.copyOfRange(data, offset + 1, offset + 17));
                return integer.bitLength() < 64 ? Long.valueOf(integer.longValue()) : integer;
            }
            if (exponent > 3) {
                throw new IllegalArgumentException(
                        "Binary plist integer is too wide");
            }
            int width =
                    1 << exponent;
            requireObjectBytes(
                    offset,
                    1 + width);
            long raw =
                    readUnsigned(
                            offset + 1,
                            width);
            // Apple binary plist uses unsigned integers at widths1/2/4;
            // signed negative values occupy eight bytes (matching our writer).
            return raw;
        }

        private Double parseReal(
                int offset,
                int marker) {
            int exponent =
                    marker & 0x0f;
            int width =
                    1 << exponent;
            requireObjectBytes(
                    offset,
                    1 + width);
            double value;
            if (width == 4) {
                value =
                        Float.intBitsToFloat(
                                (int) readUnsigned(
                                        offset + 1,
                                        width));
            } else if (width == 8) {
                value =
                        Double.longBitsToDouble(
                                readUnsigned(
                                        offset + 1,
                                        width));
            } else {
                throw new IllegalArgumentException(
                        "Binary plist real width is invalid");
            }
            if (!Double.isFinite(
                    value)) {
                throw new IllegalArgumentException(
                        "Binary plist real is not finite");
            }
            return value;
        }

        private String parseString(
                int offset,
                int marker,
                int family) {
            Length length =
                    readLength(
                            offset,
                            marker);
            Charset charset;
            int bytesPerCharacter;
            if (family == 0x50) {
                charset =
                        StandardCharsets.US_ASCII;
                bytesPerCharacter =
                        1;
            } else if (family == 0x60) {
                charset =
                        StandardCharsets.UTF_16BE;
                bytesPerCharacter =
                        2;
            } else {
                charset =
                        StandardCharsets.UTF_8;
                bytesPerCharacter =
                        1;
            }
            int byteLength =
                    Math.multiplyExact(
                            length.count,
                            bytesPerCharacter);
            requireObjectBytes(
                    length.dataOffset,
                    byteLength);
            try {
                return charset.newDecoder()
                        .onMalformedInput(
                                CodingErrorAction.REPORT)
                        .onUnmappableCharacter(
                                CodingErrorAction.REPORT)
                        .decode(
                                ByteBuffer.wrap(
                                        data,
                                        length.dataOffset,
                                        byteLength))
                        .toString();
            } catch (CharacterCodingException malformed) {
                throw new IllegalArgumentException(
                        "Binary plist string is malformed",
                        malformed);
            }
        }

        private List<Object> parseArray(
                int offset,
                int marker,
                int depth) {
            Length length =
                    readLength(
                            offset,
                            marker);
            if (length.count > MAX_OBJECTS) {
                throw new IllegalArgumentException(
                        "Binary plist array is too large");
            }
            int byteLength =
                    Math.multiplyExact(
                            length.count,
                            referenceSize);
            requireObjectBytes(
                    length.dataOffset,
                    byteLength);
            List<Object> array =
                    new ArrayList<>(
                            length.count);
            for (int item = 0;
                    item < length.count;
                    item++) {
                array.add(
                        parseObject(
                                readReference(
                                        length.dataOffset
                                                + item * referenceSize),
                                depth + 1));
            }
            return Collections.unmodifiableList(
                    array);
        }

        private Map<String, Object> parseDictionary(
                int offset,
                int marker,
                int depth) {
            Length length =
                    readLength(
                            offset,
                            marker);
            if (length.count > MAX_OBJECTS / 2) {
                throw new IllegalArgumentException(
                        "Binary plist dictionary is too large");
            }
            int byteLength =
                    Math.multiplyExact(
                            Math.multiplyExact(
                                    length.count,
                                    referenceSize),
                            2);
            requireObjectBytes(
                    length.dataOffset,
                    byteLength);
            int valuesOffset =
                    length.dataOffset
                            + length.count * referenceSize;
            LinkedHashMap<String, Object> dictionary =
                    new LinkedHashMap<>();
            for (int entry = 0;
                    entry < length.count;
                    entry++) {
                Object keyObject =
                        parseObject(
                                readReference(
                                        length.dataOffset
                                                + entry * referenceSize),
                                depth + 1);
                if (!(keyObject instanceof String key)
                        || dictionary.containsKey(
                        key)) {
                    throw new IllegalArgumentException(
                            "Binary plist dictionary key is invalid");
                }
                Object value =
                        parseObject(
                                readReference(
                                        valuesOffset
                                                + entry * referenceSize),
                                depth + 1);
                dictionary.put(
                        key,
                        value);
            }
            return Collections.unmodifiableMap(
                    dictionary);
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
            int exponent =
                    integerMarker & 0x0f;
            if (exponent > 3) {
                throw new IllegalArgumentException(
                        "Binary plist extended length is too wide");
            }
            int width =
                    1 << exponent;
            requireObjectBytes(
                    objectOffset,
                    2 + width);
            long count =
                    readUnsigned(
                            objectOffset + 2,
                            width);
            if (count < 0
                    || count > maximumLength) {
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
                    || (long) offset + length > offsetTableStart) {
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
                    || (long) offset + width > data.length) {
                throw new IllegalArgumentException(
                        "Binary plist integer bounds are invalid");
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

    private static byte[] encodeInteger(
            long value) {
        // Apple/python canonical: non-negative values use the smallest
        // unsigned power-of-two width (128 fits in one byte); negative
        // values always take eight bytes.
        int width;
        if (value < 0) {
            width =
                    8;
        } else if (value <= 0xffL) {
            width =
                    1;
        } else if (value <= 0xffffL) {
            width =
                    2;
        } else if (value <= 0xffffffffL) {
            width =
                    4;
        } else {
            width =
                    8;
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

    private static byte[] encodeReal(
            double value) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        output.write(
                0x23);
        writeUnsigned(
                output,
                Double.doubleToRawLongBits(
                        value),
                8);
        return output.toByteArray();
    }

    private static byte[] encodeData(
            byte[] value) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeCountMarker(
                output,
                0x40,
                value.length);
        output.writeBytes(
                value);
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
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeCountMarker(
                output,
                ascii
                        ? 0x50
                        : 0x60,
                ascii
                        ? bytes.length
                        : bytes.length / 2);
        output.writeBytes(
                bytes);
        return output.toByteArray();
    }

    private static byte[] encodeUid(
            int value) {
        int width =
                bytesRequired(
                        value);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        output.write(
                0x80 | (width - 1));
        writeUnsigned(
                output,
                value,
                width);
        return output.toByteArray();
    }

    private static void writeCountMarker(
            ByteArrayOutputStream output,
            int family,
            int count) {
        if (count < 0
                || count > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Binary plist object count is invalid");
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
        int bytes =
                1;
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

    private static void requireEncodedLength(
            int length) {
        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Binary plist is too large");
        }
    }

    private static final class Length {
        private final int count;
        private final int dataOffset;

        private Length(
                int count,
                int dataOffset) {
            this.count = count;
            this.dataOffset = dataOffset;
        }
    }
}
