package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Read-only decoded A-over-C plaintext. HealthDaemon23S303; never a sample decoder. */
final class NativeHealthSyncCodec {
    static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_FIELDS = 8192;
    private NativeHealthSyncCodec() { }

    record Identity(int version, UUID persistent, UUID health) { }

    /** Native hk_dataForUUIDBytes uses the sixteen UUID bytes, not a textual UUID. */
    static UUID uuid(byte[] bytes) {
        if(bytes==null || bytes.length!=16) throw new IllegalArgumentException("Invalid Health UUID bytes");
        var buffer=java.nio.ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(),buffer.getLong());
    }

    static String messageName(int id) {
        return switch (id) {
            case 1 -> "Restore";
            case 2 -> "Changes";
            case 3 -> "Authorization";
            case 4 -> "Authorization Complete";
            case 5 -> "Routine";
            case 6 -> "FitnessFriends";
            case 7 -> "Speculative Changes";
            case 8 -> "Start Workout App";
            case 9 -> "Companion UserNotification";
            case 10 -> "Tinker Pairing";
            case 11 -> "Tinker Opt In";
            case 12 -> "Tinker End to End Cloud Sync";
            case 13 -> "Notification Instruction";
            default -> "Unknown";
        };
    }

    static final class Frame implements AutoCloseable {
        final int messageId;
        final Integer priority; // absent on a response, supplied by the request otherwise
        final boolean response;
        private byte[] protobuf;
        Frame(int id, Integer priority, boolean response, byte[] payload) {
            this.messageId = id; this.priority = priority; this.response = response;
            protobuf = payload;
        }
        byte[] protobuf() {
            if (protobuf == null) throw new IllegalStateException("Health frame closed");
            return protobuf.clone();
        }
        Envelope envelope() {
            if (protobuf == null) throw new IllegalStateException("Health frame closed");
            if (messageId != 1 && messageId != 2 && messageId != 7) {
                throw new IllegalArgumentException("This Health ID has a different native PB schema");
            }
            return decodeEnvelope(protobuf);
        }
        NativeHealthChangesCodec.Node authorization() {
            if(protobuf==null) throw new IllegalStateException("Health frame closed");
            NativeHealthChangesCodec.Schema schema;
            if(messageId==3) schema=response ? NativeHealthChangesCodec.Schema.AUTHORIZATION_RESPONSE
                    : NativeHealthChangesCodec.Schema.AUTHORIZATION_REQUEST;
            else if(messageId==4 && !response) schema=NativeHealthChangesCodec.Schema.AUTHORIZATION_COMPLETE;
            else throw new IllegalArgumentException("This Health frame is not a supported authorization record");
            return NativeHealthChangesCodec.decode(schema,protobuf);
        }
        @Override public void close() { wipe(protobuf); protobuf = null; }
    }

    /** DataMessage responseIdentifier, not an IDS Protobuf response flag, selects the header. */
    static Frame decodePlaintext(byte[] data, boolean response) {
        int offset = response ? 2 : 3;
        if (data == null || data.length < offset || data.length > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid Health plaintext size");
        }
        int id = (data[0] & 255) | ((data[1] & 255) << 8);
        Integer priority = response ? null : data[2] & 255;
        return new Frame(id, priority, response, Arrays.copyOfRange(data, offset, data.length));
    }

    static final class Envelope implements AutoCloseable {
        final Integer version; // no assumed protocol version
        private final Map<Integer, byte[]> values;
        Envelope(Integer version, Map<Integer, byte[]> values) {
            this.version = version; this.values = values;
        }
        boolean has(int field) { requireOpen(); return values.containsKey(field); }
        byte[] bytes(int field) {
            requireOpen(); byte[] value = values.get(field);
            return value == null ? null : value.clone();
        }
        int size(int field) { requireOpen(); byte[] value = values.get(field); return value == null ? -1 : value.length; }
        NativeHealthChangesCodec.Node changeSet() {
            requireOpen(); byte[] value=values.get(7);
            return value==null ? null : NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.CHANGE_SET,value);
        }
        NativeHealthChangesCodec.Node status() {
            requireOpen(); byte[] value=values.get(8);
            return value==null ? null : NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.STATUS,value);
        }
        NativeHealthChangesCodec.Node restore() {
            requireOpen(); byte[] value=values.get(9);
            return value==null ? null : NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.ACTIVATION_RESTORE,value);
        }
        /** Structural validation only; matching against a persisted Health store is a separate transaction. */
        Identity requireIdentity() {
            requireOpen();
            if(version==null) throw new IllegalArgumentException("Missing Health protocol version");
            return new Identity(version,uuid(values.get(3)),uuid(values.get(4)));
        }
        private boolean closed;
        private void requireOpen() { if (closed) throw new IllegalStateException("Health envelope closed"); }
        @Override public void close() { if (!closed) values.values().forEach(NativeHealthSyncCodec::wipe); closed = true; }
    }

    /** Fields3/4 are distinct native Health pairing identities; never substitute the Bluetooth pair UUID. */
    static Envelope decodeEnvelope(byte[] data) {
        Reader reader = new Reader(data);
        Integer version = null;
        boolean versionSeen = false;
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        try {
            while (reader.hasNext()) {
                int tag = reader.tag(), field = tag >>> 3;
                if (field == 2) {
                    if ((tag & 7) != 0 || versionSeen) throw new IllegalArgumentException("Invalid Health version field");
                    long value = reader.varint();
                    // Native readFrom assigns int32; accept signed or unsigned int32 representations.
                    if (value != (int) value && (value < 0 || value > 0xffffffffL)) {
                        throw new IllegalArgumentException("Health version exceeds int32");
                    }
                    version = (int) value; versionSeen = true;
                } else if (field == 3 || field == 4 || field >= 7 && field <= 11) {
                    if ((tag & 7) != 2 || values.containsKey(field)) {
                        throw new IllegalArgumentException("Invalid or duplicate Health envelope field");
                    }
                    values.put(field, reader.bytes());
                } else reader.skip(tag);
            }
            return new Envelope(version, values);
        } catch (RuntimeException invalid) { values.values().forEach(NativeHealthSyncCodec::wipe); throw invalid; }
    }

    static final class Budget {
        private int fields;
        void field() { if (++fields > MAX_FIELDS) throw new IllegalArgumentException("Health PB field limit"); }
    }
    static final class Reader {
        final byte[] data;
        final Budget budget;
        int offset;
        Reader(byte[] data) {
            this(data,new Budget());
        }
        Reader(byte[] data, Budget budget) {
            if (data == null || data.length > MAX_BYTES) throw new IllegalArgumentException("Invalid Health PB size");
            this.data = data; this.budget=budget;
        }
        boolean hasNext() { return offset < data.length; }
        int tag() {
            budget.field();
            long tag = varint();
            if (tag <= 0 || tag > 0xffffffffL || (tag >>> 3) == 0) throw new IllegalArgumentException("Invalid Health PB tag");
            return (int) tag;
        }
        long varint() {
            long result = 0;
            for (int index = 0; index < 10; index++) {
                if (offset >= data.length) throw new IllegalArgumentException("Truncated Health PB varint");
                int b = data[offset++] & 255;
                if (index == 9 && (b & 254) != 0) throw new IllegalArgumentException("Overflow Health PB varint");
                result |= (long) (b & 127) << (index * 7);
                if ((b & 128) == 0) return result;
            }
            throw new IllegalArgumentException("Overflow Health PB varint");
        }
        int length() {
            long size = varint();
            if (size < 0 || size > data.length - offset) throw new IllegalArgumentException("Truncated Health PB bytes");
            return (int) size;
        }
        byte[] bytes() { int length = length(); byte[] result = Arrays.copyOfRange(data, offset, offset + length); offset += length; return result; }
        long fixed64() {
            int start=offset; advance(8); long bits=0;
            for(int i=0;i<8;i++) bits |= (long)(data[start+i]&255) << (i*8);
            return bits;
        }
        void skip(int tag) {
            switch (tag & 7) {
                case 0 -> varint();
                case 1 -> advance(8);
                case 2 -> advance(length());
                case 5 -> advance(4);
                default -> throw new IllegalArgumentException("Unsupported Health PB wire type");
            }
        }
        void advance(int size) {
            if (size > data.length - offset) throw new IllegalArgumentException("Truncated Health PB fixed field");
            offset += size;
        }
    }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
}
