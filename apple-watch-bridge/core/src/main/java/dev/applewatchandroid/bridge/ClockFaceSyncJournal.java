package dev.applewatchandroid.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.Set;

/** Recoverable, pair-scoped inbound records. A journal receipt is not face application. */
final class ClockFaceSyncJournal {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_RECORDS = 8192;
    private final Path directory;

    record Receipt(boolean duplicate, int changes, int bytes) { }

    ClockFaceSyncJournal(Path root, String pairingId) {
        if (pairingId == null || !UUID.fromString(pairingId).toString().equals(pairingId)) {
            throw new IllegalArgumentException("Clockface journal requires canonical pairing UUID");
        }
        directory = root.resolve(pairingId);
    }

    synchronized Receipt commit(byte[] request) throws IOException {
        int changes;
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(request)) {
            changes = frame.changes.size();
            validateChanges(frame);
        }
        return commitValidated(request, changes);
    }

    /** Caller has parsed the frame and validated all change envelopes. */
    synchronized Receipt commitValidated(byte[] request, int changes) throws IOException {
        Files.createDirectories(directory);
        Path destination = directory.resolve(sha256(request) + ".sydata");
        if (Files.isRegularFile(destination)) {
            if (Files.size(destination) != request.length
                    || !MessageDigest.isEqual(Files.readAllBytes(destination), request)) {
                throw new IOException("Clockface journal record failed integrity check");
            }
            syncDirectory();
            return new Receipt(true, changes, request.length);
        }
        File[] files = directory.toFile().listFiles();
        if (files == null) throw new IOException("Cannot inspect clockface journal");
        long bytes = 0;
        int records = 0;
        for (File file : files) {
            if (!file.isFile()) continue;
            bytes += file.length();
            records++;
        }
        if (records >= MAX_RECORDS || bytes > MAX_BYTES - request.length) {
            throw new IOException("Clockface journal capacity exceeded");
        }
        Path temporary = Files.createTempFile(directory, "pending-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(request);
                output.getFD().sync();
            }
            // Require atomic replacement; a weaker move must not receive an ACK.
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            syncDirectory();
            return new Receipt(false, changes, request.length);
        } finally { Files.deleteIfExists(temporary); }
    }

    static void validateChanges(ClockFaceSyncFrame frame) {
        for (byte[] change : frame.changes) {
            try (NtkSyncMessageCodec.Message message = ClockFaceSyncFrame.decodeChange(change)) {
                // Validate envelopes without applying operations or inflating resources.
                if (message.numberOfParts() > 4096 || message.partNumber() > 4096) {
                    throw new IllegalArgumentException("Clockface multipart bound exceeded");
                }
            }
        }
    }

    private void syncDirectory() throws IOException {
        try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
            parent.force(true);
        }
    }

    Path recordPath(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid journal digest");
        return directory.resolve(digest + ".sydata");
    }

    /** Only records no longer referenced by atomically saved receiver state may be removed. */
    synchronized int discardUnreferenced(Set<String> retained) throws IOException {
        for (String digest : retained) recordPath(digest);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        int removed = 0;
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.matches("[0-9a-f]{64}\\.sydata")
                        || retained.contains(name.substring(0, 64))
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                Files.delete(file);
                removed++;
            }
        }
        if (removed > 0) syncDirectory();
        return removed;
    }

    static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "pending-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
