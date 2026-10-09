package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Desktop-only encrypted records; owner-only key file, no Android Keystore claims. */
final class LinuxSecretStore implements DesktopSecretStore {
    private final Path directory;
    private final SecretKeySpec key;
    LinuxSecretStore(Path directory) throws Exception {
        this.directory = directory;
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path keyPath = directory.resolve("storage.key");
        if (!Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS)) {
            byte[] material = new byte[32]; new SecureRandom().nextBytes(material);
            try { atomicWrite(keyPath, material); } finally { Arrays.fill(material, (byte) 0); }
        }
        if (Files.isSymbolicLink(keyPath) || Files.size(keyPath) != 32) throw new IllegalArgumentException("Invalid storage key");
        Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
        byte[] material = Files.readAllBytes(keyPath);
        try { key = new SecretKeySpec(material, "AES"); } finally { Arrays.fill(material, (byte) 0); }
    }
    public boolean contains(String name) { return Files.isRegularFile(directory.resolve(name), LinkOption.NOFOLLOW_LINKS); }
    public String ownerConfirmedPair() throws Exception {
        if (!contains("owner-confirmed.sealed")) return null;
        byte[] bytes = load("owner-confirmed.sealed");
        try {
            String id = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
            NtkFacePayloadCodec.uuid(id);
            return id;
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
    public void publishPair(PairingSessionRecord pair) throws Exception {
        String id = OperationalSessionPolicy.pairingId(pair);
        if (id == null) return;
        NtkFacePayloadCodec.uuid(id);
        String product = pair.peerProductType();
        if (product == null || !product.matches("Watch[0-9]{1,3},[0-9]{1,3}")) product = "";
        boolean activated = pair.state().wireValue() >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue();
        boolean verified = OperationalSessionPolicy.mayUseOperationalMode(pair.state(), id, null, false,
                pair.hasObservedSetupEvidence());
        boolean ownerConfirmed = activated && id.equals(ownerConfirmedPair());
        String json = "{\"pairId\":\"" + id + "\",\"productType\":\"" + product
                + "\",\"activationConfirmed\":" + activated + ",\"protocolVerified\":" + verified
                + ",\"ownerConfirmed\":" + ownerConfirmed + "}";
        atomicWrite(directory.resolve("pair-public.json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public void store(String name, byte[] bytes) throws Exception {
        if (!name.matches("[a-z-]+\\.sealed") || bytes.length > 256 * 1024) throw new IllegalArgumentException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] encrypted = cipher.doFinal(bytes);
        byte[] record = ByteBuffer.allocate(12 + encrypted.length).put(cipher.getIV()).put(encrypted).array();
        try {
            atomicWrite(directory.resolve(name), record);
            byte[] verified = load(name);
            try {
                if (!java.security.MessageDigest.isEqual(bytes, verified)) throw new java.io.IOException("Record readback mismatch");
            } finally { Arrays.fill(verified, (byte) 0); }
        }
        finally { Arrays.fill(encrypted, (byte) 0); Arrays.fill(record, (byte) 0); }
    }
    public byte[] load(String name) throws Exception {
        Path path = directory.resolve(name);
        if (!name.matches("[a-z-]+\\.sealed") || Files.isSymbolicLink(path) || Files.size(path) > 256 * 1024) throw new IllegalArgumentException();
        byte[] record = Files.readAllBytes(path);
        try {
            if (record.length < 28) throw new IllegalArgumentException("Invalid sealed record");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, record, 0, 12));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return cipher.doFinal(record, 12, record.length - 12);
        } finally { Arrays.fill(record, (byte) 0); }
    }
    private void atomicWrite(Path path, byte[] bytes) throws Exception {
        Path temporary = Files.createTempFile(directory, ".pending-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
