package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BluetoothFixedChannelRouter;
import dev.applewatchandroid.bridge.BluetoothLinkMaintenance;
import dev.applewatchandroid.bridge.BluetoothSmpCodec;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link L2capChannelMux}: ATT client response pass-through with a real
 * {@link BluetoothFixedChannelRouter} concurrent with Watch ATT server request answering,
 * LE signaling rejection, bounded deferred SMP queue (16), SMP error/security-request handling,
 * and secret buffer zeroization on disconnect and {@link L2capChannelMux#close()}.
 */
public final class L2capChannelMuxTest {

    @Test
    public void passesThroughAttClientResponsesViaRealFixedRouterWhileAnsweringWatchAttRequests()
            throws Exception {
        BluetoothFixedChannelRouter fixedRouter = new BluetoothFixedChannelRouter();
        List<byte[]> serverSeenRequests = new ArrayList<>();
        BluetoothLinkMaintenance.AttServer attServer = new BluetoothLinkMaintenance.AttServer(req -> {
            serverSeenRequests.add(req.clone());
            // ATT_READ_REQ(0x0003) -> "iPhone"
            if ((req[0] & 0xFF) == 0x0A && req.length == 3
                    && ((req[1] & 0xFF) | ((req[2] & 0xFF) << 8)) == 0x0003) {
                return new byte[]{0x0B, 'i', 'P', 'h', 'o', 'n', 'e'};
            }
            return null;
        });

        List<TestHciTransport.SentFrame> sentFrames = new ArrayList<>();
        BlockingQueue<HciTransport.Packet> inbound = new LinkedBlockingQueue<>();

        HciTransport routerBackedTransport = new HciTransport() {
            @Override
            public int connectionHandle() {
                return TestHciTransport.DEFAULT_HANDLE;
            }

            @Override
            public int peerAddressType() {
                return TestHciTransport.DEFAULT_PEER_ADDR_TYPE;
            }

            @Override
            public byte[] peerAddress() {
                return TestHciTransport.DEFAULT_PEER_ADDR.clone();
            }

            @Override
            public dev.applewatchandroid.bridge.BluetoothLocalIdentity localIdentity() {
                return TestHciTransport.defaultLocalIdentity();
            }

            @Override
            public void checkStop() {
            }

            @Override
            public void sendL2cap(int cid, byte[] payload, String label) {
                sentFrames.add(new TestHciTransport.SentFrame(cid, payload.clone(), label));
            }

            @Override
            public Packet nextPacket(long deadlineMs) throws Exception {
                while (true) {
                    long rem = deadlineMs - dev.applewatchandroid.bridge.BridgeClock.elapsedRealtime();
                    if (rem <= 0) {
                        throw new TimeoutException("deadline elapsed");
                    }
                    Packet pkt = inbound.poll(rem, TimeUnit.MILLISECONDS);
                    if (pkt == null) {
                        throw new TimeoutException("timed out");
                    }
                    if (pkt.kind() == PacketKind.ACL && fixedRouter.acceptFrames(
                            pkt.bytes(),
                            TestHciTransport.DEFAULT_HANDLE,
                            (handle, cid, payload) -> {
                                if (cid == BluetoothLinkMaintenance.ATT_CID) {
                                    byte[] reply = attServer.accept(handle, payload);
                                    if (reply != null && reply.length > 0) {
                                        sendL2cap(BridgeCoreCodecs.ATT_FIXED_CID, reply, "FIXED ATT RSP");
                                    }
                                } else if (cid == BluetoothLinkMaintenance.LE_SIGNALING_CID) {
                                    for (byte[] reply : BluetoothLinkMaintenance.leSignalingReplies(payload)) {
                                        sendL2cap(BridgeCoreCodecs.LE_SIGNALING_CID, reply, "FIXED LE SIG");
                                    }
                                }
                            })) {
                        TestHciTransport.wipe(pkt.bytes());
                        continue;
                    }
                    return pkt;
                }
            }

            @Override
            public void startLeEncryption(byte[] rand, int ediv, byte[] longTermKey) {
            }

            @Override
            public AutoCloseable openAttClientScope() {
                boolean prev = fixedRouter.isAttClientActive();
                fixedRouter.setAttClientActive(true);
                AtomicBoolean closed = new AtomicBoolean();
                return () -> {
                    if (closed.compareAndSet(false, true)) {
                        fixedRouter.setAttClientActive(prev);
                    }
                };
            }

            @Override
            public byte[] handlePeerAttRequest(byte[] request) {
                return null;
            }

            @Override
            public void log(String message) {
            }
        };

        // Enqueue fragmented ATT client response (READ_BY_GROUP_TYPE_RSP 0x11) interleaved with:
        // - Watch ATT_EXCHANGE_MTU_REQ (0x02)
        // - Watch LE Signaling Connection Parameter Update Req (0x12)
        // - Watch ATT_READ_REQ (0x0A) for handle 0x0003
        // - Client ATT_WRITE_RSP (0x13)
        byte[] attClientGroupRsp = new byte[22];
        attClientGroupRsp[0] = (byte) BridgeCoreCodecs.ATT_OPCODE_READ_BY_GROUP_TYPE_RSP;
        attClientGroupRsp[1] = 20;
        attClientGroupRsp[2] = 0x20;
        attClientGroupRsp[4] = 0x24;

        for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(
                TestHciTransport.DEFAULT_HANDLE, BridgeCoreCodecs.ATT_FIXED_CID,
                new byte[]{0x02, (byte) 185, 0x00}, 9)) {
            inbound.offer(new HciTransport.Packet(HciTransport.PacketKind.ACL, frag));
        }
        for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(
                TestHciTransport.DEFAULT_HANDLE, BridgeCoreCodecs.ATT_FIXED_CID, attClientGroupRsp, 9)) {
            inbound.offer(new HciTransport.Packet(HciTransport.PacketKind.ACL, frag));
        }
        for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(
                TestHciTransport.DEFAULT_HANDLE, BridgeCoreCodecs.LE_SIGNALING_CID,
                new byte[]{0x12, 0x07, 0x08, 0x00, 0x18, 0x00, 0x28, 0x00, 0x00, 0x00, (byte) 0x90, 0x01}, 9)) {
            inbound.offer(new HciTransport.Packet(HciTransport.PacketKind.ACL, frag));
        }
        for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(
                TestHciTransport.DEFAULT_HANDLE, BridgeCoreCodecs.ATT_FIXED_CID,
                new byte[]{0x0A, 0x03, 0x00}, 9)) {
            inbound.offer(new HciTransport.Packet(HciTransport.PacketKind.ACL, frag));
        }
        for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(
                TestHciTransport.DEFAULT_HANDLE, BridgeCoreCodecs.ATT_FIXED_CID, new byte[]{0x13}, 9)) {
            inbound.offer(new HciTransport.Packet(HciTransport.PacketKind.ACL, frag));
        }

        try (L2capChannelMux mux = new L2capChannelMux(routerBackedTransport)) {
            try (AutoCloseable scope = mux.transport().openAttClientScope()) {
                assertTrue(fixedRouter.isAttClientActive());
                assertArrayEquals(attClientGroupRsp, mux.awaitAttClientResponse(500, "ATT ReadByGroupType Rsp"));
                assertArrayEquals(new byte[]{0x13}, mux.awaitAttClientResponse(500, "ATT Write Rsp"));
            }
            assertFalse(fixedRouter.isAttClientActive());

            // Both Watch ATT server requests were answered concurrently while scope was open
            assertEquals(1, serverSeenRequests.size()); // EXCHANGE_MTU handled natively by AttServer; READ_REQ delegated
            List<byte[]> attReplies = sentFrames.stream()
                    .filter(f -> f.cid() == BridgeCoreCodecs.ATT_FIXED_CID)
                    .map(TestHciTransport.SentFrame::payload)
                    .toList();
            assertEquals(2, attReplies.size());
            assertArrayEquals(new byte[]{0x03, (byte) 251, 0x00}, attReplies.get(0));
            assertArrayEquals(new byte[]{0x0B, 'i', 'P', 'h', 'o', 'n', 'e'}, attReplies.get(1));

            // LE signaling Connection Parameter Update Req (0x12) was rejected with 0x13
            List<byte[]> leSigReplies = sentFrames.stream()
                    .filter(f -> f.cid() == BridgeCoreCodecs.LE_SIGNALING_CID)
                    .map(TestHciTransport.SentFrame::payload)
                    .toList();
            assertEquals(1, leSigReplies.size());
            assertArrayEquals(new byte[]{0x13, 0x07, 0x02, 0x00, 0x01, 0x00}, leSigReplies.get(0));

            // After scope closes, a stray late ATT response is swallowed by fixedRouter instead of reaching mux
            for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(
                    TestHciTransport.DEFAULT_HANDLE, BridgeCoreCodecs.ATT_FIXED_CID, new byte[]{0x13}, 9)) {
                inbound.offer(new HciTransport.Packet(HciTransport.PacketKind.ACL, frag));
            }
            assertNull(mux.pollAttClientResponse(50, "stray late ATT rsp"));
            assertTrue(mux.deferredAttClientPdus.isEmpty());
        }
    }

    @Test
    public void servicesWatchAttServerRequestsConcurrentlyWhileDeliveringAttClientResponses() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        transport.setMaxAclLength(9); // Force L2CAP fragmentation over ACL
        List<byte[]> receivedAttRequests = new ArrayList<>();
        transport.setPeerAttHandler(req -> {
            receivedAttRequests.add(req.clone());
            // Answer ATT_READ_REQ(0x0003) with "iPhone"
            if ((req[0] & 0xFF) == 0x0A) {
                return new byte[]{0x0B, 'i', 'P', 'h', 'o', 'n', 'e'};
            }
            return new byte[]{0x03, (byte) 185, 0x00};
        });

        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            // Interleave Watch ATT Server requests, LE Signaling, SMP, and an ATT Client response
            transport.pushWatchAcl(BridgeCoreCodecs.ATT_FIXED_CID, new byte[]{0x02, (byte) 251, 0x00});
            transport.pushWatchAcl(BridgeCoreCodecs.LE_SIGNALING_CID,
                    new byte[]{0x12, 0x07, 0x08, 0x00, 0x18, 0x00, 0x28, 0x00, 0x00, 0x00, (byte) 0x90, 0x01});
            // Unsupported LE Credit Based Connection Req (0x14) -> Command Reject (0x01)
            transport.pushWatchAcl(BridgeCoreCodecs.LE_SIGNALING_CID,
                    new byte[]{0x14, 0x09, 0x0A, 0x00, (byte) 0x80, 0x00, 0x44, 0x00, 0x00, 0x02, 0x00, 0x02, 0x04, 0x00});
            transport.pushWatchAcl(BridgeCoreCodecs.SMP_FIXED_CID, new byte[]{0x0B, 0x0D});
            transport.pushWatchAcl(BridgeCoreCodecs.ATT_FIXED_CID, new byte[]{0x0A, 0x03, 0x00});
            transport.pushWatchAcl(BridgeCoreCodecs.ATT_FIXED_CID, new byte[]{0x13}); // ATT_WRITE_RSP (client response)

            byte[] attClientRsp = mux.awaitAttClientResponse(500, "ATT Write Rsp");
            assertArrayEquals(new byte[]{0x13}, attClientRsp);

            // Both Watch ATT server requests must have been answered immediately
            assertEquals(2, receivedAttRequests.size());
            List<TestHciTransport.SentFrame> attTx = transport.sentFramesForCid(BridgeCoreCodecs.ATT_FIXED_CID);
            assertEquals(2, attTx.size());
            assertArrayEquals(new byte[]{0x03, (byte) 185, 0x00}, attTx.get(0).payload());
            assertArrayEquals(new byte[]{0x0B, 'i', 'P', 'h', 'o', 'n', 'e'}, attTx.get(1).payload());

            // LE signaling Connection Parameter Update Req (0x12) -> 0x13 reject; unsupported 0x14 -> 0x01 reject
            List<TestHciTransport.SentFrame> leSigTx = transport.sentFramesForCid(BridgeCoreCodecs.LE_SIGNALING_CID);
            assertEquals(2, leSigTx.size());
            assertArrayEquals(new byte[]{0x13, 0x07, 0x02, 0x00, 0x01, 0x00}, leSigTx.get(0).payload());
            assertArrayEquals(new byte[]{0x01, 0x09, 0x02, 0x00, 0x00, 0x00}, leSigTx.get(1).payload());

            // Deferred SMP Security Request must remain queued for SMP waiter
            assertEquals(1, mux.deferredSmpPdus.size());
        }
    }

    @Test
    public void queuesSmpAcrossOtherWaitersAndToleratesOrRejectsSecurityRequests() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            byte[] btClVersion = BridgeCoreCodecs.buildBtClVersionPdu();
            byte[] secReq = new byte[]{BluetoothSmpCodec.SECURITY_REQUEST, 0x0D};
            byte[] pairingRsp = new byte[]{BluetoothSmpCodec.PAIRING_RESPONSE, 0x00, 0x00, 0x0D, 0x10, 0x07, 0x07};
            byte[] dynamicData = new byte[]{0x00, 0x01, 0x02, 0x03};

            // Arrive out-of-turn while awaiting BT_CL
            transport.pushWatchAcl(BridgeCoreCodecs.SMP_FIXED_CID, secReq);
            transport.pushWatchAcl(BridgeCoreCodecs.SMP_FIXED_CID, pairingRsp);
            transport.pushWatchAcl(0x0045, dynamicData);
            transport.pushWatchAcl(BridgeCoreCodecs.BT_CL_SIGNALING_CID, btClVersion);

            assertArrayEquals(btClVersion, mux.awaitBtClPdu(500, "BT_CL Version"));
            assertArrayEquals(pairingRsp,
                    mux.awaitSmpPdu(BluetoothSmpCodec.PAIRING_RESPONSE, 500, "SMP Pairing Response", true));
            assertArrayEquals(dynamicData, mux.awaitDynamicCidPdu(0x0045, 500, "Dynamic ERTM"));

            // When tolerateSecurityRequest == false, SECURITY_REQUEST fails loudly
            transport.pushWatchAcl(BridgeCoreCodecs.SMP_FIXED_CID, secReq);
            HostException unexpectedSec = assertThrows(HostException.class,
                    () -> mux.awaitSmpPdu(BluetoothSmpCodec.PAIRING_PUBLIC_KEY, 500, "SMP Public Key", false));
            assertTrue(unexpectedSec.getMessage().contains("0x0B"));
        }
    }

    @Test
    public void throwsHostExceptionWithSmpReasonNameOnPairingFailed() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            transport.pushWatchAcl(BridgeCoreCodecs.SMP_FIXED_CID,
                    BluetoothSmpCodec.pairingFailed(BluetoothSmpCodec.FAILURE_DHKEY_CHECK_FAILED));

            HostException err = assertThrows(HostException.class,
                    () -> mux.awaitSmpPdu(BluetoothSmpCodec.PAIRING_DHKEY_CHECK, 500, "SMP DHKey Check", false));
            assertTrue(err.getMessage().contains("DHKey Check Failed"));
            assertTrue(err.getMessage().contains("0x0B"));
        }
    }

    @Test
    public void disconnectWipesAllQueuedBuffersAndMarksDisconnected() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        L2capChannelMux mux = new L2capChannelMux(transport);

        byte[] smpSecret = new byte[]{BluetoothSmpCodec.PAIRING_RANDOM, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] attRsp = new byte[]{0x03, (byte) 185, 0x00};
        byte[] dynPdu = new byte[]{0x11, 0x22, 0x33, 0x44};
        byte[] btClPdu = BridgeCoreCodecs.buildBtClVersionPdu();

        transport.pushWatchAcl(BridgeCoreCodecs.SMP_FIXED_CID, smpSecret);
        transport.pushWatchAcl(BridgeCoreCodecs.ATT_FIXED_CID, attRsp);
        transport.pushWatchAcl(0x0041, dynPdu);
        transport.pushWatchAcl(BridgeCoreCodecs.BT_CL_SIGNALING_CID, btClPdu);
        // Pump until BT_CL PDU is consumed while the other 3 remain buffered
        assertArrayEquals(btClPdu, mux.awaitBtClPdu(500, "BT_CL"));

        List<byte[]> retainedRefs = new ArrayList<>();
        retainedRefs.addAll(mux.deferredSmpPdus);
        retainedRefs.addAll(mux.deferredAttClientPdus);
        retainedRefs.addAll(mux.dynamicCidQueues.get(0x0041));
        assertEquals(3, retainedRefs.size());

        // Ignore disconnect for another handle, then disconnect active handle
        transport.pushDisconnect(TestHciTransport.DEFAULT_HANDLE + 1, 0x13);
        transport.pushDisconnect(TestHciTransport.DEFAULT_HANDLE, 0x13);

        assertFalse(mux.wasDisconnected());
        HostException disconnected = assertThrows(HostException.class,
                () -> mux.awaitBtClPdu(500, "post-disconnect"));
        assertTrue(disconnected.getMessage().contains("0x13"));
        assertTrue(mux.wasDisconnected());

        assertTrue(mux.deferredSmpPdus.isEmpty());
        assertTrue(mux.deferredAttClientPdus.isEmpty());
        assertTrue(mux.deferredBtClPdus.isEmpty());
        assertTrue(mux.dynamicCidQueues.isEmpty());
        for (byte[] buf : retainedRefs) {
            assertTrue("Queued buffer must be zeroed on disconnect", TestHciTransport.isZeroed(buf));
        }
        mux.close();
    }

    @Test
    public void failsClosedOnBoundedSmpQueueDepth16OverflowAndZeroesBuffers() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        assertEquals(16, L2capChannelMux.MAX_FIXED_QUEUE_DEPTH);
        try (L2capChannelMux mux = new L2capChannelMux(transport)) {
            byte[] btClMarker = BridgeCoreCodecs.buildBtClVersionPdu();
            for (int i = 0; i < L2capChannelMux.MAX_FIXED_QUEUE_DEPTH; i++) {
                transport.pushWatchAcl(BridgeCoreCodecs.SMP_FIXED_CID,
                        new byte[]{BluetoothSmpCodec.PAIRING_RANDOM, (byte) (i + 1), 0x22, 0x33});
            }
            transport.pushWatchAcl(BridgeCoreCodecs.BT_CL_SIGNALING_CID, btClMarker);
            assertArrayEquals(btClMarker, mux.awaitBtClPdu(500, "drain exactly 16 SMP frames"));
            assertEquals(16, mux.deferredSmpPdus.size());

            List<byte[]> retainedSmpRefs = new ArrayList<>(mux.deferredSmpPdus);

            // 17th SMP frame triggers overflow, clears queue, and zeroes all 16 retained payloads
            transport.pushWatchAcl(BridgeCoreCodecs.SMP_FIXED_CID, new byte[]{0x0B, 0x0D});
            HostException overflow = assertThrows(HostException.class,
                    () -> mux.awaitAttClientResponse(500, "drain overflow"));
            assertTrue(overflow.getMessage().contains("overflow"));
            assertTrue(overflow.getMessage().contains("16"));
            assertTrue(mux.deferredSmpPdus.isEmpty());
            for (byte[] buf : retainedSmpRefs) {
                assertTrue("Retained SMP buffer must be zeroed on overflow", TestHciTransport.isZeroed(buf));
            }
        }
    }

    @Test
    public void closeZeroesAllQueuedBuffersAndRejectsSubsequentOperations() throws Exception {
        TestHciTransport transport = new TestHciTransport();
        L2capChannelMux mux = new L2capChannelMux(transport);

        byte[] smpSecret = new byte[]{BluetoothSmpCodec.PAIRING_RANDOM, 9, 8, 7, 6, 5, 4, 3, 2, 1, 1, 2, 3, 4, 5, 6, 7};
        byte[] attRsp = new byte[]{0x03, (byte) 185, 0x00};
        byte[] btClPdu = BridgeCoreCodecs.buildBtClVersionPdu();
        byte[] dynPdu = new byte[]{0x11, 0x22, 0x33, 0x44};
        byte[] dynMarker = new byte[]{0x55, 0x66};

        transport.pushWatchAcl(BridgeCoreCodecs.SMP_FIXED_CID, smpSecret);
        transport.pushWatchAcl(BridgeCoreCodecs.ATT_FIXED_CID, attRsp);
        transport.pushWatchAcl(BridgeCoreCodecs.BT_CL_SIGNALING_CID, btClPdu);
        transport.pushWatchAcl(0x0041, dynPdu);
        transport.pushWatchAcl(0x0042, dynMarker);
        assertArrayEquals(dynMarker, mux.awaitDynamicCidPdu(0x0042, 500, "pump all 4 deferred queues"));

        List<byte[]> retainedRefs = new ArrayList<>();
        retainedRefs.addAll(mux.deferredSmpPdus);
        retainedRefs.addAll(mux.deferredAttClientPdus);
        retainedRefs.addAll(mux.deferredBtClPdus);
        retainedRefs.addAll(mux.dynamicCidQueues.get(0x0041));
        assertEquals(4, retainedRefs.size());

        mux.close();
        assertFalse(mux.wasDisconnected());
        assertTrue(mux.deferredSmpPdus.isEmpty());
        assertTrue(mux.deferredAttClientPdus.isEmpty());
        assertTrue(mux.deferredBtClPdus.isEmpty());
        assertTrue(mux.dynamicCidQueues.isEmpty());
        for (byte[] buf : retainedRefs) {
            assertTrue("Queued buffer must be zeroed on close()", TestHciTransport.isZeroed(buf));
        }

        assertThrows(HostException.class, () -> mux.sendL2cap(BridgeCoreCodecs.SMP_FIXED_CID, new byte[]{1}, "closed"));
        assertThrows(HostException.class, () -> mux.awaitSmpPdu(BluetoothSmpCodec.PAIRING_RESPONSE, 100, "closed", false));
        assertThrows(HostException.class, () -> mux.awaitBtClPdu(100, "closed"));
        assertThrows(HostException.class, () -> mux.awaitAttClientResponse(100, "closed"));
        assertThrows(HostException.class, () -> mux.awaitDynamicCidPdu(0x0041, 100, "closed"));
    }
}
