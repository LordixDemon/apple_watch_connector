package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


/**
 * Wire encoder, decoder, TLV parser, and {@code Cursor} primitives for
 * {@link IdsControlChannelCodec}.
 */
public final class IdsControlChannelWireCodec {
    private static final int SETUP_HEADER_LENGTH = 16;
    private static final int ENCRYPTED_SETUP_HEADER_LENGTH = 24;
    private static final int UUID_LENGTH = 16;
    private static final int MAX_STRING_LENGTH = 0xffff;

    private IdsControlChannelWireCodec() {
    }

    static byte[] encodeFramed(
            IdsControlChannelCodec.Message message) {
        byte[] payload =
                encodePayload(
                        message);
        try {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream(
                            IdsControlChannelCodec.OUTER_HEADER_LENGTH
                                    + payload.length);
            writeU16(
                    output,
                    payload.length);
            output.writeBytes(
                    payload);
            return output.toByteArray();
        } finally {
            wipe(payload);
        }
    }

    static byte[] encodePayload(
            IdsControlChannelCodec.Message message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "IDS control message is null");
        }
        if (message instanceof IdsControlChannelCodec.HelloMessage hello) {
            return encodeHello(
                    hello);
        }
        if (message instanceof IdsControlChannelCodec.SetupChannelMessage setup) {
            return encodeSetupChannel(
                    setup);
        }
        if (message
                instanceof IdsControlChannelCodec.SetupEncryptedChannelMessage encrypted) {
            return encodeSetupEncryptedChannel(
                    encrypted);
        }
        if (message instanceof IdsControlChannelCodec.DirectMsgInfoMessage direct) {
            if (direct.info.length > IdsControlChannelCodec.MAX_PAYLOAD_LENGTH - 1) {
                throw new IllegalArgumentException("IDS direct messaging information is too large");
            }
            byte[] payload = new byte[direct.info.length + 1];
            payload[0] = (byte) IdsControlChannelCodec.TYPE_DIRECT_MSG_INFO;
            System.arraycopy(direct.info, 0, payload, 1, direct.info.length);
            return payload;
        }
        throw new IllegalArgumentException(
                "Unsupported IDS control message class");
    }

    static IdsControlChannelCodec.Message decodeFramed(
            byte[] frame) {
        if (frame == null
                || frame.length < 3) {
            throw new IllegalArgumentException(
                    "IDS control frame is truncated");
        }
        int declared =
                readU16(
                        frame,
                        0);
        if (declared
                != frame.length
                - IdsControlChannelCodec.OUTER_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS control outer length mismatch");
        }
        byte[] payload =
                Arrays.copyOfRange(
                        frame,
                        IdsControlChannelCodec.OUTER_HEADER_LENGTH,
                        frame.length);
        try {
            return decodePayload(
                    payload);
        } finally {
            wipe(payload);
        }
    }

    static IdsControlChannelCodec.Message decodePayload(
            byte[] payload) {
        if (payload == null
                || payload.length == 0
                || payload.length > IdsControlChannelCodec.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS control payload length is invalid");
        }
        return switch (payload[0] & 0xff) {
            case IdsControlChannelCodec.TYPE_HELLO ->
                    parseHello(
                            payload);
            case IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                    IdsControlChannelCodec.TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE ->
                    parseSetupChannel(
                            payload);
            case IdsControlChannelCodec.TYPE_SETUP_ENCRYPTED_CHANNEL ->
                    parseSetupEncryptedChannel(
                            payload);
            case IdsControlChannelCodec.TYPE_DIRECT_MSG_INFO ->
                    parseDirectMsgInfo(
                            payload);
            default -> new IdsControlChannelCodec.GenericControlMessage(
                    payload[0] & 0xff,
                    payload);
        };
    }

    private static byte[] encodeHello(
            IdsControlChannelCodec.HelloMessage message) {
        message.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        output.write(IdsControlChannelCodec.TYPE_HELLO);
        writeStringU16(
                output,
                message.controlChannelVersion,
                false);
        writeStringU16(
                output,
                message.productName,
                false);
        writeStringU16(
                output,
                message.productVersion,
                false);
        writeStringU16(
                output,
                message.productBuildVersion,
                false);
        writeStringU16(
                output,
                message.model,
                false);
        writeU32(
                output,
                message.pairingProtocolVersion);

        writeTlvU32(
                output,
                0,
                message.minCompatibilityVersion);
        writeTlvU32(
                output,
                1,
                message.maxCompatibilityVersion);
        writeTlvUuid(
                output,
                2,
                message.instanceId);
        writeTlvUuid(
                output,
                5,
                message.deviceUniqueId);
        ByteArrayOutputStream capability =
                new ByteArrayOutputStream(8);
        writeU64(
                capability,
                message.capabilityFlags);
        byte[] capabilityBytes =
                capability.toByteArray();
        try {
            writeTlv(
                    output,
                    3,
                    capabilityBytes);
        } finally {
            wipe(capabilityBytes);
        }
        ByteArrayOutputStream serviceMin =
                new ByteArrayOutputStream(2);
        writeU16(
                serviceMin,
                message.serviceMinCompatibilityVersion);
        byte[] serviceMinBytes =
                serviceMin.toByteArray();
        try {
            writeTlv(
                    output,
                    4,
                    serviceMinBytes);
        } finally {
            wipe(serviceMinBytes);
        }
        return checkedPayload(
                output);
    }

    private static byte[] encodeSetupChannel(
            IdsControlChannelCodec.SetupChannelMessage message) {
        message.requireValid();
        byte[] remoteGuid =
                encodeString(
                        message.remoteConnectionGuid,
                        false);
        byte[] forLocalGuid =
                encodeString(
                        message.forLocalGuid,
                        true);
        byte[] account =
                encodeString(
                        message.account,
                        false);
        byte[] service =
                encodeString(
                        message.service,
                        false);
        byte[] name =
                encodeString(
                        message.name,
                        false);
        try {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            output.write(message.type);
            output.write(message.protocol);
            writeU16(
                    output,
                    message.localPort);
            writeU16(
                    output,
                    message.remotePort);
            writeU16(
                    output,
                    remoteGuid.length);
            writeU16(
                    output,
                    forLocalGuid.length);
            writeU16(
                    output,
                    account.length);
            writeU16(
                    output,
                    service.length);
            writeU16(
                    output,
                    name.length);
            output.writeBytes(
                    remoteGuid);
            output.writeBytes(
                    forLocalGuid);
            output.writeBytes(
                    account);
            output.writeBytes(
                    service);
            output.writeBytes(
                    name);
            if (message.directFlags != null) {
                output.writeBytes(
                        message.directFlags);
            }
            return checkedPayload(
                    output);
        } finally {
            wipe(remoteGuid);
            wipe(forLocalGuid);
            wipe(account);
            wipe(service);
            wipe(name);
        }
    }

    private static byte[] encodeSetupEncryptedChannel(
            IdsControlChannelCodec.SetupEncryptedChannelMessage message) {
        message.requireValid();
        byte[] remoteGuid =
                encodeString(
                        message.remoteConnectionGuid,
                        false);
        byte[] forLocalGuid =
                encodeString(
                        message.forLocalGuid,
                        true);
        byte[] account =
                encodeString(
                        message.account,
                        false);
        byte[] service =
                encodeString(
                        message.service,
                        false);
        byte[] name =
                encodeString(
                        message.name,
                        false);
        try {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            output.write(message.type);
            output.write(message.protocol);
            writeU16(
                    output,
                    message.localPort);
            writeU16(
                    output,
                    message.remotePort);
            writeU16(
                    output,
                    remoteGuid.length);
            writeU16(
                    output,
                    forLocalGuid.length);
            writeU16(
                    output,
                    account.length);
            writeU16(
                    output,
                    service.length);
            writeU16(
                    output,
                    name.length);
            writeU32(
                    output,
                    message.ssrc);
            writeU16(
                    output,
                    message.startSequence);
            writeU16(
                    output,
                    message.keyMaterial.length);
            output.writeBytes(
                    remoteGuid);
            output.writeBytes(
                    forLocalGuid);
            output.writeBytes(
                    account);
            output.writeBytes(
                    service);
            output.writeBytes(
                    name);
            output.writeBytes(
                    message.keyMaterial);
            return checkedPayload(
                    output);
        } finally {
            wipe(remoteGuid);
            wipe(forLocalGuid);
            wipe(account);
            wipe(service);
            wipe(name);
        }
    }

    private static IdsControlChannelCodec.HelloMessage parseHello(
            byte[] payload) {
        Cursor cursor =
                new Cursor(
                        payload);
        cursor.requireU8(
                IdsControlChannelCodec.TYPE_HELLO);
        String controlVersion =
                cursor.readStringU16(
                        false);
        String productName =
                cursor.readStringU16(
                        false);
        String productVersion =
                cursor.readStringU16(
                        false);
        String productBuildVersion =
                cursor.readStringU16(
                        false);
        String model =
                cursor.readStringU16(
                        false);
        long pairingProtocolVersion =
                cursor.readU32();
        Map<Integer, byte[]> fields =
                new HashMap<>();
        try {
            while (cursor.remaining() != 0) {
                int id =
                        cursor.readU8();
                int length =
                        cursor.readU16();
                if (length < 0
                        || length > cursor.remaining()) {
                    throw new IllegalArgumentException(
                            "IDS Hello TLV length is invalid");
                }
                byte[] val =
                        cursor.readBytes(
                                length);
                if (!fields.containsKey(id)) {
                    fields.put(
                            id,
                            val);
                } else {
                    wipe(val);
                }
            }
            byte[] minimum =
                    requiredField(
                            fields,
                            0,
                            4);
            byte[] maximum =
                    requiredField(
                            fields,
                            1,
                            4);
            byte[] instance =
                    requiredField(
                            fields,
                            2,
                            UUID_LENGTH);
            byte[] device =
                    requiredField(
                            fields,
                            5,
                            UUID_LENGTH);
            byte[] capability =
                    requiredField(
                            fields,
                            3,
                            8);
            byte[] serviceMin =
                    requiredField(
                            fields,
                            4,
                            2);
            return new IdsControlChannelCodec.HelloMessage(
                    controlVersion,
                    productName,
                    productVersion,
                    productBuildVersion,
                    model,
                    pairingProtocolVersion,
                    readU32(
                            minimum,
                            0),
                    readU32(
                            maximum,
                            0),
                    readUuid(
                            instance),
                    readUuid(
                            device),
                    readU64(
                            capability,
                            0),
                    readU16(
                            serviceMin,
                            0));
        } finally {
            for (byte[] value : fields.values()) {
                wipe(value);
            }
        }
    }

    /**
     * Legacy-compatible Hello parser for watchOS 1–5 peers over {@code IDSBTLink}
     * where newer Hello TLVs ({@code 3} capability, {@code 4} serviceMin, {@code 5} deviceUUID)
     * may be omitted. Strategy 4 continues using the strict {@link #parseHello} parser.
     */
    static IdsControlChannelCodec.HelloMessage parseHelloLegacyLenient(
            Cursor cursor) {
        String controlVersion = cursor.readStringU16(false);
        String productName = cursor.readStringU16(false);
        String productVersion = cursor.readStringU16(false);
        String productBuildVersion = cursor.readStringU16(false);
        String model = cursor.readStringU16(false);
        long pairingProtocolVersion = cursor.readU32();
        Map<Integer, byte[]> fields = new HashMap<>();
        try {
            while (cursor.remaining() != 0) {
                int id = cursor.readU8();
                int length = cursor.readU16();
                if (length < 0 || length > cursor.remaining()) {
                    throw new IllegalArgumentException("Legacy IDS Hello TLV length is invalid");
                }
                byte[] val = cursor.readBytes(length);
                if (!fields.containsKey(id)) {
                    fields.put(id, val);
                } else {
                    wipe(val);
                }
            }
            byte[] minimum = fields.get(0);
            byte[] maximum = fields.get(1);
            byte[] instance = fields.get(2);
            byte[] capability = fields.get(3);
            byte[] serviceMin = fields.get(4);
            byte[] device = fields.get(5);
            long minVer = (minimum != null && minimum.length == 4) ? readU32(minimum, 0) : pairingProtocolVersion;
            long maxVer = (maximum != null && maximum.length == 4) ? readU32(maximum, 0) : pairingProtocolVersion;
            UUID instanceUuid = (instance != null && instance.length == UUID_LENGTH)
                    ? readUuid(instance) : new UUID(0L, 0L);
            UUID deviceUuid = (device != null && device.length == UUID_LENGTH)
                    ? readUuid(device) : instanceUuid;
            long capFlags = (capability != null && capability.length == 8) ? readU64(capability, 0) : 0L;
            int svcMin = (serviceMin != null && serviceMin.length == 2) ? readU16(serviceMin, 0) : 0;
            return new IdsControlChannelCodec.HelloMessage(
                    controlVersion,
                    productName,
                    productVersion,
                    productBuildVersion,
                    model,
                    pairingProtocolVersion,
                    minVer,
                    maxVer,
                    instanceUuid,
                    deviceUuid,
                    capFlags,
                    svcMin);
        } finally {
            for (byte[] value : fields.values()) {
                wipe(value);
            }
        }
    }

    /**
     * Decodes a framed or unframed IDS Control Hello payload using the lenient legacy parser
     * for watchOS 1–5 {@code IDSBTLink} frames (where TLVs 3, 4, and 5 may be absent).
     */
    public static IdsControlChannelCodec.HelloMessage decodeLegacyHelloPayload(byte[] payload) {
        if (payload == null || payload.length < 2) {
            throw new IllegalArgumentException("Legacy IDS Hello payload is truncated");
        }
        Cursor cursor = new Cursor(payload);
        int type = cursor.readU8();
        if (type != IdsControlChannelCodec.TYPE_HELLO) {
            throw new IllegalArgumentException("Expected IDS Control Hello type 0x01, got 0x"
                    + Integer.toHexString(type));
        }
        return parseHelloLegacyLenient(cursor);
    }

    private static IdsControlChannelCodec.SetupChannelMessage parseSetupChannel(
            byte[] payload) {
        if (payload.length < SETUP_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS SetupChannel header is truncated");
        }
        Cursor cursor =
                new Cursor(
                        payload);
        int type =
                cursor.readU8();
        if (type != IdsControlChannelCodec.TYPE_SETUP_CHANNEL
                && type != IdsControlChannelCodec.TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE) {
            throw new IllegalArgumentException(
                    "IDS setup channel type is invalid: 0x"
                            + Integer.toHexString(type));
        }
        int protocol =
                cursor.readU8();
        int localPort =
                cursor.readU16();
        int remotePort =
                cursor.readU16();
        int remoteGuidLength =
                cursor.readU16();
        int forLocalGuidLength =
                cursor.readU16();
        int accountLength =
                cursor.readU16();
        int serviceLength =
                cursor.readU16();
        int nameLength =
                cursor.readU16();
        int directLength =
                type == IdsControlChannelCodec.TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE
                        ? IdsControlChannelCodec.DIRECT_FLAGS_LENGTH
                        : 0;
        String remoteGuid =
                cursor.readString(
                        Math.min(remoteGuidLength, cursor.remaining()),
                        true);
        String forLocalGuid =
                cursor.readString(
                        Math.min(forLocalGuidLength, cursor.remaining()),
                        true);
        String account =
                cursor.readString(
                        Math.min(accountLength, cursor.remaining()),
                        true);
        String service =
                cursor.readString(
                        Math.min(serviceLength, cursor.remaining()),
                        true);
        String name =
                cursor.readString(
                        Math.min(nameLength, cursor.remaining()),
                        true);
        byte[] directFlags =
                (directLength > 0 && cursor.remaining() >= IdsControlChannelCodec.DIRECT_FLAGS_LENGTH)
                        ? cursor.readBytes(IdsControlChannelCodec.DIRECT_FLAGS_LENGTH)
                        : null;
        try {
            return new IdsControlChannelCodec.SetupChannelMessage(
                    type,
                    protocol,
                    localPort,
                    remotePort,
                    remoteGuid,
                    forLocalGuid,
                    account,
                    service,
                    name,
                    directFlags);
        } finally {
            wipe(directFlags);
        }
    }

    private static IdsControlChannelCodec.SetupEncryptedChannelMessage
            parseSetupEncryptedChannel(
                    byte[] payload) {
        if (payload.length
                < ENCRYPTED_SETUP_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS encrypted setup header is truncated");
        }
        Cursor cursor =
                new Cursor(
                        payload);
        int type = cursor.readU8();
        if (type != IdsControlChannelCodec.TYPE_SETUP_ENCRYPTED_CHANNEL) {
            throw new IllegalArgumentException(
                    "IDS encrypted setup type is invalid: 0x"
                            + Integer.toHexString(type));
        }
        int protocol =
                cursor.readU8();
        int localPort =
                cursor.readU16();
        int remotePort =
                cursor.readU16();
        int remoteGuidLength =
                cursor.readU16();
        int forLocalGuidLength =
                cursor.readU16();
        int accountLength =
                cursor.readU16();
        int serviceLength =
                cursor.readU16();
        int nameLength =
                cursor.readU16();
        long ssrc =
                cursor.readU32();
        int startSequence =
                cursor.readU16();
        int keyMaterialLength =
                cursor.readU16();
        String remoteGuid =
                cursor.readString(
                        remoteGuidLength,
                        true);
        String forLocalGuid =
                cursor.readString(
                        forLocalGuidLength,
                        true);
        String account =
                cursor.readString(
                        accountLength,
                        true);
        String service =
                cursor.readString(
                        serviceLength,
                        true);
        String name =
                cursor.readString(
                        nameLength,
                        true);
        int actualKeyLen =
                Math.max(
                        0,
                        Math.min(
                                keyMaterialLength,
                                cursor.remaining()));
        byte[] keyMaterial =
                cursor.readBytes(
                        actualKeyLen);
        try {
            return new IdsControlChannelCodec.SetupEncryptedChannelMessage(
                    type,
                    protocol,
                    localPort,
                    remotePort,
                    remoteGuid,
                    forLocalGuid,
                    account,
                    service,
                    name,
                    ssrc,
                    startSequence,
                    keyMaterial);
        } finally {
            wipe(keyMaterial);
        }
    }

    private static IdsControlChannelCodec.DirectMsgInfoMessage parseDirectMsgInfo(
            byte[] payload) {
        if (payload.length < 2) {
            throw new IllegalArgumentException(
                    "IDS DirectMsgInfo header is truncated");
        }
        return new IdsControlChannelCodec.DirectMsgInfoMessage(
                Arrays.copyOfRange(
                        payload,
                        1,
                        payload.length));
    }

    private static void requireExactVariableLength(
            Cursor cursor,
            int trailingLength,
            int... lengths) {
        long expected =
                trailingLength;
        for (int length : lengths) {
            expected += length;
        }
        if (expected > cursor.remaining()) {
            throw new IllegalArgumentException(
                    "IDS setup variable-field lengths exceed available payload");
        }
    }

    private static byte[] requiredField(
            Map<Integer, byte[]> fields,
            int id,
            int length) {
        byte[] value =
                fields.get(
                        id);
        if (value == null
                || value.length != length) {
            throw new IllegalArgumentException(
                    "IDS Hello TLV length/membership is invalid");
        }
        return value;
    }

    private static byte[] checkedPayload(
            ByteArrayOutputStream output) {
        byte[] payload =
                output.toByteArray();
        if (payload.length == 0
                || payload.length > IdsControlChannelCodec.MAX_PAYLOAD_LENGTH) {
            wipe(payload);
            throw new IllegalArgumentException(
                    "IDS control payload exceeds uint16");
        }
        return payload;
    }

    private static void writeStringU16(
            ByteArrayOutputStream output,
            String value,
            boolean allowEmpty) {
        byte[] encoded =
                encodeString(
                        value,
                        allowEmpty);
        try {
            writeU16(
                    output,
                    encoded.length);
            output.writeBytes(
                    encoded);
        } finally {
            wipe(encoded);
        }
    }

    private static byte[] encodeString(
            String value,
            boolean allowEmpty) {
        if (value == null) {
            if (allowEmpty) {
                return new byte[0];
            }
            throw new IllegalArgumentException(
                    "Required IDS control string is absent");
        }
        try {
            ByteBuffer buffer =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(
                                    CodingErrorAction.REPORT)
                            .onUnmappableCharacter(
                                    CodingErrorAction.REPORT)
                            .encode(
                                    CharBuffer.wrap(
                                            value));
            byte[] encoded =
                    new byte[
                            buffer.remaining()];
            buffer.get(
                    encoded);
            if ((!allowEmpty
                    && encoded.length == 0)
                    || encoded.length > MAX_STRING_LENGTH) {
                wipe(encoded);
                throw new IllegalArgumentException(
                        "IDS control string length is invalid");
            }
            return encoded;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                    "IDS control string is not valid UTF-8",
                    failure);
        }
    }

    private static String decodeString(
            byte[] encoded,
            boolean allowEmpty) {
        if (encoded == null || encoded.length == 0) {
            return null;
        }
        if (encoded.length > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS control string length is invalid");
        }
        try {
            String value =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(
                                    CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(
                                    CodingErrorAction.REPLACE)
                            .decode(
                                    ByteBuffer.wrap(
                                            encoded))
                            .toString();
            return value.isEmpty()
                    ? null
                    : value;
        } catch (CharacterCodingException failure) {
            return new String(encoded, StandardCharsets.UTF_8);
        }
    }

    static void requireCanonicalUuidString(
            String label,
            String value,
            boolean allowAbsent) {
        if (value == null) {
            if (allowAbsent) {
                return;
            }
            throw new IllegalArgumentException(
                    label + " is absent");
        }
        try {
            UUID parsed =
                    UUID.fromString(
                            value);
            if (!parsed.toString()
                    .equalsIgnoreCase(
                            value)) {
                throw new IllegalArgumentException(
                        label + " is not canonical");
            }
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    label + " is not a UUID",
                    failure);
        }
    }

    static void requireProtocol(
            int protocol) {
        if (protocol != IdsControlChannelCodec.PROTOCOL_TCP
                && protocol != IdsControlChannelCodec.PROTOCOL_UDP) {
            throw new IllegalArgumentException(
                    "IDS data protocol must be TCP or UDP");
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

    static void requireU32(
            String label,
            long value) {
        if (value < 0
                || value > 0xffffffffL) {
            throw new IllegalArgumentException(
                    label + " must fit uint32");
        }
    }

    private static void writeTlvU32(
            ByteArrayOutputStream output,
            int id,
            long value) {
        ByteArrayOutputStream encoded =
                new ByteArrayOutputStream(4);
        writeU32(
                encoded,
                value);
        byte[] bytes =
                encoded.toByteArray();
        try {
            writeTlv(
                    output,
                    id,
                    bytes);
        } finally {
            wipe(bytes);
        }
    }

    private static void writeTlvUuid(
            ByteArrayOutputStream output,
            int id,
            UUID value) {
        ByteArrayOutputStream encoded =
                new ByteArrayOutputStream(
                        UUID_LENGTH);
        writeU64(
                encoded,
                value.getMostSignificantBits());
        writeU64(
                encoded,
                value.getLeastSignificantBits());
        byte[] bytes =
                encoded.toByteArray();
        try {
            writeTlv(
                    output,
                    id,
                    bytes);
        } finally {
            wipe(bytes);
        }
    }

    private static void writeTlv(
            ByteArrayOutputStream output,
            int id,
            byte[] value) {
        output.write(id);
        writeU16(
                output,
                value.length);
        output.writeBytes(
                value);
    }

    private static void writeU16(
            ByteArrayOutputStream output,
            int value) {
        requireU16(
                "IDS control uint16",
                value);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeU32(
            ByteArrayOutputStream output,
            long value) {
        requireU32(
                "IDS control uint32",
                value);
        output.write((int) (value >>> 24) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) value & 0xff);
    }

    private static void writeU64(
            ByteArrayOutputStream output,
            long value) {
        for (int shift = 56;
                shift >= 0;
                shift -= 8) {
            output.write(
                    (int) (value >>> shift) & 0xff);
        }
    }

    static int readU16(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private static long readU32(
            byte[] bytes,
            int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xffL);
    }

    private static long readU64(
            byte[] bytes,
            int offset) {
        long value = 0;
        for (int index = 0;
                index < 8;
                index++) {
            value =
                    (value << 8)
                            | (bytes[offset + index] & 0xffL);
        }
        return value;
    }

    private static UUID readUuid(
            byte[] bytes) {
        return new UUID(
                readU64(
                        bytes,
                        0),
                readU64(
                        bytes,
                        8));
    }

    static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static void validateRequiredString(
            String value) {
        byte[] encoded =
                encodeString(
                        value,
                        false);
        wipe(encoded);
    }

    static String emptyToNull(
            String value) {
        return value == null
                || value.isEmpty()
                ? null
                : value;
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

        void requireU8(
                int expected) {
            if (readU8() != expected) {
                throw new IllegalArgumentException(
                        "IDS control message type mismatch");
            }
        }

        int readU16() {
            requireRemaining(2);
            int value =
                    IdsControlChannelWireCodec.readU16(
                            bytes,
                            offset);
            offset += 2;
            return value;
        }

        long readU32() {
            requireRemaining(4);
            long value =
                    IdsControlChannelWireCodec.readU32(
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

        String readStringU16(
                boolean allowEmpty) {
            return readString(
                    readU16(),
                    allowEmpty);
        }

        String readString(
                int length,
                boolean allowEmpty) {
            byte[] encoded =
                    readBytes(
                            length);
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
                        "IDS control message has trailing bytes");
            }
        }

        private void requireRemaining(
                int length) {
            if (length < 0
                    || length > remaining()) {
                throw new IllegalArgumentException(
                        "IDS control message is truncated");
            }
        }
    }
}
