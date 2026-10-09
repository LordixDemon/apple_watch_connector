package dev.applewatchandroid.bridge;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Authenticated Watch request ledger. Only the current child APK can complete a pending request. */
final class FindMyPhoneSession {
    record Incoming(FindMyPhoneIpcCodec.Request request, FindMyPhoneIpcCodec.Result cached) { }
    private static final class Entry {
        final FindMyPhoneIpcCodec.Request request;
        final long expires;
        FindMyPhoneIpcCodec.Result result;
        Entry(FindMyPhoneIpcCodec.Request request, long now) { this.request = request; expires = now + 60_000; }
    }
    private UUID epoch;
    private final LinkedHashMap<UUID, Entry> requests = new LinkedHashMap<>();
    void reset(UUID epoch) { this.epoch = epoch; requests.clear(); }
    Incoming receive(int type, boolean response, String id, byte[] payload, long unixMs, long elapsedMs) {
        if (epoch == null || response || (type != 1 && type != 2)) return null;
        var body = FindMyLocalDeviceCodec.PlaySoundRequest.decode(type, payload);
        if (!body.freshAt(unixMs)) return null;
        var request = new FindMyPhoneIpcCodec.Request(epoch, id, type, body, elapsedMs + 10_000);
        expire(elapsedMs);
        UUID key = UUID.fromString(id);
        Entry existing = requests.get(key);
        if (existing != null) {
            if (existing.request.type() != type || !existing.request.body().equals(body)) {
                throw new IllegalArgumentException("Conflicting phone Ping UUID");
            }
            return new Incoming(null, existing.result);
        }
        if (requests.size() >= 64) return null;
        requests.put(key, new Entry(request, elapsedMs));
        return new Incoming(request, null);
    }
    FindMyPhoneIpcCodec.Request complete(FindMyPhoneIpcCodec.Result result, long now) {
        expire(now);
        if (!result.epoch().equals(epoch)) return null;
        Entry entry = requests.get(UUID.fromString(result.messageId()));
        if (entry == null || entry.result != null || entry.request.type() != result.type()
                || now >= entry.request.deadline()) return null;
        entry.result = result;
        return entry.request;
    }
    boolean current(UUID value) { return value != null && value.equals(epoch); }
    private void expire(long now) { requests.values().removeIf(entry -> now >= entry.expires); }
}
