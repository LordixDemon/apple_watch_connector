package dev.applewatchandroid.bridge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Albert answers a successful activation POST with a compact XML plist
 * ({@code <plist version="1.0"><dict><key>ActivationRecord</key>...}) and an
 * {@code ARS} header carrying the activation signature. The Watch daemon
 * (mobileactivationd handleActivationInfoWithSession:activationSignature:)
 * verifies ARS over the exact bytes we deliver. Production delivery preserves
 * the Albert bytes. Earlier canonicalization experiments remain available
 * only through an explicit configuration override; rewriting a signed body
 * can cause "Invalid activation signature" (reproduced on Linux).
 *
 * This helper re-serializes the activation container before delivery. The
 * variant is selectable at runtime through a root-only config file so the
 * delivery format can be flipped on-device without rebuilding the APK:
 *   /data/data/dev.applewatchandroid.bridge/files/activation-delivery-variant.txt
 * Values: "bplist" (experimental binary plist, sorted keys),
 *         "xml"    (standard Apple XML plist: header + DOCTYPE, tab indent,
 *                   68-column base64 data lines),
 *         "raw"    (default: forward Albert bytes untouched).
 * Anything that is not an XML plist carrying an ActivationRecord key is
 * delivered untouched (challenge pages, session responses, binary plists).
 */
final class ActivationResponseCanonicalizer {
    static final String VARIANT_RAW = "raw";
    static final String VARIANT_BINARY = "bplist";
    static final String VARIANT_XML = "xml";
    static final String VARIANT_RECORD_BINARY = "record-bplist";
    static final String VARIANT_RECORD_XML = "record-xml";
    static final String VARIANT_RECORD_NOUDC_BINARY = "record-noudc-bplist";

    private static final String UNIQUE_DEVICE_CERT_KEY = "UniqueDeviceCertificate";

    private static final String RECORD_KEY = "ActivationRecord";
    private static final String XML_HEADER =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\""
                    + " \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                    + "<plist version=\"1.0\">\n";
    private static final int BASE64_LINE_WIDTH = 68;
    private static final int MAX_INPUT = 1024 * 1024;

    private ActivationResponseCanonicalizer() {
    }

    static final class Result {
        private final byte[] body;
        private final String variant;
        private final int inputLength;
        private final boolean changed;
        private final String note;

        Result(byte[] body, String variant, int inputLength, boolean changed) {
            this(body, variant, inputLength, changed, "");
        }

        Result(byte[] body, String variant, int inputLength, boolean changed,
                String note) {
            this.body = body;
            this.variant = variant;
            this.inputLength = inputLength;
            this.changed = changed;
            this.note = note;
        }

        byte[] body() {
            return body;
        }

        String variant() {
            return variant;
        }

        int inputLength() {
            return inputLength;
        }

        boolean changed() {
            return changed;
        }

        String note() {
            return note;
        }
    }

    /** Reads the platform's runtime variant file (default raw) and transforms. */
    static Result maybeTransform(byte[] body) {
        return maybeTransform(body, readVariant());
    }

    /** Pure transform with an explicit variant; never throws. */
    static Result maybeTransform(byte[] body, String variant) {
        if (body == null || body.length == 0) {
            return new Result(body, variant, 0, false, "empty body");
        }
        if (VARIANT_RAW.equals(variant)) {
            return new Result(body, variant, body.length, false, "variant=raw");
        }
        try {
            Object root = tryParseActivationContainer(body);
            if (root == null) {
                return new Result(body, variant, body.length, false,
                        "not an ActivationRecord container");
            }
            boolean xmlForm = VARIANT_XML.equals(variant)
                    || VARIANT_RECORD_XML.equals(variant);
            if (VARIANT_RECORD_BINARY.equals(variant)
                    || VARIANT_RECORD_XML.equals(variant)
                    || VARIANT_RECORD_NOUDC_BINARY.equals(variant)) {
                // Legacy delivery path: the Watch framework accepts a bare
                // record (no container) via getActivationRecordFromData and
                // skips the session ARS validation entirely.
                root = ((Map<?, ?>) root).get(RECORD_KEY);
                if (root == null) {
                    return new Result(body, variant, body.length, false,
                            "container has no record value");
                }
                if (VARIANT_RECORD_NOUDC_BINARY.equals(variant)) {
                    // Daemon legacy validation runs with factory certificates
                    // and rejects the session-scoped UCRT key outright:
                    // "Unsupported key in activation record for requested
                    // validation type (factory): UniqueDeviceCertificate".
                    Map<String, Object> stripped =
                            new TreeMap<>((Map<String, Object>) root);
                    stripped.remove(UNIQUE_DEVICE_CERT_KEY);
                    root = stripped;
                }
            }
            byte[] rewritten;
            if (xmlForm) {
                rewritten = writeAppleXml(root);
            } else {
                rewritten = AppleBinaryPropertyList.encode(root);
            }
            return new Result(rewritten, variant, body.length, true);
        } catch (Throwable failure) {
            // Never break delivery: on any problem forward the raw bytes.
            return new Result(body, variant, body.length, false,
                    failure.getClass().getSimpleName() + ": "
                            + failure.getMessage());
        }
    }

    private static String readVariant() {
        try {
            File file = ProtocolPaths.files().resolve("activation-delivery-variant.txt").toFile();
            if (!file.isFile()) return VARIANT_RAW;
            String value = new String(
                    Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8).trim().toLowerCase(java.util.Locale.US);
            if (VARIANT_RAW.equals(value) || VARIANT_XML.equals(value)
                    || VARIANT_BINARY.equals(value)
                    || VARIANT_RECORD_BINARY.equals(value)
                    || VARIANT_RECORD_XML.equals(value)
                    || VARIANT_RECORD_NOUDC_BINARY.equals(value)) {
                return value;
            }
        } catch (Exception ignored) {
        }
        return VARIANT_RAW;
    }

    /**
     * Returns the parsed plist object graph (maps sorted by key) when the body
     * is an XML plist containing an ActivationRecord entry; null otherwise.
     */
    private static Object tryParseActivationContainer(byte[] body)
            throws Exception {
        if (body.length > MAX_INPUT) return null;
        // Skip binary plists and obvious non-XML payloads cheaply.
        int start = 0;
        while (start < body.length && body[start] >= 0 && body[start] <= 0x20) {
            start++;
        }
        if (start >= body.length || body[start] != '<') return null;
        Object parsed = parseXmlPlist(body);
        if (parsed instanceof Map<?, ?> map
                && map.containsKey(RECORD_KEY)) {
            return parsed;
        }
        return null;
    }

    /**
     * Minimal XML-plist parser for machine-generated Apple plist documents.
     * Hand-rolled because Android's DOM implementation (kxml2-backed) rejects
     * these documents with "This parser does not support specification".
     * Handles the prologue (XML decl, DOCTYPE with optional internal subset,
     * comments), elements with attributes, self-closing tags, character
     * entities, and UTF-8 content. No network, no external DTD.
     */
    private static Object parseXmlPlist(byte[] body) {
        XmlCursor cursor = new XmlCursor(body);
        cursor.skipProlog();
        cursor.expectOpenTag("plist");
        cursor.skipIgnorable();
        Object root = cursor.parseValueElement();
        if (root == null) {
            throw new IllegalArgumentException("empty plist document");
        }
        return root;
    }

    private static final class XmlCursor {
        private final byte[] bytes;
        private int pos;

        XmlCursor(byte[] bytes) {
            this.bytes = bytes;
        }

        private boolean startsWith(String token) {
            int length = token.length();
            if (pos + length > bytes.length) return false;
            for (int i = 0; i < length; i++) {
                if (bytes[pos + i] != (byte) token.charAt(i)) return false;
            }
            return true;
        }

        private void skipWhitespace() {
            while (pos < bytes.length) {
                byte b = bytes[pos];
                if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        void skipIgnorable() {
            for (;;) {
                skipWhitespace();
                if (startsWith("<!--")) {
                    int end = indexOf("-->", pos + 4);
                    if (end < 0) throw error("unterminated comment");
                    pos = end + 3;
                } else {
                    return;
                }
            }
        }

        void skipProlog() {
            for (;;) {
                skipWhitespace();
                if (startsWith("<?")) {
                    int end = indexOf("?>", pos + 2);
                    if (end < 0) throw error("unterminated processing instruction");
                    pos = end + 2;
                } else if (startsWith("<!--")) {
                    int end = indexOf("-->", pos + 4);
                    if (end < 0) throw error("unterminated comment");
                    pos = end + 3;
                } else if (startsWith("<!DOCTYPE")) {
                    skipDoctype();
                } else {
                    return;
                }
            }
        }

        private void skipDoctype() {
            // Skip to the closing '>' of the DOCTYPE, honoring an optional
            // internal subset in [ ... ] and quoted literals.
            int i = pos + 9;
            int subsetDepth = 0;
            char quote = 0;
            while (i < bytes.length) {
                byte b = bytes[i];
                if (quote != 0) {
                    if (b == quote) quote = 0;
                } else if (b == '"' || b == '\'') {
                    quote = (char) b;
                } else if (b == '[') {
                    subsetDepth++;
                } else if (b == ']') {
                    subsetDepth--;
                } else if (b == '>' && subsetDepth <= 0) {
                    pos = i + 1;
                    return;
                }
                i++;
            }
            throw error("unterminated DOCTYPE");
        }

        private int indexOf(String token, int from) {
            outer: for (int i = from; i + token.length() <= bytes.length; i++) {
                for (int j = 0; j < token.length(); j++) {
                    if (bytes[i + j] != (byte) token.charAt(j)) continue outer;
                }
                return i;
            }
            return -1;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + pos);
        }

        /** Reads "<name ...>" or "<name .../>"; returns true if self-closing. */
        private boolean readTag(String expectedName) {
            if (pos >= bytes.length || bytes[pos] != '<') {
                throw error("expected <" + expectedName + ">");
            }
            int i = pos + 1;
            int nameStart = i;
            while (i < bytes.length) {
                byte b = bytes[i];
                if (b == ' ' || b == '\t' || b == '\r' || b == '\n'
                        || b == '>' || b == '/') {
                    break;
                }
                i++;
            }
            String name = new String(
                    bytes, nameStart, i - nameStart, StandardCharsets.UTF_8);
            if (!name.equals(expectedName)) {
                throw error("expected <" + expectedName + "> but got <" + name + ">");
            }
            // Skip attributes, honoring quoted values.
            char quote = 0;
            boolean selfClosing = false;
            while (i < bytes.length) {
                byte b = bytes[i];
                if (quote != 0) {
                    if (b == quote) quote = 0;
                } else if (b == '"' || b == '\'') {
                    quote = (char) b;
                } else if (b == '/') {
                    // Self-closing only if immediately followed by '>'; the
                    // loop increment moves to that '>' for the exit branch.
                    if (i + 1 < bytes.length && bytes[i + 1] == '>') {
                        selfClosing = true;
                    }
                } else if (b == '>') {
                    pos = i + 1;
                    return selfClosing;
                }
                i++;
            }
            throw error("unterminated tag <" + expectedName + ">");
        }

        void expectOpenTag(String name) {
            if (readTag(name)) {
                throw error("unexpected self-closing <" + name + "/>");
            }
        }

        void expectCloseTag(String name) {
            skipWhitespace();
            if (!startsWith("</")) {
                throw error("expected </" + name + ">");
            }
            pos += 2;
            int nameStart = pos;
            while (pos < bytes.length && bytes[pos] != '>') {
                pos++;
            }
            if (pos >= bytes.length) throw error("unterminated close tag");
            String name2 = new String(
                    bytes, nameStart, pos - nameStart, StandardCharsets.UTF_8)
                    .trim();
            pos++;
            if (!name2.equals(name)) {
                throw error("expected </" + name + "> but got </" + name2 + ">");
            }
        }

        /** Raw text up to the given close tag, entity-unescaped. */
        private String readText(String closeName) {
            int textStart = pos;
            while (pos < bytes.length && bytes[pos] != '<') {
                pos++;
            }
            String raw = new String(
                    bytes, textStart, pos - textStart, StandardCharsets.UTF_8);
            expectCloseTag(closeName);
            return unescape(raw);
        }

        /** Parses the next element as a plist value. Caller skipped whitespace. */
        Object parseValueElement() {
            skipWhitespace();
            if (pos >= bytes.length || bytes[pos] != '<') {
                throw error("expected value element");
            }
            if (startsWith("</")) {
                return null; // caller's close tag
            }
            // Peek the element name.
            int i = pos + 1;
            int nameStart = i;
            while (i < bytes.length) {
                byte b = bytes[i];
                if (b == ' ' || b == '\t' || b == '\r' || b == '\n'
                        || b == '>' || b == '/') {
                    break;
                }
                i++;
            }
            String name = new String(
                    bytes, nameStart, i - nameStart, StandardCharsets.UTF_8);
            switch (name) {
                case "dict": {
                    expectOpenTag("dict");
                    Map<String, Object> map = new TreeMap<>();
                    for (;;) {
                        skipIgnorable();
                        if (startsWith("</")) {
                            expectCloseTag("dict");
                            return map;
                        }
                        expectOpenTag("key");
                        String key = readText("key");
                        Object value = parseValueElement();
                        if (value == null) {
                            throw error("dict key without value: " + key);
                        }
                        map.put(key, value);
                    }
                }
                case "array": {
                    expectOpenTag("array");
                    List<Object> list = new ArrayList<>();
                    for (;;) {
                        skipIgnorable();
                        if (startsWith("</")) {
                            expectCloseTag("array");
                            return list;
                        }
                        Object item = parseValueElement();
                        if (item == null) throw error("unexpected array end");
                        list.add(item);
                    }
                }
                case "string":
                    if (readTag("string")) return "";
                    return readText("string");
                case "data": {
                    if (readTag("data")) return new byte[0];
                    String text = readText("data");
                    StringBuilder compact = new StringBuilder(text.length());
                    for (int k = 0; k < text.length(); k++) {
                        char c = text.charAt(k);
                        if (c > 0x20) compact.append(c);
                    }
                    return Base64.getDecoder().decode(
                            compact.toString().getBytes(StandardCharsets.US_ASCII));
                }
                case "true":
                    if (!readTag("true")) expectCloseTag("true");
                    return Boolean.TRUE;
                case "false":
                    if (!readTag("false")) expectCloseTag("false");
                    return Boolean.FALSE;
                case "integer":
                    expectOpenTag("integer");
                    return Long.parseLong(readText("integer").trim());
                case "real":
                    expectOpenTag("real");
                    return Double.parseDouble(readText("real").trim());
                default:
                    throw error("unsupported plist element: " + name);
            }
        }

        private static String unescape(String text) {
            int amp = text.indexOf('&');
            if (amp < 0) return text;
            StringBuilder out = new StringBuilder(text.length());
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c != '&') {
                    out.append(c);
                    i++;
                    continue;
                }
                int semi = text.indexOf(';', i);
                if (semi < 0) {
                    out.append(c);
                    i++;
                    continue;
                }
                String entity = text.substring(i + 1, semi);
                switch (entity) {
                    case "amp" -> out.append('&');
                    case "lt" -> out.append('<');
                    case "gt" -> out.append('>');
                    case "quot" -> out.append('"');
                    case "apos" -> out.append('\'');
                    default -> {
                        if (entity.startsWith("#x") || entity.startsWith("#X")) {
                            out.append((char) Integer.parseInt(
                                    entity.substring(2), 16));
                        } else if (entity.startsWith("#")) {
                            out.append((char) Integer.parseInt(
                                    entity.substring(1)));
                        } else {
                            throw new IllegalArgumentException(
                                    "unknown entity &" + entity + ";");
                        }
                    }
                }
                i = semi + 1;
            }
            return out.toString();
        }
    }

    /** Standard Apple XML plist serialization (matches CFPropertyList output). */
    private static byte[] writeAppleXml(Object root) {
        StringBuilder out = new StringBuilder(XML_HEADER);
        writeXmlValue(out, root, 0);
        out.append("</plist>\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("\t".repeat(depth));
    }

    private static void writeXmlValue(StringBuilder out, Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            indent(out, depth);
            out.append("<dict>\n");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(out, depth + 1);
                out.append("<key>").append(escapeXml((String) entry.getKey()))
                        .append("</key>\n");
                writeXmlValue(out, entry.getValue(), depth + 1);
            }
            indent(out, depth);
            out.append("</dict>\n");
        } else if (value instanceof List<?> list) {
            indent(out, depth);
            out.append("<array>\n");
            for (Object item : list) {
                writeXmlValue(out, item, depth + 1);
            }
            indent(out, depth);
            out.append("</array>\n");
        } else if (value instanceof String string) {
            indent(out, depth);
            out.append("<string>").append(escapeXml(string)).append("</string>\n");
        } else if (value instanceof Boolean bool) {
            indent(out, depth);
            out.append(bool ? "<true/>\n" : "<false/>\n");
        } else if (value instanceof Number number) {
            indent(out, depth);
            if (number instanceof Double || number instanceof Float) {
                out.append("<real>").append(number).append("</real>\n");
            } else {
                out.append("<integer>").append(number.longValue())
                        .append("</integer>\n");
            }
        } else if (value instanceof byte[] data) {
            indent(out, depth);
            out.append("<data>\n");
            String encoded = Base64.getEncoder().encodeToString(data);
            for (int offset = 0; offset < encoded.length();
                    offset += BASE64_LINE_WIDTH) {
                indent(out, depth);
                out.append(encoded, offset,
                        Math.min(offset + BASE64_LINE_WIDTH, encoded.length()));
                out.append('\n');
            }
            indent(out, depth);
            out.append("</data>\n");
        } else {
            throw new IllegalArgumentException(
                    "unsupported plist value: " + value);
        }
    }

    private static String escapeXml(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
