package dev.applewatchandroid.bridge;

import java.util.Arrays;

/**
 * Handles ICMPv6 control messages (Neighbor Discovery and Echo) over IPsec tunnels.
 *
 * <p>watchOS Darwin kernel sends ICMPv6 Neighbor Solicitations (RFC 4861) across
 * the {@code utun} interface to verify IPv6 neighbor reachability. Without a valid
 * Neighbor Advertisement, the tunnel neighbor state remains {@code INCOMPLETE} and
 * kernel networking eventually times out the connection.</p>
 */
final class Ipv6IcmpHandler {
    static final int IPV6_HEADER_LENGTH = 40;
    static final int NEXT_HEADER_ICMPV6 = 58;

    static final int ICMPV6_TYPE_ECHO_REQUEST = 128;
    static final int ICMPV6_TYPE_ECHO_REPLY = 129;
    static final int ICMPV6_TYPE_NEIGHBOR_SOLICITATION = 135;
    static final int ICMPV6_TYPE_NEIGHBOR_ADVERTISEMENT = 136;

    private Ipv6IcmpHandler() {
    }

    /**
     * Inspects an inbound clear IPv6 packet and generates an ICMPv6 response if needed.
     *
     * @param clearIpv6 the inbound clear IPv6 packet
     * @return a response IPv6 packet, or {@code null} if not handled
     */
    static byte[] handle(byte[] clearIpv6) {
        if (clearIpv6 == null || clearIpv6.length < IPV6_HEADER_LENGTH + 4) {
            return null;
        }
        if ((clearIpv6[0] & 0xf0) != 0x60) {
            return null; // Not IPv6
        }
        int nextHeader = clearIpv6[6] & 0xff;
        if (nextHeader != NEXT_HEADER_ICMPV6) {
            return null; // Not ICMPv6
        }
        int payloadLength = ((clearIpv6[4] & 0xff) << 8) | (clearIpv6[5] & 0xff);
        if (clearIpv6.length < IPV6_HEADER_LENGTH + payloadLength) {
            return null;
        }

        int icmpType = clearIpv6[IPV6_HEADER_LENGTH] & 0xff;
        if (icmpType == ICMPV6_TYPE_NEIGHBOR_SOLICITATION) {
            return handleNeighborSolicitation(clearIpv6, payloadLength);
        } else if (icmpType == ICMPV6_TYPE_ECHO_REQUEST) {
            return handleEchoRequest(clearIpv6, payloadLength);
        }
        return null;
    }

    /**
     * Unsolicited Neighbor Advertisement for the local Class-D address,
     * sent to the Watch link-local that emits MLDv2. All-nodes NA is
     * ignored on this path; live reconnect never solicits.
     */
    static byte[] unsolicitedNeighborAdvertisement(byte[] localAddress, byte[] destination) {
        if (localAddress == null || localAddress.length != 16
                || destination == null || destination.length != 16) {
            throw new IllegalArgumentException("Local and destination IPv6 addresses are required");
        }
        byte[] payload = new byte[24];
        payload[0] = (byte) ICMPV6_TYPE_NEIGHBOR_ADVERTISEMENT;
        payload[4] = 0x20;
        System.arraycopy(localAddress, 0, payload, 8, 16);
        int checksum = calculateIcmpv6Checksum(localAddress, destination, payload, payload.length);
        payload[2] = (byte) (checksum >>> 8);
        payload[3] = (byte) checksum;
        byte[] packet = new byte[IPV6_HEADER_LENGTH + payload.length];
        packet[0] = 0x60;
        packet[5] = (byte) payload.length;
        packet[6] = (byte) NEXT_HEADER_ICMPV6;
        packet[7] = (byte) 255;
        System.arraycopy(localAddress, 0, packet, 8, 16);
        System.arraycopy(destination, 0, packet, 24, 16);
        System.arraycopy(payload, 0, packet, IPV6_HEADER_LENGTH, payload.length);
        return packet;
    }

    static byte[] unsolicitedNeighborAdvertisement(byte[] localAddress) {
        byte[] allNodes = {
                (byte) 0xff, 0x02, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0x01
        };
        return unsolicitedNeighborAdvertisement(localAddress, allNodes);
    }

    private static byte[] handleNeighborSolicitation(byte[] clearIpv6, int payloadLength) {
        if (payloadLength < 24) {
            return null; // NS must be at least 24 bytes (4 header + 4 reserved + 16 target)
        }
        byte[] srcIp = Arrays.copyOfRange(clearIpv6, 8, 24);
        byte[] targetIp = Arrays.copyOfRange(clearIpv6, IPV6_HEADER_LENGTH + 8, IPV6_HEADER_LENGTH + 24);

        boolean unspecifiedSrc = true;
        for (byte b : srcIp) {
            if (b != 0) {
                unspecifiedSrc = false;
                break;
            }
        }
        byte[] replyDstIp = unspecifiedSrc
                ? new byte[]{(byte) 0xff, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01} // All-nodes multicast ff02::1
                : srcIp;
        byte[] replySrcIp = targetIp;

        int naPayloadLen = 24;
        byte[] naPayload = new byte[naPayloadLen];
        naPayload[0] = (byte) ICMPV6_TYPE_NEIGHBOR_ADVERTISEMENT;
        naPayload[1] = 0; // Code = 0
        naPayload[2] = 0; // Checksum placeholder
        naPayload[3] = 0;
        // Flags: Solicited = 1 (if not unspecified), Override = 1 -> 0x60 or 0x20
        naPayload[4] = (byte) (unspecifiedSrc ? 0x20 : 0x60);
        naPayload[5] = 0;
        naPayload[6] = 0;
        naPayload[7] = 0;
        System.arraycopy(targetIp, 0, naPayload, 8, 16);

        int checksum = calculateIcmpv6Checksum(replySrcIp, replyDstIp, naPayload, naPayloadLen);
        naPayload[2] = (byte) ((checksum >> 8) & 0xff);
        naPayload[3] = (byte) (checksum & 0xff);

        byte[] packet = new byte[IPV6_HEADER_LENGTH + naPayloadLen];
        packet[0] = 0x60; // Version 6
        packet[4] = (byte) ((naPayloadLen >> 8) & 0xff);
        packet[5] = (byte) (naPayloadLen & 0xff);
        packet[6] = (byte) NEXT_HEADER_ICMPV6;
        packet[7] = (byte) 255; // Hop limit 255 for ND
        System.arraycopy(replySrcIp, 0, packet, 8, 16);
        System.arraycopy(replyDstIp, 0, packet, 24, 16);
        System.arraycopy(naPayload, 0, packet, IPV6_HEADER_LENGTH, naPayloadLen);

        return packet;
    }

    private static byte[] handleEchoRequest(byte[] clearIpv6, int payloadLength) {
        byte[] replySrcIp = Arrays.copyOfRange(clearIpv6, 24, 40);
        byte[] replyDstIp = Arrays.copyOfRange(clearIpv6, 8, 24);

        byte[] echoPayload = Arrays.copyOfRange(clearIpv6, IPV6_HEADER_LENGTH, IPV6_HEADER_LENGTH + payloadLength);
        echoPayload[0] = (byte) ICMPV6_TYPE_ECHO_REPLY;
        echoPayload[2] = 0;
        echoPayload[3] = 0;

        int checksum = calculateIcmpv6Checksum(replySrcIp, replyDstIp, echoPayload, payloadLength);
        echoPayload[2] = (byte) ((checksum >> 8) & 0xff);
        echoPayload[3] = (byte) (checksum & 0xff);

        byte[] packet = new byte[IPV6_HEADER_LENGTH + payloadLength];
        packet[0] = 0x60;
        packet[4] = (byte) ((payloadLength >> 8) & 0xff);
        packet[5] = (byte) (payloadLength & 0xff);
        packet[6] = (byte) NEXT_HEADER_ICMPV6;
        packet[7] = 64; // Hop limit
        System.arraycopy(replySrcIp, 0, packet, 8, 16);
        System.arraycopy(replyDstIp, 0, packet, 24, 16);
        System.arraycopy(echoPayload, 0, packet, IPV6_HEADER_LENGTH, payloadLength);

        return packet;
    }

    private static int calculateIcmpv6Checksum(byte[] srcIp, byte[] dstIp, byte[] icmpPayload, int length) {
        long sum = 0;
        for (int i = 0; i < 16; i += 2) {
            sum += ((srcIp[i] & 0xff) << 8) | (srcIp[i + 1] & 0xff);
            sum += ((dstIp[i] & 0xff) << 8) | (dstIp[i + 1] & 0xff);
        }
        sum += (length >> 16) & 0xffff;
        sum += length & 0xffff;
        sum += NEXT_HEADER_ICMPV6;

        for (int i = 0; i < length; i += 2) {
            int word;
            if (i + 1 < length) {
                word = ((icmpPayload[i] & 0xff) << 8) | (icmpPayload[i + 1] & 0xff);
            } else {
                word = (icmpPayload[i] & 0xff) << 8;
            }
            sum += word;
        }

        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum & 0xffff);
    }
}
