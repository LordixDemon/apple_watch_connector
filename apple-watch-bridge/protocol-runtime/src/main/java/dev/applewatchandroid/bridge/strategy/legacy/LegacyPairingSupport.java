package dev.applewatchandroid.bridge.strategy.legacy;

import dev.applewatchandroid.bridge.BridgeClock;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;
import dev.applewatchandroid.bridge.IdsControlChannelCodec;
import dev.applewatchandroid.bridge.PairingSessionRecord;
import dev.applewatchandroid.bridge.spi.IdsLinkKind;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.strategy.PairingOutcome;
import dev.applewatchandroid.bridge.strategy.StrategySessionContext;
import dev.applewatchandroid.bridge.transport.BtClControlPlane;
import dev.applewatchandroid.bridge.transport.IdsBtLinkTransport;
import dev.applewatchandroid.bridge.transport.SmpAuthMode;
import dev.applewatchandroid.bridge.transport.SmpBondResult;
import dev.applewatchandroid.bridge.transport.SmpEngine;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared modular pairing coordinator for legacy Apple Watch Strategies 1, 2, and 3:
 * optional GATT {@code NANO} write -> unified {@link SmpEngine} (capturing {@code SC=0}
 * {@code EDIV}/{@code Rand}) -> fail-closed {@code AWB1} bond + {@code AWP2} session persistence
 * -> {@link BtClControlPlane} -> {@link IdsBtLinkTransport} or {@link LegacyOobkIkeHandshake}.
 */
public final class LegacyPairingSupport {
    private LegacyPairingSupport() {
    }

    public static PairingOutcome runLegacyPair(
            StrategySessionContext ctx,
            PairingStrategyId strategyId,
            boolean performGattNanoWrite,
            boolean offerTerminusLink) throws Exception {
        ctx.transport().checkStop();
        if (performGattNanoWrite) {
            LegacyNanoControlHandshake.execute(ctx, strategyId);
        } else {
            ctx.ui().log("[EXPERIMENTAL LEGACY] Strategy " + strategyId.wireId()
                    + " skips GATT NANO control write; proceeding directly to BLE SMP.");
        }

        byte[] opticalKey = ctx.copyOpticalOobKey16();
        SmpAuthMode authMode = opticalKey != null
                ? new SmpAuthMode.LeScOob(opticalKey)
                : new SmpAuthMode.LeScPasskey();
        if (opticalKey != null) {
            Arrays.fill(opticalKey, (byte) 0);
        }

        SmpEngine smpEngine = new SmpEngine(ctx.mux(), ctx.random());
        boolean sessionPersisted = false;
        PairingSessionRecord persistedRecord = null;
        try (authMode;
             SmpBondResult bond = smpEngine.pair(
                     authMode,
                     timeoutMs -> readSixDigitPasskey(ctx, timeoutMs),
                     (code, timeoutMs) -> ctx.ui().confirmNumericComparison(code, timeoutMs),
                     (bondRecord, sc, ediv, rand) -> ctx.persistence().persistBondSecretOrFail(bondRecord),
                     ctx.smpResponseTimeoutMs(),
                     ctx.pinInputTimeoutMs())) {

            IdsLinkKind initialLinkKind = offerTerminusLink
                    ? IdsLinkKind.DIRECT_IPSEC_OOBK
                    : IdsLinkKind.IDS_BT_LINK;
            persistedRecord = buildAndPersistLegacySessionRecord(ctx, strategyId, initialLinkKind, bond);
            sessionPersisted = persistedRecord != null;

            BtClControlPlane btcl = new BtClControlPlane(ctx.mux());
            btcl.exchangeVersion(ctx.btClTimeoutMs());

            List<BridgeCoreCodecs.BtClService> offered = offerTerminusLink
                    ? List.of(BridgeCoreCodecs.terminusLinkService(), BridgeCoreCodecs.idsBtLinkService())
                    : List.of(BridgeCoreCodecs.idsBtLinkService());
            List<Integer> common = btcl.negotiateServices(offered, ctx.btClTimeoutMs());

            IdsLinkKind negotiatedKind = initialLinkKind;
            String terminusReconciled = null;
            String terminusRole = null;
            boolean idsLinkOpened = false;
            int idsFrames = 0;

            if (offerTerminusLink && common.contains(BridgeCoreCodecs.TERMINUS_LINK_SERVICE_ID)) {
                negotiatedKind = IdsLinkKind.DIRECT_IPSEC_OOBK;
                BtClControlPlane.OpenedChannel ch = btcl.openChannel(
                        BridgeCoreCodecs.TERMINUS_LINK_SERVICE_ID,
                        BridgeCoreCodecs.TERMINUS_LINK_LOCAL_CID,
                        false,
                        ctx.btClTimeoutMs());
                idsLinkOpened = true;
                LegacyOobkIkeHandshake.PreludeOutcome prelude =
                        LegacyOobkIkeHandshake.runFreshPrelude(ctx, ch);
                terminusReconciled = prelude.reconciledStateName();
                terminusRole = prelude.localRole();
            } else if (common.contains(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID)) {
                negotiatedKind = IdsLinkKind.IDS_BT_LINK;
                if (persistedRecord != null && negotiatedKind != initialLinkKind) {
                    PairingSessionRecord updated = buildAndPersistLegacySessionRecord(
                            ctx, strategyId, negotiatedKind, bond);
                    if (updated != null) {
                        persistedRecord.destroy();
                        persistedRecord = updated;
                    }
                }
                BtClControlPlane.OpenedChannel ch = btcl.openChannel(
                        BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID,
                        BridgeCoreCodecs.IDS_BT_LINK_LOCAL_CID,
                        false,
                        ctx.btClTimeoutMs());
                idsLinkOpened = true;
                idsFrames = observeIdsBtLinkWindow(ctx, ch);
            } else {
                ctx.ui().log("[EXPERIMENTAL LEGACY] Watch accepted neither terminusLink nor com.apple.ids; common=" + common);
            }

            String summary = String.format(
                    Locale.US,
                    "strategy=S%d link=%s sc=%s bondPersisted=%s sessionPersisted=%s idsOpen=%s idsFrames=%d",
                    strategyId.wireId(), negotiatedKind, bond.secureConnections(),
                    bond.bondPersisted(), sessionPersisted, idsLinkOpened, idsFrames);
            ctx.ui().log("[EXPERIMENTAL LEGACY] STRATEGY COMPLETE: " + summary + "; secrets logged=false.");
            return new PairingOutcome(
                    strategyId,
                    negotiatedKind,
                    bond.secureConnections(),
                    bond.bondPersisted(),
                    sessionPersisted,
                    idsLinkOpened,
                    idsFrames,
                    terminusReconciled,
                    terminusRole,
                    common,
                    summary);
        } finally {
            if (persistedRecord != null) {
                persistedRecord.destroy();
            }
        }
    }

    static int observeIdsBtLinkWindow(
            StrategySessionContext ctx,
            BtClControlPlane.OpenedChannel channel) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + ctx.postSmpWindowMs();
        int frames = 0;
        try (IdsBtLinkTransport link = new IdsBtLinkTransport(ctx.mux(), channel)) {
            while (link.isOpen()) {
                long remaining = deadline - BridgeClock.elapsedRealtime();
                if (remaining <= 0) {
                    break;
                }
                byte[] frame;
                try {
                    frame = link.receiveControlFrame(remaining);
                } catch (HostException timeoutOrDisconnect) {
                    break;
                }
                try {
                    frames++;
                    IdsControlChannelCodec.HelloMessage hello = IdsBtLinkTransport.tryDecodeLegacyHello(frame);
                    if (hello != null) {
                        ctx.ui().log(String.format(
                                Locale.US,
                                "[EXPERIMENTAL LEGACY] IDSBTLink HELLO RX: product=%s version=%s build=%s model=%s "
                                        + "compat=%d..%d; payload bytes logged=false.",
                                hello.productName, hello.productVersion, hello.productBuildVersion,
                                hello.model, hello.minCompatibilityVersion, hello.maxCompatibilityVersion));
                    }
                    // Observe at least one verified frame cleanly and keep listening briefly if more are queued.
                    if (frames >= 1) {
                        break;
                    }
                } finally {
                    Arrays.fill(frame, (byte) 0);
                }
            }
            ctx.ui().log(String.format(
                    Locale.US,
                    "[EXPERIMENTAL LEGACY] IDSBTLink RX SUMMARY: frames=%d fcs=%s checksumFlagged=%s; payload bytes logged=false.",
                    frames, link.fcsEnabled(), link.checksumFlagSeen()));
        }
        return frames;
    }

    private static PairingSessionRecord buildAndPersistLegacySessionRecord(
            StrategySessionContext ctx,
            PairingStrategyId strategyId,
            IdsLinkKind linkKind,
            SmpBondResult bond) throws Exception {
        byte[] bondBytes = bond.bondSecretRecord();
        if (bondBytes == null) {
            return null;
        }
        byte[] rand = bond.rand();
        UUID gen = UUID.randomUUID();
        byte[] generationUuid = ByteBuffer.allocate(16)
                .putLong(gen.getMostSignificantBits())
                .putLong(gen.getLeastSignificantBits())
                .array();
        PairingSessionRecord record = null;
        try {
            record = PairingSessionRecord.createLegacyBonded(
                    generationUuid,
                    strategyId,
                    linkKind,
                    bondBytes,
                    bond.secureConnections(),
                    bond.ediv(),
                    rand,
                    "Apple Watch (Legacy S" + strategyId.wireId() + ")",
                    ctx.watchOsVersion() != null && !ctx.watchOsVersion().isBlank()
                            ? ctx.watchOsVersion() : strategyId.watchOsRange(),
                    ctx.productType(),
                    ctx.bluetoothCbUuid(),
                    ctx.nrUuid(),
                    ctx.localIdsDeviceUuid(),
                    null);
            ctx.persistence().persistPairingSessionOrFail(record);
            PairingSessionRecord retained = record;
            record = null;
            return retained;
        } finally {
            Arrays.fill(bondBytes, (byte) 0);
            Arrays.fill(rand, (byte) 0);
            Arrays.fill(generationUuid, (byte) 0);
            if (record != null) {
                record.destroy();
            }
        }
    }

    private static int readSixDigitPasskey(StrategySessionContext ctx, long timeoutMs) throws Exception {
        char[] digits = ctx.ui().awaitSixDigitPin(timeoutMs);
        if (digits == null || digits.length != 6) {
            if (digits != null) {
                Arrays.fill(digits, '\0');
            }
            throw new HostException("Six-digit PIN input was not supplied");
        }
        try {
            int value = 0;
            for (char c : digits) {
                if (c < '0' || c > '9') {
                    throw new HostException("PIN input must contain six ASCII digits");
                }
                value = value * 10 + (c - '0');
            }
            return value;
        } finally {
            Arrays.fill(digits, '\0');
        }
    }
}
