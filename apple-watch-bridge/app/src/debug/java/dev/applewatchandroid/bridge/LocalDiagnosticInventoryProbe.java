package dev.applewatchandroid.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.UUID;

/** Non-exported diagnostic entry point: lists existing archives only. */
public final class LocalDiagnosticInventoryProbe extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"dev.applewatchandroid.bridge.READ_DIAGNOSTIC_ARCHIVES".equals(intent.getAction())) return;
        UUID id = UUID.randomUUID();
        Log.i("DiagnosticInventoryProbe", "request=" + id + " queued="
                + OperationalWatchService.sendCommand(SysdiagnoseArchiveInventory.COMMAND, id)
                + "; archive collection=false; setup=false");
    }
}
