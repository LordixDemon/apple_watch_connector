package dev.applewatchandroid.bridge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Only ordered observations of custom text from the owned pair, never local echoes. */
final class MonogramPreferenceObservation {
    record Value(UUID pair, UUID epoch, String text, double sourceTimestamp, long observedAt) { }
    private Value value;

    synchronized boolean observe(UUID pair, UUID epoch, List<MonogramPreferenceCodec.Report> reports, long now) {
        if (pair == null || epoch == null || now <= 0) throw new IllegalArgumentException("Missing monogram transport context");
        boolean changed = false;
        if (value != null && (!pair.equals(value.pair()) || !epoch.equals(value.epoch()))) {
            value = null;
            changed = true;
        }
        for (var report : reports) {
            if (value != null && report.sourceTimestamp() <= value.sourceTimestamp()) {
                if (report.sourceTimestamp() == value.sourceTimestamp()
                        && Objects.equals(report.text(), value.text()) && now > value.observedAt()) {
                    value = new Value(pair, epoch, value.text(), value.sourceTimestamp(), now);
                    changed = true;
                }
                continue;
            }
            value = new Value(pair, epoch, report.text(), report.sourceTimestamp(), now);
            changed = true;
        }
        return changed;
    }
    synchronized Value snapshot(UUID pair, UUID epoch) {
        return value != null && Objects.equals(value.pair(), pair) && Objects.equals(value.epoch(), epoch) ? value : null;
    }
    synchronized void clear() { value = null; }
}
