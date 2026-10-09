package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Two-phase local replacement of encrypted records. No key/plaintext/IDS
 * identity is copied or removed. Caller owns the exclusive setup/HAL gate. */
final class PairingReplacementBackup {
    private static final String[] FILES = {"pairing-session.v2.aesgcm", "bluetooth-bond.v1.aesgcm"};
    private static final int LIMIT = 1024 * 1024;
    private final Path source, archive;
    private final Map<String, byte[]> digests;
    private PairingReplacementBackup(Path source, Path archive, Map<String, byte[]> digests) {
        this.source = source; this.archive = archive; this.digests = digests;
    }
    static PairingReplacementBackup stage(Path directory, String pairId, String confirmation, long confirmedAt)
            throws IOException {
        String canonical = HalHostArguments.canonicalUuid(pairId);
        if (!canonical.equals(pairId) || (confirmation != null && !pairId.equals(confirmation))) {
            throw new IllegalArgumentException("Replacement identity does not match saved confirmation");
        }
        if (!Files.isRegularFile(directory.resolve(FILES[0]), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Saved encrypted pair is missing");
        }
        Path archive = directory.resolve("pairing-backups").resolve(UUID.randomUUID().toString());
        Files.createDirectories(archive);
        Map<String, byte[]> hashes = new LinkedHashMap<>();
        for (String name : FILES) {
            Path file = directory.resolve(name);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            byte[] ciphertext = read(file);
            try {
                Path copy = archive.resolve(name);
                Files.write(copy, ciphertext, StandardOpenOption.CREATE_NEW);
                sync(copy);
                byte[] verification = read(copy);
                try {
                    if (!MessageDigest.isEqual(ciphertext, verification)) throw new IOException("Encrypted backup verification failed");
                } finally { Arrays.fill(verification, (byte) 0); }
                hashes.put(name, digest(ciphertext));
            } finally { Arrays.fill(ciphertext, (byte) 0); }
        }
        Path metadata = archive.resolve("identity.properties");
        Files.writeString(metadata, "pairId=" + pairId + "\nconfirmation="
                + (confirmation == null ? "" : confirmation) + "\nconfirmedAt=" + confirmedAt + "\n",
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        sync(metadata);
        return new PairingReplacementBackup(directory, archive, hashes);
    }
    void retire() throws IOException {
        // Verify every source before removing any source. Changed bytes indicate
        // a stale replacement request or another writer; abort rather than retire it.
        for (var entry : digests.entrySet()) {
            byte[] bytes = read(source.resolve(entry.getKey()));
            try {
                if (!MessageDigest.isEqual(entry.getValue(), digest(bytes))) throw new IOException("Saved pair changed during backup");
            } finally { Arrays.fill(bytes, (byte) 0); }
        }
        try {
            for (String name : digests.keySet()) Files.delete(source.resolve(name));
        } catch (IOException failure) {
            try { restoreMissing(); } catch (IOException restore) { failure.addSuppressed(restore); }
            throw failure;
        }
    }
    void restoreMissing() throws IOException {
        for (String name : digests.keySet()) {
            Path file = source.resolve(name);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] ciphertext = read(archive.resolve(name));
                try {
                    if (!MessageDigest.isEqual(digests.get(name), digest(ciphertext))) {
                        throw new IOException("Encrypted archive changed before restoration");
                    }
                    Files.write(file, ciphertext, StandardOpenOption.CREATE_NEW);
                    sync(file);
                } finally { Arrays.fill(ciphertext, (byte) 0); }
            }
        }
    }
    private static byte[] read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) < 1 || Files.size(file) > LIMIT) throw new IOException("Invalid encrypted backup file");
        return Files.readAllBytes(file);
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void sync(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }
}
