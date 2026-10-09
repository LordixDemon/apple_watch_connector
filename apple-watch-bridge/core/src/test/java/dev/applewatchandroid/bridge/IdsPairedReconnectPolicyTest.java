package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IdsPairedReconnectPolicyTest {
    @Test
    public void savedCredentialsResumeOnlyAnActivatedAuthenticatedSamePair() {
        var activated = PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED;
        assertTrue(IdsPairedReconnectPolicy.allowSavedDeviceInfoForPostCommit(activated, true, false, true, true));
        assertFalse(IdsPairedReconnectPolicy.allowSavedDeviceInfoForPostCommit(null, true, false, true, true));
        assertFalse(IdsPairedReconnectPolicy.allowSavedDeviceInfoForPostCommit(
                PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED, true, false, true, true));
        assertFalse(IdsPairedReconnectPolicy.allowSavedDeviceInfoForPostCommit(activated, false, false, true, true));
        assertFalse(IdsPairedReconnectPolicy.allowSavedDeviceInfoForPostCommit(activated, true, true, true, true));
        assertFalse(IdsPairedReconnectPolicy.allowSavedDeviceInfoForPostCommit(activated, true, false, false, true));
        assertFalse(IdsPairedReconnectPolicy.allowSavedDeviceInfoForPostCommit(activated, true, false, true, false));
    }

    @Test
    public void interruptedSetupRenegotiatesControlEvenAfterPreviouslyReadyData() {
        assertFalse(IdsPairedReconnectPolicy.usePairedDataBootstrap(null));
        assertFalse(IdsPairedReconnectPolicy.usePairedDataBootstrap(
                PairingSessionRecord.DurableState.IDS_CONTROL_READY));
        assertFalse(IdsPairedReconnectPolicy.usePairedDataBootstrap(
                PairingSessionRecord.DurableState.IDS_DATA_READY));
        assertFalse(IdsPairedReconnectPolicy.usePairedDataBootstrap(
                PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED));
        assertTrue(IdsPairedReconnectPolicy.usePairedDataBootstrap(
                PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED));
    }
    @Test
    public void pairingServiceCannotBeUsedAsNormalLinkFallback() {
        org.junit.Assert.assertEquals(IdsPairedReconnectPolicy.AdvertisedMode.SETUP_ONLY,
                IdsPairedReconnectPolicy.advertisedMode(java.util.List.of(1)));
        org.junit.Assert.assertEquals(IdsPairedReconnectPolicy.AdvertisedMode.NORMAL,
                IdsPairedReconnectPolicy.advertisedMode(java.util.List.of(1, 2)));
        org.junit.Assert.assertEquals(IdsPairedReconnectPolicy.AdvertisedMode.UNSUPPORTED,
                IdsPairedReconnectPolicy.advertisedMode(java.util.List.of()));
        // CONTROL_SILENCE_BOUNCE_MAX=0 since 0.2.298: bounce tore down an
        // already SYN-ACK'd Urgent-D before the 20s NWSC timer; policy now
        // waits for Watch control or a REJECTED_BY_POLICY instead.
        assertFalse(IdsPairedReconnectPolicy.allowControlSilenceBounce(0, false));
        assertFalse(IdsPairedReconnectPolicy.allowControlSilenceBounce(1, false));
        assertFalse(IdsPairedReconnectPolicy.allowControlSilenceBounce(2, false));
        assertFalse(IdsPairedReconnectPolicy.allowControlSilenceBounce(0, true));
    }

    @Test
    public void policyRejectNeverRetriesControlSyn() {
        assertFalse(
                IdsPairedReconnectPolicy.allowReconnectAfterDisconnect(
                        true,
                        true,
                        true));
    }

    @Test
    public void bootstrapWithoutHelloDoesNotReconnect() {
        assertFalse(
                IdsPairedReconnectPolicy.allowReconnectAfterDisconnect(
                        false,
                        false,
                        true));
    }

    @Test
    public void unpairedIdentityHoldDoesNotReconnect() {
        assertFalse(
                IdsPairedReconnectPolicy.allowReconnectAfterDisconnect(
                        false,
                        true,
                        false));
    }

    @Test
    public void postCommitAfterHelloMayReconnect() {
        assertTrue(
                IdsPairedReconnectPolicy.allowReconnectAfterDisconnect(
                        false,
                        true,
                        true));
    }
}
