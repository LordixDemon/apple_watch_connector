package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.file.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class PairingReplacementBackupTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String PAIR = "79105820-c9ef-4b5c-b2aa-16938715ed30";
    private static final String SESSION = "pairing-session.v2.aesgcm", BOND = "bluetooth-bond.v1.aesgcm";
    private static final byte[] SEALED_PAIR = {1, 8, 2, 4}, SEALED_BOND = {9, 5, 7};
    private Path directory() throws IOException {
        Path dir = temporary.newFolder().toPath();
        Files.write(dir.resolve(SESSION), SEALED_PAIR);
        Files.write(dir.resolve(BOND), SEALED_BOND);
        return dir;
    }
    private Path archive(Path directory) throws IOException {
        try (var rows = Files.list(directory.resolve("pairing-backups"))) { return rows.findFirst().orElseThrow(); }
    }
    @Test public void replacementPreservesEncryptedRecordsAndUnrelatedIdentityFiles() throws Exception {
        Path dir = directory();
        Files.write(dir.resolve("ids-identity"), new byte[]{6});
        var backup = PairingReplacementBackup.stage(dir, PAIR, PAIR, 42);
        assertArrayEquals(SEALED_PAIR, Files.readAllBytes(dir.resolve(SESSION)));
        assertArrayEquals(SEALED_PAIR, Files.readAllBytes(archive(dir).resolve(SESSION)));
        assertArrayEquals(SEALED_BOND, Files.readAllBytes(archive(dir).resolve(BOND)));
        backup.retire();
        assertFalse(Files.exists(dir.resolve(SESSION)));
        assertFalse(Files.exists(dir.resolve(BOND)));
        assertArrayEquals(new byte[]{6}, Files.readAllBytes(dir.resolve("ids-identity")));
        backup.restoreMissing();
        assertArrayEquals(SEALED_PAIR, Files.readAllBytes(dir.resolve(SESSION)));
        assertArrayEquals(SEALED_BOND, Files.readAllBytes(dir.resolve(BOND)));
    }
    @Test public void changedRecordAbortsBeforeRemovingAnyOriginal() throws Exception {
        Path dir = directory();
        var backup = PairingReplacementBackup.stage(dir, PAIR, null, 0);
        Files.write(dir.resolve(BOND), new byte[]{3});
        assertThrows(IOException.class, backup::retire);
        assertArrayEquals(SEALED_PAIR, Files.readAllBytes(dir.resolve(SESSION)));
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(dir.resolve(BOND)));
    }
    @Test public void corruptArchiveCannotBeRestoredOrOverwriteNewRecords() throws Exception {
        Path dir = directory();
        var backup = PairingReplacementBackup.stage(dir, PAIR, PAIR, 1);
        backup.retire();
        Files.write(archive(dir).resolve(SESSION), new byte[]{0});
        assertThrows(IOException.class, backup::restoreMissing);
        assertFalse(Files.exists(dir.resolve(SESSION)));
        Files.write(dir.resolve(SESSION), new byte[]{3});
        backup.restoreMissing();
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(dir.resolve(SESSION)));
    }
    @Test public void stagingFailureAndMismatchedConfirmationPreserveOriginals() throws Exception {
        Path dir = directory();
        assertThrows(IllegalArgumentException.class, () -> PairingReplacementBackup.stage(dir, PAIR,
                "ba670763-622e-4fab-a98a-a507f65e0b75", 0));
        Files.delete(dir.resolve(BOND));
        Files.createSymbolicLink(dir.resolve(BOND), dir.resolve(SESSION));
        assertThrows(IOException.class, () -> PairingReplacementBackup.stage(dir, PAIR, null, 0));
        assertArrayEquals(SEALED_PAIR, Files.readAllBytes(dir.resolve(SESSION)));
        assertTrue(Files.isSymbolicLink(dir.resolve(BOND)));
    }
}
