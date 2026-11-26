package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Pure-Java userspace Companion Network Relay ("Shoes") proxy over the
 * authenticated Class-D and Class-C IPv6/ESP tunnel.
 *
 * <p>Because this proxy terminates Watch TCP flows directly on
 * {@link Ipv6TcpStream} and opens standard outbound Java TCP sockets, it works
 * identically on Android, Linux, and Windows without requiring an OS TUN
 * interface. Only connection envelope metadata (service, host, port, byte
 * counts) is logged; relayed payload bytes are never logged.</p>
 */
final class WatchInternetRelaySession implements AutoCloseable {
    static final int MAX_CONCURRENT_FLOWS = 16;
    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final long IDLE_TIMEOUT_MS = 120_000L;
    static final long RETRANSMIT_INTERVAL_MS = 1_500L;
    static final int MAX_HEADER_BUFFER_BYTES = 2 + ShoesProxyCodec.MAX_HEADER_LENGTH + 4096;
    static final int MAX_PENDING_UPSTREAM_BYTES = 64 * 1024;
    static final int MAX_UPSTREAM_READ_QUEUE_BYTES = 128 * 1024;
    static final int MAX_TCP_IN_FLIGHT_BYTES = 32 * 1024;
    private static final int MAX_TO_UPSTREAM_CHUNKS = 64;
    private static final byte[] EOF_SENTINEL = new byte[0];

    @FunctionalInterface
    interface UpstreamDialer {
        Socket connect(String host, int port, int timeoutMs) throws IOException;
    }

    static final UpstreamDialer PUBLIC_DIALER = (host, port, timeoutMs) -> {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        IOException lastError = null;
        boolean attempted = false;
        for (InetAddress address : addresses) {
            if (!ShoesProxyCodec.isSafePublicTarget(address)) {
                continue;
            }
            attempted = true;
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), timeoutMs);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException error) {
                lastError = error;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (!attempted) {
            throw new IOException("Blocked non-public relay target");
        }
        throw lastError != null ? lastError : new IOException("Unable to connect relay target");
    };

    private final byte[] localClassD;
    private final byte[] remoteClassD;
    private final byte[] localClassC;
    private final byte[] remoteClassC;
    private final int proxyPort;
    private final SecureRandom random;
    private final UpstreamDialer dialer;
    private final Consumer<String> log;
    private final ExecutorService workers;
    private final Map<FlowKey, RelayFlow> flows = new LinkedHashMap<>();
    private boolean closed;

    static WatchInternetRelaySession create(NormalLinkPipeSession link, Consumer<String> log) {
        if (link == null || log == null) {
            throw new IllegalArgumentException("NormalLinkPipeSession and logger are required");
        }
        byte[] localD = link.localClassD();
        byte[] remoteD = link.remoteClassD();
        byte[] localC = link.localClassC();
        byte[] remoteC = link.remoteClassC();
        try {
            return new WatchInternetRelaySession(
                    localD, remoteD, localC, remoteC,
                    ShoesProxyCodec.PROXY_PORT, new SecureRandom(), PUBLIC_DIALER, log);
        } finally {
            wipe(localD);
            wipe(remoteD);
            wipe(localC);
            wipe(remoteC);
        }
    }

    WatchInternetRelaySession(
            byte[] localClassD,
            byte[] remoteClassD,
            byte[] localClassC,
            byte[] remoteClassC,
            int proxyPort,
            SecureRandom random,
            UpstreamDialer dialer,
            Consumer<String> log) {
        this.localClassD = localClassD.clone();
        this.remoteClassD = remoteClassD.clone();
        this.localClassC = localClassC.clone();
        this.remoteClassC = remoteClassC.clone();
        this.proxyPort = proxyPort;
        this.random = random;
        this.dialer = dialer;
        this.log = log;
        this.workers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "watch-internet-relay");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized boolean hasActiveFlows() {
        return !closed && !flows.isEmpty();
    }

    synchronized int activeFlowCount() {
        return closed ? 0 : flows.size();
    }

    synchronized boolean accept(NormalLinkPipeSession.DeliveredIp delivered) {
        if (closed || delivered == null || !delivered.protectedByEsp
                || delivered.dataClass == null || delivered.packet == null) {
            return false;
        }
        if (delivered.packet.length < Ipv6TcpPacketCodec.IPV6_HEADER_LENGTH
                + Ipv6TcpPacketCodec.TCP_MINIMUM_HEADER_LENGTH
                || (delivered.packet[0] & 0xf0) != 0x60
                || (delivered.packet[6] & 0xff) != Ipv6TcpPacketCodec.NEXT_HEADER_TCP) {
            return false;
        }
        byte[] expectedLocal = delivered.dataClass == OrdinaryIkeAuth.DataClass.CLASS_D
                ? localClassD : localClassC;
        byte[] expectedRemote = delivered.dataClass == OrdinaryIkeAuth.DataClass.CLASS_D
                ? remoteClassD : remoteClassC;

        Ipv6TcpPacketCodec.Packet packet;
        try {
            packet = Ipv6TcpPacketCodec.decode(delivered.packet);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        try {
            if (packet.destinationPort != proxyPort
                    || !Arrays.equals(packet.destinationAddress, expectedLocal)
                    || !Arrays.equals(packet.sourceAddress, expectedRemote)) {
                return false;
            }
            FlowKey key = new FlowKey(delivered.dataClass, packet.sourcePort);
            long nowMs = System.nanoTime() / 1_000_000L;
            boolean plainSyn = packet.hasFlag(Ipv6TcpPacketCodec.FLAG_SYN)
                    && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_ACK)
                    && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_RST)
                    && !packet.hasFlag(Ipv6TcpPacketCodec.FLAG_FIN);

            RelayFlow existing = flows.get(key);
            if (plainSyn) {
                if (existing != null) {
                    if (existing.stream.state() == Ipv6TcpStream.State.SYN_RECEIVED) {
                        acceptIntoFlow(key, existing, delivered.packet, nowMs);
                        return true;
                    }
                    existing.closeNow();
                    flows.remove(key);
                }
                if (flows.size() >= MAX_CONCURRENT_FLOWS) {
                    pruneCompletedFlowsLocked(nowMs);
                }
                if (flows.size() >= MAX_CONCURRENT_FLOWS) {
                    logSafe("INTERNET RELAY: flow table full (" + MAX_CONCURRENT_FLOWS
                            + "); rejecting new flow from port=" + packet.sourcePort + ".");
                    return true;
                }
                long isn = Integer.toUnsignedLong(random.nextInt());
                try (Ipv6TcpStream.PassiveOpen open = Ipv6TcpStream.acceptPassive(
                        delivered.packet, expectedLocal, expectedRemote, isn)) {
                    RelayFlow flow = new RelayFlow(
                            delivered.dataClass, expectedLocal, expectedRemote,
                            proxyPort, packet.sourcePort, open.takeStream(), nowMs);
                    flow.outboundIpv6.addLast(open.synAcknowledgement());
                    flows.put(key, flow);
                } catch (IllegalArgumentException invalidSyn) {
                    logSafe("INTERNET RELAY: ignored invalid SYN on port=" + packet.sourcePort + ".");
                }
                return true;
            }

            if (existing == null) {
                return true;
            }
            acceptIntoFlow(key, existing, delivered.packet, nowMs);
            return true;
        } finally {
            packet.close();
        }
    }

    private void acceptIntoFlow(FlowKey key, RelayFlow flow, byte[] rawIpv6, long nowMs) {
        flow.lastActivityMs = nowMs;
        if (flow.stream.state() == Ipv6TcpStream.State.CLOSED
                || flow.stream.state() == Ipv6TcpStream.State.RESET
                || flow.stream.state() == Ipv6TcpStream.State.TIME_WAIT) {
            return;
        }
        try (Ipv6TcpStream.InboundResult result = flow.stream.accept(rawIpv6)) {
            for (byte[] out : result.outboundPackets()) {
                flow.outboundIpv6.addLast(out);
            }
            for (byte[] chunk : result.deliveredBytes()) {
                try {
                    handleWatchBytes(flow, chunk);
                } finally {
                    wipe(chunk);
                }
            }
            if (result.state == Ipv6TcpStream.State.CLOSE_WAIT && !flow.watchEofObserved) {
                flow.watchEofObserved = true;
                if (flow.phase == FlowPhase.HEADER) {
                    flow.failUpstream("header_eof", null);
                } else if (flow.phase == FlowPhase.RELAYING) {
                    flow.enqueueToUpstream(EOF_SENTINEL);
                }
            } else if (result.state == Ipv6TcpStream.State.RESET
                    || result.state == Ipv6TcpStream.State.CLOSED
                    || result.state == Ipv6TcpStream.State.TIME_WAIT) {
                flow.closeUpstreamQuietly();
            }
        } catch (RuntimeException tcpError) {
            flow.failUpstream("tcp", tcpError);
        }
    }

    private void handleWatchBytes(RelayFlow flow, byte[] chunk) {
        if (chunk.length == 0 || flow.failed) {
            return;
        }
        if (flow.phase == FlowPhase.HEADER) {
            if (flow.headerBuffer.size() + chunk.length > MAX_HEADER_BUFFER_BYTES) {
                flow.failUpstream("header_limit", null);
                return;
            }
            flow.headerBuffer.writeBytes(chunk);
            ShoesProxyCodec.ParseResult parsed = ShoesProxyCodec.parse(flow.headerBuffer.toByteArray());
            if (parsed.status == ShoesProxyCodec.ParseStatus.NEED_MORE) {
                return;
            }
            if (parsed.status == ShoesProxyCodec.ParseStatus.INVALID || parsed.header == null) {
                flow.failUpstream("header_invalid", null);
                return;
            }
            try (ShoesProxyCodec.Header header = parsed.header) {
                flow.host = header.host;
                flow.port = header.port;
                flow.service = header.service;
                flow.phase = FlowPhase.CONNECTING;
                flow.headerBuffer.reset();
                byte[] trailing = header.trailingBytes();
                try {
                    if (trailing.length > 0) {
                        flow.pendingBeforeConnect.writeBytes(trailing);
                    }
                } finally {
                    wipe(trailing);
                }
            }
            logSafe("INTERNET RELAY FLOW: service=" + flow.service
                    + " host=" + flow.host + " port=" + flow.port
                    + " class=" + flow.dataClass + "; payload logged=false.");
            startUpstreamConnect(flow);
            return;
        }
        if (flow.phase == FlowPhase.CONNECTING) {
            if (flow.pendingBeforeConnect.size() + chunk.length > MAX_PENDING_UPSTREAM_BYTES) {
                flow.failUpstream("connect_buffer_limit", null);
                return;
            }
            flow.pendingBeforeConnect.writeBytes(chunk);
            return;
        }
        if (flow.phase == FlowPhase.RELAYING) {
            flow.bytesWatchToUpstream += chunk.length;
            if (!flow.enqueueToUpstream(chunk.clone())) {
                flow.failUpstream("upstream_backpressure", null);
            }
        }
    }

    private void startUpstreamConnect(RelayFlow flow) {
        if (closed) {
            flow.failUpstream("session_closed", null);
            return;
        }
        String host = flow.host;
        int port = flow.port;
        String service = flow.service;
        workers.submit(() -> {
            Socket socket = null;
            try {
                socket = dialer.connect(host, port, CONNECT_TIMEOUT_MS);
                byte[] initialData;
                boolean eofAlready;
                synchronized (WatchInternetRelaySession.this) {
                    if (closed || flow.closed || flow.failed) {
                        closeSocketQuietly(socket);
                        return;
                    }
                    flow.upstreamSocket = socket;
                    flow.phase = FlowPhase.RELAYING;
                    flow.flowOpenPending = true;
                    initialData = flow.pendingBeforeConnect.toByteArray();
                    flow.pendingBeforeConnect.reset();
                    eofAlready = flow.watchEofObserved;
                    flow.bytesWatchToUpstream += initialData.length;
                }
                logSafe("INTERNET RELAY CONNECTED: service=" + service
                        + " host=" + host + " port=" + port + ".");
                if (initialData.length > 0) {
                    flow.enqueueToUpstream(initialData);
                }
                if (eofAlready) {
                    flow.enqueueToUpstream(EOF_SENTINEL);
                }
                Socket active = socket;
                workers.submit(() -> runUpstreamWriter(flow, active));
                runUpstreamReader(flow, active);
            } catch (IOException | RuntimeException error) {
                closeSocketQuietly(socket);
                synchronized (WatchInternetRelaySession.this) {
                    flow.failUpstream("connect", error);
                }
            }
        });
    }

    private void runUpstreamWriter(RelayFlow flow, Socket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            while (!closed && !flow.closed && !flow.failed) {
                byte[] chunk = flow.toUpstream.poll(250, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    continue;
                }
                if (chunk == EOF_SENTINEL) {
                    try {
                        socket.shutdownOutput();
                    } catch (IOException ignored) {
                    }
                    return;
                }
                try {
                    out.write(chunk);
                    out.flush();
                } finally {
                    wipe(chunk);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            synchronized (WatchInternetRelaySession.this) {
                flow.failUpstream("upstream_write", error);
            }
        }
    }

    private void runUpstreamReader(RelayFlow flow, Socket socket) {
        byte[] buffer = new byte[4096];
        try {
            InputStream in = socket.getInputStream();
            while (!closed && !flow.closed && !flow.failed) {
                while (flow.fromUpstreamBytes.get() >= MAX_UPSTREAM_READ_QUEUE_BYTES) {
                    if (closed || flow.closed || flow.failed) {
                        return;
                    }
                    Thread.sleep(15L);
                }
                int read = in.read(buffer);
                if (read < 0) {
                    synchronized (WatchInternetRelaySession.this) {
                        flow.upstreamEof = true;
                        flow.lastActivityMs = System.nanoTime() / 1_000_000L;
                    }
                    return;
                }
                if (read > 0) {
                    byte[] copy = Arrays.copyOf(buffer, read);
                    flow.fromUpstream.addLast(copy);
                    flow.fromUpstreamBytes.addAndGet(read);
                    synchronized (WatchInternetRelaySession.this) {
                        flow.bytesUpstreamToWatch += read;
                        flow.lastActivityMs = System.nanoTime() / 1_000_000L;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            synchronized (WatchInternetRelaySession.this) {
                if (!flow.closed && !flow.localFinSent) {
                    flow.failUpstream("upstream_read", error);
                }
            }
        } finally {
            wipe(buffer);
        }
    }

    @FunctionalInterface
    interface PacketSink {
        boolean send(OrdinaryIkeAuth.DataClass dataClass, byte[] clearIpv6Packet);
    }

    synchronized List<byte[]> poll(NormalLinkPipeSession link) {
        if (closed || link == null) {
            return List.of();
        }
        List<byte[]> ertmFrames = new ArrayList<>();
        pollPackets((dataClass, clearIpv6) -> {
            byte[] frame = link.sendIpv6(dataClass, clearIpv6);
            if (frame == null) {
                return false;
            }
            ertmFrames.add(frame);
            return true;
        });
        return ertmFrames;
    }

    synchronized void pollPackets(PacketSink sink) {
        if (closed || sink == null) {
            return;
        }
        long nowMs = System.nanoTime() / 1_000_000L;
        Iterator<Map.Entry<FlowKey, RelayFlow>> iterator = flows.entrySet().iterator();
        while (iterator.hasNext()) {
            RelayFlow flow = iterator.next().getValue();
            advanceFlow(flow, nowMs);
            drainOutboundIntoSink(flow, sink);
            if (shouldRemoveFlow(flow, nowMs)) {
                if (!flow.failed && flow.host != null) {
                    logSafe("INTERNET RELAY COMPLETE: service=" + flow.service
                            + " host=" + flow.host + " port=" + flow.port
                            + " txBytes=" + flow.bytesWatchToUpstream
                            + " rxBytes=" + flow.bytesUpstreamToWatch + ".");
                }
                flow.closeNow();
                iterator.remove();
            }
        }
    }

    private void advanceFlow(RelayFlow flow, long nowMs) {
        if (flow.failed) {
            if (!flow.rstSent) {
                flow.rstSent = true;
                flow.outboundIpv6.clear();
                byte[] rst = Ipv6TcpPacketCodec.encode(
                        flow.localAddress,
                        flow.remoteAddress,
                        flow.localPort,
                        flow.remotePort,
                        flow.stream.sendNext(),
                        flow.stream.receiveNext(),
                        Ipv6TcpPacketCodec.FLAG_RST | Ipv6TcpPacketCodec.FLAG_ACK,
                        0,
                        0,
                        new byte[0],
                        new byte[0]);
                flow.outboundIpv6.addLast(rst);
                logSafe("INTERNET RELAY FAILED: phase=" + flow.failPhase
                        + (flow.service != null ? " service=" + flow.service : "")
                        + (flow.host != null ? " host=" + flow.host + " port=" + flow.port : "")
                        + (flow.failError != null ? " error=" + flow.failError : "") + ".");
            }
            return;
        }
        Ipv6TcpStream.State state = flow.stream.state();
        if (flow.flowOpenPending
                && (state == Ipv6TcpStream.State.ESTABLISHED || state == Ipv6TcpStream.State.CLOSE_WAIT)) {
            flow.flowOpenPending = false;
            flow.flowOpenSent = true;
            byte[] open = ShoesProxyCodec.flowOpenBytes();
            try {
                for (byte[] pkt : flow.stream.send(open)) {
                    flow.outboundIpv6.addLast(pkt);
                }
            } finally {
                wipe(open);
            }
            flow.lastActivityMs = nowMs;
        }
        if (flow.flowOpenSent
                && (state == Ipv6TcpStream.State.ESTABLISHED || state == Ipv6TcpStream.State.CLOSE_WAIT)) {
            while (!flow.fromUpstream.isEmpty()
                    && flow.stream.unacknowledgedSendBytes() < MAX_TCP_IN_FLIGHT_BYTES) {
                byte[] chunk = flow.fromUpstream.pollFirst();
                if (chunk == null) {
                    break;
                }
                flow.fromUpstreamBytes.addAndGet(-chunk.length);
                try {
                    for (byte[] pkt : flow.stream.send(chunk)) {
                        flow.outboundIpv6.addLast(pkt);
                    }
                } finally {
                    wipe(chunk);
                }
                flow.lastActivityMs = nowMs;
            }
            if (flow.upstreamEof && flow.fromUpstream.isEmpty() && !flow.localFinSent
                    && !flow.stream.hasPendingSend()) {
                flow.localFinSent = true;
                flow.outboundIpv6.addLast(flow.stream.closeOutput());
                flow.lastActivityMs = nowMs;
            }
        }
        if (flow.outboundIpv6.isEmpty()
                && flow.stream.unacknowledgedSendBytes() > 0
                && nowMs - flow.lastRetransmitMs >= RETRANSMIT_INTERVAL_MS) {
            flow.lastRetransmitMs = nowMs;
            for (byte[] pkt : flow.stream.retransmitOutstanding()) {
                flow.outboundIpv6.addLast(pkt);
            }
        }
    }

    private void drainOutboundIntoSink(RelayFlow flow, PacketSink sink) {
        while (!flow.outboundIpv6.isEmpty()) {
            byte[] next = flow.outboundIpv6.peekFirst();
            if (!sink.send(flow.dataClass, next)) {
                return;
            }
            wipe(flow.outboundIpv6.removeFirst());
        }
    }

    private boolean shouldRemoveFlow(RelayFlow flow, long nowMs) {
        if (!flow.outboundIpv6.isEmpty()) {
            return false;
        }
        if (flow.rstSent) {
            return true;
        }
        Ipv6TcpStream.State state = flow.stream.state();
        if (state == Ipv6TcpStream.State.CLOSED
                || state == Ipv6TcpStream.State.RESET
                || state == Ipv6TcpStream.State.TIME_WAIT) {
            return true;
        }
        return nowMs - flow.lastActivityMs > IDLE_TIMEOUT_MS;
    }

    private void pruneCompletedFlowsLocked(long nowMs) {
        Iterator<Map.Entry<FlowKey, RelayFlow>> iterator = flows.entrySet().iterator();
        while (iterator.hasNext()) {
            RelayFlow flow = iterator.next().getValue();
            if (shouldRemoveFlow(flow, nowMs)) {
                flow.closeNow();
                iterator.remove();
            }
        }
    }

    private void logSafe(String message) {
        try {
            log.accept(message);
        } catch (RuntimeException ignored) {
        }
    }

    private static void closeSocketQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (RelayFlow flow : flows.values()) {
            flow.closeNow();
        }
        flows.clear();
        workers.shutdownNow();
        wipe(localClassD);
        wipe(remoteClassD);
        wipe(localClassC);
        wipe(remoteClassC);
    }

    private enum FlowPhase {
        HEADER,
        CONNECTING,
        RELAYING
    }

    private static final class FlowKey {
        final OrdinaryIkeAuth.DataClass dataClass;
        final int remotePort;

        FlowKey(OrdinaryIkeAuth.DataClass dataClass, int remotePort) {
            this.dataClass = dataClass;
            this.remotePort = remotePort;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FlowKey that)) return false;
            return remotePort == that.remotePort && dataClass == that.dataClass;
        }

        @Override
        public int hashCode() {
            return Objects.hash(dataClass, remotePort);
        }
    }

    private static final class RelayFlow {
        final OrdinaryIkeAuth.DataClass dataClass;
        final byte[] localAddress;
        final byte[] remoteAddress;
        final int localPort;
        final int remotePort;
        final Ipv6TcpStream stream;
        final ArrayDeque<byte[]> outboundIpv6 = new ArrayDeque<>();
        final ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        final ByteArrayOutputStream pendingBeforeConnect = new ByteArrayOutputStream();
        final LinkedBlockingQueue<byte[]> toUpstream = new LinkedBlockingQueue<>(MAX_TO_UPSTREAM_CHUNKS);
        final ConcurrentLinkedDeque<byte[]> fromUpstream = new ConcurrentLinkedDeque<>();
        final AtomicInteger fromUpstreamBytes = new AtomicInteger();

        FlowPhase phase = FlowPhase.HEADER;
        String host;
        int port;
        String service;
        volatile Socket upstreamSocket;
        boolean flowOpenPending;
        boolean flowOpenSent;
        boolean watchEofObserved;
        volatile boolean upstreamEof;
        boolean localFinSent;
        volatile boolean failed;
        boolean rstSent;
        String failPhase;
        String failError;
        long bytesWatchToUpstream;
        long bytesUpstreamToWatch;
        long lastActivityMs;
        long lastRetransmitMs;
        volatile boolean closed;

        RelayFlow(
                OrdinaryIkeAuth.DataClass dataClass,
                byte[] localAddress,
                byte[] remoteAddress,
                int localPort,
                int remotePort,
                Ipv6TcpStream stream,
                long nowMs) {
            this.dataClass = dataClass;
            this.localAddress = localAddress.clone();
            this.remoteAddress = remoteAddress.clone();
            this.localPort = localPort;
            this.remotePort = remotePort;
            this.stream = stream;
            this.lastActivityMs = nowMs;
            this.lastRetransmitMs = nowMs;
        }

        boolean enqueueToUpstream(byte[] chunk) {
            if (closed || failed) {
                if (chunk != EOF_SENTINEL) wipe(chunk);
                return false;
            }
            if (!toUpstream.offer(chunk)) {
                if (chunk != EOF_SENTINEL) wipe(chunk);
                return false;
            }
            return true;
        }

        void failUpstream(String phaseName, Throwable error) {
            if (failed) {
                return;
            }
            failed = true;
            failPhase = phaseName;
            failError = error == null ? null : error.getClass().getSimpleName();
            closeUpstreamQuietly();
        }

        void closeUpstreamQuietly() {
            Socket socket = upstreamSocket;
            upstreamSocket = null;
            closeSocketQuietly(socket);
        }

        void closeNow() {
            if (closed) {
                return;
            }
            closed = true;
            closeUpstreamQuietly();
            while (!outboundIpv6.isEmpty()) {
                wipe(outboundIpv6.removeFirst());
            }
            byte[] queued;
            while ((queued = toUpstream.poll()) != null) {
                if (queued != EOF_SENTINEL) wipe(queued);
            }
            while ((queued = fromUpstream.pollFirst()) != null) {
                wipe(queued);
            }
            fromUpstreamBytes.set(0);
            headerBuffer.reset();
            pendingBeforeConnect.reset();
            stream.close();
            wipe(localAddress);
            wipe(remoteAddress);
        }
    }
}
