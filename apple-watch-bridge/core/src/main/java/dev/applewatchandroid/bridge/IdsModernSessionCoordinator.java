package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * End-to-end IDS session state above authenticated NetworkRelay IPv6.
 *
 * <p>This coordinator deliberately keeps two port namespaces independent:
 * type-6 Setup ports belong to {@link IdsEncryptedDataChannelSession}, while
 * TCP ports belong to {@link IdsServiceConnectorCoordinator}. The states are
 * joined only by the canonical {@code account/service/name} string, matching
 * the watchOS service-connection cache.</p>
 *
 * <p>The class does not perform Bluetooth, IKE, ESP, pairing-store, activation,
 * or setup-commit operations. It accepts and emits clear Class-C/Class-D IPv6
 * packets so the complete IDS chain can be verified offline before it is
 * attached to {@link NormalLinkPipeSession}.</p>
 */
final class IdsModernSessionCoordinator
        implements AutoCloseable {
    enum EventType {
        CONTROL_CONNECT_STARTED,
        CONTROL_SERVICE_ACCEPTED,
        CONTROL_SERVICE_REJECTED,
        CONTROL_HELLO_SENT,
        DIRECT_MESSAGING_INFO_SENT,
        CONTROL_HELLO_RECEIVED,
                    CONTROL_READY,
                    CONTROL_MESSAGE,
        DATA_SETUP_SENT,
        DATA_SETUP_REPLIED,
        DATA_SETUP_ESTABLISHED,
        DATA_CONNECT_STARTED,
        DATA_SERVICE_ACCEPTED,
        DATA_CHANNEL_JOINED,
        SERVICE_MAP_SENT,
        SERVICE_MAP_RECEIVED,
        PROTOBUF_SENT,
        PROTOBUF_RECEIVED,
        DATA_SENT,
        DATA_RECEIVED,
        APP_ACK_SENT,
        APP_ACK_RECEIVED,
        ACK_RECEIVED,
        ACK_SENT,
        KEEP_ALIVE_RECEIVED,
        UNKNOWN_COMMAND_IGNORED,
        HANDSHAKE_SENT,
        HANDSHAKE_RECEIVED,
        TRANSPORT_EVENT
    }

    /** Bounded reopen budget for a control service the peer defers. */
    static final int MAX_CONTROL_TRANSIENT_RETRIES = 6;

    /** Optional live-session diagnostics hook (set by the root HAL host). */
    static volatile java.util.function.Consumer<String> diagnosticLogger;

    final SecureRandom random;
    final IdsApplicationMessageAllocator applicationMessages;
    final IdsServiceConnectorCoordinator transport;
    final IdsPortMap setupPorts;
    final IdsSsrcMap ssrcs =
            new IdsSsrcMap();
    final String localIdsDeviceUuid;

    String localApplicationDeviceId() { return localIdsDeviceUuid; }
    final boolean directMessagingSupported;
    final Map<String, DataState> dataByService =
            new LinkedHashMap<>();
    final Map<Long, DataTransport> dataTransports =
            new LinkedHashMap<>();
    final Map<Long, DataState> dataByConnection =
            new LinkedHashMap<>();
    final List<RoutedPacket> deferredPackets =
            new ArrayList<>();
    final PairedSyncCapabilityState pairedSyncCapability =
            new PairedSyncCapabilityState();

    IdsDataChannelJoinState<DataTransport> dataJoins;
    IdsControlChannelSession control;
    long controlConnectionId;
    boolean controlOpenRequested;
    int controlTransientRetries;
    boolean closing;
    private boolean poisoned;
    boolean closed;
    @FunctionalInterface
    interface OutgoingFrameObserver {
        void record(String kind, String topic, int type, boolean response, byte[] frame);
    }
    private OutgoingFrameObserver outgoingFrameObserver;

    synchronized void setOutgoingFrameObserver(OutgoingFrameObserver observer) {
        requireUsable();
        outgoingFrameObserver = observer;
    }

    void observeOutgoingFrame(String kind, String topic, int type, boolean response, byte[] frame) {
        if (outgoingFrameObserver == null) return;
        byte[] copy = frame.clone();
        try {
            outgoingFrameObserver.record(kind, topic, type, response, copy);
        } catch (RuntimeException diagnosticFailure) {
            // Diagnostic failure must never poison the live IDS session.
            outgoingFrameObserver = null;
        } finally { wipe(copy); }
    }
    /**
     * iOS {@code IDSUTunDeliveryController} names socket-pair command 0x15
     * fragments with a uint32 message ID. WatchWitch reassembles on that
     * same field. Live 0.2.119 crashed sending the 345-UUID Capabilities
     * snapshot because this allocator left the ID unset.
     */
    long nextFragmentedMessageId;
    CompanionProtocolProfile profile = CompanionProtocolProfile.IOS_26_6;

    synchronized CompanionProtocolProfile profile() { requireUsable(); return profile; }

    synchronized void useProfile(CompanionProtocolProfile selected) {
        requireUsable();
        if (selected == null || control != null) throw new IllegalStateException("IDS profile already in use");
        profile = selected;
    }

    synchronized String observedPeerProductVersion() {
        requireUsable();
        IdsControlChannelSession.RemoteHello hello = control == null ? null : control.remoteHello();
        return hello == null ? null : hello.productVersion;
    }

    synchronized String observedPeerBuildVersion() {
        requireUsable();
        IdsControlChannelSession.RemoteHello hello = control == null ? null : control.remoteHello();
        return hello == null ? null : hello.productBuildVersion;
    }

    IdsModernSessionCoordinator(
            SecureRandom random,
            long serviceConnectorSequenceSeed,
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC,
            String localIdsDeviceUuid,
            boolean directMessagingSupported) {
        this(
                random,
                serviceConnectorSequenceSeed,
                localClassD,
                remoteClassD,
                localClassC,
                remoteClassC,
                null,
                null,
                null,
                null,
                localIdsDeviceUuid,
                directMessagingSupported);
    }

    IdsModernSessionCoordinator(
            SecureRandom random,
            long serviceConnectorSequenceSeed,
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC,
            byte[] localPrivD,
            byte[] remotePubD,
            byte[] localPrivC,
            byte[] remotePubC,
            String localIdsDeviceUuid,
            boolean directMessagingSupported) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "IDS session random source is absent");
        }
        requireCanonicalUuid(
                localIdsDeviceUuid,
                "local IDS device UUID");
        this.random = random;
        applicationMessages =
                new IdsApplicationMessageAllocator(
                        random);
        this.localIdsDeviceUuid =
                localIdsDeviceUuid;
        this.directMessagingSupported =
                directMessagingSupported;
        this.setupPorts =
                new IdsPortMap(random);
        transport =
                IdsServiceConnectorCoordinator.createPairedWithKeys(
                        random,
                        new NwServiceConnectorSequenceAllocator(
                                serviceConnectorSequenceSeed),
                        localClassD,
                        remoteClassD,
                        localClassC,
                        remoteClassC,
                        localPrivD,
                        remotePubD,
                        localPrivC,
                        remotePubC,
                        setupPorts);
        dataJoins =
                newJoinState();
    }

    NwServiceConnectorSequenceAllocator sequenceAllocator() {
        return transport.sequences();
    }

    /**
     * Starts the Class-D cloud service for the IDS control channel.
     */
    /**
     * Randomizes the IDS port cursors before the first SYN. Production hosts
     * call this once per process; tests keep the deterministic cursor.
     */
    synchronized void randomizePortCursor() {
        requireUsable();
        transport.randomizePortCursor();
        setupPorts.randomizeCursor(
                random);
    }

    synchronized void setStaleFlowAddresses(
            byte[] localClassD,
            byte[] remoteClassD) {
        requireUsable();
        transport.setStaleFlowAddresses(localClassD, remoteClassD);
    }

    synchronized void seedStaleFlows(
            List<IdsStaleFlowRecord> seeds) {
        requireUsable();
        transport.seedStaleFlows(seeds);
    }

    synchronized List<IdsStaleFlowRecord> staleFlowObservations() {
        requireUsable();
        return transport.staleFlowObservations();
    }

    /**
     * Paired reconnect: proactive RFC 5961 resets for Watch flows that
     * outlived the previous process. The Watch reopens its
     * ids-control-channel only after its stale control TCB dies
     * (watchOS 26.2 FUN_10035caa4 retry path); one reset RTT replaces the
     * ~8.6-min Watch TCP RTO (live 13:08-13:16, 0.2.305 15:10 silence).
     */
    synchronized Output resetStaleFlows() {
        requireUsable();
        OutputBuilder output =
                new OutputBuilder();
        try (IdsServiceConnectorCoordinator.AcceptResult resets =
                     transport.buildProactiveStaleResets()) {
            addPackets(
                    output,
                    resets.outboundPackets());
            drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw fail(
                    failure);
        }
    }


    final IdsModernApplicationSender appSender = new IdsModernApplicationSender(this);
    final IdsModernControlExchange controlExchange = new IdsModernControlExchange(this);
    final IdsModernDataChannelEngine dataChannelEngine = new IdsModernDataChannelEngine(this);

    synchronized Output startControl() { return controlExchange.startControl(); }
    synchronized Output startInitialLane(String topic) { return controlExchange.startInitialLane(topic); }
    synchronized Output sendControlMessage(IdsControlChannelCodec.Message message) { return controlExchange.sendControlMessage(message); }
    synchronized Output startOutgoingService(IdsServiceConnectorName name) { return controlExchange.startOutgoingService(name); }
    synchronized Output sendClassD(NanoRegistryClassDCodec.ApplicationMessage message) { return appSender.sendClassD(message); }
    synchronized Output sendClassDResponse(NanoRegistryClassDCodec.ApplicationMessage message, String outgoingResponseIdentifier) { return appSender.sendClassDResponse(message, outgoingResponseIdentifier); }
    synchronized Output sendClassDRequestWithClientAcknowledgement(NanoRegistryClassDCodec.ApplicationMessage message) { return appSender.sendClassDRequestWithClientAcknowledgement(message); }
    synchronized Output sendClassD(MessageMetadata metadata, NanoRegistryClassDCodec.ApplicationMessage message) { return appSender.sendClassD(metadata, message); }
    synchronized Output sendClassC(NanoRegistryPropertyCodec.ApplicationMessage message) { return appSender.sendClassC(message); }
    synchronized Output sendClassCResponse(NanoRegistryPropertyCodec.ApplicationMessage message, String outgoingResponseIdentifier) { return appSender.sendClassCResponse(message, outgoingResponseIdentifier); }
    synchronized Output sendClassC(MessageMetadata metadata, NanoRegistryPropertyCodec.ApplicationMessage message) { return appSender.sendClassC(metadata, message); }
    synchronized Output sendPbBridge(PbBridgeCodec.ApplicationMessage message) { return appSender.sendPbBridge(message); }
    synchronized Output sendPbBridgeReplyToFetch(PbBridgeCodec.ApplicationMessage message, String watchMessageUuid) { return appSender.sendPbBridgeReplyToFetch(message, watchMessageUuid); }
    synchronized Output sendPbBridgeResponse(PbBridgeCodec.ApplicationMessage message, String outgoingResponseIdentifier) { return appSender.sendPbBridgeResponse(message, outgoingResponseIdentifier); }
    synchronized Output sendPbBridge(MessageMetadata metadata, PbBridgeCodec.ApplicationMessage message) { return appSender.sendPbBridge(metadata, message); }
    synchronized Output sendPairedSync(PairedSyncCodec.UserDefaultsMessage message) { return appSender.sendPairedSync(message); }
    synchronized Output sendPairedSync(MessageMetadata metadata, PairedSyncCodec.UserDefaultsMessage message) { return appSender.sendPairedSync(metadata, message); }
    synchronized Output sendAppAck(String topic, String outgoingResponseIdentifier) { return appSender.sendAppAck(topic, outgoingResponseIdentifier); }
    synchronized Output sendApplicationProtobuf(String topic, int protobufType, byte[] payload) { return appSender.sendApplicationProtobuf(topic, protobufType, payload); }
    synchronized Output sendWatchReboot() { return appSender.sendWatchReboot(); }
    synchronized Output sendFindMyLocalResponse(int protobufType, byte[] payload, String responseId) { return appSender.sendFindMyLocalResponse(protobufType, payload, responseId); }
    synchronized Output sendApplicationData(String topic, byte[] payload) { return appSender.sendApplicationData(topic, payload); }
    synchronized Output sendSoftwareUpdateRequest(byte[] payload) { return appSender.sendSoftwareUpdateRequest(payload); }
    synchronized Output sendHealthSyncRequest(byte[] encrypted, java.util.UUID message) { return appSender.sendHealthSyncRequest(encrypted, message); }
    synchronized Output sendClockFaceCollectionRequest(byte[] payload) { return appSender.sendClockFaceCollectionRequest(payload); }
    synchronized Output sendClockFaceDeltaRequest(byte[] payload) { return appSender.sendClockFaceDeltaRequest(payload); }
    synchronized Output sendApplicationDataResponse(String topic, byte[] payload, String outgoingResponseIdentifier) { return appSender.sendApplicationDataResponse(topic, payload, outgoingResponseIdentifier); }
    synchronized Output sendApplicationDictionary(String topic, byte[] payload) { return appSender.sendApplicationDictionary(topic, payload); }
    synchronized String pendingApplicationFramesSummary() { return dataChannelEngine.pendingApplicationFramesSummary(); }
    synchronized boolean hasPendingApplicationFrames() { return dataChannelEngine.hasPendingApplicationFrames(); }
    synchronized Output flushPendingApplicationFrames() { return dataChannelEngine.flushPendingApplicationFrames(); }
    private void acceptDataBytes(long connectionId, byte[] bytes, OutputBuilder output) { dataChannelEngine.acceptDataBytes(connectionId, bytes, output); }




    /**
     * Paired reconnect wake: Class-D NoOp key probe, not Urgent-D Setup.
     */
    synchronized Output startPairedClassDKeyProbe() {
        requireUsable();
        OutputBuilder output =
                new OutputBuilder();
        try (IdsServiceConnectorCoordinator.AcceptResult started =
                     transport.startClassDKeyProbe()) {
            addPackets(
                    output,
                    started.outboundPackets());
            for (IdsServiceConnectorCoordinator.ConnectionEvent event :
                    started.events()) {
                processTransportEvent(
                        event,
                        output);
            }
            drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw fail(
                    failure);
        }
    }
















    /**
     * Accepts one authenticated, decrypted NetworkRelay IPv6 packet.
     */
    synchronized Output accept(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] clearIpv6Packet) {
        requireUsable();
        OutputBuilder output =
                new OutputBuilder();
        try (IdsServiceConnectorCoordinator.AcceptResult accepted =
                     transport.accept(
                             dataClass,
                             clearIpv6Packet)) {
            addPackets(
                    output,
                    accepted.outboundPackets());
            for (IdsServiceConnectorCoordinator.ConnectionEvent event :
                    accepted.events()) {
                processTransportEvent(
                        event,
                        output);
            }
            drainDeferredPackets(
                    output);
            return output.build();
        } catch (IllegalArgumentException stale) {
            if (isStaleCollisionPacket(
                    stale)) {
                drainDeferredPackets(
                        output);
                return output.build();
            }
            output.close();
            throw fail(
                    stale);
        } catch (RuntimeException failure) {
            output.close();
            throw fail(
                    failure);
        }
    }

    /**
     * Classifies a clear normal-link packet without mutating IDS state.
     */
    synchronized boolean recognizesInbound(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] clearIpv6Packet) {
        requireUsable();
        return transport.recognizesInbound(
                dataClass,
                clearIpv6Packet);
    }


    synchronized Output pollRetransmissions() {
        requireUsable();
        OutputBuilder output = new OutputBuilder();
        controlExchange.ensureControlHello(output);
        dataChannelEngine.startPendingDataConnectors(output);
        addPackets(output, transport.retransmitOutstanding());
        return output.build();
    }

    /**
     * Retransmits unacked TCP payload only. Does not start Hello or new
     * service connectors (live 0.2.124 pollRetransmissions filled TxWindow).
     */
    synchronized Output retransmitTcpOutstanding() {
        requireUsable();
        OutputBuilder output = new OutputBuilder();
        addPackets(output, transport.retransmitOutstanding());
        return output.build();
    }

    synchronized long tcpUnacknowledgedSendBytes() {
        requireUsable();
        return transport.unacknowledgedSendBytes();
    }

    synchronized String tcpUnacknowledgedSendSummary() {
        requireUsable();
        return transport.unacknowledgedSendSummary();
    }

    synchronized void setPayloadFastRetransmitEnabled(
            boolean enabled) {
        requireUsable();
        transport.setPayloadFastRetransmitEnabled(
                enabled);
    }

    synchronized Snapshot snapshot() {
        requireUsable();
        int joined = 0;
        for (DataState state :
                dataByService.values()) {
            if (state.connection != null) {
                joined++;
            }
        }
        return new Snapshot(
                controlConnectionId != 0,
                control != null
                        && control.remoteHelloReceived(),
                dataByService.size(),
                joined,
                setupPorts.dynamicAllocatedCount(),
                ssrcs.allocatedCount(),
                transport.dynamicPortCount(),
                pairedSyncCapability.status());
    }

    /**
     * True when both NanoRegistry Class-D and Class-C NWSC connections are
     * accepted and able to carry socket-pair frames.
     */
    synchronized boolean dataLanesReadyForApplication() {
        requireUsable();
        boolean classD = false;
        boolean classC = false;
        for (DataState state : dataByService.values()) {
            if (state.stateClosed
                    || state.connection == null
                    || state.connection.transportGone) {
                continue;
            }
            if (!transport.isServiceActive(
                    state.connection.connectionId)) {
                continue;
            }
            if (isRelayLaneD(state.service)) {
                classD = true;
            }
            if (isRelayLaneC(state.service)) {
                classC = true;
            }
        }
        return (classD && classC) || (directMessagingSupported && (classD || classC));
    }

    /**
     * True when the NanoRegistry Class-D NWSC connection is accepted and able
     * to carry socket-pair frames.
     */
    synchronized boolean classDLaneStableForApplication() {
        requireUsable();
        return laneStableForApplication(true);
    }

    /**
     * True when the NanoRegistry Class-C NWSC connection is accepted and able
     * to carry socket-pair frames.
     */
    synchronized boolean classCLaneStableForApplication() {
        requireUsable();
        return laneStableForApplication(false);
    }

    private boolean laneStableForApplication(
            boolean wantClassD) {
        for (DataState state : dataByService.values()) {
            if (state.stateClosed
                    || state.connection == null
                    || state.connection.transportGone) {
                continue;
            }
            if (!transport.isServiceActive(
                    state.connection.connectionId)) {
                continue;
            }
            if (wantClassD
                    && isRelayLaneD(state.service)) {
                return true;
            }
            if (!wantClassD
                    && isRelayLaneC(state.service)) {
                return true;
            }
        }
        return false;
    }

    synchronized void setPairedSyncCapability(
            PairedSyncCapabilityState.Status status) {
        requireUsable();
        pairedSyncCapability.setStatus(status);
    }

    synchronized DataSnapshot dataChannel(
            String topic) {
        requireUsable();
        IdsApplicationRoute route =
                IdsApplicationRoute.forTopic(
                        topic);
        DataState state =
                dataByService.get(
                        route.serviceConnectorName.encode());
        if (state == null && !dataByService.isEmpty()) {
            for (DataState candidate : dataByService.values()) {
                if (serviceMatches(route.serviceConnectorName.encode(), candidate.service)
                        || serviceMatches(topic, candidate.service)) {
                    state = candidate;
                    break;
                }
            }
        }
        if (state == null) {
            throw new IllegalStateException(
                    "IDS data lane does not exist");
        }
        int tcpLocalPort = 0;
        int tcpRemotePort = 0;
        long connectionId = 0;
        if (state.connection != null) {
            connectionId =
                    state.connection.connectionId;
            try {
                IdsServiceConnectorCoordinator.ConnectionSnapshot connection =
                        transport.connection(
                                connectionId);
                tcpLocalPort =
                        connection.localPort;
                tcpRemotePort =
                        connection.remotePort;
            } catch (IllegalArgumentException missing) {
                if (missing.getMessage() == null
                        || !missing.getMessage().contains(
                                "Unknown IDS TCP connection")) {
                    throw missing;
                }
            }
        }
        return new DataSnapshot(
                state.service,
                state.setup.localPort(),
                state.setup.remotePort(),
                connectionId,
                tcpLocalPort,
                tcpRemotePort,
                state.setup.established(),
                state.connection != null);
    }

    /**
     * Returns the connected peer's effective maximum pairing version.
     *
     * <p>Modern IDS publishes {@code maxCompatibilityVersion}. A legacy
     * zero value falls back to {@code pairingProtocolVersion}, matching the
     * phone-side NanoRegistry device-property updater.</p>
     */
    synchronized int effectivePeerMaxPairingVersion() {
        Integer observed = observedPeerMaxPairingVersion();
        return observed == null ? (int) IosCompanionProfile26_6.PAIRING_PROTOCOL_VERSION : observed;
    }

    /** Setup must use observed peer evidence instead of the local phone's fallback version. */
    synchronized Integer observedPeerMaxPairingVersion() {
        requireUsable();
        if (control == null
                || !control.remoteHelloReceived()) {
            return null;
        }
        IdsControlChannelSession.RemoteHello remote =
                control.remoteHello();
        long effective =
                remote.maxCompatibilityVersion == 0
                        ? remote.pairingProtocolVersion
                        : remote.maxCompatibilityVersion;
        if (effective <= 0
                || effective > 0xffff) {
            return null;
        }
        return (int) effective;
    }

    synchronized String peerVersionSummary() {
        requireUsable();
        if (control == null || !control.remoteHelloReceived()) return "Hello not received";
        IdsControlChannelSession.RemoteHello remote = control.remoteHello();
        return "protocol=" + remote.pairingProtocolVersion
                + " compatibility=" + remote.minCompatibilityVersion + ".." + remote.maxCompatibilityVersion
                + " serviceMinimum=" + remote.serviceMinCompatibilityVersion
                + " familyPairing=" + ((remote.capabilityFlags & IosCompanionProfile26_6.CAPABILITY_FAMILY_PAIRING) != 0);
    }

    synchronized boolean peerHelloMatchesIdentity(String authenticatedIdsId) {
        requireUsable();
        return control != null && control.remoteHelloReceived()
                && matchesUuid(control.remoteHello().deviceUniqueId, authenticatedIdsId);
    }

    /** Compares independent authenticated sources without exposing their values or changing state. */
    synchronized String peerIdentitySummary(String authenticatedIdsId, String networkRelayId) {
        requireUsable();
        if (control == null || !control.remoteHelloReceived()) return "Hello not received";
        IdsControlChannelSession.RemoteHello remote = control.remoteHello();
        UUID helloId = remote.deviceUniqueId;
        return "helloIdPresent=" + (helloId != null)
                + " helloIdZero=" + new UUID(0, 0).equals(helloId)
                + " matchesAuthenticatedIdsId=" + matchesUuid(helloId, authenticatedIdsId)
                + " matchesNetworkRelayId=" + matchesUuid(helloId, networkRelayId)
                + " instanceEqualsDeviceId=" + remote.instanceId.equals(helloId)
                + "; diagnostic only; identifiers logged=false";
    }

    private static boolean matchesUuid(UUID value, String reference) {
        if (value == null || reference == null) return false;
        try { return value.equals(UUID.fromString(reference)); }
        catch (IllegalArgumentException invalid) { return false; }
    }














    private void processTransportEvent(
            IdsServiceConnectorCoordinator.ConnectionEvent event,
            OutputBuilder output) {
        switch (event.type) {
            case SERVICE_ACCEPTED -> {
                if (IdsIpsecServiceRoute.CONTROL_SERVICE.equals(
                        event.serviceName)) {
                    controlExchange.acceptControlService(
                            event,
                            output);
                } else {
                    dataChannelEngine.acceptDataService(
                            event,
                            output);
                }
            }
            case APPLICATION_BYTES -> {
                byte[] bytes =
                        event.applicationBytes();
                try {
                    if (event.connectionId
                            == controlConnectionId) {
                        controlExchange.acceptControlBytes(
                                bytes,
                                output);
                    } else if (transport.isControlConnection(event.connectionId)) {
                        controlConnectionId = event.connectionId;
                        controlExchange.acceptControlBytes(
                                bytes,
                                output);
                    } else if (transport.isDataConnection(event.connectionId)) {
                        dataChannelEngine.acceptDataBytes(
                                event.connectionId,
                                bytes,
                                output);
                    } else if (control == null || !control.remoteHelloReceived()) {
                        try {
                            if (control == null) {
                                control = IdsControlChannelSession.createDefaultPaired(
                                        random,
                                        localIdsDeviceUuid,
                                        directMessagingSupported,
                                        IdsServiceMapState.APPLE_FIRST_STREAM_ID);
                                controlConnectionId = event.connectionId;
                            }
                            controlExchange.acceptControlBytes(
                                    bytes,
                                    output);
                        } catch (RuntimeException notControl) {
                            dataChannelEngine.acceptDataBytes(
                                    event.connectionId,
                                    bytes,
                                    output);
                        }
                    } else {
                        dataChannelEngine.acceptDataBytes(
                                event.connectionId,
                                bytes,
                                output);
                    }
                } finally {
                    wipe(
                            bytes);
                }
            }
            case ACTIVE_SUPERSEDED -> {
                dataChannelEngine.handleSuperseded(
                        event.relatedConnectionId);
                output.events.add(
                        SessionEvent.transport(
                                event));
            }
            case SERVICE_REJECTED_BY_POLICY, SERVICE_REJECTED_TRANSIENT, OUTGOING_CANCELLED -> {
                boolean wasControl = event.serviceName != null
                        && IdsIpsecServiceRoute.CONTROL_SERVICE.equals(event.serviceName)
                        && controlConnectionId != 0
                        && event.connectionId == controlConnectionId;
                // POLICY used to keep the dead outgoing id. Live 0.2.209: the
                // Watch still opens control itself (same 49153→61315 SYN as
                // the 13:56 run, where our SYN was RST'd). The outgoing
                // attempt is finished either way; drop it so the incoming
                // connector can become control. Do not open another SYN.
                if (wasControl
                        && (event.type != IdsServiceConnectorCoordinator.EventType.SERVICE_REJECTED_BY_POLICY
                        || control == null
                        || !control.remoteHelloReceived())) {
                    resetControlState();
                }
                if (!wasControl
                        && event.type == IdsServiceConnectorCoordinator.EventType.SERVICE_REJECTED_BY_POLICY
                        && event.serviceName != null) {
                    // iOS cancel_request_inner never opens a second SYN after
                    // 0x40. Leave the lane for a Watch-initiated connector.
                    DataState rejected = dataByService.get(event.serviceName);
                    if (rejected != null) {
                        rejected.awaitIncomingConnector = true;
                        rejected.needsReplacement = false;
                    }
                }
                output.events.add(
                        SessionEvent.transport(
                                event));
                // A transient rejection means "not yet", not "never". Without a
                // retry the control channel stays down, and every data service
                // connector opened afterwards is reset by the peer, so the setup
                // and activation messages can never be delivered.
                if (wasControl
                        && event.type == IdsServiceConnectorCoordinator
                                .EventType.SERVICE_REJECTED_TRANSIENT
                        && controlTransientRetries < MAX_CONTROL_TRANSIENT_RETRIES) {
                    controlTransientRetries++;
                    controlExchange.reopenControlService(
                            output);
                }
            }
            case KEY_PROBE_RESPONDED, KEY_PROBE_COMPLETED -> {
                output.events.add(
                        SessionEvent.transport(
                                event));
                DataState state = dataByConnection.get(event.connectionId);
                if (state != null && !state.pendingFrames.isEmpty()) {
                    List<PendingFrame> queued = new ArrayList<>(state.pendingFrames);
                    state.pendingFrames.clear();
                    for (PendingFrame queuedFrame : queued) {
                        try {
                            dataChannelEngine.sendSocketPairFrame(state, queuedFrame.bytes, queuedFrame.fragmentedMessageId, output);
                        } finally { queuedFrame.close(); }
                    }
                }
                for (DataState other : dataByService.values()) {
                    if (other != state && (other.connection == null || other.connection.connectionId == event.connectionId)) {
                        if (!other.pendingFrames.isEmpty()) {
                            List<PendingFrame> queued = new ArrayList<>(other.pendingFrames);
                            other.pendingFrames.clear();
                            for (PendingFrame queuedFrame : queued) {
                                try {
                                    dataChannelEngine.sendSocketPairFrame(other, queuedFrame.bytes, queuedFrame.fragmentedMessageId, output);
                                } finally { queuedFrame.close(); }
                            }
                        }
                    }
                }
                if (control != null && control.remoteHelloReceived()) {
                    dataChannelEngine.startPendingDataConnectors(output);
                }
            }
            default -> output.events.add(
                    SessionEvent.transport(
                            event));
        }
    }































    void sendApplication(
            long connectionId,
            byte[] bytes,
            OutputBuilder output) {
        try (IdsServiceConnectorCoordinator.PacketBatch sent =
                     transport.sendApplication(
                             connectionId,
                             bytes)) {
            addPackets(
                    output,
                    sent.packets());
        }
    }



    void resetControlState() {
        if (control != null) {
            control.close();
            control = null;
        }
        controlConnectionId = 0;
        controlOpenRequested = false;
    }

    private void closeDataStates() {
        List<DataState> states =
                new ArrayList<>(
                        dataByService.values());
        dataByService.clear();
        dataByConnection.clear();
        for (DataState state :
                states) {
            state.close();
        }
        dataJoins.close();
        dataJoins =
                newJoinState();
    }

    IdsDataChannelJoinState<DataTransport> newJoinState() {
        return new IdsDataChannelJoinState<>(
                dataChannelEngine::releaseDataTransport);
    }

    static boolean isRelayLaneD(String service) {
        if (service == null) {
            return false;
        }
        if (NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(service)
                || PairedSyncCodec.isService(service)
                || "nano-class-d".equals(service)) {
            return true;
        }
        return IdsUtunConnectionName.isClassDIdentifier(service)
                && !IdsUtunConnectionName.isClassCIdentifier(service);
    }

    static boolean isRelayLaneC(String service) {
        if (service == null) {
            return false;
        }
        if (NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(service)
                || PbBridgeCodec.SERVICE.equals(service)
                || "nano-class-c".equals(service)) {
            return true;
        }
        return IdsUtunConnectionName.isClassCIdentifier(service)
                && !IdsUtunConnectionName.isClassDIdentifier(service);
    }

    static boolean serviceMatches(String requested, String candidate) {
        if (requested == null || candidate == null) {
            return false;
        }
        if (requested.equals(candidate)) {
            return true;
        }
        if (candidate.equals(requested + "-Relay") || requested.equals(candidate + "-Relay")) {
            return true;
        }
        // Cloud and ordinary delivery use different connector namespaces and
        // listeners, even when priority and protection class are identical.
        // A fallback must never attach a Cloud socket to an ordinary lane.
        if (isCloudLane(requested) != isCloudLane(candidate)) {
            return false;
        }
        if (isRelayLaneC(candidate) && isRelayLaneC(requested)) {
            return true;
        }
        if (isRelayLaneD(candidate) && isRelayLaneD(requested)) {
            return true;
        }
        if (isRelayLaneC(candidate)
                && (NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(requested)
                || PbBridgeCodec.SERVICE.equals(requested))) {
            return true;
        }
        if (isRelayLaneD(candidate)
                && (NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(requested)
                || PairedSyncCodec.PREFERRED_SERVICE.equals(requested))) {
            return true;
        }
        return false;
    }

    static boolean isCloudLane(String service) {
        String name = IdsUtunConnectionName.lastComponent(service);
        if (name.endsWith("-Relay")) name = name.substring(0, name.length() - 6);
        return IdsUtunConnectionName.isDefaultPairedCloudIdentifier(name);
    }

    DataState requireJoinedDataState(
            String service) {
        DataState state =
                dataByService.get(
                        service);
        if (state != null && state.stateClosed) {
            state = null;
        }
        if (state == null) {
            for (DataState candidate : dataByService.values()) {
                if (!candidate.stateClosed
                        && serviceMatches(service, candidate.service)) {
                    state = candidate;
                    break;
                }
            }
        }
        // Creating the lane for this exact connector must be preferred over
        // borrowing an unrelated one: protection classes C and D are separate
        // connectors, and parking a Class-C service such as PBBridge on the
        // Class-D lane leaves it queued behind a connection it never gets.
        if (state == null && directMessagingSupported) {
            IdsServiceConnectorName parsed =
                    IdsServiceConnectorName.parseLenient(service);
            IdsIpsecServiceRoute route =
                    IdsIpsecServiceRoute.localDelivery(parsed);
            IdsEncryptedDataChannelSession setup =
                    IdsEncryptedDataChannelSession.openOutgoing(
                            setupPorts,
                            ssrcs,
                            random,
                            parsed);
            state = new DataState(this, service, route, setup);
            dataByService.put(service, state);
        }
        if (state == null || state.stateClosed) {
            throw new IllegalStateException(
                    "IDS data lane is not joined for " + service);
        }
        return state;
    }

    void requireControlReady() {
        if (control == null && directMessagingSupported) {
            control = IdsControlChannelSession.createDefaultPaired(
                    random,
                    localIdsDeviceUuid,
                    directMessagingSupported,
                    IdsServiceMapState.APPLE_FIRST_STREAM_ID);
        }
        if (!directMessagingSupported && (control == null
                || controlConnectionId == 0
                || !control.remoteHelloReceived())) {
            throw new IllegalStateException(
                    "IDS peer Hello has not completed");
        }
    }

    RuntimeException fail(
            RuntimeException failure) {
        if (!poisoned
                && !closed) {
            poisoned = true;
            closing = true;
            try {
                if (control != null) {
                    control.close();
                    control = null;
                }
                for (DataState state :
                        new ArrayList<>(
                                dataByService.values())) {
                    state.close();
                }
                dataByService.clear();
                dataByConnection.clear();
                dataJoins.close();
                dataTransports.clear();
                closePackets(
                        deferredPackets);
                transport.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(
                        closeFailure);
            } finally {
                closing = false;
            }
        }
        return failure;
    }

    void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS modern session is closed");
        }
        if (poisoned) {
            throw new IllegalStateException(
                    "IDS modern session is poisoned");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closing = true;
        outgoingFrameObserver = null;
        RuntimeException failure = null;
        try {
            if (control != null) {
                control.close();
                control = null;
            }
            for (DataState state :
                    new ArrayList<>(
                            dataByService.values())) {
                try {
                    state.close();
                } catch (RuntimeException closeFailure) {
                    failure =
                            combine(
                                    failure,
                                    closeFailure);
                }
            }
            dataByService.clear();
            dataByConnection.clear();
            try {
                dataJoins.close();
            } catch (RuntimeException closeFailure) {
                failure =
                        combine(
                                failure,
                                closeFailure);
            }
            dataTransports.clear();
            closePackets(
                    deferredPackets);
            try {
                transport.close();
            } catch (RuntimeException closeFailure) {
                failure =
                        combine(
                                failure,
                                closeFailure);
            }
            setupPorts.close();
            ssrcs.close();
            applicationMessages.close();
        } finally {
            closing = false;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException combine(
            RuntimeException first,
            RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(
                next);
        return first;
    }

    static boolean isStaleCollisionPacket(
            IllegalArgumentException failure) {
        if (failure == null || failure.getMessage() == null) {
            return false;
        }
        String msg = failure.getMessage();
        return msg.equals("Inbound IDS TCP SYN targets no listener")
                || msg.equals("Unknown IDS TCP tuple did not start with a SYN")
                || msg.contains("Unknown IDS TCP connection");
    }

    static boolean sameRoute(
            IdsIpsecServiceRoute first,
            IdsIpsecServiceRoute second) {
        return first != null
                && second != null
                && first.service.equals(
                        second.service)
                && first.connector
                == second.connector
                && first.listenerPort
                == second.listenerPort
                && first.networkRelayDataClass
                == second.networkRelayDataClass
                && first.allowsQuickRelay
                == second.allowsQuickRelay;
    }

    static void requireCanonicalUuid(
            String value,
            String label) {
        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is absent");
        }
        try {
            UUID parsed =
                    UUID.fromString(
                            value);
            if (!parsed.toString()
                    .equalsIgnoreCase(
                            value)) {
                throw new IllegalArgumentException(
                        label + " is not canonical");
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    label + " is invalid",
                    invalid);
        }
    }

    static void addPacket(
            OutputBuilder output,
            IdsServiceConnectorCoordinator.RoutedPacket packet) {
        try {
            output.packets.add(
                    routedPacket(
                            packet));
        } finally {
            packet.close();
        }
    }

    static void addPackets(
            OutputBuilder output,
            List<IdsServiceConnectorCoordinator.RoutedPacket> packets) {
        try {
            for (IdsServiceConnectorCoordinator.RoutedPacket packet :
                    packets) {
                output.packets.add(
                        routedPacket(
                                packet));
            }
        } finally {
            closeTransportPackets(
                    packets);
        }
    }

    static RoutedPacket routedPacket(
            IdsServiceConnectorCoordinator.RoutedPacket packet) {
        byte[] bytes =
                packet.clearIpv6Packet();
        try {
            return new RoutedPacket(
                    packet.dataClass,
                    bytes);
        } finally {
            wipe(
                    bytes);
        }
    }

    void drainDeferredPackets(
            OutputBuilder output) {
        output.packets.addAll(
                deferredPackets);
        deferredPackets.clear();
    }

    static void closeTransportPackets(
            List<IdsServiceConnectorCoordinator.RoutedPacket> packets) {
        for (IdsServiceConnectorCoordinator.RoutedPacket packet :
                packets) {
            packet.close();
        }
        packets.clear();
    }

    private static void closePackets(
            List<RoutedPacket> packets) {
        for (RoutedPacket packet :
                packets) {
            packet.close();
        }
        packets.clear();
    }

    private static void closeEvents(
            List<SessionEvent> events) {
        for (SessionEvent event :
                events) {
            event.close();
        }
        events.clear();
    }

    static void destroyControlMessages(
            List<IdsControlChannelCodec.Message> messages) {
        for (IdsControlChannelCodec.Message message :
                messages) {
            message.destroy();
        }
    }

    static int controlMessageLength(
            IdsControlChannelCodec.Message message) {
        if (message instanceof IdsControlChannelCodec.GenericControlMessage generic) {
            return generic.rawPayload.length;
        }
        if (message instanceof IdsControlChannelCodec.DirectMsgInfoMessage info) {
            return info.info.length;
        }
        return -1;
    }

    static String controlMessageDetail(
            IdsControlChannelCodec.Message message) {
        if (message instanceof IdsControlChannelCodec.DirectMsgInfoMessage info) {
            return info.capabilitySummary();
        }
        if (message instanceof IdsControlChannelCodec.SetupChannelMessage setup) {
            return setup.account
                    + "/"
                    + setup.service
                    + "/"
                    + setup.name;
        }
        if (message instanceof IdsControlChannelCodec
                .SetupEncryptedChannelMessage setup) {
            return setup.account
                    + "/"
                    + setup.service
                    + "/"
                    + setup.name;
        }
        return null;
    }

    static void destroySocketMessages(
            List<IdsSocketPairCodec.Message> messages) {
        for (IdsSocketPairCodec.Message message :
                messages) {
            message.destroy();
        }
    }

    static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static void wipeAll(
            List<byte[]> values) {
        for (byte[] value :
                values) {
            wipe(
                    value);
        }
        values.clear();
    }

    static final class MessageMetadata {
        final long sequence;
        final String messageUuid;
        final int flagsWithoutTopic;
        final String peerResponseIdentifier;
        final Long expirySeconds;
        final Long fragmentedMessageId;

        MessageMetadata(
                long sequence,
                String messageUuid,
                int flagsWithoutTopic,
                String peerResponseIdentifier,
                Long expirySeconds,
                Long fragmentedMessageId) {
            if (sequence < 0
                    || sequence > 0xffff_ffffL) {
                throw new IllegalArgumentException(
                        "IDS message sequence must fit uint32");
            }
            requireCanonicalUuid(
                    messageUuid,
                    "IDS message UUID");
            if ((flagsWithoutTopic
                    & IdsSocketPairCodec.FLAG_HAS_TOPIC) != 0
                    || (flagsWithoutTopic
                    & ~IdsSocketPairCodec.KNOWN_DATA_FLAGS) != 0) {
                throw new IllegalArgumentException(
                        "IDS caller flags must omit managed topic flag");
            }
            if (expirySeconds != null
                    && (expirySeconds < 0
                    || expirySeconds > 0xffff_ffffL)) {
                throw new IllegalArgumentException(
                        "IDS expiry must fit uint32");
            }
            if (fragmentedMessageId != null
                    && (fragmentedMessageId < 0
                    || fragmentedMessageId > 0xffff_ffffL)) {
                throw new IllegalArgumentException(
                        "IDS fragment ID must fit uint32");
            }
            this.sequence = sequence;
            this.messageUuid = messageUuid;
            this.flagsWithoutTopic =
                    flagsWithoutTopic;
            this.peerResponseIdentifier =
                    peerResponseIdentifier;
            this.expirySeconds =
                    expirySeconds;
            this.fragmentedMessageId =
                    fragmentedMessageId;
        }

        /**
         * Builds metadata for an IDS application response.
         *
         * <p>Apple names the value exposed to the receiving application
         * {@code outgoingResponseIdentifier}. On the socket-pair wire it is
         * the request's {@code messageUUID}, and the responder sends that
         * value back as {@code peerResponseIdentifier}.</p>
         */
        static MessageMetadata responseTo(
                long sequence,
                String messageUuid,
                SessionEvent request) {
            if (request == null) {
                throw new IllegalArgumentException(
                        "IDS response request event is absent");
            }
            return responseToIdentifier(
                    sequence,
                    messageUuid,
                    request.outgoingResponseIdentifier());
        }

        static MessageMetadata responseToIdentifier(
                long sequence,
                String messageUuid,
                String outgoingResponseIdentifier) {
            requireCanonicalUuid(
                    outgoingResponseIdentifier,
                    "IDS outgoing response identifier");
            return new MessageMetadata(
                    sequence,
                    messageUuid,
                    0,
                    outgoingResponseIdentifier,
                    null,
                    null);
        }
    }

    static final class Snapshot {
        final boolean controlAccepted;
        final boolean peerHelloReceived;
        final int dataSessionCount;
        final int joinedDataCount;
        final int setupDynamicPortCount;
        final int setupSsrcCount;
        final int transportDynamicPortCount;
        final PairedSyncCapabilityState.Status
                pairedSyncCapabilityStatus;

        private Snapshot(
                boolean controlAccepted,
                boolean peerHelloReceived,
                int dataSessionCount,
                int joinedDataCount,
                int setupDynamicPortCount,
                int setupSsrcCount,
                int transportDynamicPortCount,
                PairedSyncCapabilityState.Status
                        pairedSyncCapabilityStatus) {
            this.controlAccepted =
                    controlAccepted;
            this.peerHelloReceived =
                    peerHelloReceived;
            this.dataSessionCount =
                    dataSessionCount;
            this.joinedDataCount =
                    joinedDataCount;
            this.setupDynamicPortCount =
                    setupDynamicPortCount;
            this.setupSsrcCount =
                    setupSsrcCount;
            this.transportDynamicPortCount =
                    transportDynamicPortCount;
            this.pairedSyncCapabilityStatus =
                    pairedSyncCapabilityStatus;
        }
    }

    static final class DataSnapshot {
        final String service;
        final int setupLocalPort;
        final int setupRemotePort;
        final long connectionId;
        final int tcpLocalPort;
        final int tcpRemotePort;
        final boolean setupEstablished;
        final boolean joined;

        private DataSnapshot(
                String service,
                int setupLocalPort,
                int setupRemotePort,
                long connectionId,
                int tcpLocalPort,
                int tcpRemotePort,
                boolean setupEstablished,
                boolean joined) {
            this.service = service;
            this.setupLocalPort =
                    setupLocalPort;
            this.setupRemotePort =
                    setupRemotePort;
            this.connectionId =
                    connectionId;
            this.tcpLocalPort =
                    tcpLocalPort;
            this.tcpRemotePort =
                    tcpRemotePort;
            this.setupEstablished =
                    setupEstablished;
            this.joined = joined;
        }
    }

    static final class Output
            implements AutoCloseable {
        private final List<RoutedPacket> packets;
        private final List<SessionEvent> events;
        private boolean closed;

        Output(
                List<RoutedPacket> packets,
                List<SessionEvent> events) {
            this.packets =
                    packets;
            this.events =
                    events;
        }

        List<RoutedPacket> packets() {
            requireOpen();
            return Collections.unmodifiableList(
                    packets);
        }

        List<SessionEvent> events() {
            requireOpen();
            return Collections.unmodifiableList(
                    events);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS session output is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closePackets(
                    packets);
            closeEvents(
                    events);
        }
    }

    static final class RoutedPacket
            implements AutoCloseable {
        final OrdinaryIkeAuth.DataClass dataClass;
        private byte[] clearIpv6Packet;
        private boolean closed;

        RoutedPacket(
                OrdinaryIkeAuth.DataClass dataClass,
                byte[] clearIpv6Packet) {
            if (dataClass == null
                    || clearIpv6Packet == null) {
                throw new IllegalArgumentException(
                        "IDS routed packet is incomplete");
            }
            this.dataClass =
                    dataClass;
            this.clearIpv6Packet =
                    clearIpv6Packet.clone();
        }

        byte[] clearIpv6Packet() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS routed packet is closed");
            }
            return clearIpv6Packet.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    clearIpv6Packet);
            clearIpv6Packet =
                    new byte[0];
        }
    }

    static final class SessionEvent
            implements AutoCloseable {
        final EventType type;
        final long connectionId;
        final String service;
        final String topic;
        final IdsServiceConnectorCoordinator.EventType transportEvent;
        final IdsControlChannelSession.Compatibility compatibility;
        final long sequence;
        final int streamId;
        final int command;
        final int flags;
        final int protobufType;
        final boolean response;
        final String messageUuid;
        final String peerResponseIdentifier;
        private byte[] payload;
        private boolean closed;

        SessionEvent(
                EventType type,
                long connectionId,
                String service,
                String topic,
                IdsServiceConnectorCoordinator.EventType transportEvent,
                IdsControlChannelSession.Compatibility compatibility,
                long sequence,
                int streamId,
                int command,
                int flags,
                int protobufType,
                boolean response,
                String messageUuid,
                String peerResponseIdentifier,
                byte[] payload) {
            this.type = type;
            this.connectionId =
                    connectionId;
            this.service = service;
            this.topic = topic;
            this.transportEvent =
                    transportEvent;
            this.compatibility =
                    compatibility;
            this.sequence = sequence;
            this.streamId = streamId;
            this.command = command;
            this.flags = flags;
            this.protobufType =
                    protobufType;
            this.response = response;
            this.messageUuid =
                    messageUuid;
            this.peerResponseIdentifier =
                    peerResponseIdentifier;
            this.payload =
                    payload == null
                            ? new byte[0]
                            : payload.clone();
        }

        static SessionEvent simple(
                EventType type,
                long connectionId,
                String service,
                String topic) {
            return new SessionEvent(
                    type,
                    connectionId,
                    service,
                    topic,
                    null,
                    null,
                    0,
                    0,
                    -1,
                    0,
                    -1,
                    false,
                    null,
                    null,
                    null);
        }

        SessionEvent copy() {
            if (closed) throw new IllegalStateException("IDS session event is closed");
            return new SessionEvent(type, connectionId, service, topic, transportEvent,
                    compatibility, sequence, streamId, command, flags, protobufType,
                    response, messageUuid, peerResponseIdentifier, payload);
        }

        static SessionEvent controlMessage(
                long connectionId,
                int type,
                int length,
                String detail) {
            String topic =
                    IdsControlChannelCodec.typeName(
                            type);
            if (detail != null
                    && !detail.isEmpty()) {
                topic =
                        topic
                                + "/"
                                + detail;
            }
            return new SessionEvent(
                    EventType.CONTROL_MESSAGE,
                    connectionId,
                    IdsIpsecServiceRoute.CONTROL_SERVICE,
                    topic,
                    null,
                    null,
                    length,
                    0,
                    -1,
                    0,
                    type,
                    false,
                    null,
                    null,
                    null);
        }

        static SessionEvent controlReady(
                long connectionId,
                IdsControlChannelSession.Compatibility compatibility) {
            return new SessionEvent(
                    EventType.CONTROL_READY,
                    connectionId,
                    IdsIpsecServiceRoute.CONTROL_SERVICE,
                    null,
                    null,
                    compatibility,
                    0,
                    0,
                    -1,
                    0,
                    -1,
                    false,
                    null,
                    null,
                    null);
        }

        static SessionEvent transport(
                IdsServiceConnectorCoordinator.ConnectionEvent event) {
            return new SessionEvent(
                    EventType.TRANSPORT_EVENT,
                    event.connectionId,
                    event.serviceName,
                    null,
                    event.type,
                    null,
                    0,
                    0,
                    -1,
                    0,
                    -1,
                    false,
                    null,
                    null,
                    null);
        }

        static SessionEvent protobuf(
                EventType type,
                long connectionId,
                String service,
                String topic,
                IdsSocketPairCodec.ProtobufMessage message) {
            return new SessionEvent(
                    type,
                    connectionId,
                    service,
                    topic,
                    null,
                    null,
                    message.sequence,
                    message.streamId,
                    message.command,
                    message.flags,
                    message.protobufType,
                    message.response,
                    message.messageUuid,
                    message.peerResponseIdentifier,
                    message.payload);
        }

        static SessionEvent data(
                long connectionId,
                String service,
                String topic,
                IdsSocketPairCodec.DataMessage message) {
            return data(EventType.DATA_RECEIVED, connectionId, service, topic, message);
        }

        static SessionEvent data(EventType type, long connectionId, String service,
                                         String topic, IdsSocketPairCodec.DataMessage message) {
            return new SessionEvent(
                    type,
                    connectionId,
                    service,
                    topic,
                    null,
                    null,
                    message.sequence,
                    message.streamId,
                    message.command,
                    message.flags,
                    -1,
                    false,
                    message.messageUuid,
                    message.peerResponseIdentifier,
                    message.payload);
        }

        static SessionEvent appAck(
                EventType type,
                long connectionId,
                String service,
                String topic,
                IdsSocketPairCodec.AppAckMessage message) {
            if (type != EventType.APP_ACK_SENT
                    && type != EventType.APP_ACK_RECEIVED) {
                throw new IllegalArgumentException(
                        "IDS AppAck event type is invalid");
            }
            return new SessionEvent(
                    type,
                    connectionId,
                    service,
                    topic,
                    null,
                    null,
                    message.sequence,
                    message.streamId,
                    message.command,
                    0,
                    -1,
                    false,
                    null,
                    message.peerResponseIdentifier,
                    null);
        }

        static SessionEvent ack(
                long connectionId,
                String service,
                IdsSocketPairCodec.AckMessage message) {
            return new SessionEvent(
                    EventType.ACK_RECEIVED,
                    connectionId,
                    service,
                    null,
                    null,
                    null,
                    message.sequence,
                    0,
                    message.command,
                    0,
                    -1,
                    false,
                    null,
                    null,
                    null);
        }

        int payloadLength() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS session event is closed");
            }
            return payload.length;
        }

        byte[] payload() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS session event is closed");
            }
            return payload.clone();
        }

        /**
         * Returns the runtime identifier that must be echoed by a response.
         */
        String outgoingResponseIdentifier() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS session event is closed");
            }
            if ((type != EventType.PROTOBUF_RECEIVED
                    && type != EventType.DATA_RECEIVED)
                    || response
                    || (type == EventType.DATA_RECEIVED && (flags
                    & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE) == 0)) {
                throw new IllegalStateException(
                        "IDS event is not an incoming response request");
            }
            requireCanonicalUuid(
                    messageUuid,
                    "IDS incoming request UUID");
            return messageUuid;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    payload);
            payload =
                    new byte[0];
        }
    }

    static final class DataTransport {
        private final IdsModernSessionCoordinator owner;
        final long connectionId;
        String service;
        boolean transportGone;
        boolean released;

        DataTransport(
                IdsModernSessionCoordinator owner,
                long connectionId,
                String service) {
            this.owner = owner;
            this.connectionId =
                    connectionId;
            this.service = service;
        }

        void release() {
            owner.dataChannelEngine.releaseDataTransport(
                    this);
        }
    }

    static final class PendingFrame implements AutoCloseable {
        final byte[] bytes;
        final Long fragmentedMessageId;
        PendingFrame(byte[] bytes, Long fragmentedMessageId) {
            this.bytes = bytes.clone();
            this.fragmentedMessageId = fragmentedMessageId;
        }
        @Override public void close() { wipe(bytes); }
    }

    static final class DataState
            implements AutoCloseable {
        private final IdsModernSessionCoordinator owner;
        final String service;
        final IdsIpsecServiceRoute route;
        final IdsEncryptedDataChannelSession setup;
        final IdsSocketPairCodec.StreamDecoder decoder =
                new IdsSocketPairCodec.StreamDecoder();
        final IdsSocketPairCodec.FragmentReassembler fragments =
                new IdsSocketPairCodec.FragmentReassembler();
        final List<PendingFrame> pendingFrames =
                new ArrayList<>();
        DataTransport connection;
        boolean needsReplacement;
        boolean awaitIncomingConnector;
        boolean handshakeSent;
        boolean handshakeReceived;
        boolean stateClosed;

        DataState(
                IdsModernSessionCoordinator owner,
                String service,
                IdsIpsecServiceRoute route,
                IdsEncryptedDataChannelSession setup) {
            this.owner = owner;
            this.service = service;
            this.route = route;
            this.setup = setup;
        }

        void resetTransport() {
            decoder.reset();
            fragments.reset();
        }

        @Override
        public void close() {
            if (stateClosed) {
                return;
            }
            stateClosed = true;
            pendingFrames.forEach(PendingFrame::close);
            pendingFrames.clear();
            decoder.close();
            fragments.close();
            setup.close();
            if (connection != null) {
                DataTransport owned =
                        connection;
                connection = null;
                owned.release();
            }
        }
    }

    static final class OutputBuilder
            implements AutoCloseable {
        final List<RoutedPacket> packets =
                new ArrayList<>();
        final List<SessionEvent> events =
                new ArrayList<>();
        boolean transferred;

        Output build() {
            if (transferred) {
                throw new IllegalStateException(
                        "IDS output builder was already transferred");
            }
            transferred = true;
            return new Output(
                    packets,
                    events);
        }

        @Override
        public void close() {
            if (transferred) {
                return;
            }
            closePackets(
                    packets);
            closeEvents(
                    events);
        }
    }
}
