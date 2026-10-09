package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production transport coordinator for the watchOS IDS
 * Network.framework service connectors.
 *
 * <p>The coordinator owns the two independent connector identities
 * (normal/61314 and cloud/61315), dynamic TCP source ports, signed NWSC
 * request state, and the Class-C/Class-D IPv6/TCP router. Exactly one NWSC
 * frame is consumed at the beginning of each connection. Once accepted,
 * every remaining byte belongs directly to the IDS control or socket-pair
 * stream and is never offered to the NWSC decoder again.</p>
 *
 * <p>All output packets retain their authenticated NetworkRelay data class.
 * The caller must pass each clear IPv6 packet through the matching
 * {@link NormalLinkPipeSession} Class-C/Class-D path.</p>
 */
final class IdsServiceConnectorCoordinator
        implements AutoCloseable {
    /**
     * Reports NWSC admission decisions. Connection events alone cannot
     * distinguish an unverifiable peer key from a probe that never starts,
     * because they are emitted before the outcome is known.
     */
    public static volatile java.util.function.Consumer<String> diagnosticLogger = null;

    private static void diagnose(String message) {
        java.util.function.Consumer<String> logger = diagnosticLogger;
        if (logger != null) {
            logger.accept("[NWSC] " + message);
        }
    }

    private static final int MAX_PRELUDE_FRAME_LENGTH =
            NwServiceConnectorCodec.NORMAL_FIXED_FRAME_LENGTH
                    + NwServiceConnectorCodec.MAX_SERVICE_LENGTH;
    private static final int MAX_INLINE_REMAINDER_LENGTH =
            64 * 1024;

    enum ConnectionPhase {
        INCOMING_PRELUDE,
        OUTGOING_NORMAL_PRELUDE,
        OUTGOING_KEY_PROBE_PRELUDE,
        WAITING_FOR_REMOTE_KEY,
        ACTIVE_SERVICE,
        KEY_PROBE_RESPONDED,
        REJECTED
    }

    enum EventType {
        SERVICE_ACCEPTED,
        APPLICATION_BYTES,
        SERVICE_REJECTED_TRANSIENT,
        SERVICE_REJECTED_BY_POLICY,
        OUTGOING_CANCELLED,
        OUTGOING_WAITING_RETRY,
        OUTGOING_RETRIED,
        ACTIVE_SUPERSEDED,
        KEY_PROBE_STARTED,
        KEY_PROBE_COMPLETED,
        KEY_PROBE_RESPONDED
    }

    private final SecureRandom random;
    private final NwServiceConnectorSequenceAllocator sequences;
    private final IdsIpv6TcpRouter tcp;
    private final IdsPortMap ports;
    private ConnectorContext normal;
    private ConnectorContext cloud;
    private final Map<Long, ConnectionState> connections =
            new LinkedHashMap<>();

    private boolean closed;

    IdsServiceConnectorCoordinator(
            SecureRandom random,
            long sequenceSeed,
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC) {
        this(
                random,
                new NwServiceConnectorSequenceAllocator(
                        sequenceSeed),
                localClassD,
                remoteClassD,
                localClassC,
                remoteClassC,
                null);
    }

    IdsServiceConnectorCoordinator(
            SecureRandom random,
            NwServiceConnectorSequenceAllocator sequences,
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC) {
        this(
                random,
                sequences,
                localClassD,
                remoteClassD,
                localClassC,
                remoteClassC,
                null);
    }

    IdsServiceConnectorCoordinator(
            SecureRandom random,
            NwServiceConnectorSequenceAllocator sequences,
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC,
            IdsPortMap portMap) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "IDS service-connector random source is absent");
        }
        if (sequences == null) {
            throw new IllegalArgumentException(
                    "IDS service-connector sequence allocator is absent");
        }
        this.random = random;
        this.sequences = sequences;
        this.ports = portMap != null ? portMap : new IdsPortMap(random);
        tcp =
                new IdsIpv6TcpRouter(
                        random,
                        localClassD,
                        remoteClassD,
                        localClassC,
                        remoteClassC);
        byte[] privateKeyD =
                new byte[NwServiceConnectorCodec.PRIVATE_KEY_LENGTH];
        random.nextBytes(
                privateKeyD);
        byte[] privateKeyC =
                new byte[NwServiceConnectorCodec.PRIVATE_KEY_LENGTH];
        random.nextBytes(
                privateKeyC);
        try {
            normal =
                    new ConnectorContext(
                            IdsIpsecServiceRoute.Connector.NORMAL,
                            IdsIpsecServiceRoute.NORMAL_LISTENER_PORT,
                            NwServiceConnectorEndpointIdentifier.ipv6(
                                    remoteClassD,
                                    IdsIpsecServiceRoute
                                            .NORMAL_LISTENER_PORT),
                            NwServiceConnectorEndpointIdentifier.ipv6(
                                    remoteClassC,
                                    IdsIpsecServiceRoute
                                            .NORMAL_LISTENER_PORT),
                            NwServiceConnectorHandshake.withPrivateKeyForTest(
                                    NwServiceConnectorEndpointIdentifier.ipv6(
                                            remoteClassD,
                                            IdsIpsecServiceRoute
                                            .NORMAL_LISTENER_PORT),
                                    IdsIpsecServiceRoute
                                            .NORMAL_LISTENER_PORT,
                                    privateKeyD,
                                    sequences),
                            NwServiceConnectorHandshake.withPrivateKeyForTest(
                                    NwServiceConnectorEndpointIdentifier.ipv6(
                                            remoteClassC,
                                            IdsIpsecServiceRoute
                                            .NORMAL_LISTENER_PORT),
                                    IdsIpsecServiceRoute
                                            .NORMAL_LISTENER_PORT,
                                    privateKeyC,
                                    sequences));
            cloud =
                    new ConnectorContext(
                            IdsIpsecServiceRoute.Connector.CLOUD,
                            IdsIpsecServiceRoute.CLOUD_LISTENER_PORT,
                            NwServiceConnectorEndpointIdentifier.ipv6(
                                    remoteClassD,
                                    IdsIpsecServiceRoute
                                            .CLOUD_LISTENER_PORT),
                            NwServiceConnectorEndpointIdentifier.ipv6(
                                    remoteClassC,
                                    IdsIpsecServiceRoute
                                            .CLOUD_LISTENER_PORT),
                            NwServiceConnectorHandshake.withPrivateKeyForTest(
                                    NwServiceConnectorEndpointIdentifier.ipv6(
                                            remoteClassD,
                                            IdsIpsecServiceRoute
                                            .CLOUD_LISTENER_PORT),
                                    IdsIpsecServiceRoute
                                            .CLOUD_LISTENER_PORT,
                                    privateKeyD,
                                    sequences),
                            NwServiceConnectorHandshake.withPrivateKeyForTest(
                                    NwServiceConnectorEndpointIdentifier.ipv6(
                                            remoteClassC,
                                            IdsIpsecServiceRoute
                                            .CLOUD_LISTENER_PORT),
                                    IdsIpsecServiceRoute
                                            .CLOUD_LISTENER_PORT,
                                    privateKeyC,
                                    sequences));
        } finally {
            wipe(
                    privateKeyD);
            wipe(
                    privateKeyC);
        }

        for (OrdinaryIkeAuth.DataClass dataClass :
                List.of(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        OrdinaryIkeAuth.DataClass.CLASS_C)) {
            tcp.listen(
                    dataClass,
                    IdsIpsecServiceRoute.NORMAL_LISTENER_PORT);
            tcp.listen(
                    dataClass,
                    IdsIpsecServiceRoute.CLOUD_LISTENER_PORT);
        }
    }

    static IdsServiceConnectorCoordinator createPaired(
            SecureRandom random,
            NwServiceConnectorSequenceAllocator sequences,
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC) {
        return createPairedWithKeys(
                random,
                sequences,
                localClassD,
                remoteClassD,
                localClassC,
                remoteClassC,
                null,
                null,
                null,
                null,
                null);
    }

    static IdsServiceConnectorCoordinator createPaired(
            SecureRandom random,
            NwServiceConnectorSequenceAllocator sequences,
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC,
            IdsPortMap portMap) {
        return createPairedWithKeys(
                random,
                sequences,
                localClassD,
                remoteClassD,
                localClassC,
                remoteClassC,
                null,
                null,
                null,
                null,
                portMap);
    }

    static IdsServiceConnectorCoordinator createPairedWithKeys(
            SecureRandom random,
            NwServiceConnectorSequenceAllocator sequences,
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC,
            byte[] explicitLocalPrivD,
            byte[] explicitRemotePubD,
            byte[] explicitLocalPrivC,
            byte[] explicitRemotePubC,
            IdsPortMap portMap) {
        byte[] localPrivD = explicitLocalPrivD != null
                ? explicitLocalPrivD.clone()
                : deriveEd25519PrivateKey(localClassD, "IDS-CLASS-D");
        byte[] localPrivC = explicitLocalPrivC != null
                ? explicitLocalPrivC.clone()
                : deriveEd25519PrivateKey(localClassC, "IDS-CLASS-C");
        byte[] remotePubD = explicitRemotePubD != null
                ? explicitRemotePubD.clone()
                : null;
        byte[] remotePubC = explicitRemotePubC != null
                ? explicitRemotePubC.clone()
                : null;

        IdsServiceConnectorCoordinator coordinator =
                new IdsServiceConnectorCoordinator(
                        random,
                        sequences,
                        localClassD,
                        remoteClassD,
                        localClassC,
                        remoteClassC,
                        portMap);
        coordinator.normal.close();
        coordinator.cloud.close();

        coordinator.normal =
                new ConnectorContext(
                        IdsIpsecServiceRoute.Connector.NORMAL,
                        IdsIpsecServiceRoute.NORMAL_LISTENER_PORT,
                        NwServiceConnectorEndpointIdentifier.ipv6(
                                remoteClassD,
                                IdsIpsecServiceRoute
                                        .NORMAL_LISTENER_PORT),
                        NwServiceConnectorEndpointIdentifier.ipv6(
                                remoteClassC,
                                IdsIpsecServiceRoute
                                        .NORMAL_LISTENER_PORT),
                        NwServiceConnectorHandshake.withPrivateKeyForTest(
                                NwServiceConnectorEndpointIdentifier.ipv6(
                                        remoteClassD,
                                        IdsIpsecServiceRoute
                                        .NORMAL_LISTENER_PORT),
                                IdsIpsecServiceRoute
                                        .NORMAL_LISTENER_PORT,
                                localPrivD,
                                sequences),
                        NwServiceConnectorHandshake.withPrivateKeyForTest(
                                NwServiceConnectorEndpointIdentifier.ipv6(
                                        remoteClassC,
                                        IdsIpsecServiceRoute
                                        .NORMAL_LISTENER_PORT),
                                IdsIpsecServiceRoute
                                        .NORMAL_LISTENER_PORT,
                                localPrivC,
                                sequences));
        coordinator.cloud =
                new ConnectorContext(
                        IdsIpsecServiceRoute.Connector.CLOUD,
                        IdsIpsecServiceRoute.CLOUD_LISTENER_PORT,
                        NwServiceConnectorEndpointIdentifier.ipv6(
                                remoteClassD,
                                IdsIpsecServiceRoute
                                        .CLOUD_LISTENER_PORT),
                        NwServiceConnectorEndpointIdentifier.ipv6(
                                remoteClassC,
                                IdsIpsecServiceRoute
                                        .CLOUD_LISTENER_PORT),
                        NwServiceConnectorHandshake.withPrivateKeyForTest(
                                NwServiceConnectorEndpointIdentifier.ipv6(
                                        remoteClassD,
                                        IdsIpsecServiceRoute
                                        .CLOUD_LISTENER_PORT),
                                IdsIpsecServiceRoute
                                        .CLOUD_LISTENER_PORT,
                                localPrivD,
                                sequences),
                        NwServiceConnectorHandshake.withPrivateKeyForTest(
                                NwServiceConnectorEndpointIdentifier.ipv6(
                                        remoteClassC,
                                        IdsIpsecServiceRoute
                                        .CLOUD_LISTENER_PORT),
                                IdsIpsecServiceRoute
                                        .CLOUD_LISTENER_PORT,
                                localPrivC,
                                sequences));

        if (remotePubD != null) {
            coordinator.normal.classDHandshake.seedRemoteKey(coordinator.normal.classDEndpointIdentifier, remotePubD);
            coordinator.cloud.classDHandshake.seedRemoteKey(coordinator.cloud.classDEndpointIdentifier, remotePubD);
        }
        if (remotePubC != null) {
            coordinator.normal.classCHandshake.seedRemoteKey(coordinator.normal.classCEndpointIdentifier, remotePubC);
            coordinator.cloud.classCHandshake.seedRemoteKey(coordinator.cloud.classCEndpointIdentifier, remotePubC);
        }
        // Paired listen-only: Watch startControlChannelWithDevice: opens
        // ids-control-channel as Class-D ephemeral→phone:61315 (live 0.2.196).
        // create() already listened; createPairedWithKeys omitted it, so a
        // Watch SYN hit stale-tuple RST and never reached NWSC accept.
        for (OrdinaryIkeAuth.DataClass dataClass :
                List.of(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        OrdinaryIkeAuth.DataClass.CLASS_C)) {
            coordinator.tcp.listen(
                    dataClass,
                    IdsIpsecServiceRoute.NORMAL_LISTENER_PORT);
            coordinator.tcp.listen(
                    dataClass,
                    IdsIpsecServiceRoute.CLOUD_LISTENER_PORT);
        }
        return coordinator;
    }

    NwServiceConnectorSequenceAllocator sequences() {
        return sequences;
    }

    /**
     * Moves the dynamic source-port cursor to a random offset. Called once by
     * the production host right after construction so a restarted process
     * does not reuse the previous session's 4-tuple (a Watch holding the
     * stale ESTABLISHED flow answers REJECTED_BY_POLICY, live 0.2.163).
     */
    synchronized void randomizePortCursor() {
        requireUsable();
        ports.randomizeCursor(
                random);
    }

    /**
     * NoOp key probes to the Watch 61314 and 61315 listeners on Class-D
     * and Class-C. Control lives on 61315; a 61314-only probe never
     * publishes a key for that endpoint.
     */
    synchronized AcceptResult startClassDKeyProbe() {
        requireUsable();
        List<RoutedPacket> outbound =
                new ArrayList<>();
        List<ConnectionEvent> events =
                new ArrayList<>();
        startReverseKeyProbe(
                normal,
                OrdinaryIkeAuth.DataClass.CLASS_D,
                0,
                outbound,
                events);
        startReverseKeyProbe(
                cloud,
                OrdinaryIkeAuth.DataClass.CLASS_D,
                0,
                outbound,
                events);
        startReverseKeyProbe(
                normal,
                OrdinaryIkeAuth.DataClass.CLASS_C,
                0,
                outbound,
                events);
        startReverseKeyProbe(
                cloud,
                OrdinaryIkeAuth.DataClass.CLASS_C,
                0,
                outbound,
                events);
        return new AcceptResult(
                outbound,
                events);
    }

    synchronized void setStaleFlowAddresses(
            byte[] localClassD,
            byte[] remoteClassD) {
        requireUsable();
        tcp.setStaleFlowAddresses(localClassD, remoteClassD);
    }

    synchronized void seedStaleFlows(
            List<IdsStaleFlowRecord> seeds) {
        requireUsable();
        tcp.seedStaleFlows(seeds);
    }

    synchronized List<IdsStaleFlowRecord> staleFlowObservations() {
        requireUsable();
        return tcp.staleFlowObservations();
    }

    /**
     * Proactive resets for Watch flows that outlived the previous process.
     * The Watch reopens its ids-control-channel only after its stale
     * control TCB dies (watchOS 26.2 FUN_10035caa4 retry path); these
     * resets kill it in one RTT instead of the ~8.6-min Watch RTO.
     */
    synchronized AcceptResult buildProactiveStaleResets() {
        requireUsable();
        List<RoutedPacket> outbound =
                new ArrayList<>();
        boolean transferred = false;
        try {
            List<IdsIpv6TcpRouter.ProactiveReset> resets =
                    tcp.buildProactiveStaleResets();
            try {
                for (IdsIpv6TcpRouter.ProactiveReset reset : resets) {
                    addPacket(
                            outbound,
                            reset.dataClass,
                            reset.packet);
                    wipe(
                            reset.packet);
                }
            } finally {
                for (IdsIpv6TcpRouter.ProactiveReset reset : resets) {
                    if (reset.packet != null) {
                        wipe(
                                reset.packet);
                    }
                }
            }
            AcceptResult result =
                    new AcceptResult(
                            outbound,
                            List.of());
            transferred = true;
            return result;
        } finally {
            if (!transferred) {
                for (RoutedPacket packet : outbound) {
                    packet.close();
                }
            }
        }
    }

    synchronized void listenDataPort(
            OrdinaryIkeAuth.DataClass dataClass,
            int port) {
        requireUsable();
        for (OrdinaryIkeAuth.DataClass dc :
                List.of(
                        OrdinaryIkeAuth.DataClass.CLASS_D,
                        OrdinaryIkeAuth.DataClass.CLASS_C)) {
            tcp.listen(
                    dc,
                    port);
        }
    }

    /**
     * Opens a signed normal request in TCP Fast Open.
     */
    synchronized OpenResult openService(
            IdsIpsecServiceRoute requestedRoute) {
        return openServiceWithPort(
                requestedRoute,
                0);
    }

    /**
     * Opens a signed normal request in TCP Fast Open with an explicit port.
     */
    synchronized OpenResult openServiceWithPort(
            IdsIpsecServiceRoute requestedRoute,
            int port) {
        requireUsable();
        IdsIpsecServiceRoute route =
                requireCanonicalRoute(
                        requestedRoute);
        ConnectorContext connector =
                connector(
                        route.connector);
        if (connector.outgoingByService.containsKey(
                route.service)
                || connector.activeByService.containsKey(
                        route.service)) {
            throw new IllegalStateException(
                    "IDS service already has an outgoing or active connection");
        }

        byte[] requestUuid =
                randomNormalUuid();
        String endpointIdentifier =
                connector.endpointIdentifier(
                        dataClass(
                                route));
        NwServiceConnectorHandshake.OutgoingStart start = null;
        OutgoingAttempt attempt = null;
        boolean ownsPort = port <= 0;
        int dynamicPort = port > 0 ? port : ports.allocate();
        try {
            // The signed port is the connector's key-probe listener, not
            // this connection's ephemeral TCP source port. Native Watch
            // requests advertise 61315/61314 even when their TCP source is dynamic.
            start =
                    connector.handshake(dataClass(route)).startNormalRequest(
                            endpointIdentifier,
                            requestUuid,
                            route.service);
            byte[] frame =
                    start.frame();
            try {
                attempt =
                        new OutgoingAttempt(
                                connector,
                                route,
                                endpointIdentifier,
                                start.sequence,
                                requestUuid,
                                frame);
            } finally {
                wipe(
                        frame);
            }
            connector.outgoingByService.put(
                    route.service,
                    attempt);

            ActiveTransport active =
                    startAttemptTransportWithPort(
                            attempt,
                            dynamicPort,
                            ownsPort);
            try {
                return new OpenResult(
                        active.connectionId,
                        route,
                        active.packet);
            } finally {
                active.close();
            }
        } catch (RuntimeException failure) {
            if (ownsPort) {
                ports.release(
                        dynamicPort);
            }
            if (attempt != null) {
                connector.outgoingByService.remove(
                        route.service);
                cancelAttemptTransport(
                        attempt);
                attempt.close();
            }
            connector.handshake(dataClass(route)).cancelNormalRequest(
                    endpointIdentifier,
                    route.service);
            throw failure;
        } finally {
            if (start != null) {
                start.close();
            }
            wipe(
                    requestUuid);
        }
    }

    /**
     * Accepts one authenticated clear IPv6 packet from NetworkRelay.
     */
    synchronized AcceptResult accept(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] clearIpv6Packet) {
        requireUsable();
        List<RoutedPacket> outbound =
                new ArrayList<>();
        List<ConnectionEvent> events =
                new ArrayList<>();
        boolean transferred = false;
        try (IdsIpv6TcpRouter.InboundResult accepted =
                     tcp.accept(
                             dataClass,
                             clearIpv6Packet)) {
            addPackets(
                    outbound,
                    accepted.dataClass,
                    accepted.outboundPackets());
            for (IdsIpv6TcpRouter.StreamEvent streamEvent :
                    accepted.events()) {
                ConnectionState state =
                        connections.get(
                                streamEvent.connectionId);
                if (state == null) {
                    state =
                            registerIncoming(
                                    streamEvent.connectionId);
                }
                List<byte[]> delivered =
                        streamEvent.deliveredBytes();
                boolean wasEmpty = delivered.isEmpty();
                try {
                    for (byte[] bytes : delivered) {
                        processStreamBytes(
                                state,
                                bytes,
                                outbound,
                                events);
                    }
                } finally {
                    wipeAll(
                            delivered);
                }
                if (streamEvent.streamClosed) {
                    if (state.phase == ConnectionPhase.ACTIVE_SERVICE) {
                        events.add(
                                ConnectionEvent.simple(
                                        EventType.ACTIVE_SUPERSEDED,
                                        state,
                                        state.connectionId,
                                        state.serviceName,
                                        state.route));
                    } else if (state.phase != ConnectionPhase.KEY_PROBE_RESPONDED) {
                        events.add(
                                ConnectionEvent.simple(
                                        EventType.OUTGOING_CANCELLED,
                                        state,
                                        0,
                                        state.serviceName,
                                        null));
                    }
                    removeConnectionState(
                            state,
                            true);
                }
            }
            AcceptResult result = new AcceptResult(
                    outbound,
                    events);
            transferred = true;
            return result;
        } catch (IllegalArgumentException staleOrUnknownPacket) {
            String msg = staleOrUnknownPacket.getMessage();
            if (msg != null && (msg.contains("did not start with a SYN")
                    || msg.contains("targets no listener")
                    || msg.contains("Unknown IDS TCP connection"))) {
                try {
                    Ipv6TcpPacketCodec.Packet packet =
                            Ipv6TcpPacketCodec.decode(clearIpv6Packet);
                    try {
                        if (!packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)) {
                            long seq;
                            long ack;
                            int flags;
                            if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK)) {
                                seq = packet.acknowledgement;
                                ack = 0L;
                                flags = Ipv6TcpPacketCodec.FLAG_RST;
                            } else {
                                seq = 0L;
                                long synCost = packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN) ? 1L : 0L;
                                long finCost = packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN) ? 1L : 0L;
                                ack = (packet.sequence + packet.payload.length + synCost + finCost) & 0xffff_ffffL;
                                flags = Ipv6TcpPacketCodec.FLAG_RST | Ipv6TcpPacketCodec.FLAG_ACK;
                            }
                            byte[] rst = Ipv6TcpPacketCodec.encode(
                                    tcp.localAddress(dataClass),
                                    tcp.remoteAddress(dataClass),
                                    packet.destinationPort,
                                    packet.sourcePort,
                                    seq,
                                    ack,
                                    flags,
                                    0,
                                    0,
                                    new byte[0],
                                    new byte[0]);
                            addPacket(
                                    outbound,
                                    dataClass,
                                    rst);
                        }
                    } finally {
                        packet.close();
                    }
                } catch (Exception ignored) {
                }
                AcceptResult result = new AcceptResult(
                        outbound,
                        events);
                transferred = true;
                return result;
            }
            throw staleOrUnknownPacket;
        } finally {
            if (!transferred) {
                closePackets(
                        outbound);
                closeEvents(
                        events);
            }
        }
    }

    /**
     * Returns whether a clear normal-link packet belongs to this IDS TCP
     * namespace without advancing any connection.
     */
    synchronized boolean recognizesInbound(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] clearIpv6Packet) {
        requireUsable();
        return tcp.recognizesInbound(
                dataClass,
                clearIpv6Packet);
    }

    synchronized List<RoutedPacket> retransmitOutstanding() {
        if (closed) {
            return List.of();
        }
        List<RoutedPacket> packets = new ArrayList<>();
        for (IdsIpv6TcpRouter.PacketBatch batch : tcp.retransmitAllOutstanding()) {
            try {
                for (byte[] p : batch.packets()) {
                    packets.add(new RoutedPacket(batch.dataClass, p));
                }
            } finally {
                batch.close();
            }
        }
        return packets;
    }

    synchronized long unacknowledgedSendBytes() {
        if (closed) {
            return 0L;
        }
        return tcp.unacknowledgedSendBytes();
    }

    synchronized String unacknowledgedSendSummary() {
        if (closed) {
            return "closed";
        }
        return tcp.unacknowledgedSendSummary();
    }

    synchronized void setPayloadFastRetransmitEnabled(
            boolean enabled) {
        tcp.setPayloadFastRetransmitEnabled(
                enabled);
    }

    /**
     * Sends direct IDS application bytes after NWSC acceptance.
     */
    synchronized PacketBatch sendApplication(
            long connectionId,
            byte[] bytes) {
        requireUsable();
        ConnectionState state =
                connections.get(connectionId);
        if (state == null || state.phase != ConnectionPhase.ACTIVE_SERVICE || !tcp.isEstablished(state.connectionId)) {
            for (ConnectionState candidate : connections.values()) {
                if (candidate.phase == ConnectionPhase.ACTIVE_SERVICE && tcp.isEstablished(candidate.connectionId)) {
                    if (state == null || (candidate.connector == state.connector && candidate.dataClass == state.dataClass)) {
                        state = candidate;
                        break;
                    }
                }
            }
        }
        if (state == null) {
            state = requireConnection(connectionId);
        }
        if (state.phase != ConnectionPhase.ACTIVE_SERVICE || !tcp.isEstablished(state.connectionId)) {
            throw new IllegalStateException(
                    "IDS application bytes require an active established TCP stream");
        }
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "IDS application bytes are absent");
        }
        List<RoutedPacket> packets =
                new ArrayList<>();
        boolean transferred = false;
        try (IdsIpv6TcpRouter.PacketBatch sent =
                     tcp.send(
                             state.connectionId,
                             bytes)) {
            addPackets(
                    packets,
                    sent.dataClass,
                    sent.packets());
            PacketBatch batch = new PacketBatch(
                    packets);
            transferred = true;
            return batch;
        } finally {
            if (!transferred) {
                closePackets(
                        packets);
            }
        }
    }

    /**
     * Emits FIN when possible and forgets all local connection state.
     */
    synchronized PacketBatch closeConnection(
            long connectionId) {
        requireUsable();
        ConnectionState state =
                connections.get(
                        connectionId);
        if (state == null) {
            return new PacketBatch(
                    List.of());
        }
        List<RoutedPacket> packets =
                new ArrayList<>();
        boolean transferred = false;
        try {
            if (tcp.connection(
                            connectionId)
                    .state
                    == Ipv6TcpStream.State.ESTABLISHED) {
                try (IdsIpv6TcpRouter.PacketBatch closedOutput =
                             tcp.closeOutput(
                                     connectionId)) {
                    addPackets(
                            packets,
                            closedOutput.dataClass,
                            closedOutput.packets());
                }
            }
            removeConnectionState(
                    state,
                    true);
            PacketBatch batch = new PacketBatch(
                    packets);
            transferred = true;
            return batch;
        } finally {
            if (!transferred) {
                closePackets(
                        packets);
            }
        }
    }

    synchronized ConnectionSnapshot connection(
            long connectionId) {
        requireUsable();
        ConnectionState state =
                requireConnection(
                        connectionId);
        IdsIpv6TcpRouter.ConnectionSnapshot tcpState =
                tcp.connection(
                        connectionId);
        return new ConnectionSnapshot(
                connectionId,
                state.phase,
                tcpState.dataClass,
                tcpState.localPort,
                tcpState.remotePort,
                state.serviceName,
                state.route);
    }

    synchronized boolean isEstablished(long connectionId) {
        if (closed) {
            return false;
        }
        return tcp.isEstablished(connectionId);
    }

    synchronized boolean isServiceActive(long connectionId) {
        if (closed) {
            return false;
        }
        ConnectionState state = connections.get(connectionId);
        if (state == null || state.phase != ConnectionPhase.ACTIVE_SERVICE) {
            return false;
        }
        return tcp.isEstablished(connectionId);
    }

    synchronized boolean isControlConnection(long connectionId) {
        if (closed) {
            return false;
        }
        ConnectionState state = connections.get(connectionId);
        if (state == null) {
            return false;
        }
        if (IdsIpsecServiceRoute.CONTROL_SERVICE.equals(state.serviceName)) {
            return true;
        }
        if (state.route != null && IdsIpsecServiceRoute.CONTROL_SERVICE.equals(state.route.service)) {
            return true;
        }
        return false;
    }

    synchronized boolean isDataConnection(long connectionId) {
        if (closed) {
            return false;
        }
        ConnectionState state = connections.get(connectionId);
        if (state == null) {
            return false;
        }
        // The cloud listener also carries UTun Cloud-C and Cloud-D data.
        // Only the accepted service name determines the application protocol.
        return state.route != null && !isControlConnection(connectionId);
    }

    synchronized boolean isAdoptedConnection(long connectionId) {
        if (closed) {
            return false;
        }
        ConnectionState state = connections.get(connectionId);
        return state != null && state.isAdopted;
    }

    synchronized String serviceNameOf(long connectionId) {
        if (closed) {
            return null;
        }
        ConnectionState state = connections.get(connectionId);
        return state != null ? state.serviceName : null;
    }

    synchronized OrdinaryIkeAuth.DataClass dataClassOf(long connectionId) {
        if (closed) {
            return null;
        }
        ConnectionState state = connections.get(connectionId);
        return state != null ? state.dataClass : null;
    }

    synchronized long findActiveControlConnectionId() {
        if (closed) {
            return 0L;
        }
        for (Map.Entry<Long, ConnectionState> entry : connections.entrySet()) {
            if (isControlConnection(entry.getKey())) {
                ConnectionState state = entry.getValue();
                if (state.phase == ConnectionPhase.ACTIVE_SERVICE) {
                    return entry.getKey();
                }
            }
        }
        return 0L;
    }

    synchronized int connectionCount() {
        requireUsable();
        return connections.size();
    }

    synchronized int activeServiceCount() {
        requireUsable();
        return normal.activeByService.size()
                + cloud.activeByService.size();
    }

    synchronized int pendingOutgoingCount() {
        requireUsable();
        return normal.outgoingByService.size()
                + cloud.outgoingByService.size();
    }

    synchronized int dynamicPortCount() {
        requireUsable();
        return ports.dynamicAllocatedCount();
    }

    private ConnectionState registerIncoming(
            long connectionId) {
        IdsIpv6TcpRouter.ConnectionSnapshot snapshot =
                tcp.connection(
                        connectionId);
        ConnectorContext connector =
                connectorForPort(
                        snapshot.localPort,
                        snapshot.remotePort);
        ConnectionState state =
                ConnectionState.incoming(
                        connectionId,
                        connector,
                        snapshot.dataClass,
                        snapshot.localPort);
        state.isAdopted = tcp.isAdopted(connectionId);
        ConnectionState previous =
                connections.put(
                        connectionId,
                        state);
        if (previous != null) {
            connections.put(
                    connectionId,
                    previous);
            state.close();
            throw new IllegalStateException(
                    "IDS incoming connection registry collision");
        }
        return state;
    }

    private void processStreamBytes(
            ConnectionState state,
            byte[] bytes,
            List<RoutedPacket> outbound,
            List<ConnectionEvent> events) {
        if (bytes == null) {
            throw protocolFailure(
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
                        || state.phase == ConnectionPhase.OUTGOING_NORMAL_PRELUDE;
        if (feedbackFrame && awaitingOutgoingFeedback) {
            if (state.prelude.completed()) {
                state.prelude.close();
                state.prelude =
                        new PreludeBuffer();
            }
        } else if (state.phase
                == ConnectionPhase.ACTIVE_SERVICE
                || state.prelude.completed()) {
            if (feedbackFrame) {
                diagnose("drop late 44-byte Feedback on "
                        + state.phase
                        + " tcp="
                        + state.dynamicPort
                        + "->"
                        + (state.route != null ? state.route.listenerPort : 0));
                return;
            }
            events.add(
                    ConnectionEvent.application(
                            state,
                            bytes));
            return;
        } else if (state.phase == ConnectionPhase.INCOMING_PRELUDE
                && state.isAdopted) {
            state.phase = ConnectionPhase.ACTIVE_SERVICE;
            if (state.serviceName == null) {
                state.serviceName = state.connector == cloud
                        ? IdsIpsecServiceRoute.CONTROL_SERVICE
                        : (state.dataClass == OrdinaryIkeAuth.DataClass.CLASS_C
                                ? NanoRegistryPropertyCodec.CLASS_C_SERVICE
                                : NanoRegistryPropertyCodec.CLASS_D_SERVICE);
            }
                int protectionClass = state.dataClass == OrdinaryIkeAuth.DataClass.CLASS_C
                        ? IdsUtunConnectionName.PROTECTION_CLASS_C
                        : IdsUtunConnectionName.PROTECTION_CLASS_D;
                state.route = state.connector == cloud
                        ? IdsIpsecServiceRoute.control()
                        : IdsIpsecServiceRoute.localDelivery(
                                IdsServiceConnectorName.localDelivery(
                                        IdsUtunConnectionName.defaultPaired(
                                                IdsUtunConnectionName.PRIORITY_URGENT,
                                                protectionClass)));
            state.connector.activeByService.put(state.serviceName, state);
            diagnose("transitioned adopted stream to ACTIVE_SERVICE: connId="
                    + state.connectionId
                    + " service="
                    + state.serviceName
                    + " len="
                    + bytes.length);
            events.add(
                    ConnectionEvent.simple(
                            EventType.SERVICE_ACCEPTED,
                            state,
                            0,
                            state.serviceName,
                            state.route));
            events.add(
                    ConnectionEvent.application(
                            state,
                            bytes));
            return;
        }

        byte[] currentBytes = bytes;
        while (currentBytes != null && currentBytes.length > 0) {
            PreludeBuffer.Result completed;
            try {
                completed =
                        state.prelude.push(
                                currentBytes);
            } catch (RuntimeException failure) {
                if (connections.get(
                        state.connectionId)
                        == state) {
                    removeConnectionState(
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
                    if (connections.get(
                            state.connectionId)
                            == state) {
                        removeConnectionState(
                                state,
                                true);
                    }
                    throw failure;
                } finally {
                    wipe(
                            frame);
                }
                byte[] remainder =
                        completed.remainder();
                try {
                    if (remainder.length == 0) {
                        break;
                    }
                    if (state.phase == ConnectionPhase.ACTIVE_SERVICE) {
                        if (remainder.length >= NwServiceConnectorCodec.LENGTH_PREFIX_LENGTH) {
                            int nextLen = ((remainder[0] & 0xff) << 8) | (remainder[1] & 0xff);
                            if (nextLen == NwServiceConnectorCodec.FEEDBACK_BODY_LENGTH
                                    && remainder.length >= NwServiceConnectorCodec.FEEDBACK_FRAME_LENGTH) {
                                currentBytes = remainder.clone();
                                state.prelude.close();
                                state.prelude = new PreludeBuffer();
                                continue;
                            }
                        }
                        events.add(
                                ConnectionEvent.application(
                                        state,
                                        remainder));
                        break;
                    } else {
                        currentBytes = remainder.clone();
                        state.prelude.close();
                        state.prelude = new PreludeBuffer();
                    }
                } finally {
                    wipe(
                            remainder);
                }
            } finally {
                completed.close();
                if (preludeComplete && state.phase != ConnectionPhase.ACTIVE_SERVICE) {
                    state.prelude.close();
                    state.prelude =
                            new PreludeBuffer();
                }
            }
        }
    }

    private boolean processPreludeFrame(
            ConnectionState state,
            byte[] frame,
            List<RoutedPacket> outbound,
            List<ConnectionEvent> events) {
        NwServiceConnectorCodec.Message message =
                NwServiceConnectorCodec.decode(
                        frame);
        try {
            if (state.phase == ConnectionPhase.REJECTED) {
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
                                || !normal.outgoingByService.isEmpty()
                                || !cloud.outgoingByService.isEmpty()) {
                            yield processNormalFeedback(
                                    state,
                                    message,
                                    outbound,
                                    events);
                        } else {
                            diagnose("Ignoring unsolicited NWSC Feedback without a pending service request");
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
                        state.phase = ConnectionPhase.ACTIVE_SERVICE;
                        events.add(
                                ConnectionEvent.application(
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
                        throw protocolFailure(
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
                        throw protocolFailure(
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
                                ConnectionEvent.application(
                                        state,
                                        frame));
                        yield true;
                    }
                }
                default -> throw protocolFailure(
                        state,
                        "IDS connection has no NWSC prelude state");
            };
        } finally {
            message.destroy();
        }
    }

    private void processIncomingRequest(
            ConnectionState state,
            NwServiceConnectorCodec.Message message,
            List<RoutedPacket> outbound,
            List<ConnectionEvent> events) {
        if (!(message
                instanceof NwServiceConnectorCodec
                .SignedRequest signedRequest)) {
            throw protocolFailure(
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
                    ConnectionPhase.REJECTED;
            events.add(
                    ConnectionEvent.simple(
                            EventType.SERVICE_REJECTED_BY_POLICY,
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
            throw protocolFailure(
                    state,
                    "Passive IDS connection has an unknown signed request");
        }

        IdsIpsecServiceRoute route =
                routeForIncoming(
                        normalRequest.serviceName,
                        state.connector,
                        state.dataClass);
        try (NwServiceConnectorHandshake.IncomingResult decision =
                     state.connector.handshake(state.dataClass)
                             .receiveIncomingRequest(
                                     state.endpointIdentifier,
                                     normalRequest,
                                     route != null)) {
            IncomingMetadata metadata =
                    new IncomingMetadata(
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

    private void processIncomingOperation(
            ConnectionState state,
            NwServiceConnectorCodec.OperationRequest operation,
            List<RoutedPacket> outbound,
            List<ConnectionEvent> events) {
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
                    ConnectionPhase.KEY_PROBE_RESPONDED;
            events.add(
                    ConnectionEvent.simple(
                            EventType.KEY_PROBE_RESPONDED,
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
                for (OutgoingAttempt attempt :
                        state.connector.outgoingByService.values()) {
                    ConnectionState outgoing =
                            connections.get(
                                    attempt.connectionId);
                    if (outgoing != null
                            && outgoing.dynamicPort != 0
                            && outgoing.dynamicPort == state.localPort) {
                        diagnose("keeping original outgoing after Watch reverse key probe to source port "
                                + state.localPort
                                + "; waiting for ACCEPTED Feedback on that TCP");
                        break;
                    }
                }
            }
            if (response.retryPendingOutgoing) {
                for (OutgoingAttempt attempt :
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

    private void applyIncomingDecision(
            ConnectionState state,
            IncomingMetadata metadata,
            NwServiceConnectorHandshake.IncomingResult decision,
            List<RoutedPacket> outbound,
            List<ConnectionEvent> events) {
        diagnose("incoming request service="
                + metadata.serviceName
                + " endpoint=" + state.endpointIdentifier
                + " disposition=" + decision.disposition
                + " startReverseKeyProbe=" + decision.startReverseKeyProbe
                + " routed=" + (metadata.route != null));
        if (decision.disposition
                == NwServiceConnectorHandshake
                .IncomingDisposition.WAITING_FOR_REMOTE_KEY) {
            state.phase =
                    ConnectionPhase.WAITING_FOR_REMOTE_KEY;
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
                ConnectionState active =
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
                    ConnectionState active =
                            state.connector.activeByService.get(
                                    metadata.serviceName);
                    if (active != null) {
                        long supersededId =
                                active.connectionId;
                        removeConnectionState(
                                active,
                                false);
                        events.add(
                                ConnectionEvent.simple(
                                        EventType.ACTIVE_SUPERSEDED,
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
                            ConnectionEvent.simple(
                                    EventType.SERVICE_ACCEPTED,
                                    state,
                                    0,
                                    metadata.serviceName,
                                    metadata.route));
                }
                case REJECTED_BY_POLICY -> {
                    state.phase =
                            ConnectionPhase.REJECTED;
                    events.add(
                            ConnectionEvent.simple(
                                    EventType
                                            .SERVICE_REJECTED_BY_POLICY,
                                    state,
                                    0,
                                    metadata.serviceName,
                                    metadata.route));
                }
                case REJECTED_TRANSIENT -> {
                    state.phase =
                            ConnectionPhase.REJECTED;
                    events.add(
                            ConnectionEvent.simple(
                                    EventType
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

    private boolean processNormalFeedback(
            ConnectionState state,
            NwServiceConnectorCodec.Message message,
            List<RoutedPacket> outbound,
            List<ConnectionEvent> events) {
        if (!(message
                instanceof NwServiceConnectorCodec.Feedback feedback)) {
            throw protocolFailure(
                    state,
                    "Outgoing IDS normal request got mismatched feedback");
        }
        OutgoingAttempt attempt =
                state.outgoingAttempt;
        if (attempt == null && feedback != null) {
            for (OutgoingAttempt candidate : state.connector.outgoingByService.values()) {
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
            diagnose("outgoing feedback has no matching attempt seq="
                    + feedback.sequence
                    + " tcp="
                    + state.dynamicPort);
            return true;
        }
        NwServiceConnectorHandshake.OutgoingDisposition disposition;
        try {
            disposition =
                    attempt.connector.handshake(dataClass(attempt.route))
                            .receiveNormalFeedback(
                                    feedback);
        } catch (IllegalArgumentException e) {
            diagnose("outgoing feedback not applied seq="
                    + feedback.sequence
                    + " tcp="
                    + state.dynamicPort
                    + " reason="
                    + e.getMessage());
            return true;
        }
        diagnose("outgoing feedback disposition="
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
                        ConnectionEvent.simple(
                                EventType.SERVICE_ACCEPTED,
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
                        ConnectionPhase.REJECTED;
                state.outgoingAttempt = null;
                attempt.connectionId = 0;
                attempt.close();
                events.add(
                        ConnectionEvent.simple(
                                EventType.SERVICE_REJECTED_BY_POLICY,
                                state,
                                0,
                                attempt.route.service,
                                attempt.route));
                removeConnectionState(
                        state,
                        false);
                return false;
            }
            case WAITING -> {
                // flags == 0: Apple starts a path watcher and leaves the
                // unique connection up (0x182b7ed84 → start_path_watcher).
                // Stand 15:50: Watch sent this 44-byte frame on live 1026
                // after 20s; tearing it down and SYNing 1027 caused ACL 0x13.
                diagnose("keeping original outgoing after transient Feedback; "
                        + "waiting for ACCEPTED on this TCP");
                return true;
            }
            default -> throw new IllegalStateException(
                    "Unknown outgoing NWSC disposition");
        }
    }

    private void processKeyProbeFeedback(
            ConnectionState state,
            NwServiceConnectorCodec.Message message,
            List<RoutedPacket> outbound,
            List<ConnectionEvent> events) {
        if (!(message
                instanceof NwServiceConnectorCodec.Feedback feedback)) {
            throw protocolFailure(
                    state,
                    "Outgoing IDS key probe got non-feedback message: "
                            + (message != null ? message.getClass().getSimpleName() : "null"));
        }
        ConnectorContext connector =
                state.connector;
        KeyProbe probe =
                state.keyProbe;
        if (probe == null) {
            probe = connector.keyProbes.get(state.endpointIdentifier);
        }
        List<IncomingMetadata> pending =
                connector.removePendingIncoming(
                        state.endpointIdentifier);
        try (NwServiceConnectorHandshake.IncomingBatch decisions =
                     connector.handshake(state.dataClass)
                             .receiveKeyProbeFeedback(
                                     state.endpointIdentifier,
                                     feedback)) {
            ConnectorContext other = connector == normal ? cloud : normal;
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
                    ConnectionState probeState =
                            connections.get(
                                    probeConnectionId);
                    if (probeState != null) {
                        removeConnectionState(
                                probeState,
                                false);
                    }
                }
                probe.close();
                events.add(
                        ConnectionEvent.detached(
                                EventType.KEY_PROBE_COMPLETED,
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
                IncomingMetadata metadata =
                        pending.get(
                                index);
                ConnectionState original =
                        connections.get(
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
            for (IncomingMetadata metadata : pending) {
                metadata.close();
            }
        }
    }

    private void startReverseKeyProbe(
            ConnectorContext connector,
            OrdinaryIkeAuth.DataClass dataClass,
            long originalConnectionId,
            List<RoutedPacket> outbound,
            List<ConnectionEvent> events) {
        String endpointIdentifier =
                connector.endpointIdentifier(
                        dataClass);
        if (connector.keyProbes.containsKey(
                endpointIdentifier)) {
            diagnose("reverse key probe suppressed: one is already pending for "
                    + endpointIdentifier);
            throw new IllegalStateException(
                    "NWSC requested a duplicate reverse key probe");
        }
        diagnose("starting reverse key probe endpoint="
                + endpointIdentifier
                + " listenerPort=" + connector.listenerPort);
        NwServiceConnectorHandshake.OutgoingStart start = null;
        KeyProbe probe = null;
        int dynamicPort =
                ports.allocate();
        try {
            start =
                    connector.handshake(dataClass).startKeyProbe(
                            endpointIdentifier,
                            connector.listenerPort);
            byte[] frame =
                    start.frame();
            try {
                probe =
                        new KeyProbe(
                                connector,
                                endpointIdentifier,
                                start.sequence,
                                frame);
            } finally {
                wipe(
                        frame);
            }
            try (IdsIpv6TcpRouter.ActiveOpen active =
                         tcp.openActive(
                                 dataClass,
                                 dynamicPort,
                                 connector.listenerPort,
                                 probe.frame)) {
                ConnectionState state =
                        ConnectionState.keyProbe(
                                active.connectionId,
                                connector,
                                dataClass,
                                dynamicPort,
                                probe);
                connections.put(
                        active.connectionId,
                        state);
                probe.connectionId =
                        active.connectionId;
                connector.keyProbes.put(
                        endpointIdentifier,
                        probe);
                addPacket(
                        outbound,
                        active.dataClass,
                        active.packet());
                events.add(
                        ConnectionEvent.simple(
                                EventType.KEY_PROBE_STARTED,
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
                ports.release(
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

    private void retryPendingOutgoing(
            ConnectorContext connector,
            String endpointIdentifier,
            List<RoutedPacket> outbound,
            List<ConnectionEvent> events) {
        List<OutgoingAttempt> attempts =
                new ArrayList<>(
                        connector.outgoingByService.values());
        for (OutgoingAttempt attempt : attempts) {
            if (!attempt.waitingForRetry) {
                continue;
            }
            long oldConnectionId =
                    attempt.connectionId;
            if (oldConnectionId != 0) {
                ConnectionState old =
                        connections.get(
                                oldConnectionId);
                if (old != null) {
                    old.outgoingAttempt = null;
                    removeConnectionState(
                            old,
                            false);
                }
                attempt.connectionId = 0;
            }
            ActiveTransport restarted =
                    startAttemptTransport(
                            attempt);
            try {
                attempt.waitingForRetry = false;
                addPacket(
                        outbound,
                        restarted.dataClass,
                        restarted.packet);
                events.add(
                        ConnectionEvent.detached(
                                EventType.OUTGOING_RETRIED,
                                restarted.connectionId,
                                oldConnectionId,
                                attempt.route.service,
                                attempt.route));
            } finally {
                restarted.close();
            }
        }
    }

    private ActiveTransport startAttemptTransportWithPort(
            OutgoingAttempt attempt,
            int dynamicPort,
            boolean ownsDynamicPort) {
        OrdinaryIkeAuth.DataClass dataClass =
                dataClass(
                        attempt.route);
        try (IdsIpv6TcpRouter.ActiveOpen active =
                     tcp.openActive(
                             dataClass,
                             dynamicPort,
                             attempt.route.listenerPort,
                             attempt.frame)) {
            ConnectionState state =
                    ConnectionState.outgoing(
                            active.connectionId,
                            attempt.connector,
                            dataClass,
                            dynamicPort,
                            ownsDynamicPort,
                            attempt);
            connections.put(
                            active.connectionId,
                            state);
            attempt.connectionId =
                    active.connectionId;
            return new ActiveTransport(
                    active.connectionId,
                    active.dataClass,
                    active.packet());
        }
    }

    private ActiveTransport startAttemptTransport(
            OutgoingAttempt attempt) {
        int dynamicPort =
                ports.allocate();
        try {
            attempt.connector.handshake(dataClass(attempt.route)).cancelNormalRequest(
                    attempt.endpointIdentifier,
                    attempt.route.service);
            try (NwServiceConnectorHandshake.OutgoingStart restarted =
                         attempt.connector.handshake(dataClass(attempt.route)).startNormalRequest(
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
            ports.release(
                    dynamicPort);
            throw failure;
        }
    }

    private void cancelOutgoingForCollision(
            ConnectorContext connector,
            String serviceName,
            long winnerConnectionId,
            List<ConnectionEvent> events) {
        OutgoingAttempt attempt =
                connector.outgoingByService.remove(
                        serviceName);
        ConnectionState active =
                connector.activeByService.remove(
                        serviceName);
        if (attempt != null) {
            long cancelledConnectionId =
                    attempt.connectionId;
            cancelAttemptTransport(
                    attempt);
            attempt.close();
            if (active != null) {
                removeConnectionState(
                        active,
                        true);
            }
            events.add(
                    ConnectionEvent.detached(
                            EventType.OUTGOING_CANCELLED,
                            cancelledConnectionId,
                            winnerConnectionId,
                            serviceName,
                            attempt.route));
            return;
        }
        if (active != null) {
            long cancelledConnectionId =
                    active.connectionId;
            removeConnectionState(
                    active,
                    true);
            events.add(
                    ConnectionEvent.detached(
                            EventType.OUTGOING_CANCELLED,
                            cancelledConnectionId,
                            winnerConnectionId,
                            serviceName,
                            active.route));
        }
    }

    private void cancelAttemptTransport(
            OutgoingAttempt attempt) {
        if (attempt.connectionId == 0) {
            return;
        }
        ConnectionState state =
                connections.get(
                        attempt.connectionId);
        if (state != null) {
            state.outgoingAttempt = null;
            removeConnectionState(
                    state,
                    false);
        }
        attempt.connectionId = 0;
    }

    private void sendFeedback(
            ConnectionState state,
            byte[] feedback,
            List<RoutedPacket> outbound) {
        if (feedback == null) {
            throw protocolFailure(
                    state,
                    "NWSC decision has no feedback frame");
        }
        try (IdsIpv6TcpRouter.PacketBatch sent =
                     tcp.send(
                             state.connectionId,
                             feedback)) {
            addPackets(
                    outbound,
                    sent.dataClass,
                    sent.packets());
        } finally {
            wipe(
                    feedback);
        }
    }

    private void activate(
            ConnectionState state,
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
        ConnectionState previous =
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
                ConnectionPhase.ACTIVE_SERVICE;
        state.route = route;
        state.serviceName = serviceName;
        state.serviceSequence = sequence;
        wipe(
                state.serviceUuid);
        state.serviceUuid =
                requestUuid.clone();
    }

    private void removeConnectionState(
            ConnectionState state,
            boolean cancelHandshakeState) {
        connections.remove(
                state.connectionId);
        tcp.removeConnection(
                state.connectionId);
        if (state.dynamicPort != 0) {
            if (state.ownsDynamicPort) {
                ports.release(
                        state.dynamicPort);
            }
            state.dynamicPort = 0;
        }
        if (state.phase
                == ConnectionPhase.ACTIVE_SERVICE
                && state.serviceName != null) {
            state.connector.activeByService.remove(
                    state.serviceName,
                    state);
        }
        if (state.pendingIncoming != null) {
            /*
             * The handshake owns an ordered clone until the current key probe
             * resolves. Leave the metadata in the parallel queue so result
             * indexes remain aligned, but mark the transport as abandoned.
             */
            state.pendingIncoming.abandoned = true;
            state.pendingIncoming = null;
        }
        if (state.outgoingAttempt != null) {
            OutgoingAttempt attempt =
                    state.outgoingAttempt;
            state.outgoingAttempt = null;
            attempt.connectionId = 0;
            if (cancelHandshakeState) {
                state.connector.outgoingByService.remove(
                        attempt.route.service);
                state.connector.handshake(dataClass(attempt.route)).cancelNormalRequest(
                        attempt.endpointIdentifier,
                        attempt.route.service);
                attempt.close();
            }
        }
        if (state.keyProbe != null) {
            KeyProbe probe =
                    state.keyProbe;
            state.keyProbe = null;
            probe.connectionId = 0;
            state.connector.keyProbes.remove(
                    probe.endpointIdentifier);
            if (cancelHandshakeState) {
                state.connector.handshake(state.dataClass).cancelKeyProbe(
                        probe.endpointIdentifier);
            }
            probe.close();
        }
        state.close();
    }

    private IllegalArgumentException protocolFailure(
            ConnectionState state,
            String message) {
        removeConnectionState(
                state,
                true);
        return new IllegalArgumentException(
                message);
    }

    private ConnectionState requireConnection(
            long connectionId) {
        ConnectionState state =
                connections.get(
                        connectionId);
        if (state == null) {
            throw new IllegalArgumentException(
                    "Unknown IDS service-connector connection");
        }
        return state;
    }

    private ConnectorContext connector(
            IdsIpsecServiceRoute.Connector connector) {
        return connector
                == IdsIpsecServiceRoute.Connector.NORMAL
                ? normal
                : cloud;
    }

    private ConnectorContext connectorForPort(
            int localPort,
            int remotePort) {
        if (localPort == cloud.listenerPort
                || remotePort == cloud.listenerPort) {
            return cloud;
        }
        if (localPort == normal.listenerPort
                || remotePort == normal.listenerPort) {
            return normal;
        }
        for (ConnectionState existing : connections.values()) {
            if (existing.outgoingAttempt != null
                    && existing.dynamicPort == localPort) {
                return existing.connector;
            }
        }
        return normal;
    }

    private static OrdinaryIkeAuth.DataClass dataClass(
            IdsIpsecServiceRoute route) {
        return switch (route.networkRelayDataClass) {
            case IdsIpsecServiceRoute.NETWORK_RELAY_CLASS_D ->
                    OrdinaryIkeAuth.DataClass.CLASS_D;
            case IdsIpsecServiceRoute.NETWORK_RELAY_CLASS_C ->
                    OrdinaryIkeAuth.DataClass.CLASS_C;
            default -> throw new IllegalArgumentException(
                    "IDS route has an unknown NetworkRelay class");
        };
    }

    private static IdsIpsecServiceRoute routeForIncoming(
            String serviceName,
            ConnectorContext connector,
            OrdinaryIkeAuth.DataClass dataClass) {
        if (serviceName == null) {
            return null;
        }
        IdsIpsecServiceRoute candidate = null;
        try {
            if (IdsIpsecServiceRoute.CONTROL_SERVICE.equals(
                    serviceName)) {
                candidate =
                        IdsIpsecServiceRoute.control();
            } else {
                IdsServiceConnectorName parsed =
                        IdsServiceConnectorName.parseCanonical(
                                serviceName);
                candidate =
                        IdsIpsecServiceRoute.localDelivery(
                                parsed);
            }
        } catch (IllegalArgumentException invalid) {
            if (connector.connector == IdsIpsecServiceRoute.Connector.CLOUD) {
                candidate = IdsIpsecServiceRoute.control();
            } else {
                int protection = dataClass == OrdinaryIkeAuth.DataClass.CLASS_C
                        ? IdsUtunConnectionName.PROTECTION_CLASS_C
                        : IdsUtunConnectionName.PROTECTION_CLASS_D;
                candidate = IdsIpsecServiceRoute.localDelivery(
                        IdsServiceConnectorName.localDelivery(
                                IdsUtunConnectionName.defaultPaired(
                                        IdsUtunConnectionName.PRIORITY_URGENT,
                                        protection)));
            }
        }
        return candidate;
    }

    private static IdsIpsecServiceRoute requireCanonicalRoute(
            IdsIpsecServiceRoute route) {
        if (route == null) {
            throw new IllegalArgumentException(
                    "IDS service route is absent");
        }
        IdsIpsecServiceRoute canonical;
        if (IdsIpsecServiceRoute.CONTROL_SERVICE.equals(
                route.service)) {
            canonical =
                    IdsIpsecServiceRoute.control();
        } else {
            canonical =
                    IdsIpsecServiceRoute.localDelivery(
                            IdsServiceConnectorName.parseCanonical(
                                    route.service));
        }
        if (canonical.connector
                != route.connector
                || canonical.listenerPort
                != route.listenerPort
                || canonical.networkRelayDataClass
                != route.networkRelayDataClass
                || canonical.allowsQuickRelay
                != route.allowsQuickRelay
                || !canonical.service.equals(
                route.service)) {
            throw new IllegalArgumentException(
                    "IDS service route is not canonical");
        }
        return route;
    }

    private byte[] randomNormalUuid() {
        byte[] uuid =
                new byte[NwServiceConnectorCodec.UUID_LENGTH];
        random.nextBytes(
                uuid);
        if (uuid[0] == 0 && uuid[1] == 0 && uuid[2] == 0 && uuid[3] == 0) {
            uuid[0] = 1;
        }
        return uuid;
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS service-connector coordinator is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ConnectionState state :
                new ArrayList<>(
                        connections.values())) {
            removeConnectionState(
                    state,
                    false);
        }
        connections.clear();
        normal.close();
        cloud.close();
        tcp.close();
        ports.close();
    }

    private static void addPackets(
            List<RoutedPacket> output,
            OrdinaryIkeAuth.DataClass dataClass,
            List<byte[]> packets) {
        try {
            for (byte[] packet : packets) {
                addPacket(
                        output,
                        dataClass,
                        packet);
            }
        } finally {
            wipeAll(
                    packets);
        }
    }

    private static void addPacket(
            List<RoutedPacket> output,
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] packet) {
        try {
            output.add(
                    new RoutedPacket(
                            dataClass,
                            packet));
        } finally {
            wipe(
                    packet);
        }
    }

    private static void closePackets(
            List<RoutedPacket> packets) {
        for (RoutedPacket packet : packets) {
            packet.close();
        }
        packets.clear();
    }

    private static void closeEvents(
            List<ConnectionEvent> events) {
        for (ConnectionEvent event : events) {
            event.close();
        }
        events.clear();
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
        for (byte[] value : values) {
            wipe(
                    value);
        }
        values.clear();
    }

    static final class OpenResult
            implements AutoCloseable {
        final long connectionId;
        final IdsIpsecServiceRoute route;
        private RoutedPacket packet;
        private boolean closed;

        private OpenResult(
                long connectionId,
                IdsIpsecServiceRoute route,
                byte[] packet) {
            this.connectionId =
                    connectionId;
            this.route = route;
            this.packet =
                    new RoutedPacket(
                            dataClass(
                                    route),
                            packet);
        }

        RoutedPacket packet() {
            requireOpen();
            return packet.copy();
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS service open result is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            packet.close();
            packet = null;
        }
    }

    static final class AcceptResult
            implements AutoCloseable {
        private final List<RoutedPacket> outboundPackets;
        private final List<ConnectionEvent> events;
        private boolean closed;

        private AcceptResult(
                List<RoutedPacket> outboundPackets,
                List<ConnectionEvent> events) {
            List<RoutedPacket> packetCopies =
                    new ArrayList<>(
                            outboundPackets.size());
            for (RoutedPacket packet : outboundPackets) {
                packetCopies.add(
                        packet.copy());
            }
            this.outboundPackets =
                    packetCopies;
            List<ConnectionEvent> eventCopies =
                    new ArrayList<>(
                            events.size());
            for (ConnectionEvent event : events) {
                eventCopies.add(
                        event.copy());
            }
            this.events =
                    eventCopies;
        }

        List<RoutedPacket> outboundPackets() {
            requireOpen();
            List<RoutedPacket> output =
                    new ArrayList<>(
                            outboundPackets.size());
            for (RoutedPacket packet : outboundPackets) {
                output.add(
                        packet.copy());
            }
            return output;
        }

        List<ConnectionEvent> events() {
            requireOpen();
            return Collections.unmodifiableList(
                    events);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS service accept result is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closePackets(
                    outboundPackets);
            closeEvents(
                    events);
        }
    }

    static final class PacketBatch
            implements AutoCloseable {
        private final List<RoutedPacket> packets;
        private boolean closed;

        private PacketBatch(
                List<RoutedPacket> packets) {
            List<RoutedPacket> copies =
                    new ArrayList<>(
                            packets.size());
            for (RoutedPacket packet : packets) {
                copies.add(
                        packet.copy());
            }
            this.packets =
                    copies;
        }

        List<RoutedPacket> packets() {
            requireOpen();
            List<RoutedPacket> output =
                    new ArrayList<>(
                            packets.size());
            for (RoutedPacket packet : packets) {
                output.add(
                        packet.copy());
            }
            return output;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS service packet batch is closed");
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
            requireOpen();
            return clearIpv6Packet.clone();
        }

        private RoutedPacket copy() {
            requireOpen();
            return new RoutedPacket(
                    dataClass,
                    clearIpv6Packet);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS routed packet is closed");
            }
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

    static final class ConnectionEvent
            implements AutoCloseable {
        final EventType type;
        final long connectionId;
        final long relatedConnectionId;
        final String serviceName;
        final IdsIpsecServiceRoute route;
        private byte[] applicationBytes;
        private boolean closed;

        private ConnectionEvent(
                EventType type,
                long connectionId,
                long relatedConnectionId,
                String serviceName,
                IdsIpsecServiceRoute route,
                byte[] applicationBytes) {
            this.type = type;
            this.connectionId =
                    connectionId;
            this.relatedConnectionId =
                    relatedConnectionId;
            this.serviceName =
                    serviceName;
            this.route = route;
            this.applicationBytes =
                    applicationBytes == null
                            ? new byte[0]
                            : applicationBytes.clone();
        }

        private static ConnectionEvent application(
                ConnectionState state,
                byte[] bytes) {
            return new ConnectionEvent(
                    EventType.APPLICATION_BYTES,
                    state.connectionId,
                    0,
                    state.serviceName,
                    state.route,
                    bytes);
        }

        private static ConnectionEvent simple(
                EventType type,
                ConnectionState state,
                long relatedConnectionId,
                String serviceName,
                IdsIpsecServiceRoute route) {
            return detached(
                    type,
                    state.connectionId,
                    relatedConnectionId,
                    serviceName,
                    route);
        }

        private static ConnectionEvent detached(
                EventType type,
                long connectionId,
                long relatedConnectionId,
                String serviceName,
                IdsIpsecServiceRoute route) {
            return new ConnectionEvent(
                    type,
                    connectionId,
                    relatedConnectionId,
                    serviceName,
                    route,
                    null);
        }

        byte[] applicationBytes() {
            requireOpen();
            return applicationBytes.clone();
        }

        private ConnectionEvent copy() {
            requireOpen();
            return new ConnectionEvent(
                    type,
                    connectionId,
                    relatedConnectionId,
                    serviceName,
                    route,
                    applicationBytes);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS service event is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    applicationBytes);
            applicationBytes =
                    new byte[0];
        }
    }

    static final class ConnectionSnapshot {
        final long connectionId;
        final ConnectionPhase phase;
        final OrdinaryIkeAuth.DataClass dataClass;
        final int localPort;
        final int remotePort;
        final String serviceName;
        final IdsIpsecServiceRoute route;

        private ConnectionSnapshot(
                long connectionId,
                ConnectionPhase phase,
                OrdinaryIkeAuth.DataClass dataClass,
                int localPort,
                int remotePort,
                String serviceName,
                IdsIpsecServiceRoute route) {
            this.connectionId =
                    connectionId;
            this.phase =
                    phase;
            this.dataClass =
                    dataClass;
            this.localPort =
                    localPort;
            this.remotePort =
                    remotePort;
            this.serviceName =
                    serviceName;
            this.route = route;
        }
    }

    private static final class ConnectorContext
            implements AutoCloseable {
        final IdsIpsecServiceRoute.Connector connector;
        final int listenerPort;
        final String classDEndpointIdentifier;
        final String classCEndpointIdentifier;
        final NwServiceConnectorHandshake classDHandshake;
        final NwServiceConnectorHandshake classCHandshake;
        final Map<String, OutgoingAttempt> outgoingByService =
                new LinkedHashMap<>();
        final Map<String, ConnectionState> activeByService =
                new LinkedHashMap<>();
        final List<IncomingMetadata> pendingIncoming =
                new ArrayList<>();
        final Map<String, KeyProbe> keyProbes =
                new LinkedHashMap<>();

        private ConnectorContext(
                IdsIpsecServiceRoute.Connector connector,
                int listenerPort,
                String classDEndpointIdentifier,
                String classCEndpointIdentifier,
                NwServiceConnectorHandshake classDHandshake,
                NwServiceConnectorHandshake classCHandshake) {
            this.connector =
                    connector;
            this.listenerPort =
                    listenerPort;
            this.classDEndpointIdentifier =
                    classDEndpointIdentifier;
            this.classCEndpointIdentifier =
                    classCEndpointIdentifier;
            this.classDHandshake =
                    classDHandshake;
            this.classCHandshake =
                    classCHandshake;
        }

        NwServiceConnectorHandshake handshake(
                OrdinaryIkeAuth.DataClass dataClass) {
            return dataClass == OrdinaryIkeAuth.DataClass.CLASS_D
                    ? classDHandshake
                    : classCHandshake;
        }

        NwServiceConnectorHandshake handshake(
                String endpointIdentifier) {
            if (classCEndpointIdentifier != null
                    && classCEndpointIdentifier.equals(endpointIdentifier)) {
                return classCHandshake;
            }
            return classDHandshake;
        }

        private String endpointIdentifier(
                OrdinaryIkeAuth.DataClass dataClass) {
            return switch (dataClass) {
                case CLASS_D -> classDEndpointIdentifier;
                case CLASS_C -> classCEndpointIdentifier;
            };
        }

        private List<IncomingMetadata> removePendingIncoming(
                String endpointIdentifier) {
            List<IncomingMetadata> matching =
                    new ArrayList<>();
            for (int index = 0;
                    index < pendingIncoming.size();) {
                IncomingMetadata metadata =
                        pendingIncoming.get(
                                index);
                if (metadata.endpointIdentifier.equals(
                        endpointIdentifier)) {
                    matching.add(
                            metadata);
                    pendingIncoming.remove(
                            index);
                } else {
                    index++;
                }
            }
            return matching;
        }

        @Override
        public void close() {
            for (OutgoingAttempt attempt :
                    outgoingByService.values()) {
                attempt.close();
            }
            outgoingByService.clear();
            activeByService.clear();
            for (IncomingMetadata metadata : pendingIncoming) {
                metadata.close();
            }
            pendingIncoming.clear();
            for (KeyProbe keyProbe :
                    keyProbes.values()) {
                keyProbe.close();
            }
            keyProbes.clear();
            if (classDHandshake != null) {
                classDHandshake.close();
            }
            if (classCHandshake != null) {
                classCHandshake.close();
            }
        }
    }

    private static final class ConnectionState
            implements AutoCloseable {
        final long connectionId;
        final ConnectorContext connector;
        final OrdinaryIkeAuth.DataClass dataClass;
        final String endpointIdentifier;
        PreludeBuffer prelude =
                new PreludeBuffer();
        ConnectionPhase phase;
        int dynamicPort;
        boolean ownsDynamicPort;
        int localPort;
        boolean isAdopted;
        OutgoingAttempt outgoingAttempt;
        KeyProbe keyProbe;
        IncomingMetadata pendingIncoming;
        IdsIpsecServiceRoute route;
        String serviceName;
        long serviceSequence;
        byte[] serviceUuid =
                new byte[0];
        boolean closed;

        private ConnectionState(
                long connectionId,
                ConnectorContext connector,
                OrdinaryIkeAuth.DataClass dataClass,
                ConnectionPhase phase,
                int dynamicPort) {
            this.connectionId =
                    connectionId;
            this.connector =
                    connector;
            this.dataClass =
                    dataClass;
            endpointIdentifier =
                    connector.endpointIdentifier(
                            dataClass);
            this.phase =
                    phase;
            this.dynamicPort =
                    dynamicPort;
        }

        private static ConnectionState incoming(
                long connectionId,
                ConnectorContext connector,
                OrdinaryIkeAuth.DataClass dataClass,
                int localPort) {
            ConnectionState state = new ConnectionState(
                    connectionId,
                    connector,
                    dataClass,
                    ConnectionPhase.INCOMING_PRELUDE,
                    0);
            state.localPort = localPort;
            return state;
        }

        private static ConnectionState outgoing(
                long connectionId,
                ConnectorContext connector,
                OrdinaryIkeAuth.DataClass dataClass,
                int dynamicPort,
                boolean ownsDynamicPort,
                OutgoingAttempt attempt) {
            ConnectionState state =
                    new ConnectionState(
                            connectionId,
                            connector,
                            dataClass,
                            ConnectionPhase.OUTGOING_NORMAL_PRELUDE,
                            dynamicPort);
            state.ownsDynamicPort =
                    ownsDynamicPort;
            state.outgoingAttempt =
                    attempt;
            return state;
        }

        private static ConnectionState keyProbe(
                long connectionId,
                ConnectorContext connector,
                OrdinaryIkeAuth.DataClass dataClass,
                int dynamicPort,
                KeyProbe probe) {
            ConnectionState state =
                    new ConnectionState(
                            connectionId,
                            connector,
                            dataClass,
                            ConnectionPhase
                                    .OUTGOING_KEY_PROBE_PRELUDE,
                            dynamicPort);
            state.ownsDynamicPort =
                    true;
            state.keyProbe =
                    probe;
            return state;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            prelude.close();
            wipe(
                    serviceUuid);
            serviceUuid =
                    new byte[0];
        }
    }

    private static final class OutgoingAttempt
            implements AutoCloseable {
        final ConnectorContext connector;
        final IdsIpsecServiceRoute route;
        final String endpointIdentifier;
        final byte[] requestUuid;
        long sequence;
        byte[] frame;
        long connectionId;
        boolean waitingForRetry;
        boolean closed;

        private OutgoingAttempt(
                ConnectorContext connector,
                IdsIpsecServiceRoute route,
                String endpointIdentifier,
                long sequence,
                byte[] requestUuid,
                byte[] frame) {
            this.connector =
                    connector;
            this.route = route;
            this.endpointIdentifier =
                    endpointIdentifier;
            this.sequence =
                    sequence;
            this.requestUuid =
                    requestUuid.clone();
            this.frame =
                    frame.clone();
        }

        void replaceFrame(long newSequence, byte[] newFrame) {
            wipe(this.frame);
            this.sequence = newSequence;
            this.frame = newFrame.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    requestUuid);
            wipe(
                    frame);
        }
    }

    private static final class KeyProbe
            implements AutoCloseable {
        final ConnectorContext connector;
        final String endpointIdentifier;
        final long sequence;
        final byte[] frame;
        long connectionId;
        boolean closed;

        private KeyProbe(
                ConnectorContext connector,
                String endpointIdentifier,
                long sequence,
                byte[] frame) {
            this.connector =
                    connector;
            this.endpointIdentifier =
                    endpointIdentifier;
            this.sequence =
                    sequence;
            this.frame =
                    frame.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    frame);
        }
    }

    private static final class IncomingMetadata
            implements AutoCloseable {
        final long connectionId;
        final IdsIpsecServiceRoute route;
        final String endpointIdentifier;
        final String serviceName;
        final long sequence;
        final byte[] requestUuid;
        boolean abandoned;
        boolean closed;

        private IncomingMetadata(
                long connectionId,
                IdsIpsecServiceRoute route,
                String endpointIdentifier,
                String serviceName,
                long sequence,
                byte[] requestUuid) {
            this.connectionId =
                    connectionId;
            this.route = route;
            this.endpointIdentifier =
                    endpointIdentifier;
            this.serviceName =
                    serviceName;
            this.sequence =
                    sequence;
            this.requestUuid =
                    requestUuid.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    requestUuid);
        }
    }

    private static final class ActiveTransport
            implements AutoCloseable {
        final long connectionId;
        final OrdinaryIkeAuth.DataClass dataClass;
        byte[] packet;
        boolean closed;

        private ActiveTransport(
                long connectionId,
                OrdinaryIkeAuth.DataClass dataClass,
                byte[] packet) {
            this.connectionId =
                    connectionId;
            this.dataClass =
                    dataClass;
            this.packet =
                    packet.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    packet);
            packet =
                    new byte[0];
        }
    }

    static final class PreludeBuffer
            implements AutoCloseable {
        private byte[] buffer =
                new byte[0];
        private boolean complete;
        private boolean closed;

        boolean completed() {
            return complete;
        }

        Result push(
                byte[] bytes) {
            requireUsable();
            if (bytes == null
                    || (long) buffer.length
                    + bytes.length
                    > MAX_PRELUDE_FRAME_LENGTH
                    + MAX_INLINE_REMAINDER_LENGTH) {
                fail();
                throw new IllegalArgumentException(
                        "NWSC first-frame buffer exceeds safety limit");
            }
            byte[] combined =
                    new byte[
                            buffer.length
                                    + bytes.length];
            System.arraycopy(
                    buffer,
                    0,
                    combined,
                    0,
                    buffer.length);
            System.arraycopy(
                    bytes,
                    0,
                    combined,
                    buffer.length,
                    bytes.length);
            wipe(
                    buffer);
            buffer =
                    new byte[0];
            try {
                if (combined.length
                        < NwServiceConnectorCodec
                        .LENGTH_PREFIX_LENGTH) {
                    buffer =
                            combined;
                    combined = null;
                    return Result.incomplete();
                }
                int bodyLength =
                        ((combined[0] & 0xff) << 8)
                                | (combined[1] & 0xff);
                requirePossibleBodyLength(
                        bodyLength);
                int frameLength =
                        NwServiceConnectorCodec
                                .LENGTH_PREFIX_LENGTH
                                + bodyLength;
                if (combined.length < frameLength) {
                    buffer =
                            combined;
                    combined = null;
                    return Result.incomplete();
                }
                byte[] frame =
                        Arrays.copyOfRange(
                                combined,
                                0,
                                frameLength);
                byte[] remainder =
                        Arrays.copyOfRange(
                                combined,
                                frameLength,
                                combined.length);
                complete = true;
                try {
                    return new Result(
                            frame,
                            remainder);
                } finally {
                    wipe(
                            frame);
                    wipe(
                            remainder);
                }
            } catch (RuntimeException failure) {
                fail();
                throw failure;
            } finally {
                wipe(
                        combined);
            }
        }

        private static void requirePossibleBodyLength(
                int bodyLength) {
            boolean valid =
                    bodyLength
                            == NwServiceConnectorCodec
                            .FEEDBACK_BODY_LENGTH
                            || bodyLength
                            == NwServiceConnectorCodec
                            .OPERATION_BODY_LENGTH
                            || (bodyLength
                            >= NwServiceConnectorCodec
                            .NORMAL_FIXED_BODY_LENGTH
                            && bodyLength
                            <= NwServiceConnectorCodec
                            .NORMAL_FIXED_BODY_LENGTH
                            + NwServiceConnectorCodec
                            .MAX_SERVICE_LENGTH);
            if (!valid) {
                throw new IllegalArgumentException(
                        "NWSC first frame has an impossible body length");
            }
        }

        private void requireUsable() {
            if (closed) {
                throw new IllegalStateException(
                        "NWSC first-frame buffer is closed");
            }
            if (complete) {
                throw new IllegalStateException(
                        "NWSC first-frame buffer already completed");
            }
        }

        private void fail() {
            wipe(
                    buffer);
            buffer =
                    new byte[0];
            complete = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    buffer);
            buffer =
                    new byte[0];
        }

        static final class Result
                implements AutoCloseable {
            private final boolean complete;
            private byte[] frame;
            private byte[] remainder;
            private boolean closed;

            private Result(
                    byte[] frame,
                    byte[] remainder) {
                complete = true;
                this.frame =
                        frame.clone();
                this.remainder =
                        remainder.clone();
            }

            private Result() {
                complete = false;
                frame =
                        new byte[0];
                remainder =
                        new byte[0];
            }

            static Result incomplete() {
                return new Result();
            }

            boolean complete() {
                requireOpen();
                return complete;
            }

            byte[] frame() {
                requireOpen();
                if (!complete) {
                    throw new IllegalStateException(
                            "NWSC first frame is incomplete");
                }
                return frame.clone();
            }

            byte[] remainder() {
                requireOpen();
                if (!complete) {
                    throw new IllegalStateException(
                            "NWSC first frame is incomplete");
                }
                return remainder.clone();
            }

            private void requireOpen() {
                if (closed) {
                    throw new IllegalStateException(
                            "NWSC first-frame result is closed");
                }
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                wipe(
                        frame);
                wipe(
                        remainder);
                frame =
                        new byte[0];
                remainder =
                        new byte[0];
            }
        }
    }

    private static byte[] deriveEd25519PrivateKey(
            byte[] address,
            String purpose) {
        if (address == null
                || address.length == 0) {
            byte[] randomKey =
                    new byte[32];
            new SecureRandom().nextBytes(
                    randomKey);
            return randomKey;
        }
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            digest.update(
                    purpose.getBytes(StandardCharsets.UTF_8));
            digest.update(
                    address);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] deriveEd25519PublicKey(
            byte[] privateKey) {
        if (privateKey == null
                || privateKey.length != 32) {
            return null;
        }
        return new Ed25519PrivateKeyParameters(
                privateKey,
                0)
                .generatePublicKey()
                .getEncoded();
    }

    static boolean isRawIdsSocketPairFrame(byte[] bytes) {
        if (bytes == null || bytes.length < IdsSocketPairCodec.HEADER_LENGTH) {
            return false;
        }
        int command = bytes[0] & 0xff;
        boolean validCommand = command == IdsSocketPairCodec.COMMAND_DATA
                || command == IdsSocketPairCodec.COMMAND_ACK
                || command == IdsSocketPairCodec.COMMAND_KEEP_ALIVE
                || command == IdsSocketPairCodec.COMMAND_PROTOBUF
                || command == IdsSocketPairCodec.COMMAND_HANDSHAKE
                || command == IdsSocketPairCodec.COMMAND_APP_ACK
                || command == IdsSocketPairCodec.COMMAND_FRAGMENT
                || command == IdsSocketPairCodec.COMMAND_EXPIRED_ACK
                || command == IdsSocketPairCodec.COMMAND_SERVICE_MAP;
        if (!validCommand) {
            return false;
        }
        int nwscLen = ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
        if (nwscLen == NwServiceConnectorCodec.FEEDBACK_BODY_LENGTH
                || nwscLen == NwServiceConnectorCodec.OPERATION_BODY_LENGTH
                || (nwscLen >= NwServiceConnectorCodec.NORMAL_FIXED_BODY_LENGTH
                && nwscLen <= NwServiceConnectorCodec.NORMAL_FIXED_BODY_LENGTH
                        + NwServiceConnectorCodec.MAX_SERVICE_LENGTH)) {
            return false;
        }
        long idsBodyLen = ((bytes[1] & 0xffL) << 24)
                | ((bytes[2] & 0xffL) << 16)
                | ((bytes[3] & 0xffL) << 8)
                | (bytes[4] & 0xffL);
        return idsBodyLen >= 0 && idsBodyLen <= 1024 * 1024;
    }
}
