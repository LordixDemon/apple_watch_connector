package dev.applewatchandroid.bridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

/** Verifies that {@link ProtocolHost#resetSessionControls()} clears all process-wide state. */
public final class ProtocolHostControlsTest {
    @Before
    public void setUp() {
        ProtocolHost.resetSessionControls();
    }

    @After
    public void tearDown() {
        ProtocolHost.resetSessionControls();
    }

    @Test
    public void resetSessionControlsClearsAllFlagsAndQueues() {
        ProtocolHost.COMPANION_DISCOVERY.set(true);
        ProtocolHost.EXPERIMENTAL_LEGACY_PAIRING.set(true);
        ProtocolHost.COMPANION_WATCH_SELECTIONS.offer("AA:BB:CC:DD:EE:FF");
        ProtocolHost.PENDING_STALE_FLOW_SEEDS.offer(
                new ProtocolHost.PendingStaleFlowSeed(OrdinaryIkeAuth.DataClass.CLASS_C, 1, 2, 3L, 4L, 5L));
        ProtocolHost.OPERATIONAL_REQUESTS.newEpoch();
        ProtocolHost.FIND_MY_PHONE_RESULTS.offer(
                new FindMyPhoneIpcCodec.Result(UUID.randomUUID(), UUID.randomUUID().toString(), 2, true));
        ProtocolHost.requestForceActivationConfirmed();
        ProtocolHost.requestPublishPairedSync();
        ProtocolHost.PENDING_CLOSE_IKE_SESSION.set(true);
        ProtocolHost.PENDING_IDS_KEY_PROBE.set(true);
        ProtocolHost.PENDING_IDS_CONTROL_OPEN.set(true);
        ProtocolHost.requestSyncProgress(0.5, 1);
        ProtocolHost.requestRedriveActivation();
        ProtocolHost.requestForceSetupObserved();
        ProtocolHost.requestRetryActivation();

        ProtocolHost.resetSessionControls();

        assertFalse(ProtocolHost.COMPANION_DISCOVERY.get());
        assertFalse(ProtocolHost.EXPERIMENTAL_LEGACY_PAIRING.get());
        assertTrue(ProtocolHost.COMPANION_WATCH_SELECTIONS.isEmpty());
        assertTrue(ProtocolHost.PENDING_STALE_FLOW_SEEDS.isEmpty());
        assertTrue(ProtocolHost.FIND_MY_PHONE_RESULTS.isEmpty());
        assertFalse(ProtocolHost.PENDING_FORCE_ACTIVATION_CONFIRMED.get());
        assertFalse(ProtocolHost.PENDING_PUBLISH_PAIRED_SYNC.get());
        assertFalse(ProtocolHost.PENDING_CLOSE_IKE_SESSION.get());
        assertFalse(ProtocolHost.PENDING_IDS_KEY_PROBE.get());
        assertFalse(ProtocolHost.PENDING_IDS_CONTROL_OPEN.get());
        assertNull(ProtocolHost.PENDING_NATIVE_SNAPSHOT_DISCOVERY.get());
        assertTrue(ProtocolHost.PENDING_SYNC_PROGRESS.isEmpty());
        assertFalse(ProtocolHost.PENDING_REDRIVE_ACTIVATION.get());
        assertFalse(ProtocolHost.PENDING_FORCE_SETUP_OBSERVED.get());
        assertFalse(ProtocolHost.PENDING_RETRY_ACTIVATION.get());
    }
}
