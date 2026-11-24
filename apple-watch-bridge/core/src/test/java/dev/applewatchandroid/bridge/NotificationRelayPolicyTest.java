package dev.applewatchandroid.bridge;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationRelayPolicyTest {
    @Test public void unknownApplicationsAreForwardedWithoutNameGuessing() {
        var policy = new NotificationRelayPolicy(true, Set.of());
        assertTrue(policy.allows("org.example.messenger"));
        assertTrue(policy.allows("com.example.bank"));
        assertFalse(policy.allows("android"));
        assertFalse(policy.allows(null));
    }
    @Test public void disablingIsExactAndGlobalPauseWins() {
        var policy = new NotificationRelayPolicy(true, Set.of("org.example.chat"));
        assertFalse(policy.allows("org.example.chat"));
        assertTrue(policy.allows("org.example.chat.beta"));
        assertFalse(new NotificationRelayPolicy(false, Set.of()).allows("org.example.chat.beta"));
    }
    @Test public void policyCannotBeChangedByMutatingItsInput() {
        var blocked = new HashSet<String>();
        var policy = new NotificationRelayPolicy(true, blocked);
        blocked.add("org.example.chat");
        assertTrue(policy.allows("org.example.chat"));
    }
    @Test public void invalidAndOversizedPoliciesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NotificationRelayPolicy(true, Set.of("bad\npackage")));
        var blocked = new HashSet<String>();
        for (int i = 0; i <= NotificationRelayPolicy.MAX_APPS; i++) blocked.add("org.example.app" + i);
        assertThrows(IllegalArgumentException.class, () -> new NotificationRelayPolicy(true, blocked));
    }
    @Test public void onlyWhenScreenOffSuppressesWhilePhoneInteractiveUnlocked() {
        var policy = new NotificationRelayPolicy(true, Set.of(), true);
        assertTrue(policy.onlyWhenScreenOff());
        assertFalse(policy.allows("org.example.chat", true));
        assertTrue(policy.allows("org.example.chat", false));
        assertTrue(new NotificationRelayPolicy(true, Set.of(), false).allows("org.example.chat", true));
    }
}
