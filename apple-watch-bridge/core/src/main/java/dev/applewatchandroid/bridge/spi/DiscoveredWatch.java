package dev.applewatchandroid.bridge.spi;

import java.util.Set;

/**
 * Public UI/IPC projection of one discovered Watch. Never contains raw MAC addresses
 * or raw setup-binding bytes.
 */
public record DiscoveredWatch(
        String token,
        String productType,
        String displayName,
        String watchOs,
        int rssi,
        PairingStrategyId strategy,
        boolean experimental,
        Set<AuthStyle> authStyles) {

    public DiscoveredWatch {
        if (token == null || token.isBlank() || displayName == null || displayName.isBlank()
                || strategy == null || authStyles == null) {
            throw new IllegalArgumentException("Invalid DiscoveredWatch projection");
        }
        authStyles = Set.copyOf(authStyles);
    }
}
