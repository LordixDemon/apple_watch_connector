package dev.applewatchandroid.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.UUID;

/** Explicit non-exported entry point; never called during reconnect or setup. */
public final class LocalDiagnosticCollectionProbe extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"dev.applewatchandroid.bridge.COLLECT_WATCH_DIAGNOSTIC".equals(intent.getAction())) return;
        UUID id = UUID.randomUUID();
        Log.i("DiagnosticCollectionProbe", "request=" + id + " queued="
                + OperationalWatchService.sendCommand(SysdiagnoseCollection.COMMAND, id)
                + "; archive completion=unverified; setup=false");
    }
}
