package dev.applewatchandroid.bridge.strategy;

import dev.applewatchandroid.bridge.spi.IdsLinkKind;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;

/**
 * Public, secret-free outcome of a saved-pair reconnection attempt by a {@link PairingStrategy}.
 */
public record ReconnectOutcome(
        PairingStrategyId strategyId,
        IdsLinkKind idsLinkKind,
        boolean reconnected,
        boolean setupOnly,
        String detail) {

    public ReconnectOutcome {
        if (strategyId == null) {
            throw new IllegalArgumentException("ReconnectOutcome requires a strategyId");
        }
        detail = detail == null ? "" : detail;
    }

    public static ReconnectOutcome success(PairingStrategyId strategyId, IdsLinkKind linkKind, String detail) {
        return new ReconnectOutcome(strategyId, linkKind, true, false, detail);
    }

    public static ReconnectOutcome failed(PairingStrategyId strategyId, IdsLinkKind linkKind, String detail) {
        return new ReconnectOutcome(strategyId, linkKind, false, false, detail);
    }

    public static ReconnectOutcome peerSetupOnly(PairingStrategyId strategyId, String detail) {
        return new ReconnectOutcome(strategyId, null, false, true, detail);
    }
}
