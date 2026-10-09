package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded serial work off the UI/IDS callback thread; queued plaintext is owned and wiped. */
final class HealthObservationQueue implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    HealthObservationQueue() { this(64); }
    HealthObservationQueue(int capacity) {
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),
                task->new Thread(task,"health-observation-store"));
    }
    boolean submit(byte[] bytes,Consumer<byte[]> consumer) {
        if(bytes==null || bytes.length>NativeHealthSyncCodec.MAX_BYTES || consumer==null)throw new IllegalArgumentException();
        var task=new Task(bytes.clone(),consumer);
        try { executor.execute(task);return true; }
        catch(RejectedExecutionException full) { task.discard();return false; }
    }
    private static final class Task implements Runnable {
        private final byte[] owned;
        private final Consumer<byte[]> consumer;
        Task(byte[] owned,Consumer<byte[]> consumer) { this.owned=owned;this.consumer=consumer; }
        void discard() { Arrays.fill(owned,(byte)0); }
        @Override public void run() { try { consumer.accept(owned); }finally { discard(); } }
    }
    @Override public void close() {
        for(Runnable pending:executor.shutdownNow())((Task)pending).discard();
    }
    boolean awaitTermination(long timeout,TimeUnit unit) throws InterruptedException { return executor.awaitTermination(timeout,unit); }
}
