package dev.applewatchandroid.bridge;

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

/**
 * iOS 26.6 Network.framework service-connector framing used before the
 * modern IDS control/data byte streams.
 *
 * <p>The two-byte big-endian length is the body length and excludes the
 * length field itself. Signed requests are Ed25519 message signatures over
 * the entire final-sized frame while its 64-byte signature field is zero.
 * After a normal request is accepted, IDS socket-pair frames are sent
 * directly; {@link IdsStreamEncryption} belongs to the legacy UTun path.</p>
 */
final class NwServiceConnectorCodec {
    static final int LENGTH_PREFIX_LENGTH = 2;
    static final int LOCAL_PORT_OFFSET = 2;
    static final int SEQUENCE_OFFSET = 4;

    static final int UUID_LENGTH = 16;
    static final int PUBLIC_KEY_LENGTH = 32;
    static final int PRIVATE_KEY_LENGTH = 32;
    static final int SIGNATURE_LENGTH = 64;

    static final int NORMAL_UUID_OFFSET = 12;
    static final int NORMAL_SERVICE_LENGTH_OFFSET = 28;
    static final int NORMAL_SERVICE_OFFSET = 29;
    static final int NORMAL_FIXED_BODY_LENGTH = 91;
    static final int NORMAL_FIXED_FRAME_LENGTH = 93;
    static final int MAX_SERVICE_LENGTH = 255;

    static final int OPERATION_BODY_LENGTH = 79;
    static final int OPERATION_FRAME_LENGTH = 81;
    static final int OPERATION_DISCRIMINATOR_OFFSET = 12;
    static final int OPERATION_SIGNATURE_OFFSET = 16;
    static final int OPERATION_CODE_OFFSET = 80;
    static final int OPERATION_NO_OP = 0;
    static final int OPERATION_RETRY = 1;

    static final int FEEDBACK_BODY_LENGTH = 42;
    static final int FEEDBACK_FRAME_LENGTH = 44;
    static final int FEEDBACK_FLAGS_OFFSET = 2;
    static final int FEEDBACK_RESERVED_OFFSET = 3;
    static final int FEEDBACK_PUBLIC_KEY_OFFSET = 12;
    static final int FEEDBACK_ACCEPTED = 0x80;
    static final int FEEDBACK_REJECTED_BY_POLICY = 0x40;
    static final int KNOWN_FEEDBACK_FLAGS =
            FEEDBACK_ACCEPTED
                    | FEEDBACK_REJECTED_BY_POLICY;

    private static final int MAX_NORMAL_BODY_LENGTH =
            NORMAL_FIXED_BODY_LENGTH
                    + MAX_SERVICE_LENGTH;
    private static final int MAX_FRAME_LENGTH =
            LENGTH_PREFIX_LENGTH
                    + MAX_NORMAL_BODY_LENGTH;
    private static final int MAX_STREAM_BUFFER_LENGTH =
            64 * 1024;

    private NwServiceConnectorCodec() {
    }

    static byte[] encodeNormalStartRequest(
            int localPort,
            long sequence,
            byte[] requestUuid,
            String serviceName,
            byte[] privateEd25519) {
        requirePrivateKey(
                privateEd25519);
        byte[] service =
                encodeServiceName(
                        serviceName);
        byte[] frame = null;
        byte[] signature = null;
        try {
            frame =
                    buildNormalSigningTranscript(
                            localPort,
                            sequence,
                            requestUuid,
                            service);
            signature =
                    AppleIkeEd25519Auth.sign(
                            privateEd25519,
                            frame);
            System.arraycopy(
                    signature,
                    0,
                    frame,
                    NORMAL_SERVICE_OFFSET
                            + service.length,
                    SIGNATURE_LENGTH);
            byte[] output = frame;
            frame = null;
            return output;
        } finally {
            wipe(service);
            wipe(signature);
            wipe(frame);
        }
    }

    static byte[] encodeNoOpRequest(
            int localPort,
            long sequence,
            byte[] privateEd25519) {
        requirePrivateKey(
                privateEd25519);
        byte[] frame =
                buildOperationSigningTranscript(
                        localPort,
                        sequence,
                        OPERATION_NO_OP);
        byte[] signature = null;
        try {
            signature =
                    AppleIkeEd25519Auth.sign(
                            privateEd25519,
                            frame);
            System.arraycopy(
                    signature,
                    0,
                    frame,
                    OPERATION_SIGNATURE_OFFSET,
                    SIGNATURE_LENGTH);
            return frame;
        } catch (RuntimeException failure) {
            wipe(frame);
            throw failure;
        } finally {
            wipe(signature);
        }
    }

    static byte[] encodeFeedback(
            long replySequence,
            FeedbackDisposition disposition,
            byte[] publicEd25519) {
        if (disposition == null) {
            throw new IllegalArgumentException(
                    "Service-connector feedback disposition is absent");
        }
        requireLength(
                "Service-connector Ed25519 public key",
                publicEd25519,
                PUBLIC_KEY_LENGTH);
        byte[] frame =
                new byte[FEEDBACK_FRAME_LENGTH];
        writeU16(
                frame,
                0,
                FEEDBACK_BODY_LENGTH);
        frame[FEEDBACK_FLAGS_OFFSET] =
                (byte) disposition.flags;
        writeU64(
                frame,
                SEQUENCE_OFFSET,
                replySequence);
        System.arraycopy(
                publicEd25519,
                0,
                frame,
                FEEDBACK_PUBLIC_KEY_OFFSET,
                PUBLIC_KEY_LENGTH);
        return frame;
    }

    static Message decode(
            byte[] frame) {
        if (frame == null
                || frame.length < LENGTH_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "Service-connector frame is truncated");
        }
        int bodyLength =
                readU16(
                        frame,
                        0);
        if (bodyLength
                != frame.length
                - LENGTH_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "Service-connector body length mismatch");
        }
        if (bodyLength
                == FEEDBACK_BODY_LENGTH) {
            return decodeFeedback(
                    frame);
        }
        if (bodyLength
                == OPERATION_BODY_LENGTH) {
            return decodeOperation(
                    frame);
        }
        if (bodyLength
                >= NORMAL_FIXED_BODY_LENGTH
                && bodyLength
                <= MAX_NORMAL_BODY_LENGTH) {
            return decodeNormal(
                    frame);
        }
        throw new IllegalArgumentException(
                "Unsupported service-connector body length");
    }

    static byte[] signingTranscript(
            SignedRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Signed service-connector request is absent");
        }
        if (request instanceof NormalStartRequest normal) {
            byte[] service =
                    encodeServiceName(
                            normal.serviceName);
            try {
                return buildNormalSigningTranscript(
                        normal.localPort,
                        normal.sequence,
                        normal.requestUuid,
                        service);
            } finally {
                wipe(service);
            }
        }
        if (request instanceof OperationRequest operation) {
            return buildOperationSigningTranscript(
                    operation.localPort,
                    operation.sequence,
                    operation.operation);
        }
        throw new IllegalArgumentException(
                "Unknown signed service-connector request");
    }

    static boolean verifySignature(
            SignedRequest request,
            byte[] publicEd25519) {
        requireLength(
                "Service-connector Ed25519 public key",
                publicEd25519,
                PUBLIC_KEY_LENGTH);
        byte[] transcript =
                signingTranscript(
                        request);
        try {
            return AppleIkeEd25519Auth.verify(
                    publicEd25519,
                    transcript,
                    request.signature);
        } finally {
            wipe(transcript);
        }
    }

    private static NormalStartRequest decodeNormal(
            byte[] frame) {
        int serviceLength =
                frame[NORMAL_SERVICE_LENGTH_OFFSET]
                        & 0xff;
        if (serviceLength == 0
                || frame.length
                != NORMAL_FIXED_FRAME_LENGTH
                + serviceLength) {
            throw new IllegalArgumentException(
                    "Service-connector normal request length is invalid");
        }
        byte[] requestUuid =
                Arrays.copyOfRange(
                        frame,
                        NORMAL_UUID_OFFSET,
                        NORMAL_UUID_OFFSET
                                + UUID_LENGTH);
        byte[] service =
                Arrays.copyOfRange(
                        frame,
                        NORMAL_SERVICE_OFFSET,
                        NORMAL_SERVICE_OFFSET
                                + serviceLength);
        byte[] signature =
                Arrays.copyOfRange(
                        frame,
                        NORMAL_SERVICE_OFFSET
                                + serviceLength,
                        frame.length);
        try {
            return new NormalStartRequest(
                    readU16(
                            frame,
                            LOCAL_PORT_OFFSET),
                    readU64(
                            frame,
                            SEQUENCE_OFFSET),
                    requestUuid,
                    decodeServiceName(
                            service),
                    signature);
        } finally {
            wipe(requestUuid);
            wipe(service);
            wipe(signature);
        }
    }

    private static OperationRequest decodeOperation(
            byte[] frame) {
        for (int offset =
                     OPERATION_DISCRIMINATOR_OFFSET;
                offset < OPERATION_SIGNATURE_OFFSET;
                offset++) {
            if (frame[offset] != 0) {
                throw new IllegalArgumentException(
                        "Service-connector operation discriminator is nonzero");
            }
        }
        int operation =
                frame[OPERATION_CODE_OFFSET]
                        & 0xff;
        requireKnownOperation(
                operation);
        byte[] signature =
                Arrays.copyOfRange(
                        frame,
                        OPERATION_SIGNATURE_OFFSET,
                        OPERATION_SIGNATURE_OFFSET
                                + SIGNATURE_LENGTH);
        try {
            return new OperationRequest(
                    readU16(
                            frame,
                            LOCAL_PORT_OFFSET),
                    readU64(
                            frame,
                            SEQUENCE_OFFSET),
                    operation,
                    signature);
        } finally {
            wipe(signature);
        }
    }

    private static Feedback decodeFeedback(
            byte[] frame) {
        if (frame[FEEDBACK_RESERVED_OFFSET] != 0) {
            throw new IllegalArgumentException(
                    "Service-connector feedback flags/reserved are invalid");
        }
        int flags =
                frame[FEEDBACK_FLAGS_OFFSET]
                        & 0xff;
        if (flags == KNOWN_FEEDBACK_FLAGS) {
            throw new IllegalArgumentException(
                    "Service-connector feedback flags are invalid");
        }
        FeedbackDisposition disposition =
                FeedbackDisposition.fromFlags(
                        flags);
        byte[] publicKey =
                Arrays.copyOfRange(
                        frame,
                        FEEDBACK_PUBLIC_KEY_OFFSET,
                        FEEDBACK_PUBLIC_KEY_OFFSET
                                + PUBLIC_KEY_LENGTH);
        try {
            return new Feedback(
                    readU64(
                            frame,
                            SEQUENCE_OFFSET),
                    disposition,
                    publicKey);
        } finally {
            wipe(publicKey);
        }
    }

    private static byte[] buildNormalSigningTranscript(
            int localPort,
            long sequence,
            byte[] requestUuid,
            byte[] service) {
        requireU16(
                "Service-connector local port",
                localPort);
        requireRequestUuid(
                requestUuid);
        if (service == null
                || service.length == 0
                || service.length > MAX_SERVICE_LENGTH) {
            throw new IllegalArgumentException(
                    "Service-connector service length is invalid");
        }
        byte[] frame =
                new byte[
                        NORMAL_FIXED_FRAME_LENGTH
                                + service.length];
        writeU16(
                frame,
                0,
                NORMAL_FIXED_BODY_LENGTH
                        + service.length);
        writeU16(
                frame,
                LOCAL_PORT_OFFSET,
                localPort);
        writeU64(
                frame,
                SEQUENCE_OFFSET,
                sequence);
        System.arraycopy(
                requestUuid,
                0,
                frame,
                NORMAL_UUID_OFFSET,
                UUID_LENGTH);
        frame[NORMAL_SERVICE_LENGTH_OFFSET] =
                (byte) service.length;
        System.arraycopy(
                service,
                0,
                frame,
                NORMAL_SERVICE_OFFSET,
                service.length);
        // The final 64 bytes intentionally remain zero for signing.
        return frame;
    }

    private static byte[] buildOperationSigningTranscript(
            int localPort,
            long sequence,
            int operation) {
        requireU16(
                "Service-connector local port",
                localPort);
        requireKnownOperation(
                operation);
        byte[] frame =
                new byte[OPERATION_FRAME_LENGTH];
        writeU16(
                frame,
                0,
                OPERATION_BODY_LENGTH);
        writeU16(
                frame,
                LOCAL_PORT_OFFSET,
                localPort);
        writeU64(
                frame,
                SEQUENCE_OFFSET,
                sequence);
        frame[OPERATION_CODE_OFFSET] =
                (byte) operation;
        // Discriminator and signature region intentionally remain zero.
        return frame;
    }

    private static byte[] encodeServiceName(
            String value) {
        if (value == null
                || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Service-connector service name is absent");
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
            byte[] output =
                    new byte[encoded.remaining()];
            encoded.get(
                    output);
            if (output.length == 0
                    || output.length
                    > MAX_SERVICE_LENGTH
                    || containsZero(
                            output)) {
                wipe(output);
                throw new IllegalArgumentException(
                        "Service-connector service name is invalid");
            }
            return output;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(
                    "Service-connector service name is not valid UTF-8",
                    error);
        }
    }

    private static String decodeServiceName(
            byte[] encoded) {
        if (encoded == null
                || encoded.length == 0
                || containsZero(
                        encoded)) {
            throw new IllegalArgumentException(
                    "Service-connector service bytes are invalid");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                            CodingErrorAction.REPORT)
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT)
                    .decode(
                            ByteBuffer.wrap(
                                    encoded))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(
                    "Service-connector service bytes are not UTF-8",
                    error);
        }
    }

    private static boolean containsZero(
            byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static void requireRequestUuid(
            byte[] requestUuid) {
        requireLength(
                "Service-connector request UUID",
                requestUuid,
                UUID_LENGTH);
        boolean zeroDiscriminator = true;
        for (int index = 0;
                index < 4;
                index++) {
            zeroDiscriminator &=
                    requestUuid[index] == 0;
        }
        if (zeroDiscriminator) {
            throw new IllegalArgumentException(
                    "Normal request UUID collides with operation discriminator");
        }
    }

    private static void requireKnownOperation(
            int operation) {
        if (operation != OPERATION_NO_OP
                && operation != OPERATION_RETRY) {
            throw new IllegalArgumentException(
                    "Unsupported service-connector operation");
        }
    }

    private static void requirePrivateKey(
            byte[] privateEd25519) {
        requireLength(
                "Service-connector Ed25519 private key",
                privateEd25519,
                PRIVATE_KEY_LENGTH);
    }

    private static void requireLength(
            String label,
            byte[] value,
            int expected) {
        if (value == null
                || value.length != expected) {
            throw new IllegalArgumentException(
                    label
                            + " must be "
                            + expected
                            + " bytes");
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

    private static int readU16(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
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
                            | (bytes[offset + index]
                            & 0xffL);
        }
        return value;
    }

    private static void writeU16(
            byte[] bytes,
            int offset,
            int value) {
        requireU16(
                "Service-connector uint16",
                value);
        bytes[offset] =
                (byte) (value >>> 8);
        bytes[offset + 1] =
                (byte) value;
    }

    private static void writeU64(
            byte[] bytes,
            int offset,
            long value) {
        for (int index = 7;
                index >= 0;
                index--) {
            bytes[offset + index] =
                    (byte) value;
            value >>>= 8;
        }
    }

    private static boolean isPossibleBodyLength(
            int bodyLength) {
        return bodyLength
                == FEEDBACK_BODY_LENGTH
                || bodyLength
                == OPERATION_BODY_LENGTH
                || (bodyLength
                >= NORMAL_FIXED_BODY_LENGTH
                && bodyLength
                <= MAX_NORMAL_BODY_LENGTH);
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    enum FeedbackDisposition {
        TRANSIENT_REJECTION(0),
        REJECTED_BY_POLICY(FEEDBACK_REJECTED_BY_POLICY),
        ACCEPTED(FEEDBACK_ACCEPTED);

        final int flags;

        FeedbackDisposition(
                int flags) {
            this.flags = flags;
        }

        static FeedbackDisposition fromFlags(
                int flags) {
            if ((flags & FEEDBACK_ACCEPTED) != 0) {
                return ACCEPTED;
            }
            if ((flags & FEEDBACK_REJECTED_BY_POLICY) != 0) {
                return REJECTED_BY_POLICY;
            }
            return TRANSIENT_REJECTION;
        }
    }

    enum KeyUpdate {
        STORED_FIRST,
        STORED_SAME_KEY,
        STORED_REPLACED_KEY,
        IGNORED_OLDER
    }

    enum VerificationDecision {
        VERIFIED,
        NEEDS_NEWER_KEY,
        REJECT_STALE_OR_FORGED
    }

    static abstract class Message {
        final long sequence;
        private boolean destroyed;

        Message(
                long sequence) {
            this.sequence = sequence;
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

    static abstract class SignedRequest
            extends Message {
        final int localPort;
        final byte[] signature;

        SignedRequest(
                int localPort,
                long sequence,
                byte[] signature) {
            super(
                    sequence);
            requireU16(
                    "Service-connector local port",
                    localPort);
            requireLength(
                    "Service-connector signature",
                    signature,
                    SIGNATURE_LENGTH);
            this.localPort = localPort;
            this.signature = signature.clone();
        }

        @Override
        void destroyContents() {
            wipe(signature);
        }
    }

    static final class NormalStartRequest
            extends SignedRequest {
        final byte[] requestUuid;
        final String serviceName;

        NormalStartRequest(
                int localPort,
                long sequence,
                byte[] requestUuid,
                String serviceName,
                byte[] signature) {
            super(
                    localPort,
                    sequence,
                    signature);
            requireRequestUuid(
                    requestUuid);
            byte[] encoded =
                    encodeServiceName(
                            serviceName);
            wipe(encoded);
            this.requestUuid =
                    requestUuid.clone();
            this.serviceName = serviceName;
        }

        @Override
        void destroyContents() {
            super.destroyContents();
            wipe(requestUuid);
        }
    }

    static final class OperationRequest
            extends SignedRequest {
        final int operation;

        OperationRequest(
                int localPort,
                long sequence,
                int operation,
                byte[] signature) {
            super(
                    localPort,
                    sequence,
                    signature);
            requireKnownOperation(
                    operation);
            this.operation = operation;
        }
    }

    static final class Feedback
            extends Message {
        final FeedbackDisposition disposition;
        final byte[] publicEd25519;

        Feedback(
                long replySequence,
                FeedbackDisposition disposition,
                byte[] publicEd25519) {
            super(
                    replySequence);
            if (disposition == null) {
                throw new IllegalArgumentException(
                        "Service-connector feedback disposition is absent");
            }
            requireLength(
                    "Service-connector Ed25519 public key",
                    publicEd25519,
                    PUBLIC_KEY_LENGTH);
            this.disposition = disposition;
            this.publicEd25519 =
                    publicEd25519.clone();
        }

        @Override
        void destroyContents() {
            wipe(publicEd25519);
        }
    }

    static final class StreamDecoder
            implements AutoCloseable {
        private byte[] buffer =
                new byte[0];
        private boolean poisoned;
        private boolean closed;

        List<Message> push(
                byte[] tcpBytes) {
            requireUsable();
            if (tcpBytes == null
                    || (long) buffer.length
                    + tcpBytes.length
                    > MAX_STREAM_BUFFER_LENGTH) {
                fail();
                throw new IllegalArgumentException(
                        "Service-connector TCP buffer is invalid/too large");
            }
            byte[] old =
                    buffer;
            buffer =
                    Arrays.copyOf(
                            old,
                            old.length
                                    + tcpBytes.length);
            System.arraycopy(
                    tcpBytes,
                    0,
                    buffer,
                    old.length,
                    tcpBytes.length);
            wipe(old);

            List<Message> output =
                    new ArrayList<>();
            int consumed = 0;
            try {
                while (buffer.length - consumed
                        >= LENGTH_PREFIX_LENGTH) {
                    int bodyLength =
                            readU16(
                                    buffer,
                                    consumed);
                    if (!isPossibleBodyLength(
                            bodyLength)) {
                        throw new IllegalArgumentException(
                                "Impossible service-connector body length");
                    }
                    int frameLength =
                            LENGTH_PREFIX_LENGTH
                                    + bodyLength;
                    if (frameLength > MAX_FRAME_LENGTH) {
                        throw new IllegalArgumentException(
                                "Service-connector frame exceeds safety limit");
                    }
                    if (buffer.length - consumed
                            < frameLength) {
                        break;
                    }
                    byte[] complete =
                            Arrays.copyOfRange(
                                    buffer,
                                    consumed,
                                    consumed
                                            + frameLength);
                    try {
                        output.add(
                                decode(
                                        complete));
                    } finally {
                        wipe(complete);
                    }
                    consumed += frameLength;
                }
                if (consumed != 0) {
                    old = buffer;
                    buffer =
                            Arrays.copyOfRange(
                                    old,
                                    consumed,
                                    old.length);
                    wipe(old);
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

        int bufferedLength() {
            requireUsable();
            return buffer.length;
        }

        private void requireUsable() {
            if (closed) {
                throw new IllegalStateException(
                        "Service-connector decoder is closed");
            }
            if (poisoned) {
                throw new IllegalStateException(
                        "Service-connector decoder is poisoned");
            }
        }

        private void fail() {
            wipe(buffer);
            buffer = new byte[0];
            poisoned = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(buffer);
            buffer = new byte[0];
        }
    }

    /**
     * Per-endpoint 40-byte remote-key state:
     * uint64 sequence followed by the raw 32-byte Ed25519 public key.
     */
    static final class RemotePublicKeyStore
            implements AutoCloseable {
        private final Map<String, RemotePublicKey> keys =
                new HashMap<>();
        private boolean closed;

        synchronized KeyUpdate observeFeedback(
                String endpointIdentifier,
                Feedback feedback) {
            requireUsable();
            requireEndpointIdentifier(
                    endpointIdentifier);
            if (feedback == null) {
                throw new IllegalArgumentException(
                        "Service-connector feedback is absent");
            }
            RemotePublicKey previous =
                    keys.get(
                            endpointIdentifier);
            if (previous != null
                    && Long.compareUnsigned(
                    feedback.sequence,
                    previous.sequence) < 0) {
                return KeyUpdate.IGNORED_OLDER;
            }
            boolean same =
                    previous != null
                            && Arrays.equals(
                            previous.publicEd25519,
                            feedback.publicEd25519);
            if (previous != null) {
                previous.destroy();
            }
            keys.put(
                    endpointIdentifier,
                    new RemotePublicKey(
                            feedback.sequence,
                            feedback.publicEd25519));
            if (previous == null) {
                return KeyUpdate.STORED_FIRST;
            }
            return same
                    ? KeyUpdate.STORED_SAME_KEY
                    : KeyUpdate.STORED_REPLACED_KEY;
        }

        synchronized VerificationDecision evaluate(
                String endpointIdentifier,
                SignedRequest request) {
            requireUsable();
            requireEndpointIdentifier(
                    endpointIdentifier);
            if (request == null) {
                throw new IllegalArgumentException(
                        "Signed service-connector request is absent");
            }
            RemotePublicKey remote =
                    keys.get(
                            endpointIdentifier);
            if (remote == null) {
                return VerificationDecision.NEEDS_NEWER_KEY;
            }
            if (verifySignature(
                    request,
                    remote.publicEd25519)) {
                return VerificationDecision.VERIFIED;
            }
            if (Long.compareUnsigned(
                    request.sequence,
                    remote.sequence) > 0) {
                return VerificationDecision.NEEDS_NEWER_KEY;
            }
            return VerificationDecision.REJECT_STALE_OR_FORGED;
        }

        synchronized void seedKey(
                String endpointIdentifier,
                byte[] publicEd25519) {
            requireUsable();
            requireEndpointIdentifier(
                    endpointIdentifier);
            if (publicEd25519 == null
                    || publicEd25519.length != PUBLIC_KEY_LENGTH) {
                return;
            }
            RemotePublicKey previous =
                    keys.put(
                            endpointIdentifier,
                            new RemotePublicKey(
                                    0,
                                    publicEd25519));
            if (previous != null) {
                previous.destroy();
            }
        }

        synchronized int size() {
            requireUsable();
            return keys.size();
        }

        private void requireUsable() {
            if (closed) {
                throw new IllegalStateException(
                        "Remote service-connector key store is closed");
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (RemotePublicKey key : keys.values()) {
                key.destroy();
            }
            keys.clear();
        }
    }

    private static final class RemotePublicKey {
        final long sequence;
        final byte[] publicEd25519;

        RemotePublicKey(
                long sequence,
                byte[] publicEd25519) {
            this.sequence = sequence;
            this.publicEd25519 =
                    publicEd25519.clone();
        }

        void destroy() {
            wipe(publicEd25519);
        }
    }

    private static void requireEndpointIdentifier(
            String endpointIdentifier) {
        if (endpointIdentifier == null
                || endpointIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                    "Service-connector endpoint identifier is absent");
        }
    }
}
