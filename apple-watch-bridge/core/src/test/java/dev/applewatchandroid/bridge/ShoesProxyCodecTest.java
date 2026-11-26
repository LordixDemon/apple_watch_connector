package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class ShoesProxyCodecTest {
    @Test
    public void roundTripsShoesHeaderAndTrailingTlsBytes() {
        byte[] header = ShoesProxyCodec.encodeHeader("gsa.apple.com", 443, "com.apple.mobileassetd");
        byte[] trailing = new byte[]{0x16, 0x03, 0x01, 0x00, 0x05};
        byte[] combined = new byte[header.length + trailing.length];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(trailing, 0, combined, header.length, trailing.length);

        ShoesProxyCodec.ParseResult result = ShoesProxyCodec.parse(combined);
        assertEquals(ShoesProxyCodec.ParseStatus.COMPLETE, result.status);
        try (ShoesProxyCodec.Header parsed = result.header) {
            assertEquals("gsa.apple.com", parsed.host);
            assertEquals(443, parsed.port);
            assertEquals("com.apple.mobileassetd", parsed.service);
            assertArrayEquals(trailing, parsed.trailingBytes());
        }
    }

    @Test
    public void returnsNeedMoreForFragmentedHeadersAndRejectsMalformedOnes() {
        byte[] header = ShoesProxyCodec.encodeHeader("mesu.apple.com", 443, "com.apple.nsurlsessiond");
        assertEquals(ShoesProxyCodec.ParseStatus.NEED_MORE, ShoesProxyCodec.parse(new byte[0]).status);
        assertEquals(ShoesProxyCodec.ParseStatus.NEED_MORE, ShoesProxyCodec.parse(Arrays.copyOf(header, 1)).status);
        assertEquals(ShoesProxyCodec.ParseStatus.NEED_MORE, ShoesProxyCodec.parse(Arrays.copyOf(header, 6)).status);
        assertEquals(ShoesProxyCodec.ParseStatus.NEED_MORE,
                ShoesProxyCodec.parse(Arrays.copyOf(header, header.length - 1)).status);

        byte[] wrongVersion = header.clone();
        wrongVersion[2] = 2;
        assertEquals(ShoesProxyCodec.ParseStatus.INVALID, ShoesProxyCodec.parse(wrongVersion).status);

        byte[] wrongKind = header.clone();
        wrongKind[6 + "mesu.apple.com".length()] = 4;
        assertEquals(ShoesProxyCodec.ParseStatus.INVALID, ShoesProxyCodec.parse(wrongKind).status);

        byte[] oversized = new byte[]{0x08, 0x01, 0x01, 0x01, (byte) 0xbb};
        assertEquals(ShoesProxyCodec.ParseStatus.INVALID, ShoesProxyCodec.parse(oversized).status);
    }

    @Test
    public void blocksPrivateLoopbackLinkLocalAndAllowsPublicTargets() throws Exception {
        for (String blocked : new String[]{
                "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
                "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1",
                "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1"}) {
            assertFalse("Expected blocked: " + blocked,
                    ShoesProxyCodec.isSafePublicTarget(InetAddress.getByName(blocked)));
        }
        for (String allowed : new String[]{"17.253.144.10", "1.1.1.1", "2606:4700:4700::1111"}) {
            assertTrue("Expected allowed: " + allowed,
                    ShoesProxyCodec.isSafePublicTarget(InetAddress.getByName(allowed)));
        }
    }

    @Test
    public void flowOpenMatchesCapturedWireAck() {
        assertArrayEquals(
                new byte[]{0x00, 0x06, 0x00, 0x00, 0x04, 0x00, 0x01, 0x20},
                ShoesProxyCodec.flowOpenBytes());
        assertEquals(62742, ShoesProxyCodec.PROXY_PORT);
        assertEquals("com.apple.private.alloy.appsyncconduit.v2",
                AppSyncConduitCodec.TOPIC);
    }
}
