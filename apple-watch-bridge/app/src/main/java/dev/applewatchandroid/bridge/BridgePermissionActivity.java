package dev.applewatchandroid.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Only Android's system permission dialog; all application screens live in Flutter. */
public final class BridgePermissionActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 351) { BridgeIpcDispatcher.getInstance().refreshPhoneFindState(); finish(); }
    }
}
