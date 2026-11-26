package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.spi.IdsLinkKind;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.StrategyPolicy;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Verifies backward-compatible AWP2 TLV 45..48 persistence for Strategies 1–3,
 * {@code SC=0} {@code EDIV}/{@code Rand} retention, legacy lenient IDS Hello decoding,
 * and {@link HalHostArguments} {@code --strategies=} parsing.
 */
public final class PairingSessionRecordLegacyTest {

    @Test
    public void legacySc0RecordRoundTripsThroughAwp2WithEdivAndRandPreserved() {
        byte[] genUuid = randomBytes16(0x41);
        byte[] ltk = randomBytes16(0x52);
        byte[] localIrk = randomBytes16(0x63);
        byte[] peerIrk = randomBytes16(0x74);
        byte[] rand = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        byte[] bond = BluetoothBondSecretRecord.encode(
                1, new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, (byte) 0xC1},
                1, new byte[]{0x11, 0x12, 0x13, 0x14, 0x15, (byte) 0xC2},
                1, new byte[]{0x21, 0x22, 0x23, 0x24, 0x25, (byte) 0xC3},
                ltk, localIrk, peerIrk, 16);
        byte[] ext = new byte[]{0x01, 0x02, 0x03, 0x04};
        String cbUuid = UUID.randomUUID().toString();
        String nrUuid = UUID.randomUUID().toString();
        String idsUuid = UUID.randomUUID().toString();

        PairingSessionRecord created = PairingSessionRecord.createLegacyBonded(
                genUuid,
                PairingStrategyId.SIMULTANEOUS_S1,
                IdsLinkKind.IDS_BT_LINK,
                bond,
                false,
                0xBEEF,
                rand,
                "Apple Watch (Legacy S1)",
                "3.2.3",
                "Watch1,1",
                cbUuid,
                nrUuid,
                idsUuid,
                ext);
        byte[] serialized = null;
        PairingSessionRecord restored = null;
        PairingSessionRecord progressed = null;
        try {
            assertEquals(PairingStrategyId.SIMULTANEOUS_S1, created.pairingStrategy());
            assertEquals(IdsLinkKind.IDS_BT_LINK, created.idsLinkKind());
            assertFalse(created.smpSecureConnections());
            assertEquals(0xBEEF, created.smpEdiv());
            assertArrayEquals(rand, created.smpRand());
            assertArrayEquals(ext, created.strategyExtension());
            assertTrue(created.isLegacyStrategy());

            serialized = created.serialize();
            restored = PairingSessionRecord.parse(serialized);
            assertEquals(PairingStrategyId.SIMULTANEOUS_S1, restored.pairingStrategy());
            assertEquals(IdsLinkKind.IDS_BT_LINK, restored.idsLinkKind());
            assertFalse(restored.smpSecureConnections());
            assertEquals(0xBEEF, restored.smpEdiv());
            assertArrayEquals(rand, restored.smpRand());
            assertArrayEquals(ext, restored.strategyExtension());
            assertArrayEquals(bond, restored.bluetoothBond());
            assertEquals("Watch1,1", restored.peerProductType());
            assertEquals("3.2.3", restored.peerBuildVersion());

            progressed = restored.withLegacyProgress(
                    PairingSessionRecord.DurableState.IDS_CONTROL_READY,
                    IdsLinkKind.IDS_BT_LINK,
                    "Watch1,2",
                    "14V753",
                    null);
            byte[] progressedBytes = progressed.serialize();
            PairingSessionRecord reparsed = PairingSessionRecord.parse(progressedBytes);
            try {
                assertEquals(PairingSessionRecord.DurableState.IDS_CONTROL_READY, reparsed.state());
                assertEquals(2L, reparsed.transitionCounter());
                assertEquals(PairingStrategyId.SIMULTANEOUS_S1, reparsed.pairingStrategy());
                assertEquals(0xBEEF, reparsed.smpEdiv());
                assertArrayEquals(rand, reparsed.smpRand());
                assertEquals("Watch1,2", reparsed.peerProductType());
            } finally {
                reparsed.destroy();
                Arrays.fill(progressedBytes, (byte) 0);
            }
        } finally {
            created.destroy();
            if (restored != null) restored.destroy();
            if (progressed != null) progressed.destroy();
            if (serialized != null) Arrays.fill(serialized, (byte) 0);
            Arrays.fill(bond, (byte) 0);
        }
    }

    @Test
    public void rejectsOversizedLegacyStrategyExtension() {
        byte[] bond = BluetoothBondSecretRecord.encode(
                1, new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, (byte) 0xC1},
                1, new byte[]{0x11, 0x12, 0x13, 0x14, 0x15, (byte) 0xC2},
                1, new byte[]{0x21, 0x22, 0x23, 0x24, 0x25, (byte) 0xC3},
                randomBytes16(1), randomBytes16(2), randomBytes16(3), 16);
        assertThrows(IllegalArgumentException.class, () -> PairingSessionRecord.createLegacyBonded(
                randomBytes16(9),
                PairingStrategyId.SEQUENTIAL_S2,
                IdsLinkKind.IDS_BT_LINK,
                bond,
                true,
                0,
                new byte[8],
                "Apple Watch (Legacy S2)",
                "5.3.9",
                null,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new byte[PairingSessionRecord.MAX_STRATEGY_EXTENSION_LENGTH + 1]));
    }

    @Test
    public void legacyHelloLenientParserAcceptsWatchOs1To5WithoutOptionalTlvs3To5() {
        // Build an IDS Control Hello with ONLY TLV 0 (min), TLV 1 (max), TLV 2 (instanceId) — omitting TLVs 3, 4, 5.
        UUID instance = UUID.randomUUID();
        ByteBuffer buf = ByteBuffer.allocate(256);
        buf.put((byte) IdsControlChannelCodec.TYPE_HELLO);
        putU16String(buf, "1");
        putU16String(buf, "Watch OS");
        putU16String(buf, "3.2.3");
        putU16String(buf, "14V753");
        putU16String(buf, "Watch1,1");
        buf.putInt(2); // pairingProtocolVersion
        // TLV 0: minCompatibilityVersion
        buf.put((byte) 0).putShort((short) 4).putInt(1);
        // TLV 1: maxCompatibilityVersion
        buf.put((byte) 1).putShort((short) 4).putInt(2);
        // TLV 2: instanceId
        buf.put((byte) 2).putShort((short) 16)
                .putLong(instance.getMostSignificantBits())
                .putLong(instance.getLeastSignificantBits());
        byte[] payload = Arrays.copyOf(buf.array(), buf.position());

        // Strict Strategy 4 parser still rejects missing TLVs 3, 4, 5.
        assertThrows(IllegalArgumentException.class, () -> IdsControlChannelCodec.decodePayload(payload));

        // Legacy lenient parser decodes cleanly for watchOS 1-5 over IDSBTLink.
        IdsControlChannelCodec.HelloMessage hello =
                IdsControlChannelWireCodec.decodeLegacyHelloPayload(payload);
        assertEquals("Watch OS", hello.productName);
        assertEquals("3.2.3", hello.productVersion);
        assertEquals("14V753", hello.productBuildVersion);
        assertEquals("Watch1,1", hello.model);
        assertEquals(2L, hello.pairingProtocolVersion);
        assertEquals(instance, hello.instanceId);
        assertEquals(instance, hello.deviceUniqueId);
        assertEquals(0L, hello.capabilityFlags);
        assertEquals(0, hello.serviceMinCompatibilityVersion);
    }

    @Test
    public void halHostArgumentsParsesStrategiesCsvAndLegacyAlias() {
        HalHostArguments def = HalHostArguments.parse(new String[]{"--acknowledge-target", HalHostArguments.TARGET});
        assertEquals(StrategyPolicy.PRODUCTION_DEFAULT, def.strategyPolicy);
        assertFalse(def.experimentalLegacyPairing);

        HalHostArguments alias = HalHostArguments.parse(new String[]{
                "--acknowledge-target", HalHostArguments.TARGET, "--experimental-legacy-pairing"});
        assertEquals(StrategyPolicy.ALL_WITH_EXPERIMENTAL, alias.strategyPolicy);
        assertTrue(alias.experimentalLegacyPairing);

        HalHostArguments explicit = HalHostArguments.parse(new String[]{
                "--acknowledge-target", HalHostArguments.TARGET, "--strategies=4,2,1"});
        assertEquals(StrategyPolicy.parseCsv("4,2,1"), explicit.strategyPolicy);
        assertTrue(explicit.experimentalLegacyPairing);

        HalHostArguments s4Only = HalHostArguments.parse(new String[]{
                "--acknowledge-target", HalHostArguments.TARGET, "--strategies=4"});
        assertEquals(StrategyPolicy.PRODUCTION_DEFAULT, s4Only.strategyPolicy);
        assertFalse(s4Only.experimentalLegacyPairing);

        assertThrows(IllegalArgumentException.class, () -> HalHostArguments.parse(new String[]{
                "--acknowledge-target", HalHostArguments.TARGET, "--strategies=9"}));
        assertThrows(IllegalArgumentException.class, () -> HalHostArguments.parse(new String[]{
                "--acknowledge-target", HalHostArguments.TARGET, "--strategies=4,4"}));
    }

    private static void putU16String(ByteBuffer buf, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.putShort((short) bytes.length);
        buf.put(bytes);
    }

    private static byte[] randomBytes16(int seed) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (seed + i);
        }
        return out;
    }
}
