package dev.applewatchandroid.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * PBBridge protobufs required by fresh Watch setup on iOS/watchOS 26.6.
 *
 * <p>Activation message type 2 is deliberately represented by two separate
 * classes because Apple sends it as a non-response in both directions. The
 * wire type alone cannot identify which endpoint produced it.</p>
 */
final class PbBridgeCodec {
    static final String SERVICE =
            IdsApplicationRoute.PB_BRIDGE_SERVICE;
    static final int MESSAGE_PRIORITY = 300;

    static final int TYPE_PROXY_ACTIVATION = 2;
    // Type 3 is direction-dependent: Watch reports BeganActivating;
    // phone sends PBBProtoPushControllerType.
    static final int TYPE_BEGAN_ACTIVATING = 3;
    static final int TYPE_PUSH_CONTROLLER = 3;
    static final int CONTROLLER_BUDDY_FINISHED = 10;
    static final int TYPE_ACTIVATION_SUCCEEDED = 4;
    static final int TYPE_CAN_BEGIN_ACTIVATION = 11;
    static final int TYPE_ACTIVATION_FAILED = 12;
    // iOS 26.6 PBBridgeSupport -[PBBridgeCompanionController
    // tellGizmoToRetryActivation] sends _sendRemoteCommandWithMessageID:0xf
    // withArguments:nil; on the Watch this drives
    // -[ActivationController retryActivation:] which resets the activation
    // state machine to Idle and starts activation unconditionally.
    static final int TYPE_RETRY_ACTIVATION = 15;
    static final int TYPE_PREPARE_INITIAL_SYNC_RESPONSE = 18;
    /**
     * Phone-to-Watch PBBProtoInitialSyncStateUpdate (watchOS 26.6 gizmo
     * message 19 -> updateSynchProgress: -> delegate setSyncProgress:andState:).
     * Wire: field 1 = progress (double), field 2 = state (uint32). SetupController only logs this update; final UI progress comes from
     * PSYSyncStateObserver and the BuddyFinished controller.
     */
    static final int TYPE_UPDATE_SYNC_PROGRESS = 19;
    static final int TYPE_LANGUAGE_AND_LOCALE_STATUS = 20;
    static final int TYPE_PREPARE_INITIAL_SYNC = 21;
    static final int TYPE_LANGUAGE_AND_LOCALE = 25;
    static final int TYPE_UPDATE_NANO_REGISTRY_NORMAL = 36;
    static final int TYPE_COMPUTED_TIME_ZONE = 111;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_START_GROUP = 3;
    private static final int WIRE_END_GROUP = 4;
    private static final int WIRE_FIXED32 = 5;
    private static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;
    private static final int MAX_STRING_LENGTH = 64 * 1024;
    private static final int MAX_LANGUAGES = 64;

    /**
     * Optional live-session diagnostics hook (set by the root HAL host).
     * Never required for correctness; must never throw.
     */
    static volatile java.util.function.Consumer<String> diagnosticLogger;

    private static void logDiagnostic(
            String message) {
        java.util.function.Consumer<String> logger =
                diagnosticLogger;
        if (logger == null) {
            return;
        }
        try {
            logger.accept(
                    message);
        } catch (RuntimeException ignored) {
            // Diagnostics must never affect the session.
        }
    }

    private PbBridgeCodec() {
    }

    static byte[] encode(
            ApplicationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "PBBridge message is absent");
        }
        message.requireValid();
        if (message instanceof ActivationFetchRequest request) {
            return encodeOneBytesField(
                    request.archivedRequest);
        }
        if (message instanceof ActivationData transfer) {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            writeBytesField(
                    output,
                    1,
                    transfer.activationData);
            writeBytesField(
                    output,
                    2,
                    transfer.archivedResponseHeaders);
            return requireSize(
                    output.toByteArray());
        }
        if (message instanceof ActivationFailed failure) {
            return encodeOneStringField(
                    failure.failureDescription);
        }
        if (message instanceof LanguageAndLocaleStatus status) {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            writeTag(
                    output,
                    1,
                    WIRE_FIXED64);
            writeFixed64(
                    output,
                    Double.doubleToRawLongBits(
                            status.status));
            return requireSize(
                    output.toByteArray());
        }
        if (message instanceof LanguageAndLocale settings) {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            for (String language :
                    settings.appleLanguages) {
                writeStringField(
                        output,
                        1,
                        language);
            }
            writeStringField(
                    output,
                    2,
                    settings.appleLocale);
            writeBytesField(
                    output,
                    3,
                    settings.archivedPreferences);
            return requireSize(
                    output.toByteArray());
        }
        if (message instanceof UpdateSyncProgress sync) {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            writeTag(
                    output,
                    1,
                    WIRE_FIXED64);
            writeFixed64(
                    output,
                    Double.doubleToRawLongBits(
                            sync.progress));
            writeTag(
                    output,
                    2,
                    WIRE_VARINT);
            writeVarint(
                    output,
                    sync.state & 0xffffffffL);
            return requireSize(
                    output.toByteArray());
        }
        if (message instanceof PushBuddyFinished) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeTag(output, 1, WIRE_VARINT);
            writeVarint(output, CONTROLLER_BUDDY_FINISHED);
            return output.toByteArray();
        }
        if (message instanceof ComputedTimeZone timeZone) {
            return encodeOneStringField(
                    timeZone.computedTimeZone);
        }
        return new byte[0];
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
                    "PBBridge message is absent");
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

    /**
     * Validates the envelope without guessing which endpoint sent type 2.
     */
    static void validateEnvelope(
            IdsSocketPairCodec.ProtobufMessage envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException(
                    "PBBridge IDS envelope is absent");
        }
        switch (envelope.protobufType) {
            case TYPE_PROXY_ACTIVATION -> {
                rejectResponse(
                        envelope,
                        "activation transfer");
                validateActivationTransfer(
                        envelope.payload);
            }
            case TYPE_BEGAN_ACTIVATING -> {
                rejectResponse(
                        envelope,
                        "began activating");
                skipCompatibleEmptyMessage(
                        envelope.payload);
            }
            case TYPE_ACTIVATION_SUCCEEDED -> {
                rejectResponse(
                        envelope,
                        "activation success");
                skipCompatibleEmptyMessage(
                        envelope.payload);
            }
            case TYPE_CAN_BEGIN_ACTIVATION -> {
                rejectResponse(
                        envelope,
                        "activation permit");
                skipCompatibleEmptyMessage(
                        envelope.payload);
            }
            case TYPE_RETRY_ACTIVATION -> {
                rejectResponse(
                        envelope,
                        "activation retry");
                skipCompatibleEmptyMessage(
                        envelope.payload);
            }
            case TYPE_ACTIVATION_FAILED -> {
                rejectResponse(
                        envelope,
                        "activation failure");
                decodeActivationFailed(
                        envelope.payload);
            }
            case TYPE_LANGUAGE_AND_LOCALE_STATUS -> {
                rejectResponse(
                        envelope,
                        "language-and-locale status");
                decodeLanguageAndLocaleStatus(
                        envelope.payload);
            }
            case TYPE_PREPARE_INITIAL_SYNC -> {
                rejectResponse(
                        envelope,
                        "initial-sync preparation");
                if ((envelope.flags
                        & IdsSocketPairCodec
                        .FLAG_EXPECTS_PEER_RESPONSE) == 0) {
                    throw new IllegalArgumentException(
                            "PBBridge initial-sync preparation must "
                                    + "expect a peer response");
                }
                skipCompatibleEmptyMessage(
                        envelope.payload);
            }
            case TYPE_PREPARE_INITIAL_SYNC_RESPONSE -> {
                if (!envelope.response
                        || envelope.peerResponseIdentifier == null
                        || envelope.peerResponseIdentifier.isEmpty()
                        || (envelope.flags
                        & IdsSocketPairCodec
                        .FLAG_EXPECTS_PEER_RESPONSE) != 0) {
                    throw new IllegalArgumentException(
                            "PBBridge initial-sync acknowledgement "
                                    + "has invalid response metadata");
                }
                skipCompatibleEmptyMessage(
                        envelope.payload);
            }
            case TYPE_LANGUAGE_AND_LOCALE -> {
                rejectResponse(
                        envelope,
                        "language-and-locale update");
                LanguageAndLocale decoded =
                        decodeLanguageAndLocale(
                                envelope.payload);
                decoded.destroy();
            }
            case TYPE_UPDATE_NANO_REGISTRY_NORMAL -> {
                rejectResponse(
                        envelope,
                        "NanoRegistry Normal transition");
                skipCompatibleEmptyMessage(
                        envelope.payload);
            }
            case TYPE_UPDATE_SYNC_PROGRESS -> rejectResponse(
                    envelope,
                    "sync progress update");
            case TYPE_COMPUTED_TIME_ZONE -> {
                rejectResponse(
                        envelope,
                        "computed time zone");
                decodeComputedTimeZone(
                        envelope.payload);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported PBBridge protobuf type");
        }
    }

    static ActivationFetchRequest decodeActivationFetch(
            byte[] payload) {
        ActivationFields fields =
                parseActivationFields(
                        payload);
        try {
            if (fields.second != null) {
                throw new IllegalArgumentException(
                        "PBBridge activation fetch contains response headers");
            }
            return new ActivationFetchRequest(
                    fields.first);
        } finally {
            fields.destroy();
        }
    }

    static ActivationData decodeActivationData(
            byte[] payload) {
        ActivationFields fields =
                parseActivationFields(
                        payload);
        try {
            if (fields.second == null) {
                throw new IllegalArgumentException(
                        "PBBridge activation data lacks response headers");
            }
            return new ActivationData(
                    fields.first,
                    fields.second);
        } finally {
            fields.destroy();
        }
    }

    static ActivationFailed decodeActivationFailed(
            byte[] payload) {
        try {
            ActivationFailed decoded =
                    new ActivationFailed(
                            decodeSingleStringField(
                                    payload,
                                    "activation failure description"));
            logDiagnostic(
                    "PBBRIDGE ACTIVATION FAILED RX: payloadBytes="
                            + (payload == null ? -1 : payload.length)
                            + " description=\""
                            + abbreviateForLog(
                                    decoded.failureDescription(),
                                    4096)
                            + "\"");
            return decoded;
        } catch (RuntimeException decodeFailure) {
            logDiagnostic(
                    "PBBRIDGE ACTIVATION FAILED RX (undecodable): payloadBytes="
                            + (payload == null ? -1 : payload.length)
                            + " error="
                            + decodeFailure.getMessage()
                            + " hexPrefix="
                            + hexPrefix(
                                    payload,
                                    96));
            throw decodeFailure;
        }
    }

    private static String abbreviateForLog(
            String value,
            int maxLength) {
        if (value == null) {
            return "";
        }
        String sanitized =
                value.replace(
                        '\n',
                        ' ')
                        .replace(
                                '\r',
                                ' ');
        if (sanitized.length() <= maxLength) {
            return sanitized;
        }
        return sanitized.substring(
                0,
                maxLength)
                + "…";
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

    static LanguageAndLocaleStatus decodeLanguageAndLocaleStatus(
            byte[] payload) {
        Reader reader =
                new Reader(
                        requirePayload(
                                payload));
        Double status = null;
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            if ((tag >>> 3) == 1) {
                rejectDuplicate(
                        status != null,
                        "language-and-locale status");
                requireWire(
                        tag,
                        WIRE_FIXED64);
                status =
                        Double.longBitsToDouble(
                                reader.readFixed64());
            } else {
                reader.skipField(
                        tag);
            }
        }
        if (status == null
                || !Double.isFinite(
                        status)
                || status != Math.rint(
                        status)
                || status < 0
                || status > 0xffff) {
            throw new IllegalArgumentException(
                    "PBBridge language-and-locale status is invalid");
        }
        return new LanguageAndLocaleStatus(
                status.intValue());
    }

    static LanguageAndLocale decodeLanguageAndLocale(
            byte[] payload) {
        Reader reader =
                new Reader(
                        requirePayload(
                                payload));
        List<String> languages =
                new ArrayList<>();
        String locale = null;
        byte[] archivedPreferences = null;
        try {
            while (reader.hasRemaining()) {
                int tag =
                        reader.readTag();
                int field =
                        tag >>> 3;
                if (field == 1) {
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    if (languages.size()
                            >= MAX_LANGUAGES) {
                        throw new IllegalArgumentException(
                                "PBBridge language count is too large");
                    }
                    languages.add(
                            reader.readString());
                } else if (field == 2) {
                    rejectDuplicate(
                            locale != null,
                            "Apple locale");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    locale =
                            reader.readString();
                } else if (field == 3) {
                    rejectDuplicate(
                            archivedPreferences != null,
                            "archived locale preferences");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    archivedPreferences =
                            reader.readBytes();
                } else {
                    reader.skipField(
                            tag);
                }
            }
            LanguageAndLocale decoded =
                    new LanguageAndLocale(
                            languages,
                            locale,
                            archivedPreferences);
            wipe(
                    archivedPreferences);
            return decoded;
        } catch (RuntimeException failure) {
            wipe(
                    archivedPreferences);
            throw failure;
        }
    }

    static ComputedTimeZone decodeComputedTimeZone(
            byte[] payload) {
        return new ComputedTimeZone(
                decodeSingleStringField(
                        payload,
                        "computed time zone"));
    }

    private static String decodeSingleStringField(
            byte[] payload,
            String label) {
        Reader reader =
                new Reader(
                        requirePayload(
                                payload));
        String value = null;
        while (reader.hasRemaining()) {
            int tag =
                    reader.readTag();
            if ((tag >>> 3) == 1) {
                rejectDuplicate(
                        value != null,
                        label);
                requireWire(
                        tag,
                        WIRE_LENGTH_DELIMITED);
                value =
                        reader.readString();
            } else {
                reader.skipField(
                        tag);
            }
        }
        if (value == null) {
            throw new IllegalArgumentException(
                    "PBBridge "
                            + label
                            + " is absent");
        }
        return value;
    }

    private static byte[] encodeOneBytesField(
            byte[] value) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeBytesField(
                output,
                1,
                value);
        return requireSize(
                output.toByteArray());
    }

    private static byte[] encodeOneStringField(
            String value) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        writeStringField(
                output,
                1,
                value);
        return requireSize(
                output.toByteArray());
    }

    private static void validateActivationTransfer(
            byte[] payload) {
        ActivationFields fields =
                parseActivationFields(
                        payload);
        fields.destroy();
    }

    private static ActivationFields parseActivationFields(
            byte[] payload) {
        Reader reader =
                new Reader(
                        requirePayload(
                                payload));
        byte[] first = null;
        byte[] second = null;
        try {
            while (reader.hasRemaining()) {
                int tag;
                try {
                    tag = reader.readTag();
                } catch (RuntimeException tagEx) {
                    if (first != null) {
                        break;
                    }
                    throw tagEx;
                }
                int field =
                        tag >>> 3;
                if (field == 1) {
                    rejectDuplicate(
                            first != null,
                            "activation bytes");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    first =
                            reader.readBytes();
                } else if (field == 2) {
                    rejectDuplicate(
                            second != null,
                            "activation response headers");
                    requireWire(
                            tag,
                            WIRE_LENGTH_DELIMITED);
                    second =
                            reader.readBytes();
                } else {
                    try {
                        if (!reader.skipField(tag)) {
                            break;
                        }
                    } catch (RuntimeException skipEx) {
                        if (first != null) {
                            break;
                        }
                        throw skipEx;
                    }
                }
            }
            if (first == null) {
                throw new IllegalArgumentException(
                        "PBBridge activation field 1 is absent");
            }
            return new ActivationFields(
                    first,
                    second);
        } catch (RuntimeException failure) {
            wipe(
                    first);
            wipe(
                    second);
            throw failure;
        }
    }

    private static void skipCompatibleEmptyMessage(
            byte[] payload) {
        Reader reader =
                new Reader(
                        requirePayload(
                                payload));
        while (reader.hasRemaining()) {
            if (!reader.skipField(reader.readTag())) {
                break;
            }
        }
    }

    private static byte[] requirePayload(
            byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "PBBridge protobuf payload length is invalid");
        }
        byte[] uncompressed =
                decompressIfGzip(
                        payload);
        if (uncompressed.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "PBBridge protobuf payload length is invalid");
        }
        return uncompressed;
    }

    private static byte[] decompressIfGzip(
            byte[] payload) {
        if (payload.length >= 2
                && (payload[0] == 0x1f)
                && (payload[1] == (byte) 0x8b)) {
            try (GZIPInputStream gzip =
                         new GZIPInputStream(
                                 new ByteArrayInputStream(payload));
                 ByteArrayOutputStream out =
                         new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = gzip.read(buffer)) > 0) {
                    if (out.size() + n > MAX_PAYLOAD_LENGTH) {
                        throw new IllegalArgumentException(
                                "Decompressed PBBridge payload exceeds max length");
                    }
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            } catch (IOException ex) {
                return payload;
            }
        }
        return payload;
    }

    private static byte[] requireSize(
            byte[] payload) {
        requirePayload(
                payload);
        return payload;
    }

    private static void rejectResponse(
            IdsSocketPairCodec.ProtobufMessage envelope,
            String label) {
        // Tolerated gracefully without dropping session
    }

    private static void rejectDuplicate(
            boolean duplicate,
            String label) {
        if (duplicate) {
            throw new IllegalArgumentException(
                    "Duplicate PBBridge "
                            + label);
        }
    }

    private static void requireWire(
            int tag,
            int expected) {
        if ((tag & 7) != expected) {
            throw new IllegalArgumentException(
                    "PBBridge protobuf wire type mismatch");
        }
    }

    private static void writeTag(
            ByteArrayOutputStream output,
            int field,
            int wireType) {
        writeVarint(
                output,
                ((long) field << 3)
                        | wireType);
    }

    private static void writeFixed64(
            ByteArrayOutputStream output,
            long value) {
        for (int shift = 0;
                shift < Long.SIZE;
                shift += Byte.SIZE) {
            output.write(
                    (int) (value >>> shift)
                            & 0xff);
        }
    }

    private static void writeStringField(
            ByteArrayOutputStream output,
            int field,
            String value) {
        requireString(
                value,
                true,
                "string field");
        byte[] encoded =
                value.getBytes(
                        StandardCharsets.UTF_8);
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
                    "PBBridge bytes field is absent");
        }
        writeVarint(
                output,
                ((long) field << 3)
                        | WIRE_LENGTH_DELIMITED);
        writeVarint(
                output,
                value.length);
        output.writeBytes(
                value);
    }

    private static void requireString(
            String value,
            boolean allowEmpty,
            String label) {
        if (value == null
                || (!allowEmpty && value.isEmpty())
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "PBBridge "
                            + label
                            + " is invalid");
        }
        byte[] encoded =
                value.getBytes(
                        StandardCharsets.UTF_8);
        try {
            if (encoded.length > MAX_STRING_LENGTH
                    || !value.equals(
                            new String(
                                    encoded,
                                    StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException(
                        "PBBridge "
                                + label
                                + " is invalid");
            }
        } finally {
            wipe(
                    encoded);
        }
    }

    private static void writeVarint(
            ByteArrayOutputStream output,
            long value) {
        do {
            int next =
                    (int) value & 0x7f;
            value >>>= 7;
            if (value != 0) {
                next |= 0x80;
            }
            output.write(
                    next);
        } while (value != 0);
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

    /** Watch-to-phone MobileActivation began event. */
    static final class BeganActivating
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_BEGAN_ACTIVATING;
        }
    }

    /** Watch-to-phone MobileActivation success event. */
    static final class ActivationSucceeded
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_ACTIVATION_SUCCEEDED;
        }
    }

    /** Watch-to-phone MobileActivation failure event. */
    static final class ActivationFailed
            extends ApplicationMessage {
        private final String failureDescription;

        ActivationFailed(
                String failureDescription) {
            this.failureDescription =
                    failureDescription;
            requireValid();
        }

        String failureDescription() {
            return failureDescription;
        }

        @Override
        int protobufType() {
            return TYPE_ACTIVATION_FAILED;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            requireString(
                    failureDescription,
                    true,
                    "activation failure description");
        }
    }

    static final class CanBeginActivation
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_CAN_BEGIN_ACTIVATION;
        }
    }

    static final class RetryActivation
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_RETRY_ACTIVATION;
        }
    }

    /** Watch-to-phone status for the independent type-25 operation. */
    static final class LanguageAndLocaleStatus
            extends ApplicationMessage {
        private final int status;

        LanguageAndLocaleStatus(
                int status) {
            this.status =
                    status;
            requireValid();
        }

        int status() {
            return status;
        }

        boolean completedWithoutRelaunch() {
            return status == 1;
        }

        boolean completedAfterRelaunch() {
            return status == 2;
        }

        boolean recognizedCompletion() {
            return completedWithoutRelaunch()
                    || completedAfterRelaunch();
        }

        @Override
        int protobufType() {
            return TYPE_LANGUAGE_AND_LOCALE_STATUS;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (status < 0
                    || status > 0xffff) {
                throw new IllegalArgumentException(
                        "PBBridge language-and-locale status is invalid");
            }
        }
    }

    /** watchOS 26.6 Setup FUN_100019854 maps controller 10 to BuddyFinished. */
    static final class PushBuddyFinished extends ApplicationMessage {
        @Override int protobufType() { return TYPE_PUSH_CONTROLLER; }
        @Override boolean response() { return false; }
        @Override void requireValid() { }
    }

    /** Phone-to-Watch request; Watch answers with type 18. */
    static final class PrepareInitialSync
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_PREPARE_INITIAL_SYNC;
        }
    }

    /** Watch-to-phone response correlated to the type-21 request UUID. */
    static final class PrepareInitialSyncResponse
            extends ApplicationMessage {
        @Override
        int protobufType() {
            return TYPE_PREPARE_INITIAL_SYNC_RESPONSE;
        }

        @Override
        boolean response() {
            return true;
        }

        @Override
        void requireValid() {
        }
    }

    /** Phone-to-Watch locale update; field 3 is an opaque secure archive. */
    static final class LanguageAndLocale
            extends ApplicationMessage {
        private List<String> appleLanguages;
        private final String appleLocale;
        private byte[] archivedPreferences;

        LanguageAndLocale(
                List<String> appleLanguages,
                String appleLocale,
                byte[] archivedPreferences) {
            this.appleLanguages =
                    appleLanguages == null
                            ? null
                            : Collections.unmodifiableList(
                                    new ArrayList<>(
                                            appleLanguages));
            this.appleLocale =
                    appleLocale;
            this.archivedPreferences =
                    archivedPreferences == null
                            ? null
                            : archivedPreferences.clone();
            requireValid();
        }

        List<String> appleLanguages() {
            requireValid();
            return appleLanguages;
        }

        String appleLocale() {
            requireValid();
            return appleLocale;
        }

        byte[] archivedPreferences() {
            requireValid();
            return archivedPreferences.clone();
        }

        @Override
        int protobufType() {
            return TYPE_LANGUAGE_AND_LOCALE;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (appleLanguages == null
                    || appleLanguages.isEmpty()
                    || appleLanguages.size()
                    > MAX_LANGUAGES) {
                throw new IllegalArgumentException(
                        "PBBridge Apple languages are invalid");
            }
            long stringBytes = 0;
            for (String language :
                    appleLanguages) {
                requireString(
                        language,
                        false,
                        "Apple language");
                stringBytes +=
                        language.getBytes(
                                StandardCharsets.UTF_8).length;
            }
            requireString(
                    appleLocale,
                    true,
                    "Apple locale");
            stringBytes +=
                    appleLocale.getBytes(
                            StandardCharsets.UTF_8).length;
            if (archivedPreferences == null
                    || archivedPreferences.length == 0
                    || archivedPreferences.length
                    > MAX_PAYLOAD_LENGTH
                    || stringBytes
                    + archivedPreferences.length
                    > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge archived locale preferences are invalid");
            }
            LocalePreferencesArchiveCodec.Preferences decoded =
                    LocalePreferencesArchiveCodec.decode(
                            archivedPreferences);
            try {
                if (!appleLanguages.equals(
                        decoded.appleLanguages())
                        || !appleLocale.equals(
                        decoded.appleLocale())) {
                    throw new IllegalArgumentException(
                            "PBBridge locale fields do not match the archive");
                }
            } finally {
                decoded.destroy();
            }
        }

        @Override
        void destroy() {
            appleLanguages = null;
            wipe(
                    archivedPreferences);
            archivedPreferences = null;
        }
    }

    static final class UpdateNanoRegistryNormal
            extends EmptyRequest {
        @Override
        int protobufType() {
            return TYPE_UPDATE_NANO_REGISTRY_NORMAL;
        }
    }

    /** Phone-to-Watch one-way initial-sync progress/state update. */
    static final class UpdateSyncProgress
            extends ApplicationMessage {
        private final double progress;
        private final int state;

        UpdateSyncProgress(
                double progress,
                int state) {
            this.progress =
                    progress;
            this.state =
                    state;
            requireValid();
        }

        @Override
        int protobufType() {
            return TYPE_UPDATE_SYNC_PROGRESS;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (Double.isNaN(progress)
                    || Double.isInfinite(progress)
                    || progress < 0.0
                    || progress > 1.0
                    || state < 0) {
                throw new IllegalArgumentException(
                        "PBBridge sync progress update is invalid");
            }
        }
    }

    /** Phone-to-Watch one-way timezone update. */
    static final class ComputedTimeZone
            extends ApplicationMessage {
        private final String computedTimeZone;

        ComputedTimeZone(
                String computedTimeZone) {
            this.computedTimeZone =
                    computedTimeZone;
            requireValid();
        }

        String computedTimeZone() {
            return computedTimeZone;
        }

        @Override
        int protobufType() {
            return TYPE_COMPUTED_TIME_ZONE;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            requireString(
                    computedTimeZone,
                    false,
                    "computed time zone");
        }
    }

    abstract static class EmptyRequest
            extends ApplicationMessage {
        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
        }
    }

    static final class ActivationFetchRequest
            extends ApplicationMessage {
        private byte[] archivedRequest;

        ActivationFetchRequest(
                byte[] archivedRequest) {
            this.archivedRequest =
                    archivedRequest == null
                            ? null
                            : archivedRequest.clone();
            requireValid();
        }

        byte[] archivedRequest() {
            requireValid();
            return archivedRequest.clone();
        }

        @Override
        int protobufType() {
            return TYPE_PROXY_ACTIVATION;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (archivedRequest == null
                    || archivedRequest.length == 0
                    || archivedRequest.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge archived activation request is invalid");
            }
        }

        @Override
        void destroy() {
            wipe(
                    archivedRequest);
            archivedRequest =
                    null;
        }
    }

    static final class ActivationData
            extends ApplicationMessage {
        private byte[] activationData;
        private byte[] archivedResponseHeaders;

        ActivationData(
                byte[] activationData,
                byte[] archivedResponseHeaders) {
            this.activationData =
                    activationData == null
                            ? null
                            : activationData.clone();
            this.archivedResponseHeaders =
                    archivedResponseHeaders == null
                            ? null
                            : archivedResponseHeaders.clone();
            requireValid();
        }

        byte[] activationData() {
            requireValid();
            return activationData.clone();
        }

        byte[] archivedResponseHeaders() {
            requireValid();
            return archivedResponseHeaders.clone();
        }

        @Override
        int protobufType() {
            return TYPE_PROXY_ACTIVATION;
        }

        @Override
        boolean response() {
            return false;
        }

        @Override
        void requireValid() {
            if (activationData == null
                    || activationData.length == 0
                    || activationData.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge activation data is invalid");
            }
            if (archivedResponseHeaders == null
                    || archivedResponseHeaders.length == 0
                    || archivedResponseHeaders.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge archived response headers are invalid");
            }
            if ((long) activationData.length
                    + archivedResponseHeaders.length
                    > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "PBBridge activation transfer is too large");
            }
        }

        @Override
        void destroy() {
            wipe(
                    activationData);
            activationData =
                    null;
            wipe(
                    archivedResponseHeaders);
            archivedResponseHeaders =
                    null;
        }
    }

    private static final class ActivationFields {
        private byte[] first;
        private byte[] second;

        private ActivationFields(
                byte[] first,
                byte[] second) {
            this.first = first;
            this.second = second;
        }

        private void destroy() {
            wipe(
                    first);
            first = null;
            wipe(
                    second);
            second = null;
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        private Reader(
                byte[] data) {
            this.data = data;
        }

        private boolean hasRemaining() {
            return offset < data.length;
        }

        private int readTag() {
            long tag =
                    readVarint();
            if (tag == 0
                    || tag > Integer.MAX_VALUE
                    || (tag >>> 3) == 0) {
                throw new IllegalArgumentException(
                        "Invalid PBBridge protobuf tag");
            }
            return (int) tag;
        }

        private long readVarint() {
            long value = 0;
            for (int shift = 0;
                    shift < 64;
                    shift += 7) {
                if (offset >= data.length) {
                    throw new IllegalArgumentException(
                            "PBBridge protobuf varint is truncated");
                }
                int next =
                        data[offset++] & 0xff;
                if (shift == 63
                        && (next & 0xfe) != 0) {
                    throw new IllegalArgumentException(
                            "PBBridge protobuf varint overflows uint64");
                }
                value |=
                        (long) (next & 0x7f)
                                << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException(
                    "PBBridge protobuf varint is unterminated");
        }

        private long readFixed64() {
            if (data.length - offset < Long.BYTES) {
                throw new IllegalArgumentException(
                        "PBBridge protobuf fixed64 is truncated");
            }
            long value = 0;
            for (int shift = 0;
                    shift < Long.SIZE;
                    shift += Byte.SIZE) {
                value |=
                        (long) (data[offset++] & 0xff)
                                << shift;
            }
            return value;
        }

        private byte[] readBytes() {
            long length =
                    readVarint();
            if (length > MAX_PAYLOAD_LENGTH
                    || length > data.length - offset) {
                throw new IllegalArgumentException(
                        "PBBridge bytes field is truncated");
            }
            byte[] value =
                    Arrays.copyOfRange(
                            data,
                            offset,
                            offset + (int) length);
            offset +=
                    (int) length;
            return value;
        }

        private String readString() {
            byte[] encoded =
                    readBytes();
            try {
                if (encoded.length > MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException(
                            "PBBridge string field is too large");
                }
                String value =
                        new String(
                                encoded,
                                StandardCharsets.UTF_8);
                if (!Arrays.equals(
                        encoded,
                        value.getBytes(
                                StandardCharsets.UTF_8))) {
                    throw new IllegalArgumentException(
                            "PBBridge string field is not valid UTF-8");
                }
                requireString(
                        value,
                        true,
                        "string field");
                return value;
            } finally {
                wipe(
                        encoded);
            }
        }

        private boolean skipField(
                int tag) {
            int wireType = tag & 7;
            switch (wireType) {
                case WIRE_VARINT -> {
                    readVarint();
                    return true;
                }
                case WIRE_FIXED64 -> {
                    skip(8);
                    return true;
                }
                case WIRE_LENGTH_DELIMITED -> {
                    long length =
                            readVarint();
                    if (length > MAX_PAYLOAD_LENGTH) {
                        throw new IllegalArgumentException(
                                "PBBridge protobuf field is too large");
                    }
                    skip((int) length);
                    return true;
                }
                case WIRE_START_GROUP -> {
                    skipGroup(tag >>> 3);
                    return true;
                }
                case WIRE_END_GROUP -> {
                    return false;
                }
                case WIRE_FIXED32 -> {
                    skip(4);
                    return true;
                }
                default -> throw new IllegalArgumentException(
                        "Unsupported PBBridge protobuf wire type: " + wireType
                                + " (tag=" + tag + ", field=" + (tag >>> 3)
                                + ", offset=" + offset + "/" + data.length + ")");
            }
        }

        private void skipGroup(
                int groupNumber) {
            while (hasRemaining()) {
                int tag = readTag();
                int wireType = tag & 7;
                int field = tag >>> 3;
                if (wireType == WIRE_END_GROUP && field == groupNumber) {
                    return;
                }
                if (!skipField(tag)) {
                    return;
                }
            }
            throw new IllegalArgumentException(
                    "PBBridge protobuf group " + groupNumber + " is unterminated");
        }

        private void skip(
                int length) {
            if (length < 0
                    || length > data.length - offset) {
                throw new IllegalArgumentException(
                        "PBBridge protobuf payload is truncated");
            }
            offset += length;
        }
    }
}
