package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class NwServiceConnectorSequenceAllocatorTest {
    @Test
    public void allocationReturnsOldValueAndSkipsZero() {
        NwServiceConnectorSequenceAllocator allocator =
                new NwServiceConnectorSequenceAllocator(
                        0);
        assertEquals(
                1,
                allocator.next());
        assertEquals(
                2,
                allocator.next());
    }

    @Test
    public void unsignedWrapNeverReturnsZero() {
        NwServiceConnectorSequenceAllocator allocator =
                new NwServiceConnectorSequenceAllocator(
                        -1L);
        assertEquals(
                -1L,
                allocator.next());
        assertEquals(
                1L,
                allocator.next());
    }

    @Test
    public void appleSeedUsesBootUsecPlusHalfContinuousMicros() {
        assertEquals(
                1_699_999_995_000_000L,
                NwServiceConnectorSequenceAllocator.appleInitialSeed(
                        1_700_000_000_000L,
                        10_000_000_000L));
        assertThrows(
                IllegalArgumentException.class,
                () -> NwServiceConnectorSequenceAllocator.appleInitialSeed(
                        1,
                        -1));
    }

    @Test
    public void peekReflectsNextSequenceWithoutAdvancing() {
        NwServiceConnectorSequenceAllocator allocator =
                new NwServiceConnectorSequenceAllocator(
                        42L);
        assertEquals(
                42L,
                allocator.peek());
        assertEquals(
                42L,
                allocator.next());
        assertEquals(
                43L,
                allocator.peek());
    }

    @Test
    public void persistedSeedAddsHeadroomWhenPresent() {
        assertEquals(
                10_000_049L,
                NwServiceConnectorSequenceStore.chooseSeed(
                        10L,
                        49L));
        assertEquals(
                10_000_100L,
                NwServiceConnectorSequenceStore.chooseSeed(
                        100L,
                        49L));
        assertEquals(
                10L,
                NwServiceConnectorSequenceStore.chooseSeed(
                        10L,
                        0L));
        assertEquals(
                1L,
                NwServiceConnectorSequenceStore.chooseSeed(
                        0L,
                        0L));
    }
}
