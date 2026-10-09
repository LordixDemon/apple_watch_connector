package dev.applewatchandroid.bridge;

/** Monotonic protocol deadlines. Wall clock timestamps use System.currentTimeMillis separately. */
final class BridgeClock {
    private BridgeClock() { }
    static long elapsedRealtimeNanos() { return System.nanoTime(); }
    static long elapsedRealtime() { return elapsedRealtimeNanos() / 1_000_000; }
    static void sleep(long milliseconds) {
        long deadline = elapsedRealtime() + milliseconds;
        boolean interrupted = false;
        while (elapsedRealtime() < deadline) {
            try { Thread.sleep(deadline - elapsedRealtime()); }
            catch (InterruptedException signal) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
