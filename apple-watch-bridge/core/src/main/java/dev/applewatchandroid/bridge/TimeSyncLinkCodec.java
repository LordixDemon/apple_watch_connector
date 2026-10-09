package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Codec for the Apple "timed" TMLSLink timesync dictionaries exchanged on the
 * Nano Class-D "timesync" topic. Wire format is a standard binary plist; the
 * layouts below were verified byte-exact against a live Watch Ultra 2 capture
 * ({kTMLSLinkMsgKey=6} 66 bytes) and a 2018 iPhone timed(LinkSource) log
 * (msg 12 = 106 bytes, msg 7 = 66 bytes, msg 4 = 152 bytes).
 */
final class TimeSyncLinkCodec {

    static final int MSG_SOURCE_TIME_ZONE = 4;
    static final int MSG_REQUEST_STATE = 6;
    static final int MSG_REQUEST_ACK = 7;
    static final int MSG_CONVERT_BT_TIME = 8;
    static final int MSG_AUTOMATIC_TIME = 12;

    private static final String KEY_MSG = "kTMLSLinkMsgKey";
    private static final String KEY_AUTO = "kTMLSLinkAutomaticTimeEnabledKey";
    private static final String KEY_SOURCE = "kTMLSLinkSourceKey";
    private static final String KEY_TIME_ZONE = "kTMLSLinkTimeZoneKey";
    private static final String SOURCE_DEVICE = "TMLSSourceDevice";

    private TimeSyncLinkCodec() {
    }

    /** Returns the kTMLSLinkMsgKey value of an incoming dictionary, or -1. */
    static int messageType(byte[] payload) {
        if (payload == null) return -1;
        try {
            Object decoded = AppleBinaryPropertyList.decode(payload);
            if (decoded instanceof Map<?, ?> map && map.get(KEY_MSG) instanceof Number type)
                return type.intValue();
        } catch (IllegalArgumentException malformed) {
            return -1;
        }
        return -1;
    }

    /** Exact watchOS 23S303 LinkSource 0x87a8/0x3238/0x492c continuation. */
    static byte[][] clockConversionResponses(byte[] request, long unixMillis, long nowNanos) {
        Object decoded = AppleBinaryPropertyList.decode(request);
        if (!(decoded instanceof Map<?, ?> map)
                || !(map.get(KEY_MSG) instanceof Number type) || type.intValue() != 8
                || !(map.get("kTMLSLinkRemoteMachKey") instanceof Number mach)
                || !(map.get("kTMLSLinkRTCKey") instanceof Number rtc)
                || !(map.get("kTMLSLinkUncertaintyKey") instanceof Number uncertainty))
            throw new IllegalArgumentException("incomplete Bluetooth time conversion");
        long sampleNanos = mach.longValue();
        double remoteRtc = rtc.doubleValue();
        double error = uncertainty.doubleValue();
        if (sampleNanos <= 0 || sampleNanos > nowNanos
                || nowNanos - sampleNanos > 300_000_000_000L
                || !Double.isFinite(remoteRtc) || remoteRtc <= 0
                || !Double.isFinite(error) || error < 0 || error > 60)
            throw new IllegalArgumentException("invalid Bluetooth time conversion sample");
        Map<String, Object> conversion = new TreeMap<>();
        conversion.put(KEY_MSG, 9);
        conversion.put("kTMLSLinkRTCKey", sampleNanos / 1_000_000_000.0);
        conversion.put("kTMLSLinkRemoteRTCKey", remoteRtc);
        conversion.put("kTMLSLinkUncertaintyKey", error + 0.001);
        Map<String, Object> time = new TreeMap<>();
        time.put(KEY_MSG, 1);
        time.put("kTMLSLinkTimeKey", unixMillis / 1000.0 - 978_307_200.0);
        time.put("kTMLSLinkRTCKey", nowNanos / 1_000_000_000.0);
        time.put("kTMLSLinkUncertaintyKey", 0.1);
        time.put(KEY_SOURCE, SOURCE_DEVICE);
        time.put("kTMLSLinkReliableKey", false);
        return new byte[][] {AppleBinaryPropertyList.encode(conversion),
                AppleBinaryPropertyList.encode(time)};
    }

    /** {kTMLSLinkAutomaticTimeEnabledKey=true, kTMLSLinkMsgKey=12} (106 bytes). */
    static byte[] automaticTimeEnabled() {
        Map<String, Object> dict = new TreeMap<>();
        dict.put(KEY_AUTO, Boolean.TRUE);
        dict.put(KEY_MSG, MSG_AUTOMATIC_TIME);
        return encode(dict);
    }

    /** {kTMLSLinkMsgKey=7} (66 bytes). */
    static byte[] requestAcknowledgement() {
        Map<String, Object> dict = new TreeMap<>();
        dict.put(KEY_MSG, MSG_REQUEST_ACK);
        return encode(dict);
    }

    /** {kTMLSLinkMsgKey=4, kTMLSLinkSourceKey=TMLSSourceDevice, kTMLSLinkTimeZoneKey=tz}. */
    static byte[] sourceAndTimeZone(String timeZoneId) {
        Map<String, Object> dict = new TreeMap<>();
        dict.put(KEY_MSG, MSG_SOURCE_TIME_ZONE);
        dict.put(KEY_SOURCE, SOURCE_DEVICE);
        dict.put(KEY_TIME_ZONE, timeZoneId);
        return encode(dict);
    }

    /**
     * Minimal binary-plist encoder covering exactly what TMLSLink needs:
     * a dict with ASCII string keys and boolean/small-int/ASCII-string values.
     * Object order and trailer layout match NSPropertyListSerialization output.
     */
    private static byte[] encode(Map<String, Object> dict) {
        List<String> keys = new ArrayList<>(dict.keySet());
        int count = keys.size();
        if (count > 14) {
            throw new IllegalArgumentException("dict too large for short marker");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("bplist00".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<>();
        // Object 0: dict with key refs 1..n then value refs n+1..2n.
        offsets.add(out.size());
        out.write(0xD0 | count);
        for (int i = 0; i < count; i++) {
            out.write(1 + i);
        }
        for (int i = 0; i < count; i++) {
            out.write(1 + count + i);
        }
        // Key string objects.
        for (String key : keys) {
            offsets.add(out.size());
            writeAsciiString(out, key);
        }
        // Value objects in key order.
        for (String key : keys) {
            offsets.add(out.size());
            writeValue(out, dict.get(key));
        }
        // Offset table + trailer.
        int offsetTableOffset = out.size();
        for (int offset : offsets) {
            if (offset > 0xFF) {
                throw new IllegalArgumentException("offset table needs 2-byte ints");
            }
            out.write(offset);
        }
        for (int i = 0; i < 6; i++) {
            out.write(0);
        }
        out.write(1); // offsetIntSize
        out.write(1); // objectRefSize
        writeLongBE(out, offsets.size());
        writeLongBE(out, 0); // top object index
        writeLongBE(out, offsetTableOffset);
        return out.toByteArray();
    }

    private static void writeAsciiString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < 15) {
            out.write(0x50 | bytes.length);
        } else if (bytes.length < 256) {
            out.write(0x5F);
            out.write(0x10); // 1-byte length int
            out.write(bytes.length);
        } else {
            throw new IllegalArgumentException("string too long");
        }
        out.writeBytes(bytes);
    }

    private static void writeValue(ByteArrayOutputStream out, Object value) {
        if (value instanceof Boolean) {
            out.write(((Boolean) value) ? 0x09 : 0x08);
        } else if (value instanceof Integer) {
            int intValue = (Integer) value;
            if (intValue < 0 || intValue > 0xFF) {
                throw new IllegalArgumentException("int out of 1-byte range");
            }
            out.write(0x10);
            out.write(intValue);
        } else if (value instanceof String) {
            writeAsciiString(out, (String) value);
        } else {
            throw new IllegalArgumentException("unsupported value type");
        }
    }

    private static void writeLongBE(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xFF));
        }
    }
}
