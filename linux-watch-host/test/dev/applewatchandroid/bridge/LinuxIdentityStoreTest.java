package dev.applewatchandroid.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.KeyGenerator;

/** Restart, migration and incomplete-state regression; no Bluetooth access. */
public final class LinuxIdentityStoreTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("watch-identities-");
        try {
            LinuxSecretStore fresh = new LinuxSecretStore(root.resolve("fresh"));
            byte[] first = new LinuxIdentityStore(fresh, new SecureRandom()).idsPublicBundle();
            byte[] next = new LinuxIdentityStore(new LinuxSecretStore(root.resolve("fresh")), new SecureRandom()).idsPublicBundle();
            if (!Arrays.equals(first, next)) throw new AssertionError("Installation changed on restart");
            if (!fresh.contains("ids-installation.sealed") || fresh.contains("ids-public.sealed")) {
                throw new AssertionError("Fresh identity is not atomic");
            }
            LinuxSecretStore legacy = new LinuxSecretStore(root.resolve("legacy"));
            try (var identity = IdsMessageProtectionIdentity.generate(new SecureRandom())) {
                var generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                var key = generator.generateKey();
                byte[] bound = IdsMessageProtectionIdentity.bindPublicBundle(identity.publicBundle(),
                        "4a8c08cd-7bdb-5718-b9f6-316651d517b5");
                legacy.store("ids-public.sealed", bound);
                legacy.store("ids-key.sealed", key.getEncoded());
                legacy.store("ids-private.sealed", identity.seal(key));
                byte[] migrated = new LinuxIdentityStore(legacy, new SecureRandom()).idsPublicBundle();
                if (!Arrays.equals(bound, migrated)) throw new AssertionError("Migration replaced the public identity");
                byte[] incomplete = Arrays.copyOf(legacy.load("ids-installation.sealed"), 19);
                reject(() -> LinuxIdentityStore.validatedPublicBundle(incomplete));
                LinuxSecretStore partial = new LinuxSecretStore(root.resolve("partial"));
                partial.store("ids-public.sealed", bound);
                reject(() -> new LinuxIdentityStore(partial, new SecureRandom()).idsPublicBundle());
                if (partial.contains("ids-installation.sealed")) throw new AssertionError("Partial identity replaced");
                LinuxSecretStore mismatched = new LinuxSecretStore(root.resolve("mismatch"));
                mismatched.store("ids-public.sealed", bound);
                mismatched.store("ids-key.sealed", key.getEncoded());
                try (var other = IdsMessageProtectionIdentity.generate(new SecureRandom())) {
                    mismatched.store("ids-private.sealed", other.seal(key));
                }
                reject(() -> new LinuxIdentityStore(mismatched, new SecureRandom()).idsPublicBundle());
                if (mismatched.contains("ids-installation.sealed")) throw new AssertionError("Mismatched keys accepted");
            }
            System.out.println("Linux installation identity: PASS");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private interface Action { void run() throws Exception; }
    private static void reject(Action action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Invalid identity accepted");
    }
}
