package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BridgeClock;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;
import dev.applewatchandroid.bridge.IdsControlChannelCodec;
import dev.applewatchandroid.bridge.IdsControlChannelWireCodec;
import dev.applewatchandroid.bridge.spi.IdsLinkKind;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link IdsLinkTransport} implementation over BT_CL {@code com.apple.ids} ({@code 0x0003})
 * backed by {@link BridgeCoreCodecs.ErtmLink} and {@link BridgeCoreCodecs.IdsBtLinkDecoder}.
 *
 * <p>Negotiates L2CAP ERTM with the peer's {@code fcsEnabled} setting, transmits immediate
 * ERTM supervisory/retransmission responses ({@code RR}/{@code REJ}/{@code SREJ}), and frames
 * outbound/inbound {@code IDSBTLink} packets.</p>
 */
public final class IdsBtLinkTransport implements IdsLinkTransport {
    private static final int MAX_BUFFERED_INBOUND_FRAMES = 64;

    private final L2capChannelMux mux;
    private final int localCid;
    private final int remoteCid;
    private final boolean fcsEnabled;
    private final BridgeCoreCodecs.ErtmLink ertmLink;
    private final BridgeCoreCodecs.IdsBtLinkDecoder streamDecoder;
    private final ArrayDeque<byte[]> decodedFrames = new ArrayDeque<>();

    private boolean closed;
    private int framesReceived;
    private int framesSent;
    private boolean checksumFlagSeen;
    private IdsControlChannelCodec.HelloMessage lastPeerHello;

    public IdsBtLinkTransport(L2capChannelMux mux, BtClControlPlane.OpenedChannel channel) {
        this(
                mux,
                Objects.requireNonNull(channel, "channel").localCid(),
                channel.remoteCid(),
                channel.fcs());
    }

    public IdsBtLinkTransport(L2capChannelMux mux, int localCid, int remoteCid, boolean fcsEnabled) {
        this.mux = Objects.requireNonNull(mux, "mux");
        if (localCid < 0x0040 || localCid > 0xFFFF || remoteCid < 0x0040 || remoteCid > 0xFFFF) {
            throw new IllegalArgumentException("IDSBTLink requires dynamic L2CAP CIDs (0x0040..0xFFFF)");
        }
        this.localCid = localCid;
        this.remoteCid = remoteCid;
        this.fcsEnabled = fcsEnabled;
        this.ertmLink = new BridgeCoreCodecs.ErtmLink(localCid, remoteCid, fcsEnabled);
        this.streamDecoder = new BridgeCoreCodecs.IdsBtLinkDecoder();
    }

    @Override
    public IdsLinkKind kind() {
        return IdsLinkKind.IDS_BT_LINK;
    }

    @Override
    public boolean isOpen() {
        return !closed && !mux.wasDisconnected();
    }

    public int localCid() {
        return localCid;
    }

    public int remoteCid() {
        return remoteCid;
    }

    public boolean fcsEnabled() {
        return fcsEnabled;
    }

    public int framesReceived() {
        return framesReceived;
    }

    public int framesSent() {
        return framesSent;
    }

    public boolean checksumFlagSeen() {
        return checksumFlagSeen;
    }

    public Optional<IdsControlChannelCodec.HelloMessage> lastPeerHello() {
        return Optional.ofNullable(lastPeerHello);
    }

    @Override
    public void sendControlFrame(byte[] payload) throws Exception {
        ensureOpen();
        mux.transport().checkStop();
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("IDSBTLink outbound payload must be non-empty");
        }
        drainPendingErtmTraffic();
        byte[] framed = BridgeCoreCodecs.encodeIdsBtLinkPacket(payload);
        try {
            int offset = 0;
            while (offset < framed.length) {
                int chunkLen = Math.min(BridgeCoreCodecs.ErtmLink.TARGET_MPS, framed.length - offset);
                byte[] chunk = Arrays.copyOfRange(framed, offset, offset + chunkLen);
                byte[] ertmFrame = null;
                try {
                    ertmFrame = ertmLink.sendInformation(chunk);
                    mux.sendL2cap(remoteCid, ertmFrame, "IDSBTLink I-FRAME");
                } finally {
                    Arrays.fill(chunk, (byte) 0);
                    if (ertmFrame != null) {
                        Arrays.fill(ertmFrame, (byte) 0);
                    }
                }
                offset += chunkLen;
            }
            framesSent++;
        } finally {
            Arrays.fill(framed, (byte) 0);
        }
    }

    @Override
    public byte[] receiveControlFrame(long timeoutMs) throws Exception {
        ensureOpen();
        long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (true) {
            mux.transport().checkStop();
            ensureOpen();
            byte[] ready = decodedFrames.pollFirst();
            if (ready != null) {
                return ready;
            }
            long remaining = deadline - BridgeClock.elapsedRealtime();
            if (remaining <= 0) {
                break;
            }
            byte[] l2capPayload = mux.pollDynamicCidPdu(localCid, remaining, "IDSBTLink RX");
            if (l2capPayload == null) {
                ready = decodedFrames.pollFirst();
                if (ready != null) {
                    return ready;
                }
                break;
            }
            processInboundErtmPayload(l2capPayload);
        }
        throw new HostException(String.format(
                Locale.US, "Timed out waiting for IDSBTLink control frame after %d ms", timeoutMs));
    }

    /**
     * Best-effort lenient Hello parser for watchOS 1–5 {@code IDSBTLink} frames (where optional
     * capability/serviceMin/deviceUUID TLVs may be absent). Returns {@code null} if {@code payload}
     * is not a valid IDS Control Hello frame.
     */
    public static IdsControlChannelCodec.HelloMessage tryDecodeLegacyHello(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }
        try {
            return IdsControlChannelWireCodec.decodeLegacyHelloPayload(payload);
        } catch (IllegalArgumentException directError) {
            if ((payload[0] & 0xFF) != IdsControlChannelCodec.TYPE_HELLO && payload.length >= 4) {
                int declaredLen = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                if (declaredLen == payload.length - 2
                        && (payload[2] & 0xFF) == IdsControlChannelCodec.TYPE_HELLO) {
                    byte[] inner = Arrays.copyOfRange(payload, 2, payload.length);
                    try {
                        return IdsControlChannelWireCodec.decodeLegacyHelloPayload(inner);
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    } finally {
                        Arrays.fill(inner, (byte) 0);
                    }
                }
            }
            return null;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        byte[] buffered;
        while ((buffered = decodedFrames.pollFirst()) != null) {
            Arrays.fill(buffered, (byte) 0);
        }
        ertmLink.close();
    }

    private void drainPendingErtmTraffic() throws Exception {
        byte[] pending;
        while ((pending = mux.pollDynamicCidPdu(localCid, 0L, "IDSBTLink drain")) != null) {
            processInboundErtmPayload(pending);
        }
    }

    private void processInboundErtmPayload(byte[] l2capPayload) throws Exception {
        try (BridgeCoreCodecs.ErtmInbound inbound = acceptErtmPayload(l2capPayload)) {
            for (byte[] ctrl : inbound.immediateOutboundFrames()) {
                try {
                    mux.sendL2cap(remoteCid, ctrl, "IDSBTLink ERTM CONTROL");
                } finally {
                    Arrays.fill(ctrl, (byte) 0);
                }
            }
            if (inbound.acknowledgementRecommended()) {
                byte[] rr = ertmLink.buildAcknowledgementFrame();
                try {
                    mux.sendL2cap(remoteCid, rr, "IDSBTLink ERTM RR");
                } finally {
                    Arrays.fill(rr, (byte) 0);
                }
            }
            for (byte[] sdu : inbound.deliveredSdus()) {
                try {
                    pushStreamSdu(sdu);
                } finally {
                    Arrays.fill(sdu, (byte) 0);
                }
            }
        } finally {
            Arrays.fill(l2capPayload, (byte) 0);
        }
    }

    private BridgeCoreCodecs.ErtmInbound acceptErtmPayload(byte[] l2capPayload) throws HostException {
        try {
            return ertmLink.accept(l2capPayload);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new HostException("IDSBTLink ERTM accept failed: " + error.getMessage(), error);
        }
    }

    private void pushStreamSdu(byte[] sdu) throws HostException {
        List<BridgeCoreCodecs.IdsBtLinkPacket> packets;
        try {
            packets = streamDecoder.push(sdu);
        } catch (IllegalArgumentException error) {
            throw new HostException("IDSBTLink framing decode failed: " + error.getMessage(), error);
        }
        for (BridgeCoreCodecs.IdsBtLinkPacket packet : packets) {
            try {
                if (decodedFrames.size() >= MAX_BUFFERED_INBOUND_FRAMES) {
                    close();
                    throw new HostException("IDSBTLink inbound control frame queue overflow");
                }
                framesReceived++;
                checksumFlagSeen |= packet.checksumFlagged();
                byte[] decodedPayload = packet.payload().clone();
                IdsControlChannelCodec.HelloMessage maybeHello = tryDecodeLegacyHello(decodedPayload);
                if (maybeHello != null) {
                    lastPeerHello = maybeHello;
                }
                decodedFrames.addLast(decodedPayload);
            } finally {
                packet.wipe();
            }
        }
    }

    private void ensureOpen() throws HostException {
        if (closed) {
            throw new HostException("IDSBTLink transport is closed");
        }
        if (mux.wasDisconnected()) {
            throw new HostException("IDSBTLink underlying link is disconnected");
        }
    }
}
