package dev.applewatchandroid.bridge;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent phone-side forwarding policy and content-free Companion projection. */
final class NotificationRelaySettings {
    private static final String COMPANION_PACKAGE = "dev.applewatchandroid.companion.apple_watch_companion";
    private final Context context;
    private final SharedPreferences preferences;

    NotificationRelaySettings(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("notification_relay", Context.MODE_PRIVATE);
    }

    NotificationRelayPolicy policy() {
        return new NotificationRelayPolicy(
                preferences.getBoolean("enabled", true),
                preferences.getStringSet("blocked", Set.of()),
                preferences.getBoolean("only_when_screen_off", false));
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

    boolean updateOnlyWhenScreenOff(boolean value) {
        return preferences.edit().putBoolean("only_when_screen_off", value).commit();
    }

    void remember(String packageName, String displayName) {
        if (!NotificationRelayPolicy.validPackage(packageName)) return;
        Set<String> seen = new HashSet<>(preferences.getStringSet("applications", Set.of()));
        String name = NotificationTextContent.limitUtf8(displayName, 256);
        if (name.equals(preferences.getString("name." + packageName, "")) && seen.contains(packageName)) return;
        if (!seen.contains(packageName) && seen.size() >= NotificationRelayPolicy.MAX_APPS) return;
        seen.add(packageName);
        preferences.edit().putStringSet("applications", seen).putString("name." + packageName, name).apply();
        CompanionSessionState.notificationRelayChanged();
    }

    void appendState(Bundle state) {
        var manager = context.getSystemService(NotificationManager.class);
        boolean access = manager != null && manager.isNotificationListenerAccessGranted(
                new ComponentName(context, AppleWatchNotificationListenerService.class));
        boolean enabled = preferences.getBoolean("enabled", true);
        boolean onlyWhenScreenOff = preferences.getBoolean("only_when_screen_off", false);
        state.putBoolean("notificationAccessGranted", access);
        state.putBoolean("notificationRelayEnabled", enabled);
        state.putBoolean("notificationOnlyWhenScreenOff", onlyWhenScreenOff);
        state.putBoolean("notificationListenerConnected", AppleWatchNotificationListenerService.listenerConnected());
        state.putBoolean("notificationRelayReady", access && enabled && AppleWatchNotificationListenerService.relayReady());
        state.putInt("notificationPendingCount", AppleWatchNotificationListenerService.pendingCount());

        var blocked = preferences.getStringSet("blocked", Set.of());
        Set<String> seen = new HashSet<>(preferences.getStringSet("applications", Set.of()));
        Map<String, String> resolvedNames = new HashMap<>();
        Set<String> packages = new HashSet<>(seen);
        packages.addAll(blocked);

        PackageManager pm = context.getPackageManager();
        try {
            List<ResolveInfo> launcherApps = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            for (ResolveInfo info : launcherApps) {
                if (info == null || info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (!NotificationRelayPolicy.validPackage(pkg) || excludedPackage(pkg)) continue;
                if (packages.size() >= NotificationRelayPolicy.MAX_APPS && !packages.contains(pkg)) continue;
                packages.add(pkg);
                CharSequence label = info.loadLabel(pm);
                if (label != null && !label.toString().isBlank()) {
                    resolvedNames.put(pkg, NotificationTextContent.limitUtf8(label.toString(), 256));
                }
            }
        } catch (RuntimeException ignored) { }

        ArrayList<Bundle> applications = new ArrayList<>();
        for (String pkg : packages) {
            if (!NotificationRelayPolicy.validPackage(pkg) || excludedPackage(pkg)) continue;
            if (applications.size() >= NotificationRelayPolicy.MAX_APPS) break;
            String label = resolvedNames.get(pkg);
            if (label == null || label.equals(pkg)) label = resolvePackageLabel(pm, pkg);
            if (label == null || label.isBlank()) label = preferences.getString("name." + pkg, pkg);
            Bundle row = new Bundle();
            row.putString("packageName", pkg);
            row.putString("name", label);
            row.putBoolean("enabled", !blocked.contains(pkg));
            row.putBoolean("observed", seen.contains(pkg));
            applications.add(row);
        }
        applications.sort((a, b) -> a.getString("name", "").compareToIgnoreCase(b.getString("name", "")));
        state.putParcelableArrayList("notificationApps", applications);
    }

    byte[] loadSectionIconPng(String packageName) {
        if (!NotificationRelayPolicy.validPackage(packageName)) return null;
        try {
            Drawable drawable = context.getPackageManager().getApplicationIcon(packageName);
            int size = 58;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)) return null;
                byte[] png = out.toByteArray();
                return png.length >= 1 && png.length <= BulletinDistributorCodec.SectionIconVariant.MAX_IMAGE_BYTES
                        ? png : null;
            } finally {
                bitmap.recycle();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return null;
        }
    }

    private boolean excludedPackage(String pkg) {
        return context.getPackageName().equals(pkg)
                || COMPANION_PACKAGE.equals(pkg)
                || "android".equals(pkg)
                || "com.android.systemui".equals(pkg);
    }

    private static String resolvePackageLabel(PackageManager pm, String pkg) {
        try {
            return NotificationTextContent.limitUtf8(
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), 256);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return null;
        }
    }
}
