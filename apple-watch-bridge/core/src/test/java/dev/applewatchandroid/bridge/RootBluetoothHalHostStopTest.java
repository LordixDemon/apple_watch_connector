package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RootBluetoothHalHostStopTest {
    @Test
    public void stopDuringLiveHoldLeavesTheAclForCleanup() {
        assertFalse(ReconnectStopPolicy.tearDownIncompleteReconnect(false, true));
        assertTrue(ReconnectStopPolicy.tearDownIncompleteReconnect(false, false));
        assertFalse(ReconnectStopPolicy.tearDownIncompleteReconnect(true, false));
        assertFalse(ReconnectStopPolicy.tearDownIncompleteReconnect(true, true));
    }
}
