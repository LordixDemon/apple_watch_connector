package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BluetoothLocalIdentity;
import dev.applewatchandroid.bridge.BridgeClock;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;
import dev.applewatchandroid.bridge.IdsControlChannelCodec;
import dev.applewatchandroid.bridge.spi.IdsLinkKind;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link IdsBtLinkTransport}: bidirectional ERTM TX/RX with
 * {@code fcsEnabled=false} and {@code fcsEnabled=true}, multi-frame stream decoding,
 * supervisory {@code RR}/{@code REJ}/{@code RR(P=1)} handling, checksum-flagged inbound packets,
 * {@link IdsBtLinkTransport#tryDecodeLegacyHello(byte[])} watchOS 1–5 lenient Hello parsing,
 * and fail-closed {@link IdsBtLinkTransport#close()}.
 */
public final class IdsBtLinkTransportTest {
    private static final int HANDLE = 0x0081;
    private static final int LOCAL_CID = 0x0042;
    private static final int REMOTE_CID = 0x0055;

    @Test
    public void bidirectionalErtmTxAndRxWithFcsDisabledAndEnabled() throws Exception {
        runBidirectionalTest(false);
        runBidirectionalTest(true);
    }

    private void runBidirectionalTest(boolean fcsEnabled) throws Exception {
        SyntheticIdsBtLinkTransport hci = new SyntheticIdsBtLinkTransport(fcsEnabled);
        BtClControlPlane.OpenedChannel channel = new BtClControlPlane.OpenedChannel(
                BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID,
                LOCAL_CID,
                REMOTE_CID,
                true,
                fcsEnabled,
                11,
                0L);
        try (L2capChannelMux mux = new L2capChannelMux(hci);
             IdsBtLinkTransport link = new IdsBtLinkTransport(mux, channel)) {
            assertEquals(IdsLinkKind.IDS_BT_LINK, link.kind());
            assertEquals(LOCAL_CID, link.localCid());
            assertEquals(REMOTE_CID, link.remoteCid());
            assertEquals(fcsEnabled, link.fcsEnabled());
            assertTrue(link.isOpen());

            // 1) Multi-chunk outbound payload (> TARGET_MPS 2042 bytes)
            byte[] largePayload = new byte[2500];
            Arrays.fill(largePayload, (byte) 0x2A);
            link.sendControlFrame(largePayload);
            assertEquals(1, link.framesSent());
            assertEquals(2, hci.txWireIFrameCount);
            assertEquals(2, hci.txIFramePayloads.size());
            assertArrayEquals(largePayload, hci.reassembleTxIdsPacket());

            // 2) Inbound two IDSBTLink packets split across two ERTM I-frames; verifies RR ACK sent back on remoteCid
            byte[] msg1 = "IDS-WATCH-REPLY-1".getBytes(StandardCharsets.US_ASCII);
            byte[] msg2 = "IDS-WATCH-REPLY-2".getBytes(StandardCharsets.US_ASCII);
            byte[] f1 = BridgeCoreCodecs.encodeIdsBtLinkPacket(msg1);
            byte[] f2 = BridgeCoreCodecs.encodeIdsBtLinkPacket(msg2);
            byte[] combined = new byte[f1.length + f2.length];
            System.arraycopy(f1, 0, combined, 0, f1.length);
            System.arraycopy(f2, 0, combined, f1.length, f2.length);
            int split = f1.length + 3;
            hci.pushWatchInformationFrame(Arrays.copyOfRange(combined, 0, split));
            hci.pushWatchInformationFrame(Arrays.copyOfRange(combined, split, combined.length));

            assertArrayEquals(msg1, link.receiveControlFrame(2_000L));
            assertArrayEquals(msg2, link.receiveControlFrame(2_000L));
            assertEquals(2, link.framesReceived());
            assertFalse(link.checksumFlagSeen());
            assertTrue("RR ACK must be transmitted to remoteCid", hci.rxRrCount >= 2);
        }
    }

    @Test
    public void multiFrameStreamDecodingAndSupervisoryRrRejHandling() throws Exception {
        SyntheticIdsBtLinkTransport hci = new SyntheticIdsBtLinkTransport(false);
        try (L2capChannelMux mux = new L2capChannelMux(hci);
             IdsBtLinkTransport link = new IdsBtLinkTransport(mux, LOCAL_CID, REMOTE_CID, false)) {

            // Send outbound frame (TxSeq=0), then Watch sends REJ(reqSeq=0) asking for retransmit!
            byte[] outbound = "IDS-SYNC-1".getBytes(StandardCharsets.US_ASCII);
            link.sendControlFrame(outbound);
            assertEquals(1, hci.txWireIFrameCount);

            // Supervisory REJ (control=0x0005, reqSeq=0) -> triggers wire retransmission of TxSeq=0!
            hci.pushWatchRawErtmFrame(new byte[]{0x05, 0x00});
            // Supervisory RR Poll (control=0x0011, reqSeq=1) -> acknowledges TxSeq=0 and expects RR Final!
            hci.pushWatchRawErtmFrame(new byte[]{0x11, 0x01});

            // Watch sends out-of-order I-frame TxSeq=1 (expected 0) -> transport must emit REJ(reqSeq=0)
            hci.pushWatchRawErtmFrame(BridgeCoreCodecs.encodeErtmInformationFrame(
                    LOCAL_CID, 1, 1, BridgeCoreCodecs.encodeIdsBtLinkPacket(new byte[]{0x02, 0x44}), false));

            // Pack two IDSBTLink frames (second is checksum-flagged 0x81) into in-order ERTM I-frame TxSeq=0
            byte[] frame1 = "FRAME-ONE".getBytes(StandardCharsets.US_ASCII);
            byte[] p1 = BridgeCoreCodecs.encodeIdsBtLinkPacket(frame1);
            byte[] checksumFlaggedP2 = new byte[]{0x06, (byte) 0x81, 0x12, 0x34, 0x55, 0x66, 0x77};
            byte[] combinedSdu = new byte[p1.length + checksumFlaggedP2.length];
            System.arraycopy(p1, 0, combinedSdu, 0, p1.length);
            System.arraycopy(checksumFlaggedP2, 0, combinedSdu, p1.length, checksumFlaggedP2.length);
            hci.pushWatchRawErtmFrame(BridgeCoreCodecs.encodeErtmInformationFrame(
                    LOCAL_CID, 0, 1, combinedSdu, false));

            assertArrayEquals(frame1, link.receiveControlFrame(2_000L));
            assertArrayEquals(new byte[]{(byte) 0x81, 0x55, 0x66, 0x77}, link.receiveControlFrame(2_000L));
            assertTrue(link.checksumFlagSeen());
            assertEquals("REJ(reqSeq=0) must retransmit I-frame TxSeq=0 on wire", 2, hci.txWireIFrameCount);
            assertEquals(1, hci.txIFramePayloads.size());
            assertTrue("RR(P=1) must elicit RR(F=1)", hci.txFinalBitSeen);
            assertTrue("Out-of-order I-frame must elicit supervisory REJ", hci.txRejCount >= 1);
            assertTrue("In-order I-frame must elicit RR ACK", hci.rxRrCount >= 1);

            link.close();
            assertFalse(link.isOpen());
            assertThrows(HostException.class, () -> link.receiveControlFrame(100L));
            assertThrows(HostException.class, () -> link.sendControlFrame(new byte[]{0x01}));
        }
    }

    @Test
    public void lenientWatchOs1To5HelloDecodingViaTryDecodeLegacyHelloAndLastPeerHello() throws Exception {
        UUID instanceUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        byte[] legacyHelloPayload = buildLegacyHelloWithoutTlvs345(
                "1", "Watch OS", "4.3.2", "15U70", "Watch3,4", 2L, 1L, 3L, instanceUuid);

        // 1) Direct tryDecodeLegacyHello on unframed and 2-byte-length-prefixed legacy Hello
        IdsControlChannelCodec.HelloMessage direct = IdsBtLinkTransport.tryDecodeLegacyHello(legacyHelloPayload);
        assertNotNull(direct);
        assertEquals("1", direct.controlChannelVersion);
        assertEquals("Watch OS", direct.productName);
        assertEquals("4.3.2", direct.productVersion);
        assertEquals("15U70", direct.productBuildVersion);
        assertEquals("Watch3,4", direct.model);
        assertEquals(2L, direct.pairingProtocolVersion);
        assertEquals(1L, direct.minCompatibilityVersion);
        assertEquals(3L, direct.maxCompatibilityVersion);
        assertEquals(instanceUuid, direct.instanceId);
        assertEquals(instanceUuid, direct.deviceUniqueId);

        byte[] u16LengthPrefixed = new byte[2 + legacyHelloPayload.length];
        u16LengthPrefixed[0] = (byte) ((legacyHelloPayload.length >>> 8) & 0xFF);
        u16LengthPrefixed[1] = (byte) (legacyHelloPayload.length & 0xFF);
        System.arraycopy(legacyHelloPayload, 0, u16LengthPrefixed, 2, legacyHelloPayload.length);
        IdsControlChannelCodec.HelloMessage prefixed = IdsBtLinkTransport.tryDecodeLegacyHello(u16LengthPrefixed);
        assertNotNull(prefixed);
        assertEquals("Watch3,4", prefixed.model);

        // Invalid / non-Hello inputs return null cleanly
        assertNull(IdsBtLinkTransport.tryDecodeLegacyHello(null));
        assertNull(IdsBtLinkTransport.tryDecodeLegacyHello(new byte[]{0x01}));
        assertNull(IdsBtLinkTransport.tryDecodeLegacyHello(new byte[]{0x02, 0x06, 0x00, 0x01}));

        // 2) Full over-the-wire ERTM receive -> tryDecodeLegacyHello + lastPeerHello()
        SyntheticIdsBtLinkTransport hci = new SyntheticIdsBtLinkTransport(false);
        try (L2capChannelMux mux = new L2capChannelMux(hci);
             IdsBtLinkTransport link = new IdsBtLinkTransport(mux, LOCAL_CID, REMOTE_CID, false)) {
            assertFalse(link.lastPeerHello().isPresent());

            hci.pushWatchInformationFrame(BridgeCoreCodecs.encodeIdsBtLinkPacket(legacyHelloPayload));
            byte[] decoded = link.receiveControlFrame(2_000L);
            assertArrayEquals(legacyHelloPayload, decoded);
            assertNotNull(IdsBtLinkTransport.tryDecodeLegacyHello(decoded));

            IdsControlChannelCodec.HelloMessage hello = link.lastPeerHello().orElseThrow();
            assertEquals("Watch OS", hello.productName);
            assertEquals("4.3.2", hello.productVersion);
            assertEquals("15U70", hello.productBuildVersion);
            assertEquals("Watch3,4", hello.model);
            assertEquals(2L, hello.pairingProtocolVersion);
            assertEquals(1L, hello.minCompatibilityVersion);
            assertEquals(3L, hello.maxCompatibilityVersion);
            assertEquals(instanceUuid, hello.instanceId);

            String allLogs = String.join("\n", hci.logs);
            assertFalse("Payload bytes must never appear in logs",
                    allLogs.contains(BridgeCoreCodecs.toHex(legacyHelloPayload)));
        }
    }

    private static byte[] buildLegacyHelloWithoutTlvs345(
            String controlVersion,
            String productName,
            String productVersion,
            String buildVersion,
            String model,
            long pairingProtoVer,
            long minVer,
            long maxVer,
            UUID instanceId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(IdsControlChannelCodec.TYPE_HELLO);
        writeStringU16(out, controlVersion);
        writeStringU16(out, productName);
        writeStringU16(out, productVersion);
        writeStringU16(out, buildVersion);
        writeStringU16(out, model);
        writeU32(out, pairingProtoVer);
        out.write(0); writeU16(out, 4); writeU32(out, minVer);
        out.write(1); writeU16(out, 4); writeU32(out, maxVer);
        out.write(2); writeU16(out, 16);
        writeU64(out, instanceId.getMostSignificantBits());
        writeU64(out, instanceId.getLeastSignificantBits());
        return out.toByteArray();
    }

    private static void writeStringU16(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeU16(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >>> 24) & 0xff));
        out.write((int) ((v >>> 16) & 0xff));
        out.write((int) ((v >>> 8) & 0xff));
        out.write((int) (v & 0xff));
    }

    private static void writeU64(ByteArrayOutputStream out, long v) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((v >>> shift) & 0xff));
        }
    }

    private static final class SyntheticIdsBtLinkTransport implements HciTransport {
        private final BridgeCoreCodecs.ErtmLink watchPeerErtm;
        private final BluetoothLocalIdentity localId = BluetoothLocalIdentity.create(
                new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, (byte) 0xC1}, new byte[16]);
        private final BlockingQueue<Packet> inbound = new LinkedBlockingQueue<>();

        final List<byte[]> txIFramePayloads = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        int txWireIFrameCount;
        int rxRrCount;
        int txRejCount;
        boolean txFinalBitSeen;

        SyntheticIdsBtLinkTransport(boolean fcsEnabled) {
            this.watchPeerErtm = new BridgeCoreCodecs.ErtmLink(REMOTE_CID, LOCAL_CID, fcsEnabled);
        }

        void pushWatchInformationFrame(byte[] sdu) {
            byte[] ertmFrame = watchPeerErtm.sendInformation(sdu);
            pushWatchRawErtmFrame(ertmFrame);
        }

        void pushWatchRawErtmFrame(byte[] ertmPayload) {
            for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(HANDLE, LOCAL_CID, ertmPayload, 251)) {
                inbound.offer(new Packet(PacketKind.ACL, frag));
            }
        }

        byte[] reassembleTxIdsPacket() {
            BridgeCoreCodecs.IdsBtLinkDecoder dec = new BridgeCoreCodecs.IdsBtLinkDecoder();
            List<BridgeCoreCodecs.IdsBtLinkPacket> pkts = new ArrayList<>();
            for (byte[] sdu : txIFramePayloads) {
                pkts.addAll(dec.push(sdu));
            }
            assertEquals(1, pkts.size());
            return pkts.get(0).payload().clone();
        }

        @Override public int connectionHandle() { return HANDLE; }
        @Override public int peerAddressType() { return 1; }
        @Override public byte[] peerAddress() { return new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, (byte) 0xC2}; }
        @Override public BluetoothLocalIdentity localIdentity() { return localId; }
        @Override public void checkStop() { }

        @Override
        public void sendL2cap(int cid, byte[] payload, String label) {
            if (cid != REMOTE_CID) {
                return;
            }
            int control = (payload[0] & 0xff) | ((payload[1] & 0xff) << 8);
            if ((control & 0x0001) != 0) {
                int sFunc = (control >>> 2) & 0x03;
                if (sFunc == 0) {
                    rxRrCount++;
                } else if (sFunc == 1) {
                    txRejCount++;
                }
                if ((control & 0x0080) != 0) {
                    txFinalBitSeen = true;
                }
                return;
            }
            txWireIFrameCount++;
            try (BridgeCoreCodecs.ErtmInbound in = watchPeerErtm.accept(payload)) {
                txIFramePayloads.addAll(in.deliveredSdus());
            }
        }

        @Override
        public Packet nextPacket(long deadlineMs) throws Exception {
            long rem = Math.max(1L, deadlineMs - BridgeClock.elapsedRealtime());
            Packet pkt = inbound.poll(rem, TimeUnit.MILLISECONDS);
            if (pkt == null) {
                throw new TimeoutException("timeout");
            }
            return pkt;
        }

        @Override public void startLeEncryption(byte[] rand, int ediv, byte[] longTermKey) { }
        @Override public AutoCloseable openAttClientScope() { return () -> { }; }
        @Override public byte[] handlePeerAttRequest(byte[] request) { return null; }
        @Override public void log(String message) { logs.add(message); }
    }
}
