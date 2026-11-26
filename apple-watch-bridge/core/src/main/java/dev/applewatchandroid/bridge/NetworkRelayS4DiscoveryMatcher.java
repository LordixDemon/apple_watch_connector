package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.spi.AuthStyle;
import dev.applewatchandroid.bridge.spi.DiscoveryMatcher;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.WatchAdvertisedProfile;

import java.util.Set;

/**
 * Production Strategy 4 (NetworkRelay) discovery matcher wrapping {@link WatchSetupTargetSelector}.
 */
final class NetworkRelayS4DiscoveryMatcher
        implements DiscoveryMatcher<HciCodec.AdvertisingReport, HciCodec.AdvertisingReport> {
    private WatchSetupTargetSelector selector = new WatchSetupTargetSelector();

    @Override
    public PairingStrategyId strategyId() {
        return PairingStrategyId.NETWORK_RELAY_S4;
    }

    @Override
    public WatchAdvertisedProfile<HciCodec.AdvertisingReport> accept(
            HciCodec.AdvertisingReport report,
            long elapsedMs) {
        HciCodec.AdvertisingReport target = selector.accept(report, elapsedMs);
        if (target == null || target.setup == null || target.setup.decodedMetadata == null) {
            return null;
        }
        String product = target.setup.decodedMetadata.productType();
        String version = target.setup.decodedMetadata.systemVersionString();
        return new WatchAdvertisedProfile<>(
                PairingStrategyId.NETWORK_RELAY_S4,
                false,
                target.addressType,
                target.address,
                target.rssi,
                product,
                product != null && !product.isBlank() ? product : "Apple Watch",
                version,
                target.setup.decodedMetadata.pairingVersion,
                Set.of(AuthStyle.PIN, AuthStyle.OPTICAL),
                target.setup.watchSetupData,
                target);
    }

    @Override
    public void reset() {
        selector = new WatchSetupTargetSelector();
    }
}
