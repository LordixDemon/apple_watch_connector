package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchDiscoverySelectionTest {
    @Test public void refreshPreservesTokenAndSelectsLatestVerifiedReport() {
        var choices = new WatchDiscoverySelection<String>();
        String token = choices.observe("private address", "first report", 1);
        assertEquals(token, choices.observe("private address", "new report", 100));
        assertEquals("new report", choices.select(token, 200));
        assertFalse(token.contains("address"));
    }
    @Test public void staleUnknownAndFutureReportsCannotBeSelected() {
        var choices = new WatchDiscoverySelection<String>();
        String token = choices.observe("a", "peer", 100);
        assertNull(choices.select(token, 5_101));
        assertNull(choices.select(token, 99));
        assertNull(choices.select("unknown", 100));
    }
    @Test public void boundedInventoryAndSessionTokensPreventCrossSessionSelection() {
        var choices = new WatchDiscoverySelection<Integer>();
        String first = choices.observe("0", 0, 1);
        for (int index = 1; index <= 8; index++) choices.observe("" + index, index, 1);
        assertNull(choices.select(first, 1));
        var next = new WatchDiscoverySelection<Integer>();
        next.observe("0", 0, 1);
        assertNull(next.select(first, 1));
    }
}
