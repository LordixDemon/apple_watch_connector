package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class IdsIpv6TcpRouterTest {
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
    public void retransmitAcrossBusyLanesIsBoundedAndDoesNotStarveLaterConnections() {
        try (IdsIpv6TcpRouter phone = phoneRouter()) {
            for (int i = 0; i < 6; i++) {
                try (IdsIpv6TcpRouter.ActiveOpen open = phone.openActive(
                        OrdinaryIkeAuth.DataClass.CLASS_D, 49160 + i,
                        IdsControlChannelCodec.CONTROL_PORT, new byte[]{1})) {
                    // Lose every SYN; all six lanes need timer recovery.
                    assertTrue(open.connectionId > 0);
                }
            }
            java.util.Set<Integer> recoveredPorts = new java.util.HashSet<>();
            for (int pass = 0; pass < 2; pass++) {
                List<IdsIpv6TcpRouter.PacketBatch> batches = phone.retransmitAllOutstanding();
                try {
                    assertEquals(4, batches.size());
                    for (IdsIpv6TcpRouter.PacketBatch batch : batches) {
                        List<byte[]> packets = batch.packets();
                        try {
                            assertEquals(1, packets.size());
                            try (Ipv6TcpPacketCodec.Packet packet =
                                         Ipv6TcpPacketCodec.decode(packets.get(0))) {
                                recoveredPorts.add(packet.sourcePort);
                                assertTrue(packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN));
                            }
                        } finally {
                            for (byte[] packet : packets) Arrays.fill(packet, (byte) 0);
                        }
                    }
                } finally {
                    for (IdsIpv6TcpRouter.PacketBatch batch : batches) batch.close();
                }
            }
            assertEquals(6, recoveredPorts.size());
        }
    }

    @Test
    public void passiveFastOpenRoutesNwscAndLaterStreamBytes() {
        try (IdsIpv6TcpRouter phone =
                     phoneRouter();
             IdsIpv6TcpRouter watch =
                     watchRouter()) {
            watch.listen(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    IdsControlChannelCodec.CONTROL_PORT);
            byte[] nwsc =
                    new byte[] {
                            1,
                            2,
                            3,
                            4
                    };
            long phoneId;
            long watchId;
            byte[] syn = null;
            byte[] synAck = null;
            byte[] finalAck = null;
            try (IdsIpv6TcpRouter.ActiveOpen active =
                         phone.openActive(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 49160,
                                 IdsControlChannelCodec.CONTROL_PORT,
                                 nwsc)) {
                phoneId =
                        active.connectionId;
                syn =
                        active.packet();

                try (IdsIpv6TcpRouter.InboundResult atWatch =
                             watch.accept(
                                     OrdinaryIkeAuth.DataClass.CLASS_D,
                                     syn)) {
                    assertTrue(
                            atWatch.events()
                                    .isEmpty());
                    List<byte[]> packets =
                            atWatch.outboundPackets();
                    try {
                        assertEquals(
                                1,
                                packets.size());
                        synAck =
                                packets.get(
                                        0).clone();
                    } finally {
                        wipeAll(
                                packets);
                    }
                }

                try (IdsIpv6TcpRouter.InboundResult atPhone =
                             phone.accept(
                                     OrdinaryIkeAuth.DataClass.CLASS_D,
                                     synAck)) {
                    assertEquals(
                            1,
                            atPhone.events()
                                    .size());
                    IdsIpv6TcpRouter.StreamEvent event =
                            atPhone.events()
                                    .get(
                                            0);
                    assertEquals(
                            phoneId,
                            event.connectionId);
                    assertTrue(
                            event.becameEstablished);
                    assertTrue(
                            event.deliveredBytes()
                                    .isEmpty());
                    List<byte[]> packets =
                            atPhone.outboundPackets();
                    try {
                        assertEquals(
                                1,
                                packets.size());
                        finalAck =
                                packets.get(
                                        0).clone();
                    } finally {
                        wipeAll(
                                packets);
                    }
                }

                try (IdsIpv6TcpRouter.InboundResult atWatch =
                             watch.accept(
                                     OrdinaryIkeAuth.DataClass.CLASS_D,
                                     finalAck)) {
                    assertEquals(
                            1,
                            atWatch.events()
                                    .size());
                    IdsIpv6TcpRouter.StreamEvent event =
                            atWatch.events()
                                    .get(
                                            0);
                    watchId =
                            event.connectionId;
                    assertTrue(
                            event.becameEstablished);
                    List<byte[]> delivered =
                            event.deliveredBytes();
                    try {
                        assertEquals(
                                1,
                                delivered.size());
                        assertArrayEquals(
                                nwsc,
                                delivered.get(
                                        0));
                    } finally {
                        wipeAll(
                                delivered);
                    }
                }

                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        phone.connection(
                                        phoneId)
                                .state);
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        watch.connection(
                                        watchId)
                                .state);

                byte[] application =
                        new byte[] {
                                9,
                                8,
                                7
                        };
                try (IdsIpv6TcpRouter.PacketBatch sent =
                             phone.send(
                                     phoneId,
                                     application)) {
                    assertEquals(
                            OrdinaryIkeAuth.DataClass.CLASS_D,
                            sent.dataClass);
                    List<byte[]> packets =
                            sent.packets();
                    try {
                        assertEquals(
                                1,
                                packets.size());
                        try (IdsIpv6TcpRouter.InboundResult atWatch =
                                     watch.accept(
                                             OrdinaryIkeAuth
                                                     .DataClass.CLASS_D,
                                             packets.get(
                                                     0))) {
                            List<IdsIpv6TcpRouter.StreamEvent> events =
                                    atWatch.events();
                            assertEquals(
                                    1,
                                    events.size());
                            assertFalse(
                                    events.get(
                                            0).becameEstablished);
                            List<byte[]> delivered =
                                    events.get(
                                                    0)
                                            .deliveredBytes();
                            try {
                                assertEquals(
                                        1,
                                        delivered.size());
                                assertArrayEquals(
                                        application,
                                        delivered.get(
                                                0));
                            } finally {
                                wipeAll(
                                        delivered);
                            }
                            acknowledgeAll(
                                    phone,
                                    atWatch,
                                    OrdinaryIkeAuth
                                            .DataClass.CLASS_D);
                        }
                    } finally {
                        wipeAll(
                                packets);
                    }
                } finally {
                    wipe(
                            application);
                }
            } finally {
                wipe(
                        nwsc);
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
    public void samePortListenersRemainSeparatedByProtectionClass() {
        try (IdsIpv6TcpRouter phone =
                     phoneRouter();
             IdsIpv6TcpRouter watch =
                     watchRouter()) {
            watch.listen(
                    OrdinaryIkeAuth.DataClass.CLASS_C,
                    IdsControlChannelCodec.DATA_PORT);
            assertEquals(
                    1,
                    watch.listenerCount());

            byte[] syn = null;
            try (IdsIpv6TcpRouter.ActiveOpen active =
                         phone.openActive(
                                 OrdinaryIkeAuth.DataClass.CLASS_C,
                                 49161,
                                 IdsControlChannelCodec.DATA_PORT,
                                 new byte[] {
                                         0x55
                                 })) {
                syn =
                        active.packet();
                // Wrong protection class: no connection opens, but the peer
                // gets an RST so it does not retransmit the flow forever.
                try (IdsIpv6TcpRouter.InboundResult rejected =
                             watch.accept(
                                     OrdinaryIkeAuth.DataClass.CLASS_D,
                                     syn)) {
                    assertEquals(
                            1,
                            rejected.outboundPackets().size());
                    Ipv6TcpPacketCodec.Packet reset =
                            Ipv6TcpPacketCodec.decode(
                                    rejected.outboundPackets().get(0));
                    try {
                        assertTrue(
                                reset.hasFlag(
                                        Ipv6TcpPacketCodec.FLAG_RST));
                    } finally {
                        reset.close();
                    }
                }
                assertEquals(
                        0,
                        watch.connectionCount());
                try (IdsIpv6TcpRouter.InboundResult accepted =
                             watch.accept(
                                     OrdinaryIkeAuth.DataClass.CLASS_C,
                                     syn)) {
                    assertEquals(
                            OrdinaryIkeAuth.DataClass.CLASS_C,
                            accepted.dataClass);
                    assertEquals(
                            1,
                            watch.connectionCount());
                }
            } finally {
                wipe(
                        syn);
            }
        }
    }

    @Test
    public void listenerAndTupleValidationFailClosed() {
        try (IdsIpv6TcpRouter phone =
                     phoneRouter();
             IdsIpv6TcpRouter watch =
                     watchRouter()) {
            byte[] syn = null;
            try (IdsIpv6TcpRouter.ActiveOpen active =
                         phone.openActive(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 49162,
                                 IdsControlChannelCodec.CONTROL_PORT,
                                 new byte[0])) {
                syn =
                        active.packet();
                // No listener yet: still fail-closed (no connection), but
                // the peer receives an RST instead of silence.
                try (IdsIpv6TcpRouter.InboundResult rejected =
                             watch.accept(
                                     OrdinaryIkeAuth.DataClass.CLASS_D,
                                     syn)) {
                    assertEquals(
                            1,
                            rejected.outboundPackets().size());
                    Ipv6TcpPacketCodec.Packet reset =
                            Ipv6TcpPacketCodec.decode(
                                    rejected.outboundPackets().get(0));
                    try {
                        assertTrue(
                                reset.hasFlag(
                                        Ipv6TcpPacketCodec.FLAG_RST));
                    } finally {
                        reset.close();
                    }
                }
                assertEquals(
                        0,
                        watch.connectionCount());
            } finally {
                wipe(
                        syn);
            }

            assertThrows(
                    IllegalArgumentException.class,
                    () -> watch.listen(
                            OrdinaryIkeAuth.DataClass.CLASS_D,
                            12345));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> watch.openActive(
                            OrdinaryIkeAuth.DataClass.CLASS_C,
                            49163,
                            12345,
                            new byte[0]));

            watch.listen(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    IdsControlChannelCodec.CONTROL_PORT);
            byte[] unknownAck =
                    Ipv6TcpPacketCodec.encode(
                            PHONE_D,
                            WATCH_D,
                            49164,
                            IdsControlChannelCodec.CONTROL_PORT,
                            1,
                            1,
                            Ipv6TcpPacketCodec.FLAG_ACK,
                            Ipv6TcpStream.RECEIVE_WINDOW,
                            0,
                            new byte[0],
                            new byte[0]);
            try (IdsIpv6TcpRouter.InboundResult reset =
                         watch.accept(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 unknownAck)) {
                assertEquals(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        reset.dataClass);
                assertEquals(
                        0,
                        reset.outboundPackets().size());
            } finally {
                wipe(
                        unknownAck);
            }
        }
    }

    @Test
    public void unknownEstablishedStreamCannotInventTcpOrAlloyContinuity() {
        try (IdsIpv6TcpRouter watch = watchRouter()) {
            watch.listen(OrdinaryIkeAuth.DataClass.CLASS_D, 61314);
            byte[] incoming = Ipv6TcpPacketCodec.encode(PHONE_D, WATCH_D, 61314, 61314,
                    100, 200, Ipv6TcpPacketCodec.FLAG_ACK, Ipv6TcpStream.RECEIVE_WINDOW,
                    0, new byte[0], new byte[] {1, 2, 3, 4, 5});
            try (var result = watch.accept(OrdinaryIkeAuth.DataClass.CLASS_D, incoming)) {
                assertTrue(result.events().isEmpty());
                assertEquals(0, result.outboundPackets().size());
            } finally { wipe(incoming); }
            assertEquals(0, watch.connectionCount());
        }
    }

    @Test
    public void duplicateActiveTupleAndRemovedHandleAreRejected() {
        try (IdsIpv6TcpRouter phone =
                     phoneRouter()) {
            try (IdsIpv6TcpRouter.ActiveOpen first =
                         phone.openActive(
                                 OrdinaryIkeAuth.DataClass.CLASS_D,
                                 49165,
                                 IdsControlChannelCodec.CONTROL_PORT,
                                 new byte[0])) {
                assertThrows(
                        IllegalStateException.class,
                        () -> phone.openActive(
                                OrdinaryIkeAuth.DataClass.CLASS_D,
                                49165,
                                IdsControlChannelCodec.CONTROL_PORT,
                                new byte[0]));
                phone.removeConnection(
                        first.connectionId);
                assertEquals(
                        0,
                        phone.connectionCount());
                assertThrows(
                        IllegalArgumentException.class,
                        () -> phone.connection(
                                first.connectionId));
            }
        }
    }

    @Test
    public void classifierSeparatesIdsPortsFromOtherNormalLinkTraffic() {
        try (IdsIpv6TcpRouter watch =
                     watchRouter()) {
            watch.listen(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    IdsControlChannelCodec.CONTROL_PORT);
            byte[] idsSyn =
                    Ipv6TcpPacketCodec.encode(
                            PHONE_D,
                            WATCH_D,
                            49170,
                            IdsControlChannelCodec.CONTROL_PORT,
                            1,
                            0,
                            Ipv6TcpPacketCodec.FLAG_SYN,
                            Ipv6TcpStream.RECEIVE_WINDOW,
                            0,
                            new byte[0],
                            new byte[0]);
            byte[] otherTcp =
                    Ipv6TcpPacketCodec.encode(
                            PHONE_D,
                            WATCH_D,
                            49171,
                            49172,
                            1,
                            0,
                            Ipv6TcpPacketCodec.FLAG_SYN,
                            Ipv6TcpStream.RECEIVE_WINDOW,
                            0,
                            new byte[0],
                            new byte[0]);
            byte[] otherProtocol =
                    new byte[44];
            otherProtocol[0] = 0x60;
            otherProtocol[5] = 4;
            otherProtocol[6] = 17;
            otherProtocol[7] = 64;
            System.arraycopy(
                    PHONE_D,
                    0,
                    otherProtocol,
                    8,
                    16);
            System.arraycopy(
                    WATCH_D,
                    0,
                    otherProtocol,
                    24,
                    16);
            try {
                assertTrue(
                        watch.recognizesInbound(
                                OrdinaryIkeAuth.DataClass.CLASS_D,
                                idsSyn));
                assertFalse(
                        watch.recognizesInbound(
                                OrdinaryIkeAuth.DataClass.CLASS_D,
                                otherTcp));
                assertFalse(
                        watch.recognizesInbound(
                                OrdinaryIkeAuth.DataClass.CLASS_D,
                                otherProtocol));
                assertFalse(
                        watch.recognizesInbound(
                                OrdinaryIkeAuth.DataClass.CLASS_C,
                                idsSyn));
                assertEquals(
                        0,
                        watch.connectionCount());
            } finally {
                wipe(
                        idsSyn);
                wipe(
                        otherTcp);
                wipe(
                        otherProtocol);
            }
        }
    }

    @Test
    public void inboundFinPayloadToUnownedEphemeralDoesNotOpenConnection() {
        try (IdsIpv6TcpRouter phone =
                     phoneRouter()) {
            phone.listen(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    IdsControlChannelCodec.DATA_PORT);
            byte[] payload =
                    new byte[88];
            Arrays.fill(
                    payload,
                    (byte) 7);
            byte[] stale =
                    Ipv6TcpPacketCodec.encode(
                            WATCH_D,
                            PHONE_D,
                            IdsControlChannelCodec.CONTROL_PORT,
                            1041,
                            1L,
                            1L,
                            Ipv6TcpPacketCodec.FLAG_ACK
                                    | Ipv6TcpPacketCodec.FLAG_FIN
                                    | Ipv6TcpPacketCodec.FLAG_PSH,
                            Ipv6TcpStream.RECEIVE_WINDOW,
                            0,
                            new byte[0],
                            payload);
            try {
                assertFalse(
                        phone.recognizesInbound(
                                OrdinaryIkeAuth.DataClass.CLASS_D,
                                stale));
                IdsIpv6TcpRouter.InboundResult result =
                        phone.accept(
                                OrdinaryIkeAuth.DataClass.CLASS_D,
                                stale);
                try (result) {
                    // Unknown tuples are answered with RST (RFC 793) so the
                    // Watch tears down its stale flow instead of
                    // retransmitting it for minutes and rejecting our fresh
                    // NWSC SYN by policy (live 0.2.171).
                    assertEquals(
                            1,
                            result.outboundPackets().size());
                    Ipv6TcpPacketCodec.Packet reset =
                            Ipv6TcpPacketCodec.decode(
                                    result.outboundPackets().get(0));
                    try {
                        assertTrue(
                                reset.hasFlag(
                                        Ipv6TcpPacketCodec.FLAG_RST));
                        assertFalse(
                                reset.hasFlag(
                                        Ipv6TcpPacketCodec.FLAG_SYN));
                    } finally {
                        reset.close();
                    }
                }
                assertEquals(
                        0,
                        phone.connectionCount());
            } finally {
                wipe(
                        stale);
                wipe(
                        payload);
            }
        }
    }

    private static void acknowledgeAll(
            IdsIpv6TcpRouter receiver,
            IdsIpv6TcpRouter.InboundResult result,
            OrdinaryIkeAuth.DataClass dataClass) {
        List<byte[]> acknowledgements =
                result.outboundPackets();
        try {
            for (byte[] acknowledgement : acknowledgements) {
                try (IdsIpv6TcpRouter.InboundResult ignored =
                             receiver.accept(
                                     dataClass,
                                     acknowledgement)) {
                    assertTrue(
                            ignored.events()
                                    .isEmpty());
                }
            }
        } finally {
            wipeAll(
                    acknowledgements);
        }
    }

    @Test
    public void staleControlResetUsesStoredQuartetWhenIkeAssignmentFlipped() {
        // Live 0.2.304: stale control retransmits are answered with a valid
        // reset on the stored quartet (seq == Watch RCV.NXT per RFC 5961) so
        // the Watch aborts the dead flow and opens the new control connector
        // without waiting out its ~8.6-min RTO.
        byte[] storedLocal = address(0x11);
        byte[] storedRemote = address(0x22);
        try (IdsIpv6TcpRouter watch = watchRouter()) {
            watch.listen(OrdinaryIkeAuth.DataClass.CLASS_C, 61315);
            watch.setStaleFlowAddresses(storedLocal, storedRemote);
            byte[] incoming = Ipv6TcpPacketCodec.encode(
                    PHONE_C, WATCH_C, 49152, 61315,
                    100, 200, Ipv6TcpPacketCodec.FLAG_ACK | Ipv6TcpPacketCodec.FLAG_PSH,
                    Ipv6TcpStream.RECEIVE_WINDOW,
                    0, new byte[0], new byte[] {1, 2, 3});
            try (var result = watch.accept(OrdinaryIkeAuth.DataClass.CLASS_C, incoming)) {
                assertEquals(OrdinaryIkeAuth.DataClass.CLASS_C, result.dataClass);
                assertEquals(1, result.outboundPackets().size());
                try (var rst = Ipv6TcpPacketCodec.decode(result.outboundPackets().get(0))) {
                    assertArrayEquals(storedLocal, rst.sourceAddress);
                    assertArrayEquals(storedRemote, rst.destinationAddress);
                    assertEquals(200, rst.sequence);
                    assertTrue(rst.hasFlag(Ipv6TcpPacketCodec.FLAG_RST));
                    assertFalse(rst.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK));
                }
            } finally {
                wipe(incoming);
            }
        } finally {
            wipe(storedLocal);
            wipe(storedRemote);
        }
    }

    @Test
    public void staleDataResetUsesStoredQuartetAndEchoesTimestamps() {
        // Live 0.2.304: leftover data-flow retransmits get the same stored-
        // quartet reset on the inbound packet's own data class, carrying the
        // timestamp echo when the peer negotiated TS (Watch PAWS checks).
        byte[] storedLocal = address(0x11);
        byte[] storedRemote = address(0x22);
        byte[] timestamps = new byte[12];
        timestamps[0] = 1;
        timestamps[1] = 1;
        timestamps[2] = 8;
        timestamps[3] = 10;
        timestamps[4] = 0x11;
        timestamps[5] = 0x22;
        timestamps[6] = 0x33;
        timestamps[7] = 0x44;
        try (IdsIpv6TcpRouter watch = watchRouter()) {
            watch.listen(OrdinaryIkeAuth.DataClass.CLASS_D, 61314);
            watch.setStaleFlowAddresses(storedLocal, storedRemote);
            byte[] incoming = Ipv6TcpPacketCodec.encode(
                    PHONE_D, WATCH_D, 49156, 61314,
                    100, 200, Ipv6TcpPacketCodec.FLAG_ACK, Ipv6TcpStream.RECEIVE_WINDOW,
                    0, timestamps, new byte[] {9});
            try (var result = watch.accept(OrdinaryIkeAuth.DataClass.CLASS_D, incoming)) {
                assertEquals(OrdinaryIkeAuth.DataClass.CLASS_D, result.dataClass);
                assertEquals(1, result.outboundPackets().size());
                try (var rst = Ipv6TcpPacketCodec.decode(result.outboundPackets().get(0))) {
                    assertArrayEquals(storedLocal, rst.sourceAddress);
                    assertArrayEquals(storedRemote, rst.destinationAddress);
                    assertEquals(200, rst.sequence);
                    assertTrue(rst.hasFlag(Ipv6TcpPacketCodec.FLAG_RST));
                    byte[] options = rst.options;
                    assertEquals(12, options.length);
                    assertEquals(8, options[2] & 0xff);
                    long echo =
                            ((options[8] & 0xffL) << 24)
                                    | ((options[9] & 0xffL) << 16)
                                    | ((options[10] & 0xffL) << 8)
                                    | (options[11] & 0xffL);
                    assertEquals(0x11223344L, echo);
                }
            } finally {
                wipe(incoming);
                wipe(timestamps);
            }
        } finally {
            wipe(storedLocal);
            wipe(storedRemote);
        }
    }

    @Test
    public void proactiveResetKillsSeededFlowWithExtrapolatedTimestamp() {
        // watchOS 26.2 identityservicesd keeps its control connecting flag
        // while the old flow looks ESTABLISHED; the proactive reset (seq ==
        // Watch RCV.NXT per RFC 5961) kills it so FUN_10035caa4 retries the
        // ids-control-channel connector in one RTT instead of ~8.6 min.
        long seenAt = System.currentTimeMillis() - 60_000L;
        IdsStaleFlowRecord seed =
                new IdsStaleFlowRecord(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        PHONE_D,
                        WATCH_D,
                        61315,
                        49173,
                        3095449250L,
                        0x11223344L,
                        seenAt);
        try (IdsIpv6TcpRouter phone = phoneRouter()) {
            phone.seedStaleFlows(List.of(seed));
            List<IdsIpv6TcpRouter.ProactiveReset> resets =
                    phone.buildProactiveStaleResets();
            assertEquals(1, resets.size());
            assertEquals(OrdinaryIkeAuth.DataClass.CLASS_D, resets.get(0).dataClass);
            try (var rst = Ipv6TcpPacketCodec.decode(resets.get(0).packet)) {
                assertArrayEquals(PHONE_D, rst.sourceAddress);
                assertArrayEquals(WATCH_D, rst.destinationAddress);
                assertEquals(61315, rst.sourcePort);
                assertEquals(49173, rst.destinationPort);
                assertEquals(3095449250L, rst.sequence);
                assertTrue(rst.hasFlag(Ipv6TcpPacketCodec.FLAG_RST));
                assertFalse(rst.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK));
                byte[] options = rst.options;
                assertEquals(12, options.length);
                assertEquals(8, options[2] & 0xff);
                long echo =
                        ((options[8] & 0xffL) << 24)
                                | ((options[9] & 0xffL) << 16)
                                | ((options[10] & 0xffL) << 8)
                                | (options[11] & 0xffL);
                // Echo extrapolated at the peer's ~1000 Hz tick: at least
                // the seeded value plus the elapsed minute in ms.
                assertTrue(echo >= 0x11223344L + 59_000L);
            } finally {
                for (IdsIpv6TcpRouter.ProactiveReset reset : resets) {
                    wipe(reset.packet);
                }
            }
        }
    }

    @Test
    public void proactiveResetSkipsFreshRecordsAndListsObservations() {
        long now = System.currentTimeMillis();
        IdsStaleFlowRecord fresh =
                new IdsStaleFlowRecord(
                        OrdinaryIkeAuth.DataClass.CLASS_C,
                        PHONE_C,
                        WATCH_C,
                        61314,
                        49154,
                        2774809047L,
                        -1L,
                        now);
        IdsStaleFlowRecord old =
                new IdsStaleFlowRecord(
                        OrdinaryIkeAuth.DataClass.CLASS_C,
                        PHONE_C,
                        WATCH_C,
                        61314,
                        49155,
                        4214650874L,
                        -1L,
                        now - 30_000L);
        try (IdsIpv6TcpRouter phone = phoneRouter()) {
            phone.seedStaleFlows(List.of(fresh, old));
            assertEquals(2, phone.staleFlowObservations().size());
            List<IdsIpv6TcpRouter.ProactiveReset> resets =
                    phone.buildProactiveStaleResets();
            assertEquals(1, resets.size());
            try (var rst = Ipv6TcpPacketCodec.decode(resets.get(0).packet)) {
                assertEquals(49155, rst.destinationPort);
                assertEquals(4214650874L, rst.sequence);
                assertTrue(rst.hasFlag(Ipv6TcpPacketCodec.FLAG_RST));
                // No TS negotiated on this flow: no timestamp option.
                assertEquals(0, rst.options.length);
            } finally {
                for (IdsIpv6TcpRouter.ProactiveReset reset : resets) {
                    wipe(reset.packet);
                }
            }
        }
    }

    private static IdsIpv6TcpRouter phoneRouter() {
        return new IdsIpv6TcpRouter(
                new SecureRandom(),
                PHONE_D,
                WATCH_D,
                PHONE_C,
                WATCH_C);
    }

    private static IdsIpv6TcpRouter watchRouter() {
        return new IdsIpv6TcpRouter(
                new SecureRandom(),
                WATCH_D,
                PHONE_D,
                WATCH_C,
                PHONE_C);
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
}
