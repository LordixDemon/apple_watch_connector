package dev.applewatchandroid.bridge;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Survives process restart so NWSC request sequences stay strictly ahead of
 * the peer's stored {@code remotePubKeySeqNo}.
 */
final class NwServiceConnectorSequenceStore {
    private static final long RESERVATION_SIZE = 10_000_000L;
    static volatile File storeFile =
            ProtocolPaths.files().resolve("nwsc-sequence.v1").toFile();

    private NwServiceConnectorSequenceStore() {
    }

    static void setStoreFileForTesting(File file) {
        storeFile = file != null
                ? file
                : ProtocolPaths.files().resolve("nwsc-sequence.v1").toFile();
    }

    static synchronized long load() {
        File file = storeFile;
        if (!file.isFile()) {
            return 0L;
        }
        try (RandomAccessFile raf =
                     new RandomAccessFile(file, "r")) {
            if (raf.length() == 0 || raf.length() > 32) {
                throw new IllegalStateException("NWSC sequence file has invalid length");
            }
            byte[] raw =
                    new byte[(int) raf.length()];
            raf.readFully(raw);
            String text =
                    new String(raw, StandardCharsets.US_ASCII).trim();
            if (text.isEmpty()) {
                throw new IllegalStateException("NWSC sequence file is empty");
            }
            return Long.parseUnsignedLong(text);
        } catch (IOException | NumberFormatException error) {
            throw new IllegalStateException("Cannot restore the NWSC sequence reservation", error);
        }
    }

    static synchronized void save(long nextSequence) {
        File file = storeFile;
        File parent =
                file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create NWSC sequence store directory");
        }
        File tmp =
                new File(
                        file.getAbsolutePath() + ".tmp");
        try (RandomAccessFile raf =
                     new RandomAccessFile(tmp, "rw")) {
            raf.setLength(0);
            raf.write(
                    Long.toUnsignedString(nextSequence)
                            .getBytes(StandardCharsets.US_ASCII));
            raf.write('\n');
            raf.getFD().sync();
        } catch (IOException error) {
            tmp.delete();
            throw new IllegalStateException("Cannot persist NWSC sequence reservation", error);
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            tmp.delete();
            throw new IllegalStateException("Cannot atomically replace NWSC sequence reservation", error);
        }
    }

    static long chooseSeed(
            long clockSeed,
            long persisted) {
        if (persisted != 0L) {
            long max =
                    Long.compareUnsigned(clockSeed, persisted) >= 0
                            ? clockSeed
                            : persisted;
            return reserveAfter(max);
        }
        return clockSeed == 0L ? 1L : clockSeed;
    }

    static long reserveAfter(long sequence) {
        long reserved = sequence + RESERVATION_SIZE;
        if (Long.compareUnsigned(reserved, sequence) <= 0) {
            throw new IllegalStateException("NWSC sequence reservation exhausted");
        }
        return reserved;
    }
}
