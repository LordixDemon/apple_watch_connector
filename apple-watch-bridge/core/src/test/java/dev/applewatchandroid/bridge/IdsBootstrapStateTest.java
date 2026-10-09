package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IdsBootstrapStateTest {
    @Test
    public void controlClassDAndClassCAdvanceExactlyOnce() {
        IdsBootstrapState state =
                new IdsBootstrapState();
        assertFalse(
                state.ready());
        assertEquals(
                IdsBootstrapState.Action.START_CONTROL,
                state.begin());
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_D,
                state.observe(
                        IdsModernSessionCoordinator.EventType.CONTROL_READY,
                        IdsIpsecServiceRoute.CONTROL_SERVICE));
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_C,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .DATA_CHANNEL_JOINED,
                        classDConnector()));
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .DATA_CHANNEL_JOINED,
                        classCConnector()));
        assertFalse(
                state.ready());
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .HANDSHAKE_RECEIVED,
                        classDConnector()));
        assertEquals(
                IdsBootstrapState.Action.READY,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .HANDSHAKE_RECEIVED,
                        classCConnector()));
        assertTrue(
                state.ready());
        assertEquals(
                IdsBootstrapState.Phase.READY,
                state.phase());
    }

    @Test
    public void liveHardwareSequenceTest() {
        IdsBootstrapState state = new IdsBootstrapState();
        assertEquals(IdsBootstrapState.Action.START_CONTROL, state.begin());
        assertEquals(IdsBootstrapState.Action.START_CLASS_D, state.observe(IdsModernSessionCoordinator.EventType.CONTROL_READY, "control"));
        assertEquals(IdsBootstrapState.Action.NONE, state.observe(IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED, "nano-class-c"));
        assertEquals(IdsBootstrapState.Action.NONE, state.observe(IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED, "UTunDelivery-Default-Default-C"));
        assertEquals(IdsBootstrapState.Action.NONE, state.observe(IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED, "UTunDelivery-Default-Sync-C"));
        assertEquals(IdsBootstrapState.Action.NONE, state.observe(IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED, "UTunDelivery-Default-Default-D"));
        assertEquals(IdsBootstrapState.Action.NONE, state.observe(IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED, "UTunDelivery-Default-Sync-D"));
        assertEquals(IdsBootstrapState.Action.NONE, state.observe(IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED, "nano-class-c"));
        assertEquals(IdsBootstrapState.Action.NONE, state.observe(IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED, "UTunDelivery-Default-Default-C"));
        assertEquals(IdsBootstrapState.Action.NONE, state.observe(IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED, "UTunDelivery-Default-Sync-C"));
        IdsBootstrapState.Action action = state.observe(IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED, "UTunDelivery-Default-Default-D");
        System.out.println("LIVE ACTION ON D HANDSHAKE: " + action + " ready=" + state.ready());
        assertEquals(IdsBootstrapState.Action.READY, action);
    }

    @Test
    public void pairedReconnectStartsClassDSetupOnAttachWithHello() {
        IdsBootstrapState state = new IdsBootstrapState();
        assertEquals(IdsBootstrapState.Action.START_CLASS_D, state.beginPairedListenImmediately());
        assertEquals(IdsBootstrapState.Phase.CLASS_D_OPENING, state.phase());
        assertEquals(IdsBootstrapState.Action.NONE, state.onLinkDirectorReady());
        assertEquals(IdsBootstrapState.Action.NONE, state.onPairedKeyProbeCompleted());
        assertFalse(state.ready());
    }

    @Test
    public void pairedPolicyTimeoutRecoversControlOnceAfterFourKeyReceipts() {
        IdsBootstrapState state = new IdsBootstrapState();
        state.beginPairedListenImmediately();
        assertEquals(IdsBootstrapState.Action.START_KEY_PROBE, state.onPairedLanePolicyRejection());
        assertEquals(IdsBootstrapState.Action.NONE, state.onPairedLanePolicyRejection());
        for (int i = 0; i < 3; i++) {
            assertEquals(IdsBootstrapState.Action.NONE, state.onPairedKeyProbeCompleted());
        }
        assertEquals(IdsBootstrapState.Action.START_CONTROL, state.onPairedKeyProbeCompleted());
        assertEquals(IdsBootstrapState.Action.NONE, state.onPairedKeyProbeCompleted());
        assertEquals(IdsBootstrapState.Action.NONE, state.onPairedLanePolicyRejection());
        assertFalse(state.ready());
        state.observe(IdsModernSessionCoordinator.EventType.CONTROL_READY, "control");
        assertEquals(IdsBootstrapState.Action.NONE, state.onPairedLanePolicyRejection());
        assertEquals(IdsBootstrapState.Action.NONE, state.onPairedKeyProbeCompleted());
    }

    @Test
    public void freshPairingPolicyRejectionDoesNotStartPairedRecovery() {
        IdsBootstrapState state = new IdsBootstrapState();
        state.begin();
        assertEquals(IdsBootstrapState.Action.NONE, state.onPairedLanePolicyRejection());
        assertEquals(IdsBootstrapState.Action.NONE, state.onPairedKeyProbeCompleted());
    }


    @Test
    public void duplicatesAndUnrelatedEventsAreIdempotent() {
        IdsBootstrapState state =
                readyState();
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType.CONTROL_READY,
                        IdsIpsecServiceRoute.CONTROL_SERVICE));
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .DATA_CHANNEL_JOINED,
                        classDConnector()));
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .PROTOBUF_RECEIVED,
                        "unrelated"));
        assertTrue(
                state.ready());
    }

    @Test
    public void watchClassCBeforeClassDStillWaitsForBothLanes() {
        IdsBootstrapState state =
                new IdsBootstrapState();
        assertEquals(
                IdsBootstrapState.Action.START_CONTROL,
                state.begin());
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_D,
                state.observe(
                        IdsModernSessionCoordinator.EventType.CONTROL_READY,
                        IdsIpsecServiceRoute.CONTROL_SERVICE));
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .DATA_CHANNEL_JOINED,
                        classCConnector()));
        assertFalse(
                state.ready());
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .DATA_CHANNEL_JOINED,
                        classDConnector()));
        assertFalse(
                state.ready());
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .HANDSHAKE_RECEIVED,
                        classCConnector()));
        assertEquals(
                IdsBootstrapState.Action.READY,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .HANDSHAKE_RECEIVED,
                        classDConnector()));
        assertTrue(
                state.ready());
    }

    @Test
    public void defaultDefaultCJoinIsNotClassD() {
        IdsBootstrapState state =
                new IdsBootstrapState();
        state.begin();
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_D,
                state.observe(
                        IdsModernSessionCoordinator.EventType.CONTROL_READY,
                        IdsIpsecServiceRoute.CONTROL_SERVICE));
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .DATA_CHANNEL_JOINED,
                        "idstest/localdelivery/"
                                + IdsUtunConnectionName.defaultPaired(
                                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                                        IdsUtunConnectionName.PROTECTION_CLASS_C)));
        assertFalse(
                state.ready());
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .DATA_CHANNEL_JOINED,
                        classDConnector()));
        assertFalse(
                state.ready());
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .HANDSHAKE_RECEIVED,
                        "idstest/localdelivery/"
                                + IdsUtunConnectionName.defaultPaired(
                                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                                        IdsUtunConnectionName.PROTECTION_CLASS_C)));
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .HANDSHAKE_RECEIVED,
                        classDConnector()));
        assertEquals(
                IdsBootstrapState.Action.READY,
                state.observe(
                        IdsModernSessionCoordinator.EventType
                                .HANDSHAKE_RECEIVED,
                        classCConnector()));
        assertTrue(
                state.ready());
    }

    @Test
    public void outOfOrderAndClosedUseFailClosedSemantics() {
        IdsBootstrapState state =
                new IdsBootstrapState();
        state.begin();
        // Out-of-order Class-C before Class-D is accepted without throwing
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                        classCConnector()));

        state.close();
        assertThrows(
                IllegalStateException.class,
                state::begin);
        assertThrows(
                IllegalStateException.class,
                () -> state.observe(
                        IdsModernSessionCoordinator.EventType.CONTROL_READY,
                        IdsIpsecServiceRoute.CONTROL_SERVICE));
    }

    @Test
    public void policyRejectionStopsOnlyTheRequiredMissingBootstrapLane() {
        IdsBootstrapState state = new IdsBootstrapState();
        assertEquals(
                IdsBootstrapState.Action.START_CONTROL,
                state.beginPaired());
        assertFalse(state.requiredLaneRejected(classDConnector()));
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_D,
                state.observe(
                        IdsModernSessionCoordinator.EventType.CONTROL_READY,
                        IdsIpsecServiceRoute.CONTROL_SERVICE));
        assertTrue(state.requiredLaneRejected(classDConnector()));
        assertFalse(state.requiredLaneRejected(classCConnector()));
        assertFalse(state.requiredLaneRejected("unrelated"));
        state.observe(IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                classDConnector());
        assertFalse(state.requiredLaneRejected(classDConnector()));
        assertTrue(state.requiredLaneRejected(classCConnector()));
        state.observe(IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                classCConnector());
        assertFalse(state.requiredLaneRejected(classCConnector()));
    }

    @Test
    public void resumedUrgentJoinsStillWaitForFreshSocketHandshakes() {
        IdsBootstrapState state = new IdsBootstrapState();
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_D,
                openPairedAfterControlHello(state));
        state.observe(IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                classDConnector());
        state.observe(IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                classCConnector());
        assertFalse(state.ready());
        state.observe(IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED,
                classCConnector());
        assertFalse(state.ready());
        assertEquals(IdsBootstrapState.Action.READY,
                state.observe(IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED,
                        classDConnector()));
    }

    @Test
    public void beginPairedStartsControlBeforeClassDAndAcceptsConcreteLanes() {
        IdsBootstrapState state = new IdsBootstrapState();
        assertEquals(
                IdsBootstrapState.Action.START_CONTROL,
                state.beginPaired());
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_D,
                state.observe(
                        IdsModernSessionCoordinator.EventType.CONTROL_READY,
                        IdsIpsecServiceRoute.CONTROL_SERVICE));
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                        "UTunDelivery-Default-Default-C"));
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                        "UTunDelivery-Default-Default-D"));
        assertFalse(state.ready());
        assertEquals(
                IdsBootstrapState.Action.NONE,
                state.observe(
                        IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED,
                        "UTunDelivery-Default-Default-C"));
        assertEquals(
                IdsBootstrapState.Action.READY,
                state.observe(
                        IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED,
                        "UTunDelivery-Default-Default-D"));
        assertTrue(state.ready());
        assertEquals(
                IdsBootstrapState.Phase.READY,
                state.phase());
    }

    @Test
    public void concreteUtunHandshakesWithoutNanoAliasesSatisfyReady() {
        IdsBootstrapState state = new IdsBootstrapState();
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_D,
                openPairedAfterControlHello(state));
        state.observe(
                IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                "UTunDelivery-Default-Sync-D");
        state.observe(
                IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                "UTunDelivery-Default-Sync-C");
        assertFalse(state.ready());
        state.observe(
                IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED,
                "UTunDelivery-Default-Sync-D");
        assertFalse(state.ready());
        assertEquals(
                IdsBootstrapState.Action.READY,
                state.observe(
                        IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED,
                        "UTunDelivery-Default-Sync-C"));
        assertTrue(state.ready());
    }

    @Test
    public void directAlloyLanesOnReconnectSatisfyReadyImmediatelyWithoutLegacyHandshake() {
        IdsBootstrapState state = new IdsBootstrapState();
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_D,
                openPairedAfterControlHello(state));
        // Watch connects directly with com.apple.private.alloy.bluetoothregistry (Class D)
        assertEquals(
                IdsBootstrapState.Action.START_CLASS_C,
                state.observe(
                        IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                        NanoRegistryPropertyCodec.CLASS_D_SERVICE));
        assertFalse(state.ready());
        // Watch connects directly with com.apple.private.alloy.bluetoothregistryclassc (Class C)
        assertEquals(
                IdsBootstrapState.Action.READY,
                state.observe(
                        IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                        NanoRegistryPropertyCodec.CLASS_C_SERVICE));
        assertTrue(state.ready());
        assertEquals(IdsBootstrapState.Phase.READY, state.phase());
    }

    private static IdsBootstrapState.Action openPairedAfterControlHello(
            IdsBootstrapState state) {
        assertEquals(
                IdsBootstrapState.Action.START_CONTROL,
                state.beginPaired());
        return state.observe(
                IdsModernSessionCoordinator.EventType.CONTROL_READY,
                IdsIpsecServiceRoute.CONTROL_SERVICE);
    }

    private static IdsBootstrapState readyState() {
        IdsBootstrapState state =
                new IdsBootstrapState();
        state.begin();
        state.observe(
                IdsModernSessionCoordinator.EventType.CONTROL_READY,
                IdsIpsecServiceRoute.CONTROL_SERVICE);
        state.observe(
                IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                classDConnector());
        state.observe(
                IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                classCConnector());
        state.observe(
                IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED,
                classDConnector());
        state.observe(
                IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED,
                classCConnector());
        return state;
    }

    private static String classDConnector() {
        return NanoRegistryInitialIdsRoute
                .classDSetup()
                .serviceConnectorName
                .encode();
    }

    private static String classCConnector() {
        return NanoRegistryInitialIdsRoute
                .classCProperties()
                .serviceConnectorName
                .encode();
    }
}
