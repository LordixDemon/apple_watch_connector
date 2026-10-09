package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class IdsControlChannelSessionTest {
    @Test
    public void defaultPairedSessionEmitsOrdinaryRatherThanTinkerHello() {
        try (var session = IdsControlChannelSession.createDefaultPaired(new java.security.SecureRandom(),
                "00112233-4455-4677-8899-aabbccddeeff", true, 1)) {
            var hello = (IdsControlChannelCodec.HelloMessage) IdsControlChannelCodec.decodeFramed(session.startHello());
            try {
                org.junit.Assert.assertEquals(0xbffL, hello.capabilityFlags);
                org.junit.Assert.assertEquals(0L, hello.capabilityFlags & 0x400L);
                org.junit.Assert.assertEquals(0x100L, hello.capabilityFlags & 0x100L);
                org.junit.Assert.assertEquals(0x800L, hello.capabilityFlags & 0x800L);
            } finally { hello.destroy(); }
        }
    }
    private static final String LOCAL_DEVICE =
            "10213243-5465-7687-98a9-bacbdcedfe0f";

    @Test
    public void emitsOneExactLocalHelloAndAcceptsFragmentedPeerHello() {
        SecureRandom random =
                deterministicRandom();
        try (IdsControlChannelSession session =
                     IdsControlChannelSession
                             .createDefaultPaired(
                                     random,
                                     LOCAL_DEVICE,
                                     false,
                                     100)) {
            byte[] local =
                    session.startHello();
            IdsControlChannelCodec.HelloMessage localHello =
                    (IdsControlChannelCodec.HelloMessage)
                            IdsControlChannelCodec.decodeFramed(
                                    local);
            try {
                assertEquals(
                        "iPhone18,1",
                        localHello.model);
                assertEquals(
                        UUID.fromString(
                                LOCAL_DEVICE),
                        localHello.deviceUniqueId);
                assertEquals(
                        0x3ff,
                        localHello.capabilityFlags);
                assertEquals(
                        4,
                        localHello.instanceId.version());
                assertEquals(
                        2,
                        localHello.instanceId.variant());
                assertThrows(
                        IllegalStateException.class,
                        session::startHello);
            } finally {
                localHello.destroy();
                wipe(
                        local);
            }

            UUID remoteInstance =
                    UUID.fromString(
                            "00112233-4455-6677-8899-aabbccddeeff");
            IdsControlChannelCodec.HelloMessage watchHello =
                    new IdsControlChannelCodec.HelloMessage(
                            "5",
                            "watchOS",
                            "26.2",
                            "23S303",
                            "Watch7,5",
                            26,
                            25,
                            26,
                            remoteInstance,
                            UUID.fromString(
                                    "20314253-6475-8697-a8b9-cadbecfd0e1f"),
                            0x7ff,
                            32);
            byte[] framed =
                    IdsControlChannelCodec.encodeFramed(
                            watchHello);
            watchHello.destroy();
            List<IdsControlChannelCodec.Message> first =
                    session.acceptTcpBytes(
                            Arrays.copyOfRange(
                                    framed,
                                    0,
                                    7));
            List<IdsControlChannelCodec.Message> second =
                    session.acceptTcpBytes(
                            Arrays.copyOfRange(
                                    framed,
                                    7,
                                    framed.length));
            try {
                assertTrue(
                        first.isEmpty());
                assertEquals(
                        1,
                        second.size());
                assertTrue(
                        session.remoteHelloReceived());
                assertEquals(
                        remoteInstance,
                        session.remoteHello()
                                .instanceId);
                assertEquals(
                        "Watch7,5",
                        session.remoteHello()
                                .model);
                assertEquals(
                        IdsControlChannelSession
                                .Compatibility.MATCHED,
                        session.compatibility());
                assertEquals(
                        100,
                        session.serviceMap()
                                .routeOutgoing(
                                        "service")
                                .streamId);
            } finally {
                destroy(
                        first);
                destroy(
                        second);
                wipe(
                        framed);
            }
        }
    }

    @Test
    public void setupBeforeHelloAndDuplicateHelloPoisonTheSession() {
        IdsControlChannelCodec.SetupChannelMessage setup =
                new IdsControlChannelCodec.SetupChannelMessage(
                        IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                        IdsControlChannelCodec.PROTOCOL_TCP,
                        49152,
                        IdsControlChannelCodec.DATA_PORT,
                        "00112233-4455-6677-8899-aabbccddeeff",
                        null,
                        "account",
                        "service",
                        "name",
                        null);
        byte[] setupFrame =
                IdsControlChannelCodec.encodeFramed(
                        setup);
        setup.destroy();
        try (IdsControlChannelSession session =
                     IdsControlChannelSession
                             .createDefaultPaired(
                                     deterministicRandom(),
                                     LOCAL_DEVICE,
                                     false,
                                     1)) {
            assertFalse(
                    session.remoteHelloReceived());
            List<IdsControlChannelCodec.Message> msgs =
                    session.acceptTcpBytes(
                            setupFrame);
            try {
                assertEquals(
                        1,
                        msgs.size());
                assertFalse(
                        session.remoteHelloReceived());
            } finally {
                destroy(
                        msgs);
            }
        } finally {
            wipe(
                    setupFrame);
        }

        IdsControlChannelCodec.HelloMessage hello =
                peerHello(
                        26,
                        25,
                        26);
        byte[] helloFrame =
                IdsControlChannelCodec.encodeFramed(
                        hello);
        hello.destroy();
        try (IdsControlChannelSession session =
                     IdsControlChannelSession
                             .createDefaultPaired(
                                     deterministicRandom(),
                                     LOCAL_DEVICE,
                                     false,
                                     1)) {
            List<IdsControlChannelCodec.Message> first =
                    session.acceptTcpBytes(
                            helloFrame);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> session.acceptTcpBytes(
                                helloFrame));
            } finally {
                destroy(
                        first);
            }
        } finally {
            wipe(
                    helloFrame);
        }
    }

    @Test
    public void reportsNoOverlapWithoutInventingAProtocolFallback() {
        IdsControlChannelCodec.HelloMessage hello =
                peerHello(
                        30,
                        30,
                        31);
        byte[] frame =
                IdsControlChannelCodec.encodeFramed(
                        hello);
        hello.destroy();
        try (IdsControlChannelSession session =
                     IdsControlChannelSession
                             .createDefaultPaired(
                                     deterministicRandom(),
                                     LOCAL_DEVICE,
                                     false,
                                     1)) {
            List<IdsControlChannelCodec.Message> messages =
                    session.acceptTcpBytes(
                            frame);
            try {
                assertEquals(
                        IdsControlChannelSession
                                .Compatibility.NO_OVERLAP,
                        session.compatibility());
            } finally {
                destroy(
                        messages);
            }
        } finally {
            wipe(
                    frame);
        }
    }

    private static IdsControlChannelCodec.HelloMessage peerHello(
            long pairing,
            long minimum,
            long maximum) {
        return new IdsControlChannelCodec.HelloMessage(
                "5",
                "watchOS",
                "26.2",
                "23S303",
                "Watch7,5",
                pairing,
                minimum,
                maximum,
                UUID.fromString(
                        "00112233-4455-6677-8899-aabbccddeeff"),
                UUID.fromString(
                        "20314253-6475-8697-a8b9-cadbecfd0e1f"),
                0x7ff,
                32);
    }

    private static SecureRandom deterministicRandom() {
        return new SecureRandom() {
            private int next;

            @Override
            public void nextBytes(
                    byte[] bytes) {
                for (int index = 0;
                        index < bytes.length;
                        index++) {
                    bytes[index] =
                            (byte) next++;
                }
            }
        };
    }

    private static void destroy(
            List<IdsControlChannelCodec.Message> messages) {
        for (IdsControlChannelCodec.Message message : messages) {
            message.destroy();
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
