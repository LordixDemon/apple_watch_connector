package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * Protocol codec for Apple Bulletin Distributor (Notification Relay) service:
 * {@code com.apple.private.alloy.bulletindistributor}.
 *
 * <p>Handles mirrored notifications, dismissals, haptic play confirmations,
 * and user replies sent from the Apple Watch.</p>
 */
public final class BulletinDistributorCodec {
    public static final String SERVICE =
            IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE;
    public static final String SETTINGS_SERVICE =
            IdsApplicationRoute.BULLETIN_SETTINGS_SERVICE;

    public static final int TYPE_ADD_BULLETIN = 1;
    public static final int TYPE_REMOVE_BULLETIN = 2;
    public static final int TYPE_ADD_SUMMARY = 3;
    public static final int TYPE_CANCEL_BULLETIN = 4;
    public static final int TYPE_ACKNOWLEDGE_ACTION = 5;
    public static final int TYPE_SNOOZE_ACTION = 6;
    public static final int TYPE_SUPPLEMENTARY_ACTION = 7;
    public static final int TYPE_DISMISS_ACTION = 8;
    public static final int TYPE_ACK_INITIAL_SEQUENCE = 12;
    public static final int TYPE_DID_PLAY_LIGHTS_AND_SIRENS = 9;
    public static final int TYPE_SET_SECTION_SUBTYPE_ICON = 14;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED32 = 5;
    private static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;
    private static final int MAX_STRING_LENGTH = 64 * 1024;

    private BulletinDistributorCodec() {
    }

    public record InitialSequenceAck(Boolean assertion, UUID sessionId, Integer state) {
        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (assertion != null) { writeTag(out, 1, WIRE_VARINT); writeVarint(out, assertion ? 1 : 0); }
            if (sessionId != null) {
                byte[] id = ByteBuffer.allocate(16).putLong(sessionId.getMostSignificantBits())
                        .putLong(sessionId.getLeastSignificantBits()).array();
                writeBytesField(out, 2, id);
            }
            if (state != null) { writeTag(out, 3, WIRE_VARINT); writeVarint(out, state); }
            return out.toByteArray();
        }
        public static InitialSequenceAck decode(byte[] bytes) {
            Reader reader = new Reader(bytes);
            Boolean assertion = null; UUID session = null; Integer state = null;
            int seen = 0;
            while (reader.hasRemaining()) {
                int tag = reader.readTag(), field = tag >>> 3;
                if (field >= 1 && field <= 3) {
                    if ((seen & (1 << field)) != 0) throw new IllegalArgumentException("Duplicate ACK field");
                    seen |= 1 << field;
                }
                switch (field) {
                    case 1 -> {
                        long value = reader.readVarint();
                        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid assert flag");
                        assertion = value == 1;
                    }
                    case 2 -> {
                        byte[] value = reader.readBytes();
                        try {
                            if (value.length != 16) throw new IllegalArgumentException("Invalid ACK UUID");
                            ByteBuffer id = ByteBuffer.wrap(value);
                            session = new UUID(id.getLong(), id.getLong());
                        } finally { Arrays.fill(value, (byte) 0); }
                    }
                    case 3 -> {
                        long value = reader.readVarint();
                        if (value < 0 || value > 2) throw new IllegalArgumentException("Invalid ACK state");
                        state = (int) value;
                    }
                    default -> reader.skipField(tag);
                }
            }
            return new InitialSequenceAck(assertion, session, state);
        }
    }

    /** BLTPBAction/Appearance native text-input action, encoded as Bulletin field9. */
    public record TextInputAction(String identifier, String title, String sendTitle) {
        public TextInputAction(String identifier, String title) {
            this(identifier, title, "Send");
        }
        public byte[] encode() {
            if (identifier == null || identifier.isBlank() || identifier.length() > 128
                    || title == null || title.length() > 256
                    || sendTitle == null || sendTitle.length() > 256) throw new IllegalArgumentException("Invalid action");
            ByteArrayOutputStream appearance = new ByteArrayOutputStream();
            writeStringField(appearance, 1, title);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeStringField(out, 1, identifier);
            writeBytesField(out, 2, appearance.toByteArray());
            writeTag(out, 3, WIRE_VARINT); writeVarint(out, 1); // background activation
            writeTag(out, 5, WIRE_VARINT); writeVarint(out, 1); // text-input behavior
            byte[] params = AppleBinaryPropertyList.encode(Map.of(
                    "UNNotificationActionTextInputPlaceholder", "",
                    "UNNotificationActionTextInputButtonTitle", sendTitle));
            try { writeBytesField(out, 6, params); }
            finally { Arrays.fill(params, (byte) 0); }
            return out.toByteArray();
        }
    }

    public static final class Bulletin {
        public final String bulletinId;
        public final String sectionId;
        public final String sectionDisplayName;
        public final String title;
        public final String subtitle;
        public final String messageTitle;
        public final String publisherBulletinId;
        public final String recordId;
        public final String replyToken;
        public final boolean includesSound;
        public final int soundAlertType;
        public final long timestampMs;
        private TextInputAction textInputAction;

        public Bulletin withTextInputAction(TextInputAction action) {
            this.textInputAction = action;
            return this;
        }

        public Bulletin(
                String bulletinId,
                String sectionId,
                String sectionDisplayName,
                String title,
                String subtitle,
                String messageTitle,
                String publisherBulletinId,
                String recordId,
                String replyToken,
                boolean includesSound,
                int soundAlertType,
                long timestampMs) {
            this.bulletinId = bulletinId != null ? bulletinId : UUID.randomUUID().toString();
            this.sectionId = sectionId != null ? sectionId : "com.apple.MobileSMS";
            this.sectionDisplayName = sectionDisplayName != null ? sectionDisplayName : "Messages";
            this.title = title != null ? title : "";
            this.subtitle = subtitle;
            this.messageTitle = messageTitle != null ? messageTitle : "";
            this.publisherBulletinId = publisherBulletinId != null ? publisherBulletinId : this.bulletinId;
            this.recordId = recordId != null ? recordId : this.bulletinId;
            this.replyToken = replyToken;
            this.includesSound = includesSound;
            this.soundAlertType = soundAlertType;
            this.timestampMs = timestampMs > 0 ? timestampMs : System.currentTimeMillis();
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (bulletinId != null) writeStringField(out, 1, bulletinId);
            if (sectionId != null) writeStringField(out, 2, sectionId);
            if (sectionDisplayName != null) writeStringField(out, 3, sectionDisplayName);
            if (title != null) writeStringField(out, 4, title);
            if (subtitle != null) writeStringField(out, 5, subtitle);
            if (messageTitle != null) writeStringField(out, 6, messageTitle);

            // Field 7: Date
            writeTag(out, 7, WIRE_FIXED64);
            writeFixed64(out, Double.doubleToRawLongBits(timestampMs / 1000.0));
            if (textInputAction != null) writeBytesField(out, 9, textInputAction.encode());

            // Field 0x0a (10): Feed (59 default)
            writeTag(out, 10, WIRE_VARINT);
            writeVarint(out, 59);

            if (recordId != null) writeStringField(out, 12, recordId);
            if (publisherBulletinId != null) writeStringField(out, 13, publisherBulletinId);

            // Field 0x0f (15): SectionSubtype (2)
            writeTag(out, 15, WIRE_VARINT);
            writeVarint(out, 2);

            // Field 0x13 (19): IncludesSound
            writeTag(out, 19, WIRE_VARINT);
            writeVarint(out, includesSound ? 1 : 0);

            // Field 0x18 (24): SoundAlertType
            writeTag(out, 24, WIRE_VARINT);
            writeVarint(out, soundAlertType);

            // Field 0x1e (30): turnsOnDisplay
            writeTag(out, 30, WIRE_VARINT);
            writeVarint(out, 1);

            if (replyToken != null) writeStringField(out, 43, replyToken);

            return out.toByteArray();
        }

        public static Bulletin decode(byte[] data) {
            Reader reader = new Reader(data);
            String bId = null;
            String sId = null;
            String sDisplayName = null;
            String t = null;
            String sub = null;
            String msg = null;
            String pubId = null;
            String recId = null;
            String repToken = null;
            boolean sound = true;
            int alertType = 2;
            long ts = System.currentTimeMillis();

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> bId = reader.readString();
                    case 2 -> sId = reader.readString();
                    case 3 -> sDisplayName = reader.readString();
                    case 4 -> t = reader.readString();
                    case 5 -> sub = reader.readString();
                    case 6 -> msg = reader.readString();
                    case 7 -> {
                        if ((tag & 7) == WIRE_FIXED64) {
                            double d = Double.longBitsToDouble(reader.readFixed64());
                            ts = (long) (d * 1000);
                        } else {
                            reader.skipField(tag);
                        }
                    }
                    case 12 -> recId = reader.readString();
                    case 13 -> pubId = reader.readString();
                    case 19 -> sound = reader.readVarint() != 0;
                    case 24 -> alertType = (int) reader.readVarint();
                    case 43 -> repToken = reader.readString();
                    default -> reader.skipField(tag);
                }
            }
            return new Bulletin(bId, sId, sDisplayName, t, sub, msg, pubId, recId, repToken, sound, alertType, ts);
        }
    }

    public static final class BulletinRequest {
        public final Bulletin bulletin;
        public final boolean shouldPlayLightsAndSirens;
        public final long timestampMs;
        public final int updateType;

        public BulletinRequest(
                Bulletin bulletin,
                boolean shouldPlayLightsAndSirens,
                long timestampMs,
                int updateType) {
            this.bulletin = bulletin;
            this.shouldPlayLightsAndSirens = shouldPlayLightsAndSirens;
            this.timestampMs = timestampMs > 0 ? timestampMs : System.currentTimeMillis();
            this.updateType = updateType;
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (bulletin != null) {
                writeBytesField(out, 1, bulletin.encode());
            }
            writeTag(out, 2, WIRE_VARINT);
            writeVarint(out, shouldPlayLightsAndSirens ? 1 : 0);

            writeTag(out, 3, WIRE_FIXED64);
            writeFixed64(out, Double.doubleToRawLongBits(timestampMs / 1000.0));

            writeTag(out, 4, WIRE_VARINT);
            writeVarint(out, updateType);

            return out.toByteArray();
        }

        public static BulletinRequest decode(byte[] data) {
            Reader reader = new Reader(data);
            Bulletin b = null;
            boolean sirens = true;
            long ts = System.currentTimeMillis();
            int uType = 0;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> b = Bulletin.decode(reader.readBytes());
                    case 2 -> sirens = reader.readVarint() != 0;
                    case 3 -> {
                        if ((tag & 7) == WIRE_FIXED64) {
                            double d = Double.longBitsToDouble(reader.readFixed64());
                            ts = (long) (d * 1000);
                        } else {
                            reader.skipField(tag);
                        }
                    }
                    case 4 -> uType = (int) reader.readVarint();
                    default -> reader.skipField(tag);
                }
            }
            return new BulletinRequest(b, sirens, ts, uType);
        }
    }

    public static final class RemoveBulletinRequest {
        public final String publisherBulletinId;
        public final String recordId;
        public final String sectionId;

        public RemoveBulletinRequest(
                String publisherBulletinId,
                String recordId,
                String sectionId) {
            this.publisherBulletinId = publisherBulletinId;
            this.recordId = recordId;
            this.sectionId = sectionId;
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (publisherBulletinId != null) writeStringField(out, 1, publisherBulletinId);
            if (recordId != null) writeStringField(out, 2, recordId);
            if (sectionId != null) writeStringField(out, 3, sectionId);
            return out.toByteArray();
        }

        public static RemoveBulletinRequest decode(byte[] data) {
            Reader reader = new Reader(data);
            String pubId = null;
            String recId = null;
            String sId = null;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> pubId = reader.readString();
                    case 2 -> recId = reader.readString();
                    case 3 -> sId = reader.readString();
                    default -> reader.skipField(tag);
                }
            }
            return new RemoveBulletinRequest(pubId, recId, sId);
        }

        static RemoveBulletinRequest decodeExact(byte[] data) {
            RemoveBulletinRequest value = decode(data);
            if (value.publisherBulletinId == null || value.publisherBulletinId.isBlank() || value.publisherBulletinId.length() > 128
                    || value.recordId == null || value.recordId.isBlank() || value.recordId.length() > 128
                    || value.sectionId == null || value.sectionId.isBlank() || value.sectionId.length() > 512) {
                throw new IllegalArgumentException("Removal requires exact bounded identity");
            }
            return value;
        }
    }

    public static final class DismissActionRequest {
        public final String publisherBulletinId;
        public final String recordId;
        public final String sectionId;

        public DismissActionRequest(
                String publisherBulletinId,
                String recordId,
                String sectionId) {
            this.publisherBulletinId = publisherBulletinId;
            this.recordId = recordId;
            this.sectionId = sectionId;
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (publisherBulletinId != null) writeStringField(out, 1, publisherBulletinId);
            if (recordId != null) writeStringField(out, 2, recordId);
            if (sectionId != null) writeStringField(out, 3, sectionId);
            return out.toByteArray();
        }

        public static DismissActionRequest decode(byte[] data) {
            Reader reader = new Reader(data);
            String pubId = null;
            String recId = null;
            String sId = null;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> pubId = reader.readString();
                    case 2 -> recId = reader.readString();
                    case 3 -> sId = reader.readString();
                    default -> reader.skipField(tag);
                }
            }
            return new DismissActionRequest(pubId, recId, sId);
        }
    }

    public static final class SupplementaryActionRequest {
        public final String identifier;
        public final String publisherBulletinId;
        public final String recordId;
        public final String sectionId;
        public final String replyText;

        public SupplementaryActionRequest(
                String identifier,
                String publisherBulletinId,
                String recordId,
                String sectionId,
                String replyText) {
            this.identifier = identifier;
            this.publisherBulletinId = publisherBulletinId;
            this.recordId = recordId;
            this.sectionId = sectionId;
            this.replyText = replyText;
        }

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (identifier != null) writeStringField(out, 1, identifier);
            if (publisherBulletinId != null) writeStringField(out, 2, publisherBulletinId);
            if (recordId != null) writeStringField(out, 3, recordId);
            if (sectionId != null) writeStringField(out, 4, sectionId);
            if (replyText != null) {
                ByteArrayOutputStream actionInfo = new ByteArrayOutputStream();
                byte[] context = AppleBinaryPropertyList.encode(Map.of("userResponseInfo",
                        Map.of("UIUserNotificationActionResponseTypedTextKey", replyText)));
                writeBytesField(actionInfo, 4, context);
                writeBytesField(out, 5, actionInfo.toByteArray());
                Arrays.fill(context, (byte) 0);
            }
            return out.toByteArray();
        }

        public static SupplementaryActionRequest decode(byte[] data) {
            Reader reader = new Reader(data);
            String id = null;
            String pubId = null;
            String recId = null;
            String sId = null;
            String text = null;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> id = reader.readString();
                    case 2 -> pubId = reader.readString();
                    case 3 -> recId = reader.readString();
                    case 4 -> sId = reader.readString();
                    case 5 -> {
                        byte[] raw = reader.readBytes();
                        try { text = extractReplyText(raw); }
                        finally { Arrays.fill(raw, (byte) 0); }
                    }
                    default -> reader.skipField(tag);
                }
            }
            return new SupplementaryActionRequest(id, pubId, recId, sId, text);
        }

        private static String extractReplyText(byte[] raw) {
            Reader r = new Reader(raw);
            String text = null;
            boolean contextSeen = false;
            while (r.hasRemaining()) {
                int tag = r.readTag();
                if ((tag >>> 3) == 4) {
                    if (contextSeen) throw new IllegalArgumentException("Duplicate action context");
                    contextSeen = true;
                    byte[] context = r.readBytes();
                    try {
                        Object decoded = AppleBinaryPropertyList.decode(context);
                        if (decoded instanceof Map<?, ?> root
                                && root.get("userResponseInfo") instanceof Map<?, ?> response
                                && response.get("UIUserNotificationActionResponseTypedTextKey") instanceof String reply) {
                            if (reply.length() > 4096) throw new IllegalArgumentException("Reply too long");
                            text = reply;
                        }
                    } finally { Arrays.fill(context, (byte) 0); }
                } else {
                    r.skipField(tag);
                }
            }
            return text;
        }
    }

    public static final class DidPlayLightsAndSirens {
        public final Boolean didPlay;
        public final String publisherMatchId;
        public final String sectionId;
        public final Double dateSeconds;
        public final String replyToken;

        public DidPlayLightsAndSirens(
                Boolean didPlay, String publisherMatchId, String sectionId,
                Double dateSeconds, String replyToken) {
            this.didPlay = didPlay;
            this.publisherMatchId = publisherMatchId;
            this.sectionId = sectionId;
            this.dateSeconds = dateSeconds;
            this.replyToken = replyToken;
        }

        public static DidPlayLightsAndSirens decode(byte[] data) {
            Reader reader = new Reader(data);
            Boolean played = null;
            String pubId = null;
            String sId = null;
            Double date = null;
            String token = null;
            int seen = 0;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                if (field >= 1 && field <= 5) {
                    if ((seen & (1 << field)) != 0) throw new IllegalArgumentException("Duplicate confirmation field");
                    seen |= 1 << field;
                }
                switch (field) {
                    case 1 -> {
                        long value = reader.readVarint();
                        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid played flag");
                        played = value == 1;
                    }
                    case 2 -> pubId = reader.readString();
                    case 3 -> sId = reader.readString();
                    case 4 -> {
                        date = Double.longBitsToDouble(reader.readFixed64());
                        if (!Double.isFinite(date)) throw new IllegalArgumentException("Invalid confirmation date");
                    }
                    case 5 -> token = reader.readString();
                    default -> reader.skipField(tag);
                }
            }
            return new DidPlayLightsAndSirens(played, pubId, sId, date, token);
        }
    }

    public static final class Trailer {
        public final int sequence;
        public final boolean flag;
        public final UUID sessionId;
        public final Integer state;

        public Trailer(int sequence, boolean flag, UUID sessionId, Integer state) {
            this.sequence = sequence;
            this.flag = flag;
            this.sessionId = sessionId != null ? sessionId : UUID.randomUUID();
            this.state = state;
        }

        public byte[] encodeWithLengthSuffix() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeTag(out, 1, WIRE_VARINT);
            writeVarint(out, sequence);

            if (flag) {
                writeTag(out, 2, WIRE_VARINT);
                writeVarint(out, 1);
            }

            ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
            bb.putLong(sessionId.getMostSignificantBits());
            bb.putLong(sessionId.getLeastSignificantBits());
            writeBytesField(out, 3, bb.array());

            if (state != null) {
                writeTag(out, 4, WIRE_VARINT);
                writeVarint(out, state);
            }

            byte[] trailerBytes = out.toByteArray();
            int len = trailerBytes.length;
            ByteBuffer result = ByteBuffer.allocate(len + 2);
            result.put(trailerBytes);
            result.order(ByteOrder.LITTLE_ENDIAN);
            result.putShort((short) len);
            return result.array();
        }

        public static Trailer decode(byte[] data) {
            Reader reader = new Reader(data);
            int seq = -1;
            boolean fl = false;
            UUID sess = null;
            Integer st = null;

            while (reader.hasRemaining()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                switch (field) {
                    case 1 -> {
                        long value = reader.readVarint();
                        if (value < 0 || value > Integer.MAX_VALUE || seq >= 0) throw new IllegalArgumentException("Invalid trailer sequence");
                        seq = (int) value;
                    }
                    case 2 -> fl = reader.readVarint() != 0;
                    case 3 -> {
                        byte[] uuidBytes = reader.readBytes();
                        if (uuidBytes.length == 16 && sess == null) {
                            ByteBuffer bb = ByteBuffer.wrap(uuidBytes);
                            sess = new UUID(bb.getLong(), bb.getLong());
                        } else throw new IllegalArgumentException("Invalid trailer UUID");
                    }
                    case 4 -> st = (int) reader.readVarint();
                    default -> reader.skipField(tag);
                }
            }
            if (seq < 0 || sess == null) throw new IllegalArgumentException("Missing trailer identity");
            return new Trailer(seq, fl, sess, st);
        }
    }

    public static final class MessageAndTrailer {
        public final byte[] messagePayload;
        public final Trailer trailer;

        public MessageAndTrailer(byte[] messagePayload, Trailer trailer) {
            this.messagePayload = messagePayload;
            this.trailer = trailer;
        }
    }

    /**
     * Splits a raw bulletin payload into the core protobuf payload and the trailer.
     */
    public static MessageAndTrailer splitTrailer(byte[] rawPayload) {
        if (rawPayload == null || rawPayload.length < 4) {
            return new MessageAndTrailer(rawPayload != null ? rawPayload : new byte[0], null);
        }
        int total = rawPayload.length;
        ByteBuffer bb = ByteBuffer.wrap(rawPayload, total - 2, 2);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        int trailerLen = bb.getShort() & 0xffff;

        if (trailerLen <= 0 || trailerLen > total - 2) {
            // No valid trailer suffix found
            return new MessageAndTrailer(rawPayload, null);
        }

        int trailerStart = total - 2 - trailerLen;
        byte[] messageBytes = Arrays.copyOfRange(rawPayload, 0, trailerStart);
        byte[] trailerBytes = Arrays.copyOfRange(rawPayload, trailerStart, total - 2);

        Trailer trailer = null;
        try {
            trailer = Trailer.decode(trailerBytes);
        } catch (Exception ignored) {}

        return new MessageAndTrailer(messageBytes, trailer);
    }

    /**
     * Packs a message payload and appends a valid session trailer.
     */
    public static byte[] packWithTrailer(byte[] messagePayload, UUID sessionId, int sequence, boolean flag, Integer state) {
        Trailer trailer = new Trailer(sequence, flag, sessionId, state);
        byte[] trailerWithSuffix = trailer.encodeWithLengthSuffix();
        ByteArrayOutputStream out = new ByteArrayOutputStream(messagePayload.length + trailerWithSuffix.length);
        out.writeBytes(messagePayload);
        out.writeBytes(trailerWithSuffix);
        return out.toByteArray();
    }

    // --- Protobuf wire encoding/decoding helpers ---

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

    private static void writeFixed64(ByteArrayOutputStream output, long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            output.write((int) (value >>> shift) & 0xff);
        }
    }

    private static void writeStringField(ByteArrayOutputStream output, int field, String value) {
        if (value == null) return;
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeBytesField(output, field, encoded);
    }

    private static void writeBytesField(ByteArrayOutputStream output, int field, byte[] value) {
        if (value == null) return;
        writeTag(output, field, WIRE_LENGTH_DELIMITED);
        writeVarint(output, value.length);
        output.writeBytes(value);
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;
        private int wire = -1;

        Reader(byte[] data) {
            if (data == null || data.length > MAX_PAYLOAD_LENGTH) throw new IllegalArgumentException("Invalid protobuf size");
            this.data = data;
            this.offset = 0;
        }

        boolean hasRemaining() {
            return offset < data.length;
        }

        int readTag() {
            long value = rawVarint();
            if (value <= 0 || value > 0xffff_ffffL || (value >>> 3) == 0) throw new IllegalArgumentException("Invalid protobuf tag");
            wire = (int) value & 7;
            if (wire != 0 && wire != 1 && wire != 2 && wire != 5) throw new IllegalArgumentException("Unsupported protobuf wire type");
            return (int) value;
        }

        long readVarint() {
            expect(WIRE_VARINT);
            return rawVarint();
        }

        private long rawVarint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                require(1);
                int next = data[offset++] & 0xff;
                if (shift == 63 && (next & 0xfe) != 0) throw new IllegalArgumentException("Varint overflow");
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("Truncated varint");
        }

        long readFixed64() {
            expect(WIRE_FIXED64);
            require(8);
            long value = 0;
            for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                value |= (long) (data[offset++] & 0xff) << shift;
            }
            return value;
        }

        byte[] readBytes() {
            expect(WIRE_LENGTH_DELIMITED);
            long len = rawVarint();
            if (len < 0 || len > data.length - offset) throw new IllegalArgumentException("Truncated protobuf bytes");
            byte[] val = Arrays.copyOfRange(data, offset, offset + (int) len);
            offset += (int) len;
            return val;
        }

        String readString() {
            byte[] b = readBytes();
            try {
                if (b.length > MAX_STRING_LENGTH) throw new IllegalArgumentException("String too long");
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("Invalid UTF-8", invalid);
            } finally { Arrays.fill(b, (byte) 0); }
        }

        private void expect(int expected) {
            if (wire != expected) throw new IllegalArgumentException("Wrong protobuf wire type");
        }
        private void require(int length) {
            if (length < 0 || length > data.length - offset) throw new IllegalArgumentException("Truncated protobuf field");
        }

        void skipField(int tag) {
            switch (tag & 7) {
                case WIRE_VARINT -> rawVarint();
                case WIRE_FIXED64 -> { require(8); offset += 8; }
                case WIRE_LENGTH_DELIMITED -> {
                    long len = rawVarint();
                    if (len < 0 || len > data.length - offset) throw new IllegalArgumentException("Truncated unknown field");
                    offset += (int) len;
                }
                case WIRE_FIXED32 -> { require(4); offset += 4; }
                default -> throw new IllegalArgumentException("Unsupported protobuf wire type");
            }
        }
    }
}
