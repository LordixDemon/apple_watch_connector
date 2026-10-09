package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static dev.applewatchandroid.bridge.CompanionConnectionPolicy.Decision.*;

public class CompanionConnectionPolicyTest {
    private CompanionConnectionPolicy.State state(boolean pair, boolean activated, boolean eligible,
            boolean matching, boolean operational, boolean setup) {
        return new CompanionConnectionPolicy.State(true, true, pair, activated, eligible, matching, operational, setup);
    }
    @Test public void emptyStoreCanPairButCannotConnectOrConfirm() {
        var empty = state(false, false, false, false, false, false);
        assertEquals(START_SETUP, CompanionConnectionPolicy.decide("beginPairing", empty));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("connectWatch", empty));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("confirmSetup", empty));
    }
    @Test public void existingPairCannotBeReplacedEvenWhenDisconnected() {
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("beginPairing", state(true, true, true, true, false, false)));
    }
    @Test public void opticalReplacementRequiresExactSavedPairAndExclusiveIdleOwner() {
        assertEquals(START_SETUP, CompanionConnectionPolicy.decide("beginOpticalPairing", state(false, false, false, false, false, false)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("beginOpticalPairing", state(true, true, true, true, false, false)));
        assertEquals(START_SETUP, CompanionConnectionPolicy.decide("replacePairOptically", state(true, true, true, true, false, false)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("replacePairOptically", state(true, true, true, false, false, false)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("replacePairOptically", state(false, false, false, false, false, false)));
        assertEquals(BUSY, CompanionConnectionPolicy.decide("replacePairOptically", state(true, true, true, true, true, false)));
        assertEquals(BUSY, CompanionConnectionPolicy.decide("replacePairOptically", state(true, true, true, true, false, true)));
    }
    @Test public void partialPairResumesOnlyTheSameIdentity() {
        assertEquals(START_SETUP, CompanionConnectionPolicy.decide("resumeSetup", state(true, true, false, true, false, false)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("resumeSetup", state(true, true, false, false, false, false)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("connectWatch", state(true, true, false, true, false, false)));
    }
    @Test public void normalModeRequiresOwnerConfirmationAndExactPair() {
        assertEquals(START_OPERATIONAL, CompanionConnectionPolicy.decide("connectWatch", state(true, true, true, true, false, false)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("connectWatch", state(true, true, true, false, false, false)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("resumeSetup", state(true, true, true, true, false, false)));
    }
    @Test public void visibleFaceCanBeConfirmedDuringTheSameActivatedSetup() {
        assertEquals(FORWARD_SETUP, CompanionConnectionPolicy.decide("confirmSetup", state(true, true, false, true, false, true)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("confirmSetup", state(true, false, false, true, false, true)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("confirmSetup", state(true, true, false, false, false, true)));
        assertEquals(BUSY, CompanionConnectionPolicy.decide("confirmSetup", state(true, true, true, true, true, false)));
    }
    @Test public void setupInputNeverEntersOperationalHal() {
        for (String method : new String[]{"submitPin", "selectDiscoveredWatch", "activationResponse"}) {
            assertEquals(REJECTED, CompanionConnectionPolicy.decide(method, state(true, true, true, true, true, false)));
            assertEquals(FORWARD_SETUP, CompanionConnectionPolicy.decide(method, state(true, false, false, true, false, true)));
            assertEquals(REJECTED, CompanionConnectionPolicy.decide(method, state(true, false, false, true, false, false)));
        }
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("finishSetup", state(true, false, false, true, false, true)));
        assertEquals(REJECTED, CompanionConnectionPolicy.decide("finishSetup", state(true, true, true, true, true, false)));
    }
    @Test public void inFlightStartOrStopPreventsAnotherOwner() {
        assertEquals(BUSY, CompanionConnectionPolicy.decide("beginPairing", state(false, false, false, false, false, true)));
        assertEquals(BUSY, CompanionConnectionPolicy.decide("connectWatch", state(true, true, true, true, true, false)));
        assertEquals(STOP_SESSION, CompanionConnectionPolicy.decide("disconnectWatch", state(true, true, true, true, true, false)));
    }
    @Test public void unreadableStoreCannotBeTreatedAsEmpty() {
        assertEquals(UNAVAILABLE, CompanionConnectionPolicy.decide("beginPairing",
                new CompanionConnectionPolicy.State(true, false, false, false, false, false, false, false)));
    }
    @Test public void stopDoesNotRequirePermissionButStartDoes() {
        var denied = new CompanionConnectionPolicy.State(false, true, true, true, true, true, true, false);
        assertEquals(STOP_SESSION, CompanionConnectionPolicy.decide("disconnectWatch", denied));
        assertEquals(PERMISSION_REQUIRED, CompanionConnectionPolicy.decide("connectWatch", denied));
    }
}
