package dev.applewatchandroid.bridge;

/** Monotonic protocol deadlines. Wall clock timestamps use System.currentTimeMillis separately. */
public final class BridgeClock {
    private BridgeClock() { }
    public static long elapsedRealtimeNanos() { return System.nanoTime(); }
    public static long elapsedRealtime() { return elapsedRealtimeNanos() / 1_000_000; }
    public static void sleep(long milliseconds) {
        long deadline = elapsedRealtime() + milliseconds;
        boolean interrupted = false;
        while (elapsedRealtime() < deadline) {
            try { Thread.sleep(deadline - elapsedRealtime()); }
            catch (InterruptedException signal) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
