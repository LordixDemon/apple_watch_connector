package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    static final int OUTER_HEADER_LENGTH = 2;
    static final int DIRECT_FLAGS_LENGTH = 8;
    static final int MAX_PAYLOAD_LENGTH = 0xffff;

    private IdsControlChannelCodec() {
    }

    static byte[] encodeFramed(
            Message message) {
        return IdsControlChannelWireCodec.encodeFramed(
                message);
    }

    static byte[] encodePayload(
            Message message) {
        return IdsControlChannelWireCodec.encodePayload(
                message);
    }

    static Message decodeFramed(
            byte[] frame) {
        return IdsControlChannelWireCodec.decodeFramed(
                frame);
    }

    static Message decodePayload(
            byte[] payload) {
        return IdsControlChannelWireCodec.decodePayload(
                payload);
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
            IdsControlChannelWireCodec.validateRequiredString(
                    controlChannelVersion);
            IdsControlChannelWireCodec.validateRequiredString(
                    productName);
            IdsControlChannelWireCodec.validateRequiredString(
                    productVersion);
            IdsControlChannelWireCodec.validateRequiredString(
                    productBuildVersion);
            IdsControlChannelWireCodec.validateRequiredString(
                    model);
            IdsControlChannelWireCodec.requireU32(
                    "IDS pairing protocol version",
                    pairingProtocolVersion);
            IdsControlChannelWireCodec.requireU32(
                    "IDS minimum compatibility version",
                    minCompatibilityVersion);
            IdsControlChannelWireCodec.requireU32(
                    "IDS maximum compatibility version",
                    maxCompatibilityVersion);
            if (instanceId == null
                    || deviceUniqueId == null) {
                throw new IllegalArgumentException(
                        "IDS Hello UUID fields are absent");
            }
            IdsControlChannelWireCodec.requireU16(
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
                    IdsControlChannelWireCodec.emptyToNull(
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
            IdsControlChannelWireCodec.requireU16(
                    "IDS local port",
                    localPort);
            IdsControlChannelWireCodec.requireU16(
                    "IDS remote port",
                    remotePort);
            if (type == TYPE_SETUP_CHANNEL
                    || type == TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE) {
                IdsControlChannelWireCodec.requireProtocol(
                        protocol);
                IdsControlChannelWireCodec.requireCanonicalUuidString(
                        "IDS remote connection GUID",
                        remoteConnectionGuid,
                        false);
                IdsControlChannelWireCodec.validateRequiredString(
                        account);
                IdsControlChannelWireCodec.validateRequiredString(
                        service);
                IdsControlChannelWireCodec.validateRequiredString(
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
            IdsControlChannelWireCodec.wipe(directFlags);
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
                    IdsControlChannelWireCodec.emptyToNull(
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
            IdsControlChannelWireCodec.requireU16(
                    "IDS local port",
                    localPort);
            IdsControlChannelWireCodec.requireU16(
                    "IDS remote port",
                    remotePort);
            IdsControlChannelWireCodec.requireProtocol(
                    protocol);
            IdsControlChannelWireCodec.requireCanonicalUuidString(
                    "IDS remote connection GUID",
                    remoteConnectionGuid,
                    false);
            IdsControlChannelWireCodec.validateRequiredString(
                    account);
            IdsControlChannelWireCodec.validateRequiredString(
                    service);
            IdsControlChannelWireCodec.validateRequiredString(
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
            IdsControlChannelWireCodec.wipe(keyMaterial);
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
            IdsControlChannelWireCodec.wipe(info);
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
            IdsControlChannelWireCodec.wipe(rawPayload);
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
            IdsControlChannelWireCodec.wipe(previous);

            List<Message> output =
                    new ArrayList<>();
            int consumed = 0;
            try {
                while (buffer.length - consumed
                        >= OUTER_HEADER_LENGTH) {
                    int payloadLength =
                            IdsControlChannelWireCodec.readU16(
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
                        IdsControlChannelWireCodec.wipe(payload);
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
                    IdsControlChannelWireCodec.wipe(previous);
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
            IdsControlChannelWireCodec.wipe(buffer);
            buffer = new byte[0];
            poisoned = true;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            IdsControlChannelWireCodec.wipe(buffer);
            buffer = new byte[0];
        }
    }

}
