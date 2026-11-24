package dev.applewatchandroid.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Outbound segmentation queue, unacknowledged-segment retention buffer, TCP
 * timestamp option management, and 32-bit sequence helpers for
 * {@link Ipv6TcpStream}.
 */
final class Ipv6TcpSegmentBuffer {
    private static final long U32_MASK = 0xffff_ffffL;
    private static final int OPTION_END = 0;
    private static final int OPTION_NO_OPERATION = 1;
    private static final int OPTION_MSS = 2;
    private static final long MAX_PENDING_SEND_BYTES = 4L * 1024 * 1024;

    private final byte[] localAddress;
    private final byte[] remoteAddress;
    private final int localPort;
    private int remotePort;
    private final List<Outstanding> outstanding = new ArrayList<>();
    private final ArrayDeque<byte[]> pendingSend = new ArrayDeque<>();
    private int pendingSendOffset;
    private long pendingSendBytes;
    private boolean timestampsEnabled;
    private long peerTimestampValue;
    private int behindWindowRetransmitTick;
    private boolean payloadFastRetransmitEnabled = true;

    Ipv6TcpSegmentBuffer(
            byte[] localAddress,
            byte[] remoteAddress,
            int localPort,
            int remotePort) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.localPort = localPort;
        this.remotePort = remotePort;
    }

    void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
    }

    void enableTimestamps(long peerTimestampValue) {
        this.timestampsEnabled = true;
        this.peerTimestampValue = peerTimestampValue;
    }

    void observePeerOptions(byte[] options) {
        Ipv6TcpPacketCodec.PeerOptions peer =
                Ipv6TcpPacketCodec.parsePeerOptions(options);
        if (peer.timestampValue >= 0) {
            timestampsEnabled = true;
            peerTimestampValue = peer.timestampValue;
        }
    }

    void setPayloadFastRetransmitEnabled(boolean enabled) {
        payloadFastRetransmitEnabled = enabled;
    }

    boolean isPayloadFastRetransmitEnabled() {
        return payloadFastRetransmitEnabled;
    }

    long pendingSendBytes() {
        return pendingSendBytes;
    }

    boolean hasPendingSend() {
        return pendingSendBytes != 0;
    }

    boolean hasOutstanding() {
        return !outstanding.isEmpty();
    }

    void resetBehindWindowRetransmitTick() {
        behindWindowRetransmitTick = 0;
    }

    void enqueueSend(byte[] bytes) {
        if (pendingSendBytes + bytes.length > MAX_PENDING_SEND_BYTES) {
            throw new IllegalStateException(
                    "TCP pending send queue exceeded its capacity");
        }
        pendingSend.addLast(bytes.clone());
        pendingSendBytes += bytes.length;
    }

    /** Keep application byte ordering while the peer's advertised window is full. */
    long drainPendingSend(
            long sendUnacknowledged,
            long sendNext,
            long receiveNext,
            int peerWindow,
            int peerMss,
            List<byte[]> output) {
        while (!pendingSend.isEmpty()) {
            long available = Math.max(0L, (long) peerWindow
                    - distance32(sendUnacknowledged, sendNext));
            if (available == 0) {
                return sendNext;
            }
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
        return sendNext;
    }

    long acceptAcknowledgement(
            long sendUnacknowledged,
            long sendNext,
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
            return sendUnacknowledged;
        }
        if (mustAcknowledgeAll
                && acknowledgement != sendNext) {
            throw new IllegalArgumentException(
                    "TCP handshake did not acknowledge the SYN");
        }
        if (advance == 0) {
            return sendUnacknowledged;
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
        return acknowledgement;
    }

    List<byte[]> retransmitOutstanding(long receiveNext) {
        List<byte[]> output =
                new ArrayList<>(
                        Math.min(
                                outstanding.size(),
                                Ipv6TcpStream.MAX_RETRANSMIT_PER_PASS));
        try {
            int emitted = 0;
            for (Outstanding retained : outstanding) {
                if (emitted >= Ipv6TcpStream.MAX_RETRANSMIT_PER_PASS) {
                    break;
                }
                output.add(
                        refreshRetransmit(
                                retained.packet,
                                receiveNext));
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

    void maybeFastRetransmit(
            long sendUnacknowledged,
            long sendNext,
            long receiveNext,
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
                        oldest.packet,
                        receiveNext);
        outbound.add(
                retried);
        java.util.function.Consumer<String> logger = Ipv6TcpStream.diagnosticLogger;
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

    byte[] encodeAck(
            long sendUnacknowledged,
            long sendNext,
            long receiveNext) {
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
                    Ipv6TcpStream.RECEIVE_WINDOW,
                    0,
                    options,
                    new byte[0]);
        } finally {
            wipe(options);
        }
    }

    byte[] encodeRstForAck(long sequence) {
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

    byte[] encodeAndRetain(
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
                        Ipv6TcpStream.RECEIVE_WINDOW,
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

    void clearOutstanding() {
        for (Outstanding retained : outstanding) {
            retained.destroy();
        }
        outstanding.clear();
        while (!pendingSend.isEmpty()) wipe(pendingSend.removeFirst());
        pendingSendBytes = 0;
        pendingSendOffset = 0;
    }

    static int parsePeerMss(
            byte[] options) {
        int mss = Ipv6TcpStream.SAFE_COMPANION_LINK_MSS;
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
                                Ipv6TcpStream.SAFE_COMPANION_LINK_MSS,
                                peer);
            }
            offset += length;
        }
        return mss;
    }

    static long add32(
            long value,
            long delta) {
        return (value + delta)
                & U32_MASK;
    }

    static long distance32(
            long start,
            long end) {
        return (end - start)
                & U32_MASK;
    }

    static void requireAddress(
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

    static void requirePort(
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

    static void requireU32(
            String label,
            long value) {
        if (value < 0
                || value > U32_MASK) {
            throw new IllegalArgumentException(
                    label + " must fit uint32");
        }
    }

    static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static void wipeAll(
            List<byte[]> values) {
        for (byte[] value : values) {
            wipe(
                    value);
        }
        values.clear();
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

    private byte[] refreshRetransmit(
            byte[] original,
            long receiveNext) {
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
                    Ipv6TcpStream.RECEIVE_WINDOW,
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
