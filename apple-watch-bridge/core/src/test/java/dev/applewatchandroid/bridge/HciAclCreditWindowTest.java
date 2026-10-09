package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class HciAclCreditWindowTest {
    @Test public void fragmentedSduWaitsForControllerNotPeerAck() throws Exception {
        HciAclCreditWindow credits = new HciAclCreditWindow();
        credits.configure(2);
        credits.connected(1);
        assertTrue(credits.tryReserve(1));
        assertTrue(credits.tryReserve(1));
        assertFalse(credits.tryReserve(1));
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread sender = new Thread(() -> {
            try {
                waiting.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!credits.tryReserve(1)) {
                    if (System.nanoTime() > deadline) throw new AssertionError("Credits not released");
                    credits.awaitCredit(50);
                }
                resumed.countDown();
            } catch (Throwable failure) { error.set(failure); }
        });
        sender.start();
        try {
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            assertFalse(resumed.await(50, TimeUnit.MILLISECONDS));
            credits.complete(1, 1);
            assertTrue(resumed.await(1, TimeUnit.SECONDS));
            sender.join(1000);
            assertNull(error.get());
            assertFalse(credits.tryReserve(1));
            credits.complete(1, 2);
            assertTrue(credits.tryReserve(1));
        } finally { sender.interrupt(); sender.join(1000); }
    }

    @Test public void completionBeforeWaitDoesNotLoseWakeup() throws Exception {
        HciAclCreditWindow credits = new HciAclCreditWindow();
        credits.configure(1); credits.connected(1); credits.tryReserve(1);
        credits.complete(1, 1);
        long start = System.nanoTime();
        credits.awaitCredit(1000);
        assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500));
        assertTrue(credits.tryReserve(1));
    }

    @Test public void sharedPoolReclaimsOnlyDisconnectedHandle() {
        HciAclCreditWindow credits = new HciAclCreditWindow();
        credits.configure(2); credits.connected(1); credits.connected(2);
        credits.tryReserve(1); credits.tryReserve(2);
        credits.disconnected(1);
        credits.complete(1, 1); // flushed old handle must not refund twice
        credits.complete(99, 500); // unrelated controller event
        assertTrue(credits.tryReserve(2));
        assertFalse(credits.tryReserve(2));
        assertThrows(IllegalStateException.class, () -> credits.tryReserve(1));
        credits.complete(2, 2);
        credits.connected(1); // controller may reuse handle after disconnect
        assertTrue(credits.tryReserve(1));
    }

    @Test public void invalidCompletionsNeverCreateExtraCredits() {
        HciAclCreditWindow credits = new HciAclCreditWindow();
        assertThrows(IllegalArgumentException.class, () -> credits.configure(0));
        assertThrows(IllegalStateException.class, () -> credits.tryReserve(1));
        credits.configure(1); credits.connected(1); credits.tryReserve(1);
        assertThrows(IllegalStateException.class, () -> credits.complete(1, 2));
        assertFalse(credits.tryReserve(1));
        credits.complete(1, 1);
        assertThrows(IllegalStateException.class, () -> credits.complete(1, 1));
        assertTrue(credits.tryReserve(1));
        assertFalse(credits.tryReserve(1));
    }
}
