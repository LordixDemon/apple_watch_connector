package dev.applewatchandroid.bridge;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;

/** Background owner for pairing and diagnostic sessions, independent of Flutter navigation. */
public final class BridgeSetupService extends Service {
    private static volatile BridgeSetupService active;
    private BridgeSetupEngine engine;
    static boolean busy() { return active != null && active.engine.busy(); }
    static boolean command(String method, Bundle args) {
        return active != null && active.engine.command(method, args);
    }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                BridgeConnectionNotification.CHANNEL, getString(R.string.apple_watch_connection),
                NotificationManager.IMPORTANCE_LOW));
        engine = new BridgeSetupEngine(this, this::stopSelf);
        active = this;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(2, BridgeConnectionNotification.setup(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        Bundle args = intent == null ? null : intent.getExtras();
        try {
            if (intent == null || !engine.command(intent.getStringExtra("method"), args)) {
                if (!engine.busy()) stopSelf(startId);
            }
        } finally {
            byte[] payload = args == null ? null : args.getByteArray("opticalCode");
            if (payload != null) java.util.Arrays.fill(payload, (byte) 0);
            if (args != null) args.remove("opticalCode");
            if (intent != null) intent.removeExtra("opticalCode");
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        if (active == this) active = null;
        engine.close();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
