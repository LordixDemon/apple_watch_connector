package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec for {@code com.apple.private.alloy.appsyncconduit.v2}.
 *
 * <p>During and after pairing setup, the companion announces the standard
 * built-in Apple Watch bundle identifiers via message {@code T=30} followed by
 * {@code T=34} as IDS {@code COMMAND_DICTIONARY} (command 6) frames carrying
 * {@code FLAG_EXPECTS_PEER_RESPONSE | FLAG_HAS_EXPIRY} (0x09).</p>
 */
final class AppSyncConduitCodec {
    static final String TOPIC = IdsApplicationRoute.APP_SYNC_CONDUIT_SERVICE;
    static final long APPLE_EPOCH_OFFSET_SECONDS = 978_307_200L;
    static final long DEFAULT_EXPIRY_TTL_SECONDS = 3600L;
    static final long TYPE_DEFAULT_APPS = 30L;
    static final long TYPE_SYNC_FINISHED = 34L;

    /** Public built-in Apple Watch system bundle identifiers announced by iPhone. */
    static final List<String> DEFAULT_WATCH_APPS = List.of(
            "com.apple.NanoHome",
            "com.apple.NanoNotes",
            "com.apple.NanoBooks",
            "com.apple.NanoMail",
            "com.apple.VoiceMemos",
            "com.apple.podcasts",
            "com.apple.NanoMusic",
            "com.apple.NanoWorldClock",
            "com.apple.ActivityMonitorApp",
            "com.apple.weather.watchapp",
            "com.apple.NanoCamera",
            "com.apple.NanoCalendar",
            "com.apple.MobileSMS",
            "com.apple.NanoReminders",
            "com.apple.shortcuts.watch",
            "com.apple.AppStore",
            "com.apple.NanoPhotos",
            "com.apple.NanoPassbook",
            "com.apple.tincan",
            "com.apple.NanoCompass.watchkitapp",
            "com.apple.stocks.watchapp",
            "com.apple.NanoMaps",
            "com.apple.NanoCalculator.watchkitapp",
            "com.apple.nanonews",
            "com.apple.NanoContacts");

    private AppSyncConduitCodec() {
    }

    static byte[] encodeDefaultAppsList() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("T", TYPE_DEFAULT_APPS);
        body.put("BL", DEFAULT_WATCH_APPS);
        return AppleBinaryPropertyList.encode(body);
    }

    static byte[] encodeSyncFinished() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("T", TYPE_SYNC_FINISHED);
        return AppleBinaryPropertyList.encode(body);
    }

    static long expiryForUnixMillis(long unixMillis) {
        long appleSeconds = (unixMillis / 1000L) - APPLE_EPOCH_OFFSET_SECONDS + DEFAULT_EXPIRY_TTL_SECONDS;
        if (appleSeconds < 0L) {
            return DEFAULT_EXPIRY_TTL_SECONDS;
        }
        return Math.min(appleSeconds, 0xffff_ffffL);
    }

    /** Metadata-only diagnostic summary (never logs user app names or raw bodies). */
    static String describeInbound(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "empty";
        }
        try {
            Object decoded = AppleBinaryPropertyList.decode(payload);
            if (!(decoded instanceof Map<?, ?> map)) {
                return "root=" + (decoded == null ? "null" : decoded.getClass().getSimpleName());
            }
            Object t = map.get("T");
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                if (key instanceof String name && name.matches("[A-Za-z0-9_.$-]{1,64}")) {
                    keys.add(name);
                }
            }
            Collections.sort(keys);
            StringBuilder summary = new StringBuilder();
            summary.append("T=").append(t instanceof Number ? t : "none");
            summary.append(" keys=").append(keys);
            for (String listKey : new String[]{"BL", "IL", "RA"}) {
                Object value = map.get(listKey);
                if (value instanceof List<?> list) {
                    summary.append(' ').append(listKey).append("Count=").append(list.size());
                } else if (value instanceof Map<?, ?> sub) {
                    summary.append(' ').append(listKey).append("Count=").append(sub.size());
                }
            }
            return summary.toString();
        } catch (IllegalArgumentException malformed) {
            return "undecodable bytes=" + payload.length;
        }
    }
}
