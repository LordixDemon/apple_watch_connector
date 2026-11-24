package dev.applewatchandroid.bridge;

import org.junit.Test;

import static org.junit.Assert.*;

public final class HalGattServerTest {

    @Test
    public void respondsToWatchGattDiscoveryAndReads() {
        byte[] mtuRsp = HalGattServer.handleRequest(new byte[]{0x02, (byte) 0xFB, 0x00});
        assertArrayEquals(new byte[]{0x03, (byte) 0xFB, 0x00}, mtuRsp);

        byte[] groupRsp = HalGattServer.handleRequest(new byte[]{0x10, 0x01, 0x00, (byte) 0xFF, (byte) 0xFF, 0x00, 0x28});
        assertNotNull(groupRsp);
        assertEquals(0x11, groupRsp[0] & 0xff);

        byte[] readName = HalGattServer.handleRequest(new byte[]{0x0A, 0x03, 0x00});
        assertArrayEquals(new byte[]{0x0B, 'i', 'P', 'h', 'o', 'n', 'e'}, readName);

        byte[] writeRsp = HalGattServer.handleRequest(new byte[]{0x12, 0x14, 0x00, 0x01, 0x00});
        assertArrayEquals(new byte[]{0x13}, writeRsp);

        byte[] indCfm = HalGattServer.handleRequest(new byte[]{0x1D, 0x08, 0x00, 0x01});
        assertArrayEquals(new byte[]{0x1E}, indCfm);
    }

    @Test
    public void ignoresUnsolicitedClientAttResponsesAndReportsErrorForUnknownRequests() {
        for (int rspOpcode : new int[]{0x01, 0x03, 0x05, 0x07, 0x09, 0x0B, 0x11, 0x13, 0x1E}) {
            assertNull(
                    "Client ATT response opcode 0x" + Integer.toHexString(rspOpcode) + " must be ignored",
                    HalGattServer.handleRequest(new byte[]{(byte) rspOpcode, 0x01, 0x00, 0x01, 0x00}));
        }

        byte[] errRsp = HalGattServer.handleRequest(new byte[]{0x16, 0x10, 0x00, 0x00, 0x00});
        assertArrayEquals(new byte[]{0x01, 0x16, 0x10, 0x00, 0x0A}, errRsp);
    }
}
