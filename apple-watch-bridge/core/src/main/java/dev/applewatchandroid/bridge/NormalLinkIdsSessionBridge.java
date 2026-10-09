package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Binds the authenticated Apple normal link to the modern IDS session.
 *
 * <p>The normal-link layer owns IKE, ESP, NetworkRelay, and ERTM. The IDS
 * layer owns clear IPv6/TCP, NWSC, IDS control/data streams, and
 * NanoRegistry payloads. This bridge is the only place where packets cross
 * that boundary:</p>
 *
 * <pre>
 * ERTM -> NetworkRelay -> ESP -> clear IPv6 -> IDS
 * IDS clear IPv6 -> ESP -> NetworkRelay -> ERTM
 * </pre>
 *
 * <p>Unrelated normal-link IP and control traffic is returned untouched.
 * Ownership of both supplied sessions transfers to this bridge.</p>
 */
final class NormalLinkIdsSessionBridge
        implements AutoCloseable {
    private final NormalLinkPipeSession normalLink;
    private final IdsModernSessionCoordinator ids;
    private final List<DeferredIpv6> deferredIpv6 =
            new ArrayList<>();

    private boolean poisoned;
    private boolean closed;
    private List<NativeApplicationServiceDiscovery.Endpoint> nativeSnapshotEndpoints = List.of();
    private NativeQuicSession nativeQuic;

    NormalLinkIdsSessionBridge(
            NormalLinkPipeSession normalLink,
            IdsModernSessionCoordinator ids) {
        if (normalLink == null
                || ids == null) {
            throw new IllegalArgumentException(
                    "Normal-link and IDS sessions are required");
        }
        this.normalLink = normalLink;
        this.ids = ids;
    }

    IdsModernSessionCoordinator ids() {
        return ids;
    }

    Output startControl() {
        requireUsable();
        return route(
                ids.startControl());
    }

    byte[] buildReceiverReady() {
        requireUsable();
        return normalLink.buildReceiverReady();
    }

    List<byte[]> requestIkeDeletes() {
        requireUsable();
        return normalLink.requestIkeDeletes();
    }

    byte[] buildReceiverReadyPoll() {
        requireUsable();
        return normalLink.buildReceiverReadyPoll();
    }

    Output startInitialLane(
            String topic) {
        requireUsable();
        return route(
                ids.startInitialLane(
                        topic));
    }

    Output startOutgoingService(
            IdsServiceConnectorName name) {
        requireUsable();
        return route(
                ids.startOutgoingService(
                        name));
    }

    Output startPairedClassDKeyProbe() {
        requireUsable();
        return route(
                ids.startPairedClassDKeyProbe());
    }

    Output sendClassD(
            NanoRegistryClassDCodec.ApplicationMessage message) {
        requireUsable();
        return route(
                ids.sendClassD(
                        message));
    }

    Output sendApplicationData(String topic, byte[] payload) {
        requireUsable();
        return route(ids.sendApplicationData(topic, payload));
    }

    Output sendHealthSyncRequest(byte[] encrypted,java.util.UUID message) {
        requireUsable();return route(ids.sendHealthSyncRequest(encrypted,message));
    }

    Output sendClockFaceCollectionRequest(byte[] payload) {
        requireUsable();
        return route(ids.sendClockFaceCollectionRequest(payload));
    }

    Output sendClockFaceDeltaRequest(byte[] payload) {
        requireUsable();
        return route(ids.sendClockFaceDeltaRequest(payload));
    }

    Output sendApplicationDataResponse(
            String topic,
            byte[] payload,
            String outgoingResponseIdentifier) {
        requireUsable();
        return route(ids.sendApplicationDataResponse(
                topic, payload, outgoingResponseIdentifier));
    }

    Output sendApplicationDictionary(String topic, byte[] payload) {
        requireUsable();
        return route(ids.sendApplicationDictionary(topic, payload));
    }

    Output sendClassDRequestWithClientAcknowledgement(NanoRegistryClassDCodec.ApplicationMessage message) {
        requireUsable();
        return route(ids.sendClassDRequestWithClientAcknowledgement(message));
    }

    Output sendClassDResponse(
            NanoRegistryClassDCodec.ApplicationMessage message,
            String outgoingResponseIdentifier) {
        requireUsable();
        return route(
                ids.sendClassDResponse(
                        message,
                        outgoingResponseIdentifier));
    }

    Output sendClassD(
            IdsModernSessionCoordinator.MessageMetadata metadata,
            NanoRegistryClassDCodec.ApplicationMessage message) {
        requireUsable();
        return route(
                ids.sendClassD(
                        metadata,
                        message));
    }

    Output sendClassC(
            NanoRegistryPropertyCodec.ApplicationMessage message) {
        requireUsable();
        return route(
                ids.sendClassC(
                        message));
    }

    Output sendClassCResponse(
            NanoRegistryPropertyCodec.ApplicationMessage message,
            String outgoingResponseIdentifier) {
        requireUsable();
        return route(
                ids.sendClassCResponse(
                        message,
                        outgoingResponseIdentifier));
    }

    Output sendClassC(
            IdsModernSessionCoordinator.MessageMetadata metadata,
            NanoRegistryPropertyCodec.ApplicationMessage message) {
        requireUsable();
        return route(
                ids.sendClassC(
                        metadata,
                        message));
    }

    Output sendPbBridge(
            PbBridgeCodec.ApplicationMessage message) {
        requireUsable();
        return route(
                ids.sendPbBridge(
                        message));
    }

    Output sendPbBridgeReplyToFetch(
            PbBridgeCodec.ApplicationMessage message,
            String watchMessageUuid) {
        requireUsable();
        return route(
                ids.sendPbBridgeReplyToFetch(
                        message,
                        watchMessageUuid));
    }

    Output sendPbBridgeResponse(
            PbBridgeCodec.ApplicationMessage message,
            String outgoingResponseIdentifier) {
        requireUsable();
        return route(
                ids.sendPbBridgeResponse(
                        message,
                        outgoingResponseIdentifier));
    }

    Output sendPbBridge(
            IdsModernSessionCoordinator.MessageMetadata metadata,
            PbBridgeCodec.ApplicationMessage message) {
        requireUsable();
        return route(
                ids.sendPbBridge(
                        metadata,
                        message));
    }

    Output sendPairedSync(
            PairedSyncCodec.UserDefaultsMessage message) {
        requireUsable();
        return route(
                ids.sendPairedSync(
                        message));
    }

    String pendingApplicationFramesSummary() {
        if (!deferredIpv6.isEmpty()) {
            return ids.pendingApplicationFramesSummary()
                    + " deferredIpv6=" + deferredIpv6.size();
        }
        return ids.pendingApplicationFramesSummary();
    }

    boolean hasPendingApplicationFrames() {
        return !poisoned
                && !closed
                && (ids.hasPendingApplicationFrames() || !deferredIpv6.isEmpty());
    }

    Output flushPendingApplicationFrames() {
        requireUsable();
        return route(
                ids.flushPendingApplicationFrames());
    }

    Output sendPairedSync(
            IdsModernSessionCoordinator.MessageMetadata metadata,
            PairedSyncCodec.UserDefaultsMessage message) {
        requireUsable();
        return route(
                ids.sendPairedSync(
                        metadata,
                        message));
    }

    void setPairedSyncCapability(
            PairedSyncCapabilityState.Status status) {
        requireUsable();
        ids.setPairedSyncCapability(status);
    }

    Output sendAppAck(
            String topic,
            String outgoingResponseIdentifier) {
        requireUsable();
        return route(
                ids.sendAppAck(
                        topic,
                        outgoingResponseIdentifier));
    }

    Output sendApplicationProtobuf(
            String topic,
            int protobufType,
            byte[] payload) {
        requireUsable();
        return route(
                ids.sendApplicationProtobuf(
                        topic,
                        protobufType,
                        payload));
    }

    Output sendWatchReboot() {
        requireUsable();
        return route(ids.sendWatchReboot());
    }

    Output pollRetransmissions() {
        requireUsable();
        return route(
                ids.pollRetransmissions());
    }

    Output sendFindMyLocalResponse(int type, byte[] payload, String responseId) {
        requireUsable();
        return route(ids.sendFindMyLocalResponse(type, payload, responseId));
    }

    void setStaleFlowAddresses(
            byte[] localClassD,
            byte[] remoteClassD) {
        requireUsable();
        ids.setStaleFlowAddresses(localClassD, remoteClassD);
    }

    void seedStaleFlows(
            List<IdsStaleFlowRecord> seeds) {
        requireUsable();
        ids.seedStaleFlows(seeds);
    }

    List<IdsStaleFlowRecord> staleFlowObservations() {
        requireUsable();
        return ids.staleFlowObservations();
    }

    Output resetStaleFlows() {
        requireUsable();
        return route(
                ids.resetStaleFlows());
    }

    List<byte[]> pollLinkDirectorAnnouncements(long nowMillis) {
        requireUsable();
        List<byte[]> frames = new ArrayList<>(normalLink.pollLinkDirectorAnnouncements(nowMillis));
        if (nativeQuic != null) frames.addAll(nativeQuic.poll(normalLink));
        return frames;
    }

    void discoverNativeSnapshotService(NativeApplicationServiceDiscovery discovery,
            java.util.function.Consumer<String> log) {
        requireUsable();
        byte[] request = discovery.request();
        try {
            normalLink.requestApplicationService(request, message -> {
                List<NativeApplicationServiceDiscovery.Endpoint> endpoints = discovery.acceptAuthenticated(message);
                if (!endpoints.isEmpty()) {
                    nativeSnapshotEndpoints = endpoints;
                    log.accept("APPLICATION SERVICE DISCOVERY: matched native Replicator endpoint; count="
                        + endpoints.size() + "; port=" + endpoints.get(0).port
                        + "; address/key/token logged=false; QUIC and snapshot transfer not yet established.");
                    try { nativeQuic = NativeQuicSession.create(discovery, endpoints.get(0), normalLink,
                        ids.localApplicationDeviceId(), log); }
                    catch (RuntimeException | UnsatisfiedLinkError unavailable) {
                        log.accept("REPLICATOR QUIC: initialization unavailable: " + unavailable.getMessage()
                            + "; IDS retained; snapshot delivery unconfirmed.");
                    }
                }
            });
        } finally { java.util.Arrays.fill(request, (byte) 0); }
    }

    List<NativeApplicationServiceDiscovery.Endpoint> nativeSnapshotEndpoints() {
        requireUsable();
        return nativeSnapshotEndpoints;
    }

    boolean linkDirectorHelloAcknowledged() {
        requireUsable();
        return normalLink.linkDirectorHelloAcknowledged();
    }

    boolean linkDirectorStateAcknowledged() {
        requireUsable();
        return normalLink.linkDirectorStateAcknowledged();
    }

    /**
     * Unsolicited NAs for the inner Class-D and Class-C addresses so Watch
     * can open {@code ids-control-channel} (NREndpoint :61315 over IPsec)
     * without waiting for a phone-initiated named UTun SYN to warm NDP.
     */
    List<byte[]> announceLocalNeighbor() {
        requireUsable();
        List<byte[]> frames = new ArrayList<>();
        appendUnsolicitedNeighbor(
                OrdinaryIkeAuth.DataClass.CLASS_D,
                normalLink.localClassD(),
                normalLink.remoteClassD(),
                frames);
        appendUnsolicitedNeighbor(
                OrdinaryIkeAuth.DataClass.CLASS_C,
                normalLink.localClassC(),
                normalLink.remoteClassC(),
                frames);
        return frames;
    }

    private void appendUnsolicitedNeighbor(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] local,
            byte[] remote,
            List<byte[]> frames) {
        byte[] advertisement = null;
        try {
            advertisement = Ipv6IcmpHandler.unsolicitedNeighborAdvertisement(
                    local,
                    remote);
            byte[] frame = normalLink.sendIpv6(
                    dataClass,
                    advertisement);
            if (frame != null) {
                frames.add(frame);
            }
        } finally {
            wipe(local);
            wipe(remote);
            wipe(advertisement);
        }
    }

    private void routeNonIdsPacket(
            NormalLinkPipeSession.DeliveredIp packet,
            List<byte[]> ertmFrames,
            List<NormalLinkPipeSession.DeliveredIp> passthroughIp) {
        if (nativeQuic != null && nativeQuic.accept(packet)) {
            ertmFrames.addAll(nativeQuic.poll(normalLink));
            return;
        }
        byte[] icmpReply =
                packet.packet != null
                        ? Ipv6IcmpHandler.handle(packet.packet)
                        : null;
        if (icmpReply != null) {
            try {
                byte[] frame = normalLink.sendIpv6(
                        packet.dataClass != null
                                ? packet.dataClass
                                : OrdinaryIkeAuth.DataClass.CLASS_D,
                        icmpReply);
                if (frame != null) {
                    ertmFrames.add(frame);
                }
            } finally {
                wipe(icmpReply);
            }
            return;
        }
        passthroughIp.add(
                packet.copy());
    }

    Output retransmitTcpOutstanding() {
        requireUsable();
        return route(
                ids.retransmitTcpOutstanding());
    }

    byte[] copyOldestUnacknowledgedErtmFrame() {
        requireUsable();
        return normalLink.copyOldestUnacknowledgedErtmFrame();
    }

    java.util.List<byte[]> copyUnacknowledgedErtmFrames(int limit) {
        requireUsable();
        return normalLink.copyUnacknowledgedErtmFrames(limit);
    }

    int ertmOldestTxSequence() {
        requireUsable();
        return normalLink.ertmOldestTxSequence();
    }

    /**
     * Consumes one dynamic-L2CAP ERTM frame and routes every authenticated
     * IDS packet back through the matching ordinary data class.
     */
    Output acceptErtmFrame(
            byte[] l2capPayload) {
        requireUsable();
        List<byte[]> ertmFrames =
                new ArrayList<>();
        List<NormalLinkPipeSession.DeliveredIp> passthroughIp =
                new ArrayList<>();
        List<byte[]> normalControlMessages =
                new ArrayList<>();
        List<IdsModernSessionCoordinator.Output> idsOutputs =
                new ArrayList<>();
        List<NormalLinkPipeSession.DeliveredIp> delivered =
                new ArrayList<>();
        boolean transferred = false;
        try (NormalLinkPipeSession.InboundResult normal =
                     normalLink.acceptErtmFrame(
                             l2capPayload)) {
            ertmFrames.addAll(
                    normal.immediateErtmFrames());
            normalControlMessages.addAll(
                    normal.controlMessages());
            delivered.addAll(
                    normal.deliveredIp());
            for (NormalLinkPipeSession.DeliveredIp packet :
                    delivered) {
                if (isIdsPacket(
                        packet)) {
                    IdsModernSessionCoordinator.Output idsOutput =
                            ids.accept(
                                    packet.dataClass,
                                    packet.packet);
                    boolean outputTransferred = false;
                    try {
                        appendIdsOutput(
                                idsOutput,
                                ertmFrames);
                        idsOutputs.add(
                                idsOutput);
                        outputTransferred = true;
                    } finally {
                        if (!outputTransferred) {
                            idsOutput.close();
                        }
                    }
                } else {
                    // Same ICMPv6 path as acceptDeliveredIp. Live 0.2.294:
                    // ERTM non-IDS packets (incl. Neighbor Solicitation)
                    // were copied to passthrough and destroyed without NA,
                    // so Watch never learned phone Class-D reachability and
                    // never opened ids-control-channel after DeviceLinkState.
                    routeNonIdsPacket(
                            packet,
                            ertmFrames,
                            passthroughIp);
                }
            }
            drainDeferredIpv6Into(
                    ertmFrames);
            Output output =
                    new Output(
                            ertmFrames,
                            passthroughIp,
                            normalControlMessages,
                            idsOutputs);
            transferred = true;
            return output;
        } catch (RuntimeException failure) {
            poison();
            throw failure;
        } finally {
            destroyDelivered(
                    delivered);
            if (!transferred) {
                wipeAll(
                        ertmFrames);
                destroyDelivered(
                        passthroughIp);
                wipeAll(
                        normalControlMessages);
                closeIdsOutputs(
                        idsOutputs);
            }
        }
    }

    Output acceptDeliveredIp(
            List<NormalLinkPipeSession.DeliveredIp> delivered) {
        requireUsable();
        List<byte[]> ertmFrames =
                new ArrayList<>();
        List<NormalLinkPipeSession.DeliveredIp> passthroughIp =
                new ArrayList<>();
        List<byte[]> normalControlMessages =
                new ArrayList<>();
        List<IdsModernSessionCoordinator.Output> idsOutputs =
                new ArrayList<>();
        boolean transferred = false;
        try {
            if (delivered != null) {
                for (NormalLinkPipeSession.DeliveredIp packet :
                        delivered) {
                    if (isIdsPacket(
                            packet)) {
                        IdsModernSessionCoordinator.Output idsOutput =
                                ids.accept(
                                        packet.dataClass,
                                        packet.packet);
                        boolean outputTransferred = false;
                        try {
                            appendIdsOutput(
                                    idsOutput,
                                    ertmFrames);
                            idsOutputs.add(
                                    idsOutput);
                            outputTransferred = true;
                        } finally {
                            if (!outputTransferred) {
                                idsOutput.close();
                            }
                        }
                    } else {
                        routeNonIdsPacket(
                                packet,
                                ertmFrames,
                                passthroughIp);
                    }
                }
            }
            Output output =
                    new Output(
                            ertmFrames,
                            passthroughIp,
                            normalControlMessages,
                            idsOutputs);
            transferred = true;
            return output;
        } catch (RuntimeException failure) {
            poison();
            throw failure;
        } finally {
            if (!transferred) {
                wipeAll(
                        ertmFrames);
                destroyDelivered(
                        passthroughIp);
                wipeAll(
                        normalControlMessages);
                closeIdsOutputs(
                        idsOutputs);
            }
        }
    }

    IdsModernSessionCoordinator.Snapshot idsSnapshot() {
        requireUsable();
        return ids.snapshot();
    }

    boolean dataLanesReadyForApplication() {
        requireUsable();
        return ids.dataLanesReadyForApplication();
    }

    boolean classDLaneStableForApplication() {
        requireUsable();
        return ids.classDLaneStableForApplication();
    }

    boolean classCLaneStableForApplication() {
        requireUsable();
        return ids.classCLaneStableForApplication();
    }

    int ertmOutstandingCount() {
        requireUsable();
        return normalLink.ertmOutstandingCount();
    }

    long tcpUnacknowledgedSendBytes() {
        requireUsable();
        return ids.tcpUnacknowledgedSendBytes();
    }

    String tcpUnacknowledgedSendSummary() {
        requireUsable();
        return ids.tcpUnacknowledgedSendSummary();
    }

    void setPayloadFastRetransmitEnabled(
            boolean enabled) {
        requireUsable();
        ids.setPayloadFastRetransmitEnabled(
                enabled);
    }

    int effectivePeerMaxPairingVersion() {
        requireUsable();
        return ids.effectivePeerMaxPairingVersion();
    }

    NormalLinkPipeSession.Phase normalLinkPhase() {
        requireUsable();
        return normalLink.phase();
    }

    private Output route(
            IdsModernSessionCoordinator.Output idsOutput) {
        List<byte[]> ertmFrames =
                new ArrayList<>();
        List<IdsModernSessionCoordinator.Output> idsOutputs =
                new ArrayList<>();
        boolean transferred = false;
        try {
            appendIdsOutput(
                    idsOutput,
                    ertmFrames);
            idsOutputs.add(
                    idsOutput);
            Output output =
                    new Output(
                            ertmFrames,
                            new ArrayList<>(),
                            new ArrayList<>(),
                            idsOutputs);
            transferred = true;
            return output;
        } catch (RuntimeException failure) {
            poison();
            throw failure;
        } finally {
            if (!transferred) {
                wipeAll(
                        ertmFrames);
                if (idsOutputs.isEmpty()) {
                    idsOutput.close();
                } else {
                    closeIdsOutputs(
                            idsOutputs);
                }
            }
        }
    }

    private void appendIdsOutput(
            IdsModernSessionCoordinator.Output output,
            List<byte[]> ertmFrames) {
        drainDeferredIpv6Into(
                ertmFrames);
        for (IdsModernSessionCoordinator.RoutedPacket packet :
                output.packets()) {
            byte[] clearIpv6 =
                    packet.clearIpv6Packet();
            try {
                // Anything behind an already queued packet has to queue too,
                // otherwise the peer TCP stack sees the stream out of order.
                if (!deferredIpv6.isEmpty()) {
                    deferredIpv6.add(
                            new DeferredIpv6(
                                    packet.dataClass,
                                    clearIpv6.clone()));
                    continue;
                }
                byte[] frame = normalLink.sendIpv6(
                        packet.dataClass,
                        clearIpv6);
                if (frame != null) {
                    ertmFrames.add(frame);
                } else {
                    // The ERTM transmit window is full. The IDS coordinator has
                    // already accounted for this packet as sent, so dropping it
                    // would silently lose an application message; hold it until
                    // the window reopens instead.
                    deferredIpv6.add(
                            new DeferredIpv6(
                                    packet.dataClass,
                                    clearIpv6.clone()));
                }
            } finally {
                wipe(
                        clearIpv6);
            }
        }
    }

    private void drainDeferredIpv6Into(
            List<byte[]> ertmFrames) {
        while (!deferredIpv6.isEmpty()) {
            DeferredIpv6 head =
                    deferredIpv6.get(0);
            byte[] frame = normalLink.sendIpv6(
                    head.dataClass,
                    head.clearIpv6Packet);
            if (frame == null) {
                return;
            }
            ertmFrames.add(frame);
            deferredIpv6.remove(0);
            wipe(
                    head.clearIpv6Packet);
        }
    }

    boolean hasDeferredIpv6() {
        return !poisoned
                && !closed
                && !deferredIpv6.isEmpty();
    }

    int deferredIpv6Count() {
        return deferredIpv6.size();
    }

    /** Re-sends IPv6 packets that were held back by a full ERTM window. */
    Output drainDeferredIpv6() {
        requireUsable();
        List<byte[]> ertmFrames =
                new ArrayList<>();
        boolean transferred = false;
        try {
            drainDeferredIpv6Into(
                    ertmFrames);
            Output output =
                    new Output(
                            ertmFrames,
                            new ArrayList<>(),
                            new ArrayList<>(),
                            new ArrayList<>());
            transferred = true;
            return output;
        } catch (RuntimeException failure) {
            poison();
            throw failure;
        } finally {
            if (!transferred) {
                wipeAll(
                        ertmFrames);
            }
        }
    }

    private static final class DeferredIpv6 {
        private final OrdinaryIkeAuth.DataClass dataClass;
        private final byte[] clearIpv6Packet;

        private DeferredIpv6(
                OrdinaryIkeAuth.DataClass dataClass,
                byte[] clearIpv6Packet) {
            this.dataClass = dataClass;
            this.clearIpv6Packet = clearIpv6Packet;
        }
    }

    private boolean isIdsPacket(
            NormalLinkPipeSession.DeliveredIp packet) {
        OrdinaryIkeAuth.DataClass dc =
                packet.dataClass != null
                        ? packet.dataClass
                        : OrdinaryIkeAuth.DataClass.CLASS_D;
        return ids.recognizesInbound(
                dc,
                packet.packet);
    }

    private void requireUsable() {
        if (poisoned) {
            throw new IllegalStateException(
                    "Normal-link IDS bridge is poisoned");
        }
        if (closed) {
            throw new IllegalStateException(
                    "Normal-link IDS bridge is closed");
        }
    }

    private void poison() {
        if (poisoned
                || closed) {
            return;
        }
        poisoned = true;
        closeOwnedSessions();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        nativeSnapshotEndpoints = List.of();
        if (nativeQuic != null) { nativeQuic.close(); nativeQuic = null; }
        closeOwnedSessions();
    }

    private void closeOwnedSessions() {
        try {
            for (DeferredIpv6 held : deferredIpv6) {
                wipe(
                        held.clearIpv6Packet);
            }
            deferredIpv6.clear();
            ids.close();
        } finally {
            normalLink.close();
        }
    }

    static final class Output
            implements AutoCloseable {
        private final List<byte[]> ertmFrames;
        private final List<NormalLinkPipeSession.DeliveredIp> passthroughIp;
        private final List<byte[]> normalControlMessages;
        private final List<IdsModernSessionCoordinator.Output> idsOutputs;
        private boolean closed;

        private Output(
                List<byte[]> ertmFrames,
                List<NormalLinkPipeSession.DeliveredIp> passthroughIp,
                List<byte[]> normalControlMessages,
                List<IdsModernSessionCoordinator.Output> idsOutputs) {
            this.ertmFrames =
                    new ArrayList<>(
                            ertmFrames);
            this.passthroughIp =
                    new ArrayList<>(
                            passthroughIp);
            this.normalControlMessages =
                    new ArrayList<>(
                            normalControlMessages);
            this.idsOutputs =
                    new ArrayList<>(
                            idsOutputs);
            ertmFrames.clear();
            passthroughIp.clear();
            normalControlMessages.clear();
            idsOutputs.clear();
        }

        List<byte[]> ertmFrames() {
            requireOpen();
            return deepCopy(
                    ertmFrames);
        }

        List<NormalLinkPipeSession.DeliveredIp> passthroughIp() {
            requireOpen();
            return copyDelivered(
                    passthroughIp);
        }

        List<byte[]> normalControlMessages() {
            requireOpen();
            return deepCopy(
                    normalControlMessages);
        }

        List<IdsModernSessionCoordinator.SessionEvent> idsEvents() {
            requireOpen();
            List<IdsModernSessionCoordinator.SessionEvent> events =
                    new ArrayList<>();
            for (IdsModernSessionCoordinator.Output output :
                    idsOutputs) {
                events.addAll(
                        output.events());
            }
            return Collections.unmodifiableList(
                    events);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Normal-link IDS output is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipeAll(
                    ertmFrames);
            destroyDelivered(
                    passthroughIp);
            wipeAll(
                    normalControlMessages);
            closeIdsOutputs(
                    idsOutputs);
        }
    }

    private static List<byte[]> deepCopy(
            List<byte[]> values) {
        List<byte[]> output =
                new ArrayList<>(
                        values.size());
        for (byte[] value :
                values) {
            output.add(
                    value.clone());
        }
        return output;
    }

    private static List<NormalLinkPipeSession.DeliveredIp> copyDelivered(
            List<NormalLinkPipeSession.DeliveredIp> values) {
        List<NormalLinkPipeSession.DeliveredIp> output =
                new ArrayList<>(
                        values.size());
        for (NormalLinkPipeSession.DeliveredIp value :
                values) {
            output.add(
                    value.copy());
        }
        return output;
    }

    private static void closeIdsOutputs(
            List<IdsModernSessionCoordinator.Output> values) {
        for (IdsModernSessionCoordinator.Output value :
                values) {
            value.close();
        }
        values.clear();
    }

    private static void destroyDelivered(
            List<NormalLinkPipeSession.DeliveredIp> values) {
        for (NormalLinkPipeSession.DeliveredIp value :
                values) {
            value.destroy();
        }
        values.clear();
    }

    private static void wipeAll(
            List<byte[]> values) {
        for (byte[] value :
                values) {
            wipe(
                    value);
        }
        values.clear();
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
