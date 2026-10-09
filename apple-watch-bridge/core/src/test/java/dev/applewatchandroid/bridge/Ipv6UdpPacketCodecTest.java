package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class Ipv6UdpPacketCodecTest {
    private static final byte[] A = {0x20,1,0x0d,(byte)0xb8,0,0,0,0,0,0,0,0,0,0,0,1};
    private static final byte[] B = {0x20,1,0x0d,(byte)0xb8,0,0,0,0,0,0,0,0,0,0,0,2};
    @Test public void verifiesMandatoryChecksumWithOddAndMaximumDatagrams() {
        for (int length : new int[]{1, 3, 1200}) {
            byte[] input = new byte[length]; Arrays.fill(input, (byte)0xa5);
            byte[] wire = Ipv6UdpPacketCodec.encode(A, B, 50000, 65275, input);
            var packet = Ipv6UdpPacketCodec.decode(wire);
            assertArrayEquals(input, packet.data()); assertArrayEquals(A, packet.source());
            assertEquals(65275, packet.destinationPort());
            wire[wire.length - 1] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> Ipv6UdpPacketCodec.decode(wire));
        }
    }
    @Test public void rejectsAddressPortLengthAndChecksumCorruption() {
        byte[] original = Ipv6UdpPacketCodec.encode(A, B, 50000, 65275, new byte[]{1,2,3});
        for (int offset : new int[]{0, 4, 6, 8, 24, 40, 42, 44, 46, 48}) {
            byte[] bad = original.clone(); bad[offset] ^= offset == 0 ? 0x10 : 1;
            assertThrows(IllegalArgumentException.class, () -> Ipv6UdpPacketCodec.decode(bad));
        }
        byte[] missing = original.clone(); missing[46] = 0; missing[47] = 0;
        assertThrows(IllegalArgumentException.class, () -> Ipv6UdpPacketCodec.decode(missing));
        assertThrows(IllegalArgumentException.class,
                () -> Ipv6UdpPacketCodec.encode(A, B, 0, 1, new byte[]{1}));
    }
}
