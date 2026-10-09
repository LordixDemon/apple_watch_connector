package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Separate authenticated format/key purpose; pairing identity is authenticated as AAD. */
final class NotificationMirrorEnvelope {
    private static final byte[] MAGIC = {'A', 'W', 'N', 'E', 1};
    static final int MAX_BYTES = NotificationMirrorQueue.MAX_BYTES + 33;
    static byte[] seal(byte[] plaintext, SecretKey key, UUID pairing) throws Exception {
        if (plaintext == null || plaintext.length < 9 || plaintext.length > NotificationMirrorQueue.MAX_BYTES || pairing == null)
            throw new IllegalArgumentException("Invalid notification plaintext");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new SecureRandom());
        byte[] iv = cipher.getIV(), ciphertext = null;
        try {
            if (iv == null || iv.length != 12) throw new IllegalArgumentException("Invalid GCM IV");
            cipher.updateAAD(MAGIC); cipher.updateAAD(pairing.toString().getBytes(StandardCharsets.US_ASCII));
            ciphertext = cipher.doFinal(plaintext);
            byte[] result = new byte[MAGIC.length + iv.length + ciphertext.length];
            System.arraycopy(MAGIC, 0, result, 0, MAGIC.length);
            System.arraycopy(iv, 0, result, MAGIC.length, iv.length);
            System.arraycopy(ciphertext, 0, result, MAGIC.length + iv.length, ciphertext.length);
            return result;
        } finally { if (iv != null) Arrays.fill(iv, (byte) 0); if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0); }
    }
    static byte[] open(byte[] container, SecretKey key, UUID pairing) throws Exception {
        if (container == null || container.length < 42 || container.length > MAX_BYTES || pairing == null)
            throw new IllegalArgumentException("Invalid notification envelope");
        for (int i = 0; i < MAGIC.length; i++) if (container[i] != MAGIC[i]) throw new IllegalArgumentException("Invalid envelope version");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, container, MAGIC.length, 12));
        cipher.updateAAD(MAGIC); cipher.updateAAD(pairing.toString().getBytes(StandardCharsets.US_ASCII));
        return cipher.doFinal(container, MAGIC.length + 12, container.length - MAGIC.length - 12);
    }
}
