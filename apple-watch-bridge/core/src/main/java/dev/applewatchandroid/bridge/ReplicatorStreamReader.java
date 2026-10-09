package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Incremental native72-byte framing. Owns a bounded aggregate payload budget. */
final class ReplicatorStreamReader implements AutoCloseable {
    private static final int BUDGET = 8 * 1024 * 1024;
    private final Map<Long, Partial> streams = new HashMap<>();
    private final BiConsumer<ReplicatorNetworkHeader, byte[]> complete;
    private int allocated;
    ReplicatorStreamReader(BiConsumer<ReplicatorNetworkHeader, byte[]> complete) { this.complete = complete; }
    void accept(byte[] chunk) {
        if (chunk == null || chunk.length < 9 || chunk.length > 4105 || (chunk[8] != 0 && chunk[8] != 1))
            throw new IllegalArgumentException("Invalid bounded QUIC stream chunk");
        long id = ByteBuffer.wrap(chunk, 0, 8).getLong();
        Partial p = streams.get(id);
        if (p == null) {
            if (streams.size() >= 16) throw new IllegalStateException("Replicator stream count outside bounds");
            p = new Partial(); streams.put(id, p);
        }
        int at = 9;
        while (at < chunk.length) {
            if (p.header == null) {
                int count = Math.min(72 - p.headerOffset, chunk.length - at);
                System.arraycopy(chunk, at, p.headerBytes, p.headerOffset, count);
                at += count; p.headerOffset += count;
                if (p.headerOffset < 72) continue;
                p.header = ReplicatorNetworkHeader.decode(p.headerBytes);
                if (p.header.payloadBytes > BUDGET - allocated) throw new IllegalStateException("Replicator payload budget exceeded");
                p.payload = new byte[p.header.payloadBytes]; allocated += p.payload.length;
            }
            int count = Math.min(p.payload.length - p.payloadOffset, chunk.length - at);
            System.arraycopy(chunk, at, p.payload, p.payloadOffset, count);
            at += count; p.payloadOffset += count;
            if (p.payloadOffset == p.payload.length) {
                try { complete.accept(p.header, p.payload); }
                finally { allocated -= p.payload.length; p.clear(); }
            }
        }
        if (chunk[8] == 1) {
            if (p.headerOffset != 0) throw new IllegalArgumentException("Replicator stream ended during a frame");
            streams.remove(id);
        }
    }
    @Override public void close() { streams.values().forEach(Partial::clear); streams.clear(); allocated = 0; }
    private static final class Partial {
        final byte[] headerBytes = new byte[72]; int headerOffset, payloadOffset;
        ReplicatorNetworkHeader header; byte[] payload;
        void clear() { Arrays.fill(headerBytes, (byte)0); if (payload != null) Arrays.fill(payload, (byte)0);
            header = null; payload = null; headerOffset = 0; payloadOffset = 0; }
    }
}
