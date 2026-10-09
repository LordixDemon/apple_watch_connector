package dev.applewatchandroid.bridge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Only reports received from the currently owned transport form a write baseline. */
final class PigmentPreferenceObservation {
    record Value(UUID pair, UUID epoch, List<String> names, double sourceTimestamp, long observedAt) {
        Value { if (names != null) names = List.copyOf(names); }
    }
    private Value value;

    synchronized boolean observe(UUID pair, UUID epoch, List<PigmentPreferenceCodec.Report> reports, long now) {
        if (pair == null || epoch == null || now <= 0) throw new IllegalArgumentException("Missing pigment transport context");
        boolean changed = false;
        if (value != null && (!pair.equals(value.pair()) || !epoch.equals(value.epoch()))) {
            value = null;
            changed = true;
        }
        for (var report : reports) {
            if (value != null && report.sourceTimestamp() <= value.sourceTimestamp()) {
                // An authenticated repeat of the same set refreshes receipt age, not source
                // ordering. Same-timestamp conflicting data never refreshes it.
                if (report.sourceTimestamp() == value.sourceTimestamp()
                        && PigmentPreferenceCodec.sameNames(report.names(), value.names()) && now > value.observedAt()) {
                    value = new Value(pair, epoch, value.names(), value.sourceTimestamp(), now);
                    changed = true;
                }
                continue;
            }
            value = new Value(pair, epoch, report.names(), report.sourceTimestamp(), now);
            changed = true;
        }
        return changed;
    }

    synchronized Value snapshot(UUID pair, UUID epoch) {
        return value != null && Objects.equals(value.pair(), pair) && Objects.equals(value.epoch(), epoch) ? value : null;
    }
    synchronized void clear() { value = null; }
}
