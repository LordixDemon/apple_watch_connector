package dev.applewatchandroid.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.util.UUID;

/** Local end-to-end notification probe. The reply never leaves this application. */
public final class LocalNotificationProbe extends BroadcastReceiver {
    static final String CHANNEL = AppleWatchNotificationListenerService.LOCAL_PROBE_CHANNEL;
    static final String POST = "dev.applewatchandroid.bridge.PROBE_POST";
    static final String CANCEL = "dev.applewatchandroid.bridge.PROBE_CANCEL";
    private static final String REPLY = "dev.applewatchandroid.bridge.PROBE_REPLY";
    private static final String INPUT = "watch_local_reply";
    private static final String TAG = "watch-local-probe";
    private static final int ID = 343;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        SharedPreferences state = context.getSharedPreferences("local_notification_probe", Context.MODE_PRIVATE);
        if (POST.equals(intent.getAction())) {
            if (!manager.areNotificationsEnabled()) {
                Log.i("WatchNotification", "LOCAL_PROBE_POST denied: notification permission missing.");
                return;
            }
            String nonce = UUID.randomUUID().toString();
            if (!state.edit().putString("nonce", nonce).putBoolean("pending", true).commit()) return;
            Intent reply = new Intent(context, LocalNotificationProbe.class).setAction(REPLY)
                    .setData(Uri.parse("aw-local-probe://reply/" + nonce));
            PendingIntent pending = PendingIntent.getBroadcast(context, ID, reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            RemoteInput input = new RemoteInput.Builder(INPUT).setLabel(context.getString(R.string.test_reply)).build();
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, context.getString(R.string.watch_reply_test),
                    NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Action action = new Notification.Action.Builder(0, context.getString(R.string.reply), pending)
                    .addRemoteInput(input).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build();
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher).setContentTitle(context.getString(R.string.watch_reply_bridge_test))
                    .setContentText(context.getString(R.string.tap_reply_your_reply_stays_in_bridge_on_this))
                    .setCategory(Notification.CATEGORY_MESSAGE).setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC).addAction(action).build();
            manager.notify(TAG, ID, notification);
            Log.i("WatchNotification", "LOCAL_PROBE_POSTED replyTarget=thisApp; no external recipient.");
        } else if (CANCEL.equals(intent.getAction())) {
            state.edit().putBoolean("pending", false).apply();
            manager.cancel(TAG, ID);
        } else if (REPLY.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data == null || !"aw-local-probe".equals(data.getScheme()) || !"reply".equals(data.getHost())
                    || !state.getBoolean("pending", false)
                    || !java.util.Objects.equals(data.getLastPathSegment(), state.getString("nonce", ""))) return;
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence answer = results == null ? null : results.getCharSequence(INPUT);
            if (answer == null || answer.length() == 0 || answer.length() > 4096) return;
            if (!state.edit().putBoolean("pending", false).putInt("replyLength", answer.length())
                    .putLong("replyObservedAt", System.currentTimeMillis()).commit()) return;
            Log.i("WatchNotification", "LOCAL_REPLY_PROBE_RECEIVED chars=" + answer.length()
                    + " nonceMatched=true; content logged=false.");
            manager.cancel(TAG, ID);
        }
    }
}
