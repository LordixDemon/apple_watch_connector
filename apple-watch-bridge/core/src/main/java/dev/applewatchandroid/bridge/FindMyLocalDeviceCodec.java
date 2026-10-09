package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;

/** Exact 23S303 NanoLeash schema; request and response share their type. */
public final class FindMyLocalDeviceCodec {
    public static final String SERVICE = IdsApplicationRoute.FIND_MY_LOCAL_SERVICE;
    public static final int TYPE_PLAY_SOUND = 1;
    public static final int TYPE_PLAY_SOUND_AND_FLASH = 2;
    static final int MAX_PAYLOAD = 512;
    private FindMyLocalDeviceCodec() { }

    /** Field 1 is a double of Unix seconds, not Apple's 2001 epoch. */
    public record PlaySoundRequest(double unixSeconds, Integer behaviorOverride) {
        public PlaySoundRequest {
            if (!Double.isFinite(unixSeconds) || unixSeconds <= 0
                    || (behaviorOverride != null && (behaviorOverride < 0 || behaviorOverride > 4))) {
                throw invalid();
            }
        }
        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(9); // field 1, fixed64
            long bits = Double.doubleToRawLongBits(unixSeconds);
            for (int i = 0; i < 8; i++) out.write((int) (bits >>> (8 * i)) & 255);
            if (behaviorOverride != null) { out.write(16); out.write(behaviorOverride); }
            return out.toByteArray();
        }
        public static PlaySoundRequest decode(int type, byte[] payload) {
            requireSoundType(type);
            Reader reader = new Reader(payload);
            Double timestamp = null;
            Integer behavior = null;
            while (reader.remaining()) {
                int tag = reader.tag();
                if (tag >>> 3 == 1) {
                    if (tag != 9 || timestamp != null) throw invalid();
                    timestamp = Double.longBitsToDouble(reader.fixed64());
                } else if (tag >>> 3 == 2 && type == TYPE_PLAY_SOUND) {
                    if (tag != 16 || behavior != null) throw invalid();
                    long value = reader.varint();
                    if (value < 0 || value > 4) throw invalid();
                    behavior = (int) value;
                } else reader.skip(tag);
            }
            if (timestamp == null) throw invalid();
            return new PlaySoundRequest(timestamp, behavior);
        }
        boolean freshAt(long unixMs) {
            double age = unixMs / 1000.0 - unixSeconds;
            return age >= -5 && age < 30;
        }
    }

    /** Field 1 is required didPlay. Empty/malformed payload never means success. */
    public record PlaySoundResponse(boolean didPlay) {
        public byte[] encode() { return new byte[]{8, (byte) (didPlay ? 1 : 0)}; }
        public static PlaySoundResponse decode(byte[] payload) {
            Reader reader = new Reader(payload);
            Boolean played = null;
            while (reader.remaining()) {
                int tag = reader.tag();
                if (tag >>> 3 == 1) {
                    if (tag != 8 || played != null) throw invalid();
                    long value = reader.varint();
                    if (value != 0 && value != 1) throw invalid();
                    played = value == 1;
                } else reader.skip(tag);
            }
            if (played == null) throw invalid();
            return new PlaySoundResponse(played);
        }
    }

    static void requireSoundType(int type) {
        if (type != TYPE_PLAY_SOUND && type != TYPE_PLAY_SOUND_AND_FLASH) throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Malformed native Ping payload");
    }
    private static final class Reader {
        final byte[] data;
        int offset, fields;
        Reader(byte[] data) {
            if (data == null || data.length > MAX_PAYLOAD) throw invalid();
            this.data = data;
        }
        boolean remaining() { return offset < data.length; }
        int tag() {
            long value = varint();
            if (++fields > 32 || value < 8 || value > Integer.MAX_VALUE) throw invalid();
            return (int) value;
        }
        long varint() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                if (!remaining()) throw invalid();
                int next = data[offset++] & 255;
                if (i == 9 && (next & 254) != 0) throw invalid();
                value |= (long) (next & 127) << (7 * i);
                if ((next & 128) == 0) return value;
            }
            throw invalid();
        }
        long fixed64() {
            require(8);
            long value = 0;
            for (int i = 0; i < 8; i++) value |= (long) (data[offset++] & 255) << (8 * i);
            return value;
        }
        void require(int length) { if (length < 0 || length > data.length - offset) throw invalid(); }
        void skip(int tag) {
            int length;
            switch (tag & 7) {
                case 0 -> { varint(); return; }
                case 1 -> length = 8;
                case 2 -> {
                    long size = varint();
                    if (size < 0 || size > MAX_PAYLOAD) throw invalid();
                    length = (int) size;
                }
                case 5 -> length = 4;
                default -> throw invalid();
            }
            require(length); offset += length;
        }
    }
}
