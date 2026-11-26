package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BleSecureConnectionsCrypto;
import dev.applewatchandroid.bridge.BluetoothBondSecretRecord;
import dev.applewatchandroid.bridge.BluetoothLocalIdentity;
import dev.applewatchandroid.bridge.BluetoothSmpCodec;
import dev.applewatchandroid.bridge.BridgeClock;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;
import org.junit.Test;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link SmpEngine}, {@link SmpAuthMode}, and {@link SmpBondResult}:
 * LE SC Passkey 20-round (omitting {@code CT2}), LE SC one-way and mutual OOB, LE SC Numeric
 * Comparison (approved and rejected), LE Legacy {@code SC=0} preserving non-zero {@code EDIV} &amp;
 * {@code Rand}, wrong passkey rejection, fail-closed {@link SmpEngine.BondCommitHook}, and zero
 * secret logging/formatting.
 */
public final class SmpEngineContractTest {
    private static final int HANDLE = 0x0071;
    private static final byte[] LOCAL_ADDR = new byte[]{0x21, 0x22, 0x23, 0x24, 0x25, (byte) 0xC1};
    private static final byte[] PEER_ADDR = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, (byte) 0xC6};
    private static final byte[] PEER_ID_ADDR = new byte[]{0x31, 0x32, 0x33, 0x34, 0x35, (byte) 0xC2};

    @Test
    public void leScPasskey20RoundWithZeroSecretLogs() throws Exception {
        SyntheticSmpWatch watch = SyntheticSmpWatch.forScPasskey(482913, true);
        try (L2capChannelMux mux = new L2capChannelMux(watch)) {
            SmpEngine engine = new SmpEngine(mux, new SecureRandom());
            AtomicInteger commits = new AtomicInteger();
            try (SmpBondResult result = engine.pair(
                    new SmpAuthMode.LeScPasskey(),
                    timeoutMs -> 482913,
                    null,
                    (bond, sc, ediv, rand) -> {
                        assertEquals(0, watch.localIdentitySentCount);
                        assertTrue(sc);
                        assertEquals(0, ediv);
                        assertArrayEquals(new byte[8], rand);
                        commits.incrementAndGet();
                    },
                    3_000L, 3_000L)) {
                assertTrue(result.secureConnections());
                assertTrue(result.bondPersisted());
                assertEquals(20, watch.passkeyRound);
                assertEquals("CT2 bit must be omitted", 0, watch.preq[3] & BluetoothSmpCodec.AUTH_CT2);
                assertEquals(16, result.encryptionKeySize());
                assertEquals(0, result.ediv());
                assertArrayEquals(new byte[8], result.rand());
                assertArrayEquals(watch.expectedScLtk, result.longTermKey());
                assertArrayEquals(watch.peerIrk, result.peerIrk());
                assertArrayEquals(PEER_ID_ADDR, result.peerIdentityAddress());
                assertEquals(BluetoothBondSecretRecord.SERIALIZED_LENGTH, result.bondSecretRecord().length);
                BluetoothBondSecretRecord.validateSerialized(result.bondSecretRecord());
                assertEquals(1, commits.get());
                assertTrue(watch.localIdentitySentCount > 0);

                String allLogs = String.join("\n", watch.logs);
                assertZeroSecretText(allLogs, watch, result, null, "482913");
                assertZeroSecretText(result.toString(), watch, result, null, "482913");
            }
        }
    }

    @Test
    public void leScOneWayOpticalOobAndMutualOob() throws Exception {
        byte[] peerOobKey = new byte[16];
        new SecureRandom().nextBytes(peerOobKey);

        // 1) One-way optical OOB: init.oob=1, resp.oob=0 -> ra=0[16], rb=peerOobKey
        SyntheticSmpWatch oneWayWatch = SyntheticSmpWatch.forScOob(peerOobKey, false);
        try (L2capChannelMux mux = new L2capChannelMux(oneWayWatch);
             SmpAuthMode.LeScOob mode = new SmpAuthMode.LeScOob(peerOobKey)) {
            SmpEngine engine = new SmpEngine(mux, new SecureRandom());
            try (SmpBondResult result = engine.pair(mode, null, null, (b, sc, e, r) -> { }, 3_000L, 3_000L)) {
                assertEquals(BluetoothSmpCodec.OOB_DATA_NOT_PRESENT, oneWayWatch.pres[2]);
                assertTrue(result.secureConnections());
                assertTrue(result.bondPersisted());
                assertArrayEquals(oneWayWatch.expectedScLtk, result.longTermKey());
                assertZeroSecretText(String.join("\n", oneWayWatch.logs), oneWayWatch, result, peerOobKey, null);
            }
            mode.close();
            assertTrue("LeScOob.close() must wipe key", TestHciTransport.isZeroed(mode.opticalOobKey16()));
        }

        // 2) Mutual OOB: init.oob=1, resp.oob=1 -> ra=rb=peerOobKey
        SyntheticSmpWatch mutualWatch = SyntheticSmpWatch.forScOob(peerOobKey, true);
        try (L2capChannelMux mux = new L2capChannelMux(mutualWatch);
             SmpAuthMode.LeScOob mode = new SmpAuthMode.LeScOob(peerOobKey)) {
            SmpEngine engine = new SmpEngine(mux, new SecureRandom());
            try (SmpBondResult result = engine.pair(mode, null, null, (b, sc, e, r) -> { }, 3_000L, 3_000L)) {
                assertEquals(BluetoothSmpCodec.OOB_DATA_PRESENT, mutualWatch.pres[2]);
                assertTrue(result.secureConnections());
                assertTrue(result.bondPersisted());
                assertArrayEquals(mutualWatch.expectedScLtk, result.longTermKey());
                assertZeroSecretText(String.join("\n", mutualWatch.logs), mutualWatch, result, peerOobKey, null);
            }
        }
    }

    @Test
    public void leScNumericComparisonConfirmedAndRejected() throws Exception {
        // Confirmed
        SyntheticSmpWatch watch = SyntheticSmpWatch.forNumericComparison();
        AtomicInteger uiDigits = new AtomicInteger(-1);
        try (L2capChannelMux mux = new L2capChannelMux(watch)) {
            SmpEngine engine = new SmpEngine(mux, new SecureRandom());
            try (SmpBondResult result = engine.pair(
                    new SmpAuthMode.LeScNumericComparison(),
                    null,
                    (code, timeoutMs) -> {
                        uiDigits.set(code);
                        return true;
                    },
                    (b, sc, e, r) -> assertTrue(sc),
                    3_000L, 3_000L)) {
                assertEquals(watch.expectedNumericCode, uiDigits.get());
                assertTrue(result.secureConnections());
                assertTrue(result.bondPersisted());
                assertArrayEquals(watch.expectedScLtk, result.longTermKey());
                String paddedCode = String.format(Locale.US, "%06d", watch.expectedNumericCode);
                assertZeroSecretText(String.join("\n", watch.logs), watch, result, null, paddedCode);
            }
        }

        // Rejected -> FAILURE_NUMERIC_COMPARISON_FAILED (0x0C), no bond
        SyntheticSmpWatch rejectWatch = SyntheticSmpWatch.forNumericComparison();
        AtomicInteger commits = new AtomicInteger();
        try (L2capChannelMux mux = new L2capChannelMux(rejectWatch)) {
            SmpEngine engine = new SmpEngine(mux, new SecureRandom());
            HostException rejectedErr = assertThrows(HostException.class, () -> engine.pair(
                    new SmpAuthMode.LeScNumericComparison(),
                    null,
                    (code, timeoutMs) -> false,
                    (b, sc, e, r) -> commits.incrementAndGet(),
                    3_000L, 3_000L));
            assertTrue(rejectedErr.getMessage().contains("rejected"));
            assertEquals(BluetoothSmpCodec.FAILURE_NUMERIC_COMPARISON_FAILED, rejectWatch.pairingFailedReason);
            assertEquals(0, commits.get());
            assertFalse(rejectWatch.encryptionStarted);
        }
    }

    @Test
    public void leLegacySc0PasskeyPreservesNonZeroEdivAndRandAndWipesOnClose() throws Exception {
        byte[] expectedRand = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        int expectedEdiv = 0x3456;
        SyntheticSmpWatch watch = SyntheticSmpWatch.forLegacySc0(120394, expectedEdiv, expectedRand);
        AtomicInteger hookedEdiv = new AtomicInteger(-1);
        byte[][] hookedRand = new byte[1][];

        SmpBondResult result;
        try (L2capChannelMux mux = new L2capChannelMux(watch);
             SmpAuthMode.LeLegacy mode = new SmpAuthMode.LeLegacy(null)) {
            SmpEngine engine = new SmpEngine(mux, new SecureRandom());
            result = engine.pair(
                    mode,
                    timeoutMs -> 120394,
                    null,
                    (bond, sc, ediv, rand) -> {
                        assertFalse(sc);
                        BluetoothBondSecretRecord.validateSerialized(bond);
                        hookedEdiv.set(ediv);
                        hookedRand[0] = rand.clone();
                    },
                    3_000L, 3_000L);
        }

        try {
            assertEquals(0, watch.preq[3] & BluetoothSmpCodec.AUTH_SECURE_CONNECTIONS);
            assertFalse(result.secureConnections());
            assertTrue(result.bondPersisted());
            assertEquals(expectedEdiv, result.ediv());
            assertArrayEquals(expectedRand, result.rand());
            assertEquals(expectedEdiv, hookedEdiv.get());
            assertArrayEquals(expectedRand, hookedRand[0]);
            assertArrayEquals(watch.distributedLtk, result.longTermKey());
            assertTrue(watch.localEncryptionInfoSent);
            assertTrue(watch.localCentralIdSent);
            assertEquals(2, watch.localIdentitySentCount);
            assertTrue(watch.localSigningInfoSent);
            assertZeroSecretText(String.join("\n", watch.logs), watch, result, null, "120394");
            assertZeroSecretText(result.toString(), watch, result, null, "120394");
        } finally {
            result.close();
        }
        assertTrue(result.isClosed());
        assertThrows(IllegalStateException.class, result::longTermKey);
        assertThrows(IllegalStateException.class, result::rand);
        assertThrows(IllegalStateException.class, result::peerIrk);
        assertThrows(IllegalStateException.class, result::peerIdentityAddress);
        assertThrows(IllegalStateException.class, result::bondSecretRecord);
    }

    @Test
    public void wrongPasskeySendsConfirmFailedAndFailingBondCommitAbortsBeforeLocalKeys() throws Exception {
        // 1) Wrong passkey -> FAILURE_CONFIRM_VALUE (0x04), bondCommitHook NOT called
        SyntheticSmpWatch wrongPinWatch = SyntheticSmpWatch.forScPasskey(999999, false);
        AtomicInteger commits = new AtomicInteger();
        try (L2capChannelMux mux = new L2capChannelMux(wrongPinWatch)) {
            SmpEngine engine = new SmpEngine(mux, new SecureRandom());
            assertThrows(HostException.class, () -> engine.pair(
                    new SmpAuthMode.LeScPasskey(),
                    timeoutMs -> 111111,
                    null,
                    (b, sc, e, r) -> commits.incrementAndGet(),
                    3_000L, 3_000L));
            assertEquals(BluetoothSmpCodec.FAILURE_CONFIRM_VALUE, wrongPinWatch.pairingFailedReason);
            assertEquals(0, commits.get());
            assertFalse(wrongPinWatch.encryptionStarted);
            assertFalse(String.join("\n", wrongPinWatch.logs).contains("111111"));
        }

        // 2) Failing bondCommitHook -> PAIRING_FAILED(0x08), local keys NOT sent (tested on Legacy SC=0 so all 5 key PDUs would otherwise be sent)
        SyntheticSmpWatch commitFailWatch = SyntheticSmpWatch.forLegacySc0(
                654321, 0x4242, new byte[]{9, 8, 7, 6, 5, 4, 3, 2});
        try (L2capChannelMux mux = new L2capChannelMux(commitFailWatch);
             SmpAuthMode.LeLegacy mode = new SmpAuthMode.LeLegacy(null)) {
            SmpEngine engine = new SmpEngine(mux, new SecureRandom());
            HostException err = assertThrows(HostException.class, () -> engine.pair(
                    mode,
                    timeoutMs -> 654321,
                    null,
                    (b, sc, e, r) -> {
                        throw new HostException("simulated keystore write failure");
                    },
                    3_000L, 3_000L));
            assertTrue(err.getMessage().contains("keystore"));
            assertEquals(BluetoothSmpCodec.FAILURE_UNSPECIFIED_REASON, commitFailWatch.pairingFailedReason);
            assertEquals(0, commitFailWatch.localIdentitySentCount);
            assertFalse(commitFailWatch.localEncryptionInfoSent);
            assertFalse(commitFailWatch.localCentralIdSent);
            assertFalse(commitFailWatch.localSigningInfoSent);
        }
    }

    private static void assertZeroSecretText(
            String text,
            SyntheticSmpWatch watch,
            SmpBondResult result,
            byte[] oobKey,
            String pinText) {
        String lower = text.toLowerCase(Locale.US);
        if (pinText != null) {
            assertFalse("PIN/code must never appear in text", lower.contains(pinText.toLowerCase(Locale.US)));
        }
        for (byte[] secret : new byte[][]{
                oobKey,
                watch.expectedScLtk,
                watch.distributedLtk,
                watch.peerIrk,
                watch.localId.irk(),
                LOCAL_ADDR,
                PEER_ADDR,
                PEER_ID_ADDR,
                result.longTermKey(),
                result.rand()
        }) {
            if (secret == null || TestHciTransport.isZeroed(secret)) {
                continue;
            }
            assertFalse("Compact secret hex must never appear", lower.contains(hexCompact(secret)));
            assertFalse("Spaced secret hex must never appear", lower.contains(hexSpaced(secret)));
        }
    }

    private static String hexCompact(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(String.format(Locale.US, "%02x", v & 0xFF));
        }
        return sb.toString();
    }

    private static String hexSpaced(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static final class SyntheticSmpWatch implements HciTransport {
        enum Kind { SC_PASSKEY, SC_OOB, SC_NUMERIC_COMPARISON, LEGACY_SC0 }

        private final Kind kind;
        private final int passkey;
        private final byte[] peerOobKey;
        private final boolean mutualOob;
        private final int legacyEdiv;
        private final byte[] legacyRand;
        private final boolean sendSecurityRequestFirst;

        private final SecureRandom rng = new SecureRandom();
        private final BluetoothLocalIdentity localId =
                BluetoothLocalIdentity.create(LOCAL_ADDR, randomBytes(16));
        private final byte[] peerIrk = randomBytes(16);
        private final byte[] distributedLtk = randomBytes(16);
        private final BlockingQueue<Packet> inbound = new LinkedBlockingQueue<>();
        private final List<String> logs = new ArrayList<>();

        private BleSecureConnectionsCrypto.LocalOobMaterial watchEc;
        private byte[] preq;
        private byte[] pres;
        private byte[] pka;
        private byte[] dhKey;
        private byte[] lastNa;
        private byte[] lastNb;
        private byte[] expectedScLtk;
        private byte[] expectedLegacyStk;
        private int passkeyRound;
        int expectedNumericCode = -1;

        boolean encryptionStarted;
        int pairingFailedReason = -1;
        int localIdentitySentCount;
        boolean localEncryptionInfoSent;
        boolean localCentralIdSent;
        boolean localSigningInfoSent;

        static SyntheticSmpWatch forScPasskey(int passkey, boolean sendSecReq) {
            return new SyntheticSmpWatch(Kind.SC_PASSKEY, passkey, null, false, 0, null, sendSecReq);
        }

        static SyntheticSmpWatch forScOob(byte[] peerOobKey, boolean mutual) {
            return new SyntheticSmpWatch(Kind.SC_OOB, 0, peerOobKey, mutual, 0, null, false);
        }

        static SyntheticSmpWatch forNumericComparison() {
            return new SyntheticSmpWatch(Kind.SC_NUMERIC_COMPARISON, 0, null, false, 0, null, false);
        }

        static SyntheticSmpWatch forLegacySc0(int passkey, int ediv, byte[] rand) {
            return new SyntheticSmpWatch(Kind.LEGACY_SC0, passkey, null, false, ediv, rand, false);
        }

        private SyntheticSmpWatch(
                Kind kind,
                int passkey,
                byte[] peerOobKey,
                boolean mutualOob,
                int legacyEdiv,
                byte[] legacyRand,
                boolean sendSecurityRequestFirst) {
            this.kind = kind;
            this.passkey = passkey;
            this.peerOobKey = peerOobKey == null ? null : peerOobKey.clone();
            this.mutualOob = mutualOob;
            this.legacyEdiv = legacyEdiv;
            this.legacyRand = legacyRand == null ? null : legacyRand.clone();
            this.sendSecurityRequestFirst = sendSecurityRequestFirst;
        }

        private byte[] randomBytes(int len) {
            byte[] b = new byte[len];
            rng.nextBytes(b);
            return b;
        }

        private void pushSmp(byte[] pdu) {
            for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(
                    HANDLE, BridgeCoreCodecs.SMP_FIXED_CID, pdu, 251)) {
                inbound.offer(new Packet(PacketKind.ACL, frag));
            }
        }

        @Override public int connectionHandle() { return HANDLE; }
        @Override public int peerAddressType() { return 1; }
        @Override public byte[] peerAddress() { return PEER_ADDR.clone(); }
        @Override public BluetoothLocalIdentity localIdentity() { return localId; }
        @Override public void checkStop() { }

        @Override
        public void sendL2cap(int cid, byte[] payload, String label) {
            if (cid != BridgeCoreCodecs.SMP_FIXED_CID) {
                return;
            }
            int op = payload[0] & 0xff;
            if (op == BluetoothSmpCodec.PAIRING_REQUEST) {
                preq = payload.clone();
                if (sendSecurityRequestFirst) {
                    pushSmp(new byte[]{BluetoothSmpCodec.SECURITY_REQUEST, BluetoothSmpCodec.AUTH_SC_MITM_BOND});
                }
                int io = switch (kind) {
                    case SC_NUMERIC_COMPARISON -> BluetoothSmpCodec.IO_CAPABILITY_DISPLAY_YES_NO;
                    case SC_OOB -> BluetoothSmpCodec.IO_CAPABILITY_NO_INPUT_NO_OUTPUT;
                    default -> BluetoothSmpCodec.IO_CAPABILITY_DISPLAY_ONLY;
                };
                int oob = (kind == Kind.SC_OOB && mutualOob)
                        ? BluetoothSmpCodec.OOB_DATA_PRESENT
                        : BluetoothSmpCodec.OOB_DATA_NOT_PRESENT;
                int auth = (kind == Kind.LEGACY_SC0)
                        ? BluetoothSmpCodec.AUTH_LEGACY_MITM_BOND
                        : BluetoothSmpCodec.AUTH_SC_MITM_BOND;
                pres = new byte[]{
                        BluetoothSmpCodec.PAIRING_RESPONSE,
                        (byte) io,
                        (byte) oob,
                        (byte) auth,
                        16,
                        BluetoothSmpCodec.STANDARD_LE_KEY_DISTRIBUTION,
                        BluetoothSmpCodec.STANDARD_LE_KEY_DISTRIBUTION
                };
                pushSmp(pres.clone());
            } else if (op == BluetoothSmpCodec.PAIRING_PUBLIC_KEY) {
                pka = BluetoothSmpCodec.parsePairingPublicKey(payload);
                watchEc = BleSecureConnectionsCrypto.generateLocalOob(rng);
                dhKey = watchEc.calculateDhKey(pka);
                pushSmp(BluetoothSmpCodec.pairingPublicKey(watchEc.publicKeyForSmp()));
                passkeyRound = 0;
                if (kind == Kind.SC_NUMERIC_COMPARISON) {
                    lastNb = randomBytes(16);
                    byte[] cb = BleSecureConnectionsCrypto.f4(
                            watchEc.publicXLittleEndian(), Arrays.copyOfRange(pka, 0, 32), lastNb, 0);
                    pushSmp(BluetoothSmpCodec.pairingConfirm(cb));
                }
            } else if (op == BluetoothSmpCodec.PAIRING_CONFIRM) {
                if (kind == Kind.SC_PASSKEY) {
                    lastNb = randomBytes(16);
                    int z = BleSecureConnectionsCrypto.passkeyRoundZ(passkey, passkeyRound);
                    byte[] cb = BleSecureConnectionsCrypto.f4(
                            watchEc.publicXLittleEndian(), Arrays.copyOfRange(pka, 0, 32), lastNb, z);
                    pushSmp(BluetoothSmpCodec.pairingConfirm(cb));
                } else if (kind == Kind.LEGACY_SC0) {
                    lastNb = randomBytes(16);
                    byte[] tk = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
                    byte[] sConfirm = BleSecureConnectionsCrypto.legacyC1(
                            tk, lastNb, preq, pres, 1, LOCAL_ADDR, 1, PEER_ADDR);
                    pushSmp(BluetoothSmpCodec.pairingConfirm(sConfirm));
                }
            } else if (op == BluetoothSmpCodec.PAIRING_RANDOM) {
                lastNa = BluetoothSmpCodec.parsePairingRandom(payload);
                if (kind == Kind.SC_OOB) {
                    lastNb = randomBytes(16);
                }
                pushSmp(BluetoothSmpCodec.pairingRandom(lastNb));
                if (kind == Kind.SC_PASSKEY) {
                    passkeyRound++;
                } else if (kind == Kind.SC_NUMERIC_COMPARISON) {
                    expectedNumericCode = BleSecureConnectionsCrypto.g2NumericComparison(
                            Arrays.copyOfRange(pka, 0, 32), watchEc.publicXLittleEndian(), lastNa, lastNb);
                } else if (kind == Kind.LEGACY_SC0) {
                    byte[] tk = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
                    expectedLegacyStk = BleSecureConnectionsCrypto.legacyS1(tk, lastNb, lastNa);
                }
            } else if (op == BluetoothSmpCodec.PAIRING_DHKEY_CHECK) {
                byte[] ea = BluetoothSmpCodec.parsePairingDhKeyCheck(payload);
                byte[] localTyped = typed(LOCAL_ADDR, 1);
                byte[] peerTyped = typed(PEER_ADDR, 1);
                BleSecureConnectionsCrypto.F5Result f5 =
                        BleSecureConnectionsCrypto.f5(dhKey, lastNa, lastNb, localTyped, peerTyped);
                byte[] macKey = f5.macKey();
                expectedScLtk = f5.longTermKey();
                f5.destroy();

                byte[] ra;
                byte[] rb;
                if (kind == Kind.SC_OOB) {
                    rb = peerOobKey.clone();
                    ra = mutualOob ? peerOobKey.clone() : new byte[16];
                } else if (kind == Kind.SC_NUMERIC_COMPARISON) {
                    ra = new byte[16];
                    rb = new byte[16];
                } else {
                    ra = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
                    rb = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
                }
                byte[] localIo = new byte[]{preq[1], preq[2], preq[3]};
                byte[] peerIo = new byte[]{pres[1], pres[2], pres[3]};
                byte[] expectedEa = BleSecureConnectionsCrypto.f6(
                        macKey, lastNa, lastNb, rb, localIo, localTyped, peerTyped);
                assertTrue(MessageDigest.isEqual(ea, expectedEa));
                byte[] eb = BleSecureConnectionsCrypto.f6(
                        macKey, lastNb, lastNa, ra, peerIo, peerTyped, localTyped);
                pushSmp(BluetoothSmpCodec.pairingDhKeyCheck(eb));
            } else if (op == BluetoothSmpCodec.PAIRING_FAILED) {
                pairingFailedReason = BluetoothSmpCodec.parsePairingFailure(payload);
            } else if (op == BluetoothSmpCodec.ENCRYPTION_INFORMATION) {
                localEncryptionInfoSent = true;
            } else if (op == BluetoothSmpCodec.CENTRAL_IDENTIFICATION) {
                localCentralIdSent = true;
            } else if (op == BluetoothSmpCodec.IDENTITY_INFORMATION
                    || op == BluetoothSmpCodec.IDENTITY_ADDRESS_INFORMATION) {
                localIdentitySentCount++;
            } else if (op == BluetoothSmpCodec.SIGNING_INFORMATION) {
                localSigningInfoSent = true;
            }
        }

        @Override
        public void startLeEncryption(byte[] rand, int ediv, byte[] longTermKey) {
            encryptionStarted = true;
            if (kind == Kind.LEGACY_SC0) {
                assertArrayEquals(expectedLegacyStk, longTermKey);
                pushSmp(BluetoothSmpCodec.encryptionInformation(distributedLtk));
                pushSmp(BluetoothSmpCodec.centralIdentification(legacyEdiv, legacyRand));
            } else {
                assertArrayEquals(expectedScLtk, longTermKey);
            }
            pushSmp(BluetoothSmpCodec.identityInformation(peerIrk));
            pushSmp(BluetoothSmpCodec.identityAddressInformation(1, PEER_ID_ADDR));
            pushSmp(BluetoothSmpCodec.signingInformation(new byte[16]));
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

        @Override public AutoCloseable openAttClientScope() { return () -> { }; }
        @Override public byte[] handlePeerAttRequest(byte[] request) { return null; }
        @Override public void log(String message) { logs.add(message); }

        private static byte[] typed(byte[] addr, int type) {
            byte[] out = new byte[7];
            System.arraycopy(addr, 0, out, 0, 6);
            out[6] = (byte) type;
            return out;
        }
    }
}
