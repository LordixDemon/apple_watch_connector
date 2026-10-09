package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One setup's current network; root reads never block Bluetooth receive. */
final class InitialWifiSyncWorker implements AutoCloseable {
    static final int MAX_ATTEMPTS = 3;
    static final long RETRY_MS = 5_000;
    static final long ARCHIVE_TTL_MS = 10_000;
    enum Phase { WAITING, READING, RETRY_WAIT, READY, DISPATCHING, IDS_QUEUED, UNAVAILABLE, CLOSED }
    record Context(UUID pair, long generation, boolean activated, boolean syncPrepared,
            boolean idsReady, boolean operational) {
        boolean eligible() { return pair != null && generation > 0 && activated && syncPrepared && idsReady && !operational; }
    }
    @FunctionalInterface interface Reader { byte[] read() throws Exception; }
    private final Reader reader;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "watch-setup-wifi"); thread.setDaemon(true); return thread;
    });
    private Context owner;
    private Future<?> task;
    private Phase phase = Phase.WAITING;
    private int attempts;
    private long nextAttempt, startedAt;
    private byte[] archive;

    InitialWifiSyncWorker(Reader reader) { this.reader = java.util.Objects.requireNonNull(reader); }
    synchronized Phase phase() { return phase; }
    synchronized int attempts() { return attempts; }
    private boolean matches(Context context) {
        return owner != null && context != null && owner.pair.equals(context.pair) && owner.generation == context.generation;
    }
    synchronized boolean request(Context context, long now) {
        if (context == null || !context.eligible() || now < 0 || phase == Phase.CLOSED) return false;
        if (owner != null && !matches(context)) { close(); return false; }
        if ((phase != Phase.WAITING && phase != Phase.RETRY_WAIT) || now < nextAttempt) return false;
        owner = context; startedAt = now; attempts++; phase = Phase.READING;
        task = executor.submit(() -> {
            byte[] result = null;
            try {
                result = reader.read();
                if (result == null || result.length == 0 || result.length > 1024 * 1024) {
                    throw new IllegalArgumentException("Invalid Wi-Fi archive");
                }
                synchronized (InitialWifiSyncWorker.this) {
                    if (phase == Phase.READING) { archive = result; result = null; phase = Phase.READY; }
                }
            } catch (Exception ignored) {
                synchronized (InitialWifiSyncWorker.this) {
                    if (phase == Phase.READING) {
                        phase = attempts < MAX_ATTEMPTS ? Phase.RETRY_WAIT : Phase.UNAVAILABLE;
                        nextAttempt = startedAt + RETRY_MS;
                    }
                }
            } finally { wipe(result); }
        });
        return true;
    }
    /** Transfers ownership once; caller wipes after IDS copies the archive. */
    synchronized byte[] take(Context context, long now) {
        if (phase != Phase.READY) return null;
        if (!matches(context) || !context.eligible() || now < startedAt || now - startedAt > ARCHIVE_TTL_MS) {
            close(); return null;
        }
        byte[] result = archive; archive = null; phase = Phase.DISPATCHING; return result;
    }
    synchronized void sent() {
        if (phase != Phase.DISPATCHING) throw new IllegalStateException("No initial Wi-Fi dispatch");
        phase = Phase.IDS_QUEUED;
    }
    synchronized void failed() {
        if (phase == Phase.DISPATCHING || phase == Phase.IDS_QUEUED) phase = Phase.UNAVAILABLE;
    }
    @Override public synchronized void close() {
        phase = Phase.CLOSED; wipe(archive); archive = null;
        if (task != null) task.cancel(true);
        task = null; executor.shutdownNow();
    }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
}
