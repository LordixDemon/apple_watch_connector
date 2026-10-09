package dev.applewatchandroid.bridge;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Native notification presentation, independent of root/session lifetime. */
final class BridgeConnectionNotification {
    static final String CHANNEL = "watch_connection";
    static Notification build(Context context, String state, boolean connected, boolean phoneActive) {
        return build(context, state, connected, phoneActive, false);
    }
    static Notification setup(Context context) { return build(context, "Watch setup", false, false, true); }
    private static Notification build(Context context, String state, boolean connected, boolean phoneActive, boolean setup) {
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = setup ? new Intent(context, BridgeSetupService.class).putExtra("method", "disconnectWatch")
                : new Intent(context, OperationalWatchService.class).setAction(OperationalWatchService.ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(context, setup ? 2 : 0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String detail = context.getString(connected ? R.string.ui_notify_connected : R.string.ui_notify_waiting);
        String displayState = connected ? context.getString(R.string.ui_connected) : state;
        Notification.Builder builder = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_watch_status)
                .setContentTitle(context.getString(R.string.ui_watch_name)).setContentText(displayState).setContentIntent(open)
                .setStyle(new Notification.BigTextStyle().bigText(displayState + "\n" + detail))
                .setColor(0xffff9f0a).setColorized(false).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, context.getString(R.string.ui_open_bridge), open).build())
                .addAction(new Notification.Action.Builder(null, context.getString(R.string.disconnect), stop).build());
        if (phoneActive) {
            PendingIntent silence = PendingIntent.getService(context, 349,
                    new Intent(context, OperationalWatchService.class).setAction(OperationalWatchService.ACTION_STOP_PHONE_PING),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, context.getString(R.string.stop_alert), silence).build());
        }
        return builder.build();
    }
}
