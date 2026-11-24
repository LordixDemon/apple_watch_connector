package dev.applewatchandroid.bridge;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public final class LegacyWatchPairingCodecTest {

    @Test
    public void parsesLegacy128BitServiceUuidAndLegacyNamesWithoutMatchingStrategy4() {
        byte[] advUuidAndManualName = new byte[]{
                2, 0x01, 0x06,
                17, 0x07,
                (byte) 0x96, (byte) 0xC1, (byte) 0x9F, (byte) 0x55,
                0x31, (byte) 0xC9, 0x21, (byte) 0xB8,
                (byte) 0xC3, 0x4C, 0x5C, (byte) 0xB2,
                0x0F, 0x73, (byte) 0xA4, (byte) 0x9A,
                18, 0x09,
                'A', 'p', 'p', 'l', 'e', ' ', 'W', 'a', 't', 'c', 'h', ' ', '1', '2', '3', '4', '5'
        };
        LegacyWatchPairingCodec.LegacyAdvertisement manual =
                LegacyWatchPairingCodec.parseAdvertisement(advUuidAndManualName, null);
        assertNotNull(manual);
        assertTrue(manual.hasExtensiblePairServiceUuid);
        assertTrue(manual.manualNameMatch);
        assertEquals(12345, manual.advertisingIdentifier);
        assertEquals(LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL_DIRECT_IPSEC, manual.effectiveStrategy());

        byte[] advStrategy2Name = new byte[]{
                9, 0x09, '5', '4', '3', '2', '1', 'C', 'B', 'D'
        };
        LegacyWatchPairingCodec.LegacyAdvertisement s2 =
                LegacyWatchPairingCodec.parseAdvertisement(advStrategy2Name, null);
        assertNotNull(s2);
        assertEquals(LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL, s2.strategy);
        assertEquals(54321, s2.advertisingIdentifier);
        assertEquals(1, s2.enclosureMaterial);
        assertEquals(3, s2.deviceSize);

        // Modern Strategy 4 ('E') must never be claimed by the legacy parser.
        byte[] advModernName = new byte[]{
                9, 0x09, '1', '2', '3', '4', '5', 'E', 'B', 'D'
        };
        assertNull(LegacyWatchPairingCodec.parseAdvertisement(advModernName, null));
    }

    @Test
    public void parsesLegacyFe25Strategy1To3WhileLeavingIsExpectedTargetFalse() {
        byte[] id3 = WatchSetupMetadataCodec.encodeIdentifier(
                LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL_DIRECT_IPSEC,
                42190,
                6,
                2);
        byte[] ad = new byte[]{
                9, 0x16, 0x25, (byte) 0xFE, 0x06,
                0x20, id3[0], id3[1], id3[2], id3[3]
        };
        byte[] mac = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
        HciCodec.WatchSetupPayload setup = HciCodec.parseWatchSetupPayload(ad);
        HciCodec.AdvertisingReport report = new HciCodec.AdvertisingReport(0, 1, mac, -55, ad, setup);
        assertFalse("Modern selector predicate must remain strictly Strategy 4", report.isExpectedTarget());
        assertNotNull(report.legacySetup);
        assertEquals(LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL_DIRECT_IPSEC, report.legacySetup.strategy);
        assertEquals(42190, report.legacySetup.advertisingIdentifier);
    }

    @Test
    public void legacyTargetSelectorMergesAdvIndAndScanRspAndReleasesUuidOnlyAfterHold() {
        LegacyWatchPairingCodec.LegacyTargetSelector selector =
                new LegacyWatchPairingCodec.LegacyTargetSelector();
        byte[] mac1 = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x40};
        byte[] advUuidOnly = new byte[]{
                17, 0x06,
                (byte) 0x96, (byte) 0xC1, (byte) 0x9F, (byte) 0x55,
                0x31, (byte) 0xC9, 0x21, (byte) 0xB8,
                (byte) 0xC3, 0x4C, 0x5C, (byte) 0xB2,
                0x0F, 0x73, (byte) 0xA4, (byte) 0x9A
        };
        byte[] scanRspS3 = new byte[]{9, 0x09, '7', '6', '5', '4', '3', 'D', 'A', 'B'};

        assertNull(selector.accept(new HciCodec.AdvertisingReport(
                0, 1, mac1, -48, advUuidOnly, null), 100L));
        HciCodec.AdvertisingReport merged = selector.accept(new HciCodec.AdvertisingReport(
                4, 1, mac1, -47, scanRspS3, null), 120L);
        assertNotNull(merged);
        assertEquals(0, merged.eventType);
        assertTrue(merged.legacySetup.hasExtensiblePairServiceUuid);
        assertEquals(LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL_DIRECT_IPSEC, merged.legacySetup.strategy);

        byte[] mac2 = new byte[]{0x10, 0x20, 0x30, 0x40, 0x50, 0x41};
        assertNull(selector.accept(new HciCodec.AdvertisingReport(
                0, 1, mac2, -52, advUuidOnly, null), 500L));
        assertNull(selector.pollReady(800L));
        HciCodec.AdvertisingReport timedOutUuidOnly = selector.pollReady(950L);
        assertNotNull(timedOutUuidOnly);
        assertTrue(timedOutUuidOnly.legacySetup.hasExtensiblePairServiceUuid);
    }

    @Test
    public void nanoControlPayloadAndGattAttClientRoundTrip() {
        byte[] nano = LegacyWatchPairingCodec.encodeControlPayload(
                LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL_DIRECT_IPSEC,
                LegacyWatchPairingCodec.STYLE_PASSCODE);
        assertArrayEquals(new byte[]{'N', 'A', 'N', 'O', 0x03, 0x02}, nano);
        LegacyWatchPairingCodec.ControlPayload decoded = LegacyWatchPairingCodec.decodeControlPayload(nano);
        assertEquals(3, decoded.strategy);
        assertEquals(2, decoded.style);

        assertThrows(IllegalArgumentException.class, () -> LegacyWatchPairingCodec.encodeControlPayload(4, 2));
        assertThrows(IllegalArgumentException.class, () -> LegacyWatchPairingCodec.encodeControlPayload(3, 9));

        byte[] mtuReq = LegacyWatchPairingCodec.GattAttClient.buildExchangeMtuRequest(185);
        assertArrayEquals(new byte[]{0x02, (byte) 185, 0x00}, mtuReq);
        assertEquals(185, LegacyWatchPairingCodec.GattAttClient.parseExchangeMtuResponse(
                new byte[]{0x03, (byte) 185, 0x00}));

        byte[] findReq = LegacyWatchPairingCodec.GattAttClient.buildFindExtensiblePairServiceRequest(1);
        assertEquals(23, findReq.length);
        assertEquals(0x06, findReq[0] & 0xff);
        List<LegacyWatchPairingCodec.HandleRange> ranges =
                LegacyWatchPairingCodec.GattAttClient.parseFindByTypeValueResponse(
                        new byte[]{0x07, 0x20, 0x00, 0x24, 0x00});
        assertEquals(0x0020, ranges.get(0).startHandle);
        assertEquals(0x0024, ranges.get(0).endHandle);

        byte[] charRsp = new byte[2 + 21];
        charRsp[0] = 0x09;
        charRsp[1] = 21;
        charRsp[2] = 0x21;
        charRsp[3] = 0x00;
        charRsp[4] = 0x1A;
        charRsp[5] = 0x22;
        charRsp[6] = 0x00;
        System.arraycopy(LegacyWatchPairingCodec.EXTENSIBLE_PAIR_CONTROL_CHAR_UUID_LE, 0, charRsp, 7, 16);
        List<LegacyWatchPairingCodec.CharacteristicDeclaration> chars =
                LegacyWatchPairingCodec.GattAttClient.parseDiscoverCharacteristicsResponse(charRsp);
        assertEquals(1, chars.size());
        assertEquals(0x0022, chars.get(0).valueHandle);
        assertTrue(chars.get(0).isExtensiblePairControlCharacteristic());

        byte[] writeReq = LegacyWatchPairingCodec.GattAttClient.buildWriteRequest(0x0022, nano);
        assertArrayEquals(new byte[]{0x12, 0x22, 0x00, 'N', 'A', 'N', 'O', 0x03, 0x02}, writeReq);
        assertTrue(LegacyWatchPairingCodec.GattAttClient.isWriteResponse(new byte[]{0x13}));
    }

    @Test
    public void legacyOpticalCodeParsesAndZeroizesOnClose() {
        String text = "3--12--54321DAB--00112233445566778899AABBCCDDEEFF--0--1&&8.8.1";
        byte[] padded = Arrays.copyOf(text.getBytes(StandardCharsets.US_ASCII), 110);
        try (LegacyWatchPairingCodec.LegacyOpticalCode code =
                     LegacyWatchPairingCodec.LegacyOpticalCode.parse(padded)) {
            assertEquals(3, code.strategy);
            assertEquals(12, code.compatibilityVersion);
            assertEquals("54321DAB", code.advertisedName);
            assertEquals(0, code.material);
            assertEquals(1, code.size);
            assertEquals("8.8.1", code.systemVersion);
            assertEquals(16, code.oobKey().length);
            assertEquals(0x00, code.oobKey()[0] & 0xff);
            assertEquals(0xFF, code.oobKey()[15] & 0xff);
        }
    }

    @Test
    public void bluetoothCoreSpecAppendixD1AndD2LegacyVectorsMatchExactly() {
        byte[] tk = new byte[16];
        // Appendix D.1 c1 test vector (Core Spec values are big-endian MSB..LSB; SMP wire is little-endian).
        byte[] rLe = reverseHex("5783D52156AD6F0E6388274EC6702EE0");
        byte[] iaLe = reverseHex("A1A2A3A4A5A6");
        byte[] raLe = reverseHex("B1B2B3B4B5B6");
        byte[] preqLe = reverseHex("07071000000101");
        byte[] presLe = reverseHex("05000800000302");
        byte[] expectedC1Le = reverseHex("1E1E3FEF878988EAD2A74DC5BEF13B86");
        byte[] actualC1Le = BleSecureConnectionsCrypto.legacyC1(tk, rLe, preqLe, presLe, 1, iaLe, 0, raLe);
        assertArrayEquals(expectedC1Le, actualC1Le);

        // Appendix D.2 s1 test vector
        byte[] r1Le = reverseHex("000F0E0D0C0B0A091122334455667788");
        byte[] r2Le = reverseHex("010203040506070899AABBCCDDEEFF00");
        byte[] expectedS1Le = reverseHex("9A1FE1F0E8B0F49B5B4216AE796DA062");
        byte[] actualS1Le = BleSecureConnectionsCrypto.legacyS1(tk, r1Le, r2Le);
        assertArrayEquals(expectedS1Le, actualS1Le);
    }

    @Test
    public void leScPasskeyHelpersAndLegacySmpPdusRoundTrip() {
        byte[] r = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(123456);
        assertEquals(0x40, r[0] & 0xff);
        assertEquals(0xE2, r[1] & 0xff);
        assertEquals(0x01, r[2] & 0xff);
        for (int i = 3; i < 16; i++) assertEquals(0, r[i]);

        for (int round = 0; round < 20; round++) {
            int expectedZ = 0x80 | ((123456 >>> round) & 1);
            assertEquals(expectedZ, BleSecureConnectionsCrypto.passkeyRoundZ(123456, round));
        }

        byte[] rand = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] cidPdu = BluetoothSmpCodec.centralIdentification(0x1234, rand);
        BluetoothSmpCodec.CentralIdentification parsedCid = BluetoothSmpCodec.parseCentralIdentification(cidPdu);
        assertEquals(0x1234, parsedCid.ediv);
        assertArrayEquals(rand, parsedCid.rand());
        parsedCid.destroy();

        byte[] sample16 = new byte[16];
        Arrays.fill(sample16, 0, 16, (byte) 0x5A);
        assertArrayEquals(sample16, BluetoothSmpCodec.parsePairingConfirm(BluetoothSmpCodec.pairingConfirm(sample16)));
        assertArrayEquals(sample16, BluetoothSmpCodec.parseEncryptionInformation(BluetoothSmpCodec.encryptionInformation(sample16)));
        assertArrayEquals(sample16, BluetoothSmpCodec.parseSigningInformation(BluetoothSmpCodec.signingInformation(sample16)));
    }

    @Test
    public void idsBtLinkFramingHandlesShortExtendedAndChecksumFlaggedPayloads() {
        byte[] shortPayload = "hello-ids".getBytes(StandardCharsets.US_ASCII);
        byte[] shortWire = LegacyWatchPairingCodec.encodeIdsBtLinkPacket(shortPayload);
        assertEquals(1 + shortPayload.length, shortWire.length);
        assertEquals(shortPayload.length, shortWire[0] & 0xff);

        byte[] longPayload = new byte[300];
        new SecureRandom().nextBytes(longPayload);
        longPayload[0] = 0x15; // Ensure bit 7 is clear
        byte[] longWire = LegacyWatchPairingCodec.encodeIdsBtLinkPacket(longPayload);
        assertEquals(2 + 300, longWire.length);
        assertEquals(0x81, longWire[0] & 0xff);
        assertEquals(0x2C, longWire[1] & 0xff);

        LegacyWatchPairingCodec.IdsBtLinkStreamDecoder decoder =
                new LegacyWatchPairingCodec.IdsBtLinkStreamDecoder();
        // Feed split chunks
        byte[] combined = new byte[shortWire.length + longWire.length];
        System.arraycopy(shortWire, 0, combined, 0, shortWire.length);
        System.arraycopy(longWire, 0, combined, shortWire.length, longWire.length);
        assertTrue(decoder.push(Arrays.copyOfRange(combined, 0, 4)).isEmpty());
        List<LegacyWatchPairingCodec.IdsBtLinkFrame> frames =
                decoder.push(Arrays.copyOfRange(combined, 4, combined.length));
        assertEquals(2, frames.size());
        assertArrayEquals(shortPayload, frames.get(0).payload);
        assertFalse(frames.get(0).checksumFlagged);
        assertArrayEquals(longPayload, frames.get(1).payload);

        // Bit-7 checksum-flagged inbound frame (length=5: [0x82, 0xAB, 0xCD, 'O', 'K'])
        byte[] flaggedWire = new byte[]{5, (byte) 0x82, (byte) 0xAB, (byte) 0xCD, 'O', 'K'};
        List<LegacyWatchPairingCodec.IdsBtLinkFrame> flagged = decoder.push(flaggedWire);
        assertEquals(1, flagged.size());
        assertTrue(flagged.get(0).checksumFlagged);
        assertEquals(0xABCD, flagged.get(0).rawChecksum);
        assertArrayEquals(new byte[]{(byte) 0x82, 'O', 'K'}, flagged.get(0).payload);
    }

    @Test
    public void hciAndHostArgumentsSupportExperimentalLegacyOptIn() {
        HciCodec.BtClServiceRecord ids = HciCodec.idsBtLinkService();
        assertEquals(HciCodec.IDS_BT_LINK_SERVICE_ID, ids.serviceId);
        assertTrue(ids.ertm());

        byte[] rand = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] ltk = new byte[16];
        Arrays.fill(ltk, (byte) 0x33);
        byte[] startEnc = HciCodec.buildLeStartEncryptionParameters(0x0042, rand, 0x2A1B, ltk);
        assertEquals(28, startEnc.length);
        assertEquals(0x42, startEnc[0] & 0xff);
        assertEquals(0x1B, startEnc[10] & 0xff);
        assertEquals(0x2A, startEnc[11] & 0xff);

        HalHostArguments parsed = HalHostArguments.parse(new String[]{
                "--acknowledge-target", HalHostArguments.TARGET, "--experimental-legacy-pairing"
        });
        assertTrue(parsed.experimentalLegacyPairing);
        assertFalse(HalHostArguments.parse(new String[]{
                "--acknowledge-target", HalHostArguments.TARGET
        }).experimentalLegacyPairing);
        assertThrows(IllegalArgumentException.class, () -> HalHostArguments.parse(new String[]{
                "--acknowledge-target", HalHostArguments.TARGET,
                "--recover-stale-smp", "--experimental-legacy-pairing"
        }));
    }

    private static byte[] reverseHex(String hex) {
        byte[] be = new byte[hex.length() / 2];
        for (int i = 0; i < be.length; i++) {
            be[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    | Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        byte[] le = new byte[be.length];
        for (int i = 0; i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }
}
