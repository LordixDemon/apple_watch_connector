package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.List;

/** IDS data-channel connector attachment, socket-pair framing, fragmentation, and ACKs. */
final class IdsModernDataChannelEngine {
    private final IdsModernSessionCoordinator coord;

    IdsModernDataChannelEngine(IdsModernSessionCoordinator coord) {
        this.coord = coord;
    }

    void startDataConnector(
            IdsModernSessionCoordinator.DataState state,
            IdsModernSessionCoordinator.OutputBuilder output) {
        if (state.connection != null) {
            return;
        }
        try (IdsServiceConnectorCoordinator.OpenResult opened =
                     coord.transport.openService(
                             state.route)) {
            IdsModernSessionCoordinator.addPacket(
                    output,
                    opened.packet());
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.DATA_CONNECT_STARTED,
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
    void startPendingDataConnectors(
            IdsModernSessionCoordinator.OutputBuilder output) {
        if (coord.control == null
                || !coord.control.remoteHelloReceived()) {
            return;
        }
        for (IdsModernSessionCoordinator.DataState state : new ArrayList<>(coord.dataByService.values())) {
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

    void acceptDataService(
            IdsServiceConnectorCoordinator.ConnectionEvent event,
            IdsModernSessionCoordinator.OutputBuilder output) {
        String serviceName = event.serviceName != null && !"none".equals(event.serviceName)
                ? event.serviceName
                : (event.route != null ? event.route.service : NanoRegistryPropertyCodec.CLASS_D_SERVICE);
        if (serviceName == null) {
            serviceName = NanoRegistryPropertyCodec.CLASS_D_SERVICE;
        }
        if (coord.dataTransports.containsKey(
                event.connectionId)) {
            return;
        }
        IdsModernSessionCoordinator.DataTransport connection =
                new IdsModernSessionCoordinator.DataTransport(coord, 
                        event.connectionId,
                        serviceName);
        coord.dataTransports.put(
                event.connectionId,
                connection);
        output.events.add(
                IdsModernSessionCoordinator.SessionEvent.simple(
                        IdsModernSessionCoordinator.EventType.DATA_SERVICE_ACCEPTED,
                        event.connectionId,
                        serviceName,
                        null));

        IdsModernSessionCoordinator.DataState existing =
                coord.dataByService.get(
                        serviceName);
        if (existing == null && !coord.dataByService.isEmpty()) {
            for (IdsModernSessionCoordinator.DataState candidate : coord.dataByService.values()) {
                if (IdsModernSessionCoordinator.serviceMatches(serviceName, candidate.service)) {
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
            IdsDataChannelJoinState.Result<IdsModernSessionCoordinator.DataTransport> joined =
                    coord.dataJoins.receiveServiceConnection(
                            serviceName,
                            connection);
            if (joined.action
                    == IdsDataChannelJoinState.Action.JOINED) {
                IdsModernSessionCoordinator.DataState state =
                        coord.dataByService.get(
                                serviceName);
                if (state == null && joined.connectorService != null) {
                    state = coord.dataByService.get(joined.connectorService);
                }
                if (state == null && !coord.dataByService.isEmpty()) {
                    for (IdsModernSessionCoordinator.DataState candidate : coord.dataByService.values()) {
                        if (IdsModernSessionCoordinator.serviceMatches(serviceName, candidate.service)
                                || (joined.connectorService != null && IdsModernSessionCoordinator.serviceMatches(joined.connectorService, candidate.service))) {
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
                                    coord.setupPorts,
                                    coord.ssrcs,
                                    coord.random,
                                    parsed);
                    state = new IdsModernSessionCoordinator.DataState(coord, serviceName, route, setup);
                    coord.dataByService.put(serviceName, state);
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
                IdsModernSessionCoordinator.DataState created =
                        new IdsModernSessionCoordinator.DataState(coord, 
                                serviceName,
                                IdsIpsecServiceRoute.localDelivery(
                                        parsed),
                                IdsEncryptedDataChannelSession.openOutgoing(
                                        coord.setupPorts,
                                        coord.ssrcs,
                                        coord.random,
                                        parsed));
                coord.dataByService.put(
                        serviceName,
                        created);
                attachDataTransport(
                        created,
                        connection,
                        output);
            }
        } catch (IllegalArgumentException notCanonicalConnector) {
            for (IdsModernSessionCoordinator.DataState candidate : coord.dataByService.values()) {
                if (candidate.connection == null && (IdsModernSessionCoordinator.serviceMatches(serviceName, candidate.service) || candidate.service.contains(serviceName))) {
                    attachDataTransport(candidate, connection, output);
                    break;
                }
            }
        }
    }

    void attachDataTransport(
            IdsModernSessionCoordinator.DataState state,
            IdsModernSessionCoordinator.DataTransport connection,
            IdsModernSessionCoordinator.OutputBuilder output) {
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
            coord.dataByConnection.remove(state.connection.connectionId);
            coord.dataTransports.remove(state.connection.connectionId);
            state.connection = null;
            state.handshakeSent = false;
            state.handshakeReceived = false;
        }
        state.resetTransport();
        state.connection =
                connection;
        state.needsReplacement = false;
        coord.dataByConnection.put(
                connection.connectionId,
                state);
        output.events.add(
                IdsModernSessionCoordinator.SessionEvent.simple(
                        IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
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
    String pendingApplicationFramesSummary() {
        StringBuilder summary =
                new StringBuilder();
        for (IdsModernSessionCoordinator.DataState state : coord.dataByService.values()) {
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
                            && coord.transport.isServiceActive(connectionId))
                    .append(" established=")
                    .append(connectionId != 0
                            && coord.transport.isEstablished(connectionId))
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

    boolean hasPendingApplicationFrames() {
        if (coord.closed) {
            return false;
        }
        for (IdsModernSessionCoordinator.DataState state : coord.dataByService.values()) {
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
    IdsModernSessionCoordinator.Output flushPendingApplicationFrames() {
        coord.requireUsable();
        IdsModernSessionCoordinator.OutputBuilder output =
                new IdsModernSessionCoordinator.OutputBuilder();
        try {
            flushPendingFrames(
                    null,
                    output);
            coord.drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw coord.fail(
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
    void flushPendingFrames(
            IdsModernSessionCoordinator.DataState joined,
            IdsModernSessionCoordinator.OutputBuilder output) {
        for (IdsModernSessionCoordinator.DataState other : new ArrayList<>(coord.dataByService.values())) {
            if (other == joined || other.stateClosed || other.pendingFrames.isEmpty()) {
                continue;
            }
            flushPendingApplication(other, output);
        }
    }

    void flushPendingLinkControl(
            IdsModernSessionCoordinator.DataState state,
            IdsModernSessionCoordinator.OutputBuilder output) {
        if (state == null || state.stateClosed || state.pendingFrames.isEmpty()) {
            return;
        }
        List<IdsModernSessionCoordinator.PendingFrame> queued = new ArrayList<>(state.pendingFrames);
        state.pendingFrames.clear();
        for (IdsModernSessionCoordinator.PendingFrame queuedFrame : queued) {
            if (isSocketPairLinkControl(queuedFrame.bytes)) {
                try {
                            sendSocketPairFrame(state, queuedFrame.bytes, queuedFrame.fragmentedMessageId, output);
                        } finally { queuedFrame.close(); }
            } else {
                state.pendingFrames.add(queuedFrame);
            }
        }
    }

    void flushPendingApplication(
            IdsModernSessionCoordinator.DataState state,
            IdsModernSessionCoordinator.OutputBuilder output) {
        if (state == null || state.stateClosed || state.pendingFrames.isEmpty()) {
            return;
        }
        List<IdsModernSessionCoordinator.PendingFrame> queued = new ArrayList<>(state.pendingFrames);
        state.pendingFrames.clear();
        for (IdsModernSessionCoordinator.PendingFrame queuedFrame : queued) {
            try {
                            sendSocketPairFrame(state, queuedFrame.bytes, queuedFrame.fragmentedMessageId, output);
                        } finally { queuedFrame.close(); }
        }
    }

    /** Transport ACK echoes the received sequence; AppAck is a new outgoing message. */
    void acknowledgeCommonMessage(
            IdsModernSessionCoordinator.DataState state,
            IdsSocketPairCodec.Message message,
            IdsModernSessionCoordinator.OutputBuilder output,
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
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.ACK_SENT,
                            state.connection != null ? state.connection.connectionId : 0,
                            state.service,
                            null));
        } finally {
            IdsModernSessionCoordinator.wipe(ack);
        }
        if ((flags & IdsSocketPairCodec.FLAG_WANTS_APP_ACK) == 0) {
            return;
        }
        // Inline topics teach the outgoing map, while topic-less incoming
        // messages use our incoming map. Their numeric IDs can differ. Never
        // reflect the received ID into an outgoing application receipt.
        IdsServiceMapState.OutgoingRoute mapped = coord.control.serviceMap().routeOutgoing(resolvedTopic);
        IdsModernSessionCoordinator.MessageMetadata metadata = coord.applicationMessages.nextAppAckForIdentifier(messageUuid);
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
                    IdsModernSessionCoordinator.SessionEvent.appAck(
                            IdsModernSessionCoordinator.EventType.APP_ACK_SENT,
                            state.connection != null ? state.connection.connectionId : 0,
                            state.service,
                            resolvedTopic,
                            appAck));
        } finally {
            IdsModernSessionCoordinator.wipe(appAckFrame);
            appAck.destroy();
        }
    }

    static boolean isSocketPairLinkControl(byte[] frame) {
        if (frame == null || frame.length == 0) {
            return false;
        }
        int command = frame[0] & 0xff;
        return command == IdsSocketPairCodec.COMMAND_HANDSHAKE
                || command == IdsSocketPairCodec.COMMAND_ACK
                || command == IdsSocketPairCodec.COMMAND_EXPIRED_ACK
                || command == IdsSocketPairCodec.COMMAND_KEEP_ALIVE;
    }

    void acceptDataBytes(
            long connectionId,
            byte[] bytes,
            IdsModernSessionCoordinator.OutputBuilder output) {
        if (coord.control == null) {
            coord.control = IdsControlChannelSession.createDefaultPaired(
                    coord.random,
                    coord.localIdsDeviceUuid,
                    coord.directMessagingSupported,
                    IdsServiceMapState.APPLE_FIRST_STREAM_ID);
        }
        IdsModernSessionCoordinator.DataState state =
                coord.dataByConnection.get(
                        connectionId);
        if (state == null && !coord.dataByService.isEmpty()) {
            for (IdsModernSessionCoordinator.DataState candidate : coord.dataByService.values()) {
                if (candidate.connection != null && candidate.connection.connectionId == connectionId) {
                    state = candidate;
                    break;
                }
            }
            if (state == null) {
                OrdinaryIkeAuth.DataClass dc = coord.transport.dataClassOf(connectionId);
                String connService = coord.transport.serviceNameOf(connectionId);
                for (IdsModernSessionCoordinator.DataState candidate : coord.dataByService.values()) {
                    if (candidate.connection == null) {
                        boolean matches = (connService != null && IdsModernSessionCoordinator.serviceMatches(connService, candidate.service))
                                || (dc == OrdinaryIkeAuth.DataClass.CLASS_C && IdsModernSessionCoordinator.isRelayLaneC(candidate.service))
                                || (dc == OrdinaryIkeAuth.DataClass.CLASS_D && IdsModernSessionCoordinator.isRelayLaneD(candidate.service));
                        if (matches) {
                            attachDataTransport(candidate, new IdsModernSessionCoordinator.DataTransport(coord, connectionId, candidate.service), output);
                            state = candidate;
                            break;
                        }
                    }
                }
            }
        }
        if (state == null) {
            OrdinaryIkeAuth.DataClass dc = coord.transport.dataClassOf(connectionId);
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
                            coord.random,
                            name,
                            IdsControlChannelCodec.DATA_PORT,
                            IdsControlChannelCodec.DATA_PORT);
            state = new IdsModernSessionCoordinator.DataState(coord, serviceName, route, session);
            state.connection = new IdsModernSessionCoordinator.DataTransport(coord, connectionId, serviceName);
            coord.dataByService.put(serviceName, state);
            coord.dataByConnection.put(connectionId, state);
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
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
            IdsModernSessionCoordinator.destroySocketMessages(
                    messages);
        }
    }

    void processSocketPairMessage(
            IdsModernSessionCoordinator.DataState state,
            IdsSocketPairCodec.Message message,
            IdsModernSessionCoordinator.OutputBuilder output) {
        coord.requireControlReady();
        long connectionId =
                state.connection != null
                        ? state.connection.connectionId
                        : 0;
        if (message instanceof IdsSocketPairCodec.UnknownMessage) {
            output.events.add(new IdsModernSessionCoordinator.SessionEvent(IdsModernSessionCoordinator.EventType.UNKNOWN_COMMAND_IGNORED,
                    connectionId, state.service, null, null, null, 0, 0,
                    message.command, 0, -1, false, null, null, null));
            java.util.function.Consumer<String> logger =
                    IdsModernSessionCoordinator.diagnosticLogger;
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
            coord.control.serviceMap()
                    .acceptServiceMap(
                            map);
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.SERVICE_MAP_RECEIVED,
                            connectionId,
                            state.service,
                            map.serviceName));
            return;
        }
        if (message
                instanceof IdsSocketPairCodec.AckMessage ack) {
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.ack(
                            connectionId,
                            state.service,
                            ack));
            return;
        }
        if (message
                instanceof IdsSocketPairCodec.KeepAliveMessage) {
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.KEEP_ALIVE_RECEIVED,
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
                if (IdsModernSessionCoordinator.diagnosticLogger != null) IdsModernSessionCoordinator.diagnosticLogger.accept(
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
                if (IdsModernSessionCoordinator.diagnosticLogger != null) IdsModernSessionCoordinator.diagnosticLogger.accept("RESOURCE RX REJECTED: "
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
            java.util.function.Consumer<String> logger = IdsModernSessionCoordinator.diagnosticLogger;
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
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED,
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
            coord.pairedSyncCapability.observeInboundService(
                    mapped.serviceName);
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.protobuf(
                            IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED,
                            connectionId,
                            state.service,
                            mapped.serviceName,
                            protobuf));
        } else if (message
                instanceof IdsSocketPairCodec.DataMessage data) {
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.data(
                            connectionId,
                            state.service,
                            mapped.serviceName,
                            data));
        } else {
            IdsSocketPairCodec.AppAckMessage appAck =
                    (IdsSocketPairCodec.AppAckMessage)
                            message;
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.appAck(
                            IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED,
                            connectionId,
                            state.service,
                            mapped.serviceName,
                            appAck));
        }
    }

    IdsServiceMapState.IncomingRoute resolveIncomingRoute(
            IdsModernSessionCoordinator.DataState state, IdsSocketPairCodec.Message message, IdsModernSessionCoordinator.OutputBuilder output) {
        String fallbackService = state.service;
        if (IdsModernSessionCoordinator.isRelayLaneC(state.service)) {
            fallbackService = NanoRegistryPropertyCodec.CLASS_C_SERVICE;
        } else if (IdsModernSessionCoordinator.isRelayLaneD(state.service)) {
            fallbackService = NanoRegistryPropertyCodec.CLASS_D_SERVICE;
        }
        IdsServiceMapState.IncomingRoute mapped = coord.control.serviceMap().acceptIncoming(message, fallbackService);
        if (mapped.advertisement != null) {
            byte[] frame = null;
            try {
                frame = IdsSocketPairCodec.encodeServiceMap(mapped.advertisement);
                long connectionId = state.connection != null ? state.connection.connectionId : 0;
                coord.sendApplication(connectionId, frame, output);
                output.events.add(IdsModernSessionCoordinator.SessionEvent.simple(IdsModernSessionCoordinator.EventType.SERVICE_MAP_SENT,
                        connectionId, state.service, mapped.serviceName));
            } finally {
                IdsModernSessionCoordinator.wipe(frame);
                mapped.advertisement.destroy();
            }
        }
        return mapped;
    }

    void validateApplicationEnvelope(
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
                    coord.pairedSyncCapability.apply(
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

    void validateApplicationTopicRoute(
            IdsModernSessionCoordinator.DataState state,
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
        boolean isLaneD = IdsModernSessionCoordinator.isRelayLaneD(state.service);
        boolean isLaneC = IdsModernSessionCoordinator.isRelayLaneC(state.service);
        boolean matchD = isLaneD && route.idsProtectionClass == IdsUtunConnectionName.PROTECTION_CLASS_D;
        boolean matchC = isLaneC && (route.idsProtectionClass == IdsUtunConnectionName.PROTECTION_CLASS_C
                || route.idsProtectionClass == IdsUtunConnectionName.PROTECTION_CLASS_C_ALTERNATE);
        if (!matchD && !matchC) {
            throw new IllegalArgumentException(
                    "Application topic arrived on the wrong IDS lane: " + state.service + " vs " + topic);
        }
    }

    void sendDataHandshake(
            IdsModernSessionCoordinator.DataState state,
            IdsModernSessionCoordinator.OutputBuilder output) {
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
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.HANDSHAKE_SENT,
                            state.connection.connectionId,
                            state.service,
                            null));
        } finally {
            IdsModernSessionCoordinator.wipe(
                    frame);
        }
    }

    void sendSocketPairFrame(
            IdsModernSessionCoordinator.DataState state,
            byte[] frame,
            Long fragmentedMessageId,
            IdsModernSessionCoordinator.OutputBuilder output) {
        if (state == null) {
            throw new IllegalArgumentException(
                    "IDS data channel state is absent");
        }
        long connectionId = state.connection != null && !state.connection.transportGone
                ? state.connection.connectionId : 0;
        if (connectionId != 0 && !coord.transport.isServiceActive(connectionId)) {
            state.connection = null;
            connectionId = 0;
        }
        if (connectionId == 0) {
            for (IdsModernSessionCoordinator.DataState candidate : coord.dataByService.values()) {
                if (candidate.connection == null || candidate.connection.transportGone || !coord.transport.isServiceActive(candidate.connection.connectionId)) {
                    continue;
                }
                if (!IdsModernSessionCoordinator.serviceMatches(state.service, candidate.service)) {
                    continue;
                }
                if (IdsModernSessionCoordinator.isRelayLaneC(state.service) != IdsModernSessionCoordinator.isRelayLaneC(candidate.service)
                        || IdsModernSessionCoordinator.isRelayLaneD(state.service) != IdsModernSessionCoordinator.isRelayLaneD(candidate.service)) {
                    continue;
                }
                if (IdsUtunConnectionName.isConcreteUtunName(state.service)
                        && IdsUtunConnectionName.isConcreteUtunName(candidate.service)
                        && !state.service.equals(candidate.service)
                        && (IdsModernSessionCoordinator.isRelayLaneC(state.service) != IdsModernSessionCoordinator.isRelayLaneC(candidate.service)
                        || IdsModernSessionCoordinator.isRelayLaneD(state.service) != IdsModernSessionCoordinator.isRelayLaneD(candidate.service))) {
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
                || !coord.transport.isServiceActive(connectionId)
                || waitForHandshake) {
            long queuedBytes = state.pendingFrames.stream().mapToLong(item -> item.bytes.length).sum();
            if (state.pendingFrames.size() >= 256 || queuedBytes + frame.length > 4L * 1024 * 1024) {
                throw new IllegalStateException("IDS pending application queue exceeded its capacity");
            }
            state.pendingFrames.add(new IdsModernSessionCoordinator.PendingFrame(frame, fragmentedMessageId));
            if (connectionId == 0 || !coord.transport.isServiceActive(connectionId)) {
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
            coord.sendApplication(
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
                coord.sendApplication(
                        connectionId,
                        fragment,
                        output);
            }
        } finally {
            IdsModernSessionCoordinator.wipeAll(
                    fragments);
        }
    }

    long takeFragmentedMessageId() {
        if (coord.nextFragmentedMessageId > 0xffff_ffffL) {
            throw new IllegalStateException(
                    "IDS fragment ID space is exhausted");
        }
        long fragmentId =
                coord.nextFragmentedMessageId;
        coord.nextFragmentedMessageId++;
        return fragmentId;
    }

    void handleSuperseded(
            long relatedConnectionId) {
        if (relatedConnectionId == 0) {
            return;
        }
        if (relatedConnectionId
                == coord.controlConnectionId) {
            coord.resetControlState();
            return;
        }
        IdsModernSessionCoordinator.DataTransport connection =
                coord.dataTransports.remove(
                        relatedConnectionId);
        if (connection == null) {
            return;
        }
        connection.transportGone = true;
        IdsModernSessionCoordinator.DataState state =
                coord.dataByConnection.remove(
                        relatedConnectionId);
        if (state != null
                && state.connection
                == connection) {
            state.connection = null;
            state.needsReplacement = true;
            state.resetTransport();
        }
    }

    void releaseDataTransport(
            IdsModernSessionCoordinator.DataTransport connection) {
        if (connection == null
                || connection.released) {
            return;
        }
        connection.released = true;
        coord.dataTransports.remove(
                connection.connectionId);
        IdsModernSessionCoordinator.DataState state =
                coord.dataByConnection.remove(
                        connection.connectionId);
        if (state != null
                && state.connection
                == connection) {
            state.connection = null;
        }
        if (connection.transportGone
                || coord.closing
                || coord.closed) {
            return;
        }
        try (IdsServiceConnectorCoordinator.PacketBatch closedOutput =
                     coord.transport.closeConnection(
                             connection.connectionId)) {
            List<IdsServiceConnectorCoordinator.RoutedPacket> packets =
                    closedOutput.packets();
            try {
                for (IdsServiceConnectorCoordinator.RoutedPacket packet :
                        packets) {
                    coord.deferredPackets.add(
                            IdsModernSessionCoordinator.routedPacket(
                                    packet));
                }
            } finally {
                IdsModernSessionCoordinator.closeTransportPackets(
                        packets);
            }
        }
    }
}
