package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class BridgeIpcDispatcherTest {
    @Test public void onlyObservedDeviceStateIsPublishedAndOneConsumerCannotSuppressAnother() {
        BridgeIpcDispatcher dispatcher = BridgeIpcDispatcher.getInstance();
        List<Integer> batteries = new ArrayList<>();
        List<String> faces = new ArrayList<>();
        var stateListener = new BridgeIpcDispatcher.OnDeviceStateListener() {
            public void onConnectionStateChanged(boolean connected, String name) {}
            public void onBatteryChanged(int percent, boolean charging) { batteries.add(percent); }
            public void onActiveFaceChanged(String face) { faces.add(face); }
        };
        dispatcher.addStateListener(stateListener);
        try {
            assertEquals(-1, dispatcher.getBatteryPercentage());
            assertNull(dispatcher.getCurrentFaceId());
            assertTrue(batteries.isEmpty());
            assertTrue(faces.isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> dispatcher.createActiveFaceSyncPayload("requested-face"));
            assertNull(dispatcher.getCurrentFaceId());
            assertTrue(faces.isEmpty());
            assertThrows(IllegalArgumentException.class, () -> dispatcher.updateBattery(101, false));
            dispatcher.updateBattery(42, false);
            assertEquals(List.of(42), batteries);
        } finally { dispatcher.removeStateListener(stateListener); }

        List<Integer> received = new ArrayList<>();
        BridgeIpcDispatcher.OnApplicationMessageListener failing = event -> { throw new IllegalStateException(); };
        BridgeIpcDispatcher.OnApplicationMessageListener succeeding = event -> received.add(event.protobufType);
        dispatcher.addApplicationListener(failing);
        dispatcher.addApplicationListener(succeeding);
        dispatcher.addApplicationListener(succeeding);
        try (var event = new BridgeApplicationEventCodec.Event("test.topic", 7, false, new byte[]{1})) {
            dispatcher.dispatchApplicationMessage(event);
            assertEquals(List.of(7), received);
        } finally {
            dispatcher.removeApplicationListener(failing);
            dispatcher.removeApplicationListener(succeeding);
        }
    }
}
