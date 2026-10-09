package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Exact 23S303 SYMessageHeader/SYPeer/SYVectorClock/SYClock schema. */
final class ClockFaceSyncHeaderCodec {
    static final long APPLE_EPOCH_MS = 978307200000L;
    static final int FULL_REQUEST = 0x65;
    record Header(UUID peer, UUID generation, long sequence, int version,
                  double timestamp, Map<UUID, Long> clocks) { }
    record FullResponse(Header header, boolean accepted, boolean hasError) { }
    private ClockFaceSyncHeaderCodec() { }

    static byte[] header(UUID peer, UUID generation, long sequence, long unixMs, Map<UUID, Long> clocks) {
        if (peer == null || generation == null || sequence < 1 || unixMs <= APPLE_EPOCH_MS) throw invalid();
        byte[] state = vector(clocks);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fixed(out, 1, (unixMs - APPLE_EPOCH_MS) / 1000.0);
        field(out, 2, peer(peer, generation)); field(out, 3, state);
        integer(out, 4, 2); integer(out, 5, sequence);
        return out.toByteArray();
    }

    static byte[] fullRequest(byte[] header) {
        return fullRequest(header, null);
    }

    static byte[] fullRequest(byte[] header, String unfinishedSession) {
        decode(header);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(FULL_REQUEST); out.write(0); out.write(0); // uint16 ID + priority
        field(out, 1, header);
        // SYResetRequest field2 cancels this synchronization session only.
        // The caller derives it from the pair's durable unfinished receiver.
        if (unfinishedSession != null) field(out, 2, cancelSessionBytes(unfinishedSession));
        return out.toByteArray();
    }

    static Header decodeFullRequest(byte[] packet) {
        if (packet == null || packet.length < 4 || packet.length > 4096
                || (packet[0] & 255) != FULL_REQUEST || packet[1] != 0 || (packet[2] & 255) > 3) throw invalid();
        Map<Integer, Value> f = fields(java.util.Arrays.copyOfRange(packet, 3, packet.length));
        if (!f.keySet().contains(1) || !java.util.Set.of(1, 2).containsAll(f.keySet())) throw invalid();
        if (f.containsKey(2)) cancelSessionBytes(cancelSessionText(data(f, 2)));
        Header header = decode(data(f, 1));
        if (header.version != 2 || header.sequence < 1 || !Long.valueOf(1).equals(header.clocks.get(header.peer))) throw invalid();
        return header;
    }

    static String recoverySession(byte[] packet) {
        decodeFullRequest(packet);
        Map<Integer, Value> values = fields(java.util.Arrays.copyOfRange(packet, 3, packet.length));
        return values.containsKey(2) ? cancelSessionText(data(values, 2)) : null;
    }

    private static String cancelSessionText(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 128) throw invalid();
        for (byte b : bytes) if ((b & 255) < 0x20 || (b & 255) > 0x7e) throw invalid();
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static byte[] cancelSessionBytes(String session) {
        if (session == null || !session.matches("[WP][0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}")) throw invalid();
        return session.getBytes(StandardCharsets.US_ASCII);
    }

    static FullResponse fullResponse(byte[] packet) {
        if (packet == null || packet.length < 4 || packet.length > 4096
                || (packet[0] & 255) != FULL_REQUEST || packet[1] != 0) throw invalid();
        Map<Integer, Value> fields = fields(java.util.Arrays.copyOfRange(packet, 2, packet.length));
        if (!fields.keySet().containsAll(java.util.Set.of(1, 2))
                || !java.util.Set.of(1, 2, 3).containsAll(fields.keySet())) throw invalid();
        long accepted = number(fields, 2);
        if (accepted != 0 && accepted != 1) throw invalid();
        if (fields.containsKey(3)) data(fields, 3);
        return new FullResponse(decode(data(fields, 1)), accepted == 1, fields.containsKey(3));
    }

    static Header decode(byte[] bytes) {
        Map<Integer, Value> f = fields(bytes);
        if (!f.keySet().containsAll(java.util.Set.of(1, 2, 3, 4, 5))
                || !java.util.Set.of(1, 2, 3, 4, 5, 6).containsAll(f.keySet())) throw invalid();
        double time = real(f, 1);
        if (!Double.isFinite(time) || time <= 0) throw invalid();
        if (f.containsKey(6) && (!Double.isFinite(real(f, 6)) || real(f, 6) < time)) throw invalid();
        Map<Integer, Value> sender = fields(data(f, 2));
        if (!sender.keySet().equals(java.util.Set.of(1, 2))) throw invalid();
        long version = number(f, 4), sequence = number(f, 5);
        if (version < 1 || version > 2 || sequence < 0) throw invalid();
        return new Header(uuid(data(sender, 1)), uuid(data(sender, 2)), sequence, (int) version,
                time, decodeVector(data(f, 3)));
    }

    private static byte[] peer(UUID id, UUID generation) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, 1, id.toString().toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        if (generation != null) field(out, 2, generation.toString().toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] vector(Map<UUID, Long> clocks) {
        if (clocks == null || clocks.size() > 32) throw invalid();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (var entry : clocks.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0) throw invalid();
            ByteArrayOutputStream clock = new ByteArrayOutputStream();
            field(clock, 1, peer(entry.getKey(), null)); integer(clock, 2, entry.getValue());
            field(out, 1, clock.toByteArray());
        }
        return out.toByteArray();
    }

    private static Map<UUID, Long> decodeVector(byte[] bytes) {
        Reader reader = new Reader(bytes);
        Map<UUID, Long> clocks = new LinkedHashMap<>();
        while (reader.remaining()) {
            if (reader.tag() != 10 || clocks.size() >= 32) throw invalid();
            Map<Integer, Value> clock = fields(reader.data());
            if (!clock.keySet().equals(java.util.Set.of(1, 2))) throw invalid();
            Map<Integer, Value> p = fields(data(clock, 1));
            if (!p.keySet().contains(1) || !java.util.Set.of(1, 2).containsAll(p.keySet())) throw invalid();
            UUID id = uuid(data(p, 1));
            if (p.containsKey(2)) uuid(data(p, 2));
            long version = number(clock, 2);
            if (version < 0 || clocks.put(id, version) != null) throw invalid();
        }
        return java.util.Collections.unmodifiableMap(clocks);
    }

    private static UUID uuid(byte[] bytes) {
        if (bytes.length != 36) throw invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return UUID.fromString(NtkFacePayloadCodec.uuid(text));
        } catch (java.nio.charset.CharacterCodingException failure) { throw invalid(); }
    }
    static void field(ByteArrayOutputStream out, int field, byte[] bytes) {
        varint(out, (field << 3) | 2); varint(out, bytes.length); out.write(bytes, 0, bytes.length);
    }
    static void integer(ByteArrayOutputStream out, int field, long value) {
        varint(out, field << 3); varint(out, value);
    }
    private static void fixed(ByteArrayOutputStream out, int field, double value) {
        varint(out, (field << 3) | 1);
        long bits = Double.doubleToRawLongBits(value);
        for (int i = 0; i < 8; i++) out.write((int) (bits >>> (i * 8)) & 255);
    }
    private static void varint(ByteArrayOutputStream out, long value) {
        do { int next = (int) (value & 127); value >>>= 7; out.write(value == 0 ? next : next | 128); } while (value != 0);
    }
    private record Value(int wire, byte[] bytes, long value) { }
    private static Map<Integer, Value> fields(byte[] bytes) {
        Reader reader = new Reader(bytes);
        Map<Integer, Value> fields = new LinkedHashMap<>();
        while (reader.remaining()) {
            int tag = reader.tag(), wire = tag & 7;
            Value value = switch (wire) {
                case 0 -> new Value(wire, null, reader.varint());
                case 1 -> new Value(wire, null, reader.fixed());
                case 2 -> new Value(wire, reader.data(), 0);
                default -> throw invalid();
            };
            if (fields.put(tag >>> 3, value) != null) throw invalid();
        }
        return fields;
    }
    private static byte[] data(Map<Integer, Value> fields, int field) {
        Value value = fields.get(field); if (value == null || value.wire != 2) throw invalid(); return value.bytes;
    }
    private static long number(Map<Integer, Value> fields, int field) {
        Value value = fields.get(field); if (value == null || value.wire != 0) throw invalid(); return value.value;
    }
    private static double real(Map<Integer, Value> fields, int field) {
        Value value = fields.get(field); if (value == null || value.wire != 1) throw invalid(); return Double.longBitsToDouble(value.value);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid native clockface sync header/request response"); }
    private static final class Reader {
        final byte[] bytes; int offset, count;
        Reader(byte[] bytes) { if (bytes == null || bytes.length > 4096) throw invalid(); this.bytes = bytes; }
        boolean remaining() { return offset < bytes.length; }
        int tag() { long tag = varint(); if (++count > 32 || tag < 8 || tag > Integer.MAX_VALUE) throw invalid(); return (int) tag; }
        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (!remaining()) throw invalid();
                int next = bytes[offset++] & 255;
                if (shift == 63 && next > 1) throw invalid();
                value |= (long) (next & 127) << shift;
                if ((next & 128) == 0) return value;
            }
            throw invalid();
        }
        byte[] data() {
            long length = varint(); if (length < 0 || length > bytes.length - offset) throw invalid();
            byte[] data = java.util.Arrays.copyOfRange(bytes, offset, offset + (int) length); offset += (int) length; return data;
        }
        long fixed() {
            if (bytes.length - offset < 8) throw invalid();
            long value = 0; for (int i = 0; i < 8; i++) value |= (long) (bytes[offset++] & 255) << (i * 8); return value;
        }
    }
}
