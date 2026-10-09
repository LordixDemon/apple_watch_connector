package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Private APK→HAL ciphertext only. Identities/epoch/deadline are checked again at actual send. */
final class HealthOutboundIpcCodec {
    static final String PREFIX="BRIDGE_HEALTH_SEND_V1:", STATUS_PREFIX="BRIDGE_HEALTH_SEND_STATUS_V1:";
    static final int MAX_FRAME=HealthDataEventCodec.MAX_FRAME;
    record Header(UUID pair,UUID local,UUID peer,UUID epoch,UUID message,long deadline) {
        Header {
            if(pair==null || local==null || peer==null || epoch==null || message==null || message.version()!=4
                    || message.variant()!=2 || deadline<1) throw new IllegalArgumentException("Invalid Health send context");
        }
        void requireContext(UUID expectedPair,UUID expectedLocal,UUID expectedPeer,UUID expectedEpoch,long now) {
            if(now<0 || !pair.equals(expectedPair) || !local.equals(expectedLocal) || !peer.equals(expectedPeer)
                    || !epoch.equals(expectedEpoch) || now>=deadline || deadline-now>60_000) {
                throw new IllegalArgumentException("Stale/foreign Health send context");
            }
        }
    }
    static final class Request implements AutoCloseable {
        final Header header;
        private byte[] encrypted;
        Request(Header header,byte[] encrypted) {
            if(header==null)throw new IllegalArgumentException();
            HealthDataEventCodec.requireEncryptedDictionary(encrypted);this.header=header;this.encrypted=encrypted.clone();
        }
        byte[] encrypted() { requireOpen();return encrypted.clone(); }
        private void requireOpen() { if(encrypted==null)throw new IllegalStateException("Health send closed"); }
        @Override public void close() { wipe(encrypted);encrypted=null; }
    }
    private HealthOutboundIpcCodec() { }
    static byte[] encode(Request request) {
        request.requireOpen();Header h=request.header;Map<String,Object> map=new LinkedHashMap<>();
        map.put("v",1L);map.put("pair",h.pair.toString());map.put("local",h.local.toString());map.put("peer",h.peer.toString());
        map.put("epoch",h.epoch.toString());map.put("id",h.message.toString());map.put("deadline",h.deadline);map.put("encrypted",request.encrypted);
        byte[] bytes=AppleBinaryPropertyList.encode(map);
        if(bytes.length>MAX_FRAME) { wipe(bytes);throw new IllegalArgumentException("Health send frame limit"); }return bytes;
    }
    static Request decode(byte[] bytes) {
        if(bytes==null || bytes.length>MAX_FRAME)throw new IllegalArgumentException("Health send frame limit");
        Object decoded=AppleBinaryPropertyList.decode(bytes);
        try {
            if(!(decoded instanceof Map<?,?> map) || map.size()!=8 || !Long.valueOf(1).equals(map.get("v"))
                    || !(map.get("deadline") instanceof Long deadline) || !(map.get("encrypted") instanceof byte[] encrypted)) {
                throw new IllegalArgumentException("Invalid Health send fields");
            }
            return new Request(new Header(uuid(map.get("pair")),uuid(map.get("local")),uuid(map.get("peer")),
                    uuid(map.get("epoch")),uuid(map.get("id")),deadline),encrypted);
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }
    private static UUID uuid(Object value) {
        if(!(value instanceof String text))throw new IllegalArgumentException("Missing Health send UUID");
        UUID uuid=UUID.fromString(text);if(!uuid.toString().equals(text))throw new IllegalArgumentException("Invalid UUID");return uuid;
    }
    private static void wipe(byte[] bytes) { if(bytes!=null)Arrays.fill(bytes,(byte)0); }
}
