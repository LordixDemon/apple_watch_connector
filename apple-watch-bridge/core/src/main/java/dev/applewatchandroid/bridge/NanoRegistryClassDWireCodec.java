package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;


/**
 * Protobuf wire encoder, decoder, reader, and field validation helpers for
 * {@link NanoRegistryClassDCodec}.
 */
final class NanoRegistryClassDWireCodec {
    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED32 = 5;

    private NanoRegistryClassDWireCodec() {
    }

    static byte[] encode(
            NanoRegistryClassDCodec.ApplicationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class D message is absent");
        }
        message.requireValid();
        byte[] encoded;
        if (message instanceof NanoRegistryClassDCodec.DeviceWillUnpairRequest request) {
            encoded = encodeDeviceWillUnpairRequest(
                    request);
        } else if (message instanceof NanoRegistryClassDCodec.DeviceWillUnpairResponse) {
            encoded = new byte[0];
        } else if (message instanceof NanoRegistryClassDCodec.PairingModeRequest request) {
            encoded = encodePairingModeRequest(
                    request);
        } else if (message instanceof NanoRegistryClassDCodec.PairingModeResponse response) {
            encoded = encodePairingModeResponse(
                    response);
        } else if (message instanceof NanoRegistryClassDCodec.PingRequest request) {
            encoded = encodePingRequest(
                    request);
        } else if (message instanceof NanoRegistryClassDCodec.PingResponse response) {
            encoded = encodePingResponse(
                    response);
        } else if (message
                instanceof NanoRegistryClassDCodec.WatchMigrationCompletionRequest request) {
            encoded = encodeWatchMigrationCompletionRequest(
                    request);
        } else if (message
                instanceof NanoRegistryClassDCodec.RtcMigrationMetricSessionId request) {
            encoded = encodeOptionalString(
                    request.sessionId,
                    "RTC migration metric session ID");
        } else if (message instanceof NanoRegistryClassDCodec.PairingSessionId request) {
            encoded = encodeOptionalString(
                    request.pairingSessionId,
                    "pairing session ID");
        } else if (message instanceof NanoRegistryClassDCodec.GraduationRequest) {
            encoded = new byte[0];
        } else {
            throw new IllegalArgumentException(
                    "Unknown NanoRegistry Class D message");
        }
        return requireEncodedSize(
                encoded);
    }

    static NanoRegistryClassDCodec.ApplicationMessage decode(
            IdsSocketPairCodec.ProtobufMessage envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException(
                    "IDS protobuf envelope is absent");
        }
        return switch (envelope.protobufType) {
            case NanoRegistryClassDCodec.TYPE_DEVICE_WILL_UNPAIR ->
                    envelope.response
                            ? decodeDeviceWillUnpairResponse(
                                    envelope.payload)
                            : decodeDeviceWillUnpairRequest(
                                    envelope.payload);
            case NanoRegistryClassDCodec.TYPE_PAIRING_MODE ->
                    envelope.response
                            ? decodePairingModeResponse(
                                    envelope.payload)
                            : decodePairingModeRequest(
                                    envelope.payload);
            case NanoRegistryClassDCodec.TYPE_PING ->
                    envelope.response
                            ? decodePingResponse(
                                    envelope.payload)
                            : decodePingRequest(
                                    envelope.payload);
            case NanoRegistryClassDCodec.TYPE_WATCH_MIGRATION_COMPLETION -> {
                rejectResponse(
                        envelope.response,
                        "watch migration completion");
                yield decodeWatchMigrationCompletionRequest(
                        envelope.payload);
            }
            case NanoRegistryClassDCodec.TYPE_RTC_MIGRATION_METRIC_SESSION_ID -> {
                rejectResponse(
                        envelope.response,
                        "RTC migration metric session ID");
                yield decodeRtcMigrationMetricSessionId(
                        envelope.payload);
            }
            case NanoRegistryClassDCodec.TYPE_PAIRING_SESSION_ID -> {
                rejectResponse(
                        envelope.response,
                        "pairing session ID");
                yield decodePairingSessionId(
                        envelope.payload);
            }
            case NanoRegistryClassDCodec.TYPE_GRADUATION -> {
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
            NanoRegistryClassDCodec.ApplicationMessage message,
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
            NanoRegistryClassDCodec.DeviceWillUnpairRequest request) {
        requireMessage(
                request,
                "device-will-unpair request");
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeStringField(
                output,
                1,
                request.advertisedName,
                NanoRegistryClassDCodec.MAX_ADVERTISED_NAME_LENGTH,
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
                    NanoRegistryClassDCodec.MAX_STRING_LENGTH,
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

    static NanoRegistryClassDCodec.DeviceWillUnpairRequest decodeDeviceWillUnpairRequest(
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
                                    NanoRegistryClassDCodec.MAX_ADVERTISED_NAME_LENGTH,
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
                                    NanoRegistryClassDCodec.MAX_STRING_LENGTH,
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
        return new NanoRegistryClassDCodec.DeviceWillUnpairRequest(
                advertisedName,
                shouldObliterate,
                pairingFailureCode,
                abortReason,
                shouldBrick,
                shouldPreserveEsim);
    }

    static NanoRegistryClassDCodec.DeviceWillUnpairResponse decodeDeviceWillUnpairResponse(
            byte[] payload) {
        skipEmptyMessageFields(
                payload);
        return new NanoRegistryClassDCodec.DeviceWillUnpairResponse();
    }

    static byte[] encodePairingModeRequest(
            NanoRegistryClassDCodec.PairingModeRequest request) {
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

    static NanoRegistryClassDCodec.PairingModeRequest decodePairingModeRequest(
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
        return new NanoRegistryClassDCodec.PairingModeRequest(
                pairingMode,
                phoneMax,
                watchVersion,
                phoneMin);
    }

    static byte[] encodePairingModeResponse(
            NanoRegistryClassDCodec.PairingModeResponse response) {
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

    static NanoRegistryClassDCodec.PairingModeResponse decodePairingModeResponse(
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
        return new NanoRegistryClassDCodec.PairingModeResponse(
                success);
    }

    static byte[] encodePingRequest(
            NanoRegistryClassDCodec.PingRequest request) {
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

    static NanoRegistryClassDCodec.PingRequest decodePingRequest(
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
                                        NanoRegistryClassDCodec.MAX_PAYLOAD_LENGTH,
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
            return new NanoRegistryClassDCodec.PingRequest(
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
            NanoRegistryClassDCodec.PingResponse response) {
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

    static NanoRegistryClassDCodec.PingResponse decodePingResponse(
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
                                        NanoRegistryClassDCodec.MAX_PAYLOAD_LENGTH,
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
            return new NanoRegistryClassDCodec.PingResponse(
                    responseDate,
                    responsePayload);
        } finally {
            wipe(
                    responsePayload);
        }
    }

    static byte[] encodeWatchMigrationCompletionRequest(
            NanoRegistryClassDCodec.WatchMigrationCompletionRequest request) {
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

    static NanoRegistryClassDCodec.WatchMigrationCompletionRequest
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
        return new NanoRegistryClassDCodec.WatchMigrationCompletionRequest(
                status);
    }

    static NanoRegistryClassDCodec.RtcMigrationMetricSessionId
            decodeRtcMigrationMetricSessionId(
                    byte[] payload) {
        return new NanoRegistryClassDCodec.RtcMigrationMetricSessionId(
                decodeOptionalString(
                        payload,
                        "RTC migration metric session ID"));
    }

    static NanoRegistryClassDCodec.PairingSessionId decodePairingSessionId(
            byte[] payload) {
        return new NanoRegistryClassDCodec.PairingSessionId(
                decodeOptionalString(
                        payload,
                        "pairing session ID"));
    }

    static NanoRegistryClassDCodec.GraduationRequest decodeGraduationRequest(
            byte[] payload) {
        skipEmptyMessageFields(
                payload);
        return new NanoRegistryClassDCodec.GraduationRequest();
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
                    NanoRegistryClassDCodec.MAX_SESSION_ID_LENGTH,
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
                                NanoRegistryClassDCodec.MAX_SESSION_ID_LENGTH,
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
        if (payload.length > NanoRegistryClassDCodec.MAX_PAYLOAD_LENGTH) {
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

    static void validateString(
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

    static byte[] clonePayload(
            byte[] payload,
            String label) {
        if (payload == null) {
            return null;
        }
        if (payload.length > NanoRegistryClassDCodec.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "NanoRegistry " + label + " is too large");
        }
        return payload.clone();
    }

    private static byte[] requireEncodedSize(
            byte[] payload) {
        if (payload.length > NanoRegistryClassDCodec.MAX_PAYLOAD_LENGTH) {
            wipe(
                    payload);
            throw new IllegalArgumentException(
                    "NanoRegistry Class D payload is too large");
        }
        return payload;
    }

    private static void requireMessage(
            NanoRegistryClassDCodec.ApplicationMessage message,
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

    static boolean isCanonicalUuid(
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

    static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
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
                                    NanoRegistryClassDCodec.MAX_PAYLOAD_LENGTH,
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
