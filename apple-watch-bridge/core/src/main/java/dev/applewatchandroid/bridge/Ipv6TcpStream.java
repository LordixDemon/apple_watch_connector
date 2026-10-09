package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
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

    private static final long U32_MASK = 0xffff_ffffL;
    private static final int OPTION_END = 0;
    private static final int OPTION_NO_OPERATION = 1;
    private static final int OPTION_MSS = 2;

    final byte[] localAddress;
    final byte[] remoteAddress;
    private final int localPort;
    private int remotePort;
    private final long initialSendSequence;
    private final List<Outstanding> outstanding =
            new ArrayList<>();
    private final ArrayDeque<byte[]> pendingSend = new ArrayDeque<>();
    private int pendingSendOffset;
    private long pendingSendBytes;
    private static final long MAX_PENDING_SEND_BYTES = 4L * 1024 * 1024;

    void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
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
    private boolean timestampsEnabled;
    private long peerTimestampValue;
    private byte[] pendingFastOpenPayload =
            new byte[0];
    private byte[] activeFastOpenPayload =
            new byte[0];
    private boolean started;
    private boolean closed;
    private int behindWindowRetransmitTick;
    /**
     * Live 0.2.124/0.2.126: payload fast-retransmit mints a <em>new</em>
     * ERTM I-frame. While NanoRegistry Check is held, that fills TxWindow
     * to 32; the HAL retransmits the original I-frame instead.
     */
    private boolean payloadFastRetransmitEnabled = true;

    private Ipv6TcpStream(
            byte[] localAddress,
            byte[] remoteAddress,
            int localPort,
            int remotePort,
            long initialSendSequence) {
        requireAddress(
                "local",
                localAddress);
        requireAddress(
                "remote",
                remoteAddress);
        requirePort(
                "local",
                localPort);
        requirePort(
                "remote",
                remotePort);
        requireU32(
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
                    parsePeerMss(
                            syn.options);
            stream.receiveNext =
                    add32(
                            syn.sequence,
                            1L + syn.payload.length);
            stream.replacePendingFastOpenPayload(
                    syn.payload);
            stream.sendNext =
                    add32(
                            stream.initialSendSequence,
                            1);
            Ipv6TcpPacketCodec.PeerOptions peer =
                    Ipv6TcpPacketCodec.parsePeerOptions(syn.options);
            if (peer.timestampValue >= 0) {
                stream.timestampsEnabled = true;
                stream.peerTimestampValue = peer.timestampValue;
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
                wipe(synAckOptions);
            }
            PassiveOpen output =
                    new PassiveOpen(
                            stream,
                            response);
            stream = null;
            wipe(
                    response);
            response = null;
            return output;
        } finally {
            syn.close();
            if (stream != null) {
                stream.close();
            }
            wipe(
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
            stream.peerMss = parsePeerMss(packet.options);
            // Live 0.2.304: adopted ACKs must carry the peer's timestamp
            // option. The Watch negotiates TS on every flow and PAWS-drops
            // bare ACKs, so the 0.2.301 stale-quartet adoption was ignored
            // and the Watch retransmitted the stale segment for ~8.6 min.
            stream.observePeerOptions(packet.options);
            stream.sendUnacknowledged = packet.acknowledgement;
            stream.sendNext = packet.acknowledgement;
            stream.receiveNext =
                    add32(
                            packet.sequence,
                            packet.payload.length);
            if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN)) {
                stream.receiveNext = add32(stream.receiveNext, 1);
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
            wipe(response);
            wipe(payload);
            response = null;
            payload = null;
            return output;
        } finally {
            packet.close();
            if (stream != null) {
                stream.close();
            }
            wipe(response);
            wipe(payload);
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
                add32(
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
            wipe(
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
                        long synAckTarget = add32(initialSendSequence, 1L);
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
            wipeAll(
                    outbound);
            wipeAll(
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
        if (pendingSendBytes + bytes.length > MAX_PENDING_SEND_BYTES) {
            throw new IllegalStateException(
                    "TCP pending send queue exceeded its capacity");
        }
        pendingSend.addLast(bytes.clone());
        pendingSendBytes += bytes.length;
        List<byte[]> output =
                new ArrayList<>();
        try {
            drainPendingSend(output);
            return List.copyOf(output);
        } catch (RuntimeException failure) {
            wipeAll(output);
            throw failure;
        }
    }

    /** Keep application byte ordering while the peer's advertised window is full. */
    private void drainPendingSend(List<byte[]> output) {
        if (state != State.ESTABLISHED && state != State.CLOSE_WAIT) return;
        while (!pendingSend.isEmpty()) {
                long available = Math.max(0L, (long) peerWindow
                        - distance32(sendUnacknowledged, sendNext));
                if (available == 0) return;
                byte[] bytes = pendingSend.peekFirst();
                int length =
                        (int) Math.min(available, Math.min(
                                peerMss,
                                bytes.length - pendingSendOffset));
                byte[] payload =
                        Arrays.copyOfRange(
                                bytes,
                                pendingSendOffset,
                                pendingSendOffset + length);
                long segmentEnd =
                        add32(
                                sendNext,
                                length);
                try {
                    output.add(
                            encodeAndRetain(
                                    sendNext,
                                    receiveNext,
                                    Ipv6TcpPacketCodec.FLAG_ACK
                                            | Ipv6TcpPacketCodec.FLAG_PSH,
                                    new byte[0],
                                    payload,
                                    segmentEnd));
                    sendNext = segmentEnd;
                    pendingSendOffset += length;
                    pendingSendBytes -= length;
                    if (pendingSendOffset == bytes.length) {
                        wipe(pendingSend.removeFirst());
                        pendingSendOffset = 0;
                    }
                } finally {
                    wipe(
                            payload);
                }
        }
    }

    byte[] closeOutput() {
        requireUsable();
        if (pendingSendBytes != 0) {
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
                add32(
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
        List<byte[]> output =
                new ArrayList<>(
                        Math.min(
                                outstanding.size(),
                                MAX_RETRANSMIT_PER_PASS));
        try {
            int emitted = 0;
            for (Outstanding retained : outstanding) {
                if (emitted >= MAX_RETRANSMIT_PER_PASS) {
                    break;
                }
                output.add(
                        refreshRetransmit(
                                retained.packet));
                emitted++;
            }
            return List.copyOf(
                    output);
        } catch (RuntimeException failure) {
            wipeAll(
                    output);
            throw failure;
        }
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
        return pendingSendBytes + distance32(
                sendUnacknowledged,
                sendNext);
    }

    void setPayloadFastRetransmitEnabled(
            boolean enabled) {
        payloadFastRetransmitEnabled = enabled;
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
                    parsePeerMss(
                            packet.options);
            observePeerOptions(packet.options);
        }
        long synCost =
                packet.hasFlag(
                        Ipv6TcpPacketCodec.FLAG_SYN)
                        ? 1L
                        : 0L;
        receiveNext =
                add32(
                        packet.sequence,
                        synCost + packet.payload.length);
        if (packet.hasFlag(
                Ipv6TcpPacketCodec.FLAG_ACK)) {
            long synAckTarget =
                    add32(
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
                        add32(
                                receiveNext,
                                1L);
                outbound.add(
                        encodeAck());
                state = State.CLOSE_WAIT;
                wipe(
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
                            (int) distance32(
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
                        wipe(
                                remainingPayload);
                    }
                }
            } else if (packet.payload.length != 0) {
                outbound.add(encodeAck());
            }
            wipe(
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
            wipe(
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
                            == add32(
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
                    add32(
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
            long behind = distance32(packet.sequence, receiveNext);
            if (behind > 0 && behind < 0x80000000L && behind < (long) packet.payload.length) {
                int offset = (int) behind;
                byte[] newPayload = Arrays.copyOfRange(
                        packet.payload,
                        offset,
                        packet.payload.length);
                delivered.add(newPayload);
                receiveNext = add32(receiveNext, newPayload.length);
                behindWindowRetransmitTick = 0;
                if (logger != null) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[TCP RX OVERLAP] seq=%d expectedSeq=%d overlap=%d newBytes=%d",
                            packet.sequence,
                            add32(packet.sequence, offset),
                            offset,
                            newPayload.length));
                }
                if (packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN)) {
                    receiveNext = add32(receiveNext, 1);
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
            behindWindowRetransmitTick = 0;
        }
        if (packet.payload.length != 0) {
            delivered.add(
                    packet.payload.clone());
            receiveNext =
                    add32(
                            receiveNext,
                            packet.payload.length);
        }
        boolean fin =
                packet.hasFlag(
                        Ipv6TcpPacketCodec.FLAG_FIN);
        if (fin) {
            receiveNext =
                    add32(
                            receiveNext,
                            1);
        }
        if (packet.payload.length != 0
                || fin) {
            outbound.add(
                    encodeAck());
        } else if (duplicateAck
                && !outstanding.isEmpty()
                && payloadFastRetransmitEnabled) {
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
        long outstandingBytes =
                distance32(
                        sendUnacknowledged,
                        sendNext);
        long advance =
                distance32(
                        sendUnacknowledged,
                        acknowledgement);
        if (advance > outstandingBytes) {
            return;
        }
        if (mustAcknowledgeAll
                && acknowledgement != sendNext) {
            throw new IllegalArgumentException(
                    "TCP handshake did not acknowledge the SYN");
        }
        if (advance == 0) {
            return;
        }
        long oldUnacknowledged =
                sendUnacknowledged;
        Iterator<Outstanding> iterator =
                outstanding.iterator();
        while (iterator.hasNext()) {
            Outstanding retained =
                    iterator.next();
            long retainedEndDistance =
                    distance32(
                            oldUnacknowledged,
                            retained.endSequence);
            if (retainedEndDistance <= advance) {
                retained.destroy();
                iterator.remove();
            }
        }
        sendUnacknowledged =
                acknowledgement;
    }

    private byte[] encodeAck() {
        // While Check (or any payload) is unacked, SND.NXT is already past
        // Watch RCV.NXT. An empty ACK with that SEQ is a future segment;
        // live 0.2.123 Watch ignored it and kept the 9-byte handshake replay.
        long sequence =
                outstanding.isEmpty()
                        ? sendNext
                        : sendUnacknowledged;
        byte[] options = timestampOptions();
        try {
            return Ipv6TcpPacketCodec.encode(
                    localAddress,
                    remoteAddress,
                    localPort,
                    remotePort,
                    sequence,
                    receiveNext,
                    Ipv6TcpPacketCodec.FLAG_ACK,
                    RECEIVE_WINDOW,
                    0,
                    options,
                    new byte[0]);
        } finally {
            wipe(options);
        }
    }

    private void observePeerOptions(byte[] options) {
        Ipv6TcpPacketCodec.PeerOptions peer =
                Ipv6TcpPacketCodec.parsePeerOptions(options);
        if (peer.timestampValue >= 0) {
            timestampsEnabled = true;
            peerTimestampValue = peer.timestampValue;
        }
    }

    private byte[] timestampOptions() {
        if (!timestampsEnabled) {
            return new byte[0];
        }
        return Ipv6TcpPacketCodec.encodeTimestampOption(peerTimestampValue);
    }

    private byte[] withTimestamps(byte[] options) {
        if (!timestampsEnabled || containsTimestamp(options)) {
            return options == null ? new byte[0] : options;
        }
        byte[] timestamps = timestampOptions();
        if (options == null || options.length == 0) {
            return timestamps;
        }
        byte[] merged = new byte[options.length + timestamps.length];
        System.arraycopy(options, 0, merged, 0, options.length);
        System.arraycopy(timestamps, 0, merged, options.length, timestamps.length);
        wipe(timestamps);
        return merged;
    }

    private static boolean containsTimestamp(byte[] options) {
        if (options == null) {
            return false;
        }
        int index = 0;
        while (index < options.length) {
            int kind = options[index] & 0xff;
            if (kind == 0) {
                return false;
            }
            if (kind == 1) {
                index++;
                continue;
            }
            if (kind == 8) {
                return true;
            }
            if (index + 1 >= options.length) {
                return false;
            }
            int length = options[index + 1] & 0xff;
            if (length < 2) {
                return false;
            }
            index += length;
        }
        return false;
    }

    private boolean sequenceAlreadyDelivered(
            long sequence) {
        long behind =
                distance32(
                        sequence,
                        receiveNext);
        return behind != 0
                && behind <= RECEIVE_WINDOW;
    }

    private void maybeFastRetransmit(
            List<byte[]> outbound) {
        if (!payloadFastRetransmitEnabled
                || outstanding.isEmpty()) {
            return;
        }
        behindWindowRetransmitTick++;
        // Second behind-window replay, not the first (live 0.2.121 Check
        // TX+2 ms) and not the third (live 0.2.123 only two 49155 OOO
        // before the 1.7 s RTO).
        if (behindWindowRetransmitTick != 2
                && (behindWindowRetransmitTick - 2) % 3 != 0) {
            return;
        }
        Outstanding oldest =
                outstanding.isEmpty() ? null : outstanding.get(0);
        if (oldest == null) {
            return;
        }
        byte[] retried =
                refreshRetransmit(
                        oldest.packet);
        outbound.add(
                retried);
        java.util.function.Consumer<String> logger = diagnosticLogger;
        if (logger != null) {
            logger.accept(String.format(
                    java.util.Locale.US,
                    "[TCP TX RETRANSMIT] port %d->%d segments=1 una=%d nxt=%d",
                    localPort,
                    remotePort,
                    sendUnacknowledged,
                    sendNext));
        }
    }

    private byte[] refreshRetransmit(
            byte[] original) {
        Ipv6TcpPacketCodec.Packet decoded = null;
        byte[] refreshedOptions = null;
        try {
            decoded =
                    Ipv6TcpPacketCodec.decode(
                            original);
            refreshedOptions = refreshTimestampOption(decoded.options);
            return Ipv6TcpPacketCodec.encode(
                    localAddress,
                    remoteAddress,
                    localPort,
                    remotePort,
                    decoded.sequence,
                    receiveNext,
                    decoded.flags,
                    RECEIVE_WINDOW,
                    0,
                    refreshedOptions,
                    decoded.payload);
        } finally {
            wipe(refreshedOptions);
            if (decoded != null) {
                decoded.close();
            }
        }
    }

    /** Preserve SYN/MSS options, but avoid PAWS rejection after newer packets advanced TS.Recent. */
    private byte[] refreshTimestampOption(byte[] options) {
        byte[] refreshed = options.clone();
        int offset = 0;
        while (offset < refreshed.length) {
            int kind = refreshed[offset] & 0xff;
            if (kind == 0) break;
            if (kind == 1) { offset++; continue; }
            if (offset + 1 >= refreshed.length) break;
            int length = refreshed[offset + 1] & 0xff;
            if (length < 2 || offset + length > refreshed.length) break;
            if (kind == 8 && length == 10) {
                byte[] current = Ipv6TcpPacketCodec.encodeTimestampOption(peerTimestampValue);
                try { System.arraycopy(current, 2, refreshed, offset, 10); }
                finally { wipe(current); }
                break;
            }
            offset += length;
        }
        return refreshed;
    }

    private byte[] encodeRstForAck(long sequence) {
        return Ipv6TcpPacketCodec.encode(
                localAddress,
                remoteAddress,
                localPort,
                remotePort,
                sequence,
                0L,
                Ipv6TcpPacketCodec.FLAG_RST,
                0,
                0,
                new byte[0],
                new byte[0]);
    }

    private byte[] encodeAndRetain(
            long sequence,
            long acknowledgement,
            int flags,
            byte[] options,
            byte[] payload,
            long endSequence) {
        byte[] stamped = withTimestamps(options);
        byte[] packet =
                Ipv6TcpPacketCodec.encode(
                        localAddress,
                        remoteAddress,
                        localPort,
                        remotePort,
                        sequence,
                        acknowledgement,
                        flags,
                        RECEIVE_WINDOW,
                        0,
                        stamped,
                        payload);
        if (stamped != options) {
            wipe(stamped);
        }
        try {
            outstanding.add(
                    new Outstanding(
                            endSequence,
                            packet));
            return packet;
        } catch (RuntimeException failure) {
            wipe(
                    packet);
            throw failure;
        }
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
        wipe(
                pendingFastOpenPayload);
        pendingFastOpenPayload =
                replacement.clone();
    }

    private void clearOutstanding() {
        for (Outstanding retained : outstanding) {
            retained.destroy();
        }
        outstanding.clear();
        while (!pendingSend.isEmpty()) wipe(pendingSend.removeFirst());
        pendingSendBytes = 0;
        pendingSendOffset = 0;
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
        wipe(
                localAddress);
        wipe(
                remoteAddress);
        wipe(
                pendingFastOpenPayload);
        pendingFastOpenPayload =
                new byte[0];
        wipe(
                activeFastOpenPayload);
        activeFastOpenPayload =
                new byte[0];
    }

    private static int parsePeerMss(
            byte[] options) {
        int mss = SAFE_COMPANION_LINK_MSS;
        int offset = 0;
        while (offset < options.length) {
            int kind =
                    options[offset] & 0xff;
            if (kind == OPTION_END) {
                break;
            }
            if (kind == OPTION_NO_OPERATION) {
                offset++;
                continue;
            }
            if (offset + 1 >= options.length) {
                throw new IllegalArgumentException(
                        "TCP option length byte is missing");
            }
            int length =
                    options[offset + 1] & 0xff;
            if (length < 2
                    || offset + length > options.length) {
                throw new IllegalArgumentException(
                        "TCP option has an invalid length");
            }
            if (kind == OPTION_MSS) {
                if (length != 4) {
                    throw new IllegalArgumentException(
                            "TCP MSS option has an invalid length");
                }
                int peer =
                        ((options[offset + 2] & 0xff) << 8)
                                | (options[offset + 3] & 0xff);
                if (peer == 0) {
                    throw new IllegalArgumentException(
                            "TCP peer advertised an MSS of zero");
                }
                mss =
                        Math.min(
                                SAFE_COMPANION_LINK_MSS,
                                peer);
            }
            offset += length;
        }
        return mss;
    }

    private static long add32(
            long value,
            long delta) {
        return (value + delta)
                & U32_MASK;
    }

    private static long distance32(
            long start,
            long end) {
        return (end - start)
                & U32_MASK;
    }

    private static void requireAddress(
            String label,
            byte[] address) {
        if (address == null
                || address.length != 16) {
            throw new IllegalArgumentException(
                    "TCP "
                            + label
                            + " IPv6 address must be 16 bytes");
        }
    }

    private static void requirePort(
            String label,
            int port) {
        if (port <= 0
                || port > 0xffff) {
            throw new IllegalArgumentException(
                    "TCP "
                            + label
                            + " port must be 1..65535");
        }
    }

    private static void requireU32(
            String label,
            long value) {
        if (value < 0
                || value > U32_MASK) {
            throw new IllegalArgumentException(
                    label + " must fit uint32");
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
            wipe(
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
            wipe(acknowledgement);
            wipe(deliveredPayload);
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
            wipeAll(
                    outbound);
            wipeAll(
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
            wipeAll(
                    outbound);
            wipeAll(
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

    private static final class Outstanding {
        final long endSequence;
        final byte[] packet;

        Outstanding(
                long endSequence,
                byte[] packet) {
            this.endSequence = endSequence;
            this.packet = packet.clone();
        }

        void destroy() {
            wipe(
                    packet);
        }
    }
}
