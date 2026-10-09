package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded value-only OPACK writer; no object instantiation or reference table. */
final class OpackEncoder {
    static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value, 0); return out.toByteArray();
    }
    private static void write(ByteArrayOutputStream out, Object value, int depth) {
        if (depth > 12) throw new IllegalArgumentException("OPACK nesting too deep");
        if (value == null) out.write(4);
        else if (value instanceof Boolean b) out.write(b ? 1 : 2);
        else if (value instanceof UUID id) {
            out.write(5); append(out, ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
        } else if (value instanceof String text) {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            if (data.length > 65536) throw new IllegalArgumentException("OPACK string too large");
            length(out, data.length, 0x40, 0x61); append(out, data);
        } else if (value instanceof byte[] data) {
            length(out, data.length, 0x70, 0x91); append(out, data);
        } else if (value instanceof Integer || value instanceof Long) {
            long n = ((Number)value).longValue();
            if (n == -1) out.write(7);
            else if (n >= 0 && n <= 39) out.write(8 + (int)n);
            else { int bytes = n >= 0 && n <= 255 ? 1 : n >= 0 && n <= 65535 ? 2 : n >= 0 && n <= 0xffffffffL ? 4 : 8;
                out.write(0x30 + Integer.numberOfTrailingZeros(bytes)); little(out, n, bytes); }
        } else if (value instanceof Map<?, ?> map) {
            if (map.size() > 1024) throw new IllegalArgumentException("OPACK map too large");
            out.write(0xe0 | Math.min(15, map.size()));
            for (var item : map.entrySet()) {
                if (!(item.getKey() instanceof String)) throw new IllegalArgumentException("OPACK map requires string keys");
                write(out, item.getKey(), depth + 1); write(out, item.getValue(), depth + 1);
            }
            if (map.size() >= 15) out.write(3);
        } else if (value instanceof List<?> list) {
            if (list.size() > 1024) throw new IllegalArgumentException("OPACK list too large");
            out.write(0xd0 | Math.min(15, list.size()));
            for (Object item : list) write(out, item, depth + 1);
            if (list.size() >= 15) out.write(3);
        } else throw new IllegalArgumentException("Unsupported OPACK value");
        if (out.size() > OpackDecoder.MAX_BYTES) throw new IllegalArgumentException("OPACK output too large");
    }
    private static void append(ByteArrayOutputStream out, byte[] data) {
        if (data.length > OpackDecoder.MAX_BYTES - out.size()) throw new IllegalArgumentException("OPACK output too large");
        out.write(data, 0, data.length);
    }
    private static void length(ByteArrayOutputStream out, int length, int inline, int extended) {
        if (length <= 32) out.write(inline + length);
        else { int size = length <= 255 ? 1 : length <= 65535 ? 2 : 4;
            out.write(extended + Integer.numberOfTrailingZeros(size)); little(out, length, size); }
    }
    private static void little(ByteArrayOutputStream out, long value, int size) {
        for (int i = 0; i < size; i++) out.write((int)(value >>> (8 * i)) & 255);
    }
}
