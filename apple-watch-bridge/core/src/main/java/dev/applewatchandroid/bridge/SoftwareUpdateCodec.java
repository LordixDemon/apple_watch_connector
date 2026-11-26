package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native SUBMessageEndpoint binary-plist envelopes (23S303 and 24R365). */
final class SoftwareUpdateCodec {
    static final String TOPIC = "com.apple.private.alloy.otaupdate";
    static final int MAX_BYTES = 512 * 1024;
    static final int MAX_TERMS_BYTES = 256 * 1024;
    static final String QUERY = "QueryManagerState", SCAN = "ScanForUpdates";
    static final String DOWNLOAD = "StartDownload", INSTALL = "InstallUpdate";
    static final String TERMS = "UserDidAcceptTermsAndConditionsWithReply";
    private static final Set<String> COMMANDS = Set.of(QUERY, SCAN, DOWNLOAD, INSTALL, TERMS, "AddClient");
    private static final Pattern HTML_MARKER = Pattern.compile("<(?:!doctype|html|body|p|div)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRIP_HIDDEN_BLOCKS = Pattern.compile(
            "<(script|style|head)\\b[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANCHOR_TAG = Pattern.compile(
            "<a\\b[^>]*href\\s*=\\s*[\"'](https?://[^\"'\\s>]+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "</?(?:p|div|br|li|h[1-6]|tr)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos|nbsp);");

    private SoftwareUpdateCodec() { }

    static byte[] request(String command, byte[] descriptor) {
        if (!COMMANDS.contains(command)) throw new IllegalArgumentException("Unsupported update command");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("MessageType", command);
        if (Set.of(DOWNLOAD, INSTALL, TERMS).contains(command)) {
            try (Descriptor validated = descriptor(descriptor)) { /* Transmit the original native archive. */ }
            body.put("Descriptor", descriptor);
        } else if (descriptor != null) throw new IllegalArgumentException("Unexpected update descriptor");
        return AppleBinaryPropertyList.encode(body);
    }

    static Map<?, ?> message(byte[] payload) {
        if (payload == null || payload.length > MAX_BYTES) throw new IllegalArgumentException("Update envelope size");
        Object root = AppleBinaryPropertyList.decode(payload);
        if (!(root instanceof Map<?, ?> map) || map.size() > 32)
            throw new IllegalArgumentException("Update envelope is not a dictionary");
        return map;
    }

    static Descriptor descriptor(byte[] data) {
        try (Archive archive = new Archive(data)) {
            Map<?, ?> root = archive.root("SUBDescriptor");
            String version = version(archive.field(root, "productVersion"), false);
            String build = version(archive.field(root, "productBuildVersion"), true);
            String licenseTerms = extractLicenseTerms(archive, root);
            return new Descriptor(data.clone(), version, build,
                    nonnegative(root.get("downloadSize")), nonnegative(root.get("TotalRequiredFreeSpace")),
                    Boolean.TRUE.equals(root.get("userDidAcceptTermsAndConditions")),
                    Boolean.TRUE.equals(root.get("isAwaitingAdmissionControlForInstallation")),
                    licenseTerms);
        }
    }

    private static String extractLicenseTerms(Archive archive, Map<?, ?> root) {
        Object docRef = archive.field(root, "documentation");
        if (docRef == null) return null;
        Map<?, ?> doc = archive.typed(docRef, "SUBDocumentation");
        Object raw = archive.field(doc, "licenseAgreement");
        if (raw == null) return null;
        String text;
        if (raw instanceof byte[] bytes) {
            if (bytes.length > MAX_TERMS_BYTES) throw new IllegalArgumentException("Update terms exceed size limit");
            text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        } else if (raw instanceof String str) {
            if (str.getBytes(StandardCharsets.UTF_8).length > MAX_TERMS_BYTES)
                throw new IllegalArgumentException("Update terms exceed size limit");
            text = str;
        } else {
            throw new IllegalArgumentException("Invalid update terms encoding");
        }
        String rendered = HTML_MARKER.matcher(text).find() ? htmlToPlainText(text) : text.trim();
        return rendered.isEmpty() ? null : rendered;
    }

    /** Offline HTML-to-plain-text renderer for captured SUBDocumentation licenseAgreement; never fetches URLs. */
    static String htmlToPlainText(String html) {
        if (html == null || html.isEmpty()) return "";
        String cleaned = STRIP_HIDDEN_BLOCKS.matcher(html).replaceAll("");
        StringBuilder withLinks = new StringBuilder();
        Matcher links = ANCHOR_TAG.matcher(cleaned);
        while (links.find()) {
            String href = links.group(1);
            String inner = links.group(2);
            String replacement = inner + " (" + href + ")";
            links.appendReplacement(withLinks, Matcher.quoteReplacement(replacement));
        }
        links.appendTail(withLinks);
        String withBreaks = BLOCK_TAG.matcher(withLinks.toString()).replaceAll("\n");
        String stripped = ANY_TAG.matcher(withBreaks).replaceAll("");
        String decoded = decodeEntities(stripped);
        StringBuilder out = new StringBuilder();
        for (String line : decoded.split("\\R")) {
            String collapsed = line.trim().replaceAll("[ \\t\\x0B\\f]+", " ");
            if (!collapsed.isEmpty()) {
                if (out.length() > 0) out.append('\n');
                out.append(collapsed);
            }
        }
        return out.toString();
    }

    private static String decodeEntities(String input) {
        Matcher m = ENTITY.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        while (m.find()) {
            String token = m.group(1);
            String replacement;
            if (token.startsWith("#x") || token.startsWith("#X")) {
                replacement = codePointString(Integer.parseInt(token.substring(2), 16));
            } else if (token.startsWith("#")) {
                replacement = codePointString(Integer.parseInt(token.substring(1), 10));
            } else {
                replacement = switch (token.toLowerCase(Locale.ROOT)) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    case "nbsp" -> " ";
                    default -> m.group(0);
                };
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String codePointString(int cp) {
        return Character.isValidCodePoint(cp) && cp != 0 ? new String(Character.toChars(cp)) : "";
    }

    static Descriptor descriptorOrNull(byte[] data) {
        try (Archive archive = new Archive(data)) {
            if (archive.isNull()) return null;
        }
        return descriptor(data);
    }

    static Progress progress(byte[] data) {
        try (Archive archive = new Archive(data)) {
            if (archive.isNull()) return null;
            Map<?, ?> root = archive.root("SUBDownload");
            Object ref = archive.field(root, "progress");
            Map<?, ?> progress = archive.typed(ref, "SUBProgress");
            Object number = progress.get("portionComplete");
            if (!(number instanceof Number value) || !Double.isFinite(value.doubleValue())
                    || value.doubleValue() < 0 || value.doubleValue() > 1)
                throw new IllegalArgumentException("Invalid update progress");
            Object phase = archive.field(progress, "phase");
            if (!(phase instanceof String text) || text.length() > 64)
                throw new IllegalArgumentException("Invalid update progress phase");
            return new Progress(text, value.doubleValue(), Boolean.TRUE.equals(progress.get("isStalled")));
        }
    }

    static Long errorCode(Object data) {
        ErrorInfo info = errorInfo(data);
        return info == null ? null : info.code();
    }

    static ErrorInfo errorInfo(Object data) {
        if (data == null) return null;
        if (!(data instanceof byte[] bytes)) throw new IllegalArgumentException("Invalid update error archive");
        try (Archive archive = new Archive(bytes)) {
            if (archive.isNull()) return null;
            Map<?, ?> root = archive.root("NSError");
            Object code = root.get("NSCode");
            if (!(code instanceof Long value)) throw new IllegalArgumentException("Invalid update error");
            Object rawDomain = archive.field(root, "NSDomain");
            String domain = rawDomain instanceof String text
                    && text.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}") ? text : null;
            return new ErrorInfo(domain, value, classifyError(domain, value));
        }
    }

    static String classifyError(String domain, long code) {
        if ("SUBError".equals(domain) && code == 34L) {
            return "Update requires companion update";
        }
        if ("com.apple.MobileAssetError.Download".equals(domain)) {
            if (code == 47L) return "MADownloadNetworkingError";
            if (code == 53L) return "MADownloadTimedOutResource";
            if (code == 65L) return "MADownloadNotConnectedToInternet";
        }
        return null;
    }

    private static long nonnegative(Object value) {
        if (value == null) return -1; // Presence is independent of a legitimate zero.
        if (!(value instanceof Long number) || number < 0) throw new IllegalArgumentException("Invalid update size");
        return number;
    }
    private static String version(Object value, boolean build) {
        if (!(value instanceof String text) || text.length() > 32
                || !text.matches(build ? "[0-9]{1,3}[A-Za-z][0-9]{1,6}[a-z]?" : "[0-9]{1,3}(\\.[0-9]{1,3}){0,3}"))
            throw new IllegalArgumentException("Invalid native update version");
        return text;
    }

    record ErrorInfo(String domain, long code, String diagnostic) { }
    record Progress(String phase, double portion, boolean stalled) { }
    record Descriptor(byte[] archive, String version, String build, long downloadBytes,
                      long requiredSpaceBytes, boolean termsAccepted, boolean awaitingAdmission,
                      String licenseTerms) implements AutoCloseable {
        boolean hasTerms() { return licenseTerms != null && !licenseTerms.isEmpty(); }
        @Override public byte[] archive() { return archive.clone(); }
        @Override public void close() { Arrays.fill(archive, (byte) 0); }
    }

    /** Resolves only requested references; never instantiates classes from the Watch. */
    private static final class Archive implements AutoCloseable {
        final Object decoded;
        final List<?> objects;
        final Object root;
        Archive(byte[] bytes) {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
                throw new IllegalArgumentException("Invalid update archive size");
            decoded = AppleBinaryPropertyList.decode(bytes);
            if (!(decoded instanceof Map<?, ?> map) || !"NSKeyedArchiver".equals(map.get("$archiver"))
                    || !Long.valueOf(100000).equals(map.get("$version"))
                    || !(map.get("$objects") instanceof List<?> list) || list.isEmpty() || list.size() > 4096
                    || !"$null".equals(list.get(0)) || !(map.get("$top") instanceof Map<?, ?> top)
                    || top.size() != 1 || !top.containsKey("root")) {
                IdsMessageProtectionIdentity.wipeValues(decoded);
                throw new IllegalArgumentException("Invalid update keyed archive");
            }
            objects = list;
            try { root = resolve(top.get("root")); }
            catch (RuntimeException invalid) { IdsMessageProtectionIdentity.wipeValues(decoded); throw invalid; }
        }
        Object resolve(Object ref) {
            if (!(ref instanceof AppleBinaryPropertyList.Uid uid)) return ref;
            if (uid.value() == 0) return null;
            if (uid.value() >= objects.size()) throw new IllegalArgumentException("Invalid update reference");
            Object value = objects.get(uid.value());
            if (value instanceof AppleBinaryPropertyList.Uid) throw new IllegalArgumentException("Indirect update reference");
            return value;
        }
        Object field(Map<?, ?> map, String key) { return resolve(map.get(key)); }
        Map<?, ?> root(String name) { return typed(root, name); }
        boolean isNull() {
            if (root == null) return true;
            if (!(root instanceof Map<?, ?> map) || !(resolve(map.get("$class")) instanceof Map<?, ?> type)) return false;
            return "NSNull".equals(type.get("$classname"));
        }
        Map<?, ?> typed(Object value, String name) {
            if (!(value instanceof Map<?, ?> map) || !(resolve(map.get("$class")) instanceof Map<?, ?> type)
                    || !name.equals(type.get("$classname"))) throw new IllegalArgumentException("Unexpected update archive class");
            return map;
        }
        @Override public void close() { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }
}
