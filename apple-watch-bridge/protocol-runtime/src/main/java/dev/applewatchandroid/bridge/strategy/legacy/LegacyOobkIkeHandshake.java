package dev.applewatchandroid.bridge.strategy.legacy;

import dev.applewatchandroid.bridge.BridgeClock;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;
import dev.applewatchandroid.bridge.strategy.StrategySessionContext;
import dev.applewatchandroid.bridge.transport.BtClControlPlane;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Shared Strategy 3 ({@code NRPairingStrategySequentialDirectIPSec}) {@code terminusLink}
 * ({@code 0x0002}) ERTM prelude handshake and OOBK/IDS-key IKEv2 extension point.
 */
public final class LegacyOobkIkeHandshake {
    public record PreludeOutcome(int reconciledStateWireValue, String reconciledStateName, String localRole) {}

    private LegacyOobkIkeHandshake() {
    }

    public static PreludeOutcome runFreshPrelude(
            StrategySessionContext ctx,
            BtClControlPlane.OpenedChannel channel) throws Exception {
        int localState = ctx.hasOpticalOobKey()
                ? BridgeCoreCodecs.PRELUDE_STATE_PAIR_WITH_OOB_KEY
                : BridgeCoreCodecs.PRELUDE_STATE_PAIR_WITH_OOB_KEY_OR_IDS_KEYS;
        return runPrelude(ctx, channel, localState);
    }

    public static PreludeOutcome runReconnectPrelude(
            StrategySessionContext ctx,
            BtClControlPlane.OpenedChannel channel) throws Exception {
        return runPrelude(ctx, channel, BridgeCoreCodecs.PRELUDE_STATE_HAS_COMPLETED_PAIRING);
    }

    private static PreludeOutcome runPrelude(
            StrategySessionContext ctx,
            BtClControlPlane.OpenedChannel channel,
            int localState) throws Exception {
        byte[] localUuid = new byte[16];
        ctx.random().nextBytes(localUuid);
        byte[] remoteUuid = null;
        byte[] prelude = null;
        byte[] preludeFrame = null;
        try (BridgeCoreCodecs.ErtmLink ertm = new BridgeCoreCodecs.ErtmLink(
                channel.localCid(), channel.remoteCid(), channel.fcs())) {
            prelude = BridgeCoreCodecs.encodePrelude(localState, localUuid, BridgeCoreCodecs.PRELUDE_FLAG_COMPANION_APL);
            preludeFrame = ertm.sendInformation(prelude);
            ctx.mux().sendL2cap(channel.remoteCid(), preludeFrame,
                    "[EXPERIMENTAL LEGACY] TERMINUS PRELUDE state=" + localState);

            long deadline = BridgeClock.elapsedRealtime() + ctx.gattTimeoutMs();
            byte[] remotePreludeBytes = null;
            while (remotePreludeBytes == null) {
                long remaining = deadline - BridgeClock.elapsedRealtime();
                if (remaining <= 0) {
                    throw new HostException("[EXPERIMENTAL LEGACY] Timed out waiting for terminusLink prelude");
                }
                byte[] l2capPayload = ctx.mux().pollDynamicCidPdu(channel.localCid(), remaining, "terminusLink prelude");
                if (l2capPayload == null) {
                    throw new HostException("[EXPERIMENTAL LEGACY] Timed out waiting for terminusLink prelude");
                }
                try (BridgeCoreCodecs.ErtmInbound inbound = ertm.accept(l2capPayload)) {
                    List<byte[]> immediate = inbound.immediateOutboundFrames();
                    try {
                        for (byte[] ctrl : immediate) {
                            ctx.mux().sendL2cap(channel.remoteCid(), ctrl, "[EXPERIMENTAL LEGACY] TERMINUS PRELUDE CONTROL");
                        }
                    } finally {
                        for (byte[] ctrl : immediate) {
                            Arrays.fill(ctrl, (byte) 0);
                        }
                    }
                    if (inbound.acknowledgementRecommended()) {
                        byte[] ack = ertm.buildAcknowledgementFrame();
                        try {
                            ctx.mux().sendL2cap(channel.remoteCid(), ack, "[EXPERIMENTAL LEGACY] TERMINUS PRELUDE RR ACK");
                        } finally {
                            Arrays.fill(ack, (byte) 0);
                        }
                    }
                    List<byte[]> sdus = inbound.deliveredSdus();
                    try {
                        if (!sdus.isEmpty()) {
                            remotePreludeBytes = sdus.get(0).clone();
                        }
                    } finally {
                        for (byte[] sdu : sdus) {
                            Arrays.fill(sdu, (byte) 0);
                        }
                    }
                } finally {
                    Arrays.fill(l2capPayload, (byte) 0);
                }
            }

            try {
                BridgeCoreCodecs.PreludeParsed remote = BridgeCoreCodecs.parsePrelude(remotePreludeBytes);
                int reconciledWire = BridgeCoreCodecs.reconcilePreludeStates(localState, remote.stateWireValue());
                remoteUuid = remote.uuid();
                String role = BridgeCoreCodecs.electPreludeRole(localUuid, remoteUuid);
                String reconciledName = preludeStateName(reconciledWire);
                ctx.ui().log(String.format(
                        Locale.US,
                        "[EXPERIMENTAL LEGACY] TERMINUS PRELUDE PASS: local=%d remote=%s reconciled=%s role=%s fcs=%s; "
                                + "UUID bytes logged=false.",
                        localState, remote.stateName(), reconciledName, role, channel.fcs()));
                return new PreludeOutcome(reconciledWire, reconciledName, role);
            } finally {
                Arrays.fill(remotePreludeBytes, (byte) 0);
            }
        } finally {
            Arrays.fill(localUuid, (byte) 0);
            if (remoteUuid != null) Arrays.fill(remoteUuid, (byte) 0);
            if (prelude != null) Arrays.fill(prelude, (byte) 0);
            if (preludeFrame != null) Arrays.fill(preludeFrame, (byte) 0);
        }
    }

    private static String preludeStateName(int wireValue) {
        return switch (wireValue) {
            case 10 -> "PAIR_WITH_OOB_KEY";
            case 11 -> "PAIR_WITH_IDS_KEYS";
            case 12 -> "PAIR_WITH_OOB_KEY_OR_IDS_KEYS";
            case 13 -> "MODERN_PAIRING_KEY_CONFIRMATION";
            case 20 -> "HAS_COMPLETED_PAIRING";
            default -> "STATE_" + wireValue;
        };
    }
}
