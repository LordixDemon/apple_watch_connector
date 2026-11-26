package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link GattAttClient}: MTU exchange, direct {@code FIND_BY_TYPE_VALUE},
 * paginated {@code READ_BY_GROUP_TYPE} fallback on {@code 0x06/0x10/0x0A}, paginated
 * {@code READ_BY_TYPE} characteristic discovery, Strategy {@code 0x02} &amp; {@code 0x03}
 * {@code NANO} control writes, unsolicited notification filtering, and
 * {@link HciTransport#openAttClientScope()} lifecycle guarantees.
 */
public final class GattAttClientTest {
    private static final byte[] EXTENSIBLE_PAIR_SVC_UUID_LE = new byte[]{
            (byte) 0x96, (byte) 0xC1, (byte) 0x9F, (byte) 0x55,
            (byte) 0x31, (byte) 0xC9, (byte) 0x21, (byte) 0xB8,
            (byte) 0xC3, (byte) 0x4C, (byte) 0x5C, (byte) 0xB2,
            (byte) 0x0F, (byte) 0x73, (byte) 0xA4, (byte) 0x9A
    };
    private static final byte[] EXTENSIBLE_PAIR_CTRL_UUID_LE = new byte[]{
            (byte) 0x60, (byte) 0x34, (byte) 0x94, (byte) 0x34,
            (byte) 0x71, (byte) 0x01, (byte) 0x0B, (byte) 0x81,
            (byte) 0x0E, (byte) 0x40, (byte) 0xC8, (byte) 0x8A,
            (byte) 0x23, (byte) 0x6A, (byte) 0x6C, (byte) 0x5F
    };

    @Test
    public void strategy3NanoControlWriteUsesFindByTypeValueAndClosesAttScope() throws Exception {
        for (int style : new int[]{BridgeCoreCodecs.LEGACY_STYLE_OOB, BridgeCoreCodecs.LEGACY_STYLE_PASSCODE}) {
            TestHciTransport transport = new TestHciTransport();
            byte[][] writtenValue = new byte[1][];
            transport.setOutboundHandler((cid, req, label) -> {
                if (cid != BridgeCoreCodecs.ATT_FIXED_CID) {
                    return;
                }
                assertTrue("ATT client scope must be active during ATT request", transport.isAttScopeActive());
                int op = req[0] & 0xFF;
                if (op == 0x02) { // EXCHANGE_MTU_REQ
                    transport.pushWatchAcl(cid, new byte[]{0x03, (byte) 185, 0x00});
                } else if (op == 0x06) { // FIND_BY_TYPE_VALUE_REQ
                    transport.pushWatchAcl(cid, new byte[]{0x07, 0x20, 0x00, 0x24, 0x00});
                } else if (op == 0x08) { // READ_BY_TYPE_REQ (0x2803)
                    byte[] rsp = new byte[2 + 21];
                    rsp[0] = 0x09;
                    rsp[1] = 21;
                    rsp[2] = 0x21; rsp[3] = 0x00;
                    rsp[4] = 0x0A;
                    rsp[5] = 0x22; rsp[6] = 0x00;
                    System.arraycopy(EXTENSIBLE_PAIR_CTRL_UUID_LE, 0, rsp, 7, 16);
                    transport.pushWatchAcl(cid, rsp);
                } else if (op == 0x12) { // WRITE_REQ
                    assertEquals(0x0022, (req[1] & 0xFF) | ((req[2] & 0xFF) << 8));
                    writtenValue[0] = Arrays.copyOfRange(req, 3, req.length);
                    transport.pushWatchAcl(cid, new byte[]{0x13});
                }
            });

            try (L2capChannelMux mux = new L2capChannelMux(transport)) {
                GattAttClient client = new GattAttClient(mux);
                int controlHandle = client.writeNanoControl(0x03, style, 1000);
                assertEquals(0x0022, controlHandle);
                assertArrayEquals(new byte[]{'N', 'A', 'N', 'O', 0x03, (byte) style}, writtenValue[0]);
                assertFalse(transport.isAttScopeActive());
                assertEquals(1, transport.attScopeOpenCount());
                assertEquals(1, transport.attScopeCloseCount());
            }
        }
    }

    @Test
    public void strategy2FallsBackToPaginatedReadByGroupTypeOnAllThreeErrorCodes() throws Exception {
        for (int errorCode : new int[]{
                BridgeCoreCodecs.ATT_ERR_REQUEST_NOT_SUPPORTED,
                BridgeCoreCodecs.ATT_ERR_UNSUPPORTED_GROUP_TYPE,
                BridgeCoreCodecs.ATT_ERR_ATTRIBUTE_NOT_FOUND}) {
            TestHciTransport transport = new TestHciTransport();
            byte[][] writtenValue = new byte[1][];
            int[] groupPages = new int[1];
            int[] charPages = new int[1];
            transport.setOutboundHandler((cid, req, label) -> {
                if (cid != BridgeCoreCodecs.ATT_FIXED_CID) {
                    return;
                }
                int op = req[0] & 0xFF;
                if (op == 0x02) {
                    // Simulate ATT error on Exchange MTU; client must gracefully fall back to 23
                    transport.pushWatchAcl(cid, new byte[]{0x01, 0x02, 0x00, 0x00, 0x06});
                } else if (op == 0x06) {
                    transport.pushWatchAcl(cid, new byte[]{0x01, 0x06, 0x01, 0x00, (byte) errorCode});
                } else if (op == 0x10) {
                    groupPages[0]++;
                    int start = (req[1] & 0xFF) | ((req[2] & 0xFF) << 8);
                    if (start == 1) {
                        transport.pushWatchAcl(cid, new byte[]{0x11, 0x06, 0x01, 0x00, 0x09, 0x00, 0x00, 0x18});
                    } else {
                        byte[] rsp = new byte[2 + 20];
                        rsp[0] = 0x11;
                        rsp[1] = 20;
                        rsp[2] = 0x10; rsp[3] = 0x00;
                        rsp[4] = 0x1F; rsp[5] = 0x00;
                        System.arraycopy(EXTENSIBLE_PAIR_SVC_UUID_LE, 0, rsp, 6, 16);
                        transport.pushWatchAcl(cid, rsp);
                    }
                } else if (op == 0x08) {
                    charPages[0]++;
                    int start = (req[1] & 0xFF) | ((req[2] & 0xFF) << 8);
                    if (start == 0x10) {
                        // Unrelated 16-bit characteristic first
                        transport.pushWatchAcl(cid, new byte[]{0x09, 0x07, 0x11, 0x00, 0x02, 0x12, 0x00, 0x00, 0x2A});
                    } else {
                        byte[] rsp = new byte[2 + 21];
                        rsp[0] = 0x09;
                        rsp[1] = 21;
                        rsp[2] = 0x13; rsp[3] = 0x00;
                        rsp[4] = 0x08;
                        rsp[5] = 0x14; rsp[6] = 0x00;
                        System.arraycopy(EXTENSIBLE_PAIR_CTRL_UUID_LE, 0, rsp, 7, 16);
                        transport.pushWatchAcl(cid, rsp);
                    }
                } else if (op == 0x12) {
                    writtenValue[0] = Arrays.copyOfRange(req, 3, req.length);
                    transport.pushWatchAcl(cid, new byte[]{0x13});
                }
            });

            try (L2capChannelMux mux = new L2capChannelMux(transport)) {
                GattAttClient client = new GattAttClient(mux);
                int handle = client.writeNanoControl(0x02, BridgeCoreCodecs.LEGACY_STYLE_OOB, 1000);
                assertEquals(0x0014, handle);
                assertEquals(2, groupPages[0]);
                assertEquals(2, charPages[0]);
                assertArrayEquals(new byte[]{'N', 'A', 'N', 'O', 0x02, 0x01}, writtenValue[0]);
                assertFalse(transport.isAttScopeActive());
            }
        }
    }

    @Test
    public void exchangeMtuNegotiatesMinClampedTo23AndSkipsUnsolicitedNotifications() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        int[] mtuSeq = new int[]{251, 64, 16};
        int[] idx = new int[1];
        transport.setOutboundHandler((cid, req, label) -> {
            if ((req[0] & 0xFF) == 0x02) {
                if (idx[0] == 0) {
                    // Unsolicited Handle Value Notification (0x1B) and Multi-Notification (0x23)
                    transport.pushWatchAcl(cid, new byte[]{0x1B, 0x05, 0x00, 0x01, 0x02});
                    transport.pushWatchAcl(cid, new byte[]{0x23, 0x05, 0x00, 0x01, 0x00, 0x00});
                }
                int serverMtu = mtuSeq[idx[0]++];
                transport.pushWatchAcl(cid, new byte[]{0x03, (byte) (serverMtu & 0xFF), (byte) (serverMtu >>> 8)});
            }
        });

        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            GattAttClient client = new GattAttClient(mux);
            assertEquals(185, client.exchangeMtu(185, 500));
            assertEquals(64, client.exchangeMtu(185, 500));
            assertEquals(23, client.exchangeMtu(185, 500));
            assertFalse(transport.isAttScopeActive());
        }
    }

    @Test
    public void discoverServiceAndCharacteristicFailureCasesCloseAttScope() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        int[] scenario = new int[1];
        transport.setOutboundHandler((cid, req, label) -> {
            int op = req[0] & 0xFF;
            if (op == 0x06) {
                if (scenario[0] == 0) {
                    // Non-fallback ATT error 0x02 (Read Not Permitted)
                    transport.pushWatchAcl(cid, new byte[]{0x01, 0x06, 0x01, 0x00, 0x02});
                } else {
                    // Fallback error 0x06 -> READ_BY_GROUP_TYPE returns ATTRIBUTE_NOT_FOUND
                    transport.pushWatchAcl(cid, new byte[]{0x01, 0x06, 0x01, 0x00, 0x06});
                }
            } else if (op == 0x10) {
                transport.pushWatchAcl(cid, new byte[]{0x01, 0x10, 0x01, 0x00, 0x0A});
            } else if (op == 0x08) {
                transport.pushWatchAcl(cid, new byte[]{0x01, 0x08, 0x20, 0x00, 0x0A});
            }
        });

        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            GattAttClient client = new GattAttClient(mux);
            assertThrows(HostException.class, () -> client.discoverExtensiblePairService(500));
            assertFalse(transport.isAttScopeActive());

            scenario[0] = 1;
            HostException notFound = assertThrows(HostException.class,
                    () -> client.discoverExtensiblePairService(500));
            assertTrue(notFound.getMessage().contains("9AA4730F"));
            assertFalse(transport.isAttScopeActive());

            HostException charMissing = assertThrows(HostException.class,
                    () -> client.discoverExtensiblePairControlHandle(
                            new BridgeCoreCodecs.AttHandleRange(0x20, 0x24), 500));
            assertTrue(charMissing.getMessage().contains("5F6C6A23"));
            assertFalse(transport.isAttScopeActive());
            assertEquals(3, transport.attScopeOpenCount());
            assertEquals(3, transport.attScopeCloseCount());
        }
    }

    @Test
    public void exchangeMtuReturns23OnAttErrorAndClosesScopeEvenOnFailure() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        transport.setOutboundHandler((cid, req, label) -> {
            int op = req[0] & 0xFF;
            if (op == 0x02) {
                transport.pushWatchAcl(cid, new byte[]{0x01, 0x02, 0x00, 0x00, 0x06});
            } else if (op == 0x12) {
                transport.pushWatchAcl(cid, new byte[]{0x01, 0x12, 0x22, 0x00, 0x03}); // Write Not Permitted
            }
        });

        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            GattAttClient client = new GattAttClient(mux);
            assertEquals(23, client.exchangeMtu(185, 500));
            assertFalse(transport.isAttScopeActive());

            assertThrows(HostException.class,
                    () -> client.writeCharacteristic(0x0022, new byte[]{1, 2, 3}, 500));
            assertFalse("ATT scope must close on error", transport.isAttScopeActive());
            assertEquals(2, transport.attScopeOpenCount());
            assertEquals(2, transport.attScopeCloseCount());
        }
    }
}
