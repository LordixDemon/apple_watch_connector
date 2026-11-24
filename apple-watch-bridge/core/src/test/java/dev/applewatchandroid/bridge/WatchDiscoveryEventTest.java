package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchDiscoveryEventTest {
    private static final String EVENT =
            "WATCH_DISCOVERED_V1:453e5743-08ed-42b7-8b18-8736444471ca:Watch6,10:26.6.0:-54";

    @Test public void findsEventBehindHelperLogPrefix() {
        assertEquals(EVENT, WatchDiscoveryEvent.find("[WatchHal] [T+18149ms|Δ1007ms] " + EVENT));
        assertEquals(5, WatchDiscoveryEvent.find("[WatchHal] [T+18149ms|Δ1007ms] " + EVENT).split(":").length);
    }

    @Test public void findsUnprefixedEvent() {
        assertEquals(EVENT, WatchDiscoveryEvent.find(EVENT));
    }

    @Test public void ignoresOtherLines() {
        assertNull(WatchDiscoveryEvent.find("[WatchHal] [T+00633ms|Δ0000ms] LE SCAN ACTIVE: FE25/subtype 06"));
        assertNull(WatchDiscoveryEvent.find(""));
        assertNull(WatchDiscoveryEvent.find(null));
    }
}
