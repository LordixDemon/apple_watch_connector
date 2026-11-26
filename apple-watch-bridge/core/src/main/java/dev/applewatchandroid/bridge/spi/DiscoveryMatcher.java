package dev.applewatchandroid.bridge.spi;

import java.util.List;

/**
 * Plug-in advertisement matcher for one pairing strategy (or family).
 * Higher {@link #priority()} wins when a peer matches multiple enabled matchers.
 */
public interface DiscoveryMatcher<R, T> {
    PairingStrategyId strategyId();

    default int priority() {
        return strategyId().discoveryPriority();
    }

    WatchAdvertisedProfile<T> accept(R report, long elapsedMs);

    default List<WatchAdvertisedProfile<T>> pollReady(long elapsedMs) {
        return List.of();
    }

    void reset();
}
