package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Ipv6TcpPacketCodecTest {
    private static final byte[] LOCAL_CLASS_C =
            hex(
                    "fd 74 65 72 6d 6e 75 73 "
                            + "00 0c 10 11 12 13 14 15");
    private static final byte[] REMOTE_CLASS_C =
            hex(
                    "fd 74 65 72 6d 6e 75 73 "
                            + "00 0c 20 21 22 23 24 25");

    @Test
    public void offloadPseudoChecksumMatchesLiveControlSegment() {
        byte[] source = hex(
                "fd7465726d6e7573000d93150d5ba76e");
        byte[] destination = hex(
                "fd7465726d6e7573000dc9324f0b7264");
        byte[] packet = new byte[40 + 259];
        packet[0] = 0x60;
        packet[4] = 0x01;
        packet[5] = 0x03;
        packet[6] = 6;
        packet[7] = 64;
        System.arraycopy(source, 0, packet, 8, 16);
        System.arraycopy(destination, 0, packet, 24, 16);
        Ipv6TcpPacketCodec.writeOffloadPseudoChecksum(packet);
        assertEquals(0x5f, packet[40 + 16] & 0xff);
        assertEquals(0x37, packet[40 + 17] & 0xff);
    }

    @Test
    public void synAckOptionsEchoLiveWatchTimestampsAndStayWordAligned() {
        byte[] syn = hex("02043fc4010303050101080a809a30050000000004022202");
        Ipv6TcpPacketCodec.PeerOptions peer = Ipv6TcpPacketCodec.parsePeerOptions(syn);
        assertEquals(5, peer.windowScale);
        assertTrue(peer.sackPermitted);
        assertEquals(0x809a3005L, peer.timestampValue);
        byte[] options = Ipv6TcpPacketCodec.encodeSynAckOptions(1200, peer);
        assertEquals(0, options.length & 3);
        long echo = -1L;
        for (int index = 0; index + 10 <= options.length; index++) {
            if ((options[index] & 0xff) == 8 && (options[index + 1] & 0xff) == 10) {
                echo = ((options[index + 6] & 0xffL) << 24)
                        | ((options[index + 7] & 0xffL) << 16)
                        | ((options[index + 8] & 0xffL) << 8)
                        | (options[index + 9] & 0xffL);
            }
        }
        assertEquals(0x809a3005L, echo);
        byte[] packet = Ipv6TcpPacketCodec.encode(
                LOCAL_CLASS_C, REMOTE_CLASS_C, 61315, 49169,
                1, 2,
                Ipv6TcpPacketCodec.FLAG_SYN | Ipv6TcpPacketCodec.FLAG_ACK,
                65535, 0, options, new byte[0]);
        Ipv6TcpPacketCodec.Packet decoded = Ipv6TcpPacketCodec.decode(packet);
        try {
            assertTrue(decoded.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN));
            assertTrue(decoded.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK));
        } finally {
            decoded.close();
        }
    }

    @Test
    public void encodesAndDecodesBaselineClassCTcp() {
        byte[] options =
                Ipv6TcpPacketCodec.encodeMssOption(
                        1024);
        byte[] payload =
                "ids-control-channel"
                        .getBytes(
                                StandardCharsets.US_ASCII);
        byte[] encoded =
                Ipv6TcpPacketCodec.encode(
                        LOCAL_CLASS_C,
                        REMOTE_CLASS_C,
                        49152,
                        IdsControlChannelCodec.CONTROL_PORT,
                        0x1020_3040L,
                        0xa0b0_c0d0L,
                        Ipv6TcpPacketCodec.FLAG_ACK
                                | Ipv6TcpPacketCodec.FLAG_PSH,
                        0xffff,
                        0,
                        options,
                        payload);
        try (Ipv6TcpPacketCodec.Packet decoded =
                     Ipv6TcpPacketCodec.decode(
                             encoded)) {
            assertEquals(
                    40 + 24 + payload.length,
                    encoded.length);
            assertEquals(
                    0,
                    decoded.trafficClass);
            assertEquals(
                    0,
                    decoded.flowLabel);
            assertEquals(
                    64,
                    decoded.hopLimit);
            assertArrayEquals(
                    LOCAL_CLASS_C,
                    decoded.sourceAddress);
            assertArrayEquals(
                    REMOTE_CLASS_C,
                    decoded.destinationAddress);
            assertEquals(
                    49152,
                    decoded.sourcePort);
            assertEquals(
                    61315,
                    decoded.destinationPort);
            assertEquals(
                    0x1020_3040L,
                    decoded.sequence);
            assertEquals(
                    0xa0b0_c0d0L,
                    decoded.acknowledgement);
            assertTrue(
                    decoded.hasFlag(
                            Ipv6TcpPacketCodec.FLAG_ACK));
            assertTrue(
                    decoded.hasFlag(
                            Ipv6TcpPacketCodec.FLAG_PSH));
            assertFalse(
                    decoded.hasFlag(
                            Ipv6TcpPacketCodec.FLAG_SYN));
            assertArrayEquals(
                    options,
                    decoded.options);
            assertArrayEquals(
                    payload,
                    decoded.payload);
        } finally {
            wipe(
                    options);
            wipe(
                    payload);
            wipe(
                    encoded);
        }
    }

    @Test
    public void acceptsTcpFastOpenPayloadOnSyn() {
        byte[] initialRequest =
                sequence(
                        0x30,
                        112);
        byte[] encoded =
                Ipv6TcpPacketCodec.encode(
                        REMOTE_CLASS_C,
                        LOCAL_CLASS_C,
                        50321,
                        IdsControlChannelCodec.CONTROL_PORT,
                        0xffff_fff0L,
                        0,
                        Ipv6TcpPacketCodec.FLAG_SYN,
                        65535,
                        0,
                        new byte[0],
                        initialRequest);
        try (Ipv6TcpPacketCodec.Packet decoded =
                     Ipv6TcpPacketCodec.decode(
                             encoded)) {
            assertTrue(
                    decoded.hasFlag(
                            Ipv6TcpPacketCodec.FLAG_SYN));
            assertFalse(
                    decoded.hasFlag(
                            Ipv6TcpPacketCodec.FLAG_ACK));
            assertArrayEquals(
                    initialRequest,
                    decoded.payload);
        } finally {
            wipe(
                    initialRequest);
            wipe(
                    encoded);
        }
    }

    @Test
    public void checksumCoversAddressesHeaderOptionsAndOddPayload() {
        byte[] encoded =
                Ipv6TcpPacketCodec.encode(
                        LOCAL_CLASS_C,
                        REMOTE_CLASS_C,
                        40000,
                        61314,
                        1,
                        2,
                        Ipv6TcpPacketCodec.FLAG_ACK,
                        4096,
                        0,
                        hex("01 01 01 00"),
                        hex("aa bb cc"));
        byte[] corruptedPayload =
                encoded.clone();
        byte[] corruptedAddress =
                encoded.clone();
        byte[] corruptedOption =
                encoded.clone();
        try {
            corruptedPayload[
                    corruptedPayload.length - 1] ^= 1;
            corruptedAddress[23] ^= 1;
            corruptedOption[
                    Ipv6TcpPacketCodec.IPV6_HEADER_LENGTH
                            + 20] ^= 1;

            assertThrows(
                    IllegalArgumentException.class,
                    () -> Ipv6TcpPacketCodec.decode(
                            corruptedPayload,
                            true));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Ipv6TcpPacketCodec.decode(
                            corruptedAddress,
                            true));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Ipv6TcpPacketCodec.decode(
                            corruptedOption,
                            true));
        } finally {
            wipe(
                    encoded);
            wipe(
                    corruptedPayload);
            wipe(
                    corruptedAddress);
            wipe(
                    corruptedOption);
        }
    }

    @Test
    public void rejectsTruncationExtensionsAndReservedBits() {
        byte[] valid =
                Ipv6TcpPacketCodec.encode(
                        LOCAL_CLASS_C,
                        REMOTE_CLASS_C,
                        40001,
                        61315,
                        3,
                        4,
                        Ipv6TcpPacketCodec.FLAG_ACK,
                        1024,
                        0,
                        new byte[0],
                        new byte[0]);
        byte[] truncated =
                Arrays.copyOf(
                        valid,
                        valid.length - 1);
        byte[] extension =
                valid.clone();
        byte[] reserved =
                valid.clone();
        try {
            extension[6] = 0;
            reserved[
                    Ipv6TcpPacketCodec.IPV6_HEADER_LENGTH
                            + 12] |= 2;

            assertThrows(
                    IllegalArgumentException.class,
                    () -> Ipv6TcpPacketCodec.decode(
                            truncated));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Ipv6TcpPacketCodec.decode(
                            extension));
        } finally {
            wipe(
                    valid);
            wipe(
                    truncated);
            wipe(
                    extension);
            wipe(
                    reserved);
        }
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
}
