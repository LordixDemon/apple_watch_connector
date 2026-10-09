package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded CompanionSync DATA request; SYSyncBatch.changes is field 4. */
final class ClockFaceSyncFrame implements AutoCloseable {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    final int messageId;
    final byte[] header;
    final byte[] sessionId;
    final byte[] index;
    final byte[] metadata;
    final List<byte[]> changes;
    final long batchIndex;
    final boolean resetSync;
    final boolean rollback;
    final boolean endHasError;

    private ClockFaceSyncFrame(int messageId, byte[] header, byte[] sessionId,
                              byte[] index, byte[] metadata, List<byte[]> changes,
                              long batchIndex, boolean resetSync, boolean rollback, boolean endHasError) {
        this.messageId = messageId;
        this.header = header;
        this.sessionId = sessionId;
        this.index = index;
        this.metadata = metadata;
        this.changes = changes;
        this.batchIndex = batchIndex;
        this.resetSync = resetSync;
        this.rollback = rollback;
        this.endHasError = endHasError;
    }

    static ClockFaceSyncFrame parse(byte[] request) {
        if (request == null || request.length < 4 || request.length > MAX_BYTES
                || request[1] != 0 || (request[2] & 0xff) > 3) throw invalid("frame");
        int type = request[0] & 0xff;
        if (type != ClockFaceSyncAccept.START && type != ClockFaceSyncAccept.BATCH
                && type != ClockFaceSyncAccept.END) throw invalid("message id");
        Reader reader = new Reader(request, 3);
        byte[] header = null, session = null, index = null, metadata = null;
        long batchIndex = -1;
        boolean reset = false, rollback = false, error = false;
        List<byte[]> changes = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        while (reader.hasNext()) {
            int tag = reader.tag();
            int field = tag >>> 3, wire = tag & 7;
            boolean repeated = type == ClockFaceSyncAccept.BATCH && field == 4;
            if (!repeated && !seen.add(field)) throw invalid("duplicate field");
            if (repeated && changes.size() >= 256) throw invalid("change count");
            if (field == 1) {
                reader.requireWire(wire, 2); header = reader.bytes();
            } else if (field == (type == ClockFaceSyncAccept.START ? 3 : 2)) {
                reader.requireWire(wire, 2); session = reader.bytes();
            } else if (type == ClockFaceSyncAccept.BATCH && field == 3) {
                reader.requireWire(wire, 0);
                int start = reader.offset;
                long value = reader.varint();
                if (value < 0) throw invalid("batch index");
                batchIndex = value;
                index = Arrays.copyOfRange(request, start, reader.offset);
            } else if (repeated) {
                reader.requireWire(wire, 2); changes.add(reader.bytes());
            } else if (type == ClockFaceSyncAccept.START && field == 7) {
                reader.requireWire(wire, 2); metadata = reader.bytes();
            } else if (type == ClockFaceSyncAccept.START && (field == 2 || field == 4 || field == 5)
                    || type == ClockFaceSyncAccept.END && field == 4) {
                reader.requireWire(wire, 0);
                long value = reader.varint();
                if (value != 0 && value != 1) throw invalid("boolean");
                if (field == 2) reset = value == 1;
                if (type == ClockFaceSyncAccept.END) rollback = value == 1;
            } else if (type == ClockFaceSyncAccept.END && field == 3) {
                reader.requireWire(wire, 2); reader.bytes(); error = true;
            } else reader.skip(wire);
        }
        if (header == null || header.length == 0 || header.length > 4096
                || session == null || session.length == 0 || session.length > 128
                || (type == ClockFaceSyncAccept.BATCH && index == null)) throw invalid("required field");
        text(session);
        return new ClockFaceSyncFrame(type, header, session, index, metadata, changes,
                batchIndex, reset, rollback, error);
    }

    static NtkSyncMessageCodec.Message decodeChange(byte[] change) {
        Reader reader = new Reader(change, 0);
        Set<Integer> seen = new HashSet<>();
        Long type = null;
        byte[] objectId = null, archive = null;
        while (reader.hasNext()) {
            int tag = reader.tag(), field = tag >>> 3, wire = tag & 7;
            if (!seen.add(field)) throw invalid("duplicate change field");
            if (field == 1 || field == 3) {
                reader.requireWire(wire, 0);
                long value = reader.varint();
                if (field == 1) type = value;
            } else if (field == 2 || field == 4 || field == 5) {
                reader.requireWire(wire, 2);
                byte[] value = reader.bytes();
                if (field == 2) objectId = value;
                else if (field == 4) archive = value;
                else text(value);
            } else reader.skip(wire);
        }
        if (type == null || type < 0 || type > 2 || objectId == null || objectId.length == 0
                || objectId.length > 4096 || archive == null || archive.length == 0) {
            throw invalid("SYChange");
        }
        text(objectId);
        try {
            NtkSyncMessageCodec.Message message = NtkSyncMessageCodec.decode(archive);
            long expectedType = message.type() == 0 ? 0
                    : message.type() == 3 || message.type() == 7 ? 2 : 1;
            if (type != expectedType) {
                message.close();
                throw invalid("SYChange operation mismatch");
            }
            return message;
        }
        finally { Arrays.fill(archive, (byte) 0); }
    }

    String sessionText() { return text(sessionId); }

    @Override public void close() {
        Arrays.fill(header, (byte) 0);
        Arrays.fill(sessionId, (byte) 0);
        if (index != null) Arrays.fill(index, (byte) 0);
        if (metadata != null) Arrays.fill(metadata, (byte) 0);
        for (byte[] change : changes) Arrays.fill(change, (byte) 0);
    }

    private static String text(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.indexOf('\0') >= 0) throw invalid("text");
            return value;
        } catch (CharacterCodingException failure) { throw invalid("UTF-8"); }
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid clockface sync " + field);
    }

    private static final class Reader {
        private final byte[] bytes;
        private int offset;
        Reader(byte[] bytes, int offset) { this.bytes = bytes; this.offset = offset; }
        boolean hasNext() { return offset < bytes.length; }
        int tag() {
            long value = varint();
            if (value < 8 || value > 0xffff_ffffL || value >>> 3 > 536870911) throw invalid("tag");
            return (int) value;
        }
        void requireWire(int actual, int expected) {
            if (actual != expected) throw invalid("wire type");
        }
        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (offset >= bytes.length) throw invalid("truncated varint");
                int next = bytes[offset++] & 0xff;
                if (shift == 63 && next > 1) throw invalid("varint overflow");
                value |= (long) (next & 127) << shift;
                if ((next & 128) == 0) return value;
            }
            throw invalid("unterminated varint");
        }
        byte[] bytes() {
            long length = varint();
            if (length < 0 || length > bytes.length - offset) throw invalid("length");
            byte[] result = Arrays.copyOfRange(bytes, offset, offset + (int) length);
            offset += (int) length;
            return result;
        }
        void skip(int wire) {
            if (wire == 0) varint();
            else if (wire == 2) {
                long length = varint();
                if (length < 0 || length > bytes.length - offset) throw invalid("length");
                offset += (int) length;
            } else if (wire == 1 || wire == 5) {
                int length = wire == 1 ? 8 : 4;
                if (length > bytes.length - offset) throw invalid("fixed value");
                offset += length;
            } else throw invalid("wire type");
        }
    }
}
