package dev.applewatchandroid.bridge;

import java.util.UUID;

/** Outbound IDS/NanoRegistry/PBBridge/PairedSync application-frame senders. */
final class IdsModernApplicationSender {
    private final IdsModernSessionCoordinator coord;

    IdsModernApplicationSender(IdsModernSessionCoordinator coord) {
        this.coord = coord;
    }

    IdsModernSessionCoordinator.Output sendClassD(
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
                coord.applicationMessages.nextOneWay(),
                message);
    }

    IdsModernSessionCoordinator.Output sendClassDResponse(
            NanoRegistryClassDCodec.ApplicationMessage message,
            String outgoingResponseIdentifier) {
        if (message == null
                || !message.response()) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class-D response is invalid");
        }
        message.requireValid();
        return sendClassD(
                coord.applicationMessages.nextResponseToIdentifier(
                        outgoingResponseIdentifier),
                message);
    }

    IdsModernSessionCoordinator.Output sendClassDRequestWithClientAcknowledgement(
            NanoRegistryClassDCodec.ApplicationMessage message) {
        if (message == null || message.response()) {
            throw new IllegalArgumentException("Expected a NanoRegistry Class-D request");
        }
        return sendClassD(coord.applicationMessages.nextRequestExpectingPeerResponseAndAppAck(), message);
    }

    IdsModernSessionCoordinator.Output sendClassD(
            IdsModernSessionCoordinator.MessageMetadata metadata,
            NanoRegistryClassDCodec.ApplicationMessage message) {
        return sendNanoRegistry(
                NanoRegistryPropertyCodec.CLASS_D_SERVICE,
                metadata,
                message,
                null);
    }

    IdsModernSessionCoordinator.Output sendClassC(
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

    IdsModernSessionCoordinator.Output sendClassCResponse(
            NanoRegistryPropertyCodec.ApplicationMessage message,
            String outgoingResponseIdentifier) {
        if (message == null
                || !message.response()) {
            throw new IllegalArgumentException(
                    "NanoRegistry Class-C response is invalid");
        }
        return sendClassC(
                coord.applicationMessages.nextResponseToIdentifier(
                        outgoingResponseIdentifier),
                message);
    }

    IdsModernSessionCoordinator.Output sendClassC(
            IdsModernSessionCoordinator.MessageMetadata metadata,
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
    IdsModernSessionCoordinator.MessageMetadata classCRequestMetadata(
            NanoRegistryPropertyCodec.ApplicationMessage message) {
        if (message instanceof NanoRegistryPropertyCodec.PropertyRequest) {
            return coord.applicationMessages.nextRequestExpectingPeerResponse();
        }
        return coord.applicationMessages.nextOneWay();
    }

    IdsModernSessionCoordinator.Output sendPbBridge(
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
        IdsModernSessionCoordinator.MessageMetadata metadata =
                message instanceof PbBridgeCodec.PrepareInitialSync
                        ? coord.applicationMessages
                        .nextRequestExpectingPeerResponse()
                        : coord.applicationMessages.nextOneWay();
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
    IdsModernSessionCoordinator.Output sendPbBridgeReplyToFetch(
            PbBridgeCodec.ApplicationMessage message,
            String watchMessageUuid) {
        if (message == null
                || message.response()) {
            throw new IllegalArgumentException(
                    "Activation data reply must stay a protobuf request");
        }
        message.requireValid();
        return sendPbBridge(
                coord.applicationMessages.nextResponseToIdentifier(
                        watchMessageUuid),
                message);
    }

    IdsModernSessionCoordinator.Output sendPbBridgeResponse(
            PbBridgeCodec.ApplicationMessage message,
            String outgoingResponseIdentifier) {
        if (message == null
                || !message.response()) {
            throw new IllegalArgumentException(
                    "PBBridge response is invalid");
        }
        message.requireValid();
        return sendPbBridge(
                coord.applicationMessages.nextResponseToIdentifier(
                        outgoingResponseIdentifier),
                message);
    }

    IdsModernSessionCoordinator.Output sendPbBridge(
            IdsModernSessionCoordinator.MessageMetadata metadata,
            PbBridgeCodec.ApplicationMessage message) {
        coord.requireUsable();
        coord.requireControlReady();
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
            IdsModernSessionCoordinator.requireCanonicalUuid(
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
            IdsModernSessionCoordinator.wipe(
                    payload);
        }
    }

    IdsModernSessionCoordinator.Output sendPairedSync(
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
                coord.applicationMessages.nextOneWayRequestingAppAck(),
                message);
    }

    IdsModernSessionCoordinator.Output sendPairedSync(
            IdsModernSessionCoordinator.MessageMetadata metadata,
            PairedSyncCodec.UserDefaultsMessage message) {
        coord.requireUsable();
        coord.requireControlReady();
        if (message == null) {
            throw new IllegalArgumentException(
                    "PairedSync message is absent");
        }
        String topic =
                coord.pairedSyncCapability.requireService();
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
            IdsModernSessionCoordinator.wipe(
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
    IdsModernSessionCoordinator.Output sendAppAck(
            String topic,
            String outgoingResponseIdentifier) {
        coord.requireUsable();
        coord.requireControlReady();
        IdsApplicationRoute route =
                IdsApplicationRoute.forTopic(
                        topic);
        IdsModernSessionCoordinator.DataState state =
                coord.requireJoinedDataState(
                        route.serviceConnectorName.encode());
        IdsModernSessionCoordinator.MessageMetadata metadata =
                coord.applicationMessages.nextAppAckForIdentifier(
                        outgoingResponseIdentifier);
        coord.requireControlReady();
        IdsServiceMapState.OutgoingRoute mapped =
                coord.control.serviceMap()
                        .routeOutgoing(
                                topic);
        IdsSocketPairCodec.AppAckMessage envelope =
                new IdsSocketPairCodec.AppAckMessage(
                        metadata.sequence,
                        mapped.streamId,
                        metadata.peerResponseIdentifier,
                        mapped.wireTopic());

        byte[] frame = null;
        IdsModernSessionCoordinator.OutputBuilder output =
                new IdsModernSessionCoordinator.OutputBuilder();
        try {
            frame =
                    IdsSocketPairCodec.encodeAppAck(
                            envelope);
            coord.dataChannelEngine.sendSocketPairFrame(
                    state,
                    frame,
                    null,
                    output);
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.appAck(
                            IdsModernSessionCoordinator.EventType.APP_ACK_SENT,
                            state.connection != null ? state.connection.connectionId : 0,
                            state.service,
                            topic,
                            envelope));
            coord.drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw coord.fail(
                    failure);
        } finally {
            IdsModernSessionCoordinator.wipe(
                    frame);
            envelope.destroy();
        }
    }

    IdsModernSessionCoordinator.Output sendNanoRegistry(
            String topic,
            IdsModernSessionCoordinator.MessageMetadata metadata,
            NanoRegistryClassDCodec.ApplicationMessage classD,
            NanoRegistryPropertyCodec.ApplicationMessage classC) {
        coord.requireUsable();
        coord.requireControlReady();
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
            IdsModernSessionCoordinator.wipe(
                    payload);
        }
    }

    IdsModernSessionCoordinator.Output sendApplicationProtobuf(
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
        coord.requireUsable();
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
        IdsModernSessionCoordinator.MessageMetadata metadata =
                nanoRegistryRequest || nssRequest || findMyRequest ? coord.applicationMessages.nextRequestExpectingPeerResponseAndAppAck()
                        : coord.applicationMessages.nextOneWayRequestingAppAck();
        return sendApplicationProtobuf(
                topic,
                metadata,
                protobufType,
                false,
                payload);
    }

    /** Fixed, empty native NSS reboot. Generic NSS requests stay read-only. */
    IdsModernSessionCoordinator.Output sendWatchReboot() {
        coord.requireUsable();
        return sendApplicationProtobuf(NativeWatchReboot.TOPIC,
                coord.applicationMessages.nextOneWayRequestingAppAck(), NativeWatchReboot.TYPE,
                false, new byte[0]);
    }

    IdsModernSessionCoordinator.Output sendFindMyLocalResponse(int protobufType, byte[] payload, String responseId) {
        coord.requireUsable();
        FindMyLocalDeviceCodec.requireSoundType(protobufType);
        FindMyLocalDeviceCodec.PlaySoundResponse.decode(payload);
        if (responseId == null || !UUID.fromString(responseId).toString().equalsIgnoreCase(responseId)) {
            throw new IllegalArgumentException("Invalid Ping response identifier");
        }
        return sendApplicationProtobuf(IdsApplicationRoute.FIND_MY_LOCAL_SERVICE,
                coord.applicationMessages.nextResponseToIdentifier(responseId), protobufType, true, payload);
    }

    IdsModernSessionCoordinator.Output sendApplicationData(String topic, byte[] payload) {
        return sendApplicationData(topic, payload, IdsSocketPairCodec.COMMAND_DATA);
    }

    IdsModernSessionCoordinator.Output sendSoftwareUpdateRequest(byte[] payload) {
        SoftwareUpdateCodec.message(payload);
        return sendApplicationData(
                SoftwareUpdateCodec.TOPIC,
                payload,
                IdsSocketPairCodec.COMMAND_DATA,
                true);
    }

    IdsModernSessionCoordinator.Output sendHealthSyncRequest(byte[] encrypted,java.util.UUID message) {
        HealthDataEventCodec.requireEncryptedDictionary(encrypted);
        return sendApplicationData(IdsApplicationRoute.HEALTH_SYNC_SERVICE,encrypted,
                IdsSocketPairCodec.COMMAND_DATA,true,message);
    }

    /** Native clockface full-collection request with response UUID and app ACK. */
    IdsModernSessionCoordinator.Output sendClockFaceCollectionRequest(byte[] payload) {
        ClockFaceSyncHeaderCodec.decodeFullRequest(payload);
        return sendApplicationData(IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, payload,
                IdsSocketPairCodec.COMMAND_DATA, true);
    }

    IdsModernSessionCoordinator.Output sendClockFaceDeltaRequest(byte[] payload) {
        ClockFaceDeltaProtocol.validateRequest(payload);
        return sendApplicationData(IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, payload,
                IdsSocketPairCodec.COMMAND_DATA, true);
    }

    /**
     * One-way data reply correlated to the Watch message UUID.
     * Flags stay clear of {@code EXPECTS_PEER_RESPONSE}: this is the response.
     */
    IdsModernSessionCoordinator.Output sendApplicationDataResponse(
            String topic,
            byte[] payload,
            String outgoingResponseIdentifier) {
        coord.requireUsable();
        coord.requireControlReady();
        IdsApplicationRoute route = IdsApplicationRoute.forTopic(topic);
        IdsModernSessionCoordinator.DataState state = coord.requireJoinedDataState(route.serviceConnectorName.encode());
        IdsModernSessionCoordinator.MessageMetadata metadata = coord.applicationMessages.nextResponseToIdentifier(
                outgoingResponseIdentifier);
        IdsServiceMapState.OutgoingRoute mapped = coord.control.serviceMap().routeOutgoing(topic);
        int flags = metadata.flagsWithoutTopic
                | (mapped.includeTopic ? IdsSocketPairCodec.FLAG_HAS_TOPIC : 0);
        IdsSocketPairCodec.DataMessage envelope = new IdsSocketPairCodec.DataMessage(
                IdsSocketPairCodec.COMMAND_DATA, metadata.sequence, mapped.streamId,
                flags, metadata.peerResponseIdentifier, metadata.messageUuid,
                mapped.wireTopic(), payload, null);
        byte[] frame = null;
        IdsModernSessionCoordinator.OutputBuilder output = new IdsModernSessionCoordinator.OutputBuilder();
        try {
            frame = IdsSocketPairCodec.encodeData(envelope);
            coord.dataChannelEngine.sendSocketPairFrame(state, frame, null, output);
            output.events.add(IdsModernSessionCoordinator.SessionEvent.data(IdsModernSessionCoordinator.EventType.DATA_SENT,
                    state.connection != null ? state.connection.connectionId : 0,
                    state.service, topic, envelope));
            coord.drainDeferredPackets(output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw coord.fail(failure);
        } finally { IdsModernSessionCoordinator.wipe(frame); envelope.destroy(); }
    }

    IdsModernSessionCoordinator.Output sendApplicationDictionary(String topic, byte[] payload) {
        return sendApplicationData(topic, payload, IdsSocketPairCodec.COMMAND_DICTIONARY);
    }

    IdsModernSessionCoordinator.Output sendApplicationDictionaryWithExpiry(
            String topic, byte[] payload, long expirySeconds) {
        coord.requireUsable();
        coord.requireControlReady();
        if (NanoSystemSettingsDiagnostics.TOPIC.equals(topic)
                || SysdiagnoseArchiveInventory.TOPIC.equals(topic)) {
            throw new IllegalArgumentException("Diagnostics require a supported read-only Protobuf request");
        }
        IdsApplicationRoute route = IdsApplicationRoute.forTopic(topic);
        IdsModernSessionCoordinator.DataState state = coord.requireJoinedDataState(route.serviceConnectorName.encode());
        IdsModernSessionCoordinator.MessageMetadata metadata =
                coord.applicationMessages.nextRequestExpectingPeerResponseWithExpiry(expirySeconds);
        IdsServiceMapState.OutgoingRoute mapped = coord.control.serviceMap().routeOutgoing(topic);
        IdsSocketPairCodec.DataMessage envelope = new IdsSocketPairCodec.DataMessage(
                IdsSocketPairCodec.COMMAND_DICTIONARY, metadata.sequence, mapped.streamId,
                metadata.flagsWithoutTopic | (mapped.includeTopic ? IdsSocketPairCodec.FLAG_HAS_TOPIC : 0),
                metadata.peerResponseIdentifier, metadata.messageUuid, mapped.wireTopic(),
                payload, metadata.expirySeconds);
        byte[] frame = null;
        IdsModernSessionCoordinator.OutputBuilder output = new IdsModernSessionCoordinator.OutputBuilder();
        try {
            frame = IdsSocketPairCodec.encodeData(envelope);
            coord.dataChannelEngine.sendSocketPairFrame(state, frame, null, output);
            output.events.add(IdsModernSessionCoordinator.SessionEvent.data(IdsModernSessionCoordinator.EventType.DATA_SENT,
                    state.connection != null ? state.connection.connectionId : 0,
                    state.service, topic, envelope));
            coord.drainDeferredPackets(output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw coord.fail(failure);
        } finally { IdsModernSessionCoordinator.wipe(frame); envelope.destroy(); }
    }

    IdsModernSessionCoordinator.Output sendApplicationData(String topic, byte[] payload, int command) {
        return sendApplicationData(topic, payload, command, false);
    }

    IdsModernSessionCoordinator.Output sendApplicationData(String topic, byte[] payload, int command, boolean requestReply) {
        return sendApplicationData(topic,payload,command,requestReply,null);
    }

    IdsModernSessionCoordinator.Output sendApplicationData(String topic, byte[] payload, int command, boolean requestReply,java.util.UUID healthMessage) {
        coord.requireUsable();
        coord.requireControlReady();
        if (NanoSystemSettingsDiagnostics.TOPIC.equals(topic)
                || SysdiagnoseArchiveInventory.TOPIC.equals(topic)) {
            throw new IllegalArgumentException("Diagnostics require a supported read-only Protobuf request");
        }
        IdsApplicationRoute route = IdsApplicationRoute.forTopic(topic);
        IdsModernSessionCoordinator.DataState state = coord.requireJoinedDataState(route.serviceConnectorName.encode());
        IdsModernSessionCoordinator.MessageMetadata metadata = healthMessage!=null ? coord.applicationMessages.nextHealthRequest(healthMessage)
                : requestReply ? coord.applicationMessages.nextRequestExpectingPeerResponseAndAppAck()
                : WifiNetworkSyncCodec.TOPIC.equals(topic) ? coord.applicationMessages.nextOneWayRequestingAppAck()
                : coord.applicationMessages.nextOneWay();
        IdsServiceMapState.OutgoingRoute mapped = coord.control.serviceMap().routeOutgoing(topic);
        IdsSocketPairCodec.DataMessage envelope = new IdsSocketPairCodec.DataMessage(
                command, metadata.sequence, mapped.streamId,
                metadata.flagsWithoutTopic | (mapped.includeTopic ? IdsSocketPairCodec.FLAG_HAS_TOPIC : 0),
                metadata.peerResponseIdentifier, metadata.messageUuid, mapped.wireTopic(), payload, null);
        byte[] frame = null;
        IdsModernSessionCoordinator.OutputBuilder output = new IdsModernSessionCoordinator.OutputBuilder();
        try {
            frame = IdsSocketPairCodec.encodeData(envelope);
            coord.dataChannelEngine.sendSocketPairFrame(state, frame, null, output);
            output.events.add(IdsModernSessionCoordinator.SessionEvent.data(IdsModernSessionCoordinator.EventType.DATA_SENT,
                    state.connection != null ? state.connection.connectionId : 0,
                    state.service, topic, envelope));
            coord.drainDeferredPackets(output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw coord.fail(failure);
        } finally { IdsModernSessionCoordinator.wipe(frame); envelope.destroy(); }
    }

    IdsModernSessionCoordinator.Output sendApplicationProtobuf(
            String topic,
            IdsModernSessionCoordinator.MessageMetadata metadata,
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
        IdsModernSessionCoordinator.DataState state =
                coord.requireJoinedDataState(
                        route.serviceConnectorName.encode());
        coord.requireControlReady();
        IdsServiceMapState.OutgoingRoute mapped =
                coord.control.serviceMap()
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
        IdsModernSessionCoordinator.OutputBuilder output =
                new IdsModernSessionCoordinator.OutputBuilder();
        try {
            frame =
                    IdsSocketPairCodec.encodeProtobuf(
                            envelope);
            coord.observeOutgoingFrame("protobuf", topic, protobufType, response, frame);
            coord.dataChannelEngine.sendSocketPairFrame(
                    state,
                    frame,
                    metadata.fragmentedMessageId,
                    output);
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.protobuf(
                            IdsModernSessionCoordinator.EventType.PROTOBUF_SENT,
                            state.connection != null ? state.connection.connectionId : 0,
                            state.service,
                            topic,
                            envelope));
            coord.drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw coord.fail(
                    failure);
        } finally {
            IdsModernSessionCoordinator.wipe(
                    frame);
            envelope.destroy();
        }
    }
}
