package dev.applewatchandroid.bridge;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** App-private, backup-excluded journal for one owner-confirmed pair. */
final class NotificationMirrorStore {
    private static final String FILE_PREFIX = "notification-mirror-";
    private static final String ALIAS_PREFIX = "apple-watch-bridge.notification-mirror.v1.";
    private final UUID pairing;
    private final AtomicFile file;
    private final String alias;
    NotificationMirrorStore(Context context, String pairingId) {
        pairing = UUID.fromString(pairingId);
        alias = ALIAS_PREFIX + pairing;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), FILE_PREFIX + pairing + ".v1.aesgcm"));
    }
    NotificationMirrorQueue load() throws Exception {
        byte[] sealed = null, plaintext = null;
        try {
            // AtomicFile resolves interrupted writes before opening the current file.
            try (var input = file.openRead()) {
                sealed = input.readNBytes(NotificationMirrorEnvelope.MAX_BYTES + 1);
            } catch (java.io.FileNotFoundException absent) { return new NotificationMirrorQueue(); }
            plaintext = NotificationMirrorEnvelope.open(sealed, key(false), pairing);
            return NotificationMirrorQueue.decode(plaintext);
        } finally { wipe(sealed); wipe(plaintext); }
    }
    void save(NotificationMirrorQueue queue) throws Exception {
        byte[] plaintext = queue.encode();
        try { saveEncoded(plaintext); }
        finally { wipe(plaintext); }
    }
    void saveEncoded(byte[] plaintext) throws Exception {
        byte[] sealed = null;
        FileOutputStream output = null;
        try {
            sealed = NotificationMirrorEnvelope.seal(plaintext, key(true), pairing);
            output = file.startWrite(); output.write(sealed); file.finishWrite(output); output = null;
        } finally { if (output != null) file.failWrite(output); wipe(sealed); }
    }
    static void purgeOtherPairings(Context context, String activePairingId) {
        if (context == null || activePairingId == null || activePairingId.isBlank()) return;
        UUID activeUuid;
        try { activeUuid = UUID.fromString(activePairingId); }
        catch (IllegalArgumentException ignored) { return; }
        String keepBase = FILE_PREFIX + activeUuid + ".v1.aesgcm";
        String keepAlias = ALIAS_PREFIX + activeUuid;
        File dir = context.getNoBackupFilesDir();
        if (dir != null) {
            File[] files = dir.listFiles((ignored, name) -> name.startsWith(FILE_PREFIX) && !name.startsWith(keepBase));
            if (files != null) {
                for (File stale : files) {
                    try { stale.delete(); } catch (SecurityException ignored) { }
                }
            }
        }
        try {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            var aliases = keys.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (alias.startsWith(ALIAS_PREFIX) && !alias.equals(keepAlias)) {
                    try { keys.deleteEntry(alias); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
    }
    private SecretKey key(boolean create) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (keys.getKey(alias, null) instanceof SecretKey existing) return existing;
        if (!create) throw new IllegalStateException("Notification journal key missing");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
}
