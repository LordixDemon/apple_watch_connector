package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeWatchRebootTest {
    @Test public void setupAndPendingMutationCannotConsumeOrSendReboot() {
        NativeWatchReboot reboot = new NativeWatchReboot();
        assertThrows(IllegalStateException.class, () -> reboot.begin(false, false));
        assertThrows(IllegalStateException.class, () -> reboot.begin(true, true));
        reboot.begin(true, false);
        assertThrows(IllegalStateException.class, () -> reboot.begin(true, false));
    }

    @Test public void rebootCannotEnterGenericReadOnlyNssOrRawCommandPath() {
        assertTrue(OperationalCommandPolicy.isAllowed(NativeWatchReboot.COMMAND));
        assertFalse(OperationalCommandPolicy.isAllowed("REBOOT_WATCH:19"));
        assertFalse(OperationalCommandPolicy.isAllowed("REBOOT_WATCH\nFINISH_SETUP"));
        assertFalse(OperationalCommandPolicy.isAllowed("ERASE_WATCH"));
        assertThrows(IllegalArgumentException.class, () ->
                NanoSystemSettingsDiagnostics.requireReadOnlyRequest(NativeWatchReboot.TYPE, new byte[0]));
        assertThrows(IllegalArgumentException.class, () ->
                NanoSystemSettingsDiagnostics.requireReadOnlyRequest(8, new byte[0]));
    }
}
