package dev.applewatchandroid.bridge.spi;

import java.util.Arrays;
import java.util.Set;

/**
 * Unified discovered Watch target produced by a {@link DiscoveryMatcher}.
 * Secret/identifying byte buffers ({@link #address} and {@link #rawSetupBinding})
 * are defensively copied and zeroized on {@link #close()}.
 */
public final class WatchAdvertisedProfile<T> implements AutoCloseable {
    public final PairingStrategyId strategyId;
    public final boolean strategyInferred;
    public final int addressType;
    private final byte[] address;
    public final int rssi;
    public final String productType;
    public final String displayName;
    public final String watchOsVersion;
    public final int pairingVersion;
    public final Set<AuthStyle> authStyles;
    private final byte[] rawSetupBinding;
    public final T payload;

    public WatchAdvertisedProfile(
            PairingStrategyId strategyId,
            boolean strategyInferred,
            int addressType,
            byte[] address,
            int rssi,
            String productType,
            String displayName,
            String watchOsVersion,
            int pairingVersion,
            Set<AuthStyle> authStyles,
            byte[] rawSetupBinding,
            T payload) {
        if (strategyId == null || address == null || address.length != 6 || displayName == null || authStyles == null) {
            throw new IllegalArgumentException("Invalid WatchAdvertisedProfile");
        }
        this.strategyId = strategyId;
        this.strategyInferred = strategyInferred;
        this.addressType = addressType;
        this.address = address.clone();
        this.rssi = rssi;
        this.productType = productType;
        this.displayName = displayName;
        this.watchOsVersion = watchOsVersion;
        this.pairingVersion = pairingVersion;
        this.authStyles = Set.copyOf(authStyles);
        this.rawSetupBinding = rawSetupBinding == null ? null : rawSetupBinding.clone();
        this.payload = payload;
    }

    public byte[] addressCopy() {
        return address.clone();
    }

    public byte[] rawSetupBindingCopy() {
        return rawSetupBinding == null ? null : rawSetupBinding.clone();
    }

    public String privatePeerKey() {
        StringBuilder sb = new StringBuilder(14);
        sb.append(addressType).append(':');
        for (byte b : address) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public DiscoveredWatch toPublicObservation(String token) {
        return new DiscoveredWatch(
                token,
                productType,
                displayName,
                watchOsVersion,
                rssi,
                strategyId,
                strategyId.isExperimental(),
                authStyles);
    }

    @Override
    public void close() {
        Arrays.fill(address, (byte) 0);
        if (rawSetupBinding != null) {
            Arrays.fill(rawSetupBinding, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "WatchAdvertisedProfile[strategy=" + strategyId.wireId()
                + ", inferred=" + strategyInferred
                + ", rssi=" + rssi
                + "; address logged=false]";
    }
}
