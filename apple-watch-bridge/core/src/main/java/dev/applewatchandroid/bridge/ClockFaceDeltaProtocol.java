package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact 23S303 CompanionSync V2 delta requests and non-priority response envelopes. */
final class ClockFaceDeltaProtocol {
    static final int MAX_BATCH_BYTES = 512 * 1024;
    static final int MAX_CHANGES = 8;
    static final int MAX_RESPONSE_BYTES = 16 * 1024;
    record Response(int type, ClockFaceSyncHeaderCodec.Header header, String session,
                    long index, boolean accepted, boolean hasError, boolean didRollback) { }
    private ClockFaceDeltaProtocol() { }

    static byte[] start(byte[] header, String session) {
        return start(header, session, 90.0);
    }
    static byte[] start(byte[] header, String session, double timeout) {
        if (!Double.isFinite(timeout) || timeout < 90.0 || timeout > 1800.0) throw invalid();
        ByteArrayOutputStream out = request(ClockFaceSyncAccept.START, header);
        ClockFaceSyncHeaderCodec.integer(out, 2, 0); // delta only, never a reset sync
        ClockFaceSyncHeaderCodec.field(out, 3, sessionBytes(session));
        ClockFaceSyncHeaderCodec.integer(out, 4, 0); // no rollback or restart support claimed
        ClockFaceSyncHeaderCodec.integer(out, 5, 0);
        // Native SYStartSyncSession sends CFAbsoluteTimeGetCurrent() + fullSessionTimeout.
        // The receiver subtracts its current time before creating the session timer.
        fixed(out, 6, ClockFaceSyncHeaderCodec.decode(header).timestamp() + timeout);
        byte[] metadata = NtkFaceChangeEncoder.senderMetadata();
        try { ClockFaceSyncHeaderCodec.field(out, 7, metadata); }
        finally { Arrays.fill(metadata, (byte) 0); }
        return out.toByteArray();
    }

    static byte[] batch(byte[] header, String session, List<byte[]> changes) {
        return batch(header, session, 0, changes);
    }
    static byte[] batch(byte[] header, String session, int index, List<byte[]> changes) {
        if (index < 0 || index > 128) throw invalid();
        if (changes == null || changes.isEmpty() || changes.size() > MAX_CHANGES) throw invalid();
        ByteArrayOutputStream out = request(ClockFaceSyncAccept.BATCH, header);
        ClockFaceSyncHeaderCodec.field(out, 2, sessionBytes(session));
        ClockFaceSyncHeaderCodec.integer(out, 3, index);
        for (byte[] bytes : changes) {
            if (bytes == null || bytes.length > MAX_BATCH_BYTES - out.size() - 8) throw invalid();
            try (var change = ClockFaceSyncFrame.decodeChange(bytes)) {
                if (change.type() != 0 && change.type() != 1 && change.type() != 3
                        && change.type() != 4 && change.type() != 5) throw invalid();
                validateParts(change);
            }
            ClockFaceSyncHeaderCodec.field(out, 4, bytes);
        }
        if (out.size() > MAX_BATCH_BYTES) throw invalid();
        return out.toByteArray();
    }

    static byte[] end(byte[] header, String session) {
        ByteArrayOutputStream out = request(ClockFaceSyncAccept.END, header);
        ClockFaceSyncHeaderCodec.field(out, 2, sessionBytes(session));
        ClockFaceSyncHeaderCodec.integer(out, 4, 0);
        return out.toByteArray();
    }

    static void validateRequest(byte[] packet) {
        if (packet == null || packet.length > MAX_BATCH_BYTES) throw invalid();
        try (var frame = ClockFaceSyncFrame.parse(packet)) {
            var header = ClockFaceSyncHeaderCodec.decode(frame.header);
            if (header.version() != 2 || header.sequence() < 1 || !Long.valueOf(1).equals(header.clocks().get(header.peer()))
                    || !frame.sessionText().startsWith("P") || frame.resetSync || frame.rollback || frame.endHasError) throw invalid();
            sessionBytes(frame.sessionText());
            if (frame.messageId == ClockFaceSyncAccept.START) {
                Value deadline = fields(packet, 3).get(6);
                if (deadline == null || deadline.wire != 1
                        || !Double.isFinite(Double.longBitsToDouble(deadline.integer))
                        || Double.longBitsToDouble(deadline.integer) - header.timestamp() < 90.0
                        || Double.longBitsToDouble(deadline.integer) - header.timestamp() > 1800.0) throw invalid();
                byte[] metadata = NtkFaceChangeEncoder.senderMetadata();
                try { if (!Arrays.equals(metadata, frame.metadata)) throw invalid(); }
                finally { Arrays.fill(metadata, (byte) 0); }
            } else if (frame.messageId == ClockFaceSyncAccept.BATCH) {
                if (frame.batchIndex < 0 || frame.batchIndex > 128 || frame.changes.isEmpty() || frame.changes.size() > MAX_CHANGES) throw invalid();
                for (byte[] bytes : frame.changes) try (var change = ClockFaceSyncFrame.decodeChange(bytes)) {
                    if (change.type() != 0 && change.type() != 1 && change.type() != 3
                            && change.type() != 4 && change.type() != 5) throw invalid();
                    validateParts(change);
                }
            }
        }
    }

    private static void validateParts(NtkSyncMessageCodec.Message change) {
        if (!change.multipart()) return;
        if (change.type() != 0 || change.wideLoadId() == null || change.numberOfParts() < 2 || change.numberOfParts() > 128
                || change.partNumber() < 0 || change.partNumber() >= change.numberOfParts() || change.maxPartSize() != 128 * 1024
                || change.payloadLength() == 0 || change.payloadLength() > change.maxPartSize()
                || change.partNumber() < change.numberOfParts() - 1 && change.payloadLength() != change.maxPartSize()) throw invalid();
    }

    private static ByteArrayOutputStream request(int type, byte[] header) {
        var value = ClockFaceSyncHeaderCodec.decode(header);
        if (value.version() != 2 || value.sequence() < 1 || !Long.valueOf(1).equals(value.clocks().get(value.peer()))) throw invalid();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(type); out.write(0); out.write(0);
        ClockFaceSyncHeaderCodec.field(out, 1, header); return out;
    }

    static Response response(byte[] packet) {
        if (packet == null || packet.length < 4 || packet.length > MAX_RESPONSE_BYTES || packet[1] != 0) throw invalid();
        int type = packet[0] & 255;
        Set<Integer> allowed, required;
        if (type == ClockFaceSyncAccept.START) {
            allowed = Set.of(1, 2, 3, 4, 5, 6, 7); required = Set.of(1, 2, 3);
        } else if (type == ClockFaceSyncAccept.BATCH) {
            allowed = Set.of(1, 2, 3, 4); required = Set.of(1, 2, 3);
        } else if (type == ClockFaceSyncAccept.END) {
            allowed = Set.of(1, 2, 3, 4); required = Set.of(1, 2);
        } else throw invalid();
        Map<Integer, Value> fields = fields(packet, 2);
        if (!fields.keySet().containsAll(required) || !allowed.containsAll(fields.keySet())) throw invalid();
        var header = ClockFaceSyncHeaderCodec.decode(data(fields, 1));
        if (header.version() != 2 || header.sequence() < 1) throw invalid();
        byte[] session = data(fields, 2);
        if (session.length > 128) throw invalid();
        for (byte b : session) if ((b & 255) < 0x20 || (b & 255) > 0x7e) throw invalid();
        String sessionText = new String(session, StandardCharsets.US_ASCII);
        sessionBytes(sessionText);
        boolean accepted = true, rollback = false;
        long index = -1;
        int errorField = type == ClockFaceSyncAccept.END ? 3 : 4;
        boolean error = fields.containsKey(errorField);
        if (error) data(fields, errorField); // even an empty native error means failure
        if (type == ClockFaceSyncAccept.START) {
            accepted = bool(fields, 3);
            if (fields.containsKey(5)) bool(fields, 5);
            if (fields.containsKey(6)) bool(fields, 6);
            if (fields.containsKey(7)) data(fields, 7);
        } else if (type == ClockFaceSyncAccept.BATCH) {
            index = integer(fields, 3); if (index < 0) throw invalid();
        } else if (fields.containsKey(4)) rollback = bool(fields, 4);
        return new Response(type, header, sessionText, index, accepted, error, rollback);
    }

    private static byte[] sessionBytes(String session) {
        if (session == null || !session.matches("[WP][0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}")) throw invalid();
        return session.getBytes(StandardCharsets.US_ASCII);
    }

    private static void fixed(ByteArrayOutputStream out, int field, double value) {
        out.write((field << 3) | 1);
        long bits = Double.doubleToRawLongBits(value);
        for (int i = 0; i < 8; i++) out.write((int) (bits >>> (8 * i)) & 255);
    }

    private record Value(int wire, long integer, byte[] bytes) { }
    private static Map<Integer, Value> fields(byte[] bytes, int offset) {
        Reader reader = new Reader(bytes, offset); Map<Integer, Value> result = new LinkedHashMap<>();
        while (reader.offset < bytes.length) {
            long tag = reader.varint();
            if (tag < 8 || tag > 63 || result.size() >= 7) throw invalid();
            int wire = (int) tag & 7, field = (int) tag >>> 3;
            Value value = switch (wire) {
                case 0 -> new Value(wire, reader.varint(), null);
                case 1 -> new Value(wire, reader.fixed(), null);
                case 2 -> new Value(wire, 0, reader.bytes());
                default -> throw invalid();
            };
            if (result.put(field, value) != null) throw invalid();
        }
        return result;
    }
    private static byte[] data(Map<Integer, Value> fields, int field) {
        Value value = fields.get(field); if (value == null || value.wire != 2) throw invalid(); return value.bytes;
    }
    private static long integer(Map<Integer, Value> fields, int field) {
        Value value = fields.get(field); if (value == null || value.wire != 0) throw invalid(); return value.integer;
    }
    private static boolean bool(Map<Integer, Value> fields, int field) {
        long value = integer(fields, field); if (value != 0 && value != 1) throw invalid(); return value == 1;
    }
    private static final class Reader {
        final byte[] bytes; int offset;
        Reader(byte[] bytes, int offset) { this.bytes = bytes; this.offset = offset; }
        long fixed() {
            if (bytes.length - offset < 8) throw invalid();
            long value = 0;
            for (int i = 0; i < 8; i++) value |= (long) (bytes[offset++] & 255) << (8 * i);
            return value;
        }
        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (offset >= bytes.length) throw invalid();
                int b = bytes[offset++] & 255;
                if (shift == 63 && b > 1) throw invalid();
                value |= (long) (b & 127) << shift;
                if ((b & 128) == 0) return value;
            }
            throw invalid();
        }
        byte[] bytes() {
            long length = varint(); if (length < 0 || length > bytes.length - offset) throw invalid();
            byte[] result = Arrays.copyOfRange(bytes, offset, offset + (int) length); offset += (int) length; return result;
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid native clockface delta envelope"); }
}
