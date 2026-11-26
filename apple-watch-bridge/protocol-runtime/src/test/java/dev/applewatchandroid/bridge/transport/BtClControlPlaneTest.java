package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link BtClControlPlane}: {@code BT_CL} {@code v<8} ({@code version=6})
 * short-length framing and {@code v>=8} ({@code version=11}) 2-byte-length framing, companion-
 * and Watch-initiated {@code REMOTE_SERVICES} / {@code SERVICE_ADDED} / {@code CREATE_CHANNEL},
 * {@code TIME_SYNC_REQUEST}, {@code SERVICE_REMOVED}, {@code SIGNALING_ERROR 0x05} retry, and
 * per-service FCS capability tracking.
 */
public final class BtClControlPlaneTest {

    @Test
    public void btClVersion6UsesShort1ByteLengthsAcrossHandshakeRetryTimeSyncAndOpenChannel()
            throws Exception {
        TestHciTransport transport = new TestHciTransport();
        int[] negotiatedPeerVersion = new int[]{11};
        int[] remoteServicesAttempts = new int[1];
        List<BridgeCoreCodecs.BtClFrame> txFrames = new ArrayList<>();

        transport.setOutboundHandler((cid, payload, label) -> {
            if (cid != BridgeCoreCodecs.BT_CL_SIGNALING_CID) {
                return;
            }
            int op = payload[0] & 0xFF;
            if (op != BridgeCoreCodecs.BT_CL_VERSION_INFO) {
                // Verify companion actually emitted 1-byte length header (headerLength == 2) for version=6!
                assertEquals("BT_CL v<8 must encode 1-byte payload length",
                        payload[1] & 0xFF, payload.length - 2);
            }
            BridgeCoreCodecs.BtClFrame frame = BridgeCoreCodecs.parseBtCl(
                    negotiatedPeerVersion[0], 0L, payload);
            txFrames.add(frame);
            if (frame.opcode() == BridgeCoreCodecs.BT_CL_VERSION_INFO) {
                negotiatedPeerVersion[0] = 6;
                transport.pushWatchAcl(cid, new byte[]{
                        (byte) BridgeCoreCodecs.BT_CL_VERSION_INFO, 0x05,
                        0x06, 0x01, 0x00, 0x00, 0x00
                });
            } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_REMOTE_SERVICES) {
                remoteServicesAttempts[0]++;
                if (remoteServicesAttempts[0] == 1) {
                    // 1-byte payload SIGNALING_ERROR 0x05 triggers immediate retry
                    transport.pushWatchAcl(cid, new byte[]{
                            (byte) BridgeCoreCodecs.BT_CL_SIGNALING_ERROR, 0x01, 0x05
                    });
                } else {
                    // v<8 short-length TIME_SYNC_REQUEST (2 bytes: opcode + len=0)
                    transport.pushWatchAcl(cid, new byte[]{
                            (byte) BridgeCoreCodecs.BT_CL_TIME_SYNC_REQUEST, 0x00
                    });
                    // v<8 short-length COMMON_SERVICES([0x0003])
                    transport.pushWatchAcl(cid, BridgeCoreCodecs.buildBtClCommonServicesPdu(
                            6, 0x01L, List.of(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID)));
                }
            } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_CREATE_CHANNEL) {
                BridgeCoreCodecs.BtClCreateChannel cc = BridgeCoreCodecs.parseBtClCreateChannel(frame);
                transport.pushWatchAcl(cid, BridgeCoreCodecs.buildBtClAcceptChannelPdu(
                        6, 0x01L, 0, cc.serviceId(), 0x0048));
            }
        });

        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            BtClControlPlane cp = new BtClControlPlane(mux);
            BridgeCoreCodecs.BtClVersion version = cp.exchangeVersion(1000);
            assertEquals(6, version.version());
            assertEquals(0x01L, version.features());
            assertEquals(6, cp.peerVersion());
            assertEquals(0x01L, cp.peerFeatures());

            List<Integer> common = cp.negotiateServices(
                    List.of(BridgeCoreCodecs.idsBtLinkService()), 1000);
            assertEquals(List.of(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID), common);
            assertEquals(2, remoteServicesAttempts[0]);
            assertTrue(txFrames.stream().anyMatch(f -> f.opcode() == BridgeCoreCodecs.BT_CL_TIME_SYNC_INFO));
            assertTrue(txFrames.stream().anyMatch(f -> f.opcode() == BridgeCoreCodecs.BT_CL_TIME_SYNC_CORRECTION));

            BtClControlPlane.OpenedChannel ch = cp.openChannel(
                    BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID,
                    BridgeCoreCodecs.IDS_BT_LINK_LOCAL_CID,
                    false,
                    1000);
            assertEquals(6, ch.peerVersion());
            assertEquals(0x01L, ch.peerFeatures());
            assertEquals(0x0048, ch.remoteCid());
            assertFalse(ch.fcs());
        }
    }

    @Test
    public void companionInitiatedNegotiationWithRetryTimeSyncAndServiceRemoved() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        int[] versionAttempts = new int[1];
        int[] remoteServiceAttempts = new int[1];
        int[] createChannelAttempts = new int[1];
        List<Integer> txOpcodes = new ArrayList<>();

        transport.setOutboundHandler((cid, payload, label) -> {
            if (cid != BridgeCoreCodecs.BT_CL_SIGNALING_CID) {
                return;
            }
            BridgeCoreCodecs.BtClFrame frame = BridgeCoreCodecs.parseBtCl(11, 0L, payload);
            txOpcodes.add(frame.opcode());
            if (frame.opcode() == BridgeCoreCodecs.BT_CL_VERSION_INFO) {
                versionAttempts[0]++;
                if (versionAttempts[0] == 1) {
                    // Signaling error 0x05 triggers immediate retry
                    transport.pushWatchAcl(cid, new byte[]{
                            (byte) BridgeCoreCodecs.BT_CL_SIGNALING_ERROR, 0x02,
                            (byte) BridgeCoreCodecs.BT_CL_VERSION_INFO, 0x05
                    });
                } else {
                    transport.pushWatchAcl(cid, new byte[]{
                            (byte) BridgeCoreCodecs.BT_CL_VERSION_INFO, 0x05,
                            0x0B, 0x21, 0x15, 0x00, 0x00
                    });
                }
            } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_REMOTE_SERVICES) {
                remoteServiceAttempts[0]++;
                if (remoteServiceAttempts[0] == 1) {
                    // Send TIME_SYNC_REQUEST + SERVICE_REMOVED before answering COMMON_SERVICES
                    transport.pushWatchAcl(cid, new byte[]{
                            (byte) BridgeCoreCodecs.BT_CL_TIME_SYNC_REQUEST, 0x00, 0x00
                    });
                    transport.pushWatchAcl(cid, new byte[]{
                            (byte) BridgeCoreCodecs.BT_CL_SERVICE_REMOVED, 0x02, 0x00,
                            0x01, 0x00
                    });
                    transport.pushWatchAcl(cid, BridgeCoreCodecs.buildBtClCommonServicesPdu(
                            11, 0x1521L, List.of(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID)));
                }
            } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_CREATE_CHANNEL) {
                createChannelAttempts[0]++;
                if (createChannelAttempts[0] == 1) {
                    transport.pushWatchAcl(cid, new byte[]{
                            (byte) BridgeCoreCodecs.BT_CL_SIGNALING_ERROR, 0x02,
                            (byte) BridgeCoreCodecs.BT_CL_CREATE_CHANNEL, 0x05
                    });
                } else {
                    BridgeCoreCodecs.BtClCreateChannel cc = BridgeCoreCodecs.parseBtClCreateChannel(frame);
                    transport.pushWatchAcl(cid, BridgeCoreCodecs.buildBtClAcceptChannelPdu(
                            11, 0x1521L, 0, cc.serviceId(), 0x0055));
                }
            }
        });

        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            BtClControlPlane controlPlane = new BtClControlPlane(mux);
            BridgeCoreCodecs.BtClVersion version = controlPlane.exchangeVersion(1000);
            assertEquals(11, version.version());
            assertEquals(0x1521L, version.features());

            List<Integer> common = controlPlane.negotiateServices(
                    List.of(BridgeCoreCodecs.terminusLinkService(), BridgeCoreCodecs.idsBtLinkService()),
                    1500);
            assertEquals(List.of(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID), common);

            // Time sync (0x71 + 0x72) and remove confirmation (0x07) must have been sent
            assertTrue(txOpcodes.contains(BridgeCoreCodecs.BT_CL_TIME_SYNC_INFO));
            assertTrue(txOpcodes.contains(BridgeCoreCodecs.BT_CL_TIME_SYNC_CORRECTION));
            assertTrue(txOpcodes.contains(BridgeCoreCodecs.BT_CL_REMOVE_CONFIRMATION));

            BtClControlPlane.OpenedChannel ch = controlPlane.openChannel(
                    BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID,
                    BridgeCoreCodecs.IDS_BT_LINK_LOCAL_CID,
                    true,
                    1000);
            assertEquals(2, createChannelAttempts[0]);
            assertEquals(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID, ch.serviceId());
            assertEquals(BridgeCoreCodecs.IDS_BT_LINK_LOCAL_CID, ch.localCid());
            assertEquals(0x0055, ch.remoteCid());
            assertEquals(11, ch.peerVersion());
            assertEquals(0x1521L, ch.peerFeatures());
            assertTrue("Should fall back to defaultFcs when peer did not advertise service flags", ch.fcs());
        }
    }

    @Test
    public void handlesWatchInitiatedRemoteServicesServiceAddedCreateChannelAndPropagatesFcs() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        List<BridgeCoreCodecs.BtClFrame> txFrames = new ArrayList<>();

        transport.setOutboundHandler((cid, payload, label) -> {
            if (cid != BridgeCoreCodecs.BT_CL_SIGNALING_CID) {
                return;
            }
            BridgeCoreCodecs.BtClFrame frame = BridgeCoreCodecs.parseBtCl(11, 0L, payload);
            txFrames.add(frame);
            if (frame.opcode() == BridgeCoreCodecs.BT_CL_VERSION_INFO) {
                transport.pushWatchAcl(cid, new byte[]{
                        (byte) BridgeCoreCodecs.BT_CL_VERSION_INFO, 0x05,
                        0x0B, 0x21, 0x15, 0x00, 0x00
                });
            } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_REMOTE_SERVICES) {
                // Watch proactively sends REMOTE_SERVICES with FCS=true (flags=0x03: ERTM|FCS) for 0x0003
                BridgeCoreCodecs.BtClService watchIdsWithFcs = new BridgeCoreCodecs.BtClService(
                        BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID,
                        1,
                        "com.apple.ids\0".getBytes(StandardCharsets.UTF_8),
                        0x03,
                        true,
                        true);
                BridgeCoreCodecs.BtClService unrelated = new BridgeCoreCodecs.BtClService(
                        0x0099,
                        1,
                        "com.apple.other\0".getBytes(StandardCharsets.UTF_8),
                        0x01,
                        true,
                        false);
                transport.pushWatchAcl(cid, BridgeCoreCodecs.buildBtClRemoteServicesPdu(
                        11, 0x1521L, List.of(unrelated, watchIdsWithFcs)));
            } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_CREATE_CHANNEL) {
                // Watch first requests an unrelated service (must be rejected with status=5), then 0x0003
                transport.pushWatchAcl(cid, BridgeCoreCodecs.buildBtClCreateChannelPdu(
                        11, 0x1521L, 0x0076, 0x0099));
                transport.pushWatchAcl(cid, BridgeCoreCodecs.buildBtClCreateChannelPdu(
                        11, 0x1521L, 0x0077, BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID));
            }
        });

        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            BtClControlPlane cp = new BtClControlPlane(mux);
            cp.exchangeVersion(1000);

            List<Integer> common = cp.negotiateServices(
                    List.of(BridgeCoreCodecs.idsBtLinkService()),
                    1000);
            assertEquals(List.of(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID), common);

            // Verify companion answered Watch's REMOTE_SERVICES with COMMON_SERVICES([0x0003])
            BridgeCoreCodecs.BtClFrame commonReply = txFrames.stream()
                    .filter(f -> f.opcode() == BridgeCoreCodecs.BT_CL_COMMON_SERVICES)
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID),
                    BridgeCoreCodecs.parseBtClCommonServices(commonReply));

            BtClControlPlane.OpenedChannel ch = cp.openChannel(
                    BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID,
                    BridgeCoreCodecs.IDS_BT_LINK_LOCAL_CID,
                    false, // defaultFcs=false, MUST be overridden by peer's fcs=true!
                    1000);
            assertEquals(0x0077, ch.remoteCid());
            assertTrue("Peer's negotiated fcs=true must override defaultFcs=false", ch.fcs());

            List<BridgeCoreCodecs.BtClAcceptChannel> acceptReplies = txFrames.stream()
                    .filter(f -> f.opcode() == BridgeCoreCodecs.BT_CL_ACCEPT_CHANNEL)
                    .map(BridgeCoreCodecs::parseBtClAcceptChannel)
                    .toList();
            assertEquals(2, acceptReplies.size());
            assertEquals(5, acceptReplies.get(0).status());
            assertEquals(0x0099, acceptReplies.get(0).serviceId());
            assertEquals(0, acceptReplies.get(1).status());
            assertEquals(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID, acceptReplies.get(1).serviceId());
            assertEquals(BridgeCoreCodecs.IDS_BT_LINK_LOCAL_CID, acceptReplies.get(1).responderLocalCid());
        }
    }

    @Test
    public void handlesWatchServiceAddedWithFcsFalseAndRetransmitsAfter800Ms() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        int[] remoteServicesCount = new int[1];

        transport.setOutboundHandler((cid, payload, label) -> {
            if (cid != BridgeCoreCodecs.BT_CL_SIGNALING_CID) {
                return;
            }
            BridgeCoreCodecs.BtClFrame frame = BridgeCoreCodecs.parseBtCl(11, 0L, payload);
            if (frame.opcode() == BridgeCoreCodecs.BT_CL_VERSION_INFO) {
                transport.pushWatchAcl(cid, new byte[]{
                        (byte) BridgeCoreCodecs.BT_CL_VERSION_INFO, 0x05,
                        0x0B, 0x00, 0x00, 0x00, 0x00
                });
            } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_REMOTE_SERVICES) {
                remoteServicesCount[0]++;
                if (remoteServicesCount[0] >= 2) {
                    // Watch sends SERVICE_ADDED with flags=0x01 (ERTM=true, FCS=false) after retransmit
                    transport.pushWatchAcl(cid, BridgeCoreCodecs.buildBtClServiceAddedPdu(
                            11, 0L, BridgeCoreCodecs.terminusLinkService()));
                }
            } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_CREATE_CHANNEL) {
                transport.pushWatchAcl(cid, BridgeCoreCodecs.buildBtClAcceptChannelPdu(
                        11, 0L, 0, BridgeCoreCodecs.TERMINUS_LINK_SERVICE_ID, 0x0066));
            }
        });

        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            BtClControlPlane cp = new BtClControlPlane(mux);
            cp.exchangeVersion(500);
            List<Integer> common = cp.negotiateServices(
                    List.of(BridgeCoreCodecs.terminusLinkService()),
                    1600);
            assertEquals(List.of(BridgeCoreCodecs.TERMINUS_LINK_SERVICE_ID), common);
            assertTrue("Must have retransmitted REMOTE_SERVICES after 800ms slice", remoteServicesCount[0] >= 2);

            BtClControlPlane.OpenedChannel ch = cp.openChannel(
                    BridgeCoreCodecs.TERMINUS_LINK_SERVICE_ID,
                    BridgeCoreCodecs.TERMINUS_LINK_LOCAL_CID,
                    true, // defaultFcs=true overridden by peer SERVICE_ADDED fcs=false!
                    500);
            assertFalse("Peer SERVICE_ADDED fcs=false must override defaultFcs=true", ch.fcs());
        }
    }

    @Test
    public void rejectsImplausiblePeerVersion() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        transport.setOutboundHandler((cid, payload, label) -> {
            BridgeCoreCodecs.BtClFrame frame = BridgeCoreCodecs.parseBtCl(11, 0L, payload);
            if (frame.opcode() == BridgeCoreCodecs.BT_CL_VERSION_INFO) {
                transport.pushWatchAcl(cid, new byte[]{
                        (byte) BridgeCoreCodecs.BT_CL_VERSION_INFO, 0x05,
                        (byte) 0x7F, 0x00, 0x00, 0x00, 0x00
                });
            }
        });
        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            BtClControlPlane cp = new BtClControlPlane(mux);
            HostException err = assertThrows(HostException.class, () -> cp.exchangeVersion(500));
            assertTrue(err.getMessage().contains("Implausible"));
        }
    }
}
