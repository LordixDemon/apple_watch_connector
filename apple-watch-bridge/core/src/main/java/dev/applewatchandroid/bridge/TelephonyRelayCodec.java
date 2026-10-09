package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Protocol codec for Apple Telephony & Call Relay:
 * {@code com.apple.private.alloy.telephony}.
 *
 * <p>Relays incoming/outgoing telephone calls, ringing states, and watch-side
 * call controls (answer, decline, mute).</p>
 */
public final class TelephonyRelayCodec {
    public static final String SERVICE =
            IdsApplicationRoute.TELEPHONY_SERVICE;

    public static final int TYPE_INCOMING_CALL = 1;
    public static final int TYPE_CALL_STATUS_CHANGED = 2;
    public static final int TYPE_CALL_ACTION = 3;

    public static final int STATUS_RINGING = 1;
    public static final int STATUS_ACTIVE = 2;
    public static final int STATUS_ENDED = 3;
    public static final int STATUS_MISSED = 4;

    public static final int ACTION_ANSWER = 1;
    public static final int ACTION_DECLINE = 2;
    public static final int ACTION_MUTE = 3;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_LENGTH_DELIMITED = 2;

    private TelephonyRelayCodec() {
    }

    public static final class IncomingCallAlert {
        public final String callId;
        public final String callerName;
        public final String callerNumber;
        public final boolean isVideo;

        public IncomingCallAlert(String callId, String callerName, String callerNumber, boolean isVideo) {
            this.callId = callId;
            this.callerName = callerName != null ? callerName : "";
            this.callerNumber = callerNumber != null ? callerNumber : "";
            this.isVideo = isVideo;
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (callId != null) writeStringField(out, 1, callId);
            if (callerName != null) writeStringField(out, 2, callerName);
            if (callerNumber != null) writeStringField(out, 3, callerNumber);

            writeTag(out, 4, WIRE_VARINT);
            writeVarint(out, isVideo ? 1 : 0);

            return out.toByteArray();
        }

        public static IncomingCallAlert decode(byte[] data) {
            Reader reader = new Reader(data);
            String id = null;
            String name = "";
            String num = "";
            boolean vid = false;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> id = reader.readString();
                    case 2 -> name = reader.readString();
                    case 3 -> num = reader.readString();
                    case 4 -> vid = reader.readVarint() != 0;
                    default -> reader.skipField(tag);
                }
            }
            return new IncomingCallAlert(id, name, num, vid);
        }
    }

    public static final class CallStatusChange {
        public final String callId;
        public final int status;

        public CallStatusChange(String callId, int status) {
            this.callId = callId;
            this.status = status;
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (callId != null) writeStringField(out, 1, callId);
            writeTag(out, 2, WIRE_VARINT);
            writeVarint(out, status);
            return out.toByteArray();
        }

        public static CallStatusChange decode(byte[] data) {
            Reader reader = new Reader(data);
            String id = null;
            int st = STATUS_ENDED;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> id = reader.readString();
                    case 2 -> st = (int) reader.readVarint();
                    default -> reader.skipField(tag);
                }
            }
            return new CallStatusChange(id, st);
        }
    }

    public static final class CallAction {
        public final String callId;
        public final int action;

        public CallAction(String callId, int action) {
            this.callId = callId;
            this.action = action;
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (callId != null) writeStringField(out, 1, callId);
            writeTag(out, 2, WIRE_VARINT);
            writeVarint(out, action);
            return out.toByteArray();
        }

        public static CallAction decode(byte[] data) {
            Reader reader = new Reader(data);
            String id = null;
            int act = ACTION_DECLINE;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> id = reader.readString();
                    case 2 -> act = (int) reader.readVarint();
                    default -> reader.skipField(tag);
                }
            }
            return new CallAction(id, act);
        }
    }

    private static void writeTag(ByteArrayOutputStream output, int field, int wireType) {
        writeVarint(output, ((long) field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        do {
            int next = (int) value & 0x7f;
            value >>>= 7;
            if (value != 0) next |= 0x80;
            output.write(next);
        } while (value != 0);
    }

    private static void writeStringField(ByteArrayOutputStream output, int field, String value) {
        if (value == null) return;
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeTag(output, field, WIRE_LENGTH_DELIMITED);
        writeVarint(output, encoded.length);
        output.writeBytes(encoded);
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        Reader(byte[] data) {
            this.data = data != null ? data : new byte[0];
            this.offset = 0;
        }

        boolean hasRemaining() {
            return offset < data.length;
        }

        int readTag() {
            return (int) readVarint();
        }

        long readVarint() {
            long value = 0;
            for (int shift = 0; shift < 64 && offset < data.length; shift += 7) {
                int next = data[offset++] & 0xff;
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
            return value;
        }

        byte[] readBytes() {
            long len = readVarint();
            if (len <= 0 || len > data.length - offset) return new byte[0];
            byte[] val = Arrays.copyOfRange(data, offset, offset + (int) len);
            offset += (int) len;
            return val;
        }

        String readString() {
            byte[] b = readBytes();
            return new String(b, StandardCharsets.UTF_8);
        }

        void skipField(int tag) {
            switch (tag & 7) {
                case WIRE_VARINT -> readVarint();
                case WIRE_LENGTH_DELIMITED -> {
                    long len = readVarint();
                    offset = Math.min(data.length, offset + (int) len);
                }
                default -> offset = data.length;
            }
        }
    }
}
