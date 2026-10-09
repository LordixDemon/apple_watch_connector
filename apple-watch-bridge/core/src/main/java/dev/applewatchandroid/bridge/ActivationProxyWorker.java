package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Performs HTTPS without blocking the HCI/ERTM/IDS receive loop. */
final class ActivationProxyWorker implements AutoCloseable {
    private final MobileActivationHttpProxy proxy;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "watch-activation-https");
        thread.setDaemon(true);
        return thread;
    });
    private Object pending;
    private Future<?> task;
    private Result completed;
    private byte[] activeRequest;
    private boolean closed;

    ActivationProxyWorker(MobileActivationHttpProxy proxy) {
        if (proxy == null) throw new IllegalArgumentException("Activation proxy is absent");
        this.proxy = proxy;
    }

    synchronized void start(long generation, int attempt,
            MobileActivationHttpProxy.RequestKind kind, byte[] archivedRequest) {
        if (closed || pending != null) throw new IllegalStateException("Activation worker is busy or closed");
        if (generation <= 0 || attempt <= 0 || kind == null || archivedRequest == null) {
            throw new IllegalArgumentException("Activation job is incomplete");
        }
        byte[] request = archivedRequest.clone();
        activeRequest = request;
        Object token = new Object();
        pending = token;
        try {
            task = executor.submit(() -> {
                MobileActivationHttpProxy.ProxyResponse response = null;
                Exception failure = null;
                try {
                    response = proxy.execute(kind, request);
                } catch (Exception error) {
                    failure = error;
                } finally {
                    Arrays.fill(request, (byte) 0);
                }
                Result result = new Result(generation, attempt, kind, response, failure);
                synchronized (ActivationProxyWorker.this) {
                    if (activeRequest == request) activeRequest = null;
                    if (closed || pending != token) result.close();
                    else completed = result;
                }
            });
        } catch (RuntimeException error) {
            pending = null;
            activeRequest = null;
            Arrays.fill(request, (byte) 0);
            throw error;
        }
    }

    synchronized Result poll() {
        if (completed == null) return null;
        Result result = completed;
        completed = null;
        pending = null;
        if (activeRequest != null) Arrays.fill(activeRequest, (byte) 0);
        activeRequest = null;
        task = null;
        return result;
    }

    synchronized boolean busy() { return pending != null; }

    @Override
    public synchronized void close() {
        closed = true;
        pending = null;
        if (activeRequest != null) Arrays.fill(activeRequest, (byte) 0);
        activeRequest = null;
        if (completed != null) completed.close();
        completed = null;
        if (task != null) task.cancel(true);
        task = null;
        executor.shutdownNow();
    }

    static final class Result implements AutoCloseable {
        final long generation;
        final int attempt;
        final MobileActivationHttpProxy.RequestKind kind;
        final Exception failure;
        private MobileActivationHttpProxy.ProxyResponse response;

        private Result(long generation, int attempt, MobileActivationHttpProxy.RequestKind kind,
                MobileActivationHttpProxy.ProxyResponse response, Exception failure) {
            this.generation = generation;
            this.attempt = attempt;
            this.kind = kind;
            this.response = response;
            this.failure = failure;
        }

        MobileActivationHttpProxy.ProxyResponse takeResponse() {
            if (response == null || failure != null) throw new IllegalStateException("Activation job has no response");
            var result = response;
            response = null;
            return result;
        }

        @Override public void close() {
            if (response != null) response.destroy();
            response = null;
        }
    }
}
