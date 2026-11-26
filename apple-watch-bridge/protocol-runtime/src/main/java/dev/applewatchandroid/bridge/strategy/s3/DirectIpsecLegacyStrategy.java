package dev.applewatchandroid.bridge.strategy.s3;

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
 * Strategy 3 ({@code NRPairingStrategySequentialDirectIPSec}, watchOS 6–9) plugin.
 *
 * <p>Executes GATT {@code NRExtensiblePair} {@code "NANO" + 0x03 + style} write -> BLE SMP ->
 * {@code BT_CL} {@code terminusLink} ({@code 0x0002}) Direct IPSec prelude with automatic
 * fallback to {@code com.apple.ids} ({@code 0x0003}) {@code IDSBTLink}.</p>
 */
public final class DirectIpsecLegacyStrategy implements PairingStrategy {
    public static final StrategyCapabilities CAPABILITIES = new StrategyCapabilities(
            PairingStrategyId.DIRECT_IPSEC_S3,
            Set.of(AuthStyle.PIN, AuthStyle.OPTICAL),
            IdsLinkKind.DIRECT_IPSEC_OOBK,
            true,
            false,
            false,
            false,
            false,
            false);

    @Override
    public PairingStrategyId id() {
        return PairingStrategyId.DIRECT_IPSEC_S3;
    }

    @Override
    public StrategyCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public PairingOutcome pair(StrategySessionContext context) throws Exception {
        return LegacyPairingSupport.runLegacyPair(context, PairingStrategyId.DIRECT_IPSEC_S3, true, true);
    }

    @Override
    public ReconnectOutcome reconnect(StrategySessionContext context, PairingSessionRecord restored) throws Exception {
        return LegacyReconnectSupport.reconnect(context, restored);
    }
}
