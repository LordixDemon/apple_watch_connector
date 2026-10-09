package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact 23S303 SystemPreferencesSync keys; no setup, radio, or security writes. */
final class WatchSettingsCodec {
    static final String COMMAND_PREFIX = "SET_WATCH_SETTING:";
    static final double APPLE_EPOCH_UNIX_SECONDS = 978307200.0;

    enum Setting {
        RIGHT_WRIST("com.apple.nano", "wornOnRightArm", true),
        INVERT_SCREEN("com.apple.nano", "invertUI", true),
        TIME_24_HOUR(".GlobalPreferences", "AppleICUForce24HourTime", false);

        final String domain;
        final String key;
        final boolean twoWay;
        Setting(String domain, String key, boolean twoWay) {
            this.domain = domain;
            this.key = key;
            this.twoWay = twoWay;
        }
    }

    record Change(Setting setting, boolean value) { }
    record Observed(Setting setting, Boolean value, double sourceTimestamp) { }
    private WatchSettingsCodec() { }

    static String command(String setting, boolean value) {
        return COMMAND_PREFIX + Setting.valueOf(setting).name() + ":" + value;
    }

    static Change parseCommand(String command) {
        if (command == null || !command.startsWith(COMMAND_PREFIX)) {
            throw new IllegalArgumentException("Unknown Watch setting command");
        }
        String[] parts = command.substring(COMMAND_PREFIX.length()).split(":", -1);
        if (parts.length != 2 || !(parts[1].equals("true") || parts[1].equals("false"))) {
            throw new IllegalArgumentException("Invalid Watch setting value");
        }
        return new Change(Setting.valueOf(parts[0]), parts[1].equals("true"));
    }

    static byte[] encode(Change change, long unixMillis) {
        double timestamp = unixMillis / 1000.0 - APPLE_EPOCH_UNIX_SECONDS;
        if (change == null || change.setting() == null || timestamp <= 0) {
            throw new IllegalArgumentException("Invalid Watch setting change");
        }
        Setting setting = change.setting();
        List<PairedSyncCodec.UserDefaultsKey> keys = new ArrayList<>();
        addBoolean(keys, setting.key, change.value(), setting.twoWay, timestamp);
        if (setting == Setting.TIME_24_HOUR) {
            // Avoid simultaneously forcing both formats in CoreFoundation.
            addBoolean(keys, "AppleICUForce12HourTime", !change.value(), false, timestamp);
        }
        PairedSyncCodec.UserDefaultsMessage message = new PairedSyncCodec.UserDefaultsMessage(
                timestamp, setting.domain, keys, false);
        try { return PairedSyncCodec.encode(message); }
        finally {
            message.destroy();
            keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy);
        }
    }

    private static void addBoolean(List<PairedSyncCodec.UserDefaultsKey> keys, String key,
            boolean value, boolean twoWay, double timestamp) {
        byte[] plist = BinaryPropertyListCodec.encodeBoolean(value);
        try {
            keys.add(new PairedSyncCodec.UserDefaultsKey(key, plist,
                    twoWay ? Boolean.TRUE : null, twoWay ? timestamp : null));
        } finally { Arrays.fill(plist, (byte) 0); }
    }

    /** Only actual inbound native reports count; caller checks topic/type/direction. */
    static List<Observed> decodeObserved(byte[] payload, long unixMillis) {
        PairedSyncCodec.UserDefaultsMessage message = PairedSyncCodec.decodeInbound(payload);
        var keys = message.keys();
        try {
            double now = unixMillis / 1000.0 - APPLE_EPOCH_UNIX_SECONDS;
            if (message.timestamp < 0 || message.timestamp > now + 5) {
                throw new IllegalArgumentException("Invalid native preference timestamp");
            }
            List<Observed> result = new ArrayList<>();
            for (var key : keys) {
                for (Setting setting : Setting.values()) {
                    if (!setting.domain.equals(message.domain) || !setting.key.equals(key.key)) continue;
                    double order = key.timestamp == null ? message.timestamp : key.timestamp;
                    if (order < 0 || order > now + 5) continue;
                    byte[] value = key.value();
                    try {
                        // A removed or malformed value means unknown, never false.
                        if (value == null || value.length <= 1024) result.add(new Observed(
                                setting, value == null ? null : BinaryPropertyListCodec.decodeBoolean(value), order));
                    } catch (IllegalArgumentException invalid) { /* Ignore this key only. */ }
                    finally { if (value != null) Arrays.fill(value, (byte) 0); }
                }
            }
            return List.copyOf(result);
        } finally {
            message.destroy();
            keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy);
        }
    }
}
