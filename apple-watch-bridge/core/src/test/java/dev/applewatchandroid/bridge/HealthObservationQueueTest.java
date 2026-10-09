package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class HealthObservationQueueTest {
    @Test public void callerBufferIsClonedAndWorkBufferWipedAfterCompletion() throws Exception {
        var queue=new HealthObservationQueue(2);var started=new CountDownLatch(1);var read=new CountDownLatch(1);
        byte[] input={1,2,3};var captured=new AtomicReference<byte[]>();var seen=new AtomicReference<byte[]>();
        assertTrue(queue.submit(input,owned->{
            captured.set(owned);started.countDown();
            try { read.await(); }catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            seen.set(owned.clone());
        }));
        assertTrue(started.await(2,TimeUnit.SECONDS));input[0]=99;read.countDown();
        queue.close();assertTrue(queue.awaitTermination(2,TimeUnit.SECONDS));
        assertArrayEquals(new byte[]{1,2,3},seen.get());assertArrayEquals(new byte[3],captured.get());
    }
    @Test public void fullQueueRefusesWorkAndCloseDiscardsPendingConsumers() throws Exception {
        var queue=new HealthObservationQueue(1);var started=new CountDownLatch(1);var pendingRan=new AtomicBoolean();
        assertTrue(queue.submit(new byte[]{1},owned->{
            started.countDown();try { new CountDownLatch(1).await(); }catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }));
        assertTrue(started.await(2,TimeUnit.SECONDS));
        assertTrue(queue.submit(new byte[]{2},owned->pendingRan.set(true)));
        byte[] rejected={3};assertFalse(queue.submit(rejected,owned->fail("Rejected work ran")));
        assertArrayEquals(new byte[]{3},rejected);queue.close();assertTrue(queue.awaitTermination(2,TimeUnit.SECONDS));
        assertFalse(pendingRan.get());assertFalse(queue.submit(new byte[]{4},owned->fail("Closed work ran")));
    }
    @Test public void invalidWorkDoesNotEnterQueue() throws Exception {
        try(var queue=new HealthObservationQueue(1)) {
            assertThrows(IllegalArgumentException.class,()->queue.submit(null,b->{}));
            assertThrows(IllegalArgumentException.class,()->queue.submit(new byte[NativeHealthSyncCodec.MAX_BYTES+1],b->{}));
            assertThrows(IllegalArgumentException.class,()->queue.submit(new byte[0],null));
        }
    }
}
