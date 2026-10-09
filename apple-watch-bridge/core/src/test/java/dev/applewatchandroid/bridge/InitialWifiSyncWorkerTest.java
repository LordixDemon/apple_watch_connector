package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class InitialWifiSyncWorkerTest {
    private static final UUID PAIR = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static InitialWifiSyncWorker.Context context(boolean activation, boolean prepared, boolean ids, boolean operational) {
        return new InitialWifiSyncWorker.Context(PAIR, 7, activation, prepared, ids, operational);
    }
    private static void awaitRead(InitialWifiSyncWorker worker) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (worker.phase() == InitialWifiSyncWorker.Phase.READING && System.nanoTime() < end) Thread.sleep(1);
        assertNotEquals(InitialWifiSyncWorker.Phase.READING, worker.phase());
    }
    @Test public void waitsForActivationCorrelatedInitialSyncAndLiveIdsAndExcludesOperational() {
        AtomicInteger calls = new AtomicInteger();
        try (var worker = new InitialWifiSyncWorker(() -> { calls.incrementAndGet(); return new byte[]{1}; })) {
            assertFalse(worker.request(context(false, true, true, false), 0));
            assertFalse(worker.request(context(true, false, true, false), 0));
            assertFalse(worker.request(context(true, true, false, false), 0));
            assertFalse(worker.request(context(true, true, true, true), 0));
            assertEquals(0, calls.get());
        }
    }
    @Test public void transfersOneArchiveOnceWithoutRepeatingOnLaterSetupTicks() throws Exception {
        byte[] secret = {1, 2, 3}; AtomicInteger calls = new AtomicInteger();
        try (var worker = new InitialWifiSyncWorker(() -> { calls.incrementAndGet(); return secret; })) {
            var context = context(true, true, true, false);
            assertTrue(worker.request(context, 10)); awaitRead(worker);
            assertSame(secret, worker.take(context, 11));
            assertNull(worker.take(context, 12)); worker.sent();
            assertFalse(worker.request(context, 20_000));
            assertEquals(1, calls.get());
            assertEquals(InitialWifiSyncWorker.Phase.IDS_QUEUED, worker.phase());
            Arrays.fill(secret, (byte) 0);
        }
    }
    @Test public void networkUnavailableRetriesAreBoundedAndDelayed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var worker = new InitialWifiSyncWorker(() -> { calls.incrementAndGet(); throw new IllegalStateException(); })) {
            var context = context(true, true, true, false);
            for (int attempt = 0; attempt < InitialWifiSyncWorker.MAX_ATTEMPTS; attempt++) {
                long now = attempt * InitialWifiSyncWorker.RETRY_MS;
                assertTrue(worker.request(context, now)); awaitRead(worker);
                assertFalse(worker.request(context, now + 1));
            }
            assertEquals(InitialWifiSyncWorker.Phase.UNAVAILABLE, worker.phase());
            assertFalse(worker.request(context, 1_000_000));
            assertEquals(InitialWifiSyncWorker.MAX_ATTEMPTS, calls.get());
        }
    }
    @Test public void stalePairGenerationDisconnectedAndExpiredArchivesAreWiped() throws Exception {
        var contexts = new InitialWifiSyncWorker.Context[]{
                new InitialWifiSyncWorker.Context(UUID.randomUUID(), 7, true, true, true, false),
                new InitialWifiSyncWorker.Context(PAIR, 8, true, true, true, false),
                context(true, true, false, false), context(true, true, true, false)};
        for (int i = 0; i < contexts.length; i++) {
            byte[] secret = {7, 8};
            try (var worker = new InitialWifiSyncWorker(() -> secret)) {
                worker.request(context(true, true, true, false), 0); awaitRead(worker);
                assertNull(worker.take(contexts[i], i == 3 ? InitialWifiSyncWorker.ARCHIVE_TTL_MS + 1 : 1));
                assertArrayEquals(new byte[2], secret);
            }
        }
    }
    @Test public void closeWipesLateReaderResultEvenIfReaderIgnoresInterruption() throws Exception {
        byte[] secret = {9, 9}; CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var worker = new InitialWifiSyncWorker(() -> {
            entered.countDown();
            while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) { } }
            return secret;
        });
        worker.request(context(true, true, true, false), 0);
        try { assertTrue(entered.await(2, TimeUnit.SECONDS)); worker.close(); }
        finally { release.countDown(); }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Arrays.equals(new byte[2], secret) && System.nanoTime() < end) Thread.sleep(1);
        assertArrayEquals(new byte[2], secret);
        assertEquals(InitialWifiSyncWorker.Phase.CLOSED, worker.phase());
    }
}
