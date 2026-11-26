package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.discovery.CompositeDiscoveryEngine;
import dev.applewatchandroid.bridge.spi.DiscoveryMatcher;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.StrategyPolicy;

import java.util.List;

/**
 * Factory assembling the priority-ordered {@link CompositeDiscoveryEngine} across
 * Strategy 4 (production) and opt-in legacy Strategies 1–3.
 */
public final class WatchDiscoveryEngines {
    private WatchDiscoveryEngines() {
    }

    public static CompositeDiscoveryEngine<HciCodec.AdvertisingReport, HciCodec.AdvertisingReport>
            forPolicy(StrategyPolicy policy) {
        StrategyPolicy effective = policy == null ? StrategyPolicy.PRODUCTION_DEFAULT : policy;
        PairingStrategyId inferredFallback = effective.preferredLegacyStrategy();
        List<DiscoveryMatcher<HciCodec.AdvertisingReport, HciCodec.AdvertisingReport>> matchers = List.of(
                new NetworkRelayS4DiscoveryMatcher(),
                new LegacyWatchDiscoveryMatcher(
                        PairingStrategyId.DIRECT_IPSEC_S3,
                        inferredFallback == PairingStrategyId.DIRECT_IPSEC_S3),
                new LegacyWatchDiscoveryMatcher(
                        PairingStrategyId.SEQUENTIAL_S2,
                        inferredFallback == PairingStrategyId.SEQUENTIAL_S2),
                new LegacyWatchDiscoveryMatcher(
                        PairingStrategyId.SIMULTANEOUS_S1,
                        inferredFallback == PairingStrategyId.SIMULTANEOUS_S1));
        return new CompositeDiscoveryEngine<>(effective, matchers);
    }
}
