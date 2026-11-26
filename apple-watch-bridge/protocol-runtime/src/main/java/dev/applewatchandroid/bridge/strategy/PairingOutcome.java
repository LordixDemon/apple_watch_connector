package dev.applewatchandroid.bridge.strategy;

import dev.applewatchandroid.bridge.spi.IdsLinkKind;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;

import java.util.List;

/**
 * Public, secret-free summary of a fresh pairing execution by a {@link PairingStrategy} plugin.
 */
public record PairingOutcome(
        PairingStrategyId strategyId,
        IdsLinkKind idsLinkKind,
        boolean secureConnections,
        boolean bondPersisted,
        boolean sessionRecordPersisted,
        boolean idsLinkOpened,
        int idsFramesObserved,
        String terminusReconciledState,
        String terminusRole,
        List<Integer> commonServices,
        String summary) {

    public PairingOutcome {
        if (strategyId == null) {
            throw new IllegalArgumentException("PairingOutcome requires a strategyId");
        }
        commonServices = commonServices == null ? List.of() : List.copyOf(commonServices);
        summary = summary == null ? "" : summary;
    }
}
