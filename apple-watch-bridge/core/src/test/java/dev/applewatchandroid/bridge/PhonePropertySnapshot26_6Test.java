package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public final class PhonePropertySnapshot26_6Test {
    @Test
    public void allWireNamesMatchTheExtractedFirmwareStrings() throws Exception {
        // Fixture resolved from the NSString pointers loaded for all 60
        // initializeGetters keys in 23G71, independently of this schema.
        try (java.io.InputStream input = getClass().getResourceAsStream(
                "/nanoregistry/phone-property-names-23G71.txt")) {
            org.junit.Assert.assertNotNull(input);
            java.util.List<String> nativeNames = new String(input.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            assertEquals(nativeNames, PhonePropertySnapshot26_6.PROPERTY_NAMES);
            assertTrue(nativeNames.contains("buildString"));
            assertTrue(nativeNames.contains("_supportedPairingStrategy"));
            assertTrue(nativeNames.contains("capabilities"));
            org.junit.Assert.assertFalse(nativeNames.contains("ProductType"));
        }
    }

    @Test
    public void schemaContainsExactPhoneSideCountAndBoundaryNames() {
        assertEquals(
                60,
                PhonePropertySnapshot26_6.PROPERTY_NAMES.size());
        assertEquals(
                60,
                PhonePropertySnapshot26_6.PROPERTY_NAMES
                        .stream()
                        .distinct()
                        .count());
        assertEquals(
                "backgroundAtrialFibrillationVersion",
                PhonePropertySnapshot26_6.PROPERTY_NAMES.get(
                        0));
        assertEquals(
                "MDMManagementState",
                PhonePropertySnapshot26_6.PROPERTY_NAMES.get(
                        59));
    }

    @Test
    public void fullSnapshotPreservesAllSlotsButOnlyExplicitValues() {
        NanoRegistryPropertyCodec.PropertiesChanged snapshot =
                PhonePropertySnapshot26_6.fullSnapshot(
                        Map.of(
                                "productType",
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .string(
                                                "iPhone18,1")),
                        123.5);
        byte[] encoded =
                NanoRegistryPropertyCodec.encode(
                        snapshot);
        NanoRegistryPropertyCodec.PropertiesChanged decoded =
                NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                encoded);
        try {
            assertTrue(
                    decoded.thisIsAllOfThem);
            assertEquals(
                    Double.valueOf(
                            123.5),
                    decoded.bornOn);
            assertEquals(
                    60,
                    decoded.properties.size());
            assertEquals(
                    "productType",
                    decoded.properties.get(
                            20).name);
            assertEquals(
                    "iPhone18,1",
                    decoded.properties.get(
                            20).value.stringValue);
            assertNull(
                    decoded.properties.get(
                            19).value);
            assertNull(
                    decoded.properties.get(
                            21).value);
        } finally {
            snapshot.destroy();
            decoded.destroy();
            java.util.Arrays.fill(
                    encoded,
                    (byte) 0);
        }
    }

    @Test
    public void emptyUnknownAndNonFiniteProfilesFailBeforeEncoding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PhonePropertySnapshot26_6.fullSnapshot(
                        Map.of(),
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> PhonePropertySnapshot26_6.fullSnapshot(
                        Map.of(
                                "InventedProperty",
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .string(
                                                "x")),
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> PhonePropertySnapshot26_6.fullSnapshot(
                        Map.of(
                                "productType",
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .string(
                                                "iPhone18,1")),
                        Double.NaN));
    }

    @Test
    public void unixEpochConversionMatchesAppleReferenceEpoch() {
        assertEquals(
                0,
                PhonePropertySnapshot26_6
                        .bornOnFromUnixMilliseconds(
                                978_307_200_000L),
                0);
        assertEquals(
                1.25,
                PhonePropertySnapshot26_6
                        .bornOnFromUnixMilliseconds(
                                978_307_201_250L),
                0);
    }
}
