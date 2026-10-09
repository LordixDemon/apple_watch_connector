package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NetworkRelay stream TLV framing between the normal Bluetooth pipe and the
 * IPsec nexus.
 *
 * <p>Types 1..5 use the Internet checksum over type, length, and payload.
 * Fixed IPv6 types 100..105 instead carry Apple's two-byte header check. The
 * latter omit the complete IPv6 header and reconstruct it from direction-local
 * Class-D address contexts.</p>
 */
final class NetworkRelayPacketCodec {
    static final int TYPE_PAD0 = 0;
    static final int TYPE_PAD_N = 1;
    static final int TYPE_UNCOMPRESSED_IP = 2;
    static final int TYPE_ENCAPSULATED_6LOWPAN = 3;
    static final int TYPE_IKEV2_POINT_TO_POINT = 4;
    static final int TYPE_CONTROL_MESSAGE = 5;

    static final int TYPE_KNOWN_IPV6_ESP = 100;
    static final int TYPE_KNOWN_IPV6_ESP_ECT0 = 101;
    static final int TYPE_KNOWN_IPV6_TCP = 102;
    static final int TYPE_KNOWN_IPV6_TCP_ECT0 = 103;
    static final int TYPE_KNOWN_IPV6_ESP_CLASS_C = 104;
    static final int TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0 = 105;

    static final int IPV6_HEADER_LENGTH = 40;
    static final int IPV6_NEXT_HEADER_TCP = 6;
    static final int IPV6_NEXT_HEADER_ESP = 50;
    static final int IPV6_HOP_LIMIT = 64;
    static final int TRAFFIC_CLASS_DEFAULT = 0;
    static final int TRAFFIC_CLASS_ECT0 = 2;

    private static final int HEADER_LENGTH = 3;
    private static final int CHECK_LENGTH = 2;
    private static final int MAX_PAYLOAD_LENGTH = 0xffff;
    private static final int MAX_BUFFERED_BYTES =
            (MAX_PAYLOAD_LENGTH + HEADER_LENGTH + CHECK_LENGTH) * 2;

    public static volatile java.util.function.Consumer<String> diagnosticLogger = null;

    static String hexPrefix(byte[] data, int maxLen) {
        if (data == null) return "null";
        int len = Math.min(data.length, maxLen);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", data[i]));
        }
        return sb.toString();
    }

    private NetworkRelayPacketCodec() {
    }

    static byte[] encodeIke(
            byte[] ikePacket) {
        return encodeChecksummed(
                TYPE_IKEV2_POINT_TO_POINT,
                ikePacket);
    }

    static byte[] encodeControlMessage(
            byte[] payload) {
        return encodeChecksummed(
                TYPE_CONTROL_MESSAGE,
                payload);
    }

    static byte[] encodeUncompressedIp(
            byte[] ipPacket) {
        validateIpPacket(
                ipPacket);
        return encodeChecksummed(
                TYPE_UNCOMPRESSED_IP,
                ipPacket);
    }

    /**
     * Encodes one IPv6 packet using a fixed Apple template when possible,
     * otherwise falls back to the accepted uncompressed-IP representation.
     */
    static byte[] encodeBestIpv6(
            byte[] ipv6Packet,
            byte[] sourceClassD,
            byte[] destinationClassD) {
        validateIpv6Packet(
                ipv6Packet);
        requireAddress(
                "source Class-D context",
                sourceClassD);
        requireAddress(
                "destination Class-D context",
                destinationClassD);

        int trafficClass =
                ipv6TrafficClass(
                        ipv6Packet);
        int nextHeader = ipv6Packet[6] & 0xff;
        if ((trafficClass != TRAFFIC_CLASS_DEFAULT
                && trafficClass != TRAFFIC_CLASS_ECT0)
                || (nextHeader != IPV6_NEXT_HEADER_ESP
                && nextHeader != IPV6_NEXT_HEADER_TCP)
                || !hasFixedIpv6Header(
                        ipv6Packet)) {
            return encodeUncompressedIp(
                    ipv6Packet);
        }

        byte[] actualSource =
                Arrays.copyOfRange(
                        ipv6Packet,
                        8,
                        24);
        byte[] actualDestination =
                Arrays.copyOfRange(
                        ipv6Packet,
                        24,
                        40);
        byte[] sourceClassC =
                classCFromClassD(
                        sourceClassD);
        byte[] destinationClassC =
                classCFromClassD(
                        destinationClassD);
        try {
            boolean classD =
                    Arrays.equals(
                            actualSource,
                            sourceClassD)
                            && Arrays.equals(
                            actualDestination,
                            destinationClassD);
            boolean classC =
                    Arrays.equals(
                            actualSource,
                            sourceClassC)
                            && Arrays.equals(
                            actualDestination,
                            destinationClassC);
            int type;
            if (nextHeader == IPV6_NEXT_HEADER_TCP) {
                if (!classD) {
                    return encodeUncompressedIp(
                            ipv6Packet);
                }
                type = trafficClass
                        == TRAFFIC_CLASS_ECT0
                        ? TYPE_KNOWN_IPV6_TCP_ECT0
                        : TYPE_KNOWN_IPV6_TCP;
            } else if (classC) {
                type = trafficClass
                        == TRAFFIC_CLASS_ECT0
                        ? TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0
                        : TYPE_KNOWN_IPV6_ESP_CLASS_C;
            } else if (classD) {
                type = trafficClass
                        == TRAFFIC_CLASS_ECT0
                        ? TYPE_KNOWN_IPV6_ESP_ECT0
                        : TYPE_KNOWN_IPV6_ESP;
            } else {
                return encodeUncompressedIp(
                        ipv6Packet);
            }
            byte[] payload =
                    Arrays.copyOfRange(
                            ipv6Packet,
                            IPV6_HEADER_LENGTH,
                            ipv6Packet.length);
            try {
                return encodeKnown(
                        type,
                        payload);
            } finally {
                wipe(payload);
            }
        } finally {
            wipe(actualSource);
            wipe(actualDestination);
            wipe(sourceClassC);
            wipe(destinationClassC);
        }
    }

    static byte[] encodeLowpanClassC(
            byte[] ipv6Packet) {
        validateIpv6Packet(ipv6Packet);
        byte[] actualSource = Arrays.copyOfRange(ipv6Packet, 8, 24);
        byte[] actualDestination = Arrays.copyOfRange(ipv6Packet, 24, 40);
        int trafficClass = ipv6TrafficClass(ipv6Packet);
        int nextHeader = ipv6Packet[6] & 0xff;
        byte[] payload = Arrays.copyOfRange(ipv6Packet, IPV6_HEADER_LENGTH, ipv6Packet.length);
        try {
            boolean hasTrafficClass = (trafficClass != 0);
            int headerOverhead = 3 + (hasTrafficClass ? 1 : 0) + 16 + 16;
            byte[] lowpanPayload = new byte[headerOverhead + payload.length];
            int offset = 0;
            lowpanPayload[offset++] = (byte) (hasTrafficClass ? 0x72 : 0x7a);
            lowpanPayload[offset++] = 0x00;
            if (hasTrafficClass) {
                lowpanPayload[offset++] = (byte) trafficClass;
            }
            lowpanPayload[offset++] = (byte) nextHeader;
            // Live type 3 puts the context destination first, then the source.
            System.arraycopy(actualDestination, 0, lowpanPayload, offset, 16);
            offset += 16;
            System.arraycopy(actualSource, 0, lowpanPayload, offset, 16);
            offset += 16;
            System.arraycopy(payload, 0, lowpanPayload, offset, payload.length);
            return encodeChecksummed(TYPE_ENCAPSULATED_6LOWPAN, lowpanPayload);
        } finally {
            wipe(actualSource);
            wipe(actualDestination);
            wipe(payload);
        }
    }

    /**
     * Type 3 with RFC 6282 inline order: source address, then destination.
     * The existing encoder writes destination first. This form is only for
     * the stale control acknowledgement, whose checksum must match the
     * header the Watch reconstructs.
     */
    static byte[] encodeLowpanRfcInline(byte[] ipv6Packet) {
        validateIpv6Packet(ipv6Packet);
        byte[] actualSource = Arrays.copyOfRange(ipv6Packet, 8, 24);
        byte[] actualDestination = Arrays.copyOfRange(ipv6Packet, 24, 40);
        int nextHeader = ipv6Packet[6] & 0xff;
        byte[] payload = Arrays.copyOfRange(ipv6Packet, IPV6_HEADER_LENGTH, ipv6Packet.length);
        try {
            byte[] lowpanPayload = new byte[3 + 32 + payload.length];
            lowpanPayload[0] = 0x7a;
            lowpanPayload[1] = 0x00;
            lowpanPayload[2] = (byte) nextHeader;
            System.arraycopy(actualSource, 0, lowpanPayload, 3, 16);
            System.arraycopy(actualDestination, 0, lowpanPayload, 19, 16);
            System.arraycopy(payload, 0, lowpanPayload, 35, payload.length);
            return encodeChecksummed(TYPE_ENCAPSULATED_6LOWPAN, lowpanPayload);
        } finally {
            wipe(actualSource);
            wipe(actualDestination);
            wipe(payload);
        }
    }

    static byte[] encodeKnown(
            int type,
            byte[] payload) {
        requireKnownType(
                type);
        requirePayload(
                payload);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        HEADER_LENGTH
                                + payload.length
                                + CHECK_LENGTH);
        output.write(type);
        writeBe16(
                output,
                payload.length);
        output.writeBytes(payload);
        byte[] check =
                knownHeaderCheck(
                        type,
                        payload.length);
        output.writeBytes(check);
        return output.toByteArray();
    }

    static byte[] knownHeaderCheck(
            int type,
            int payloadLength) {
        requireKnownType(
                type);
        requirePayloadLength(
                payloadLength);
        return new byte[]{
                (byte) ((payloadLength >>> 8)
                        ^ (type >>> 4)),
                (byte) (payloadLength
                        ^ (type << 4))
        };
    }

    private static byte[] encodeChecksummed(
            int type,
            byte[] payload) {
        if (type < TYPE_PAD_N
                || type > TYPE_CONTROL_MESSAGE) {
            throw new IllegalArgumentException(
                    "Checksummed NetworkRelay type must be 1..5");
        }
        requirePayload(
                payload);
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        HEADER_LENGTH
                                + payload.length
                                + CHECK_LENGTH);
        output.write(type);
        writeBe16(
                output,
                payload.length);
        output.writeBytes(payload);
        int checksum =
                IkeV2Codec.internetChecksum(
                        output.toByteArray());
        writeBe16(
                output,
                checksum);
        return output.toByteArray();
    }

    private static byte[] reconstructKnownIpv6(
            int type,
            byte[] payload,
            byte[] sourceClassD,
            byte[] destinationClassD) {
        requireKnownType(
                type);
        int trafficClass =
                type == TYPE_KNOWN_IPV6_ESP_ECT0
                        || type
                        == TYPE_KNOWN_IPV6_TCP_ECT0
                        || type
                        == TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0
                        ? TRAFFIC_CLASS_ECT0
                        : TRAFFIC_CLASS_DEFAULT;
        int nextHeader =
                type == TYPE_KNOWN_IPV6_TCP
                        || type
                        == TYPE_KNOWN_IPV6_TCP_ECT0
                        ? IPV6_NEXT_HEADER_TCP
                        : IPV6_NEXT_HEADER_ESP;
        boolean classC =
                type == TYPE_KNOWN_IPV6_ESP_CLASS_C
                        || type
                        == TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0;
        byte[] source =
                classC
                        ? classCFromClassD(
                                sourceClassD)
                        : sourceClassD.clone();
        byte[] destination =
                classC
                        ? classCFromClassD(
                                destinationClassD)
                        : destinationClassD.clone();
        try {
            byte[] output =
                    new byte[
                            IPV6_HEADER_LENGTH
                                    + payload.length];
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
            output[7] = (byte) IPV6_HOP_LIMIT;
            System.arraycopy(
                    source,
                    0,
                    output,
                    8,
                    source.length);
            System.arraycopy(
                    destination,
                    0,
                    output,
                    24,
                    destination.length);
            System.arraycopy(
                    payload,
                    0,
                    output,
                    IPV6_HEADER_LENGTH,
                    payload.length);
            java.util.function.Consumer<String> logger = diagnosticLogger;
            if (logger != null) {
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[KnownIp OUT] type=%d srcD=%s dstD=%s outSrc=%s outDst=%s nextHdr=%d",
                        type,
                        IdsIpv6TcpRouter.formatIpv6(sourceClassD),
                        IdsIpv6TcpRouter.formatIpv6(destinationClassD),
                        IdsIpv6TcpRouter.formatIpv6(source),
                        IdsIpv6TcpRouter.formatIpv6(destination),
                        nextHeader));
            }
            return output;
        } finally {
            wipe(source);
            wipe(destination);
        }
    }

    private static byte[] classCFromClassD(
            byte[] classD) {
        requireAddress(
                "Class-D context",
                classD);
        byte[] classC = classD.clone();
        classC[8] = 0;
        classC[9] = 0x0c;
        return classC;
    }

    private static boolean hasFixedIpv6Header(
            byte[] packet) {
        int flowLabel =
                ((packet[1] & 0x0f) << 16)
                        | ((packet[2] & 0xff) << 8)
                        | (packet[3] & 0xff);
        return flowLabel == 0
                && (packet[7] & 0xff)
                == IPV6_HOP_LIMIT;
    }

    private static int ipv6TrafficClass(
            byte[] packet) {
        return ((packet[0] & 0x0f) << 4)
                | ((packet[1] & 0xf0) >>> 4);
    }

    private static void validateIpPacket(
            byte[] packet) {
        if (packet == null || packet.length == 0) {
            throw new IllegalArgumentException(
                    "IP packet is empty");
        }
        int version = (packet[0] & 0xf0) >>> 4;
        if (version == 6) {
            validateIpv6Packet(
                    packet);
            return;
        }
        if (version != 4
                || packet.length < 20
                || (packet[0] & 0x0f) < 5
                || (packet[0] & 0x0f) * 4
                > packet.length
                || be16(packet, 2)
                != packet.length) {
            throw new IllegalArgumentException(
                    "Uncompressed IP packet is invalid");
        }
    }

    private static void validateIpv6Packet(
            byte[] packet) {
        if (packet == null
                || packet.length < IPV6_HEADER_LENGTH
                || (packet[0] & 0xf0) != 0x60
                || be16(packet, 4)
                != packet.length - IPV6_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "IPv6 packet is invalid");
        }
    }

    private static void requirePayload(
            byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "NetworkRelay payload is null");
        }
        requirePayloadLength(
                payload.length);
    }

    private static void requirePayloadLength(
            int payloadLength) {
        if (payloadLength < 0
                || payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "NetworkRelay payload length exceeds uint16");
        }
    }

    private static void requireKnownType(
            int type) {
        if (type < TYPE_KNOWN_IPV6_ESP
                || type
                > TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0) {
            throw new IllegalArgumentException(
                    "NetworkRelay type is not a fixed IPv6 template");
        }
    }

    private static void requireAddress(
            String label,
            byte[] address) {
        if (address == null
                || address.length != 16) {
            throw new IllegalArgumentException(
                    label + " must contain 16 bytes");
        }
    }

    private static int be16(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private static void writeBe16(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class StreamDecoder
            implements AutoCloseable {
        private final byte[] sourceClassD;
        private final byte[] destinationClassD;
        private byte[] buffer = new byte[0];
        private boolean failed;
        private boolean closed;

        StreamDecoder(
                byte[] sourceClassD,
                byte[] destinationClassD) {
            requireAddress(
                    "decoder source Class-D context",
                    sourceClassD);
            requireAddress(
                    "decoder destination Class-D context",
                    destinationClassD);
            this.sourceClassD =
                    sourceClassD.clone();
            this.destinationClassD =
                    destinationClassD.clone();
        }

        List<DecodedFrame> push(
                byte[] bytes) {
            requireUsable();
            if (bytes == null) {
                throw new IllegalArgumentException(
                        "NetworkRelay stream chunk is null");
            }
            if ((long) buffer.length + bytes.length
                    > MAX_BUFFERED_BYTES) {
                fail();
                throw new IllegalArgumentException(
                        "NetworkRelay stream buffer limit exceeded");
            }
            byte[] oldBuffer = buffer;
            byte[] joined =
                    Arrays.copyOf(
                            oldBuffer,
                            oldBuffer.length
                                    + bytes.length);
            System.arraycopy(
                    bytes,
                    0,
                    joined,
                    oldBuffer.length,
                    bytes.length);
            wipe(oldBuffer);
            buffer = joined;

            List<DecodedFrame> frames =
                    new ArrayList<>();
            int consumed = 0;
            try {
                while (consumed < buffer.length) {
                    int type =
                            buffer[consumed] & 0xff;
                    if (type == TYPE_PAD0) {
                        frames.add(
                                DecodedFrame.padding(
                                        TYPE_PAD0,
                                        new byte[0]));
                        consumed++;
                        continue;
                    }
                    if (buffer.length - consumed
                            < HEADER_LENGTH) {
                        break;
                    }
                    int payloadLength =
                            be16(
                                    buffer,
                                    consumed + 1);
                    int frameLength =
                            HEADER_LENGTH
                                    + payloadLength
                                    + CHECK_LENGTH;
                    if (buffer.length - consumed
                            < frameLength) {
                        java.util.function.Consumer<String> logger = diagnosticLogger;
                        if (logger != null) {
                            logger.accept(String.format(
                                    java.util.Locale.US,
                                    "[NR RX] waiting bytes: have=%d need=%d type=%d payloadLen=%d",
                                    buffer.length - consumed,
                                    frameLength,
                                    type,
                                    payloadLength));
                        }
                        break;
                    }
                    int payloadOffset =
                            consumed + HEADER_LENGTH;
                    int checkOffset =
                            payloadOffset
                                    + payloadLength;
                    validateCheck(
                            type,
                            buffer,
                            consumed,
                            checkOffset,
                            payloadLength);
                    byte[] payload =
                            Arrays.copyOfRange(
                                    buffer,
                                    payloadOffset,
                                    checkOffset);
                    try {
                        frames.add(
                                decodeFrame(
                                        type,
                                        payload));
                    } finally {
                        wipe(payload);
                    }
                    consumed += frameLength;
                }
                if (consumed != 0) {
                    byte[] old = buffer;
                    buffer =
                            Arrays.copyOfRange(
                                    old,
                                    consumed,
                                    old.length);
                    wipe(old);
                }
                return List.copyOf(
                        frames);
            } catch (RuntimeException error) {
                for (DecodedFrame frame : frames) {
                    frame.destroy();
                }
                fail();
                throw error;
            }
        }

        int bufferedLength() {
            requireUsable();
            return buffer.length;
        }

        private DecodedFrame decodeFrame(
                int type,
                byte[] payload) {
            if (type == TYPE_PAD_N) {
                for (byte value : payload) {
                    if (value != 0) {
                        throw new IllegalArgumentException(
                                "PadN contains non-zero bytes");
                    }
                }
                return DecodedFrame.padding(
                        type,
                        payload);
            }
            if (type == TYPE_UNCOMPRESSED_IP) {
                validateIpPacket(
                        payload);
                return DecodedFrame.ip(
                        type,
                        payload,
                        payload);
            }
            if (type
                    == TYPE_ENCAPSULATED_6LOWPAN) {
                byte[] ip =
                        reconstructAppleLowpanIpv6(
                                payload,
                                sourceClassD,
                                destinationClassD);
                try {
                    return DecodedFrame.ip(
                            type,
                            payload,
                            ip);
                } finally {
                    wipe(ip);
                }
            }
            if (type
                    == TYPE_IKEV2_POINT_TO_POINT) {
                return DecodedFrame.ike(
                        payload);
            }
            if (type == TYPE_CONTROL_MESSAGE) {
                return DecodedFrame.control(
                        payload);
            }
            if (type >= TYPE_KNOWN_IPV6_ESP
                    && type
                    <= TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0) {
                byte[] ip =
                        reconstructKnownIpv6(
                                type,
                                payload,
                                sourceClassD,
                                destinationClassD);
                try {
                    return DecodedFrame.ip(
                            type,
                            payload,
                            ip);
                } finally {
                    wipe(ip);
                }
            }
            throw new IllegalArgumentException(
                    "Unsupported NetworkRelay type "
                            + type);
        }

        /**
         * Reconstructs the four IPHC forms emitted by NetworkRelay for the
         * normal Bluetooth IPsec path. These are the same templates behind
         * fixed types 100/101/104/105, but type 3 carries the template bytes
         * in-line.
         */
        private static byte[] reconstructAppleLowpanIpv6(
                byte[] payload,
                byte[] sourceClassD,
                byte[] destinationClassD) {
            if (payload.length < 3) {
                throw new IllegalArgumentException(
                        "NetworkRelay IPHC header is truncated");
            }
            int first = payload[0] & 0xff;
            int second = payload[1] & 0xff;
            java.util.function.Consumer<String> logger = diagnosticLogger;
            if (logger != null) {
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[Lowpan RX] payloadLen=%d first=0x%02x second=0x%02x srcD=%s dstD=%s hexPrefix=%s",
                        payload.length,
                        first,
                        second,
                        IdsIpv6TcpRouter.formatIpv6(sourceClassD),
                        IdsIpv6TcpRouter.formatIpv6(destinationClassD),
                        hexPrefix(payload, Math.min(payload.length, 32))));
            }
            if ((first & 0xe0) != 0x60) {
                throw new IllegalArgumentException(
                        "NetworkRelay type 3 has no LOWPAN_IPHC dispatch");
            }

            int trafficFlow = (first >>> 3) & 0x03;
            boolean nextHeaderCompressed = (first & 0x04) != 0;
            int hopLimitMode = first & 0x03;
            boolean contextIdExtension = (second & 0x80) != 0;
            int sourceAddressShape = second & 0x70;
            int destinationAddressShape = second & 0x0f;
            if (trafficFlow == 3
                    && !nextHeaderCompressed
                    && hopLimitMode == 1
                    && !contextIdExtension
                    && second == 0x1b) {
                return reconstructLinkLocalMulticast(
                        payload);
            }

            int offset = 2;
            int trafficClass = TRAFFIC_CLASS_DEFAULT;
            if (trafficFlow == 2) {
                if (offset >= payload.length) {
                    throw new IllegalArgumentException(
                            "NetworkRelay IPHC traffic class is truncated");
                }
                trafficClass = payload[offset++] & 0xff;
            } else if (trafficFlow == 3) {
                trafficClass = 0;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported LOWPAN_IPHC traffic flow: " + trafficFlow);
            }

            if (nextHeaderCompressed) {
                throw new IllegalArgumentException(
                        "Compressed next header is unsupported in LOWPAN_IPHC");
            }
            if (offset >= payload.length) {
                throw new IllegalArgumentException(
                        "NetworkRelay IPHC next header is truncated");
            }
            int nextHeader = payload[offset++] & 0xff;

            int hopLimit;
            switch (hopLimitMode) {
                case 1 -> hopLimit = 1;
                case 2 -> hopLimit = IPV6_HOP_LIMIT;
                case 3 -> hopLimit = 255;
                default -> {
                    if (offset >= payload.length) {
                        throw new IllegalArgumentException(
                                "NetworkRelay IPHC inline hop limit is truncated");
                    }
                    hopLimit = payload[offset++] & 0xff;
                }
            }

            byte[] source = new byte[16];
            byte[] destination = new byte[16];
            try {
                boolean sac = (second & 0x40) != 0;
                int sam = (second >>> 4) & 0x03;
                boolean m = (second & 0x08) != 0;
                boolean dac = (second & 0x04) != 0;
                int dam = second & 0x03;

                if (second == 0x33) {
                    source = sourceClassD.clone();
                    destination = destinationClassD.clone();
                } else if (second == 0x66) {
                    source = sourceClassD.clone();
                    destination = destinationClassD.clone();
                    if (offset + 4 > payload.length) {
                        throw new IllegalArgumentException(
                                "NetworkRelay Class-C IPHC addresses are truncated");
                    }
                    source[8] = payload[offset++];
                    source[9] = payload[offset++];
                    destination[8] = payload[offset++];
                    destination[9] = payload[offset++];
                } else if (second == 0x30) {
                    // Apple NetworkRelay 6LoWPAN: Destination is elided (destinationClassD),
                    // and Source is 128-bit inline (sourceClassD / Watch).
                    destination = destinationClassD.clone();
                    if (offset + 16 > payload.length) {
                        throw new IllegalArgumentException(
                                "NetworkRelay IPHC 0x30 source address is truncated");
                    }
                    System.arraycopy(payload, offset, source, 0, 16);
                    offset += 16;
                } else if (second == 0x03) {
                    // Apple NetworkRelay 6LoWPAN: Source is elided (sourceClassD),
                    // and Destination is 128-bit inline.
                    source = sourceClassD.clone();
                    if (offset + 16 > payload.length) {
                        throw new IllegalArgumentException(
                                "NetworkRelay IPHC 0x03 destination address is truncated");
                    }
                    System.arraycopy(payload, offset, destination, 0, 16);
                    offset += 16;
                } else if (!m && !sac && !dac && sam == 0 && dam == 0) {
                    if (offset + 32 > payload.length) {
                        throw new IllegalArgumentException(
                                "NetworkRelay IPHC 128-bit inline addresses truncated");
                    }
                    // Live frames carry the destination, then the source.
                    System.arraycopy(payload, offset, destination, 0, 16);
                    offset += 16;
                    System.arraycopy(payload, offset, source, 0, 16);
                    offset += 16;
                } else {
                    if (!sac) {
                        switch (sam) {
                            case 0 -> {
                                if (offset + 16 > payload.length) {
                                    throw new IllegalArgumentException("NetworkRelay IPHC 128-bit source truncated");
                                }
                                System.arraycopy(payload, offset, source, 0, 16);
                                offset += 16;
                            }
                            case 1 -> {
                                if (offset + 8 > payload.length) {
                                    throw new IllegalArgumentException("NetworkRelay IPHC 64-bit source truncated");
                                }
                                source[0] = (byte) 0xfe;
                                source[1] = (byte) 0x80;
                                System.arraycopy(payload, offset, source, 8, 8);
                                offset += 8;
                            }
                            case 2 -> {
                                if (offset + 2 > payload.length) {
                                    throw new IllegalArgumentException("NetworkRelay IPHC 16-bit source truncated");
                                }
                                source[0] = (byte) 0xfe;
                                source[1] = (byte) 0x80;
                                source[11] = (byte) 0xff;
                                source[12] = (byte) 0xfe;
                                source[14] = payload[offset++];
                                source[15] = payload[offset++];
                            }
                            case 3 -> {
                                System.arraycopy(sourceClassD, 0, source, 0, 16);
                            }
                        }
                    } else {
                        System.arraycopy(sourceClassD, 0, source, 0, 16);
                        if (sam == 1 && offset + 8 <= payload.length) {
                            System.arraycopy(payload, offset, source, 8, 8);
                            offset += 8;
                        } else if (sam == 2 && offset + 2 <= payload.length) {
                            source[14] = payload[offset++];
                            source[15] = payload[offset++];
                        }
                    }

                    if (!m) {
                        if (!dac) {
                            switch (dam) {
                                case 0 -> {
                                    if (offset + 16 > payload.length) {
                                        throw new IllegalArgumentException("NetworkRelay IPHC 128-bit destination truncated");
                                    }
                                    System.arraycopy(payload, offset, destination, 0, 16);
                                    offset += 16;
                                }
                                case 1 -> {
                                    if (offset + 8 > payload.length) {
                                        throw new IllegalArgumentException("NetworkRelay IPHC 64-bit destination truncated");
                                    }
                                    destination[0] = (byte) 0xfe;
                                    destination[1] = (byte) 0x80;
                                    System.arraycopy(payload, offset, destination, 8, 8);
                                    offset += 8;
                                }
                                case 2 -> {
                                    if (offset + 2 > payload.length) {
                                        throw new IllegalArgumentException("NetworkRelay IPHC 16-bit destination truncated");
                                    }
                                    destination[0] = (byte) 0xfe;
                                    destination[1] = (byte) 0x80;
                                    destination[11] = (byte) 0xff;
                                    destination[12] = (byte) 0xfe;
                                    destination[14] = payload[offset++];
                                    destination[15] = payload[offset++];
                                }
                                case 3 -> {
                                    System.arraycopy(destinationClassD, 0, destination, 0, 16);
                                }
                            }
                        } else {
                            System.arraycopy(destinationClassD, 0, destination, 0, 16);
                            if (dam == 1 && offset + 8 <= payload.length) {
                                System.arraycopy(payload, offset, destination, 8, 8);
                                offset += 8;
                            } else if (dam == 2 && offset + 2 <= payload.length) {
                                destination[14] = payload[offset++];
                                destination[15] = payload[offset++];
                            }
                        }
                    } else {
                        if (!dac) {
                            switch (dam) {
                                case 0 -> {
                                    if (offset + 16 > payload.length) {
                                        throw new IllegalArgumentException("NetworkRelay IPHC 128-bit multicast destination truncated");
                                    }
                                    System.arraycopy(payload, offset, destination, 0, 16);
                                    offset += 16;
                                }
                                case 1 -> {
                                    if (offset + 6 > payload.length) {
                                        throw new IllegalArgumentException("NetworkRelay IPHC 48-bit multicast destination truncated");
                                    }
                                    destination[0] = (byte) 0xff;
                                    destination[1] = payload[offset++];
                                    System.arraycopy(payload, offset, destination, 11, 5);
                                    offset += 5;
                                }
                                case 2 -> {
                                    if (offset + 4 > payload.length) {
                                        throw new IllegalArgumentException("NetworkRelay IPHC 32-bit multicast destination truncated");
                                    }
                                    destination[0] = (byte) 0xff;
                                    destination[1] = payload[offset++];
                                    System.arraycopy(payload, offset, destination, 13, 3);
                                    offset += 3;
                                }
                                case 3 -> {
                                    if (offset + 1 > payload.length) {
                                        throw new IllegalArgumentException("NetworkRelay IPHC 8-bit multicast destination truncated");
                                    }
                                    destination[0] = (byte) 0xff;
                                    destination[1] = 0x02;
                                    destination[15] = payload[offset++];
                                }
                            }
                        } else {
                            if (dam == 0 && offset + 6 <= payload.length) {
                                destination[0] = (byte) 0xff;
                                destination[1] = payload[offset++];
                                destination[2] = payload[offset++];
                                destination[3] = payload[offset++];
                                System.arraycopy(payload, offset, destination, 12, 4);
                                offset += 4;
                            } else {
                                destination[0] = (byte) 0xff;
                                destination[1] = 0x02;
                                if (offset < payload.length) {
                                    destination[15] = payload[offset++];
                                }
                            }
                        }
                    }
                }

                int upperLayerLength = payload.length - offset;
                byte[] output = new byte[IPV6_HEADER_LENGTH + upperLayerLength];
                output[0] = (byte) (0x60 | (trafficClass >>> 4));
                output[1] = (byte) (trafficClass << 4);
                output[4] = (byte) (upperLayerLength >>> 8);
                output[5] = (byte) upperLayerLength;
                output[6] = (byte) nextHeader;
                output[7] = (byte) hopLimit;
                System.arraycopy(source, 0, output, 8, 16);
                System.arraycopy(destination, 0, output, 24, 16);
                System.arraycopy(payload, offset, output, IPV6_HEADER_LENGTH, upperLayerLength);
                validateIpv6Packet(output);
                if (logger != null) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[Lowpan OUT] src=%s dst=%s nextHdr=%d hopL=%d upLen=%d",
                            IdsIpv6TcpRouter.formatIpv6(source),
                            IdsIpv6TcpRouter.formatIpv6(destination),
                            nextHeader,
                            hopLimit,
                            upperLayerLength));
                }
                return output;
            } finally {
                wipe(source);
                wipe(destination);
            }
        }

        private static byte[] reconstructLinkLocalMulticast(
                byte[] payload) {
            // RFC 6282 SAC=0/SAM=01 carries the source IID in 64 bits;
            // M=1/DAC=0/DAM=11 carries ff02::GG in one byte.
            int offset = 2;
            if (offset + 1 + 8 + 1 > payload.length) {
                throw new IllegalArgumentException(
                        "NetworkRelay link-local multicast IPHC is truncated");
            }
            int nextHeader = payload[offset++] & 0xff;
            byte[] source = new byte[16];
            source[0] = (byte) 0xfe;
            source[1] = (byte) 0x80;
            System.arraycopy(
                    payload,
                    offset,
                    source,
                    8,
                    8);
            offset += 8;
            byte[] destination = new byte[16];
            destination[0] = (byte) 0xff;
            destination[1] = 0x02;
            destination[15] = payload[offset++];
            int upperLayerLength = payload.length - offset;
            try {
                byte[] output =
                        new byte[IPV6_HEADER_LENGTH + upperLayerLength];
                output[0] = 0x60;
                output[4] =
                        (byte) (upperLayerLength >>> 8);
                output[5] =
                        (byte) upperLayerLength;
                output[6] = (byte) nextHeader;
                output[7] = 1;
                System.arraycopy(source, 0, output, 8, 16);
                System.arraycopy(destination, 0, output, 24, 16);
                System.arraycopy(
                        payload,
                        offset,
                        output,
                        IPV6_HEADER_LENGTH,
                        upperLayerLength);
                validateIpv6Packet(output);
                return output;
            } finally {
                wipe(source);
                wipe(destination);
            }
        }

        private static void validateCheck(
                int type,
                byte[] bytes,
                int frameOffset,
                int checkOffset,
                int payloadLength) {
            if (type >= TYPE_KNOWN_IPV6_ESP
                    && type
                    <= TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0) {
                byte[] expected =
                        knownHeaderCheck(
                                type,
                                payloadLength);
                try {
                    if (bytes[checkOffset]
                            != expected[0]
                            || bytes[checkOffset + 1]
                            != expected[1]) {
                        throw new IllegalArgumentException(
                                "NetworkRelay known-header "
                                        + "check mismatch");
                    }
                } finally {
                    wipe(expected);
                }
                return;
            }
            if (type < TYPE_PAD_N
                    || type > TYPE_CONTROL_MESSAGE) {
                throw new IllegalArgumentException(
                        "Unsupported NetworkRelay type "
                                + type);
            }
            int received =
                    be16(
                            bytes,
                            checkOffset);
            byte[] covered =
                    Arrays.copyOfRange(
                            bytes,
                            frameOffset,
                            checkOffset);
            try {
                int expected =
                        IkeV2Codec.internetChecksum(
                                covered);
                if (received != expected) {
                    throw new IllegalArgumentException(
                            "NetworkRelay Internet checksum mismatch");
                }
            } finally {
                wipe(covered);
            }
        }

        private void requireUsable() {
            if (closed) {
                throw new IllegalStateException(
                        "NetworkRelay decoder is closed");
            }
            if (failed) {
                throw new IllegalStateException(
                        "NetworkRelay decoder is poisoned");
            }
        }

        private void fail() {
            wipe(buffer);
            buffer = new byte[0];
            failed = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            wipe(sourceClassD);
            wipe(destinationClassD);
            wipe(buffer);
            buffer = new byte[0];
            closed = true;
        }
    }

    static final class DecodedFrame {
        enum Kind {
            PADDING,
            IP,
            IKE,
            CONTROL
        }

        final int type;
        final Kind kind;
        final byte[] payload;
        final byte[] ipPacket;
        private boolean destroyed;

        private DecodedFrame(
                int type,
                Kind kind,
                byte[] payload,
                byte[] ipPacket) {
            this.type = type;
            this.kind = kind;
            this.payload = payload.clone();
            this.ipPacket =
                    ipPacket == null
                            ? null
                            : ipPacket.clone();
        }

        static DecodedFrame padding(
                int type,
                byte[] payload) {
            return new DecodedFrame(
                    type,
                    Kind.PADDING,
                    payload,
                    null);
        }

        static DecodedFrame ip(
                int type,
                byte[] payload,
                byte[] ipPacket) {
            return new DecodedFrame(
                    type,
                    Kind.IP,
                    payload,
                    ipPacket);
        }

        static DecodedFrame ike(
                byte[] payload) {
            return new DecodedFrame(
                    TYPE_IKEV2_POINT_TO_POINT,
                    Kind.IKE,
                    payload,
                    null);
        }

        static DecodedFrame control(
                byte[] payload) {
            return new DecodedFrame(
                    TYPE_CONTROL_MESSAGE,
                    Kind.CONTROL,
                    payload,
                    null);
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(payload);
            wipe(ipPacket);
            destroyed = true;
        }
    }
}
