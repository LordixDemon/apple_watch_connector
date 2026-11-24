package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.List;


/** NWSC prelude framing, signature admission, collision resolution, and key-probe FSM. */
final class IdsServiceConnectorHandshakeEngine {
    private final IdsServiceConnectorCoordinator coordinator;

    IdsServiceConnectorHandshakeEngine(IdsServiceConnectorCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    IdsServiceConnectorState.ConnectionState registerIncoming(
            long connectionId) {
        IdsIpv6TcpRouter.ConnectionSnapshot snapshot =
                coordinator.tcp.connection(
                        connectionId);
        IdsServiceConnectorState.ConnectorContext connector =
                coordinator.connectorForPort(
                        snapshot.localPort,
                        snapshot.remotePort);
        IdsServiceConnectorState.ConnectionState state =
                IdsServiceConnectorState.ConnectionState.incoming(
                        connectionId,
                        connector,
                        snapshot.dataClass,
                        snapshot.localPort);
        state.isAdopted = coordinator.tcp.isAdopted(connectionId);
        IdsServiceConnectorState.ConnectionState previous =
                coordinator.connections.put(
                        connectionId,
                        state);
        if (previous != null) {
            coordinator.connections.put(
                    connectionId,
                    previous);
            state.close();
            throw new IllegalStateException(
                    "IDS incoming connection registry collision");
        }
        return state;
    }

    void processStreamBytes(
            IdsServiceConnectorState.ConnectionState state,
            byte[] bytes,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        if (bytes == null) {
            throw coordinator.protocolFailure(
                    state,
                    "IDS TCP delivered absent bytes");
        }
        if (bytes.length == 0) {
            return;
        }
        boolean feedbackFrame =
                bytes.length == NwServiceConnectorCodec.FEEDBACK_FRAME_LENGTH
                        && bytes[0] == 0
                        && (bytes[1] & 0xff) == NwServiceConnectorCodec.FEEDBACK_BODY_LENGTH;
        boolean awaitingOutgoingFeedback =
                state.outgoingAttempt != null
                        || state.phase == IdsServiceConnectorCoordinator.ConnectionPhase.OUTGOING_NORMAL_PRELUDE;
        if (feedbackFrame && awaitingOutgoingFeedback) {
            if (state.prelude.completed()) {
                state.prelude.close();
                state.prelude =
                        new IdsServiceConnectorCoordinator.PreludeBuffer();
            }
        } else if (state.phase
                == IdsServiceConnectorCoordinator.ConnectionPhase.ACTIVE_SERVICE
                || state.prelude.completed()) {
            if (feedbackFrame) {
                IdsServiceConnectorCoordinator.diagnose("drop late 44-byte Feedback on "
                        + state.phase
                        + " tcp="
                        + state.dynamicPort
                        + "->"
                        + (state.route != null ? state.route.listenerPort : 0));
                return;
            }
            events.add(
                    IdsServiceConnectorCoordinator.ConnectionEvent.application(
                            state,
                            bytes));
            return;
        } else if (state.phase == IdsServiceConnectorCoordinator.ConnectionPhase.INCOMING_PRELUDE
                && state.isAdopted) {
            state.phase = IdsServiceConnectorCoordinator.ConnectionPhase.ACTIVE_SERVICE;
            if (state.serviceName == null) {
                state.serviceName = state.connector == coordinator.cloud
                        ? IdsIpsecServiceRoute.CONTROL_SERVICE
                        : (state.dataClass == OrdinaryIkeAuth.DataClass.CLASS_C
                                ? NanoRegistryPropertyCodec.CLASS_C_SERVICE
                                : NanoRegistryPropertyCodec.CLASS_D_SERVICE);
            }
                int protectionClass = state.dataClass == OrdinaryIkeAuth.DataClass.CLASS_C
                        ? IdsUtunConnectionName.PROTECTION_CLASS_C
                        : IdsUtunConnectionName.PROTECTION_CLASS_D;
                state.route = state.connector == coordinator.cloud
                        ? IdsIpsecServiceRoute.control()
                        : IdsIpsecServiceRoute.localDelivery(
                                IdsServiceConnectorName.localDelivery(
                                        IdsUtunConnectionName.defaultPaired(
                                                IdsUtunConnectionName.PRIORITY_URGENT,
                                                protectionClass)));
            state.connector.activeByService.put(state.serviceName, state);
            IdsServiceConnectorCoordinator.diagnose("transitioned adopted stream to ACTIVE_SERVICE: connId="
                    + state.connectionId
                    + " service="
                    + state.serviceName
                    + " len="
                    + bytes.length);
            events.add(
                    IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                            IdsServiceConnectorCoordinator.EventType.SERVICE_ACCEPTED,
                            state,
                            0,
                            state.serviceName,
                            state.route));
            events.add(
                    IdsServiceConnectorCoordinator.ConnectionEvent.application(
                            state,
                            bytes));
            return;
        }

        byte[] currentBytes = bytes;
        while (currentBytes != null && currentBytes.length > 0) {
            IdsServiceConnectorCoordinator.PreludeBuffer.Result completed;
            try {
                completed =
                        state.prelude.push(
                                currentBytes);
            } catch (RuntimeException failure) {
                if (coordinator.connections.get(
                        state.connectionId)
                        == state) {
                    coordinator.removeConnectionState(
                            state,
                            true);
                }
                throw failure;
            }
            boolean preludeComplete = completed.complete();
            try {
                if (!preludeComplete) {
                    break;
                }
                byte[] frame =
                        completed.frame();
                try {
                    processPreludeFrame(
                            state,
                            frame,
                            outbound,
                            events);
                } catch (RuntimeException failure) {
                    if (coordinator.connections.get(
                            state.connectionId)
                            == state) {
                        coordinator.removeConnectionState(
                                state,
                                true);
                    }
                    throw failure;
                } finally {
                    IdsServiceConnectorCoordinator.wipe(
                            frame);
                }
                byte[] remainder =
                        completed.remainder();
                try {
                    if (remainder.length == 0) {
                        break;
                    }
                    if (state.phase == IdsServiceConnectorCoordinator.ConnectionPhase.ACTIVE_SERVICE) {
                        if (remainder.length >= NwServiceConnectorCodec.LENGTH_PREFIX_LENGTH) {
                            int nextLen = ((remainder[0] & 0xff) << 8) | (remainder[1] & 0xff);
                            if (nextLen == NwServiceConnectorCodec.FEEDBACK_BODY_LENGTH
                                    && remainder.length >= NwServiceConnectorCodec.FEEDBACK_FRAME_LENGTH) {
                                currentBytes = remainder.clone();
                                state.prelude.close();
                                state.prelude = new IdsServiceConnectorCoordinator.PreludeBuffer();
                                continue;
                            }
                        }
                        events.add(
                                IdsServiceConnectorCoordinator.ConnectionEvent.application(
                                        state,
                                        remainder));
                        break;
                    } else {
                        currentBytes = remainder.clone();
                        state.prelude.close();
                        state.prelude = new IdsServiceConnectorCoordinator.PreludeBuffer();
                    }
                } finally {
                    IdsServiceConnectorCoordinator.wipe(
                            remainder);
                }
            } finally {
                completed.close();
                if (preludeComplete && state.phase != IdsServiceConnectorCoordinator.ConnectionPhase.ACTIVE_SERVICE) {
                    state.prelude.close();
                    state.prelude =
                            new IdsServiceConnectorCoordinator.PreludeBuffer();
                }
            }
        }
    }

    boolean processPreludeFrame(
            IdsServiceConnectorState.ConnectionState state,
            byte[] frame,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        NwServiceConnectorCodec.Message message =
                NwServiceConnectorCodec.decode(
                        frame);
        try {
            if (state.phase == IdsServiceConnectorCoordinator.ConnectionPhase.REJECTED) {
                if (message instanceof NwServiceConnectorCodec.Feedback feedback) {
                    if (state.outgoingAttempt != null) {
                        return processNormalFeedback(
                                state,
                                message,
                                outbound,
                                events);
                    }
                } else if (message instanceof NwServiceConnectorCodec.SignedRequest) {
                    processIncomingRequest(
                            state,
                            message,
                            outbound,
                            events);
                    return false;
                }
            }
            return switch (state.phase) {
                case INCOMING_PRELUDE, KEY_PROBE_RESPONDED -> {
                    if (message instanceof NwServiceConnectorCodec.Feedback) {
                        if (state.outgoingAttempt != null
                                || !state.connector.outgoingByService.isEmpty()
                                || !coordinator.normal.outgoingByService.isEmpty()
                                || !coordinator.cloud.outgoingByService.isEmpty()) {
                            yield processNormalFeedback(
                                    state,
                                    message,
                                    outbound,
                                    events);
                        } else {
                            IdsServiceConnectorCoordinator.diagnose("Ignoring unsolicited NWSC Feedback without a pending service request");
                            yield false;
                        }
                    } else if (message instanceof NwServiceConnectorCodec.SignedRequest) {
                        processIncomingRequest(
                                state,
                                message,
                                outbound,
                                events);
                        yield false;
                    } else {
                        state.phase = IdsServiceConnectorCoordinator.ConnectionPhase.ACTIVE_SERVICE;
                        events.add(
                                IdsServiceConnectorCoordinator.ConnectionEvent.application(
                                        state,
                                        frame));
                        yield true;
                    }
                }
                case OUTGOING_NORMAL_PRELUDE -> {
                    if (message instanceof NwServiceConnectorCodec.Feedback) {
                        yield processNormalFeedback(
                                state,
                                message,
                                outbound,
                                events);
                    } else if (message instanceof NwServiceConnectorCodec.SignedRequest) {
                        processIncomingRequest(
                                state,
                                message,
                                outbound,
                                events);
                        yield false;
                    } else {
                        throw coordinator.protocolFailure(
                                state,
                                "Outgoing IDS normal request got mismatched message: " + message);
                    }
                }
                case OUTGOING_KEY_PROBE_PRELUDE, WAITING_FOR_REMOTE_KEY -> {
                    if (message instanceof NwServiceConnectorCodec.Feedback) {
                        processKeyProbeFeedback(
                                state,
                                message,
                                outbound,
                                events);
                        yield false;
                    } else if (message instanceof NwServiceConnectorCodec.SignedRequest) {
                        processIncomingRequest(
                                state,
                                message,
                                outbound,
                                events);
                        yield false;
                    } else {
                        throw coordinator.protocolFailure(
                                state,
                                "Waiting for remote key got invalid message: " + message);
                    }
                }
                case ACTIVE_SERVICE -> {
                    if (message instanceof NwServiceConnectorCodec.Feedback) {
                        yield processNormalFeedback(
                                state,
                                message,
                                outbound,
                                events);
                    } else if (message instanceof NwServiceConnectorCodec.SignedRequest) {
                        processIncomingRequest(
                                state,
                                message,
                                outbound,
                                events);
                        yield false;
                    } else {
                        events.add(
                                IdsServiceConnectorCoordinator.ConnectionEvent.application(
                                        state,
                                        frame));
                        yield true;
                    }
                }
                default -> throw coordinator.protocolFailure(
                        state,
                        "IDS connection has no NWSC prelude state");
            };
        } finally {
            message.destroy();
        }
    }

    void processIncomingRequest(
            IdsServiceConnectorState.ConnectionState state,
            NwServiceConnectorCodec.Message message,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        if (!(message
                instanceof NwServiceConnectorCodec
                .SignedRequest signedRequest)) {
            throw coordinator.protocolFailure(
                    state,
                    "Passive IDS connection did not start with a signed request");
        }

        if (signedRequest.localPort <= 0
                || signedRequest.localPort > 65535) {
            try (NwServiceConnectorHandshake.IncomingResult rejected =
                         state.connector.handshake(state.dataClass)
                                 .createIncomingRejection(
                                         true)) {
                sendFeedback(
                        state,
                        rejected.feedbackFrame(),
                        outbound);
            }
            state.phase =
                    IdsServiceConnectorCoordinator.ConnectionPhase.REJECTED;
            events.add(
                    IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                            IdsServiceConnectorCoordinator.EventType.SERVICE_REJECTED_BY_POLICY,
                            state,
                            0,
                            null,
                            null));
            return;
        }

        if (signedRequest
                instanceof NwServiceConnectorCodec
                .OperationRequest operation) {
            processIncomingOperation(
                    state,
                    operation,
                    outbound,
                    events);
            return;
        }
        if (!(signedRequest
                instanceof NwServiceConnectorCodec
                .NormalStartRequest normalRequest)) {
            throw coordinator.protocolFailure(
                    state,
                    "Passive IDS connection has an unknown signed request");
        }

        IdsIpsecServiceRoute route =
                IdsServiceConnectorCoordinator.routeForIncoming(
                        normalRequest.serviceName,
                        state.connector,
                        state.dataClass);
        try (NwServiceConnectorHandshake.IncomingResult decision =
                     state.connector.handshake(state.dataClass)
                             .receiveIncomingRequest(
                                     state.endpointIdentifier,
                                     normalRequest,
                                     route != null)) {
            IdsServiceConnectorState.IncomingMetadata metadata =
                    new IdsServiceConnectorState.IncomingMetadata(
                            state.connectionId,
                            route,
                            state.endpointIdentifier,
                            normalRequest.serviceName,
                            normalRequest.sequence,
                            normalRequest.requestUuid);
            try {
                applyIncomingDecision(
                        state,
                        metadata,
                        decision,
                        outbound,
                        events);
            } finally {
                if (state.pendingIncoming == null) {
                    metadata.close();
                }
            }
        }
    }

    void processIncomingOperation(
            IdsServiceConnectorState.ConnectionState state,
            NwServiceConnectorCodec.OperationRequest operation,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        try (NwServiceConnectorHandshake.IncomingResult response =
                     state.connector.handshake(state.dataClass)
                             .receiveIncomingRequest(
                                     state.endpointIdentifier,
                                     operation,
                                     true)) {
            sendFeedback(
                    state,
                    response.feedbackFrame(),
                    outbound);
            state.phase =
                    IdsServiceConnectorCoordinator.ConnectionPhase.KEY_PROBE_RESPONDED;
            events.add(
                    IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                            IdsServiceConnectorCoordinator.EventType.KEY_PROBE_RESPONDED,
                            state,
                            0,
                            null,
                            null));
            // Live Watch reverse-probes our SYN source (49154→1029),
            // stores the Ed25519 key from ACCEPTED probe Feedback, FINs
            // the probe, then sends 44-byte NWSC Feedback on the original
            // tuple (stand 15:39: 61314→1029 / 61315→1026). 0.2.97/98
            // tore that TCP down and SYNed a new port; Watch's ACCEPTED
            // landed on the dead tuple (drop stale) and ACL 0x13 followed.
            // Keep the first SYN. OPERATION_RETRY still parks a resend.
            if (operation.operation
                    == NwServiceConnectorCodec.OPERATION_NO_OP
                    && state.localPort != 0) {
                for (IdsServiceConnectorState.OutgoingAttempt attempt :
                        state.connector.outgoingByService.values()) {
                    IdsServiceConnectorState.ConnectionState outgoing =
                            coordinator.connections.get(
                                    attempt.connectionId);
                    if (outgoing != null
                            && outgoing.dynamicPort != 0
                            && outgoing.dynamicPort == state.localPort) {
                        IdsServiceConnectorCoordinator.diagnose("keeping original outgoing after Watch reverse key probe to source port "
                                + state.localPort
                                + "; waiting for ACCEPTED Feedback on that TCP");
                        break;
                    }
                }
            }
            if (response.retryPendingOutgoing) {
                for (IdsServiceConnectorState.OutgoingAttempt attempt :
                        state.connector.outgoingByService.values()) {
                    if (attempt.endpointIdentifier.equals(
                            state.endpointIdentifier)) {
                        attempt.waitingForRetry = true;
                    }
                }
                retryPendingOutgoing(
                        state.connector,
                        state.endpointIdentifier,
                        outbound,
                        events);
            }
        }
    }

    void applyIncomingDecision(
            IdsServiceConnectorState.ConnectionState state,
            IdsServiceConnectorState.IncomingMetadata metadata,
            NwServiceConnectorHandshake.IncomingResult decision,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        IdsServiceConnectorCoordinator.diagnose("incoming request service="
                + metadata.serviceName
                + " endpoint=" + state.endpointIdentifier
                + " disposition=" + decision.disposition
                + " startReverseKeyProbe=" + decision.startReverseKeyProbe
                + " routed=" + (metadata.route != null));
        if (decision.disposition
                == NwServiceConnectorHandshake
                .IncomingDisposition.WAITING_FOR_REMOTE_KEY) {
            state.phase =
                    IdsServiceConnectorCoordinator.ConnectionPhase.WAITING_FOR_REMOTE_KEY;
            state.pendingIncoming =
                    metadata;
            state.connector.pendingIncoming.add(
                    metadata);
            if (decision.startReverseKeyProbe) {
                startReverseKeyProbe(
                        state.connector,
                        state.dataClass,
                        state.connectionId,
                        outbound,
                        events);
            }
            return;
        }

        NwServiceConnectorHandshake.IncomingResult finalDecision =
                decision;
        NwServiceConnectorHandshake.IncomingResult arbitrationRejection =
                null;
        try {
            if (decision.disposition
                    == NwServiceConnectorHandshake
                    .IncomingDisposition.ACCEPTED) {
                IdsServiceConnectorState.ConnectionState active =
                        state.connector.activeByService.get(
                                metadata.serviceName);
                if (active != null
                        && !NwServiceConnectorHandshake
                        .incomingSupersedesActive(
                                metadata.sequence,
                                metadata.requestUuid,
                                active.serviceSequence,
                                active.serviceUuid)) {
                    arbitrationRejection =
                            state.connector.handshake(state.dataClass)
                                    .createIncomingRejection(
                                            false);
                    finalDecision =
                            arbitrationRejection;
                }
            }

            if (finalDecision.cancelOutgoingNormal) {
                cancelOutgoingForCollision(
                        state.connector,
                        finalDecision.cancelOutgoingServiceName,
                        state.connectionId,
                        events);
            }

            sendFeedback(
                    state,
                    finalDecision.feedbackFrame(),
                    outbound);
            switch (finalDecision.disposition) {
                case ACCEPTED -> {
                    IdsServiceConnectorState.ConnectionState active =
                            state.connector.activeByService.get(
                                    metadata.serviceName);
                    if (active != null) {
                        long supersededId =
                                active.connectionId;
                        coordinator.removeConnectionState(
                                active,
                                false);
                        events.add(
                                IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                                        IdsServiceConnectorCoordinator.EventType.ACTIVE_SUPERSEDED,
                                        state,
                                        supersededId,
                                        metadata.serviceName,
                                        metadata.route));
                    }
                    activate(
                            state,
                            metadata.route,
                            metadata.serviceName,
                            metadata.sequence,
                            metadata.requestUuid);
                    events.add(
                            IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                                    IdsServiceConnectorCoordinator.EventType.SERVICE_ACCEPTED,
                                    state,
                                    0,
                                    metadata.serviceName,
                                    metadata.route));
                }
                case REJECTED_BY_POLICY -> {
                    state.phase =
                            IdsServiceConnectorCoordinator.ConnectionPhase.REJECTED;
                    events.add(
                            IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                                    IdsServiceConnectorCoordinator.EventType
                                            .SERVICE_REJECTED_BY_POLICY,
                                    state,
                                    0,
                                    metadata.serviceName,
                                    metadata.route));
                }
                case REJECTED_TRANSIENT -> {
                    state.phase =
                            IdsServiceConnectorCoordinator.ConnectionPhase.REJECTED;
                    events.add(
                            IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                                    IdsServiceConnectorCoordinator.EventType
                                            .SERVICE_REJECTED_TRANSIENT,
                                    state,
                                    0,
                                    metadata.serviceName,
                                    metadata.route));
                }
                case WAITING_FOR_REMOTE_KEY ->
                        throw new IllegalStateException(
                                "Resolved NWSC decision remained pending");
            }
        } finally {
            if (arbitrationRejection != null) {
                arbitrationRejection.close();
            }
        }
    }

    boolean processNormalFeedback(
            IdsServiceConnectorState.ConnectionState state,
            NwServiceConnectorCodec.Message message,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        if (!(message
                instanceof NwServiceConnectorCodec.Feedback feedback)) {
            throw coordinator.protocolFailure(
                    state,
                    "Outgoing IDS normal request got mismatched feedback");
        }
        IdsServiceConnectorState.OutgoingAttempt attempt =
                state.outgoingAttempt;
        if (attempt == null && feedback != null) {
            for (IdsServiceConnectorState.OutgoingAttempt candidate : state.connector.outgoingByService.values()) {
                if (candidate.sequence == feedback.sequence) {
                    attempt = candidate;
                    break;
                }
            }
        }
        if (attempt == null && !state.connector.outgoingByService.isEmpty()) {
            attempt = state.connector.outgoingByService.values().iterator().next();
        }
        if (attempt == null) {
            IdsServiceConnectorCoordinator.diagnose("outgoing feedback has no matching attempt seq="
                    + feedback.sequence
                    + " tcp="
                    + state.dynamicPort);
            return true;
        }
        NwServiceConnectorHandshake.OutgoingDisposition disposition;
        try {
            disposition =
                    attempt.connector.handshake(IdsServiceConnectorCoordinator.dataClass(attempt.route))
                            .receiveNormalFeedback(
                                    feedback);
        } catch (IllegalArgumentException e) {
            IdsServiceConnectorCoordinator.diagnose("outgoing feedback not applied seq="
                    + feedback.sequence
                    + " tcp="
                    + state.dynamicPort
                    + " reason="
                    + e.getMessage());
            return true;
        }
        IdsServiceConnectorCoordinator.diagnose("outgoing feedback disposition="
                + disposition
                + " seq=" + feedback.sequence
                + " service=" + attempt.route.service
                + " tcp=" + state.dynamicPort
                + "->" + attempt.route.listenerPort);
        switch (disposition) {
            case ACCEPTED -> {
                state.connector.outgoingByService.remove(
                        attempt.route.service);
                activate(
                        state,
                        attempt.route,
                        attempt.route.service,
                        attempt.sequence,
                        attempt.requestUuid);
                state.outgoingAttempt = null;
                attempt.connectionId = 0;
                attempt.close();
                events.add(
                        IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                                IdsServiceConnectorCoordinator.EventType.SERVICE_ACCEPTED,
                                state,
                                0,
                                state.serviceName,
                                state.route));
                return true;
            }
            case REJECTED_BY_POLICY -> {
                // iOS 26.6 should_accept_connection block_invoke.211:
                // flags & 0x40 → cancel_request_inner, never a new SYN.
                // Every 44-byte Feedback carries a pubkey, so treating
                // "has key" as WAITING retried policy rejects too.
                state.connector.outgoingByService.remove(
                        attempt.route.service);
                state.phase =
                        IdsServiceConnectorCoordinator.ConnectionPhase.REJECTED;
                state.outgoingAttempt = null;
                attempt.connectionId = 0;
                attempt.close();
                events.add(
                        IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                                IdsServiceConnectorCoordinator.EventType.SERVICE_REJECTED_BY_POLICY,
                                state,
                                0,
                                attempt.route.service,
                                attempt.route));
                coordinator.removeConnectionState(
                        state,
                        false);
                return false;
            }
            case WAITING -> {
                // flags == 0: Apple starts a path watcher and leaves the
                // unique connection up (0x182b7ed84 → start_path_watcher).
                // Stand 15:50: Watch sent this 44-byte frame on live 1026
                // after 20s; tearing it down and SYNing 1027 caused ACL 0x13.
                IdsServiceConnectorCoordinator.diagnose("keeping original outgoing after transient Feedback; "
                        + "waiting for ACCEPTED on this TCP");
                return true;
            }
            default -> throw new IllegalStateException(
                    "Unknown outgoing NWSC disposition");
        }
    }

    void processKeyProbeFeedback(
            IdsServiceConnectorState.ConnectionState state,
            NwServiceConnectorCodec.Message message,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        if (!(message
                instanceof NwServiceConnectorCodec.Feedback feedback)) {
            throw coordinator.protocolFailure(
                    state,
                    "Outgoing IDS key probe got non-feedback message: "
                            + (message != null ? message.getClass().getSimpleName() : "null"));
        }
        IdsServiceConnectorState.ConnectorContext connector =
                state.connector;
        IdsServiceConnectorState.KeyProbe probe =
                state.keyProbe;
        if (probe == null) {
            probe = connector.keyProbes.get(state.endpointIdentifier);
        }
        List<IdsServiceConnectorState.IncomingMetadata> pending =
                connector.removePendingIncoming(
                        state.endpointIdentifier);
        try (NwServiceConnectorHandshake.IncomingBatch decisions =
                     connector.handshake(state.dataClass)
                             .receiveKeyProbeFeedback(
                                     state.endpointIdentifier,
                                     feedback)) {
            IdsServiceConnectorState.ConnectorContext other = connector == coordinator.normal ? coordinator.cloud : coordinator.normal;
            if (other != null && feedback.publicEd25519 != null) {
                String otherEndpoint = other.endpointIdentifier(state.dataClass);
                if (otherEndpoint != null) {
                    other.handshake(state.dataClass).seedRemoteKey(otherEndpoint, feedback.publicEd25519);
                }
            }
            if (decisions.size()
                    != pending.size()) {
                throw new IllegalStateException(
                        "NWSC key-probe result count is inconsistent");
            }
            if (probe != null) {
                long probeConnectionId =
                        probe.connectionId != 0 ? probe.connectionId : state.connectionId;
                state.keyProbe = null;
                probe.connectionId = 0;
                connector.keyProbes.remove(
                        state.endpointIdentifier);
                if (probeConnectionId != 0) {
                    IdsServiceConnectorState.ConnectionState probeState =
                            coordinator.connections.get(
                                    probeConnectionId);
                    if (probeState != null) {
                        coordinator.removeConnectionState(
                                probeState,
                                false);
                    }
                }
                probe.close();
                events.add(
                        IdsServiceConnectorCoordinator.ConnectionEvent.detached(
                                IdsServiceConnectorCoordinator.EventType.KEY_PROBE_COMPLETED,
                                probeConnectionId,
                                0,
                                null,
                                null));
            }

            retryPendingOutgoing(
                    connector,
                    state.endpointIdentifier,
                    outbound,
                    events);

            for (int index = 0;
                    index < pending.size();
                    index++) {
                IdsServiceConnectorState.IncomingMetadata metadata =
                        pending.get(
                                index);
                IdsServiceConnectorState.ConnectionState original =
                        coordinator.connections.get(
                                metadata.connectionId);
                try {
                    if (original == null
                            || original.pendingIncoming
                            != metadata) {
                        continue;
                    }
                    original.pendingIncoming = null;
                    applyIncomingDecision(
                            original,
                            metadata,
                            decisions.get(
                                    index),
                            outbound,
                            events);
                } finally {
                    metadata.close();
                }
            }
        } finally {
            for (IdsServiceConnectorState.IncomingMetadata metadata : pending) {
                metadata.close();
            }
        }
    }

    void startReverseKeyProbe(
            IdsServiceConnectorState.ConnectorContext connector,
            OrdinaryIkeAuth.DataClass dataClass,
            long originalConnectionId,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        String endpointIdentifier =
                connector.endpointIdentifier(
                        dataClass);
        if (connector.keyProbes.containsKey(
                endpointIdentifier)) {
            IdsServiceConnectorCoordinator.diagnose("reverse key probe suppressed: one is already pending for "
                    + endpointIdentifier);
            throw new IllegalStateException(
                    "NWSC requested a duplicate reverse key probe");
        }
        IdsServiceConnectorCoordinator.diagnose("starting reverse key probe endpoint="
                + endpointIdentifier
                + " listenerPort=" + connector.listenerPort);
        NwServiceConnectorHandshake.OutgoingStart start = null;
        IdsServiceConnectorState.KeyProbe probe = null;
        int dynamicPort =
                coordinator.ports.allocate();
        try {
            start =
                    connector.handshake(dataClass).startKeyProbe(
                            endpointIdentifier,
                            connector.listenerPort);
            byte[] frame =
                    start.frame();
            try {
                probe =
                        new IdsServiceConnectorState.KeyProbe(
                                connector,
                                endpointIdentifier,
                                start.sequence,
                                frame);
            } finally {
                IdsServiceConnectorCoordinator.wipe(
                        frame);
            }
            try (IdsIpv6TcpRouter.ActiveOpen active =
                         coordinator.tcp.openActive(
                                 dataClass,
                                 dynamicPort,
                                 connector.listenerPort,
                                 probe.frame)) {
                IdsServiceConnectorState.ConnectionState state =
                        IdsServiceConnectorState.ConnectionState.keyProbe(
                                active.connectionId,
                                connector,
                                dataClass,
                                dynamicPort,
                                probe);
                coordinator.connections.put(
                        active.connectionId,
                        state);
                probe.connectionId =
                        active.connectionId;
                connector.keyProbes.put(
                        endpointIdentifier,
                        probe);
                IdsServiceConnectorCoordinator.addPacket(
                        outbound,
                        active.dataClass,
                        active.packet());
                events.add(
                        IdsServiceConnectorCoordinator.ConnectionEvent.simple(
                                IdsServiceConnectorCoordinator.EventType.KEY_PROBE_STARTED,
                                state,
                                originalConnectionId,
                                null,
                                null));
                dynamicPort = 0;
                probe = null;
            }
        } catch (RuntimeException failure) {
            connector.handshake(dataClass).cancelKeyProbe(
                    endpointIdentifier);
            if (dynamicPort != 0) {
                coordinator.ports.release(
                        dynamicPort);
            }
            if (probe != null) {
                probe.close();
            }
            throw failure;
        } finally {
            if (start != null) {
                start.close();
            }
        }
    }

    void retryPendingOutgoing(
            IdsServiceConnectorState.ConnectorContext connector,
            String endpointIdentifier,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        List<IdsServiceConnectorState.OutgoingAttempt> attempts =
                new ArrayList<>(
                        connector.outgoingByService.values());
        for (IdsServiceConnectorState.OutgoingAttempt attempt : attempts) {
            if (!attempt.waitingForRetry) {
                continue;
            }
            long oldConnectionId =
                    attempt.connectionId;
            if (oldConnectionId != 0) {
                IdsServiceConnectorState.ConnectionState old =
                        coordinator.connections.get(
                                oldConnectionId);
                if (old != null) {
                    old.outgoingAttempt = null;
                    coordinator.removeConnectionState(
                            old,
                            false);
                }
                attempt.connectionId = 0;
            }
            IdsServiceConnectorState.ActiveTransport restarted =
                    startAttemptTransport(
                            attempt);
            try {
                attempt.waitingForRetry = false;
                IdsServiceConnectorCoordinator.addPacket(
                        outbound,
                        restarted.dataClass,
                        restarted.packet);
                events.add(
                        IdsServiceConnectorCoordinator.ConnectionEvent.detached(
                                IdsServiceConnectorCoordinator.EventType.OUTGOING_RETRIED,
                                restarted.connectionId,
                                oldConnectionId,
                                attempt.route.service,
                                attempt.route));
            } finally {
                restarted.close();
            }
        }
    }

    IdsServiceConnectorState.ActiveTransport startAttemptTransportWithPort(
            IdsServiceConnectorState.OutgoingAttempt attempt,
            int dynamicPort,
            boolean ownsDynamicPort) {
        OrdinaryIkeAuth.DataClass dataClass =
                IdsServiceConnectorCoordinator.dataClass(
                        attempt.route);
        try (IdsIpv6TcpRouter.ActiveOpen active =
                     coordinator.tcp.openActive(
                             dataClass,
                             dynamicPort,
                             attempt.route.listenerPort,
                             attempt.frame)) {
            IdsServiceConnectorState.ConnectionState state =
                    IdsServiceConnectorState.ConnectionState.outgoing(
                            active.connectionId,
                            attempt.connector,
                            dataClass,
                            dynamicPort,
                            ownsDynamicPort,
                            attempt);
            coordinator.connections.put(
                            active.connectionId,
                            state);
            attempt.connectionId =
                    active.connectionId;
            return new IdsServiceConnectorState.ActiveTransport(
                    active.connectionId,
                    active.dataClass,
                    active.packet());
        }
    }

    IdsServiceConnectorState.ActiveTransport startAttemptTransport(
            IdsServiceConnectorState.OutgoingAttempt attempt) {
        int dynamicPort =
                coordinator.ports.allocate();
        try {
            attempt.connector.handshake(IdsServiceConnectorCoordinator.dataClass(attempt.route)).cancelNormalRequest(
                    attempt.endpointIdentifier,
                    attempt.route.service);
            try (NwServiceConnectorHandshake.OutgoingStart restarted =
                         attempt.connector.handshake(IdsServiceConnectorCoordinator.dataClass(attempt.route)).startNormalRequest(
                                 attempt.endpointIdentifier,
                                 dynamicPort,
                                 attempt.requestUuid,
                                 attempt.route.service)) {
                attempt.replaceFrame(
                        restarted.sequence,
                        restarted.frame());
            }
            return startAttemptTransportWithPort(
                    attempt,
                    dynamicPort,
                    true);
        } catch (RuntimeException failure) {
            coordinator.ports.release(
                    dynamicPort);
            throw failure;
        }
    }

    void cancelOutgoingForCollision(
            IdsServiceConnectorState.ConnectorContext connector,
            String serviceName,
            long winnerConnectionId,
            List<IdsServiceConnectorCoordinator.ConnectionEvent> events) {
        IdsServiceConnectorState.OutgoingAttempt attempt =
                connector.outgoingByService.remove(
                        serviceName);
        IdsServiceConnectorState.ConnectionState active =
                connector.activeByService.remove(
                        serviceName);
        if (attempt != null) {
            long cancelledConnectionId =
                    attempt.connectionId;
            cancelAttemptTransport(
                    attempt);
            attempt.close();
            if (active != null) {
                coordinator.removeConnectionState(
                        active,
                        true);
            }
            events.add(
                    IdsServiceConnectorCoordinator.ConnectionEvent.detached(
                            IdsServiceConnectorCoordinator.EventType.OUTGOING_CANCELLED,
                            cancelledConnectionId,
                            winnerConnectionId,
                            serviceName,
                            attempt.route));
            return;
        }
        if (active != null) {
            long cancelledConnectionId =
                    active.connectionId;
            coordinator.removeConnectionState(
                    active,
                    true);
            events.add(
                    IdsServiceConnectorCoordinator.ConnectionEvent.detached(
                            IdsServiceConnectorCoordinator.EventType.OUTGOING_CANCELLED,
                            cancelledConnectionId,
                            winnerConnectionId,
                            serviceName,
                            active.route));
        }
    }

    void cancelAttemptTransport(
            IdsServiceConnectorState.OutgoingAttempt attempt) {
        if (attempt.connectionId == 0) {
            return;
        }
        IdsServiceConnectorState.ConnectionState state =
                coordinator.connections.get(
                        attempt.connectionId);
        if (state != null) {
            state.outgoingAttempt = null;
            coordinator.removeConnectionState(
                    state,
                    false);
        }
        attempt.connectionId = 0;
    }

    void sendFeedback(
            IdsServiceConnectorState.ConnectionState state,
            byte[] feedback,
            List<IdsServiceConnectorCoordinator.RoutedPacket> outbound) {
        if (feedback == null) {
            throw coordinator.protocolFailure(
                    state,
                    "NWSC decision has no feedback frame");
        }
        try (IdsIpv6TcpRouter.PacketBatch sent =
                     coordinator.tcp.send(
                             state.connectionId,
                             feedback)) {
            IdsServiceConnectorCoordinator.addPackets(
                    outbound,
                    sent.dataClass,
                    sent.packets());
        } finally {
            IdsServiceConnectorCoordinator.wipe(
                    feedback);
        }
    }

    void activate(
            IdsServiceConnectorState.ConnectionState state,
            IdsIpsecServiceRoute route,
            String serviceName,
            long sequence,
            byte[] requestUuid) {
        if (route == null
                || serviceName == null
                || requestUuid == null) {
            throw new IllegalArgumentException(
                    "Accepted IDS service metadata is incomplete");
        }
        IdsServiceConnectorState.ConnectionState previous =
                state.connector.activeByService.put(
                        serviceName,
                        state);
        if (previous != null
                && previous != state) {
            state.connector.activeByService.put(
                    serviceName,
                    previous);
            throw new IllegalStateException(
                    "IDS active-service registry collision");
        }
        state.phase =
                IdsServiceConnectorCoordinator.ConnectionPhase.ACTIVE_SERVICE;
        state.route = route;
        state.serviceName = serviceName;
        state.serviceSequence = sequence;
        IdsServiceConnectorCoordinator.wipe(
                state.serviceUuid);
        state.serviceUuid =
                requestUuid.clone();
    }
}
