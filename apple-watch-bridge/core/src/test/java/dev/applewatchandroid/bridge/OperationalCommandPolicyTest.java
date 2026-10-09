package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class OperationalCommandPolicyTest {
    @Test public void rejectsSetupAndInjectedStdinLines() {
        assertFalse(OperationalCommandPolicy.isAllowed("FINISH_SETUP"));
        assertFalse(OperationalCommandPolicy.isAllowed("REDRIVE_ACTIVATION"));
        assertFalse(OperationalCommandPolicy.isAllowed("PIN:123456"));
        assertFalse(OperationalCommandPolicy.isAllowed("SET_ACTIVE_FACE:wayfinder\nRETRY_ACTIVATION"));
        assertFalse(OperationalCommandPolicy.isAllowed("SEND_BULLETIN:YWJj\rSTOP"));
        assertFalse(OperationalCommandPolicy.isAllowed("REMOVE_BULLETIN:YWJj\nFINISH_SETUP"));
        assertFalse(OperationalCommandPolicy.isAllowed("REMOVE_BULLETIN:"));
        assertFalse(OperationalCommandPolicy.isAllowed("SET_ACTIVE_FACE:\0"));
        assertFalse(OperationalCommandPolicy.isAllowed("SET_ACTIVE_FACE:"));
        assertFalse(OperationalCommandPolicy.isAllowed(null));
        assertFalse(OperationalCommandPolicy.isAllowed("SEND_BULLETIN:" + "A".repeat(180_000)));
    }
    @Test public void acceptsBoundedOrdinaryCommandsAndBoundsReconnectBackoff() {
        assertTrue(OperationalCommandPolicy.isAllowed("PING_WATCH\n"));
        assertTrue(OperationalCommandPolicy.isAllowed("SET_ACTIVE_FACE:3AE33A93-96BD-422A-AA56-C3E312ABABCD"));
        assertTrue(OperationalCommandPolicy.isAllowed("SEND_BULLETIN:YWJjZA=="));
        assertTrue(OperationalCommandPolicy.isAllowed("REMOVE_BULLETIN:YWJjZA=="));
        assertEquals(15_000L, OperationalCommandPolicy.reconnectDelayMs(1));
        assertEquals(30_000L, OperationalCommandPolicy.reconnectDelayMs(2));
        assertEquals(60_000L, OperationalCommandPolicy.reconnectDelayMs(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> OperationalCommandPolicy.reconnectDelayMs(0));
    }
    @Test public void diagnosticInventoryDoesNotExposeCollectionFetchOrRawProtobuf() {
        assertTrue(OperationalCommandPolicy.isAllowed(SysdiagnoseArchiveInventory.COMMAND));
        assertTrue(OperationalCommandPolicy.isAllowed(SysdiagnoseCollection.COMMAND));
        assertFalse(OperationalCommandPolicy.isAllowed(SysdiagnoseCollection.COMMAND + ":flags"));
        assertFalse(OperationalCommandPolicy.isAllowed(SysdiagnoseArchiveInventory.COMMAND + ":archive"));
        assertFalse(OperationalCommandPolicy.isAllowed(SysdiagnoseArchiveInventory.COMMAND + "\nSTOP"));
        assertFalse(OperationalCommandPolicy.isAllowed("SEND_APP_PROTOBUF:com.apple.private.alloy.sysdiagnose:1:YWJj"));
        assertFalse(OperationalCommandPolicy.isAllowed("FETCH_DIAGNOSTIC_ARCHIVE"));
    }
}
