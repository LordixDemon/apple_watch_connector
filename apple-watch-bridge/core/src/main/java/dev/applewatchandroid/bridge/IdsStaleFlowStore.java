package dev.applewatchandroid.bridge;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Survives process restart so a paired reconnect can proactively reset the
 * Watch TCP flows its identityservicesd still considers ESTABLISHED. The
 * Watch only reopens its ids-control-channel after the previous control
 * connection dies (watchOS 26.2: {@code FUN_10035caa4} clears the
 * connecting flag and retries); without these resets the reconnect waits
 * out the Watch's ~8.6-min TCP RTO (live 13:08-13:16).
 *
 * <p>Format: one record per line, {@code 1 <class> <localPort>
 * <remotePort> <peerAck> <peerTsval> <lastSeenEpochMs> <localHex>
 * <remoteHex>}. Malformed lines are skipped: a partially written tail must
 * not kill the reconnect path.</p>
 */
final class IdsStaleFlowStore {
    private static final int MAX_RECORDS = 64;

    static volatile File storeFile =
            ProtocolPaths.files().resolve("stale-flows.v1").toFile();

    private IdsStaleFlowStore() {
    }

    static synchronized List<IdsStaleFlowRecord> load() {
        File file = storeFile;
        List<IdsStaleFlowRecord> records =
                new ArrayList<>();
        if (!file.isFile()) {
            return records;
        }
        try (RandomAccessFile raf =
                     new RandomAccessFile(file, "r")) {
            long length = raf.length();
            if (length <= 0
                    || length > 64 * 1024) {
                return records;
            }
            byte[] raw =
                    new byte[(int) length];
            raf.readFully(raw);
            String text =
                    new String(raw, StandardCharsets.US_ASCII);
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    records.add(
                            IdsStaleFlowRecord.decode(trimmed));
                } catch (RuntimeException malformed) {
                    // Skip the bad line, keep the rest.
                }
            }
            return records;
        } catch (IOException error) {
            return records;
        }
    }

    static synchronized void save(List<IdsStaleFlowRecord> records) {
        Map<String, IdsStaleFlowRecord> deduped =
                new LinkedHashMap<>();
        for (IdsStaleFlowRecord record : records) {
            deduped.put(
                    record.key(),
                    record);
        }
        while (deduped.size() > MAX_RECORDS) {
            String eldest = deduped.keySet().iterator().next();
            deduped.remove(eldest);
        }
        StringBuilder text = new StringBuilder();
        for (IdsStaleFlowRecord record : deduped.values()) {
            text.append(record.encode());
            text.append('\n');
        }
        byte[] raw = text.toString().getBytes(StandardCharsets.US_ASCII);
        File file = storeFile;
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (RandomAccessFile raf =
                     new RandomAccessFile(file, "rw")) {
            raf.setLength(0);
            raf.write(raw);
            raf.getFD().sync();
        } catch (IOException ignored) {
            // Losing the store only costs the next reconnect its fast path.
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /** Merges freshly observed flows into the store (newest wins). */
    static synchronized void merge(List<IdsStaleFlowRecord> observed) {
        if (observed == null
                || observed.isEmpty()) {
            return;
        }
        Map<String, IdsStaleFlowRecord> merged =
                new LinkedHashMap<>();
        for (IdsStaleFlowRecord record : load()) {
            merged.put(
                    record.key(),
                    record);
        }
        for (IdsStaleFlowRecord record : observed) {
            merged.put(
                    record.key(),
                    record);
        }
        save(new ArrayList<>(merged.values()));
    }
}
