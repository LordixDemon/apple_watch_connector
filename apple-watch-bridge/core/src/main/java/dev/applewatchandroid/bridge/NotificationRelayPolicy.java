package dev.applewatchandroid.bridge;

import java.util.Set;

/** Phone-side policy uses exact package identities, never guessed app categories. */
final class NotificationRelayPolicy {
    static final int MAX_APPS = 1024;
    private final boolean enabled;
    private final Set<String> blocked;
    private final boolean onlyWhenScreenOff;

    NotificationRelayPolicy(boolean enabled, Set<String> blocked) {
        this(enabled, blocked, false);
    }

    NotificationRelayPolicy(boolean enabled, Set<String> blocked, boolean onlyWhenScreenOff) {
        if (blocked == null || blocked.size() > MAX_APPS || blocked.stream().anyMatch(p -> !validPackage(p))) {
            throw new IllegalArgumentException("Invalid notification application policy");
        }
        this.enabled = enabled;
        this.blocked = Set.copyOf(blocked);
        this.onlyWhenScreenOff = onlyWhenScreenOff;
    }

    boolean onlyWhenScreenOff() {
        return onlyWhenScreenOff;
    }

    boolean allows(String packageName) {
        return enabled && validPackage(packageName) && !blocked.contains(packageName);
    }

    boolean allows(String packageName, boolean phoneInteractiveUnlocked) {
        return allows(packageName) && (!onlyWhenScreenOff || !phoneInteractiveUnlocked);
    }

    static boolean validPackage(String value) {
        return value != null && value.length() <= 256
                && value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }
}
