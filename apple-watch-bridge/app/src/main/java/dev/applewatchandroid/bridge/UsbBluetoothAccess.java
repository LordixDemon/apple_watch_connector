package dev.applewatchandroid.bridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Finds the external USB Bluetooth adapter and obtains the user's USB permission. */
final class UsbBluetoothAccess {
    private static final String ACTION_PERMISSION = "dev.applewatchandroid.bridge.USB_PERMISSION";
    private static final long PERMISSION_TIMEOUT_MS = 60_000;

    private UsbBluetoothAccess() { }

    /** True when a USB Bluetooth controller is plugged in; selects the no-root transport. */
    static boolean attached(Context context) {
        return !UsbBluetoothController.controllers(context.getSystemService(UsbManager.class)).isEmpty();
    }

    /** Blocks a worker thread until a permitted adapter is available; returns its device name. */
    static String requireAdapter(Context context, Consumer<String> log) throws IOException {
        UsbManager usb = context.getSystemService(UsbManager.class);
        if (usb == null) throw new IOException("This phone has no USB host support");
        List<UsbDevice> controllers = UsbBluetoothController.controllers(usb);
        if (controllers.isEmpty()) {
            throw new IOException("No USB Bluetooth adapter found. Connect a Bluetooth 5.x USB dongle "
                    + "through a USB-OTG adapter and try again.");
        }
        for (UsbDevice device : controllers) {
            if (usb.hasPermission(device)) {
                log.accept("USB BLUETOOTH ADAPTER READY: " + UsbBluetoothController.describe(device)
                        + "; no root, stock Bluetooth untouched.");
                return device.getDeviceName();
            }
        }
        UsbDevice device = controllers.get(0);
        log.accept("USB PERMISSION REQUESTED: " + UsbBluetoothController.describe(device)
                + "; confirm the Android dialog.");
        CountDownLatch answered = new CountDownLatch(1);
        boolean[] granted = new boolean[1];
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                answered.countDown();
            }
        };
        Context app = context.getApplicationContext();
        app.registerReceiver(receiver, new IntentFilter(ACTION_PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        try {
            Intent intent = new Intent(ACTION_PERMISSION).setPackage(app.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(app, 0, intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            usb.requestPermission(device, pending);
            if (!answered.await(PERMISSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("USB permission dialog was not answered");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for USB permission", interrupted);
        } finally {
            app.unregisterReceiver(receiver);
        }
        if (!granted[0] || !usb.hasPermission(device)) {
            throw new IOException("USB permission for the Bluetooth adapter was denied");
        }
        log.accept("USB PERMISSION GRANTED: " + UsbBluetoothController.describe(device) + ".");
        return device.getDeviceName();
    }
}
