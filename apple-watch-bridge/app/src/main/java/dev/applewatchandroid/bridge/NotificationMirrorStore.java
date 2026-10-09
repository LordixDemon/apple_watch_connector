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
    private final UUID pairing;
    private final AtomicFile file;
    private final String alias;
    NotificationMirrorStore(Context context, String pairingId) {
        pairing = UUID.fromString(pairingId);
        alias = "apple-watch-bridge.notification-mirror.v1." + pairing;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "notification-mirror-" + pairing + ".v1.aesgcm"));
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
        byte[] plaintext = queue.encode(), sealed = null;
        FileOutputStream output = null;
        try {
            sealed = NotificationMirrorEnvelope.seal(plaintext, key(true), pairing);
            output = file.startWrite(); output.write(sealed); file.finishWrite(output); output = null;
        } finally { if (output != null) file.failWrite(output); wipe(plaintext); wipe(sealed); }
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
