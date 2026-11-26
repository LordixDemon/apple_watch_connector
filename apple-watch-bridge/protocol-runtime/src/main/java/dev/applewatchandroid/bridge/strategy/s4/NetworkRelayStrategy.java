package dev.applewatchandroid.bridge.strategy.s4;

import dev.applewatchandroid.bridge.PairingSessionRecord;
import dev.applewatchandroid.bridge.spi.AuthStyle;
import dev.applewatchandroid.bridge.spi.IdsLinkKind;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.StrategyCapabilities;
import dev.applewatchandroid.bridge.strategy.PairingOutcome;
import dev.applewatchandroid.bridge.strategy.PairingStrategy;
import dev.applewatchandroid.bridge.strategy.ReconnectOutcome;
import dev.applewatchandroid.bridge.strategy.StrategySessionContext;

import java.util.Objects;
import java.util.Set;

/**
 * Production Strategy 4 ({@code NRPairingStrategyNetworkRelayPairing}, watchOS 9/10+) plugin.
 *
 * <p>Delegates cleanly to the proven NetworkRelay pairing/reconnect pipeline with zero
 * dependency on legacy Strategies 1–3.</p>
 */
public final class NetworkRelayStrategy implements PairingStrategy {
    @FunctionalInterface
    public interface PairDelegate {
        PairingOutcome runPair(StrategySessionContext context) throws Exception;
    }

    @FunctionalInterface
    public interface ReconnectDelegate {
        ReconnectOutcome runReconnect(StrategySessionContext context, PairingSessionRecord restored) throws Exception;
    }

    public static final StrategyCapabilities CAPABILITIES = new StrategyCapabilities(
            PairingStrategyId.NETWORK_RELAY_S4,
            Set.of(AuthStyle.PIN, AuthStyle.OPTICAL, AuthStyle.OOB_KEY),
            IdsLinkKind.NETWORK_RELAY_ESP,
            true,
            true,
            true,
            true,
            true,
            true);

    private final PairDelegate pairDelegate;
    private final ReconnectDelegate reconnectDelegate;

    public NetworkRelayStrategy(PairDelegate pairDelegate, ReconnectDelegate reconnectDelegate) {
        this.pairDelegate = Objects.requireNonNull(pairDelegate, "pairDelegate");
        this.reconnectDelegate = Objects.requireNonNull(reconnectDelegate, "reconnectDelegate");
    }

    @Override
    public PairingStrategyId id() {
        return PairingStrategyId.NETWORK_RELAY_S4;
    }

    @Override
    public StrategyCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public PairingOutcome pair(StrategySessionContext context) throws Exception {
        return pairDelegate.runPair(context);
    }

    @Override
    public ReconnectOutcome reconnect(StrategySessionContext context, PairingSessionRecord restored) throws Exception {
        return reconnectDelegate.runReconnect(context, restored);
    }
}
