package dev.applewatchandroid.bridge.spi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable ordered set of enabled pairing strategies for one session.
 * Production default enables only {@link PairingStrategyId#NETWORK_RELAY_S4}.
 */
public final class StrategyPolicy {
    public static final StrategyPolicy PRODUCTION_DEFAULT = new StrategyPolicy(
            List.of(PairingStrategyId.NETWORK_RELAY_S4));

    public static final StrategyPolicy ALL_WITH_EXPERIMENTAL = new StrategyPolicy(List.of(
            PairingStrategyId.NETWORK_RELAY_S4,
            PairingStrategyId.DIRECT_IPSEC_S3,
            PairingStrategyId.SEQUENTIAL_S2,
            PairingStrategyId.SIMULTANEOUS_S1));

    private final List<PairingStrategyId> orderedEnabled;

    public StrategyPolicy(List<PairingStrategyId> enabled) {
        if (enabled == null || enabled.isEmpty()) {
            throw new IllegalArgumentException("At least one pairing strategy must be enabled");
        }
        Set<PairingStrategyId> unique = new LinkedHashSet<>(enabled);
        if (unique.contains(null)) {
            throw new IllegalArgumentException("Strategy policy cannot contain null");
        }
        this.orderedEnabled = List.copyOf(unique);
    }

    public List<PairingStrategyId> orderedEnabled() {
        return orderedEnabled;
    }

    public boolean allows(PairingStrategyId id) {
        return id != null && orderedEnabled.contains(id);
    }

    public boolean includesExperimental() {
        for (PairingStrategyId id : orderedEnabled) {
            if (id.isExperimental()) {
                return true;
            }
        }
        return false;
    }

    /** Highest-priority enabled legacy strategy (S1..S3), or null if only S4 is enabled. */
    public PairingStrategyId preferredLegacyStrategy() {
        PairingStrategyId best = null;
        for (PairingStrategyId id : orderedEnabled) {
            if (id.isExperimental() && (best == null || id.discoveryPriority() > best.discoveryPriority())) {
                best = id;
            }
        }
        return best;
    }

    public String toCsv() {
        return orderedEnabled.stream()
                .map(id -> Integer.toString(id.wireId()))
                .collect(Collectors.joining(","));
    }

    public static StrategyPolicy parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("Strategy CSV must not be empty");
        }
        String[] tokens = csv.split(",");
        if (tokens.length > PairingStrategyId.values().length) {
            throw new IllegalArgumentException("Too many strategy entries");
        }
        List<PairingStrategyId> parsed = new ArrayList<>(tokens.length);
        for (String raw : tokens) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Empty strategy token in: " + csv);
            }
            int wireId;
            try {
                wireId = Integer.parseInt(trimmed);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid strategy id: " + trimmed, invalid);
            }
            PairingStrategyId id = PairingStrategyId.fromWireId(wireId);
            if (id == null) {
                throw new IllegalArgumentException("Unsupported pairing strategy id: " + wireId);
            }
            if (parsed.contains(id)) {
                throw new IllegalArgumentException("Duplicate pairing strategy id: " + wireId);
            }
            parsed.add(id);
        }
        return new StrategyPolicy(parsed);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StrategyPolicy policy && orderedEnabled.equals(policy.orderedEnabled);
    }

    @Override
    public int hashCode() {
        return orderedEnabled.hashCode();
    }

    @Override
    public String toString() {
        return "StrategyPolicy[" + toCsv() + "]";
    }
}
