package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.Map;

/** Accumulates the Watch's two independently published PairedSync defaults. */
final class PairedSyncPeerProgress {
    private Integer watchProgress;
    private Integer globalProgress;
    private Integer clientProgress;
    private double watchTimestamp = Double.NEGATIVE_INFINITY;
    private double clientTimestamp = Double.NEGATIVE_INFINITY;

    void accept(PairedSyncCodec.UserDefaultsMessage message) {
        if (!PairedSyncCodec.DOMAIN.equals(message.domain)) return;
        var keys = message.keys();
        try {
            for (var key : keys) {
                boolean watch = PairedSyncCodec.WATCH_SYNC_STATE_KEY.equals(key.key);
                boolean client = PairedSyncCodec.WATCH_SYNC_CLIENT_STATE_KEY.equals(key.key);
                if (!watch && !client) continue;
                double timestamp = key.timestamp != null ? key.timestamp : message.timestamp;
                if (timestamp < (watch ? watchTimestamp : clientTimestamp)) continue;
                byte[] value = key.value();
                try {
                    Map<String, Object> state = value == null ? Map.of()
                            : BinaryPropertyListCodec.decodeDictionary(value);
                    Integer version = integer(state.get("version"));
                    Integer progress = Integer.valueOf(1).equals(version)
                            ? integer(state.get("syncProgressState")) : null;
                    if (watch) {
                        watchTimestamp = timestamp;
                        watchProgress = progress;
                        globalProgress = Integer.valueOf(1).equals(version)
                                ? integer(state.get("globalProgress")) : null;
                    } else {
                        clientTimestamp = timestamp;
                        clientProgress = progress;
                    }
                } finally {
                    if (value != null) Arrays.fill(value, (byte) 0);
                }
            }
        } finally {
            keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy);
        }
    }

    boolean complete() {
        return Integer.valueOf(3).equals(watchProgress)
                && Integer.valueOf(100).equals(globalProgress)
                && Integer.valueOf(3).equals(clientProgress);
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number number)) return null;
        double d = number.doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < 0 || d > Integer.MAX_VALUE) return null;
        return (int) d;
    }
}
