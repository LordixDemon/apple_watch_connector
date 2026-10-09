package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DeviceAboutSnapshotTest {
    @Test public void entireNativeSnapshotReplacesOldCountsAndClearsAtDisconnect() {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        dispatcher.updateConnectionState(false, "test");
        try {
            var about = NanoSystemSettingsDiagnostics.decodeAbout(new byte[]{8, 100, 16, 3,
                    24, 0, 32, 2, 40, 90, 48, 1, 56, 5});
            dispatcher.observeAbout(about, 12345);
            var snapshot = dispatcher.deviceObservation();
            assertSame(about, snapshot.about());
            assertEquals(Long.valueOf(100), snapshot.about().availableStorageBytes());
            assertEquals(Long.valueOf(0), snapshot.about().songs());
            assertNull(snapshot.about().userDeletableBytes());
            assertEquals(Integer.valueOf(90), snapshot.battery().percentage());
            assertEquals(12345, snapshot.battery().observedAt());
            dispatcher.observeAbout(NanoSystemSettingsDiagnostics.decodeAbout(new byte[]{40, 80}), 12346);
            assertNull(dispatcher.deviceObservation().about().availableStorageBytes());
            assertNull(dispatcher.deviceObservation().battery().charging());
            assertEquals(Integer.valueOf(80), dispatcher.deviceObservation().battery().percentage());
            dispatcher.updateConnectionState(false, "test");
            assertNull(dispatcher.deviceObservation().about());
            assertNull(dispatcher.deviceObservation().battery().percentage());
        } finally { dispatcher.updateConnectionState(false, "test"); }
    }
}
