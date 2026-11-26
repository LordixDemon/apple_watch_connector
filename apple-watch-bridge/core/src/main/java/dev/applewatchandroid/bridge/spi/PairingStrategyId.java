package dev.applewatchandroid.bridge.spi;

/**
 * Apple Watch pairing strategies from {@code nanoregistryd} ({@code NRPairingStrategy}).
 *
 * <ul>
 *   <li>{@link #SIMULTANEOUS_S1}: watchOS 1–3, no GATT NANO write, BLE SMP + {@code com.apple.ids}.</li>
 *   <li>{@link #SEQUENTIAL_S2}: watchOS 3–5, GATT NANO write -> BLE SMP -> {@code com.apple.ids}.</li>
 *   <li>{@link #DIRECT_IPSEC_S3}: watchOS 6–9, GATT NANO write -> BLE SMP -> {@code terminusLink} or {@code com.apple.ids}.</li>
 *   <li>{@link #NETWORK_RELAY_S4}: watchOS 9/10+, {@code terminusPairing} uIKE -> OOB SMP -> {@code terminusLink} IPsec.</li>
 * </ul>
 */
public enum PairingStrategyId {
    SIMULTANEOUS_S1(1, "simultaneous_legacy", 'B', Maturity.EXPERIMENTAL, "watchOS 1–3", 10),
    SEQUENTIAL_S2(2, "sequential_legacy", 'C', Maturity.EXPERIMENTAL, "watchOS 3–5", 20),
    DIRECT_IPSEC_S3(3, "direct_ipsec_legacy", 'D', Maturity.EXPERIMENTAL, "watchOS 6–9", 30),
    NETWORK_RELAY_S4(4, "network_relay", 'E', Maturity.PRODUCTION, "watchOS 9+", 100);

    public enum Maturity {
        PRODUCTION,
        EXPERIMENTAL
    }

    private final int wireId;
    private final String slug;
    private final char advertisedChar;
    private final Maturity maturity;
    private final String watchOsRange;
    private final int discoveryPriority;

    PairingStrategyId(
            int wireId,
            String slug,
            char advertisedChar,
            Maturity maturity,
            String watchOsRange,
            int discoveryPriority) {
        this.wireId = wireId;
        this.slug = slug;
        this.advertisedChar = advertisedChar;
        this.maturity = maturity;
        this.watchOsRange = watchOsRange;
        this.discoveryPriority = discoveryPriority;
    }

    public int wireId() {
        return wireId;
    }

    public String slug() {
        return slug;
    }

    public char advertisedChar() {
        return advertisedChar;
    }

    public Maturity maturity() {
        return maturity;
    }

    public boolean isExperimental() {
        return maturity == Maturity.EXPERIMENTAL;
    }

    public String watchOsRange() {
        return watchOsRange;
    }

    public int discoveryPriority() {
        return discoveryPriority;
    }

    public static PairingStrategyId fromWireId(int wireId) {
        for (PairingStrategyId candidate : values()) {
            if (candidate.wireId == wireId) {
                return candidate;
            }
        }
        return null;
    }

    public static PairingStrategyId fromAdvertisedChar(char c) {
        for (PairingStrategyId candidate : values()) {
            if (candidate.advertisedChar == c) {
                return candidate;
            }
        }
        return null;
    }
}
