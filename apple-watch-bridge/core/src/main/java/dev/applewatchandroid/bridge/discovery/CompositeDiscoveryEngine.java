package dev.applewatchandroid.bridge.discovery;

import dev.applewatchandroid.bridge.spi.DiscoveryMatcher;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.StrategyPolicy;
import dev.applewatchandroid.bridge.spi.WatchAdvertisedProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Priority-ordered composite discovery router across all enabled {@link DiscoveryMatcher}
 * plugins. Guarantees that higher-priority strategies (Strategy 4 = 100) take precedence
 * over lower-priority experimental strategies (S3 = 30, S2 = 20, S1 = 10) and isolates
 * session-local selection tokens.
 */
public final class CompositeDiscoveryEngine<R, T> {
    private static final long SELECTION_TTL_MS = 5_000L;
    private static final int MAX_PEERS = 8;

    private record Entry<T>(String token, WatchAdvertisedProfile<T> profile, long seenAtMs) {}

    private final List<DiscoveryMatcher<R, T>> matchers;
    private final Map<String, Entry<T>> peers = new LinkedHashMap<>();

    public CompositeDiscoveryEngine(StrategyPolicy policy, List<DiscoveryMatcher<R, T>> allMatchers) {
        if (policy == null || allMatchers == null) {
            throw new IllegalArgumentException("CompositeDiscoveryEngine requires policy and matchers");
        }
        List<DiscoveryMatcher<R, T>> enabled = new ArrayList<>();
        for (DiscoveryMatcher<R, T> matcher : allMatchers) {
            if (policy.allows(matcher.strategyId())) {
                enabled.add(matcher);
            }
        }
        enabled.sort(Comparator.comparingInt((DiscoveryMatcher<R, T> m) -> m.priority()).reversed());
        this.matchers = List.copyOf(enabled);
    }

    public List<PairingStrategyId> enabledMatcherStrategies() {
        List<PairingStrategyId> ids = new ArrayList<>(matchers.size());
        for (DiscoveryMatcher<R, T> m : matchers) {
            ids.add(m.strategyId());
        }
        return List.copyOf(ids);
    }

    /** Feeds one raw report through matchers in strict descending priority; first match wins. */
    public WatchAdvertisedProfile<T> accept(R report, long elapsedMs) {
        for (DiscoveryMatcher<R, T> matcher : matchers) {
            WatchAdvertisedProfile<T> match = matcher.accept(report, elapsedMs);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    /** Polls deferred/buffered candidates (e.g. legacy UUID-only peers after hold window). */
    public List<WatchAdvertisedProfile<T>> pollReady(long elapsedMs) {
        List<WatchAdvertisedProfile<T>> ready = new ArrayList<>();
        for (DiscoveryMatcher<R, T> matcher : matchers) {
            List<WatchAdvertisedProfile<T>> batch = matcher.pollReady(elapsedMs);
            if (batch != null && !batch.isEmpty()) {
                ready.addAll(batch);
            }
        }
        return ready;
    }

    /** Records an observed profile under an opaque session-local token without exposing MAC. */
    public String observeSelection(WatchAdvertisedProfile<T> profile, long nowMs) {
        String key = profile.privatePeerKey();
        Entry<T> old = peers.get(key);
        String token = old == null ? UUID.randomUUID().toString() : old.token();
        if (old == null && peers.size() >= MAX_PEERS) {
            peers.remove(peers.keySet().iterator().next());
        }
        peers.put(key, new Entry<>(token, profile, nowMs));
        return token;
    }

    /** Resolves an opaque user-selected token if still within the 5-second freshness window. */
    public WatchAdvertisedProfile<T> select(String token, long nowMs) {
        if (token == null) {
            return null;
        }
        for (Entry<T> entry : peers.values()) {
            if (entry.token().equals(token) && nowMs >= entry.seenAtMs()
                    && nowMs - entry.seenAtMs() <= SELECTION_TTL_MS) {
                return entry.profile();
            }
        }
        return null;
    }

    public void reset() {
        for (DiscoveryMatcher<R, T> matcher : matchers) {
            matcher.reset();
        }
        for (Entry<T> entry : peers.values()) {
            entry.profile().close();
        }
        peers.clear();
    }
}
