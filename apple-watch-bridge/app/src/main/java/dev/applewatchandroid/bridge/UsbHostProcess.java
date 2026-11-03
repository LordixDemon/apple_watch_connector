package dev.applewatchandroid.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link java.lang.Process} view of one {@link UsbHciHostService} run, so the
 * existing setup/operational owners keep their stdin/stdout protocol unchanged
 * while no {@code su} child is spawned.
 */
final class UsbHostProcess extends java.lang.Process {
    private static final long BIND_TIMEOUT_MS = 10_000;

    private final Context context;
    private final ServiceConnection connection;
    private final int pid;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final CountDownLatch exited = new CountDownLatch(1);
    private volatile int exitValue = -1;
    private volatile boolean released;

    private UsbHostProcess(Context context, ServiceConnection connection, int pid, OutputStream stdin,
            InputStream stdout, ParcelFileDescriptor status) {
        this.context = context;
        this.connection = connection;
        this.pid = pid;
        this.stdin = stdin;
        this.stdout = stdout;
        Thread watcher = new Thread(() -> {
            int value = 1;
            try (DataInputStream input = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(status))) {
                value = input.readInt();
            } catch (IOException ended) {
                value = 137; // Host process died or was destroyed before reporting.
            }
            exitValue = value;
            exited.countDown();
            release(false);
        }, "usb-hci-host-exit");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** Starts one host; arguments use the established root-host grammar. */
    static UsbHostProcess start(Context context, String[] args, String deviceName) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("USB host must not be started on the main thread");
        }
        Context app = context.getApplicationContext();
        IOException last = null;
        // A just-discarded host process can still be bound while it dies; retry once.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return startOnce(app, args, deviceName);
            } catch (IOException failure) {
                last = failure;
                try { Thread.sleep(750); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while starting USB host", interrupted);
                }
            }
        }
        throw last;
    }

    private static UsbHostProcess startOnce(Context app, String[] args, String deviceName) throws IOException {
        AtomicReference<IBinder> bound = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                bound.set(service);
                ready.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
            @Override public void onBindingDied(ComponentName name) { ready.countDown(); }
            @Override public void onNullBinding(ComponentName name) { ready.countDown(); }
        };
        Intent intent = new Intent(app, UsbHciHostService.class);
        if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT)) {
            app.unbindService(connection);
            throw new IOException("Cannot bind the USB HCI host service");
        }
        ParcelFileDescriptor[] input = null, output = null, status = null;
        boolean success = false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (!ready.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) || bound.get() == null) {
                throw new IOException("USB HCI host service did not start");
            }
            input = ParcelFileDescriptor.createPipe();
            output = ParcelFileDescriptor.createPipe();
            status = ParcelFileDescriptor.createPipe();
            data.writeInterfaceToken(UsbHciHostService.DESCRIPTOR);
            data.writeStringArray(args);
            data.writeString(deviceName);
            data.writeFileDescriptor(input[0].getFileDescriptor());
            data.writeFileDescriptor(output[1].getFileDescriptor());
            data.writeFileDescriptor(status[1].getFileDescriptor());
            if (!bound.get().transact(UsbHciHostService.TRANSACTION_START, data, reply, 0)) {
                throw new IOException("USB HCI host refused the session");
            }
            reply.readException();
            int pid = reply.readInt();
            if (pid <= 0) throw new IOException("USB HCI host is already in use");
            // The remote ends now live in the host process only.
            input[0].close();
            output[1].close();
            status[1].close();
            UsbHostProcess process = new UsbHostProcess(app, connection, pid,
                    new ParcelFileDescriptor.AutoCloseOutputStream(input[1]),
                    new ParcelFileDescriptor.AutoCloseInputStream(output[0]), status[0]);
            success = true;
            return process;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while binding USB HCI host", interrupted);
        } catch (RemoteException | RuntimeException failure) {
            throw new IOException("USB HCI host start failed: " + failure.getMessage(), failure);
        } finally {
            data.recycle();
            reply.recycle();
            if (!success) {
                closeAll(input);
                closeAll(output);
                closeAll(status);
                try { app.unbindService(connection); } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private static void closeAll(ParcelFileDescriptor[] pair) {
        if (pair == null) return;
        for (ParcelFileDescriptor descriptor : pair) {
            try { descriptor.close(); } catch (IOException | RuntimeException ignored) { }
        }
    }

    private void release(boolean kill) {
        synchronized (this) {
            if (released) return;
            released = true;
        }
        // Unbind first so the system does not restart the discarded host.
        try { context.unbindService(connection); } catch (IllegalArgumentException ignored) { }
        if (kill) android.os.Process.killProcess(pid);
    }

    @Override public OutputStream getOutputStream() { return stdin; }
    @Override public InputStream getInputStream() { return stdout; }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }

    @Override
    public int waitFor() throws InterruptedException {
        exited.await();
        return exitValue;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return exited.await(timeout, unit);
    }

    @Override
    public int exitValue() {
        if (exited.getCount() != 0) throw new IllegalThreadStateException("USB host is still running");
        return exitValue;
    }

    @Override public boolean isAlive() { return exited.getCount() != 0; }

    @Override
    public void destroy() {
        if (!isAlive()) return;
        release(true);
        try { stdin.close(); } catch (IOException ignored) { }
    }

    @Override
    public java.lang.Process destroyForcibly() {
        destroy();
        return this;
    }
}
