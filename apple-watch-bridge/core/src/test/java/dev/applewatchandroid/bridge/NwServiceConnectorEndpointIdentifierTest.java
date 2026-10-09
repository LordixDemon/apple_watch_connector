package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class NwServiceConnectorEndpointIdentifierTest {
    @Test
    public void ipv6UsesCompressedLowercaseAddressDotPort() {
        assertEquals(
                "fd12:3456::abcd.61314",
                NwServiceConnectorEndpointIdentifier.ipv6(
                        new byte[]{
                                (byte) 0xfd, 0x12, 0x34, 0x56,
                                0, 0, 0, 0,
                                0, 0, 0, 0,
                                0, 0, (byte) 0xab, (byte) 0xcd
                        },
                        61314));
    }

    @Test
    public void ipv6CompressionUsesFirstLongestRun() {
        assertEquals(
                "2001::1:0:0:1:1.61315",
                NwServiceConnectorEndpointIdentifier.ipv6(
                        new byte[]{
                                0x20, 0x01, 0, 0,
                                0, 0, 0, 1,
                                0, 0, 0, 0,
                                0, 1, 0, 1
                        },
                        61315));
        assertEquals(
                "::.1",
                NwServiceConnectorEndpointIdentifier.ipv6(
                        new byte[16],
                        1));
    }

    @Test
    public void ipv4UsesColonSeparator() {
        assertEquals(
                "192.0.2.7:61314",
                NwServiceConnectorEndpointIdentifier.ipv4(
                        new byte[]{
                                (byte) 192, 0, 2, 7
                        },
                        61314));
        assertThrows(
                IllegalArgumentException.class,
                () -> NwServiceConnectorEndpointIdentifier.ipv6(
                        new byte[15],
                        61314));
    }
}
