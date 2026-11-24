package dev.applewatchandroid.bridge;

import java.util.List;

/** IDS control-channel setup, plain/encrypted channel setup, and service lifecycle. */
final class IdsModernControlExchange {
    private final IdsModernSessionCoordinator coord;

    IdsModernControlExchange(IdsModernSessionCoordinator coord) {
        this.coord = coord;
    }

    IdsModernSessionCoordinator.Output startControl() {
        coord.requireUsable();        long active = activeControlConnection();
        if (active != 0 && coord.transport.isServiceActive(active)) {
            IdsModernSessionCoordinator.OutputBuilder output = new IdsModernSessionCoordinator.OutputBuilder();
            if (coord.control != null && coord.control.remoteHello() != null) {
                output.events.add(
                        IdsModernSessionCoordinator.SessionEvent.controlReady(
                                active,
                                coord.control.compatibility()));
            }
            return output.build();
        }
        if (coord.controlOpenRequested
                || coord.controlConnectionId != 0) {
            throw new IllegalStateException(
                    "IDS control connection was already started");
        }
        IdsModernSessionCoordinator.OutputBuilder output =
                new IdsModernSessionCoordinator.OutputBuilder();
        try (IdsServiceConnectorCoordinator.OpenResult opened =
                     coord.transport.openService(
                             IdsIpsecServiceRoute.control())) {
            IdsModernSessionCoordinator.addPacket(
                    output,
                    opened.packet());
            coord.controlOpenRequested = true;
            coord.controlConnectionId = opened.connectionId;
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.CONTROL_CONNECT_STARTED,
                            opened.connectionId,
                            IdsIpsecServiceRoute.CONTROL_SERVICE,
                            null));
            coord.drainDeferredPackets(
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
    IdsModernSessionCoordinator.Output startInitialLane(
            String topic) {
        coord.requireUsable();
        coord.requireControlReady();
        NanoRegistryInitialIdsRoute lane =
                NanoRegistryInitialIdsRoute.forTopic(
                        topic);
        String service =
                lane.serviceConnectorName.encode();
        IdsModernSessionCoordinator.OutputBuilder output =
                new IdsModernSessionCoordinator.OutputBuilder();
        if (coord.dataByService.containsKey(
                service)) {
            IdsModernSessionCoordinator.DataState existing = coord.dataByService.get(service);
            if (existing != null && existing.connection != null) {
                output.events.add(
                        IdsModernSessionCoordinator.SessionEvent.simple(
                                IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED,
                                existing.connection.connectionId,
                                service,
                                null));
            }
            return output.build();
        }

        IdsEncryptedDataChannelSession setup =
                IdsEncryptedDataChannelSession.openOutgoing(
                        coord.setupPorts,
                        coord.ssrcs,
                        coord.random,
                        lane.serviceConnectorName);
        IdsModernSessionCoordinator.DataState state =
                new IdsModernSessionCoordinator.DataState(coord, 
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
            coord.transport.listenDataPort(
                    dc,
                    message.localPort);
            frame =
                    IdsControlChannelCodec.encodeFramed(
                            message);
            long active = activeControlConnection();
            if (active != 0 && coord.transport.isServiceActive(active)) {
                coord.sendApplication(
                        active,
                        frame,
                        output);
                output.events.add(
                        IdsModernSessionCoordinator.SessionEvent.simple(
                                IdsModernSessionCoordinator.EventType.DATA_SETUP_SENT,
                                active,
                                service,
                                topic));
            }
            coord.dataByService.put(
                    service,
                    state);
            // Remember the Setup so an inbound NWSC can still join. JOINED
            // is emitted only after SERVICE_ACCEPTED attaches a transport.
            coord.dataJoins.receiveSetup(
                    lane.serviceConnectorName);
            // iOS/watchOS: Setup first, then nw_service_connector_start_request.
            // Same-burst or poll-fallback SYN to 61314 races Watch's post-Hello
            // DirectMsgInfo and leaves the service-available block unclaimed.
            transferred = true;
            coord.drainDeferredPackets(
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw coord.fail(
                    failure);
        } finally {
            if (message != null) {
                message.destroy();
            }
            IdsModernSessionCoordinator.wipe(
                    frame);
            if (!transferred) {
                state.close();
            }
        }
    }

    IdsModernSessionCoordinator.Output sendControlMessage(
            IdsControlChannelCodec.Message message) {
        coord.requireUsable();
        coord.requireControlReady();
        if (message == null) {
            throw new IllegalArgumentException(
                    "IDS control message is absent");
        }
        IdsModernSessionCoordinator.OutputBuilder output =
                new IdsModernSessionCoordinator.OutputBuilder();
        byte[] frame =
                IdsControlChannelCodec.encodeFramed(
                        message);
        try {
            coord.sendApplication(
                    activeControlConnection(),
                    frame,
                    output);
            return output.build();
        } catch (RuntimeException failure) {
            output.close();
            throw coord.fail(
                    failure);
        } finally {
            IdsModernSessionCoordinator.wipe(
                    frame);
        }
    }

    IdsModernSessionCoordinator.Output startOutgoingService(
            IdsServiceConnectorName name) {
        coord.requireUsable();
        coord.requireControlReady();
        if (name == null) {
            throw new IllegalArgumentException(
                    "IDS service-connector name is absent");
        }
        String service =
                name.encode();
        IdsModernSessionCoordinator.DataState state =
                coord.dataByService.get(
                        service);
        IdsModernSessionCoordinator.OutputBuilder output =
                new IdsModernSessionCoordinator.OutputBuilder();
        try {
            if (state == null) {
                state =
                        new IdsModernSessionCoordinator.DataState(coord, 
                                service,
                                IdsIpsecServiceRoute.localDelivery(
                                        name),
                                IdsEncryptedDataChannelSession.openOutgoing(
                                        coord.setupPorts,
                                        coord.ssrcs,
                                        coord.random,
                                        name));
                coord.dataByService.put(
                        service,
                        state);
            }
            coord.dataChannelEngine.startDataConnector(
                    state,
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

    void ensureControlHello(IdsModernSessionCoordinator.OutputBuilder output) {
        long active = activeControlConnection();
        if (active != 0 && coord.transport.isServiceActive(active)) {
            if (coord.control == null || !coord.control.localHelloSent()) {
                if (coord.control == null) {
                    coord.control =
                            IdsControlChannelSession.createDefaultPaired(
                                    coord.random,
                                    coord.localIdsDeviceUuid,
                                    coord.directMessagingSupported,
                                    IdsServiceMapState.APPLE_FIRST_STREAM_ID)
                                    .useProfile(coord.profile);
                    coord.controlConnectionId = active;
                }
                if (!coord.control.localHelloSent()) {
                    byte[] hello =
                            coord.control.startHello();
                    try {
                        coord.observeOutgoingFrame("hello", IdsIpsecServiceRoute.CONTROL_SERVICE, 1, false, hello);
                        coord.sendApplication(
                                active,
                                hello,
                                output);
                        output.events.add(
                                IdsModernSessionCoordinator.SessionEvent.simple(
                                        IdsModernSessionCoordinator.EventType.CONTROL_HELLO_SENT,
                                        active,
                                        IdsIpsecServiceRoute.CONTROL_SERVICE,
                                        null));
                    } finally {
                        IdsModernSessionCoordinator.wipe(
                                hello);
                    }
                }
            }
        }
    }

    void acceptControlService(
            IdsServiceConnectorCoordinator.ConnectionEvent event,
            IdsModernSessionCoordinator.OutputBuilder output) {
        if (event.route == null
                || !IdsModernSessionCoordinator.sameRoute(
                        event.route,
                        IdsIpsecServiceRoute.control())) {
            throw new IllegalArgumentException(
                    "IDS control service arrived on the wrong route");
        }
        if (coord.control != null && coord.controlConnectionId != event.connectionId) {
            if (coord.controlConnectionId != 0
                    && coord.transport.isServiceActive(coord.controlConnectionId)) {
                return;
            }
            coord.resetControlState();
        }
        if (coord.control != null) {
            if (coord.controlConnectionId
                    == event.connectionId) {
                return;
            }
            coord.resetControlState();
        }

        coord.control =
                IdsControlChannelSession.createDefaultPaired(
                        coord.random,
                        coord.localIdsDeviceUuid,
                        coord.directMessagingSupported,
                        IdsServiceMapState.APPLE_FIRST_STREAM_ID)
                        .useProfile(coord.profile);
        coord.controlConnectionId =
                event.connectionId;
        coord.controlOpenRequested = false;
        output.events.add(
                IdsModernSessionCoordinator.SessionEvent.simple(
                        IdsModernSessionCoordinator.EventType.CONTROL_SERVICE_ACCEPTED,
                        coord.controlConnectionId,
                        IdsIpsecServiceRoute.CONTROL_SERVICE,
                        null));

        byte[] hello =
                coord.control.startHello();
        try {
            coord.observeOutgoingFrame("hello", IdsIpsecServiceRoute.CONTROL_SERVICE, 1, false, hello);
            coord.sendApplication(
                    activeControlConnection(),
                    hello,
                    output);
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.CONTROL_HELLO_SENT,
                            coord.controlConnectionId,
                            IdsIpsecServiceRoute.CONTROL_SERVICE,
                            null));
        } finally {
            IdsModernSessionCoordinator.wipe(
                    hello);
        }
    }

    long activeControlConnection() {
        if (coord.controlConnectionId != 0 && coord.transport.isServiceActive(coord.controlConnectionId)) {
            return coord.controlConnectionId;
        }
        long active = coord.transport.findActiveControlConnectionId();
        if (active != 0) {
            coord.controlConnectionId = active;
            return active;
        }
        return coord.controlConnectionId;
    }

    void acceptControlBytes(
            byte[] bytes,
            IdsModernSessionCoordinator.OutputBuilder output) {
        if (coord.control == null
                || coord.controlConnectionId == 0) {
            throw new IllegalStateException(
                    "IDS control bytes arrived before service acceptance");
        }
        List<IdsControlChannelCodec.Message> messages =
                coord.control.acceptTcpBytes(
                        bytes);
        try {
            for (IdsControlChannelCodec.Message message :
                    messages) {
                output.events.add(
                        IdsModernSessionCoordinator.SessionEvent.controlMessage(
                                coord.controlConnectionId,
                                message.type,
                                IdsModernSessionCoordinator.controlMessageLength(
                                        message),
                                IdsModernSessionCoordinator.controlMessageDetail(
                                        message)));
                if (message
                        instanceof IdsControlChannelCodec.HelloMessage) {
                    IdsControlChannelSession.Compatibility compatibility =
                            coord.control.compatibility();
                    if (compatibility
                            == IdsControlChannelSession
                            .Compatibility.NO_OVERLAP) {
                        throw new IllegalArgumentException(
                                "Peer IDS Hello has no compatible version");
                    }
                    if (coord.directMessagingSupported) {
                        IdsControlChannelCodec.DirectMsgInfoMessage info =
                                IdsControlChannelCodec.DirectMsgInfoMessage.basicVersionOne();
                        byte[] frame = null;
                        try {
                            frame = IdsControlChannelCodec.encodeFramed(info);
                            coord.sendApplication(activeControlConnection(), frame, output);
                            output.events.add(IdsModernSessionCoordinator.SessionEvent.simple(IdsModernSessionCoordinator.EventType.DIRECT_MESSAGING_INFO_SENT,
                                    coord.controlConnectionId, IdsIpsecServiceRoute.CONTROL_SERVICE,
                                    info.capabilitySummary()));
                        } finally { IdsModernSessionCoordinator.wipe(frame); info.destroy(); }
                    }
                    output.events.add(
                            IdsModernSessionCoordinator.SessionEvent.simple(
                                    IdsModernSessionCoordinator.EventType.CONTROL_HELLO_RECEIVED,
                                    coord.controlConnectionId,
                                    IdsIpsecServiceRoute.CONTROL_SERVICE,
                                    null));
                    output.events.add(
                            IdsModernSessionCoordinator.SessionEvent.controlReady(
                                    coord.controlConnectionId,
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
            IdsModernSessionCoordinator.destroyControlMessages(
                    messages);
        }
    }

    void acceptDirectSetup(
            IdsControlChannelCodec.SetupChannelMessage incoming,
            IdsModernSessionCoordinator.OutputBuilder output) {
        boolean isReply = incoming.forLocalGuid != null;
        IdsModernSessionCoordinator.DataState state = findDataState(
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
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.DATA_SETUP_REPLIED,
                            coord.controlConnectionId,
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

    void replyToIncomingPlainSetup(
            IdsControlChannelCodec.SetupChannelMessage incoming,
            IdsModernSessionCoordinator.OutputBuilder output) {
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
        IdsModernSessionCoordinator.DataState existing =
                coord.dataByService.get(
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
                        IdsModernSessionCoordinator.SessionEvent.simple(
                                IdsModernSessionCoordinator.EventType.DATA_SETUP_REPLIED,
                                coord.controlConnectionId,
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
                        coord.random,
                        incoming);
        IdsModernSessionCoordinator.DataState state =
                new IdsModernSessionCoordinator.DataState(coord, 
                        service,
                        route,
                        session);
        coord.dataByService.put(
                service,
                state);
        state.awaitIncomingConnector =
                true;
        sendPlainSetupReply(
                incoming,
                session.localGuid(),
                output);
        output.events.add(
                IdsModernSessionCoordinator.SessionEvent.simple(
                        IdsModernSessionCoordinator.EventType.DATA_SETUP_REPLIED,
                        coord.controlConnectionId,
                        service,
                        null));
        completeSetupEstablishment(
                state,
                output);
    }

    void sendPlainSetupReply(
            IdsControlChannelCodec.SetupChannelMessage incoming,
            String localGuid,
            IdsModernSessionCoordinator.OutputBuilder output) {
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
            coord.sendApplication(
                    activeControlConnection(),
                    frame,
                    output);
        } finally {
            reply.destroy();
            IdsModernSessionCoordinator.wipe(
                    frame);
        }
    }

    void acceptEncryptedSetup(
            IdsControlChannelCodec.SetupEncryptedChannelMessage incoming,
            IdsModernSessionCoordinator.OutputBuilder output) {
        boolean isReply = incoming.forLocalGuid != null;
        IdsModernSessionCoordinator.DataState state = findDataState(
                incoming.account,
                incoming.service,
                incoming.name,
                isReply);

        if (state == null) {
            if (incoming.forLocalGuid != null) {
                // A stale or retransmitted reply must not tear down the
                // session; the lane it belonged to is simply gone.
                java.util.function.Consumer<String> logger =
                        IdsModernSessionCoordinator.diagnosticLogger;
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
                            IdsModernSessionCoordinator.diagnosticLogger;
                    if (logger != null) {
                        logger.accept(
                                "IDS ENCRYPTED SETUP IGNORED: unroutable connector="
                                        + connectorName.encode());
                    }
                    return;
                }
                java.util.function.Consumer<String> logger =
                        IdsModernSessionCoordinator.diagnosticLogger;
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
                                    coord.ssrcs,
                                    coord.random,
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
                coord.transport.listenDataPort(
                        dc,
                        responder.reply.localPort);
                coord.sendApplication(
                        activeControlConnection(),
                        replyFrame,
                        output);
                session =
                        responder.takeSession();
                state =
                        new IdsModernSessionCoordinator.DataState(coord, 
                                service,
                                route,
                                session);
                session = null;
                coord.dataByService.put(
                        service,
                        state);
                output.events.add(
                        IdsModernSessionCoordinator.SessionEvent.simple(
                                IdsModernSessionCoordinator.EventType.DATA_SETUP_REPLIED,
                                coord.controlConnectionId,
                                service,
                                null));
            } finally {
                responder.close();
                if (session != null) {
                    session.close();
                }
                IdsModernSessionCoordinator.wipe(
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

    IdsModernSessionCoordinator.DataState findDataState(
            String account,
            String service,
            String name,
            boolean isReply) {
        IdsModernSessionCoordinator.DataState state = null;
        if (account != null && service != null && name != null) {
            try {
                IdsServiceConnectorName connectorName =
                        IdsServiceConnectorName.of(
                                account,
                                service,
                                name);
                state = coord.dataByService.get(connectorName.encode());
            } catch (Exception ignored) {
            }
        }
        if (state == null && isReply) {
            if (name != null) {
                for (IdsModernSessionCoordinator.DataState candidate : coord.dataByService.values()) {
                    if (IdsModernSessionCoordinator.serviceMatches(name, candidate.service)
                            || IdsModernSessionCoordinator.serviceMatches(candidate.service, name)) {
                        state = candidate;
                        break;
                    }
                }
            }
            if (state == null && service != null) {
                for (IdsModernSessionCoordinator.DataState candidate : coord.dataByService.values()) {
                    if (IdsModernSessionCoordinator.serviceMatches(service, candidate.service)
                            || IdsModernSessionCoordinator.serviceMatches(candidate.service, service)) {
                        state = candidate;
                        break;
                    }
                }
            }
            if (state == null) {
                for (IdsModernSessionCoordinator.DataState candidate : coord.dataByService.values()) {
                    if (!candidate.setup.established() || candidate.connection == null) {
                        state = candidate;
                        break;
                    }
                }
                if (state == null && !coord.dataByService.isEmpty()) {
                    state = coord.dataByService.values().iterator().next();
                }
            }
        }
        return state;
    }

    void completeSetupEstablishment(
            IdsModernSessionCoordinator.DataState state,
            IdsModernSessionCoordinator.OutputBuilder output) {
        if (state == null
                || !state.setup.established()) {
            return;
        }
        output.events.add(
                IdsModernSessionCoordinator.SessionEvent.simple(
                        IdsModernSessionCoordinator.EventType.DATA_SETUP_ESTABLISHED,
                        coord.controlConnectionId,
                        state.service,
                        null));
        IdsDataChannelJoinState.Result<IdsModernSessionCoordinator.DataTransport> joined =
                coord.dataJoins.receiveSetup(
                        IdsServiceConnectorName.parseLenient(state.service));
        switch (joined.action) {
            case START_OUTGOING_CONNECTOR -> {
                if (state.connection == null
                        && !state.awaitIncomingConnector) {
                    coord.dataChannelEngine.startDataConnector(
                            state,
                            output);
                }
            }
            case WAITING_FOR_SERVICE_CONNECTION -> {
            }
            case JOINED ->
                    coord.dataChannelEngine.attachDataTransport(
                            state,
                            joined.connection,
                            output);
            case CACHED_FOR_LATER_SETUP -> {
            }
        }
    }

    void reopenControlService(
            IdsModernSessionCoordinator.OutputBuilder output) {
        try (IdsServiceConnectorCoordinator.OpenResult opened =
                     coord.transport.openService(
                             IdsIpsecServiceRoute.control())) {
            coord.controlConnectionId = opened.connectionId;
            coord.controlOpenRequested = true;
            IdsModernSessionCoordinator.addPacket(
                    output,
                    opened.packet());
            output.events.add(
                    IdsModernSessionCoordinator.SessionEvent.simple(
                            IdsModernSessionCoordinator.EventType.CONTROL_CONNECT_STARTED,
                            opened.connectionId,
                            IdsIpsecServiceRoute.CONTROL_SERVICE,
                            null));
        } catch (IllegalStateException alreadyStarted) {
            // An outgoing control attempt is already pending.
        }
    }
}
