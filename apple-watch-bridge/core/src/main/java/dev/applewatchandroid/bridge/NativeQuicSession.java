package dev.applewatchandroid.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Exact discovery-authorized UDP tuple. Rust owns TLS/QUIC; HAL owns ESP/ERTM. */
final class NativeQuicSession implements AutoCloseable {
    interface Backend extends AutoCloseable {
        void receive(byte[] data);
        byte[][] poll();
        String state();
        default void send(byte[] data) { throw new IllegalStateException("QUIC application send unavailable"); }
        default byte[][] read() { return new byte[0][]; }
        @Override void close();
    }
    private final Backend backend;
    private final OrdinaryIkeAuth.DataClass dataClass;
    private final byte[] local, remote;
    private final int localPort, remotePort;
    private final Consumer<String> log;
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>(4);
    private String lastState = "";
    private boolean closed;
    private ReplicatorReadSession application;
    private ReplicatorStreamReader streamReader;
    private boolean handshakeSent;

    NativeQuicSession(Backend backend, OrdinaryIkeAuth.DataClass dataClass, byte[] local, byte[] remote,
            int localPort, int remotePort, Consumer<String> log) {
        this.backend = backend; this.dataClass = dataClass;
        this.local = local.clone(); this.remote = remote.clone();
        this.localPort = localPort; this.remotePort = remotePort; this.log = log;
    }

    static NativeQuicSession create(NativeApplicationServiceDiscovery discovery,
            NativeApplicationServiceDiscovery.Endpoint endpoint, NormalLinkPipeSession link,
            String localDeviceId, Consumer<String> log) {
        byte[] remote = endpoint.address(), local = null, d = link.remoteClassD(), c = link.remoteClassC();
        byte[] privateKey = null;
        try {
            OrdinaryIkeAuth.DataClass dataClass;
            if (Arrays.equals(remote, d)) { dataClass = OrdinaryIkeAuth.DataClass.CLASS_D; local = link.localClassD(); }
            else if (Arrays.equals(remote, c)) { dataClass = OrdinaryIkeAuth.DataClass.CLASS_C; local = link.localClassC(); }
            else throw new IllegalStateException("Discovered endpoint does not match this pair's current IPv6 assignment");
            String library = System.getProperty("watch.native.quic.path");
            if (library == null || !library.startsWith("/") || !library.endsWith("/libwatch_replicator_quic.so"))
                throw new IllegalStateException("Native QUIC library path unavailable");
            System.load(library);
            int port = 49152 + new java.security.SecureRandom().nextInt(16384);
            privateKey = discovery.privateKeyEncoded();
            List<byte[]> keys = endpoint.publicKeys();
            long handle = nativeCreate(privateKey, keys.toArray(new byte[0][]), local, port, remote, endpoint.port);
            if (handle == 0) throw new IllegalStateException("Native QUIC handle absent");
            log.accept("REPLICATOR QUIC: initialized pinned TLS13; class=" + dataClass
                + "; ALPN=application-service; no snapshot or setup confirmation.");
            NativeQuicSession session = new NativeQuicSession(new Backend() {
                public void receive(byte[] data) { nativeReceive(handle, data); }
                public byte[][] poll() { return nativePoll(handle); }
                public String state() { return nativeState(handle); }
                public void send(byte[] data) { nativeSend(handle, data); }
                public byte[][] read() { return nativeRead(handle); }
                public void close() { nativeClose(handle); }
            }, dataClass, local, remote, port, endpoint.port, log);
            try {
                session.application = new ReplicatorReadSession(localDeviceId, link.localApplicationDeviceName(), log);
                session.streamReader = new ReplicatorStreamReader(session.application::accept);
                return session;
            } catch (RuntimeException failure) {
                session.close();
                throw failure;
            }
        } finally {
            wipe(remote); wipe(local); wipe(d); wipe(c); wipe(privateKey);
        }
    }

    boolean accept(NormalLinkPipeSession.DeliveredIp packet) {
        if (closed || !packet.protectedByEsp || packet.dataClass != dataClass) return false;
        Ipv6UdpPacketCodec.Packet udp;
        try { udp = Ipv6UdpPacketCodec.decode(packet.packet); }
        catch (IllegalArgumentException unrelated) { return false; }
        try {
            if (udp.sourcePort() != remotePort || udp.destinationPort() != localPort
                    || !Arrays.equals(udp.source(), remote) || !Arrays.equals(udp.destination(), local)) return false;
            try { backend.receive(udp.data()); }
            catch (RuntimeException failure) { fail("native receive failed"); }
            return true;
        } finally { udp.destroy(); }
    }

    List<byte[]> poll(NormalLinkPipeSession link) {
        if (closed) return List.of();
        List<byte[]> frames = new ArrayList<>();
        String stage = "transport poll";
        try {
            if (pending.isEmpty()) {
                byte[][] output = backend.poll();
                if (output == null || output.length > 4) throw new IllegalStateException("Invalid QUIC batch");
                for (byte[] data : output) {
                    try { pending.add(Ipv6UdpPacketCodec.encode(local, remote, localPort, remotePort, data)); }
                    finally { wipe(data); }
                }
                String state = backend.state();
                if (state != null && !state.equals(lastState)) {
                    lastState = state;
                    log.accept("REPLICATOR QUIC: " + state + "; snapshots received=false.");
                }
                if (state != null && state.startsWith("failed")) { close(); return frames; }
                if ("connected".equals(state) && application != null && !handshakeSent) {
                    stage = "application handshake send";
                    byte[] request = application.initialFrame();
                    try {
                        backend.send(request); handshakeSent = true;
                        log.accept("REPLICATOR APPLICATION: initial native snapshot-zone handshake queued; bytes="
                            + request.length + "; no file/face publication or setup replay.");
                    } finally { wipe(request); }
                }
                if (streamReader != null) {
                    stage = "application stream read";
                    byte[][] chunks = backend.read();
                    if (chunks == null || chunks.length > 4) throw new IllegalStateException("Invalid QUIC stream batch");
                    for (byte[] chunk : chunks) {
                        stage = "application frame decode";
                        if (chunk != null && chunk.length >= 9)
                            log.accept("REPLICATOR APPLICATION: stream chunk; bytes=" + (chunk.length - 9)
                                + "; stream=" + java.nio.ByteBuffer.wrap(chunk, 0, 8).getLong()
                                + "; ended=" + (chunk[8] == 1) + "; payload/device IDs logged=false.");
                        try { streamReader.accept(chunk); }
                        finally { wipe(chunk); }
                    }
                }
                if ("connected".equals(state) && application != null) {
                    byte[] reply = application.pollOutbound();
                    if (reply != null) {
                        stage = "application reply send";
                        try { backend.send(reply); }
                        finally { wipe(reply); }
                    }
                }
            }
            while (!pending.isEmpty()) {
                stage = "bounded tunnel send";
                byte[] frame = link.sendIpv6(dataClass, pending.peek());
                if (frame == null) break;
                wipe(pending.remove()); frames.add(frame);
            }
        } catch (RuntimeException failure) {
            String detail = failure.getClass().getSimpleName();
            String message = failure.getMessage();
            if (message != null && message.matches("QUIC application stream reset code=[0-9]{1,20}")) detail = message;
            if (message != null && List.of("Replicator peer is not paired", "Incompatible Replicator peer protocol",
                    "Invalid Replicator zone descriptors", "Invalid Replicator zone pair",
                    "Incompatible Replicator snapshot zone", "Replicator snapshot zones unavailable",
                    "Replicator envelope identity mismatch", "Replicator completion is incompatible",
                    "Uncorrelated Replicator completion").contains(message)) detail = message;
            fail(stage + " failed (" + detail + ")");
        }
        return frames;
    }

    private void fail(String reason) { log.accept("REPLICATOR QUIC: " + reason + "; IDS retained; snapshot delivery unconfirmed."); close(); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try { backend.close(); }
        catch (RuntimeException failure) { log.accept("REPLICATOR QUIC: native handle release failed; IDS retained."); }
        finally { pending.forEach(NativeQuicSession::wipe); pending.clear(); wipe(local); wipe(remote);
            if (streamReader != null) streamReader.close();
            if (application != null) application.close(); }
    }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private static native long nativeCreate(byte[] privateKey, byte[][] pins, byte[] local, int localPort, byte[] remote, int remotePort);
    private static native void nativeReceive(long handle, byte[] data);
    private static native byte[][] nativePoll(long handle);
    private static native String nativeState(long handle);
    private static native void nativeSend(long handle, byte[] data);
    private static native byte[][] nativeRead(long handle);
    private static native void nativeClose(long handle);
}
