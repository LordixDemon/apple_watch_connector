package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Projects {@code BRIDGE_APPLICATION_V1:} envelopes into bounded desktop wire
 * events without leaking raw application payloads to stdout or the disk journal.
 */
final class DesktopApplicationEventProjector {
    private static final int MAX_ENCODED_LENGTH = ((BridgeApplicationEventCodec.MAX_FRAME + 2) / 3) * 4;
    static final String ABOUT_PREFIX = "DESKTOP_ABOUT_V1:";
    static final String WATCH_SETTINGS_PREFIX = "DESKTOP_WATCH_SETTINGS_V1:";

    private final WatchSettingsObservation watchSettings = new WatchSettingsObservation();

    record Result(List<String> wireLines, String logSummary) {}

    synchronized void reset() {
        watchSettings.clear();
    }

    synchronized Result project(String encodedBase64, long nowMillis) {
        if (encodedBase64 == null || encodedBase64.isEmpty() || encodedBase64.length() > MAX_ENCODED_LENGTH) {
            return new Result(List.of(), "DESKTOP APP EVENT rejected; payload logged=false.");
        }
        byte[] frame = null;
        byte[] payload = null;
        try {
            frame = BridgeBase64.decode(encodedBase64, BridgeBase64.NO_WRAP);
            try (BridgeApplicationEventCodec.Event event = BridgeApplicationEventCodec.decode(frame)) {
                payload = event.payload();
                if (NanoSystemSettingsDiagnostics.TOPIC.equals(event.topic)
                        && event.protobufType == NanoSystemSettingsDiagnostics.ABOUT_RESPONSE
                        && event.response) {
                    NanoSystemSettingsDiagnostics.About about = NanoSystemSettingsDiagnostics.decodeAbout(payload);
                    Long capacity = about.batteryCapacity();
                    Integer batteryLevel = capacity != null && capacity >= 0 && capacity <= 100
                            ? capacity.intValue()
                            : null;
                    boolean chargingObserved = about.charging() != null;
                    String json = "{"
                            + "\"observedAt\":" + nowMillis
                            + ",\"batteryLevel\":" + batteryLevel
                            + ",\"isCharging\":" + about.charging()
                            + ",\"chargingObserved\":" + chargingObserved
                            + ",\"availableStorageBytes\":" + about.availableStorageBytes()
                            + ",\"numberOfApps\":" + about.apps()
                            + ",\"numberOfSongs\":" + about.songs()
                            + ",\"numberOfPhotos\":" + about.photos()
                            + ",\"purgeableSpaceBytes\":" + about.purgeableBytes()
                            + ",\"userDeletableSpaceBytes\":" + about.userDeletableBytes()
                            + "}";
                    return new Result(
                            List.of(ABOUT_PREFIX + json),
                            "DESKTOP ABOUT RX: battery=" + batteryLevel
                                    + " charging=" + about.charging()
                                    + "; storage/counts logged=false.");
                }
                if (IdsApplicationRoute.PREFERENCE_SYNC_SERVICE.equals(event.topic)
                        && event.protobufType == 0
                        && !event.response) {
                    boolean changed = watchSettings.observe(
                            WatchSettingsCodec.decodeObserved(payload, nowMillis), nowMillis);
                    Map<WatchSettingsCodec.Setting, WatchSettingsObservation.Value> snapshot = watchSettings.snapshot();
                    String summary = "DESKTOP WATCH SETTINGS RX: count=" + snapshot.size()
                            + "; values logged=false.";
                    if (!changed) {
                        return new Result(List.of(), summary);
                    }
                    StringBuilder json = new StringBuilder("{");
                    boolean first = true;
                    for (var entry : snapshot.entrySet()) {
                        if (!first) json.append(',');
                        first = false;
                        json.append('"').append(entry.getKey().name()).append("\":{")
                                .append("\"value\":").append(entry.getValue().value())
                                .append(",\"observedAt\":").append(entry.getValue().observedAt())
                                .append('}');
                    }
                    json.append('}');
                    return new Result(List.of(WATCH_SETTINGS_PREFIX + json), summary);
                }
                return new Result(
                        List.of(),
                        "DESKTOP APP EVENT RX: topic=" + event.topic
                                + " type=" + event.protobufType
                                + "; payload logged=false.");
            }
        } catch (IllegalArgumentException invalid) {
            return new Result(List.of(), "DESKTOP APP EVENT rejected; payload logged=false.");
        } finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
            if (frame != null) Arrays.fill(frame, (byte) 0);
        }
    }
}
