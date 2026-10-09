package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Versioned HAL stdout event. Application payloads must never enter the journal. */
public final class BridgeApplicationEventCodec {
    public static final String PREFIX = "BRIDGE_APPLICATION_V1:";
    public static final int MAX_PAYLOAD = 128 * 1024;
    public static final int MAX_FRAME = MAX_PAYLOAD + 528;
    private static final int MAGIC = 0x41574249;
    private BridgeApplicationEventCodec() {}

    public static final class Event implements AutoCloseable {
        public final String topic;
        public final int protobufType;
        public final boolean response;
        private byte[] payload;
        public Event(String topic, int protobufType, boolean response, byte[] payload) {
            if (topic == null || !topic.matches("[A-Za-z0-9._-]{1,512}")
                    || protobufType < 0 || protobufType > 65535
                    || payload == null || payload.length > MAX_PAYLOAD) {
                throw new IllegalArgumentException("Invalid bridge application event");
            }
            this.topic = topic;
            this.protobufType = protobufType;
            this.response = response;
            this.payload = payload.clone();
        }
        public byte[] payload() {
            if (payload == null) throw new IllegalStateException("Event was closed");
            return payload.clone();
        }
        public int payloadLength() {
            if (payload == null) throw new IllegalStateException("Event was closed");
            return payload.length;
        }
        @Override public void close() {
            if (payload != null) Arrays.fill(payload, (byte) 0);
            payload = null;
        }
    }

    public static byte[] encode(Event event) {
        byte[] payload = event.payload();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            byte[] topic = event.topic.getBytes(StandardCharsets.US_ASCII);
            out.writeInt(MAGIC);
            out.writeByte(1);
            out.writeShort(topic.length);
            out.write(topic);
            out.writeInt(event.protobufType);
            out.writeByte(event.response ? 1 : 0);
            out.writeInt(payload.length);
            out.write(payload);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    public static Event decode(byte[] frame) {
        if (frame == null || frame.length < 17 || frame.length > MAX_FRAME) {
            throw new IllegalArgumentException("Invalid bridge event frame size");
        }
        byte[] payload = null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != 1) {
                throw new IllegalArgumentException("Unsupported bridge event version");
            }
            int topicLength = in.readUnsignedShort();
            if (topicLength == 0 || topicLength > 512 || topicLength > in.available() - 9) {
                throw new IllegalArgumentException("Invalid bridge event topic size");
            }
            byte[] topicBytes = in.readNBytes(topicLength);
            for (byte value : topicBytes) {
                if (value < 0) throw new IllegalArgumentException("Non-ASCII bridge event topic");
            }
            String topic = new String(topicBytes, StandardCharsets.US_ASCII);
            int type = in.readInt();
            int response = in.readUnsignedByte();
            int length = in.readInt();
            if (response > 1 || length < 0 || length > MAX_PAYLOAD || length != in.available()) {
                throw new IllegalArgumentException("Invalid bridge event payload size or flags");
            }
            payload = in.readNBytes(length);
            return new Event(topic, type, response == 1, payload);
        } catch (IOException truncated) {
            throw new IllegalArgumentException("Truncated bridge event", truncated);
        } finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
        }
    }
}
