package dev.applewatchandroid.bridge;

import java.util.List;

/** One bounded NPS parse shared by native color and monogram projections.
 * Values/keys are borrowed until close; projections must retain only typed data. */
final class NtkPreferenceEnvelope implements AutoCloseable {
    final PairedSyncCodec.UserDefaultsMessage message;
    final List<PairedSyncCodec.UserDefaultsKey> keys;
    private final double latestTimestamp;

    private NtkPreferenceEnvelope(PairedSyncCodec.UserDefaultsMessage message, long unixMillis) {
        this.message = message;
        latestTimestamp = unixMillis / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS + 5;
        if (unixMillis <= 0 || !validTimestamp(message.timestamp)) {
            throw new IllegalArgumentException("Invalid native preference report timestamp");
        }
        keys = message.keys();
    }

    static NtkPreferenceEnvelope decode(byte[] payload, long unixMillis) {
        var message = PairedSyncCodec.decodeInbound(payload);
        try { return new NtkPreferenceEnvelope(message, unixMillis); }
        catch (RuntimeException invalid) { message.destroy(); throw invalid; }
    }

    boolean nativeDomain() { return PigmentPreferenceCodec.DOMAIN.equals(message.domain); }
    boolean validTimestamp(double timestamp) {
        return Double.isFinite(timestamp) && timestamp >= 0 && timestamp <= latestTimestamp;
    }
    double timestamp(PairedSyncCodec.UserDefaultsKey key) {
        return key.timestamp == null ? message.timestamp : key.timestamp;
    }

    @Override public void close() {
        message.destroy();
        keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy);
    }
}
