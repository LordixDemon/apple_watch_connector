package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.spi.IdsLinkKind;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.StrategyPolicy;
import dev.applewatchandroid.bridge.testing.FakeBluetoothController;
import dev.applewatchandroid.bridge.testing.VirtualWatchPeer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end contract tests driving the real {@link HalTransportSession},
 * {@link BluetoothFixedChannelRouter}, {@link HalHciTransportAdapter}, and modular S1–S4
 * strategy plugins through {@link FakeBluetoothController} + {@link VirtualWatchPeer}
 * with zero reflection.
 */
public final class PairingStrategyContractTest {
    private static final byte[] LOCAL_ADDR = new byte[]{0x21, 0x22, 0x23, 0x24, 0x25, (byte) 0xC1};
    private PrintStream previousOut;

    @Before
    public void setUp() {
        ProtocolHost.resetSessionControls();
        previousOut = System.out;
    }

    @After
    public void tearDown() {
        ProtocolHost.resetSessionControls();
        System.setOut(previousOut);
    }

    @Test
    public void strategy1FullPairPersistAndReconnectPreservingSc0EdivAndRandWithoutLegacyFlag() throws Exception {
        byte[] peerAddr = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, (byte) 0xC6};
        byte[] peerIrk = randomBytes(16);
        byte[] distLtk = randomBytes(16);
        byte[] expectedRand = new byte[]{0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, (byte) 0x80};
        int expectedEdiv = 0x4A2B;
        int passkey = 120394;

        VirtualWatchPeer watch = VirtualWatchPeer.s1(peerAddr, passkey, expectedEdiv, expectedRand, peerIrk, distLtk);
        byte[] localIdentityBytes = createLocalIdentityBytes();

        // Phase 1: Fresh Strategy 1 pairing (opt-in --strategies=4,1)
        ProtocolHost.setStrategyPolicy(StrategyPolicy.parseCsv("4,1"));
        SessionHarness fresh = new SessionHarness(watch, "120394", true, false);
        byte[] persistedAwp2;
        try {
            fresh.localIdentityRecords.offer(localIdentityBytes.clone());
            fresh.session.initialize();
            fresh.session.runTransportHandshake(false);

            assertTrue("Strategy 1 fresh pairing must complete milestone", fresh.session.legacyPairingCompleted());
            assertNull("Strategy 1 must skip GATT NANO write", watch.writtenNanoPayload);
            assertEquals(1, watch.idsHelloSentCount);

            String logs = fresh.logs();
            assertTrue("Must emit unified PIN_REQUIRED marker", logs.contains("PIN_REQUIRED:"));
            assertTrue("Must emit PAIRING_STRATEGY_V1:1", logs.contains("PAIRING_STRATEGY_V1:1"));
            assertTrue("Must decode minimal watchOS 2.2 Hello", logs.contains("IDSBTLink HELLO RX: product=Watch OS version=2.2"));
            assertNoSecretsLeaked(logs, "120394", distLtk, peerIrk, peerAddr, expectedRand, null);

            persistedAwp2 = fresh.lastPairingSessionRecordBytes.get();
            assertNotNull("AWP2 pairing session record must be persisted", persistedAwp2);
            PairingSessionRecord parsed = PairingSessionRecord.parse(persistedAwp2);
            try {
                assertEquals(PairingStrategyId.SIMULTANEOUS_S1, parsed.pairingStrategy());
                assertEquals(IdsLinkKind.IDS_BT_LINK, parsed.idsLinkKind());
                assertFalse("SC=0 must be persisted", parsed.smpSecureConnections());
                assertEquals(expectedEdiv, parsed.smpEdiv());
                assertArrayEquals(expectedRand, parsed.smpRand());
                assertTrue(parsed.isLegacyStrategy());
            } finally {
                parsed.destroy();
            }
        } finally {
            fresh.session.cleanup();
            fresh.restoreOut();
        }

        // Phase 2: Reset policy to PRODUCTION_DEFAULT (NO legacy CLI flag!) and reconnect from AWP2!
        ProtocolHost.resetSessionControls();
        assertFalse(ProtocolHost.effectiveStrategyPolicy().includesExperimental());
        watch.setPairedReconnectMode(true);

        SessionHarness reconnect = new SessionHarness(watch, null, true, false);
        try {
            reconnect.localIdentityRecords.offer(localIdentityBytes.clone());
            reconnect.restoredPairingSessionRecords.offer(persistedAwp2.clone());
            reconnect.session.initialize();
            reconnect.session.runTransportHandshake(false);

            assertTrue("Strategy 1 reconnect must succeed without legacy CLI flag",
                    reconnect.session.legacyPairingCompleted());
            assertTrue("Virtual S1 Watch must verify exact EDIV/Rand/LTK", watch.reconnectEncryptionVerified);
            assertEquals(expectedEdiv, reconnect.controller.lastStartEncEdiv());
            assertArrayEquals(expectedRand, reconnect.controller.lastStartEncRand());
            assertArrayEquals(distLtk, reconnect.controller.lastStartEncLtk());
            assertEquals(2, watch.idsHelloSentCount);
            assertTrue(reconnect.logs().contains("PAIRING_STRATEGY_V1:1"));
            assertTrue(reconnect.logs().contains("PAIRED RECONNECT PASS: strategy=S1"));
        } finally {
            reconnect.session.cleanup();
            reconnect.restoreOut();
            Arrays.fill(persistedAwp2, (byte) 0);
            Arrays.fill(localIdentityBytes, (byte) 0);
        }
    }

    @Test
    public void strategy2LiveAttRouterPassThroughWithConcurrentWatchAttAndReconnect() throws Exception {
        byte[] peerAddr = new byte[]{0x12, 0x23, 0x34, 0x45, 0x56, (byte) 0xC7};
        byte[] peerIrk = randomBytes(16);
        int passkey = 739204;

        VirtualWatchPeer watch = VirtualWatchPeer.s2(peerAddr, passkey, peerIrk);
        byte[] localIdentityBytes = createLocalIdentityBytes();

        ProtocolHost.setStrategyPolicy(StrategyPolicy.parseCsv("4,2"));
        SessionHarness fresh = new SessionHarness(watch, "739204", true, false);
        // Force multi-fragment ACL packets on CID 0x0004 to prove live BluetoothFixedChannelRouter
        // passes through fragmented ATT client responses while concurrently answering Watch ATT requests!
        fresh.controller.setMaxWatchAclLength(11);
        byte[] persistedAwp2;
        try {
            fresh.localIdentityRecords.offer(localIdentityBytes.clone());
            fresh.session.initialize();
            fresh.session.runTransportHandshake(false);

            assertTrue(fresh.session.legacyPairingCompleted());
            assertTrue("Watch-initiated concurrent ATT_READ_REQ(0x0003) must be answered with 'iPhone'",
                    watch.watchAttReadAnsweredWithIphone);
            assertTrue("GATT READ_BY_GROUP_TYPE fallback must succeed through live fixed router",
                    watch.usedReadByGroupTypeFallback);
            assertArrayEquals(new byte[]{'N', 'A', 'N', 'O', 0x02, 0x02}, watch.writtenNanoPayload);
            assertFalse("ATT client scope must be closed after GATT finishes",
                    fresh.session.normalLink.fixedChannelRouter.isAttClientActive());
            assertTrue(fresh.logs().contains("PAIRING_STRATEGY_V1:2"));
            assertTrue(fresh.logs().contains("IDSBTLink HELLO RX: product=Watch OS version=4.3.1"));
            assertNoSecretsLeaked(fresh.logs(), "739204", watch.expectedLtk(), peerIrk, peerAddr, null, null);

            persistedAwp2 = fresh.lastPairingSessionRecordBytes.get();
            assertNotNull(persistedAwp2);
        } finally {
            fresh.session.cleanup();
            fresh.restoreOut();
        }

        // Reconnect under PRODUCTION_DEFAULT policy
        ProtocolHost.resetSessionControls();
        watch.setPairedReconnectMode(true);
        SessionHarness reconnect = new SessionHarness(watch, null, true, false);
        try {
            reconnect.localIdentityRecords.offer(localIdentityBytes.clone());
            reconnect.restoredPairingSessionRecords.offer(persistedAwp2.clone());
            reconnect.session.initialize();
            reconnect.session.runTransportHandshake(false);

            assertTrue(reconnect.session.legacyPairingCompleted());
            assertTrue(watch.reconnectEncryptionVerified);
            assertEquals(0, reconnect.controller.lastStartEncEdiv());
            assertArrayEquals(new byte[8], reconnect.controller.lastStartEncRand());
            assertTrue(reconnect.logs().contains("PAIRED RECONNECT PASS: strategy=S2"));
        } finally {
            reconnect.session.cleanup();
            reconnect.restoreOut();
            Arrays.fill(persistedAwp2, (byte) 0);
            Arrays.fill(localIdentityBytes, (byte) 0);
        }
    }

    @Test
    public void strategy3OneWayOpticalOobWithFe25NameSynthesisTerminusPreludeAndReconnect() throws Exception {
        byte[] peerAddr = new byte[]{0x13, 0x24, 0x35, 0x46, 0x57, (byte) 0xC8};
        byte[] peerIrk = randomBytes(16);
        byte[] oobKey = randomBytes(16);
        StringBuilder oobHex = new StringBuilder(32);
        for (byte b : oobKey) {
            oobHex.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        byte[] opticalPayload = Arrays.copyOf(
                ("3--1--12345DAA--" + oobHex + "--0--0&&7.5").getBytes(StandardCharsets.US_ASCII),
                110);

        // FE25 strategy-3 advertisement WITHOUT AD local name -> relies on synthesized optical name "12345DAA"
        byte[] id3 = WatchSetupMetadataCodec.encodeIdentifier(3, 12345, 0, 0);
        byte[] fe25Ad = buildFe25SetupAd(id3, new byte[7]);
        VirtualWatchPeer watch = VirtualWatchPeer.s3Optical(peerAddr, fe25Ad, oobKey, peerIrk);
        byte[] localIdentityBytes = createLocalIdentityBytes();

        ProtocolHost.setStrategyPolicy(StrategyPolicy.parseCsv("4,3"));
        SessionHarness fresh = new SessionHarness(watch, null, true, false);
        byte[] persistedAwp2;
        try {
            fresh.session.configureOpticalPairing(opticalPayload.clone());
            fresh.localIdentityRecords.offer(localIdentityBytes.clone());
            fresh.session.initialize();
            fresh.session.runTransportHandshake(false);

            assertTrue(fresh.session.legacyPairingCompleted());
            assertArrayEquals("Strategy 3 optical must write NANO 0x03 0x01",
                    new byte[]{'N', 'A', 'N', 'O', 0x03, 0x01}, watch.writtenNanoPayload);
            assertEquals(1, watch.terminusPreludeRepliedCount);
            assertEquals(BridgeCoreCodecs.PRELUDE_STATE_PAIR_WITH_OOB_KEY, watch.lastTerminusPreludeRemoteState);
            assertTrue(fresh.logs().contains("PAIRING_STRATEGY_V1:3"));
            assertTrue(fresh.logs().contains("TERMINUS PRELUDE PASS"));
            assertNoSecretsLeaked(fresh.logs(), null, watch.expectedLtk(), peerIrk, peerAddr, null, oobKey);

            persistedAwp2 = fresh.lastPairingSessionRecordBytes.get();
            assertNotNull(persistedAwp2);
            PairingSessionRecord parsed = PairingSessionRecord.parse(persistedAwp2);
            try {
                assertEquals(PairingStrategyId.DIRECT_IPSEC_S3, parsed.pairingStrategy());
                assertEquals(IdsLinkKind.DIRECT_IPSEC_OOBK, parsed.idsLinkKind());
                assertTrue(parsed.smpSecureConnections());
            } finally {
                parsed.destroy();
            }
        } finally {
            fresh.session.cleanup();
            fresh.restoreOut();
        }

        // Reconnect under PRODUCTION_DEFAULT policy
        ProtocolHost.resetSessionControls();
        watch.setPairedReconnectMode(true);
        SessionHarness reconnect = new SessionHarness(watch, null, true, false);
        try {
            reconnect.localIdentityRecords.offer(localIdentityBytes.clone());
            reconnect.restoredPairingSessionRecords.offer(persistedAwp2.clone());
            reconnect.session.initialize();
            reconnect.session.runTransportHandshake(false);

            assertTrue(reconnect.session.legacyPairingCompleted());
            assertTrue(watch.reconnectEncryptionVerified);
            assertEquals(2, watch.terminusPreludeRepliedCount);
            assertEquals(BridgeCoreCodecs.PRELUDE_STATE_HAS_COMPLETED_PAIRING, watch.lastTerminusPreludeRemoteState);
            assertTrue(reconnect.logs().contains("PAIRED RECONNECT PASS: strategy=S3"));
        } finally {
            reconnect.session.cleanup();
            reconnect.restoreOut();
            Arrays.fill(persistedAwp2, (byte) 0);
            Arrays.fill(localIdentityBytes, (byte) 0);
            Arrays.fill(oobKey, (byte) 0);
        }
    }

    @Test
    public void failClosedBondStoreRejectionAbortsBeforeLocalKeysAndNeverPersistsSession() throws Exception {
        byte[] peerAddr = new byte[]{0x14, 0x25, 0x36, 0x47, 0x58, (byte) 0xC9};
        VirtualWatchPeer watch = VirtualWatchPeer.s1(
                peerAddr, 654321, 0x2233, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, randomBytes(16), randomBytes(16));
        byte[] localIdentityBytes = createLocalIdentityBytes();

        ProtocolHost.setStrategyPolicy(StrategyPolicy.parseCsv("4,1"));
        SessionHarness harness = new SessionHarness(watch, "654321", false, false);
        try {
            harness.localIdentityRecords.offer(localIdentityBytes.clone());
            harness.session.initialize();
            HostException err = assertThrows(HostException.class, () -> harness.session.runTransportHandshake(false));
            assertTrue(err.getMessage().contains("bond-store"));
            assertFalse(harness.session.legacyPairingCompleted());
            assertEquals(BluetoothSmpCodec.FAILURE_UNSPECIFIED_REASON, watch.pairingFailedReason);
            assertEquals("Local keys must NOT be distributed after rejected bond store",
                    0, watch.localKeysReceivedCount);
            assertNull("AWP2 session must NOT be persisted if bond store failed",
                    harness.lastPairingSessionRecordBytes.get());
        } finally {
            harness.session.cleanup();
            harness.restoreOut();
            Arrays.fill(localIdentityBytes, (byte) 0);
        }
    }

    @Test
    public void stopRequestedDuringPinPromptTriggersCleanHciDisconnectAndRouterReset() throws Exception {
        byte[] peerAddr = new byte[]{0x15, 0x26, 0x37, 0x48, 0x59, (byte) 0xCA};
        VirtualWatchPeer watch = VirtualWatchPeer.s1(
                peerAddr, 555666, 0x1111, new byte[]{1, 1, 1, 1, 1, 1, 1, 1}, randomBytes(16), randomBytes(16));
        byte[] localIdentityBytes = createLocalIdentityBytes();

        ProtocolHost.setStrategyPolicy(StrategyPolicy.parseCsv("4,1"));
        SessionHarness harness = new SessionHarness(watch, null, true, true);
        try {
            harness.localIdentityRecords.offer(localIdentityBytes.clone());
            harness.session.initialize();
            assertThrows(HalTransportSession.StopRequested.class, () -> harness.session.runTransportHandshake(false));
            harness.session.cleanup();
            assertTrue("Cleanup must send HCI Disconnect on STOP", harness.controller.disconnectCount() >= 1);
            assertFalse(harness.session.normalLink.fixedChannelRouter.isAttClientActive());
            assertFalse(harness.session.legacyPairingCompleted());
        } finally {
            harness.session.cleanup();
            harness.restoreOut();
            Arrays.fill(localIdentityBytes, (byte) 0);
        }
    }

    private static byte[] createLocalIdentityBytes() {
        byte[] irk = randomBytes(16);
        try (BluetoothLocalIdentity id = BluetoothLocalIdentity.create(LOCAL_ADDR, irk)) {
            return id.serialize();
        } finally {
            Arrays.fill(irk, (byte) 0);
        }
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static byte[] buildFe25SetupAd(byte[] id, byte[] meta) {
        byte[] ad = new byte[10 + meta.length];
        ad[0] = (byte) (ad.length - 1);
        ad[1] = 0x16;
        ad[2] = 0x25;
        ad[3] = (byte) 0xFE;
        ad[4] = 0x06;
        ad[5] = 0x20;
        System.arraycopy(id, 0, ad, 6, 4);
        System.arraycopy(meta, 0, ad, 10, meta.length);
        return ad;
    }

    private static void assertNoSecretsLeaked(
            String logs,
            String pin,
            byte[] ltk,
            byte[] peerIrk,
            byte[] peerAddr,
            byte[] rand,
            byte[] oobKey) {
        String lower = logs.toLowerCase(Locale.US);
        if (pin != null) {
            assertFalse("PIN must never appear in logs", lower.contains(pin));
        }
        for (byte[] secret : new byte[][]{ltk, peerIrk, peerAddr, rand, oobKey}) {
            if (secret == null) continue;
            String hex = HciCodec.toHex(secret).toLowerCase(Locale.US);
            assertFalse("Secret hex must never appear in logs", lower.contains(hex));
        }
    }

    /** Captures System.out lines in real time and drives stdin-equivalent queues cleanly. */
    private final class SessionHarness {
        final FakeBluetoothController controller = new FakeBluetoothController();
        final AtomicBoolean stopRequested = new AtomicBoolean();
        final BlockingQueue<char[]> pinInputs = new LinkedBlockingQueue<>(1);
        final BlockingQueue<Boolean> bondStoreResults = new LinkedBlockingQueue<>(1);
        final BlockingQueue<Boolean> pairingSessionStoreResults = new LinkedBlockingQueue<>(1);
        final BlockingQueue<byte[]> localIdentityRecords = new LinkedBlockingQueue<>(1);
        final BlockingQueue<byte[]> localIdsPublicRecords = new LinkedBlockingQueue<>(1);
        final BlockingQueue<byte[]> restoredPairingSessionRecords = new LinkedBlockingQueue<>(1);
        final BlockingQueue<ProtocolHost.OutboundAppMessage> outboundAppMessages = new LinkedBlockingQueue<>(32);
        final HalTransportSession session;
        final AtomicReference<byte[]> lastPairingSessionRecordBytes = new AtomicReference<>();
        final StringBuilder capturedLogs = new StringBuilder();

        SessionHarness(
                VirtualWatchPeer peer,
                String pinToReply,
                boolean acceptBondStore,
                boolean stopOnPinPrompt) {
            controller.addPeer(peer);
            session = new HalTransportSession(
                    controller,
                    stopRequested,
                    pinInputs,
                    bondStoreResults,
                    pairingSessionStoreResults,
                    localIdentityRecords,
                    localIdsPublicRecords,
                    restoredPairingSessionRecords,
                    outboundAppMessages,
                    true,
                    null);
            System.setOut(new PrintStream(new LineDispatchOutputStream(line -> {
                synchronized (capturedLogs) {
                    capturedLogs.append(line).append('\n');
                }
                if (line.startsWith(ProtocolHost.BOND_SECRET_PREFIX)) {
                    bondStoreResults.offer(acceptBondStore);
                } else if (line.startsWith(ProtocolHost.PAIRING_SESSION_PREFIX)) {
                    String b64 = line.substring(ProtocolHost.PAIRING_SESSION_PREFIX.length()).trim();
                    lastPairingSessionRecordBytes.set(BridgeBase64.decode(b64, BridgeBase64.NO_WRAP));
                    pairingSessionStoreResults.offer(true);
                } else if (line.contains("PIN_REQUIRED:")) {
                    if (stopOnPinPrompt) {
                        stopRequested.set(true);
                    } else if (pinToReply != null) {
                        pinInputs.offer(pinToReply.toCharArray());
                    }
                }
            }), true, StandardCharsets.UTF_8));
        }

        String logs() {
            synchronized (capturedLogs) {
                return capturedLogs.toString();
            }
        }

        void restoreOut() {
            System.setOut(previousOut);
        }
    }

    private static final class LineDispatchOutputStream extends OutputStream {
        private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        private final java.util.function.Consumer<String> onLine;

        LineDispatchOutputStream(java.util.function.Consumer<String> onLine) {
            this.onLine = onLine;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                String line = lineBuf.toString(StandardCharsets.UTF_8);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                lineBuf.reset();
                onLine.accept(line);
            } else {
                lineBuf.write(b);
            }
        }
    }
}
