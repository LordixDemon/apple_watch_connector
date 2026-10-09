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
    private static final int MAX_CONTROL_TRANSIENT_RETRIES = 6;

    /** Optional live-session diagnostics hook (set by the root HAL host). */
    static volatile java.util.function.Consumer<String> diagnosticLogger;

    private final SecureRandom random;
    private final IdsApplicationMessageAllocator applicationMessages;
    private final IdsServiceConnectorCoordinator transport;
    private final IdsPortMap setupPorts;
    private final IdsSsrcMap ssrcs =
            new IdsSsrcMap();
    private final String localIdsDeviceUuid;

    String localApplicationDeviceId() { return localIdsDeviceUuid; }
    private final boolean directMessagingSupported;
    private final Map<String, DataState> dataByService =
            new LinkedHashMap<>();
    private final Map<Long, DataTransport> dataTransports =
            new LinkedHashMap<>();
    private final Map<Long, DataState> dataByConnection =
            new LinkedHashMap<>();
    private final List<RoutedPacket> deferredPackets =
            new ArrayList<>();
    private final PairedSyncCapabilityState pairedSyncCapability =
            new PairedSyncCapabilityState();

    private IdsDataChannelJoinState<DataTransport> dataJoins;
    private IdsControlChannelSession control;
    private long controlConnectionId;
    private boolean controlOpenRequested;
    private int controlTransientRetries;
    private boolean closing;
    private boolean poisoned;
    private boolean closed;
    @FunctionalInterface
    interface OutgoingFrameObserver {
        void record(String kind, String topic, int type, boolean response, byte[] frame);
    }
    private OutgoingFrameObserver outgoingFrameObserver;

    synchronized void setOutgoingFrameObserver(OutgoingFrameObserver observer) {
        requireUsable();
        outgoingFrameObserver = observer;
    }

    private void observeOutgoingFrame(String kind, String topic, int type, boolean response, byte[] frame) {
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
    private long nextFragmentedMessageId;

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

    synchronized Output startControl() {
        requireUsable();        long active = activeControlConnection();
        if (active != 0 && transport.isServiceActive(active)) {
            OutputBuilder output = new OutputBuilder();
            if (control != null && control.remoteHello() != null) {
                output.events.add(
                        SessionEvent.controlReady(
                                active,
                                control.compatibility()));
            }
            return output.build();
        }
        if (controlOpenRequested
                || controlConnectionId != 0) {
            throw new IllegalStateException(
                    "IDS control connection was already started");
        }
        OutputBuilder output =
                new OutputBuilder();
        try (IdsServiceConnectorCoordinator.OpenResult opened =
                     transport.openService(
                             IdsIpsecServiceRoute.control())) {
            addPacket(
                    output,
                    opened.packet());
            controlOpenRequested = true;
            controlConnectionId = opened.connectionId;
            output.events.add(
                    SessionEvent.simple(
                            EventType.CONTROL_CONNECT_STARTED,
                            opened.connectionId,
                            IdsIpsecServiceRoute.CONTROL_SERVICE,
                            null));
            drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw failure;
        }
    }

    /**
     * Emits an initial type-6 Setup for one proven NanoRegistry lane.
     *
     * <p>After Hello the phone sends Setup on the control TCP and waits for
     * the peer ACK before SYNing Watch:61314. The 2.5s L2CAP poll must not
     * SYN early: live Watch then reverse-probes an unclaimed service-available
     * block, ACCEPTED lands late on a dead tuple, and ACL 0x13 follows.</p>
     */
    synchronized Output startInitialLane(
            String topic) {
        requireUsable();
        requireControlReady();
        NanoRegistryInitialIdsRoute lane =
                NanoRegistryInitialIdsRoute.forTopic(
                        topic);
        String service =
                lane.serviceConnectorName.encode();
        OutputBuilder output =
                new OutputBuilder();
        if (dataByService.containsKey(
                service)) {
            DataState existing = dataByService.get(service);
            if (existing != null && existing.connection != null) {
                output.events.add(
                        SessionEvent.simple(
                                EventType.DATA_CHANNEL_JOINED,
                                existing.connection.connectionId,
                                service,
                                null));
            }
            return output.build();
        }

        IdsEncryptedDataChannelSession setup =
                IdsEncryptedDataChannelSession.openOutgoing(
                        setupPorts,
                        ssrcs,
                        random,
                        lane.serviceConnectorName);
        DataState state =
                new DataState(
                        service,
                        lane.ipsecRoute,
                        setup);
        IdsControlChannelCodec.SetupEncryptedChannelMessage message =
                null;
        byte[] frame = null;
        boolean transferred = false;
        try {
            message =
                    setup.startSetup();
            OrdinaryIkeAuth.DataClass dc =
                    lane.idsProtectionClass == IdsUtunConnectionName.PROTECTION_CLASS_D
                            ? OrdinaryIkeAuth.DataClass.CLASS_D
                            : OrdinaryIkeAuth.DataClass.CLASS_C;
            transport.listenDataPort(
                    dc,
                    message.localPort);
            frame =
                    IdsControlChannelCodec.encodeFramed(
                            message);
            long active = activeControlConnection();
            if (active != 0 && transport.isServiceActive(active)) {
                sendApplication(
                        active,
                        frame,
                        output);
                output.events.add(
                        SessionEvent.simple(
                                EventType.DATA_SETUP_SENT,
                                active,
                                service,
                                topic));
            }
            dataByService.put(
                    service,
                    state);
            // Remember the Setup so an inbound NWSC can still join. JOINED
            // is emitted only after SERVICE_ACCEPTED attaches a transport.
            dataJoins.receiveSetup(
                    lane.serviceConnectorName);
            // iOS/watchOS: Setup first, then nw_service_connector_start_request.
            // Same-burst or poll-fallback SYN to 61314 races Watch's post-Hello
            // DirectMsgInfo and leaves the service-available block unclaimed.
            transferred = true;
            drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw fail(
                    failure);
        } finally {
            if (message != null) {
                message.destroy();
            }
            wipe(
                    frame);
            if (!transferred) {
                state.close();
            }
        }
    }

    synchronized Output sendControlMessage(
            IdsControlChannelCodec.Message message) {
        requireUsable();
        requireControlReady();
        if (message == null) {
            throw new IllegalArgumentException(
                    "IDS control message is absent");
        }
        OutputBuilder output =
                new OutputBuilder();
        byte[] frame =
                IdsControlChannelCodec.encodeFramed(
                        message);
        try {
            sendApplication(
                    activeControlConnection(),
                    frame,
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw fail(
                    failure);
        } finally {
            wipe(
                    frame);
        }
    }

    synchronized Output startOutgoingService(
            IdsServiceConnectorName name) {
        requireUsable();
        requireControlReady();
        if (name == null) {
            throw new IllegalArgumentException(
                    "IDS service-connector name is absent");
        }
        String service =
                name.encode();
        DataState state =
                dataByService.get(
                        service);
        OutputBuilder output =
                new OutputBuilder();
        try {
            if (state == null) {
                state =
                        new DataState(
                                service,
                                IdsIpsecServiceRoute.localDelivery(
                                        name),
                                IdsEncryptedDataChannelSession.openOutgoing(
                                        setupPorts,
                                        ssrcs,
                                        random,
                                        name));
                dataByService.put(
                        service,
                        state);
            }
            startDataConnector(
                    state,
                    output);
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

    synchronized Output sendClassD(
            NanoRegistryClassDCodec.ApplicationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class-D message is absent");
        }
        message.requireValid();
        if (message.response()) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class-D response needs a peer identifier");
        }
        // NanoRegistry sendRequest means IDSProtobuf.isResponse=false.
        // Its options do not set ExpectsPeerResponse or WantsClientAck;
        // _IDSConnection reads the absent options as false (23G71).
        return sendClassD(
                applicationMessages.nextOneWay(),
                message);
    }

    synchronized Output sendClassDResponse(
            NanoRegistryClassDCodec.ApplicationMessage message,
            String outgoingResponseIdentifier) {
        if (message == null
                || !message.response()) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class-D response is invalid");
        }
        message.requireValid();
        return sendClassD(
                applicationMessages.nextResponseToIdentifier(
                        outgoingResponseIdentifier),
                message);
    }

    synchronized Output sendClassDRequestWithClientAcknowledgement(
            NanoRegistryClassDCodec.ApplicationMessage message) {
        if (message == null || message.response()) {
            throw new IllegalArgumentException("Expected a NanoRegistry Class-D request");
        }
        return sendClassD(applicationMessages.nextRequestExpectingPeerResponseAndAppAck(), message);
    }

    synchronized Output sendClassD(
            MessageMetadata metadata,
            NanoRegistryClassDCodec.ApplicationMessage message) {
        return sendNanoRegistry(
                NanoRegistryPropertyCodec.CLASS_D_SERVICE,
                metadata,
                message,
                null);
    }

    synchronized Output sendClassC(
            NanoRegistryPropertyCodec.ApplicationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class-C message is absent");
        }
        if (message.response()) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class-C response needs a peer identifier");
        }
        return sendClassC(
                classCRequestMetadata(
                        message),
                message);
    }

    synchronized Output sendClassCResponse(
            NanoRegistryPropertyCodec.ApplicationMessage message,
            String outgoingResponseIdentifier) {
        if (message == null
                || !message.response()) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class-C response is invalid");
        }
        return sendClassC(
                applicationMessages.nextResponseToIdentifier(
                        outgoingResponseIdentifier),
                message);
    }

    synchronized Output sendClassC(
            MessageMetadata metadata,
            NanoRegistryPropertyCodec.ApplicationMessage message) {
        return sendNanoRegistry(
                NanoRegistryPropertyCodec.CLASS_C_SERVICE,
                metadata,
                null,
                message);
    }

    /**
     * Native NanoRegistry requests carry isResponse=false in the protobuf
     * header. That is independent of the optional socket-pair reply/ACK flags.
     */
    private MessageMetadata classCRequestMetadata(
            NanoRegistryPropertyCodec.ApplicationMessage message) {
        if (message instanceof NanoRegistryPropertyCodec.PropertyRequest) {
            return applicationMessages.nextRequestExpectingPeerResponse();
        }
        return applicationMessages.nextOneWay();
    }

    synchronized Output sendPbBridge(
            PbBridgeCodec.ApplicationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "PBBridge application message is absent");
        }
        message.requireValid();
        if (message.response()) {
            throw new IllegalArgumentException(
                    "PBBridge response needs a peer identifier");
        }
        MessageMetadata metadata =
                message instanceof PbBridgeCodec.PrepareInitialSync
                        ? applicationMessages
                        .nextRequestExpectingPeerResponse()
                        : applicationMessages.nextOneWay();
        return sendPbBridge(
                metadata,
                message);
    }

    /**
     * The Watch's ProxyActivation fetch is an IDS request. The protobuf
     * itself stays {@code isResponse=false} (type 2 is a request in both
     * directions), but the socket-pair envelope must name the fetch's
     * message UUID or the Watch's completion never runs.
     */
    synchronized Output sendPbBridgeReplyToFetch(
            PbBridgeCodec.ApplicationMessage message,
            String watchMessageUuid) {
        if (message == null
                || message.response()) {
            throw new IllegalArgumentException(
                    "Activation data reply must stay a protobuf request");
        }
        message.requireValid();
        return sendPbBridge(
                applicationMessages.nextResponseToIdentifier(
                        watchMessageUuid),
                message);
    }

    synchronized Output sendPbBridgeResponse(
            PbBridgeCodec.ApplicationMessage message,
            String outgoingResponseIdentifier) {
        if (message == null
                || !message.response()) {
            throw new IllegalArgumentException(
                    "PBBridge response is invalid");
        }
        message.requireValid();
        return sendPbBridge(
                applicationMessages.nextResponseToIdentifier(
                        outgoingResponseIdentifier),
                message);
    }

    synchronized Output sendPbBridge(
            MessageMetadata metadata,
            PbBridgeCodec.ApplicationMessage message) {
        requireUsable();
        requireControlReady();
        if (message == null) {
            throw new IllegalArgumentException(
                    "PBBridge application message is absent");
        }
        if (message.response()
                && (metadata == null
                || metadata.peerResponseIdentifier == null
                || metadata.peerResponseIdentifier.isEmpty())) {
            throw new IllegalArgumentException(
                    "PBBridge response requires the peer runtime "
                            + "response identifier");
        }
        if (message.response()) {
            requireCanonicalUuid(
                    metadata.peerResponseIdentifier,
                    "PBBridge outgoing response identifier");
            if ((metadata.flagsWithoutTopic
                    & IdsSocketPairCodec
                    .FLAG_EXPECTS_PEER_RESPONSE) != 0) {
                throw new IllegalArgumentException(
                        "PBBridge response cannot expect another response");
            }
        }
        if (message instanceof PbBridgeCodec.PrepareInitialSync
                && (metadata == null
                || (metadata.flagsWithoutTopic
                & IdsSocketPairCodec
                .FLAG_EXPECTS_PEER_RESPONSE) == 0)) {
            throw new IllegalArgumentException(
                    "PBBridge initial-sync preparation must expect "
                            + "a peer response");
        }
        byte[] payload =
                PbBridgeCodec.encode(
                        message);
        try {
            return sendApplicationProtobuf(
                    PbBridgeCodec.SERVICE,
                    metadata,
                    message.protobufType(),
                    message.response(),
                    payload);
        } finally {
            wipe(
                    payload);
        }
    }

    synchronized Output sendPairedSync(
            PairedSyncCodec.UserDefaultsMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "PairedSync message is absent");
        }
        message.requireValid();
        if (message.response()) {
            throw new IllegalArgumentException(
                    "PairedSync response needs a peer identifier");
        }
        return sendPairedSync(
                applicationMessages.nextOneWayRequestingAppAck(),
                message);
    }

    synchronized Output sendPairedSync(
            MessageMetadata metadata,
            PairedSyncCodec.UserDefaultsMessage message) {
        requireUsable();
        requireControlReady();
        if (message == null) {
            throw new IllegalArgumentException(
                    "PairedSync message is absent");
        }
        String topic =
                pairedSyncCapability.requireService();
        byte[] payload =
                PairedSyncCodec.encode(
                        message);
        try {
            return sendApplicationProtobuf(
                    topic,
                    metadata,
                    message.protobufType(),
                    message.response(),
                    payload);
        } finally {
            wipe(
                    payload);
        }
    }

    /**
     * Sends an IDS application receipt after the application callback for the
     * original message has completed.
     *
     * <p>The receipt consumes a fresh controller-global sequence. Its only
     * original-message correlation is the original message UUID carried as
     * {@code peerResponseIdentifier}; the original sequence must not be
     * reused.</p>
     */
    synchronized Output sendAppAck(
            String topic,
            String outgoingResponseIdentifier) {
        requireUsable();
        requireControlReady();
        IdsApplicationRoute route =
                IdsApplicationRoute.forTopic(
                        topic);
        DataState state =
                requireJoinedDataState(
                        route.serviceConnectorName.encode());
        MessageMetadata metadata =
                applicationMessages.nextAppAckForIdentifier(
                        outgoingResponseIdentifier);
        requireControlReady();
        IdsServiceMapState.OutgoingRoute mapped =
                control.serviceMap()
                        .routeOutgoing(
                                topic);
        IdsSocketPairCodec.AppAckMessage envelope =
                new IdsSocketPairCodec.AppAckMessage(
                        metadata.sequence,
                        mapped.streamId,
                        metadata.peerResponseIdentifier,
                        mapped.wireTopic());

        byte[] frame = null;
        OutputBuilder output =
                new OutputBuilder();
        try {
            frame =
                    IdsSocketPairCodec.encodeAppAck(
                            envelope);
            sendSocketPairFrame(
                    state,
                    frame,
                    null,
                    output);
            output.events.add(
                    SessionEvent.appAck(
                            EventType.APP_ACK_SENT,
                            state.connection != null ? state.connection.connectionId : 0,
                            state.service,
                            topic,
                            envelope));
            drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw fail(
                    failure);
        } finally {
            wipe(
                    frame);
            envelope.destroy();
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

    private void ensureControlHello(OutputBuilder output) {
        long active = activeControlConnection();
        if (active != 0 && transport.isServiceActive(active)) {
            if (control == null || !control.localHelloSent()) {
                if (control == null) {
                    control =
                            IdsControlChannelSession.createDefaultPaired(
                                    random,
                                    localIdsDeviceUuid,
                                    directMessagingSupported,
                                    IdsServiceMapState.APPLE_FIRST_STREAM_ID);
                    controlConnectionId = active;
                }
                if (!control.localHelloSent()) {
                    byte[] hello =
                            control.startHello();
                    try {
                        observeOutgoingFrame("hello", IdsIpsecServiceRoute.CONTROL_SERVICE, 1, false, hello);
                        sendApplication(
                                active,
                                hello,
                                output);
                        output.events.add(
                                SessionEvent.simple(
                                        EventType.CONTROL_HELLO_SENT,
                                        active,
                                        IdsIpsecServiceRoute.CONTROL_SERVICE,
                                        null));
                    } finally {
                        wipe(
                                hello);
                    }
                }
            }
        }
    }

    synchronized Output pollRetransmissions() {
        requireUsable();
        OutputBuilder output = new OutputBuilder();
        ensureControlHello(output);
        startPendingDataConnectors(output);
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
        requireUsable();
        if (control == null
                || !control.remoteHelloReceived()) {
            return (int) IosCompanionProfile26_6.PAIRING_PROTOCOL_VERSION;
        }
        IdsControlChannelSession.RemoteHello remote =
                control.remoteHello();
        long effective =
                remote.maxCompatibilityVersion == 0
                        ? remote.pairingProtocolVersion
                        : remote.maxCompatibilityVersion;
        if (effective <= 0
                || effective > 0xffff) {
            return (int) IosCompanionProfile26_6.PAIRING_PROTOCOL_VERSION;
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

    private Output sendNanoRegistry(
            String topic,
            MessageMetadata metadata,
            NanoRegistryClassDCodec.ApplicationMessage classD,
            NanoRegistryPropertyCodec.ApplicationMessage classC) {
        requireUsable();
        requireControlReady();
        byte[] payload;
        int protobufType;
        boolean response;
        if (classD != null
                && classC == null) {
            payload =
                    NanoRegistryClassDCodec.encode(
                            classD);
            protobufType =
                    classD.protobufType();
            response =
                    classD.response();
        } else if (classC != null
                && classD == null) {
            payload =
                    NanoRegistryPropertyCodec.encode(
                            classC);
            protobufType =
                    classC.protobufType();
            response =
                    classC.response();
        } else {
            throw new IllegalArgumentException(
                    "Exactly one NanoRegistry application message "
                            + "is required");
        }
        try {
            return sendApplicationProtobuf(
                    topic,
                    metadata,
                    protobufType,
                    response,
                    payload);
        } finally {
            wipe(
                    payload);
        }
    }

    synchronized Output sendApplicationProtobuf(
            String topic,
            int protobufType,
            byte[] payload) {
        if (topic == null) {
            throw new IllegalArgumentException(
                    "IDS topic is absent");
        }
        if (payload == null) {
            throw new IllegalArgumentException(
                    "IDS payload is absent");
        }
        requireUsable();
        boolean nssRequest = NanoSystemSettingsDiagnostics.TOPIC.equals(topic);
        boolean findMyRequest = IdsApplicationRoute.FIND_MY_LOCAL_SERVICE.equals(topic);
        if (findMyRequest) FindMyLocalDeviceCodec.PlaySoundRequest.decode(protobufType, payload);
        if (nssRequest) NanoSystemSettingsDiagnostics.requireReadOnlyRequest(protobufType, payload);
        if (SysdiagnoseArchiveInventory.TOPIC.equals(topic)) {
            SysdiagnoseCollection.requireDiagnosticRequest(protobufType, payload);
        }
        boolean nanoRegistryRequest = (NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(topic)
                && protobufType == NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST)
                || (NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(topic)
                && protobufType == NanoRegistryClassDCodec.TYPE_PING);
        MessageMetadata metadata =
                nanoRegistryRequest || nssRequest || findMyRequest ? applicationMessages.nextRequestExpectingPeerResponseAndAppAck()
                        : applicationMessages.nextOneWayRequestingAppAck();
        return sendApplicationProtobuf(
                topic,
                metadata,
                protobufType,
                false,
                payload);
    }

    /** Fixed, empty native NSS reboot. Generic NSS requests stay read-only. */
    synchronized Output sendWatchReboot() {
        requireUsable();
        return sendApplicationProtobuf(NativeWatchReboot.TOPIC,
                applicationMessages.nextOneWayRequestingAppAck(), NativeWatchReboot.TYPE,
                false, new byte[0]);
    }

    synchronized Output sendFindMyLocalResponse(int protobufType, byte[] payload, String responseId) {
        requireUsable();
        FindMyLocalDeviceCodec.requireSoundType(protobufType);
        FindMyLocalDeviceCodec.PlaySoundResponse.decode(payload);
        if (responseId == null || !UUID.fromString(responseId).toString().equalsIgnoreCase(responseId)) {
            throw new IllegalArgumentException("Invalid Ping response identifier");
        }
        return sendApplicationProtobuf(IdsApplicationRoute.FIND_MY_LOCAL_SERVICE,
                applicationMessages.nextResponseToIdentifier(responseId), protobufType, true, payload);
    }

    synchronized Output sendApplicationData(String topic, byte[] payload) {
        return sendApplicationData(topic, payload, IdsSocketPairCodec.COMMAND_DATA);
    }

    synchronized Output sendHealthSyncRequest(byte[] encrypted,java.util.UUID message) {
        HealthDataEventCodec.requireEncryptedDictionary(encrypted);
        return sendApplicationData(IdsApplicationRoute.HEALTH_SYNC_SERVICE,encrypted,
                IdsSocketPairCodec.COMMAND_DATA,true,message);
    }

    /** Native clockface full-collection request with response UUID and app ACK. */
    synchronized Output sendClockFaceCollectionRequest(byte[] payload) {
        ClockFaceSyncHeaderCodec.decodeFullRequest(payload);
        return sendApplicationData(IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, payload,
                IdsSocketPairCodec.COMMAND_DATA, true);
    }

    synchronized Output sendClockFaceDeltaRequest(byte[] payload) {
        ClockFaceDeltaProtocol.validateRequest(payload);
        return sendApplicationData(IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, payload,
                IdsSocketPairCodec.COMMAND_DATA, true);
    }

    /**
     * One-way data reply correlated to the Watch message UUID.
     * Flags stay clear of {@code EXPECTS_PEER_RESPONSE}: this is the response.
     */
    synchronized Output sendApplicationDataResponse(
            String topic,
            byte[] payload,
            String outgoingResponseIdentifier) {
        requireUsable();
        requireControlReady();
        IdsApplicationRoute route = IdsApplicationRoute.forTopic(topic);
        DataState state = requireJoinedDataState(route.serviceConnectorName.encode());
        MessageMetadata metadata = applicationMessages.nextResponseToIdentifier(
                outgoingResponseIdentifier);
        IdsServiceMapState.OutgoingRoute mapped = control.serviceMap().routeOutgoing(topic);
        int flags = metadata.flagsWithoutTopic
                | (mapped.includeTopic ? IdsSocketPairCodec.FLAG_HAS_TOPIC : 0);
        IdsSocketPairCodec.DataMessage envelope = new IdsSocketPairCodec.DataMessage(
                IdsSocketPairCodec.COMMAND_DATA, metadata.sequence, mapped.streamId,
                flags, metadata.peerResponseIdentifier, metadata.messageUuid,
                mapped.wireTopic(), payload, null);
        byte[] frame = null;
        OutputBuilder output = new OutputBuilder();
        try {
            frame = IdsSocketPairCodec.encodeData(envelope);
            sendSocketPairFrame(state, frame, null, output);
            output.events.add(SessionEvent.data(EventType.DATA_SENT,
                    state.connection != null ? state.connection.connectionId : 0,
                    state.service, topic, envelope));
            drainDeferredPackets(output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw fail(failure);
        } finally { wipe(frame); envelope.destroy(); }
    }

    synchronized Output sendApplicationDictionary(String topic, byte[] payload) {
        return sendApplicationData(topic, payload, IdsSocketPairCodec.COMMAND_DICTIONARY);
    }

    private Output sendApplicationData(String topic, byte[] payload, int command) {
        return sendApplicationData(topic, payload, command, false);
    }

    private Output sendApplicationData(String topic, byte[] payload, int command, boolean requestReply) {
        return sendApplicationData(topic,payload,command,requestReply,null);
    }
    private Output sendApplicationData(String topic, byte[] payload, int command, boolean requestReply,java.util.UUID healthMessage) {
        requireUsable();
        requireControlReady();
        if (NanoSystemSettingsDiagnostics.TOPIC.equals(topic)
                || SysdiagnoseArchiveInventory.TOPIC.equals(topic)) {
            throw new IllegalArgumentException("Diagnostics require a supported read-only Protobuf request");
        }
        IdsApplicationRoute route = IdsApplicationRoute.forTopic(topic);
        DataState state = requireJoinedDataState(route.serviceConnectorName.encode());
        MessageMetadata metadata = healthMessage!=null ? applicationMessages.nextHealthRequest(healthMessage)
                : requestReply ? applicationMessages.nextRequestExpectingPeerResponseAndAppAck()
                : WifiNetworkSyncCodec.TOPIC.equals(topic) ? applicationMessages.nextOneWayRequestingAppAck()
                : applicationMessages.nextOneWay();
        IdsServiceMapState.OutgoingRoute mapped = control.serviceMap().routeOutgoing(topic);
        IdsSocketPairCodec.DataMessage envelope = new IdsSocketPairCodec.DataMessage(
                command, metadata.sequence, mapped.streamId,
                metadata.flagsWithoutTopic | (mapped.includeTopic ? IdsSocketPairCodec.FLAG_HAS_TOPIC : 0),
                metadata.peerResponseIdentifier, metadata.messageUuid, mapped.wireTopic(), payload, null);
        byte[] frame = null;
        OutputBuilder output = new OutputBuilder();
        try {
            frame = IdsSocketPairCodec.encodeData(envelope);
            sendSocketPairFrame(state, frame, null, output);
            output.events.add(SessionEvent.data(EventType.DATA_SENT,
                    state.connection != null ? state.connection.connectionId : 0,
                    state.service, topic, envelope));
            drainDeferredPackets(output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw fail(failure);
        } finally { wipe(frame); envelope.destroy(); }
    }

    private Output sendApplicationProtobuf(
            String topic,
            MessageMetadata metadata,
            int protobufType,
            boolean response,
            byte[] payload) {
        if (metadata == null) {
            throw new IllegalArgumentException(
                    "IDS message metadata is absent");
        }
        IdsApplicationRoute route =
                IdsApplicationRoute.forTopic(
                        topic);
        DataState state =
                requireJoinedDataState(
                        route.serviceConnectorName.encode());
        requireControlReady();
        IdsServiceMapState.OutgoingRoute mapped =
                control.serviceMap()
                        .routeOutgoing(
                                topic);
        int flags =
                metadata.flagsWithoutTopic
                        | (mapped.includeTopic
                        ? IdsSocketPairCodec.FLAG_HAS_TOPIC
                        : 0);
        IdsSocketPairCodec.ProtobufMessage envelope =
                new IdsSocketPairCodec.ProtobufMessage(
                        metadata.sequence,
                        mapped.streamId,
                        flags,
                        metadata.peerResponseIdentifier,
                        metadata.messageUuid,
                        mapped.wireTopic(),
                        protobufType,
                        response,
                        payload,
                        metadata.expirySeconds);

        byte[] frame = null;
        OutputBuilder output =
                new OutputBuilder();
        try {
            frame =
                    IdsSocketPairCodec.encodeProtobuf(
                            envelope);
            observeOutgoingFrame("protobuf", topic, protobufType, response, frame);
            sendSocketPairFrame(
                    state,
                    frame,
                    metadata.fragmentedMessageId,
                    output);
            output.events.add(
                    SessionEvent.protobuf(
                            EventType.PROTOBUF_SENT,
                            state.connection != null ? state.connection.connectionId : 0,
                            state.service,
                            topic,
                            envelope));
            drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw fail(
                    failure);
        } finally {
            wipe(
                    frame);
            envelope.destroy();
        }
    }

    private void processTransportEvent(
            IdsServiceConnectorCoordinator.ConnectionEvent event,
            OutputBuilder output) {
        switch (event.type) {
            case SERVICE_ACCEPTED -> {
                if (IdsIpsecServiceRoute.CONTROL_SERVICE.equals(
                        event.serviceName)) {
                    acceptControlService(
                            event,
                            output);
                } else {
                    acceptDataService(
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
                        acceptControlBytes(
                                bytes,
                                output);
                    } else if (transport.isControlConnection(event.connectionId)) {
                        controlConnectionId = event.connectionId;
                        acceptControlBytes(
                                bytes,
                                output);
                    } else if (transport.isDataConnection(event.connectionId)) {
                        acceptDataBytes(
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
                            acceptControlBytes(
                                    bytes,
                                    output);
                        } catch (RuntimeException notControl) {
                            acceptDataBytes(
                                    event.connectionId,
                                    bytes,
                                    output);
                        }
                    } else {
                        acceptDataBytes(
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
                handleSuperseded(
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
                    reopenControlService(
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
                            sendSocketPairFrame(state, queuedFrame.bytes, queuedFrame.fragmentedMessageId, output);
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
                                    sendSocketPairFrame(other, queuedFrame.bytes, queuedFrame.fragmentedMessageId, output);
                                } finally { queuedFrame.close(); }
                            }
                        }
                    }
                }
                if (control != null && control.remoteHelloReceived()) {
                    startPendingDataConnectors(output);
                }
            }
            default -> output.events.add(
                    SessionEvent.transport(
                            event));
        }
    }

    private void acceptControlService(
            IdsServiceConnectorCoordinator.ConnectionEvent event,
            OutputBuilder output) {
        if (event.route == null
                || !sameRoute(
                        event.route,
                        IdsIpsecServiceRoute.control())) {
            throw new IllegalArgumentException(
                    "IDS control service arrived on the wrong route");
        }
        if (control != null && controlConnectionId != event.connectionId) {
            if (controlConnectionId != 0
                    && transport.isServiceActive(controlConnectionId)) {
                return;
            }
            resetControlState();
        }
        if (control != null) {
            if (controlConnectionId
                    == event.connectionId) {
                return;
            }
            resetControlState();
        }

        control =
                IdsControlChannelSession.createDefaultPaired(
                        random,
                        localIdsDeviceUuid,
                        directMessagingSupported,
                        IdsServiceMapState.APPLE_FIRST_STREAM_ID);
        controlConnectionId =
                event.connectionId;
        controlOpenRequested = false;
        output.events.add(
                SessionEvent.simple(
                        EventType.CONTROL_SERVICE_ACCEPTED,
                        controlConnectionId,
                        IdsIpsecServiceRoute.CONTROL_SERVICE,
                        null));

        byte[] hello =
                control.startHello();
        try {
            observeOutgoingFrame("hello", IdsIpsecServiceRoute.CONTROL_SERVICE, 1, false, hello);
            sendApplication(
                    activeControlConnection(),
                    hello,
                    output);
            output.events.add(
                    SessionEvent.simple(
                            EventType.CONTROL_HELLO_SENT,
                            controlConnectionId,
                            IdsIpsecServiceRoute.CONTROL_SERVICE,
                            null));
        } finally {
            wipe(
                    hello);
        }
    }

    private long activeControlConnection() {
        if (controlConnectionId != 0 && transport.isServiceActive(controlConnectionId)) {
            return controlConnectionId;
        }
        long active = transport.findActiveControlConnectionId();
        if (active != 0) {
            controlConnectionId = active;
            return active;
        }
        return controlConnectionId;
    }

    private void acceptControlBytes(
            byte[] bytes,
            OutputBuilder output) {
        if (control == null
                || controlConnectionId == 0) {
            throw new IllegalStateException(
                    "IDS control bytes arrived before service acceptance");
        }
        List<IdsControlChannelCodec.Message> messages =
                control.acceptTcpBytes(
                        bytes);
        try {
            for (IdsControlChannelCodec.Message message :
                    messages) {
                output.events.add(
                        SessionEvent.controlMessage(
                                controlConnectionId,
                                message.type,
                                controlMessageLength(
                                        message),
                                controlMessageDetail(
                                        message)));
                if (message
                        instanceof IdsControlChannelCodec.HelloMessage) {
                    IdsControlChannelSession.Compatibility compatibility =
                            control.compatibility();
                    if (compatibility
                            == IdsControlChannelSession
                            .Compatibility.NO_OVERLAP) {
                        throw new IllegalArgumentException(
                                "Peer IDS Hello has no compatible version");
                    }
                    if (directMessagingSupported) {
                        IdsControlChannelCodec.DirectMsgInfoMessage info =
                                IdsControlChannelCodec.DirectMsgInfoMessage.basicVersionOne();
                        byte[] frame = null;
                        try {
                            frame = IdsControlChannelCodec.encodeFramed(info);
                            sendApplication(activeControlConnection(), frame, output);
                            output.events.add(SessionEvent.simple(EventType.DIRECT_MESSAGING_INFO_SENT,
                                    controlConnectionId, IdsIpsecServiceRoute.CONTROL_SERVICE,
                                    info.capabilitySummary()));
                        } finally { wipe(frame); info.destroy(); }
                    }
                    output.events.add(
                            SessionEvent.simple(
                                    EventType.CONTROL_HELLO_RECEIVED,
                                    controlConnectionId,
                                    IdsIpsecServiceRoute.CONTROL_SERVICE,
                                    null));
                    output.events.add(
                            SessionEvent.controlReady(
                                    controlConnectionId,
                                    compatibility));
                } else if (message
                        instanceof IdsControlChannelCodec
                        .SetupEncryptedChannelMessage setup) {
                    acceptEncryptedSetup(
                            setup,
                            output);
                } else if (message
                        instanceof IdsControlChannelCodec
                        .SetupChannelMessage setup) {
                    acceptDirectSetup(
                            setup,
                            output);
                } else if (message
                        instanceof IdsControlChannelCodec
                        .DirectMsgInfoMessage) {
                    // Each side sends its own announcement after Hello; this
                    // is not a request/reply exchange and must not be echoed.
                } else if (message
                        instanceof IdsControlChannelCodec
                        .GenericControlMessage) {
                    // Close/Compression/Fairplay/OTR: log via CONTROL_MESSAGE.
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported production IDS control message");
                }
            }
        } finally {
            destroyControlMessages(
                    messages);
        }
    }

    private void acceptDirectSetup(
            IdsControlChannelCodec.SetupChannelMessage incoming,
            OutputBuilder output) {
        boolean isReply = incoming.forLocalGuid != null;
        DataState state = findDataState(
                incoming.account,
                incoming.service,
                incoming.name,
                isReply);
        if (state == null) {
            if (isReply) {
                return;
            }
            replyToIncomingPlainSetup(
                    incoming,
                    output);
            return;
        }
        if (!isReply
                && !state.setup.established()) {
            state.setup.markPlaintextEstablished();
            state.awaitIncomingConnector =
                    true;
            sendPlainSetupReply(
                    incoming,
                    state.setup.localGuid(),
                    output);
            output.events.add(
                    SessionEvent.simple(
                            EventType.DATA_SETUP_REPLIED,
                            controlConnectionId,
                            state.service,
                            null));
            completeSetupEstablishment(
                    state,
                    output);
            return;
        }
        completeSetupEstablishment(
                state,
                output);
    }

    private void replyToIncomingPlainSetup(
            IdsControlChannelCodec.SetupChannelMessage incoming,
            OutputBuilder output) {
        IdsServiceConnectorName connectorName;
        IdsIpsecServiceRoute route;
        try {
            connectorName =
                    IdsServiceConnectorName.of(
                            incoming.account,
                            incoming.service,
                            incoming.name);
            route =
                    IdsIpsecServiceRoute.localDelivery(
                            connectorName);
        } catch (IllegalArgumentException unknownLane) {
            return;
        }
        String service =
                connectorName.encode();
        DataState existing =
                dataByService.get(
                        service);
        if (existing != null) {
            if (!existing.setup.established()) {
                existing.setup.markPlaintextEstablished();
                existing.awaitIncomingConnector =
                        true;
                sendPlainSetupReply(
                        incoming,
                        existing.setup.localGuid(),
                        output);
                output.events.add(
                        SessionEvent.simple(
                                EventType.DATA_SETUP_REPLIED,
                                controlConnectionId,
                                existing.service,
                                null));
            }
            completeSetupEstablishment(
                    existing,
                    output);
            return;
        }
        IdsEncryptedDataChannelSession session =
                IdsEncryptedDataChannelSession.openPlainEstablished(
                        random,
                        incoming);
        DataState state =
                new DataState(
                        service,
                        route,
                        session);
        dataByService.put(
                service,
                state);
        state.awaitIncomingConnector =
                true;
        sendPlainSetupReply(
                incoming,
                session.localGuid(),
                output);
        output.events.add(
                SessionEvent.simple(
                        EventType.DATA_SETUP_REPLIED,
                        controlConnectionId,
                        service,
                        null));
        completeSetupEstablishment(
                state,
                output);
    }

    private void sendPlainSetupReply(
            IdsControlChannelCodec.SetupChannelMessage incoming,
            String localGuid,
            OutputBuilder output) {
        IdsControlChannelCodec.SetupChannelMessage reply =
                new IdsControlChannelCodec.SetupChannelMessage(
                        incoming.type,
                        incoming.protocol,
                        incoming.localPort,
                        incoming.remotePort,
                        localGuid,
                        incoming.remoteConnectionGuid,
                        incoming.account,
                        incoming.service,
                        incoming.name,
                        incoming.directFlags);
        byte[] frame = null;
        try {
            frame =
                    IdsControlChannelCodec.encodeFramed(
                            reply);
            sendApplication(
                    activeControlConnection(),
                    frame,
                    output);
        } finally {
            reply.destroy();
            wipe(
                    frame);
        }
    }

    private void acceptEncryptedSetup(
            IdsControlChannelCodec.SetupEncryptedChannelMessage incoming,
            OutputBuilder output) {
        boolean isReply = incoming.forLocalGuid != null;
        DataState state = findDataState(
                incoming.account,
                incoming.service,
                incoming.name,
                isReply);

        if (state == null) {
            if (incoming.forLocalGuid != null) {
                // A stale or retransmitted reply must not tear down the
                // session; the lane it belonged to is simply gone.
                java.util.function.Consumer<String> logger =
                        diagnosticLogger;
                if (logger != null) {
                    logger.accept(
                            "IDS SETUP REPLY IGNORED: no local connection for guid; name="
                                    + incoming.name);
                }
                return;
            }
            IdsServiceConnectorName connectorName =
                    IdsServiceConnectorName.of(
                            incoming.account,
                            incoming.service,
                            incoming.name);
            IdsIpsecServiceRoute route = null;
            try {
                route =
                        IdsIpsecServiceRoute.localDelivery(
                                connectorName);
            } catch (IllegalArgumentException notLocalDelivery) {
                // watchOS 26.6 buddy can open a new encrypted data channel
                // under a non-localdelivery account/service late in setup
                // (live 0.2.190 16:44: the Watch's new channel killed the
                // whole session with "IDS IPsec data route requires
                // localdelivery" and pairing stalled). Route by the UTun
                // runtime identifier instead of dying.
                try {
                    route =
                            IdsIpsecServiceRoute.localDelivery(
                                    IdsServiceConnectorName.localDelivery(
                                            connectorName.name));
                } catch (IllegalArgumentException unknownIdentifier) {
                    java.util.function.Consumer<String> logger =
                            diagnosticLogger;
                    if (logger != null) {
                        logger.accept(
                                "IDS ENCRYPTED SETUP IGNORED: unroutable connector="
                                        + connectorName.encode());
                    }
                    return;
                }
                java.util.function.Consumer<String> logger =
                        diagnosticLogger;
                if (logger != null) {
                    logger.accept(
                            "IDS ENCRYPTED SETUP: non-localdelivery connector="
                                    + connectorName.encode()
                                    + " routed by UTun identifier");
                }
            }
            String service =
                    connectorName.encode();
            IdsEncryptedDataChannelSession.ResponderStart responder =
                    IdsEncryptedDataChannelSession
                            .respondToInitialRequest(
                                    ssrcs,
                                    random,
                                    incoming);
            byte[] replyFrame = null;
            IdsEncryptedDataChannelSession session = null;
            try {
                replyFrame =
                        IdsControlChannelCodec.encodeFramed(
                                responder.reply);
                OrdinaryIkeAuth.DataClass dc =
                        route.networkRelayDataClass == IdsIpsecServiceRoute.NETWORK_RELAY_CLASS_D
                                ? OrdinaryIkeAuth.DataClass.CLASS_D
                                : OrdinaryIkeAuth.DataClass.CLASS_C;
                transport.listenDataPort(
                        dc,
                        responder.reply.localPort);
                sendApplication(
                        activeControlConnection(),
                        replyFrame,
                        output);
                session =
                        responder.takeSession();
                state =
                        new DataState(
                                service,
                                route,
                                session);
                session = null;
                dataByService.put(
                        service,
                        state);
                output.events.add(
                        SessionEvent.simple(
                                EventType.DATA_SETUP_REPLIED,
                                controlConnectionId,
                                service,
                                null));
            } finally {
                responder.close();
                if (session != null) {
                    session.close();
                }
                wipe(
                        replyFrame);
            }
        } else {
            state.setup.acceptPeerSetup(
                    incoming);
        }

        completeSetupEstablishment(
                state,
                output);
    }

    private DataState findDataState(
            String account,
            String service,
            String name,
            boolean isReply) {
        DataState state = null;
        if (account != null && service != null && name != null) {
            try {
                IdsServiceConnectorName connectorName =
                        IdsServiceConnectorName.of(
                                account,
                                service,
                                name);
                state = dataByService.get(connectorName.encode());
            } catch (Exception ignored) {
            }
        }
        if (state == null && isReply) {
            if (name != null) {
                for (DataState candidate : dataByService.values()) {
                    if (serviceMatches(name, candidate.service)
                            || serviceMatches(candidate.service, name)) {
                        state = candidate;
                        break;
                    }
                }
            }
            if (state == null && service != null) {
                for (DataState candidate : dataByService.values()) {
                    if (serviceMatches(service, candidate.service)
                            || serviceMatches(candidate.service, service)) {
                        state = candidate;
                        break;
                    }
                }
            }
            if (state == null) {
                for (DataState candidate : dataByService.values()) {
                    if (!candidate.setup.established() || candidate.connection == null) {
                        state = candidate;
                        break;
                    }
                }
                if (state == null && !dataByService.isEmpty()) {
                    state = dataByService.values().iterator().next();
                }
            }
        }
        return state;
    }

    private void completeSetupEstablishment(
            DataState state,
            OutputBuilder output) {
        if (state == null
                || !state.setup.established()) {
            return;
        }
        output.events.add(
                SessionEvent.simple(
                        EventType.DATA_SETUP_ESTABLISHED,
                        controlConnectionId,
                        state.service,
                        null));
        IdsDataChannelJoinState.Result<DataTransport> joined =
                dataJoins.receiveSetup(
                        IdsServiceConnectorName.parseLenient(state.service));
        switch (joined.action) {
            case START_OUTGOING_CONNECTOR -> {
                if (state.connection == null
                        && !state.awaitIncomingConnector) {
                    startDataConnector(
                            state,
                            output);
                }
            }
            case WAITING_FOR_SERVICE_CONNECTION -> {
            }
            case JOINED ->
                    attachDataTransport(
                            state,
                            joined.connection,
                            output);
            case CACHED_FOR_LATER_SETUP -> {
            }
        }
    }

    private void reopenControlService(
            OutputBuilder output) {
        try (IdsServiceConnectorCoordinator.OpenResult opened =
                     transport.openService(
                             IdsIpsecServiceRoute.control())) {
            controlConnectionId = opened.connectionId;
            controlOpenRequested = true;
            addPacket(
                    output,
                    opened.packet());
            output.events.add(
                    SessionEvent.simple(
                            EventType.CONTROL_CONNECT_STARTED,
                            opened.connectionId,
                            IdsIpsecServiceRoute.CONTROL_SERVICE,
                            null));
        } catch (IllegalStateException alreadyStarted) {
            // An outgoing control attempt is already pending.
        }
    }

    private void startDataConnector(
            DataState state,
            OutputBuilder output) {
        if (state.connection != null) {
            return;
        }
        try (IdsServiceConnectorCoordinator.OpenResult opened =
                     transport.openService(
                             state.route)) {
            addPacket(
                    output,
                    opened.packet());
            output.events.add(
                    SessionEvent.simple(
                            EventType.DATA_CONNECT_STARTED,
                            opened.connectionId,
                            state.service,
                            null));
        } catch (IllegalStateException alreadyStarted) {
            // Outgoing attempt already started for this service route
        }
    }

    /**
     * Opens data lanes to Watch:61314 after a locally initiated Setup.
     *
     * <p>Watch-initiated type-2 SetupChannel already has the Watch SYNing
     * 61314. Bursting our own SYNs in the same ERTM window wrapped TxSeq
     * 63→0 and poisoned the pipe. Those lanes wait for the incoming
     * connector instead.</p>
     */
    private void startPendingDataConnectors(
            OutputBuilder output) {
        if (control == null
                || !control.remoteHelloReceived()) {
            return;
        }
        for (DataState state : new ArrayList<>(dataByService.values())) {
            if (state.stateClosed
                    || state.connection != null
                    || state.route == null
                    || !state.setup.established()
                    || state.awaitIncomingConnector) {
                continue;
            }
            startDataConnector(
                    state,
                    output);
        }
    }

    private void acceptDataService(
            IdsServiceConnectorCoordinator.ConnectionEvent event,
            OutputBuilder output) {
        String serviceName = event.serviceName != null && !"none".equals(event.serviceName)
                ? event.serviceName
                : (event.route != null ? event.route.service : NanoRegistryPropertyCodec.CLASS_D_SERVICE);
        if (serviceName == null) {
            serviceName = NanoRegistryPropertyCodec.CLASS_D_SERVICE;
        }
        if (dataTransports.containsKey(
                event.connectionId)) {
            return;
        }
        DataTransport connection =
                new DataTransport(
                        event.connectionId,
                        serviceName);
        dataTransports.put(
                event.connectionId,
                connection);
        output.events.add(
                SessionEvent.simple(
                        EventType.DATA_SERVICE_ACCEPTED,
                        event.connectionId,
                        serviceName,
                        null));

        DataState existing =
                dataByService.get(
                        serviceName);
        if (existing == null && !dataByService.isEmpty()) {
            for (DataState candidate : dataByService.values()) {
                if (serviceMatches(serviceName, candidate.service)) {
                    existing = candidate;
                    break;
                }
            }
        }
        if (existing != null) {
            attachDataTransport(
                    existing,
                    connection,
                    output);
            return;
        }

        try {
            IdsDataChannelJoinState.Result<DataTransport> joined =
                    dataJoins.receiveServiceConnection(
                            serviceName,
                            connection);
            if (joined.action
                    == IdsDataChannelJoinState.Action.JOINED) {
                DataState state =
                        dataByService.get(
                                serviceName);
                if (state == null && joined.connectorService != null) {
                    state = dataByService.get(joined.connectorService);
                }
                if (state == null && !dataByService.isEmpty()) {
                    for (DataState candidate : dataByService.values()) {
                        if (serviceMatches(serviceName, candidate.service)
                                || (joined.connectorService != null && serviceMatches(joined.connectorService, candidate.service))) {
                            state = candidate;
                            break;
                        }
                    }
                }
                if (state == null) {
                    IdsServiceConnectorName parsed =
                            IdsServiceConnectorName.parseLenient(serviceName);
                    IdsIpsecServiceRoute route =
                            IdsIpsecServiceRoute.localDelivery(parsed);
                    IdsEncryptedDataChannelSession setup =
                            IdsEncryptedDataChannelSession.openOutgoing(
                                    setupPorts,
                                    ssrcs,
                                    random,
                                    parsed);
                    state = new DataState(serviceName, route, setup);
                    dataByService.put(serviceName, state);
                }
                attachDataTransport(
                        state,
                        joined.connection,
                        output);
            } else if (joined.action
                    != IdsDataChannelJoinState
                    .Action.CACHED_FOR_LATER_SETUP) {
                // Reaching here means no registered lane matched, so one has to
                // be created. Attaching an unregistered lane would strand the
                // transport: senders resolve lanes through dataByService only,
                // so every later message would queue against a live connection
                // nothing can find.
                IdsServiceConnectorName parsed =
                        IdsServiceConnectorName.parseLenient(
                                serviceName);
                DataState created =
                        new DataState(
                                serviceName,
                                IdsIpsecServiceRoute.localDelivery(
                                        parsed),
                                IdsEncryptedDataChannelSession.openOutgoing(
                                        setupPorts,
                                        ssrcs,
                                        random,
                                        parsed));
                dataByService.put(
                        serviceName,
                        created);
                attachDataTransport(
                        created,
                        connection,
                        output);
            }
        } catch (IllegalArgumentException notCanonicalConnector) {
            for (DataState candidate : dataByService.values()) {
                if (candidate.connection == null && (serviceMatches(serviceName, candidate.service) || candidate.service.contains(serviceName))) {
                    attachDataTransport(candidate, connection, output);
                    break;
                }
            }
        }
    }

    private void attachDataTransport(
            DataState state,
            DataTransport connection,
            OutputBuilder output) {
        if (connection == null
                || connection.released) {
            throw new IllegalArgumentException(
                    "IDS joined data transport is invalid");
        }
        connection.service = state.service;
        if (state.connection != null) {
            if (state.connection.connectionId == connection.connectionId) {
                return;
            }
            dataByConnection.remove(state.connection.connectionId);
            dataTransports.remove(state.connection.connectionId);
            state.connection = null;
            state.handshakeSent = false;
            state.handshakeReceived = false;
        }
        state.resetTransport();
        state.connection =
                connection;
        state.needsReplacement = false;
        dataByConnection.put(
                connection.connectionId,
                state);
        output.events.add(
                SessionEvent.simple(
                        EventType.DATA_CHANNEL_JOINED,
                        connection.connectionId,
                        state.service,
                        null));
        if (state.awaitIncomingConnector) {
            sendDataHandshake(
                    state,
                    output);
        }
        // Application frames stay parked until the socket-pair Handshake
        // has been answered. WatchWitch AlloyHandler: the TCP accepting
        // side sends Handshake first; the initiator replies, then data.
        flushPendingLinkControl(state, output);
        if (state.handshakeReceived || !state.awaitIncomingConnector) {
            flushPendingApplication(state, output);
        }
        flushPendingFrames(state, output);
    }

    /** Describes every data lane and its backlog, for host diagnostics. */
    synchronized String pendingApplicationFramesSummary() {
        StringBuilder summary =
                new StringBuilder();
        for (DataState state : dataByService.values()) {
            if (summary.length() > 0) {
                summary.append("; ");
            }
            long connectionId =
                    state.connection != null
                            ? state.connection.connectionId
                            : 0;
            summary.append(state.service)
                    .append(" parked=")
                    .append(state.pendingFrames.size())
                    .append(" conn=")
                    .append(connectionId)
                    .append(" active=")
                    .append(connectionId != 0
                            && transport.isServiceActive(connectionId))
                    .append(" established=")
                    .append(connectionId != 0
                            && transport.isEstablished(connectionId))
                    .append(" gone=")
                    .append(state.connection != null
                            && state.connection.transportGone)
                    .append(" needsRepl=")
                    .append(state.needsReplacement)
                    .append(" route=")
                    .append(state.route != null)
                    .append(" closed=")
                    .append(state.stateClosed);
        }
        return summary.toString();
    }

    synchronized boolean hasPendingApplicationFrames() {
        if (closed) {
            return false;
        }
        for (DataState state : dataByService.values()) {
            if (!state.stateClosed && !state.pendingFrames.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drains application frames parked while no lane could carry them.
     *
     * <p>Post-commit setup messages are prepared before the Watch has finished
     * opening its service lanes, so a lane becoming usable is not guaranteed to
     * coincide with a join event. Callers pump this until
     * {@link #hasPendingApplicationFrames()} stays false; otherwise the
     * activation permit never reaches the Watch.</p>
     */
    synchronized Output flushPendingApplicationFrames() {
        requireUsable();
        OutputBuilder output =
                new OutputBuilder();
        try {
            flushPendingFrames(
                    null,
                    output);
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
     * Drains frames parked while no lane could carry them.
     *
     * <p>Resolution is delegated to {@link #sendSocketPairFrame}, which matches
     * a parked lane against the active lanes in the same direction as the
     * original send. Deciding here instead would invert that comparison and
     * strand services such as PBBridge that ride a relay lane.</p>
     */
    private void flushPendingFrames(
            DataState joined,
            OutputBuilder output) {
        for (DataState other : new ArrayList<>(dataByService.values())) {
            if (other == joined || other.stateClosed || other.pendingFrames.isEmpty()) {
                continue;
            }
            flushPendingApplication(other, output);
        }
    }

    private void flushPendingLinkControl(
            DataState state,
            OutputBuilder output) {
        if (state == null || state.stateClosed || state.pendingFrames.isEmpty()) {
            return;
        }
        List<PendingFrame> queued = new ArrayList<>(state.pendingFrames);
        state.pendingFrames.clear();
        for (PendingFrame queuedFrame : queued) {
            if (isSocketPairLinkControl(queuedFrame.bytes)) {
                try {
                            sendSocketPairFrame(state, queuedFrame.bytes, queuedFrame.fragmentedMessageId, output);
                        } finally { queuedFrame.close(); }
            } else {
                state.pendingFrames.add(queuedFrame);
            }
        }
    }

    private void flushPendingApplication(
            DataState state,
            OutputBuilder output) {
        if (state == null || state.stateClosed || state.pendingFrames.isEmpty()) {
            return;
        }
        List<PendingFrame> queued = new ArrayList<>(state.pendingFrames);
        state.pendingFrames.clear();
        for (PendingFrame queuedFrame : queued) {
            try {
                            sendSocketPairFrame(state, queuedFrame.bytes, queuedFrame.fragmentedMessageId, output);
                        } finally { queuedFrame.close(); }
        }
    }

    /** Transport ACK echoes the received sequence; AppAck is a new outgoing message. */
    private void acknowledgeCommonMessage(
            DataState state,
            IdsSocketPairCodec.Message message,
            OutputBuilder output,
            String resolvedTopic) {
        long sequence;
        int flags;
        String messageUuid;
        if (message instanceof IdsSocketPairCodec.ProtobufMessage protobuf) {
            sequence = protobuf.sequence;
            flags = protobuf.flags;
            messageUuid = protobuf.messageUuid;
        } else if (message instanceof IdsSocketPairCodec.DataMessage data) {
            sequence = data.sequence;
            flags = data.flags;
            messageUuid = data.messageUuid;
        } else {
            return;
        }
        byte[] ack = IdsSocketPairCodec.encodeAck(sequence, false);
        try {
            sendSocketPairFrame(state, ack, null, output);
            output.events.add(
                    SessionEvent.simple(
                            EventType.ACK_SENT,
                            state.connection != null ? state.connection.connectionId : 0,
                            state.service,
                            null));
        } finally {
            wipe(ack);
        }
        if ((flags & IdsSocketPairCodec.FLAG_WANTS_APP_ACK) == 0) {
            return;
        }
        // Inline topics teach the outgoing map, while topic-less incoming
        // messages use our incoming map. Their numeric IDs can differ. Never
        // reflect the received ID into an outgoing application receipt.
        IdsServiceMapState.OutgoingRoute mapped = control.serviceMap().routeOutgoing(resolvedTopic);
        MessageMetadata metadata = applicationMessages.nextAppAckForIdentifier(messageUuid);
        IdsSocketPairCodec.AppAckMessage appAck =
                new IdsSocketPairCodec.AppAckMessage(
                        metadata.sequence,
                        mapped.streamId,
                        metadata.peerResponseIdentifier,
                        mapped.wireTopic());
        byte[] appAckFrame = null;
        try {
            appAckFrame = IdsSocketPairCodec.encodeAppAck(appAck);
            sendSocketPairFrame(state, appAckFrame, null, output);
            output.events.add(
                    SessionEvent.appAck(
                            EventType.APP_ACK_SENT,
                            state.connection != null ? state.connection.connectionId : 0,
                            state.service,
                            resolvedTopic,
                            appAck));
        } finally {
            wipe(appAckFrame);
            appAck.destroy();
        }
    }

    private static boolean isSocketPairLinkControl(byte[] frame) {
        if (frame == null || frame.length == 0) {
            return false;
        }
        int command = frame[0] & 0xff;
        return command == IdsSocketPairCodec.COMMAND_HANDSHAKE
                || command == IdsSocketPairCodec.COMMAND_ACK
                || command == IdsSocketPairCodec.COMMAND_EXPIRED_ACK
                || command == IdsSocketPairCodec.COMMAND_KEEP_ALIVE;
    }

    private void acceptDataBytes(
            long connectionId,
            byte[] bytes,
            OutputBuilder output) {
        if (control == null) {
            control = IdsControlChannelSession.createDefaultPaired(
                    random,
                    localIdsDeviceUuid,
                    directMessagingSupported,
                    IdsServiceMapState.APPLE_FIRST_STREAM_ID);
        }
        DataState state =
                dataByConnection.get(
                        connectionId);
        if (state == null && !dataByService.isEmpty()) {
            for (DataState candidate : dataByService.values()) {
                if (candidate.connection != null && candidate.connection.connectionId == connectionId) {
                    state = candidate;
                    break;
                }
            }
            if (state == null) {
                OrdinaryIkeAuth.DataClass dc = transport.dataClassOf(connectionId);
                String connService = transport.serviceNameOf(connectionId);
                for (DataState candidate : dataByService.values()) {
                    if (candidate.connection == null) {
                        boolean matches = (connService != null && serviceMatches(connService, candidate.service))
                                || (dc == OrdinaryIkeAuth.DataClass.CLASS_C && isRelayLaneC(candidate.service))
                                || (dc == OrdinaryIkeAuth.DataClass.CLASS_D && isRelayLaneD(candidate.service));
                        if (matches) {
                            attachDataTransport(candidate, new DataTransport(connectionId, candidate.service), output);
                            state = candidate;
                            break;
                        }
                    }
                }
            }
        }
        if (state == null) {
            OrdinaryIkeAuth.DataClass dc = transport.dataClassOf(connectionId);
            boolean isClassC = dc == OrdinaryIkeAuth.DataClass.CLASS_C;
            String serviceName = isClassC
                    ? NanoRegistryPropertyCodec.CLASS_C_SERVICE
                    : NanoRegistryPropertyCodec.CLASS_D_SERVICE;
            int protectionClass = isClassC
                    ? IdsUtunConnectionName.PROTECTION_CLASS_C
                    : IdsUtunConnectionName.PROTECTION_CLASS_D;
            IdsServiceConnectorName name =
                    IdsServiceConnectorName.localDelivery(
                            IdsUtunConnectionName.defaultPaired(
                                    IdsUtunConnectionName.PRIORITY_URGENT,
                                    protectionClass));
            IdsIpsecServiceRoute route =
                    IdsIpsecServiceRoute.localDelivery(name);
            IdsEncryptedDataChannelSession session =
                    IdsEncryptedDataChannelSession.openPlainAdopted(
                            random,
                            name,
                            IdsControlChannelCodec.DATA_PORT,
                            IdsControlChannelCodec.DATA_PORT);
            state = new DataState(serviceName, route, session);
            state.connection = new DataTransport(connectionId, serviceName);
            dataByService.put(serviceName, state);
            dataByConnection.put(connectionId, state);
            output.events.add(
                    SessionEvent.simple(
                            EventType.DATA_CHANNEL_JOINED,
                            connectionId,
                            serviceName,
                            null));
            flushPendingLinkControl(state, output);
            flushPendingApplication(state, output);
            flushPendingFrames(state, output);
        }
        if (state == null) {
            throw new IllegalStateException(
                    "Unknown IDS data connection");
        }
        List<IdsSocketPairCodec.Message> messages =
                state.decoder.push(
                        bytes);
        try {
            for (IdsSocketPairCodec.Message message :
                    messages) {
                if (message
                        instanceof IdsSocketPairCodec.FragmentMessage
                        fragment) {
                    IdsSocketPairCodec.Message reassembled =
                            state.fragments.accept(
                                    fragment);
                    if (reassembled != null) {
                        try {
                            processSocketPairMessage(
                                    state,
                                    reassembled,
                                    output);
                        } finally {
                            reassembled.destroy();
                        }
                    }
                } else {
                    processSocketPairMessage(
                            state,
                            message,
                            output);
                }
            }
        } finally {
            destroySocketMessages(
                    messages);
        }
    }

    private void processSocketPairMessage(
            DataState state,
            IdsSocketPairCodec.Message message,
            OutputBuilder output) {
        requireControlReady();
        long connectionId =
                state.connection != null
                        ? state.connection.connectionId
                        : 0;
        if (message instanceof IdsSocketPairCodec.UnknownMessage) {
            output.events.add(new SessionEvent(EventType.UNKNOWN_COMMAND_IGNORED,
                    connectionId, state.service, null, null, null, 0, 0,
                    message.command, 0, -1, false, null, null, null));
            java.util.function.Consumer<String> logger =
                    diagnosticLogger;
            if (logger != null) {
                logger.accept(
                        "IDS UNKNOWN COMMAND RX: service="
                                + state.service
                                + " command=0x"
                                + Integer.toHexString(
                                        message.command)
                                + " bodyHexPrefix="
                                + ((IdsSocketPairCodec.UnknownMessage) message)
                                .bodyHexPrefix);
            }
            return;
        }
        if (message
                instanceof IdsSocketPairCodec.ServiceMapMessage map) {
            control.serviceMap()
                    .acceptServiceMap(
                            map);
            output.events.add(
                    SessionEvent.simple(
                            EventType.SERVICE_MAP_RECEIVED,
                            connectionId,
                            state.service,
                            map.serviceName));
            return;
        }
        if (message
                instanceof IdsSocketPairCodec.AckMessage ack) {
            output.events.add(
                    SessionEvent.ack(
                            connectionId,
                            state.service,
                            ack));
            return;
        }
        if (message
                instanceof IdsSocketPairCodec.KeepAliveMessage) {
            output.events.add(
                    SessionEvent.simple(
                            EventType.KEEP_ALIVE_RECEIVED,
                            connectionId,
                            state.service,
                            null));
            return;
        }
        if (message instanceof IdsSocketPairCodec.DataMessage resource
                && resource.command == IdsSocketPairCodec.COMMAND_RESOURCE_TRANSFER) {
            // 23S303 sends a resource control payload 03 <reason> when it
            // removes an outgoing sender. This is not a BE64 file offset.
            // Keep the incomplete file for diagnostics; cancellation neither
            // completes it nor warrants an IDS/AppAck.
            if (resource.payload.length == 2 && resource.payload[0] == 3) {
                if (diagnosticLogger != null) diagnosticLogger.accept(
                        "RESOURCE RX CANCELLED: uuid=" + resource.messageUuid
                                + " sequence=" + resource.sequence
                                + " reason=" + (resource.payload[1] & 255)
                                + "; partial preserved; resource not completed.");
                return;
            }
            PairedSyncResourceStore.Result stored;
            try {
                stored = PairedSyncResourceStore.append(resource);
            } catch (RuntimeException unavailableResource) {
                if (diagnosticLogger != null) diagnosticLogger.accept("RESOURCE RX REJECTED: "
                        + unavailableResource.getClass().getSimpleName()
                        + (unavailableResource.getMessage() != null
                            && unavailableResource.getMessage().startsWith("Resource chunk leaves a gap:")
                                ? " " + unavailableResource.getMessage() : "")
                        + "; resource not completed; application values logged=false.");
                return;
            }
            // 23S303 _processDecryptedMessage's resource branch bypasses
            // _sendAckForMessage:. The receiver dispatches the message only
            // after finalizedMessageDictionaryIfDone returns the whole file.
            // An IDS ACK retires the sender's message, not a byte chunk. TCP
            // and ERTM still acknowledge transport receipt independently.
            IdsServiceMapState.IncomingRoute mapped = resolveIncomingRoute(state, message, output);
            if (stored.savedName != null) {
                acknowledgeCommonMessage(state, message, output, mapped.serviceName);
            }
            java.util.function.Consumer<String> logger = diagnosticLogger;
            if (logger != null) {
                logger.accept(
                        "RESOURCE RX: topic="
                                + (resource.topic != null ? resource.topic : state.service)
                                + " uuid="
                                + resource.messageUuid
                                + " bytes="
                                + stored.chunkBytes
                                + " total="
                                + stored.totalBytes
                                + " sequence="
                                + resource.sequence
                                + (stored.savedName != null
                                ? " file=" + stored.savedName
                                : ""));
            }
            return;
        }
        if (message
                instanceof IdsSocketPairCodec.HandshakeMessage) {
            state.handshakeReceived = true;
            output.events.add(
                    SessionEvent.simple(
                            EventType.HANDSHAKE_RECEIVED,
                            connectionId,
                            state.service,
                            null));
            sendDataHandshake(
                    state,
                    output);
            flushPendingApplication(state, output);
            flushPendingFrames(state, output);
            return;
        }
        if (!(message
                instanceof IdsSocketPairCodec.DataMessage)
                && !(message
                instanceof IdsSocketPairCodec.ProtobufMessage)
                && !(message
                instanceof IdsSocketPairCodec.AppAckMessage)) {
            throw new IllegalArgumentException(
                    "Unsupported IDS socket-pair application command");
        }

        IdsServiceMapState.IncomingRoute mapped = resolveIncomingRoute(state, message, output);
        acknowledgeCommonMessage(state, message, output, mapped.serviceName);
        try {
            validateApplicationTopicRoute(
                    state,
                    mapped.serviceName);
        } catch (IllegalArgumentException ignoredLane) {
            // A NanoRegistry snapshot on the other protection-class lane
            // must not poison the IDS session. The frame is still delivered
            // to the setup adapter.
        }
        if (message
                instanceof IdsSocketPairCodec.ProtobufMessage protobuf) {
            try {
                validateApplicationEnvelope(
                        mapped.serviceName,
                        protobuf);
            } catch (IllegalArgumentException ignoredEnvelope) {
                // Unknown or response-flagged NanoRegistry payloads are
                // still surfaced as PROTOBUF_RECEIVED. Live 0.2.114 never
                // logged that event after PropertyRequest; a thrown decode
                // here poisons accept() and drops ACL.
            }
            pairedSyncCapability.observeInboundService(
                    mapped.serviceName);
            output.events.add(
                    SessionEvent.protobuf(
                            EventType.PROTOBUF_RECEIVED,
                            connectionId,
                            state.service,
                            mapped.serviceName,
                            protobuf));
        } else if (message
                instanceof IdsSocketPairCodec.DataMessage data) {
            output.events.add(
                    SessionEvent.data(
                            connectionId,
                            state.service,
                            mapped.serviceName,
                            data));
        } else {
            IdsSocketPairCodec.AppAckMessage appAck =
                    (IdsSocketPairCodec.AppAckMessage)
                            message;
            output.events.add(
                    SessionEvent.appAck(
                            EventType.APP_ACK_RECEIVED,
                            connectionId,
                            state.service,
                            mapped.serviceName,
                            appAck));
        }
    }

    private IdsServiceMapState.IncomingRoute resolveIncomingRoute(
            DataState state, IdsSocketPairCodec.Message message, OutputBuilder output) {
        String fallbackService = state.service;
        if (isRelayLaneC(state.service)) {
            fallbackService = NanoRegistryPropertyCodec.CLASS_C_SERVICE;
        } else if (isRelayLaneD(state.service)) {
            fallbackService = NanoRegistryPropertyCodec.CLASS_D_SERVICE;
        }
        IdsServiceMapState.IncomingRoute mapped = control.serviceMap().acceptIncoming(message, fallbackService);
        if (mapped.advertisement != null) {
            byte[] frame = null;
            try {
                frame = IdsSocketPairCodec.encodeServiceMap(mapped.advertisement);
                long connectionId = state.connection != null ? state.connection.connectionId : 0;
                sendApplication(connectionId, frame, output);
                output.events.add(SessionEvent.simple(EventType.SERVICE_MAP_SENT,
                        connectionId, state.service, mapped.serviceName));
            } finally {
                wipe(frame);
                mapped.advertisement.destroy();
            }
        }
        return mapped;
    }

    private void validateApplicationEnvelope(
            String topic,
            IdsSocketPairCodec.ProtobufMessage envelope) {
        if (NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(
                topic)) {
            try {
                NanoRegistryClassDCodec.ApplicationMessage decoded =
                        NanoRegistryClassDCodec.decode(
                                envelope);
                decoded.destroy();
                return;
            } catch (IllegalArgumentException classD) {
                NanoRegistryPropertyCodec.ApplicationMessage classC =
                        NanoRegistryPropertyCodec.decode(
                                envelope);
                classC.destroy();
                return;
            }
        }
        if (NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(
                topic)) {
            NanoRegistryPropertyCodec.ApplicationMessage decoded =
                    NanoRegistryPropertyCodec.decode(
                            envelope);
            try {
                if (decoded
                        instanceof NanoRegistryPropertyCodec
                        .PropertiesChanged changed) {
                    pairedSyncCapability.apply(
                            changed);
                }
            } finally {
                decoded.destroy();
            }
            return;
        }
        if (PbBridgeCodec.SERVICE.equals(
                topic)) {
            PbBridgeCodec.validateEnvelope(
                    envelope);
            return;
        }
        if (PairedSyncCodec.isService(
                topic)) {
            PairedSyncCodec.validateEnvelope(
                    envelope);
            return;
        }
        // Unknown or supplementary watchOS topic: allow without failing the session
    }

    private void validateApplicationTopicRoute(
            DataState state,
            String topic) {
        IdsApplicationRoute route;
        try {
            route = IdsApplicationRoute.forTopic(topic);
        } catch (IllegalArgumentException unknown) {
            return;
        }
        if (state.service.equals(topic)
                || state.service.equals(route.topic)
                || state.service.equals(
                route.serviceConnectorName.encode())) {
            return;
        }
        boolean isLaneD = isRelayLaneD(state.service);
        boolean isLaneC = isRelayLaneC(state.service);
        boolean matchD = isLaneD && route.idsProtectionClass == IdsUtunConnectionName.PROTECTION_CLASS_D;
        boolean matchC = isLaneC && (route.idsProtectionClass == IdsUtunConnectionName.PROTECTION_CLASS_C
                || route.idsProtectionClass == IdsUtunConnectionName.PROTECTION_CLASS_C_ALTERNATE);
        if (!matchD && !matchC) {
            throw new IllegalArgumentException(
                    "Application topic arrived on the wrong IDS lane: " + state.service + " vs " + topic);
        }
    }

    private void sendDataHandshake(
            DataState state,
            OutputBuilder output) {
        if (state == null
                || state.handshakeSent
                || state.connection == null
                || state.connection.transportGone) {
            return;
        }
        byte[] frame =
                IdsSocketPairCodec.encodeHandshake(
                        1);
        try {
            sendSocketPairFrame(
                    state,
                    frame,
                    null,
                    output);
            state.handshakeSent =
                    true;
            output.events.add(
                    SessionEvent.simple(
                            EventType.HANDSHAKE_SENT,
                            state.connection.connectionId,
                            state.service,
                            null));
        } finally {
            wipe(
                    frame);
        }
    }

    private void sendSocketPairFrame(
            DataState state,
            byte[] frame,
            Long fragmentedMessageId,
            OutputBuilder output) {
        if (state == null) {
            throw new IllegalArgumentException(
                    "IDS data channel state is absent");
        }
        long connectionId = state.connection != null && !state.connection.transportGone
                ? state.connection.connectionId : 0;
        if (connectionId != 0 && !transport.isServiceActive(connectionId)) {
            state.connection = null;
            connectionId = 0;
        }
        if (connectionId == 0) {
            for (DataState candidate : dataByService.values()) {
                if (candidate.connection == null || candidate.connection.transportGone || !transport.isServiceActive(candidate.connection.connectionId)) {
                    continue;
                }
                if (!serviceMatches(state.service, candidate.service)) {
                    continue;
                }
                if (isRelayLaneC(state.service) != isRelayLaneC(candidate.service)
                        || isRelayLaneD(state.service) != isRelayLaneD(candidate.service)) {
                    continue;
                }
                if (IdsUtunConnectionName.isConcreteUtunName(state.service)
                        && IdsUtunConnectionName.isConcreteUtunName(candidate.service)
                        && !state.service.equals(candidate.service)
                        && (isRelayLaneC(state.service) != isRelayLaneC(candidate.service)
                        || isRelayLaneD(state.service) != isRelayLaneD(candidate.service))) {
                    continue;
                }
                connectionId = candidate.connection.connectionId;
                state.connection = candidate.connection;
                state.handshakeReceived = candidate.handshakeReceived;
                if (candidate.handshakeReceived) {
                    state.awaitIncomingConnector = false;
                }
                break;
            }
        }
        boolean linkControl = isSocketPairLinkControl(frame);
        boolean waitForHandshake = state.awaitIncomingConnector
                && !state.handshakeReceived
                && !linkControl;
        if (connectionId == 0
                || !transport.isServiceActive(connectionId)
                || waitForHandshake) {
            long queuedBytes = state.pendingFrames.stream().mapToLong(item -> item.bytes.length).sum();
            if (state.pendingFrames.size() >= 256 || queuedBytes + frame.length > 4L * 1024 * 1024) {
                throw new IllegalStateException("IDS pending application queue exceeded its capacity");
            }
            state.pendingFrames.add(new PendingFrame(frame, fragmentedMessageId));
            if (connectionId == 0 || !transport.isServiceActive(connectionId)) {
                state.connection = null;
                state.needsReplacement = true;
                if (!state.awaitIncomingConnector) {
                    startDataConnector(state, output);
                }
            }
            return;
        }
        if (frame.length
                <= IdsSocketPairCodec.FRAGMENT_FRAME_LIMIT) {
            sendApplication(
                    connectionId,
                    frame,
                    output);
            return;
        }
        long fragmentId =
                fragmentedMessageId != null
                        ? fragmentedMessageId
                        : takeFragmentedMessageId();
        List<byte[]> fragments =
                IdsSocketPairCodec.fragment(
                        fragmentId,
                        frame);
        try {
            for (byte[] fragment :
                    fragments) {
                sendApplication(
                        connectionId,
                        fragment,
                        output);
            }
        } finally {
            wipeAll(
                    fragments);
        }
    }

    private long takeFragmentedMessageId() {
        if (nextFragmentedMessageId > 0xffff_ffffL) {
            throw new IllegalStateException(
                    "IDS fragment ID space is exhausted");
        }
        long fragmentId =
                nextFragmentedMessageId;
        nextFragmentedMessageId++;
        return fragmentId;
    }

    private void sendApplication(
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

    private void handleSuperseded(
            long relatedConnectionId) {
        if (relatedConnectionId == 0) {
            return;
        }
        if (relatedConnectionId
                == controlConnectionId) {
            resetControlState();
            return;
        }
        DataTransport connection =
                dataTransports.remove(
                        relatedConnectionId);
        if (connection == null) {
            return;
        }
        connection.transportGone = true;
        DataState state =
                dataByConnection.remove(
                        relatedConnectionId);
        if (state != null
                && state.connection
                == connection) {
            state.connection = null;
            state.needsReplacement = true;
            state.resetTransport();
        }
    }

    private void releaseDataTransport(
            DataTransport connection) {
        if (connection == null
                || connection.released) {
            return;
        }
        connection.released = true;
        dataTransports.remove(
                connection.connectionId);
        DataState state =
                dataByConnection.remove(
                        connection.connectionId);
        if (state != null
                && state.connection
                == connection) {
            state.connection = null;
        }
        if (connection.transportGone
                || closing
                || closed) {
            return;
        }
        try (IdsServiceConnectorCoordinator.PacketBatch closedOutput =
                     transport.closeConnection(
                             connection.connectionId)) {
            List<IdsServiceConnectorCoordinator.RoutedPacket> packets =
                    closedOutput.packets();
            try {
                for (IdsServiceConnectorCoordinator.RoutedPacket packet :
                        packets) {
                    deferredPackets.add(
                            routedPacket(
                                    packet));
                }
            } finally {
                closeTransportPackets(
                        packets);
            }
        }
    }

    private void resetControlState() {
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

    private IdsDataChannelJoinState<DataTransport> newJoinState() {
        return new IdsDataChannelJoinState<>(
                this::releaseDataTransport);
    }

    private static boolean isRelayLaneD(String service) {
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

    private static boolean isRelayLaneC(String service) {
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

    private static boolean serviceMatches(String requested, String candidate) {
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

    private static boolean isCloudLane(String service) {
        String name = IdsUtunConnectionName.lastComponent(service);
        if (name.endsWith("-Relay")) name = name.substring(0, name.length() - 6);
        return IdsUtunConnectionName.isDefaultPairedCloudIdentifier(name);
    }

    private DataState requireJoinedDataState(
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
            state = new DataState(service, route, setup);
            dataByService.put(service, state);
        }
        if (state == null || state.stateClosed) {
            throw new IllegalStateException(
                    "IDS data lane is not joined for " + service);
        }
        return state;
    }

    private void requireControlReady() {
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

    private RuntimeException fail(
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

    private void requireUsable() {
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

    private static boolean isStaleCollisionPacket(
            IllegalArgumentException failure) {
        if (failure == null || failure.getMessage() == null) {
            return false;
        }
        String msg = failure.getMessage();
        return msg.equals("Inbound IDS TCP SYN targets no listener")
                || msg.equals("Unknown IDS TCP tuple did not start with a SYN")
                || msg.contains("Unknown IDS TCP connection");
    }

    private static boolean sameRoute(
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

    private static void requireCanonicalUuid(
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

    private static void addPacket(
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

    private static void addPackets(
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

    private static RoutedPacket routedPacket(
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

    private void drainDeferredPackets(
            OutputBuilder output) {
        output.packets.addAll(
                deferredPackets);
        deferredPackets.clear();
    }

    private static void closeTransportPackets(
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

    private static void destroyControlMessages(
            List<IdsControlChannelCodec.Message> messages) {
        for (IdsControlChannelCodec.Message message :
                messages) {
            message.destroy();
        }
    }

    private static int controlMessageLength(
            IdsControlChannelCodec.Message message) {
        if (message instanceof IdsControlChannelCodec.GenericControlMessage generic) {
            return generic.rawPayload.length;
        }
        if (message instanceof IdsControlChannelCodec.DirectMsgInfoMessage info) {
            return info.info.length;
        }
        return -1;
    }

    private static String controlMessageDetail(
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

    private static void destroySocketMessages(
            List<IdsSocketPairCodec.Message> messages) {
        for (IdsSocketPairCodec.Message message :
                messages) {
            message.destroy();
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
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

        private Output(
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

        private RoutedPacket(
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

        private SessionEvent(
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

        private static SessionEvent simple(
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

        private static SessionEvent controlMessage(
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

        private static SessionEvent controlReady(
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

        private static SessionEvent transport(
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

        private static SessionEvent data(
                long connectionId,
                String service,
                String topic,
                IdsSocketPairCodec.DataMessage message) {
            return data(EventType.DATA_RECEIVED, connectionId, service, topic, message);
        }

        private static SessionEvent data(EventType type, long connectionId, String service,
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

    private final class DataTransport {
        final long connectionId;
        String service;
        boolean transportGone;
        boolean released;

        private DataTransport(
                long connectionId,
                String service) {
            this.connectionId =
                    connectionId;
            this.service = service;
        }

        private void release() {
            releaseDataTransport(
                    this);
        }
    }

    private static final class PendingFrame implements AutoCloseable {
        final byte[] bytes;
        final Long fragmentedMessageId;
        PendingFrame(byte[] bytes, Long fragmentedMessageId) {
            this.bytes = bytes.clone();
            this.fragmentedMessageId = fragmentedMessageId;
        }
        @Override public void close() { wipe(bytes); }
    }

    private final class DataState
            implements AutoCloseable {
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

        private DataState(
                String service,
                IdsIpsecServiceRoute route,
                IdsEncryptedDataChannelSession setup) {
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

    private static final class OutputBuilder
            implements AutoCloseable {
        final List<RoutedPacket> packets =
                new ArrayList<>();
        final List<SessionEvent> events =
                new ArrayList<>();
        boolean transferred;

        private Output build() {
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
