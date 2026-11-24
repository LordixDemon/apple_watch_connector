package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native SUBMessageEndpoint binary-plist envelopes (23S303 and 24R365). */
final class SoftwareUpdateCodec {
    static final String TOPIC = "com.apple.private.alloy.otaupdate";
    static final int MAX_BYTES = 128 * 1024;
    static final String QUERY = "QueryManagerState", SCAN = "ScanForUpdates";
    static final String DOWNLOAD = "StartDownload", INSTALL = "InstallUpdate";
    static final String TERMS = "UserDidAcceptTermsAndConditionsWithReply";
    private static final Set<String> COMMANDS = Set.of(QUERY, SCAN, DOWNLOAD, INSTALL, TERMS, "AddClient");

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
            return new Descriptor(data.clone(), version, build,
                    nonnegative(root.get("downloadSize")), nonnegative(root.get("TotalRequiredFreeSpace")),
                    Boolean.TRUE.equals(root.get("userDidAcceptTermsAndConditions")),
                    Boolean.TRUE.equals(root.get("isAwaitingAdmissionControlForInstallation")));
        }
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
        if (data == null) return null;
        if (!(data instanceof byte[] bytes)) throw new IllegalArgumentException("Invalid update error archive");
        try (Archive archive = new Archive(bytes)) {
            if (archive.isNull()) return null;
            Map<?, ?> root = archive.root("NSError");
            Object code = root.get("NSCode");
            if (!(code instanceof Long value)) throw new IllegalArgumentException("Invalid update error");
            return value;
        }
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

    record Progress(String phase, double portion, boolean stalled) { }
    record Descriptor(byte[] archive, String version, String build, long downloadBytes,
                      long requiredSpaceBytes, boolean termsAccepted, boolean awaitingAdmission) implements AutoCloseable {
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
