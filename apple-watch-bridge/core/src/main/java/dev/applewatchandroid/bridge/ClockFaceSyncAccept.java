package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;

/**
 * CompanionSync replies on {@code clockface.sync}.
 *
 * <p>A Watch request is {@code uint16 messageId + priority + protobuf}.
 * The peer response is {@code uint16 messageId + protobuf} with no priority
 * byte. Live 17:08: echoing a start is a second session and the Watch
 * cancels both.</p>
 */
final class ClockFaceSyncAccept {
    static final int START = 0x66;
    static final int BATCH = 0x67;
    static final int END = 0x69;

    private ClockFaceSyncAccept() {
    }

    static byte[] accept(byte[] start) {
        return reply(start);
    }

    /**
     * Peer-response body for a start, a sync batch, or an end.
     * Returns null when the payload is not one of those requests.
     */
    static byte[] reply(byte[] request) {
        return reply(request, null);
    }

    static byte[] reply(byte[] request, byte[] localHeader) {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(request)) {
            if (localHeader != null) ClockFaceSyncHeaderCodec.decode(localHeader);
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    frame.header.length + frame.sessionId.length + 16);
            out.write(frame.messageId);
            out.write(0);
            writeBytes(out, 1, localHeader == null ? frame.header : localHeader);
            writeBytes(out, 2, frame.sessionId);
            if (frame.messageId == START) {
                writeVarintField(out, 3, 1);
                writeVarintField(out, 5, 0);
                writeVarintField(out, 6, 0);
                if (frame.metadata != null) {
                    // NTKDCompanionSyncWrapper adds its receiving version to the session metadata.
                    // An echoed sender-only dictionary leaves native getNextMessage at version zero.
                    byte[] metadata = NtkFaceChangeEncoder.receiverMetadata();
                    try { writeBytes(out, 7, metadata); }
                    finally { java.util.Arrays.fill(metadata, (byte) 0); }
                }
            } else if (frame.messageId == BATCH) {
                out.write(3 << 3);
                out.write(frame.index, 0, frame.index.length);
            }
            return out.toByteArray();
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static void writeBytes(ByteArrayOutputStream out, int field, byte[] value) {
        out.write((field << 3) | 2);
        int length = value.length;
        while (length > 127) {
            out.write((length & 0x7f) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.write(value, 0, value.length);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, int value) {
        out.write(field << 3);
        out.write(value);
    }
}
