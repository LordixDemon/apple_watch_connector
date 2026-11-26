package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BluetoothLinkMaintenance;
import dev.applewatchandroid.bridge.BluetoothSmpCodec;
import dev.applewatchandroid.bridge.BridgeClock;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Stateful per-connection L2CAP demultiplexer wrapping {@link HciTransport} and
 * {@link BridgeCoreCodecs.AclReassemblerHandle}.
 *
 * <p>Routes inbound ATT PDUs cleanly between local ATT client responses and Watch-initiated ATT
 * server requests, answers LE signaling PDUs immediately, tracks link disconnection, and maintains
 * bounded deferred queues whose secret-bearing payloads are zeroized on {@link #clear()} /
 * {@link #close()}.</p>
 */
public final class L2capChannelMux implements AutoCloseable {
    static final int MAX_FIXED_QUEUE_DEPTH = 16;
    static final int MAX_DYNAMIC_QUEUE_DEPTH = 64;
    private static final int MAX_DYNAMIC_CIDS = 32;

    private final HciTransport transport;
    private final BridgeCoreCodecs.AclReassemblerHandle reassembler;
    private final Object queueLock = new Object();
    private final Object sendLock = new Object();

    final ArrayDeque<byte[]> deferredSmpPdus = new ArrayDeque<>(MAX_FIXED_QUEUE_DEPTH);
    final ArrayDeque<byte[]> deferredAttClientPdus = new ArrayDeque<>(MAX_FIXED_QUEUE_DEPTH);
    final ArrayDeque<byte[]> deferredBtClPdus = new ArrayDeque<>(MAX_FIXED_QUEUE_DEPTH);
    final Map<Integer, ArrayDeque<byte[]>> dynamicCidQueues = new HashMap<>();

    private volatile boolean disconnected;
    private volatile boolean closed;

    public L2capChannelMux(HciTransport transport) {
        this(transport, new BridgeCoreCodecs.AclReassemblerHandle());
    }

    public L2capChannelMux(HciTransport transport, BridgeCoreCodecs.AclReassemblerHandle reassembler) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.reassembler = Objects.requireNonNull(reassembler, "reassembler");
    }

    public HciTransport transport() {
        return transport;
    }

    public boolean wasDisconnected() {
        return disconnected;
    }

    public void sendL2cap(int cid, byte[] payload, String label) throws Exception {
        transport.checkStop();
        ensureUsable("send " + label);
        synchronized (sendLock) {
            transport.sendL2cap(cid, payload, label);
        }
    }

    public byte[] awaitAttClientResponse(long timeoutMs, String label) throws Exception {
        byte[] pdu = pollAttClientResponse(timeoutMs, label);
        if (pdu == null) {
            throw new HostException(String.format(
                    Locale.US, "Timed out waiting for %s after %d ms", label, timeoutMs));
        }
        return pdu;
    }

    public byte[] pollAttClientResponse(long timeoutMs, String label) throws Exception {
        return pollFixedQueue(deferredAttClientPdus, timeoutMs, label);
    }

    public byte[] awaitSmpPdu(
            int expectedOpcode,
            long timeoutMs,
            String label,
            boolean tolerateSecurityRequest) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        int securityRequests = 0;
        while (true) {
            transport.checkStop();
            ensureUsable("await " + label);
            byte[] payload = pollFromQueue(deferredSmpPdus);
            if (payload == null) {
                if (BridgeClock.elapsedRealtime() >= deadline || !pumpOnePacket(deadline, label)) {
                    payload = pollFromQueue(deferredSmpPdus);
                    if (payload == null) {
                        break;
                    }
                } else {
                    continue;
                }
            }
            try {
                int opcode = parseSmpOpcode(payload, label);
                if (opcode == BluetoothSmpCodec.PAIRING_FAILED) {
                    int reason = parseSmpFailureReason(payload, label);
                    throw new HostException(String.format(
                            Locale.US,
                            "Watch returned SMP Pairing Failed reason=0x%02X (%s) while awaiting %s",
                            reason,
                            BluetoothSmpCodec.pairingFailureReasonName(reason),
                            label));
                }
                if (opcode == BluetoothSmpCodec.SECURITY_REQUEST && tolerateSecurityRequest) {
                    int auth = parseSmpSecurityRequest(payload);
                    if (++securityRequests > 32) {
                        throw new HostException("Too many SMP Security Requests while awaiting " + label);
                    }
                    transport.log(String.format(
                            Locale.US,
                            "SMP SECURITY REQUEST RX: auth=0x%02X count=%d; deadline unchanged.",
                            auth,
                            securityRequests));
                    continue;
                }
                if (opcode != expectedOpcode) {
                    throw new HostException(String.format(
                            Locale.US,
                            "Unexpected SMP opcode=0x%02X while awaiting %s",
                            opcode,
                            label));
                }
                byte[] result = payload.clone();
                return result;
            } finally {
                wipe(payload);
            }
        }
        throw new HostException(String.format(
                Locale.US,
                "%s timed out after %d ms; securityRequests=%d",
                label,
                timeoutMs,
                securityRequests));
    }

    public byte[] awaitBtClPdu(long timeoutMs, String label) throws Exception {
        byte[] pdu = pollBtClPdu(timeoutMs, label);
        if (pdu == null) {
            throw new HostException(String.format(
                    Locale.US, "Timed out waiting for %s after %d ms", label, timeoutMs));
        }
        return pdu;
    }

    public byte[] pollBtClPdu(long timeoutMs, String label) throws Exception {
        return pollFixedQueue(deferredBtClPdus, timeoutMs, label);
    }

    public byte[] awaitDynamicCidPdu(int localCid, long timeoutMs, String label) throws Exception {
        byte[] pdu = pollDynamicCidPdu(localCid, timeoutMs, label);
        if (pdu == null) {
            throw new HostException(String.format(
                    Locale.US, "Timed out waiting for %s on CID 0x%04X after %d ms", label, localCid, timeoutMs));
        }
        return pdu;
    }

    public byte[] pollDynamicCidPdu(int localCid, long timeoutMs, String label) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (true) {
            transport.checkStop();
            ensureUsable("await " + label);
            byte[] buffered = pollDynamicQueue(localCid);
            if (buffered != null) {
                return buffered;
            }
            if (BridgeClock.elapsedRealtime() >= deadline || !pumpOnePacket(deadline, label)) {
                return pollDynamicQueue(localCid);
            }
        }
    }

    public void clear() {
        synchronized (queueLock) {
            drainAndWipe(deferredSmpPdus);
            drainAndWipe(deferredAttClientPdus);
            drainAndWipe(deferredBtClPdus);
            for (ArrayDeque<byte[]> queue : dynamicCidQueues.values()) {
                drainAndWipe(queue);
            }
            dynamicCidQueues.clear();
            reassembler.reset();
        }
    }

    @Override
    public void close() {
        closed = true;
        clear();
    }

    private byte[] pollFixedQueue(ArrayDeque<byte[]> queue, long timeoutMs, String label) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (true) {
            transport.checkStop();
            ensureUsable("await " + label);
            byte[] buffered = pollFromQueue(queue);
            if (buffered != null) {
                return buffered;
            }
            if (BridgeClock.elapsedRealtime() >= deadline || !pumpOnePacket(deadline, label)) {
                return pollFromQueue(queue);
            }
        }
    }

    private boolean pumpOnePacket(long deadline, String label) throws Exception {
        HciTransport.Packet packet;
        try {
            packet = transport.nextPacket(deadline);
        } catch (TimeoutException elapsed) {
            return false;
        }
        if (packet == null) {
            return false;
        }
        if (packet.kind() == HciTransport.PacketKind.EVENT) {
            handleHciEvent(packet.bytes(), label);
            return true;
        }
        if (packet.kind() == HciTransport.PacketKind.ACL) {
            handleAclPacket(packet.bytes());
        }
        return true;
    }

    private void handleHciEvent(byte[] eventBytes, String label) throws HostException {
        BridgeCoreCodecs.DisconnectEvent disconnect = BridgeCoreCodecs.parseDisconnectionComplete(eventBytes);
        if (disconnect != null && disconnect.connectionHandle() == transport.connectionHandle()) {
            disconnected = true;
            clear();
            throw new HostException(String.format(
                    Locale.US,
                    "Watch disconnected during %s: status=0x%02X reason=0x%02X",
                    label,
                    disconnect.status(),
                    disconnect.reason()));
        }
    }

    private void handleAclPacket(byte[] aclBytes) throws Exception {
        BridgeCoreCodecs.L2capFrame frame;
        try {
            synchronized (queueLock) {
                frame = reassembler.accept(aclBytes);
            }
        } catch (IllegalArgumentException malformed) {
            transport.log("ACL DROP: " + malformed.getMessage());
            return;
        }
        if (frame == null) {
            return;
        }
        byte[] payload = frame.payload();
        if (frame.connectionHandle() != transport.connectionHandle()) {
            wipe(payload);
            return;
        }
        int cid = frame.destinationCid();
        if (cid == BridgeCoreCodecs.ATT_FIXED_CID) {
            routeAttPdu(payload);
        } else if (cid == BridgeCoreCodecs.LE_SIGNALING_CID) {
            routeLeSignalingPdu(payload);
        } else if (cid == BridgeCoreCodecs.SMP_FIXED_CID) {
            offerFixedQueue(deferredSmpPdus, payload, "SMP deferred queue");
        } else if (cid == BridgeCoreCodecs.BT_CL_SIGNALING_CID) {
            offerFixedQueue(deferredBtClPdus, payload, "BT_CL deferred queue");
        } else if (cid >= 0x0040) {
            offerDynamicQueue(cid, payload);
        } else {
            transport.log(String.format(Locale.US, "L2CAP DROP: unsupported fixed CID 0x%04X", cid));
            wipe(payload);
        }
    }

    private void routeAttPdu(byte[] payload) throws Exception {
        if (payload.length > 0 && BluetoothLinkMaintenance.isAttClientResponseOpcode(payload[0] & 0xFF)) {
            offerFixedQueue(deferredAttClientPdus, payload, "ATT client response queue");
            return;
        }
        try {
            byte[] reply = transport.handlePeerAttRequest(payload);
            if (reply != null && reply.length > 0) {
                try {
                    sendL2cap(BridgeCoreCodecs.ATT_FIXED_CID, reply, "ATT PEER REQUEST RSP");
                } finally {
                    wipe(reply);
                }
            }
        } finally {
            wipe(payload);
        }
    }

    private void routeLeSignalingPdu(byte[] payload) throws Exception {
        try {
            List<byte[]> replies = BluetoothLinkMaintenance.leSignalingReplies(payload);
            for (byte[] reply : replies) {
                try {
                    sendL2cap(BridgeCoreCodecs.LE_SIGNALING_CID, reply, "LE SIGNALING REPLY");
                } finally {
                    wipe(reply);
                }
            }
        } finally {
            wipe(payload);
        }
    }

    private void offerFixedQueue(ArrayDeque<byte[]> queue, byte[] payload, String queueName) throws HostException {
        synchronized (queueLock) {
            if (closed || disconnected) {
                wipe(payload);
                return;
            }
            if (queue.size() >= MAX_FIXED_QUEUE_DEPTH) {
                wipe(payload);
                clear();
                throw new HostException(queueName + " overflow (max=" + MAX_FIXED_QUEUE_DEPTH + ")");
            }
            queue.addLast(payload);
        }
    }

    private void offerDynamicQueue(int cid, byte[] payload) throws HostException {
        synchronized (queueLock) {
            if (closed || disconnected) {
                wipe(payload);
                return;
            }
            ArrayDeque<byte[]> queue = dynamicCidQueues.get(cid);
            if (queue == null) {
                if (dynamicCidQueues.size() >= MAX_DYNAMIC_CIDS) {
                    wipe(payload);
                    clear();
                    throw new HostException("Too many active dynamic L2CAP CIDs");
                }
                queue = new ArrayDeque<>(MAX_DYNAMIC_QUEUE_DEPTH);
                dynamicCidQueues.put(cid, queue);
            }
            if (queue.size() >= MAX_DYNAMIC_QUEUE_DEPTH) {
                wipe(payload);
                clear();
                throw new HostException(String.format(
                        Locale.US,
                        "Dynamic CID 0x%04X queue overflow (max=%d)",
                        cid,
                        MAX_DYNAMIC_QUEUE_DEPTH));
            }
            queue.addLast(payload);
        }
    }

    private byte[] pollFromQueue(ArrayDeque<byte[]> queue) {
        synchronized (queueLock) {
            return queue.pollFirst();
        }
    }

    private byte[] pollDynamicQueue(int cid) {
        synchronized (queueLock) {
            ArrayDeque<byte[]> queue = dynamicCidQueues.get(cid);
            if (queue == null) {
                return null;
            }
            byte[] pdu = queue.pollFirst();
            if (queue.isEmpty()) {
                dynamicCidQueues.remove(cid);
            }
            return pdu;
        }
    }

    private void ensureUsable(String action) throws HostException {
        if (disconnected) {
            throw new HostException("Link is disconnected; cannot " + action);
        }
        if (closed) {
            throw new HostException("L2CAP mux is closed; cannot " + action);
        }
    }

    private static int parseSmpOpcode(byte[] payload, String label) throws HostException {
        try {
            return BluetoothSmpCodec.opcode(payload);
        } catch (IllegalArgumentException invalid) {
            throw new HostException("Malformed SMP PDU while awaiting " + label, invalid);
        }
    }

    private static int parseSmpFailureReason(byte[] payload, String label) throws HostException {
        try {
            return BluetoothSmpCodec.parsePairingFailure(payload);
        } catch (IllegalArgumentException invalid) {
            throw new HostException("Malformed SMP Pairing Failed PDU while awaiting " + label, invalid);
        }
    }

    private static int parseSmpSecurityRequest(byte[] payload) throws HostException {
        try {
            return BluetoothSmpCodec.parseSecurityRequest(payload);
        } catch (IllegalArgumentException invalid) {
            throw new HostException("Malformed SMP Security Request", invalid);
        }
    }

    private static void drainAndWipe(ArrayDeque<byte[]> queue) {
        byte[] item;
        while ((item = queue.pollFirst()) != null) {
            wipe(item);
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
