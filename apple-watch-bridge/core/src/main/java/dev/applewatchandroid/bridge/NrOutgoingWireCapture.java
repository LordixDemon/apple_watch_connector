package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;

/** Bounded owner-only diagnostic files. Captures encoded frames, not delivery success. */
final class NrOutgoingWireCapture {
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private final Path directory;
    private final int maxFrames;
    private final int maxBytes;
    private int frames;
    private int bytes;
    private boolean disabled;

    NrOutgoingWireCapture(Path parent) throws IOException {
        this(parent, 64, 256 * 1024);
    }

    NrOutgoingWireCapture(Path parent, int maxFrames, int maxBytes) throws IOException {
        if (maxFrames < 1 || maxFrames > 64 || maxBytes < 1 || maxBytes > 256 * 1024) {
            throw new IllegalArgumentException("Invalid NR capture limits");
        }
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
        directory = Files.createTempDirectory(parent, "apple-watch-bridge-nr-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        createFile(directory.resolve("manifest.tsv"),
                "index\tkind\ttopic\ttype\tresponse\tbytes\tfile\tunixMillis\n".getBytes(StandardCharsets.UTF_8));
    }

    Path directory() { return directory; }
    int frameCount() { return frames; }

    /** Returns one safe diagnostic on failure/limit, then becomes a no-op. */
    synchronized String record(String kind, String topic, int type, boolean response, byte[] frame) {
        if (disabled || !allowed(kind, topic, type)) return null;
        if (frame == null || frame.length == 0 || frame.length > MAX_FRAME_BYTES
                || frames >= maxFrames || frame.length > maxBytes - bytes) {
            disabled = true;
            return "capture limit reached";
        }
        String name = String.format(Locale.ROOT, "%03d-%s-%d.bin", frames + 1, kind, type);
        try {
            createFile(directory.resolve(name), frame);
            String row = (frames + 1) + "\t" + kind + "\t" + topic + "\t" + type + "\t"
                    + response + "\t" + frame.length + "\t" + name + "\t" + System.currentTimeMillis() + "\n";
            Files.write(directory.resolve("manifest.tsv"), row.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            frames++;
            bytes += frame.length;
            return null;
        } catch (IOException | RuntimeException failure) {
            disabled = true;
            return "capture storage unavailable (" + failure.getClass().getSimpleName() + ")";
        }
    }

    private static boolean allowed(String kind, String topic, int type) {
        if ("hello".equals(kind)) return IdsIpsecServiceRoute.CONTROL_SERVICE.equals(topic) && type == 1;
        if (!"protobuf".equals(kind)) return false;
        return (NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(topic) && (type == 2 || type == 4))
                || (NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(topic) && (type == 3 || type == 5));
    }

    private static void createFile(Path path, byte[] content) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer data = ByteBuffer.wrap(content);
            while (data.hasRemaining()) channel.write(data);
        }
    }
}
