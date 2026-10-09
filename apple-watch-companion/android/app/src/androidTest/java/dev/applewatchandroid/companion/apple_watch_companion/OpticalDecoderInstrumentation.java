package dev.applewatchandroid.companion.apple_watch_companion;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.*;

/** Explicit release-worker test using private camera/oracle files in target
 * cache. Uses platform instrumentation so test dependencies cannot be stripped
 * or renamed by the target's release shrinker. No recovered bytes are logged. */
public final class OpticalDecoderInstrumentation extends Instrumentation {
    private Messenger service;
    private int request;
    private Messenger replies;
    private final BlockingQueue<Bundle> results = new ArrayBlockingQueue<>(1);

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try { runPrivateCapture(); result.putString("result", "PASS: release optical worker restored identical code after 20 frames"); }
        catch (Throwable error) { result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage()); }
        finish(result.getString("result").startsWith("PASS") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private void runPrivateCapture() throws Exception {
        Context context = getTargetContext();
        File capture = new File(context.getCacheDir(), "optical-device-test.awuv");
        File expected = new File(context.getCacheDir(), "optical-device-test.payload");
        check(capture.isFile() && expected.isFile(), "Provision private fixtures for this explicit test");
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) { service = new Messenger(binder); connected.countDown(); }
            public void onServiceDisconnected(ComponentName name) { service = null; }
        };
        HandlerThread thread = new HandlerThread("OpticalTestReply"); thread.start();
        replies = new Messenger(new Handler(thread.getLooper()) {
            @Override public void handleMessage(Message message) { results.offer(message.getData()); }
        });
        Intent intent = new Intent().setComponent(new ComponentName(context.getPackageName(),
                context.getPackageName() + ".OpticalDecoderService"));
        boolean bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        byte[] decoded = null, reference = null;
        try {
            check(bound && connected.await(10, TimeUnit.SECONDS), "Worker did not bind");
            try (DataInputStream input = new DataInputStream(new FileInputStream(capture))) {
                byte[] magic = new byte[8]; input.readFully(magic);
                check(Arrays.equals(magic, "AWUV0001".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), "Bad private capture header");
                for (int frame = 0; frame < 90; frame++) {
                    byte[] raw = new byte[24]; input.readFully(raw);
                    ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                    int size = header.getInt(), width = header.getInt(), height = header.getInt();
                    check(width > 0 && width <= 960 && height > 0 && height <= 540 && size == width * height * 2, "Invalid private frame shape");
                    if (frame == 0) {
                        Bundle args = new Bundle(); args.putInt("width", width); args.putInt("height", height); exchange(1, args);
                    }
                    byte[] uv = new byte[size]; input.readFully(uv);
                    Bundle value;
                    try { Bundle args = new Bundle(); args.putByteArray("uv", uv); value = exchange(2, args); }
                    finally { Arrays.fill(uv, (byte) 0); }
                    decoded = value.getByteArray("code");
                    if (decoded != null) { check(value.getInt("frames") == 20, "Unexpected completion frame count"); break; }
                }
            }
            check(decoded != null && decoded.length == 110, "No restored optical code");
            try (FileInputStream input = new FileInputStream(expected)) { reference = input.readAllBytes(); }
            check(Arrays.equals(reference, decoded), "Code differs from independent private oracle");
            exchange(3, new Bundle());
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
            if (reference != null) Arrays.fill(reference, (byte) 0);
            if (bound) context.unbindService(connection);
            thread.quitSafely();
        }
    }
    private Bundle exchange(int what, Bundle args) throws Exception {
        args.putInt("request", ++request);
        Message message = Message.obtain(null, what); message.setData(args); message.replyTo = replies;
        service.send(message);
        Bundle value = results.poll(10, TimeUnit.SECONDS);
        check(value != null && value.getInt("request") == request && value.getBoolean("ok"), "Worker response missing or rejected");
        return value;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
