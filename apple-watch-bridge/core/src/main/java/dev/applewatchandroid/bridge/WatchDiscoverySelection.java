package dev.applewatchandroid.bridge;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Opaque session-local choices. A stale token never selects a different peer. */
final class WatchDiscoverySelection<T> {
    private record Entry<T>(String token, T peer, long seenAt) {}
    private final LinkedHashMap<String, Entry<T>> peers = new LinkedHashMap<>();
    String observe(String privateKey, T peer, long now) {
        Entry<T> old = peers.get(privateKey);
        String token = old == null ? UUID.randomUUID().toString() : old.token;
        if (old == null && peers.size() >= 8) peers.remove(peers.keySet().iterator().next());
        peers.put(privateKey, new Entry<>(token, peer, now));
        return token;
    }
    T select(String token, long now) {
        if (token == null) return null;
        for (Entry<T> peer : peers.values()) {
            if (peer.token.equals(token) && now >= peer.seenAt && now - peer.seenAt <= 5_000) return peer.peer;
        }
        return null;
    }
}
