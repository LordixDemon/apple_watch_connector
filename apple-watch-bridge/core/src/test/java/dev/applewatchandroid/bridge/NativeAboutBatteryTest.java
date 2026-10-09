package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeAboutBatteryTest {
    @Test public void onlyNativeAboutResponseCanPublishPercentAndUnknownChargingStaysNullable() {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        dispatcher.updateConnectionState(false, "test");
        try {
            for (boolean response : new boolean[]{false, true}) {
                try (var event = new BridgeApplicationEventCodec.Event("wrong.topic", 6, response, new byte[]{40, 92, 48, 1})) {
                    dispatcher.dispatchApplicationMessage(event);
                    assertEquals(-1, dispatcher.getBatteryPercentage());
                }
            }
            try (var event = new BridgeApplicationEventCodec.Event(NanoSystemSettingsDiagnostics.TOPIC, 6, false,
                    new byte[]{40, 92, 48, 1})) {
                dispatcher.dispatchApplicationMessage(event);
                assertEquals(-1, dispatcher.getBatteryPercentage());
            }
            try (var event = new BridgeApplicationEventCodec.Event(NanoSystemSettingsDiagnostics.TOPIC, 6, true,
                    new byte[]{40, 92})) {
                dispatcher.dispatchApplicationMessage(event);
                assertEquals(92, dispatcher.getBatteryPercentage());
                assertNull(dispatcher.batteryObservation().charging());
                assertTrue(dispatcher.batteryObservation().observedAt() > 0);
            }
            try (var event = new BridgeApplicationEventCodec.Event(NanoSystemSettingsDiagnostics.TOPIC, 6, true,
                    new byte[]{40, 50, 48, 2})) {
                dispatcher.dispatchApplicationMessage(event);
                assertEquals(92, dispatcher.getBatteryPercentage());
            }
            dispatcher.observeAbout(NanoSystemSettingsDiagnostics.decodeAbout(new byte[]{40, 101, 48, 0}), 123);
            assertEquals(-1, dispatcher.getBatteryPercentage());
            assertEquals(Boolean.FALSE, dispatcher.batteryObservation().charging());
            assertEquals(123, dispatcher.batteryObservation().observedAt());
            dispatcher.observeAbout(NanoSystemSettingsDiagnostics.decodeAbout(new byte[]{40, 0, 48, 1}), 124);
            assertEquals(0, dispatcher.getBatteryPercentage());
            assertTrue(dispatcher.isCharging());
            dispatcher.updateConnectionState(false, "test");
            assertEquals(-1, dispatcher.getBatteryPercentage());
            assertNull(dispatcher.batteryObservation().charging());
            assertEquals(0, dispatcher.batteryObservation().observedAt());
        } finally { dispatcher.updateConnectionState(false, "test"); }
    }

    @Test public void requestSurfaceIsAnExactReadOnlyCommand() {
        assertTrue(OperationalCommandPolicy.isAllowed("REQUEST_DEVICE_ABOUT"));
        for (String command : new String[]{"REQUEST_DEVICE_ABOUT:payload", "REQUEST_DEVICE_ABOUT\nSETUP",
                "REQUEST_DEVICE_ABOUT\0", "REQUEST_DEVICE_ABOUT 5"}) {
            assertFalse(OperationalCommandPolicy.isAllowed(command));
        }
    }
}
