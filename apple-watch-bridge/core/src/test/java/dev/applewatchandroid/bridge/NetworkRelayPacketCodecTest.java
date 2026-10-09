package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class NetworkRelayPacketCodecTest {
    @Test
    public void matchesRecoveredKnownHeaderCheckVectors() {
        byte[] payload = new byte[0x30];

        byte[] classD =
                NetworkRelayPacketCodec.encodeKnown(
                        NetworkRelayPacketCodec
                                .TYPE_KNOWN_IPV6_ESP,
                        payload);
        byte[] classC =
                NetworkRelayPacketCodec.encodeKnown(
                        NetworkRelayPacketCodec
                                .TYPE_KNOWN_IPV6_ESP_CLASS_C,
                        payload);

        assertArrayEquals(
                new byte[]{0x06, 0x70},
                Arrays.copyOfRange(
                        classD,
                        classD.length - 2,
                        classD.length));
        assertArrayEquals(
                new byte[]{0x06, (byte) 0xb0},
                Arrays.copyOfRange(
                        classC,
                        classC.length - 2,
                        classC.length));
        assertArrayEquals(
                new byte[]{0x07, (byte) 0xb3},
                NetworkRelayPacketCodec.knownHeaderCheck(
                        NetworkRelayPacketCodec
                                .TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0,
                        0x0123));
    }

    @Test
    public void emitsExactTypeFourFrameUsedByExistingUikeCodec() {
        byte[] ike = new byte[]{
                1, 2, 3, 4, 5, 6, 7
        };

        assertArrayEquals(
                IkeV2Codec.encodeUikeFrame(ike),
                NetworkRelayPacketCodec.encodeIke(ike));
    }

    @Test
    public void streamsMultipleSplitFramesAndReconstructsClassC() {
        byte[] sourceD = address(0x11, 0x0d);
        byte[] destinationD = address(0x22, 0x0d);
        byte[] sourceC = sourceD.clone();
        byte[] destinationC = destinationD.clone();
        sourceC[8] = 0;
        sourceC[9] = 0x0c;
        destinationC[8] = 0;
        destinationC[9] = 0x0c;
        byte[] esp = sequence(48);
        byte[] classCIp =
                ipv6(
                        NetworkRelayPacketCodec
                                .TRAFFIC_CLASS_ECT0,
                        NetworkRelayPacketCodec
                                .IPV6_NEXT_HEADER_ESP,
                        sourceC,
                        destinationC,
                        esp);
        byte[] known =
                NetworkRelayPacketCodec.encodeBestIpv6(
                        classCIp,
                        sourceD,
                        destinationD);
        byte[] ike =
                NetworkRelayPacketCodec.encodeIke(
                        new byte[]{9, 8, 7});
        byte[] uncompressedIp =
                ipv6(
                        0x20,
                        17,
                        sourceD,
                        destinationD,
                        new byte[]{1, 3, 5, 7});
        byte[] uncompressed =
                NetworkRelayPacketCodec.encodeBestIpv6(
                        uncompressedIp,
                        sourceD,
                        destinationD);
        byte[] stream =
                concatenate(
                        new byte[]{0},
                        ike,
                        known,
                        uncompressed);

        assertEquals(
                NetworkRelayPacketCodec
                        .TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0,
                known[0] & 0xff);
        assertEquals(
                NetworkRelayPacketCodec.TYPE_UNCOMPRESSED_IP,
                uncompressed[0] & 0xff);

        NetworkRelayPacketCodec.StreamDecoder decoder =
                new NetworkRelayPacketCodec.StreamDecoder(
                        sourceD,
                        destinationD);
        List<NetworkRelayPacketCodec.DecodedFrame> frames =
                new ArrayList<>();
        try {
            int[] cuts = new int[]{1, 4, 17, stream.length};
            int start = 0;
            for (int cut : cuts) {
                frames.addAll(
                        decoder.push(
                                Arrays.copyOfRange(
                                        stream,
                                        start,
                                        cut)));
                start = cut;
            }
            assertEquals(4, frames.size());
            assertEquals(
                    NetworkRelayPacketCodec
                            .DecodedFrame.Kind.PADDING,
                    frames.get(0).kind);
            assertEquals(
                    NetworkRelayPacketCodec
                            .DecodedFrame.Kind.IKE,
                    frames.get(1).kind);
            assertArrayEquals(
                    new byte[]{9, 8, 7},
                    frames.get(1).payload);
            assertEquals(
                    NetworkRelayPacketCodec
                            .DecodedFrame.Kind.IP,
                    frames.get(2).kind);
            assertArrayEquals(
                    classCIp,
                    frames.get(2).ipPacket);
            assertArrayEquals(
                    uncompressedIp,
                    frames.get(3).ipPacket);
            assertEquals(0, decoder.bufferedLength());
        } finally {
            for (NetworkRelayPacketCodec.DecodedFrame frame
                    : frames) {
                frame.destroy();
            }
            decoder.close();
        }
    }

    @Test
    public void fixedHeaderCheckDeliberatelyLeavesPayloadToEspAead() {
        byte[] sourceD = address(0x31, 0x0d);
        byte[] destinationD = address(0x41, 0x0d);
        byte[] encoded =
                NetworkRelayPacketCodec.encodeKnown(
                        NetworkRelayPacketCodec
                                .TYPE_KNOWN_IPV6_ESP,
                        sequence(32));
        encoded[3] ^= 1;

        NetworkRelayPacketCodec.StreamDecoder decoder =
                new NetworkRelayPacketCodec.StreamDecoder(
                        sourceD,
                        destinationD);
        List<NetworkRelayPacketCodec.DecodedFrame> frames =
                decoder.push(encoded);
        try {
            assertEquals(1, frames.size());
            assertEquals(
                    (byte) 1,
                    frames.get(0).ipPacket[40]);
        } finally {
            frames.get(0).destroy();
            decoder.close();
        }
    }

    @Test
    public void rejectsBadChecksAndDecodesAppleTypeThreeTemplates() {
        byte[] sourceD = address(0x51, 0x0d);
        byte[] destinationD = address(0x61, 0x0d);
        byte[] bad =
                NetworkRelayPacketCodec.encodeKnown(
                        NetworkRelayPacketCodec
                                .TYPE_KNOWN_IPV6_ESP,
                        new byte[24]);
        bad[bad.length - 1] ^= 1;
        NetworkRelayPacketCodec.StreamDecoder badDecoder =
                new NetworkRelayPacketCodec.StreamDecoder(
                        sourceD,
                        destinationD);
        assertThrows(
                IllegalArgumentException.class,
                () -> badDecoder.push(bad));
        assertThrows(
                IllegalStateException.class,
                () -> badDecoder.push(new byte[0]));
        badDecoder.close();

        byte[] lowpan =
                checksummed(
                        NetworkRelayPacketCodec
                                .TYPE_ENCAPSULATED_6LOWPAN,
                        concatenate(
                                new byte[]{0x7a, 0x33, 0x32},
                                sequence(32)));
        NetworkRelayPacketCodec.StreamDecoder lowpanDecoder =
                new NetworkRelayPacketCodec.StreamDecoder(
                        sourceD,
                        destinationD);
        List<NetworkRelayPacketCodec.DecodedFrame> lowpanFrames =
                lowpanDecoder.push(lowpan);
        try {
            assertEquals(1, lowpanFrames.size());
            assertArrayEquals(
                    ipv6(
                            NetworkRelayPacketCodec
                                    .TRAFFIC_CLASS_DEFAULT,
                            NetworkRelayPacketCodec
                                    .IPV6_NEXT_HEADER_ESP,
                            sourceD,
                            destinationD,
                            sequence(32)),
                    lowpanFrames.get(0).ipPacket);
        } finally {
            lowpanFrames.get(0).destroy();
            lowpanDecoder.close();
        }

        byte[] sourceC = sourceD.clone();
        byte[] destinationC = destinationD.clone();
        sourceC[8] = 0;
        sourceC[9] = 0x0c;
        destinationC[8] = 0;
        destinationC[9] = 0x0c;
        byte[] lowpanClassC =
                checksummed(
                        NetworkRelayPacketCodec
                                .TYPE_ENCAPSULATED_6LOWPAN,
                        concatenate(
                                new byte[]{
                                        0x72, 0x66, 0x02, 0x32,
                                        0x00, 0x0c, 0x00, 0x0c
                                },
                                sequence(24)));
        NetworkRelayPacketCodec.StreamDecoder classCDecoder =
                new NetworkRelayPacketCodec.StreamDecoder(
                        sourceD,
                        destinationD);
        List<NetworkRelayPacketCodec.DecodedFrame> classCFrames =
                classCDecoder.push(lowpanClassC);
        try {
            assertEquals(1, classCFrames.size());
            assertArrayEquals(
                    ipv6(
                            NetworkRelayPacketCodec
                                    .TRAFFIC_CLASS_ECT0,
                            NetworkRelayPacketCodec
                                    .IPV6_NEXT_HEADER_ESP,
                            sourceC,
                            destinationC,
                            sequence(24)),
                    classCFrames.get(0).ipPacket);
        } finally {
            classCFrames.get(0).destroy();
            classCDecoder.close();
        }

        byte[] lowpan0x30 =
                checksummed(
                        NetworkRelayPacketCodec
                                .TYPE_ENCAPSULATED_6LOWPAN,
                        concatenate(
                                new byte[]{0x7a, 0x30, 0x32},
                                sourceD,
                                sequence(20)));
        NetworkRelayPacketCodec.StreamDecoder decoder0x30 =
                new NetworkRelayPacketCodec.StreamDecoder(
                        sourceD,
                        destinationD);
        List<NetworkRelayPacketCodec.DecodedFrame> frames0x30 =
                decoder0x30.push(lowpan0x30);
        try {
            assertEquals(1, frames0x30.size());
            assertArrayEquals(
                    ipv6(
                            NetworkRelayPacketCodec
                                    .TRAFFIC_CLASS_DEFAULT,
                            NetworkRelayPacketCodec
                                    .IPV6_NEXT_HEADER_ESP,
                            sourceD,
                            destinationD,
                            sequence(20)),
                    frames0x30.get(0).ipPacket);
        } finally {
            frames0x30.get(0).destroy();
            decoder0x30.close();
        }

        byte[] sourceIid = new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8
        };
        byte[] linkLocalSource = new byte[16];
        linkLocalSource[0] = (byte) 0xfe;
        linkLocalSource[1] = (byte) 0x80;
        System.arraycopy(sourceIid, 0, linkLocalSource, 8, 8);
        byte[] multicastDestination = new byte[16];
        multicastDestination[0] = (byte) 0xff;
        multicastDestination[1] = 0x02;
        multicastDestination[15] = 1;
        byte[] multicast =
                checksummed(
                        NetworkRelayPacketCodec
                                .TYPE_ENCAPSULATED_6LOWPAN,
                        concatenate(
                                new byte[]{0x79, 0x1b, 58},
                                sourceIid,
                                new byte[]{1},
                                sequence(16)));
        NetworkRelayPacketCodec.StreamDecoder multicastDecoder =
                new NetworkRelayPacketCodec.StreamDecoder(
                        sourceD,
                        destinationD);
        List<NetworkRelayPacketCodec.DecodedFrame> multicastFrames =
                multicastDecoder.push(multicast);
        try {
            assertEquals(1, multicastFrames.size());
            byte[] expected =
                    ipv6(
                            NetworkRelayPacketCodec
                                    .TRAFFIC_CLASS_DEFAULT,
                            58,
                            linkLocalSource,
                            multicastDestination,
                            sequence(16));
            expected[7] = 1;
            assertArrayEquals(
                    expected,
                    multicastFrames.get(0).ipPacket);
        } finally {
            multicastFrames.get(0).destroy();
            multicastDecoder.close();
        }
    }

    @Test
    public void padNRequiresZerosAndUncompressedIpIsValidated() {
        byte[] sourceD = address(0x71, 0x0d);
        byte[] destinationD = address(0x72, 0x0d);
        NetworkRelayPacketCodec.StreamDecoder decoder =
                new NetworkRelayPacketCodec.StreamDecoder(
                        sourceD,
                        destinationD);
        byte[] padding =
                checksummed(
                        NetworkRelayPacketCodec.TYPE_PAD_N,
                        new byte[]{0, 0, 0});
        List<NetworkRelayPacketCodec.DecodedFrame> frames =
                decoder.push(padding);
        try {
            assertEquals(1, frames.size());
            assertNull(frames.get(0).ipPacket);
            assertTrue(
                    frames.get(0).kind
                            == NetworkRelayPacketCodec
                            .DecodedFrame.Kind.PADDING);
        } finally {
            frames.get(0).destroy();
            decoder.close();
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkRelayPacketCodec
                        .encodeUncompressedIp(
                                new byte[]{0x60, 0, 0}));
    }

    @Test
    public void lowpanClassCRoundtripPreservesSourceAndDestinationOrdering() {
        byte[] src = address(0x11, 0x0c);
        byte[] dst = address(0x22, 0x0c);
        byte[] ipv6Pkt = ipv6(
                NetworkRelayPacketCodec.TRAFFIC_CLASS_ECT0,
                NetworkRelayPacketCodec.IPV6_NEXT_HEADER_ESP,
                src,
                dst,
                sequence(40));

        byte[] encoded = NetworkRelayPacketCodec.encodeLowpanClassC(ipv6Pkt);
        assertEquals(NetworkRelayPacketCodec.TYPE_ENCAPSULATED_6LOWPAN, encoded[0] & 0xff);

        // Header: [type:1][len:2] [payload: [0x72][0x00][tc:0x02][nh:0x32] [dst:16] [src:16] ...]
        byte[] encodedDst = Arrays.copyOfRange(encoded, 7, 23);
        byte[] encodedSrc = Arrays.copyOfRange(encoded, 23, 39);
        assertArrayEquals(dst, encodedDst);
        assertArrayEquals(src, encodedSrc);

        byte[] rfc = NetworkRelayPacketCodec.encodeLowpanRfcInline(ipv6Pkt);
        assertEquals(NetworkRelayPacketCodec.TYPE_ENCAPSULATED_6LOWPAN, rfc[0] & 0xff);
        assertArrayEquals(src, Arrays.copyOfRange(rfc, 6, 22));
        assertArrayEquals(dst, Arrays.copyOfRange(rfc, 22, 38));

        NetworkRelayPacketCodec.StreamDecoder decoder =
                new NetworkRelayPacketCodec.StreamDecoder(src, dst);
        List<NetworkRelayPacketCodec.DecodedFrame> frames = decoder.push(encoded);
        try {
            assertEquals(1, frames.size());
            assertArrayEquals(ipv6Pkt, frames.get(0).ipPacket);
        } finally {
            frames.get(0).destroy();
            decoder.close();
        }
    }

    private static byte[] checksummed(
            int type,
            byte[] payload) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        output.write(type);
        output.write(payload.length >>> 8);
        output.write(payload.length);
        output.writeBytes(payload);
        int checksum =
                IkeV2Codec.internetChecksum(
                        output.toByteArray());
        output.write(checksum >>> 8);
        output.write(checksum);
        return output.toByteArray();
    }

    private static byte[] ipv6(
            int trafficClass,
            int nextHeader,
            byte[] source,
            byte[] destination,
            byte[] payload) {
        byte[] output =
                new byte[40 + payload.length];
        output[0] =
                (byte) (0x60
                        | (trafficClass >>> 4));
        output[1] =
                (byte) (trafficClass << 4);
        output[4] =
                (byte) (payload.length >>> 8);
        output[5] =
                (byte) payload.length;
        output[6] = (byte) nextHeader;
        output[7] =
                (byte) NetworkRelayPacketCodec
                        .IPV6_HOP_LIMIT;
        System.arraycopy(source, 0, output, 8, 16);
        System.arraycopy(destination, 0, output, 24, 16);
        System.arraycopy(payload, 0, output, 40, payload.length);
        return output;
    }

    private static byte[] address(
            int marker,
            int dataClass) {
        byte[] output = new byte[]{
                (byte) 0xfd, 0x74, 0x65, 0x72,
                0x6d, 0x6e, 0x75, 0x73,
                0, 0, 0, 0, 0, 0, 0, 0
        };
        output[9] = (byte) dataClass;
        output[15] = (byte) marker;
        return output;
    }

    private static byte[] sequence(
            int length) {
        byte[] output = new byte[length];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] = (byte) index;
        }
        return output;
    }

    private static byte[] concatenate(
            byte[]... values) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.writeBytes(value);
        }
        return output.toByteArray();
    }
}
