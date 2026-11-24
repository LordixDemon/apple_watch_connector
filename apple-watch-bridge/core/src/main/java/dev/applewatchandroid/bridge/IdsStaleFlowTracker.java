package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks observed Watch TCP flows across sessions, generates proactive/reactive
 * RFC 5961 resets, and filters segments for kernel-rejected or stale tuples.
 * Must be invoked under {@link IdsIpv6TcpRouter}'s monitor lock.
 */
final class IdsStaleFlowTracker {
    private static final int ADDRESS_LENGTH = 16;
    private static final long MIN_RESET_AGE_MS = 10_000L;

    /** Paired quartet from the first session, when this IKE assignment flipped. */
    private byte[] staleLocalClassD;
    private byte[] staleRemoteClassD;
    /** Tuples the Watch kernel already rejected with a no-connection RST. */
    private final Set<IdsIpv6TcpRouter.ConnectionKey> kernelRejectedTuples =
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

    void setStaleFlowAddresses(
            byte[] localClassD,
            byte[] remoteClassD) {
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

    void noteFlowObservation(
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

    void seedStaleFlows(
            List<IdsStaleFlowRecord> seeds) {
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

    List<IdsStaleFlowRecord> staleFlowObservations() {
        return new ArrayList<>(
                observedFlows.values());
    }

    List<IdsIpv6TcpRouter.ProactiveReset> buildProactiveStaleResets() {
        List<IdsIpv6TcpRouter.ProactiveReset> resets =
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
                java.util.function.Consumer<String> logger = IdsIpv6TcpRouter.diagnosticLogger;
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
                        new IdsIpv6TcpRouter.ProactiveReset(
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

    boolean shouldDropKernelRejectedAndClearIfFresh(
            IdsIpv6TcpRouter.ConnectionKey key,
            OrdinaryIkeAuth.DataClass dataClass,
            Ipv6TcpPacketCodec.Packet packet) {
        boolean peerAbort = packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)
                && packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK);
        boolean newSyn = packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)
                && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK);
        if (kernelRejectedTuples.contains(key) && !peerAbort && !newSyn) {
            java.util.function.Consumer<String> logger = IdsIpv6TcpRouter.diagnosticLogger;
            if (logger != null) {
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[TCP Router] stale replay dropped after kernel RST: dc=%s %d->%d",
                        dataClass,
                        packet.sourcePort,
                        packet.destinationPort));
            }
            return true;
        }
        if (peerAbort || newSyn) {
            kernelRejectedTuples.remove(key);
            kernelRejectedTuples.remove(new IdsIpv6TcpRouter.ConnectionKey(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    key.localPort,
                    key.remotePort));
            kernelRejectedTuples.remove(new IdsIpv6TcpRouter.ConnectionKey(
                    OrdinaryIkeAuth.DataClass.CLASS_C,
                    key.localPort,
                    key.remotePort));
        }
        return false;
    }

    void markKernelRejected(IdsIpv6TcpRouter.ConnectionKey key) {
        kernelRejectedTuples.add(key);
    }

    void markKernelRejectedBothClasses(int localPort, int remotePort) {
        kernelRejectedTuples.add(new IdsIpv6TcpRouter.ConnectionKey(
                OrdinaryIkeAuth.DataClass.CLASS_D, localPort, remotePort));
        kernelRejectedTuples.add(new IdsIpv6TcpRouter.ConnectionKey(
                OrdinaryIkeAuth.DataClass.CLASS_C, localPort, remotePort));
    }

    IdsIpv6TcpRouter.InboundResult handleUnownedDestination(
            OrdinaryIkeAuth.DataClass dataClass,
            Ipv6TcpPacketCodec.Packet packet) {
        java.util.function.Consumer<String> logger = IdsIpv6TcpRouter.diagnosticLogger;
        if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)) {
            return new IdsIpv6TcpRouter.InboundResult(dataClass, List.of(), List.of());
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
                return new IdsIpv6TcpRouter.InboundResult(dataClass, List.of(ack), List.of());
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
        try {
            return new IdsIpv6TcpRouter.InboundResult(dataClass, List.of(reset), List.of());
        } finally {
            wipe(reset);
        }
    }

    IdsIpv6TcpRouter.InboundResult handleNonSynOwnedPort(
            OrdinaryIkeAuth.DataClass dataClass,
            IdsIpv6TcpRouter.ConnectionKey key,
            byte[] clearIpv6Packet,
            Ipv6TcpPacketCodec.Packet packet) {
        if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)) {
            return new IdsIpv6TcpRouter.InboundResult(dataClass, List.of(), List.of());
        }
        if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK)
                && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)) {
            // Live 20:42: the Watch retransmits the previous session's
            // established segment (49156->61314, same seq/ack as 20:14)
            // and ignores a bare RST, so the new control SYN is
            // REJECTED_BY_POLICY. Live 0.2.304: reset every leftover flow on
            // the stored quartet so RFC 5961 aborts it at once.
            return resetStaleFlow(
                    dataClass,
                    key,
                    clearIpv6Packet,
                    IdsIpv6TcpRouter.diagnosticLogger);
        }
        byte[] reset = encodeReset(packet);
        try {
            return new IdsIpv6TcpRouter.InboundResult(dataClass, List.of(reset), List.of());
        } finally {
            wipe(reset);
        }
    }

    void close() {
        wipe(staleLocalClassD);
        wipe(staleRemoteClassD);
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

    private IdsIpv6TcpRouter.InboundResult resetStaleFlow(
            OrdinaryIkeAuth.DataClass dataClass,
            IdsIpv6TcpRouter.ConnectionKey key,
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
            return new IdsIpv6TcpRouter.InboundResult(dataClass, List.of(), List.of());
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
            return new IdsIpv6TcpRouter.InboundResult(
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

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
