package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NanoPreferencesSync payload used to publish PairedSync completion.
 */
final class PairedSyncCodec {
    static final String PREFERRED_SERVICE =
            IdsApplicationRoute.PAIRED_SYNC_SERVICE;
    static final String FALLBACK_SERVICE =
            IdsApplicationRoute.PREFERENCE_SYNC_SERVICE;
    static final String DOMAIN =
            "com.apple.pairedsync";
    static final String WATCH_SYNC_STATE_KEY =
            "PSYWatchSyncState";
    static final String WATCH_SYNC_CLIENT_STATE_KEY =
            "PSYWatchSyncClientState";
    /**
     * watchOS NanoPreferencesSync kNPSInitialSyncKey. SystemPreferencesSync
     * on 23S303 permits this key in Local/Tinker sync groups and posts its
     * initial-sync-completion notification. NPSServer also writes it locally.
     * Delivery of this value is separate from PSY apply or visible Buddy
     * completion; the 23S303 NR PairedSync saga observes PSY client state 3.
     */
    static final String NANOPREFSYNCD_DOMAIN =
            "com.apple.nanoprefsyncd";
    static final String PAST_INITIAL_SYNC_KEY =
            "past-initial-sync";

    static final int PROTOBUF_TYPE_USER_DEFAULTS = 0;
    /** Backup has its own schema: container=1, domain=2, repeated keys=3. */
    static final int PROTOBUF_TYPE_USER_DEFAULT_BACKUP = 2;
    static final int MESSAGE_PRIORITY = 300;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED32 = 5;
    private static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;
    private static final int MAX_STRING_LENGTH = 64 * 1024;
    private static final int MAX_KEYS = 128;
    /** Minimal binary plist holding a single boolean {@code true}. */
    private static final byte[] BPLIST_TRUE =
            new byte[] {
                    'b', 'p', 'l', 'i', 's', 't', '0', '0',
                    0x09,
                    0x08,
                    0, 0, 0, 0, 0, 0,
                    0x01,
                    0x01,
                    0, 0, 0, 0, 0, 0, 0, 1,
                    0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0x09,
            };

    private PairedSyncCodec() {
    }

    /** Native NR WaitForSyncToStart requires type zero and nonempty active labels. */
    static UserDefaultsMessage initialSyncStarted(double appleReferenceTimestamp) {
        byte[] watchValue = BinaryPropertyListCodec.encodeDictionary(Map.of(
                "version", 1L, "syncProgressState", 2L, "globalProgress", 0L));
        byte[] clientValue = BinaryPropertyListCodec.encodeDictionary(Map.of(
                "version", 1L, "syncProgressState", 2L, "syncSessionType", 0L,
                "migrationSync", Boolean.FALSE,
                "activeActivityLabels", List.of("InitialSync"),
                "completedActivityLabels", List.of()));
        try {
            return new UserDefaultsMessage(appleReferenceTimestamp, DOMAIN, List.of(
                    new UserDefaultsKey(WATCH_SYNC_STATE_KEY, watchValue, null, null),
                    new UserDefaultsKey(WATCH_SYNC_CLIENT_STATE_KEY, clientValue, null, null)));
        } finally {
            wipe(watchValue);
            wipe(clientValue);
        }
    }

    static UserDefaultsMessage initialSyncCompletion(
            double appleReferenceTimestamp) {
        LinkedHashMap<String, Object> watchState =
                new LinkedHashMap<>();
        watchState.put(
                "version",
                1L);
        watchState.put(
                "syncProgressState",
                3L);
        watchState.put(
                "globalProgress",
                100L);

        LinkedHashMap<String, Object> clientState =
                new LinkedHashMap<>();
        clientState.put(
                "version",
                1L);
        clientState.put(
                "syncProgressState",
                3L);
        clientState.put(
                "syncSessionType",
                0L);
        clientState.put(
                "migrationSync",
                Boolean.FALSE);

        byte[] watchValue =
                BinaryPropertyListCodec.encodeDictionary(
                        watchState);
        byte[] clientValue =
                BinaryPropertyListCodec.encodeDictionary(
                        clientState);
        try {
            return new UserDefaultsMessage(
                    appleReferenceTimestamp,
                    DOMAIN,
                    List.of(
                            new UserDefaultsKey(
                                    WATCH_SYNC_STATE_KEY,
                                    watchValue,
                                    null,
                                    null),
                            new UserDefaultsKey(
                                    WATCH_SYNC_CLIENT_STATE_KEY,
                                    clientValue,
                                    null,
                                    null)));
        } finally {
            wipe(
                    watchValue);
            wipe(
                    clientValue);
        }
    }

    /**
     * kNPSInitialSyncKey publication: single-key user-defaults change in the
     * {@code com.apple.nanoprefsyncd} domain carrying a binary-plist boolean
     * {@code true}. Not part of the strict completion schema: the Watch only
     * needs the key applied to local defaults.
     */
    static UserDefaultsMessage nanoPrefSyncInitialSyncCompletion(
            double appleReferenceTimestamp) {
        byte[] value =
                BPLIST_TRUE.clone();
        try {
            return new UserDefaultsMessage(
                    appleReferenceTimestamp,
                    NANOPREFSYNCD_DOMAIN,
                    List.of(
                            new UserDefaultsKey(
                                    PAST_INITIAL_SYNC_KEY,
                                    value,
                                    null,
                                    null)),
                    false);
        } finally {
            wipe(
                    value);
        }
    }

    static byte[] encode(
            UserDefaultsMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "PairedSync message is absent");
        }
        message.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeTag(
                output,
                1,
                WIRE_FIXED64);
        writeFixed64(
                output,
                Double.doubleToRawLongBits(
                        message.timestamp));
        writeStringField(
                output,
                2,
                message.domain);
        for (UserDefaultsKey key :
                message.keys) {
            byte[] encodedKey =
                    encodeKey(
                            key);
            try {
                writeBytesField(
                        output,
                        3,
                        encodedKey);
            } finally {
                wipe(
                        encodedKey);
            }
        }
        return requireSize(
                output.toByteArray());
    }

    static UserDefaultsMessage decode(
            byte[] payload) {
        return decode(
                payload,
                true);
    }

    /**
     * Inbound variant used for live Watch traffic: any domain and any key
     * set is acceptable. The completion whitelist only matters for messages
     * WE publish; rejecting the Watch's real health/coreaudio defaults
     * frames (live 0.2.165/0.2.166) blinded the session to peer progress.
     */
    static UserDefaultsMessage decodeInbound(
            byte[] payload) {
        return decode(
                payload,
                false);
    }

    private static UserDefaultsMessage decode(
            byte[] payload,
            boolean strict) {
        Reader reader =
                new Reader(
                        requirePayload(
                                payload));
        Double timestamp = null;
        String domain = null;
        List<UserDefaultsKey> keys =
                new ArrayList<>();
        try {
            while (reader.hasRemaining()) {
                int tag =
                        reader.readTag();
                int field =
                        tag >>> 3;
                if (field == 1) {
                    rejectDuplicate(
                            timestamp != null,
                            "message timestamp");
                    requireWire(
                            tag,
                            WIRE_FIXED64);
                    timestamp =
                            Double.longBitsToDouble(
                                    reader.readFixed64());
                } else if (field == 2) {
                    rejectDuplicate(
                            domain != null,
                            "domain");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    domain =
                            reader.readString();
                } else if (field == 3) {
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    if (keys.size() >= MAX_KEYS) {
                        throw new IllegalArgumentException(
                                "PairedSync key count is too large");
                    }
                    byte[] nested =
                            reader.readBytes();
                    try {
                        keys.add(
                                decodeKey(
                                        nested));
                    } finally {
                        wipe(
                                nested);
                    }
                } else {
                    reader.skipField(
                            tag);
                }
            }
            if (timestamp == null
                    || domain == null
                    || keys.isEmpty()) {
                throw new IllegalArgumentException(
                        "PairedSync user-defaults message is incomplete");
            }
            UserDefaultsMessage decoded =
                    new UserDefaultsMessage(
                            timestamp,
                            domain,
                            keys,
                            strict);
            destroyKeys(
                    keys);
            return decoded;
        } catch (RuntimeException failure) {
            destroyKeys(
                    keys);
            throw failure;
        }
    }

    static void validateEnvelope(
            IdsSocketPairCodec.ProtobufMessage envelope) {
        if (envelope == null
                || envelope.protobufType
                != PROTOBUF_TYPE_USER_DEFAULTS
                || envelope.response) {
            throw new IllegalArgumentException(
                    "PairedSync IDS envelope is invalid");
        }
        UserDefaultsMessage decoded =
                decode(
                        envelope.payload);
        decoded.destroy();
    }

    static boolean isService(
            String topic) {
        return PREFERRED_SERVICE.equals(
                topic)
                || FALLBACK_SERVICE.equals(
                topic);
    }

    static boolean isInboundUserDefaults(
            int protobufType) {
        return protobufType == PROTOBUF_TYPE_USER_DEFAULTS
                || protobufType == PROTOBUF_TYPE_USER_DEFAULT_BACKUP;
    }

    public static volatile java.util.function.Consumer<String> diagnosticLogger = null;

    /**
     * Best-effort summary of an inbound user-defaults payload for the run
     * log. Never throws and never includes value bytes: only the domain,
     * key names, value sizes and the raw timestamp bits are reported.
     */
    static String summarizeForLog(
            byte[] payload) {
        if (payload == null) {
            return "payload=null";
        }
        StringBuilder summary =
                new StringBuilder(
                        "payloadBytes=");
        summary.append(
                payload.length);
        try {
            Reader reader =
                    new Reader(
                            payload);
            Long timestampBits = null;
            String domain = null;
            int keyCount = 0;
            List<String> keyNames =
                    new ArrayList<>();
            while (reader.hasRemaining()) {
                int tag =
                        reader.readTag();
                int field =
                        tag >>> 3;
                if (field == 1) {
                    timestampBits =
                            reader.readFixed64();
                } else if (field == 2) {
                    domain =
                            reader.readString();
                } else if (field == 3) {
                    keyCount++;
                    byte[] nested =
                            reader.readBytes();
                    try {
                        keyNames.add(
                                summarizeKeyForLog(
                                        nested));
                    } finally {
                        wipe(
                                nested);
                    }
                } else {
                    reader.skipField(
                            tag);
                }
            }
            if (timestampBits != null) {
                double timestamp =
                        Double.longBitsToDouble(
                                timestampBits);
                summary.append(
                        " timestampBits=0x")
                        .append(
                                Long.toHexString(
                                        timestampBits))
                        .append(
                                Double.isFinite(
                                        timestamp)
                                        ? " finite"
                                        : " NON-FINITE");
            }
            summary.append(
                    " domain=")
                    .append(
                            domain == null
                                    ? "<absent>"
                                    : domain)
                    .append(
                            " keys=")
                    .append(
                            keyCount)
                    .append(
                            keyNames);
            return summary.toString();
        } catch (RuntimeException undecodable) {
            summary.append(
                    " unparseable: ")
                    .append(
                            undecodable.getMessage());
            return summary.toString();
        }
    }

    private static String summarizeKeyForLog(
            byte[] nested) {
        try {
            Reader reader =
                    new Reader(
                            nested);
            String name = null;
            int valueSize = -1;
            while (reader.hasRemaining()) {
                int tag =
                        reader.readTag();
                int field =
                        tag >>> 3;
                if (field == 1) {
                    name =
                            reader.readString();
                } else if (field == 2) {
                    byte[] value =
                            reader.readBytes();
                    try {
                        valueSize =
                                value.length;
                    } finally {
                        wipe(
                                value);
                    }
                } else {
                    reader.skipField(
                            tag);
                }
            }
            return (name == null
                    ? "<unnamed>"
                    : name)
                    + "(" + valueSize + "B)";
        } catch (RuntimeException undecodable) {
            return "<unparseable key>";
        }
    }

    private static byte[] encodeKey(
            UserDefaultsKey key) {
        key.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeStringField(
                output,
                1,
                key.key);
        if (key.value != null) {
            writeBytesField(
                    output,
                    2,
                    key.value);
        }
        if (key.twoWaySync != null) {
            writeTag(
                    output,
                    3,
                    WIRE_VARINT);
            writeVarint(
                    output,
                    key.twoWaySync
                            ? 1
                            : 0);
        }
        if (key.timestamp != null) {
            writeTag(
                    output,
                    4,
                    WIRE_FIXED64);
            writeFixed64(
                    output,
                    Double.doubleToRawLongBits(
                            key.timestamp));
        }
        return requireSize(
                output.toByteArray());
    }

    private static UserDefaultsKey decodeKey(
            byte[] payload) {
        Reader reader =
                new Reader(
                        requirePayload(
                                payload));
        String key = null;
        byte[] value = null;
        Boolean twoWaySync = null;
        Double timestamp = null;
        try {
            while (reader.hasRemaining()) {
                int tag =
                        reader.readTag();
                int field =
                        tag >>> 3;
                if (field == 1) {
                    rejectDuplicate(
                            key != null,
                            "key name");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    key =
                            reader.readString();
                } else if (field == 2) {
                    rejectDuplicate(
                            value != null,
                            "key value");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    value =
                            reader.readBytes();
                } else if (field == 3) {
                    rejectDuplicate(
                            twoWaySync != null,
                            "two-way flag");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    long encoded =
                            reader.readVarint();
                    if (encoded > 1) {
                        throw new IllegalArgumentException(
                                "PairedSync two-way flag is not boolean");
                    }
                    twoWaySync =
                            encoded != 0;
                } else if (field == 4) {
                    rejectDuplicate(
                            timestamp != null,
                            "key timestamp");
                    requireWire(
                            tag,
                            WIRE_FIXED64);
                    timestamp =
                            Double.longBitsToDouble(
                                    reader.readFixed64());
                } else {
                    reader.skipField(
                            tag);
                }
            }
            if (key == null) {
                throw new IllegalArgumentException(
                        "PairedSync key name is absent");
            }
            return new UserDefaultsKey(
                    key,
                    value,
                    twoWaySync,
                    timestamp);
        } finally {
            wipe(
                    value);
        }
    }

    private static void writeStringField(
            ByteArrayOutputStream output,
            int field,
            String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "PairedSync string field is absent");
        }
        byte[] encoded =
                value.getBytes(
                        StandardCharsets.UTF_8);
        try {
            if (encoded.length > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException(
                        "PairedSync string field is too long");
            }
            writeBytesField(
                    output,
                    field,
                    encoded);
        } finally {
            wipe(
                    encoded);
        }
    }

    private static void writeBytesField(
            ByteArrayOutputStream output,
            int field,
            byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "PairedSync bytes field is absent");
        }
        writeTag(
                output,
                field,
                WIRE_LENGTH_DELIMITED);
        writeVarint(
                output,
                value.length);
        output.writeBytes(
                value);
    }

    private static void writeTag(
            ByteArrayOutputStream output,
            int field,
            int wire) {
        writeVarint(
                output,
                ((long) field << 3)
                        | wire);
    }

    private static void writeFixed64(
            ByteArrayOutputStream output,
            long value) {
        for (int shift = 0;
                shift < 64;
                shift += 8) {
            output.write(
                    (int) (value >>> shift)
                            & 0xff);
        }
    }

    private static void writeVarint(
            ByteArrayOutputStream output,
            long value) {
        do {
            int next =
                    (int) value & 0x7f;
            value >>>= 7;
            if (value != 0) {
                next |= 0x80;
            }
            output.write(
                    next);
        } while (value != 0);
    }

    private static byte[] requirePayload(
            byte[] payload) {
        if (payload == null
                || payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "PairedSync protobuf payload length is invalid");
        }
        return payload;
    }

    private static byte[] requireSize(
            byte[] payload) {
        requirePayload(
                payload);
        return payload;
    }

    private static void requireWire(
            int tag,
            int expected) {
        if ((tag & 7) != expected) {
            throw new IllegalArgumentException(
                    "PairedSync protobuf wire type mismatch");
        }
    }

    private static void rejectDuplicate(
            boolean duplicate,
            String label) {
        if (duplicate) {
            throw new IllegalArgumentException(
                    "Duplicate PairedSync "
                            + label);
        }
    }

    private static void destroyKeys(
            List<UserDefaultsKey> keys) {
        for (UserDefaultsKey key :
                keys) {
            key.destroy();
        }
        keys.clear();
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static final class UserDefaultsMessage {
        final double timestamp;
        final String domain;
        private final List<UserDefaultsKey> keys;
        private final boolean strict;
        private boolean destroyed;

        UserDefaultsMessage(
                double timestamp,
                String domain,
                List<UserDefaultsKey> keys) {
            this(
                    timestamp,
                    domain,
                    keys,
                    true);
        }

        UserDefaultsMessage(
                double timestamp,
                String domain,
                List<UserDefaultsKey> keys,
                boolean strict) {
            this.timestamp = timestamp;
            this.domain = domain;
            this.strict = strict;
            if (keys == null) {
                this.keys = null;
            } else {
                this.keys =
                        new ArrayList<>(
                                keys.size());
                for (UserDefaultsKey key :
                        keys) {
                    this.keys.add(
                            key == null
                                    ? null
                                    : key.copy());
                }
            }
            requireValid();
        }

        List<UserDefaultsKey> keys() {
            requireValid();
            List<UserDefaultsKey> result =
                    new ArrayList<>(
                            keys.size());
            for (UserDefaultsKey key :
                    keys) {
                result.add(
                        key.copy());
            }
            return Collections.unmodifiableList(
                    result);
        }

        boolean isInitialSyncCompletion() {
            requireValid();
            if (!DOMAIN.equals(
                    domain)
                    || keys.size() != 2) {
                return false;
            }
            Map<String, Object> watchState = null;
            Map<String, Object> clientState = null;
            for (UserDefaultsKey key :
                    keys) {
                if (key.value == null
                        || key.twoWaySync != null
                        || key.timestamp != null) {
                    return false;
                }
                if (WATCH_SYNC_STATE_KEY.equals(
                        key.key)) {
                    watchState =
                            BinaryPropertyListCodec.decodeDictionary(
                                    key.value);
                } else if (WATCH_SYNC_CLIENT_STATE_KEY.equals(
                        key.key)) {
                    clientState =
                            BinaryPropertyListCodec.decodeDictionary(
                                    key.value);
                } else {
                    return false;
                }
            }
            return expectedWatchState().equals(
                    watchState)
                    && expectedClientState().equals(
                    clientState);
        }

        int protobufType() {
            return PROTOBUF_TYPE_USER_DEFAULTS;
        }

        boolean response() {
            return false;
        }

        void requireValid() {
            if (destroyed
                    || !Double.isFinite(
                    timestamp)
                    || domain == null
                    || domain.isEmpty()
                    || keys == null
                    || keys.isEmpty()
                    || keys.size() > MAX_KEYS) {
                throw new IllegalArgumentException(
                        "PairedSync user-defaults message is invalid");
            }
            if (!strict) {
                for (UserDefaultsKey key :
                        keys) {
                    if (key == null) {
                        throw new IllegalArgumentException(
                                "PairedSync key is absent");
                    }
                    key.requireValid();
                }
                return;
            }
            if (!DOMAIN.equals(
                    domain)) {
                throw new IllegalArgumentException(
                        "PairedSync user-defaults message is invalid");
            }
            List<String> names =
                    new ArrayList<>();
            for (UserDefaultsKey key :
                    keys) {
                if (key == null) {
                    throw new IllegalArgumentException(
                            "PairedSync key is absent");
                }
                key.requireValid();
                if ((!WATCH_SYNC_STATE_KEY.equals(
                        key.key)
                        && !WATCH_SYNC_CLIENT_STATE_KEY.equals(
                        key.key))
                        || names.contains(
                        key.key)) {
                    throw new IllegalArgumentException(
                            "PairedSync key is not permitted");
                }
                names.add(
                        key.key);
            }
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            if (keys != null) {
                destroyKeys(
                        keys);
            }
        }
    }

    static final class UserDefaultsKey {
        final String key;
        private byte[] value;
        final Boolean twoWaySync;
        final Double timestamp;
        private boolean destroyed;

        UserDefaultsKey(
                String key,
                byte[] value,
                Boolean twoWaySync,
                Double timestamp) {
            this.key = key;
            this.value =
                    value == null
                            ? null
                            : value.clone();
            this.twoWaySync =
                    twoWaySync;
            this.timestamp =
                    timestamp;
            requireValid();
        }

        byte[] value() {
            requireValid();
            return value == null
                    ? null
                    : value.clone();
        }

        private UserDefaultsKey copy() {
            requireValid();
            return new UserDefaultsKey(
                    key,
                    value,
                    twoWaySync,
                    timestamp);
        }

        private void requireValid() {
            if (destroyed
                    || key == null
                    || key.isEmpty()
                    || key.getBytes(
                    StandardCharsets.UTF_8).length
                    > MAX_STRING_LENGTH
                    || value != null
                    && value.length > MAX_PAYLOAD_LENGTH
                    || timestamp != null
                    && !Double.isFinite(
                    timestamp)) {
                throw new IllegalArgumentException(
                        "PairedSync user-defaults key is invalid");
            }
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            wipe(
                    value);
            value = null;
        }
    }

    private static Map<String, Object> expectedWatchState() {
        LinkedHashMap<String, Object> expected =
                new LinkedHashMap<>();
        expected.put(
                "version",
                1L);
        expected.put(
                "syncProgressState",
                3L);
        expected.put(
                "globalProgress",
                100L);
        return expected;
    }

    private static Map<String, Object> expectedClientState() {
        LinkedHashMap<String, Object> expected =
                new LinkedHashMap<>();
        expected.put(
                "version",
                1L);
        expected.put(
                "syncProgressState",
                3L);
        expected.put(
                "syncSessionType",
                0L);
        expected.put(
                "migrationSync",
                Boolean.FALSE);
        return expected;
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        private Reader(
                byte[] data) {
            this.data = data;
        }

        private boolean hasRemaining() {
            return offset < data.length;
        }

        private int readTag() {
            long tag =
                    readVarint();
            if (tag == 0
                    || tag > Integer.MAX_VALUE
                    || (tag >>> 3) == 0) {
                throw new IllegalArgumentException(
                        "Invalid PairedSync protobuf tag");
            }
            return (int) tag;
        }

        private long readVarint() {
            long value = 0;
            for (int shift = 0;
                    shift < 64;
                    shift += 7) {
                if (offset >= data.length) {
                    throw new IllegalArgumentException(
                            "PairedSync protobuf varint is truncated");
                }
                int next =
                        data[offset++] & 0xff;
                if (shift == 63
                        && (next & 0xfe) != 0) {
                    throw new IllegalArgumentException(
                            "PairedSync protobuf varint overflows uint64");
                }
                value |=
                        (long) (next & 0x7f)
                                << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException(
                    "PairedSync protobuf varint is unterminated");
        }

        private long readFixed64() {
            requireRemaining(
                    8);
            long value = 0;
            for (int shift = 0;
                    shift < 64;
                    shift += 8) {
                value |=
                        (long) (data[offset++] & 0xff)
                                << shift;
            }
            return value;
        }

        private byte[] readBytes() {
            long length =
                    readVarint();
            if (length > MAX_PAYLOAD_LENGTH
                    || length > data.length - offset) {
                throw new IllegalArgumentException(
                        "PairedSync bytes field is truncated");
            }
            byte[] value =
                    Arrays.copyOfRange(
                            data,
                            offset,
                            offset + (int) length);
            offset +=
                    (int) length;
            return value;
        }

        private String readString() {
            byte[] encoded =
                    readBytes();
            try {
                if (encoded.length > MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException(
                            "PairedSync string is too long");
                }
                String value =
                        new String(
                                encoded,
                                StandardCharsets.UTF_8);
                if (!Arrays.equals(
                        encoded,
                        value.getBytes(
                                StandardCharsets.UTF_8))) {
                    throw new IllegalArgumentException(
                            "PairedSync string is not canonical UTF-8");
                }
                return value;
            } finally {
                wipe(
                        encoded);
            }
        }

        private void skipField(
                int tag) {
            switch (tag & 7) {
                case WIRE_VARINT -> readVarint();
                case WIRE_FIXED64 -> {
                    requireRemaining(
                            8);
                    offset += 8;
                }
                case WIRE_LENGTH_DELIMITED -> {
                    long length =
                            readVarint();
                    if (length > MAX_PAYLOAD_LENGTH
                            || length > data.length - offset) {
                        throw new IllegalArgumentException(
                                "PairedSync unknown field is truncated");
                    }
                    offset +=
                            (int) length;
                }
                case WIRE_FIXED32 -> {
                    requireRemaining(
                            4);
                    offset += 4;
                }
                default -> throw new IllegalArgumentException(
                        "Unsupported PairedSync protobuf wire type");
            }
        }

        private void requireRemaining(
                int length) {
            if (length < 0
                    || offset > data.length - length) {
                throw new IllegalArgumentException(
                        "PairedSync protobuf field is truncated");
            }
        }
    }
}
