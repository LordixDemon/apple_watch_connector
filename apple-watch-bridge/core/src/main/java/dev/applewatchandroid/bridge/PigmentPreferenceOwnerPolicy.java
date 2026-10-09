package dev.applewatchandroid.bridge;

import java.util.UUID;

/** Capture authenticated native preferences after IsPaired commit, including setup.
 * This authorizes a local read receipt, never a native preference write. */
final class PigmentPreferenceOwnerPolicy {
    private PigmentPreferenceOwnerPolicy() { }
    static UUID owner(PairingSessionRecord record, String operationalPairing, boolean controlReady) {
        if (record == null) return null;
        return owner(UUID.fromString(OperationalSessionPolicy.pairingId(record)), record.state(),
                record.idsAuthenticationAccepted(), operationalPairing, controlReady);
    }
    static UUID owner(UUID generation, PairingSessionRecord.DurableState state,
                      boolean idsAuthenticated, String operationalPairing, boolean controlReady) {
        if (generation == null || state == null || !idsAuthenticated || !controlReady
                || state.wireValue() < PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED.wireValue()) return null;
        if (operationalPairing != null && !OperationalSessionPolicy.mayUseOperationalMode(
                state, generation.toString(), operationalPairing, false)) return null;
        return generation;
    }
}
