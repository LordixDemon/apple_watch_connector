package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Private child HAL IPC. Never accept this envelope from an exported Android intent. */
final class FindMyPhoneIpcCodec {
    static final String REQUEST_PREFIX = "FIND_MY_PHONE_REQUEST_V1:";
    static final String RESULT_PREFIX = "FIND_MY_PHONE_RESULT_V1:";
    static final int MAX_FRAME = 80;
    private static final int REQUEST_MAGIC = 0x41574651, RESULT_MAGIC = 0x41574652;
    record Request(UUID epoch, String messageId, int type,
                   FindMyLocalDeviceCodec.PlaySoundRequest body, long deadline) {
        Request {
            validate(epoch, messageId, type);
            if (body == null || deadline <= 0 || (type == 2 && body.behaviorOverride() != null)) throw invalid();
        }
        int behavior() { return type == 2 ? 1 : body.behaviorOverride() == null ? 0 : body.behaviorOverride(); }
    }
    record Result(UUID epoch, String messageId, int type, boolean didPlay) {
        Result { validate(epoch, messageId, type); }
    }
    private FindMyPhoneIpcCodec() { }
    private static void validate(UUID epoch, String id, int type) {
        FindMyLocalDeviceCodec.requireSoundType(type);
        if (epoch == null || id == null || id.length() != 36
                || !UUID.fromString(id).toString().equalsIgnoreCase(id)) throw invalid();
    }
    static byte[] encode(Request request) {
        return encode(REQUEST_MAGIC, request.epoch, request.messageId, request.type, out -> {
            out.writeLong(request.deadline);
            out.writeDouble(request.body.unixSeconds());
            out.writeByte(request.body.behaviorOverride() == null ? 255 : request.body.behaviorOverride());
        });
    }
    static byte[] encode(Result result) {
        return encode(RESULT_MAGIC, result.epoch, result.messageId, result.type,
                out -> out.writeByte(result.didPlay ? 1 : 0));
    }
    private interface Tail { void write(DataOutputStream out) throws IOException; }
    private static byte[] encode(int magic, UUID epoch, String id, int type, Tail tail) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(magic); out.writeByte(1);
            out.writeLong(epoch.getMostSignificantBits()); out.writeLong(epoch.getLeastSignificantBits());
            out.writeByte(type); out.write(id.getBytes(StandardCharsets.US_ASCII));
            tail.write(out);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Header(UUID epoch, String id, int type) { }
    private static Header header(DataInputStream in, int magic) throws IOException {
        if (in.readInt() != magic || in.readUnsignedByte() != 1) throw invalid();
        UUID epoch = new UUID(in.readLong(), in.readLong());
        int type = in.readUnsignedByte();
        byte[] bytes = in.readNBytes(36);
        if (bytes.length != 36) throw invalid();
        for (byte b : bytes) if (b < 0) throw invalid();
        String id = new String(bytes, StandardCharsets.US_ASCII);
        validate(epoch, id, type);
        return new Header(epoch, id, type);
    }
    static Request decodeRequest(byte[] frame) {
        try {
            DataInputStream in = input(frame);
            Header h = header(in, REQUEST_MAGIC);
            long deadline = in.readLong();
            double timestamp = in.readDouble();
            int behavior = in.readUnsignedByte();
            if (in.available() != 0) throw invalid();
            return new Request(h.epoch, h.id, h.type,
                    new FindMyLocalDeviceCodec.PlaySoundRequest(timestamp, behavior == 255 ? null : behavior), deadline);
        } catch (IOException failure) { throw invalid(); }
    }
    static Result decodeResult(byte[] frame) {
        try {
            DataInputStream in = input(frame);
            Header h = header(in, RESULT_MAGIC);
            int played = in.readUnsignedByte();
            if (played > 1 || in.available() != 0) throw invalid();
            return new Result(h.epoch, h.id, h.type, played == 1);
        } catch (IOException failure) { throw invalid(); }
    }
    private static DataInputStream input(byte[] frame) {
        if (frame == null || frame.length > MAX_FRAME) throw invalid();
        return new DataInputStream(new ByteArrayInputStream(frame));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid phone Ping IPC"); }
}
