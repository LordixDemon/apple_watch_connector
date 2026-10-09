package dev.applewatchandroid.bridge;

import android.app.Activity;
import android.os.Bundle;

/** Compatibility launcher. Companion owns every application screen. */
public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            startActivity(new android.content.Intent().setComponent(new android.content.ComponentName(
                    "dev.applewatchandroid.companion.apple_watch_companion",
                    "dev.applewatchandroid.companion.apple_watch_companion.MainActivity")));
        } catch (android.content.ActivityNotFoundException missing) {
            android.widget.Toast.makeText(this, "Install Apple Watch Companion", android.widget.Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /** Compatibility entry used by the existing same-app command receivers. */
    public static void sendCommandToRoot(String command) {
        BridgeSetupEngine.sendCommandToRoot(command);
    }
}
