package dev.applewatchandroid.bridge;

import java.util.Set;

/** Phone-side policy uses exact package identities, never guessed app categories. */
final class NotificationRelayPolicy {
    static final int MAX_APPS = 256;
    private final boolean enabled;
    private final Set<String> blocked;

    NotificationRelayPolicy(boolean enabled, Set<String> blocked) {
        if (blocked == null || blocked.size() > MAX_APPS || blocked.stream().anyMatch(p -> !validPackage(p))) {
            throw new IllegalArgumentException("Invalid notification application policy");
        }
        this.enabled = enabled;
        this.blocked = Set.copyOf(blocked);
    }

    boolean allows(String packageName) {
        return enabled && validPackage(packageName) && !blocked.contains(packageName);
    }

    static boolean validPackage(String value) {
        return value != null && value.length() <= 256
                && value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }
}
