package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;

import org.junit.Test;

public final class IdsSsrcMapTest {
    @Test
    public void randomCollisionRetriesAndReleaseRestoresAvailability() {
        SequenceRandom random =
                new SequenceRandom(
                        0x11223344,
                        0x11223344,
                        0x55667788,
                        0x11223344);
        try (IdsSsrcMap map =
                     new IdsSsrcMap()) {
            int first =
                    map.allocate(
                            random);
            int second =
                    map.allocate(
                            random);

            assertEquals(
                    0x11223344,
                    first);
            assertEquals(
                    0x55667788,
                    second);
            assertEquals(
                    2,
                    map.allocatedCount());
            assertTrue(
                    map.isAllocated(
                            first));

            map.release(
                    first);
            assertFalse(
                    map.isAllocated(
                            first));
            assertEquals(
                    first,
                    map.allocate(
                            random));
        }
    }

    @Test
    public void unknownReleaseAndClosedUseFailClosed() {
        IdsSsrcMap map =
                new IdsSsrcMap();
        assertThrows(
                IllegalArgumentException.class,
                () -> map.release(
                        1));
        map.close();
        assertThrows(
                IllegalStateException.class,
                map::allocatedCount);
        assertThrows(
                IllegalStateException.class,
                () -> map.allocate(
                        new SecureRandom()));
    }

    private static final class SequenceRandom
            extends SecureRandom {
        private final int[] values;
        private int index;

        private SequenceRandom(
                int... values) {
            this.values =
                    values.clone();
        }

        @Override
        public int nextInt() {
            if (index >= values.length) {
                throw new IllegalStateException(
                        "Test random sequence is exhausted");
            }
            return values[index++];
        }
    }
}
