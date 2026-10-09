package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Read-only HealthDaemon23S303 objectData inspection; never adopts identities or advances anchors. */
final class NativeHealthObjectCodec {
    private NativeHealthObjectCodec() { }
    private static final int[] OPAQUE_COLLECTION_FIELDS={5,6,7,8,10,11,13,14,15,21,22,23,25,27,28,29,30};

    /** Local observation index only: nil category/domain remain distinct from zero/empty, never adopted. */
    static byte[] defaultsObservationKey(Long category,String domain) {
        byte[] text=domain==null ? null : domain.getBytes(StandardCharsets.UTF_8);
        try {
            var key=ByteBuffer.allocate(2+(category==null ? 0 : 8)+(text==null ? 0 : 4+text.length));
            key.put((byte)(category==null ? 0 : 1));if(category!=null)key.putLong(category);
            key.put((byte)(text==null ? 0 : 1));if(text!=null)key.putInt(text.length).put(text);
            return key.array();
        } finally { if(text!=null)Arrays.fill(text,(byte)0); }
    }

    /** A present entity identifier is authoritative, including unsupported schemas/identifiers. */
    static NativeHealthChangesCodec.Schema schemaFor(NativeHealthChangesCodec.Node change) {
        if(change.schema!=NativeHealthChangesCodec.Schema.CHANGE)throw new IllegalArgumentException("Expected Health change");
        var identifiers=change.children(9);
        if(!identifiers.isEmpty()) {
            var identifier=identifiers.get(0);
            Long entity=identifier.int64(2);
            if(identifier.unknownFields()!=0 || identifier.string(1)!=null || entity==null)return null;
            if(entity==11)return NativeHealthChangesCodec.Schema.SOURCE;
            if(entity==13)return NativeHealthChangesCodec.Schema.DEVICE;
            if(entity==2 || entity==4)return NativeHealthChangesCodec.Schema.OBJECT_COLLECTION;
            if(entity==16 || entity==17)return NativeHealthChangesCodec.Schema.DOMAIN_DICTIONARY;
            return null;
        }
        Integer type=change.int32(1);
        if(type==null)return null;
        return switch(type) {
            case 10 -> NativeHealthChangesCodec.Schema.SOURCE;
            case 12 -> NativeHealthChangesCodec.Schema.DEVICE;
            case 1,2 -> NativeHealthChangesCodec.Schema.OBJECT_COLLECTION;
            case 16,17 -> NativeHealthChangesCodec.Schema.DOMAIN_DICTIONARY;
            default -> null;
        };
    }

    /** Native NSDate-reference seconds are preserved; no guessed unit, type mapping or current time. */
    record SampleHeader(UUID uuid,Long dataType,Double startReferenceSeconds,Double endReferenceSeconds) {
        boolean structurallyComplete() {
            return uuid!=null && dataType!=null && startReferenceSeconds!=null && endReferenceSeconds!=null
                    && Double.isFinite(startReferenceSeconds) && Double.isFinite(endReferenceSeconds)
                    && startReferenceSeconds<=endReferenceSeconds;
        }
        @Override public String toString() { return "Health sample header (values withheld)"; }
    }
    static SampleHeader header(NativeHealthChangesCodec.Node sample) {
        if(sample.schema!=NativeHealthChangesCodec.Schema.SAMPLE)throw new IllegalArgumentException("Expected Health sample");
        var objects=sample.children(1);
        byte[] uuid=objects.isEmpty() ? null : objects.get(0).bytes(1);
        try {
            UUID parsed=null;
            if(uuid!=null && uuid.length==16) {
                var buffer=ByteBuffer.wrap(uuid);parsed=new UUID(buffer.getLong(),buffer.getLong());
            }
            return new SampleHeader(parsed,sample.int64(2),sample.doubleValue(3),sample.doubleValue(4));
        } finally { if(uuid!=null)Arrays.fill(uuid,(byte)0); }
    }
    record Counts(int categorySamples,int quantitySamples,int deletedSamples,int completeSampleHeaders,
                  int opaqueChildren,int unknownFields) { }

    /** Counts nested records, not unique samples, clinical validity, grants or persisted measurements. */
    static Counts counts(NativeHealthChangesCodec.Node object) {
        if(object.schema!=NativeHealthChangesCodec.Schema.OBJECT_COLLECTION)
            return new Counts(0,0,0,0,0,object.unknownFieldsDeep());
        int complete=0,opaque=0;
        for(int field:new int[]{3,4,9})for(var value:object.children(field)) {
            var samples=value.children(1);
            if(!samples.isEmpty() && header(samples.get(0)).structurallyComplete())complete++;
        }
        for(int field:OPAQUE_COLLECTION_FIELDS)opaque+=object.byteCount(field);
        byte[] generated=object.bytes(18);
        try { if(generated!=null)opaque++; }
        finally { if(generated!=null)Arrays.fill(generated,(byte)0); }
        return new Counts(object.children(3).size(),object.children(4).size(),object.children(9).size(),
                complete,opaque,object.unknownFieldsDeep());
    }
}
