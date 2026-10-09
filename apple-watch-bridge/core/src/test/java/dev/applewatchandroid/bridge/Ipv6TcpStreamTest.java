package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Ipv6TcpStreamTest {
    @Test
    public void zeroAndSmallPeerWindowsQueueAndDrainInOrderAcrossSequenceWrap() {
        try (Ipv6TcpStream stream = Ipv6TcpStream.passiveEstablished(
                ANDROID, WATCH, 49152, 61315, 0xfffffffeL, 7000)) {
            byte[] windowClosed = windowAck(stream, 0);
            try (var ignored = stream.accept(windowClosed)) { }
            assertTrue(stream.send(new byte[]{1, 2, 3, 4, 5}).isEmpty());
            assertTrue(stream.send(new byte[]{6, 7, 8}).isEmpty());
            assertEquals(8, stream.unacknowledgedSendBytes());
            java.io.ByteArrayOutputStream delivered = new java.io.ByteArrayOutputStream();
            for (int remaining : new int[]{8, 5, 2, 0}) {
                byte[] ack = windowAck(stream, 3);
                try (var result = stream.accept(ack)) {
                    for (byte[] packet : result.outboundPackets()) {
                        try (var decoded = Ipv6TcpPacketCodec.decode(packet)) {
                            assertTrue(decoded.payload.length <= 3);
                            delivered.writeBytes(decoded.payload);
                        } finally { Arrays.fill(packet, (byte) 0); }
                    }
                    assertEquals(remaining, stream.unacknowledgedSendBytes());
                } finally { Arrays.fill(ack, (byte) 0); }
            }
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, delivered.toByteArray());
            assertEquals(6, stream.sendNext());
        }
    }

    private static byte[] windowAck(Ipv6TcpStream stream, int window) {
        return Ipv6TcpPacketCodec.encode(WATCH, ANDROID, 61315, 49152,
                7000, stream.sendNext(), Ipv6TcpPacketCodec.FLAG_ACK, window, 0,
                new byte[0], new byte[0]);
    }

    @Test
    public void retransmissionRefreshesTimestampEchoWithoutChangingApplicationBytes() {
        try (Ipv6TcpStream stream = Ipv6TcpStream.passiveEstablished(
                ANDROID, WATCH, 49152, 61315, 1000, 7000)) {
            stream.setPayloadFastRetransmitEnabled(false);
            byte[] original = null;
            for (int peerStamp : new int[]{11, 22}) {
                byte[] options = Ipv6TcpPacketCodec.encodeTimestampOption(0);
                Arrays.fill(options, 4, 8, (byte) 0);
                options[7] = (byte) peerStamp;
                byte[] ack = Ipv6TcpPacketCodec.encode(WATCH, ANDROID, 61315, 49152,
                        7000, 1000, Ipv6TcpPacketCodec.FLAG_ACK, 4096, 0, options, new byte[0]);
                try (var ignored = stream.accept(ack)) { }
                if (peerStamp == 11) original = stream.send(new byte[]{1, 2, 3}).get(0);
                Arrays.fill(ack, (byte) 0);
                Arrays.fill(options, (byte) 0);
            }
            var retransmissions = new ArrayList<>(stream.retransmitOutstanding());
            try (var before = Ipv6TcpPacketCodec.decode(original);
                 var after = Ipv6TcpPacketCodec.decode(retransmissions.get(0))) {
                assertArrayEquals(new byte[]{0, 0, 0, 11}, Arrays.copyOfRange(before.options, 8, 12));
                assertArrayEquals(new byte[]{0, 0, 0, 22}, Arrays.copyOfRange(after.options, 8, 12));
                assertEquals(before.sequence, after.sequence);
                assertArrayEquals(before.payload, after.payload);
            } finally {
                wipe(original);
                wipeAll(retransmissions);
            }
        }
    }

    private static final byte[] ANDROID =
            hex(
                    "fd 74 65 72 6d 6e 75 73 "
                            + "00 0c 10 11 12 13 14 15");
    private static final byte[] WATCH =
            hex(
                    "fd 74 65 72 6d 6e 75 73 "
                            + "00 0c 20 21 22 23 24 25");

    @Test
    public void activeAndPassiveStreamsHandshakeTransferAndClose() {
        Ipv6TcpStream client =
                Ipv6TcpStream.active(
                        ANDROID,
                        WATCH,
                        49152,
                        61315,
                        0xffff_fff0L);
        Ipv6TcpStream server = null;
        Ipv6TcpStream.PassiveOpen passive = null;
        byte[] syn = null;
        byte[] synAck = null;
        List<byte[]> clientAck = new ArrayList<>();
        byte[] application =
                sequence(
                        0x20,
                        2500);
        List<byte[]> dataPackets = new ArrayList<>();
        List<byte[]> delivered = new ArrayList<>();
        try {
            syn = client.startActiveOpen();
            passive =
                    Ipv6TcpStream.acceptPassive(
                            syn,
                            0x1122_3344L);
            server = passive.takeStream();
            synAck = passive.synAcknowledgement();

            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 synAck)) {
                clientAck.addAll(
                        result.outboundPackets());
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        result.state);
                assertTrue(
                        result.deliveredBytes()
                                .isEmpty());
            }
            assertEquals(
                    1,
                    clientAck.size());
            try (Ipv6TcpStream.InboundResult result =
                         server.accept(
                                 clientAck.get(0))) {
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        result.state);
                assertTrue(
                        result.outboundPackets()
                                .isEmpty());
            }

            dataPackets.addAll(
                    client.send(
                            application));
            assertEquals(
                    3,
                    dataPackets.size());
            for (byte[] packet : dataPackets) {
                List<byte[]> acknowledgements;
                try (Ipv6TcpStream.InboundResult result =
                             server.accept(
                                     packet)) {
                    delivered.addAll(
                            result.deliveredBytes());
                    acknowledgements =
                            result.outboundPackets();
                }
                try {
                    assertEquals(
                            1,
                            acknowledgements.size());
                    try (Ipv6TcpStream.InboundResult ignored =
                                 client.accept(
                                         acknowledgements.get(0))) {
                        assertTrue(
                                ignored.deliveredBytes()
                                        .isEmpty());
                    }
                } finally {
                    wipeAll(
                            acknowledgements);
                }
            }
            assertArrayEquals(
                    application,
                    concatenate(
                            delivered));
            assertTrue(
                    client.retransmitOutstanding()
                            .isEmpty());
            assertEquals(
                    client.sendNext(),
                    client.sendUnacknowledged());
        } finally {
            client.close();
            if (server != null) {
                server.close();
            }
            if (passive != null) {
                passive.close();
            }
            wipe(
                    syn);
            wipe(
                    synAck);
            wipeAll(
                    clientAck);
            wipe(
                    application);
            wipeAll(
                    dataPackets);
            wipeAll(
                    delivered);
        }
    }

    @Test
    public void passiveOpenRetainsFastOpenBytesUntilFinalAck() {
        byte[] fastOpen =
                sequence(
                        0x50,
                        112);
        long watchSequence =
                0x5566_7788L;
        long androidSequence =
                0xaabb_ccddL;
        byte[] syn =
                Ipv6TcpPacketCodec.encode(
                        WATCH,
                        ANDROID,
                        50321,
                        61315,
                        watchSequence,
                        0,
                        Ipv6TcpPacketCodec.FLAG_SYN,
                        0xffff,
                        0,
                        Ipv6TcpPacketCodec
                                .encodeMssOption(
                                        1440),
                        fastOpen);
        Ipv6TcpStream.PassiveOpen passive = null;
        Ipv6TcpStream stream = null;
        byte[] synAck = null;
        byte[] finalAck = null;
        try {
            passive =
                    Ipv6TcpStream.acceptPassive(
                            syn,
                            androidSequence);
            stream = passive.takeStream();
            synAck = passive.synAcknowledgement();
            try (Ipv6TcpPacketCodec.Packet decoded =
                         Ipv6TcpPacketCodec.decode(
                                 synAck)) {
                assertEquals(
                        (watchSequence
                                + 1
                                + fastOpen.length)
                                & 0xffff_ffffL,
                        decoded.acknowledgement);
            }

            finalAck =
                    Ipv6TcpPacketCodec.encode(
                            WATCH,
                            ANDROID,
                            50321,
                            61315,
                            (watchSequence
                                    + 1
                                    + fastOpen.length)
                                    & 0xffff_ffffL,
                            (androidSequence + 1)
                                    & 0xffff_ffffL,
                            Ipv6TcpPacketCodec.FLAG_ACK,
                            0xffff,
                            0,
                            new byte[0],
                            new byte[0]);
            try (Ipv6TcpStream.InboundResult result =
                         stream.accept(
                                 finalAck)) {
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        result.state);
                List<byte[]> delivered =
                        result.deliveredBytes();
                try {
                    assertEquals(
                            1,
                            delivered.size());
                    assertArrayEquals(
                            fastOpen,
                            delivered.get(0));
                } finally {
                    wipeAll(
                            delivered);
                }
            }
        } finally {
            if (stream != null) {
                stream.close();
            }
            if (passive != null) {
                passive.close();
            }
            wipe(
                    fastOpen);
            wipe(
                    syn);
            wipe(
                    synAck);
            wipe(
                    finalAck);
        }
    }

    @Test
    public void activeFastOpenBytesAreAcknowledgedWithTheSyn() {
        byte[] fastOpen =
                sequence(
                        0x31,
                        112);
        Ipv6TcpStream client =
                Ipv6TcpStream.active(
                        ANDROID,
                        WATCH,
                        49154,
                        61315,
                        0xffff_ffd0L);
        Ipv6TcpStream.PassiveOpen passive = null;
        Ipv6TcpStream server = null;
        byte[] syn = null;
        byte[] synAck = null;
        List<byte[]> finalAck =
                new ArrayList<>();
        try {
            syn =
                    client.startActiveOpen(
                            fastOpen);
            try (Ipv6TcpPacketCodec.Packet decoded =
                         Ipv6TcpPacketCodec.decode(
                                 syn)) {
                assertArrayEquals(
                        fastOpen,
                        decoded.payload);
            }
            passive =
                    Ipv6TcpStream.acceptPassive(
                            syn,
                            0x0102_0304L);
            server =
                    passive.takeStream();
            synAck =
                    passive.synAcknowledgement();
            try (Ipv6TcpPacketCodec.Packet decoded =
                         Ipv6TcpPacketCodec.decode(
                                 synAck)) {
                assertEquals(
                        (0xffff_ffd0L
                                + 1
                                + fastOpen.length)
                                & 0xffff_ffffL,
                        decoded.acknowledgement);
            }
            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 synAck)) {
                finalAck.addAll(
                        result.outboundPackets());
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        result.state);
            }
            try (Ipv6TcpStream.InboundResult result =
                         server.accept(
                                 finalAck.get(0))) {
                List<byte[]> delivered =
                        result.deliveredBytes();
                try {
                    assertEquals(
                            1,
                            delivered.size());
                    assertArrayEquals(
                            fastOpen,
                            delivered.get(0));
                } finally {
                    wipeAll(
                            delivered);
                }
            }
        } finally {
            client.close();
            if (server != null) {
                server.close();
            }
            if (passive != null) {
                passive.close();
            }
            wipe(
                    fastOpen);
            wipe(
                    syn);
            wipe(
                    synAck);
            wipeAll(
                    finalAck);
        }
    }

    @Test
    public void activeFastOpenFallsBackToPayloadInEstablishedWhenPeerAcksOnlySyn() {
        byte[] fastOpen =
                sequence(
                        0x42,
                        64);
        Ipv6TcpStream client =
                Ipv6TcpStream.active(
                        ANDROID,
                        WATCH,
                        49155,
                        61315,
                        0x2233_4455L);
        byte[] syn = null;
        byte[] peerSynAckOnlySyn = null;
        List<byte[]> clientDataPackets =
                new ArrayList<>();
        try {
            syn =
                    client.startActiveOpen(
                            fastOpen);
            // Peer acknowledges only initialSendSequence + 1 (the SYN bit)
            peerSynAckOnlySyn =
                    Ipv6TcpPacketCodec.encode(
                            WATCH,
                            ANDROID,
                            61315,
                            49155,
                            0x9988_7766L,
                            0x2233_4456L,
                            Ipv6TcpPacketCodec.FLAG_SYN
                                    | Ipv6TcpPacketCodec.FLAG_ACK,
                            0xffff,
                            0,
                            new byte[0],
                            new byte[0]);

            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 peerSynAckOnlySyn)) {
                clientDataPackets.addAll(
                        result.outboundPackets());
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        result.state);
            }
            // Client must have emitted a packet containing ACK + the Fast Open payload
            assertEquals(
                    1,
                    clientDataPackets.size());
            try (Ipv6TcpPacketCodec.Packet decoded =
                         Ipv6TcpPacketCodec.decode(
                                 clientDataPackets.get(0))) {
                assertTrue(
                        decoded.hasFlag(
                                Ipv6TcpPacketCodec.FLAG_ACK));
                assertEquals(
                        0x2233_4456L,
                        decoded.sequence);
                assertEquals(
                        0x9988_7767L,
                        decoded.acknowledgement);
                assertArrayEquals(
                        fastOpen,
                        decoded.payload);
            }
        } finally {
            client.close();
            wipe(
                    fastOpen);
            wipe(
                    syn);
            wipe(
                    peerSynAckOnlySyn);
            wipeAll(
                    clientDataPackets);
        }
    }

    @Test
    public void simultaneousOpenConvergesWithoutChangingInitialSequences() {
        Ipv6TcpStream android =
                Ipv6TcpStream.active(
                        ANDROID,
                        WATCH,
                        49153,
                        61315,
                        100);
        Ipv6TcpStream watch =
                Ipv6TcpStream.active(
                        WATCH,
                        ANDROID,
                        61315,
                        49153,
                        900);
        byte[] androidSyn = null;
        byte[] watchSyn = null;
        List<byte[]> androidSynAck = new ArrayList<>();
        List<byte[]> watchSynAck = new ArrayList<>();
        List<byte[]> androidFinal = new ArrayList<>();
        List<byte[]> watchFinal = new ArrayList<>();
        try {
            androidSyn = android.startActiveOpen();
            watchSyn = watch.startActiveOpen();
            try (Ipv6TcpStream.InboundResult result =
                         android.accept(
                                 watchSyn)) {
                androidSynAck.addAll(
                        result.outboundPackets());
                assertEquals(
                        Ipv6TcpStream.State.SYN_RECEIVED,
                        result.state);
            }
            try (Ipv6TcpStream.InboundResult result =
                         watch.accept(
                                 androidSyn)) {
                watchSynAck.addAll(
                        result.outboundPackets());
                assertEquals(
                        Ipv6TcpStream.State.SYN_RECEIVED,
                        result.state);
            }
            try (Ipv6TcpStream.InboundResult result =
                         android.accept(
                                 watchSynAck.get(0))) {
                androidFinal.addAll(
                        result.outboundPackets());
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        result.state);
            }
            try (Ipv6TcpStream.InboundResult result =
                         watch.accept(
                                 androidSynAck.get(0))) {
                watchFinal.addAll(
                        result.outboundPackets());
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        result.state);
            }
            assertEquals(
                    101,
                    android.sendNext());
            assertEquals(
                    901,
                    watch.sendNext());
        } finally {
            android.close();
            watch.close();
            wipe(
                    androidSyn);
            wipe(
                    watchSyn);
            wipeAll(
                    androidSynAck);
            wipeAll(
                    watchSynAck);
            wipeAll(
                    androidFinal);
            wipeAll(
                    watchFinal);
        }
    }

    @Test
    public void behindWindowHandshakeReplayRetransmitsUnackedPayload() {
        Ipv6TcpStream client =
                Ipv6TcpStream.active(
                        ANDROID,
                        WATCH,
                        49152,
                        61314,
                        0x1000L);
        Ipv6TcpStream server = null;
        Ipv6TcpStream.PassiveOpen passive = null;
        byte[] syn = null;
        byte[] synAck = null;
        List<byte[]> clientAck = new ArrayList<>();
        List<byte[]> handshakePackets = new ArrayList<>();
        List<byte[]> snapshotPackets = new ArrayList<>();
        List<byte[]> replayOutbound = new ArrayList<>();
        byte[] handshake =
                sequence(
                        0x41,
                        9);
        byte[] snapshot =
                sequence(
                        0x20,
                        200);
        try {
            syn = client.startActiveOpen();
            passive =
                    Ipv6TcpStream.acceptPassive(
                            syn,
                            0x2000L);
            server = passive.takeStream();
            synAck = passive.synAcknowledgement();
            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 synAck)) {
                clientAck.addAll(
                        result.outboundPackets());
            }
            try (Ipv6TcpStream.InboundResult ignored =
                         server.accept(
                                 clientAck.get(0))) {
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        ignored.state);
            }

            handshakePackets.addAll(
                    server.send(
                            handshake));
            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 handshakePackets.get(0))) {
                assertEquals(
                        1,
                        result.deliveredBytes().size());
                assertEquals(
                        9,
                        result.deliveredBytes().get(0).length);
                try (Ipv6TcpStream.InboundResult acked =
                             server.accept(
                                     result.outboundPackets().get(0))) {
                    assertTrue(
                            acked.deliveredBytes().isEmpty());
                }
            }

            snapshotPackets.addAll(
                    client.send(
                            snapshot));
            assertEquals(
                    1,
                    snapshotPackets.size());
            assertTrue(
                    client.sendNext()
                            != client.sendUnacknowledged());

            try (Ipv6TcpStream.InboundResult firstReplay =
                         client.accept(
                                 handshakePackets.get(0))) {
                assertTrue(
                        firstReplay.deliveredBytes().isEmpty());
                boolean sawSnapshotOnFirstReplay = false;
                boolean sawAckAtUna = false;
                for (byte[] packet : firstReplay.outboundPackets()) {
                    try (Ipv6TcpPacketCodec.Packet decoded =
                                 Ipv6TcpPacketCodec.decode(
                                         packet)) {
                        if (decoded.payload.length == 0) {
                            assertEquals(
                                    client.sendUnacknowledged(),
                                    decoded.sequence);
                            sawAckAtUna = true;
                        }
                        if (Arrays.equals(
                                snapshot,
                                decoded.payload)) {
                            sawSnapshotOnFirstReplay = true;
                        }
                    }
                }
                assertTrue(sawAckAtUna);
                assertFalse(sawSnapshotOnFirstReplay);
            }

            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 handshakePackets.get(0))) {
                replayOutbound.addAll(
                        result.outboundPackets());
                assertTrue(
                        result.deliveredBytes().isEmpty());
            }
            boolean sawSnapshotReplay = false;
            boolean sawAck = false;
            for (byte[] packet : replayOutbound) {
                try (Ipv6TcpPacketCodec.Packet decoded =
                             Ipv6TcpPacketCodec.decode(
                                     packet)) {
                    if (decoded.payload.length == 0) {
                        sawAck = true;
                    }
                    if (Arrays.equals(
                            snapshot,
                            decoded.payload)) {
                        sawSnapshotReplay = true;
                        assertEquals(
                                client.receiveNext(),
                                decoded.acknowledgement);
                    }
                }
            }
            assertTrue(sawAck);
            assertTrue(sawSnapshotReplay);
        } finally {
            client.close();
            if (server != null) {
                server.close();
            }
            if (passive != null) {
                passive.close();
            }
            wipe(syn);
            wipe(synAck);
            wipe(handshake);
            wipe(snapshot);
            wipeAll(clientAck);
            wipeAll(handshakePackets);
            wipeAll(snapshotPackets);
        }
    }

    @Test
    public void disabledPayloadFastRetransmitSuppressesReplayAndDuplicateAck() {
        Ipv6TcpStream client =
                Ipv6TcpStream.active(
                        ANDROID,
                        WATCH,
                        49155,
                        61314,
                        0x2000L);
        Ipv6TcpStream server = null;
        Ipv6TcpStream.PassiveOpen passive = null;
        byte[] syn = null;
        byte[] synAck = null;
        byte[] handshake = hex("090000000000000001");
        byte[] snapshot = hex("12046e616e6f");
        List<byte[]> clientAck = new ArrayList<>();
        List<byte[]> handshakePackets = new ArrayList<>();
        List<byte[]> snapshotPackets = new ArrayList<>();
        List<byte[]> replayOutbound = new ArrayList<>();
        try {
            syn = client.startActiveOpen();
            passive =
                    Ipv6TcpStream.acceptPassive(
                            syn,
                            0x7000L);
            server = passive.takeStream();
            synAck = passive.synAcknowledgement();
            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 synAck)) {
                clientAck.addAll(
                        result.outboundPackets());
            }
            assertEquals(1, clientAck.size());
            try (Ipv6TcpStream.InboundResult ignored =
                         server.accept(clientAck.get(0))) {
                assertTrue(ignored.deliveredBytes().isEmpty());
            }

            handshakePackets.addAll(server.send(handshake));
            try (Ipv6TcpStream.InboundResult result =
                         client.accept(handshakePackets.get(0))) {
                try (Ipv6TcpStream.InboundResult acked =
                             server.accept(result.outboundPackets().get(0))) {
                    assertTrue(acked.deliveredBytes().isEmpty());
                }
            }

            snapshotPackets.addAll(client.send(snapshot));
            assertEquals(1, snapshotPackets.size());

            // Disable fast retransmit (as done during post-commit)
            client.setPayloadFastRetransmitEnabled(false);

            // Send multiple behind-window replays and duplicate ACKs
            for (int tick = 0; tick < 5; tick++) {
                try (Ipv6TcpStream.InboundResult result =
                             client.accept(handshakePackets.get(0))) {
                    replayOutbound.addAll(result.outboundPackets());
                }
            }

            // Verify that ONLY pure ACKs were generated, and NO payload was retransmitted
            for (byte[] packet : replayOutbound) {
                try (Ipv6TcpPacketCodec.Packet decoded =
                             Ipv6TcpPacketCodec.decode(packet)) {
                    assertEquals(0, decoded.payload.length);
                    assertFalse(Arrays.equals(snapshot, decoded.payload));
                }
            }
        } finally {
            client.close();
            if (server != null) {
                server.close();
            }
            if (passive != null) {
                passive.close();
            }
            wipe(syn);
            wipe(synAck);
            wipe(handshake);
            wipe(snapshot);
            wipeAll(clientAck);
            wipeAll(handshakePackets);
            wipeAll(snapshotPackets);
            wipeAll(replayOutbound);
        }
    }

    @Test
    public void unacknowledgedSendBytesTracksEstablishedPayload() {
        Ipv6TcpStream client =
                Ipv6TcpStream.active(
                        ANDROID,
                        WATCH,
                        49152,
                        61314,
                        0x3000L);
        Ipv6TcpStream server = null;
        Ipv6TcpStream.PassiveOpen passive = null;
        byte[] syn = null;
        byte[] synAck = null;
        List<byte[]> clientAck = new ArrayList<>();
        List<byte[]> payloadPackets = new ArrayList<>();
        byte[] payload =
                sequence(
                        0x50,
                        117);
        try {
            syn = client.startActiveOpen();
            assertEquals(
                    0L,
                    client.unacknowledgedSendBytes());
            passive =
                    Ipv6TcpStream.acceptPassive(
                            syn,
                            0x4000L);
            server = passive.takeStream();
            synAck = passive.synAcknowledgement();
            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 synAck)) {
                clientAck.addAll(
                        result.outboundPackets());
            }
            try (Ipv6TcpStream.InboundResult ignored =
                         server.accept(
                                 clientAck.get(0))) {
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        ignored.state);
            }
            assertEquals(
                    0L,
                    client.unacknowledgedSendBytes());
            payloadPackets.addAll(
                    client.send(
                            payload));
            assertEquals(
                    117L,
                    client.unacknowledgedSendBytes());
            try (Ipv6TcpStream.InboundResult result =
                         server.accept(
                                 payloadPackets.get(0))) {
                try (Ipv6TcpStream.InboundResult acked =
                             client.accept(
                                     result.outboundPackets().get(0))) {
                    assertTrue(
                            acked.deliveredBytes().isEmpty());
                }
            }
            assertEquals(
                    0L,
                    client.unacknowledgedSendBytes());
        } finally {
            client.close();
            if (server != null) {
                server.close();
            }
            if (passive != null) {
                passive.close();
            }
            wipe(syn);
            wipe(synAck);
            wipe(payload);
            wipeAll(clientAck);
            wipeAll(payloadPackets);
        }
    }

    @Test
    public void suppressedFastRetransmitDoesNotCloneUnackedPayload() {
        Ipv6TcpStream client =
                Ipv6TcpStream.active(
                        ANDROID,
                        WATCH,
                        49152,
                        61314,
                        0x5000L);
        Ipv6TcpStream server = null;
        Ipv6TcpStream.PassiveOpen passive = null;
        byte[] syn = null;
        byte[] synAck = null;
        List<byte[]> clientAck = new ArrayList<>();
        List<byte[]> handshakePackets = new ArrayList<>();
        List<byte[]> snapshotPackets = new ArrayList<>();
        byte[] handshake =
                sequence(
                        0x41,
                        9);
        byte[] snapshot =
                sequence(
                        0x20,
                        200);
        try {
            syn = client.startActiveOpen();
            passive =
                    Ipv6TcpStream.acceptPassive(
                            syn,
                            0x6000L);
            server = passive.takeStream();
            synAck = passive.synAcknowledgement();
            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 synAck)) {
                clientAck.addAll(
                        result.outboundPackets());
            }
            try (Ipv6TcpStream.InboundResult ignored =
                         server.accept(
                                 clientAck.get(0))) {
                assertEquals(
                        Ipv6TcpStream.State.ESTABLISHED,
                        ignored.state);
            }
            handshakePackets.addAll(
                    server.send(
                            handshake));
            try (Ipv6TcpStream.InboundResult result =
                         client.accept(
                                 handshakePackets.get(0))) {
                try (Ipv6TcpStream.InboundResult acked =
                             server.accept(
                                     result.outboundPackets().get(0))) {
                    assertTrue(
                            acked.deliveredBytes().isEmpty());
                }
            }
            snapshotPackets.addAll(
                    client.send(
                            snapshot));
            client.setPayloadFastRetransmitEnabled(
                    false);
            for (int replay = 0;
                    replay < 3;
                    replay++) {
                try (Ipv6TcpStream.InboundResult result =
                             client.accept(
                                     handshakePackets.get(0))) {
                    boolean sawSnapshot = false;
                    for (byte[] packet : result.outboundPackets()) {
                        try (Ipv6TcpPacketCodec.Packet decoded =
                                     Ipv6TcpPacketCodec.decode(
                                             packet)) {
                            if (Arrays.equals(
                                    snapshot,
                                    decoded.payload)) {
                                sawSnapshot = true;
                            }
                        }
                    }
                    assertFalse(sawSnapshot);
                }
            }
        } finally {
            client.close();
            if (server != null) {
                server.close();
            }
            if (passive != null) {
                passive.close();
            }
            wipe(syn);
            wipe(synAck);
            wipe(handshake);
            wipe(snapshot);
            wipeAll(clientAck);
            wipeAll(handshakePackets);
            wipeAll(snapshotPackets);
        }
    }

    @Test
    public void overlappingRetransmitWithNewSuffixDeliversNewBytesAndAdvancesRcvNxt() {
        Ipv6TcpStream client =
                Ipv6TcpStream.active(
                        ANDROID,
                        WATCH,
                        49154,
                        61314,
                        1000L);
        Ipv6TcpStream.PassiveOpen passive = null;
        Ipv6TcpStream server = null;
        byte[] syn = null;
        byte[] synAck = null;
        try {
            syn = client.startActiveOpen();
            passive =
                    Ipv6TcpStream.acceptPassive(
                            syn,
                            2000L);
            server = passive.takeStream();
            synAck = passive.synAcknowledgement();
            try (Ipv6TcpStream.InboundResult r1 = client.accept(synAck)) {
                assertEquals(Ipv6TcpStream.State.ESTABLISHED, r1.state);
                try (Ipv6TcpStream.InboundResult r2 = server.accept(r1.outboundPackets().get(0))) {
                    assertEquals(Ipv6TcpStream.State.ESTABLISHED, r2.state);
                }
            }

            byte[] first10 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            List<byte[]> p1 = client.send(first10);
            assertEquals(1, p1.size());
            try (Ipv6TcpStream.InboundResult r = server.accept(p1.get(0))) {
                assertEquals(1, r.deliveredBytes().size());
                assertArrayEquals(first10, r.deliveredBytes().get(0));
            }
            assertEquals(1011L, server.receiveNext());

            byte[] total25 = new byte[25];
            System.arraycopy(first10, 0, total25, 0, 10);
            for (int i = 10; i < 25; i++) {
                total25[i] = (byte) (i + 1);
            }
            byte[] overlappingPacket = Ipv6TcpPacketCodec.encode(
                    ANDROID,
                    WATCH,
                    49154,
                    61314,
                    1001L,
                    server.sendNext(),
                    Ipv6TcpPacketCodec.FLAG_ACK | Ipv6TcpPacketCodec.FLAG_PSH,
                    65535,
                    0,
                    new byte[0],
                    total25);

            try (Ipv6TcpStream.InboundResult r = server.accept(overlappingPacket)) {
                assertEquals(1, r.deliveredBytes().size());
                assertEquals(15, r.deliveredBytes().get(0).length);
                byte[] expectedNew = Arrays.copyOfRange(total25, 10, 25);
                assertArrayEquals(expectedNew, r.deliveredBytes().get(0));
            }
            assertEquals(1026L, server.receiveNext());
        } finally {
            client.close();
            if (server != null) {
                server.close();
            }
            if (passive != null) {
                passive.close();
            }
            wipe(syn);
            wipe(synAck);
        }
    }

    private static byte[] concatenate(
            List<byte[]> values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] output =
                new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(
                    value,
                    0,
                    output,
                    offset,
                    value.length);
            offset += value.length;
        }
        return output;
    }

    private static byte[] sequence(
            int start,
            int length) {
        byte[] output =
                new byte[length];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) (start + index);
        }
        return output;
    }

    @Test
    public void adoptedEstablishedAckEchoesPeerTimestamps() {
        // Live 0.2.304: the Watch PAWS-drops forged ACKs that lack the
        // negotiated timestamp option, so stale-quartet adoption must echo
        // the peer TSval. Without it the Watch retransmits the stale segment
        // for ~8.6 min before opening the new control connector.
        long watchTimestamp = 0x1122_3344L;
        byte[] timestamps = new byte[12];
        timestamps[0] = 1;
        timestamps[1] = 1;
        timestamps[2] = 8;
        timestamps[3] = 10;
        timestamps[4] = 0x11;
        timestamps[5] = 0x22;
        timestamps[6] = 0x33;
        timestamps[7] = 0x44;
        byte[] segment =
                Ipv6TcpPacketCodec.encode(
                        WATCH,
                        ANDROID,
                        49156,
                        61314,
                        100,
                        200,
                        Ipv6TcpPacketCodec.FLAG_ACK
                                | Ipv6TcpPacketCodec.FLAG_PSH,
                        Ipv6TcpStream.RECEIVE_WINDOW,
                        0,
                        timestamps,
                        new byte[] {1, 2, 3});
        Ipv6TcpStream.AdoptedOpen adopted = null;
        try {
            adopted = Ipv6TcpStream.adoptEstablished(segment);
            byte[] acknowledgement = adopted.acknowledgement();
            try (Ipv6TcpPacketCodec.Packet ack =
                         Ipv6TcpPacketCodec.decode(acknowledgement)) {
                assertEquals(200, ack.sequence);
                assertEquals(103, ack.acknowledgement);
                byte[] options = ack.options;
                assertEquals(12, options.length);
                assertEquals(1, options[0] & 0xff);
                assertEquals(1, options[1] & 0xff);
                assertEquals(8, options[2] & 0xff);
                assertEquals(10, options[3] & 0xff);
                long echo =
                        ((options[8] & 0xffL) << 24)
                                | ((options[9] & 0xffL) << 16)
                                | ((options[10] & 0xffL) << 8)
                                | (options[11] & 0xffL);
                assertEquals(watchTimestamp, echo);
            } finally {
                wipe(acknowledgement);
            }
        } finally {
            if (adopted != null) {
                adopted.close();
            }
            wipe(segment);
            wipe(timestamps);
        }
    }

    @Test
    public void adoptedEstablishedAckOmitsTimestampsWhenPeerDidNotNegotiate() {
        byte[] segment =
                Ipv6TcpPacketCodec.encode(
                        WATCH,
                        ANDROID,
                        49156,
                        61314,
                        100,
                        200,
                        Ipv6TcpPacketCodec.FLAG_ACK,
                        Ipv6TcpStream.RECEIVE_WINDOW,
                        0,
                        new byte[0],
                        new byte[] {9});
        Ipv6TcpStream.AdoptedOpen adopted = null;
        try {
            adopted = Ipv6TcpStream.adoptEstablished(segment);
            byte[] acknowledgement = adopted.acknowledgement();
            try (Ipv6TcpPacketCodec.Packet ack =
                         Ipv6TcpPacketCodec.decode(acknowledgement)) {
                assertEquals(0, ack.options.length);
            } finally {
                wipe(acknowledgement);
            }
        } finally {
            if (adopted != null) {
                adopted.close();
            }
            wipe(segment);
        }
    }

    private static byte[] hex(
            String value) {
        String compact =
                value.replaceAll(
                        "\\s+",
                        "");
        byte[] output =
                new byte[compact.length() / 2];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) Integer.parseInt(
                            compact.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return output;
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
