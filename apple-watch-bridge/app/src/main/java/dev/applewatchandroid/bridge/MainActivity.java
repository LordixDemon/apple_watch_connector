package dev.applewatchandroid.bridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

/** Compatibility launcher. Companion owns every application screen. */
public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestUsbAdapterPermission();
        try {
            startActivity(new android.content.Intent().setComponent(new android.content.ComponentName(
                    "dev.applewatchandroid.companion.apple_watch_companion",
                    "dev.applewatchandroid.companion.apple_watch_companion.MainActivity")));
        } catch (android.content.ActivityNotFoundException missing) {
            android.widget.Toast.makeText(this, "Install Apple Watch Companion", android.widget.Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /**
     * A foreground Activity is the reliable place for the USB permission dialog.
     * Accepting the USB_DEVICE_ATTACHED prompt grants it as well; the grant is
     * recorded by the system, so no result needs to be observed here.
     */
    private void requestUsbAdapterPermission() {
        UsbManager usb = getSystemService(UsbManager.class);
        if (usb == null) return;
        for (UsbDevice device : UsbBluetoothController.controllers(usb)) {
            if (usb.hasPermission(device)) return;
        }
        java.util.List<UsbDevice> controllers = UsbBluetoothController.controllers(usb);
        if (controllers.isEmpty()) return;
        PendingIntent pending = PendingIntent.getBroadcast(this, 1,
                new android.content.Intent("dev.applewatchandroid.bridge.USB_PERMISSION").setPackage(getPackageName()),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        usb.requestPermission(controllers.get(0), pending);
    }

    /** Compatibility entry used by the existing same-app command receivers. */
    public static void sendCommandToRoot(String command) {
        BridgeSetupEngine.sendCommandToRoot(command);
    }
}
