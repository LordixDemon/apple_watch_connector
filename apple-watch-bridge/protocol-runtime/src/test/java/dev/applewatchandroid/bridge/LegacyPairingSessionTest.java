package dev.applewatchandroid.bridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * [EXPERIMENTAL] Synthetic controller tests covering the opt-in legacy Watch pairing
 * path (Strategies 1-3, GATT 9AA4730F/5F6C6A23, LE SC Passkey 20-round & LE Legacy SC=0,
 * and post-SMP BT_CL handoffs to terminusLink / com.apple.ids) plus default-off isolation.
 */
public final class LegacyPairingSessionTest {
    private static final int HANDLE = 0x0042;
    private static final byte[] PEER_ADDR = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, (byte) 0xC6};
    private static final int PEER_ADDR_TYPE = 1;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private PrintStream previousOut;

    @Before
    public void setUp() {
        ProtocolHost.resetSessionControls();
        previousOut = System.out;
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    @After
    public void tearDown() {
        ProtocolHost.resetSessionControls();
        System.setOut(previousOut);
    }

    @Test
    public void defaultModeIgnoresLegacyAdvertisementsAndSelectsModernStrategy4() throws Exception {
        assertFalse(ProtocolHost.EXPERIMENTAL_LEGACY_PAIRING.get());

        FakeController controller = new FakeController();
        HalTransportSession session = new HalTransportSession(
                controller, new AtomicBoolean(),
                new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(),
                new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(),
                new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(),
                new LinkedBlockingQueue<>(), false, null);
        BluetoothController.Callbacks callbacks = HalTransportSession_Helper.callbacks(session);
        byte[] legacyAd = new byte[]{
                17, 0x06,
                (byte) 0x96, (byte) 0xC1, (byte) 0x9F, (byte) 0x55,
                0x31, (byte) 0xC9, 0x21, (byte) 0xB8,
                (byte) 0xC3, 0x4C, 0x5C, (byte) 0xB2,
                0x0F, 0x73, (byte) 0xA4, (byte) 0x9A,
                9, 0x09, '1', '2', '3', '4', '5', 'D', 'A', 'B'
        };
        byte[] modernId = WatchSetupMetadataCodec.encodeIdentifier(4, 12345, 1, 0);
        byte[] modernMeta = WatchSetupMetadataCodec.encodeExtendedMetadata(25, 7, 5, false, 0x001A0200L, new byte[0]);
        byte[] modernFullAd = buildFe25SetupAd(modernId, modernMeta);

        byte[] legacyMac = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x41};
        byte[] modernMac = new byte[]{0x09, 0x08, 0x07, 0x06, 0x05, 0x42};
        callbacks.hciEventReceived(leAdvReportEvent(0, 1, legacyMac, -40, legacyAd));
        callbacks.hciEventReceived(leAdvReportEvent(0, 1, modernMac, -55, modernFullAd));

        Method awaitTarget = HalTransportSession.class.getDeclaredMethod("awaitTarget");
        awaitTarget.setAccessible(true);
        HciCodec.AdvertisingReport selected = (HciCodec.AdvertisingReport) awaitTarget.invoke(session);
        assertNotNull(selected);
        assertTrue(selected.isExpectedTarget());
        assertArrayEquals(modernMac, selected.address);
    }

    @Test
    public void strategy3PasskeyEndToEndWithTerminusLinkPreludeAndZeroSecretLogs() throws Exception {
        ProtocolHost.EXPERIMENTAL_LEGACY_PAIRING.set(true);
        final int passkey = 482913;
        byte[] localAddr = new byte[]{0x21, 0x22, 0x23, 0x24, 0x25, (byte) 0xC1};
        byte[] localIrk = new byte[16];
        byte[] peerIrk = new byte[16];
        new SecureRandom().nextBytes(localIrk);
        new SecureRandom().nextBytes(peerIrk);
        byte[] peerIdAddr = new byte[]{0x31, 0x32, 0x33, 0x34, 0x35, (byte) 0xC2};

        SyntheticLegacyHarness harness = new SyntheticLegacyHarness(
                localAddr, localIrk, peerIrk, peerIdAddr,
                passkey, true, true, false,
                LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL_DIRECT_IPSEC);

        byte[] id3 = WatchSetupMetadataCodec.encodeIdentifier(3, 12345, 1, 0);
        byte[] adv = buildFe25SetupAd(id3, new byte[7]);
        HciCodec.AdvertisingReport target = new HciCodec.AdvertisingReport(
                0, PEER_ADDR_TYPE, PEER_ADDR.clone(), -52, adv, HciCodec.parseWatchSetupPayload(adv));

        harness.queuePin("482913");
        LegacyPairingRunner.LegacyPairingResult result = harness.run(target, null);

        assertEquals(LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL_DIRECT_IPSEC, result.strategy);
        assertEquals(LegacyWatchPairingCodec.STYLE_PASSCODE, result.style);
        assertTrue(result.secureConnections);
        assertTrue(result.bondPersisted);
        assertEquals(NrLinkBluetoothPrelude.PairingState.PAIR_WITH_IDS_KEYS, result.terminusReconciledState);
        assertNotNull(result.terminusRole);
        assertArrayEquals(new byte[]{'N', 'A', 'N', 'O', 0x03, 0x02}, harness.writtenNanoPayload);

        String logs = output.toString(StandardCharsets.UTF_8);
        assertFalse("PIN must never appear in logs", logs.contains("482913"));
        assertFalse("Peer IRK must never appear in logs", logs.contains(HciCodec.toHex(peerIrk)));
        assertFalse("Peer address must never appear in logs", logs.contains(HciCodec.toHex(PEER_ADDR)));
        assertTrue(logs.contains("[EXPERIMENTAL LEGACY] TERMINUS PRELUDE PASS"));
    }

    @Test
    public void strategy2FallsBackToReadByGroupTypeAndOpensIdsBtLink() throws Exception {
        ProtocolHost.EXPERIMENTAL_LEGACY_PAIRING.set(true);
        final int passkey = 739204;
        byte[] localAddr = new byte[]{0x21, 0x22, 0x23, 0x24, 0x25, (byte) 0xC1};
        byte[] localIrk = new byte[16];
        byte[] peerIrk = new byte[16];
        new SecureRandom().nextBytes(localIrk);
        new SecureRandom().nextBytes(peerIrk);
        byte[] peerIdAddr = new byte[]{0x31, 0x32, 0x33, 0x34, 0x35, (byte) 0xC2};

        SyntheticLegacyHarness harness = new SyntheticLegacyHarness(
                localAddr, localIrk, peerIrk, peerIdAddr,
                passkey, true, false, true,
                LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL);

        byte[] adv = new byte[]{9, 0x09, '5', '4', '3', '2', '1', 'C', 'B', 'C'};
        HciCodec.AdvertisingReport target = new HciCodec.AdvertisingReport(
                0, PEER_ADDR_TYPE, PEER_ADDR.clone(), -58, adv, null);

        harness.queuePin("739204");
        LegacyPairingRunner.LegacyPairingResult result = harness.run(target, null);

        assertEquals(LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL, result.strategy);
        assertTrue(result.bondPersisted);
        assertTrue(result.idsBtLinkOpened);
        assertEquals(1, result.idsBtLinkFramesDecoded);
        assertTrue(harness.usedReadByGroupTypeFallback);
        assertArrayEquals(new byte[]{'N', 'A', 'N', 'O', 0x02, 0x02}, harness.writtenNanoPayload);
    }

    @Test
    public void strategy1SkipsGattAndCompletesLeLegacySc0Smp() throws Exception {
        ProtocolHost.EXPERIMENTAL_LEGACY_PAIRING.set(true);
        final int passkey = 120394;
        byte[] localAddr = new byte[]{0x21, 0x22, 0x23, 0x24, 0x25, (byte) 0xC1};
        byte[] localIrk = new byte[16];
        byte[] peerIrk = new byte[16];
        new SecureRandom().nextBytes(localIrk);
        new SecureRandom().nextBytes(peerIrk);
        byte[] peerIdAddr = new byte[]{0x31, 0x32, 0x33, 0x34, 0x35, (byte) 0xC2};

        SyntheticLegacyHarness harness = new SyntheticLegacyHarness(
                localAddr, localIrk, peerIrk, peerIdAddr,
                passkey, false, false, false,
                LegacyWatchPairingCodec.STRATEGY_SIMULTANEOUS);

        byte[] adv = new byte[]{9, 0x09, '1', '1', '1', '1', '1', 'B', 'A', 'A'};
        HciCodec.AdvertisingReport target = new HciCodec.AdvertisingReport(
                0, PEER_ADDR_TYPE, PEER_ADDR.clone(), -60, adv, null);

        harness.queuePin("120394");
        LegacyPairingRunner.LegacyPairingResult result = harness.run(target, null);

        assertEquals(LegacyWatchPairingCodec.STRATEGY_SIMULTANEOUS, result.strategy);
        assertFalse(result.secureConnections);
        assertTrue(result.bondPersisted);
        assertNull("Strategy 1 must skip GATT NANO write", harness.writtenNanoPayload);
        assertTrue("Initiator must distribute 0x06 Encryption Info in SC=0", harness.initiatorSentEncInfo);
        assertTrue("Initiator must distribute 0x0A Signing Info when negotiated", harness.initiatorSentSigningInfo);
    }

    @Test
    public void wrongPasskeySendsConfirmValueFailedAndNeverPersistsBond() throws Exception {
        ProtocolHost.EXPERIMENTAL_LEGACY_PAIRING.set(true);
        byte[] localAddr = new byte[]{0x21, 0x22, 0x23, 0x24, 0x25, (byte) 0xC1};
        byte[] localIrk = new byte[16];
        byte[] peerIrk = new byte[16];
        byte[] peerIdAddr = new byte[]{0x31, 0x32, 0x33, 0x34, 0x35, (byte) 0xC2};

        SyntheticLegacyHarness harness = new SyntheticLegacyHarness(
                localAddr, localIrk, peerIrk, peerIdAddr,
                999999, true, false, false,
                LegacyWatchPairingCodec.STRATEGY_SIMULTANEOUS);

        byte[] adv = new byte[]{9, 0x09, '2', '2', '2', '2', '2', 'B', 'A', 'A'};
        HciCodec.AdvertisingReport target = new HciCodec.AdvertisingReport(
                0, PEER_ADDR_TYPE, PEER_ADDR.clone(), -50, adv, null);

        harness.queuePin("111111");
        Exception failure = assertThrows(Exception.class, () -> harness.run(target, null));
        assertTrue(failure.getMessage().contains("mismatch"));
        assertTrue(harness.initiatorSentConfirmValueFailed);
        assertEquals(0, harness.bondPersistCount);
        assertFalse(harness.encryptionStarted);
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

    private static byte[] leAdvReportEvent(int eventType, int addrType, byte[] addr, int rssi, byte[] data) {
        byte[] evt = new byte[13 + data.length];
        evt[0] = 0x3E;
        evt[1] = (byte) (evt.length - 2);
        evt[2] = 0x02;
        evt[3] = 0x01;
        evt[4] = (byte) eventType;
        evt[5] = (byte) addrType;
        System.arraycopy(addr, 0, evt, 6, 6);
        evt[12] = (byte) data.length;
        System.arraycopy(data, 0, evt, 13, data.length);
        byte[] withRssi = Arrays.copyOf(evt, evt.length + 1);
        withRssi[1] = (byte) (withRssi.length - 2);
        withRssi[withRssi.length - 1] = (byte) rssi;
        return withRssi;
    }

    private static final class HalTransportSession_Accessor {
        static HalTransportSession.Packet eventPacket(byte[] bytes) {
            return new HalTransportSession.Packet(HalTransportSession.PacketKind.EVENT, bytes);
        }
        static HalTransportSession.Packet aclPacket(byte[] bytes) {
            return new HalTransportSession.Packet(HalTransportSession.PacketKind.ACL, bytes);
        }
    }

    private static final class FakeController implements BluetoothController {
        @Override public void initialize(Callbacks callbacks) { }
        @Override public void sendHciCommand(byte[] bytes) { }
        @Override public void sendAclData(byte[] bytes) { }
        @Override public void close() { }
    }

    /** Reuses reflection once to extract Session callbacks without altering production visibility. */
    private static final class HalTransportSession_Helper {
        static BluetoothController.Callbacks callbacks(HalTransportSession s) throws Exception {
            var f = HalTransportSession.class.getDeclaredField("callbacks");
            f.setAccessible(true);
            return (BluetoothController.Callbacks) f.get(s);
        }
    }

    // Small helper wrapper in HalTransportSession scope
    static {
        // verify package access compiles
    }

    private static final class SyntheticLegacyHarness {
        private final BluetoothLocalIdentity localId;
        private final byte[] localAddr;
        private final byte[] peerIrk;
        private final byte[] peerIdAddr;
        private final int watchPasskey;
        private final boolean watchSupportsSc;
        private final boolean watchSupportsTerminusLink;
        private final boolean forceGroupTypeFallback;
        private final int strategy;

        private final BlockingQueue<HalTransportSession.Packet> inbound = new LinkedBlockingQueue<>();
        private final BlockingQueue<char[]> pins = new LinkedBlockingQueue<>();
        private final SecureRandom rng = new SecureRandom();
        private final HciCodec.AclReassembler txDecoder = new HciCodec.AclReassembler();

        private BleSecureConnectionsCrypto.LocalOobMaterial watchEc;
        private byte[] preq;
        private byte[] pres;
        private byte[] pka;
        private byte[] dhKey;
        private byte[] lastCa;
        private byte[] lastNa;
        private byte[] lastNb;
        private byte[] macKey;
        private byte[] scLtk;
        private byte[] legacyStk;
        private int passkeyRound;
        private int peerBtClVersion = 11;

        byte[] writtenNanoPayload;
        boolean usedReadByGroupTypeFallback;
        boolean initiatorSentEncInfo;
        boolean initiatorSentSigningInfo;
        boolean initiatorSentConfirmValueFailed;
        boolean encryptionStarted;
        int bondPersistCount;

        SyntheticLegacyHarness(
                byte[] localAddr,
                byte[] localIrk,
                byte[] peerIrk,
                byte[] peerIdAddr,
                int watchPasskey,
                boolean watchSupportsSc,
                boolean watchSupportsTerminusLink,
                boolean forceGroupTypeFallback,
                int strategy) {
            this.localAddr = localAddr.clone();
            this.localId = BluetoothLocalIdentity.create(localAddr, localIrk);
            this.peerIrk = peerIrk.clone();
            this.peerIdAddr = peerIdAddr.clone();
            this.watchPasskey = watchPasskey;
            this.watchSupportsSc = watchSupportsSc;
            this.watchSupportsTerminusLink = watchSupportsTerminusLink;
            this.forceGroupTypeFallback = forceGroupTypeFallback;
            this.strategy = strategy;
        }

        void queuePin(String digits) {
            pins.offer(digits.toCharArray());
        }

        LegacyPairingRunner.LegacyPairingResult run(
                HciCodec.AdvertisingReport target,
                LegacyWatchPairingCodec.LegacyOpticalCode opticalCode) throws Exception {
            LegacyPairingRunner runner = new LegacyPairingRunner(new LegacyPairingRunner.Transport() {
                private final HciCodec.AclReassembler rxReassembler = new HciCodec.AclReassembler();

                @Override public int connectionHandle() { return HANDLE; }
                @Override public int peerAddressType() { return PEER_ADDR_TYPE; }
                @Override public byte[] peerAddress() { return PEER_ADDR.clone(); }
                @Override public BluetoothLocalIdentity localIdentity() { return localId; }
                @Override public void checkStop() { }
                @Override public void sendL2cap(int cid, byte[] payload, String label) {
                    onCompanionSendL2cap(cid, payload);
                }
                @Override public HalTransportSession.Packet nextPacket(long deadlineMs) throws Exception {
                    long rem = Math.max(1L, deadlineMs - BridgeClock.elapsedRealtime());
                    HalTransportSession.Packet p = inbound.poll(rem, TimeUnit.MILLISECONDS);
                    if (p == null) {
                        throw new java.util.concurrent.TimeoutException("synthetic timeout");
                    }
                    return p;
                }
                @Override public HciCodec.L2capPdu reassembleAcl(byte[] acl) {
                    return rxReassembler.accept(acl);
                }
                @Override public byte[] handlePeerAttRequest(byte[] request) { return null; }
                @Override public void startLeEncryption(byte[] rand, int ediv, byte[] longTermKey) {
                    onCompanionStartEncryption(rand, ediv, longTermKey);
                }
                @Override public char[] pollPinInput() { return pins.poll(); }
                @Override public void persistBondSecretOrFail(byte[] bondSecretRecord) {
                    BluetoothBondSecretRecord.validateSerialized(bondSecretRecord);
                    bondPersistCount++;
                }
            }, rng);
            return runner.run(target, opticalCode);
        }

        private void pushAclFromWatch(int cid, byte[] payload) {
            for (byte[] frag : HciCodec.buildLeAclL2capFragments(HANDLE, cid, payload, 251)) {
                inbound.offer(HalTransportSession_Accessor.aclPacket(frag));
            }
        }

        private void onCompanionSendL2cap(int cid, byte[] payload) {
            if (cid == LegacyWatchPairingCodec.ATT_FIXED_CID) {
                handleAtt(payload);
            } else if (cid == BluetoothSmpCodec.FIXED_CID) {
                handleSmp(payload);
            } else if (cid == HciCodec.BT_CL_SIGNALING_CID) {
                handleBtCl(payload);
            } else if (cid == 0x0045) {
                handleTerminusLinkErtm(payload);
            } else if (cid == 0x0046) {
                // ACK frame on IDSBTLink ERTM
            }
        }

        private void handleAtt(byte[] req) {
            int op = req[0] & 0xff;
            if (op == LegacyWatchPairingCodec.ATT_OPCODE_EXCHANGE_MTU_REQ) {
                pushAclFromWatch(0x0004, new byte[]{0x03, (byte) 185, 0x00});
            } else if (op == LegacyWatchPairingCodec.ATT_OPCODE_FIND_BY_TYPE_VALUE_REQ) {
                if (forceGroupTypeFallback) {
                    pushAclFromWatch(0x0004, new byte[]{0x01, 0x06, 0x01, 0x00, 0x06});
                } else {
                    pushAclFromWatch(0x0004, new byte[]{0x07, 0x20, 0x00, 0x24, 0x00});
                }
            } else if (op == LegacyWatchPairingCodec.ATT_OPCODE_READ_BY_GROUP_TYPE_REQ) {
                usedReadByGroupTypeFallback = true;
                int start = (req[1] & 0xff) | ((req[2] & 0xff) << 8);
                if (start == 1) {
                    pushAclFromWatch(0x0004, new byte[]{0x11, 0x06, 0x01, 0x00, 0x05, 0x00, 0x00, 0x18});
                } else {
                    byte[] rsp = new byte[2 + 20];
                    rsp[0] = 0x11;
                    rsp[1] = 20;
                    rsp[2] = 0x20; rsp[3] = 0x00;
                    rsp[4] = 0x24; rsp[5] = 0x00;
                    System.arraycopy(LegacyWatchPairingCodec.EXTENSIBLE_PAIR_SERVICE_UUID_LE, 0, rsp, 6, 16);
                    pushAclFromWatch(0x0004, rsp);
                }
            } else if (op == LegacyWatchPairingCodec.ATT_OPCODE_READ_BY_TYPE_REQ) {
                byte[] rsp = new byte[2 + 21];
                rsp[0] = 0x09;
                rsp[1] = 21;
                rsp[2] = 0x21; rsp[3] = 0x00;
                rsp[4] = 0x1A;
                rsp[5] = 0x22; rsp[6] = 0x00;
                System.arraycopy(LegacyWatchPairingCodec.EXTENSIBLE_PAIR_CONTROL_CHAR_UUID_LE, 0, rsp, 7, 16);
                pushAclFromWatch(0x0004, rsp);
            } else if (op == LegacyWatchPairingCodec.ATT_OPCODE_WRITE_REQ) {
                writtenNanoPayload = Arrays.copyOfRange(req, 3, req.length);
                pushAclFromWatch(0x0004, new byte[]{0x13});
            }
        }

        private void handleSmp(byte[] pdu) {
            int op = pdu[0] & 0xff;
            if (op == BluetoothSmpCodec.PAIRING_REQUEST) {
                preq = pdu.clone();
                int auth = watchSupportsSc
                        ? BluetoothSmpCodec.AUTH_SC_MITM_BOND
                        : (BluetoothSmpCodec.AUTH_BONDING | BluetoothSmpCodec.AUTH_MITM);
                pres = new byte[]{
                        BluetoothSmpCodec.PAIRING_RESPONSE,
                        BluetoothSmpCodec.IO_CAPABILITY_DISPLAY_ONLY,
                        BluetoothSmpCodec.OOB_DATA_NOT_PRESENT,
                        (byte) auth,
                        16,
                        0x07,
                        0x07
                };
                pushAclFromWatch(BluetoothSmpCodec.FIXED_CID, pres.clone());
            } else if (op == BluetoothSmpCodec.PAIRING_PUBLIC_KEY) {
                pka = BluetoothSmpCodec.parsePairingPublicKey(pdu);
                watchEc = BleSecureConnectionsCrypto.generateLocalOob(rng);
                dhKey = watchEc.calculateDhKey(pka);
                pushAclFromWatch(BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec.pairingPublicKey(watchEc.publicKeyForSmp()));
                passkeyRound = 0;
            } else if (op == BluetoothSmpCodec.PAIRING_CONFIRM) {
                if (watchSupportsSc) {
                    lastCa = BluetoothSmpCodec.parsePairingConfirm(pdu);
                    lastNb = new byte[16];
                    rng.nextBytes(lastNb);
                    int z = BleSecureConnectionsCrypto.passkeyRoundZ(watchPasskey, passkeyRound);
                    byte[] cb = BleSecureConnectionsCrypto.f4(
                            watchEc.publicXLittleEndian(),
                            Arrays.copyOfRange(pka, 0, 32),
                            lastNb,
                            z);
                    pushAclFromWatch(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.pairingConfirm(cb));
                } else {
                    lastCa = BluetoothSmpCodec.parsePairingConfirm(pdu);
                    lastNb = new byte[16];
                    rng.nextBytes(lastNb);
                    byte[] tk = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(watchPasskey);
                    byte[] sConfirm = BleSecureConnectionsCrypto.legacyC1(
                            tk, lastNb, preq, pres, 1, localAddr, PEER_ADDR_TYPE, PEER_ADDR);
                    pushAclFromWatch(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.pairingConfirm(sConfirm));
                }
            } else if (op == BluetoothSmpCodec.PAIRING_RANDOM) {
                lastNa = BluetoothSmpCodec.parsePairingRandom(pdu);
                pushAclFromWatch(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.pairingRandom(lastNb));
                if (!watchSupportsSc) {
                    byte[] tk = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(watchPasskey);
                    legacyStk = BleSecureConnectionsCrypto.legacyS1(tk, lastNb, lastNa);
                } else {
                    passkeyRound++;
                }
            } else if (op == BluetoothSmpCodec.PAIRING_DHKEY_CHECK) {
                byte[] ea = BluetoothSmpCodec.parsePairingDhKeyCheck(pdu);
                byte[] localTyped = typed(localAddr, 1);
                byte[] peerTyped = typed(PEER_ADDR, PEER_ADDR_TYPE);
                BleSecureConnectionsCrypto.F5Result f5 =
                        BleSecureConnectionsCrypto.f5(dhKey, lastNa, lastNb, localTyped, peerTyped);
                macKey = f5.macKey();
                scLtk = f5.longTermKey();
                byte[] r = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(watchPasskey);
                byte[] localIo = new byte[]{preq[1], preq[2], preq[3]};
                byte[] peerIo = new byte[]{pres[1], pres[2], pres[3]};
                byte[] expectedEa = BleSecureConnectionsCrypto.f6(
                        macKey, lastNa, lastNb, r, localIo, localTyped, peerTyped);
                assertTrue(MessageDigest.isEqual(ea, expectedEa));
                byte[] eb = BleSecureConnectionsCrypto.f6(
                        macKey, lastNb, lastNa, r, peerIo, peerTyped, localTyped);
                pushAclFromWatch(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.pairingDhKeyCheck(eb));
            } else if (op == BluetoothSmpCodec.PAIRING_FAILED) {
                if ((pdu[1] & 0xff) == BluetoothSmpCodec.FAILURE_CONFIRM_VALUE) {
                    initiatorSentConfirmValueFailed = true;
                }
            } else if (op == BluetoothSmpCodec.ENCRYPTION_INFORMATION) {
                initiatorSentEncInfo = true;
            } else if (op == BluetoothSmpCodec.SIGNING_INFORMATION) {
                initiatorSentSigningInfo = true;
            }
        }

        private void onCompanionStartEncryption(byte[] rand, int ediv, byte[] key) {
            encryptionStarted = true;
            byte[] expected = watchSupportsSc ? scLtk : legacyStk;
            assertArrayEquals(expected, key);
            if (!watchSupportsSc) {
                byte[] distributedLtk = new byte[16];
                Arrays.fill(distributedLtk, (byte) 0x77);
                pushAclFromWatch(BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec.encryptionInformation(distributedLtk));
                pushAclFromWatch(BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec.centralIdentification(0x1122, new byte[8]));
            }
            pushAclFromWatch(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.identityInformation(peerIrk));
            pushAclFromWatch(BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.identityAddressInformation(1, peerIdAddr));
            pushAclFromWatch(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.signingInformation(new byte[16]));
        }

        private void handleBtCl(byte[] payload) {
            HciCodec.BtClPdu pdu = HciCodec.parseBtCl(peerBtClVersion, 0L, payload);
            if (pdu.opcode == HciCodec.BT_CL_VERSION_INFO) {
                pushAclFromWatch(HciCodec.BT_CL_SIGNALING_CID, HciCodec.buildBtClVersionPdu());
            } else if (pdu.opcode == HciCodec.BT_CL_REMOTE_SERVICES) {
                List<Integer> accepted = watchSupportsTerminusLink
                        ? List.of(HciCodec.TERMINUS_LINK_SERVICE_ID)
                        : List.of(HciCodec.IDS_BT_LINK_SERVICE_ID);
                pushAclFromWatch(HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.buildCommonServicesPdu(peerBtClVersion, 0L, accepted));
            } else if (pdu.opcode == HciCodec.BT_CL_CREATE_CHANNEL) {
                HciCodec.CreateChannel req = HciCodec.parseCreateChannel(pdu);
                int rspCid = (req.serviceId == HciCodec.TERMINUS_LINK_SERVICE_ID) ? 0x0045 : 0x0046;
                pushAclFromWatch(HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.buildAcceptChannelPdu(peerBtClVersion, 0L, 0, req.serviceId, rspCid));
                if (req.serviceId == HciCodec.IDS_BT_LINK_SERVICE_ID) {
                    byte[] idsPacket = LegacyWatchPairingCodec.encodeIdsBtLinkPacket(
                            "IDS-CONTROL-HELLO".getBytes(StandardCharsets.US_ASCII));
                    pushAclFromWatch(req.requesterLocalCid,
                            L2capErtmCodec.encodeInformationFrame(req.requesterLocalCid, 0, 0, idsPacket, false));
                }
            }
        }

        private void handleTerminusLinkErtm(byte[] payload) {
            L2capErtmCodec.Frame f = L2capErtmCodec.decode(0x0045, payload, false);
            if (f.supervisory) {
                return;
            }
            NrLinkBluetoothPrelude.Parsed req = NrLinkBluetoothPrelude.parse(f.information);
            assertEquals(NrLinkBluetoothPrelude.PairingState.PAIR_WITH_OOB_KEY_OR_IDS_KEYS, req.state);
            byte[] watchUuid = new byte[16];
            Arrays.fill(watchUuid, (byte) 0x55);
            byte[] replyPrelude = NrLinkBluetoothPrelude.encode(
                    NrLinkBluetoothPrelude.PairingState.PAIR_WITH_IDS_KEYS,
                    watchUuid,
                    NrLinkBluetoothPrelude.FLAG_COMPANION_APL);
            pushAclFromWatch(HciCodec.TERMINUS_LINK_LOCAL_CID,
                    L2capErtmCodec.encodeInformationFrame(HciCodec.TERMINUS_LINK_LOCAL_CID, 0, 1, replyPrelude, false));
        }

        private static byte[] typed(byte[] a, int t) {
            byte[] out = new byte[7];
            System.arraycopy(a, 0, out, 0, 6);
            out[6] = (byte) t;
            return out;
        }
    }
}
