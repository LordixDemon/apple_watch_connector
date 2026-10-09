package dev.applewatchandroid.bridge;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Current transport session's native preference reports, including deletion tombstones. */
final class WatchSettingsObservation {
    record Value(Boolean value, double sourceTimestamp, long observedAt) { }
    private final EnumMap<WatchSettingsCodec.Setting, Value> values =
            new EnumMap<>(WatchSettingsCodec.Setting.class);

    synchronized boolean observe(List<WatchSettingsCodec.Observed> reports, long observedAt) {
        if (observedAt <= 0) throw new IllegalArgumentException("Invalid observation time");
        boolean changed = false;
        for (var report : reports) {
            Value old = values.get(report.setting());
            if (old != null && report.sourceTimestamp() <= old.sourceTimestamp()) continue;
            values.put(report.setting(), new Value(report.value(), report.sourceTimestamp(), observedAt));
            changed = true;
        }
        return changed;
    }

    synchronized Map<WatchSettingsCodec.Setting, Value> snapshot() { return Map.copyOf(values); }
    synchronized void clear() { values.clear(); }
}
