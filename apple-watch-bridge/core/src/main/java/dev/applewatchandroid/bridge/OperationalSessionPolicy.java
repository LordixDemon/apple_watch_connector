package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/** Verified native completion or same-pair owner confirmation controls setup replay. */
final class OperationalSessionPolicy {
    private OperationalSessionPolicy() {}

    static String pairingId(PairingSessionRecord record) {
        byte[] generation = record.generationUuid();
        try {
            ByteBuffer bytes = ByteBuffer.wrap(generation);
            return new UUID(bytes.getLong(), bytes.getLong()).toString();
        } finally {
            Arrays.fill(generation, (byte) 0);
        }
    }

    static boolean mayUseOperationalMode(PairingSessionRecord.DurableState state,
            String recordPairing, String ownerConfirmedPairing, boolean freshPairing) {
        return mayUseOperationalMode(state, recordPairing, ownerConfirmedPairing, freshPairing, false);
    }

    static boolean mayUseOperationalMode(PairingSessionRecord.DurableState state,
            String recordPairing, String ownerConfirmedPairing, boolean freshPairing,
            boolean observedSetupEvidence) {
        if (freshPairing || state == null
                || state.wireValue() < PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()
                || recordPairing == null) return false;
        try {
            String pair = HalHostArguments.canonicalUuid(recordPairing);
            if (observedSetupEvidence
                    && state == PairingSessionRecord.DurableState.OPERATIONAL_HEALTH_CONFIRMED) return true;
            return ownerConfirmedPairing != null
                    && pair.equals(HalHostArguments.canonicalUuid(ownerConfirmedPairing));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static void requireMatchingActivatedPair(PairingSessionRecord record, String pairing) {
        if (record == null || !pairingId(record).equals(pairing)
                || !mayUseOperationalMode(record.state(), pairingId(record), pairing, false)) {
            throw new IllegalStateException("Operational mode requires the confirmed activated pairing");
        }
    }
}
