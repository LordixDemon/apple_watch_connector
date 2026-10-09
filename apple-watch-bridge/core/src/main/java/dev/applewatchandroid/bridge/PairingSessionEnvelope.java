package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Authenticated container for a serialized {@link PairingSessionRecord}.
 *
 * <p>The format is magic/version, IV length, IV, then AES-GCM ciphertext
 * including its 128-bit tag. Magic/version is also authenticated as AAD.</p>
 */
final class PairingSessionEnvelope {
    static final byte[] MAGIC =
            new byte[]{'A', 'W', 'P', 'E', 2};
    static final int IV_LENGTH = 12;
    static final int TAG_LENGTH_BITS = 128;
    static final int MAX_CONTAINER_LENGTH =
            PairingSessionRecord.MAX_SERIALIZED_LENGTH
                    + MAGIC.length
                    + 1
                    + IV_LENGTH
                    + TAG_LENGTH_BITS / 8;

    private PairingSessionEnvelope() {
    }

    static byte[] encrypt(
            byte[] plaintext,
            SecretKey key,
            SecureRandom random)
            throws Exception {
        if (plaintext == null
                || plaintext.length == 0
                || plaintext.length
                > PairingSessionRecord.MAX_SERIALIZED_LENGTH
                || key == null
                || random == null) {
            throw new IllegalArgumentException(
                    "Pairing-session encryption input is invalid");
        }
        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = null;
        try {
            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding");
            // AndroidKeyStore keys created with randomized encryption
            // prohibit a caller-supplied nonce. Let the provider generate
            // it, then persist the returned IV alongside the ciphertext.
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    key,
                    random);
            byte[] providerIv = cipher.getIV();
            if (providerIv == null
                    || providerIv.length != IV_LENGTH) {
                wipe(providerIv);
                throw new IllegalStateException(
                        "AES-GCM provider returned an invalid IV");
            }
            System.arraycopy(
                    providerIv,
                    0,
                    iv,
                    0,
                    IV_LENGTH);
            wipe(providerIv);
            cipher.updateAAD(MAGIC);
            ciphertext = cipher.doFinal(plaintext);
            return buildContainer(
                    iv,
                    ciphertext);
        } finally {
            wipe(iv);
            wipe(ciphertext);
        }
    }

    static byte[] decrypt(
            byte[] container,
            SecretKey key)
            throws Exception {
        if (container == null
                || container.length
                < MAGIC.length
                + 1
                + IV_LENGTH
                + TAG_LENGTH_BITS / 8
                || container.length > MAX_CONTAINER_LENGTH
                || key == null) {
            throw new IllegalArgumentException(
                    "Encrypted pairing-session container size is invalid");
        }
        int difference = 0;
        for (int index = 0; index < MAGIC.length; index++) {
            difference |= container[index] ^ MAGIC[index];
        }
        if (difference != 0) {
            throw new IllegalArgumentException(
                    "Encrypted pairing-session magic is invalid");
        }
        int ivLength = container[MAGIC.length] & 0xff;
        int ciphertextOffset =
                MAGIC.length + 1 + ivLength;
        if (ivLength != IV_LENGTH
                || ciphertextOffset
                + TAG_LENGTH_BITS / 8
                > container.length) {
            throw new IllegalArgumentException(
                    "Encrypted pairing-session IV is invalid");
        }
        byte[] iv = Arrays.copyOfRange(
                container,
                MAGIC.length + 1,
                ciphertextOffset);
        try {
            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(
                            TAG_LENGTH_BITS,
                            iv));
            cipher.updateAAD(MAGIC);
            return cipher.doFinal(
                    container,
                    ciphertextOffset,
                    container.length - ciphertextOffset);
        } finally {
            wipe(iv);
        }
    }

    static byte[] encryptWithIv(
            byte[] plaintext,
            SecretKey key,
            byte[] iv)
            throws Exception {
        if (plaintext == null
                || plaintext.length == 0
                || plaintext.length
                > PairingSessionRecord.MAX_SERIALIZED_LENGTH
                || key == null
                || iv == null
                || iv.length != IV_LENGTH) {
            throw new IllegalArgumentException(
                    "Pairing-session encryption input is invalid");
        }
        Cipher cipher =
                Cipher.getInstance(
                        "AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                key,
                new GCMParameterSpec(
                        TAG_LENGTH_BITS,
                        iv));
        cipher.updateAAD(MAGIC);
        byte[] ciphertext =
                cipher.doFinal(plaintext);
        try {
            return buildContainer(
                    iv,
                    ciphertext);
        } finally {
            wipe(ciphertext);
        }
    }

    private static byte[] buildContainer(
            byte[] iv,
            byte[] ciphertext) {
        byte[] output = new byte[
                MAGIC.length
                        + 1
                        + iv.length
                        + ciphertext.length];
        int offset = 0;
        System.arraycopy(
                MAGIC,
                0,
                output,
                offset,
                MAGIC.length);
        offset += MAGIC.length;
        output[offset++] = (byte) iv.length;
        System.arraycopy(
                iv,
                0,
                output,
                offset,
                iv.length);
        offset += iv.length;
        System.arraycopy(
                ciphertext,
                0,
                output,
                offset,
                ciphertext.length);
        return output;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
