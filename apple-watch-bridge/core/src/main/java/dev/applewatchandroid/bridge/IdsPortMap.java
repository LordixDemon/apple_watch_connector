package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The per-device IDS generic-connection port allocator used by watchOS
 * 26.2.
 *
 * <p>Ports 0 through 1023 are reserved by the initial bitmap and IDS then
 * explicitly reserves its legacy control/data ports 1024 and 1025. Dynamic
 * allocation starts after a last-port cursor of 1024, so the first available
 * result is 1026. Allocation is a circular next-fit scan over the complete
 * uint16 space. Production sessions should call {@link #randomizeCursor}
 * once after construction so a restarted process does not reuse a stale
 * session's 4-tuple.</p>
 */
final class IdsPortMap
        implements AutoCloseable {
    static final int LEGACY_CONTROL_PORT =
            1024;
    static final int LEGACY_DATA_PORT =
            1025;
    static final int FIRST_DYNAMIC_PORT =
            1026;
    static final int LAST_PORT =
            0xffff;
    static final int DYNAMIC_PORT_COUNT =
            LAST_PORT
                    - FIRST_DYNAMIC_PORT
                    + 1;

    private final boolean[] allocated =
            new boolean[LAST_PORT + 1];

    private int lastPort =
            LEGACY_CONTROL_PORT;
    private int dynamicAllocated;
    private boolean closed;

    IdsPortMap() {
        this(null);
    }

    IdsPortMap(SecureRandom random) {
        Arrays.fill(
                allocated,
                0,
                LEGACY_DATA_PORT + 1,
                true);
    }

    /**
     * Seeds the allocation cursor at a random point of the dynamic space.
     *
     * <p>The Watch keys its NWSC session table by the 4-tuple, and a fresh
     * phone process that reuses the previous session's source port gets its
     * SYN matched to the still-ESTABLISHED stale flow — the Watch then
     * answers REJECTED_BY_POLICY (NWSC flags 0x40) after ~20 s of
     * retransmitting the old session's payload (live 0.2.163, rejects at
     * 13:39/13:56/13:59/14:05/14:26 all on source 1026). Native iOS requests
     * advertise 61315/61314 while their TCP source is dynamic; randomizing
     * the cursor restores that property across process restarts. Production
     * hosts call this right after construction; the default cursor stays
     * deterministic so in-process tests keep stable wire frames.</p>
     */
    synchronized IdsPortMap randomizeCursor(
            SecureRandom random) {
        requireUsable();
        if (random == null) {
            throw new IllegalArgumentException(
                    "IDS port map random source is absent");
        }
        if (dynamicAllocated != 0) {
            throw new IllegalStateException(
                    "IDS port map cursor cannot move after allocation");
        }
        lastPort =
                FIRST_DYNAMIC_PORT
                        + random.nextInt(
                                DYNAMIC_PORT_COUNT);
        return this;
    }

    synchronized int allocate() {
        requireUsable();
        if (dynamicAllocated
                == DYNAMIC_PORT_COUNT) {
            throw new IllegalStateException(
                    "IDS dynamic port space is exhausted");
        }
        for (int inspected = 0;
                inspected <= LAST_PORT;
                inspected++) {
            int candidate =
                    (lastPort + 1)
                            & LAST_PORT;
            lastPort =
                    candidate;
            if (allocated[candidate]) {
                continue;
            }
            allocated[candidate] =
                    true;
            dynamicAllocated++;
            return candidate;
        }
        throw new IllegalStateException(
                "IDS dynamic port bitmap is inconsistent");
    }

    synchronized void release(
            int port) {
        requireUsable();
        requirePort(
                port);
        if (port < FIRST_DYNAMIC_PORT) {
            throw new IllegalArgumentException(
                    "IDS reserved port cannot be released");
        }
        if (!allocated[port]) {
            throw new IllegalArgumentException(
                    "IDS dynamic port was not allocated");
        }
        allocated[port] =
                false;
        dynamicAllocated--;
    }

    synchronized boolean isAllocated(
            int port) {
        requireUsable();
        requirePort(
                port);
        return allocated[port];
    }

    synchronized int dynamicAllocatedCount() {
        requireUsable();
        return dynamicAllocated;
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS port map is closed");
        }
    }

    private static void requirePort(
            int port) {
        if (port < 0
                || port > LAST_PORT) {
            throw new IllegalArgumentException(
                    "IDS port must fit uint16");
        }
    }

    public static volatile java.util.function.Consumer<String> diagnosticLogger = null;

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        java.util.function.Consumer<String> logger = diagnosticLogger;
        if (logger != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            new Exception("[IdsPortMap] closed here").printStackTrace(new java.io.PrintWriter(sw));
            logger.accept(sw.toString());
        }
        closed = true;
        Arrays.fill(
                allocated,
                false);
        dynamicAllocated = 0;
        lastPort =
                LEGACY_CONTROL_PORT;
    }
}
