package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Bounded stdin requests/stdout receipts. No receipt here means Watch-local APPLIED. */
final class BridgeCommandCodec {
    static final String REQUEST_PREFIX = "BRIDGE_COMMAND_V1:";
    static final String STATUS_PREFIX = "BRIDGE_COMMAND_STATUS_V1:";
    static final int MAX_FRAME = 180_100;
    private static final int REQUEST_MAGIC = 0x41574351, STATUS_MAGIC = 0x41574353;
    // Append only: previous on-wire ordinal values must stay unchanged.
    // NATIVE_FACE_APPLIED is issued only after the complete correlated Watch readback.
    enum Stage { HAL_ACCEPTED, IDS_QUEUED, APP_ACK_RECEIVED, APP_RESPONSE_RECEIVED, REJECTED, EXPIRED, FAILED, UNKNOWN, NATIVE_FACE_APPLIED }
    record Request(UUID id, UUID epoch, long deadlineMs, String command) {
        Request {
            if (id == null || epoch == null || deadlineMs < 1 || !OperationalCommandPolicy.isAllowed(command)) {
                throw new IllegalArgumentException("Invalid operational request");
            }
            command = command.stripTrailing();
        }
    }
    record Status(UUID id, UUID epoch, Stage stage) {
        Status {
            if (id == null || epoch == null || stage == null) throw new IllegalArgumentException("Invalid request status");
        }
    }
    static byte[] encode(Request request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(REQUEST_MAGIC); out.writeByte(1);
            uuid(out, request.id); uuid(out, request.epoch);
            out.writeLong(request.deadlineMs);
            byte[] command = request.command.getBytes(StandardCharsets.US_ASCII);
            out.writeInt(command.length); out.write(command);
            java.util.Arrays.fill(command, (byte) 0);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static Request decodeRequest(byte[] frame) {
        try {
            DataInputStream in = input(frame, REQUEST_MAGIC);
            UUID id = uuid(in), epoch = uuid(in);
            long deadlineMs = in.readLong();
            int length = in.readInt();
            if (length < 1 || length > 180_000 || length != in.available()) throw new IllegalArgumentException("Invalid command length");
            byte[] command = in.readNBytes(length);
            try {
                for (byte b : command) if (b < 0) throw new IllegalArgumentException("Non-ASCII command");
                return new Request(id, epoch, deadlineMs, new String(command, StandardCharsets.US_ASCII));
            } finally { java.util.Arrays.fill(command, (byte) 0); }
        } catch (IOException truncated) { throw new IllegalArgumentException("Truncated request", truncated); }
    }
    static byte[] encode(Status status) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(STATUS_MAGIC); out.writeByte(1);
            uuid(out, status.id); uuid(out, status.epoch); out.writeByte(status.stage.ordinal());
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static Status decodeStatus(byte[] frame) {
        try {
            DataInputStream in = input(frame, STATUS_MAGIC);
            UUID id = uuid(in), epoch = uuid(in);
            int stage = in.readUnsignedByte();
            if (stage >= Stage.values().length || in.available() != 0) throw new IllegalArgumentException("Invalid status");
            return new Status(id, epoch, Stage.values()[stage]);
        } catch (IOException truncated) { throw new IllegalArgumentException("Truncated status", truncated); }
    }
    private static DataInputStream input(byte[] frame, int magic) throws IOException {
        if (frame == null || frame.length > MAX_FRAME) throw new IllegalArgumentException("Invalid frame size");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
        if (in.readInt() != magic || in.readUnsignedByte() != 1) throw new IllegalArgumentException("Invalid frame version/type");
        return in;
    }
    private static void uuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits()); out.writeLong(uuid.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
}
