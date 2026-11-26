package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BluetoothFixedChannelRouter;
import dev.applewatchandroid.bridge.BluetoothLocalIdentity;
import dev.applewatchandroid.bridge.BridgeClock;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic in-memory {@link HciTransport} test double with L2CAP ACL
 * fragmentation/reassembly, ATT scope tracking, Watch request simulation, and
 * captured logs for zero-secret assertions.
 */
final class TestHciTransport implements HciTransport {
    static final int DEFAULT_HANDLE = 0x0042;
    static final int DEFAULT_PEER_ADDR_TYPE = 1;
    static final byte[] DEFAULT_PEER_ADDR = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, (byte) 0xC6};
    static final byte[] DEFAULT_LOCAL_ADDR = new byte[]{0x21, 0x22, 0x23, 0x24, 0x25, (byte) 0xC1};

    @FunctionalInterface
    interface OutboundHandler {
        void onSendL2cap(int cid, byte[] payload, String label) throws Exception;
    }

    @FunctionalInterface
    interface PeerAttHandler {
        byte[] handle(byte[] request) throws Exception;
    }

    @FunctionalInterface
    interface EncryptionHandler {
        void onStartEncryption(byte[] rand, int ediv, byte[] longTermKey) throws Exception;
    }

    record SentFrame(int cid, byte[] payload, String label) {}

    static final class StopException extends RuntimeException {
        StopException() {
            super("Stop requested");
        }
    }

    private final int connectionHandle;
    private final int peerAddressType;
    private final byte[] peerAddress;
    private final BluetoothLocalIdentity localIdentity;
    private final BlockingQueue<Packet> inbound = new LinkedBlockingQueue<>();
    private final List<SentFrame> sentFrames = Collections.synchronizedList(new ArrayList<>());
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicInteger attScopeOpenCount = new AtomicInteger();
    private final AtomicInteger attScopeCloseCount = new AtomicInteger();
    private final BluetoothFixedChannelRouter fixedRouter = new BluetoothFixedChannelRouter();

    private volatile OutboundHandler outboundHandler;
    private volatile PeerAttHandler peerAttHandler;
    private volatile EncryptionHandler encryptionHandler;
    private volatile int maxAclLength = 251;

    TestHciTransport() {
        this(DEFAULT_HANDLE, DEFAULT_PEER_ADDR_TYPE, DEFAULT_PEER_ADDR, defaultLocalIdentity());
    }

    TestHciTransport(
            int connectionHandle,
            int peerAddressType,
            byte[] peerAddress,
            BluetoothLocalIdentity localIdentity) {
        this.connectionHandle = connectionHandle;
        this.peerAddressType = peerAddressType;
        this.peerAddress = peerAddress.clone();
        this.localIdentity = localIdentity;
    }

    static BluetoothLocalIdentity defaultLocalIdentity() {
        byte[] irk = new byte[16];
        for (int i = 0; i < irk.length; i++) {
            irk[i] = (byte) (0xA0 + i);
        }
        return BluetoothLocalIdentity.create(DEFAULT_LOCAL_ADDR.clone(), irk);
    }

    void setOutboundHandler(OutboundHandler handler) {
        this.outboundHandler = handler;
    }

    void setPeerAttHandler(PeerAttHandler handler) {
        this.peerAttHandler = handler;
    }

    void setEncryptionHandler(EncryptionHandler handler) {
        this.encryptionHandler = handler;
    }

    void setMaxAclLength(int maxAclLength) {
        this.maxAclLength = maxAclLength;
    }

    void requestStop() {
        stopped.set(true);
    }

    void pushWatchAcl(int cid, byte[] payload) {
        pushWatchAclForHandle(connectionHandle, cid, payload);
    }

    void pushWatchAclForHandle(int handle, int cid, byte[] payload) {
        for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(handle, cid, payload, maxAclLength)) {
            inbound.offer(new Packet(PacketKind.ACL, frag));
        }
    }

    void pushWatchEvent(byte[] event) {
        inbound.offer(new Packet(PacketKind.EVENT, event.clone()));
    }

    void pushDisconnect(int handle, int reason) {
        pushWatchEvent(new byte[]{
                0x05, 0x04, 0x00,
                (byte) (handle & 0xFF), (byte) ((handle >>> 8) & 0xFF),
                (byte) (reason & 0xFF)
        });
    }

    List<SentFrame> sentFrames() {
        synchronized (sentFrames) {
            return List.copyOf(sentFrames);
        }
    }

    List<SentFrame> sentFramesForCid(int cid) {
        synchronized (sentFrames) {
            List<SentFrame> out = new ArrayList<>();
            for (SentFrame f : sentFrames) {
                if (f.cid() == cid) {
                    out.add(f);
                }
            }
            return out;
        }
    }

    String joinedLogs() {
        synchronized (logs) {
            return String.join("\n", logs);
        }
    }

    boolean isAttScopeActive() {
        return fixedRouter.isAttClientActive();
    }

    int attScopeOpenCount() {
        return attScopeOpenCount.get();
    }

    int attScopeCloseCount() {
        return attScopeCloseCount.get();
    }

    @Override
    public int connectionHandle() {
        return connectionHandle;
    }

    @Override
    public int peerAddressType() {
        return peerAddressType;
    }

    @Override
    public byte[] peerAddress() {
        return peerAddress.clone();
    }

    @Override
    public BluetoothLocalIdentity localIdentity() {
        return localIdentity;
    }

    @Override
    public void checkStop() {
        if (stopped.get()) {
            throw new StopException();
        }
    }

    @Override
    public void sendL2cap(int cid, byte[] payload, String label) throws Exception {
        byte[] copy = payload.clone();
        sentFrames.add(new SentFrame(cid, copy, label));
        OutboundHandler handler = outboundHandler;
        if (handler != null) {
            handler.onSendL2cap(cid, copy, label);
        }
    }

    @Override
    public Packet nextPacket(long deadlineMs) throws Exception {
        long rem = deadlineMs - BridgeClock.elapsedRealtime();
        if (rem <= 0) {
            throw new TimeoutException("deadline elapsed");
        }
        Packet pkt = inbound.poll(rem, TimeUnit.MILLISECONDS);
        if (pkt == null) {
            throw new TimeoutException("timed out");
        }
        return pkt;
    }

    @Override
    public void startLeEncryption(byte[] rand, int ediv, byte[] longTermKey) throws Exception {
        EncryptionHandler handler = encryptionHandler;
        if (handler != null) {
            handler.onStartEncryption(
                    rand == null ? null : rand.clone(),
                    ediv,
                    longTermKey == null ? null : longTermKey.clone());
        }
    }

    @Override
    public AutoCloseable openAttClientScope() {
        fixedRouter.setAttClientActive(true);
        attScopeOpenCount.incrementAndGet();
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                fixedRouter.setAttClientActive(false);
                attScopeCloseCount.incrementAndGet();
            }
        };
    }

    @Override
    public byte[] handlePeerAttRequest(byte[] request) throws Exception {
        PeerAttHandler handler = peerAttHandler;
        return handler == null ? null : handler.handle(request == null ? null : request.clone());
    }

    @Override
    public void log(String message) {
        logs.add(message);
    }

    static byte[] allZero(int len) {
        return new byte[len];
    }

    static boolean isZeroed(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    static byte[] typedAddress(byte[] addr, int type) {
        byte[] out = new byte[7];
        System.arraycopy(addr, 0, out, 0, 6);
        out[6] = (byte) type;
        return out;
    }

    static void wipe(byte[] b) {
        if (b != null) {
            Arrays.fill(b, (byte) 0);
        }
    }
}
