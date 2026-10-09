package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;

/** Atomic installation identities, independent of HCI and session orchestration. */
final class LinuxIdentityStore {
    private static final String IDS_RECORD = "ids-installation.sealed";
    private static final int MAGIC = 0x49445331;
    private final DesktopSecretStore secrets;
    private final SecureRandom random;

    LinuxIdentityStore(DesktopSecretStore secrets, SecureRandom random) {
        this.secrets = secrets;
        this.random = random;
    }

    byte[] bluetoothRecord() throws Exception {
        if (!secrets.contains("bluetooth-identity.sealed")) {
            byte[] address = new byte[6], irk = new byte[16];
            random.nextBytes(address);
            random.nextBytes(irk);
            address[5] |= (byte) 0xc0;
            try (BluetoothLocalIdentity identity = BluetoothLocalIdentity.create(address, irk)) {
                byte[] record = identity.serialize();
                try { secrets.store("bluetooth-identity.sealed", record); }
                finally { Arrays.fill(record, (byte) 0); }
            } finally { Arrays.fill(address, (byte) 0); Arrays.fill(irk, (byte) 0); }
        }
        return secrets.load("bluetooth-identity.sealed");
    }

    byte[] idsPublicBundle() throws Exception {
        if (!secrets.contains(IDS_RECORD)) {
            boolean publicExists = secrets.contains("ids-public.sealed");
            boolean keyExists = secrets.contains("ids-key.sealed");
            boolean privateExists = secrets.contains("ids-private.sealed");
            if (publicExists || keyExists || privateExists) {
                if (!(publicExists && keyExists && privateExists)) {
                    throw new IOException("Incomplete legacy IDS identity; existing records retained");
                }
                migrateLegacy();
            } else {
                if (secrets.contains("pairing.sealed")) {
                    throw new IOException("Saved pair has no IDS identity; refusing to replace it");
                }
                generateIds();
            }
        }
        byte[] record = secrets.load(IDS_RECORD);
        try { return validatedPublicBundle(record); }
        finally { Arrays.fill(record, (byte) 0); }
    }

    private void migrateLegacy() throws Exception {
        byte[] bundle = secrets.load("ids-public.sealed"), key = null, privateRecord = null;
        try {
            key = secrets.load("ids-key.sealed");
            privateRecord = secrets.load("ids-private.sealed");
            storeIds(bundle, key, privateRecord);
            // Retain legacy ciphertext for rollback; it is never used after migration.
        } finally { wipe(bundle, key, privateRecord); }
    }

    private void generateIds() throws Exception {
        try (IdsMessageProtectionIdentity identity = IdsMessageProtectionIdentity.generate(random)) {
            byte[] bundle = identity.publicBundle(), bound = null, keyBytes = null, privateRecord = null;
            try {
                bound = IdsMessageProtectionIdentity.bindPublicBundle(bundle, UUID.randomUUID().toString());
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256, random);
                var key = generator.generateKey();
                keyBytes = key.getEncoded();
                privateRecord = identity.seal(key);
                storeIds(bound, keyBytes, privateRecord);
            } finally { wipe(bundle, bound, keyBytes, privateRecord); }
        }
    }

    private void storeIds(byte[] bundle, byte[] key, byte[] privateRecord) throws Exception {
        byte[] record = ByteBuffer.allocate(16 + bundle.length + key.length + privateRecord.length)
                .putInt(MAGIC).putInt(bundle.length).putInt(key.length).putInt(privateRecord.length)
                .put(bundle).put(key).put(privateRecord).array();
        try {
            byte[] verified = validatedPublicBundle(record);
            Arrays.fill(verified, (byte) 0);
            secrets.store(IDS_RECORD, record);
        } finally { Arrays.fill(record, (byte) 0); }
    }

    static byte[] validatedPublicBundle(byte[] record) throws Exception {
        if (record.length < 16 || record.length > 96 * 1024) throw new IOException("Invalid IDS installation record");
        ByteBuffer input = ByteBuffer.wrap(record);
        if (input.getInt() != MAGIC) throw new IOException("Unsupported IDS installation record");
        int publicLength = input.getInt(), keyLength = input.getInt(), privateLength = input.getInt();
        if (publicLength <= 0 || publicLength > 32 * 1024 || keyLength != 32
                || privateLength <= 0 || privateLength > 32 * 1024
                || publicLength + keyLength + privateLength != input.remaining()) {
            throw new IOException("Invalid IDS installation lengths");
        }
        byte[] bundle = new byte[publicLength], key = new byte[keyLength], privateRecord = new byte[privateLength];
        byte[] regenerated = null, bound = null;
        input.get(bundle).get(key).get(privateRecord);
        try (IdsMessageProtectionIdentity identity = IdsMessageProtectionIdentity.open(
                privateRecord, new SecretKeySpec(key, "AES"))) {
            regenerated = identity.publicBundle();
            bound = IdsMessageProtectionIdentity.bindPublicBundle(regenerated,
                    IdsMessageProtectionIdentity.publicBundleIdentifier(bundle));
            if (!MessageDigest.isEqual(bundle, bound)) throw new IOException("IDS public/private identity mismatch");
            return bundle.clone();
        } finally { wipe(bundle, key, privateRecord, regenerated, bound); }
    }

    private static void wipe(byte[]... values) {
        for (byte[] value : values) if (value != null) Arrays.fill(value, (byte) 0);
    }
}
