package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Original NTKPigmentPreferenceManager fullname-set schema, not face JSON. */
final class PigmentPreferenceCodec {
    static final String DOMAIN = "com.apple.NanoTimeKit";
    static final String KEY = "SelectedPigmentList";
    static final String AUTO_KEY = "AutoSelectedPigmentList";
    static final int MAX_NAMES = 1023, MAX_VALUE_BYTES = 64 * 1024;
    record Report(List<String> names, double sourceTimestamp) {
        Report {
            if (!Double.isFinite(sourceTimestamp) || sourceTimestamp < 0) {
                throw new IllegalArgumentException("Invalid pigment timestamp");
            }
            if (names != null) names = PigmentPreferenceCodec.names(names);
        }
    }

    private PigmentPreferenceCodec() { }

    /** Both native sets belong to a pair; neither may replay from the global cache. */
    static boolean isPairedPreference(String domain, String key) {
        return DOMAIN.equals(domain) && (KEY.equals(key) || AUTO_KEY.equals(key));
    }

    /** The two verified NPS routes carry type-0 updates; type-2 backups differ. */
    static boolean isObservationEnvelope(String topic, int type, boolean response) {
        return !response && type == PairedSyncCodec.PROTOBUF_TYPE_USER_DEFAULTS
                && PairedSyncCodec.isService(topic);
    }

    static List<Report> decodeObserved(byte[] payload, long unixMillis) {
        return decodeObserved(payload, unixMillis, KEY);
    }

    static List<Report> decodeObserved(byte[] payload, long unixMillis, String preferenceKey) {
        if (!isPairedPreference(DOMAIN, preferenceKey)) throw new IllegalArgumentException("Invalid pigment preference key");
        return decodeObservedLists(payload, unixMillis).getOrDefault(preferenceKey, List.of());
    }

    /** The HAL receives every NPS domain: parse/copy the envelope once for both color sets. */
    static Map<String, List<Report>> decodeObservedLists(byte[] payload, long unixMillis) {
        try (var envelope = NtkPreferenceEnvelope.decode(payload, unixMillis)) {
            return decodeObservedLists(envelope);
        }
    }

    static Map<String, List<Report>> decodeObservedLists(NtkPreferenceEnvelope envelope) {
        if (!envelope.nativeDomain()) return Map.of();
        List<Report> selected = new ArrayList<>(), automatic = new ArrayList<>();
        for (var key : envelope.keys) {
            if (!isPairedPreference(DOMAIN, key.key)) continue;
            List<Report> result = KEY.equals(key.key) ? selected : automatic;
            double timestamp = envelope.timestamp(key);
            if (!envelope.validTimestamp(timestamp)) continue;
            byte[] value = key.value();
            try {
                if (value == null) result.add(new Report(null, timestamp));
                else if (value.length <= MAX_VALUE_BYTES) {
                    result.add(new Report(BinaryPropertyListCodec.decodeStringArray(value), timestamp));
                }
            } catch (IllegalArgumentException invalid) { /* Never replace a valid list with partial data. */ }
            finally { if (value != null) Arrays.fill(value, (byte) 0); }
        }
        return Map.of(KEY, List.copyOf(selected), AUTO_KEY, List.copyOf(automatic));
    }

    static List<String> names(List<String> names) {
        if (names == null || names.size() > MAX_NAMES) throw new IllegalArgumentException("Invalid pigment list size");
        var distinct = new LinkedHashSet<String>();
        int size = 0;
        for (String name : names) {
            if (!validName(name) || !distinct.add(name)) throw new IllegalArgumentException("Invalid pigment identity");
            size += name.length() * 2;
            if (size > MAX_VALUE_BYTES / 2) throw new IllegalArgumentException("Pigment list too large");
        }
        return List.copyOf(distinct);
    }

    /** Native NSSet membership is independent of allObjects serialization order.
     * Inputs are complete, duplicate-free lists validated by the report/store codecs. */
    static boolean sameNames(List<String> first, List<String> second) {
        return java.util.Objects.equals(first, second) || first != null && second != null
                && first.size() == second.size() && new java.util.HashSet<>(first).containsAll(second);
    }

    private static boolean validName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 256 && name.indexOf(':') < 0
                && name.chars().noneMatch(Character::isISOControl);
    }

    /** Only manual deltas alter a complete observed list; unknown native names survive. */
    static List<String> merge(List<String> baseline, Map<String, Boolean> changes) {
        var next = new LinkedHashSet<>(names(baseline));
        if (changes == null || changes.size() > MAX_NAMES) throw new IllegalArgumentException("Invalid pigment changes");
        for (var entry : changes.entrySet()) {
            if (!validName(entry.getKey()) || entry.getValue() == null) throw new IllegalArgumentException("Invalid pigment change");
            if (entry.getValue()) next.add(entry.getKey()); else next.remove(entry.getKey());
        }
        return names(new ArrayList<>(next));
    }

    /** Every manual edit takes its fullname out of automatic tracking, regardless of visibility. */
    static List<String> removeAutomatic(List<String> baseline, Map<String, Boolean> changes) {
        merge(List.of(), changes); // Validate identities and boolean values for removals too.
        return names(baseline).stream().filter(name -> !changes.containsKey(name)).collect(java.util.stream.Collectors.toList());
    }

    static byte[] encodeManualAt(Report selected, Report automatic, Map<String, Boolean> changes, double timestamp) {
        requireBaseline(selected, timestamp);
        requireBaseline(automatic, timestamp);
        byte[] selectedBytes = BinaryPropertyListCodec.encodeStringArray(merge(selected.names(), changes));
        byte[] autoBytes = BinaryPropertyListCodec.encodeStringArray(removeAutomatic(automatic.names(), changes));
        if (selectedBytes.length > MAX_VALUE_BYTES || autoBytes.length > MAX_VALUE_BYTES) {
            Arrays.fill(selectedBytes, (byte) 0); Arrays.fill(autoBytes, (byte) 0);
            throw new IllegalArgumentException("Pigment value too large");
        }
        var visibleKey = new PairedSyncCodec.UserDefaultsKey(KEY, selectedBytes, true, timestamp);
        var autoKey = new PairedSyncCodec.UserDefaultsKey(AUTO_KEY, autoBytes, true, timestamp);
        var message = new PairedSyncCodec.UserDefaultsMessage(timestamp, DOMAIN, List.of(visibleKey, autoKey), false);
        try { return PairedSyncCodec.encode(message); }
        finally { Arrays.fill(selectedBytes, (byte) 0); Arrays.fill(autoBytes, (byte) 0);
            message.destroy(); visibleKey.destroy(); autoKey.destroy(); }
    }

    /** Wire planner; transport callers must recheck the complete owned-session baseline. */
    static byte[] encodeChange(Report baseline, Map<String, Boolean> changes, long unixMillis) {
        double timestamp = unixMillis / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS;
        return encodeChangeAt(baseline, changes, timestamp);
    }

    static byte[] encodeChangeAt(Report baseline, Map<String, Boolean> changes, double timestamp) {
        requireBaseline(baseline, timestamp);
        byte[] value = BinaryPropertyListCodec.encodeStringArray(merge(baseline.names(), changes));
        if (value.length > MAX_VALUE_BYTES) { Arrays.fill(value, (byte) 0); throw new IllegalArgumentException("Pigment value too large"); }
        var key = new PairedSyncCodec.UserDefaultsKey(KEY, value, Boolean.TRUE, timestamp);
        var message = new PairedSyncCodec.UserDefaultsMessage(timestamp, DOMAIN, List.of(key), false);
        try { return PairedSyncCodec.encode(message); }
        finally { Arrays.fill(value, (byte) 0); message.destroy(); key.destroy(); }
    }

    private static void requireBaseline(Report baseline, double timestamp) {
        if (baseline == null || baseline.names() == null || !Double.isFinite(baseline.sourceTimestamp())
                || baseline.sourceTimestamp() < 0 || !Double.isFinite(timestamp) || timestamp <= baseline.sourceTimestamp()) {
            throw new IllegalArgumentException("Fresh complete pigment baseline is required");
        }
    }
}
