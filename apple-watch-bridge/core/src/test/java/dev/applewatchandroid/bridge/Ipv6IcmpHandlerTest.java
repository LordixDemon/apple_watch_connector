package dev.applewatchandroid.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class Ipv6IcmpHandlerTest {

    @Test
    public void ignoresNonIpv6OrShortPackets() {
        assertNull(Ipv6IcmpHandler.handle(null));
        assertNull(Ipv6IcmpHandler.handle(new byte[10]));
        byte[] nonIpv6 = new byte[64];
        nonIpv6[0] = 0x40; // IPv4
        assertNull(Ipv6IcmpHandler.handle(nonIpv6));
    }

    @Test
    public void respondsToNeighborSolicitation() {
        byte[] srcIp = new byte[]{
                (byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        };
        byte[] dstIp = new byte[]{
                (byte) 0xff, 0x02, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0x01, (byte) 0xff, 0x06, 0x07, 0x09
        };
        byte[] targetIp = new byte[]{
                (byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x09
        };

        byte[] nsPacket = new byte[64];
        nsPacket[0] = 0x60; // IPv6
        nsPacket[4] = 0; // Payload len MSB
        nsPacket[5] = 24; // Payload len LSB (24 bytes)
        nsPacket[6] = 58; // Next Header ICMPv6
        nsPacket[7] = (byte) 255; // Hop limit
        System.arraycopy(srcIp, 0, nsPacket, 8, 16);
        System.arraycopy(dstIp, 0, nsPacket, 24, 16);

        nsPacket[40] = (byte) 135; // Type Neighbor Solicitation
        nsPacket[41] = 0; // Code 0
        System.arraycopy(targetIp, 0, nsPacket, 48, 16);

        byte[] reply = Ipv6IcmpHandler.handle(nsPacket);
        assertNotNull(reply);
        assertEquals(64, reply.length);
        assertEquals(0x60, reply[0] & 0xf0);
        assertEquals(58, reply[6] & 0xff); // Next Header ICMPv6
        assertEquals((byte) 136, reply[40]); // Type Neighbor Advertisement
        assertEquals(0, reply[41]); // Code 0
        assertEquals(0x60, reply[44] & 0xff); // Flags: Solicited=1, Override=1
    }

    @Test
    public void respondsToEchoRequest() {
        byte[] srcIp = new byte[]{
                (byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        };
        byte[] dstIp = new byte[]{
                (byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x09
        };

        byte[] ping = new byte[48];
        ping[0] = 0x60;
        ping[5] = 8; // 8 bytes payload
        ping[6] = 58;
        ping[7] = 64;
        System.arraycopy(srcIp, 0, ping, 8, 16);
        System.arraycopy(dstIp, 0, ping, 24, 16);
        ping[40] = (byte) 128; // Echo Request
        ping[41] = 0;

        byte[] reply = Ipv6IcmpHandler.handle(ping);
        assertNotNull(reply);
        assertEquals(48, reply.length);
        assertEquals((byte) 129, reply[40]); // Echo Reply
    }

    @Test
    public void unsolicitedNeighborAdvertisementUsesAllNodesAndOverride() {
        byte[] local = {
                (byte) 0xfd, 0x74, 0x65, 0x72, 0x6d, 0x6e, 0x75, 0x73,
                0, 0x0d, (byte) 0xc9, 0x32, 0x4f, 0x0b, 0x72, 0x64
        };
        byte[] reply = Ipv6IcmpHandler.unsolicitedNeighborAdvertisement(local);
        assertEquals(64, reply.length);
        assertEquals((byte) 136, reply[40]);
        assertEquals(0x20, reply[44] & 0xff);
        assertEquals((byte) 0xff, reply[24]);
        assertEquals(0x01, reply[39] & 0xff);
        byte[] watchLinkLocal = {
                (byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0,
                (byte) 0xa0, (byte) 0xd4, (byte) 0x8b, 0x6b,
                0x5e, (byte) 0xe7, (byte) 0xde, 0x70
        };
        byte[] directed = Ipv6IcmpHandler.unsolicitedNeighborAdvertisement(local, watchLinkLocal);
        assertEquals((byte) 0xfe, directed[24]);
        assertEquals((byte) 0xde, directed[38]);
        assertEquals(0x70, directed[39] & 0xff);
        byte[] classDRemote = {
                (byte) 0xfd, 0x74, 0x65, 0x72, 0x6d, 0x6e, 0x75, 0x73,
                0, 0x0d, (byte) 0x93, 0x15, 0x0d, 0x5b, (byte) 0xa7, 0x6e
        };
        byte[] inner = Ipv6IcmpHandler.unsolicitedNeighborAdvertisement(local, classDRemote);
        assertEquals((byte) 0xfd, inner[24]);
        assertEquals((byte) 0x93, inner[34]);
        assertEquals((byte) 136, inner[40]);
        // Paired post-LDM announce uses Class-D unicast NA (0.2.295), not
        // hardcoded fe80 over Class-C only.
        assertEquals((byte) 0xfd, inner[8]);
        assertEquals((byte) 0xc9, inner[18]);
    }
}
