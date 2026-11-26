package dev.applewatchandroid.bridge.strategy.s1;

import dev.applewatchandroid.bridge.PairingSessionRecord;
import dev.applewatchandroid.bridge.spi.AuthStyle;
import dev.applewatchandroid.bridge.spi.IdsLinkKind;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.StrategyCapabilities;
import dev.applewatchandroid.bridge.strategy.PairingOutcome;
import dev.applewatchandroid.bridge.strategy.PairingStrategy;
import dev.applewatchandroid.bridge.strategy.ReconnectOutcome;
import dev.applewatchandroid.bridge.strategy.StrategySessionContext;
import dev.applewatchandroid.bridge.strategy.legacy.LegacyPairingSupport;
import dev.applewatchandroid.bridge.strategy.legacy.LegacyReconnectSupport;

import java.util.Set;

/**
 * Strategy 1 ({@code NRPairingStrategySimultaneous}, watchOS 1–3, Series 0 / BT 4.0) plugin.
 *
 * <p>Skips GATT {@code NANO} write, executes BLE SMP (tolerating {@code SC=0} LE Legacy with
 * persisted {@code EDIV}/{@code Rand} as well as {@code SC=1}), and opens {@code com.apple.ids}
 * ({@code 0x0003}) over {@code BT_CL}.</p>
 */
public final class SimultaneousLegacyStrategy implements PairingStrategy {
    public static final StrategyCapabilities CAPABILITIES = new StrategyCapabilities(
            PairingStrategyId.SIMULTANEOUS_S1,
            Set.of(AuthStyle.PIN, AuthStyle.OPTICAL),
            IdsLinkKind.IDS_BT_LINK,
            true,
            false,
            false,
            false,
            false,
            false);

    @Override
    public PairingStrategyId id() {
        return PairingStrategyId.SIMULTANEOUS_S1;
    }

    @Override
    public StrategyCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public PairingOutcome pair(StrategySessionContext context) throws Exception {
        return LegacyPairingSupport.runLegacyPair(context, PairingStrategyId.SIMULTANEOUS_S1, false, false);
    }

    @Override
    public ReconnectOutcome reconnect(StrategySessionContext context, PairingSessionRecord restored) throws Exception {
        return LegacyReconnectSupport.reconnect(context, restored);
    }
}
