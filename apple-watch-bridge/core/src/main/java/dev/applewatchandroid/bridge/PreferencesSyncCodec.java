package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Protocol codec for Apple NanoPreferencesSync:
 * {@code com.apple.private.alloy.preferencessync}.
 *
 * <p>Ordinary user-defaults use Apple reference time and binary plist values.
 * Faces require the separate native collection protocol.</p>
 */
public final class PreferencesSyncCodec {
    public static final String SERVICE =
            IdsApplicationRoute.PREFERENCE_SYNC_SERVICE;
    public static final String PAIRED_SYNC_SERVICE =
            IdsApplicationRoute.PAIRED_SYNC_SERVICE;

    public static final int TYPE_USER_DEFAULT = 0;
    public static final int TYPE_USER_DEFAULT_BACKUP = 2;
    public static final int TYPE_FILE_BACKUP = 3;

    public static final String DOMAIN_CAROUSEL = "com.apple.Carousel";
    public static final String DOMAIN_NANOTIMEKIT = "com.apple.NanoTimeKit";
    public static final String DOMAIN_PREFERENCES = "com.apple.preferences";
    public static final String DOMAIN_TIMERS = "com.apple.mobiletimerd";

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;

    private PreferencesSyncCodec() {
    }

    public static final class PreferenceKey {
        public final String name;
        public final byte[] valueBytes;
        public final String stringValue;
        public final boolean isSet;

        public PreferenceKey(String name, byte[] valueBytes, String stringValue, boolean isSet) {
            this.name = name;
            this.valueBytes = valueBytes;
            this.stringValue = stringValue;
            this.isSet = isSet;
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (name != null) writeStringField(out, 1, name);
            if (valueBytes != null) {
                writeBytesField(out, 2, valueBytes);
            } else if (stringValue != null) {
                writeStringField(out, 2, stringValue);
            }
            writeTag(out, 3, WIRE_VARINT);
            writeVarint(out, isSet ? 1 : 0);
            return out.toByteArray();
        }

        public static PreferenceKey decode(byte[] data) {
            Reader reader = new Reader(data);
            String name = null;
            byte[] valBytes = null;
            String strVal = null;
            boolean set = true;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> name = reader.readString();
                    case 2 -> {
                        valBytes = reader.readBytes();
                        try {
                            strVal = new String(valBytes, StandardCharsets.UTF_8);
                        } catch (Exception ignored) {}
                    }
                    case 3 -> set = reader.readVarint() != 0;
                    default -> reader.skipField(tag);
                }
            }
            return new PreferenceKey(name, valBytes, strVal, set);
        }
    }

    public static final class UserDefaultsMessage {
        public final String domain;
        public final long timestampMs;
        public final List<PreferenceKey> keys;

        public UserDefaultsMessage(String domain, long timestampMs, List<PreferenceKey> keys) {
            this.domain = domain != null ? domain : DOMAIN_CAROUSEL;
            this.timestampMs = timestampMs > 0 ? timestampMs : System.currentTimeMillis();
            this.keys = keys != null ? Collections.unmodifiableList(new ArrayList<>(keys)) : Collections.emptyList();
        }

        public byte[] encode() {
            List<PairedSyncCodec.UserDefaultsKey> nativeKeys = new ArrayList<>();
            for (PreferenceKey key : keys) {
                if (key.stringValue != null) throw new IllegalArgumentException("Preference values must be binary plists");
                nativeKeys.add(new PairedSyncCodec.UserDefaultsKey(key.name, key.valueBytes,
                        key.isSet ? Boolean.TRUE : null, null));
            }
            var nativeMessage = new PairedSyncCodec.UserDefaultsMessage(
                    timestampMs / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS,
                    domain, nativeKeys, false);
            try { return PairedSyncCodec.encode(nativeMessage); }
            finally { nativeMessage.destroy(); nativeKeys.forEach(PairedSyncCodec.UserDefaultsKey::destroy); }
        }

        public static UserDefaultsMessage decode(byte[] data) {
            var message = PairedSyncCodec.decodeInbound(data);
            var nativeKeys = message.keys();
            List<PreferenceKey> list = new ArrayList<>();
            try {
                for (var key : nativeKeys) {
                    list.add(new PreferenceKey(key.key, key.value(), null, Boolean.TRUE.equals(key.twoWaySync)));
                }
                return new UserDefaultsMessage(message.domain,
                        (long) ((message.timestamp + WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS) * 1000), list);
            } finally {
                message.destroy(); nativeKeys.forEach(PairedSyncCodec.UserDefaultsKey::destroy);
            }
        }
    }

    public static final class FileBackupEntry {
        public final String fileUrl;
        public final byte[] fileData;

        public FileBackupEntry(String fileUrl, byte[] fileData) {
            this.fileUrl = fileUrl;
            this.fileData = fileData != null ? fileData : new byte[0];
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (fileUrl != null) writeStringField(out, 1, fileUrl);
            if (fileData != null && fileData.length > 0) writeBytesField(out, 2, fileData);
            return out.toByteArray();
        }

        public static FileBackupEntry decode(byte[] data) {
            Reader reader = new Reader(data);
            String url = null;
            byte[] bytes = new byte[0];

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> url = reader.readString();
                    case 2 -> bytes = reader.readBytes();
                    default -> reader.skipField(tag);
                }
            }
            return new FileBackupEntry(url, bytes);
        }
    }

    public static final class FileBackupMessage {
        public final String domain;
        public final List<FileBackupEntry> entries;

        public FileBackupMessage(String domain, List<FileBackupEntry> entries) {
            this.domain = domain;
            this.entries = entries != null ? Collections.unmodifiableList(new ArrayList<>(entries)) : Collections.emptyList();
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (domain != null) writeStringField(out, 2, domain);
            for (FileBackupEntry e : entries) {
                writeBytesField(out, 3, e.encode());
            }
            return out.toByteArray();
        }

        public static FileBackupMessage decode(byte[] data) {
            Reader reader = new Reader(data);
            String dom = null;
            List<FileBackupEntry> list = new ArrayList<>();

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 2 -> dom = reader.readString();
                    case 3 -> list.add(FileBackupEntry.decode(reader.readBytes()));
                    default -> reader.skipField(tag);
                }
            }
            return new FileBackupMessage(dom, list);
        }
    }

    /**
     * Creates a sync message to change active watch face on Apple Watch.
     */
    public static UserDefaultsMessage createActiveFaceSync(String faceId) {
        throw new UnsupportedOperationException("Face selection requires the native collection protocol");
    }

    /**
     * Creates a sync message for wrist orientation and digital crown placement.
     */
    public static UserDefaultsMessage createWristOrientationSync(boolean isRightWrist, boolean isCrownRight) {
        throw new UnsupportedOperationException("Use explicit RIGHT_WRIST and INVERT_SCREEN native settings");
    }

    /**
     * Creates a sync message for 24-hour time format.
     */
    public static UserDefaultsMessage create24HourTimeSync(boolean is24Hour) {
        List<PreferenceKey> keys = new ArrayList<>();
        keys.add(new PreferenceKey("AppleICUForce24HourTime", BinaryPropertyListCodec.encodeBoolean(is24Hour), null, false));
        keys.add(new PreferenceKey("AppleICUForce12HourTime", BinaryPropertyListCodec.encodeBoolean(!is24Hour), null, false));
        return new UserDefaultsMessage(".GlobalPreferences", System.currentTimeMillis(), keys);
    }

    // --- Protobuf wire encoding/decoding helpers ---

    private static void writeTag(ByteArrayOutputStream output, int field, int wireType) {
        writeVarint(output, ((long) field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        do {
            int next = (int) value & 0x7f;
            value >>>= 7;
            if (value != 0) next |= 0x80;
            output.write(next);
        } while (value != 0);
    }

    private static void writeFixed64(ByteArrayOutputStream output, long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            output.write((int) (value >>> shift) & 0xff);
        }
    }

    private static void writeStringField(ByteArrayOutputStream output, int field, String value) {
        if (value == null) return;
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeBytesField(output, field, encoded);
    }

    private static void writeBytesField(ByteArrayOutputStream output, int field, byte[] value) {
        if (value == null) return;
        writeTag(output, field, WIRE_LENGTH_DELIMITED);
        writeVarint(output, value.length);
        output.writeBytes(value);
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        Reader(byte[] data) {
            this.data = data != null ? data : new byte[0];
            this.offset = 0;
        }

        boolean hasRemaining() {
            return offset < data.length;
        }

        int readTag() {
            return (int) readVarint();
        }

        long readVarint() {
            long value = 0;
            for (int shift = 0; shift < 64 && offset < data.length; shift += 7) {
                int next = data[offset++] & 0xff;
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
            return value;
        }

        long readFixed64() {
            if (data.length - offset < 8) return 0;
            long value = 0;
            for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                value |= (long) (data[offset++] & 0xff) << shift;
            }
            return value;
        }

        byte[] readBytes() {
            long len = readVarint();
            if (len <= 0 || len > data.length - offset) return new byte[0];
            byte[] val = Arrays.copyOfRange(data, offset, offset + (int) len);
            offset += (int) len;
            return val;
        }

        String readString() {
            byte[] b = readBytes();
            return new String(b, StandardCharsets.UTF_8);
        }

        void skipField(int tag) {
            switch (tag & 7) {
                case WIRE_VARINT -> readVarint();
                case WIRE_FIXED64 -> offset = Math.min(data.length, offset + 8);
                case WIRE_LENGTH_DELIMITED -> {
                    long len = readVarint();
                    offset = Math.min(data.length, offset + (int) len);
                }
                default -> offset = data.length;
            }
        }
    }
}
