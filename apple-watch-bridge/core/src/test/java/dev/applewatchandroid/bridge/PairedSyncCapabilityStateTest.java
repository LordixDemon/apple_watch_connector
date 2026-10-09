package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

public final class PairedSyncCapabilityStateTest {
    @Test
    public void exactWatchCapabilitySelectsPreferredServiceAfterWireRoundTrip() {
        PairedSyncCapabilityState state =
                new PairedSyncCapabilityState();
        NanoRegistryPropertyCodec.PropertiesChanged original =
                full(
                        capabilitySet(
                                PairedSyncCapabilityState
                                        .capabilityUuid()));
        byte[] encoded =
                NanoRegistryPropertyCodec
                        .encodePropertiesChanged(
                                original);
        NanoRegistryPropertyCodec.PropertiesChanged decoded =
                NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                encoded);
        try {
            assertEquals(
                    PairedSyncCapabilityState.Status.PRESENT,
                    state.apply(
                            decoded));
            assertEquals(
                    PairedSyncCodec.PREFERRED_SERVICE,
                    state.requireService());
            byte[] uuid =
                    PairedSyncCapabilityState.capabilityUuid();
            int externalId =
                    (uuid[0] & 0xff) << 24
                            | (uuid[1] & 0xff) << 16
                            | (uuid[2] & 0xff) << 8
                            | uuid[3] & 0xff;
            assertEquals(
                    PairedSyncCapabilityState.CAPABILITY_ID,
                    externalId);
        } finally {
            decoded.destroy();
            original.destroy();
        }
    }

    @Test
    public void validSetWithoutCapabilitySelectsFallbackService() {
        PairedSyncCapabilityState state =
                new PairedSyncCapabilityState();
        NanoRegistryPropertyCodec.PropertiesChanged changed =
                full(
                        capabilitySet(
                                uuid(
                                        0x10)));
        try {
            assertEquals(
                    PairedSyncCapabilityState.Status.ABSENT,
                    state.apply(
                            changed));
            assertEquals(
                    PairedSyncCodec.FALLBACK_SERVICE,
                    state.requireService());
        } finally {
            changed.destroy();
        }
    }

    @Test
    public void emptySetSurvivesWireRoundTripAsAbsent() {
        PairedSyncCapabilityState state =
                new PairedSyncCapabilityState();
        NanoRegistryPropertyCodec.PropertiesChanged original =
                full(
                        capabilitySet());
        byte[] encoded =
                NanoRegistryPropertyCodec
                        .encodePropertiesChanged(
                                original);
        NanoRegistryPropertyCodec.PropertiesChanged decoded =
                NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                encoded);
        try {
            assertEquals(
                    PairedSyncCapabilityState.Status.ABSENT,
                    state.apply(
                            decoded));
        } finally {
            decoded.destroy();
            original.destroy();
        }
    }

    @Test
    public void deltaWithoutCapabilitiesPreservesKnownState() {
        PairedSyncCapabilityState state =
                new PairedSyncCapabilityState();
        NanoRegistryPropertyCodec.PropertiesChanged initial =
                full(
                        capabilitySet(
                                PairedSyncCapabilityState
                                        .capabilityUuid()));
        NanoRegistryPropertyCodec.PropertiesChanged unrelated =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        false,
                        List.of(
                                new NanoRegistryPropertyCodec.Property(
                                        "productType",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .string(
                                                        "Watch7,5"))),
                        null);
        try {
            state.apply(
                    initial);
            assertEquals(
                    PairedSyncCapabilityState.Status.PRESENT,
                    state.apply(
                            unrelated));
        } finally {
            unrelated.destroy();
            initial.destroy();
        }
    }

    @Test
    public void explicitAbsentValueClearsEarlierSupport() {
        PairedSyncCapabilityState state =
                new PairedSyncCapabilityState();
        NanoRegistryPropertyCodec.PropertiesChanged initial =
                full(
                        capabilitySet(
                                PairedSyncCapabilityState
                                        .capabilityUuid()));
        NanoRegistryPropertyCodec.PropertiesChanged removed =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        false,
                        List.of(
                                new NanoRegistryPropertyCodec.Property(
                                        PairedSyncCapabilityState
                                                .PROPERTY_NAME,
                                        null)),
                        null);
        try {
            state.apply(
                    initial);
            assertEquals(
                    PairedSyncCapabilityState.Status.ABSENT,
                    state.apply(
                            removed));
            assertEquals(
                    PairedSyncCodec.FALLBACK_SERVICE,
                    state.requireService());
        } finally {
            removed.destroy();
            initial.destroy();
        }
    }

    @Test
    public void unknownUsesOrdinaryDefaultsButIncompleteFullSnapshotStillFails() {
        PairedSyncCapabilityState state =
                new PairedSyncCapabilityState();
        assertEquals(PairedSyncCodec.FALLBACK_SERVICE, state.requireService());
        assertEquals(PairedSyncCapabilityState.Status.UNKNOWN, state.status());

        NanoRegistryPropertyCodec.PropertiesChanged incomplete =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        true,
                        List.of(
                                new NanoRegistryPropertyCodec.Property(
                                        "productType",
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .string(
                                                        "Watch7,5"))),
                        null);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> state.apply(
                            incomplete));
            assertEquals(
                    PairedSyncCapabilityState.Status.UNKNOWN,
                    state.status());
        } finally {
            incomplete.destroy();
        }
    }

    @Test
    public void malformedCapabilityShapesAreRejectedWithoutStateMutation() {
        PairedSyncCapabilityState state =
                new PairedSyncCapabilityState();

        assertRejected(
                state,
                full(
                        NanoRegistryPropertyCodec.PropertyValue.array(
                                List.of(
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .uuid(
                                                        PairedSyncCapabilityState
                                                                .capabilityUuid())))));
        assertRejected(
                state,
                full(
                        NanoRegistryPropertyCodec.PropertyValue.set(
                                List.of(
                                        NanoRegistryPropertyCodec
                                                .PropertyValue
                                                .string(
                                                        "not-a-uuid")))));
        byte[] target =
                PairedSyncCapabilityState.capabilityUuid();
        assertRejected(
                state,
                full(
                        capabilitySet(
                                target,
                                target)));

        NanoRegistryPropertyCodec.PropertiesChanged duplicateProperty =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        true,
                        List.of(
                                capabilityProperty(
                                        capabilitySet(
                                                target)),
                                capabilityProperty(
                                        capabilitySet(
                                                target))),
                        null);
        assertRejected(
                state,
                duplicateProperty);
    }

    @Test
    public void miniUuidSetWireFlagIsNotAcceptedAsWatchWireShape() {
        byte[] miniSet =
                new byte[] {
                        0x08, 0x01,
                        0x12, 0x12,
                        0x0a, 0x0c,
                        0x43, 0x61, 0x70, 0x61,
                        0x62, 0x69, 0x6c, 0x69,
                        0x74, 0x69, 0x65, 0x73,
                        0x12, 0x02, 0x60, 0x01
                };
        NanoRegistryPropertyCodec.PropertiesChanged changed =
                NanoRegistryPropertyCodec
                        .decodePropertiesChanged(
                                miniSet);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new PairedSyncCapabilityState()
                            .apply(
                                    changed));
        } finally {
            changed.destroy();
        }
    }

    private static void assertRejected(
            PairedSyncCapabilityState state,
            NanoRegistryPropertyCodec.PropertiesChanged changed) {
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> state.apply(
                            changed));
            assertEquals(
                    PairedSyncCapabilityState.Status.UNKNOWN,
                    state.status());
        } finally {
            changed.destroy();
        }
    }

    private static NanoRegistryPropertyCodec.PropertiesChanged full(
            NanoRegistryPropertyCodec.PropertyValue value) {
        return new NanoRegistryPropertyCodec.PropertiesChanged(
                true,
                List.of(
                        capabilityProperty(
                                value)),
                800000000.5);
    }

    private static NanoRegistryPropertyCodec.Property capabilityProperty(
            NanoRegistryPropertyCodec.PropertyValue value) {
        return new NanoRegistryPropertyCodec.Property(
                PairedSyncCapabilityState.PROPERTY_NAME,
                value);
    }

    private static NanoRegistryPropertyCodec.PropertyValue capabilitySet(
            byte[]... uuids) {
        return NanoRegistryPropertyCodec.PropertyValue.set(
                java.util.Arrays.stream(
                                uuids)
                        .map(
                                NanoRegistryPropertyCodec
                                        .PropertyValue::uuid)
                        .toList());
    }

    private static byte[] uuid(
            int seed) {
        byte[] value =
                new byte[16];
        for (int index = 0;
                index < value.length;
                index++) {
            value[index] =
                    (byte) (seed + index);
        }
        return value;
    }
}
