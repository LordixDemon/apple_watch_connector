package dev.applewatchandroid.bridge.strategy.legacy;

import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.strategy.StrategySessionContext;
import dev.applewatchandroid.bridge.transport.GattAttClient;

/**
 * Shared GATT {@code NRExtensiblePair} ({@code 9AA4730F-...} / {@code 5F6C6A23-...})
 * {@code "NANO" + strategy + style} control-write handshake used by Strategies 2 and 3.
 *
 * <p>Strategy 1 ({@code NRPairingStrategySimultaneous}) intentionally skips this step.</p>
 */
public final class LegacyNanoControlHandshake {
    private LegacyNanoControlHandshake() {
    }

    public static int execute(StrategySessionContext ctx, PairingStrategyId strategyId) throws Exception {
        if (strategyId != PairingStrategyId.SEQUENTIAL_S2
                && strategyId != PairingStrategyId.DIRECT_IPSEC_S3) {
            throw new IllegalArgumentException("GATT NANO control write applies only to Strategies 2 and 3");
        }
        int style = ctx.hasOpticalOobKey()
                ? BridgeCoreCodecs.LEGACY_STYLE_OOB
                : BridgeCoreCodecs.LEGACY_STYLE_PASSCODE;
        GattAttClient client = new GattAttClient(ctx.mux());
        int handle = client.writeNanoControl(strategyId.wireId(), style, ctx.gattTimeoutMs());
        ctx.ui().log("[EXPERIMENTAL LEGACY] GATT NANO control write complete: strategy="
                + strategyId.wireId() + " style=" + style + " handle=0x" + Integer.toHexString(handle));
        return handle;
    }
}
