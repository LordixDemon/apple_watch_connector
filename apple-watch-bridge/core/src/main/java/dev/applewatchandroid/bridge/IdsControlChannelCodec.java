package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
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

/**
 * iOS 26.6 IDS direct-IPsec control-channel wire codec.
 */
final class IdsControlChannelCodec {
    static final int TYPE_HELLO = 0x01;
    static final int TYPE_SETUP_CHANNEL = 0x02;
    static final int TYPE_CLOSE_CHANNEL = 0x03;
    static final int TYPE_COMPRESSION_REQUEST = 0x04;
    static final int TYPE_COMPRESSION_RESPONSE = 0x05;
    static final int TYPE_SETUP_ENCRYPTED_CHANNEL = 0x06;
    static final int TYPE_FAIRPLAY_HOST_SESSION_INFO = 0x07;
    static final int TYPE_FAIRPLAY_DEVICE_INFO = 0x08;
    static final int TYPE_FAIRPLAY_DEVICE_SESSION_INFO = 0x09;
    static final int TYPE_OTR_NEGOTIATION = 0x0a;
    static final int TYPE_ENCRYPT_CONTROL_CHANNEL = 0x0b;
    static final int TYPE_SUSPEND_OTR_NEGOTIATION = 0x0c;
    static final int TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE = 0x0d;
    static final int TYPE_DIRECT_MSG_INFO = 0x0e;

    static final int PROTOCOL_TCP = 6;
    static final int PROTOCOL_UDP = 17;
    static final int CURRENT_CONTROL_CHANNEL_VERSION = 5;
    static final int CURRENT_KEY_MATERIAL_LENGTH =
            IdsStreamEncryption.CONTRIBUTION_LENGTH;
    static final int CONTROL_PORT = 61315;
    static final int DATA_PORT = 61314;

    private static final int OUTER_HEADER_LENGTH = 2;
    private static final int SETUP_HEADER_LENGTH = 16;
    private static final int ENCRYPTED_SETUP_HEADER_LENGTH = 24;
    private static final int DIRECT_FLAGS_LENGTH = 8;
    private static final int UUID_LENGTH = 16;
    private static final int MAX_PAYLOAD_LENGTH = 0xffff;
    private static final int MAX_STRING_LENGTH = 0xffff;

    private IdsControlChannelCodec() {
    }

    static byte[] encodeFramed(
            Message message) {
        byte[] payload =
                encodePayload(
                        message);
        try {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream(
                            OUTER_HEADER_LENGTH
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
            Message message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "IDS control message is null");
        }
        if (message instanceof HelloMessage hello) {
            return encodeHello(
                    hello);
        }
        if (message instanceof SetupChannelMessage setup) {
            return encodeSetupChannel(
                    setup);
        }
        if (message
                instanceof SetupEncryptedChannelMessage encrypted) {
            return encodeSetupEncryptedChannel(
                    encrypted);
        }
        if (message instanceof DirectMsgInfoMessage direct) {
            if (direct.info.length > MAX_PAYLOAD_LENGTH - 1) {
                throw new IllegalArgumentException("IDS direct messaging information is too large");
            }
            byte[] payload = new byte[direct.info.length + 1];
            payload[0] = (byte) TYPE_DIRECT_MSG_INFO;
            System.arraycopy(direct.info, 0, payload, 1, direct.info.length);
            return payload;
        }
        throw new IllegalArgumentException(
                "Unsupported IDS control message class");
    }

    static Message decodeFramed(
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
                - OUTER_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS control outer length mismatch");
        }
        byte[] payload =
                Arrays.copyOfRange(
                        frame,
                        OUTER_HEADER_LENGTH,
                        frame.length);
        try {
            return decodePayload(
                    payload);
        } finally {
            wipe(payload);
        }
    }

    static Message decodePayload(
            byte[] payload) {
        if (payload == null
                || payload.length == 0
                || payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS control payload length is invalid");
        }
        return switch (payload[0] & 0xff) {
            case TYPE_HELLO ->
                    parseHello(
                            payload);
            case TYPE_SETUP_CHANNEL,
                    TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE ->
                    parseSetupChannel(
                            payload);
            case TYPE_SETUP_ENCRYPTED_CHANNEL ->
                    parseSetupEncryptedChannel(
                            payload);
            case TYPE_DIRECT_MSG_INFO ->
                    parseDirectMsgInfo(
                            payload);
            default -> new GenericControlMessage(
                    payload[0] & 0xff,
                    payload);
        };
    }

    static byte[] combineContributions(
            byte[] localContribution,
            SetupEncryptedChannelMessage remote) {
        if (remote == null) {
            throw new IllegalArgumentException(
                    "Remote IDS encrypted setup is null");
        }
        return IdsStreamEncryption.combineContributions(
                localContribution,
                remote.keyMaterial);
    }

    private static byte[] encodeHello(
            HelloMessage message) {
        message.requireValid();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        output.write(TYPE_HELLO);
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
            SetupChannelMessage message) {
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
            SetupEncryptedChannelMessage message) {
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

    private static HelloMessage parseHello(
            byte[] payload) {
        Cursor cursor =
                new Cursor(
                        payload);
        cursor.requireU8(
                TYPE_HELLO);
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
            return new HelloMessage(
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

    private static SetupChannelMessage parseSetupChannel(
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
        if (type != TYPE_SETUP_CHANNEL
                && type != TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE) {
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
                type == TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE
                        ? DIRECT_FLAGS_LENGTH
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
                (directLength > 0 && cursor.remaining() >= DIRECT_FLAGS_LENGTH)
                        ? cursor.readBytes(DIRECT_FLAGS_LENGTH)
                        : null;
        try {
            return new SetupChannelMessage(
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

    private static SetupEncryptedChannelMessage
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
        if (type != TYPE_SETUP_ENCRYPTED_CHANNEL) {
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
            return new SetupEncryptedChannelMessage(
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

    static String typeName(
            int type) {
        return switch (type) {
            case TYPE_HELLO ->
                    "Hello";
            case TYPE_SETUP_CHANNEL ->
                    "SetupChannel";
            case TYPE_CLOSE_CHANNEL ->
                    "CloseChannel";
            case TYPE_COMPRESSION_REQUEST ->
                    "CompressionRequest";
            case TYPE_COMPRESSION_RESPONSE ->
                    "CompressionResponse";
            case TYPE_SETUP_ENCRYPTED_CHANNEL ->
                    "SetupEncryptedChannel";
            case TYPE_FAIRPLAY_HOST_SESSION_INFO ->
                    "FairplayHostSessionInfo";
            case TYPE_FAIRPLAY_DEVICE_INFO ->
                    "FairplayDeviceInfo";
            case TYPE_FAIRPLAY_DEVICE_SESSION_INFO ->
                    "FairplayDeviceSessionInfo";
            case TYPE_OTR_NEGOTIATION ->
                    "OTRNegotiation";
            case TYPE_ENCRYPT_CONTROL_CHANNEL ->
                    "EncryptControlChannel";
            case TYPE_SUSPEND_OTR_NEGOTIATION ->
                    "SuspendOTRNegotiation";
            case TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE ->
                    "SetupChannelForDirectMsg";
            case TYPE_DIRECT_MSG_INFO ->
                    "DirectMsgInfo";
            default ->
                    "Unknown-0x" + Integer.toHexString(type);
        };
    }

    static boolean isKnownTypeName(
            String name) {
        if (name == null
                || name.isEmpty()) {
            return false;
        }
        if (name.startsWith("Unknown-0x")) {
            return true;
        }
        for (int type = TYPE_HELLO;
                type <= TYPE_DIRECT_MSG_INFO;
                type++) {
            if (typeName(type).equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static DirectMsgInfoMessage parseDirectMsgInfo(
            byte[] payload) {
        if (payload.length < 2) {
            throw new IllegalArgumentException(
                    "IDS DirectMsgInfo header is truncated");
        }
        return new DirectMsgInfoMessage(
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
                || payload.length > MAX_PAYLOAD_LENGTH) {
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

    private static void requireCanonicalUuidString(
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

    private static void requireProtocol(
            int protocol) {
        if (protocol != PROTOCOL_TCP
                && protocol != PROTOCOL_UDP) {
            throw new IllegalArgumentException(
                    "IDS data protocol must be TCP or UDP");
        }
    }

    private static void requireU16(
            String label,
            int value) {
        if (value < 0
                || value > 0xffff) {
            throw new IllegalArgumentException(
                    label + " must fit uint16");
        }
    }

    private static void requireU32(
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

    private static int readU16(
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

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static abstract class Message {
        final int type;
        private boolean destroyed;

        Message(
                int type) {
            this.type = type;
        }

        final void destroy() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            destroyContents();
        }

        void destroyContents() {
        }
    }

    static final class HelloMessage extends Message {
        final String controlChannelVersion;
        final String productName;
        final String productVersion;
        final String productBuildVersion;
        final String model;
        final long pairingProtocolVersion;
        final long minCompatibilityVersion;
        final long maxCompatibilityVersion;
        final UUID instanceId;
        final UUID deviceUniqueId;
        final long capabilityFlags;
        final int serviceMinCompatibilityVersion;

        HelloMessage(
                String controlChannelVersion,
                String productName,
                String productVersion,
                String productBuildVersion,
                String model,
                long pairingProtocolVersion,
                long minCompatibilityVersion,
                long maxCompatibilityVersion,
                UUID instanceId,
                UUID deviceUniqueId,
                long capabilityFlags,
                int serviceMinCompatibilityVersion) {
            super(
                    TYPE_HELLO);
            this.controlChannelVersion = controlChannelVersion;
            this.productName = productName;
            this.productVersion = productVersion;
            this.productBuildVersion = productBuildVersion;
            this.model = model;
            this.pairingProtocolVersion = pairingProtocolVersion;
            this.minCompatibilityVersion =
                    minCompatibilityVersion;
            this.maxCompatibilityVersion =
                    maxCompatibilityVersion;
            this.instanceId = instanceId;
            this.deviceUniqueId = deviceUniqueId;
            this.capabilityFlags = capabilityFlags;
            this.serviceMinCompatibilityVersion =
                    serviceMinCompatibilityVersion;
            requireValid();
        }

        void requireValid() {
            validateRequiredString(
                    controlChannelVersion);
            validateRequiredString(
                    productName);
            validateRequiredString(
                    productVersion);
            validateRequiredString(
                    productBuildVersion);
            validateRequiredString(
                    model);
            requireU32(
                    "IDS pairing protocol version",
                    pairingProtocolVersion);
            requireU32(
                    "IDS minimum compatibility version",
                    minCompatibilityVersion);
            requireU32(
                    "IDS maximum compatibility version",
                    maxCompatibilityVersion);
            if (instanceId == null
                    || deviceUniqueId == null) {
                throw new IllegalArgumentException(
                        "IDS Hello UUID fields are absent");
            }
            requireU16(
                    "IDS service minimum compatibility version",
                    serviceMinCompatibilityVersion);
        }
    }

    static class SetupChannelMessage extends Message {
        final int protocol;
        final int localPort;
        final int remotePort;
        final String remoteConnectionGuid;
        final String forLocalGuid;
        final String account;
        final String service;
        final String name;
        final byte[] directFlags;

        SetupChannelMessage(
                int type,
                int protocol,
                int localPort,
                int remotePort,
                String remoteConnectionGuid,
                String forLocalGuid,
                String account,
                String service,
                String name,
                byte[] directFlags) {
            super(
                    type);
            this.protocol = protocol;
            this.localPort = localPort;
            this.remotePort = remotePort;
            this.remoteConnectionGuid =
                    remoteConnectionGuid;
            this.forLocalGuid =
                    emptyToNull(
                            forLocalGuid);
            this.account = account;
            this.service = service;
            this.name = name;
            this.directFlags =
                    directFlags == null
                            ? null
                            : directFlags.clone();
            requireValid();
        }

        void requireValid() {
            if (type != TYPE_SETUP_CHANNEL
                    && type != TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE) {
                throw new IllegalArgumentException(
                        "IDS SetupChannel type is invalid");
            }
            requireU16(
                    "IDS local port",
                    localPort);
            requireU16(
                    "IDS remote port",
                    remotePort);
            if (type == TYPE_SETUP_CHANNEL
                    || type == TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE) {
                requireProtocol(
                        protocol);
                requireCanonicalUuidString(
                        "IDS remote connection GUID",
                        remoteConnectionGuid,
                        false);
                validateRequiredString(
                        account);
                validateRequiredString(
                        service);
                validateRequiredString(
                        name);
            }
            if (type == TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE) {
                if (directFlags == null
                        || directFlags.length
                        != DIRECT_FLAGS_LENGTH) {
                    throw new IllegalArgumentException(
                            "IDS direct-message flags must be eight bytes");
                }
            } else if (directFlags != null) {
                throw new IllegalArgumentException(
                        "Ordinary IDS setup must not carry direct flags");
            }
        }

        @Override
        void destroyContents() {
            wipe(directFlags);
        }
    }

    static final class SetupEncryptedChannelMessage
            extends Message {
        final int protocol;
        final int localPort;
        final int remotePort;
        final String remoteConnectionGuid;
        final String forLocalGuid;
        final String account;
        final String service;
        final String name;
        final long ssrc;
        final int startSequence;
        final byte[] keyMaterial;

        SetupEncryptedChannelMessage(
                int type,
                int protocol,
                int localPort,
                int remotePort,
                String remoteConnectionGuid,
                String forLocalGuid,
                String account,
                String service,
                String name,
                long ssrc,
                int startSequence,
                byte[] keyMaterial) {
            super(
                    type);
            this.protocol = protocol;
            this.localPort = localPort;
            this.remotePort = remotePort;
            this.remoteConnectionGuid =
                    remoteConnectionGuid;
            this.forLocalGuid =
                    emptyToNull(
                            forLocalGuid);
            this.account = account;
            this.service = service;
            this.name = name;
            this.ssrc = ssrc;
            this.startSequence = startSequence;
            this.keyMaterial =
                    keyMaterial == null
                            ? null
                            : keyMaterial.clone();
            requireValid();
        }

        SetupEncryptedChannelMessage(
                int protocol,
                int localPort,
                int remotePort,
                String remoteConnectionGuid,
                String forLocalGuid,
                String account,
                String service,
                String name,
                long ssrc,
                int startSequence,
                byte[] keyMaterial) {
            this(
                    TYPE_SETUP_ENCRYPTED_CHANNEL,
                    protocol,
                    localPort,
                    remotePort,
                    remoteConnectionGuid,
                    forLocalGuid,
                    account,
                    service,
                    name,
                    ssrc,
                    startSequence,
                    keyMaterial);
        }

        void requireValid() {
            if (type != TYPE_SETUP_ENCRYPTED_CHANNEL) {
                throw new IllegalArgumentException(
                        "IDS encrypted setup type is invalid");
            }
            requireU16(
                    "IDS local port",
                    localPort);
            requireU16(
                    "IDS remote port",
                    remotePort);
            requireProtocol(
                    protocol);
            requireCanonicalUuidString(
                    "IDS remote connection GUID",
                    remoteConnectionGuid,
                    false);
            validateRequiredString(
                    account);
            validateRequiredString(
                    service);
            validateRequiredString(
                    name);
            if (keyMaterial == null
                    || keyMaterial.length
                    != CURRENT_KEY_MATERIAL_LENGTH) {
                throw new IllegalArgumentException(
                        "IDS encrypted setup contribution must be 60 bytes");
            }
        }

        @Override
        void destroyContents() {
            wipe(keyMaterial);
        }
    }

    static final class DirectMsgInfoMessage extends Message {
        final byte[] info;

        static DirectMsgInfoMessage basicVersionOne() {
            // IDSDaemon.writeIDSDirectMessageInfo: writes a 16-byte structure.
            // Byte 0 is the version, bytes 8..15 are a big-endian feature mask.
            // Device-connection and concise-ACK extensions are not implemented.
            byte[] info = new byte[16];
            info[0] = 1;
            return new DirectMsgInfoMessage(info);
        }

        String capabilitySummary() {
            if (info.length < 16) return "truncated bytes=" + info.length;
            long features = 0;
            for (int i = 8; i < 16; i++) features = (features << 8) | (info[i] & 0xffL);
            return "version=" + (info[0] & 0xff) + ",features=0x" + Long.toUnsignedString(features, 16);
        }

        DirectMsgInfoMessage(
                byte[] info) {
            super(
                    TYPE_DIRECT_MSG_INFO);
            this.info =
                    info == null
                            ? new byte[0]
                            : info.clone();
        }

        @Override
        void destroyContents() {
            wipe(info);
        }
    }

    static final class GenericControlMessage extends Message {
        final byte[] rawPayload;

        GenericControlMessage(
                int type,
                byte[] rawPayload) {
            super(type);
            this.rawPayload =
                    rawPayload != null
                            ? rawPayload.clone()
                            : new byte[0];
        }

        @Override
        void destroyContents() {
            wipe(rawPayload);
        }
    }

    static final class StreamDecoder
            implements AutoCloseable {
        private byte[] buffer =
                new byte[0];
        private boolean poisoned;
        private boolean closed;

        synchronized List<Message> push(
                byte[] tcpBytes) {
            requireUsable();
            if (tcpBytes == null
                    || (long) buffer.length
                    + tcpBytes.length
                    > (MAX_PAYLOAD_LENGTH
                    + OUTER_HEADER_LENGTH) * 2L) {
                fail();
                throw new IllegalArgumentException(
                        "IDS control TCP buffer is invalid/too large");
            }
            byte[] previous =
                    buffer;
            buffer =
                    Arrays.copyOf(
                            previous,
                            previous.length
                                    + tcpBytes.length);
            System.arraycopy(
                    tcpBytes,
                    0,
                    buffer,
                    previous.length,
                    tcpBytes.length);
            wipe(previous);

            List<Message> output =
                    new ArrayList<>();
            int consumed = 0;
            try {
                while (buffer.length - consumed
                        >= OUTER_HEADER_LENGTH) {
                    int payloadLength =
                            readU16(
                                    buffer,
                                    consumed);
                    if (payloadLength == 0) {
                        throw new IllegalArgumentException(
                                "IDS control frame has an empty payload");
                    }
                    int frameLength =
                            OUTER_HEADER_LENGTH
                                    + payloadLength;
                    if (buffer.length - consumed
                            < frameLength) {
                        break;
                    }
                    byte[] payload =
                            Arrays.copyOfRange(
                                    buffer,
                                    consumed
                                            + OUTER_HEADER_LENGTH,
                                    consumed
                                            + frameLength);
                    try {
                        output.add(
                                decodePayload(
                                        payload));
                    } finally {
                        wipe(payload);
                    }
                    consumed += frameLength;
                }
                if (consumed != 0) {
                    previous = buffer;
                    buffer =
                            Arrays.copyOfRange(
                                    previous,
                                    consumed,
                                    previous.length);
                    wipe(previous);
                }
                return List.copyOf(
                        output);
            } catch (RuntimeException failure) {
                for (Message message : output) {
                    message.destroy();
                }
                fail();
                throw failure;
            }
        }

        synchronized int bufferedLength() {
            requireUsable();
            return buffer.length;
        }

        private void requireUsable() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS control decoder is closed");
            }
            if (poisoned) {
                throw new IllegalStateException(
                        "IDS control decoder is poisoned");
            }
        }

        private void fail() {
            wipe(buffer);
            buffer = new byte[0];
            poisoned = true;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(buffer);
            buffer = new byte[0];
        }
    }

    private static void validateRequiredString(
            String value) {
        byte[] encoded =
                encodeString(
                        value,
                        false);
        wipe(encoded);
    }

    private static String emptyToNull(
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
                    IdsControlChannelCodec.readU16(
                            bytes,
                            offset);
            offset += 2;
            return value;
        }

        long readU32() {
            requireRemaining(4);
            long value =
                    IdsControlChannelCodec.readU32(
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
