package dev.applewatchandroid.bridge.strategy;

import dev.applewatchandroid.bridge.spi.StrategyPolicy;

/**
 * Immutable per-session configuration replacing process-wide mutable flags where possible.
 */
public record SessionConfig(
        StrategyPolicy strategyPolicy,
        boolean commitAuthorized,
        boolean companionDiscovery,
        String operationalPairingId) {

    public SessionConfig {
        strategyPolicy = strategyPolicy == null ? StrategyPolicy.PRODUCTION_DEFAULT : strategyPolicy;
    }

    public boolean isOperationalMode() {
        return operationalPairingId != null && !operationalPairingId.isBlank();
    }

    public boolean allowsExperimentalLegacy() {
        return !isOperationalMode() && strategyPolicy.includesExperimental();
    }
}
