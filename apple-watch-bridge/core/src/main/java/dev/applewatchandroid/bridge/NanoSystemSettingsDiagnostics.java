package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Read-only NSS requests verified against Watch7,5 23S303 nanosystemsettingsd. */
final class NanoSystemSettingsDiagnostics {
    static final String TOPIC = "com.apple.private.alloy.systemsettings";
    static final int ABOUT_REQUEST = 5;
    static final int ABOUT_RESPONSE = 6;
    static final int LOGS_REQUEST = 24;
    static final int LOGS_RESPONSE = 25;
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_FIELDS = 8192;
    private static final int MAX_FILES = 1024;
    private final Map<UUID, Integer> pending = new LinkedHashMap<>();
    private List<LogFile> files = List.of();
    private About about;

    static void requireReadOnlyRequest(int type, byte[] payload) {
        if ((type != ABOUT_REQUEST && type != LOGS_REQUEST)
                || payload == null || payload.length != 0) {
            throw new IllegalArgumentException("Only empty NSS About and diagnostic-list requests are supported");
        }
    }

    void queued(int type, String identifier) {
        if (type != ABOUT_REQUEST && type != LOGS_REQUEST) return;
        UUID id = UUID.fromString(identifier);
        if (pending.size() >= 8) pending.remove(pending.keySet().iterator().next());
        pending.put(id, type);
    }

    /** Returns no observation for unsolicited, mismatched or non-response frames. */
    String response(int type, boolean isResponse, String identifier, byte[] payload) {
        if (!isResponse || identifier == null) return null;
        UUID id;
        try { id = UUID.fromString(identifier); }
        catch (IllegalArgumentException invalid) { return null; }
        Integer request = pending.get(id);
        if (request == null || type != request + 1) return null;
        Reader reader = new Reader(payload, new int[]{0});
        String summary;
        if (type == LOGS_RESPONSE) {
            List<LogFile> decoded = new ArrayList<>();
            Map<Integer, Integer> categories = new LinkedHashMap<>();
            while (reader.hasNext()) {
                int tag = reader.tag();
                if (((tag >>> 3) == 1 && tag != 10) || ((tag >>> 3) == 2 && tag != 18)) {
                    throw new IllegalArgumentException("Invalid NSS list field type");
                }
                if (tag == 10) addFile(decoded, new LogFile(-1, "", reader.string()));
                else if (tag == 18) category(reader.message(), decoded, categories);
                else reader.skip(tag);
            }
            files = List.copyOf(decoded);
            long sysdiagnoses = files.stream().filter(f ->
                    f.path.toLowerCase(Locale.ROOT).contains("sysdiagnose")
                            || f.directory.toLowerCase(Locale.ROOT).contains("sysdiagnose")).count();
            summary = "diagnostic list: files=" + files.size() + " categories=" + categories
                    + " sysdiagnoseCandidates=" + sysdiagnoses + "; paths/content logged=false";
        } else {
            Map<Integer, Integer> fields = new LinkedHashMap<>();
            while (reader.hasNext()) {
                int tag = reader.tag();
                fields.merge(tag >>> 3, 1, Integer::sum);
                if (fields.size() > 128) throw new IllegalArgumentException("Too many NSS About fields");
                reader.skip(tag);
            }
            About decoded = decodeAbout(payload);
            about = decoded;
            summary = "About response: fields=" + fields + "; batteryCapacityRaw="
                    + decoded.batteryCapacity + " charging=" + decoded.charging
                    + "; storage/counts/content logged=false";
        }
        pending.remove(id);
        return summary;
    }

    List<LogFile> files() { return files; }
    About about() { return about; }
    void reset() { pending.clear(); files = List.of(); about = null; }

    /** Exact NSSAboutInfoRespMsg fields from 23S303. Null means absent, never zero. */
    record About(Long availableStorageBytes, Long apps, Long songs, Long photos,
                 Long batteryCapacity, Boolean charging, Long purgeableBytes, Long userDeletableBytes) { }

    static About decodeAbout(byte[] payload) {
        Reader reader = new Reader(payload, new int[]{0});
        Long[] numbers = new Long[8];
        Boolean charging = null;
        int seen = 0;
        while (reader.hasNext()) {
            int tag = reader.tag();
            int field = tag >>> 3;
            if (field >= 1 && field <= 8) {
                int bit = 1 << field;
                if ((tag & 7) != 0 || (seen & bit) != 0) {
                    throw new IllegalArgumentException("Invalid or duplicate NSS About field");
                }
                seen |= bit;
                long value = reader.varint();
                if (value < 0 || (field == 6 && value > 1)) {
                    throw new IllegalArgumentException("Invalid NSS About scalar");
                }
                if (field == 6) charging = value == 1;
                else numbers[field - 1] = value;
            } else reader.skip(tag);
        }
        return new About(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4],
                charging, numbers[6], numbers[7]);
    }

    private static void category(Reader reader, List<LogFile> result,
                                 Map<Integer, Integer> categories) {
        int type = -1;
        String directory = "";
        List<String> names = new ArrayList<>();
        while (reader.hasNext()) {
            int tag = reader.tag();
            if (((tag >>> 3) == 1 && tag != 8) || ((tag >>> 3) == 2 && tag != 18)
                    || ((tag >>> 3) == 3 && tag != 26)) {
                throw new IllegalArgumentException("Invalid NSS category field type");
            }
            if (tag == 8) type = (int) reader.varint();
            else if (tag == 18) directory = reader.string();
            else if (tag == 26) {
                if (names.size() >= MAX_FILES) throw new IllegalArgumentException("Too many NSS paths");
                names.add(reader.string());
            } else reader.skip(tag);
        }
        if (categories.size() >= 32 && !categories.containsKey(type)) {
            throw new IllegalArgumentException("Too many NSS categories");
        }
        categories.merge(type, names.size(), Integer::sum);
        for (String name : names) addFile(result, new LogFile(type, directory, name));
    }

    private static void addFile(List<LogFile> files, LogFile file) {
        if (files.size() >= MAX_FILES) throw new IllegalArgumentException("Too many NSS files");
        files.add(file);
    }

    static final class LogFile {
        final int category;
        final String directory;
        final String path;
        LogFile(int category, String directory, String path) {
            this.category = category; this.directory = directory; this.path = path;
        }
        // Deliberately no formatter: filenames can contain private device/app data.
    }

    private static final class Reader {
        final byte[] data;
        final int end;
        final int[] fields;
        int offset;

        Reader(byte[] data, int[] fields) {
            if (data == null || data.length > MAX_BYTES) throw new IllegalArgumentException("Invalid NSS response size");
            this.data = data; this.end = data.length; this.fields = fields;
        }
        Reader(byte[] data, int offset, int end, int[] fields) {
            this.data = data; this.offset = offset; this.end = end; this.fields = fields;
        }
        boolean hasNext() { return offset < end; }
        int tag() {
            if (++fields[0] > MAX_FIELDS) throw new IllegalArgumentException("Too many NSS fields");
            long tag = varint();
            if (tag <= 0 || tag > 0xffffffffL || (tag >>> 3) == 0) {
                throw new IllegalArgumentException("Invalid NSS protobuf tag");
            }
            return (int) tag;
        }
        long varint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                require(1);
                int value = data[offset++] & 255;
                if (shift == 63 && (value & 254) != 0) throw new IllegalArgumentException("NSS varint overflow");
                result |= (long) (value & 127) << shift;
                if ((value & 128) == 0) return result;
            }
            throw new IllegalArgumentException("Invalid NSS varint");
        }
        int length() {
            long length = varint();
            if (length < 0 || length > end - offset) throw new IllegalArgumentException("Truncated NSS field");
            return (int) length;
        }
        Reader message() {
            int length = length();
            Reader result = new Reader(data, offset, offset + length, fields);
            offset += length;
            return result;
        }
        String string() {
            int length = length();
            if (length > 2048) throw new IllegalArgumentException("NSS path too long");
            try {
                String result = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, offset, length)).toString();
                offset += length;
                if (result.chars().anyMatch(c -> c < 32 || c == 127)) {
                    throw new IllegalArgumentException("Invalid NSS path characters");
                }
                return result;
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("Invalid NSS UTF-8", invalid);
            }
        }
        void skip(int tag) {
            switch (tag & 7) {
                case 0 -> varint();
                case 1 -> { require(8); offset += 8; }
                case 2 -> { int length = length(); offset += length; }
                case 5 -> { require(4); offset += 4; }
                default -> throw new IllegalArgumentException("Unsupported NSS wire type");
            }
        }
        void require(int size) {
            if (end - offset < size) throw new IllegalArgumentException("Truncated NSS protobuf");
        }
    }
}
