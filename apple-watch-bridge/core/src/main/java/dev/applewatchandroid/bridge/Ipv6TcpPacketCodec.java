package dev.applewatchandroid.bridge;

import java.util.Arrays;

/**
 * Strict baseline IPv6/TCP packet codec for the Class-C NetworkRelay path.
 *
 * <p>Apple's fixed NetworkRelay representation reconstructs a 40-byte IPv6
 * header with no extension headers, flow label zero and hop limit 64. This
 * codec deliberately accepts only that baseline shape and verifies the
 * mandatory IPv6 TCP pseudo-header checksum before exposing any payload.</p>
 */
final class Ipv6TcpPacketCodec {
    static final int IPV6_HEADER_LENGTH = 40;
    static final int TCP_MINIMUM_HEADER_LENGTH = 20;
    static final int TCP_MAXIMUM_HEADER_LENGTH = 60;
    static final int NEXT_HEADER_TCP = 6;
    static final int DEFAULT_HOP_LIMIT = 64;

    static final int FLAG_FIN = 0x001;
    static final int FLAG_SYN = 0x002;
    static final int FLAG_RST = 0x004;
    static final int FLAG_PSH = 0x008;
    static final int FLAG_ACK = 0x010;
    static final int FLAG_URG = 0x020;
    static final int FLAG_ECE = 0x040;
    static final int FLAG_CWR = 0x080;
    static final int FLAG_NS = 0x100;
    static final int ALL_FLAGS = 0x1ff;

    private static final int ADDRESS_LENGTH = 16;
    private static final int MAX_IPV6_PAYLOAD_LENGTH = 0xffff;
    private static final int TCP_CHECKSUM_OFFSET = 16;

    private Ipv6TcpPacketCodec() {
    }

    static byte[] encode(
            byte[] sourceAddress,
            byte[] destinationAddress,
            int sourcePort,
            int destinationPort,
            long sequence,
            long acknowledgement,
            int flags,
            int window,
            int urgentPointer,
            byte[] options,
            byte[] payload) {
        requireAddress(
                "source",
                sourceAddress);
        requireAddress(
                "destination",
                destinationAddress);
        requirePort(
                "source",
                sourcePort);
        requirePort(
                "destination",
                destinationPort);
        requireU32(
                "TCP sequence",
                sequence);
        requireU32(
                "TCP acknowledgement",
                acknowledgement);
        if ((flags & ~ALL_FLAGS) != 0) {
            throw new IllegalArgumentException(
                    "TCP flags exceed the 9-bit header field");
        }
        requireU16(
                "TCP window",
                window);
        requireU16(
                "TCP urgent pointer",
                urgentPointer);
        if (options == null
                || options.length > 40
                || (options.length & 3) != 0) {
            throw new IllegalArgumentException(
                    "TCP options must be 0..40 bytes and 32-bit aligned");
        }
        if (payload == null) {
            throw new IllegalArgumentException(
                    "TCP payload is null");
        }

        int tcpHeaderLength =
                TCP_MINIMUM_HEADER_LENGTH
                        + options.length;
        long segmentLengthLong =
                (long) tcpHeaderLength
                        + payload.length;
        if (segmentLengthLong
                > MAX_IPV6_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "TCP segment exceeds the IPv6 uint16 payload limit");
        }
        int segmentLength =
                (int) segmentLengthLong;
        byte[] packet =
                new byte[
                        IPV6_HEADER_LENGTH
                                + segmentLength];

        packet[0] = 0x60;
        writeU16(
                packet,
                4,
                segmentLength);
        packet[6] = NEXT_HEADER_TCP;
        packet[7] = DEFAULT_HOP_LIMIT;
        System.arraycopy(
                sourceAddress,
                0,
                packet,
                8,
                ADDRESS_LENGTH);
        System.arraycopy(
                destinationAddress,
                0,
                packet,
                24,
                ADDRESS_LENGTH);

        int tcp = IPV6_HEADER_LENGTH;
        writeU16(
                packet,
                tcp,
                sourcePort);
        writeU16(
                packet,
                tcp + 2,
                destinationPort);
        writeU32(
                packet,
                tcp + 4,
                sequence);
        writeU32(
                packet,
                tcp + 8,
                acknowledgement);
        packet[tcp + 12] =
                (byte) ((tcpHeaderLength / 4) << 4);
        if ((flags & FLAG_NS) != 0) {
            packet[tcp + 12] |= 1;
        }
        packet[tcp + 13] =
                (byte) flags;
        writeU16(
                packet,
                tcp + 14,
                window);
        writeU16(
                packet,
                tcp + 18,
                urgentPointer);
        System.arraycopy(
                options,
                0,
                packet,
                tcp + TCP_MINIMUM_HEADER_LENGTH,
                options.length);
        System.arraycopy(
                payload,
                0,
                packet,
                tcp + tcpHeaderLength,
                payload.length);

        int checksum =
                tcpChecksum(
                        sourceAddress,
                        destinationAddress,
                        packet,
                        tcp,
                        segmentLength);
        writeU16(
                packet,
                tcp + TCP_CHECKSUM_OFFSET,
                checksum);
        return packet;
    }

    static Packet decode(
            byte[] packet) {
        return decode(
                packet,
                false);
    }

    static Packet decode(
            byte[] packet,
            boolean verifyChecksum) {
        if (packet == null
                || packet.length
                < IPV6_HEADER_LENGTH
                + TCP_MINIMUM_HEADER_LENGTH
                || (packet[0] & 0xf0) != 0x60) {
            throw new IllegalArgumentException(
                    "IPv6/TCP packet is truncated or has a wrong version");
        }
        int declaredPayloadLength =
                readU16(
                        packet,
                        4);
        if (declaredPayloadLength
                > packet.length
                - IPV6_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "IPv6 payload length does not match the packet");
        }
        int nextHeader = packet[6] & 0xff;
        int currentOffset = IPV6_HEADER_LENGTH;

        while (currentOffset < packet.length) {
            if (nextHeader == NEXT_HEADER_TCP) {
                break;
            }
            if (nextHeader == 0 || nextHeader == 43 || nextHeader == 60) {
                if (currentOffset + 2 > packet.length) {
                    throw new IllegalArgumentException("IPv6 extension header is truncated");
                }
                int extNextHeader = packet[currentOffset] & 0xff;
                int hdrExtLen = packet[currentOffset + 1] & 0xff;
                int extLen = (hdrExtLen + 1) * 8;
                if (currentOffset + extLen > packet.length) {
                    throw new IllegalArgumentException("IPv6 extension header length exceeds packet");
                }
                nextHeader = extNextHeader;
                currentOffset += extLen;
            } else if (nextHeader == 44) {
                if (currentOffset + 8 > packet.length) {
                    throw new IllegalArgumentException("IPv6 fragment header is truncated");
                }
                int extNextHeader = packet[currentOffset] & 0xff;
                nextHeader = extNextHeader;
                currentOffset += 8;
            } else if (nextHeader == 51) {
                if (currentOffset + 2 > packet.length) {
                    throw new IllegalArgumentException("IPv6 AH header is truncated");
                }
                int extNextHeader = packet[currentOffset] & 0xff;
                int hdrExtLen = packet[currentOffset + 1] & 0xff;
                int extLen = (hdrExtLen + 2) * 4;
                if (currentOffset + extLen > packet.length) {
                    throw new IllegalArgumentException("IPv6 AH header length exceeds packet");
                }
                nextHeader = extNextHeader;
                currentOffset += extLen;
            } else {
                throw new IllegalArgumentException(
                        "Baseline IPv6 packet carries unsupported protocol: " + nextHeader);
            }
        }

        if (nextHeader != NEXT_HEADER_TCP) {
            throw new IllegalArgumentException(
                    "Baseline IPv6 packet does not carry TCP directly");
        }

        int tcp = currentOffset;
        int extensionLength = tcp - IPV6_HEADER_LENGTH;
        int segmentLength = declaredPayloadLength - extensionLength;
        if (segmentLength < TCP_MINIMUM_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "IPv6 TCP segment is smaller than minimum header");
        }

        int tcpHeaderLength =
                ((packet[tcp + 12] & 0xf0) >>> 4)
                        * 4;
        if (tcpHeaderLength
                < TCP_MINIMUM_HEADER_LENGTH
                || tcpHeaderLength
                > TCP_MAXIMUM_HEADER_LENGTH
                || tcpHeaderLength
                > segmentLength) {
            throw new IllegalArgumentException(
                    "TCP data offset is invalid");
        }

        byte[] source =
                Arrays.copyOfRange(
                        packet,
                        8,
                        24);
        byte[] destination =
                Arrays.copyOfRange(
                        packet,
                        24,
                        40);
        try {
            int encodedChecksum =
                    readU16(
                            packet,
                            tcp + TCP_CHECKSUM_OFFSET);
            int expectedChecksum =
                    tcpChecksum(
                            source,
                            destination,
                            packet,
                            tcp,
                            segmentLength);
            if (verifyChecksum
                    && encodedChecksum != 0
                    && encodedChecksum != expectedChecksum) {
                throw new IllegalArgumentException(
                        "IPv6 TCP checksum verification failed");
            }

            int sourcePort =
                    readU16(
                            packet,
                            tcp);
            int destinationPort =
                    readU16(
                            packet,
                            tcp + 2);
            requirePort(
                    "source",
                    sourcePort);
            requirePort(
                    "destination",
                    destinationPort);
            long sequence =
                    readU32(
                            packet,
                            tcp + 4);
            long acknowledgement =
                    readU32(
                            packet,
                            tcp + 8);
            int flags =
                    (packet[tcp + 13] & 0xff)
                            | ((packet[tcp + 12] & 1)
                            != 0
                            ? FLAG_NS
                            : 0);
            int window =
                    readU16(
                            packet,
                            tcp + 14);
            int urgentPointer =
                    readU16(
                            packet,
                            tcp + 18);
            byte[] options =
                    Arrays.copyOfRange(
                            packet,
                            tcp + TCP_MINIMUM_HEADER_LENGTH,
                            tcp + tcpHeaderLength);
            byte[] payload =
                    Arrays.copyOfRange(
                            packet,
                            tcp + tcpHeaderLength,
                            tcp + segmentLength);
            return new Packet(
                    ipv6TrafficClass(
                            packet),
                    ipv6FlowLabel(
                            packet),
                    packet[7] & 0xff,
                    source,
                    destination,
                    sourcePort,
                    destinationPort,
                    sequence,
                    acknowledgement,
                    flags,
                    window,
                    urgentPointer,
                    options,
                    payload);
        } finally {
            wipe(
                    source);
            wipe(
                    destination);
        }
    }

    static final class PeerOptions {
        final int windowScale;
        final boolean sackPermitted;
        final long timestampValue;

        PeerOptions(int windowScale, boolean sackPermitted, long timestampValue) {
            this.windowScale = windowScale;
            this.sackPermitted = sackPermitted;
            this.timestampValue = timestampValue;
        }
    }

    static PeerOptions parsePeerOptions(byte[] options) {
        int windowScale = -1;
        boolean sack = false;
        long timestamp = -1L;
        if (options == null) {
            return new PeerOptions(windowScale, sack, timestamp);
        }
        int index = 0;
        while (index < options.length) {
            int kind = options[index] & 0xff;
            if (kind == 0) {
                break;
            }
            if (kind == 1) {
                index++;
                continue;
            }
            if (index + 1 >= options.length) {
                break;
            }
            int length = options[index + 1] & 0xff;
            if (length < 2 || index + length > options.length) {
                break;
            }
            if (kind == 3 && length == 3) {
                windowScale = options[index + 2] & 0xff;
            } else if (kind == 4 && length == 2) {
                sack = true;
            } else if (kind == 8 && length == 10) {
                timestamp = readU32(options, index + 2);
            }
            index += length;
        }
        return new PeerOptions(windowScale, sack, timestamp);
    }

    static byte[] encodeSynAckOptions(int maximumSegmentSize, PeerOptions peer) {
        requireU16("TCP maximum segment size", maximumSegmentSize);
        byte[] mss = encodeMssOption(maximumSegmentSize);
        int size = mss.length;
        boolean scale = peer != null && peer.windowScale >= 0;
        boolean sack = peer != null && peer.sackPermitted;
        boolean timestamps = peer != null && peer.timestampValue >= 0;
        if (scale) {
            size += 4;
        }
        if (timestamps) {
            size += 12;
        }
        if (sack) {
            size += 2;
        }
        byte[] options = new byte[(size + 3) & ~3];
        System.arraycopy(mss, 0, options, 0, mss.length);
        int offset = mss.length;
        if (scale) {
            options[offset++] = 1;
            options[offset++] = 3;
            options[offset++] = 3;
            options[offset++] = (byte) peer.windowScale;
        }
        if (timestamps) {
            options[offset++] = 1;
            options[offset++] = 1;
            offset = writeTimestamp(options, offset, peer.timestampValue);
        }
        if (sack) {
            options[offset++] = 4;
            options[offset] = 2;
        }
        return options;
    }

    /** iPhone SYN: MSS, window scale 5, timestamps, SACK, 4-byte aligned. */
    static byte[] encodeActiveSynOptions(int maximumSegmentSize) {
        return encodeSynAckOptions(
                maximumSegmentSize,
                new PeerOptions(5, true, 0));
    }

    static byte[] encodeTimestampOption(long echo) {
        byte[] options = new byte[12];
        options[0] = 1;
        options[1] = 1;
        writeTimestamp(options, 2, echo);
        return options;
    }

    private static int writeTimestamp(byte[] options, int offset, long echo) {
        options[offset++] = 8;
        options[offset++] = 10;
        long now = System.currentTimeMillis() & 0xffff_ffffL;
        writeU32(options, offset, now);
        writeU32(options, offset + 4, echo);
        return offset + 8;
    }

    static byte[] encodeMssOption(
            int maximumSegmentSize) {
        requireU16(
                "TCP maximum segment size",
                maximumSegmentSize);
        if (maximumSegmentSize == 0) {
            throw new IllegalArgumentException(
                    "TCP maximum segment size must be nonzero");
        }
        return new byte[]{
                2,
                4,
                (byte) (maximumSegmentSize >>> 8),
                (byte) maximumSegmentSize
        };
    }

    static String checksumFacts(byte[] packet) {
        int tcp = IPV6_HEADER_LENGTH;
        int tcpLength = packet.length - IPV6_HEADER_LENGTH;
        int stored = readU16(packet, tcp + TCP_CHECKSUM_OFFSET);
        byte[] source = Arrays.copyOfRange(packet, 8, 24);
        byte[] destination = Arrays.copyOfRange(packet, 24, 40);
        byte[] zeroAddress = new byte[16];
        int full = tcpChecksum(source, destination, packet, tcp, tcpLength);
        int bare = tcpChecksum(zeroAddress, zeroAddress, packet, tcp, tcpLength);
        int dataOffset = (packet[tcp + 12] & 0xf0) >>> 4;
        StringBuilder hit = new StringBuilder();
        if (tcpLength >= 20) {
            int payloadOnly = tcpChecksum(
                    source, destination, packet, tcp, tcpLength - 20);
            if (payloadOnly == stored) {
                hit.append("lenPayload ");
            }
        }
        int segmentWords = complementDifference(bare, tcpLength + NEXT_HEADER_TCP);
        if (segmentWords == stored) {
            hit.append("segment ");
        }
        byte[] raw = packet.clone();
        raw[tcp + TCP_CHECKSUM_OFFSET] = 0;
        raw[tcp + TCP_CHECKSUM_OFFSET + 1] = 0;
        int rawSum = IkeV2Codec.internetChecksum(raw);
        if (rawSum == stored) {
            hit.append("rawIpv6 ");
        }
        int little = littleEndianTcpChecksum(
                source, destination, packet, tcp, tcpLength);
        if (little == stored) {
            hit.append("little ");
        }
        byte[] mark0Src = source.clone();
        byte[] mark0Dst = destination.clone();
        mark0Src[9] = 0;
        mark0Dst[9] = 0;
        if (tcpChecksum(mark0Src, mark0Dst, packet, tcp, tcpLength) == stored) {
            hit.append("mark0 ");
        }
        if (tcpChecksum(mark0Dst, mark0Src, packet, tcp, tcpLength) == stored) {
            hit.append("mark0swap ");
        }
        int matchedLength = -1;
        int matchedSwappedLength = -1;
        int matchedClassCLength = -1;
        int matchedClassCSwappedLength = -1;
        byte[] classCSource = source.clone();
        byte[] classCDestination = destination.clone();
        classCSource[9] = AppleNetworkRelayInnerAddresses.CLASS_C_MARKER;
        classCDestination[9] = AppleNetworkRelayInnerAddresses.CLASS_C_MARKER;
        for (int length = 0; length <= tcpLength; length++) {
            if (matchedLength < 0
                    && tcpChecksum(source, destination, packet, tcp, length) == stored) {
                matchedLength = length;
            }
            if (matchedSwappedLength < 0
                    && tcpChecksum(destination, source, packet, tcp, length) == stored) {
                matchedSwappedLength = length;
            }
            if (matchedClassCLength < 0
                    && tcpChecksum(classCSource, classCDestination, packet, tcp, length) == stored) {
                matchedClassCLength = length;
            }
            if (matchedClassCSwappedLength < 0
                    && tcpChecksum(classCDestination, classCSource, packet, tcp, length) == stored) {
                matchedClassCSwappedLength = length;
            }
        }
        int head = Math.min(8, packet.length - (tcp + TCP_MINIMUM_HEADER_LENGTH));
        StringBuilder payloadHex = new StringBuilder();
        for (int index = 0; index < head; index++) {
            payloadHex.append(String.format(
                    java.util.Locale.US,
                    "%02x",
                    packet[tcp + TCP_MINIMUM_HEADER_LENGTH + index] & 0xff));
        }
        byte[] linkLocal = new byte[]{
                (byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0,
                (byte) 0xa0, (byte) 0xd4, (byte) 0x8b, 0x6b,
                0x5e, (byte) 0xe7, (byte) 0xde, 0x70
        };
        String linkHit = "none";
        if (tcpChecksum(linkLocal, destination, packet, tcp, tcpLength) == stored) {
            linkHit = "ll-dst";
        } else if (tcpChecksum(linkLocal, source, packet, tcp, tcpLength) == stored) {
            linkHit = "ll-src";
        } else if (tcpChecksum(destination, linkLocal, packet, tcp, tcpLength) == stored) {
            linkHit = "dst-ll";
        } else if (tcpChecksum(source, linkLocal, packet, tcp, tcpLength) == stored) {
            linkHit = "src-ll";
        }
        return String.format(
                java.util.Locale.US,
                "stored=%04x full=%04x len=%d hit=%s matchLen=%d matchSwapLen=%d matchC=%d matchCSwap=%d link=%s",
                stored,
                full,
                tcpLength,
                hit.length() == 0 ? "none" : hit.toString().trim(),
                matchedLength,
                matchedSwappedLength,
                matchedClassCLength,
                matchedClassCSwappedLength,
                linkHit);
    }

    /** Which one 16-bit address word makes the stored checksum match. */
    private static String findSingleAddressWord(
            byte[] packet,
            byte[] source,
            byte[] destination,
            int tcp,
            int tcpLength,
            int stored) {
        StringBuilder found = new StringBuilder();
        byte[] src = source.clone();
        byte[] dst = destination.clone();
        try {
            for (int which = 0; which < 2; which++) {
                byte[] addr = which == 0 ? src : dst;
                String name = which == 0 ? "src" : "dst";
                for (int word = 0; word < 8; word++) {
                    int old = ((addr[word * 2] & 0xff) << 8)
                            | (addr[word * 2 + 1] & 0xff);
                    addr[word * 2] = 0;
                    addr[word * 2 + 1] = 0;
                    int baseSum = (~tcpChecksum(src, dst, packet, tcp, tcpLength)) & 0xffff;
                    addr[word * 2] = (byte) (old >>> 8);
                    addr[word * 2 + 1] = (byte) old;
                    for (int value = 0; value <= 0xffff; value++) {
                        if (value == old) {
                            continue;
                        }
                        int sum = baseSum + value;
                        sum = (sum & 0xffff) + (sum >>> 16);
                        if (((~sum) & 0xffff) == stored) {
                            found.append(name)
                                    .append(word)
                                    .append('=')
                                    .append(String.format(java.util.Locale.US, "%04x", value))
                                    .append(' ');
                            break;
                        }
                    }
                }
            }
        } finally {
            Arrays.fill(src, (byte) 0);
            Arrays.fill(dst, (byte) 0);
        }
        return found.length() == 0 ? "none" : found.toString().trim();
    }

    private static int complementDifference(int checksum, int subtract) {
        int sum = (~checksum) & 0xffff;
        int adjusted = sum - (subtract & 0xffff);
        while (adjusted < 0) {
            adjusted += 0xffff;
        }
        while ((adjusted >>> 16) != 0) {
            adjusted = (adjusted & 0xffff) + (adjusted >>> 16);
        }
        return (~adjusted) & 0xffff;
    }

    private static int littleEndianTcpChecksum(
            byte[] sourceAddress,
            byte[] destinationAddress,
            byte[] packet,
            int tcpOffset,
            int tcpLength) {
        byte[] segment = Arrays.copyOfRange(
                packet, tcpOffset, tcpOffset + tcpLength);
        segment[TCP_CHECKSUM_OFFSET] = 0;
        segment[TCP_CHECKSUM_OFFSET + 1] = 0;
        long sum = 0;
        sum = addLittleWords(sum, sourceAddress, 0, sourceAddress.length);
        sum = addLittleWords(sum, destinationAddress, 0, destinationAddress.length);
        sum += (tcpLength >>> 16) & 0xffffL;
        sum += tcpLength & 0xffffL;
        sum += NEXT_HEADER_TCP;
        sum = addLittleWords(sum, segment, 0, segment.length);
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffffL) + (sum >>> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private static long addLittleWords(long sum, byte[] bytes, int offset, int length) {
        int end = offset + length;
        for (int index = offset; index + 1 < end; index += 2) {
            sum += (bytes[index] & 0xff) | ((bytes[index + 1] & 0xff) << 8);
        }
        if (((length) & 1) != 0) {
            sum += bytes[end - 1] & 0xff;
        }
        return sum;
    }

    /**
     * Live 22:54: the Watch leaves the TCP checksum as the uncomplemented
     * sum of the IPv6 pseudo-header only. For the setup control segment that
     * sum is {@code 0x5f37}, exactly addresses + length 259 + protocol 6.
     * The payload is not included and the sum is not complemented.
     */
    static void writeOffloadPseudoChecksum(byte[] packet) {
        if (packet == null
                || packet.length < IPV6_HEADER_LENGTH + TCP_MINIMUM_HEADER_LENGTH) {
            return;
        }
        int tcpLength = packet.length - IPV6_HEADER_LENGTH;
        long sum = 0;
        for (int index = 8; index < 40; index += 2) {
            sum += ((packet[index] & 0xff) << 8)
                    | (packet[index + 1] & 0xff);
        }
        sum += tcpLength & 0xffffL;
        sum += NEXT_HEADER_TCP;
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffffL) + (sum >>> 16);
        }
        writeU16(packet, IPV6_HEADER_LENGTH + TCP_CHECKSUM_OFFSET, (int) sum);
    }

    static void updateTcpChecksum(byte[] packet) {
        if (packet == null || packet.length < IPV6_HEADER_LENGTH + TCP_MINIMUM_HEADER_LENGTH) {
            return;
        }
        byte[] src = Arrays.copyOfRange(packet, 8, 24);
        byte[] dst = Arrays.copyOfRange(packet, 24, 40);
        int tcpLength = packet.length - IPV6_HEADER_LENGTH;
        int checksum = tcpChecksum(src, dst, packet, IPV6_HEADER_LENGTH, tcpLength);
        writeU16(packet, IPV6_HEADER_LENGTH + TCP_CHECKSUM_OFFSET, checksum);
    }

    private static int tcpChecksum(
            byte[] sourceAddress,
            byte[] destinationAddress,
            byte[] packet,
            int tcpOffset,
            int tcpLength) {
        long sum = 0;
        sum =
                addWords(
                        sum,
                        sourceAddress,
                        0,
                        sourceAddress.length,
                        -1,
                        -1);
        sum =
                addWords(
                        sum,
                        destinationAddress,
                        0,
                        destinationAddress.length,
                        -1,
                        -1);
        sum += (tcpLength >>> 16) & 0xffffL;
        sum += tcpLength & 0xffffL;
        sum += NEXT_HEADER_TCP;
        sum =
                addWords(
                        sum,
                        packet,
                        tcpOffset,
                        tcpLength,
                        tcpOffset + TCP_CHECKSUM_OFFSET,
                        tcpOffset + TCP_CHECKSUM_OFFSET + 1);
        while ((sum >>> 16) != 0) {
            sum =
                    (sum & 0xffffL)
                            + (sum >>> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private static long addWords(
            long sum,
            byte[] bytes,
            int offset,
            int length,
            int zeroOffsetA,
            int zeroOffsetB) {
        int end = offset + length;
        for (int index = offset;
                index < end;
                index += 2) {
            int high =
                    index == zeroOffsetA
                            || index == zeroOffsetB
                            ? 0
                            : bytes[index] & 0xff;
            int low = 0;
            if (index + 1 < end) {
                low =
                        index + 1 == zeroOffsetA
                                || index + 1 == zeroOffsetB
                                ? 0
                                : bytes[index + 1] & 0xff;
            }
            sum += (high << 8) | low;
        }
        return sum;
    }

    private static int ipv6TrafficClass(
            byte[] packet) {
        return ((packet[0] & 0x0f) << 4)
                | ((packet[1] & 0xf0) >>> 4);
    }

    private static int ipv6FlowLabel(
            byte[] packet) {
        return ((packet[1] & 0x0f) << 16)
                | ((packet[2] & 0xff) << 8)
                | (packet[3] & 0xff);
    }

    private static int readU16(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private static long readU32(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xffL) << 24)
                | ((bytes[offset + 1] & 0xffL) << 16)
                | ((bytes[offset + 2] & 0xffL) << 8)
                | (bytes[offset + 3] & 0xffL);
    }

    private static void writeU16(
            byte[] bytes,
            int offset,
            int value) {
        bytes[offset] =
                (byte) (value >>> 8);
        bytes[offset + 1] =
                (byte) value;
    }

    private static void writeU32(
            byte[] bytes,
            int offset,
            long value) {
        bytes[offset] =
                (byte) (value >>> 24);
        bytes[offset + 1] =
                (byte) (value >>> 16);
        bytes[offset + 2] =
                (byte) (value >>> 8);
        bytes[offset + 3] =
                (byte) value;
    }

    private static void requireAddress(
            String label,
            byte[] address) {
        if (address == null
                || address.length != ADDRESS_LENGTH) {
            throw new IllegalArgumentException(
                    "IPv6 "
                            + label
                            + " address must be 16 bytes");
        }
    }

    private static void requirePort(
            String label,
            int port) {
        if (port <= 0
                || port > 0xffff) {
            throw new IllegalArgumentException(
                    "TCP "
                            + label
                            + " port must be 1..65535");
        }
    }

    private static void requireU16(
            String label,
            int value) {
        if (value < 0
                || value > 0xffff) {
            throw new IllegalArgumentException(
                    label + " must fit uint16");
        }
    }

    private static void requireU32(
            String label,
            long value) {
        if (value < 0
                || value > 0xffff_ffffL) {
            throw new IllegalArgumentException(
                    label + " must fit uint32");
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

    static final class Packet
            implements AutoCloseable {
        final int trafficClass;
        final int flowLabel;
        final int hopLimit;
        final byte[] sourceAddress;
        final byte[] destinationAddress;
        final int sourcePort;
        final int destinationPort;
        final long sequence;
        final long acknowledgement;
        final int flags;
        final int window;
        final int urgentPointer;
        final byte[] options;
        final byte[] payload;
        private boolean closed;

        Packet(
                int trafficClass,
                int flowLabel,
                int hopLimit,
                byte[] sourceAddress,
                byte[] destinationAddress,
                int sourcePort,
                int destinationPort,
                long sequence,
                long acknowledgement,
                int flags,
                int window,
                int urgentPointer,
                byte[] options,
                byte[] payload) {
            this.trafficClass = trafficClass;
            this.flowLabel = flowLabel;
            this.hopLimit = hopLimit;
            this.sourceAddress =
                    sourceAddress.clone();
            this.destinationAddress =
                    destinationAddress.clone();
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.sequence = sequence;
            this.acknowledgement = acknowledgement;
            this.flags = flags;
            this.window = window;
            this.urgentPointer = urgentPointer;
            this.options = options.clone();
            this.payload = payload.clone();
        }

        boolean hasFlag(
                int flag) {
            requireOpen();
            if ((flag & ~ALL_FLAGS) != 0
                    || flag == 0) {
                throw new IllegalArgumentException(
                        "TCP flag mask is invalid");
            }
            return (flags & flag) == flag;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Decoded IPv6/TCP packet is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    sourceAddress);
            wipe(
                    destinationAddress);
            wipe(
                    options);
            wipe(
                    payload);
        }
    }
}
