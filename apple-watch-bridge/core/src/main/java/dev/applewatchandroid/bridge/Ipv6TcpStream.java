package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One ordered IPv6/TCP byte stream for the direct IDS IPsec path.
 *
 * <p>ERTM is the Bluetooth reliability layer, but live watchOS 26.2 still
 * loses I-frames of the Check/snapshot burst and then keeps resending the
 * 9-byte Alloy handshake. Those retransmits never idle the L2CAP loop, so
 * the HAL {@code pollRetransmissions} path never runs. Empty ACKs of those
 * replays use {@code SND.UNA} while Check is unacked so Watch sees
 * {@code SEG.SEQ == RCV.NXT} (live 0.2.123 {@code seq=SND.NXT} was 117 bytes
 * ahead and Watch kept replaying the 9-byte handshake). Fast retransmit of
 * unacked payload runs on the second behind-window replay, not the first
 * (0.2.121 blasted Check 2 ms after TX) and not the third (0.2.123 only
 * saw two 49155 replays before RTO).</p>
 *
 * <p>The remaining surface stays narrow: active and passive open, simultaneous
 * open, TCP Fast Open payload on SYN, cumulative acknowledgements,
 * duplicate/future-segment acknowledgement, segmentation, exact
 * retransmission retention, FIN, and RST. The same state machine is used for
 * Class-D control and class-selected C/D local-delivery routes.</p>
 */
final class Ipv6TcpStream
        implements AutoCloseable {
    enum State {
        CLOSED,
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        FIN_WAIT_1,
        FIN_WAIT_2,
        CLOSE_WAIT,
        LAST_ACK,
        TIME_WAIT,
        RESET
    }

    static final int SAFE_COMPANION_LINK_MSS = 1200;
    static final int RECEIVE_WINDOW = 0xffff;

    public static volatile java.util.function.Consumer<String> diagnosticLogger = null;

    final byte[] localAddress;
    final byte[] remoteAddress;
    private final int localPort;
    private int remotePort;
    private final long initialSendSequence;
    private final Ipv6TcpSegmentBuffer segments;

    void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
        this.segments.setRemotePort(remotePort);
    }

    int remotePort() {
        return remotePort;
    }

    long initialSendSequence() {
        return initialSendSequence;
    }

    private State state = State.CLOSED;
    private long sendUnacknowledged;
    private long sendNext;
    private long receiveNext;
    private int peerWindow = RECEIVE_WINDOW;
    private int peerMss = SAFE_COMPANION_LINK_MSS;
    private byte[] pendingFastOpenPayload =
            new byte[0];
    private byte[] activeFastOpenPayload =
            new byte[0];
    private boolean started;
    private boolean closed;

    private Ipv6TcpStream(
            byte[] localAddress,
            byte[] remoteAddress,
            int localPort,
            int remotePort,
            long initialSendSequence) {
        Ipv6TcpSegmentBuffer.requireAddress(
                "local",
                localAddress);
        Ipv6TcpSegmentBuffer.requireAddress(
                "remote",
                remoteAddress);
        Ipv6TcpSegmentBuffer.requirePort(
                "local",
                localPort);
        Ipv6TcpSegmentBuffer.requirePort(
                "remote",
                remotePort);
        Ipv6TcpSegmentBuffer.requireU32(
                "initial TCP sequence",
                initialSendSequence);
        this.localAddress =
                localAddress.clone();
        this.remoteAddress =
                remoteAddress.clone();
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.initialSendSequence =
                initialSendSequence;
        sendUnacknowledged =
                initialSendSequence;
        sendNext =
                initialSendSequence;
        this.segments =
                new Ipv6TcpSegmentBuffer(
                        this.localAddress,
                        this.remoteAddress,
                        localPort,
                        remotePort);
    }

    static Ipv6TcpStream active(
            byte[] localAddress,
            byte[] remoteAddress,
            int localPort,
            int remotePort,
            long initialSendSequence) {
        return new Ipv6TcpStream(
                localAddress,
                remoteAddress,
                localPort,
                remotePort,
                initialSendSequence);
    }

    static Ipv6TcpStream passiveEstablished(
            byte[] localAddress,
            byte[] remoteAddress,
            int localPort,
            int remotePort,
            long initialSendSequence,
            long initialReceiveSequence) {
        Ipv6TcpStream stream = new Ipv6TcpStream(
                localAddress,
                remoteAddress,
                localPort,
                remotePort,
                initialSendSequence);
        stream.started = true;
        stream.state = State.ESTABLISHED;
        stream.receiveNext = initialReceiveSequence;
        return stream;
    }

    byte[] encodeAck(long sendSeq, long ackSeq) {
        return encodeAndRetain(
                sendSeq,
                ackSeq,
                Ipv6TcpPacketCodec.FLAG_ACK,
                new byte[0],
                new byte[0],
                sendSeq);
    }

    static PassiveOpen acceptPassive(
            byte[] incomingIpv6,
            long initialSendSequence) {
        return acceptPassive(incomingIpv6, null, null, initialSendSequence);
    }

    static PassiveOpen acceptPassive(
            byte[] incomingIpv6,
            byte[] localAddress,
            byte[] remoteAddress,
            long initialSendSequence) {
        Ipv6TcpPacketCodec.Packet syn =
                Ipv6TcpPacketCodec.decode(
                        incomingIpv6);
        Ipv6TcpStream stream = null;
        byte[] response = null;
        try {
            if (!syn.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_SYN)
                    || syn.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_ACK)
                    || syn.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_RST)
                    || syn.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_FIN)
                    || syn.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_URG)) {
                throw new IllegalArgumentException(
                        "Passive TCP open requires a plain SYN");
            }
            byte[] effectiveLocal = localAddress != null ? localAddress : syn.destinationAddress;
            byte[] effectiveRemote = remoteAddress != null ? remoteAddress : syn.sourceAddress;
            stream =
                    new Ipv6TcpStream(
                            effectiveLocal,
                            effectiveRemote,
                            syn.destinationPort,
                            syn.sourcePort,
                            initialSendSequence);
            stream.started = true;
            stream.state = State.SYN_RECEIVED;
            stream.peerWindow = syn.window;
            stream.peerMss =
                    Ipv6TcpSegmentBuffer.parsePeerMss(
                            syn.options);
            stream.receiveNext =
                    Ipv6TcpSegmentBuffer.add32(
                            syn.sequence,
                            1L + syn.payload.length);
            stream.replacePendingFastOpenPayload(
                    syn.payload);
            stream.sendNext =
                    Ipv6TcpSegmentBuffer.add32(
                            stream.initialSendSequence,
                            1);
            Ipv6TcpPacketCodec.PeerOptions peer =
                    Ipv6TcpPacketCodec.parsePeerOptions(syn.options);
            if (peer.timestampValue >= 0) {
                stream.segments.enableTimestamps(peer.timestampValue);
            }
            byte[] synAckOptions =
                    Ipv6TcpPacketCodec.encodeSynAckOptions(
                            SAFE_COMPANION_LINK_MSS,
                            peer);
            try {
                response =
                        stream.encodeAndRetain(
                                stream.initialSendSequence,
                                stream.receiveNext,
                                Ipv6TcpPacketCodec.FLAG_SYN
                                        | Ipv6TcpPacketCodec.FLAG_ACK,
                                synAckOptions,
                                new byte[0],
                                stream.sendNext);
            } finally {
                Ipv6TcpSegmentBuffer.wipe(synAckOptions);
            }
            PassiveOpen output =
                    new PassiveOpen(
                            stream,
                            response);
            stream = null;
            Ipv6TcpSegmentBuffer.wipe(
                    response);
            response = null;
            return output;
        } finally {
            syn.close();
            if (stream != null) {
                stream.close();
            }
            Ipv6TcpSegmentBuffer.wipe(
                    response);
        }
    }

    static AdoptedOpen adoptEstablished(
            byte[] incomingIpv6) {
        return adoptEstablished(incomingIpv6, null, null);
    }

    static AdoptedOpen adoptEstablished(
            byte[] incomingIpv6,
            byte[] localAddress,
            byte[] remoteAddress) {
        Ipv6TcpPacketCodec.Packet packet =
                Ipv6TcpPacketCodec.decode(
                        incomingIpv6);
        Ipv6TcpStream stream = null;
        byte[] response = null;
        byte[] payload = null;
        try {
            if (packet.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_SYN)
                    || packet.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_RST)
                    || !packet.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_ACK)) {
                throw new IllegalArgumentException(
                        "Adopt TCP stream requires an ACK segment without SYN or RST");
            }
            byte[] effectiveLocal = localAddress != null ? localAddress : packet.destinationAddress;
            byte[] effectiveRemote = remoteAddress != null ? remoteAddress : packet.sourceAddress;
            stream =
                    new Ipv6TcpStream(
                            effectiveLocal,
                            effectiveRemote,
                            packet.destinationPort,
                            packet.sourcePort,
                            packet.acknowledgement);
            stream.started = true;
            stream.state = State.ESTABLISHED;
            stream.peerWindow = packet.window;
            stream.peerMss = Ipv6TcpSegmentBuffer.parsePeerMss(packet.options);
            // Live 0.2.304: adopted ACKs must carry the peer's timestamp
            // option. The Watch negotiates TS on every flow and PAWS-drops
            // bare ACKs, so the 0.2.301 stale-quartet adoption was ignored
            // and the Watch retransmitted the stale segment for ~8.6 min.
            stream.segments.observePeerOptions(packet.options);
            stream.sendUnacknowledged = packet.acknowledgement;
            stream.sendNext = packet.acknowledgement;
            stream.receiveNext =
                    Ipv6TcpSegmentBuffer.add32(
                            packet.sequence,
                            packet.payload.length);
            if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN)) {
                stream.receiveNext = Ipv6TcpSegmentBuffer.add32(stream.receiveNext, 1);
                stream.state = State.CLOSE_WAIT;
            }
            response = stream.encodeAck();
            payload = packet.payload.length != 0
                    ? packet.payload.clone()
                    : new byte[0];
            AdoptedOpen output =
                    new AdoptedOpen(
                            stream,
                            response,
                            payload);
            stream = null;
            Ipv6TcpSegmentBuffer.wipe(response);
            Ipv6TcpSegmentBuffer.wipe(payload);
            response = null;
            payload = null;
            return output;
        } finally {
            packet.close();
            if (stream != null) {
                stream.close();
            }
            Ipv6TcpSegmentBuffer.wipe(response);
            Ipv6TcpSegmentBuffer.wipe(payload);
        }
    }

    byte[] startActiveOpen() {
        return startActiveOpen(
                new byte[0]);
    }

    /**
     * Starts an active open with optional TCP Fast Open application bytes.
     *
     * <p>Network.framework queues the signed service-connector record before
     * starting the connection. The kernel may therefore place that record in
     * the SYN. Callers may use an empty payload for the ordinary three-way
     * handshake fallback.</p>
     */
    byte[] startActiveOpen(
            byte[] fastOpenPayload) {
        requireUsable();
        if (started
                || state != State.CLOSED) {
            throw new IllegalStateException(
                    "TCP active open has already started");
        }
        if (fastOpenPayload == null
                || fastOpenPayload.length
                > SAFE_COMPANION_LINK_MSS) {
            throw new IllegalArgumentException(
                    "TCP Fast Open payload is absent/too large");
        }
        started = true;
        state = State.SYN_SENT;
        activeFastOpenPayload =
                fastOpenPayload.clone();
        sendNext =
                Ipv6TcpSegmentBuffer.add32(
                        initialSendSequence,
                        1L + fastOpenPayload.length);
        byte[] options =
                Ipv6TcpPacketCodec.encodeActiveSynOptions(
                        SAFE_COMPANION_LINK_MSS);
        try {
            return encodeAndRetain(
                    initialSendSequence,
                    0,
                    Ipv6TcpPacketCodec.FLAG_SYN,
                    options,
                    fastOpenPayload,
                    sendNext);
        } finally {
            Ipv6TcpSegmentBuffer.wipe(
                    options);
        }
    }

    InboundResult accept(
            byte[] incomingIpv6) {
        requireUsable();
        if (!started
                || state == State.CLOSED
                || state == State.RESET
                || state == State.TIME_WAIT) {
            throw new IllegalStateException(
                    "TCP stream cannot accept a packet in "
                            + state);
        }
        Ipv6TcpPacketCodec.Packet packet =
                Ipv6TcpPacketCodec.decode(
                        incomingIpv6);
        List<byte[]> outbound =
                new ArrayList<>();
        List<byte[]> delivered =
                new ArrayList<>();
        try {
            requireTuple(
                    packet);
            if (packet.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_URG)) {
                throw new IllegalArgumentException(
                        "Urgent TCP data is unsupported on IDS streams");
            }
            peerWindow = packet.window;
            observePeerOptions(packet.options);

            if (packet.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_RST)) {
                if (!Arrays.equals(packet.sourceAddress, remoteAddress)
                        || !Arrays.equals(packet.destinationAddress, localAddress)) {
                    return new InboundResult(state, outbound, delivered);
                }
                if (state == State.SYN_SENT) {
                    if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK)) {
                        long synAckTarget = Ipv6TcpSegmentBuffer.add32(initialSendSequence, 1L);
                        long fullAckTarget = sendNext;
                        long ack = packet.acknowledgement;
                        if (ack != synAckTarget && ack != fullAckTarget) {
                            return new InboundResult(
                                    state,
                                    outbound,
                                    delivered);
                        }
                    } else {
                        return new InboundResult(
                                state,
                                outbound,
                                delivered);
                    }
                }
                state = State.RESET;
                clearOutstanding();
                replacePendingFastOpenPayload(
                        new byte[0]);
                return new InboundResult(
                        state,
                        outbound,
                        delivered);
            }

            switch (state) {
                case SYN_SENT ->
                        acceptSynSent(
                                packet,
                                outbound,
                                delivered);
                case SYN_RECEIVED ->
                        acceptSynReceived(
                                packet,
                                outbound,
                                delivered);
                case ESTABLISHED,
                        FIN_WAIT_1,
                        FIN_WAIT_2,
                        CLOSE_WAIT,
                        LAST_ACK ->
                        acceptConnected(
                                packet,
                                outbound,
                                delivered);
                default -> throw new IllegalStateException(
                        "Unsupported TCP receive state "
                                + state);
            }
            drainPendingSend(outbound);
            return new InboundResult(
                    state,
                    outbound,
                    delivered);
        } catch (RuntimeException failure) {
            Ipv6TcpSegmentBuffer.wipeAll(
                    outbound);
            Ipv6TcpSegmentBuffer.wipeAll(
                    delivered);
            throw failure;
        } finally {
            packet.close();
        }
    }

    List<byte[]> send(
            byte[] bytes) {
        requireUsable();
        if (state != State.ESTABLISHED
                && state != State.CLOSE_WAIT) {
            throw new IllegalStateException(
                    "TCP stream is not established");
        }
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "TCP stream payload is null");
        }
        if (bytes.length == 0) {
            return List.of();
        }
        segments.enqueueSend(bytes);
        List<byte[]> output =
                new ArrayList<>();
        try {
            drainPendingSend(output);
            return List.copyOf(output);
        } catch (RuntimeException failure) {
            Ipv6TcpSegmentBuffer.wipeAll(output);
            throw failure;
        }
    }

    /** Keep application byte ordering while the peer's advertised window is full. */
    private void drainPendingSend(List<byte[]> output) {
        if (state != State.ESTABLISHED && state != State.CLOSE_WAIT) return;
        sendNext = segments.drainPendingSend(
                sendUnacknowledged,
                sendNext,
                receiveNext,
                peerWindow,
                peerMss,
                output);
    }

    boolean hasPendingSend() {
        requireUsable();
        return segments.hasPendingSend();
    }

    byte[] closeOutput() {
        requireUsable();
        if (segments.hasPendingSend()) {
            throw new IllegalStateException("TCP output still has queued application bytes");
        }
        if (state != State.ESTABLISHED
                && state != State.CLOSE_WAIT) {
            throw new IllegalStateException(
                    "TCP output cannot close in "
                            + state);
        }
        int flags =
                Ipv6TcpPacketCodec.FLAG_FIN
                        | Ipv6TcpPacketCodec.FLAG_ACK;
        long finEnd =
                Ipv6TcpSegmentBuffer.add32(
                        sendNext,
                        1);
        byte[] fin =
                encodeAndRetain(
                        sendNext,
                        receiveNext,
                        flags,
                        new byte[0],
                        new byte[0],
                        finEnd);
        sendNext = finEnd;
        state =
                state == State.ESTABLISHED
                        ? State.FIN_WAIT_1
                        : State.LAST_ACK;
        return fin;
    }

    /**
     * How many unacked TCP segments one pass may turn into new ERTM
     * I-frames. Live 0.2.211 emitted 19 at once, filled the 32-deep
     * window, and the Watch never advanced past TxSeq 30.
     */
    static final int MAX_RETRANSMIT_PER_PASS = 4;

    List<byte[]> retransmitOutstanding() {
        requireUsable();
        return segments.retransmitOutstanding(
                receiveNext);
    }

    State state() {
        requireUsable();
        return state;
    }

    long sendUnacknowledged() {
        requireUsable();
        return sendUnacknowledged;
    }

    long sendNext() {
        requireUsable();
        return sendNext;
    }

    /**
     * Established-stream bytes awaiting delivery, including queued window-limited bytes.
     * SYN-only states return 0 so Check is not held on a fresh connector.
     */
    long unacknowledgedSendBytes() {
        requireUsable();
        if (state != State.ESTABLISHED
                && state != State.FIN_WAIT_1
                && state != State.FIN_WAIT_2
                && state != State.CLOSE_WAIT
                && state != State.LAST_ACK) {
            return 0L;
        }
        return segments.pendingSendBytes() + Ipv6TcpSegmentBuffer.distance32(
                sendUnacknowledged,
                sendNext);
    }

    void setPayloadFastRetransmitEnabled(
            boolean enabled) {
        segments.setPayloadFastRetransmitEnabled(enabled);
    }

    long receiveNext() {
        requireUsable();
        return receiveNext;
    }

    private void acceptSynSent(
            Ipv6TcpPacketCodec.Packet packet,
            List<byte[]> outbound,
            List<byte[]> delivered) {
        if (!packet.hasFlag(
                Ipv6TcpPacketCodec.FLAG_SYN)
                && !packet.hasFlag(
                Ipv6TcpPacketCodec.FLAG_ACK)) {
            throw new IllegalArgumentException(
                    "SYN-SENT received a non-SYN non-ACK handshake packet");
        }
        if (packet.options != null && packet.options.length > 0) {
            peerMss =
                    Ipv6TcpSegmentBuffer.parsePeerMss(
                            packet.options);
            observePeerOptions(packet.options);
        }
        long synCost =
                packet.hasFlag(
                        Ipv6TcpPacketCodec.FLAG_SYN)
                        ? 1L
                        : 0L;
        receiveNext =
                Ipv6TcpSegmentBuffer.add32(
                        packet.sequence,
                        synCost + packet.payload.length);
        if (packet.hasFlag(
                Ipv6TcpPacketCodec.FLAG_ACK)) {
            long synAckTarget =
                    Ipv6TcpSegmentBuffer.add32(
                            initialSendSequence,
                            1L);
            long fullAckTarget =
                    sendNext;
            long ack =
                    packet.acknowledgement;
            if (ack != synAckTarget
                    && ack != fullAckTarget) {
                if (!packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)
                        && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)) {
                    outbound.add(
                            encodeRstForAck(
                                    ack));
                    outbound.addAll(
                            retransmitOutstanding());
                }
                return;
            }
            state = State.ESTABLISHED;
            clearOutstanding();
            sendUnacknowledged =
                    ack;
            if (packet.payload.length != 0) {
                delivered.add(
                        packet.payload.clone());
            }
            if (packet.hasFlag(
                    Ipv6TcpPacketCodec.FLAG_FIN)) {
                receiveNext =
                        Ipv6TcpSegmentBuffer.add32(
                                receiveNext,
                                1L);
                outbound.add(
                        encodeAck());
                state = State.CLOSE_WAIT;
                Ipv6TcpSegmentBuffer.wipe(
                        activeFastOpenPayload);
                activeFastOpenPayload =
                        new byte[0];
                return;
            }
            if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)) {
                if (ack == fullAckTarget
                        || activeFastOpenPayload.length == 0) {
                    outbound.add(
                            encodeAck());
                } else {
                    int offset =
                            (int) Ipv6TcpSegmentBuffer.distance32(
                                    synAckTarget,
                                    ack);
                    byte[] remainingPayload =
                            Arrays.copyOfRange(
                                    activeFastOpenPayload,
                                    offset,
                                    activeFastOpenPayload.length);
                    try {
                        outbound.add(
                                encodeAndRetain(
                                        ack,
                                        receiveNext,
                                        Ipv6TcpPacketCodec.FLAG_ACK,
                                        new byte[0],
                                        remainingPayload,
                                        sendNext));
                    } finally {
                        Ipv6TcpSegmentBuffer.wipe(
                                remainingPayload);
                    }
                }
            } else if (packet.payload.length != 0) {
                outbound.add(encodeAck());
            }
            Ipv6TcpSegmentBuffer.wipe(
                    activeFastOpenPayload);
            activeFastOpenPayload =
                    new byte[0];
            return;
        }

        if (packet.hasFlag(
                Ipv6TcpPacketCodec.FLAG_FIN)) {
            throw new IllegalArgumentException(
                    "SYN-SENT received a FIN handshake packet");
        }

        state = State.SYN_RECEIVED;
        replacePendingFastOpenPayload(
                packet.payload);
        clearOutstanding();
        byte[] options =
                Ipv6TcpPacketCodec.encodeMssOption(
                        SAFE_COMPANION_LINK_MSS);
        try {
            outbound.add(
                    encodeAndRetain(
                            initialSendSequence,
                            receiveNext,
                            Ipv6TcpPacketCodec.FLAG_SYN
                                    | Ipv6TcpPacketCodec.FLAG_ACK,
                            options,
                            new byte[0],
                            sendNext));
        } finally {
            Ipv6TcpSegmentBuffer.wipe(
                    options);
        }
    }

    private void acceptSynReceived(
            Ipv6TcpPacketCodec.Packet packet,
            List<byte[]> outbound,
            List<byte[]> delivered) {
        boolean syn =
                packet.hasFlag(
                        Ipv6TcpPacketCodec.FLAG_SYN);
        boolean ack =
                packet.hasFlag(
                        Ipv6TcpPacketCodec.FLAG_ACK);
        if (syn) {
            boolean samePeerSyn =
                    packet.sequence
                            == Ipv6TcpSegmentBuffer.add32(
                            receiveNext,
                            -1L - packet.payload.length)
                            && Arrays.equals(
                            pendingFastOpenPayload,
                            packet.payload);
            if (!samePeerSyn) {
                throw new IllegalArgumentException(
                        "SYN-RECEIVED got a different peer SYN");
            }
            if (!ack) {
                outbound.addAll(
                        retransmitOutstanding());
                return;
            }
            acceptAcknowledgement(
                    packet.acknowledgement,
                    true);
            state = State.ESTABLISHED;
            if (pendingFastOpenPayload.length != 0) {
                delivered.add(
                        pendingFastOpenPayload.clone());
                replacePendingFastOpenPayload(
                        new byte[0]);
            }
            outbound.add(
                    encodeAck());
            return;
        }
        if (!ack) {
            throw new IllegalArgumentException(
                    "SYN-RECEIVED requires the final ACK");
        }
        if (packet.sequence != receiveNext) {
            outbound.add(
                    encodeAck());
            return;
        }
        acceptAcknowledgement(
                packet.acknowledgement,
                true);
        state = State.ESTABLISHED;
        if (pendingFastOpenPayload.length != 0) {
            delivered.add(
                    pendingFastOpenPayload.clone());
            replacePendingFastOpenPayload(
                    new byte[0]);
        }
        if (packet.payload.length != 0) {
            delivered.add(
                    packet.payload.clone());
            receiveNext =
                    Ipv6TcpSegmentBuffer.add32(
                            receiveNext,
                            packet.payload.length);
            outbound.add(
                    encodeAck());
        }
    }

    private void acceptConnected(
            Ipv6TcpPacketCodec.Packet packet,
            List<byte[]> outbound,
            List<byte[]> delivered) {
        java.util.function.Consumer<String> logger = diagnosticLogger;
        if (logger != null) {
            logger.accept(String.format(
                    java.util.Locale.US,
                    "[TCP RX] port %d->%d: seq=%d ack=%d len=%d flags=0x%02X expectedSeq=%d sendNext=%d",
                    localPort,
                    remotePort,
                    packet.sequence,
                    packet.acknowledgement,
                    packet.payload.length,
                    packet.flags,
                    receiveNext,
                    sendNext));
        }
        if (packet.hasFlag(
                Ipv6TcpPacketCodec.FLAG_SYN)) {
            if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK)) {
                outbound.add(encodeAck());
                return;
            }
            throw new IllegalArgumentException(
                    "Established TCP stream received a new SYN");
        }
        if (!packet.hasFlag(
                Ipv6TcpPacketCodec.FLAG_ACK)) {
            throw new IllegalArgumentException(
                    "Established TCP segment is missing ACK");
        }
        boolean duplicateAck =
                packet.acknowledgement
                        == sendUnacknowledged;
        acceptAcknowledgement(
                packet.acknowledgement,
                false);
        if (packet.sequence != receiveNext) {
            long behind = Ipv6TcpSegmentBuffer.distance32(packet.sequence, receiveNext);
            if (behind > 0 && behind < 0x80000000L && behind < (long) packet.payload.length) {
                int offset = (int) behind;
                byte[] newPayload = Arrays.copyOfRange(
                        packet.payload,
                        offset,
                        packet.payload.length);
                delivered.add(newPayload);
                receiveNext = Ipv6TcpSegmentBuffer.add32(receiveNext, newPayload.length);
                segments.resetBehindWindowRetransmitTick();
                if (logger != null) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[TCP RX OVERLAP] seq=%d expectedSeq=%d overlap=%d newBytes=%d",
                            packet.sequence,
                            Ipv6TcpSegmentBuffer.add32(packet.sequence, offset),
                            offset,
                            newPayload.length));
                }
                if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN)) {
                    receiveNext = Ipv6TcpSegmentBuffer.add32(receiveNext, 1);
                }
                outbound.add(encodeAck());
                return;
            }
            if (behind > 0 && behind < 0x80000000L && behind >= (long) packet.payload.length) {
                if (logger != null) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[TCP RX DUPLICATE/RETRANSMIT] seq=%d len=%d expectedSeq=%d",
                            packet.sequence,
                            packet.payload.length,
                            receiveNext));
                }
                outbound.add(encodeAck());
                if (sequenceAlreadyDelivered(packet.sequence)) {
                    maybeFastRetransmit(outbound);
                }
                return;
            }
            if (logger != null) {
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[TCP RX OUT-OF-ORDER] seq=%d != expectedSeq=%d",
                        packet.sequence,
                        receiveNext));
            }
            outbound.add(
                    encodeAck());
            if (sequenceAlreadyDelivered(
                    packet.sequence)) {
                maybeFastRetransmit(
                        outbound);
            }
            return;
        }
        if (!duplicateAck) {
            segments.resetBehindWindowRetransmitTick();
        }
        if (packet.payload.length != 0) {
            delivered.add(
                    packet.payload.clone());
            receiveNext =
                    Ipv6TcpSegmentBuffer.add32(
                            receiveNext,
                            packet.payload.length);
        }
        boolean fin =
                packet.hasFlag(
                        Ipv6TcpPacketCodec.FLAG_FIN);
        if (fin) {
            receiveNext =
                    Ipv6TcpSegmentBuffer.add32(
                            receiveNext,
                            1);
        }
        if (packet.payload.length != 0
                || fin) {
            outbound.add(
                    encodeAck());
        } else if (duplicateAck
                && segments.hasOutstanding()
                && segments.isPayloadFastRetransmitEnabled()) {
            maybeFastRetransmit(
                    outbound);
        }

        if (state == State.FIN_WAIT_1
                && sendUnacknowledged == sendNext) {
            state =
                    fin
                            ? State.TIME_WAIT
                            : State.FIN_WAIT_2;
        } else if (state == State.FIN_WAIT_2
                && fin) {
            state = State.TIME_WAIT;
        } else if (state == State.ESTABLISHED
                && fin) {
            state = State.CLOSE_WAIT;
        } else if (state == State.LAST_ACK
                && sendUnacknowledged == sendNext) {
            state = State.CLOSED;
        }
    }

    private void acceptAcknowledgement(
            long acknowledgement,
            boolean mustAcknowledgeAll) {
        sendUnacknowledged =
                segments.acceptAcknowledgement(
                        sendUnacknowledged,
                        sendNext,
                        acknowledgement,
                        mustAcknowledgeAll);
    }

    private byte[] encodeAck() {
        return segments.encodeAck(
                sendUnacknowledged,
                sendNext,
                receiveNext);
    }

    private void observePeerOptions(byte[] options) {
        segments.observePeerOptions(options);
    }

    private boolean sequenceAlreadyDelivered(
            long sequence) {
        long behind =
                Ipv6TcpSegmentBuffer.distance32(
                        sequence,
                        receiveNext);
        return behind != 0
                && behind <= RECEIVE_WINDOW;
    }

    private void maybeFastRetransmit(
            List<byte[]> outbound) {
        segments.maybeFastRetransmit(
                sendUnacknowledged,
                sendNext,
                receiveNext,
                outbound);
    }

    private byte[] encodeRstForAck(long sequence) {
        return segments.encodeRstForAck(sequence);
    }

    private byte[] encodeAndRetain(
            long sequence,
            long acknowledgement,
            int flags,
            byte[] options,
            byte[] payload,
            long endSequence) {
        return segments.encodeAndRetain(
                sequence,
                acknowledgement,
                flags,
                options,
                payload,
                endSequence);
    }

    private void requireTuple(
            Ipv6TcpPacketCodec.Packet packet) {
        if (packet.sourcePort != remotePort
                || packet.destinationPort != localPort) {
            throw new IllegalArgumentException(
                    "IPv6/TCP packet does not belong to this stream");
        }
    }

    private void replacePendingFastOpenPayload(
            byte[] replacement) {
        Ipv6TcpSegmentBuffer.wipe(
                pendingFastOpenPayload);
        pendingFastOpenPayload =
                replacement.clone();
    }

    private void clearOutstanding() {
        segments.clearOutstanding();
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IPv6/TCP stream is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearOutstanding();
        Ipv6TcpSegmentBuffer.wipe(
                localAddress);
        Ipv6TcpSegmentBuffer.wipe(
                remoteAddress);
        Ipv6TcpSegmentBuffer.wipe(
                pendingFastOpenPayload);
        pendingFastOpenPayload =
                new byte[0];
        Ipv6TcpSegmentBuffer.wipe(
                activeFastOpenPayload);
        activeFastOpenPayload =
                new byte[0];
    }

    static final class PassiveOpen
            implements AutoCloseable {
        private Ipv6TcpStream stream;
        private byte[] synAcknowledgement;
        private boolean closed;

        PassiveOpen(
                Ipv6TcpStream stream,
                byte[] synAcknowledgement) {
            this.stream = stream;
            this.synAcknowledgement =
                    synAcknowledgement.clone();
        }

        Ipv6TcpStream takeStream() {
            requireOpen();
            Ipv6TcpStream output =
                    stream;
            stream = null;
            return output;
        }

        byte[] synAcknowledgement() {
            requireOpen();
            return synAcknowledgement.clone();
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Passive TCP open is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (stream != null) {
                stream.close();
                stream = null;
            }
            Ipv6TcpSegmentBuffer.wipe(
                    synAcknowledgement);
            synAcknowledgement =
                    new byte[0];
        }
    }

    static final class AdoptedOpen
            implements AutoCloseable {
        private Ipv6TcpStream stream;
        private byte[] acknowledgement;
        private byte[] deliveredPayload;
        private boolean closed;

        AdoptedOpen(
                Ipv6TcpStream stream,
                byte[] acknowledgement,
                byte[] deliveredPayload) {
            this.stream = stream;
            this.acknowledgement =
                    acknowledgement != null ? acknowledgement.clone() : new byte[0];
            this.deliveredPayload =
                    deliveredPayload != null ? deliveredPayload.clone() : new byte[0];
        }

        Ipv6TcpStream takeStream() {
            requireOpen();
            Ipv6TcpStream output = stream;
            stream = null;
            return output;
        }

        byte[] acknowledgement() {
            requireOpen();
            return acknowledgement.clone();
        }

        byte[] deliveredPayload() {
            requireOpen();
            return deliveredPayload.clone();
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Adopted TCP open is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (stream != null) {
                stream.close();
                stream = null;
            }
            Ipv6TcpSegmentBuffer.wipe(acknowledgement);
            Ipv6TcpSegmentBuffer.wipe(deliveredPayload);
            acknowledgement = new byte[0];
            deliveredPayload = new byte[0];
        }
    }

    static final class InboundResult
            implements AutoCloseable {
        final State state;
        private final List<byte[]> outbound;
        private final List<byte[]> delivered;
        private boolean closed;

        InboundResult(
                State state,
                List<byte[]> outbound,
                List<byte[]> delivered) {
            this.state = state;
            this.outbound =
                    cloneAll(
                            outbound);
            this.delivered =
                    cloneAll(
                            delivered);
            Ipv6TcpSegmentBuffer.wipeAll(
                    outbound);
            Ipv6TcpSegmentBuffer.wipeAll(
                    delivered);
        }

        List<byte[]> outboundPackets() {
            requireOpen();
            return cloneAll(
                    outbound);
        }

        List<byte[]> deliveredBytes() {
            requireOpen();
            return cloneAll(
                    delivered);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "TCP inbound result is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Ipv6TcpSegmentBuffer.wipeAll(
                    outbound);
            Ipv6TcpSegmentBuffer.wipeAll(
                    delivered);
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
    }

}
