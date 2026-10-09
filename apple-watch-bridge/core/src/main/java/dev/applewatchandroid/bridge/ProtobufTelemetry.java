package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;

/**
 * Generic read-only protobuf wire-format summarizer for inbound Watch topics
 * that have no dedicated codec (e.g. ct.commcenter.sim). Logs field numbers,
 * wire types, varint values and printable strings; nested messages one level
 * deep; byte blobs as lengths only.
 */
final class ProtobufTelemetry {

    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_FIELDS = 64;
    private static final int MAX_STRING = 160;

    private ProtobufTelemetry() {
    }

    static String describe(byte[] payload) {
        if (payload == null) {
            return "absent";
        }
        if (payload.length == 0) {
            return "empty";
        }
        if (payload.length > MAX_BYTES) {
            return "oversized bytes=" + payload.length;
        }
        StringBuilder out = new StringBuilder(512);
        int[] cursor = new int[]{0};
        int count = fields(payload, cursor, payload.length, out, 0);
        if (cursor[0] != payload.length) {
            out.append(out.length() == 0 ? "" : " ");
            out.append("trailing=").append(payload.length - cursor[0]);
        }
        return "fields=" + count + " {" + out + "}";
    }

    /** Returns number of fields rendered; advances cursor. */
    private static int fields(byte[] data, int[] cursor, int end, StringBuilder out, int depth) {
        int count = 0;
        while (cursor[0] < end && count < MAX_FIELDS) {
            long tag = varint(data, cursor, end);
            if (tag < 0) {
                break;
            }
            int fieldNumber = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (fieldNumber <= 0 || fieldNumber > 0x1FFFFFFF) {
                break;
            }
            if (count > 0) {
                out.append(", ");
            }
            out.append('f').append(fieldNumber);
            switch (wire) {
                case 0 -> {
                    long value = varint(data, cursor, end);
                    if (value < 0) return count;
                    out.append("=varint(").append(value).append(')');
                }
                case 1 -> {
                    if (!skip(data, cursor, end, 8)) return count;
                    out.append("=fixed64");
                }
                case 2 -> {
                    long length = varint(data, cursor, end);
                    if (length < 0 || length > end - cursor[0]) return count;
                    int start = cursor[0];
                    cursor[0] += (int) length;
                    out.append('=').append(blob(data, start, (int) length, depth));
                }
                case 5 -> {
                    if (!skip(data, cursor, end, 4)) return count;
                    out.append("=fixed32");
                }
                default -> {
                    out.append("=wire").append(wire).append("(unsupported)");
                    cursor[0] = end;
                    return count + 1;
                }
            }
            count++;
        }
        return count;
    }

    private static String blob(byte[] data, int start, int length, int depth) {
        if (length == 0) {
            return "empty";
        }
        String text = printable(data, start, length);
        if (text != null) {
            return "string(\"" + text + "\")";
        }
        if (depth < 1 && length <= 512 && length >= 2) {
            int[] nested = new int[]{start};
            StringBuilder inner = new StringBuilder(256);
            int count = fields(data, nested, start + length, inner, depth + 1);
            if (count > 0 && nested[0] == start + length) {
                return "msg{" + inner + "}";
            }
        }
        return "bytes(" + length + ")";
    }

    private static String printable(byte[] data, int start, int length) {
        if (length < 2 || length > MAX_STRING) {
            return null;
        }
        for (int i = start; i < start + length; i++) {
            int b = data[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                return null;
            }
        }
        return new String(data, start, length, StandardCharsets.US_ASCII);
    }

    private static long varint(byte[] data, int[] cursor, int end) {
        long result = 0;
        int shift = 0;
        while (cursor[0] < end && shift < 64) {
            int b = data[cursor[0]++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        return -1;
    }

    private static boolean skip(byte[] data, int[] cursor, int end, int count) {
        if (end - cursor[0] < count) {
            return false;
        }
        cursor[0] += count;
        return true;
    }
}
