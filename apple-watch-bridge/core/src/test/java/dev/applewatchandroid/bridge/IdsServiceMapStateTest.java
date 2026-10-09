package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

public final class IdsServiceMapStateTest {
    private static final String SERVICE =
            "com.apple.private.alloy.preferencessync.pairedsync";
    private static final String OTHER_SERVICE =
            "com.apple.private.alloy.preferencessync";
    private static final String MESSAGE_UUID =
            "00112233-4455-6677-8899-aabbccddeeff";

    @Test
    public void firstMessageIsInlineThenThirdAdvertisesRuntimeMap() {
        try (IdsServiceMapState sender =
                        new IdsServiceMapState(
                                0x0100);
                IdsServiceMapState receiver =
                        new IdsServiceMapState(
                                0x0200)) {
            IdsServiceMapState.OutgoingRoute initial =
                    sender.routeOutgoing(
                            SERVICE);
            assertEquals(0x0100, initial.streamId);
            assertTrue(initial.includeTopic);
            assertEquals(SERVICE, initial.wireTopic());

            IdsSocketPairCodec.ProtobufMessage inline =
                    protobuf(
                            initial.streamId,
                            initial.wireTopic());
            IdsServiceMapState.IncomingRoute first =
                    receiver.acceptIncoming(
                            inline);
            IdsServiceMapState.IncomingRoute second =
                    receiver.acceptIncoming(
                            inline);
            IdsServiceMapState.IncomingRoute third =
                    receiver.acceptIncoming(
                            inline);
            try {
                assertEquals(SERVICE, first.serviceName);
                assertNull(first.advertisement);
                assertNull(second.advertisement);
                assertEquals(SERVICE, third.serviceName);
                assertEquals(
                        IdsServiceMapState.ADVERTISEMENT_REASON,
                        third.advertisement.reason);
                assertEquals(0x0200, third.advertisement.streamId);
                assertEquals(SERVICE, third.advertisement.serviceName);

                sender.acceptServiceMap(
                        third.advertisement);
                IdsServiceMapState.OutgoingRoute optimized =
                        sender.routeOutgoing(
                                SERVICE);
                assertEquals(0x0200, optimized.streamId);
                assertFalse(optimized.includeTopic);
                assertNull(optimized.wireTopic());

                IdsSocketPairCodec.ProtobufMessage mapped =
                        protobuf(
                                optimized.streamId,
                                null);
                try {
                    IdsServiceMapState.IncomingRoute accepted =
                            receiver.acceptIncoming(
                                    mapped);
                    assertEquals(SERVICE, accepted.serviceName);
                    assertNull(accepted.advertisement);
                } finally {
                    mapped.destroy();
                }
            } finally {
                if (third.advertisement != null) {
                    third.advertisement.destroy();
                }
                inline.destroy();
            }
        }
    }

    @Test
    public void inlineTopicImmediatelyMapsResponsesToPeerId() {
        try (IdsServiceMapState state =
                new IdsServiceMapState(
                        100)) {
            IdsSocketPairCodec.DataMessage incoming =
                    data(
                            77,
                            SERVICE);
            try {
                IdsServiceMapState.IncomingRoute route =
                        state.acceptIncoming(
                                incoming);
                assertEquals(SERVICE, route.serviceName);
                IdsServiceMapState.OutgoingRoute response =
                        state.routeOutgoing(
                                SERVICE);
                assertEquals(77, response.streamId);
                assertFalse(response.includeTopic);
                assertEquals(1, state.localMappingCount());
                assertEquals(1, state.peerMappingCount());
            } finally {
                incoming.destroy();
            }
        }
    }

    @Test
    public void allocatorWrapsAndMayReuseOutgoingMapId() {
        try (IdsServiceMapState state =
                new IdsServiceMapState(
                        0xffff)) {
            IdsSocketPairCodec.ServiceMapMessage peer =
                    new IdsSocketPairCodec.ServiceMapMessage(
                            1,
                            0xffff,
                            SERVICE);
            try {
                state.acceptServiceMap(
                        peer);
                IdsServiceMapState.OutgoingRoute other =
                        state.routeOutgoing(
                                OTHER_SERVICE);
                assertEquals(0xffff, other.streamId);
                assertTrue(other.includeTopic);
            } finally {
                peer.destroy();
            }
        }
    }

    @Test
    public void unknownTopiclessStreamPoisonsConnectionState() {
        try (IdsServiceMapState state =
                new IdsServiceMapState(
                        1)) {
            IdsSocketPairCodec.DataMessage unknown =
                    data(
                            99,
                            null);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> state.acceptIncoming(
                                unknown));
                assertThrows(
                        IllegalStateException.class,
                        () -> state.routeOutgoing(
                                SERVICE));
            } finally {
                unknown.destroy();
            }
        }
    }

    @Test
    public void rejectsConflictsInsideOneMapButAllowsCrossMapReuse() {
        try (IdsServiceMapState changedId =
                new IdsServiceMapState(
                        100)) {
            IdsSocketPairCodec.ServiceMapMessage first =
                    serviceMap(
                            10,
                            SERVICE);
            IdsSocketPairCodec.ServiceMapMessage changed =
                    serviceMap(
                            11,
                            SERVICE);
            try {
                changedId.acceptServiceMap(
                        first);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> changedId.acceptServiceMap(
                                changed));
            } finally {
                first.destroy();
                changed.destroy();
            }
        }

        try (IdsServiceMapState reusedId =
                new IdsServiceMapState(
                        100)) {
            IdsSocketPairCodec.ServiceMapMessage first =
                    serviceMap(
                            10,
                            SERVICE);
            IdsSocketPairCodec.ServiceMapMessage reused =
                    serviceMap(
                            10,
                            OTHER_SERVICE);
            try {
                reusedId.acceptServiceMap(
                        first);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> reusedId.acceptServiceMap(
                                reused));
            } finally {
                first.destroy();
                reused.destroy();
            }
        }

        try (IdsServiceMapState crossMap =
                new IdsServiceMapState(
                        20)) {
            assertEquals(
                    20,
                    crossMap.routeOutgoing(
                            SERVICE).streamId);
            IdsSocketPairCodec.ServiceMapMessage conflict =
                    serviceMap(
                            20,
                            OTHER_SERVICE);
            try {
                crossMap.acceptServiceMap(
                        conflict);
                assertEquals(
                        20,
                        crossMap.routeOutgoing(
                                OTHER_SERVICE).streamId);
                assertFalse(
                        crossMap.routeOutgoing(
                                OTHER_SERVICE).includeTopic);
            } finally {
                conflict.destroy();
            }
        }
    }

    @Test
    public void topiclessIncomingUsesOnlyIncomingMap() {
        try (IdsServiceMapState state =
                new IdsServiceMapState(
                        1)) {
            IdsSocketPairCodec.ServiceMapMessage outgoingOnly =
                    serviceMap(
                            44,
                            SERVICE);
            IdsSocketPairCodec.DataMessage topicless =
                    data(
                            44,
                            null);
            try {
                state.acceptServiceMap(
                        outgoingOnly);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> state.acceptIncoming(
                                topicless));
            } finally {
                outgoingOnly.destroy();
                topicless.destroy();
            }
        }
    }

    @Test
    public void remoteInstanceChangeRequiresReconnect() {
        UUID first =
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000001");
        UUID changed =
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000002");
        try (IdsServiceMapState state =
                new IdsServiceMapState(
                        1)) {
            state.observeRemoteInstance(
                    first);
            state.observeRemoteInstance(
                    first);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> state.observeRemoteInstance(
                            changed));
            assertThrows(
                    IllegalStateException.class,
                    () -> state.routeOutgoing(
                            SERVICE));
        }
    }

    @Test
    public void onlyCurrentAdvertisementReasonIsAccepted() {
        try (IdsServiceMapState state =
                new IdsServiceMapState(
                        1)) {
            IdsSocketPairCodec.ServiceMapMessage unsupported =
                    new IdsSocketPairCodec.ServiceMapMessage(
                            2,
                            3,
                            SERVICE);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> state.acceptServiceMap(
                                unsupported));
            } finally {
                unsupported.destroy();
            }
        }
    }

    @Test
    public void fallbackServiceAdoptsUnknownStreamWhenTopicOmitted() {
        try (IdsServiceMapState stateWithoutFallback = new IdsServiceMapState(1);
                IdsServiceMapState stateWithFallback = new IdsServiceMapState(1)) {
            IdsSocketPairCodec.ProtobufMessage mapped = protobuf(1, null);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> stateWithoutFallback.acceptIncoming(mapped));

                IdsServiceMapState.IncomingRoute accepted =
                        stateWithFallback.acceptIncoming(mapped, SERVICE);
                assertEquals(SERVICE, accepted.serviceName);

                IdsServiceMapState.IncomingRoute subsequent =
                        stateWithFallback.acceptIncoming(mapped);
                assertEquals(SERVICE, subsequent.serviceName);
            } finally {
                mapped.destroy();
            }
        }
    }

    private static IdsSocketPairCodec.ProtobufMessage protobuf(
            int streamId,
            String topic) {
        return new IdsSocketPairCodec.ProtobufMessage(
                1,
                streamId,
                topic == null
                        ? 0
                        : IdsSocketPairCodec.FLAG_HAS_TOPIC,
                null,
                MESSAGE_UUID,
                topic,
                0,
                false,
                new byte[]{
                        1
                },
                null);
    }

    private static IdsSocketPairCodec.DataMessage data(
            int streamId,
            String topic) {
        return new IdsSocketPairCodec.DataMessage(
                1,
                streamId,
                topic == null
                        ? 0
                        : IdsSocketPairCodec.FLAG_HAS_TOPIC,
                null,
                MESSAGE_UUID,
                topic,
                new byte[]{
                        1
                },
                null);
    }

    private static IdsSocketPairCodec.ServiceMapMessage serviceMap(
            int streamId,
            String service) {
        return new IdsSocketPairCodec.ServiceMapMessage(
                1,
                streamId,
                service);
    }
}
