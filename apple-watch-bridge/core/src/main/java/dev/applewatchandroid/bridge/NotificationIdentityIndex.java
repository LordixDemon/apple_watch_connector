package dev.applewatchandroid.bridge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded exact notification identities. Unknown or previous-process actions fail closed. */
final class NotificationIdentityIndex {
    record Identity(String androidKey, String sectionId, String publisherId, String recordId, String replyToken) { }
    private final int capacity;
    private final Map<String, Identity> byKey = new HashMap<>();
    private final Map<String, Identity> byPublisher = new HashMap<>();
    private final Map<String, Identity> byToken = new HashMap<>();
    NotificationIdentityIndex(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Invalid capacity");
        this.capacity = capacity;
    }
    synchronized Identity register(String key, String section) {
        if (key == null || key.isBlank() || section == null || section.isBlank()) return null;
        Identity existing = byKey.get(key);
        if (existing != null) return existing.sectionId.equals(section) ? existing : null;
        if (byKey.size() >= capacity) return null;
        String publisher = UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
        Identity identity = new Identity(key, section, publisher, publisher, UUID.randomUUID().toString());
        byKey.put(key, identity);
        byPublisher.put(publisher, identity);
        byToken.put(identity.replyToken, identity);
        return identity;
    }
    synchronized Identity match(String publisher, String record, String section) {
        Identity identity = byPublisher.get(publisher);
        return identity != null && identity.sectionId.equals(section)
                && (record == null || identity.recordId.equals(record)) ? identity : null;
    }
    synchronized Identity remove(String key) {
        Identity identity = byKey.remove(key);
        if (identity != null) { byPublisher.remove(identity.publisherId); byToken.remove(identity.replyToken); }
        return identity;
    }
    synchronized Identity matchLights(String publisher, String section, String token) {
        Identity identity = token != null ? byToken.get(token) : match(publisher, null, section);
        return identity != null && (publisher == null || identity.publisherId.equals(publisher))
                && (section == null || identity.sectionId.equals(section)) ? identity : null;
    }
    synchronized void clear() { byKey.clear(); byPublisher.clear(); byToken.clear(); }
}
