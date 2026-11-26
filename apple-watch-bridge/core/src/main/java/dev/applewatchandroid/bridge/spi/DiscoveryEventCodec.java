package dev.applewatchandroid.bridge.spi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Wire codec for Watch discovery events emitted by protocol hosts and parsed by
 * Android Bridge, Rust desktop backend, and tests.
 *
 * <ul>
 *   <li>{@link #V1_PREFIX}: legacy 5-field colon format preserved for Strategy 4 backward compatibility.</li>
 *   <li>{@link #V2_PREFIX}: compact bounded JSON format carrying strategy metadata for S1–S4.</li>
 * </ul>
 */
public final class DiscoveryEventCodec {
    public static final String V1_PREFIX = "WATCH_DISCOVERED_V1:";
    public static final String V2_PREFIX = "WATCH_DISCOVERED_V2:";
    public static final int MAX_V2_JSON_LENGTH = 512;

    private DiscoveryEventCodec() {
    }

    public static String encodeV1(DiscoveredWatch watch) {
        String product = sanitizeColonField(
                watch.productType() != null && !watch.productType().isBlank()
                        ? watch.productType()
                        : watch.displayName());
        String version = sanitizeColonField(
                watch.watchOs() != null && !watch.watchOs().isBlank()
                        ? watch.watchOs()
                        : watch.strategy().watchOsRange());
        return V1_PREFIX + watch.token() + ":" + product + ":" + version + ":" + watch.rssi();
    }

    public static String encodeV2(DiscoveredWatch watch) {
        StringBuilder sb = new StringBuilder(192);
        sb.append(V2_PREFIX).append('{');
        sb.append("\"token\":").append(jsonString(watch.token()));
        if (watch.productType() != null && !watch.productType().isBlank()) {
            sb.append(",\"productType\":").append(jsonString(watch.productType()));
        }
        sb.append(",\"displayName\":").append(jsonString(watch.displayName()));
        if (watch.watchOs() != null && !watch.watchOs().isBlank()) {
            sb.append(",\"watchOs\":").append(jsonString(watch.watchOs()));
        }
        sb.append(",\"rssi\":").append(watch.rssi());
        sb.append(",\"strategy\":").append(watch.strategy().wireId());
        sb.append(",\"experimental\":").append(watch.experimental());
        sb.append(",\"authStyles\":[");
        boolean first = true;
        for (AuthStyle style : watch.authStyles()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(jsonString(style.name()));
        }
        sb.append("]}");
        String encoded = sb.toString();
        if (encoded.length() - V2_PREFIX.length() > MAX_V2_JSON_LENGTH) {
            throw new IllegalArgumentException("WATCH_DISCOVERED_V2 exceeds max bound");
        }
        return encoded;
    }

    /** Locates a {@code WATCH_DISCOVERED_V2:{...}} marker at a token boundary in a log line. */
    public static String findV2(String line) {
        if (line == null) {
            return null;
        }
        int from = 0;
        int at;
        while ((at = line.indexOf(V2_PREFIX, from)) >= 0) {
            if (at == 0 || Character.isWhitespace(line.charAt(at - 1))) {
                String candidate = line.substring(at);
                String json = candidate.substring(V2_PREFIX.length());
                if (json.length() >= 2 && json.length() <= MAX_V2_JSON_LENGTH
                        && json.startsWith("{") && json.endsWith("}")) {
                    return candidate;
                }
            }
            from = at + V2_PREFIX.length();
        }
        return null;
    }

    /** Decodes a {@code WATCH_DISCOVERED_V2:{...}} marker (with or without prefix). */
    public static DiscoveredWatch decodeV2(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Null discovery V2 payload");
        }
        String json = input.startsWith(V2_PREFIX) ? input.substring(V2_PREFIX.length()) : input;
        if (json.length() < 2 || json.length() > MAX_V2_JSON_LENGTH
                || !json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("Invalid discovery V2 JSON bounds");
        }
        JsonCursor cursor = new JsonCursor(json);
        cursor.expect('{');
        String token = null;
        String productType = null;
        String displayName = null;
        String watchOs = null;
        Integer rssi = null;
        Integer strategyNum = null;
        Boolean experimental = null;
        Set<AuthStyle> authStyles = new LinkedHashSet<>();

        cursor.skipWs();
        if (!cursor.peek('}')) {
            while (true) {
                String key = cursor.readString();
                cursor.expect(':');
                switch (key) {
                    case "token" -> token = cursor.readString();
                    case "productType" -> productType = cursor.readNullableString();
                    case "displayName" -> displayName = cursor.readString();
                    case "watchOs" -> watchOs = cursor.readNullableString();
                    case "rssi" -> rssi = cursor.readInt();
                    case "strategy" -> strategyNum = cursor.readInt();
                    case "experimental" -> experimental = cursor.readBoolean();
                    case "authStyles" -> {
                        for (String item : cursor.readStringArray()) {
                            authStyles.add(AuthStyle.valueOf(item));
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown discovery V2 field: " + key);
                }
                cursor.skipWs();
                if (cursor.peek(',')) {
                    cursor.next();
                    continue;
                }
                break;
            }
        }
        cursor.expect('}');
        cursor.skipWs();
        if (!cursor.eof()) {
            throw new IllegalArgumentException("Trailing bytes in discovery V2 JSON");
        }
        if (token == null || !UUID.fromString(token).toString().equals(token)) {
            throw new IllegalArgumentException("Discovery V2 token must be canonical UUID");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 80) {
            throw new IllegalArgumentException("Invalid discovery V2 displayName");
        }
        if (rssi == null || rssi < -127 || rssi > 20) {
            throw new IllegalArgumentException("Invalid discovery V2 RSSI");
        }
        PairingStrategyId strategy = strategyNum == null ? null : PairingStrategyId.fromWireId(strategyNum);
        if (strategy == null) {
            throw new IllegalArgumentException("Invalid discovery V2 strategy");
        }
        if (authStyles.isEmpty()) {
            authStyles.add(AuthStyle.PIN);
        }
        boolean exp = experimental != null ? experimental : strategy.isExperimental();
        return new DiscoveredWatch(token, productType, displayName, watchOs, rssi, strategy, exp, authStyles);
    }

    private static String sanitizeColonField(String value) {
        return value.replace(':', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.append('"').toString();
    }

    private static final class JsonCursor {
        private final String s;
        private int pos;

        JsonCursor(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        boolean peek(char c) {
            skipWs();
            return pos < s.length() && s.charAt(pos) == c;
        }

        char next() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of discovery JSON");
            }
            return s.charAt(pos++);
        }

        void expect(char c) {
            skipWs();
            if (next() != c) {
                throw new IllegalArgumentException("Expected '" + c + "' in discovery JSON");
            }
        }

        String readNullableString() {
            skipWs();
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return readString();
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Truncated escape in discovery JSON");
                    }
                    char esc = s.charAt(pos++);
                    if (esc == '"' || esc == '\\' || esc == '/') {
                        sb.append(esc);
                    } else {
                        throw new IllegalArgumentException("Unsupported escape in discovery JSON");
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("Control character in discovery JSON");
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string in discovery JSON");
        }

        int readInt() {
            skipWs();
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            int digits = 0;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
                digits++;
            }
            if (digits == 0) {
                throw new IllegalArgumentException("Expected integer in discovery JSON");
            }
            return Integer.parseInt(s.substring(start, pos));
        }

        boolean readBoolean() {
            skipWs();
            if (s.startsWith("true", pos)) {
                pos += 4;
                return true;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return false;
            }
            throw new IllegalArgumentException("Expected boolean in discovery JSON");
        }

        List<String> readStringArray() {
            expect('[');
            List<String> items = new ArrayList<>();
            skipWs();
            if (!peek(']')) {
                while (true) {
                    items.add(readString());
                    skipWs();
                    if (peek(',')) {
                        next();
                        continue;
                    }
                    break;
                }
            }
            expect(']');
            return items;
        }
    }
}
