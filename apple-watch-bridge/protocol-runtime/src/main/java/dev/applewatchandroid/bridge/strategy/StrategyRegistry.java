package dev.applewatchandroid.bridge.strategy;

import dev.applewatchandroid.bridge.PairingSessionRecord;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.StrategyCapabilities;
import dev.applewatchandroid.bridge.spi.StrategyPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered registry of installed {@link PairingStrategy} plugins ({@code [S4, S3, S2, S1]}).
 *
 * <p>Dispatches fresh pairing by selected strategy (enforcing {@link StrategyPolicy}) and
 * dispatches saved-pair reconnection strictly from the persisted {@link PairingSessionRecord}'s
 * {@link PairingStrategyId} without requiring any legacy CLI flag.</p>
 */
public final class StrategyRegistry {
    private final Map<PairingStrategyId, PairingStrategy> byId = new EnumMap<>(PairingStrategyId.class);
    private final List<PairingStrategy> ordered;

    public StrategyRegistry(List<PairingStrategy> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("StrategyRegistry requires at least one strategy");
        }
        List<PairingStrategy> copy = new ArrayList<>(strategies.size());
        for (PairingStrategy strategy : strategies) {
            if (strategy == null || strategy.id() == null) {
                throw new IllegalArgumentException("StrategyRegistry cannot contain null strategies");
            }
            if (byId.put(strategy.id(), strategy) != null) {
                throw new IllegalArgumentException("Duplicate pairing strategy registered: " + strategy.id());
            }
            copy.add(strategy);
        }
        copy.sort(Comparator.comparingInt((PairingStrategy s) -> s.id().discoveryPriority()).reversed());
        this.ordered = List.copyOf(copy);
    }

    public PairingStrategy require(PairingStrategyId id) {
        PairingStrategy strategy = id == null ? null : byId.get(id);
        if (strategy == null) {
            throw new IllegalArgumentException("No pairing strategy registered for " + id);
        }
        return strategy;
    }

    public PairingStrategy requireEnabled(PairingStrategyId id, StrategyPolicy policy) {
        if (policy == null || !policy.allows(id)) {
            throw new IllegalStateException("Pairing strategy " + id + " is not enabled by active policy");
        }
        return require(id);
    }

    /** Resolves the strategy plugin for an already-persisted AWP2 pairing record. */
    public PairingStrategy forRecord(PairingSessionRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("PairingSessionRecord is required");
        }
        return require(record.pairingStrategy());
    }

    public List<PairingStrategy> all() {
        return ordered;
    }

    public List<PairingStrategy> enabledFor(StrategyPolicy policy) {
        StrategyPolicy effective = policy == null ? StrategyPolicy.PRODUCTION_DEFAULT : policy;
        List<PairingStrategy> enabled = new ArrayList<>();
        for (PairingStrategy strategy : ordered) {
            if (effective.allows(strategy.id())) {
                enabled.add(strategy);
            }
        }
        return List.copyOf(enabled);
    }

    public List<StrategyCapabilities> capabilities() {
        List<StrategyCapabilities> list = new ArrayList<>(ordered.size());
        for (PairingStrategy strategy : ordered) {
            list.add(strategy.capabilities());
        }
        return List.copyOf(list);
    }
}
