package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/** Private authenticated-IDS-response notification. Public Class-A key only; no private material. */
final class HealthPeerIdentityCodec {
    static final String PREFIX = "BRIDGE_HEALTH_PEER_V1:";
    static final int MAX_FRAME = 8192;
    static final class Identity implements AutoCloseable {
        final UUID pair, epoch, local, peer;
        private byte[] key;
        Identity(UUID pair, UUID epoch, UUID local, UUID peer, byte[] key) {
            if (pair == null || epoch == null || local == null || peer == null || local.equals(peer)) {
                throw new IllegalArgumentException("Invalid Health peer identity");
            }
            IdsMessageProtectionIdentity.validatePublic(key);
            this.pair=pair; this.epoch=epoch; this.local=local; this.peer=peer; this.key=key.clone();
        }
        byte[] key() {
            if (key == null) throw new IllegalStateException("Health peer identity closed");
            return key.clone();
        }
        void requirePair(UUID expectedPair, UUID expectedLocal, UUID expectedPeer) {
            if (!pair.equals(expectedPair) || !local.equals(expectedLocal) || !peer.equals(expectedPeer)) {
                throw new IllegalArgumentException("Health peer differs from activated pair/installation");
            }
        }
        @Override public void close() { if(key != null) Arrays.fill(key,(byte)0); key=null; }
    }
    static byte[] encode(Identity identity) {
        byte[] key=identity.key();
        try {
            byte[] bytes=AppleBinaryPropertyList.encode(Map.of("v",1L,"pair",identity.pair.toString(),
                    "epoch",identity.epoch.toString(),"local",identity.local.toString(),
                    "peer",identity.peer.toString(),"A",key));
            if(bytes.length > MAX_FRAME) throw new IllegalArgumentException("Health peer frame limit");
            return bytes;
        } finally { Arrays.fill(key,(byte)0); }
    }
    static Identity decode(byte[] bytes) {
        if(bytes == null || bytes.length > MAX_FRAME) throw new IllegalArgumentException("Health peer frame limit");
        Object decoded=AppleBinaryPropertyList.decode(bytes);
        try {
            if(!(decoded instanceof Map<?,?> map) || map.size()!=6 || !Long.valueOf(1).equals(map.get("v"))
                    || !(map.get("A") instanceof byte[] key)) throw new IllegalArgumentException("Invalid Health peer frame");
            return new Identity(uuid(map.get("pair")),uuid(map.get("epoch")),uuid(map.get("local")),uuid(map.get("peer")),key);
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }
    private static UUID uuid(Object value) {
        if(!(value instanceof String text)) throw new IllegalArgumentException("Missing Health peer UUID");
        UUID uuid=UUID.fromString(text);
        if(!uuid.toString().equals(text)) throw new IllegalArgumentException("Noncanonical Health peer UUID");
        return uuid;
    }
}
