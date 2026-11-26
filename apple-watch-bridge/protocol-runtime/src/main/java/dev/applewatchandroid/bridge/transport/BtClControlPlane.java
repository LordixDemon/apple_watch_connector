package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BridgeClock;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Modular {@code BT_CL} ({@code 0x003A}) control plane over {@link L2capChannelMux}.
 *
 * <p>Supports both companion-initiated and Watch-initiated {@code REMOTE_SERVICES} /
 * {@code SERVICE_ADDED} / {@code CREATE_CHANNEL} negotiation, tracks per-service ERTM and FCS
 * capabilities, and automatically answers Watch {@code TIME_SYNC_REQUEST} and
 * {@code SERVICE_REMOVED} signaling PDUs.</p>
 */
public final class BtClControlPlane {
    public record OpenedChannel(
            int serviceId,
            int localCid,
            int remoteCid,
            boolean ertm,
            boolean fcs,
            int peerVersion,
            long peerFeatures) {}

    private record ServiceNegotiation(boolean ertm, boolean fcs) {}

    private static final long RETRANSMIT_INTERVAL_MS = 800L;
    private static final int SIGNALING_ERR_RETRY_CODE = 0x05;
    private static final int MAX_PLAUSIBLE_BT_CL_VERSION = 0x40;

    private final L2capChannelMux mux;
    private final Map<Integer, ServiceNegotiation> peerNegotiatedServices = new HashMap<>();

    private int peerVersion = BridgeCoreCodecs.BT_CL_CURRENT_VERSION;
    private long peerFeatures;

    public BtClControlPlane(L2capChannelMux mux) {
        this.mux = Objects.requireNonNull(mux, "mux");
    }

    public int peerVersion() {
        return peerVersion;
    }

    public long peerFeatures() {
        return peerFeatures;
    }

    public BridgeCoreCodecs.BtClVersion exchangeVersion(long timeoutMs) throws Exception {
        byte[] versionTx = BridgeCoreCodecs.buildBtClVersionPdu();
        try {
            mux.sendL2cap(BridgeCoreCodecs.BT_CL_SIGNALING_CID, versionTx, "BT_CL VERSION");
            long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
            while (true) {
                long remaining = deadline - BridgeClock.elapsedRealtime();
                if (remaining <= 0) {
                    throw new HostException("Timed out waiting for BT_CL VERSION_INFO");
                }
                BridgeCoreCodecs.BtClFrame frame = awaitBtClFrame(remaining, "BT_CL VERSION_INFO");
                if (frame == null) {
                    throw new HostException("Timed out waiting for BT_CL VERSION_INFO");
                }
                if (isSignalingRetryError(frame)) {
                    mux.sendL2cap(BridgeCoreCodecs.BT_CL_SIGNALING_CID, versionTx, "BT_CL VERSION RETRY");
                    continue;
                }
                if (handleControlSideChannel(frame)) {
                    continue;
                }
                if (frame.opcode() == BridgeCoreCodecs.BT_CL_VERSION_INFO) {
                    BridgeCoreCodecs.BtClVersion version = BridgeCoreCodecs.parseBtClVersionInfo(frame);
                    if (version.version() <= 0 || version.version() > MAX_PLAUSIBLE_BT_CL_VERSION) {
                        throw new HostException("Implausible Watch BT_CL version " + version.version());
                    }
                    this.peerVersion = version.version();
                    this.peerFeatures = version.features();
                    mux.transport().log(String.format(
                            Locale.US,
                            "BT_CL VERSION RX: version=%d features=0x%08X",
                            peerVersion,
                            peerFeatures));
                    return version;
                }
            }
        } finally {
            Arrays.fill(versionTx, (byte) 0);
        }
    }

    public List<Integer> negotiateServices(
            List<BridgeCoreCodecs.BtClService> localServices, long timeoutMs) throws Exception {
        if (localServices == null || localServices.isEmpty()) {
            throw new IllegalArgumentException("localServices must be non-empty");
        }
        Set<Integer> localIds = new HashSet<>();
        for (BridgeCoreCodecs.BtClService s : localServices) {
            localIds.add(s.serviceId());
        }
        byte[] remoteServicesTx =
                BridgeCoreCodecs.buildBtClRemoteServicesPdu(peerVersion, peerFeatures, localServices);
        try {
            mux.sendL2cap(BridgeCoreCodecs.BT_CL_SIGNALING_CID, remoteServicesTx, "BT_CL REMOTE_SERVICES");
            long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
            long nextRetransmitAt = BridgeClock.elapsedRealtime() + RETRANSMIT_INTERVAL_MS;
            while (true) {
                long now = BridgeClock.elapsedRealtime();
                if (now >= deadline) {
                    throw new HostException("Timed out waiting for BT_CL COMMON_SERVICES");
                }
                if (now >= nextRetransmitAt) {
                    mux.sendL2cap(
                            BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                            remoteServicesTx,
                            "BT_CL REMOTE_SERVICES RETRANSMIT");
                    nextRetransmitAt = now + RETRANSMIT_INTERVAL_MS;
                }
                long waitMs = Math.max(1L, Math.min(deadline, nextRetransmitAt) - now);
                BridgeCoreCodecs.BtClFrame frame = awaitBtClFrame(waitMs, "BT_CL COMMON_SERVICES");
                if (frame == null) {
                    continue;
                }
                if (isSignalingRetryError(frame)) {
                    mux.sendL2cap(
                            BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                            remoteServicesTx,
                            "BT_CL REMOTE_SERVICES ERROR RETRY");
                    nextRetransmitAt = BridgeClock.elapsedRealtime() + RETRANSMIT_INTERVAL_MS;
                    continue;
                }
                if (handleControlSideChannel(frame)) {
                    continue;
                }
                if (frame.opcode() == BridgeCoreCodecs.BT_CL_COMMON_SERVICES) {
                    List<Integer> common = BridgeCoreCodecs.parseBtClCommonServices(frame);
                    mux.transport().log("BT_CL COMMON_SERVICES RX: ids=" + common);
                    return List.copyOf(common);
                }
                if (frame.opcode() == BridgeCoreCodecs.BT_CL_REMOTE_SERVICES) {
                    List<Integer> common = handlePeerRemoteServices(frame, localIds);
                    return common;
                }
                if (frame.opcode() == BridgeCoreCodecs.BT_CL_SERVICE_ADDED) {
                    List<Integer> common = handlePeerServiceAdded(frame, localIds);
                    if (!common.isEmpty()) {
                        return common;
                    }
                }
            }
        } finally {
            Arrays.fill(remoteServicesTx, (byte) 0);
        }
    }

    public OpenedChannel openChannel(
            int serviceId, int localCid, boolean defaultFcs, long timeoutMs) throws Exception {
        byte[] createTx = BridgeCoreCodecs.buildBtClCreateChannelPdu(
                peerVersion, peerFeatures, localCid, serviceId);
        try {
            mux.sendL2cap(
                    BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                    createTx,
                    String.format(Locale.US, "BT_CL CREATE_CHANNEL localCID=0x%04X service=0x%04X",
                            localCid, serviceId));
            long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
            while (true) {
                long remaining = deadline - BridgeClock.elapsedRealtime();
                if (remaining <= 0) {
                    throw new HostException(String.format(
                            Locale.US,
                            "Timed out waiting for BT_CL channel open on service 0x%04X",
                            serviceId));
                }
                BridgeCoreCodecs.BtClFrame frame = awaitBtClFrame(remaining, "BT_CL ACCEPT/CREATE_CHANNEL");
                if (frame == null) {
                    throw new HostException(String.format(
                            Locale.US,
                            "Timed out waiting for BT_CL channel open on service 0x%04X",
                            serviceId));
                }
                if (isSignalingRetryError(frame)) {
                    mux.sendL2cap(BridgeCoreCodecs.BT_CL_SIGNALING_CID, createTx, "BT_CL CREATE_CHANNEL RETRY");
                    continue;
                }
                if (handleControlSideChannel(frame)) {
                    continue;
                }
                if (frame.opcode() == BridgeCoreCodecs.BT_CL_REMOTE_SERVICES) {
                    handlePeerRemoteServices(frame, Set.of(serviceId));
                    continue;
                }
                if (frame.opcode() == BridgeCoreCodecs.BT_CL_SERVICE_ADDED) {
                    handlePeerServiceAdded(frame, Set.of(serviceId));
                    continue;
                }
                if (frame.opcode() == BridgeCoreCodecs.BT_CL_ACCEPT_CHANNEL) {
                    BridgeCoreCodecs.BtClAcceptChannel ac = BridgeCoreCodecs.parseBtClAcceptChannel(frame);
                    if (ac.serviceId() != serviceId) {
                        throw new HostException(String.format(
                                Locale.US,
                                "BT_CL ACCEPT_CHANNEL service mismatch: expected=0x%04X actual=0x%04X",
                                serviceId,
                                ac.serviceId()));
                    }
                    if (ac.status() != 0) {
                        throw new HostException(String.format(
                                Locale.US,
                                "Watch rejected BT_CL channel for service 0x%04X with status=%d",
                                serviceId,
                                ac.status()));
                    }
                    return buildOpenedChannel(serviceId, localCid, ac.responderLocalCid(), defaultFcs);
                }
                if (frame.opcode() == BridgeCoreCodecs.BT_CL_CREATE_CHANNEL) {
                    BridgeCoreCodecs.BtClCreateChannel req = BridgeCoreCodecs.parseBtClCreateChannel(frame);
                    if (req.serviceId() != serviceId) {
                        byte[] reject = BridgeCoreCodecs.buildBtClAcceptChannelPdu(
                                peerVersion, peerFeatures, 5, req.serviceId(), 0);
                        try {
                            mux.sendL2cap(
                                    BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                                    reject,
                                    "BT_CL ACCEPT_CHANNEL REJECT");
                        } finally {
                            Arrays.fill(reject, (byte) 0);
                        }
                        continue;
                    }
                    byte[] accept = BridgeCoreCodecs.buildBtClAcceptChannelPdu(
                            peerVersion, peerFeatures, 0, serviceId, localCid);
                    try {
                        mux.sendL2cap(
                                BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                                accept,
                                String.format(Locale.US,
                                        "BT_CL ACCEPT_CHANNEL (reply to Watch CREATE_CHANNEL) localCID=0x%04X",
                                        localCid));
                    } finally {
                        Arrays.fill(accept, (byte) 0);
                    }
                    return buildOpenedChannel(serviceId, localCid, req.requesterLocalCid(), defaultFcs);
                }
            }
        } finally {
            Arrays.fill(createTx, (byte) 0);
        }
    }

    private OpenedChannel buildOpenedChannel(
            int serviceId, int localCid, int remoteCid, boolean defaultFcs) {
        ServiceNegotiation negotiated = peerNegotiatedServices.get(serviceId);
        boolean ertm = negotiated == null || negotiated.ertm();
        boolean fcs = negotiated != null ? negotiated.fcs() : defaultFcs;
        mux.transport().log(String.format(
                Locale.US,
                "BT_CL CHANNEL OPENED: service=0x%04X localCID=0x%04X remoteCID=0x%04X ertm=%s fcs=%s",
                serviceId,
                localCid,
                remoteCid,
                ertm,
                fcs));
        return new OpenedChannel(serviceId, localCid, remoteCid, ertm, fcs, peerVersion, peerFeatures);
    }

    private List<Integer> handlePeerRemoteServices(
            BridgeCoreCodecs.BtClFrame frame, Set<Integer> localIds) throws Exception {
        List<BridgeCoreCodecs.BtClService> remoteList = BridgeCoreCodecs.parseBtClRemoteServices(frame);
        List<Integer> common = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (BridgeCoreCodecs.BtClService svc : remoteList) {
            if (localIds.contains(svc.serviceId()) && seen.add(svc.serviceId())) {
                common.add(svc.serviceId());
                peerNegotiatedServices.put(
                        svc.serviceId(), new ServiceNegotiation(svc.ertm(), svc.fcs()));
            }
        }
        sendCommonServicesReply(common);
        mux.transport().log("BT_CL WATCH REMOTE_SERVICES intersected common=" + common);
        return List.copyOf(common);
    }

    private List<Integer> handlePeerServiceAdded(
            BridgeCoreCodecs.BtClFrame frame, Set<Integer> localIds) throws Exception {
        BridgeCoreCodecs.BtClService svc = BridgeCoreCodecs.parseBtClServiceAdded(frame);
        List<Integer> common = localIds.contains(svc.serviceId())
                ? List.of(svc.serviceId())
                : List.of();
        if (!common.isEmpty()) {
            peerNegotiatedServices.put(
                    svc.serviceId(), new ServiceNegotiation(svc.ertm(), svc.fcs()));
        }
        sendCommonServicesReply(common);
        mux.transport().log("BT_CL WATCH SERVICE_ADDED id=0x"
                + Integer.toHexString(svc.serviceId()) + " fcs=" + svc.fcs());
        return common;
    }

    private void sendCommonServicesReply(List<Integer> commonIds) throws Exception {
        byte[] reply = BridgeCoreCodecs.buildBtClCommonServicesPdu(peerVersion, peerFeatures, commonIds);
        try {
            mux.sendL2cap(BridgeCoreCodecs.BT_CL_SIGNALING_CID, reply, "BT_CL COMMON_SERVICES REPLY");
        } finally {
            Arrays.fill(reply, (byte) 0);
        }
    }

    private boolean handleControlSideChannel(BridgeCoreCodecs.BtClFrame frame) throws Exception {
        if (frame.opcode() == BridgeCoreCodecs.BT_CL_TIME_SYNC_REQUEST) {
            if (frame.payload().length != 0) {
                mux.transport().log("BT_CL TIME_SYNC_REQUEST rejected: nonempty payload");
                return true;
            }
            byte[] info = BridgeCoreCodecs.buildBtClTimeSyncInfo(peerVersion, peerFeatures);
            try {
                mux.sendL2cap(BridgeCoreCodecs.BT_CL_SIGNALING_CID, info, "BT_CL TIME_SYNC_INFO");
            } finally {
                Arrays.fill(info, (byte) 0);
            }
            byte[] corr = BridgeCoreCodecs.buildBtClTimeSyncCorrection(
                    peerVersion, peerFeatures, System.nanoTime());
            try {
                mux.sendL2cap(BridgeCoreCodecs.BT_CL_SIGNALING_CID, corr, "BT_CL TIME_SYNC_CORRECTION");
            } finally {
                Arrays.fill(corr, (byte) 0);
            }
            return true;
        }
        if (frame.opcode() == BridgeCoreCodecs.BT_CL_SERVICE_REMOVED) {
            int removedId = BridgeCoreCodecs.parseBtClServiceRemoved(frame);
            peerNegotiatedServices.remove(removedId);
            byte[] confirm = BridgeCoreCodecs.buildBtClRemoveConfirmationPdu(
                    peerVersion, peerFeatures, removedId, 0);
            try {
                mux.sendL2cap(
                        BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                        confirm,
                        "BT_CL REMOVE_CONFIRMATION");
            } finally {
                Arrays.fill(confirm, (byte) 0);
            }
            return true;
        }
        return false;
    }

    private BridgeCoreCodecs.BtClFrame awaitBtClFrame(long timeoutMs, String label) throws Exception {
        byte[] payload = mux.pollBtClPdu(timeoutMs, label);
        if (payload == null) {
            return null;
        }
        try {
            return BridgeCoreCodecs.parseBtCl(peerVersion, peerFeatures, payload);
        } catch (IllegalArgumentException invalid) {
            throw new HostException("Malformed BT_CL PDU while awaiting " + label, invalid);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private static boolean isSignalingRetryError(BridgeCoreCodecs.BtClFrame frame) {
        if (frame.opcode() != BridgeCoreCodecs.BT_CL_SIGNALING_ERROR) {
            return false;
        }
        byte[] p = frame.payload();
        return (p.length == 2 && (p[1] & 0xFF) == SIGNALING_ERR_RETRY_CODE)
                || (p.length == 1 && (p[0] & 0xFF) == SIGNALING_ERR_RETRY_CODE);
    }
}
