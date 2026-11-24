package dev.applewatchandroid.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Only Android's system permission dialog; all application screens live in Flutter. */
public final class BridgePermissionActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ("notifications".equals(getIntent().getStringExtra("scope"))) {
            var component = new android.content.ComponentName(this, AppleWatchNotificationListenerService.class);
            var intent = new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString());
            try { startActivityForResult(intent, 352); }
            catch (android.content.ActivityNotFoundException unavailable) {
                try { startActivityForResult(new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), 352); }
                catch (android.content.ActivityNotFoundException missing) { finish(); }
            }
            return;
        }
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        String[] requested = "flash".equals(getIntent().getStringExtra("scope"))
                ? new String[]{Manifest.permission.CAMERA}
                : new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.POST_NOTIFICATIONS};
        for (String permission : requested) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (missing.isEmpty()) finish();
        else requestPermissions(missing.toArray(new String[0]), 351);
    }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 352) {
            var component = new android.content.ComponentName(this, AppleWatchNotificationListenerService.class);
            if (getSystemService(android.app.NotificationManager.class).isNotificationListenerAccessGranted(component)) {
                android.service.notification.NotificationListenerService.requestRebind(component);
            }
            CompanionSessionState.notificationRelayChanged();
            finish();
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 351) { BridgeIpcDispatcher.getInstance().refreshPhoneFindState(); finish(); }
    }
}
