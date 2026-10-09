package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.file.*;

/** Private content-addressed native packages, never embedded in Binder state or collection snapshots. */
final class NativeFacePackageStore {
    static final int MAX_PACKAGE_BYTES = 16 * 1024 * 1024;
    private final Path root;
    NativeFacePackageStore(Path root) { this.root = root; }
    String save(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKAGE_BYTES) throw new IOException("Invalid native package size");
        String hash = hash(bytes); Files.createDirectories(root);
        Path path = path(hash);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) ClockFaceSyncJournal.atomicWrite(path, bytes);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid native package file");
        // Parent app-private files directory remains inaccessible to other apps.
        // HAL-created blobs are readable by the owning Bridge APK for chunked exports.
        if (!path.toFile().setReadable(true, false)) throw new IOException("Cannot expose native package to Bridge owner");
        return hash;
    }
    byte[] read(String hash) throws IOException { return readFile(path(hash), hash); }
    private Path path(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid native package digest");
        return root.resolve(hash + ".watchface");
    }
    static byte[] readFile(Path path, String expected) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_PACKAGE_BYTES)
            throw new IOException("Native package is unavailable");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() > MAX_PACKAGE_BYTES - n) throw new IOException("Native package size exceeded");
                out.write(buffer, 0, n);
            }
        }
        byte[] bytes = out.toByteArray();
        if (!hash(bytes).equals(expected)) { java.util.Arrays.fill(bytes, (byte) 0); throw new IOException("Native package digest mismatch"); }
        return bytes;
    }
    static String hash(byte[] bytes) {
        return ClockFaceSyncJournal.sha256(bytes);
    }
}
