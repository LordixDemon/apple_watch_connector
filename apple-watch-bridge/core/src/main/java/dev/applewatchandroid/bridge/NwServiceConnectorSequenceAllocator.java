package dev.applewatchandroid.bridge;

/**
 * Process-wide unsigned 64-bit sequence allocator used by Network.framework
 * service connectors.
 *
 * <p>iOS 26.6 backs every normal/cloud connector with one atomic global
 * counter. Allocation returns the old value and skips zero after wrap.</p>
 */
final class NwServiceConnectorSequenceAllocator {
    private long nextSequence;

    NwServiceConnectorSequenceAllocator(
            long initialSequence) {
        nextSequence = initialSequence;
    }

    synchronized long next() {
        while (true) {
            long candidate =
                    nextSequence;
            nextSequence =
                    candidate + 1;
            if (candidate != 0) {
                return candidate;
            }
        }
    }

    synchronized long peek() {
        return nextSequence;
    }

    /**
     * Mirrors the iOS 26.6 NWSC initializer using Android clock inputs.
     *
     * @param wallClockMillis Unix wall time in milliseconds
     * @param elapsedRealtimeNanos monotonic time since boot, including sleep
     */
    static long appleInitialSeed(
            long wallClockMillis,
            long elapsedRealtimeNanos) {
        if (elapsedRealtimeNanos < 0) {
            throw new IllegalArgumentException(
                    "NWSC elapsed realtime cannot be negative");
        }
        long continuousMicros =
                elapsedRealtimeNanos / 1_000L;
        long bootTimeMicros =
                wallClockMillis * 1_000L
                        - continuousMicros;
        return bootTimeMicros
                + (continuousMicros >>> 1);
    }
}
