package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only archive inventory: Watch7,5 23S303 SystemDiagnosticIDS, types 6/7.
 * The payload is UTF-8, despite the outer IDS Protobuf envelope. The Watch
 * replies with a new type-7 request containing an NSKeyedArchiver array.
 * No named Objective-C classes from that archive are instantiated.
 */
final class SysdiagnoseArchiveInventory {
    static final String COMMAND = "REQUEST_DIAGNOSTIC_ARCHIVES";
    static final String TOPIC = "com.apple.private.alloy.sysdiagnose";
    static final int LIST_REQUEST = 6;
    static final int LIST_RESULT = 7;
    static final int MAX_BYTES = 256 * 1024;
    private final Set<String> pending = new LinkedHashSet<>();

    static void requireReadOnlyRequest(int type, byte[] payload) {
        if (type != LIST_REQUEST || payload == null || payload.length != 36) {
            throw new IllegalArgumentException("Only a sysdiagnose archive-list request with a UUID is supported");
        }
        String nonce = new String(payload, StandardCharsets.US_ASCII);
        if (!UUID.fromString(nonce).toString().equalsIgnoreCase(nonce)) {
            throw new IllegalArgumentException("Invalid sysdiagnose inventory correlation");
        }
    }

    void queued(int type, byte[] payload) {
        requireReadOnlyRequest(type, payload);
        if (pending.size() >= 4) pending.remove(pending.iterator().next());
        pending.add(new String(payload, StandardCharsets.US_ASCII));
    }

    /** Empty inventories omit the nonce in Apple's implementation. They are
     * reported as uncorrelated and never used to advance pairing/setup state. */
    Observation receive(int type, byte[] payload, Path parent) throws IOException {
        if (type != LIST_RESULT || pending.isEmpty()) return null;
        if (payload == null || payload.length == 0 || payload.length > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid sysdiagnose inventory size");
        }
        Object decoded = AppleBinaryPropertyList.decode(payload);
        try {
            if (!(decoded instanceof Map<?, ?> archive)
                    || !"NSKeyedArchiver".equals(archive.get("$archiver"))
                    || !Long.valueOf(100000).equals(archive.get("$version"))
                    || !(archive.get("$objects") instanceof List<?> objects)
                    || objects.isEmpty() || objects.size() > 4096
                    || !(archive.get("$top") instanceof Map<?, ?> top)) {
                throw new IllegalArgumentException("Invalid sysdiagnose keyed archive");
            }
            Object root = resolve(objects, top.get("root"));
            if (!(root instanceof Map<?, ?> array)
                    || !Set.of("NSArray", "NSMutableArray").contains(className(objects, array))
                    || !(array.get("NS.objects") instanceof List<?> items)
                    || items.isEmpty() || items.size() > 1025) {
                throw new IllegalArgumentException("Invalid sysdiagnose archive-list root");
            }
            Object first = resolve(objects, items.get(0));
            String correlation = first instanceof String string ? string : null;
            boolean empty = items.size() == 1 && first instanceof Map<?, ?> object
                    && "NSNull".equals(className(objects, object));
            if (!empty && (correlation == null || !pending.contains(correlation))) return null;
            Path directory = Files.createTempDirectory(parent, "apple-watch-bridge-sysdiagnose-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path file = directory.resolve("inventory.bplist");
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer data = ByteBuffer.wrap(payload);
                while (data.hasRemaining()) channel.write(data);
            }
            // The nonce-less empty reply cannot identify one of several requests.
            if (empty) pending.clear(); else pending.remove(correlation);
            return new Observation(!empty, empty ? 0 : items.size() - 1, file, empty ? null : correlation);
        } finally {
            IdsMessageProtectionIdentity.wipeValues(decoded);
        }
    }

    void reset() { pending.clear(); }

    private static Object resolve(List<?> objects, Object reference) {
        if (!(reference instanceof AppleBinaryPropertyList.Uid uid)
                || uid.value() <= 0 || uid.value() >= objects.size()) {
            throw new IllegalArgumentException("Invalid sysdiagnose archive reference");
        }
        return objects.get(uid.value());
    }

    private static String className(List<?> objects, Map<?, ?> object) {
        Object definition = resolve(objects, object.get("$class"));
        if (!(definition instanceof Map<?, ?> dictionary)
                || !(dictionary.get("$classname") instanceof String name)) {
            throw new IllegalArgumentException("Invalid sysdiagnose archive class descriptor");
        }
        return name;
    }

    static final class Observation {
        final boolean correlated;
        final int entries;
        final Path file;
        final String correlation;
        Observation(boolean correlated, int entries, Path file, String correlation) {
            this.correlated = correlated;
            this.entries = entries;
            this.file = file;
            this.correlation = correlation;
        }
    }
}
