package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class LinkDirectorMessageCodecTest {
    @Test
    public void matchesWatch262HelloAndScalableLinkStateLayout() {
        // NRLinkDirectorMessage v2: u16 TLV size, reserved u32, big-endian u64 ID.
        assertArrayEquals(new byte[] {
                2, 0, 0, 3, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 1, 0, 0},
                LinkDirectorMessageCodec.encode(0x0102030405060708L, LinkDirectorMessageCodec.HELLO));
        assertArrayEquals(new byte[] {
                2, 0, 0, 4, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 9, 6, 0, 1, 1},
                LinkDirectorMessageCodec.encode(0x0102030405060709L, LinkDirectorMessageCodec.DEVICE_LINK_STATE));
    }

    @Test
    public void seedsFromBootEpochAndHalfContinuousUptime() {
        assertEquals(1_800_000_000_500_000L,
                LinkDirectorMessageCodec.sequenceSeed(1_800_000_001_000L, 1_000_000_000L));
        assertThrows(IllegalArgumentException.class, () -> LinkDirectorMessageCodec.sequenceSeed(0, 0));
        assertThrows(ArithmeticException.class, () -> LinkDirectorMessageCodec.sequenceSeed(Long.MAX_VALUE, 0));
    }

    @Test
    public void refusesUnimplementedAnnouncementsAndInvalidIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> LinkDirectorMessageCodec.encode(0, 1));
        assertThrows(IllegalArgumentException.class, () -> LinkDirectorMessageCodec.encode(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> LinkDirectorMessageCodec.encode(1, 23));
    }
}
