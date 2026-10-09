package dev.applewatchandroid.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.UUID;

/** Explicit local recovery entry point; never invoked by reconnect or setup. */
public final class LocalWatchRebootProbe extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"dev.applewatchandroid.bridge.REBOOT_WATCH".equals(intent.getAction())) return;
        UUID id = UUID.randomUUID();
        Log.i("WatchRebootProbe", "request=" + id + " queued="
                + OperationalWatchService.sendCommand(NativeWatchReboot.COMMAND, id)
                + "; native ordinary reboot; completion unverified; erase=false; retry=false");
    }
}
