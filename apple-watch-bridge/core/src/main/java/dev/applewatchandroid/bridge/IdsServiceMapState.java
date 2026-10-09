package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-connection IDS dynamic service-name/stream-ID state.
 *
 * <p>Stream IDs are runtime mappings. No service, including PairedSync, has
 * a stable wire ID.
 */
final class IdsServiceMapState implements AutoCloseable {
    static final int ADVERTISEMENT_REASON = 1;
    static final int APPLE_FIRST_STREAM_ID = 1;
    private static final int MAX_STREAM_ID = 0xffff;
    private static final int ADVERTISEMENT_TOPIC_COUNT = 3;
    private static final int MAX_SERVICE_NAME_LENGTH = 0xffff;

    /*
     * identityservicesd owns two independent IDSUTunPeerServiceMap objects.
     * The incoming map is allocated locally and resolves topic-less incoming
     * frames. The outgoing map is learned from inline topics or ServiceMap
     * command 0x27 and is used to omit topics on outgoing frames. Numeric IDs
     * may therefore collide across the two maps without ambiguity.
     */
    private final Map<String, Integer> incomingByService =
            new HashMap<>();
    private final Map<Integer, String> incomingById =
            new HashMap<>();
    private final Map<String, Integer> outgoingByService =
            new HashMap<>();
    private final Map<Integer, String> outgoingById =
            new HashMap<>();
    private final Map<String, Integer> inlineTopicCount =
            new HashMap<>();
    private final Map<String, Boolean> advertised =
            new HashMap<>();

    private int nextIncomingStreamId;
    private UUID remoteInstanceId;
    private boolean poisoned;
    private boolean closed;

    IdsServiceMapState(
            int firstIncomingStreamId) {
        if (firstIncomingStreamId <= 0
                || firstIncomingStreamId > MAX_STREAM_ID) {
            throw new IllegalArgumentException(
                    "Initial IDS stream ID must be nonzero uint16");
        }
        nextIncomingStreamId = firstIncomingStreamId;
    }

    synchronized void observeRemoteInstance(
            UUID instanceId) {
        requireUsable();
        if (instanceId == null) {
            fail();
            throw new IllegalArgumentException(
                    "IDS remote instance UUID is absent");
        }
        if (remoteInstanceId == null) {
            remoteInstanceId = instanceId;
            return;
        }
        if (!remoteInstanceId.equals(
                instanceId)) {
            fail();
            throw new IllegalArgumentException(
                    "IDS remote instance changed; reconnect is required");
        }
    }

    synchronized OutgoingRoute routeOutgoing(
            String serviceName) {
        requireUsable();
        requireServiceName(
                serviceName);
        Integer outgoingStreamId =
                outgoingByService.get(
                        serviceName);
        if (outgoingStreamId != null) {
            return new OutgoingRoute(
                    serviceName,
                    outgoingStreamId,
                    false);
        }
        return new OutgoingRoute(
                serviceName,
                ensureIncomingMapping(
                        serviceName),
                true);
    }

    synchronized IncomingRoute acceptIncoming(
            IdsSocketPairCodec.Message message) {
        return acceptIncoming(message, null);
    }

    synchronized IncomingRoute acceptIncoming(
            IdsSocketPairCodec.Message message,
            String fallbackService) {
        requireUsable();
        if (message == null) {
            fail();
            throw new IllegalArgumentException(
                    "IDS incoming mapped message is null");
        }

        int streamId;
        String inlineTopic;
        if (message instanceof IdsSocketPairCodec.DataMessage data) {
            streamId = data.streamId;
            inlineTopic = data.topic;
        } else if (message
                instanceof IdsSocketPairCodec.ProtobufMessage protobuf) {
            streamId = protobuf.streamId;
            inlineTopic = protobuf.topic;
        } else if (message
                instanceof IdsSocketPairCodec.AppAckMessage appAck) {
            streamId = appAck.streamId;
            inlineTopic = appAck.topic;
        } else {
            fail();
            throw new IllegalArgumentException(
                    "IDS command does not carry a mapped service");
        }

        try {
            if (streamId <= 0
                    || streamId > MAX_STREAM_ID) {
                throw new IllegalArgumentException(
                        "IDS mapped message uses stream ID zero/out of range");
            }
            if (inlineTopic == null) {
                String service =
                        incomingById.get(
                                streamId);
                if (service == null && fallbackService != null && !fallbackService.isBlank()) {
                    requireServiceName(fallbackService);
                    incomingByService.put(fallbackService, streamId);
                    incomingById.put(streamId, fallbackService);
                    service = fallbackService;
                }
                if (service == null) {
                    throw new IllegalArgumentException(
                            "IDS message omitted an unknown dynamic service");
                }
                return new IncomingRoute(
                        service,
                        null);
            }

            requireServiceName(
                    inlineTopic);
            associateOutgoingMapping(
                    inlineTopic,
                    streamId);
            int incomingStreamId =
                    ensureIncomingMapping(
                            inlineTopic);
            int count =
                    inlineTopicCount.getOrDefault(
                            inlineTopic,
                            0)
                            + 1;
            inlineTopicCount.put(
                    inlineTopic,
                    count);
            IdsSocketPairCodec.ServiceMapMessage advertisement =
                    null;
            if (count >= ADVERTISEMENT_TOPIC_COUNT
                    && !advertised.containsKey(
                            inlineTopic)) {
                advertised.put(
                        inlineTopic,
                        Boolean.TRUE);
                advertisement =
                        new IdsSocketPairCodec.ServiceMapMessage(
                                ADVERTISEMENT_REASON,
                                incomingStreamId,
                                inlineTopic);
            }
            return new IncomingRoute(
                    inlineTopic,
                    advertisement);
        } catch (RuntimeException failure) {
            fail();
            throw failure;
        }
    }

    synchronized void acceptServiceMap(
            IdsSocketPairCodec.ServiceMapMessage message) {
        requireUsable();
        if (message == null) {
            fail();
            throw new IllegalArgumentException(
                    "IDS ServiceMap is null");
        }
        try {
            if (message.reason
                    != ADVERTISEMENT_REASON) {
                throw new IllegalArgumentException(
                        "Unsupported IDS ServiceMap reason");
            }
            requireServiceName(
                    message.serviceName);
            associateOutgoingMapping(
                    message.serviceName,
                    message.streamId);
        } catch (RuntimeException failure) {
            fail();
            throw failure;
        }
    }

    synchronized int localMappingCount() {
        requireUsable();
        return incomingByService.size();
    }

    synchronized int peerMappingCount() {
        requireUsable();
        return outgoingByService.size();
    }

    private int ensureIncomingMapping(
            String serviceName) {
        Integer existing =
                incomingByService.get(
                        serviceName);
        if (existing != null) {
            return existing;
        }
        for (int attempts = 0;
                attempts < MAX_STREAM_ID;
                attempts++) {
            int candidate =
                    nextIncomingStreamId;
            nextIncomingStreamId =
                    candidate == MAX_STREAM_ID
                            ? 1
                            : candidate + 1;
            if (incomingById.containsKey(
                    candidate)) {
                continue;
            }
            incomingByService.put(
                    serviceName,
                    candidate);
            incomingById.put(
                    candidate,
                    serviceName);
            return candidate;
        }
        throw new IllegalStateException(
                "IDS dynamic service stream-ID space is exhausted");
    }

    private void associateOutgoingMapping(
            String serviceName,
            int streamId) {
        if (streamId <= 0
                || streamId > MAX_STREAM_ID) {
            throw new IllegalArgumentException(
                    "IDS peer stream ID must be nonzero uint16");
        }
        Integer byService =
                outgoingByService.get(
                        serviceName);
        if (byService != null
                && byService != streamId) {
            throw new IllegalArgumentException(
                    "IDS peer changed the ID for one service");
        }
        String byId =
                outgoingById.get(
                        streamId);
        if (byId != null
                && !byId.equals(
                        serviceName)) {
            throw new IllegalArgumentException(
                    "IDS peer reused one ID for two services");
        }
        outgoingByService.put(
                serviceName,
                streamId);
        outgoingById.put(
                streamId,
                serviceName);
    }

    private static void requireServiceName(
            String serviceName) {
        if (serviceName == null
                || serviceName.isEmpty()) {
            throw new IllegalArgumentException(
                    "IDS service name is empty");
        }
        byte[] encoded =
                serviceName.getBytes(
                        StandardCharsets.UTF_8);
        try {
            if (encoded.length
                    > MAX_SERVICE_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "IDS service name exceeds uint16");
            }
        } finally {
            Arrays.fill(
                    encoded,
                    (byte) 0);
        }
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS service-map state is closed");
        }
        if (poisoned) {
            throw new IllegalStateException(
                    "IDS service-map state is poisoned");
        }
    }

    private void fail() {
        clear();
        poisoned = true;
    }

    private void clear() {
        incomingByService.clear();
        incomingById.clear();
        outgoingByService.clear();
        outgoingById.clear();
        inlineTopicCount.clear();
        advertised.clear();
        remoteInstanceId = null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        clear();
    }

    static final class OutgoingRoute {
        final String serviceName;
        final int streamId;
        final boolean includeTopic;

        OutgoingRoute(
                String serviceName,
                int streamId,
                boolean includeTopic) {
            this.serviceName = serviceName;
            this.streamId = streamId;
            this.includeTopic = includeTopic;
        }

        String wireTopic() {
            return includeTopic
                    ? serviceName
                    : null;
        }
    }

    static final class IncomingRoute {
        final String serviceName;
        final IdsSocketPairCodec.ServiceMapMessage advertisement;

        IncomingRoute(
                String serviceName,
                IdsSocketPairCodec.ServiceMapMessage advertisement) {
            this.serviceName = serviceName;
            this.advertisement = advertisement;
        }
    }
}
