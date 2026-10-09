package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneFindObservationTest {
    @Test public void noneAndFailedStartCannotBecomeAnActiveSignal() {
        assertThrows(IllegalArgumentException.class, () -> new PhoneFindObservation(true, 4, false, true, 100, null));
        assertThrows(IllegalArgumentException.class, () -> new PhoneFindObservation(true, 0, false, false, 100, null));
        assertThrows(IllegalArgumentException.class, () -> new PhoneFindObservation(true, 0, false, true, 100, "stopped"));
        assertThrows(IllegalArgumentException.class, () -> new PhoneFindObservation(false, 5, false, false, 100, null));
        assertThrows(IllegalArgumentException.class, () -> new PhoneFindObservation(false, 0, false, false, 0, null));
        assertFalse(new PhoneFindObservation(false, 4, false, true, 100, null).active());
    }
    @Test public void actualStopPreservesSourceAndOriginalStartResult() {
        var start = new PhoneFindObservation(true, 1, true, true, 100, null);
        var stop = start.stopped(200, "Companion stop action");
        assertFalse(stop.active()); assertTrue(stop.didPlay()); assertTrue(stop.localProbe());
        assertEquals(1, stop.behavior()); assertEquals(200, stop.observedAt());
        assertEquals("Companion stop action", stop.stopReason());
    }
    @Test public void phoneAndWatchObservationsStaySeparateAndBadSubscriberIsIsolated() {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        int[] updates = {0};
        BridgeIpcDispatcher.OnPhoneFindStateListener bad = () -> { throw new IllegalStateException(); };
        BridgeIpcDispatcher.OnPhoneFindStateListener good = () -> updates[0]++;
        dispatcher.addPhoneFindListener(bad); dispatcher.addPhoneFindListener(good);
        try {
            var start = new PhoneFindObservation(true, 0, false, true, 100, null);
            dispatcher.observePhoneFind(start);
            assertEquals(start, dispatcher.phoneFindObservation()); assertEquals(1, updates[0]);
            dispatcher.updateConnectionState(false, "Watch");
            assertEquals(start, dispatcher.phoneFindObservation()); // Android owner publishes its actual stop separately.
            dispatcher.observePhoneFind(start.stopped(200, "transport disconnected"));
            assertFalse(dispatcher.phoneFindObservation().active()); assertEquals(2, updates[0]);
            dispatcher.observePhoneFind(null); assertNull(dispatcher.phoneFindObservation()); assertEquals(3, updates[0]);
        } finally {
            dispatcher.removePhoneFindListener(bad); dispatcher.removePhoneFindListener(good);
            dispatcher.observePhoneFind(null);
        }
    }
}
