package dev.applewatchandroid.bridge.strategy.legacy;

import dev.applewatchandroid.bridge.BluetoothBondSecretRecord;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;
import dev.applewatchandroid.bridge.PairingSessionRecord;
import dev.applewatchandroid.bridge.spi.IdsLinkKind;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.strategy.ReconnectOutcome;
import dev.applewatchandroid.bridge.strategy.StrategySessionContext;
import dev.applewatchandroid.bridge.transport.BtClControlPlane;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Shared reconnect support for persisted legacy Strategies 1, 2, and 3.
 *
 * <p>Crucially passes persisted {@code SC=0} {@code EDIV} and 8-byte {@code Rand} (or
 * {@code 0/0} for {@code SC=1}) into {@code HCI_LE_Start_Encryption}, then reopens
 * {@code com.apple.ids} ({@code 0x0003}) or {@code terminusLink} ({@code 0x0002}).</p>
 */
public final class LegacyReconnectSupport {
    private LegacyReconnectSupport() {
    }

    public static ReconnectOutcome reconnect(
            StrategySessionContext ctx,
            PairingSessionRecord restored) throws Exception {
        ctx.transport().checkStop();
        PairingStrategyId strategyId = restored.pairingStrategy();
        byte[] bond = restored.bluetoothBond();
        if (bond == null) {
            throw new HostException("Legacy reconnect requires a persisted Bluetooth bond");
        }
        byte[] ltk = null;
        byte[] rand = null;
        try {
            ltk = BluetoothBondSecretRecord.extractLongTermKey(bond);
            boolean sc = restored.smpSecureConnections();
            int ediv = sc ? 0 : restored.smpEdiv();
            rand = sc ? new byte[8] : restored.smpRand();
            ctx.ui().log(String.format(
                    Locale.US,
                    "[EXPERIMENTAL LEGACY] RECONNECT ENCRYPT: strategy=S%d sc=%s hasEdiv=%s; key/rand bytes logged=false.",
                    strategyId.wireId(), sc, ediv != 0));
            ctx.transport().startLeEncryption(rand, ediv, ltk);
        } finally {
            Arrays.fill(bond, (byte) 0);
            if (ltk != null) Arrays.fill(ltk, (byte) 0);
            if (rand != null) Arrays.fill(rand, (byte) 0);
        }

        BtClControlPlane btcl = new BtClControlPlane(ctx.mux());
        btcl.exchangeVersion(ctx.btClTimeoutMs());

        boolean preferTerminus = restored.idsLinkKind() == IdsLinkKind.DIRECT_IPSEC_OOBK
                || strategyId == PairingStrategyId.DIRECT_IPSEC_S3;
        List<BridgeCoreCodecs.BtClService> offered = preferTerminus
                ? List.of(BridgeCoreCodecs.terminusLinkService(), BridgeCoreCodecs.idsBtLinkService())
                : List.of(BridgeCoreCodecs.idsBtLinkService());
        List<Integer> common = btcl.negotiateServices(offered, ctx.btClTimeoutMs());

        if (preferTerminus && common.contains(BridgeCoreCodecs.TERMINUS_LINK_SERVICE_ID)) {
            BtClControlPlane.OpenedChannel ch = btcl.openChannel(
                    BridgeCoreCodecs.TERMINUS_LINK_SERVICE_ID,
                    BridgeCoreCodecs.TERMINUS_LINK_LOCAL_CID,
                    false,
                    ctx.btClTimeoutMs());
            LegacyOobkIkeHandshake.PreludeOutcome prelude =
                    LegacyOobkIkeHandshake.runReconnectPrelude(ctx, ch);
            return ReconnectOutcome.success(
                    strategyId,
                    IdsLinkKind.DIRECT_IPSEC_OOBK,
                    "terminusLink reopened state=" + prelude.reconciledStateName()
                            + " role=" + prelude.localRole());
        }
        if (common.contains(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID)) {
            BtClControlPlane.OpenedChannel ch = btcl.openChannel(
                    BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID,
                    BridgeCoreCodecs.IDS_BT_LINK_LOCAL_CID,
                    false,
                    ctx.btClTimeoutMs());
            int frames = LegacyPairingSupport.observeIdsBtLinkWindow(ctx, ch);
            return ReconnectOutcome.success(
                    strategyId,
                    IdsLinkKind.IDS_BT_LINK,
                    "com.apple.ids reopened frames=" + frames + " fcs=" + ch.fcs());
        }
        if (common.contains(BridgeCoreCodecs.TERMINUS_PAIRING_SERVICE_ID)) {
            return ReconnectOutcome.peerSetupOnly(strategyId, "Watch advertised setup-only on reconnect");
        }
        return ReconnectOutcome.failed(
                strategyId,
                restored.idsLinkKind(),
                "Watch accepted neither com.apple.ids nor terminusLink; common=" + common);
    }
}
