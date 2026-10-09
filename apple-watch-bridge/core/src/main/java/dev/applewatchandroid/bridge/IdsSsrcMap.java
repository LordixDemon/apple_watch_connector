package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-device registry for locally generated IDS stream SSRC values.
 *
 * <p>watchOS draws a random uint32 and retries while that value is already
 * present in the outgoing-encryption table. The Android host keeps the same
 * lifetime rule and releases the value with its generic connection.</p>
 */
final class IdsSsrcMap
        implements AutoCloseable {
    private static final int MAX_COLLISION_RETRIES =
            1 << 20;

    private final Set<Integer> allocated =
            new HashSet<>();
    private boolean closed;

    synchronized int allocate(
            SecureRandom random) {
        requireUsable();
        if (random == null) {
            throw new IllegalArgumentException(
                    "IDS SSRC random source is absent");
        }
        for (int attempt = 0;
                attempt < MAX_COLLISION_RETRIES;
                attempt++) {
            int candidate =
                    random.nextInt();
            if (allocated.add(
                    candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "IDS SSRC random source repeated an allocated value");
    }

    synchronized void release(
            int ssrc) {
        requireUsable();
        if (!allocated.remove(
                ssrc)) {
            throw new IllegalArgumentException(
                    "IDS SSRC was not allocated");
        }
    }

    synchronized boolean isAllocated(
            int ssrc) {
        requireUsable();
        return allocated.contains(
                ssrc);
    }

    synchronized int allocatedCount() {
        requireUsable();
        return allocated.size();
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS SSRC map is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        allocated.clear();
    }
}
