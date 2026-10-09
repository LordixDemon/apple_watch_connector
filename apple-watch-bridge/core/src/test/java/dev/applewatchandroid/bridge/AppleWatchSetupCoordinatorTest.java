package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public final class AppleWatchSetupCoordinatorTest {
    @Test
    public void freshUltra2FlowJoinsIndependentGatesAndUsesRuntime25() {
        AppleWatchSetupCoordinator coordinator =
                new AppleWatchSetupCoordinator();

        assertTypes(
                coordinator.beginUltra2Generation(
                        1,
                        physicalIdentifier(),
                        physicalMetadata()),
                AppleWatchSetupCoordinator.ActionType
                        .START_NETWORK_RELAY_AUTH);
        assertTrue(
                coordinator.onActiveChanged(
                        1,
                        true).isEmpty());
        assertTrue(
                coordinator.onAuthDataReady(
                        1).isEmpty());
        assertTypes(
                coordinator.onNetworkRelayCompleted(
                        1,
                        true,
                        true),
                AppleWatchSetupCoordinator.ActionType
                        .CREATE_LOCAL_PAIRING_STORE);
        assertTypes(
                coordinator.onLocalPairingStoreCreated(
                        1,
                        true),
                AppleWatchSetupCoordinator.ActionType
                        .ADD_IDS_PAIRED_DEVICE);
        assertTypes(
                coordinator.onIdsPeerAdded(
                        1,
                        true),
                AppleWatchSetupCoordinator.ActionType
                        .INITIALIZE_IDS_CHANNELS);
        assertTrue(
                coordinator.onIdsChannelsInitialized(
                        1,
                        true).isEmpty());

        AppleWatchSetupCoordinator.Action check =
                only(
                        coordinator
                                .onIdsCompatibilityVersion(
                                        1,
                                        25));
        assertEquals(
                AppleWatchSetupCoordinator.ActionType
                        .SEND_COMPATIBILITY_STATE,
                check.type);
        assertEquals(
                Integer.valueOf(
                        NanoRegistryClassDCodec
                                .COMPATIBILITY_STATE_CHECK),
                check.compatibilityState);
        byte[] checkWire =
                NanoRegistryClassDCodec.encode(
                        check.pairingModeRequest());
        try {
            assertArrayEquals(
                    hex(
                            "08 01 10 1a 18 19 20 19"),
                    checkWire);
        } finally {
            wipe(
                    checkWire);
        }

        List<AppleWatchSetupCoordinator.Action>
                fullSnapshot =
                coordinator.onFullWatchClassCSnapshot(
                        1,
                        "Watch7,5",
                        "Ultra2-runtime-SKU",
                        0x8310,
                        capabilities());
        assertTypes(
                fullSnapshot,
                AppleWatchSetupCoordinator.ActionType
                        .SEND_COMPATIBILITY_STATE);
        assertEquals(
                Integer.valueOf(
                        NanoRegistryClassDCodec
                                .COMPATIBILITY_STATE_CONFIGURE),
                fullSnapshot.get(0)
                        .compatibilityState);

        assertTypes(
                coordinator.onIdsAccountAndDeviceReady(
                        1),
                AppleWatchSetupCoordinator.ActionType
                        .PAIRING_TRANSPORT_COMPLETE);
        assertTrue(
                coordinator.onIdsAccountAndDeviceReady(
                        1).isEmpty());

        AppleWatchSetupCoordinator.Action normal =
                only(
                        coordinator.onIsPairedChanged(
                                1,
                                true));
        assertEquals(
                Integer.valueOf(
                        NanoRegistryClassDCodec
                                .COMPATIBILITY_STATE_NORMAL),
                normal.compatibilityState);
        assertEquals(
                AppleWatchSetupCoordinator.Phase.NORMAL,
                coordinator.snapshot().phase);
    }

    @Test
    public void atomicFullSnapshotSkipsOptionalCheckState() {
        AppleWatchSetupCoordinator coordinator =
                new AppleWatchSetupCoordinator();
        coordinator.beginUltra2Generation(
                2,
                physicalIdentifier(),
                physicalMetadata());
        coordinator.onActiveChanged(
                2,
                true);

        assertTrue(
                coordinator.onFullWatchClassCSnapshot(
                        2,
                        "Watch7,5",
                        "Runtime-model",
                        0x8310,
                        capabilities()).isEmpty());
        AppleWatchSetupCoordinator.Action configure =
                only(
                        coordinator
                                .onIdsCompatibilityVersion(
                                        2,
                                        25));

        assertEquals(
                Integer.valueOf(
                        NanoRegistryClassDCodec
                                .COMPATIBILITY_STATE_CONFIGURE),
                configure.compatibilityState);
    }

    @Test
    public void earlyReadinessEventsCannotBypassIdsSequence() {
        AppleWatchSetupCoordinator coordinator =
                new AppleWatchSetupCoordinator();
        coordinator.beginUltra2Generation(
                3,
                physicalIdentifier(),
                physicalMetadata());
        coordinator.onAuthDataReady(
                3);
        coordinator.onNetworkRelayCompleted(
                3,
                true,
                true);

        assertTrue(
                coordinator.onFullWatchClassCSnapshot(
                        3,
                        "Watch7,5",
                        "Runtime-model",
                        0x8310,
                        capabilities()).isEmpty());
        assertTrue(
                coordinator.onIdsAccountAndDeviceReady(
                        3).isEmpty());
        assertFalse(
                coordinator.snapshot()
                        .transportCompletionIssued);

        coordinator.onLocalPairingStoreCreated(
                3,
                true);
        coordinator.onIdsPeerAdded(
                3,
                true);
        assertTypes(
                coordinator.onIdsChannelsInitialized(
                        3,
                        true),
                AppleWatchSetupCoordinator.ActionType
                        .PAIRING_TRANSPORT_COMPLETE);
    }

    @Test
    public void staleGenerationCannotAdvanceReplacement() {
        AppleWatchSetupCoordinator coordinator =
                new AppleWatchSetupCoordinator();
        coordinator.beginUltra2Generation(
                10,
                physicalIdentifier(),
                physicalMetadata());
        coordinator.onAuthDataReady(
                10);

        assertTypes(
                coordinator.beginUltra2Generation(
                        11,
                        physicalIdentifier(),
                        physicalMetadata()),
                AppleWatchSetupCoordinator.ActionType
                        .RESET_PAIRING_GENERATION,
                AppleWatchSetupCoordinator.ActionType
                        .START_NETWORK_RELAY_AUTH);
        assertTrue(
                coordinator.onNetworkRelayCompleted(
                        10,
                        true,
                        true).isEmpty());

        List<AppleWatchSetupCoordinator.Action> failure =
                coordinator.onNetworkRelayCompleted(
                        11,
                        true,
                        true);
        assertTypes(
                failure,
                AppleWatchSetupCoordinator.ActionType
                        .REPORT_FAILURE,
                AppleWatchSetupCoordinator.ActionType
                        .RESET_PAIRING_GENERATION);
        assertEquals(
                AppleWatchSetupCoordinator.Failure
                        .AUTH_DATA_MISSING,
                failure.get(0).failure);
        assertEquals(
                AppleWatchSetupCoordinator.Phase.FAILED,
                coordinator.snapshot().phase);
    }

    @Test
    public void type10PersistsBeforeSendAndRetriesWithoutRotation() {
        AppleWatchSetupCoordinator coordinator =
                new AppleWatchSetupCoordinator();
        coordinator.beginUltra2Generation(
                20,
                physicalIdentifier(),
                physicalMetadata());

        assertTrue(
                coordinator.onActiveChanged(
                        20,
                        true).isEmpty());
        assertTrue(
                coordinator.onFullWatchClassCSnapshot(
                        20,
                        "Watch7,5",
                        "Runtime-model",
                        0x8310,
                        capabilities()).isEmpty());
        assertTrue(
                coordinator.onPairingSessionIdProperty(
                        20,
                        false).isEmpty());

        assertTypes(
                coordinator.onPairingSessionRetryTrigger(
                        20,
                        AppleWatchSetupCoordinator
                                .PairingSessionRetryTrigger
                                .NEW_WATCH_PAIRING_SUCCEEDED),
                AppleWatchSetupCoordinator.ActionType
                        .PERSIST_PAIRING_SESSION_ID);
        assertTrue(
                coordinator.onPairingSessionRetryTrigger(
                        20,
                        AppleWatchSetupCoordinator
                                .PairingSessionRetryTrigger
                                .COMPANION_PASSCODE_SUCCEEDED)
                        .isEmpty());

        assertTypes(
                coordinator.onPairingSessionIdPersisted(
                        20,
                        true),
                AppleWatchSetupCoordinator.ActionType
                        .SEND_PAIRING_SESSION_ID);
        assertTrue(
                coordinator.onPairingSessionIdSendResult(
                        20,
                        false).isEmpty());
        assertTypes(
                coordinator.onClassDReconnect(
                        20),
                AppleWatchSetupCoordinator.ActionType
                        .SEND_PAIRING_SESSION_ID);
        assertTrue(
                coordinator.onPairingSessionIdSendResult(
                        20,
                        true).isEmpty());
        assertTrue(
                coordinator.onPairingSessionRetryTrigger(
                        20,
                        AppleWatchSetupCoordinator
                                .PairingSessionRetryTrigger
                                .DEVICE_SWITCH_SUCCEEDED)
                        .isEmpty());

        AppleWatchSetupCoordinator.Snapshot snapshot =
                coordinator.snapshot();
        assertTrue(
                snapshot.pairingSessionIdPresent);
        assertFalse(
                snapshot.pairingSessionPersistRequested);
        assertFalse(
                snapshot.pairingSessionNeedsSend);
    }

    @Test
    public void existingPairingSessionIdSuppressesGeneration() {
        AppleWatchSetupCoordinator coordinator =
                new AppleWatchSetupCoordinator();
        coordinator.beginUltra2Generation(
                21,
                physicalIdentifier(),
                physicalMetadata());
        coordinator.onFullWatchClassCSnapshot(
                21,
                "Watch7,5",
                "Runtime-model",
                0x8310,
                capabilities());
        coordinator.onPairingSessionIdProperty(
                21,
                true);

        assertTrue(
                coordinator.onPairingSessionRetryTrigger(
                        21,
                        AppleWatchSetupCoordinator
                                .PairingSessionRetryTrigger
                                .NEW_WATCH_PAIRING_SUCCEEDED)
                        .isEmpty());
        assertTrue(
                coordinator.snapshot()
                        .pairingSessionIdPresent);
    }

    @Test
    public void identityAndImperativeOrderFailClosed() {
        AppleWatchSetupCoordinator wrongIdentity =
                new AppleWatchSetupCoordinator();
        wrongIdentity.beginUltra2Generation(
                30,
                physicalIdentifier(),
                physicalMetadata());

        List<AppleWatchSetupCoordinator.Action>
                identityFailure =
                wrongIdentity.onFullWatchClassCSnapshot(
                        30,
                        "Watch7,5",
                        "Runtime-model",
                        0x007b,
                        capabilities());
        assertEquals(
                AppleWatchSetupCoordinator.Failure
                        .TARGET_IDENTITY_MISMATCH,
                identityFailure.get(0).failure);

        AppleWatchSetupCoordinator wrongOrder =
                new AppleWatchSetupCoordinator();
        wrongOrder.beginUltra2Generation(
                31,
                physicalIdentifier(),
                physicalMetadata());
        List<AppleWatchSetupCoordinator.Action>
                orderFailure =
                wrongOrder.onIdsPeerAdded(
                        31,
                        true);
        assertEquals(
                AppleWatchSetupCoordinator.Failure
                        .UNEXPECTED_ORDER,
                orderFailure.get(0).failure);
    }

    @Test
    public void version26ReferenceUsesDynamicFieldThree() {
        AppleWatchSetupCoordinator coordinator =
                new AppleWatchSetupCoordinator();
        WatchSetupMetadataCodec.ExtendedMetadata metadata26 =
                WatchSetupMetadataCodec
                        .decodeExtendedMetadata(
                                WatchSetupMetadataCodec
                                        .encodeExtendedMetadata(
                                                26,
                                                7,
                                                5,
                                                false,
                                                0x001a_0600L,
                                                null));
        coordinator.beginUltra2Generation(
                40,
                physicalIdentifier(),
                metadata26);
        coordinator.onActiveChanged(
                40,
                true);

        AppleWatchSetupCoordinator.Action check =
                only(
                        coordinator
                                .onIdsCompatibilityVersion(
                                        40,
                                        26));
        byte[] wire =
                NanoRegistryClassDCodec.encode(
                        check.pairingModeRequest());
        try {
            assertArrayEquals(
                    hex(
                            "08 01 10 1a 18 1a 20 19"),
                    wire);
        } finally {
            wipe(
                    wire);
        }
    }

    @Test
    public void pairingModeAccessorRejectsNonCompatibilityAction() {
        AppleWatchSetupCoordinator coordinator =
                new AppleWatchSetupCoordinator();
        AppleWatchSetupCoordinator.Action start =
                only(
                        coordinator.beginUltra2Generation(
                                50,
                                physicalIdentifier(),
                                physicalMetadata()));

        boolean threw = false;
        try {
            start.pairingModeRequest();
        } catch (IllegalStateException expected) {
            threw = true;
        }
        assertTrue(
                threw);
        assertNull(
                start.compatibilityState);
    }

    private static WatchSetupMetadataCodec.Identifier
            physicalIdentifier() {
        return WatchSetupMetadataCodec
                .decodeIdentifier(
                        hex(
                                "86 93 f4 ce"));
    }

    private static WatchSetupMetadataCodec.ExtendedMetadata
            physicalMetadata() {
        return WatchSetupMetadataCodec
                .decodeExtendedMetadata(
                        hex(
                                "64 38 50 00 d0 10 00"));
    }

    private static Set<String> capabilities() {
        return Set.of(
                AppleWatchSetupCoordinator
                        .PAIRING_SESSION_ID_CAPABILITY);
    }

    private static AppleWatchSetupCoordinator.Action only(
            List<AppleWatchSetupCoordinator.Action> actions) {
        assertEquals(
                1,
                actions.size());
        return actions.get(0);
    }

    private static void assertTypes(
            List<AppleWatchSetupCoordinator.Action> actions,
            AppleWatchSetupCoordinator.ActionType... types) {
        assertEquals(
                types.length,
                actions.size());
        for (int index = 0;
             index < types.length;
             index++) {
            assertEquals(
                    types[index],
                    actions.get(index).type);
        }
    }

    private static byte[] hex(
            String value) {
        String compact =
                value.replace(
                        " ",
                        "");
        byte[] result =
                new byte[compact.length() / 2];
        for (int index = 0;
             index < result.length;
             index++) {
            result[index] =
                    (byte) Integer.parseInt(
                            compact.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return result;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            java.util.Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
