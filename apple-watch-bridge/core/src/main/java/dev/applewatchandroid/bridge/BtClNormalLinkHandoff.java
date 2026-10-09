package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.List;

/**
 * Exact BT_CL control-plane handoff from the pairing pipe to the primary
 * encrypted {@code com.apple.terminusLink} pipe.
 *
 * <p>This object deliberately contains no retry interval or timeout. Physical
 * watchOS 26.2 bluetoothd stores the first remote service and rejects a later
 * record with the same service ID or name. The normal service is therefore
 * advertised exactly once; late Watch endpoint registration re-enumerates the
 * already stored record and produces COMMON_SERVICES without a retransmit.</p>
 */
final class BtClNormalLinkHandoff {
    enum Phase {
        READY_TO_ADVERTISE,
        WAITING_FOR_COMMON_SERVICES,
        WAITING_FOR_ACCEPT_CHANNEL,
        PIPE_OPEN
    }

    private final int peerVersion;
    private final HciCodec.BtClServiceRecord service;
    private final int requesterLocalCid;

    private Phase phase;
    private boolean advertised;
    private int responderLocalCid;

    private BtClNormalLinkHandoff(
            int peerVersion,
            boolean linkEncryptionConfirmed) {
        if (peerVersion <= 0 || peerVersion > 0xff) {
            throw new IllegalArgumentException(
                    "BT_CL peer version is outside uint8");
        }
        if (!linkEncryptionConfirmed) {
            throw new IllegalStateException(
                    "terminusLink must not be negotiated before "
                            + "Bluetooth link encryption");
        }
        this.peerVersion = peerVersion;
        service = HciCodec.terminusLinkService();
        requesterLocalCid =
                HciCodec.TERMINUS_LINK_LOCAL_CID;
        phase = Phase.READY_TO_ADVERTISE;
        responderLocalCid = -1;
    }

    static BtClNormalLinkHandoff begin(
            int peerVersion,
            boolean linkEncryptionConfirmed) {
        return new BtClNormalLinkHandoff(
                peerVersion,
                linkEncryptionConfirmed);
    }

    static BtClNormalLinkHandoff fromReconnectedChannel(
            int peerVersion,
            int requesterLocalCid,
            int responderLocalCid,
            boolean linkEncryptionConfirmed) {
        BtClNormalLinkHandoff handoff = new BtClNormalLinkHandoff(
                peerVersion,
                linkEncryptionConfirmed);
        handoff.phase = Phase.PIPE_OPEN;
        handoff.advertised = true;
        handoff.responderLocalCid = responderLocalCid;
        return handoff;
    }

    /** Emits opcode 0x05 exactly once. */
    byte[] advertise() {
        if (phase != Phase.READY_TO_ADVERTISE || advertised) {
            throw new IllegalStateException(
                    "terminusLink was already advertised");
        }
        phase = Phase.WAITING_FOR_COMMON_SERVICES;
        advertised = true;
        return HciCodec.buildServiceAddedPdu(
                peerVersion,
                service);
    }

    /**
     * Consumes the Watch's opcode 0x02 and emits opcode 0x03.
     */
    byte[] acceptCommonServices(
            HciCodec.BtClPdu commonServices) {
        if (phase
                != Phase.WAITING_FOR_COMMON_SERVICES) {
            throw new IllegalStateException(
                    "COMMON_SERVICES arrived outside advertisement state");
        }
        List<Integer> accepted =
                HciCodec.parseCommonServices(
                        commonServices);
        if (!accepted.equals(
                List.of(service.serviceId))) {
            throw new IllegalArgumentException(
                    "Watch did not accept exactly the primary "
                            + "terminusLink service");
        }
        phase = Phase.WAITING_FOR_ACCEPT_CHANNEL;
        return HciCodec.buildCreateChannelPdu(
                peerVersion,
                requesterLocalCid,
                service.serviceId);
    }

    /**
     * Commits the Watch's opcode 0x04 and exposes the negotiated CID pair.
     */
    void acceptChannel(
            HciCodec.BtClPdu acceptChannel) {
        if (phase
                != Phase.WAITING_FOR_ACCEPT_CHANNEL) {
            throw new IllegalStateException(
                    "ACCEPT_CHANNEL arrived outside create state");
        }
        HciCodec.AcceptChannel accepted =
                HciCodec.parseAcceptChannel(
                        acceptChannel);
        if (accepted.status != 0) {
            throw new IllegalArgumentException(
                    "Watch rejected terminusLink channel with status "
                            + accepted.status);
        }
        if (accepted.serviceId != service.serviceId) {
            throw new IllegalArgumentException(
                    "Watch accepted a different BT_CL service");
        }
        if (accepted.responderLocalCid < 0x0040) {
            throw new IllegalArgumentException(
                    "Watch returned a non-dynamic L2CAP CID");
        }
        responderLocalCid = accepted.responderLocalCid;
        phase = Phase.PIPE_OPEN;
    }

    Phase phase() {
        return phase;
    }

    boolean advertised() {
        return advertised;
    }

    int requesterLocalCid() {
        requireOpen();
        return requesterLocalCid;
    }

    int responderLocalCid() {
        requireOpen();
        return responderLocalCid;
    }

    boolean ertmEnabled() {
        return service.ertm();
    }

    boolean fcsEnabled() {
        return service.fcs();
    }

    NrLinkBluetoothSession beginPrelude(
            SecureRandom random) {
        requireOpen();
        return NrLinkBluetoothSession
                .freshInitiatorPreferred(random);
    }

    private void requireOpen() {
        if (phase != Phase.PIPE_OPEN) {
            throw new IllegalStateException(
                    "terminusLink pipe is not open");
        }
    }
}
