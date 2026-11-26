package dev.applewatchandroid.bridge.strategy.s2;

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
 * Strategy 2 ({@code NRPairingStrategySequential}, watchOS 3–5) plugin.
 *
 * <p>Executes GATT {@code NRExtensiblePair} {@code "NANO" + 0x02 + style} write -> BLE SMP ->
 * {@code BT_CL} {@code com.apple.ids} ({@code 0x0003}) {@code IDSBTLink}.</p>
 */
public final class SequentialLegacyStrategy implements PairingStrategy {
    public static final StrategyCapabilities CAPABILITIES = new StrategyCapabilities(
            PairingStrategyId.SEQUENTIAL_S2,
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
        return PairingStrategyId.SEQUENTIAL_S2;
    }

    @Override
    public StrategyCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public PairingOutcome pair(StrategySessionContext context) throws Exception {
        return LegacyPairingSupport.runLegacyPair(context, PairingStrategyId.SEQUENTIAL_S2, true, false);
    }

    @Override
    public ReconnectOutcome reconnect(StrategySessionContext context, PairingSessionRecord restored) throws Exception {
        return LegacyReconnectSupport.reconnect(context, restored);
    }
}
