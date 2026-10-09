package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;
import java.util.UUID;

/** Bounded encrypted inbox. Staging never advances native Health anchors or publishes samples. */
final class HealthInboundJournal {
    static final int MAX_RECORDS = 128;
    static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    enum Outcome { STAGED_ENCRYPTED, DUPLICATE, CONFLICT, FULL }
    private final Path directory;
    private final UUID pair;
    @FunctionalInterface interface Reader { void accept(HealthDataEventCodec.Event event) throws IOException; }
    HealthInboundJournal(Path root, UUID pair) {
        if (root == null || pair == null) throw new IllegalArgumentException("Missing Health inbox pair");
        this.directory = root.resolve(pair.toString()); this.pair = pair;
    }

    synchronized Outcome stage(HealthDataEventCodec.Event event, UUID currentEpoch) throws IOException {
        if (!pair.equals(event.pair) || currentEpoch == null || !currentEpoch.equals(event.epoch)) {
            throw new IllegalArgumentException("Health inbox pair/epoch mismatch");
        }
        Files.createDirectories(directory);
        Path target = directory.resolve(event.messageId + ".aoverc");
        if (Files.exists(target)) {
            if (Files.size(target) > HealthDataEventCodec.MAX_FRAME) return Outcome.CONFLICT;
            byte[] old = Files.readAllBytes(target), a = null, b = null, ka = null, kb = null;
            try (var stored = HealthDataEventCodec.decode(old)) {
                a = stored.encrypted(); b = event.encrypted(); ka = stored.peerKey(); kb = event.peerKey();
                // A new transport epoch is permitted for an unchanged encrypted native message.
                return stored.pair.equals(event.pair) && stored.messageId.equals(event.messageId)
                        && java.util.Objects.equals(stored.responseTo,event.responseTo)
                        && Arrays.equals(a,b) && (ka == null || kb == null || Arrays.equals(ka,kb))
                        ? Outcome.DUPLICATE : Outcome.CONFLICT;
            } catch (IllegalArgumentException invalid) { return Outcome.CONFLICT; }
            finally { wipe(old); wipe(a); wipe(b); wipe(ka); wipe(kb); }
        }
        byte[] record = HealthDataEventCodec.encode(event);
        Path temporary = null;
        try {
            int count = 0; long total = 0;
            try (var paths = Files.newDirectoryStream(directory)) {
                for (Path path : paths) {
                    // Include abandoned temp files in the byte budget rather than hiding their size.
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Health inbox entry");
                    total += Files.size(path); count++;
                    if (count >= MAX_RECORDS || total + record.length > MAX_TOTAL_BYTES) return Outcome.FULL;
                }
            }
            temporary = Files.createTempFile(directory,"incoming-",".tmp");
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(record);
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); temporary = null;
            try (FileChannel parent = FileChannel.open(directory,StandardOpenOption.READ)) { parent.force(true); }
            return Outcome.STAGED_ENCRYPTED;
        } finally { if (temporary != null) Files.deleteIfExists(temporary); wipe(record); }
    }
    /** Replay original metadata, including its original transport epoch. Never restage or delete it. */
    synchronized int replay(Reader reader) throws IOException {
        if (reader == null) throw new IllegalArgumentException("Missing Health inbox reader");
        if (!Files.exists(directory)) return 0;
        java.util.List<Path> records = new java.util.ArrayList<>();
        long total = 0; int entries = 0;
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Health inbox entry");
                if (++entries > MAX_RECORDS || (total += Files.size(path)) > MAX_TOTAL_BYTES) {
                    throw new IOException("Health inbox replay budget exceeded");
                }
                if (path.getFileName().toString().endsWith(".aoverc")) records.add(path);
            }
        }
        records.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        int replayed = 0;
        for (Path path : records) {
            if (Files.size(path) > HealthDataEventCodec.MAX_FRAME) throw new IOException("Oversized Health inbox record");
            byte[] bytes = Files.readAllBytes(path);
            try (var event = HealthDataEventCodec.decode(bytes)) {
                if (!pair.equals(event.pair) || !path.getFileName().toString().equals(event.messageId + ".aoverc")) {
                    throw new IOException("Health inbox record identity mismatch");
                }
                reader.accept(event); replayed++;
            } catch (IllegalArgumentException invalid) { throw new IOException("Invalid Health inbox record",invalid); }
            finally { wipe(bytes); }
        }
        return replayed;
    }
    private static void wipe(byte[] value) { if(value != null) Arrays.fill(value,(byte)0); }
}
