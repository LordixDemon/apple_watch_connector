package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.discovery.CompositeDiscoveryEngine;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.StrategyPolicy;
import dev.applewatchandroid.bridge.spi.WatchAdvertisedProfile;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public final class CompositeDiscoveryEngineTest {

    @Test
    public void productionDefaultIgnoresLegacyStrategiesAndMatchesStrategy4() {
        CompositeDiscoveryEngine<HciCodec.AdvertisingReport, HciCodec.AdvertisingReport> engine =
                WatchDiscoveryEngines.forPolicy(StrategyPolicy.PRODUCTION_DEFAULT);
        assertEquals(List.of(PairingStrategyId.NETWORK_RELAY_S4), engine.enabledMatcherStrategies());

        HciCodec.AdvertisingReport legacyS1 = report(new byte[]{1, 2, 3, 4, 5, 0x41}, -42,
                nameAd("12345BAA"));
        assertNull(engine.accept(legacyS1, 100));
        assertTrue(engine.pollReady(10_000).isEmpty());

        HciCodec.AdvertisingReport modernS4 = modernS4Report(new byte[]{9, 8, 7, 6, 5, 0x42}, -55);
        try (WatchAdvertisedProfile<HciCodec.AdvertisingReport> matched = engine.accept(modernS4, 200)) {
            assertNotNull(matched);
            assertEquals(PairingStrategyId.NETWORK_RELAY_S4, matched.strategyId);
            assertFalse(matched.strategyInferred);
        }
    }

    @Test
    public void explicitLegacyStrategiesRouteToExactStrategyMatcher() {
        CompositeDiscoveryEngine<HciCodec.AdvertisingReport, HciCodec.AdvertisingReport> engine =
                WatchDiscoveryEngines.forPolicy(StrategyPolicy.ALL_WITH_EXPERIMENTAL);

        try (WatchAdvertisedProfile<HciCodec.AdvertisingReport> s1 =
                     engine.accept(report(new byte[]{1, 1, 1, 1, 1, 0x41}, -50, nameAd("11111BAA")), 100);
             WatchAdvertisedProfile<HciCodec.AdvertisingReport> s2 =
                     engine.accept(report(new byte[]{2, 2, 2, 2, 2, 0x42}, -50, nameAd("22222CAA")), 100);
             WatchAdvertisedProfile<HciCodec.AdvertisingReport> s3 =
                     engine.accept(report(new byte[]{3, 3, 3, 3, 3, 0x43}, -50, nameAd("33333DAA")), 100)) {
            assertNotNull(s1);
            assertEquals(PairingStrategyId.SIMULTANEOUS_S1, s1.strategyId);
            assertFalse(s1.strategyInferred);

            assertNotNull(s2);
            assertEquals(PairingStrategyId.SEQUENTIAL_S2, s2.strategyId);
            assertFalse(s2.strategyInferred);

            assertNotNull(s3);
            assertEquals(PairingStrategyId.DIRECT_IPSEC_S3, s3.strategyId);
            assertFalse(s3.strategyInferred);
        }
    }

    @Test
    public void unknownLegacyStrategyRoutesToPreferredEnabledLegacyStrategyNotHardcodedS3() {
        // Explicit S4 + S1 policy: an "Apple Watch 54321" ad without strategy char must infer S1, not S3.
        StrategyPolicy policyS4S1 = StrategyPolicy.parseCsv("4,1");
        CompositeDiscoveryEngine<HciCodec.AdvertisingReport, HciCodec.AdvertisingReport> engine =
                WatchDiscoveryEngines.forPolicy(policyS4S1);

        // Explicit S3 advertisement must be ignored when only S4 and S1 are enabled.
        assertNull(engine.accept(report(new byte[]{3, 3, 3, 3, 3, 0x43}, -45, nameAd("33333DAA")), 100));

        // Ambiguous "Apple Watch 54321" ad matches S1 as inferred.
        try (WatchAdvertisedProfile<HciCodec.AdvertisingReport> inferred =
                     engine.accept(report(new byte[]{4, 4, 4, 4, 4, 0x44}, -48, nameAd("Apple Watch 54321")), 100)) {
            assertNotNull(inferred);
            assertEquals(PairingStrategyId.SIMULTANEOUS_S1, inferred.strategyId);
            assertTrue(inferred.strategyInferred);
        }
    }

    @Test
    public void selectionTokenEnforcesFreshnessTtlAndWipesOnReset() {
        CompositeDiscoveryEngine<HciCodec.AdvertisingReport, HciCodec.AdvertisingReport> engine =
                WatchDiscoveryEngines.forPolicy(StrategyPolicy.ALL_WITH_EXPERIMENTAL);
        WatchAdvertisedProfile<HciCodec.AdvertisingReport> s2 =
                engine.accept(report(new byte[]{0x10, 0x20, 0x30, 0x40, 0x50, 0x60}, -51, nameAd("98765CAA")), 1000);
        assertNotNull(s2);

        String token = engine.observeSelection(s2, 1000);
        assertSame(s2, engine.select(token, 5999));
        assertNull(engine.select(token, 6001));

        engine.reset();
        assertArrayEquals(new byte[6], s2.addressCopy());
    }

    @Test
    public void opticalMatchingSynthesizes8CharNameFromFe25IdentifierWhenLocalNameAbsent() {
        byte[] id3 = WatchSetupMetadataCodec.encodeIdentifier(3, 12345, 0, 0);
        byte[] fe25OnlyAd = buildFe25Ad(id3, new byte[7]);
        LegacyWatchPairingCodec.LegacyAdvertisement parsed =
                LegacyWatchPairingCodec.parseAdvertisement(fe25OnlyAd, HciCodec.parseWatchSetupPayload(fe25OnlyAd));
        assertNotNull(parsed);
        assertEquals(3, parsed.strategy);

        // strategy=3 -> 'D', enclosureMaterial=0 -> 'A', deviceSize=0 -> 'A', id=12345 -> "12345DAA"
        try (LegacyWatchPairingCodec.LegacyOpticalCode matching =
                     LegacyWatchPairingCodec.LegacyOpticalCode.parse(buildLegacyOpticalPayload("12345DAA"));
             LegacyWatchPairingCodec.LegacyOpticalCode nonMatching =
                     LegacyWatchPairingCodec.LegacyOpticalCode.parse(buildLegacyOpticalPayload("99999DAA"))) {
            assertTrue(parsed.matchesOpticalCode(matching));
            assertFalse(parsed.matchesOpticalCode(nonMatching));
        }
    }

    private static byte[] buildLegacyOpticalPayload(String advertisedName) {
        String text = "3--1--" + advertisedName + "--00112233445566778899AABBCCDDEEFF--0--0&&7.5";
        byte[] textBytes = text.getBytes(StandardCharsets.US_ASCII);
        byte[] opticalPayload = new byte[110];
        System.arraycopy(textBytes, 0, opticalPayload, 0, textBytes.length);
        return opticalPayload;
    }

    private static HciCodec.AdvertisingReport report(byte[] addr, int rssi, byte[] ad) {
        return new HciCodec.AdvertisingReport(0, 1, addr, rssi, ad, HciCodec.parseWatchSetupPayload(ad));
    }

    private static HciCodec.AdvertisingReport modernS4Report(byte[] addr, int rssi) {
        byte[] id = WatchSetupMetadataCodec.encodeIdentifier(4, 12345, 1, 0);
        byte[] meta = WatchSetupMetadataCodec.encodeExtendedMetadata(25, 7, 5, false, 0x001A0200L, new byte[0]);
        byte[] ad = buildFe25Ad(id, meta);
        return new HciCodec.AdvertisingReport(0, 1, addr, rssi, ad, HciCodec.parseWatchSetupPayload(ad));
    }

    private static byte[] nameAd(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        byte[] ad = new byte[2 + bytes.length];
        ad[0] = (byte) (bytes.length + 1);
        ad[1] = 0x09;
        System.arraycopy(bytes, 0, ad, 2, bytes.length);
        return ad;
    }

    private static byte[] buildFe25Ad(byte[] id, byte[] meta) {
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
}
