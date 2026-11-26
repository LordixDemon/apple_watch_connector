package dev.applewatchandroid.bridge.spi;

import java.util.Set;

/** Static capability declaration exposed by each {@code PairingStrategy} plugin. */
public record StrategyCapabilities(
        PairingStrategyId id,
        Set<AuthStyle> supportedAuthStyles,
        IdsLinkKind primaryIdsLink,
        boolean supportsReconnect,
        boolean supportsOperationalIds,
        boolean supportsActivation,
        boolean supportsWatchFaces,
        boolean supportsHealth,
        boolean supportsWifiSync) {

    public StrategyCapabilities {
        if (id == null || supportedAuthStyles == null || supportedAuthStyles.isEmpty() || primaryIdsLink == null) {
            throw new IllegalArgumentException("StrategyCapabilities requires id, authStyles, and primaryIdsLink");
        }
        supportedAuthStyles = Set.copyOf(supportedAuthStyles);
    }
}
