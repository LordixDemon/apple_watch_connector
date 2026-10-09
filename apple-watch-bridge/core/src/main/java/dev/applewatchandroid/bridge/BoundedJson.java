package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Data-only JSON decoder with explicit size, nesting, node and Unicode bounds. */
final class BoundedJson {
    private final String source;
    private int offset;
    private int nodes;

    private BoundedJson(String source) { this.source = source; }

    static Object decode(byte[] bytes, int maximumBytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > maximumBytes) throw invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            BoundedJson parser = new BoundedJson(text);
            Object result = parser.value(0);
            parser.space();
            if (parser.offset != text.length()) throw invalid();
            return result;
        } catch (java.nio.charset.CharacterCodingException failure) { throw invalid(); }
    }

    private Object value(int depth) {
        if (depth > 32 || ++nodes > 8192) throw invalid();
        space();
        if (offset == source.length()) throw invalid();
        char next = source.charAt(offset);
        if (next == '"') return string();
        if (next == '{') {
            offset++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            do {
                space();
                if (offset == source.length() || source.charAt(offset) != '"') throw invalid();
                String key = string();
                if (result.containsKey(key)) throw invalid();
                space(); require(':');
                result.put(key, value(depth + 1));
                space();
                if (take('}')) return result;
                require(',');
            } while (true);
        }
        if (next == '[') {
            offset++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            do {
                result.add(value(depth + 1));
                space();
                if (take(']')) return result;
                require(',');
            } while (true);
        }
        if (source.startsWith("true", offset)) { offset += 4; return true; }
        if (source.startsWith("false", offset)) { offset += 5; return false; }
        if (source.startsWith("null", offset)) { offset += 4; return null; }
        int start = offset;
        take('-');
        if (!take('0')) digits();
        if (take('.')) digits();
        if (take('e') || take('E')) { if (!take('+')) take('-'); digits(); }
        if (offset == start) throw invalid();
        String token = source.substring(start, offset);
        try {
            double number = Double.parseDouble(token);
            if (!Double.isFinite(number)) throw invalid();
            return number;
        } catch (NumberFormatException failure) { throw invalid(); }
    }

    private String string() {
        require('"');
        StringBuilder result = new StringBuilder();
        while (offset < source.length()) {
            char next = source.charAt(offset++);
            if (next == '"') {
                for (int index = 0; index < result.length(); index++) {
                    char unit = result.charAt(index);
                    if (Character.isHighSurrogate(unit)) {
                        if (++index >= result.length() || !Character.isLowSurrogate(result.charAt(index))) throw invalid();
                    } else if (Character.isLowSurrogate(unit)) throw invalid();
                }
                return result.toString();
            }
            if (next < 0x20) throw invalid();
            if (next == '\\') {
                if (offset == source.length()) throw invalid();
                next = source.charAt(offset++);
                switch (next) {
                    case '"', '\\', '/' -> result.append(next);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        int unit = 0;
                        for (int count = 0; count < 4; count++) {
                            if (offset == source.length()) throw invalid();
                            char hex = source.charAt(offset++);
                            int digit = "0123456789abcdef".indexOf(Character.toLowerCase(hex));
                            if (digit < 0) throw invalid();
                            unit = (unit << 4) | digit;
                        }
                        result.append((char) unit);
                    }
                    default -> throw invalid();
                }
            } else result.append(next);
        }
        throw invalid();
    }

    private void digits() {
        int start = offset;
        while (offset < source.length() && source.charAt(offset) >= '0' && source.charAt(offset) <= '9') offset++;
        if (offset == start) throw invalid();
    }
    private void space() {
        while (offset < source.length() && " \r\n\t".indexOf(source.charAt(offset)) >= 0) offset++;
    }
    private boolean take(char value) {
        if (offset < source.length() && source.charAt(offset) == value) { offset++; return true; }
        return false;
    }
    private void require(char value) { if (!take(value)) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid bounded JSON"); }
}
