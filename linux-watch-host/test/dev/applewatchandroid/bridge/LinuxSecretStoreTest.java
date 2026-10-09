package dev.applewatchandroid.bridge;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

/** Standalone Linux persistence regression, compiled against the production worker. */
public final class LinuxSecretStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("watch-store-test-");
        try {
            LinuxSecretStore store = new LinuxSecretStore(directory);
            byte[] value = {1, 2, 3, 4};
            store.store("pairing.sealed", value);
            if (!Arrays.equals(value, new LinuxSecretStore(directory).load("pairing.sealed"))) throw new AssertionError("Restart readback");
            if (!Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"))) throw new AssertionError("Directory permissions");
            if (!Files.getPosixFilePermissions(directory.resolve("storage.key")).equals(PosixFilePermissions.fromString("rw-------"))) throw new AssertionError("Key permissions");
            Files.copy(directory.resolve("pairing.sealed"), directory.resolve("bond.sealed"));
            mustReject(() -> store.load("bond.sealed"));
            byte[] corrupted = Files.readAllBytes(directory.resolve("pairing.sealed"));
            corrupted[corrupted.length - 1] ^= 1;
            Files.write(directory.resolve("pairing.sealed"), corrupted);
            mustReject(() -> store.load("pairing.sealed"));
            mustReject(() -> store.store("../escape.sealed", value));
            Files.delete(directory.resolve("bond.sealed"));
            Files.createSymbolicLink(directory.resolve("bond.sealed"), directory.resolve("pairing.sealed"));
            mustReject(() -> store.load("bond.sealed"));
            try (var files = Files.list(directory)) {
                if (files.anyMatch(path -> path.getFileName().toString().startsWith(".pending-"))) throw new AssertionError("Temporary plaintext or ciphertext file retained");
            }
            System.out.println("Linux encrypted store: PASS");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private interface Action { void run() throws Exception; }
    private static void mustReject(Action action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Invalid record accepted");
    }
}
