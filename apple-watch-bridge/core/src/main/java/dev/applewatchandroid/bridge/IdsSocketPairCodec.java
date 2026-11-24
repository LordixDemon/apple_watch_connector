package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Current IDSFoundation socket-pair framing above one reassembled TCP byte
 * stream.
 */
final class IdsSocketPairCodec {
    static final int COMMAND_DATA = 0x00;
    static final int COMMAND_ACK = 0x01;
    static final int COMMAND_KEEP_ALIVE = 0x02;
    static final int COMMAND_PROTOBUF = 0x03;
    static final int COMMAND_HANDSHAKE = 0x04;
    static final int COMMAND_DICTIONARY = 0x06;
    static final int COMMAND_APP_ACK = 0x07;
    static final int COMMAND_FRAGMENT = 0x15;
    /** IDSFoundation IDSSocketPairResourceTransferMessage. macOS command IMP is mov w0, #0x16. */
    static final int COMMAND_RESOURCE_TRANSFER = 0x16;
    static final int COMMAND_EXPIRED_ACK = 0x25;
    static final int COMMAND_SERVICE_MAP = 0x27;

    static final int FLAG_EXPECTS_PEER_RESPONSE = 0x01;
    static final int FLAG_COMPRESSED = 0x02;
    static final int FLAG_WANTS_APP_ACK = 0x04;
    static final int FLAG_HAS_EXPIRY = 0x08;
    static final int FLAG_HAS_TOPIC = 0x10;
    static final int FLAG_DID_WAKE_HINT = 0x20;
    static final int KNOWN_DATA_FLAGS = 0x3f;

    static final int HEADER_LENGTH = 5;
    static final int FRAGMENT_FRAME_LIMIT = 8000;
    static final int FRAGMENT_METADATA_LENGTH = 12;
    static final int FRAGMENT_SLICE_CAPACITY =
            FRAGMENT_FRAME_LIMIT
                    - HEADER_LENGTH
                    - FRAGMENT_METADATA_LENGTH;

    static final int MAX_FRAME_LENGTH = 1024 * 1024;
    static final int MAX_STRING_LENGTH = 0xffff;
    private static final int MAX_IN_FLIGHT_FRAGMENTED_MESSAGES = 16;

    private IdsSocketPairCodec() {
    }

    static byte[] encodeHandshake(
            long version) {
        return IdsSocketPairWireCodec.encodeHandshake(
                version);
    }

    static byte[] encodeAck(
            long sequence,
            boolean expired) {
        return IdsSocketPairWireCodec.encodeAck(
                sequence,
                expired);
    }

    static byte[] encodeKeepAlive() {
        return IdsSocketPairWireCodec.encodeKeepAlive();
    }

    static byte[] encodeData(
            DataMessage message) {
        return IdsSocketPairWireCodec.encodeData(
                message);
    }

    static byte[] encodeProtobuf(
            ProtobufMessage message) {
        return IdsSocketPairWireCodec.encodeProtobuf(
                message);
    }

    static byte[] encodeAppAck(
            AppAckMessage message) {
        return IdsSocketPairWireCodec.encodeAppAck(
                message);
    }

    static byte[] encodeServiceMap(
            ServiceMapMessage message) {
        return IdsSocketPairWireCodec.encodeServiceMap(
                message);
    }

    static List<byte[]> fragment(
            long fragmentedMessageId,
            byte[] originalFrame) {
        return IdsSocketPairWireCodec.fragment(
                fragmentedMessageId,
                originalFrame);
    }

    static Message decode(
            byte[] frame) {
        return IdsSocketPairWireCodec.decode(
                frame);
    }

    static byte[] decompressPayload(
            byte[] payload,
            int flags) {
        return IdsSocketPairWireCodec.decompressPayload(
                payload,
                flags);
    }

    static abstract class Message {
        final int command;
        private boolean destroyed;

        Message(
                int command) {
            this.command = command;
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            destroyContents();
        }

        void destroyContents() {
        }
    }

    static final class UnknownMessage extends Message {
        final String bodyHexPrefix;

        UnknownMessage(int command) { this(command, ""); }

        UnknownMessage(int command, String bodyHexPrefix) {
            super(command);
            this.bodyHexPrefix =
                    bodyHexPrefix == null ? "" : bodyHexPrefix;
        }
    }

    static final class DataMessage extends Message {
        final long sequence;
        final int streamId;
        final int flags;
        final String peerResponseIdentifier;
        final String messageUuid;
        final String topic;
        final byte[] payload;
        final Long expirySeconds;

        DataMessage(
                long sequence,
                int streamId,
                int flags,
                String peerResponseIdentifier,
                String messageUuid,
                String topic,
                byte[] payload,
                Long expirySeconds) {
            this(COMMAND_DATA, sequence, streamId, flags, peerResponseIdentifier,
                    messageUuid, topic, payload, expirySeconds);
        }

        // IDSFoundation 23G71: DictionaryMessage inherits the complete DataMessage
        // envelope and overrides only command (0x1a95a71a8 returns 6).
        DataMessage(int command, long sequence, int streamId, int flags,
                    String peerResponseIdentifier, String messageUuid, String topic,
                    byte[] payload, Long expirySeconds) {
            super(command);
            if (command != COMMAND_DATA
                    && command != COMMAND_DICTIONARY
                    && command != COMMAND_RESOURCE_TRANSFER) {
                throw new IllegalArgumentException("Invalid IDS Data/Dictionary command");
            }
            this.sequence = sequence;
            this.streamId = streamId;
            this.flags = flags;
            this.peerResponseIdentifier =
                    IdsSocketPairWireCodec.emptyToNull(
                            peerResponseIdentifier);
            this.messageUuid = messageUuid;
            this.topic =
                    IdsSocketPairWireCodec.emptyToNull(
                            topic);
            this.payload =
                    payload == null
                            ? null
                            : payload.clone();
            this.expirySeconds = expirySeconds;
            requireValid();
        }

        void requireValid() {
            IdsSocketPairWireCodec.validateCommonMessage(
                    sequence,
                    streamId,
                    flags,
                    messageUuid,
                    topic,
                    payload,
                    expirySeconds);
        }

        @Override
        void destroyContents() {
            IdsSocketPairWireCodec.wipe(payload);
        }
    }

    static final class ProtobufMessage extends Message {
        final long sequence;
        final int streamId;
        final int flags;
        final String peerResponseIdentifier;
        final String messageUuid;
        final String topic;
        final int protobufType;
        final boolean response;
        final byte[] payload;
        final Long expirySeconds;

        ProtobufMessage(
                long sequence,
                int streamId,
                int flags,
                String peerResponseIdentifier,
                String messageUuid,
                String topic,
                int protobufType,
                boolean response,
                byte[] payload,
                Long expirySeconds) {
            super(
                    COMMAND_PROTOBUF);
            this.sequence = sequence;
            this.streamId = streamId;
            this.flags = flags;
            this.peerResponseIdentifier =
                    IdsSocketPairWireCodec.emptyToNull(
                            peerResponseIdentifier);
            this.messageUuid = messageUuid;
            this.topic =
                    IdsSocketPairWireCodec.emptyToNull(
                            topic);
            this.protobufType = protobufType;
            this.response = response;
            this.payload =
                    payload == null
                            ? null
                            : payload.clone();
            this.expirySeconds = expirySeconds;
            requireValid();
        }

        void requireValid() {
            IdsSocketPairWireCodec.validateCommonMessage(
                    sequence,
                    streamId,
                    flags,
                    messageUuid,
                    topic,
                    payload,
                    expirySeconds);
            IdsSocketPairWireCodec.requireU16(
                    "IDS protobuf type",
                    protobufType);
        }

        @Override
        void destroyContents() {
            IdsSocketPairWireCodec.wipe(payload);
        }
    }

    static final class AckMessage extends Message {
        final long sequence;

        AckMessage(
                int command,
                long sequence) {
            super(
                    command);
            this.sequence = sequence;
            IdsSocketPairWireCodec.requireU32(
                    "IDS Ack sequence",
                    sequence);
        }
    }

    static final class KeepAliveMessage extends Message {
        KeepAliveMessage() {
            super(
                    COMMAND_KEEP_ALIVE);
        }
    }

    static final class HandshakeMessage extends Message {
        final long version;

        HandshakeMessage(
                long version) {
            super(
                    COMMAND_HANDSHAKE);
            this.version = version;
        }
    }

    static final class AppAckMessage extends Message {
        final long sequence;
        final int streamId;
        final String peerResponseIdentifier;
        final String topic;

        AppAckMessage(
                long sequence,
                int streamId,
                String peerResponseIdentifier,
                String topic) {
            super(
                    COMMAND_APP_ACK);
            this.sequence = sequence;
            this.streamId = streamId;
            this.peerResponseIdentifier =
                    IdsSocketPairWireCodec.emptyToNull(
                            peerResponseIdentifier);
            this.topic =
                    IdsSocketPairWireCodec.emptyToNull(
                            topic);
            requireValid();
        }

        void requireValid() {
            IdsSocketPairWireCodec.requireU32(
                    "IDS AppAck sequence",
                    sequence);
            IdsSocketPairWireCodec.requireU16(
                    "IDS AppAck stream ID",
                    streamId);
            if (peerResponseIdentifier != null) {
                byte[] encoded =
                        IdsSocketPairWireCodec.encodeString(
                                peerResponseIdentifier);
                IdsSocketPairWireCodec.wipe(encoded);
            }
            if (topic != null) {
                byte[] encoded =
                        IdsSocketPairWireCodec.encodeString(
                                topic);
                IdsSocketPairWireCodec.wipe(encoded);
            }
        }
    }

    static final class FragmentMessage extends Message {
        final long fragmentedMessageId;
        final int fragmentIndex;
        final int totalFragmentCount;
        final byte[] slice;

        FragmentMessage(
                long fragmentedMessageId,
                int fragmentIndex,
                int totalFragmentCount,
                byte[] slice) {
            super(
                    COMMAND_FRAGMENT);
            this.fragmentedMessageId =
                    fragmentedMessageId;
            this.fragmentIndex =
                    fragmentIndex;
            this.totalFragmentCount =
                    totalFragmentCount;
            this.slice = slice.clone();
        }

        @Override
        void destroyContents() {
            IdsSocketPairWireCodec.wipe(slice);
        }
    }

    static final class ServiceMapMessage extends Message {
        final int reason;
        final int streamId;
        final String serviceName;

        ServiceMapMessage(
                int reason,
                int streamId,
                String serviceName) {
            super(
                    COMMAND_SERVICE_MAP);
            this.reason = reason;
            this.streamId = streamId;
            this.serviceName = serviceName;
            requireValid();
        }

        void requireValid() {
            if (reason < 0
                    || reason > 0xff
                    || streamId <= 0
                    || streamId > 0xffff) {
                throw new IllegalArgumentException(
                        "IDS ServiceMap reason/stream ID is invalid");
            }
            byte[] encoded =
                    IdsSocketPairWireCodec.encodeString(
                            serviceName);
            IdsSocketPairWireCodec.wipe(encoded);
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
                    > MAX_FRAME_LENGTH * 2L) {
                fail();
                throw new IllegalArgumentException(
                        "IDS socket-pair TCP buffer is invalid/too large");
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
            IdsSocketPairWireCodec.wipe(old);

            List<Message> output =
                    new ArrayList<>();
            int consumed = 0;
            try {
                while (buffer.length - consumed
                        >= HEADER_LENGTH) {
                    long bodyLength =
                            IdsSocketPairWireCodec.readU32(
                                    buffer,
                                    consumed + 1);
                    if (bodyLength
                            > MAX_FRAME_LENGTH
                            - HEADER_LENGTH) {
                        throw new IllegalArgumentException(
                                "IDS socket-pair declared frame is too large: bodyLength="
                                        + bodyLength
                                        + " limit="
                                        + (MAX_FRAME_LENGTH - HEADER_LENGTH)
                                        + " cmd=0x"
                                        + Integer.toHexString(buffer[consumed] & 0xff)
                                        + " consumed="
                                        + consumed
                                        + " bufferLen="
                                        + buffer.length);
                    }
                    int frameLength =
                            HEADER_LENGTH
                                    + (int) bodyLength;
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
                        IdsSocketPairWireCodec.wipe(complete);
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
                    IdsSocketPairWireCodec.wipe(old);
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
                        "IDS socket-pair decoder is closed");
            }
            if (poisoned) {
                throw new IllegalStateException(
                        "IDS socket-pair decoder is poisoned");
            }
        }

        void reset() {
            IdsSocketPairWireCodec.wipe(buffer);
            buffer = new byte[0];
            poisoned = false;
        }

        private void fail() {
            IdsSocketPairWireCodec.wipe(buffer);
            buffer = new byte[0];
            poisoned = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            IdsSocketPairWireCodec.wipe(buffer);
            buffer = new byte[0];
        }
    }

    static final class FragmentReassembler
            implements AutoCloseable {
        private final Map<Long, Assembly> assemblies =
                new HashMap<>();
        private boolean poisoned;
        private boolean closed;

        void reset() {
            for (Assembly assembly : assemblies.values()) {
                assembly.destroy();
            }
            assemblies.clear();
            poisoned = false;
        }

        Message accept(
                FragmentMessage fragment) {
            requireUsable();
            if (fragment == null) {
                fail();
                throw new IllegalArgumentException(
                        "IDS Fragment is null");
            }
            try {
                Assembly assembly =
                        assemblies.get(
                                fragment.fragmentedMessageId);
                if (assembly == null) {
                    if (assemblies.size()
                            >= MAX_IN_FLIGHT_FRAGMENTED_MESSAGES) {
                        throw new IllegalArgumentException(
                                "Too many IDS fragmented messages in flight");
                    }
                    long maximumLength =
                            (long) fragment.totalFragmentCount
                                    * FRAGMENT_SLICE_CAPACITY;
                    if (maximumLength > MAX_FRAME_LENGTH) {
                        throw new IllegalArgumentException(
                                "IDS fragmented message exceeds safety limit");
                    }
                    assembly =
                            new Assembly(
                                    fragment.totalFragmentCount);
                    assemblies.put(
                                fragment.fragmentedMessageId,
                                assembly);
                }
                if (assembly.slices.length
                        != fragment.totalFragmentCount) {
                    throw new IllegalArgumentException(
                            "IDS Fragment count changed for one ID");
                }
                assembly.add(
                        fragment.fragmentIndex,
                        fragment.slice);
                if (!assembly.complete()) {
                    return null;
                }
                byte[] complete =
                        assembly.join();
                assemblies.remove(
                        fragment.fragmentedMessageId);
                assembly.destroy();
                try {
                    Message decoded =
                            decode(
                                    complete);
                    if (decoded.command
                            == COMMAND_FRAGMENT) {
                        decoded.destroy();
                        throw new IllegalArgumentException(
                                "Nested IDS Fragment is forbidden");
                    }
                    return decoded;
                } finally {
                    IdsSocketPairWireCodec.wipe(complete);
                }
            } catch (RuntimeException failure) {
                fail();
                throw failure;
            }
        }

        private void requireUsable() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS Fragment reassembler is closed");
            }
            if (poisoned) {
                throw new IllegalStateException(
                        "IDS Fragment reassembler is poisoned");
            }
        }

        private void fail() {
            for (Assembly assembly : assemblies.values()) {
                assembly.destroy();
            }
            assemblies.clear();
            poisoned = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (Assembly assembly : assemblies.values()) {
                assembly.destroy();
            }
            assemblies.clear();
        }
    }

    private static final class Assembly {
        final byte[][] slices;
        int received;

        Assembly(
                int total) {
            slices =
                    new byte[total][];
        }

        void add(
                int index,
                byte[] slice) {
            byte[] previous =
                    slices[index];
            if (previous != null) {
                if (!Arrays.equals(
                        previous,
                        slice)) {
                    throw new IllegalArgumentException(
                            "IDS duplicate Fragment changed bytes");
                }
                return;
            }
            slices[index] =
                    slice.clone();
            received++;
        }

        boolean complete() {
            return received == slices.length;
        }

        byte[] join() {
            int length = 0;
            for (byte[] slice : slices) {
                length =
                        Math.addExact(
                                length,
                                slice.length);
            }
            if (length > MAX_FRAME_LENGTH) {
                throw new IllegalArgumentException(
                        "IDS reassembled frame exceeds safety limit");
            }
            byte[] output =
                    new byte[length];
            int offset = 0;
            for (byte[] slice : slices) {
                System.arraycopy(
                        slice,
                        0,
                        output,
                        offset,
                        slice.length);
                offset += slice.length;
            }
            return output;
        }

        void destroy() {
            for (byte[] slice : slices) {
                IdsSocketPairWireCodec.wipe(slice);
            }
            received = 0;
        }
    }
}
