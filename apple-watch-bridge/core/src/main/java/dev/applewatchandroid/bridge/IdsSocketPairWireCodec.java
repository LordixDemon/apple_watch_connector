package dev.applewatchandroid.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Wire encoder, decoder, fragmenter, and decompression codec for
 * {@link IdsSocketPairCodec}.
 */
final class IdsSocketPairWireCodec {
    private IdsSocketPairWireCodec() {
    }

    static byte[] encodeHandshake(
            long version) {
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(4);
        writeU32(
                body,
                version);
        return frame(
                IdsSocketPairCodec.COMMAND_HANDSHAKE,
                body.toByteArray());
    }

    static byte[] encodeAck(
            long sequence,
            boolean expired) {
        ByteArrayOutputStream body =
                new ByteArrayOutputStream(4);
        writeU32(
                body,
                sequence);
        return frame(
                expired
                        ? IdsSocketPairCodec.COMMAND_EXPIRED_ACK
                        : IdsSocketPairCodec.COMMAND_ACK,
                body.toByteArray());
    }

    static byte[] encodeKeepAlive() {
        return frame(
                IdsSocketPairCodec.COMMAND_KEEP_ALIVE,
                new byte[0]);
    }

    static byte[] encodeData(
            IdsSocketPairCodec.DataMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "IDS Data message is null");
        }
        message.requireValid();
        ByteArrayOutputStream body =
                commonDataPrefix(
                        message.sequence,
                        message.streamId,
                        message.flags,
                        message.peerResponseIdentifier,
                        message.messageUuid,
                        message.topic);
        body.writeBytes(
                message.payload);
        if (message.expirySeconds != null) {
            writeU32(
                    body,
                    message.expirySeconds);
        }
        return frame(
                message.command,
                body.toByteArray());
    }

    static byte[] encodeProtobuf(
            IdsSocketPairCodec.ProtobufMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "IDS Protobuf message is null");
        }
        message.requireValid();
        ByteArrayOutputStream body =
                commonDataPrefix(
                        message.sequence,
                        message.streamId,
                        message.flags,
                        message.peerResponseIdentifier,
                        message.messageUuid,
                        message.topic);
        writeU16(
                body,
                message.protobufType);
        writeU16(
                body,
                message.response ? 1 : 0);
        writeU32(
                body,
                message.payload.length);
        body.writeBytes(
                message.payload);
        if (message.expirySeconds != null) {
            writeU32(
                    body,
                    message.expirySeconds);
        }
        return frame(
                IdsSocketPairCodec.COMMAND_PROTOBUF,
                body.toByteArray());
    }

    static byte[] encodeAppAck(
            IdsSocketPairCodec.AppAckMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "IDS AppAck message is null");
        }
        message.requireValid();
        ByteArrayOutputStream body =
                new ByteArrayOutputStream();
        writeU32(
                body,
                message.sequence);
        writeU16(
                body,
                message.streamId);
        writeStringU32(
                body,
                message.peerResponseIdentifier);
        if (message.topic != null) {
            writeStringU32(
                    body,
                    message.topic);
        }
        return frame(
                IdsSocketPairCodec.COMMAND_APP_ACK,
                body.toByteArray());
    }

    static byte[] encodeServiceMap(
            IdsSocketPairCodec.ServiceMapMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "IDS ServiceMap message is null");
        }
        message.requireValid();
        ByteArrayOutputStream body =
                new ByteArrayOutputStream();
        writeTlv(
                body,
                1,
                new byte[]{
                        (byte) message.reason
                });
        ByteArrayOutputStream stream =
                new ByteArrayOutputStream(2);
        writeU16(
                stream,
                message.streamId);
        writeTlv(
                body,
                2,
                stream.toByteArray());
        writeTlv(
                body,
                3,
                encodeString(
                        message.serviceName));
        return frame(
                IdsSocketPairCodec.COMMAND_SERVICE_MAP,
                body.toByteArray());
    }

    static List<byte[]> fragment(
            long fragmentedMessageId,
            byte[] originalFrame) {
        requireU32(
                "fragmented message ID",
                fragmentedMessageId);
        if (originalFrame == null
                || originalFrame.length <= IdsSocketPairCodec.FRAGMENT_FRAME_LIMIT
                || originalFrame.length > IdsSocketPairCodec.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Only complete socket-pair frames above 8000 bytes "
                            + "require fragmentation");
        }
        // Validate before wrapping it as fragments.
        IdsSocketPairCodec.Message original =
                decode(
                        originalFrame);
        original.destroy();

        int count =
                (originalFrame.length
                        + IdsSocketPairCodec.FRAGMENT_SLICE_CAPACITY
                        - 1)
                        / IdsSocketPairCodec.FRAGMENT_SLICE_CAPACITY;
        List<byte[]> output =
                new ArrayList<>(
                        count);
        for (int index = 0;
                index < count;
                index++) {
            int start =
                    index
                            * IdsSocketPairCodec.FRAGMENT_SLICE_CAPACITY;
            int end =
                    Math.min(
                            originalFrame.length,
                            start
                                    + IdsSocketPairCodec.FRAGMENT_SLICE_CAPACITY);
            ByteArrayOutputStream body =
                    new ByteArrayOutputStream(
                            IdsSocketPairCodec.FRAGMENT_METADATA_LENGTH
                                    + end
                                    - start);
            writeU32(
                    body,
                    fragmentedMessageId);
            writeU32(
                    body,
                    index);
            writeU32(
                    body,
                    count);
            body.write(
                    originalFrame,
                    start,
                    end - start);
            output.add(
                    frame(
                            IdsSocketPairCodec.COMMAND_FRAGMENT,
                            body.toByteArray()));
        }
        return output;
    }

    static IdsSocketPairCodec.Message decode(
            byte[] frame) {
        if (frame == null
                || frame.length < IdsSocketPairCodec.HEADER_LENGTH
                || frame.length > IdsSocketPairCodec.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS socket-pair frame length is invalid");
        }
        long bodyLength =
                readU32(
                        frame,
                        1);
        if (bodyLength
                != frame.length
                - IdsSocketPairCodec.HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS socket-pair body length mismatch");
        }
        int command =
                frame[0] & 0xff;
        byte[] body =
                Arrays.copyOfRange(
                        frame,
                        IdsSocketPairCodec.HEADER_LENGTH,
                        frame.length);
        try {
            return switch (command) {
                case IdsSocketPairCodec.COMMAND_DATA,
                        IdsSocketPairCodec.COMMAND_DICTIONARY,
                        IdsSocketPairCodec.COMMAND_RESOURCE_TRANSFER ->
                        parseData(
                                command,
                                body);
                case IdsSocketPairCodec.COMMAND_ACK,
                        IdsSocketPairCodec.COMMAND_EXPIRED_ACK ->
                        parseAck(
                                command,
                                body);
                case IdsSocketPairCodec.COMMAND_KEEP_ALIVE ->
                        parseKeepAlive(
                                body);
                case IdsSocketPairCodec.COMMAND_PROTOBUF ->
                        parseProtobuf(
                                body);
                case IdsSocketPairCodec.COMMAND_HANDSHAKE ->
                        parseHandshake(
                                body);
                case IdsSocketPairCodec.COMMAND_APP_ACK ->
                        parseAppAck(
                                body);
                case IdsSocketPairCodec.COMMAND_FRAGMENT ->
                        parseFragment(
                                body);
                case IdsSocketPairCodec.COMMAND_SERVICE_MAP ->
                        parseServiceMap(
                                body);
                // The five-byte frame header still bounds an extension we do
                // not implement. Surface it without interpreting or ACKing it;
                // unrelated extension traffic must not poison the IDS session.
                // Keep a short body hex prefix so live logs can identify the
                // extension (live 0.2.167: Watch sent unknown command 0x16).
                default -> new IdsSocketPairCodec.UnknownMessage(command, hexPrefix(body, 48));
            };
        } finally {
            wipe(body);
        }
    }

    private static IdsSocketPairCodec.DataMessage parseData(
            int command,
            byte[] body) {
        Cursor cursor =
                new Cursor(
                        body);
        CommonData common =
                parseCommonData(
                        cursor);
        int payloadEnd =
                body.length
                        - (common.expiryPresent
                        ? 4
                        : 0);
        if (payloadEnd < cursor.offset) {
            throw new IllegalArgumentException(
                    "IDS Data payload/expiry is truncated");
        }
        byte[] rawPayload =
                Arrays.copyOfRange(
                        body,
                        cursor.offset,
                        payloadEnd);
        Long expiry =
                common.expiryPresent
                        ? readU32(
                                body,
                                payloadEnd)
                        : null;
        byte[] payload =
                command == IdsSocketPairCodec.COMMAND_RESOURCE_TRANSFER
                        ? decompressResourcePayload(rawPayload, common.flags)
                        : decompressPayload(
                        rawPayload,
                        common.flags);
        if (command == IdsSocketPairCodec.COMMAND_RESOURCE_TRANSFER && payload.length != 4008
                && !(payload.length > 3 && payload[0] == 1 && payload[1] == 0x1f
                && (payload[2] & 255) == 0x8b)) {
            captureResourceWireSample(command, body, common.messageUuid);
        }
        int flags =
                (payload != rawPayload)
                        ? (common.flags & ~IdsSocketPairCodec.FLAG_COMPRESSED)
                        : common.flags;
        if (payload != rawPayload) {
            wipe(rawPayload);
        }
        try {
            return new IdsSocketPairCodec.DataMessage(
                    command,
                    common.sequence,
                    common.streamId,
                    flags,
                    common.peerResponseIdentifier,
                    common.messageUuid,
                    common.topic,
                    payload,
                    expiry);
        } finally {
            wipe(payload);
        }
    }

    private static IdsSocketPairCodec.ProtobufMessage parseProtobuf(
            byte[] body) {
        Cursor cursor =
                new Cursor(
                        body);
        CommonData common =
                parseCommonData(
                        cursor);
        int protobufType =
                cursor.readU16();
        int isResponse =
                cursor.readU16();
        if (isResponse > 1) {
            throw new IllegalArgumentException(
                    "IDS Protobuf response field is not boolean");
        }
        long payloadLength =
                cursor.readU32();
        int expiryLength =
                common.expiryPresent
                        ? 4
                        : 0;
        if (payloadLength > Integer.MAX_VALUE
                || cursor.remaining()
                != (int) payloadLength
                + expiryLength) {
            throw new IllegalArgumentException(
                    "IDS Protobuf payload length mismatch");
        }
        byte[] rawPayload =
                cursor.readBytes(
                        (int) payloadLength);
        Long expiry =
                common.expiryPresent
                        ? cursor.readU32()
                        : null;
        cursor.requireEnd();
        byte[] payload =
                decompressPayload(
                        rawPayload,
                        common.flags);
        int flags =
                (payload != rawPayload)
                        ? (common.flags & ~IdsSocketPairCodec.FLAG_COMPRESSED)
                        : common.flags;
        if (payload != rawPayload) {
            wipe(rawPayload);
        }
        try {
            return new IdsSocketPairCodec.ProtobufMessage(
                    common.sequence,
                    common.streamId,
                    flags,
                    common.peerResponseIdentifier,
                    common.messageUuid,
                    common.topic,
                    protobufType,
                    isResponse == 1,
                    payload,
                    expiry);
        } finally {
            wipe(payload);
        }
    }

    static byte[] decompressPayload(
            byte[] payload,
            int flags) {
        if (payload == null || payload.length < 2) {
            return payload;
        }
        boolean isGzip = (payload[0] == 0x1f) && (payload[1] == (byte) 0x8b);
        boolean isZlib = (payload[0] == 0x78)
                && (payload[1] == 0x01 || payload[1] == 0x5e || payload[1] == (byte) 0x9c || payload[1] == (byte) 0xda);
        boolean flaggedCompressed = (flags & IdsSocketPairCodec.FLAG_COMPRESSED) != 0;
        if (!isGzip && !isZlib && !flaggedCompressed) {
            return payload;
        }
        if (isGzip) {
            try (GZIPInputStream gzip =
                         new GZIPInputStream(
                                 new ByteArrayInputStream(payload));
                 ByteArrayOutputStream out =
                         new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = gzip.read(buffer)) > 0) {
                    if (out.size() + n > IdsSocketPairCodec.MAX_FRAME_LENGTH) {
                        throw new IllegalArgumentException(
                                "Decompressed IDS payload exceeds max length");
                    }
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            } catch (IOException ex) {
                // If GZIP fails, fall through to Inflater
            }
        }
        try {
            Inflater inflater = new Inflater(false);
            inflater.setInput(payload);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                if (out.size() + count > IdsSocketPairCodec.MAX_FRAME_LENGTH) {
                    inflater.end();
                    throw new IllegalArgumentException(
                            "Decompressed IDS payload exceeds max length");
                }
                out.write(buffer, 0, count);
            }
            boolean complete = inflater.finished();
            inflater.end();
            if (complete && out.size() > 0) {
                return out.toByteArray();
            }
        } catch (DataFormatException ex) {
            try {
                Inflater rawInflater = new Inflater(true);
                rawInflater.setInput(payload);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                while (!rawInflater.finished()) {
                    int count = rawInflater.inflate(buffer);
                    if (count == 0) {
                        if (rawInflater.needsInput() || rawInflater.needsDictionary()) {
                            break;
                        }
                    }
                    if (out.size() + count > IdsSocketPairCodec.MAX_FRAME_LENGTH) {
                        rawInflater.end();
                        throw new IllegalArgumentException(
                                "Decompressed IDS payload exceeds max length");
                    }
                    out.write(buffer, 0, count);
                }
                boolean complete = rawInflater.finished();
                rawInflater.end();
                if (complete && out.size() > 0) {
                    return out.toByteArray();
                }
            } catch (DataFormatException ex2) {
                // Decompression failed; return original payload
            }
        }
        return payload;
    }

    private static byte[] decompressResourcePayload(byte[] payload, int flags) {
        if ((flags & IdsSocketPairCodec.FLAG_COMPRESSED) == 0) return payload;
        // Modern native fixtures compress the entire header/chunk. Keep that
        // branch distinct from Watch's uncompressed offset + compressed data.
        if (payload.length >= 2 && ((payload[0] == 0x1f && (payload[1] & 255) == 0x8b)
                || payload[0] == 0x78)) {
            return decompressPayload(payload, flags);
        }
        // 23S303 receiver strips the offset before its compressed-data call.
        // Live 328: BE64(372000) + gzip(4000 bytes), wire payload3734B.
        // Initial JW plist starts 01+gzip and has no external offset.
        if (payload.length < 8
                || ByteBuffer.wrap(payload, 0, 8).getLong() < 0
                || ByteBuffer.wrap(payload, 0, 8).getLong() > IdsResourceTransferStore.MAX_FILE_BYTES) {
            return payload;
        }
        byte[] compressed = Arrays.copyOfRange(payload, 8, payload.length);
        byte[] plain = null;
        try {
            plain = decompressPayload(compressed, flags);
            if (plain == compressed) {
                throw new IllegalArgumentException("Compressed resource chunk is incomplete or invalid");
            }
            byte[] result = Arrays.copyOf(payload, 8 + plain.length);
            System.arraycopy(plain, 0, result, 8, plain.length);
            return result;
        } finally {
            wipe(compressed);
            if (plain != compressed) wipe(plain);
        }
    }

    /** First unusual resource frame only, private diagnostic evidence. */
    private static void captureResourceWireSample(int command, byte[] body, String uuid) {
        if (uuid == null || !uuid.matches("[A-Fa-f0-9\\-]{36}") || body.length > 1024 * 1024) return;
        try {
            java.nio.file.Path dir = PairedSyncResourceStore.directory.toPath().resolve("wire-samples");
            java.nio.file.Files.createDirectories(PairedSyncResourceStore.directory.toPath());
            if (!java.nio.file.Files.exists(dir)) java.nio.file.Files.createDirectory(dir,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            java.nio.file.Path file = dir.resolve(uuid + "-first-unusual.frame");
            if (java.nio.file.Files.exists(file)) return;
            java.nio.file.Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            byte[] frame = ByteBuffer.allocate(IdsSocketPairCodec.HEADER_LENGTH + body.length)
                    .put((byte) command).putInt(body.length).put(body).array();
            try { java.nio.file.Files.write(file, frame); } finally { wipe(frame); }
        } catch (IOException | RuntimeException ignored) {
            // Diagnostic capture must not change delivery or acknowledgements.
        }
    }

    private static IdsSocketPairCodec.Message parseAck(
            int command,
            byte[] body) {
        if (body.length != 4) {
            throw new IllegalArgumentException(
                    "IDS Ack body must contain one uint32");
        }
        return new IdsSocketPairCodec.AckMessage(
                command,
                readU32(
                        body,
                        0));
    }

    private static IdsSocketPairCodec.Message parseKeepAlive(
            byte[] body) {
        if (body.length != 0) {
            throw new IllegalArgumentException(
                    "IDS KeepAlive body is not empty");
        }
        return new IdsSocketPairCodec.KeepAliveMessage();
    }

    private static IdsSocketPairCodec.Message parseHandshake(
            byte[] body) {
        if (body.length != 4) {
            throw new IllegalArgumentException(
                    "IDS Handshake body must contain one uint32");
        }
        return new IdsSocketPairCodec.HandshakeMessage(
                readU32(
                        body,
                        0));
    }

    private static IdsSocketPairCodec.Message parseAppAck(
            byte[] body) {
        Cursor cursor =
                new Cursor(
                        body);
        long sequence =
                cursor.readU32();
        int streamId =
                cursor.readU16();
        String peerResponse =
                cursor.readStringU32(
                        true);
        String topic =
                cursor.remaining() == 0
                        ? null
                        : cursor.readStringU32(
                                false);
        cursor.requireEnd();
        return new IdsSocketPairCodec.AppAckMessage(
                sequence,
                streamId,
                peerResponse,
                topic);
    }

    private static IdsSocketPairCodec.Message parseFragment(
            byte[] body) {
        if (body.length
                < IdsSocketPairCodec.FRAGMENT_METADATA_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS Fragment body is truncated");
        }
        long messageId =
                readU32(
                        body,
                        0);
        long index =
                readU32(
                        body,
                        4);
        long total =
                readU32(
                        body,
                        8);
        if (total == 0
                || total > Integer.MAX_VALUE
                || index >= total
                || body.length
                - IdsSocketPairCodec.FRAGMENT_METADATA_LENGTH
                > IdsSocketPairCodec.FRAGMENT_SLICE_CAPACITY) {
            throw new IllegalArgumentException(
                    "IDS Fragment metadata is invalid");
        }
        byte[] slice =
                Arrays.copyOfRange(
                        body,
                        IdsSocketPairCodec.FRAGMENT_METADATA_LENGTH,
                        body.length);
        try {
            return new IdsSocketPairCodec.FragmentMessage(
                    messageId,
                    (int) index,
                    (int) total,
                    slice);
        } finally {
            wipe(slice);
        }
    }

    private static IdsSocketPairCodec.Message parseServiceMap(
            byte[] body) {
        Cursor cursor =
                new Cursor(
                        body);
        Map<Integer, byte[]> fields =
                new HashMap<>();
        try {
            while (cursor.remaining() != 0) {
                int field =
                        cursor.readU8();
                int length =
                        cursor.readU16();
                byte[] value = cursor.readBytes(length);
                // IDSFoundation 23G71, -[IDSSocketPairServiceMapMessage
                // initWithCommand:underlyingData:] (0x1a9701f30), skips unknown
                // fields. Their declared lengths still delimit the next TLV.
                if (field < 1 || field > 3) {
                    wipe(value);
                    continue;
                }
                if (fields.containsKey(field)) {
                    wipe(value);
                    throw new IllegalArgumentException(
                        "IDS ServiceMap TLV ID is invalid/duplicated");
                }
                fields.put(field, value);
            }
            byte[] reason =
                    fields.get(1);
            byte[] stream =
                    fields.get(2);
            byte[] service =
                    fields.get(3);
            if (reason == null
                    || reason.length != 1
                    || stream == null
                    || stream.length != 2
                    || service == null) {
                throw new IllegalArgumentException(
                        "IDS ServiceMap lacks required TLVs");
            }
            return new IdsSocketPairCodec.ServiceMapMessage(
                    reason[0] & 0xff,
                    readU16(
                            stream,
                            0),
                    decodeString(
                            service,
                            false));
        } finally {
            for (byte[] value : fields.values()) {
                wipe(value);
            }
        }
    }

    private static CommonData parseCommonData(
            Cursor cursor) {
        long sequence =
                cursor.readU32();
        int streamId =
                cursor.readU16();
        int flags =
                cursor.readU8();
        if ((flags & ~IdsSocketPairCodec.KNOWN_DATA_FLAGS) != 0) {
            throw new IllegalArgumentException(
                    "IDS data flags contain unknown bits");
        }
        String peerResponse =
                cursor.readNamedStringU32("peerResponseIdentifier", true);
        String messageUuid =
                cursor.readNamedStringU32("messageUuid", false);
        requireCanonicalUuid(
                messageUuid);
        String topic =
                (flags & IdsSocketPairCodec.FLAG_HAS_TOPIC) != 0
                        ? cursor.readNamedStringU32("topic", false)
                        : null;
        return new CommonData(
                sequence,
                streamId,
                flags,
                peerResponse,
                messageUuid,
                topic,
                (flags & IdsSocketPairCodec.FLAG_HAS_EXPIRY) != 0);
    }

    private static ByteArrayOutputStream commonDataPrefix(
            long sequence,
            int streamId,
            int flags,
            String peerResponseIdentifier,
            String messageUuid,
            String topic) {
        ByteArrayOutputStream body =
                new ByteArrayOutputStream();
        writeU32(
                body,
                sequence);
        writeU16(
                body,
                streamId);
        body.write(flags);
        writeStringU32(
                body,
                peerResponseIdentifier);
        writeStringU32(
                body,
                messageUuid);
        if ((flags & IdsSocketPairCodec.FLAG_HAS_TOPIC) != 0) {
            writeStringU32(
                    body,
                    topic);
        }
        return body;
    }

    private static byte[] frame(
            int command,
            byte[] body) {
        if (body.length
                + IdsSocketPairCodec.HEADER_LENGTH
                > IdsSocketPairCodec.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS socket-pair frame exceeds safety limit");
        }
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        IdsSocketPairCodec.HEADER_LENGTH
                                + body.length);
        output.write(command);
        writeU32(
                output,
                body.length);
        output.writeBytes(
                body);
        return output.toByteArray();
    }

    private static void writeTlv(
            ByteArrayOutputStream output,
            int field,
            byte[] value) {
        if (value.length > 0xffff) {
            throw new IllegalArgumentException(
                    "IDS TLV exceeds uint16");
        }
        output.write(field);
        writeU16(
                output,
                value.length);
        output.writeBytes(
                value);
    }

    private static void writeStringU32(
            ByteArrayOutputStream output,
            String value) {
        byte[] encoded =
                value == null
                        ? new byte[0]
                        : encodeString(
                                value);
        try {
            writeU32(
                    output,
                    encoded.length);
            output.writeBytes(
                    encoded);
        } finally {
            wipe(encoded);
        }
    }

    static byte[] encodeString(
            String value) {
        if (value == null
                || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Required IDS string is empty");
        }
        byte[] encoded =
                value.getBytes(
                        StandardCharsets.UTF_8);
        if (encoded.length > IdsSocketPairCodec.MAX_STRING_LENGTH) {
            wipe(encoded);
            throw new IllegalArgumentException(
                    "IDS UTF-8 string exceeds safety limit");
        }
        return encoded;
    }

    private static String decodeString(
            byte[] bytes,
            boolean allowEmpty) {
        if ((!allowEmpty && bytes.length == 0)
                || bytes.length > IdsSocketPairCodec.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS UTF-8 string length is invalid");
        }
        try {
            CharBuffer decoded =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(
                                    CodingErrorAction.REPORT)
                            .onUnmappableCharacter(
                                    CodingErrorAction.REPORT)
                            .decode(
                                    ByteBuffer.wrap(
                                            bytes));
            String value =
                    decoded.toString();
            return value.isEmpty()
                    ? null
                    : value;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(
                    "IDS string is not valid UTF-8",
                    error);
        }
    }

    static void requireCanonicalUuid(
            String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "IDS message UUID is absent");
        }
        try {
            UUID parsed =
                    UUID.fromString(
                            value);
            if (!parsed.toString()
                    .equalsIgnoreCase(
                            value)) {
                throw new IllegalArgumentException(
                        "IDS message UUID is not canonical");
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "IDS message UUID is invalid",
                    error);
        }
    }

    static void requireU32(
            String label,
            long value) {
        if (value < 0
                || value > 0xffffffffL) {
            throw new IllegalArgumentException(
                    label + " must fit uint32");
        }
    }

    static void requireU16(
            String label,
            int value) {
        if (value < 0
                || value > 0xffff) {
            throw new IllegalArgumentException(
                    label + " must fit uint16");
        }
    }

    static long readU32(
            byte[] bytes,
            int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xffL);
    }

    static int readU16(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    static void writeU16(
            ByteArrayOutputStream output,
            int value) {
        requireU16(
                "IDS uint16",
                value);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    static void writeU32(
            ByteArrayOutputStream output,
            long value) {
        requireU32(
                "IDS uint32",
                value);
        output.write((int) (value >>> 24) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) value & 0xff);
    }

    private static String hexPrefix(
            byte[] value,
            int maxBytes) {
        if (value == null || value.length == 0) {
            return "";
        }
        int limit =
                Math.min(
                        value.length,
                        maxBytes);
        StringBuilder out =
                new StringBuilder(
                        limit * 2);
        for (int i = 0;
                i < limit;
                i++) {
            out.append(
                    String.format(
                            "%02x",
                            value[i]));
        }
        return out.toString();
    }

    static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static void validateCommonMessage(
            long sequence,
            int streamId,
            int flags,
            String messageUuid,
            String topic,
            byte[] payload,
            Long expirySeconds) {
        requireU32(
                "IDS message sequence",
                sequence);
        requireU16(
                "IDS stream ID",
                streamId);
        if ((flags & ~IdsSocketPairCodec.KNOWN_DATA_FLAGS) != 0
                || payload == null) {
            throw new IllegalArgumentException(
                    "IDS common message fields are invalid");
        }
        requireCanonicalUuid(
                messageUuid);
        boolean hasTopic =
                (flags & IdsSocketPairCodec.FLAG_HAS_TOPIC) != 0;
        if (hasTopic != (topic != null)) {
            throw new IllegalArgumentException(
                    "IDS topic flag/value disagree");
        }
        if (topic != null) {
            byte[] encoded =
                    encodeString(
                            topic);
            wipe(encoded);
        }
        boolean hasExpiry =
                (flags & IdsSocketPairCodec.FLAG_HAS_EXPIRY) != 0;
        if (hasExpiry != (expirySeconds != null)) {
            throw new IllegalArgumentException(
                    "IDS expiry flag/value disagree");
        }
        if (expirySeconds != null) {
            requireU32(
                    "IDS expiry",
                    expirySeconds);
        }
    }

    static String emptyToNull(
            String value) {
        return value == null
                || value.isEmpty()
                ? null
                : value;
    }

    private static final class CommonData {
        final long sequence;
        final int streamId;
        final int flags;
        final String peerResponseIdentifier;
        final String messageUuid;
        final String topic;
        final boolean expiryPresent;

        CommonData(
                long sequence,
                int streamId,
                int flags,
                String peerResponseIdentifier,
                String messageUuid,
                String topic,
                boolean expiryPresent) {
            this.sequence = sequence;
            this.streamId = streamId;
            this.flags = flags;
            this.peerResponseIdentifier =
                    peerResponseIdentifier;
            this.messageUuid = messageUuid;
            this.topic = topic;
            this.expiryPresent = expiryPresent;
        }
    }

    private static final class Cursor {
        final byte[] bytes;
        int offset;

        Cursor(
                byte[] bytes) {
            this.bytes = bytes;
        }

        int readU8() {
            requireRemaining(1);
            return bytes[offset++] & 0xff;
        }

        int readU16() {
            requireRemaining(2);
            int value =
                    IdsSocketPairWireCodec.readU16(
                            bytes,
                            offset);
            offset += 2;
            return value;
        }

        long readU32() {
            requireRemaining(4);
            long value =
                    IdsSocketPairWireCodec.readU32(
                            bytes,
                            offset);
            offset += 4;
            return value;
        }

        byte[] readBytes(
                int length) {
            requireRemaining(
                    length);
            byte[] output =
                    Arrays.copyOfRange(
                            bytes,
                            offset,
                            offset + length);
            offset += length;
            return output;
        }

        String readNamedStringU32(String field, boolean allowEmpty) {
            int start = offset;
            try {
                return readStringU32(allowEmpty);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("IDS " + field + " at body offset "
                        + start + ": " + error.getMessage(), error);
            }
        }

        String readStringU32(
                boolean allowEmpty) {
            long length =
                    readU32();
            if (length > IdsSocketPairCodec.MAX_STRING_LENGTH) {
                throw new IllegalArgumentException(
                        "IDS string length exceeds safety limit");
            }
            byte[] encoded =
                    readBytes(
                            (int) length);
            try {
                return decodeString(
                        encoded,
                        allowEmpty);
            } finally {
                wipe(encoded);
            }
        }

        int remaining() {
            return bytes.length - offset;
        }

        void requireEnd() {
            if (remaining() != 0) {
                throw new IllegalArgumentException(
                        "IDS message contains trailing bytes");
            }
        }

        private void requireRemaining(
                int length) {
            if (length < 0
                    || length > remaining()) {
                throw new IllegalArgumentException(
                        "IDS message is truncated");
            }
        }
    }
}
