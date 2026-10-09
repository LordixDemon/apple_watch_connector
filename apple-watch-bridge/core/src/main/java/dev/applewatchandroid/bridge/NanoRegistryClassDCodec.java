package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * NanoRegistry Class D protobufs recovered from iOS/watchOS 26.6.
 *
 * <p>This class only encodes and decodes application messages. It does not
 * choose a pairing-mode state, send anything to a peer, or expose destructive
 * unpair operations through the Android UI.
 */
final class NanoRegistryClassDCodec {
    static final String SERVICE =
            NanoRegistryPropertyCodec.CLASS_D_SERVICE;
    static final int MESSAGE_PRIORITY = 300;

    static final int TYPE_DEVICE_WILL_UNPAIR = 1;
    static final int TYPE_PAIRING_MODE = 3;
    static final int TYPE_PING = 5;
    static final int TYPE_WATCH_MIGRATION_COMPLETION = 6;
    static final int TYPE_RTC_MIGRATION_METRIC_SESSION_ID = 9;
    static final int TYPE_PAIRING_SESSION_ID = 10;
    static final int TYPE_GRADUATION = 11;

    static final int COMPATIBILITY_STATE_INVALID = 0;
    /**
     * Transitional state while the phone can check protocol compatibility.
     *
     * <p>During fresh setup this state is only materialized when the remote
     * maximum protocol version is present before the remote model number.
     */
    static final int COMPATIBILITY_STATE_CHECK = 1;
    static final int COMPATIBILITY_STATE_UPDATE = 2;
    /**
     * Compatible, identified, not-yet-paired device.
     */
    static final int COMPATIBILITY_STATE_CONFIGURE = 3;
    /**
     * Fully paired, normal device; maps to the unrestricted IDS traffic
     * class.
     */
    static final int COMPATIBILITY_STATE_NORMAL = 4;
    static final int COMPATIBILITY_STATE_ALT_ACCOUNT = 5;

    static final int IOS_26_6_PHONE_MAX_VERSION = 26;
    static final int WATCHOS_26_6_ULTRA2_VERSION = 26;
    static final int IOS_26_6_ULTRA2_PHONE_MIN_VERSION = 25;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED32 = 5;

    private static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;
    private static final int MAX_STRING_LENGTH = 64 * 1024;
    private static final int MAX_ADVERTISED_NAME_LENGTH = 1024;
    private static final int MAX_SESSION_ID_LENGTH = 1024;

    private NanoRegistryClassDCodec() {
    }

    static byte[] encode(
            ApplicationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class D message is absent");
        }
        message.requireValid();
        byte[] encoded;
        if (message instanceof DeviceWillUnpairRequest request) {
            encoded = encodeDeviceWillUnpairRequest(
                    request);
        } else if (message instanceof DeviceWillUnpairResponse) {
            encoded = new byte[0];
        } else if (message instanceof PairingModeRequest request) {
            encoded = encodePairingModeRequest(
                    request);
        } else if (message instanceof PairingModeResponse response) {
            encoded = encodePairingModeResponse(
                    response);
        } else if (message instanceof PingRequest request) {
            encoded = encodePingRequest(
                    request);
        } else if (message instanceof PingResponse response) {
            encoded = encodePingResponse(
                    response);
        } else if (message
                instanceof WatchMigrationCompletionRequest request) {
            encoded = encodeWatchMigrationCompletionRequest(
                    request);
        } else if (message
                instanceof RtcMigrationMetricSessionId request) {
            encoded = encodeOptionalString(
                    request.sessionId,
                    "RTC migration metric session ID");
        } else if (message instanceof PairingSessionId request) {
            encoded = encodeOptionalString(
                    request.pairingSessionId,
                    "pairing session ID");
        } else if (message instanceof GraduationRequest) {
            encoded = new byte[0];
        } else {
            throw new IllegalArgumentException(
                    "Unknown NanoRegistry Class D message");
        }
        return requireEncodedSize(
                encoded);
    }

    static ApplicationMessage decode(
            IdsSocketPairCodec.ProtobufMessage envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException(
                    "IDS protobuf envelope is absent");
        }
        return switch (envelope.protobufType) {
            case TYPE_DEVICE_WILL_UNPAIR ->
                    envelope.response
                            ? decodeDeviceWillUnpairResponse(
                                    envelope.payload)
                            : decodeDeviceWillUnpairRequest(
                                    envelope.payload);
            case TYPE_PAIRING_MODE ->
                    envelope.response
                            ? decodePairingModeResponse(
                                    envelope.payload)
                            : decodePairingModeRequest(
                                    envelope.payload);
            case TYPE_PING ->
                    envelope.response
                            ? decodePingResponse(
                                    envelope.payload)
                            : decodePingRequest(
                                    envelope.payload);
            case TYPE_WATCH_MIGRATION_COMPLETION -> {
                rejectResponse(
                        envelope.response,
                        "watch migration completion");
                yield decodeWatchMigrationCompletionRequest(
                        envelope.payload);
            }
            case TYPE_RTC_MIGRATION_METRIC_SESSION_ID -> {
                rejectResponse(
                        envelope.response,
                        "RTC migration metric session ID");
                yield decodeRtcMigrationMetricSessionId(
                        envelope.payload);
            }
            case TYPE_PAIRING_SESSION_ID -> {
                rejectResponse(
                        envelope.response,
                        "pairing session ID");
                yield decodePairingSessionId(
                        envelope.payload);
            }
            case TYPE_GRADUATION -> {
                rejectResponse(
                        envelope.response,
                        "graduation");
                yield decodeGraduationRequest(
                        envelope.payload);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported NanoRegistry Class D protobuf type");
        };
    }

    static IdsSocketPairCodec.ProtobufMessage envelope(
            long sequence,
            int streamId,
            int flags,
            String peerResponseIdentifier,
            String messageUuid,
            String topic,
            ApplicationMessage message,
            Long expirySeconds) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class D message is absent");
        }
        byte[] payload =
                encode(
                        message);
        try {
            return new IdsSocketPairCodec.ProtobufMessage(
                    sequence,
                    streamId,
                    flags,
                    peerResponseIdentifier,
                    messageUuid,
                    topic,
                    message.protobufType(),
                    message.response(),
                    payload,
                    expirySeconds);
        } finally {
            wipe(
                    payload);
        }
    }

    static byte[] encodeDeviceWillUnpairRequest(
            DeviceWillUnpairRequest request) {
        requireMessage(
                request,
                "device-will-unpair request");
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeStringField(
                output,
                1,
                request.advertisedName,
                MAX_ADVERTISED_NAME_LENGTH,
                "advertised name");
        if (request.shouldObliterate != null) {
            writeBoolField(
                    output,
                    2,
                    request.shouldObliterate);
        }
        if (request.pairingFailureCode != null) {
            writeInt32Field(
                    output,
                    3,
                    request.pairingFailureCode);
        }
        if (request.abortReason != null) {
            writeStringField(
                    output,
                    4,
                    request.abortReason,
                    MAX_STRING_LENGTH,
                    "unpair abort reason");
        }
        if (request.shouldBrick != null) {
            writeBoolField(
                    output,
                    5,
                    request.shouldBrick);
        }
        if (request.shouldPreserveEsim != null) {
            writeBoolField(
                    output,
                    6,
                    request.shouldPreserveEsim);
        }
        return requireEncodedSize(
                output.toByteArray());
    }

    static DeviceWillUnpairRequest decodeDeviceWillUnpairRequest(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        String advertisedName = null;
        Boolean shouldObliterate = null;
        Integer pairingFailureCode = null;
        String abortReason = null;
        Boolean shouldBrick = null;
        Boolean shouldPreserveEsim = null;
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            switch (fieldNumber(
                    tag)) {
                case 1 -> {
                    rejectDuplicate(
                            advertisedName != null,
                            "advertised name");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    advertisedName =
                            reader.readUtf8(
                                    MAX_ADVERTISED_NAME_LENGTH,
                                    "advertised name");
                }
                case 2 -> {
                    rejectDuplicate(
                            shouldObliterate != null,
                            "shouldObliterate");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    shouldObliterate =
                            reader.readBool();
                }
                case 3 -> {
                    rejectDuplicate(
                            pairingFailureCode != null,
                            "pairingFailureCode");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    pairingFailureCode =
                            reader.readInt32();
                }
                case 4 -> {
                    rejectDuplicate(
                            abortReason != null,
                            "abortReason");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    abortReason =
                            reader.readUtf8(
                                    MAX_STRING_LENGTH,
                                    "unpair abort reason");
                }
                case 5 -> {
                    rejectDuplicate(
                            shouldBrick != null,
                            "shouldBrick");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    shouldBrick =
                            reader.readBool();
                }
                case 6 -> {
                    rejectDuplicate(
                            shouldPreserveEsim != null,
                            "shouldPreserveESim");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    shouldPreserveEsim =
                            reader.readBool();
                }
                default -> reader.skipField(
                        tag);
            }
        }
        if (advertisedName == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry advertised name is missing");
        }
        return new DeviceWillUnpairRequest(
                advertisedName,
                shouldObliterate,
                pairingFailureCode,
                abortReason,
                shouldBrick,
                shouldPreserveEsim);
    }

    static DeviceWillUnpairResponse decodeDeviceWillUnpairResponse(
            byte[] payload) {
        skipEmptyMessageFields(
                payload);
        return new DeviceWillUnpairResponse();
    }

    static byte[] encodePairingModeRequest(
            PairingModeRequest request) {
        requireMessage(
                request,
                "pairing-mode request");
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeInt32Field(
                output,
                1,
                request.pairingMode);
        if (request.phonePairingProtocolVersionMax != null) {
            writeInt32Field(
                    output,
                    2,
                    request.phonePairingProtocolVersionMax);
        }
        if (request.watchPairingProtocolVersion != null) {
            writeInt32Field(
                    output,
                    3,
                    request.watchPairingProtocolVersion);
        }
        if (request.phonePairingProtocolVersionMin != null) {
            writeInt32Field(
                    output,
                    4,
                    request.phonePairingProtocolVersionMin);
        }
        return output.toByteArray();
    }

    static PairingModeRequest decodePairingModeRequest(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        int pairingMode = 0;
        boolean sawPairingMode = false;
        Integer phoneMax = null;
        Integer watchVersion = null;
        Integer phoneMin = null;
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            switch (fieldNumber(
                    tag)) {
                case 1 -> {
                    rejectDuplicate(
                            sawPairingMode,
                            "pairingMode");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    pairingMode =
                            reader.readInt32();
                    sawPairingMode = true;
                }
                case 2 -> {
                    rejectDuplicate(
                            phoneMax != null,
                            "phonePairingProtocolVersionMax");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    phoneMax =
                            reader.readInt32();
                }
                case 3 -> {
                    rejectDuplicate(
                            watchVersion != null,
                            "watchPairingProtocolVersion");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    watchVersion =
                            reader.readInt32();
                }
                case 4 -> {
                    rejectDuplicate(
                            phoneMin != null,
                            "phonePairingProtocolVersionMin");
                    requireWire(
                            tag,
                            WIRE_VARINT);
                    phoneMin =
                            reader.readInt32();
                }
                default -> reader.skipField(
                        tag);
            }
        }
        if (!sawPairingMode) {
            throw new IllegalArgumentException(
                    "NanoRegistry pairingMode is missing");
        }
        return new PairingModeRequest(
                pairingMode,
                phoneMax,
                watchVersion,
                phoneMin);
    }

    static byte[] encodePairingModeResponse(
            PairingModeResponse response) {
        requireMessage(
                response,
                "pairing-mode response");
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeBoolField(
                output,
                1,
                response.success);
        return output.toByteArray();
    }

    static PairingModeResponse decodePairingModeResponse(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        boolean success = false;
        boolean sawSuccess = false;
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            if (fieldNumber(
                    tag) == 1) {
                rejectDuplicate(
                        sawSuccess,
                        "pairing-mode success");
                requireWire(
                        tag,
                        WIRE_VARINT);
                success =
                        reader.readBool();
                sawSuccess = true;
            } else {
                reader.skipField(
                        tag);
            }
        }
        if (!sawSuccess) {
            throw new IllegalArgumentException(
                    "NanoRegistry pairing-mode success is missing");
        }
        return new PairingModeResponse(
                success);
    }

    static byte[] encodePingRequest(
            PingRequest request) {
        requireMessage(
                request,
                "ping request");
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeInt32Field(
                output,
                1,
                request.responseIdsPriority);
        writeDoubleField(
                output,
                2,
                request.timeout);
        writeInt32Field(
                output,
                3,
                request.pingType);
        if (request.payload != null) {
            writeBytesField(
                    output,
                    4,
                    request.payload);
        }
        return requireEncodedSize(
                output.toByteArray());
    }

    static PingRequest decodePingRequest(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        int responseIdsPriority = 0;
        double timeout = 0;
        int pingType = 0;
        byte[] pingPayload = null;
        boolean sawPriority = false;
        boolean sawTimeout = false;
        boolean sawPingType = false;
        try {
            while (reader.hasRemaining()) {
                int tag =
                        reader.readTag();
                switch (fieldNumber(
                        tag)) {
                    case 1 -> {
                        rejectDuplicate(
                                sawPriority,
                                "responseIDSPriority");
                        requireWire(
                                tag,
                                WIRE_VARINT);
                        responseIdsPriority =
                                reader.readInt32();
                        sawPriority = true;
                    }
                    case 2 -> {
                        rejectDuplicate(
                                sawTimeout,
                                "ping timeout");
                        requireWire(
                                tag,
                                WIRE_FIXED64);
                        timeout =
                                reader.readDouble();
                        sawTimeout = true;
                    }
                    case 3 -> {
                        rejectDuplicate(
                                sawPingType,
                                "pingType");
                        requireWire(
                                tag,
                                WIRE_VARINT);
                        pingType =
                                reader.readInt32();
                        sawPingType = true;
                    }
                    case 4 -> {
                        rejectDuplicate(
                                pingPayload != null,
                                "ping payload");
                        requireWire(
                                tag,
                                WIRE_LENGTH_DELIMITED);
                        pingPayload =
                                reader.readBytes(
                                        MAX_PAYLOAD_LENGTH,
                                        "ping payload");
                    }
                    default -> reader.skipField(
                            tag);
                }
            }
            if (!sawPriority
                    || !sawTimeout
                    || !sawPingType) {
                throw new IllegalArgumentException(
                        "NanoRegistry ping request is incomplete");
            }
            return new PingRequest(
                    responseIdsPriority,
                    timeout,
                    pingType,
                    pingPayload);
        } finally {
            wipe(
                    pingPayload);
        }
    }

    static byte[] encodePingResponse(
            PingResponse response) {
        requireMessage(
                response,
                "ping response");
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeDoubleField(
                output,
                1,
                response.responseDate);
        if (response.payload != null) {
            writeBytesField(
                    output,
                    2,
                    response.payload);
        }
        return requireEncodedSize(
                output.toByteArray());
    }

    static PingResponse decodePingResponse(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        double responseDate = 0;
        byte[] responsePayload = null;
        boolean sawResponseDate = false;
        try {
            while (reader.hasRemaining()) {
                int tag =
                        reader.readTag();
                switch (fieldNumber(
                        tag)) {
                    case 1 -> {
                        rejectDuplicate(
                                sawResponseDate,
                                "ping responseDate");
                        requireWire(
                                tag,
                                WIRE_FIXED64);
                        responseDate =
                                reader.readDouble();
                        sawResponseDate = true;
                    }
                    case 2 -> {
                        rejectDuplicate(
                                responsePayload != null,
                                "ping response payload");
                        requireWire(
                                tag,
                                WIRE_LENGTH_DELIMITED);
                        responsePayload =
                                reader.readBytes(
                                        MAX_PAYLOAD_LENGTH,
                                        "ping response payload");
                    }
                    default -> reader.skipField(
                            tag);
                }
            }
            if (!sawResponseDate) {
                throw new IllegalArgumentException(
                        "NanoRegistry ping responseDate is missing");
            }
            return new PingResponse(
                    responseDate,
                    responsePayload);
        } finally {
            wipe(
                    responsePayload);
        }
    }

    static byte[] encodeWatchMigrationCompletionRequest(
            WatchMigrationCompletionRequest request) {
        requireMessage(
                request,
                "watch migration completion request");
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        if (request.status != null) {
            writeInt32Field(
                    output,
                    1,
                    request.status);
        }
        return output.toByteArray();
    }

    static WatchMigrationCompletionRequest
            decodeWatchMigrationCompletionRequest(
                    byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        Integer status = null;
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            if (fieldNumber(
                    tag) == 1) {
                rejectDuplicate(
                        status != null,
                        "migration status");
                requireWire(
                        tag,
                        WIRE_VARINT);
                status =
                        reader.readInt32();
            } else {
                reader.skipField(
                        tag);
            }
        }
        return new WatchMigrationCompletionRequest(
                status);
    }

    static RtcMigrationMetricSessionId
            decodeRtcMigrationMetricSessionId(
                    byte[] payload) {
        return new RtcMigrationMetricSessionId(
                decodeOptionalString(
                        payload,
                        "RTC migration metric session ID"));
    }

    static PairingSessionId decodePairingSessionId(
            byte[] payload) {
        return new PairingSessionId(
                decodeOptionalString(
                        payload,
                        "pairing session ID"));
    }

    static GraduationRequest decodeGraduationRequest(
            byte[] payload) {
        skipEmptyMessageFields(
                payload);
        return new GraduationRequest();
    }

    private static byte[] encodeOptionalString(
            String value,
            String label) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        if (value != null) {
            writeStringField(
                    output,
                    1,
                    value,
                    MAX_SESSION_ID_LENGTH,
                    label);
        }
        return output.toByteArray();
    }

    private static String decodeOptionalString(
            byte[] payload,
            String label) {
        Reader reader =
                rootReader(
                        payload);
        String value = null;
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            if (fieldNumber(
                    tag) == 1) {
                rejectDuplicate(
                        value != null,
                        label);
                requireWire(
                        tag,
                        WIRE_LENGTH_DELIMITED);
                value =
                        reader.readUtf8(
                                MAX_SESSION_ID_LENGTH,
                                label);
            } else {
                reader.skipField(
                        tag);
            }
        }
        return value;
    }

    private static void skipEmptyMessageFields(
            byte[] payload) {
        Reader reader =
                rootReader(
                        payload);
        while (reader.hasRemaining()) {
            reader.skipField(
                    reader.readTag());
        }
    }

    private static Reader rootReader(
            byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class D payload is absent");
        }
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class D payload is too large");
        }
        return new Reader(
                payload,
                0,
                payload.length);
    }

    private static void writeInt32Field(
            ByteArrayOutputStream output,
            int field,
            int value) {
        writeTag(
                output,
                field,
                WIRE_VARINT);
        writeVarint(
                output,
                value);
    }

    private static void writeBoolField(
            ByteArrayOutputStream output,
            int field,
            boolean value) {
        writeTag(
                output,
                field,
                WIRE_VARINT);
        writeVarint(
                output,
                value
                        ? 1
                        : 0);
    }

    private static void writeDoubleField(
            ByteArrayOutputStream output,
            int field,
            double value) {
        writeTag(
                output,
                field,
                WIRE_FIXED64);
        long bits =
                Double.doubleToRawLongBits(
                        value);
        for (int shift = 0;
                shift < Long.SIZE;
                shift += Byte.SIZE) {
            output.write(
                    (int) (bits >>> shift)
                            & 0xff);
        }
    }

    private static void writeStringField(
            ByteArrayOutputStream output,
            int field,
            String value,
            int maximumLength,
            String label) {
        byte[] encoded =
                encodeUtf8(
                        value,
                        maximumLength,
                        label);
        try {
            writeBytesField(
                    output,
                    field,
                    encoded);
        } finally {
            wipe(
                    encoded);
        }
    }

    private static void writeBytesField(
            ByteArrayOutputStream output,
            int field,
            byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry bytes are absent");
        }
        writeTag(
                output,
                field,
                WIRE_LENGTH_DELIMITED);
        writeVarint(
                output,
                value.length);
        output.writeBytes(
                value);
    }

    private static void writeTag(
            ByteArrayOutputStream output,
            int field,
            int wire) {
        if (field <= 0
                || field > 0x1fff_ffff) {
            throw new IllegalArgumentException(
                    "Invalid NanoRegistry protobuf field number");
        }
        writeVarint(
                output,
                ((long) field << 3)
                        | wire);
    }

    private static void writeVarint(
            ByteArrayOutputStream output,
            long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write(
                    ((int) remaining & 0x7f)
                            | 0x80);
            remaining >>>= 7;
        }
        output.write(
                (int) remaining);
    }

    private static byte[] encodeUtf8(
            String value,
            int maximumLength,
            String label) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry " + label + " is absent");
        }
        try {
            ByteBuffer encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(
                                    CodingErrorAction.REPORT)
                            .onUnmappableCharacter(
                                    CodingErrorAction.REPORT)
                            .encode(
                                    CharBuffer.wrap(
                                            value));
            if (encoded.remaining() > maximumLength) {
                throw new IllegalArgumentException(
                        "NanoRegistry " + label + " is too long");
            }
            byte[] output =
                    new byte[encoded.remaining()];
            encoded.get(
                    output);
            return output;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                    "NanoRegistry " + label + " is not valid UTF-8",
                    failure);
        }
    }

    private static void validateString(
            String value,
            int maximumLength,
            String label,
            boolean allowEmpty) {
        byte[] encoded =
                encodeUtf8(
                        value,
                        maximumLength,
                        label);
        try {
            if (!allowEmpty
                    && encoded.length == 0) {
                throw new IllegalArgumentException(
                        "NanoRegistry " + label + " is empty");
            }
        } finally {
            wipe(
                    encoded);
        }
    }

    private static byte[] clonePayload(
            byte[] payload,
            String label) {
        if (payload == null) {
            return null;
        }
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "NanoRegistry " + label + " is too large");
        }
        return payload.clone();
    }

    private static byte[] requireEncodedSize(
            byte[] payload) {
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            wipe(
                    payload);
            throw new IllegalArgumentException(
                    "NanoRegistry Class D payload is too large");
        }
        return payload;
    }

    private static void requireMessage(
            ApplicationMessage message,
            String label) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry " + label + " is absent");
        }
        message.requireValid();
    }

    private static void rejectResponse(
            boolean response,
            String label) {
        if (response) {
            throw new IllegalArgumentException(
                    "NanoRegistry " + label
                            + " has no proven response schema");
        }
    }

    private static int fieldNumber(
            int tag) {
        return tag >>> 3;
    }

    private static void requireWire(
            int tag,
            int expected) {
        if ((tag & 7) != expected) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class D protobuf wire type mismatch");
        }
    }

    private static void rejectDuplicate(
            boolean duplicate,
            String label) {
        if (duplicate) {
            throw new IllegalArgumentException(
                    "Duplicate NanoRegistry " + label);
        }
    }

    private static boolean isCanonicalUuid(
            String value) {
        if (value == null
                || value.length() != 36
                || value.charAt(
                        8) != '-'
                || value.charAt(
                        13) != '-'
                || value.charAt(
                        18) != '-'
                || value.charAt(
                        23) != '-') {
            return false;
        }
        for (int index = 0;
                index < value.length();
                index++) {
            char character =
                    value.charAt(
                            index);
            if (index == 8
                    || index == 13
                    || index == 18
                    || index == 23) {
                continue;
            }
            if (Character.digit(
                    character,
                    16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    abstract static class ApplicationMessage {
        abstract int protobufType();

        abstract boolean response();

        abstract void requireValid();

        void destroy() {
        }
    }

    static final class DeviceWillUnpairRequest
            extends ApplicationMessage {
        final String advertisedName;
        final Boolean shouldObliterate;
        final Integer pairingFailureCode;
        final String abortReason;
        final Boolean shouldBrick;
        final Boolean shouldPreserveEsim;

        DeviceWillUnpairRequest(
                String advertisedName,
                Boolean shouldObliterate,
                Integer pairingFailureCode,
                String abortReason,
                Boolean shouldBrick,
                Boolean shouldPreserveEsim) {
            this.advertisedName = advertisedName;
            this.shouldObliterate = shouldObliterate;
            this.pairingFailureCode = pairingFailureCode;
            this.abortReason = abortReason;
            this.shouldBrick = shouldBrick;
            this.shouldPreserveEsim = shouldPreserveEsim;
            requireValid();
        }

        @Override
        int protobufType() {
            return TYPE_DEVICE_WILL_UNPAIR;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            validateString(
                    advertisedName,
                    MAX_ADVERTISED_NAME_LENGTH,
                    "advertised name",
                    false);
            if (abortReason != null) {
                validateString(
                        abortReason,
                        MAX_STRING_LENGTH,
                        "unpair abort reason",
                        true);
            }
        }
    }

    static final class DeviceWillUnpairResponse
            extends ApplicationMessage {
        @Override
        int protobufType() {
            return TYPE_DEVICE_WILL_UNPAIR;
        }

        @Override
        boolean response() {
            return true;
        }

        @Override
        void requireValid() {
        }
    }

    static final class PairingModeRequest
            extends ApplicationMessage {
        final int pairingMode;
        final Integer phonePairingProtocolVersionMax;
        final Integer watchPairingProtocolVersion;
        final Integer phonePairingProtocolVersionMin;

        PairingModeRequest(
                int pairingMode,
                Integer phonePairingProtocolVersionMax,
                Integer watchPairingProtocolVersion,
                Integer phonePairingProtocolVersionMin) {
            this.pairingMode = pairingMode;
            this.phonePairingProtocolVersionMax =
                    phonePairingProtocolVersionMax;
            this.watchPairingProtocolVersion =
                    watchPairingProtocolVersion;
            this.phonePairingProtocolVersionMin =
                    phonePairingProtocolVersionMin;
            requireValid();
        }

        static PairingModeRequest modernIos26_6Ultra2(
                int pairingMode) {
            return modernIos26_6Ultra2(
                    pairingMode,
                    WATCHOS_26_6_ULTRA2_VERSION);
        }

        static PairingModeRequest modernIos26_6Ultra2(
                int pairingMode,
                int runtimeWatchPairingVersion) {
            if (runtimeWatchPairingVersion
                    < IOS_26_6_ULTRA2_PHONE_MIN_VERSION
                    || runtimeWatchPairingVersion
                    > IOS_26_6_PHONE_MAX_VERSION) {
                throw new IllegalArgumentException(
                        "Ultra 2 runtime pairing version is incompatible");
            }
            return new PairingModeRequest(
                    pairingMode,
                    IOS_26_6_PHONE_MAX_VERSION,
                    runtimeWatchPairingVersion,
                    IOS_26_6_ULTRA2_PHONE_MIN_VERSION);
        }

        boolean hasAllVersionMetadata() {
            return phonePairingProtocolVersionMax != null
                    && watchPairingProtocolVersion != null
                    && phonePairingProtocolVersionMin != null;
        }

        boolean matchesIos26_6Ultra2VersionTuple() {
            return Integer.valueOf(
                    IOS_26_6_PHONE_MAX_VERSION).equals(
                    phonePairingProtocolVersionMax)
                    && Integer.valueOf(
                            WATCHOS_26_6_ULTRA2_VERSION).equals(
                            watchPairingProtocolVersion)
                    && Integer.valueOf(
                            IOS_26_6_ULTRA2_PHONE_MIN_VERSION).equals(
                            phonePairingProtocolVersionMin);
        }

        @Override
        int protobufType() {
            return TYPE_PAIRING_MODE;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (pairingMode < COMPATIBILITY_STATE_INVALID
                    || pairingMode > COMPATIBILITY_STATE_ALT_ACCOUNT) {
                throw new IllegalArgumentException(
                        "Unknown NanoRegistry compatibility state");
            }
        }
    }

    /**
     * Generated by Apple, but no current iOS/watchOS 26.6 non-generated
     * sender was found. The current type-3 request is effectively one-way.
     */
    static final class PairingModeResponse
            extends ApplicationMessage {
        final boolean success;

        PairingModeResponse(
                boolean success) {
            this.success = success;
        }

        @Override
        int protobufType() {
            return TYPE_PAIRING_MODE;
        }

        @Override
        boolean response() {
            return true;
        }

        @Override
        void requireValid() {
        }
    }

    static final class PingRequest
            extends ApplicationMessage {
        final int responseIdsPriority;
        final double timeout;
        final int pingType;
        final byte[] payload;

        PingRequest(
                int responseIdsPriority,
                double timeout,
                int pingType,
                byte[] payload) {
            this.responseIdsPriority = responseIdsPriority;
            this.timeout = timeout;
            this.pingType = pingType;
            this.payload =
                    clonePayload(
                            payload,
                            "ping payload");
        }

        @Override
        int protobufType() {
            return TYPE_PING;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (payload != null
                    && payload.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry ping payload is too large");
            }
        }

        @Override
        void destroy() {
            wipe(
                    payload);
        }
    }

    static final class PingResponse
            extends ApplicationMessage {
        final double responseDate;
        final byte[] payload;

        PingResponse(
                double responseDate,
                byte[] payload) {
            this.responseDate = responseDate;
            this.payload =
                    clonePayload(
                            payload,
                            "ping response payload");
        }

        @Override
        int protobufType() {
            return TYPE_PING;
        }

        @Override
        boolean response() {
            return true;
        }

        @Override
        void requireValid() {
            if (payload != null
                    && payload.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "NanoRegistry ping response payload is too large");
            }
        }

        @Override
        void destroy() {
            wipe(
                    payload);
        }
    }

    static final class WatchMigrationCompletionRequest
            extends ApplicationMessage {
        final Integer status;

        WatchMigrationCompletionRequest(
                Integer status) {
            this.status = status;
        }

        @Override
        int protobufType() {
            return TYPE_WATCH_MIGRATION_COMPLETION;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
        }
    }

    static final class RtcMigrationMetricSessionId
            extends ApplicationMessage {
        final String sessionId;

        RtcMigrationMetricSessionId(
                String sessionId) {
            this.sessionId = sessionId;
            requireValid();
        }

        @Override
        int protobufType() {
            return TYPE_RTC_MIGRATION_METRIC_SESSION_ID;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (sessionId != null) {
                validateString(
                        sessionId,
                        MAX_SESSION_ID_LENGTH,
                        "RTC migration metric session ID",
                        true);
            }
        }
    }

    static final class PairingSessionId
            extends ApplicationMessage {
        final String pairingSessionId;

        PairingSessionId(
                String pairingSessionId) {
            this.pairingSessionId = pairingSessionId;
            requireValid();
        }

        static PairingSessionId fromUuid(
                UUID value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "NanoRegistry pairing session UUID is absent");
            }
            return new PairingSessionId(
                    value.toString());
        }

        @Override
        int protobufType() {
            return TYPE_PAIRING_SESSION_ID;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (pairingSessionId == null) {
                return;
            }
            validateString(
                    pairingSessionId,
                    MAX_SESSION_ID_LENGTH,
                    "pairing session ID",
                    false);
            if (!isCanonicalUuid(
                    pairingSessionId)) {
                throw new IllegalArgumentException(
                        "NanoRegistry pairing session ID is not a canonical UUID");
            }
        }
    }

    static final class GraduationRequest
            extends ApplicationMessage {
        @Override
        int protobufType() {
            return TYPE_GRADUATION;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
        }
    }

    private static final class Reader {
        private final byte[] data;
        private final int limit;
        private int offset;

        Reader(
                byte[] data,
                int offset,
                int limit) {
            this.data = data;
            this.offset = offset;
            this.limit = limit;
        }

        boolean hasRemaining() {
            return offset < limit;
        }

        int readTag() {
            long raw =
                    readVarint();
            if (raw <= 0
                    || raw > 0xffff_ffffL) {
                throw new IllegalArgumentException(
                        "Invalid NanoRegistry Class D protobuf tag");
            }
            int tag =
                    (int) raw;
            int field =
                    fieldNumber(
                            tag);
            int wire =
                    tag & 7;
            if (field <= 0
                    || wire == 3
                    || wire == 4
                    || wire > WIRE_FIXED32) {
                throw new IllegalArgumentException(
                        "Unsupported NanoRegistry Class D protobuf tag");
            }
            return tag;
        }

        int readInt32() {
            return (int) readVarint();
        }

        long readVarint() {
            long result = 0;
            for (int index = 0;
                    index < 10;
                    index++) {
                requireRemaining(
                        1);
                int next =
                        data[offset++] & 0xff;
                if (index == 9
                        && (next & 0xfe) != 0) {
                    throw new IllegalArgumentException(
                            "NanoRegistry Class D protobuf varint overflow");
                }
                result |=
                        (long) (next & 0x7f)
                                << index * 7;
                if ((next & 0x80) == 0) {
                    return result;
                }
            }
            throw new IllegalArgumentException(
                    "NanoRegistry Class D protobuf varint is unterminated");
        }

        boolean readBool() {
            long value =
                    readVarint();
            if (value != 0
                    && value != 1) {
                throw new IllegalArgumentException(
                        "NanoRegistry Class D boolean is not canonical");
            }
            return value == 1;
        }

        double readDouble() {
            requireRemaining(
                    8);
            long bits = 0;
            for (int index = 0;
                    index < 8;
                    index++) {
                bits |=
                        (long) (data[offset + index] & 0xff)
                                << index * 8;
            }
            offset += 8;
            return Double.longBitsToDouble(
                    bits);
        }

        byte[] readBytes(
                int maximumLength,
                String label) {
            int length =
                    readLength(
                            maximumLength,
                            label);
            byte[] value =
                    Arrays.copyOfRange(
                            data,
                            offset,
                            offset + length);
            offset += length;
            return value;
        }

        String readUtf8(
                int maximumLength,
                String label) {
            int length =
                    readLength(
                            maximumLength,
                            label);
            try {
                String value =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(
                                        CodingErrorAction.REPORT)
                                .onUnmappableCharacter(
                                        CodingErrorAction.REPORT)
                                .decode(
                                        ByteBuffer.wrap(
                                                data,
                                                offset,
                                                length))
                                .toString();
                offset += length;
                return value;
            } catch (CharacterCodingException failure) {
                throw new IllegalArgumentException(
                        "NanoRegistry " + label
                                + " is not valid UTF-8",
                        failure);
            }
        }

        void skipField(
                int tag) {
            switch (tag & 7) {
                case WIRE_VARINT -> readVarint();
                case WIRE_FIXED64 -> requireAndAdvance(
                        8);
                case WIRE_LENGTH_DELIMITED -> {
                    int length =
                            readLength(
                                    MAX_PAYLOAD_LENGTH,
                                    "unknown field");
                    offset += length;
                }
                case WIRE_FIXED32 -> requireAndAdvance(
                        4);
                default -> throw new IllegalArgumentException(
                        "Unsupported NanoRegistry Class D protobuf wire type");
            }
        }

        private int readLength(
                int maximumLength,
                String label) {
            long raw =
                    readVarint();
            if (raw > maximumLength
                    || raw > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "NanoRegistry " + label + " is too large");
            }
            int length =
                    (int) raw;
            requireRemaining(
                    length);
            return length;
        }

        private void requireAndAdvance(
                int length) {
            requireRemaining(
                    length);
            offset += length;
        }

        private void requireRemaining(
                int length) {
            if (length < 0
                    || offset > limit - length) {
                throw new IllegalArgumentException(
                        "NanoRegistry Class D protobuf payload is truncated");
            }
        }
    }
}
