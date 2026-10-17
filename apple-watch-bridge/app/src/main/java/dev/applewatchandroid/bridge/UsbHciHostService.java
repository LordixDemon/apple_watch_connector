package dev.applewatchandroid.bridge;

import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs exactly one protocol host per {@code :hci} process, standing in for the
 * former {@code su -c app_process} child: stdin/stdout are the supplied pipes,
 * the exit status is written to a third pipe, and the process is discarded
 * afterwards so the host's static state never leaks into the next session.
 */
public final class UsbHciHostService extends Service {
    static final int TRANSACTION_START = IBinder.FIRST_CALL_TRANSACTION;
    static final String DESCRIPTOR = "dev.applewatchandroid.bridge.UsbHciHost";

    private final AtomicBoolean started = new AtomicBoolean();

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != TRANSACTION_START) return false;
            if (Binder.getCallingUid() != Process.myUid()) return false;
            data.enforceInterface(DESCRIPTOR);
            String[] args = data.createStringArray();
            String deviceName = data.readString();
            ParcelFileDescriptor stdin = data.readFileDescriptor();
            ParcelFileDescriptor stdout = data.readFileDescriptor();
            ParcelFileDescriptor status = data.readFileDescriptor();
            if (stdin == null || stdout == null || status == null || args == null || !started.compareAndSet(false, true)) {
                closeQuietly(stdin);
                closeQuietly(stdout);
                closeQuietly(status);
                reply.writeNoException();
                reply.writeInt(-1);
                return true;
            }
            Thread host = new Thread(() -> runHost(args, deviceName, stdin, stdout, status), "usb-hci-host");
            host.start();
            reply.writeNoException();
            reply.writeInt(Process.myPid());
            return true;
        }
    };

    private void runHost(String[] args, String deviceName, ParcelFileDescriptor stdin,
            ParcelFileDescriptor stdout, ParcelFileDescriptor status) {
        int exit = 1;
        PrintStream out = new PrintStream(new FileOutputStream(stdout.getFileDescriptor()), true,
                StandardCharsets.UTF_8);
        try {
            System.setIn(new FileInputStream(stdin.getFileDescriptor()));
            System.setOut(out);
            System.setErr(out);
            System.setProperty("watch.files", getFilesDir().getAbsolutePath());
            System.setProperty("watch.tmp", getCacheDir().getAbsolutePath());
            System.setProperty("watch.native.quic.path", getApplicationInfo().sourceDir
                    + "!/lib/arm64-v8a/libwatch_replicator_quic.so");
            RealtekFirmwareLoader.installAssets(getAssets());
            exit = UsbHciProtocolHost.run(getSystemService(UsbManager.class), deviceName, getCacheDir(), args);
        } catch (Throwable failure) {
            out.println("RESULT: FAIL — " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            out.flush();
            try (DataOutputStream result = new DataOutputStream(new FileOutputStream(status.getFileDescriptor()))) {
                result.writeInt(exit);
                result.flush();
            } catch (IOException ignored) {
                // The launcher treats a missing status as a failed host.
            }
            closeQuietly(status);
            closeQuietly(stdout);
            closeQuietly(stdin);
            // Mirror a real child process: never reuse host statics in a later run.
            Process.killProcess(Process.myPid());
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (IOException ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
