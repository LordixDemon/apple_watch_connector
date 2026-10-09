package dev.applewatchandroid.bridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable copy of Watch NanoPreferencesSync user-defaults. The live session
 * used to decode each frame for the completion gate and then wipe it, so a
 * later reconnect had nothing to show for health, clock, or control-center
 * keys the Watch had already delivered.
 */
final class PairedSyncPreferenceStore {
    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_VALUE_BYTES = 4 * 1024 * 1024;
    private static final byte[] MAGIC = new byte[] {'N', 'P', 'S', '1'};

    static volatile File storeFile = ProtocolPaths.files().resolve("paired-sync-prefs.v1").toFile();

    private static final Map<String, Map<String, Entry>> domains = new LinkedHashMap<>();
    private static boolean loaded;
    private static int totalValueBytes;

    private PairedSyncPreferenceStore() {
    }

    static final class Result {
        final int accepted;
        final int entries;
        final int domainCount;

        Result(int accepted, int entries, int domainCount) {
            this.accepted = accepted;
            this.entries = entries;
            this.domainCount = domainCount;
        }
    }

    static synchronized int valueLengthForTesting(String domain, String key) {
        Map<String, Entry> entries = domains.get(domain);
        if (entries == null) {
            return -1;
        }
        Entry entry = entries.get(key);
        if (entry == null || entry.value == null) {
            return -1;
        }
        return entry.value.length;
    }

    static void setStoreFileForTesting(File file) {
        synchronized (PairedSyncPreferenceStore.class) {
            storeFile = file != null
                    ? file
                    : ProtocolPaths.files().resolve("paired-sync-prefs.v1").toFile();
            domains.clear();
            loaded = false;
            totalValueBytes = 0;
        }
    }

    /**
     * One encoded user-defaults frame per domain that contains a two-way key.
     * One-way Watch reports stay on the phone. The caller wipes the arrays.
     */
    static synchronized List<byte[]> twoWayPayloads() {
        ensureLoaded();
        List<byte[]> payloads = new ArrayList<>();
        for (Map.Entry<String, Map<String, Entry>> domain : domains.entrySet()) {
            List<PairedSyncCodec.UserDefaultsKey> keys = new ArrayList<>();
            double latest = Double.NEGATIVE_INFINITY;
            for (Map.Entry<String, Entry> key : domain.getValue().entrySet()) {
                Entry entry = key.getValue();
                // This old store has no pairing identity. Replaying either native
                // color set or custom monogram could overwrite a different pair.
                if (PigmentPreferenceCodec.isPairedPreference(domain.getKey(), key.getKey())
                        || MonogramPreferenceCodec.isPairedPreference(domain.getKey(), key.getKey())) continue;
                if (!Boolean.TRUE.equals(entry.twoWaySync) || entry.value == null) {
                    continue;
                }
                keys.add(new PairedSyncCodec.UserDefaultsKey(
                        key.getKey(), entry.value, Boolean.TRUE, entry.orderTimestamp));
                if (entry.orderTimestamp > latest) {
                    latest = entry.orderTimestamp;
                }
            }
            if (keys.isEmpty()) {
                continue;
            }
            PairedSyncCodec.UserDefaultsMessage message =
                    new PairedSyncCodec.UserDefaultsMessage(
                            latest, domain.getKey(), keys, false);
            try {
                payloads.add(PairedSyncCodec.encode(message));
            } finally {
                message.destroy();
                for (PairedSyncCodec.UserDefaultsKey key : keys) {
                    key.destroy();
                }
            }
        }
        return payloads;
    }

    static synchronized Result merge(PairedSyncCodec.UserDefaultsMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("PairedSync message is absent");
        }
        ensureLoaded();
        int accepted = 0;
        var keys = message.keys();
        try {
            Map<String, Entry> domain = domains.computeIfAbsent(
                    message.domain, ignored -> new LinkedHashMap<>());
            for (PairedSyncCodec.UserDefaultsKey key : keys) {
                double order = key.timestamp != null ? key.timestamp : message.timestamp;
                Entry existing = domain.get(key.key);
                if (existing != null && order < existing.orderTimestamp) {
                    continue;
                }
                byte[] value = key.value();
                int nextBytes = totalValueBytes
                        - (existing == null || existing.value == null ? 0 : existing.value.length)
                        + (value == null ? 0 : value.length);
                int nextEntries = entryCount() + (existing == null && value != null ? 1 : 0);
                if (value != null
                        && (nextBytes > MAX_TOTAL_VALUE_BYTES || nextEntries > MAX_ENTRIES)) {
                    wipe(value);
                    continue;
                }
                if (existing != null && existing.value != null) {
                    totalValueBytes -= existing.value.length;
                    wipe(existing.value);
                }
                if (value == null) {
                    domain.remove(key.key);
                } else {
                    domain.put(key.key, new Entry(value, order, key.twoWaySync));
                    totalValueBytes += value.length;
                }
                accepted++;
            }
            if (domain.isEmpty()) {
                domains.remove(message.domain);
            }
        } finally {
            keys.forEach(PairedSyncCodec.UserDefaultsKey::destroy);
        }
        save();
        return new Result(accepted, entryCount(), domains.size());
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        File file = storeFile;
        if (!file.isFile()) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1]
                    || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
                throw new IOException("bad magic");
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IOException("entry count");
            }
            for (int index = 0; index < count; index++) {
                String domainName = in.readUTF();
                String key = in.readUTF();
                double order = in.readDouble();
                int flags = in.readUnsignedByte();
                Boolean twoWay = (flags & 2) == 0 ? null : (flags & 4) != 0;
                int length = in.readInt();
                if (length < 0 || length > MAX_VALUE_BYTES) {
                    throw new IOException("value length");
                }
                byte[] value = new byte[length];
                in.readFully(value);
                if (totalValueBytes + length > MAX_TOTAL_VALUE_BYTES) {
                    wipe(value);
                    break;
                }
                domains.computeIfAbsent(domainName, ignored -> new LinkedHashMap<>())
                        .put(key, new Entry(value, order, twoWay));
                totalValueBytes += length;
            }
        } catch (IOException invalid) {
            domains.clear();
            totalValueBytes = 0;
        }
    }

    private static void save() {
        File file = storeFile;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create paired-sync preference directory");
        }
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(stream)) {
            out.write(MAGIC);
            out.writeInt(entryCount());
            for (Map.Entry<String, Map<String, Entry>> domain : domains.entrySet()) {
                for (Map.Entry<String, Entry> key : domain.getValue().entrySet()) {
                    Entry entry = key.getValue();
                    out.writeUTF(domain.getKey());
                    out.writeUTF(key.getKey());
                    out.writeDouble(entry.orderTimestamp);
                    int flags = entry.twoWaySync == null ? 0 : (2 | (entry.twoWaySync ? 4 : 0));
                    out.writeByte(flags);
                    byte[] value = entry.value == null ? new byte[0] : entry.value;
                    out.writeInt(value.length);
                    out.write(value);
                }
            }
            out.flush();
            stream.getFD().sync();
        } catch (IOException error) {
            tmp.delete();
            throw new IllegalStateException("Cannot persist paired-sync preferences", error);
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            tmp.delete();
            throw new IllegalStateException("Cannot replace paired-sync preference file", error);
        }
    }

    private static int entryCount() {
        int count = 0;
        for (Map<String, Entry> domain : domains.values()) {
            count += domain.size();
        }
        return count;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            java.util.Arrays.fill(value, (byte) 0);
        }
    }

    private static final class Entry {
        final byte[] value;
        final double orderTimestamp;
        final Boolean twoWaySync;

        Entry(byte[] value, double orderTimestamp, Boolean twoWaySync) {
            this.value = value;
            this.orderTimestamp = orderTimestamp;
            this.twoWaySync = twoWaySync;
        }
    }
}
