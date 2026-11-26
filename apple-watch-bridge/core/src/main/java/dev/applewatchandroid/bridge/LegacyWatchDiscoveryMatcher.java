package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.spi.AuthStyle;
import dev.applewatchandroid.bridge.spi.DiscoveryMatcher;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.WatchAdvertisedProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parameterized legacy Watch discovery matcher for Strategies 1, 2, and 3.
 *
 * <p>Avoids blindly misclassifying ambiguous UUID-only or {@code "Apple Watch NNNNN"}
 * advertisements as Strategy 3 when a lower strategy is explicitly selected: explicit
 * strategy advertisements ({@code 'B'=1, 'C'=2, 'D'=3} or {@code 0xFE25} strategy 1–3)
 * match ONLY their exact strategy, while strategy-unspecified advertisements match only
 * the designated fallback matcher (with {@code strategyInferred = true}).</p>
 */
final class LegacyWatchDiscoveryMatcher
        implements DiscoveryMatcher<HciCodec.AdvertisingReport, HciCodec.AdvertisingReport> {
    private final PairingStrategyId targetStrategy;
    private final boolean acceptInferredUnknown;
    private LegacyWatchPairingCodec.LegacyTargetSelector selector =
            new LegacyWatchPairingCodec.LegacyTargetSelector();

    LegacyWatchDiscoveryMatcher(PairingStrategyId targetStrategy, boolean acceptInferredUnknown) {
        if (targetStrategy == null || !targetStrategy.isExperimental()) {
            throw new IllegalArgumentException("LegacyWatchDiscoveryMatcher requires S1, S2, or S3");
        }
        this.targetStrategy = targetStrategy;
        this.acceptInferredUnknown = acceptInferredUnknown;
    }

    @Override
    public PairingStrategyId strategyId() {
        return targetStrategy;
    }

    @Override
    public WatchAdvertisedProfile<HciCodec.AdvertisingReport> accept(
            HciCodec.AdvertisingReport report,
            long elapsedMs) {
        HciCodec.AdvertisingReport matched = selector.accept(report, elapsedMs);
        return toProfile(matched);
    }

    @Override
    public List<WatchAdvertisedProfile<HciCodec.AdvertisingReport>> pollReady(long elapsedMs) {
        List<WatchAdvertisedProfile<HciCodec.AdvertisingReport>> results = new ArrayList<>();
        HciCodec.AdvertisingReport ready;
        while ((ready = selector.pollReady(elapsedMs)) != null) {
            WatchAdvertisedProfile<HciCodec.AdvertisingReport> profile = toProfile(ready);
            if (profile != null) {
                results.add(profile);
            }
        }
        return results;
    }

    @Override
    public void reset() {
        selector = new LegacyWatchPairingCodec.LegacyTargetSelector();
    }

    private WatchAdvertisedProfile<HciCodec.AdvertisingReport> toProfile(HciCodec.AdvertisingReport matched) {
        if (matched == null || matched.legacySetup == null) {
            return null;
        }
        int advStrategy = matched.legacySetup.strategy;
        boolean inferred;
        if (LegacyWatchPairingCodec.isSupportedLegacyStrategy(advStrategy)) {
            if (advStrategy != targetStrategy.wireId()) {
                return null;
            }
            inferred = false;
        } else {
            if (!acceptInferredUnknown) {
                return null;
            }
            inferred = true;
        }
        String product = matched.legacySetup.productType;
        String displayName = (product != null && !product.isBlank())
                ? product + " (Legacy S" + targetStrategy.wireId() + ")"
                : "Apple Watch (Legacy S" + targetStrategy.wireId() + ")";
        String watchOs = (matched.legacySetup.systemVersion != null && !matched.legacySetup.systemVersion.isBlank())
                ? matched.legacySetup.systemVersion
                : targetStrategy.watchOsRange();
        return new WatchAdvertisedProfile<>(
                targetStrategy,
                inferred,
                matched.addressType,
                matched.address,
                matched.rssi,
                product,
                displayName,
                watchOs,
                matched.legacySetup.pairingVersion,
                Set.of(AuthStyle.PIN, AuthStyle.OPTICAL),
                null,
                matched);
    }
}
