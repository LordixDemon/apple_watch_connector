package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Original dictionary plus a bounded selector; preserves source syncIdentity without adopting it. */
final class HealthDefaultsRecord implements AutoCloseable {
    private NativeHealthDefaults dictionary;
    private final int item;
    private HealthDefaultsRecord(NativeHealthDefaults dictionary,int item) { this.dictionary=dictionary;this.item=item; }
    static byte[] encode(NativeHealthDefaults dictionary,NativeHealthDefaults.Entry entry) {
        if(!dictionary.entries().contains(entry))throw new IllegalArgumentException("Foreign Health defaults selector");
        byte[] original=dictionary.original();
        try { return ByteBuffer.allocate(8+original.length).putInt(0x41574431).putInt(entry.index).put(original).array(); }
        finally { Arrays.fill(original,(byte)0); }
    }
    static HealthDefaultsRecord decode(NativeHealthReceivePolicy.Entity entity,String build,byte[] bytes) {
        if(bytes==null || bytes.length<8 || bytes.length>NativeHealthSyncCodec.MAX_BYTES+8)
            throw new IllegalArgumentException("Invalid Health defaults record");
        var input=ByteBuffer.wrap(bytes);
        if(input.getInt()!=0x41574431)throw new IllegalArgumentException("Unsupported Health defaults record version");
        int item=input.getInt();byte[] original=new byte[input.remaining()];input.get(original);
        NativeHealthDefaults dictionary=null;
        try {
            dictionary=NativeHealthDefaults.decode(entity,build,original);
            if(item<0 || item>=dictionary.entries().size())throw new IllegalArgumentException("Invalid Health defaults selector");
            var result=new HealthDefaultsRecord(dictionary,item);dictionary=null;return result;
        } finally { Arrays.fill(original,(byte)0);if(dictionary!=null)dictionary.close(); }
    }
    NativeHealthDefaults.Entry entry() { requireOpen();return dictionary.entries().get(item); }
    byte[] key() { requireOpen();return key(dictionary.domain(),entry().key()); }
    static byte[] key(String domain,String key) {
        if(domain==null || key==null)throw new IllegalArgumentException("Missing Health defaults key");
        byte[] a=domain.getBytes(StandardCharsets.UTF_8),b=key.getBytes(StandardCharsets.UTF_8);
        try { return ByteBuffer.allocate(8+a.length+b.length).putInt(a.length).put(a).putInt(b.length).put(b).array(); }
        finally { Arrays.fill(a,(byte)0);Arrays.fill(b,(byte)0); }
    }
    static byte[] aad(String scope,String entity,String object,String variant) {
        for(String token:new String[]{scope,object,variant})
            if(token==null || !token.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid Health defaults index");
        if(!"DEFAULTS".equals(entity) && !"PROTECTED_DEFAULTS".equals(entity))
            throw new IllegalArgumentException("Invalid Health defaults entity");
        return ("AWHealthDefaultsMirror1\n"+scope+"\n"+entity+"\n"+object+"\n"+variant).getBytes(StandardCharsets.US_ASCII);
    }
    private void requireOpen() { if(dictionary==null)throw new IllegalStateException("Closed Health defaults record"); }
    @Override public void close() { if(dictionary!=null) { dictionary.close();dictionary=null; } }
    @Override public String toString() { return "Health defaults mirror record (values withheld)"; }
}
