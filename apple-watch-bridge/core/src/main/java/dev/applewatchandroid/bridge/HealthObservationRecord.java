package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/** Owned original record selected from an authenticated observation cell; never native acceptance. */
final class HealthObservationRecord implements AutoCloseable {
    private final String kind;
    private NativeHealthChangesCodec.Node root;
    private final NativeHealthChangesCodec.Node selected;
    private HealthObservationRecord(String kind,NativeHealthChangesCodec.Node root,NativeHealthChangesCodec.Node selected) {
        this.kind=kind;this.root=root;this.selected=selected;
    }
    static HealthObservationRecord decode(String kind,byte[] body) {
        if(kind==null || body==null || body.length<8 || body.length>NativeHealthSyncCodec.MAX_BYTES+8)
            throw new IllegalArgumentException("Invalid Health observation cell size");
        var buffer=ByteBuffer.wrap(body);int field=buffer.getInt(),item=buffer.getInt();
        boolean sample=switch(kind) {
            case "QUANTITY","CATEGORY","DELETED" -> true;
            case "SOURCE","DEVICE","DEFAULTS","PROTECTED_DEFAULTS" -> false;
            default -> throw new IllegalArgumentException("Unknown Health observation kind");
        };
        int expected=switch(kind) { case "CATEGORY" -> 3;case "QUANTITY" -> 4;case "DELETED" -> 9;default -> 0; };
        if(field!=expected || item<0 || !sample && item!=0)
            throw new IllegalArgumentException("Health observation selector/kind mismatch");
        var schema=sample ? NativeHealthChangesCodec.Schema.OBJECT_COLLECTION : switch(kind) {
            case "SOURCE" -> NativeHealthChangesCodec.Schema.SOURCE;
            case "DEVICE" -> NativeHealthChangesCodec.Schema.DEVICE;
            default -> NativeHealthChangesCodec.Schema.DOMAIN_DICTIONARY;
        };
        byte[] original=Arrays.copyOfRange(body,8,body.length);
        NativeHealthChangesCodec.Node root=null;
        try {
            root=NativeHealthChangesCodec.decode(schema,original);
            var counts=NativeHealthObjectCodec.counts(root);
            if(counts.unknownFields()!=0 || counts.opaqueChildren()!=0)
                throw new IllegalArgumentException("Unsupported Health observation schema");
            NativeHealthChangesCodec.Node selected=root;
            if(sample) {
                List<NativeHealthChangesCodec.Node> children=root.children(field);
                if(item>=children.size())throw new IllegalArgumentException("Invalid Health observation item");
                selected=children.get(item);
                var samples=selected.children(1);
                if(samples.size()!=1 || !NativeHealthObjectCodec.header(samples.get(0)).structurallyComplete())
                    throw new IllegalArgumentException("Incomplete Health observation sample");
            }
            var record=new HealthObservationRecord(kind,root,selected);
            byte[] key=record.objectKey();
            try {
                if((kind.equals("SOURCE") || kind.equals("DEVICE")) && (key==null || key.length!=16))
                    throw new IllegalArgumentException("Invalid Health observation UUID");
            } finally { if(key!=null)Arrays.fill(key,(byte)0); }
            root=null;return record;
        } finally { Arrays.fill(original,(byte)0);if(root!=null)root.close(); }
    }
    private void requireOpen() { if(root==null)throw new IllegalStateException("Health observation closed"); }
    String kind() { requireOpen();return kind; }
    /** Parent owns source/provenance/metadata nodes; their getters refuse use after close. */
    NativeHealthChangesCodec.Node collection() { requireOpen();return root; }
    NativeHealthChangesCodec.Node selected() { requireOpen();return selected; }
    NativeHealthObjectCodec.SampleHeader header() {
        requireOpen();return switch(kind) {
            case "QUANTITY","CATEGORY","DELETED" -> NativeHealthObjectCodec.header(selected.children(1).get(0));
            default -> null;
        };
    }
    /** Original scope-local index identity, independently recomputed on every read. Caller wipes. */
    byte[] objectKey() {
        requireOpen();return switch(kind) {
            case "SOURCE" -> selected.bytes(5);
            case "DEVICE" -> selected.bytes(9);
            case "DEFAULTS","PROTECTED_DEFAULTS" -> NativeHealthObjectCodec.defaultsObservationKey(selected.int64(1),selected.string(2));
            default -> {
                var uuid=header().uuid();
                yield ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
            }
        };
    }
    /** Exact build plus full-width code and native class must agree. No unknown-code/unit fallback. */
    NativeHealthTypeCatalog.Definition definition(String build) {
        requireOpen();var header=header();if(header==null)return null;
        var definition=NativeHealthTypeCatalog.lookup(build,header.dataType());
        if(definition==null)return null;
        return switch(kind) {
            case "QUANTITY" -> definition.quantity() ? definition : null;
            case "CATEGORY" -> definition.category() ? definition : null;
            default -> definition;
        };
    }
    Double canonicalValue() { requireOpen();return kind.equals("QUANTITY") ? selected.doubleValue(2) : null; }
    Double originalValue() { requireOpen();return kind.equals("QUANTITY") ? selected.doubleValue(3) : null; }
    String originalUnit() { requireOpen();return kind.equals("QUANTITY") ? selected.string(4) : null; }
    Long categoryValue() { requireOpen();return kind.equals("CATEGORY") ? selected.int64(2) : null; }
    @Override public String toString() { return "Original Health observation (values withheld)"; }
    @Override public void close() { if(root!=null) { root.close();root=null; } }
}
