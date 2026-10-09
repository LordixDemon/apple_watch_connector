package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.applewatchandroid.bridge.SetupProgressState.Phase.*;

public class SetupProgressStateTest {
    @Test public void repeatedTransportEventsCannotRegressObservedSetup() {
        var progress = new SetupProgressState();
        progress.reset();
        for (var phase : new SetupProgressState.Phase[]{DISCOVERING, CONNECTING, PIN_REQUIRED,
                SECURITY, IDS, REGISTRY, CONFIGURING, ACTIVATING, ACTIVATION_INPUT,
                ACTIVATING, ACTIVATED, SYNCING, WAITING_FOR_WATCH}) progress.observe(phase);
        assertEquals(WAITING_FOR_WATCH, progress.phase());
        for (var late : new SetupProgressState.Phase[]{DISCOVERING, SECURITY, REGISTRY, ACTIVATING}) {
            assertFalse(progress.observe(late));
        }
        assertEquals(WAITING_FOR_WATCH, progress.phase());
        assertNotEquals(VERIFIED, progress.phase());
    }
    @Test public void stopAndFailureAreTerminalUntilNewOwnerStarts() {
        var progress = new SetupProgressState();
        progress.reset(); progress.observe(ACTIVATING); progress.observe(STOPPING);
        assertFalse(progress.observe(ACTIVATED));
        assertTrue(progress.observe(FAILED));
        assertFalse(progress.observe(WAITING_FOR_WATCH));
        progress.reset(); progress.observe(STOPPING); progress.observe(STOPPED);
        assertFalse(progress.observe(SYNCING));
        progress.ownerConfirmed(); assertEquals(VERIFIED, progress.phase());
        progress.reset(); assertEquals(STARTING, progress.phase());
    }
    @Test public void publicProgressFrameIsBoundedAndStrict() {
        assertEquals(SYNCING, SetupProgressState.decode(SetupProgressState.encode(SYNCING)));
        for (String value : new String[]{"SYNCING", SetupProgressState.PREFIX + "SYNCING\nFAILED",
                SetupProgressState.PREFIX + "syncing", SetupProgressState.PREFIX + "x".repeat(100)}) {
            try { SetupProgressState.decode(value); fail("Malformed progress accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
