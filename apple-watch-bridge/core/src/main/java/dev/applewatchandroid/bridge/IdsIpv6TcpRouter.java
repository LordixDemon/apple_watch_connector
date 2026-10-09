package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Narrow multi-connection IPv6/TCP demultiplexer for the proven IDS
 * NetworkRelay routes.
 *
 * <p>The caller supplies the authenticated NetworkRelay data class for every
 * inbound clear IPv6 packet and sends returned packets through the same
 * class. This prevents a Class-C packet from being accepted by a Class-D
 * listener even when its TCP ports happen to match. Only the two physical
 * watchOS IDS listener ports, 61314 and 61315, may be opened.</p>
 *
 * <p>This class owns every {@link Ipv6TcpStream}. Connection identifiers are
 * local opaque handles and are never wire values.</p>
 */
final class IdsIpv6TcpRouter
        implements AutoCloseable {
    private static final int ADDRESS_LENGTH = 16;
    private static final int MAX_CONNECTIONS = 1024;

    public static volatile java.util.function.Consumer<String> diagnosticLogger = null;

    private final SecureRandom random;
    private final byte[] localClassD;
    private final byte[] remoteClassD;
    private final byte[] localClassC;
    private final byte[] remoteClassC;
    /** Paired quartet from the first session, when this IKE assignment flipped. */
    private byte[] staleLocalClassD;
    private byte[] staleRemoteClassD;
    private final Set<ListenerKey> listeners =
            new LinkedHashSet<>();
    private final Map<ConnectionKey, Endpoint> byTuple =
            new LinkedHashMap<>();
    private final Map<Long, Endpoint> byId =
            new LinkedHashMap<>();
    /** Tuples the Watch kernel already rejected with a no-connection RST. */
    private final Set<ConnectionKey> kernelRejectedTuples =
            new LinkedHashSet<>();
    /**
     * Every Watch flow observed on the wire, newest segment wins. The host
     * persists these so a restarted process can proactively reset the Watch
     * TCBs that outlived the previous session; without the reset the Watch
     * identityservicesd keeps its control connection ESTABLISHED and never
     * opens the new connector (watchOS 26.2 zombie gate, live 0.2.304).
     */
    private final Map<String, IdsStaleFlowRecord> observedFlows =
            new LinkedHashMap<>();

    private long nextConnectionId = 1;
    private int nextRetransmitEndpoint;
    private boolean closed;
    private boolean payloadFastRetransmitEnabled = true;

    IdsIpv6TcpRouter(
            SecureRandom random,
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "IDS TCP random source is absent");
        }
        requireAddress(
                "local Class D",
                localClassD);
        requireAddress(
                "remote Class D",
                remoteClassD);
        requireAddress(
                "local Class C",
                localClassC);
        requireAddress(
                "remote Class C",
                remoteClassC);
        this.random = random;
        this.localClassD =
                localClassD.clone();
        this.remoteClassD =
                remoteClassD.clone();
        this.localClassC =
                localClassC.clone();
        this.remoteClassC =
                remoteClassC.clone();
    }

    synchronized void listen(
            OrdinaryIkeAuth.DataClass dataClass,
            int localPort) {
        requireUsable();
        requireDataClass(
                dataClass);
        requireIdsListenerPort(
                localPort);
        listeners.add(
                new ListenerKey(
                        dataClass,
                        localPort));
    }

    synchronized void stopListening(
            OrdinaryIkeAuth.DataClass dataClass,
            int localPort) {
        requireUsable();
        requireDataClass(
                dataClass);
        requireIdsListenerPort(
                localPort);
        listeners.remove(
                new ListenerKey(
                        dataClass,
                        localPort));
    }

    synchronized boolean isListening(
            OrdinaryIkeAuth.DataClass dataClass,
            int localPort) {
        requireUsable();
        return listeners.contains(
                new ListenerKey(
                        requireDataClass(
                                dataClass),
                        requireIdsListenerPort(
                                localPort)));
    }

    /**
     * Classifies an authenticated clear packet without changing TCP state.
     *
     * <p>An IDS packet either belongs to an existing tuple or has one end on
     * one of the two proven Network.framework listener ports. Keeping this
     * check here prevents unrelated normal-link IPv6 traffic (PBBridge,
     * paired sync, and other Alloy services) from being offered to the IDS
     * TCP state machine.</p>
     */
    synchronized boolean recognizesInbound(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] clearIpv6Packet) {
        requireUsable();
        requireDataClass(
                dataClass);
        Ipv6TcpPacketCodec.Packet packet;
        try {
            packet =
                    Ipv6TcpPacketCodec.decode(
                            clearIpv6Packet);
        } catch (IllegalArgumentException notBaselineTcp) {
            return false;
        }
        try {
            try {
                requireAddresses(
                        dataClass,
                        packet);
            } catch (IllegalArgumentException otherRoute) {
                return false;
            }
            ConnectionKey key =
                    new ConnectionKey(
                            dataClass,
                            packet.destinationPort,
                            packet.sourcePort);
            boolean portMatch = false;
            for (ConnectionKey existing : byTuple.keySet()) {
                if (existing.localPort == packet.destinationPort
                        && existing.remotePort == packet.sourcePort) {
                    portMatch = true;
                    break;
                }
            }
            return byTuple.containsKey(
                    key)
                    || portMatch
                    || ownsLocalPort(
                            dataClass,
                            packet.destinationPort)
                    || listeners.contains(
                            new ListenerKey(
                                    dataClass,
                                    packet.sourcePort));
        } finally {
            packet.close();
        }
    }

    /**
     * The first paired session's inner addresses. A later IKE exchange can
     * assign the opposite quartet; replies built from that quartet miss the
     * Watch flow and the kernel answers RST.
     */
    synchronized void setStaleFlowAddresses(
            byte[] localClassD,
            byte[] remoteClassD) {
        requireUsable();
        if (localClassD == null || remoteClassD == null
                || localClassD.length != ADDRESS_LENGTH
                || remoteClassD.length != ADDRESS_LENGTH) {
            throw new IllegalArgumentException("Stale flow addresses are absent");
        }
        wipe(staleLocalClassD);
        wipe(staleRemoteClassD);
        staleLocalClassD = localClassD.clone();
        staleRemoteClassD = remoteClassD.clone();
    }

    private void noteFlowObservation(
            OrdinaryIkeAuth.DataClass dataClass,
            Ipv6TcpPacketCodec.Packet packet) {
        try {
            IdsStaleFlowRecord record =
                    IdsStaleFlowRecord.fromInbound(
                            dataClass,
                            packet,
                            System.currentTimeMillis());
            observedFlows.put(
                    record.key(),
                    record);
        } catch (RuntimeException malformed) {
            // Observation is best-effort; routing must never fail on it.
        }
    }

    /**
     * Seeds flows observed by a previous process so the host can reset
     * them right after the link comes up, instead of waiting for the
     * Watch to retransmit (it has nothing to retransmit when the previous
     * session acknowledged everything — live 0.2.305 15:10 silence).
     */
    synchronized void seedStaleFlows(
            List<IdsStaleFlowRecord> seeds) {
        requireUsable();
        if (seeds == null) {
            return;
        }
        for (IdsStaleFlowRecord seed : seeds) {
            if (seed != null) {
                observedFlows.put(
                        seed.key(),
                        seed);
            }
        }
    }

    synchronized List<IdsStaleFlowRecord> staleFlowObservations() {
        requireUsable();
        return new ArrayList<>(
                observedFlows.values());
    }

    /**
     * Builds a proactive RFC 5961 reset for every remembered Watch flow.
     * The reset sequence is the peer's last acknowledgement — exactly the
     * Watch's RCV.NXT, so validation passes at once; when the flow moved
     * on by a few bytes the value still lands inside the window and the
     * Watch answers with a challenge ACK, which the reactive stale path
     * then resets precisely. The timestamp echo is extrapolated at the
     * peer's ~1000 Hz tick (future echoes are not PAWS-checked on RST).
     * Records younger than {@value #MIN_RESET_AGE_MS} ms are skipped so a
     * mid-session call can never kill a live flow.
     */
    synchronized List<ProactiveReset> buildProactiveStaleResets() {
        requireUsable();
        List<ProactiveReset> resets =
                new ArrayList<>();
        long now = System.currentTimeMillis();
        for (IdsStaleFlowRecord record : observedFlows.values()) {
            if (now - record.lastSeenEpochMs < MIN_RESET_AGE_MS) {
                continue;
            }
            byte[] options = null;
            byte[] reset = null;
            try {
                options =
                        record.peerTimestamp >= 0
                                ? Ipv6TcpPacketCodec.encodeTimestampOption(
                                        (record.peerTimestamp
                                                + (now - record.lastSeenEpochMs))
                                                & 0xffff_ffffL)
                                : new byte[0];
                reset =
                        Ipv6TcpPacketCodec.encode(
                                record.localAddress,
                                record.remoteAddress,
                                record.localPort,
                                record.remotePort,
                                record.peerAck,
                                0,
                                Ipv6TcpPacketCodec.FLAG_RST,
                                0,
                                0,
                                options,
                                new byte[0]);
                options = null;
                java.util.function.Consumer<String> logger = diagnosticLogger;
                if (logger != null) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[TCP Router] proactive stale RST: dc=%s %d->%d seq=%d ageMs=%d",
                            record.dataClass,
                            record.remotePort,
                            record.localPort,
                            record.peerAck,
                            now - record.lastSeenEpochMs));
                }
                resets.add(
                        new ProactiveReset(
                                record.dataClass,
                                reset));
                reset = null;
            } finally {
                if (options != null) {
                    wipe(options);
                }
                if (reset != null) {
                    wipe(reset);
                }
            }
        }
        return resets;
    }

    private static final long MIN_RESET_AGE_MS = 10_000L;

    /** Ownership of the packet passes to the caller. */
    static final class ProactiveReset {
        final OrdinaryIkeAuth.DataClass dataClass;
        final byte[] packet;

        ProactiveReset(
                OrdinaryIkeAuth.DataClass dataClass,
                byte[] packet) {
            this.dataClass = dataClass;
            this.packet = packet;
        }
    }

    synchronized ActiveOpen openActive(
            OrdinaryIkeAuth.DataClass dataClass,
            int localPort,
            int remotePort,
            byte[] fastOpenPayload) {
        requireUsable();
        requireDataClass(
                dataClass);
        requirePort(
                "local",
                localPort);
        requireIdsListenerPort(
                remotePort);
        requireConnectionRoom();

        ConnectionKey key =
                new ConnectionKey(
                        dataClass,
                        localPort,
                        remotePort);
        if (byTuple.containsKey(
                key)) {
            throw new IllegalStateException(
                    "IDS TCP tuple is already active");
        }

        Ipv6TcpStream stream =
                Ipv6TcpStream.active(
                        localAddress(
                                dataClass),
                        remoteAddress(
                                dataClass),
                        localPort,
                        remotePort,
                        randomSequence());
        byte[] syn = null;
        Endpoint endpoint = null;
        try {
            syn =
                    stream.startActiveOpen(
                            fastOpenPayload);
            endpoint =
                    new Endpoint(
                            takeConnectionId(),
                            key,
                            stream);
            register(
                    endpoint);
            java.util.function.Consumer<String> logger = diagnosticLogger;
            if (logger != null) {
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[TCP Router] openActive: dc=%s %d->%d ep=id=%d seq=%d payloadLen=%d",
                        dataClass,
                        localPort,
                        remotePort,
                        endpoint.id,
                        stream.initialSendSequence(),
                        fastOpenPayload != null ? fastOpenPayload.length : 0));
            }
            stream = null;
            ActiveOpen output =
                    new ActiveOpen(
                            endpoint.id,
                            dataClass,
                            syn);
            wipe(
                    syn);
            syn = null;
            return output;
        } finally {
            if (stream != null) {
                stream.close();
            }
            wipe(
                    syn);
        }
    }

    synchronized InboundResult accept(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] clearIpv6Packet) {
        requireUsable();
        requireDataClass(
                dataClass);
        Ipv6TcpPacketCodec.Packet packet =
                Ipv6TcpPacketCodec.decode(
                        clearIpv6Packet);
        try {
            requireAddresses(
                    dataClass,
                    packet);
            if (packet.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_ACK)) {
                noteFlowObservation(
                        dataClass,
                        packet);
            }
            ConnectionKey key =
                    new ConnectionKey(
                            dataClass,
                            packet.destinationPort,
                            packet.sourcePort);
            Endpoint endpoint =
                    byTuple.get(
                            key);
            if (endpoint == null) {
                for (Endpoint candidate : byTuple.values()) {
                    if (candidate.key.localPort == packet.destinationPort) {
                        if (candidate.key.remotePort == packet.sourcePort) {
                            endpoint = candidate;
                            dataClass = candidate.key.dataClass;
                            break;
                        } else if (candidate.stream.state() == Ipv6TcpStream.State.SYN_SENT
                                && packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)
                                && (candidate.key.remotePort == IdsIpsecServiceRoute.CLOUD_LISTENER_PORT
                                || candidate.key.remotePort == IdsIpsecServiceRoute.NORMAL_LISTENER_PORT)) {
                            byTuple.remove(candidate.key);
                            candidate.stream.setRemotePort(packet.sourcePort);
                            candidate.key = new ConnectionKey(candidate.key.dataClass, candidate.key.localPort, packet.sourcePort);
                            byTuple.put(candidate.key, candidate);
                            endpoint = candidate;
                            dataClass = candidate.key.dataClass;
                            break;
                        }
                    }
                }
            }
            java.util.function.Consumer<String> logger = diagnosticLogger;
            if (logger != null) {
                StringBuilder optHex = new StringBuilder();
                if (packet.options != null && packet.options.length > 0) {
                    for (byte b : packet.options) {
                        optHex.append(String.format("%02X ", b & 0xFF));
                    }
                }
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[TCP Router] accept: dc=%s %d->%d ep=%s seq=%d ackNum=%d syn=%b ack=%b fin=%b rst=%b payloadLen=%d win=%d optLen=%d optHex=[%s] srcIp=%s dstIp=%s localC=%s remoteC=%s localD=%s remoteD=%s",
                        dataClass,
                        packet.sourcePort,
                        packet.destinationPort,
                        endpoint != null ? ("id=" + endpoint.id) : "null",
                        packet.sequence,
                        packet.acknowledgement,
                        packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN),
                        packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK),
                        packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN),
                        packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST),
                        packet.payload.length,
                        packet.window,
                        packet.options != null ? packet.options.length : 0,
                        optHex.toString().trim(),
                        formatIpv6(packet.sourceAddress),
                        formatIpv6(packet.destinationAddress),
                        formatIpv6(localClassC),
                        formatIpv6(remoteClassC),
                        formatIpv6(localClassD),
                        formatIpv6(remoteClassD)));
            }
            if (endpoint == null) {
                boolean peerAbort = packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)
                        && packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK);
                boolean newSyn = packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)
                        && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK);
                if (kernelRejectedTuples.contains(key) && !peerAbort && !newSyn) {
                    if (logger != null) {
                        logger.accept(String.format(
                                java.util.Locale.US,
                                "[TCP Router] stale replay dropped after kernel RST: dc=%s %d->%d",
                                dataClass,
                                packet.sourcePort,
                                packet.destinationPort));
                    }
                    return new InboundResult(dataClass, List.of(), List.of());
                }
                if (peerAbort || newSyn) {
                    kernelRejectedTuples.remove(key);
                    kernelRejectedTuples.remove(new ConnectionKey(
                            OrdinaryIkeAuth.DataClass.CLASS_D,
                            key.localPort,
                            key.remotePort));
                    kernelRejectedTuples.remove(new ConnectionKey(
                            OrdinaryIkeAuth.DataClass.CLASS_C,
                            key.localPort,
                            key.remotePort));
                }
                return acceptNewPassive(
                        dataClass,
                        key,
                        clearIpv6Packet,
                        packet);
            }
            if (endpoint.isAdopted
                    && packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)
                    && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK)) {
                int localPort = packet.destinationPort;
                int remotePort = packet.sourcePort;
                kernelRejectedTuples.add(new ConnectionKey(
                        OrdinaryIkeAuth.DataClass.CLASS_D, localPort, remotePort));
                kernelRejectedTuples.add(new ConnectionKey(
                        OrdinaryIkeAuth.DataClass.CLASS_C, localPort, remotePort));
                byId.remove(endpoint.id);
                byTuple.remove(endpoint.key);
                endpoint.close();
                if (logger != null) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[TCP Router] adopted control has no Watch TCB; "
                                    + "further replies dropped: %d->%d",
                            remotePort,
                            localPort));
                }
                return new InboundResult(dataClass, List.of(), List.of());
            }
            return acceptExisting(
                    endpoint,
                    clearIpv6Packet);
        } finally {
            packet.close();
        }
    }

    synchronized PacketBatch send(
            long connectionId,
            byte[] bytes) {
        requireUsable();
        Endpoint endpoint =
                byId.get(connectionId);
        if (endpoint == null && !byId.isEmpty()) {
            for (Endpoint candidate : byId.values()) {
                if (candidate.stream.state() == Ipv6TcpStream.State.ESTABLISHED) {
                    endpoint = candidate;
                    break;
                }
            }
            if (endpoint == null) {
                endpoint = byId.values().iterator().next();
            }
        }
        if (endpoint == null) {
            throw new IllegalArgumentException(
                    "Unknown IDS TCP connection");
        }
        List<byte[]> packets =
                new ArrayList<>(
                        endpoint.stream.send(
                                bytes));
        try {
            return new PacketBatch(
                    endpoint.key.dataClass,
                    packets);
        } finally {
            wipeAll(
                    packets);
        }
    }

    synchronized boolean isEstablished(
            long connectionId) {
        if (closed) {
            return false;
        }
        Endpoint endpoint = byId.get(connectionId);
        return endpoint != null && endpoint.stream.state() == Ipv6TcpStream.State.ESTABLISHED;
    }

    synchronized boolean isAdopted(long connectionId) {
        if (closed) {
            return false;
        }
        Endpoint endpoint = byId.get(connectionId);
        return endpoint != null && endpoint.isAdopted;
    }

    synchronized PacketBatch retransmitOutstanding(
            long connectionId) {
        requireUsable();
        Endpoint endpoint =
                requireEndpoint(
                        connectionId);
        List<byte[]> packets =
                new ArrayList<>(
                        endpoint.stream
                                .retransmitOutstanding());
        try {
            return new PacketBatch(
                    endpoint.key.dataClass,
                    packets);
        } finally {
            wipeAll(
                    packets);
        }
    }

    synchronized List<PacketBatch> retransmitAllOutstanding() {
        if (closed) {
            return List.of();
        }
        List<PacketBatch> batches = new ArrayList<>();
        // All connections share one ERTM window. A per-stream limit still
        // emitted dozens of packets; recover the oldest hole on at most four
        // streams, rotating so a persistently stalled connection cannot starve
        // the other lanes.
        List<Endpoint> endpoints = new ArrayList<>(byId.values());
        int count = endpoints.size();
        if (count == 0) return batches;
        int start = nextRetransmitEndpoint % count;
        int scanned = 0;
        while (scanned < count && batches.size() < Ipv6TcpStream.MAX_RETRANSMIT_PER_PASS) {
            Endpoint endpoint = endpoints.get((start + scanned++) % count);
            List<byte[]> packets = endpoint.stream.retransmitOutstanding();
            try {
                if (!packets.isEmpty()) {
                    batches.add(new PacketBatch(endpoint.key.dataClass,
                            List.of(packets.get(0))));
                }
            } finally {
                for (byte[] packet : packets) wipe(packet);
            }
        }
        nextRetransmitEndpoint = (start + scanned) % count;
        return batches;
    }

    synchronized long unacknowledgedSendBytes() {
        if (closed) {
            return 0L;
        }
        long total = 0L;
        for (Endpoint endpoint : byId.values()) {
            total +=
                    endpoint.stream.unacknowledgedSendBytes();
        }
        return total;
    }

    synchronized String unacknowledgedSendSummary() {
        if (closed) {
            return "closed";
        }
        StringBuilder summary =
                new StringBuilder();
        for (Endpoint endpoint : byId.values()) {
            long bytes =
                    endpoint.stream.unacknowledgedSendBytes();
            if (bytes <= 0L) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append(',');
            }
            summary.append(
                    endpoint.key.dataClass);
            summary.append(':');
            summary.append(
                    endpoint.key.localPort);
            summary.append("->");
            summary.append(
                    endpoint.key.remotePort);
            summary.append('=');
            summary.append(
                    bytes);
        }
        return summary.length() == 0
                ? "0"
                : summary.toString();
    }

    synchronized void setPayloadFastRetransmitEnabled(
            boolean enabled) {
        payloadFastRetransmitEnabled = enabled;
        if (closed) {
            return;
        }
        for (Endpoint endpoint : byId.values()) {
            endpoint.stream.setPayloadFastRetransmitEnabled(
                    enabled);
        }
    }

    synchronized PacketBatch closeOutput(
            long connectionId) {
        requireUsable();
        Endpoint endpoint =
                requireEndpoint(
                        connectionId);
        byte[] fin =
                endpoint.stream.closeOutput();
        try {
            return new PacketBatch(
                    endpoint.key.dataClass,
                    List.of(
                            fin));
        } finally {
            wipe(
                    fin);
        }
    }

    synchronized void removeConnection(
            long connectionId) {
        requireUsable();
        Endpoint endpoint =
                byId.remove(
                        connectionId);
        if (endpoint == null) {
            return;
        }
        byTuple.remove(
                endpoint.key);
        endpoint.close();
    }

    synchronized ConnectionSnapshot connection(
            long connectionId) {
        requireUsable();
        Endpoint endpoint =
                requireEndpoint(
                        connectionId);
        return new ConnectionSnapshot(
                endpoint.id,
                endpoint.key.dataClass,
                endpoint.key.localPort,
                endpoint.key.remotePort,
                endpoint.stream.state());
    }

    synchronized int connectionCount() {
        requireUsable();
        return byId.size();
    }

    synchronized int listenerCount() {
        requireUsable();
        return listeners.size();
    }

    /** Standard RFC 793 reset for a segment that matches no live tuple. */
    private static byte[] encodeReset(
            Ipv6TcpPacketCodec.Packet packet) {
        boolean hasAck = packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK);
        long acknowledgement = hasAck ? 0 : (packet.sequence + packet.payload.length
                + (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN) ? 1 : 0)
                + (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN) ? 1 : 0)) & 0xffff_ffffL;
        return Ipv6TcpPacketCodec.encode(packet.destinationAddress, packet.sourceAddress,
                packet.destinationPort, packet.sourcePort, hasAck ? packet.acknowledgement : 0,
                acknowledgement, Ipv6TcpPacketCodec.FLAG_RST | (hasAck ? 0 : Ipv6TcpPacketCodec.FLAG_ACK),
                0, 0, new byte[0], new byte[0]);
    }

    /** ACK a FIN after the local TCB is already gone, without RST. */
    private static byte[] encodeClosingAck(
            Ipv6TcpPacketCodec.Packet packet) {
        long acknowledgement =
                (packet.sequence
                        + packet.payload.length
                        + 1L) & 0xffff_ffffL;
        return Ipv6TcpPacketCodec.encode(
                packet.destinationAddress,
                packet.sourceAddress,
                packet.destinationPort,
                packet.sourcePort,
                packet.acknowledgement,
                acknowledgement,
                Ipv6TcpPacketCodec.FLAG_ACK,
                0,
                0,
                new byte[0],
                new byte[0]);
    }

    private InboundResult acceptNewPassive(
            OrdinaryIkeAuth.DataClass dataClass,
            ConnectionKey key,
            byte[] clearIpv6Packet,
            Ipv6TcpPacketCodec.Packet packet) {
        // Own a dest only if it is a registered listener (61314/61315 or
        // type-6) or the local port of a live tuple. The old 1026..3126
        // wildcard treated Watch FIN/Feedback to a previous HAL's
        // ephemeral ports as new NWSC connections and retry-stormed
        // control (stand 15:19 reconnect: 61315→1041 payload 88).
        boolean ownedDestination =
                ownsLocalPort(
                        dataClass,
                        packet.destinationPort);
        if (!ownedDestination) {
            java.util.function.Consumer<String> logger = diagnosticLogger;
            if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)) {
                return new InboundResult(dataClass, List.of(), List.of());
            }
            if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN)
                    && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)
                    && packet.payload.length == 0) {
                if (logger != null) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[TCP Router] stale FIN ACK: dc=%s %d->%d",
                            dataClass,
                            packet.sourcePort,
                            packet.destinationPort));
                }
                byte[] ack = encodeClosingAck(packet);
                try {
                    return new InboundResult(dataClass, List.of(ack), List.of());
                } finally {
                    wipe(ack);
                }
            }
            if (logger != null) {
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[TCP Router] stale tuple -> RST: dc=%s %d->%d syn=%b ack=%b fin=%b rst=%b payloadLen=%d",
                        dataClass,
                        packet.sourcePort,
                        packet.destinationPort,
                        packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN),
                        packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK),
                        packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN),
                        packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST),
                        packet.payload.length));
            }
            byte[] reset = encodeReset(packet);
            try { return new InboundResult(dataClass, List.of(reset), List.of()); }
            finally { wipe(reset); }
        }
        if (!packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)
                || packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK)
                || packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN)
                || packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)) {
            if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)) {
                return new InboundResult(dataClass, List.of(), List.of());
            }
            if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK)
                    && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)) {
                // Live 20:42: the Watch retransmits the previous session's
                // established segment (49156→61314, same seq/ack as 20:14)
                // and ignores a bare RST, so the new control SYN is
                // REJECTED_BY_POLICY. Acknowledge the bytes and FIN. Do not
                // deliver the payload: it is the middle of an old IDS stream.
                return acknowledgeAndFinishStale(
                        dataClass,
                        key,
                        clearIpv6Packet);
            }
            byte[] reset = encodeReset(packet);
            try { return new InboundResult(dataClass, List.of(reset), List.of()); }
            finally { wipe(reset); }
        }
        requireConnectionRoom();

        Ipv6TcpStream.PassiveOpen passive = null;
        Ipv6TcpStream stream = null;
        byte[] synAck = null;
        Endpoint endpoint = null;
        try {
            passive =
                    Ipv6TcpStream.acceptPassive(
                            clearIpv6Packet,
                            replySource(clearIpv6Packet),
                            replyDestination(clearIpv6Packet),
                            randomSequence());
            stream =
                    passive.takeStream();
            synAck =
                    passive.synAcknowledgement();
            endpoint =
                    new Endpoint(
                            takeConnectionId(),
                            key,
                            stream);
            register(
                    endpoint);
            stream = null;
            OrdinaryIkeAuth.DataClass replyClass =
                    classDAddresses(clearIpv6Packet)
                            ? OrdinaryIkeAuth.DataClass.CLASS_D
                            : dataClass;
            return new InboundResult(
                    replyClass,
                    List.of(
                            synAck),
                    List.of());
        } finally {
            if (stream != null) {
                stream.close();
            }
            if (passive != null) {
                passive.close();
            }
            wipe(
                    synAck);
        }
    }

    /**
     * Stale established segments belong to the quartet saved with the pair,
     * so any reply must carry those addresses — a reply on the packet header
     * reaches a Watch kernel with no TCB (live 00:07). Answering with a
     * forged ACK only teaches the Watch its dead flows are alive, and it
     * then never opens the new control connector (live 0.2.304 14:35: flows
     * went quiet, connector never came). A reset with seq = the segment's
     * ack number is exactly the Watch's RCV.NXT, so RFC 5961 validation
     * passes and the Watch aborts the stale flow at once — its session
     * layer then opens the new connector without waiting out the ~8.6-min
     * RTO. The reset carries the timestamp echo when the peer negotiated
     * TS, and goes out on the segment's own data class so the Watch's
     * SA-bound TCB accepts it.
     */
    private InboundResult resetStaleFlow(
            OrdinaryIkeAuth.DataClass dataClass,
            ConnectionKey key,
            byte[] clearIpv6Packet,
            java.util.function.Consumer<String> logger) {
        if (staleLocalClassD == null || staleRemoteClassD == null) {
            if (logger != null) {
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[TCP Router] stale flow withheld (no quartet): dc=%s %d->%d bytes=%d",
                        dataClass,
                        key.remotePort,
                        key.localPort,
                        packetPayloadLength(clearIpv6Packet)));
            }
            return new InboundResult(dataClass, List.of(), List.of());
        }
        Ipv6TcpPacketCodec.Packet packet =
                Ipv6TcpPacketCodec.decode(clearIpv6Packet);
        byte[] options = null;
        byte[] reset = null;
        try {
            Ipv6TcpPacketCodec.PeerOptions peer =
                    Ipv6TcpPacketCodec.parsePeerOptions(packet.options);
            options =
                    peer.timestampValue >= 0
                            ? Ipv6TcpPacketCodec.encodeTimestampOption(
                                    peer.timestampValue)
                            : new byte[0];
            reset =
                    Ipv6TcpPacketCodec.encode(
                            staleLocalClassD,
                            staleRemoteClassD,
                            packet.destinationPort,
                            packet.sourcePort,
                            packet.acknowledgement,
                            0,
                            Ipv6TcpPacketCodec.FLAG_RST,
                            0,
                            0,
                            options,
                            new byte[0]);
            options = null;
            if (logger != null) {
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[TCP Router] stale RST on stored quartet: %s->%s port=%d->%d seq=%d",
                        hostSuffix(staleLocalClassD),
                        hostSuffix(staleRemoteClassD),
                        key.remotePort,
                        key.localPort,
                        packet.acknowledgement & 0xffff_ffffL));
            }
            byte[] outbound = reset;
            reset = null;
            return new InboundResult(
                    dataClass,
                    List.of(outbound),
                    List.of());
        } finally {
            packet.close();
            if (options != null) {
                wipe(options);
            }
            if (reset != null) {
                wipe(reset);
            }
        }
    }

    /**
     * Class-D sockets are reached through the Class-D SA in the
     * phone-to-watch direction. A reply built from the parsed header
     * after the source-first decode goes the other way and the SA drops it.
     */
    private byte[] replySource(byte[] packet) {
        return classDAddresses(packet) ? localClassD : null;
    }

    private byte[] replyDestination(byte[] packet) {
        return classDAddresses(packet) ? remoteClassD : null;
    }

    private static boolean classDAddresses(byte[] packet) {
        return packet != null
                && packet.length >= 40
                && packet[17] == AppleNetworkRelayInnerAddresses.CLASS_D_MARKER
                && packet[33] == AppleNetworkRelayInnerAddresses.CLASS_D_MARKER;
    }

    private static String hostSuffix(byte[] address) {
        if (address == null || address.length < 12) {
            return "absent";
        }
        return String.format(
                java.util.Locale.US,
                "%02x%02x",
                address[10] & 0xff,
                address[11] & 0xff);
    }

    private static int packetPayloadLength(byte[] clearIpv6Packet) {
        try {
            Ipv6TcpPacketCodec.Packet packet =
                    Ipv6TcpPacketCodec.decode(clearIpv6Packet);
            try {
                return packet.payload.length;
            } finally {
                packet.close();
            }
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private InboundResult acknowledgeAndFinishStale(
            OrdinaryIkeAuth.DataClass dataClass,
            ConnectionKey key,
            byte[] clearIpv6Packet) {
        java.util.function.Consumer<String> logger = diagnosticLogger;
        // Live 0.2.304: kill every leftover flow with a valid reset on the
        // stored quartet. Forged ACKs only made the Watch keep its dead
        // flows alive and never open the new control connector (14:35); the
        // reset aborts them at once (RFC 5961 seq == Watch RCV.NXT).
        return resetStaleFlow(dataClass, key, clearIpv6Packet, logger);
    }

    private InboundResult acceptExisting(
            Endpoint endpoint,
            byte[] clearIpv6Packet) {
        Ipv6TcpStream.State before =
                endpoint.stream.state();
        List<byte[]> outbound =
                new ArrayList<>();
        List<byte[]> delivered =
                new ArrayList<>();
        List<StreamEvent> events =
                new ArrayList<>();
        try (Ipv6TcpStream.InboundResult result =
                     endpoint.stream.accept(
                             clearIpv6Packet)) {
            outbound.addAll(
                    result.outboundPackets());
            delivered.addAll(
                    result.deliveredBytes());
            Ipv6TcpStream.State after =
                    result.state;
            if (after == Ipv6TcpStream.State.CLOSE_WAIT) {
                byte[] fin = endpoint.stream.closeOutput();
                outbound.add(fin);
                after = endpoint.stream.state();
            }
            boolean becameEstablished =
                    before != Ipv6TcpStream.State.ESTABLISHED
                            && after
                            == Ipv6TcpStream.State.ESTABLISHED
                            && !endpoint.establishedNotified;
            if (becameEstablished) {
                endpoint.establishedNotified = true;
            }
            boolean streamClosed = (after == Ipv6TcpStream.State.RESET
                    || after == Ipv6TcpStream.State.CLOSED
                    || after == Ipv6TcpStream.State.LAST_ACK
                    || after == Ipv6TcpStream.State.TIME_WAIT);
            if (!endpoint.drainOnly
                    && (becameEstablished
                    || !delivered.isEmpty()
                    || streamClosed)) {
                events.add(
                        new StreamEvent(
                                endpoint.id,
                                becameEstablished,
                                streamClosed,
                                delivered));
            }

            InboundResult output =
                    new InboundResult(
                            endpoint.outboundClass != null
                                    ? endpoint.outboundClass
                                    : endpoint.key.dataClass,
                            outbound,
                            events);
            if (after == Ipv6TcpStream.State.RESET
                    || after == Ipv6TcpStream.State.CLOSED) {
                if (endpoint.drainOnly
                        && after == Ipv6TcpStream.State.RESET) {
                    kernelRejectedTuples.add(endpoint.key);
                }
                byId.remove(
                        endpoint.id);
                byTuple.remove(
                        endpoint.key);
                endpoint.close();
            }
            return output;
        } finally {
            wipeAll(
                    outbound);
            wipeAll(
                    delivered);
            closeEvents(
                    events);
        }
    }

    private void register(
            Endpoint endpoint) {
        endpoint.stream.setPayloadFastRetransmitEnabled(
                payloadFastRetransmitEnabled);
        Endpoint oldTuple =
                byTuple.put(
                        endpoint.key,
                        endpoint);
        Endpoint oldId =
                byId.put(
                        endpoint.id,
                        endpoint);
        if (oldTuple != null
                || oldId != null) {
            byTuple.remove(
                    endpoint.key);
            byId.remove(
                    endpoint.id);
            throw new IllegalStateException(
                    "IDS TCP connection registry collision");
        }
    }

    private Endpoint requireEndpoint(
            long connectionId) {
        Endpoint endpoint =
                byId.get(
                        connectionId);
        if (endpoint == null) {
            throw new IllegalArgumentException(
                    "Unknown IDS TCP connection");
        }
        return endpoint;
    }

    byte[] localAddress(
            OrdinaryIkeAuth.DataClass dataClass) {
        return dataClass
                == OrdinaryIkeAuth.DataClass.CLASS_D
                ? localClassD
                : localClassC;
    }

    byte[] remoteAddress(
            OrdinaryIkeAuth.DataClass dataClass) {
        return dataClass
                == OrdinaryIkeAuth.DataClass.CLASS_D
                ? remoteClassD
                : remoteClassC;
    }

    private void requireAddresses(
            OrdinaryIkeAuth.DataClass dataClass,
            Ipv6TcpPacketCodec.Packet packet) {
        if (packet.sourceAddress == null
                || packet.sourceAddress.length != 16
                || packet.destinationAddress == null
                || packet.destinationAddress.length != 16) {
            throw new IllegalArgumentException(
                    "Inbound IDS TCP packet addresses are malformed");
        }
    }

    private long randomSequence() {
        return random.nextInt()
                & 0xffff_ffffL;
    }

    private long takeConnectionId() {
        for (int attempts = 0;
                attempts <= MAX_CONNECTIONS;
                attempts++) {
            long candidate =
                    nextConnectionId;
            nextConnectionId++;
            if (nextConnectionId <= 0) {
                nextConnectionId = 1;
            }
            if (candidate > 0
                    && !byId.containsKey(
                            candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "IDS TCP connection identifier space is exhausted");
    }

    private void requireConnectionRoom() {
        if (byId.size() >= MAX_CONNECTIONS) {
            throw new IllegalStateException(
                    "IDS TCP connection safety limit reached");
        }
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS TCP router is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Endpoint endpoint : byId.values()) {
            endpoint.close();
        }
        byId.clear();
        byTuple.clear();
        listeners.clear();
        wipe(
                localClassD);
        wipe(
                remoteClassD);
        wipe(
                localClassC);
        wipe(
                remoteClassC);
        wipe(staleLocalClassD);
        wipe(staleRemoteClassD);
    }

    private boolean ownsLocalPort(
            OrdinaryIkeAuth.DataClass dataClass,
            int port) {
        if (listeners.contains(
                new ListenerKey(
                        dataClass,
                        port))) {
            return true;
        }
        for (Endpoint endpoint : byTuple.values()) {
            if (endpoint.key.dataClass == dataClass
                    && endpoint.key.localPort == port) {
                return true;
            }
        }
        return false;
    }

    private static OrdinaryIkeAuth.DataClass requireDataClass(
            OrdinaryIkeAuth.DataClass dataClass) {
        if (dataClass
                != OrdinaryIkeAuth.DataClass.CLASS_D
                && dataClass
                != OrdinaryIkeAuth.DataClass.CLASS_C) {
            throw new IllegalArgumentException(
                    "IDS TCP requires Class D or Class C");
        }
        return dataClass;
    }

    private static int requireIdsListenerPort(
            int port) {
        if (!IdsIpsecServiceRoute.listenerPorts()
                .contains(
                        port)
                && (port < IdsPortMap.FIRST_DYNAMIC_PORT
                        || port > IdsPortMap.FIRST_DYNAMIC_PORT + 64)) {
            throw new IllegalArgumentException(
                    "Port is not an IDS service-connector listener");
        }
        return port;
    }

    private static void requirePort(
            String label,
            int port) {
        if (port < 1
                || port > 0xffff) {
            throw new IllegalArgumentException(
                    "IDS TCP "
                            + label
                            + " port must be 1..65535");
        }
    }

    private static void requireAddress(
            String label,
            byte[] address) {
        if (address == null
                || address.length != ADDRESS_LENGTH) {
            throw new IllegalArgumentException(
                    label
                            + " IPv6 address must contain "
                            + ADDRESS_LENGTH
                            + " bytes");
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
        for (byte[] value : values) {
            wipe(
                    value);
        }
        values.clear();
    }

    private static List<byte[]> cloneAll(
            List<byte[]> values) {
        List<byte[]> output =
                new ArrayList<>(
                        values.size());
        for (byte[] value : values) {
            output.add(
                    value.clone());
        }
        return output;
    }

    private static void closeEvents(
            List<StreamEvent> events) {
        for (StreamEvent event : events) {
            event.close();
        }
        events.clear();
    }

    static final class ActiveOpen
            implements AutoCloseable {
        final long connectionId;
        final OrdinaryIkeAuth.DataClass dataClass;
        private byte[] packet;
        private boolean closed;

        private ActiveOpen(
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

        byte[] packet() {
            requireOpen();
            return packet.clone();
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS TCP active-open result is closed");
            }
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

    static final class InboundResult
            implements AutoCloseable {
        final OrdinaryIkeAuth.DataClass dataClass;
        private final List<byte[]> outboundPackets;
        private final List<StreamEvent> events;
        private boolean closed;

        private InboundResult(
                OrdinaryIkeAuth.DataClass dataClass,
                List<byte[]> outboundPackets,
                List<StreamEvent> events) {
            this.dataClass =
                    dataClass;
            this.outboundPackets =
                    cloneAll(
                            outboundPackets);
            List<StreamEvent> copied =
                    new ArrayList<>(
                            events.size());
            for (StreamEvent event : events) {
                copied.add(
                        event.copy());
            }
            this.events =
                    copied;
        }

        List<byte[]> outboundPackets() {
            requireOpen();
            return cloneAll(
                    outboundPackets);
        }

        List<StreamEvent> events() {
            requireOpen();
            return Collections.unmodifiableList(
                    events);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS TCP inbound result is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipeAll(
                    outboundPackets);
            closeEvents(
                    events);
        }
    }

    static final class PacketBatch
            implements AutoCloseable {
        final OrdinaryIkeAuth.DataClass dataClass;
        private final List<byte[]> packets;
        private boolean closed;

        private PacketBatch(
                OrdinaryIkeAuth.DataClass dataClass,
                List<byte[]> packets) {
            this.dataClass =
                    dataClass;
            this.packets =
                    cloneAll(
                            packets);
        }

        List<byte[]> packets() {
            requireOpen();
            return cloneAll(
                    packets);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS TCP packet batch is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipeAll(
                    packets);
        }
    }

    static final class StreamEvent
            implements AutoCloseable {
        final long connectionId;
        final boolean becameEstablished;
        final boolean streamClosed;
        private final List<byte[]> deliveredBytes;
        private boolean closed;

        private StreamEvent(
                long connectionId,
                boolean becameEstablished,
                boolean streamClosed,
                List<byte[]> deliveredBytes) {
            this.connectionId =
                    connectionId;
            this.becameEstablished =
                    becameEstablished;
            this.streamClosed =
                    streamClosed;
            this.deliveredBytes =
                    cloneAll(
                            deliveredBytes);
        }

        List<byte[]> deliveredBytes() {
            requireOpen();
            return cloneAll(
                    deliveredBytes);
        }

        private StreamEvent copy() {
            requireOpen();
            return new StreamEvent(
                    connectionId,
                    becameEstablished,
                    streamClosed,
                    deliveredBytes);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "IDS TCP stream event is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipeAll(
                    deliveredBytes);
        }
    }

    static final class ConnectionSnapshot {
        final long connectionId;
        final OrdinaryIkeAuth.DataClass dataClass;
        final int localPort;
        final int remotePort;
        final Ipv6TcpStream.State state;

        private ConnectionSnapshot(
                long connectionId,
                OrdinaryIkeAuth.DataClass dataClass,
                int localPort,
                int remotePort,
                Ipv6TcpStream.State state) {
            this.connectionId =
                    connectionId;
            this.dataClass =
                    dataClass;
            this.localPort =
                    localPort;
            this.remotePort =
                    remotePort;
            this.state =
                    state;
        }
    }

    private static final class Endpoint
            implements AutoCloseable {
        final long id;
        ConnectionKey key;
        final Ipv6TcpStream stream;
        final boolean isAdopted;
        final boolean drainOnly;
        /** SA used for replies when it differs from the inbound class. */
        OrdinaryIkeAuth.DataClass outboundClass;
        boolean establishedNotified;
        boolean closed;

        private Endpoint(
                long id,
                ConnectionKey key,
                Ipv6TcpStream stream) {
            this(id, key, stream, false, false);
        }

        private Endpoint(
                long id,
                ConnectionKey key,
                Ipv6TcpStream stream,
                boolean isAdopted) {
            this(id, key, stream, isAdopted, false);
        }

        private Endpoint(
                long id,
                ConnectionKey key,
                Ipv6TcpStream stream,
                boolean isAdopted,
                boolean drainOnly) {
            this.id = id;
            this.key = key;
            this.stream = stream;
            this.isAdopted = isAdopted;
            this.drainOnly = drainOnly;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            stream.close();
        }
    }

    private static final class ListenerKey {
        final OrdinaryIkeAuth.DataClass dataClass;
        final int localPort;

        private ListenerKey(
                OrdinaryIkeAuth.DataClass dataClass,
                int localPort) {
            this.dataClass = dataClass;
            this.localPort = localPort;
        }

        @Override
        public boolean equals(
                Object other) {
            return other
                    instanceof ListenerKey key
                    && dataClass == key.dataClass
                    && localPort == key.localPort;
        }

        @Override
        public int hashCode() {
            return 31 * dataClass.hashCode()
                    + localPort;
        }
    }

    private static final class ConnectionKey {
        final OrdinaryIkeAuth.DataClass dataClass;
        final int localPort;
        final int remotePort;

        private ConnectionKey(
                OrdinaryIkeAuth.DataClass dataClass,
                int localPort,
                int remotePort) {
            this.dataClass = dataClass;
            this.localPort = localPort;
            this.remotePort = remotePort;
        }

        @Override
        public boolean equals(
                Object other) {
            return other
                    instanceof ConnectionKey key
                    && dataClass == key.dataClass
                    && localPort == key.localPort
                    && remotePort == key.remotePort;
        }

        @Override
        public int hashCode() {
            int result =
                    dataClass.hashCode();
            result =
                    31 * result
                            + localPort;
            return 31 * result
                    + remotePort;
        }
    }

    static String formatIpv6(byte[] addr) {
        if (addr == null || addr.length != 16) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) sb.append(':');
            int val = ((addr[i] & 0xff) << 8) | (addr[i + 1] & 0xff);
            sb.append(Integer.toHexString(val));
        }
        return sb.toString();
    }
}
