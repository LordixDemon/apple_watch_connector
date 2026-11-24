package dev.applewatchandroid.bridge;

/** Exact correlation identity tuple for a mirrored Android notification. */
final class NotificationIdentityIndex {
    private NotificationIdentityIndex() { }
    record Identity(String androidKey, String sectionId, String publisherId, String recordId, String replyToken) { }
}
