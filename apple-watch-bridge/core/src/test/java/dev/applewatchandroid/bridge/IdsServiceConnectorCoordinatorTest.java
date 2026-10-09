package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

public final class IdsServiceConnectorCoordinatorTest {
    private static final byte[] PHONE_D =
            address(
                    0x10);
    private static final byte[] WATCH_D =
            address(
                    0x20);
    private static final byte[] PHONE_C =
            address(
                    0x30);
    private static final byte[] WATCH_C =
            address(
                    0x40);

    @Test
    public void normalRequestAdvertisesKeyListenerInsteadOfEphemeralTcpSource() {
        for (IdsIpsecServiceRoute route : List.of(IdsIpsecServiceRoute.control(),
                NanoRegistryInitialIdsRoute.classDSetup().ipsecRoute,
                NanoRegistryInitialIdsRoute.classCProperties().ipsecRoute)) {
            try (Side phone = phone(); var opened = phone.coordinator.openService(route);
                 var tcp = Ipv6TcpPacketCodec.decode(opened.packet().clearIpv6Packet())) {
                var request = NwServiceConnectorCodec.decode(tcp.payload);
                try {
                    assertEquals(route.listenerPort, ((NwServiceConnectorCodec.NormalStartRequest) request).localPort);
                    assertTrue(tcp.sourcePort != route.listenerPort);
                } finally { request.destroy(); }
            }
        }
    }

    @Test
    public void staleEstablishedDataIsResetAndFreshKeyBootstrapStillWorks() {
        try (Side phone = phone(); Side watch = watch()) {
            byte[] stale = Ipv6TcpPacketCodec.encode(WATCH_D, PHONE_D, 61315, 61315,
                    100, 200, Ipv6TcpPacketCodec.FLAG_ACK, Ipv6TcpStream.RECEIVE_WINDOW,
                    0, new byte[0], new byte[] {(byte) 0xff, (byte) 0xfe, 0, 42, 7});
            try (var result = phone.coordinator.accept(OrdinaryIkeAuth.DataClass.CLASS_D, stale)) {
                assertTrue(result.events().isEmpty());
                assertEquals(0, phone.coordinator.activeServiceCount());
                assertEquals(0, result.outboundPackets().size());
            } finally { wipe(stale); }
            try (var opened = phone.coordinator.openService(IdsIpsecServiceRoute.control());
                 var handshake = pump(phone, watch, List.of(opened.packet()))) {
                assertEquals(1, handshake.count("phone", IdsServiceConnectorCoordinator.EventType.SERVICE_ACCEPTED));
                assertEquals(1, handshake.count("watch", IdsServiceConnectorCoordinator.EventType.SERVICE_ACCEPTED));
            }
        }
    }

    @Test
    public void fragmentedIncomingNwscRequestRetainsItsPartialHeaderAndBody() {
        for (int split : new int[] {1, 2, 7, 42, 79}) {
            try (Side phone = phone(); Side watch = watch();
                 IdsServiceConnectorCoordinator.OpenResult opened =
                         phone.coordinator.openService(IdsIpsecServiceRoute.control());
                 Ipv6TcpPacketCodec.Packet original =
                         Ipv6TcpPacketCodec.decode(opened.packet().clearIpv6Packet())) {
                assertTrue(original.payload.length > split);
                byte[] first = Ipv6TcpPacketCodec.encode(
                        original.sourceAddress, original.destinationAddress,
                        original.sourcePort, original.destinationPort,
                        original.sequence, original.acknowledgement, original.flags,
                        original.window, original.urgentPointer, original.options,
                        Arrays.copyOfRange(original.payload, 0, split));
                long acknowledgement = -1;
                try (IdsServiceConnectorCoordinator.AcceptResult partial =
                             watch.coordinator.accept(OrdinaryIkeAuth.DataClass.CLASS_D, first)) {
                    for (IdsServiceConnectorCoordinator.RoutedPacket reply : partial.outboundPackets()) {
                        try (reply; Ipv6TcpPacketCodec.Packet decoded =
                                     Ipv6TcpPacketCodec.decode(reply.clearIpv6Packet())) {
                            if (decoded.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)) {
                                acknowledgement = (decoded.sequence + 1) & 0xffff_ffffL;
                            }
                        }
                    }
                    assertEquals(0, partial.events().stream().filter(event -> event.type
                            == IdsServiceConnectorCoordinator.EventType.KEY_PROBE_STARTED).count());
                }
                assertTrue(acknowledgement >= 0);
                byte[] rest = Ipv6TcpPacketCodec.encode(
                        original.sourceAddress, original.destinationAddress,
                        original.sourcePort, original.destinationPort,
                        (original.sequence + 1 + split) & 0xffff_ffffL, acknowledgement,
                        Ipv6TcpPacketCodec.FLAG_ACK | Ipv6TcpPacketCodec.FLAG_PSH,
                        original.window, 0, new byte[0],
                        Arrays.copyOfRange(original.payload, split, original.payload.length));
                try (IdsServiceConnectorCoordinator.AcceptResult complete =
                             watch.coordinator.accept(OrdinaryIkeAuth.DataClass.CLASS_D, rest)) {
                    assertEquals("split=" + split, 1, complete.events().stream().filter(event ->
                            event.type == IdsServiceConnectorCoordinator.EventType.KEY_PROBE_STARTED).count());
                } finally {
                    wipe(first);
                    wipe(rest);
                }
            }
        }
    }

    @Test
    public void freshControlPeersBootstrapKeyThenCarryDirectIdsBytes() {
        try (Side phone =
                     phone();
             Side watch =
                     watch();
             IdsServiceConnectorCoordinator.OpenResult opened =
                     phone.coordinator.openService(
                             IdsIpsecServiceRoute.control());
             PumpResult handshake =
                     pump(
                             phone,
                             watch,
                             List.of(
                                     opened.packet()))) {
            assertEquals(
                    1,
                    handshake.count(
                            "phone",
                            IdsServiceConnectorCoordinator
                                    .EventType.SERVICE_ACCEPTED));
            assertEquals(
                    1,
                    handshake.count(
                            "watch",
                            IdsServiceConnectorCoordinator
                                    .EventType.SERVICE_ACCEPTED));
            assertEquals(
                    1,
                    handshake.count(
                            "watch",
                            IdsServiceConnectorCoordinator
                                    .EventType.KEY_PROBE_STARTED));
            assertEquals(
                    1,
                    handshake.count(
                            "watch",
                            IdsServiceConnectorCoordinator
                                    .EventType.KEY_PROBE_COMPLETED));
            assertEquals(
                    1,
                    phone.coordinator.activeServiceCount());
            assertEquals(
                    1,
                    watch.coordinator.activeServiceCount());
            assertEquals(
                    0,
                    phone.coordinator.pendingOutgoingCount());
            assertEquals(
                    0,
                    watch.coordinator.pendingOutgoingCount());
            assertEquals(
                    1,
                    phone.coordinator.dynamicPortCount());
            assertEquals(
                    0,
                    watch.coordinator.dynamicPortCount());

            byte[] directIds =
                    new byte[] {
                            (byte) IdsSocketPairCodec.COMMAND_PROTOBUF,
                            0x01,
                            0x02,
                            0x03,
                            0x04
                    };
            try (IdsServiceConnectorCoordinator.PacketBatch sent =
                         phone.coordinator.sendApplication(
                                 opened.connectionId,
                                 directIds);
                 PumpResult application =
                         pump(
                                 phone,
                                 watch,
                                 sent.packets())) {
                ObservedEvent delivered =
                        application.first(
                                "watch",
                                IdsServiceConnectorCoordinator
                                        .EventType.APPLICATION_BYTES);
                assertArrayEquals(
                        directIds,
                        delivered.applicationBytes);
                assertEquals(
                        IdsIpsecServiceRoute.CONTROL_SERVICE,
                        delivered.serviceName);
            } finally {
                wipe(
                        directIds);
            }
        }
    }

    @Test
    public void reverseKeyProbeKeepsOriginalTcpThenAcceptedFeedbackActivates() {
        IdsIpsecServiceRoute classD =
                NanoRegistryInitialIdsRoute
                        .classDSetup()
                        .ipsecRoute;
        try (Side phone =
                     phone();
             IdsIpv6TcpRouter watchTcp =
                     new IdsIpv6TcpRouter(
                             new SecureRandom(),
                             WATCH_D,
                             PHONE_D,
                             WATCH_C,
                             PHONE_C);
             IdsServiceConnectorCoordinator.OpenResult opened =
                     phone.coordinator.openService(
                             classD)) {
            watchTcp.listen(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    IdsControlChannelCodec.DATA_PORT);
            byte[] syn =
                    opened.packet().clearIpv6Packet();
            Ipv6TcpPacketCodec.Packet decodedSyn =
                    Ipv6TcpPacketCodec.decode(
                            syn);
            int phoneSourcePort =
                    decodedSyn.sourcePort;
            byte[] startFrame =
                    decodedSyn.payload.clone();
            decodedSyn.close();
            NwServiceConnectorCodec.Message startMessage =
                    NwServiceConnectorCodec.decode(
                            startFrame);
            long requestSequence =
                    startMessage.sequence;
            startMessage.destroy();
            wipe(startFrame);

            byte[] synAck;
            try (IdsIpv6TcpRouter.InboundResult atWatch =
                         watchTcp.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 syn)) {
                synAck =
                        atWatch.outboundPackets().get(0).clone();
            }
            try (IdsServiceConnectorCoordinator.AcceptResult established =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 synAck)) {
                assertEquals(
                        0,
                        countCoordinatorEvents(
                                established,
                                IdsServiceConnectorCoordinator
                                        .EventType.SERVICE_ACCEPTED));
                for (IdsServiceConnectorCoordinator.RoutedPacket ack :
                        established.outboundPackets()) {
                    try (IdsIpv6TcpRouter.InboundResult ignored =
                                 watchTcp.accept(
                                         OrdinaryIkeAuth.DataClass.CLASS_D,
                                         ack.clearIpv6Packet())) {
                    }
                }
            } finally {
                wipe(synAck);
                wipe(syn);
            }

            byte[] probeSyn =
                    Ipv6TcpPacketCodec.encode(
                            WATCH_D,
                            PHONE_D,
                            49154,
                            phoneSourcePort,
                            1000L,
                            0L,
                            Ipv6TcpPacketCodec.FLAG_SYN,
                            65535,
                            0,
                            new byte[0],
                            new byte[0]);
            byte[] probeSynAck;
            try (IdsServiceConnectorCoordinator.AcceptResult probed =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 probeSyn)) {
                List<IdsServiceConnectorCoordinator.RoutedPacket> outbound =
                        probed.outboundPackets();
                try {
                    assertFalse(outbound.isEmpty());
                    probeSynAck =
                            outbound.get(0).clearIpv6Packet();
                } finally {
                    closePackets(outbound);
                }
            } finally {
                wipe(probeSyn);
            }

            Ipv6TcpPacketCodec.Packet decodedSynAck =
                    Ipv6TcpPacketCodec.decode(
                            probeSynAck);
            long sendSeq =
                    decodedSynAck.acknowledgement;
            long ackSeq =
                    decodedSynAck.sequence + 1L;
            decodedSynAck.close();
            wipe(probeSynAck);

            byte[] noOp =
                    NwServiceConnectorCodec.encodeNoOpRequest(
                            49154,
                            99L,
                            classDPrivateKey(WATCH_D));
            byte[] probePayload =
                    Ipv6TcpPacketCodec.encode(
                            WATCH_D,
                            PHONE_D,
                            49154,
                            phoneSourcePort,
                            sendSeq,
                            ackSeq,
                            Ipv6TcpPacketCodec.FLAG_ACK
                                    | Ipv6TcpPacketCodec.FLAG_PSH,
                            65535,
                            0,
                            new byte[0],
                            noOp);
            try (IdsServiceConnectorCoordinator.AcceptResult responded =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 probePayload)) {
                assertEquals(
                        1,
                        countCoordinatorEvents(
                                responded,
                                IdsServiceConnectorCoordinator
                                        .EventType.KEY_PROBE_RESPONDED));
                assertEquals(
                        0,
                        countCoordinatorEvents(
                                responded,
                                IdsServiceConnectorCoordinator
                                        .EventType.OUTGOING_RETRIED));
                assertTrue(
                        copySynToPort(
                                responded.outboundPackets(),
                                IdsControlChannelCodec.DATA_PORT)
                                == null);
                assertEquals(
                        1,
                        phone.coordinator.pendingOutgoingCount());
            } finally {
                wipe(noOp);
                wipe(probePayload);
            }

            byte[] watchPublic =
                    new byte[NwServiceConnectorCodec.PUBLIC_KEY_LENGTH];
            Arrays.fill(
                    watchPublic,
                    (byte) 0x5b);
            byte[] feedback =
                    NwServiceConnectorCodec.encodeFeedback(
                            requestSequence,
                            NwServiceConnectorCodec
                                    .FeedbackDisposition
                                    .ACCEPTED,
                            watchPublic);
            try (IdsIpv6TcpRouter.PacketBatch sent =
                         watchTcp.send(
                                 0,
                                 feedback)) {
                int accepted = 0;
                for (byte[] packet : sent.packets()) {
                    try (IdsServiceConnectorCoordinator.AcceptResult result =
                                 phone.coordinator.accept(
                                         OrdinaryIkeAuth.DataClass.CLASS_D,
                                         packet)) {
                        accepted +=
                                countCoordinatorEvents(
                                        result,
                                        IdsServiceConnectorCoordinator
                                                .EventType.SERVICE_ACCEPTED);
                    }
                }
                assertEquals(
                        1,
                        accepted);
                assertEquals(
                        1,
                        phone.coordinator.activeServiceCount());
                assertEquals(
                        0,
                        phone.coordinator.pendingOutgoingCount());
            } finally {
                wipe(watchPublic);
                wipe(feedback);
            }
        }
    }

    @Test
    public void reverseKeyProbeOnControlKeepsOutgoingUntilAcceptedFeedback() {
        IdsIpsecServiceRoute control =
                IdsIpsecServiceRoute.control();
        try (Side phone =
                     phone();
             IdsIpv6TcpRouter watchTcp =
                     new IdsIpv6TcpRouter(
                             new SecureRandom(),
                             WATCH_D,
                             PHONE_D,
                             WATCH_C,
                             PHONE_C);
             IdsServiceConnectorCoordinator.OpenResult opened =
                     phone.coordinator.openService(
                             control)) {
            watchTcp.listen(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    IdsIpsecServiceRoute.CLOUD_LISTENER_PORT);
            byte[] syn =
                    opened.packet().clearIpv6Packet();
            Ipv6TcpPacketCodec.Packet decodedSyn =
                    Ipv6TcpPacketCodec.decode(
                            syn);
            int phoneSourcePort =
                    decodedSyn.sourcePort;
            byte[] startFrame =
                    decodedSyn.payload.clone();
            decodedSyn.close();
            NwServiceConnectorCodec.Message startMessage =
                    NwServiceConnectorCodec.decode(
                            startFrame);
            long requestSequence =
                    startMessage.sequence;
            startMessage.destroy();
            wipe(startFrame);

            byte[] synAck;
            try (IdsIpv6TcpRouter.InboundResult atWatch =
                         watchTcp.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 syn)) {
                synAck =
                        atWatch.outboundPackets().get(0).clone();
            }
            try (IdsServiceConnectorCoordinator.AcceptResult established =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 synAck)) {
                assertEquals(
                        0,
                        countCoordinatorEvents(
                                established,
                                IdsServiceConnectorCoordinator
                                        .EventType.SERVICE_ACCEPTED));
                for (IdsServiceConnectorCoordinator.RoutedPacket ack :
                        established.outboundPackets()) {
                    try (IdsIpv6TcpRouter.InboundResult ignored =
                                 watchTcp.accept(
                                         OrdinaryIkeAuth.DataClass.CLASS_D,
                                         ack.clearIpv6Packet())) {
                    }
                }
            } finally {
                wipe(synAck);
                wipe(syn);
            }

            byte[] probeSyn =
                    Ipv6TcpPacketCodec.encode(
                            WATCH_D,
                            PHONE_D,
                            49155,
                            phoneSourcePort,
                            1000L,
                            0L,
                            Ipv6TcpPacketCodec.FLAG_SYN,
                            65535,
                            0,
                            new byte[0],
                            new byte[0]);
            byte[] probeSynAck;
            try (IdsServiceConnectorCoordinator.AcceptResult probed =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 probeSyn)) {
                List<IdsServiceConnectorCoordinator.RoutedPacket> outbound =
                        probed.outboundPackets();
                try {
                    assertFalse(outbound.isEmpty());
                    probeSynAck =
                            outbound.get(0).clearIpv6Packet();
                } finally {
                    closePackets(outbound);
                }
            } finally {
                wipe(probeSyn);
            }

            Ipv6TcpPacketCodec.Packet decodedSynAck =
                    Ipv6TcpPacketCodec.decode(
                            probeSynAck);
            long sendSeq =
                    decodedSynAck.acknowledgement;
            long ackSeq =
                    decodedSynAck.sequence + 1L;
            decodedSynAck.close();
            wipe(probeSynAck);

            byte[] noOp =
                    NwServiceConnectorCodec.encodeNoOpRequest(
                            49155,
                            99L,
                            classDPrivateKey(WATCH_D));
            byte[] probePayload =
                    Ipv6TcpPacketCodec.encode(
                            WATCH_D,
                            PHONE_D,
                            49155,
                            phoneSourcePort,
                            sendSeq,
                            ackSeq,
                            Ipv6TcpPacketCodec.FLAG_ACK
                                    | Ipv6TcpPacketCodec.FLAG_PSH,
                            65535,
                            0,
                            new byte[0],
                            noOp);
            try (IdsServiceConnectorCoordinator.AcceptResult responded =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 probePayload)) {
                assertEquals(
                        1,
                        countCoordinatorEvents(
                                responded,
                                IdsServiceConnectorCoordinator
                                        .EventType.KEY_PROBE_RESPONDED));
                assertEquals(
                        0,
                        countCoordinatorEvents(
                                responded,
                                IdsServiceConnectorCoordinator
                                        .EventType.SERVICE_ACCEPTED));
                assertEquals(
                        1,
                        phone.coordinator.pendingOutgoingCount());
                assertEquals(
                        0,
                        phone.coordinator.activeServiceCount());
                assertFalse(
                        phone.coordinator.isServiceActive(
                                opened.connectionId));
                assertThrows(
                        IllegalStateException.class,
                        () -> {
                            try (IdsServiceConnectorCoordinator.PacketBatch ignored =
                                         phone.coordinator.sendApplication(
                                                 opened.connectionId,
                                                 new byte[] {
                                                         0x01
                                                 })) {
                            }
                        });
            } finally {
                wipe(noOp);
                wipe(probePayload);
            }

            byte[] watchPublic =
                    new byte[NwServiceConnectorCodec.PUBLIC_KEY_LENGTH];
            Arrays.fill(
                    watchPublic,
                    (byte) 0x5d);
            byte[] feedback =
                    NwServiceConnectorCodec.encodeFeedback(
                            requestSequence,
                            NwServiceConnectorCodec
                                    .FeedbackDisposition
                                    .ACCEPTED,
                            watchPublic);
            try (IdsIpv6TcpRouter.PacketBatch sent =
                         watchTcp.send(
                                 0,
                                 feedback)) {
                int accepted = 0;
                for (byte[] packet : sent.packets()) {
                    try (IdsServiceConnectorCoordinator.AcceptResult result =
                                 phone.coordinator.accept(
                                         OrdinaryIkeAuth.DataClass.CLASS_D,
                                         packet)) {
                        accepted +=
                                countCoordinatorEvents(
                                        result,
                                        IdsServiceConnectorCoordinator
                                                .EventType.SERVICE_ACCEPTED);
                    }
                }
                assertEquals(
                        1,
                        accepted);
                assertEquals(
                        1,
                        phone.coordinator.activeServiceCount());
                assertEquals(
                        0,
                        phone.coordinator.pendingOutgoingCount());
                assertTrue(
                        phone.coordinator.isServiceActive(
                                opened.connectionId));
            } finally {
                wipe(watchPublic);
                wipe(feedback);
            }
        }
    }

    @Test
    public void transientOutgoingFeedbackKeepsOriginalTcpThenAcceptedActivates() {
        IdsIpsecServiceRoute classD =
                NanoRegistryInitialIdsRoute
                        .classDSetup()
                        .ipsecRoute;
        try (Side phone =
                     phone();
             IdsIpv6TcpRouter watchTcp =
                     new IdsIpv6TcpRouter(
                             new SecureRandom(),
                             WATCH_D,
                             PHONE_D,
                             WATCH_C,
                             PHONE_C);
             IdsServiceConnectorCoordinator.OpenResult opened =
                     phone.coordinator.openService(
                             classD)) {
            watchTcp.listen(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    IdsControlChannelCodec.DATA_PORT);
            byte[] syn =
                    opened.packet().clearIpv6Packet();
            Ipv6TcpPacketCodec.Packet decodedSyn =
                    Ipv6TcpPacketCodec.decode(
                            syn);
            byte[] startFrame =
                    decodedSyn.payload.clone();
            decodedSyn.close();
            NwServiceConnectorCodec.Message startMessage =
                    NwServiceConnectorCodec.decode(
                            startFrame);
            long requestSequence =
                    startMessage.sequence;
            startMessage.destroy();
            wipe(startFrame);

            byte[] synAck;
            try (IdsIpv6TcpRouter.InboundResult atWatch =
                         watchTcp.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 syn)) {
                synAck =
                        atWatch.outboundPackets().get(0).clone();
            }
            try (IdsServiceConnectorCoordinator.AcceptResult established =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 synAck)) {
                for (IdsServiceConnectorCoordinator.RoutedPacket ack :
                        established.outboundPackets()) {
                    try (IdsIpv6TcpRouter.InboundResult ignored =
                                 watchTcp.accept(
                                         OrdinaryIkeAuth.DataClass.CLASS_D,
                                         ack.clearIpv6Packet())) {
                    }
                }
            } finally {
                wipe(synAck);
                wipe(syn);
            }

            byte[] watchPublic =
                    new byte[NwServiceConnectorCodec.PUBLIC_KEY_LENGTH];
            Arrays.fill(
                    watchPublic,
                    (byte) 0x5c);
            byte[] transientFeedback =
                    NwServiceConnectorCodec.encodeFeedback(
                            requestSequence,
                            NwServiceConnectorCodec
                                    .FeedbackDisposition
                                    .TRANSIENT_REJECTION,
                            watchPublic);
            try (IdsIpv6TcpRouter.PacketBatch sent =
                         watchTcp.send(
                                 0,
                                 transientFeedback)) {
                int retried = 0;
                int accepted = 0;
                for (byte[] packet : sent.packets()) {
                    try (IdsServiceConnectorCoordinator.AcceptResult result =
                                 phone.coordinator.accept(
                                         OrdinaryIkeAuth.DataClass.CLASS_D,
                                         packet)) {
                        retried +=
                                countCoordinatorEvents(
                                        result,
                                        IdsServiceConnectorCoordinator
                                                .EventType.OUTGOING_RETRIED);
                        accepted +=
                                countCoordinatorEvents(
                                        result,
                                        IdsServiceConnectorCoordinator
                                                .EventType.SERVICE_ACCEPTED);
                        assertTrue(
                                copySynToPort(
                                        result.outboundPackets(),
                                        IdsControlChannelCodec.DATA_PORT)
                                        == null);
                    }
                }
                assertEquals(
                        0,
                        retried);
                assertEquals(
                        0,
                        accepted);
                assertEquals(
                        1,
                        phone.coordinator.pendingOutgoingCount());
                assertEquals(
                        0,
                        phone.coordinator.activeServiceCount());
            } finally {
                wipe(transientFeedback);
            }

            byte[] acceptedFeedback =
                    NwServiceConnectorCodec.encodeFeedback(
                            requestSequence,
                            NwServiceConnectorCodec
                                    .FeedbackDisposition
                                    .ACCEPTED,
                            watchPublic);
            try (IdsIpv6TcpRouter.PacketBatch sent =
                         watchTcp.send(
                                 0,
                                 acceptedFeedback)) {
                int accepted = 0;
                int retried = 0;
                for (byte[] packet : sent.packets()) {
                    try (IdsServiceConnectorCoordinator.AcceptResult result =
                                 phone.coordinator.accept(
                                         OrdinaryIkeAuth.DataClass.CLASS_D,
                                         packet)) {
                        accepted +=
                                countCoordinatorEvents(
                                        result,
                                        IdsServiceConnectorCoordinator
                                                .EventType.SERVICE_ACCEPTED);
                        retried +=
                                countCoordinatorEvents(
                                        result,
                                        IdsServiceConnectorCoordinator
                                                .EventType.OUTGOING_RETRIED);
                    }
                }
                assertEquals(
                        1,
                        accepted);
                assertEquals(
                        0,
                        retried);
                assertEquals(
                        1,
                        phone.coordinator.activeServiceCount());
                assertEquals(
                        0,
                        phone.coordinator.pendingOutgoingCount());
            } finally {
                wipe(watchPublic);
                wipe(acceptedFeedback);
            }
        }
    }

    @Test
    public void policyRejectOnOutgoingDoesNotOpenReplacementSyn() {
        IdsIpsecServiceRoute classD =
                NanoRegistryInitialIdsRoute
                        .classDSetup()
                        .ipsecRoute;
        try (Side phone =
                     phone();
             IdsIpv6TcpRouter watchTcp =
                     new IdsIpv6TcpRouter(
                             new SecureRandom(),
                             WATCH_D,
                             PHONE_D,
                             WATCH_C,
                             PHONE_C);
             IdsServiceConnectorCoordinator.OpenResult opened =
                     phone.coordinator.openService(
                             classD)) {
            watchTcp.listen(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    IdsControlChannelCodec.DATA_PORT);
            byte[] syn =
                    opened.packet().clearIpv6Packet();
            Ipv6TcpPacketCodec.Packet decodedSyn =
                    Ipv6TcpPacketCodec.decode(
                            syn);
            byte[] startFrame =
                    decodedSyn.payload.clone();
            decodedSyn.close();
            NwServiceConnectorCodec.Message startMessage =
                    NwServiceConnectorCodec.decode(
                            startFrame);
            long requestSequence =
                    startMessage.sequence;
            startMessage.destroy();
            wipe(startFrame);

            byte[] synAck;
            try (IdsIpv6TcpRouter.InboundResult atWatch =
                         watchTcp.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 syn)) {
                synAck =
                        atWatch.outboundPackets().get(0).clone();
            }
            try (IdsServiceConnectorCoordinator.AcceptResult established =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 synAck)) {
                for (IdsServiceConnectorCoordinator.RoutedPacket ack :
                        established.outboundPackets()) {
                    try (IdsIpv6TcpRouter.InboundResult ignored =
                                 watchTcp.accept(
                                         OrdinaryIkeAuth.DataClass.CLASS_D,
                                         ack.clearIpv6Packet())) {
                    }
                }
            } finally {
                wipe(synAck);
                wipe(syn);
            }

            byte[] watchPublic =
                    new byte[NwServiceConnectorCodec.PUBLIC_KEY_LENGTH];
            Arrays.fill(
                    watchPublic,
                    (byte) 0x5d);
            byte[] policyFeedback =
                    NwServiceConnectorCodec.encodeFeedback(
                            requestSequence,
                            NwServiceConnectorCodec
                                    .FeedbackDisposition
                                    .REJECTED_BY_POLICY,
                            watchPublic);
            try (IdsIpv6TcpRouter.PacketBatch sent =
                         watchTcp.send(
                                 0,
                                 policyFeedback)) {
                int rejected = 0;
                int retried = 0;
                for (byte[] packet : sent.packets()) {
                    try (IdsServiceConnectorCoordinator.AcceptResult result =
                                 phone.coordinator.accept(
                                         OrdinaryIkeAuth.DataClass.CLASS_D,
                                         packet)) {
                        rejected +=
                                countCoordinatorEvents(
                                        result,
                                        IdsServiceConnectorCoordinator
                                                .EventType
                                                .SERVICE_REJECTED_BY_POLICY);
                        retried +=
                                countCoordinatorEvents(
                                        result,
                                        IdsServiceConnectorCoordinator
                                                .EventType.OUTGOING_RETRIED);
                        assertTrue(
                                copySynToPort(
                                        result.outboundPackets(),
                                        IdsControlChannelCodec.DATA_PORT)
                                        == null);
                    }
                }
                assertEquals(
                        1,
                        rejected);
                assertEquals(
                        0,
                        retried);
                assertEquals(
                        0,
                        phone.coordinator.pendingOutgoingCount());
                assertEquals(
                        0,
                        phone.coordinator.activeServiceCount());
            } finally {
                wipe(watchPublic);
                wipe(policyFeedback);
            }
        }
    }

    @Test
    public void normalConnectorKeepsClassCAndClassDStreamsSeparate() {
        try (Side phone =
                     phone();
             Side watch =
                     watch()) {
            IdsIpsecServiceRoute classC =
                    NanoRegistryInitialIdsRoute
                            .classCProperties()
                            .ipsecRoute;
            try (IdsServiceConnectorCoordinator.OpenResult opened =
                         phone.coordinator.openService(
                                 classC);
                 PumpResult exchange =
                         pump(
                                 phone,
                                 watch,
                                 List.of(
                                         opened.packet()))) {
                assertTrue(
                        exchange.onlyDataClass(
                                OrdinaryIkeAuth.DataClass.CLASS_C));
                ObservedEvent atWatch =
                        exchange.first(
                                "watch",
                                IdsServiceConnectorCoordinator
                                        .EventType.SERVICE_ACCEPTED);
                assertEquals(
                        IdsIpsecServiceRoute.NETWORK_RELAY_CLASS_C,
                        atWatch.route.networkRelayDataClass);
                assertEquals(
                        IdsIpsecServiceRoute.NORMAL_LISTENER_PORT,
                        atWatch.route.listenerPort);
            }

            IdsIpsecServiceRoute classD =
                    NanoRegistryInitialIdsRoute
                            .classDSetup()
                            .ipsecRoute;
            try (IdsServiceConnectorCoordinator.OpenResult opened =
                         phone.coordinator.openService(
                                 classD);
                 PumpResult exchange =
                         pump(
                                 phone,
                                 watch,
                                 List.of(
                                         opened.packet()))) {
                assertTrue(
                        exchange.onlyDataClass(
                                OrdinaryIkeAuth.DataClass.CLASS_D));
                assertEquals(
                        1,
                        exchange.count(
                                "watch",
                                IdsServiceConnectorCoordinator
                                        .EventType.KEY_PROBE_STARTED));
                ObservedEvent atWatch =
                        exchange.first(
                                "watch",
                                IdsServiceConnectorCoordinator
                                        .EventType.SERVICE_ACCEPTED);
                assertEquals(
                        IdsIpsecServiceRoute.NETWORK_RELAY_CLASS_D,
                        atWatch.route.networkRelayDataClass);
            }

            assertEquals(
                    2,
                    phone.coordinator.activeServiceCount());
            assertEquals(
                    2,
                    watch.coordinator.activeServiceCount());
        }
    }

    @Test
    public void classCAndClassDEndpointsUseIndependentKeyProbes() {
        try (Side phone =
                     phone();
             Side watch =
                     watch();
             IdsServiceConnectorCoordinator.OpenResult classC =
                     phone.coordinator.openService(
                             NanoRegistryInitialIdsRoute
                                     .classCProperties()
                                     .ipsecRoute);
             IdsServiceConnectorCoordinator.OpenResult classD =
                     phone.coordinator.openService(
                             NanoRegistryInitialIdsRoute
                                     .classDSetup()
                                     .ipsecRoute);
             PumpResult exchange =
                     pumpTransits(
                             List.of(
                                     transit(
                                             phone,
                                             watch,
                                             classC.packet()),
                                     transit(
                                             phone,
                                             watch,
                                             classD.packet())))) {
            assertEquals(
                    2,
                    exchange.count(
                            "watch",
                            IdsServiceConnectorCoordinator
                                    .EventType.KEY_PROBE_STARTED));
            assertEquals(
                    2,
                    exchange.count(
                            "watch",
                            IdsServiceConnectorCoordinator
                                    .EventType.KEY_PROBE_COMPLETED));
            assertEquals(
                    2,
                    exchange.count(
                            "phone",
                            IdsServiceConnectorCoordinator
                                    .EventType.SERVICE_ACCEPTED));
            assertEquals(
                    2,
                    exchange.count(
                            "watch",
                            IdsServiceConnectorCoordinator
                                    .EventType.SERVICE_ACCEPTED));
            assertTrue(
                    exchange.dataClasses.contains(
                            OrdinaryIkeAuth.DataClass.CLASS_C));
            assertTrue(
                    exchange.dataClasses.contains(
                            OrdinaryIkeAuth.DataClass.CLASS_D));
            assertEquals(
                    2,
                    phone.coordinator.activeServiceCount());
            assertEquals(
                    2,
                    watch.coordinator.activeServiceCount());
            assertEquals(
                    0,
                    phone.coordinator.pendingOutgoingCount());
            assertEquals(
                    0,
                    watch.coordinator.pendingOutgoingCount());
        }
    }

    @Test
    public void cloudBootstrapDoesNotAuthorizeNormalConnector() {
        try (Side phone =
                     phone();
             Side watch =
                     watch();
             IdsServiceConnectorCoordinator.OpenResult control =
                     phone.coordinator.openService(
                             IdsIpsecServiceRoute.control());
             PumpResult controlExchange =
                     pump(
                             phone,
                             watch,
                             List.of(
                                     control.packet()))) {
            assertEquals(
                    1,
                    controlExchange.count(
                            "watch",
                            IdsServiceConnectorCoordinator
                                    .EventType.KEY_PROBE_STARTED));

            try (IdsServiceConnectorCoordinator.OpenResult data =
                         phone.coordinator.openService(
                                 NanoRegistryInitialIdsRoute
                                         .classCProperties()
                                         .ipsecRoute);
                 PumpResult dataExchange =
                         pump(
                                 phone,
                                 watch,
                                 List.of(
                                         data.packet()))) {
                assertEquals(
                        1,
                        dataExchange.count(
                                "watch",
                                IdsServiceConnectorCoordinator
                                        .EventType.KEY_PROBE_STARTED));
                assertEquals(
                        2,
                        phone.coordinator.activeServiceCount());
                assertEquals(
                        2,
                        watch.coordinator.activeServiceCount());
            }
        }
    }

    @Test
    public void firstFrameBufferPreservesAcceptedApplicationRemainder() {
        byte[] publicKey =
                new byte[NwServiceConnectorCodec.PUBLIC_KEY_LENGTH];
        Arrays.fill(
                publicKey,
                (byte) 0x5a);
        byte[] feedback =
                NwServiceConnectorCodec.encodeFeedback(
                        7,
                        NwServiceConnectorCodec
                                .FeedbackDisposition.ACCEPTED,
                        publicKey);
        byte[] application =
                new byte[] {
                        0x03,
                        0x11,
                        0x22,
                        0x33
                };
        byte[] firstByte =
                Arrays.copyOfRange(
                        feedback,
                        0,
                        1);
        byte[] rest =
                new byte[
                        feedback.length
                                - 1
                                + application.length];
        System.arraycopy(
                feedback,
                1,
                rest,
                0,
                feedback.length - 1);
        System.arraycopy(
                application,
                0,
                rest,
                feedback.length - 1,
                application.length);
        try (IdsServiceConnectorCoordinator.PreludeBuffer buffer =
                     new IdsServiceConnectorCoordinator.PreludeBuffer();
             IdsServiceConnectorCoordinator.PreludeBuffer.Result partial =
                     buffer.push(
                             firstByte);
             IdsServiceConnectorCoordinator.PreludeBuffer.Result complete =
                     buffer.push(
                             rest)) {
            assertFalse(
                    partial.complete());
            assertTrue(
                    complete.complete());
            byte[] decodedFrame =
                    complete.frame();
            byte[] remainder =
                    complete.remainder();
            try {
                assertArrayEquals(
                        feedback,
                        decodedFrame);
                assertArrayEquals(
                        application,
                        remainder);
            } finally {
                wipe(
                        decodedFrame);
                wipe(
                        remainder);
            }
        } finally {
            wipe(
                    publicKey);
            wipe(
                    feedback);
            wipe(
                    application);
            wipe(
                    firstByte);
            wipe(
                    rest);
        }
    }

    @Test
    public void simultaneousSameServiceOpenLeavesExactlyOneConnection() {
        try (Side phone =
                     phone();
             Side watch =
                     watch();
             IdsServiceConnectorCoordinator.OpenResult phoneOpen =
                     phone.coordinator.openService(
                             IdsIpsecServiceRoute.control());
             IdsServiceConnectorCoordinator.OpenResult watchOpen =
                     watch.coordinator.openService(
                             IdsIpsecServiceRoute.control());
             PumpResult exchange =
                     pumpTransits(
                             List.of(
                                     transit(
                                             phone,
                                             watch,
                                             phoneOpen.packet()),
                                     transit(
                                             watch,
                                             phone,
                                             watchOpen.packet())),
                             true)) {
            int cancelled =
                    exchange.count(
                            null,
                            IdsServiceConnectorCoordinator
                                    .EventType.OUTGOING_CANCELLED);
            assertTrue(
                    cancelled >= 1);
            assertTrue(
                    cancelled <= 2);
            assertTrue(
                    exchange.count(
                            null,
                            IdsServiceConnectorCoordinator
                                    .EventType.SERVICE_ACCEPTED) >= 2);
            assertEquals(
                    1,
                    phone.coordinator.activeServiceCount());
            assertEquals(
                    1,
                    watch.coordinator.activeServiceCount());
            assertEquals(
                    0,
                    phone.coordinator.pendingOutgoingCount());
            assertEquals(
                    0,
                    watch.coordinator.pendingOutgoingCount());
            assertEquals(
                    1,
                    phone.coordinator.dynamicPortCount()
                            + watch.coordinator.dynamicPortCount());

            ObservedEvent phoneAccepted =
                    exchange.first(
                            "phone",
                            IdsServiceConnectorCoordinator
                                    .EventType.SERVICE_ACCEPTED);
            byte[] application =
                    new byte[] {
                            0x03,
                            0x55,
                            0x66
                    };
            try (IdsServiceConnectorCoordinator.PacketBatch sent =
                         phone.coordinator.sendApplication(
                                 phoneAccepted.connectionId,
                                 application);
                 PumpResult delivered =
                         pump(
                                 phone,
                                 watch,
                                 sent.packets())) {
                assertArrayEquals(
                        application,
                        delivered.first(
                                        "watch",
                                        IdsServiceConnectorCoordinator
                                                .EventType
                                                .APPLICATION_BYTES)
                                .applicationBytes);
            } finally {
                wipe(
                        application);
            }
        }
    }

    @Test
    public void malformedFirstFrameFailsClosedAndReleasesState() {
        try (IdsServiceConnectorCoordinator watch =
                     watch().coordinator;
             IdsIpv6TcpRouter rawPhone =
                     rawPhone()) {
            byte[] invalidPrelude =
                    new byte[] {
                            0,
                            1,
                            0
                    };
            byte[] syn = null;
            byte[] synAck = null;
            byte[] finalAck = null;
            try (IdsIpv6TcpRouter.ActiveOpen opened =
                         rawPhone.openActive(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 49160,
                                 IdsIpsecServiceRoute
                                         .CLOUD_LISTENER_PORT,
                                 invalidPrelude)) {
                syn =
                        opened.packet();
                try (IdsServiceConnectorCoordinator.AcceptResult atWatch =
                             watch.accept(
                                     OrdinaryIkeAuth.DataClass.CLASS_D,
                                     syn)) {
                    List<IdsServiceConnectorCoordinator.RoutedPacket>
                            packets =
                            atWatch.outboundPackets();
                    try {
                        assertEquals(
                                1,
                                packets.size());
                        synAck =
                                packets.get(
                                                0)
                                        .clearIpv6Packet();
                    } finally {
                        closePackets(
                                packets);
                    }
                }
                try (IdsIpv6TcpRouter.InboundResult atPhone =
                             rawPhone.accept(
                                     OrdinaryIkeAuth.DataClass.CLASS_D,
                                     synAck)) {
                    List<byte[]> packets =
                            atPhone.outboundPackets();
                    try {
                        assertEquals(
                                1,
                                packets.size());
                        finalAck =
                                packets.get(
                                                0)
                                        .clone();
                    } finally {
                        wipeAll(
                                packets);
                    }
                }

                byte[] rejectedAck =
                        finalAck;
                assertThrows(
                        IllegalArgumentException.class,
                        () -> watch.accept(
                                OrdinaryIkeAuth.DataClass.CLASS_D,
                                rejectedAck));
                assertEquals(
                        0,
                        watch.connectionCount());
                assertEquals(
                        0,
                        watch.dynamicPortCount());
            } finally {
                wipe(
                        invalidPrelude);
                wipe(
                        syn);
                wipe(
                        synAck);
                wipe(
                        finalAck);
            }
        }
    }

    @Test
    public void staleFinPayloadOnClosedTupleYieldsRstWithoutRetry() {
        try (Side phone =
                     phone()) {
            byte[] payload =
                    new byte[88];
            Arrays.fill(
                    payload,
                    (byte) 7);
            byte[] staleFinAck =
                    Ipv6TcpPacketCodec.encode(
                            WATCH_D,
                            PHONE_D,
                            61315,
                            1041,
                            1504624765,
                            829261228,
                            Ipv6TcpPacketCodec.FLAG_FIN
                                    | Ipv6TcpPacketCodec.FLAG_ACK
                                    | Ipv6TcpPacketCodec.FLAG_PSH,
                            Ipv6TcpStream.RECEIVE_WINDOW,
                            0,
                            new byte[0],
                            payload);
            try (IdsServiceConnectorCoordinator.AcceptResult result =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 staleFinAck)) {
                assertEquals(
                        1,
                        result.outboundPackets().size());
                assertEquals(
                        0,
                        countCoordinatorEvents(
                                result,
                                IdsServiceConnectorCoordinator
                                        .EventType.OUTGOING_WAITING_RETRY));
                assertEquals(
                        0,
                        countCoordinatorEvents(
                                result,
                                IdsServiceConnectorCoordinator
                                        .EventType.OUTGOING_RETRIED));
                assertEquals(
                        0,
                        phone.coordinator.connectionCount());
                IdsServiceConnectorCoordinator.RoutedPacket rstPacket =
                        result.outboundPackets().get(0);
                Ipv6TcpPacketCodec.Packet decoded =
                        Ipv6TcpPacketCodec.decode(
                                rstPacket.clearIpv6Packet());
                try {
                    assertEquals(
                            1041,
                            decoded.sourcePort);
                    assertEquals(
                            61315,
                            decoded.destinationPort);
                    assertTrue(
                            decoded.hasFlag(
                                    Ipv6TcpPacketCodec.FLAG_RST));
                    assertFalse(
                            decoded.hasFlag(
                                    Ipv6TcpPacketCodec.FLAG_SYN));
                } finally {
                    decoded.close();
                }
            } finally {
                wipe(
                        staleFinAck);
                wipe(
                        payload);
            }
        }
    }

    @Test
    public void staleFinOnClosedTupleIsWithheld() {
        try (Side phone =
                     phone()) {
            byte[] staleFinAck =
                    Ipv6TcpPacketCodec.encode(
                            WATCH_D,
                            PHONE_D,
                            61315,
                            1027,
                            1504624765,
                            829261228,
                            Ipv6TcpPacketCodec.FLAG_FIN | Ipv6TcpPacketCodec.FLAG_ACK,
                            Ipv6TcpStream.RECEIVE_WINDOW,
                            0,
                            new byte[0],
                            new byte[0]);
            try (IdsServiceConnectorCoordinator.AcceptResult result =
                         phone.coordinator.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 staleFinAck)) {
                assertEquals(
                        1,
                        result.outboundPackets().size());
                IdsServiceConnectorCoordinator.RoutedPacket ackPacket =
                        result.outboundPackets().get(0);
                Ipv6TcpPacketCodec.Packet decoded =
                        Ipv6TcpPacketCodec.decode(
                                ackPacket.clearIpv6Packet());
                try {
                    assertTrue(
                            decoded.hasFlag(
                                    Ipv6TcpPacketCodec.FLAG_ACK));
                    assertFalse(
                            decoded.hasFlag(
                                    Ipv6TcpPacketCodec.FLAG_RST));
                    assertFalse(
                            decoded.hasFlag(
                                    Ipv6TcpPacketCodec.FLAG_FIN));
                } finally {
                    decoded.close();
                }
            } finally {
                wipe(
                        staleFinAck);
            }
        }
    }

    @Test
    public void isRawIdsSocketPairFrameDistinguishesNwscFramesFromSocketPair() {
        // NWSC Feedback frame (44 bytes total, body length 42)
        byte[] nwscFeedback = new byte[44];
        nwscFeedback[0] = 0;
        nwscFeedback[1] = (byte) NwServiceConnectorCodec.FEEDBACK_BODY_LENGTH;
        nwscFeedback[2] = (byte) NwServiceConnectorCodec.FEEDBACK_ACCEPTED;
        assertFalse(IdsServiceConnectorCoordinator.isRawIdsSocketPairFrame(nwscFeedback));

        // NWSC Operation frame (81 bytes total, body length 79)
        byte[] nwscOp = new byte[81];
        nwscOp[0] = 0;
        nwscOp[1] = (byte) NwServiceConnectorCodec.OPERATION_BODY_LENGTH;
        assertFalse(IdsServiceConnectorCoordinator.isRawIdsSocketPairFrame(nwscOp));

        // NWSC Normal Start Request (e.g. 135 bytes total, body length 133)
        byte[] nwscNormal = new byte[135];
        nwscNormal[0] = 0;
        nwscNormal[1] = (byte) 133;
        assertFalse(IdsServiceConnectorCoordinator.isRawIdsSocketPairFrame(nwscNormal));

        // Genuine IDS Socket Pair DATA frame (command=0x00, bodyLength=100)
        byte[] idsData = new byte[105];
        idsData[0] = (byte) IdsSocketPairCodec.COMMAND_DATA;
        idsData[1] = 0;
        idsData[2] = 0;
        idsData[3] = 0;
        idsData[4] = 100;
        assertTrue(IdsServiceConnectorCoordinator.isRawIdsSocketPairFrame(idsData));

        // Genuine IDS Socket Pair PROTOBUF frame (command=0x03, bodyLength=50)
        byte[] idsProtobuf = new byte[55];
        idsProtobuf[0] = (byte) IdsSocketPairCodec.COMMAND_PROTOBUF;
        idsProtobuf[1] = 0;
        idsProtobuf[2] = 0;
        idsProtobuf[3] = 0;
        idsProtobuf[4] = 50;
        assertTrue(IdsServiceConnectorCoordinator.isRawIdsSocketPairFrame(idsProtobuf));

        // Genuine IDS Socket Pair HANDSHAKE frame (command=0x04, bodyLength=4)
        byte[] idsHandshake = new byte[9];
        idsHandshake[0] = (byte) IdsSocketPairCodec.COMMAND_HANDSHAKE;
        idsHandshake[1] = 0;
        idsHandshake[2] = 0;
        idsHandshake[3] = 0;
        idsHandshake[4] = 4;
        assertTrue(IdsServiceConnectorCoordinator.isRawIdsSocketPairFrame(idsHandshake));
    }

    private static PumpResult pump(
            Side source,
            Side target,
            List<IdsServiceConnectorCoordinator.RoutedPacket> packets) {
        List<Transit> initial =
                new ArrayList<>(
                        packets.size());
        try {
            for (IdsServiceConnectorCoordinator.RoutedPacket packet :
                    packets) {
                initial.add(
                        transit(
                                source,
                                target,
                                packet));
            }
            return pumpTransits(
                    initial,
                    false);
        } finally {
            for (Transit transit : initial) {
                transit.close();
            }
            closePackets(
                    packets);
        }
    }

    private static PumpResult pumpTransits(
            List<Transit> initial) {
        return pumpTransits(
                initial,
                false);
    }

    private static PumpResult pumpTransits(
            List<Transit> initial,
            boolean dropStaleCollisionPackets) {
        Deque<Transit> queue =
                new ArrayDeque<>();
        for (Transit transit : initial) {
            queue.addLast(
                    transit.copy());
            transit.close();
        }
        List<ObservedEvent> events =
                new ArrayList<>();
        List<OrdinaryIkeAuth.DataClass> dataClasses =
                new ArrayList<>();
        int steps = 0;
        try {
            while (!queue.isEmpty()) {
                if (++steps > 512) {
                    throw new IllegalStateException(
                            "IDS coordinator packet pump did not quiesce");
                }
                Transit transit =
                        queue.removeFirst();
                dataClasses.add(
                        transit.dataClass);
                try (IdsServiceConnectorCoordinator.AcceptResult result =
                             acceptOrDropStale(
                                     transit,
                                     dropStaleCollisionPackets)) {
                    if (result == null) {
                        continue;
                    }
                    for (IdsServiceConnectorCoordinator.ConnectionEvent event :
                            result.events()) {
                        events.add(
                                new ObservedEvent(
                                        transit.target.name,
                                        event));
                    }
                    List<IdsServiceConnectorCoordinator.RoutedPacket>
                            outbound =
                            result.outboundPackets();
                    try {
                        for (IdsServiceConnectorCoordinator.RoutedPacket packet :
                                outbound) {
                            queue.addLast(
                                    transit(
                                            transit.target,
                                            transit.source,
                                            packet));
                        }
                    } finally {
                        closePackets(
                                outbound);
                    }
                } finally {
                    transit.close();
                }
            }
            return new PumpResult(
                    events,
                    dataClasses);
        } catch (RuntimeException failure) {
            closeObserved(
                    events);
            throw failure;
        } finally {
            while (!queue.isEmpty()) {
                queue.removeFirst()
                        .close();
            }
        }
    }

    private static IdsServiceConnectorCoordinator.AcceptResult
            acceptOrDropStale(
                    Transit transit,
                    boolean dropStaleCollisionPackets) {
        try {
            return transit.target.coordinator.accept(
                    transit.dataClass,
                    transit.packet);
        } catch (IllegalArgumentException failure) {
            boolean stale =
                    "Inbound IDS TCP SYN targets no listener"
                            .equals(
                                    failure.getMessage())
                            || "Unknown IDS TCP tuple did not start with a SYN"
                            .equals(
                                    failure.getMessage());
            if (dropStaleCollisionPackets
                    && stale) {
                return null;
            }
            throw failure;
        }
    }

    private static Transit transit(
            Side source,
            Side target,
            IdsServiceConnectorCoordinator.RoutedPacket packet) {
        byte[] bytes =
                packet.clearIpv6Packet();
        try {
            return new Transit(
                    source,
                    target,
                    packet.dataClass,
                    bytes);
        } finally {
            wipe(
                    bytes);
            packet.close();
        }
    }

    private static Side phone() {
        return new Side(
                "phone",
                new IdsServiceConnectorCoordinator(
                        new SecureRandom(),
                        100,
                        PHONE_D,
                        WATCH_D,
                        PHONE_C,
                        WATCH_C));
    }

    private static Side watch() {
        return new Side(
                "watch",
                new IdsServiceConnectorCoordinator(
                        new SecureRandom(),
                        300,
                        WATCH_D,
                        PHONE_D,
                        WATCH_C,
                        PHONE_C));
    }

    private static IdsIpv6TcpRouter rawPhone() {
        return new IdsIpv6TcpRouter(
                new SecureRandom(),
                PHONE_D,
                WATCH_D,
                PHONE_C,
                WATCH_C);
    }

    private static byte[] address(
            int suffix) {
        byte[] value =
                new byte[16];
        value[0] =
                (byte) 0xfd;
        value[1] =
                0x74;
        value[2] =
                0x65;
        value[3] =
                0x72;
        value[4] =
                0x6d;
        value[5] =
                0x6e;
        value[6] =
                0x75;
        value[7] =
                0x73;
        value[15] =
                (byte) suffix;
        return value;
    }

    private static byte[] copySynToPort(
            List<IdsServiceConnectorCoordinator.RoutedPacket> packets,
            int destinationPort) {
        for (IdsServiceConnectorCoordinator.RoutedPacket packet : packets) {
            Ipv6TcpPacketCodec.Packet decoded =
                    Ipv6TcpPacketCodec.decode(
                            packet.clearIpv6Packet());
            try {
                if (decoded.hasFlag(
                        Ipv6TcpPacketCodec.FLAG_SYN)
                        && decoded.destinationPort == destinationPort) {
                    return packet.clearIpv6Packet();
                }
            } finally {
                decoded.close();
            }
        }
        return null;
    }

    private static int countCoordinatorEvents(
            IdsServiceConnectorCoordinator.AcceptResult result,
            IdsServiceConnectorCoordinator.EventType type) {
        int count = 0;
        for (IdsServiceConnectorCoordinator.ConnectionEvent event :
                result.events()) {
            if (event.type == type) {
                count++;
            }
        }
        return count;
    }

    private static byte[] classDPrivateKey(
            byte[] address) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            digest.update(
                    "IDS-CLASS-D".getBytes(StandardCharsets.UTF_8));
            digest.update(address);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void closePackets(
            List<IdsServiceConnectorCoordinator.RoutedPacket> packets) {
        for (IdsServiceConnectorCoordinator.RoutedPacket packet : packets) {
            packet.close();
        }
    }

    private static void closeObserved(
            List<ObservedEvent> events) {
        for (ObservedEvent event : events) {
            event.close();
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

    private static void wipeAll(
            List<byte[]> values) {
        for (byte[] value : values) {
            wipe(
                    value);
        }
        values.clear();
    }

    private static final class Side
            implements AutoCloseable {
        final String name;
        final IdsServiceConnectorCoordinator coordinator;

        private Side(
                String name,
                IdsServiceConnectorCoordinator coordinator) {
            this.name = name;
            this.coordinator =
                    coordinator;
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static final class Transit
            implements AutoCloseable {
        final Side source;
        final Side target;
        final OrdinaryIkeAuth.DataClass dataClass;
        byte[] packet;
        boolean closed;

        private Transit(
                Side source,
                Side target,
                OrdinaryIkeAuth.DataClass dataClass,
                byte[] packet) {
            this.source = source;
            this.target = target;
            this.dataClass =
                    dataClass;
            this.packet =
                    packet.clone();
        }

        private Transit copy() {
            return new Transit(
                    source,
                    target,
                    dataClass,
                    packet);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    packet);
            packet =
                    new byte[0];
        }
    }

    private static final class ObservedEvent
            implements AutoCloseable {
        final String owner;
        final IdsServiceConnectorCoordinator.EventType type;
        final long connectionId;
        final long relatedConnectionId;
        final String serviceName;
        final IdsIpsecServiceRoute route;
        byte[] applicationBytes;

        private ObservedEvent(
                String owner,
                IdsServiceConnectorCoordinator.ConnectionEvent event) {
            this.owner = owner;
            type =
                    event.type;
            connectionId =
                    event.connectionId;
            relatedConnectionId =
                    event.relatedConnectionId;
            serviceName =
                    event.serviceName;
            route =
                    event.route;
            applicationBytes =
                    event.applicationBytes();
        }

        @Override
        public void close() {
            wipe(
                    applicationBytes);
            applicationBytes =
                    new byte[0];
        }
    }

    private static final class PumpResult
            implements AutoCloseable {
        final List<ObservedEvent> events;
        final List<OrdinaryIkeAuth.DataClass> dataClasses;

        private PumpResult(
                List<ObservedEvent> events,
                List<OrdinaryIkeAuth.DataClass> dataClasses) {
            this.events =
                    new ArrayList<>(
                            events);
            this.dataClasses =
                    List.copyOf(
                            dataClasses);
        }

        int count(
                String owner,
                IdsServiceConnectorCoordinator.EventType type) {
            int count = 0;
            for (ObservedEvent event : events) {
                if ((owner == null
                        || owner.equals(
                                event.owner))
                        && type == event.type) {
                    count++;
                }
            }
            return count;
        }

        ObservedEvent first(
                String owner,
                IdsServiceConnectorCoordinator.EventType type) {
            for (ObservedEvent event : events) {
                if ((owner == null
                        || owner.equals(
                                event.owner))
                        && type == event.type) {
                    return event;
                }
            }
            throw new AssertionError(
                    "Missing event "
                            + owner
                            + "/"
                            + type);
        }

        boolean onlyDataClass(
                OrdinaryIkeAuth.DataClass dataClass) {
            assertFalse(
                    dataClasses.isEmpty());
            for (OrdinaryIkeAuth.DataClass seen : dataClasses) {
                if (seen != dataClass) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void close() {
            closeObserved(
                    events);
        }
    }
}
