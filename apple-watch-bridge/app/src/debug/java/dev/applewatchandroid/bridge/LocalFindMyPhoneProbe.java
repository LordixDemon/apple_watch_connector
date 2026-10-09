package dev.applewatchandroid.bridge;

import android.content.*;
import android.content.pm.ApplicationInfo;

/** Not exported. Local synthetic phone signal evidence cannot be mistaken for a Watch request. */
public final class LocalFindMyPhoneProbe extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
                || !"dev.applewatchandroid.bridge.PROBE_PHONE_PING".equals(intent.getAction())) return;
        String token = intent.getStringExtra("probeId");
        if (token == null || !token.matches("[A-Za-z0-9_-]{1,64}")) return;
        OperationalWatchService.probePhonePing(intent.getIntExtra("behavior", 0), token,
                intent.getLongExtra("unixMs", System.currentTimeMillis()));
    }
}
