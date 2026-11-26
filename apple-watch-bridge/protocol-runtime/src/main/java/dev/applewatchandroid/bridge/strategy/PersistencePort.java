package dev.applewatchandroid.bridge.strategy;

import dev.applewatchandroid.bridge.PairingSessionRecord;

/**
 * Durable secret/session storage port injected into {@link PairingStrategy} plugins.
 * Implementations must enforce fail-closed atomic persistence before returning.
 */
public interface PersistencePort {
    /** Persists a validated 75-byte {@code AWB1} Bluetooth bond record or throws. */
    void persistBondSecretOrFail(byte[] bondSecretRecord) throws Exception;

    /** Persists a validated {@code AWP2} pairing session record or throws. */
    void persistPairingSessionOrFail(PairingSessionRecord record) throws Exception;

    /** Persists a replacement record and atomically updates the active session owner. */
    void persistAndReplaceActivePairingSession(PairingSessionRecord replacement) throws Exception;
}
