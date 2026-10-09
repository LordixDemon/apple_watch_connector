package dev.applewatchandroid.bridge;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.Consumer;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Shared Keystore-backed storage, serialized across UI and service instances. */
final class BridgeIdentityStore {
    private static final Object FILE_LOCK = new Object();
    private final Context context;
    private final Consumer<String> logger;
    private final File encryptedBondFile;
    private final File encryptedPairingSessionFile;

    BridgeIdentityStore(Context context, Consumer<String> logger) {
        this.context = context.getApplicationContext();
        this.logger = logger;
        encryptedBondFile = new File(getFilesDir(), "bluetooth-bond.v1.aesgcm");
        encryptedPairingSessionFile = new File(getFilesDir(), "pairing-session.v2.aesgcm");
    }
    private File getFilesDir() { return context.getFilesDir(); }
    private void appendLog(String line) { logger.accept(line); }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private String safeMessage(Throwable e) { return e.getMessage() == null ? context.getString(R.string.no_message) : e.getMessage(); }

    PairingSessionRecord readRecord() throws Exception {
        synchronized (FILE_LOCK) {
            byte[] plaintext = restorePlaintext();
            try { return PairingSessionRecord.parse(plaintext); }
            finally { wipe(plaintext); }
        }
    }
    byte[] restorePlaintext() throws Exception {
        synchronized (FILE_LOCK) {
            byte[] container = readBoundedFile(encryptedPairingSessionFile, PairingSessionEnvelope.MAX_CONTAINER_LENGTH);
            try { return decryptPairingSessionContainer(container); }
            finally { wipe(container); }
        }
    }
    String operationalPairing() throws Exception {
        String confirmed = context.getSharedPreferences("watch_operating_mode", Context.MODE_PRIVATE)
                .getString("owner_confirmed_pairing", null);
        PairingSessionRecord record = readRecord();
        try {
            String pairing = OperationalSessionPolicy.pairingId(record);
            if (!OperationalSessionPolicy.mayUseOperationalMode(
                    record.state(), pairing, confirmed, false, record.hasObservedSetupEvidence())) {
                if (confirmed == null) return null;
                throw new IOException(context.getString(R.string.watch_face_confirmation_does_not_match_the_saved_pair));
            }
            return pairing;
        } finally { record.destroy(); }
    }
    boolean storePairing(String encoded, String operationalPairing) {
        synchronized (FILE_LOCK) {
            if (operationalPairing != null) {
                byte[] bytes = null;
                PairingSessionRecord record = null;
                try {
                    bytes = Base64.decode(encoded, Base64.NO_WRAP);
                    record = PairingSessionRecord.parse(bytes);
                    OperationalSessionPolicy.requireMatchingActivatedPair(record, operationalPairing);
                } catch (Exception error) {
                    appendLog("OPERATIONAL STORE rejected: pair/state mismatch; plaintext logged=false.");
                    return false;
                } finally { if (record != null) record.destroy(); wipe(bytes); }
            }
            return persistEncryptedPairingSession(encoded);
        }
    }
    boolean storeBond(String encoded) {
        synchronized (FILE_LOCK) { return persistEncryptedBondSecret(encoded); }
    }
    byte[] decryptBond() throws Exception {
        synchronized (FILE_LOCK) { return decryptStoredBondSecret(); }
    }
    byte[] publicBundle() throws Exception {
        synchronized (FILE_LOCK) { return loadOrCreateIdsPublicBundle(); }
    }
    byte[] decryptHealthData(byte[] encrypted, byte[] peerPublicIdentity) throws Exception {
        synchronized (FILE_LOCK) {
            byte[] sealed = readBoundedFile(new File(getFilesDir(),"ids-message-protection.v1.aesgcm"),32*1024);
            try (var identity = IdsMessageProtectionIdentity.open(sealed,
                    getOrCreateEncryptionKey("apple-watch-bridge.ids-message-protection.v1"))) {
                return identity.decryptClassA(encrypted,peerPublicIdentity);
            } finally { wipe(sealed); }
        }
    }
    byte[] encryptHealthData(byte[] plaintext,byte[] peerPublicIdentity) throws Exception {
        synchronized(FILE_LOCK) {
            byte[] sealed=readBoundedFile(new File(getFilesDir(),"ids-message-protection.v1.aesgcm"),32*1024);
            try(var identity=IdsMessageProtectionIdentity.open(sealed,
                    getOrCreateEncryptionKey("apple-watch-bridge.ids-message-protection.v1"))) {
                return identity.encryptClassA(plaintext,peerPublicIdentity,new java.security.SecureRandom());
            } finally { wipe(sealed); }
        }
    }
    String idsIdentifier() throws Exception {
        synchronized (FILE_LOCK) { return loadOrCreateIdsDeviceIdentifier(); }
    }
    byte[] localIdentity() throws Exception {
        synchronized (FILE_LOCK) { return deriveStableLocalIdentityRecord(); }
    }
    byte[] decryptPairing(byte[] container) throws Exception {
        synchronized (FILE_LOCK) { return decryptPairingSessionContainer(container); }
    }
    static byte[] readFile(File file, int limit) throws IOException { return readBoundedFile(file, limit); }

    private static final String BOND_KEY_ALIAS =
            "apple_watch_bridge_bond_v1";
    private static final String PAIRING_SESSION_KEY_ALIAS =
            "apple_watch_bridge_pairing_session_v2";
    private static final String LOCAL_IDENTITY_KEY_ALIAS =
            "apple_watch_bridge_local_identity_v1";
    private static final byte[] LOCAL_ADDRESS_DERIVATION_LABEL =
            "apple-watch-bridge/static-random-address/v1"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] LOCAL_IRK_DERIVATION_LABEL =
            "apple-watch-bridge/local-irk/v1"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENCRYPTED_RECORD_MAGIC =
            new byte[]{'A', 'W', 'B', 'E', 1};
    private boolean persistEncryptedBondSecret(
            String encoded) {
        byte[] plaintext = null;
        byte[] ciphertext = null;
        byte[] iv = null;
        FileOutputStream output = null;
        AtomicFile atomicFile =
                new AtomicFile(encryptedBondFile);
        try {
            plaintext =
                    Base64.decode(
                            encoded,
                            Base64.NO_WRAP);
            BluetoothBondSecretRecord
                    .validateSerialized(plaintext);
            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    getOrCreateBondEncryptionKey());
            iv = cipher.getIV();
            if (iv == null
                    || iv.length < 12
                    || iv.length > 16) {
                throw new IllegalStateException(
                        "Unexpected AES-GCM IV length");
            }
            ciphertext =
                    cipher.doFinal(plaintext);
            output = atomicFile.startWrite();
            output.write(ENCRYPTED_RECORD_MAGIC);
            output.write(iv.length);
            output.write(iv);
            output.write(ciphertext);
            atomicFile.finishWrite(output);
            output = null;
            return true;
        } catch (Exception error) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            return false;
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
            if (ciphertext != null) {
                Arrays.fill(ciphertext, (byte) 0);
            }
            if (iv != null) {
                Arrays.fill(iv, (byte) 0);
            }
        }
    }

    private boolean persistEncryptedPairingSession(
            String encoded) {
        byte[] plaintext = null;
        byte[] oldContainer = null;
        byte[] oldPlaintext = null;
        byte[] newContainer = null;
        byte[] readbackContainer = null;
        byte[] readbackPlaintext = null;
        PairingSessionRecord incoming = null;
        PairingSessionRecord existing = null;
        PairingSessionRecord readback = null;
        boolean promoted = false;
        try {
            plaintext =
                    Base64.decode(
                            encoded,
                            Base64.NO_WRAP);
            incoming =
                    PairingSessionRecord.parse(
                            plaintext);

            if (encryptedPairingSessionFile.isFile()) {
                oldContainer = readBoundedFile(
                        encryptedPairingSessionFile,
                        PairingSessionEnvelope
                                .MAX_CONTAINER_LENGTH);
                oldPlaintext =
                        decryptPairingSessionContainer(
                                oldContainer);
                existing =
                        PairingSessionRecord.parse(
                                oldPlaintext);
                if (sameGeneration(incoming, existing)) {
                    if (incoming.transitionCounter()
                            < existing.transitionCounter()) {
                        return false;
                    }
                    if (incoming.transitionCounter()
                            == existing.transitionCounter()) {
                        return MessageDigest.isEqual(
                                plaintext,
                                oldPlaintext);
                    }
                } else {
                    archiveEncryptedPairingSession(
                            oldContainer);
                }
            }

            newContainer =
                    encryptPairingSessionPlaintext(
                            plaintext);
            writeAtomicFile(
                    encryptedPairingSessionFile,
                    newContainer);
            promoted = true;

            readbackContainer = readBoundedFile(
                    encryptedPairingSessionFile,
                    PairingSessionEnvelope
                            .MAX_CONTAINER_LENGTH);
            readbackPlaintext =
                    decryptPairingSessionContainer(
                            readbackContainer);
            readback =
                    PairingSessionRecord.parse(
                            readbackPlaintext);
            if (!sameGeneration(incoming, readback)
                    || incoming.state() != readback.state()
                    || incoming.transitionCounter()
                    != readback.transitionCounter()
                    || !MessageDigest.isEqual(
                            plaintext,
                            readbackPlaintext)) {
                throw new IllegalStateException(
                        "Pairing session encrypted readback mismatch");
            }
            return true;
        } catch (Exception error) {
            appendLog("PAIRING SESSION STORE ERROR: "
                    + error.getClass().getSimpleName()
                    + ": "
                    + safeMessage(error));
            if (promoted) {
                try {
                    if (oldContainer == null) {
                        if (encryptedPairingSessionFile.exists()
                                && !encryptedPairingSessionFile
                                .delete()) {
                            return false;
                        }
                    } else {
                        writeAtomicFile(
                                encryptedPairingSessionFile,
                                oldContainer);
                    }
                } catch (Exception rollbackFailure) {
                    return false;
                }
            }
            return false;
        } finally {
            if (readback != null) {
                readback.destroy();
            }
            if (existing != null) {
                existing.destroy();
            }
            if (incoming != null) {
                incoming.destroy();
            }
            wipe(plaintext);
            wipe(oldContainer);
            wipe(oldPlaintext);
            wipe(newContainer);
            wipe(readbackContainer);
            wipe(readbackPlaintext);
        }
    }

    private byte[] encryptPairingSessionPlaintext(
        byte[] plaintext)
            throws Exception {
        PairingSessionRecord parsed = null;
        try {
            parsed =
                    PairingSessionRecord.parse(
                            plaintext);
            return PairingSessionEnvelope.encrypt(
                    plaintext,
                    getOrCreatePairingSessionEncryptionKey(),
                    new java.security.SecureRandom());
        } finally {
            if (parsed != null) {
                parsed.destroy();
            }
        }
    }

    private byte[] decryptPairingSessionContainer(
            byte[] container)
            throws Exception {
        byte[] plaintext = null;
        PairingSessionRecord parsed = null;
        try {
            plaintext =
                    PairingSessionEnvelope.decrypt(
                            container,
                            getOrCreatePairingSessionEncryptionKey());
            parsed =
                    PairingSessionRecord.parse(
                            plaintext);
            byte[] result = plaintext;
            plaintext = null;
            return result;
        } finally {
            if (parsed != null) {
                parsed.destroy();
            }
            wipe(plaintext);
        }
    }

    private SecretKey getOrCreatePairingSessionEncryptionKey()
            throws Exception {
        return getOrCreateEncryptionKey(
                PAIRING_SESSION_KEY_ALIAS);
    }

    private void archiveEncryptedPairingSession(
            byte[] oldContainer)
            throws IOException {
        File archive = new File(
                getFilesDir(),
                "pairing-session.v2.rollback."
                        + System.currentTimeMillis()
                        + ".aesgcm");
        writeAtomicFile(archive, oldContainer);
    }

    private static boolean sameGeneration(
            PairingSessionRecord first,
            PairingSessionRecord second) {
        byte[] firstGeneration = null;
        byte[] secondGeneration = null;
        try {
            firstGeneration = first.generationUuid();
            secondGeneration = second.generationUuid();
            return MessageDigest.isEqual(
                    firstGeneration,
                    secondGeneration);
        } finally {
            wipe(firstGeneration);
            wipe(secondGeneration);
        }
    }

    private static byte[] readBoundedFile(
            File file,
            int maximumLength)
            throws IOException {
        long length = file.length();
        if (length < 1 || length > maximumLength) {
            throw new IOException(
                    "Encrypted record file size is invalid");
        }
        try (FileInputStream input =
                     new FileInputStream(file);
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream((int) length)) {
            byte[] buffer = new byte[4096];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > maximumLength) {
                        throw new IOException(
                                "Encrypted record exceeds size limit");
                    }
                }
                return output.toByteArray();
            } finally {
                wipe(buffer);
            }
        }
    }

    private static void writeAtomicFile(
            File file,
            byte[] value)
            throws IOException {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(value);
            atomicFile.finishWrite(output);
            output = null;
        } finally {
            if (output != null) {
                atomicFile.failWrite(output);
            }
        }
    }

    private SecretKey getOrCreateBondEncryptionKey()
            throws Exception {
        return getOrCreateEncryptionKey(
                BOND_KEY_ALIAS);
    }

    private SecretKey getOrCreateEncryptionKey(
            String alias)
            throws Exception {
        KeyStore keyStore =
                KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing =
                keyStore.getKey(
                        alias,
                        null);
        if (existing instanceof SecretKey secretKey) {
            return secretKey;
        }
        KeyGenerator generator =
                KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        "AndroidKeyStore");
        generator.init(
                new KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT
                                | KeyProperties
                                .PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(
                                KeyProperties
                                        .BLOCK_MODE_GCM)
                        .setEncryptionPaddings(
                                KeyProperties
                                        .ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build());
        return generator.generateKey();
    }

    private byte[] loadOrCreateIdsPublicBundle() throws Exception {
        File file = new File(getFilesDir(), "ids-message-protection.v1.aesgcm");
        SecretKey key = getOrCreateEncryptionKey("apple-watch-bridge.ids-message-protection.v1");
        byte[] sealed = null;
        byte[] expected = null;
        byte[] readback = null;
        try {
            if (file.isFile()) {
                sealed = readBoundedFile(file, 32 * 1024);
                try (IdsMessageProtectionIdentity identity = IdsMessageProtectionIdentity.open(sealed, key)) {
                    return identity.publicBundle();
                }
            }
            if (encryptedPairingSessionFile.isFile()) {
                byte[] previous = null;
                byte[] plaintext = null;
                PairingSessionRecord record = null;
                try {
                    previous = readBoundedFile(encryptedPairingSessionFile, PairingSessionEnvelope.MAX_CONTAINER_LENGTH);
                    plaintext = decryptPairingSessionContainer(previous);
                    record = PairingSessionRecord.parse(plaintext);
                    if (record.hasExchangedIdsDeviceInfo()) {
                        throw new IOException("IDS private identity is missing for the existing paired device");
                    }
                } finally {
                    if (record != null) record.destroy();
                    wipe(previous); wipe(plaintext);
                }
            }
            try (IdsMessageProtectionIdentity identity = IdsMessageProtectionIdentity.generate(new java.security.SecureRandom())) {
                expected = identity.publicBundle();
                sealed = identity.seal(key);
            }
            writeAtomicFile(file, sealed);
            readback = readBoundedFile(file, 32 * 1024);
            try (IdsMessageProtectionIdentity checked = IdsMessageProtectionIdentity.open(readback, key)) {
                byte[] actual = checked.publicBundle();
                if (!MessageDigest.isEqual(expected, actual)) {
                    wipe(actual);
                    throw new IOException("IDS identity encrypted readback mismatch");
                }
                return actual;
            }
        } finally {
            wipe(sealed); wipe(expected); wipe(readback);
        }
    }

    private String loadOrCreateIdsDeviceIdentifier() throws Exception {
        // The UUID is public protocol metadata. App-private atomic storage is
        // sufficient; the actual signing/decryption keys remain Keystore-sealed.
        File file = new File(getFilesDir(), "ids-device-identifier.v1");
        byte[] bytes = null;
        byte[] plaintext = null;
        PairingSessionRecord legacy = null;
        try {
            if (file.isFile()) {
                bytes = readBoundedFile(file, 128);
                return IdsDeviceIdentifier.decode(bytes);
            }
            String previous = null;
            if (encryptedPairingSessionFile.isFile()) {
                bytes = readBoundedFile(encryptedPairingSessionFile, PairingSessionEnvelope.MAX_CONTAINER_LENGTH);
                plaintext = decryptPairingSessionContainer(bytes);
                legacy = PairingSessionRecord.parse(plaintext);
                previous = legacy.localIdsDeviceUuid();
            }
            String identifier = IdsDeviceIdentifier.choose(null, previous);
            wipe(bytes);
            bytes = IdsDeviceIdentifier.encode(identifier);
            writeAtomicFile(file, bytes);
            wipe(bytes);
            bytes = readBoundedFile(file, 128);
            if (!identifier.equals(IdsDeviceIdentifier.decode(bytes))) {
                throw new IOException("IDS installation identifier readback mismatch");
            }
            appendLog(previous == null ? context.getString(R.string.ids_identity_persistent_phone_identifier_created)
                    : context.getString(R.string.ids_identity_existing_pair_identifier_saved_as_the_persistent));
            return identifier;
        } finally {
            if (legacy != null) legacy.destroy();
            wipe(bytes); wipe(plaintext);
        }
    }

    private byte[] deriveStableLocalIdentityRecord()
            throws Exception {
        byte[] addressDigest = null;
        byte[] irkDigest = null;
        byte[] address = null;
        byte[] irk = null;
        BluetoothLocalIdentity identity = null;
        try {
            SecretKey key = getOrCreateLocalIdentityKey();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            addressDigest = mac.doFinal(
                    LOCAL_ADDRESS_DERIVATION_LABEL);
            mac.init(key);
            irkDigest = mac.doFinal(
                    LOCAL_IRK_DERIVATION_LABEL);

            address = Arrays.copyOf(
                    addressDigest,
                    BluetoothLocalIdentity.ADDRESS_LENGTH);
            address[address.length - 1] =
                    (byte) ((address[address.length - 1] & 0x3f)
                            | 0xc0);
            avoidDegenerateStaticRandomAddress(address);
            irk = Arrays.copyOf(
                    irkDigest,
                    BluetoothLocalIdentity.IRK_LENGTH);
            identity = BluetoothLocalIdentity.create(address, irk);
            return identity.serialize();
        } finally {
            if (identity != null) {
                identity.destroy();
            }
            wipe(addressDigest);
            wipe(irkDigest);
            wipe(address);
            wipe(irk);
        }
    }

    private SecretKey getOrCreateLocalIdentityKey()
            throws Exception {
        KeyStore keyStore =
                KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing =
                keyStore.getKey(
                        LOCAL_IDENTITY_KEY_ALIAS,
                        null);
        if (existing instanceof SecretKey secretKey) {
            return secretKey;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                "AndroidKeyStore");
        generator.init(
                new KeyGenParameterSpec.Builder(
                        LOCAL_IDENTITY_KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN
                                | KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(256)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build());
        return generator.generateKey();
    }

    private static void avoidDegenerateStaticRandomAddress(
            byte[] address) {
        boolean randomPartAllZero =
                (address[address.length - 1] & 0x3f) == 0;
        boolean randomPartAllOne =
                (address[address.length - 1] & 0x3f) == 0x3f;
        for (int index = 0; index < address.length - 1; index++) {
            randomPartAllZero &= address[index] == 0;
            randomPartAllOne &= (address[index] & 0xff) == 0xff;
        }
        if (randomPartAllZero || randomPartAllOne) {
            address[0] ^= 0x01;
        }
    }

    private byte[] decryptStoredBondSecret()
            throws Exception {
        byte[] container = null;
        byte[] iv = null;
        byte[] plaintext = null;
        try {
            long length = encryptedBondFile.length();
            if (length < ENCRYPTED_RECORD_MAGIC.length
                    + 1
                    + 12
                    + 16
                    || length > 512) {
                throw new IllegalArgumentException(
                        "Encrypted bond container size is invalid");
            }
            try (FileInputStream input =
                         new FileInputStream(
                                 encryptedBondFile);
                 ByteArrayOutputStream output =
                         new ByteArrayOutputStream(
                                 (int) length)) {
                byte[] buffer = new byte[256];
                int count;
                while ((count = input.read(buffer))
                        != -1) {
                    output.write(buffer, 0, count);
                }
                Arrays.fill(buffer, (byte) 0);
                container = output.toByteArray();
            }
            int difference = 0;
            for (int index = 0;
                    index
                            < ENCRYPTED_RECORD_MAGIC
                                    .length;
                    index++) {
                difference |= container[index]
                        ^ ENCRYPTED_RECORD_MAGIC[index];
            }
            if (difference != 0) {
                throw new IllegalArgumentException(
                        "Encrypted bond container magic is invalid");
            }
            int ivLength =
                    container[
                            ENCRYPTED_RECORD_MAGIC.length]
                            & 0xff;
            int ciphertextOffset =
                    ENCRYPTED_RECORD_MAGIC.length
                            + 1
                            + ivLength;
            if (ivLength < 12
                    || ivLength > 16
                    || ciphertextOffset + 16
                    > container.length) {
                throw new IllegalArgumentException(
                        "Encrypted bond container IV is invalid");
            }
            iv = Arrays.copyOfRange(
                    container,
                    ENCRYPTED_RECORD_MAGIC.length + 1,
                    ciphertextOffset);
            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateBondEncryptionKey(),
                    new GCMParameterSpec(
                            128,
                            iv));
            plaintext =
                    cipher.doFinal(
                            container,
                            ciphertextOffset,
                            container.length
                                    - ciphertextOffset);
            BluetoothBondSecretRecord
                    .validateSerialized(
                            plaintext);
            byte[] result = plaintext;
            plaintext = null;
            return result;
        } finally {
            if (container != null) {
                Arrays.fill(container, (byte) 0);
            }
            if (iv != null) {
                Arrays.fill(iv, (byte) 0);
            }
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

}
