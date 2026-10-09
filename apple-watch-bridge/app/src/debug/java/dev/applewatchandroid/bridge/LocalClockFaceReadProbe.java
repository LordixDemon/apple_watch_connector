package dev.applewatchandroid.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.UUID;

/** Non-exported local diagnostic; the only command is a native collection read request. */
public final class LocalClockFaceReadProbe extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"dev.applewatchandroid.bridge.READ_FACE_COLLECTION".equals(intent.getAction())) return;
        UUID id = UUID.randomUUID();
        Log.i("ClockFaceReadProbe", "request=" + id + " queued="
                + OperationalWatchService.sendCommand("REQUEST_FACE_COLLECTION", id)
                + "; collection requires successful native END; setup=false");
    }
}
