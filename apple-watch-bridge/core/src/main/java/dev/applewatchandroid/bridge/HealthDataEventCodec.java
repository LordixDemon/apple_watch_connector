package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Private HAL→APK encrypted Health DataMessage event; never an IDS Protobuf/sample. */
final class HealthDataEventCodec {
    static final String PREFIX = "BRIDGE_HEALTH_DATA_V1:";
    static final int MAX_PAYLOAD = 512 * 1024;
    static final int MAX_FRAME = MAX_PAYLOAD + 8192;
    private HealthDataEventCodec() { }

    static final class Event implements AutoCloseable {
        final UUID pair, epoch, messageId, responseTo;
        final int streamId, flags;
        private byte[] peerKey, encrypted;
        Event(UUID pair, UUID epoch, UUID messageId, UUID responseTo, int streamId, int flags,
              byte[] peerKey, byte[] encrypted) {
            if (pair == null || epoch == null || messageId == null || streamId < 1 || streamId > 65535
                    || flags < 0 || flags > 255 || encrypted == null || encrypted.length > MAX_PAYLOAD) {
                throw new IllegalArgumentException("Invalid Health Data event");
            }
            // Ciphertext may arrive before the correlated IDS device-info response.
            if (peerKey != null) IdsMessageProtectionIdentity.validatePublic(peerKey);
            requireEncryptedDictionary(encrypted);
            this.pair = pair; this.epoch = epoch; this.messageId = messageId; this.responseTo = responseTo;
            this.streamId = streamId; this.flags = flags;
            this.peerKey = peerKey == null ? null : peerKey.clone(); this.encrypted = encrypted.clone();
        }
        byte[] peerKey() { requireOpen(); return peerKey == null ? null : peerKey.clone(); }
        byte[] encrypted() { requireOpen(); return encrypted.clone(); }
        int encryptedSize() { requireOpen(); return encrypted.length; }
        private void requireOpen() { if (encrypted == null) throw new IllegalStateException("Health Data event closed"); }
        @Override public void close() { wipe(peerKey); wipe(encrypted); peerKey = null; encrypted = null; }
    }

    static void requireEncryptedDictionary(byte[] payload) {
        if (payload == null || payload.length < 8 || payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid encrypted Health size");
        }
        Object decoded = AppleBinaryPropertyList.decode(payload);
        try {
            if (!(decoded instanceof Map<?, ?> map) || !(map.get("ekd") instanceof byte[] ekd)
                    || !(map.get("sed") instanceof byte[] sed) || ekd.length < 4 || ekd.length > 4096
                    || sed.length == 0 || (sed.length & 15) != 0) {
                throw new IllegalArgumentException("Health requires encrypted A-over-C data");
            }
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }

    static byte[] encode(Event event) {
        event.requireOpen();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("v", 2L); map.put("pair", event.pair.toString()); map.put("epoch", event.epoch.toString());
        map.put("id", event.messageId.toString());
        if (event.responseTo != null) map.put("reply", event.responseTo.toString());
        map.put("stream", (long)event.streamId); map.put("flags", (long)event.flags);
        if (event.peerKey != null) map.put("peerA", event.peerKey);
        map.put("encrypted", event.encrypted);
        byte[] bytes = AppleBinaryPropertyList.encode(map);
        if (bytes.length > MAX_FRAME) { wipe(bytes); throw new IllegalArgumentException("Health IPC frame limit"); }
        return bytes;
    }
    static Event decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_FRAME) throw new IllegalArgumentException("Health IPC frame limit");
        Object decoded = AppleBinaryPropertyList.decode(bytes);
        try {
            if (!(decoded instanceof Map<?, ?> map)
                    || !(Long.valueOf(1).equals(map.get("v")) || Long.valueOf(2).equals(map.get("v")))
                    || (Long.valueOf(1).equals(map.get("v")) && !map.containsKey("peerA"))
                    || map.size() != 7 + (map.containsKey("reply") ? 1 : 0) + (map.containsKey("peerA") ? 1 : 0)
                    || !(map.get("stream") instanceof Long stream) || stream < 1 || stream > 65535
                    || !(map.get("flags") instanceof Long flags) || flags < 0 || flags > 255
                    || (map.containsKey("peerA") && !(map.get("peerA") instanceof byte[]))
                    || !(map.get("encrypted") instanceof byte[] payload)) {
                throw new IllegalArgumentException("Invalid Health IPC fields");
            }
            return new Event(uuid(map.get("pair")), uuid(map.get("epoch")), uuid(map.get("id")),
                    map.containsKey("reply") ? uuid(map.get("reply")) : null,
                    stream.intValue(), flags.intValue(), (byte[])map.get("peerA"), payload);
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }
    private static UUID uuid(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Missing Health IPC identity");
        UUID uuid = UUID.fromString(text);
        if (!uuid.toString().equals(text)) throw new IllegalArgumentException("Noncanonical Health IPC identity");
        return uuid;
    }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte)0); }
}
