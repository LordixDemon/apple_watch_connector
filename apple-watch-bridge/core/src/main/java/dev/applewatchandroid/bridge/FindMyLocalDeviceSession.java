package dev.applewatchandroid.bridge;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Epoch-local, bounded, one-use correlation of native Ping application replies. */
final class FindMyLocalDeviceSession {
    private record Pending(int type, long deadline) { }
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();
    void reset() { pending.clear(); }
    boolean canQueue(long now) { expire(now); return pending.size() < 8; }

    void queued(int type, boolean response, String uuid, long now) {
        expire(now);
        if (response || uuid == null || (type != 1 && type != 2)) return;
        if (!UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)) throw new IllegalArgumentException("Invalid Ping UUID");
        if (pending.containsKey(uuid)) return;
        if (pending.size() >= 8) throw new IllegalStateException("Ping reply window full");
        pending.put(uuid, new Pending(type, now + 60_000));
    }

    FindMyLocalDeviceCodec.PlaySoundResponse receive(int type, boolean response,
            String responseId, byte[] payload, long now) {
        expire(now);
        if (!response || responseId == null) return null;
        Pending request = pending.get(responseId);
        if (request == null || request.type != type) return null;
        var result = FindMyLocalDeviceCodec.PlaySoundResponse.decode(payload);
        pending.remove(responseId); // Malformed/wrong-direction replies do not consume the request.
        return result;
    }
    private void expire(long now) { pending.values().removeIf(request -> now >= request.deadline); }
}
