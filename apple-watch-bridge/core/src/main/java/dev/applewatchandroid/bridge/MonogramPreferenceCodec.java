package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** NTKCustomMonogramStore's string preference, independent of face complications. */
final class MonogramPreferenceCodec {
    static final String DOMAIN = "com.apple.NanoTimeKit", KEY = "customMonogram";
    static final int MAX_VALUE_BYTES = 1024;
    record Report(String text, double sourceTimestamp) {
        Report {
            if (text != null && !NativeMonogramTextRules.valid(text)
                    || !Double.isFinite(sourceTimestamp) || sourceTimestamp < 0) {
                throw new IllegalArgumentException("Invalid native monogram report");
            }
        }
    }
    private MonogramPreferenceCodec() { }

    static boolean isPairedPreference(String domain, String key) {
        return DOMAIN.equals(domain) && KEY.equals(key);
    }

    static boolean isObservationEnvelope(String topic, int type, boolean response) {
        return !response && type == PairedSyncCodec.PROTOBUF_TYPE_USER_DEFAULTS && PairedSyncCodec.isService(topic);
    }

    /** A deletion means native default/unknown custom text; malformed data is ignored. */
    static List<Report> decodeObserved(byte[] payload, long unixMillis) {
        try (var envelope = NtkPreferenceEnvelope.decode(payload, unixMillis)) {
            return decodeObserved(envelope);
        }
    }

    static List<Report> decodeObserved(NtkPreferenceEnvelope envelope) {
        if (!envelope.nativeDomain()) return List.of();
        var reports = new ArrayList<Report>();
        for (var key : envelope.keys) {
            if (!KEY.equals(key.key)) continue;
            double stamp = envelope.timestamp(key);
            if (!envelope.validTimestamp(stamp)) continue;
            byte[] value = key.value();
            try {
                if (value == null) reports.add(new Report(null, stamp));
                else if (value.length <= MAX_VALUE_BYTES) {
                    reports.add(new Report(BinaryPropertyListCodec.decodeStringRoot(value), stamp));
                }
            } catch (IllegalArgumentException invalid) { /* Retain prior valid text. */ }
            finally { if (value != null) Arrays.fill(value, (byte) 0); }
        }
        return List.copyOf(reports);
    }

    /** Native per-gizmo TwoWaySync policy. Caller owns pair/epoch and source clock checks.
     * Input is already normalized/validated; this codec never changes user text. */
    static byte[] encodeChangeAt(String text, double timestamp) {
        if (!NativeMonogramTextRules.valid(text) || !Double.isFinite(timestamp) || timestamp < 0) {
            throw new IllegalArgumentException("Invalid native monogram change");
        }
        byte[] value = BinaryPropertyListCodec.encodeStringRoot(text);
        var key = new PairedSyncCodec.UserDefaultsKey(KEY, value, true, timestamp);
        var message = new PairedSyncCodec.UserDefaultsMessage(timestamp, DOMAIN, List.of(key), false);
        try { return PairedSyncCodec.encode(message); }
        finally { Arrays.fill(value, (byte) 0); message.destroy(); key.destroy(); }
    }
}
