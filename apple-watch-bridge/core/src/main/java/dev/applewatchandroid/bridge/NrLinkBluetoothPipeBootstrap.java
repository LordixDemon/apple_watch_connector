package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * ERTM framing for the first bidirectional payload on a normal
 * {@code com.apple.terminusLink} channel.
 *
 * <p>The 38-byte TERMINUS prelude is the ERTM SDU itself. It is not wrapped
 * in the NetworkRelay/uIKE type-0x04 envelope; that envelope starts only
 * after this bootstrap has negotiated state and ordinary-IKE role.</p>
 */
final class NrLinkBluetoothPipeBootstrap implements AutoCloseable {
    private final BtClNormalLinkHandoff handoff;
    private final NrLinkBluetoothSession session;
    private final byte[] localPrelude;

    private L2capErtmSession transport;
    private byte[] remotePrelude;
    private NrLinkBluetoothSession.Negotiated negotiated;
    private boolean outboundBuilt;
    private boolean inboundAccepted;
    private boolean remotePreludeAcknowledgementBuilt;
    private boolean detached;
    private boolean closed;
    private int remoteRequestSequence;

    private NrLinkBluetoothPipeBootstrap(
            BtClNormalLinkHandoff handoff,
            NrLinkBluetoothSession session) {
        if (handoff == null || session == null) {
            throw new IllegalArgumentException(
                    "Normal-link handoff and session are required");
        }
        if (handoff.phase()
                != BtClNormalLinkHandoff.Phase.PIPE_OPEN) {
            throw new IllegalStateException(
                    "BT_CL normal pipe is not open");
        }
        this.handoff = handoff;
        this.session = session;
        localPrelude = session.outboundPrelude();
        transport =
                new L2capErtmSession(
                        handoff.requesterLocalCid(),
                        handoff.responderLocalCid(),
                        handoff.fcsEnabled());
        remoteRequestSequence = -1;
    }

    static NrLinkBluetoothPipeBootstrap begin(
            BtClNormalLinkHandoff handoff,
            SecureRandom random) {
        return new NrLinkBluetoothPipeBootstrap(
                handoff,
                handoff.beginPrelude(random));
    }

    static NrLinkBluetoothPipeBootstrap beginPaired(
            BtClNormalLinkHandoff handoff,
            SecureRandom random) {
        return beginPaired(
                handoff,
                NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                random);
    }

    static NrLinkBluetoothPipeBootstrap beginPaired(
            BtClNormalLinkHandoff handoff,
            NrLinkBluetoothPrelude.LocalRole preferredRole,
            SecureRandom random) {
        return beginPaired(
                handoff,
                preferredRole,
                NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION,
                random);
    }

    static NrLinkBluetoothPipeBootstrap beginPaired(
            BtClNormalLinkHandoff handoff,
            NrLinkBluetoothPrelude.LocalRole preferredRole,
            NrLinkBluetoothPrelude.PairingState pairingState,
            SecureRandom random) {
        return new NrLinkBluetoothPipeBootstrap(
                handoff,
                NrLinkBluetoothSession.pairedWithPreferredRole(
                        random,
                        preferredRole,
                        pairingState));
    }

    static NrLinkBluetoothPipeBootstrap withSessionForTest(
            BtClNormalLinkHandoff handoff,
            NrLinkBluetoothSession session) {
        return new NrLinkBluetoothPipeBootstrap(
                handoff,
                session);
    }

    byte[] buildOutboundPreludeFrame() {
        if (outboundBuilt || inboundAccepted) {
            throw new IllegalStateException(
                    "Normal-link prelude was already emitted or accepted");
        }
        requireAttached();
        outboundBuilt = true;
        return transport.sendInformation(
                localPrelude);
    }

    NrLinkBluetoothSession.Negotiated
            acceptInboundPreludeFrame(
            byte[] ertmPayload) {
        if (!outboundBuilt || inboundAccepted) {
            throw new IllegalStateException(
                    "Inbound normal-link prelude is out of order");
        }
        requireAttached();
        L2capErtmCodec.Frame decoded = null;
        List<byte[]> delivered = null;
        List<byte[]> immediate = null;
        try {
            decoded =
                    L2capErtmCodec.decode(
                            handoff.requesterLocalCid(),
                            ertmPayload,
                            handoff.fcsEnabled());
            if (decoded.supervisory
                    || decoded.txSequence != 0
                    || decoded.sar
                    != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new IllegalArgumentException(
                        "First normal-link frame is not an "
                                + "unsegmented TxSeq=0 I-frame");
            }
            try (L2capErtmSession.InboundResult result =
                    transport.accept(
                            ertmPayload)) {
                delivered = result.deliveredSdus();
                immediate =
                        result.immediateOutboundFrames();
                if (delivered.size() != 1
                        || !immediate.isEmpty()) {
                    throw new IllegalArgumentException(
                            "First normal-link I-frame did not deliver "
                                    + "exactly one prelude");
                }
                negotiated =
                        session.acceptRemotePrelude(
                                delivered.get(0));
                remotePrelude =
                        delivered.get(0).clone();
            }
            remoteRequestSequence =
                    decoded.requestSequence;
            inboundAccepted = true;
            return negotiated;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        } finally {
            wipeAll(delivered);
            wipeAll(immediate);
            if (decoded != null) {
                Arrays.fill(
                        decoded.information,
                        (byte) 0);
            }
        }
    }

    /**
     * Acknowledges the peer's TxSeq-zero prelude. This RR can be sent
     * immediately; the first ordinary-IKE I-frame would carry the same
     * ReqSeq value, but requiring an explicit acknowledgement also covers
     * the responder role where no immediate information frame exists.
     */
    byte[] buildRemotePreludeAcknowledgementFrame() {
        requireNegotiated();
        if (remotePreludeAcknowledgementBuilt) {
            throw new IllegalStateException(
                    "Remote prelude acknowledgement was already built");
        }
        remotePreludeAcknowledgementBuilt = true;
        return transport.buildReceiverReady();
    }

    /**
     * Consumes only ERTM supervisory traffic while waiting for the peer to
     * acknowledge our retained TxSeq-zero prelude. The caller owns and must
     * close the returned result after sending any immediate poll response.
     */
    L2capErtmSession.InboundResult acceptPeerControlFrame(
            byte[] ertmPayload) {
        requireAttached();
        if (!outboundBuilt) {
            throw new IllegalStateException(
                    "Local normal-link prelude was not sent");
        }
        L2capErtmCodec.Frame decoded =
                L2capErtmCodec.decode(
                        handoff.requesterLocalCid(),
                        ertmPayload,
                        handoff.fcsEnabled());
        try {
            if (!decoded.supervisory) {
                throw new IllegalArgumentException(
                        "Only ERTM supervisory traffic is valid "
                                + "during prelude acknowledgement");
            }
        } finally {
            Arrays.fill(
                    decoded.information,
                    (byte) 0);
        }
        return transport.accept(
                ertmPayload);
    }

    int nextOutboundTxSequence() {
        requireNegotiated();
        return transport.nextTxSequence();
    }

    int nextExpectedRemoteTxSequence() {
        requireNegotiated();
        return transport.expectedRemoteTxSequence();
    }

    int remoteRequestSequence() {
        requireNegotiated();
        return remoteRequestSequence;
    }

    boolean outboundPreludeAcknowledged() {
        requireNegotiated();
        return transport.outstandingCount() == 0;
    }

    /**
     * Transfers the live ERTM scheduler after the remote TxSeq-zero prelude
     * has been acknowledged. The local TxSeq-zero frame may remain retained:
     * the peer may acknowledge it by piggybacking ReqSeq one on its first
     * ordinary I-frame rather than sending a standalone RR.
     */
    PreludeHandoff detach() {
        requireNegotiated();
        if (!remotePreludeAcknowledgementBuilt) {
            throw new IllegalStateException(
                    "Remote prelude has not been acknowledged");
        }
        if (transport.nextTxSequence() != 1
                || transport.expectedRemoteTxSequence() != 1
                || transport.outstandingCount() > 1
                || (transport.outstandingCount() == 1
                && transport.oldestOutstandingSequence() != 0)) {
            throw new IllegalStateException(
                    "Prelude ERTM sequence state is inconsistent");
        }
        PreludeHandoff output =
                new PreludeHandoff(
                        transport,
                        negotiated.localRole,
                        localPrelude,
                        remotePrelude);
        transport = null;
        detached = true;
        return output;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (transport != null) {
            transport.close();
            transport = null;
        }
        session.close();
        Arrays.fill(
                localPrelude,
                (byte) 0);
        if (remotePrelude != null) {
            Arrays.fill(
                    remotePrelude,
                    (byte) 0);
            remotePrelude = null;
        }
        negotiated = null;
    }

    private void requireNegotiated() {
        requireAttached();
        if (!inboundAccepted) {
            throw new IllegalStateException(
                    "Normal-link prelude is not negotiated");
        }
    }

    private void requireAttached() {
        if (closed) {
            throw new IllegalStateException(
                    "Normal-link bootstrap is closed");
        }
        if (detached || transport == null) {
            throw new IllegalStateException(
                    "Normal-link ERTM state was detached");
        }
    }

    private static void wipeAll(
            List<byte[]> values) {
        if (values == null) {
            return;
        }
        for (byte[] value : values) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
        values.clear();
    }

    static final class PreludeHandoff implements AutoCloseable {
        private final NrLinkBluetoothPrelude.LocalRole localRole;
        private final byte[] localPrelude;
        private final byte[] remotePrelude;

        private L2capErtmSession transport;
        private boolean consumed;
        private boolean closed;

        private PreludeHandoff(
                L2capErtmSession transport,
                NrLinkBluetoothPrelude.LocalRole localRole,
                byte[] localPrelude,
                byte[] remotePrelude) {
            this.transport = transport;
            this.localRole = localRole;
            this.localPrelude = localPrelude.clone();
            this.remotePrelude = remotePrelude.clone();
        }

        NrLinkBluetoothPrelude.LocalRole localRole() {
            requireOpen();
            return localRole;
        }

        byte[] localPrelude() {
            requireOpen();
            return localPrelude.clone();
        }

        byte[] remotePrelude() {
            requireOpen();
            return remotePrelude.clone();
        }

        L2capErtmSession takeTransport() {
            requireOpen();
            consumed = true;
            L2capErtmSession output =
                    transport;
            transport = null;
            return output;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (transport != null) {
                transport.close();
                transport = null;
            }
            Arrays.fill(
                    localPrelude,
                    (byte) 0);
            Arrays.fill(
                    remotePrelude,
                    (byte) 0);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Prelude handoff is closed");
            }
            if (consumed) {
                throw new IllegalStateException(
                        "Prelude handoff was already consumed");
            }
        }
    }
}
