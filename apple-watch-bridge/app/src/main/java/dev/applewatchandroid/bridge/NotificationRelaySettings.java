package dev.applewatchandroid.bridge;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Persistent phone-side forwarding policy and content-free Companion projection. */
final class NotificationRelaySettings {
    private final Context context;
    private final SharedPreferences preferences;

    NotificationRelaySettings(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("notification_relay", Context.MODE_PRIVATE);
    }

    NotificationRelayPolicy policy() {
        return new NotificationRelayPolicy(preferences.getBoolean("enabled", true),
                preferences.getStringSet("blocked", Set.of()));
    }

    boolean update(String packageName, boolean enabled) {
        var edit = preferences.edit();
        if (packageName == null) edit.putBoolean("enabled", enabled);
        else {
            if (!NotificationRelayPolicy.validPackage(packageName)) throw new IllegalArgumentException("Invalid application");
            Set<String> blocked = new HashSet<>(preferences.getStringSet("blocked", Set.of()));
            if (enabled) blocked.remove(packageName); else blocked.add(packageName);
            new NotificationRelayPolicy(true, blocked);
            edit.putStringSet("blocked", blocked);
        }
        return edit.commit();
    }

    void remember(String packageName, String displayName) {
        if (!NotificationRelayPolicy.validPackage(packageName)) return;
        Set<String> seen = new HashSet<>(preferences.getStringSet("applications", Set.of()));
        String name = NotificationTextContent.limitUtf8(displayName, 256);
        if (name.equals(preferences.getString("name." + packageName, "")) && seen.contains(packageName)) return;
        if (!seen.contains(packageName) && seen.size() >= NotificationRelayPolicy.MAX_APPS) return;
        seen.add(packageName);
        if (preferences.edit().putStringSet("applications", seen).putString("name." + packageName, name).commit()) {
            CompanionSessionState.notificationRelayChanged();
        }
    }

    void appendState(Bundle state) {
        var manager = context.getSystemService(NotificationManager.class);
        boolean access = manager != null && manager.isNotificationListenerAccessGranted(
                new ComponentName(context, AppleWatchNotificationListenerService.class));
        boolean enabled = preferences.getBoolean("enabled", true);
        state.putBoolean("notificationAccessGranted", access);
        state.putBoolean("notificationRelayEnabled", enabled);
        state.putBoolean("notificationListenerConnected", AppleWatchNotificationListenerService.listenerConnected());
        state.putBoolean("notificationRelayReady", access && enabled && AppleWatchNotificationListenerService.relayReady());
        state.putInt("notificationPendingCount", AppleWatchNotificationListenerService.pendingCount());
        var blocked = preferences.getStringSet("blocked", Set.of());
        Set<String> packages = new HashSet<>(preferences.getStringSet("applications", Set.of()));
        packages.addAll(blocked);
        ArrayList<Bundle> applications = new ArrayList<>();
        for (String pkg : packages) {
            if (!NotificationRelayPolicy.validPackage(pkg)) continue;
            Bundle row = new Bundle();
            row.putString("packageName", pkg);
            row.putString("name", preferences.getString("name." + pkg, pkg));
            row.putBoolean("enabled", !blocked.contains(pkg));
            applications.add(row);
        }
        applications.sort((a, b) -> a.getString("name", "").compareToIgnoreCase(b.getString("name", "")));
        state.putParcelableArrayList("notificationApps", applications);
    }
}
