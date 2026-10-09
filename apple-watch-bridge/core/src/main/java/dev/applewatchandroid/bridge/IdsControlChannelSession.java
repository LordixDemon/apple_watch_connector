package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * IDS control-channel state immediately after NWSC acceptance.
 *
 * <p>The local Hello is emitted once. The peer's first complete control
 * message must also be Hello; later Setup messages remain caller-owned and
 * are returned for data-channel creation. A remote IDS instance UUID is
 * scoped to this live connection through {@link IdsServiceMapState}.</p>
 */
final class IdsControlChannelSession
        implements AutoCloseable {
    enum Compatibility {
        MATCHED,
        LEGACY_UNSPECIFIED,
        NO_OVERLAP
    }

    private final UUID localInstanceId;
    private final UUID localDeviceId;
    private final boolean directMessagingSupported;
    private final IdsControlChannelCodec.StreamDecoder decoder =
            new IdsControlChannelCodec.StreamDecoder();
    private final IdsServiceMapState serviceMap;

    private RemoteHello remoteHello;
    private boolean localHelloSent;
    private boolean poisoned;
    private boolean closed;

    private IdsControlChannelSession(
            UUID localInstanceId,
            UUID localDeviceId,
            boolean directMessagingSupported,
            int firstLocalStreamId) {
        if (localInstanceId == null
                || localDeviceId == null) {
            throw new IllegalArgumentException(
                    "IDS control local UUIDs are absent");
        }
        this.localInstanceId =
                localInstanceId;
        this.localDeviceId =
                localDeviceId;
        this.directMessagingSupported =
                directMessagingSupported;
        serviceMap =
                new IdsServiceMapState(
                        firstLocalStreamId);
    }

    static IdsControlChannelSession createDefaultPaired(
            SecureRandom random,
            String localIdsDeviceUuid,
            boolean directMessagingSupported,
            int firstLocalStreamId) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "IDS control random source is absent");
        }
        UUID localDevice;
        try {
            localDevice =
                    UUID.fromString(
                            localIdsDeviceUuid);
            if (!localDevice.toString().equals(
                    localIdsDeviceUuid)) {
                throw new IllegalArgumentException(
                        "Local IDS device UUID is not canonical");
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Local IDS device UUID is not canonical",
                    invalid);
        }
        return new IdsControlChannelSession(
                randomUuid(
                        random),
                localDevice,
                directMessagingSupported,
                firstLocalStreamId);
    }

    synchronized byte[] startHello() {
        requireUsable();
        if (localHelloSent) {
            throw new IllegalStateException(
                    "IDS local Hello was already sent");
        }
        localHelloSent = true;
        IdsControlChannelCodec.HelloMessage hello =
                IosCompanionProfile26_6.hello(
                        localInstanceId,
                        localDeviceId,
                        false,
                        directMessagingSupported,
                        true);
        try {
            return IdsControlChannelCodec.encodeFramed(
                    hello);
        } finally {
            hello.destroy();
        }
    }

    /**
     * Decodes zero or more complete peer messages.
     *
     * <p>The returned messages belong to the caller and must be destroyed.
     * The session retains only a non-secret Hello snapshot.</p>
     */
    synchronized List<IdsControlChannelCodec.Message> acceptTcpBytes(
            byte[] bytes) {
        requireUsable();
        List<IdsControlChannelCodec.Message> messages;
        try {
            messages =
                    decoder.push(
                            bytes);
        } catch (RuntimeException failure) {
            poisoned = true;
            throw failure;
        }
        List<IdsControlChannelCodec.Message> output =
                new ArrayList<>(
                        messages.size());
        try {
            for (IdsControlChannelCodec.Message message : messages) {
                if (message instanceof IdsControlChannelCodec.HelloMessage hello) {
                    if (remoteHello != null) {
                        throw new IllegalArgumentException(
                                "Peer IDS control stream repeated Hello");
                    }
                    remoteHello =
                            new RemoteHello(
                                    hello);
                    serviceMap.observeRemoteInstance(
                            hello.instanceId);
                }
                output.add(
                        message);
            }
            return List.copyOf(
                    output);
        } catch (RuntimeException failure) {
            for (IdsControlChannelCodec.Message message : messages) {
                message.destroy();
            }
            output.clear();
            poisoned = true;
            throw failure;
        }
    }

    synchronized boolean remoteHelloReceived() {
        requireUsable();
        return remoteHello != null;
    }

    synchronized RemoteHello remoteHello() {
        requireUsable();
        if (remoteHello == null) {
            throw new IllegalStateException(
                    "Peer IDS Hello has not arrived");
        }
        return remoteHello;
    }

    synchronized Compatibility compatibility() {
        requireUsable();
        if (remoteHello == null) {
            return null;
        }
        if (remoteHello.minCompatibilityVersion == 0
                && remoteHello.maxCompatibilityVersion == 0) {
            return Compatibility.LEGACY_UNSPECIFIED;
        }
        long overlapMin =
                Math.max(
                        IosCompanionProfile26_6.MIN_COMPATIBILITY_VERSION,
                        remoteHello.minCompatibilityVersion);
        long overlapMax =
                Math.min(
                        IosCompanionProfile26_6.MAX_COMPATIBILITY_VERSION,
                        remoteHello.maxCompatibilityVersion);
        if (overlapMin <= overlapMax) {
            return Compatibility.MATCHED;
        }
        return Compatibility.NO_OVERLAP;
    }

    synchronized IdsServiceMapState serviceMap() {
        requireUsable();
        if (!directMessagingSupported && remoteHello == null) {
            throw new IllegalStateException(
                    "IDS service map requires the peer Hello");
        }
        return serviceMap;
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS control session is closed");
        }
        if (poisoned) {
            throw new IllegalStateException(
                    "IDS control session is poisoned");
        }
    }

    synchronized boolean localHelloSent() {
        return localHelloSent;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        decoder.close();
        serviceMap.close();
        remoteHello = null;
    }

    private static UUID randomUuid(
            SecureRandom random) {
        byte[] bytes =
                new byte[16];
        random.nextBytes(
                bytes);
        try {
            bytes[6] =
                    (byte) ((bytes[6] & 0x0f)
                            | 0x40);
            bytes[8] =
                    (byte) ((bytes[8] & 0x3f)
                            | 0x80);
            return new UUID(
                    readU64(
                            bytes,
                            0),
                    readU64(
                            bytes,
                            8));
        } finally {
            Arrays.fill(
                    bytes,
                    (byte) 0);
        }
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

    static final class RemoteHello {
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

        RemoteHello(
                IdsControlChannelCodec.HelloMessage hello) {
            controlChannelVersion =
                    hello.controlChannelVersion;
            productName =
                    hello.productName;
            productVersion =
                    hello.productVersion;
            productBuildVersion =
                    hello.productBuildVersion;
            model =
                    hello.model;
            pairingProtocolVersion =
                    hello.pairingProtocolVersion;
            minCompatibilityVersion =
                    hello.minCompatibilityVersion;
            maxCompatibilityVersion =
                    hello.maxCompatibilityVersion;
            instanceId =
                    hello.instanceId;
            deviceUniqueId =
                    hello.deviceUniqueId;
            capabilityFlags =
                    hello.capabilityFlags;
            serviceMinCompatibilityVersion =
                    hello.serviceMinCompatibilityVersion;
        }
    }
}
