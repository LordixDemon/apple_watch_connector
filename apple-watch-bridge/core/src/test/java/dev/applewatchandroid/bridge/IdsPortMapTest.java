package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IdsPortMapTest {
    @Test
    public void reservesZeroThrough1025AndStartsAt1026() {
        try (IdsPortMap ports =
                     new IdsPortMap()) {
            assertTrue(
                    ports.isAllocated(
                            0));
            assertTrue(
                    ports.isAllocated(
                            IdsPortMap
                                    .LEGACY_CONTROL_PORT));
            assertTrue(
                    ports.isAllocated(
                            IdsPortMap
                                    .LEGACY_DATA_PORT));
            assertFalse(
                    ports.isAllocated(
                            IdsPortMap
                                    .FIRST_DYNAMIC_PORT));

            assertEquals(
                    1026,
                    ports.allocate());
            assertEquals(
                    1027,
                    ports.allocate());
            assertEquals(
                    2,
                    ports.dynamicAllocatedCount());
        }
    }

    @Test
    public void releaseIsFailClosedAndReuseFollowsCircularCursor() {
        try (IdsPortMap ports =
                     new IdsPortMap()) {
            int first =
                    ports.allocate();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ports.release(
                            IdsPortMap
                                    .LEGACY_CONTROL_PORT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ports.release(
                            IdsPortMap
                                    .LEGACY_DATA_PORT));

            ports.release(
                    first);
            assertFalse(
                    ports.isAllocated(
                            first));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ports.release(
                            first));

            for (int count = 1;
                    count < IdsPortMap
                            .DYNAMIC_PORT_COUNT;
                    count++) {
                ports.allocate();
            }
            assertEquals(
                    first,
                    ports.allocate());
            assertEquals(
                    IdsPortMap
                            .DYNAMIC_PORT_COUNT,
                    ports.dynamicAllocatedCount());
        }
    }

    @Test
    public void completeDynamicSpaceExhaustionDoesNotUseReservedPorts() {
        try (IdsPortMap ports =
                     new IdsPortMap()) {
            for (int count = 0;
                    count < IdsPortMap
                            .DYNAMIC_PORT_COUNT;
                    count++) {
                int port =
                        ports.allocate();
                assertTrue(
                        port
                                >= IdsPortMap
                                .FIRST_DYNAMIC_PORT);
            }
            assertThrows(
                    IllegalStateException.class,
                    ports::allocate);
            assertTrue(
                    ports.isAllocated(
                            IdsPortMap
                                    .LEGACY_CONTROL_PORT));
            assertTrue(
                    ports.isAllocated(
                            IdsPortMap
                                    .LEGACY_DATA_PORT));
        }
    }

    @Test
    public void randomSeededCursorBreaksTupleReuseAcrossRestarts() {
        // Live 0.2.163: with the cursor always at 1024 the first control SYN
        // reused source 1026, the Watch matched it to a stale ESTABLISHED
        // session and answered REJECTED_BY_POLICY. randomizeCursor must move
        // the first allocation reproducibly off the default.
        java.security.SecureRandom fixed =
                new java.security.SecureRandom() {
                    @Override
                    public int nextInt(int bound) {
                        return bound / 2;
                    }
                };
        int expectedCursor =
                IdsPortMap.FIRST_DYNAMIC_PORT
                        + IdsPortMap.DYNAMIC_PORT_COUNT / 2;
        int first;
        try (IdsPortMap ports =
                     new IdsPortMap()
                             .randomizeCursor(
                                     fixed)) {
            first = ports.allocate();
            assertEquals(
                    expectedCursor + 1,
                    first);
            assertTrue(
                    first != 1026);
        }
        try (IdsPortMap replayed =
                     new IdsPortMap()
                             .randomizeCursor(
                                     fixed)) {
            assertEquals(
                    first,
                    replayed.allocate());
        }
        try (IdsPortMap allocated =
                     new IdsPortMap()) {
            allocated.allocate();
            assertThrows(
                    IllegalStateException.class,
                    () -> allocated.randomizeCursor(
                            fixed));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new IdsPortMap()
                            .randomizeCursor(
                                    null));
        }
    }

    @Test
    public void closedMapCannotLeakOrAllocateState() {        IdsPortMap ports =
                new IdsPortMap();
        ports.allocate();
        ports.close();

        assertThrows(
                IllegalStateException.class,
                ports::allocate);
        assertThrows(
                IllegalStateException.class,
                ports::dynamicAllocatedCount);
        assertThrows(
                IllegalStateException.class,
                () -> ports.isAllocated(
                        IdsPortMap
                                .FIRST_DYNAMIC_PORT));
    }
}
