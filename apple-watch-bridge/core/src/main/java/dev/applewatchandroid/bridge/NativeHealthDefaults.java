package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Owned 23S303 defaults values and the native updatePolicy=2 comparison. No receipt or anchor effects. */
final class NativeHealthDefaults implements AutoCloseable {
    enum Kind { DOUBLE, INTEGER, STRING, BYTES, TOMBSTONE }
    static final class Entry {
        private final NativeHealthChangesCodec.Node pair;
        final int index;
        private Entry(NativeHealthChangesCodec.Node pair,int index) { this.pair=pair;this.index=index; }
        String key() { return pair.string(1); }
        double date() { return pair.doubleValue(2); }
        Kind kind() {
            // -decodedValue checks these fields in this order, even if several are present.
            if(pair.doubleValue(4)!=null)return Kind.DOUBLE;
            if(pair.int64(3)!=null)return Kind.INTEGER;
            if(pair.string(5)!=null)return Kind.STRING;
            byte[] bytes=pair.bytes(6);
            try { return bytes==null ? Kind.TOMBSTONE : Kind.BYTES; }
            finally { wipe(bytes); }
        }
        Double doubleValue() { return kind()==Kind.DOUBLE ? pair.doubleValue(4) : null; }
        Long integerValue() { return kind()==Kind.INTEGER ? pair.int64(3) : null; }
        String stringValue() { return kind()==Kind.STRING ? pair.string(5) : null; }
        byte[] bytesValue() { return kind()==Kind.BYTES ? pair.bytes(6) : null; }
        @Override public String toString() { return "Health defaults entry (values withheld)"; }
    }
    private NativeHealthChangesCodec.Node dictionary;
    final NativeHealthReceivePolicy.Entity entity;
    private final List<Entry> entries;
    private NativeHealthDefaults(NativeHealthChangesCodec.Node dictionary,NativeHealthReceivePolicy.Entity entity) {
        this.dictionary=dictionary;this.entity=entity;
        List<Entry> values=new ArrayList<>();int index=0;
        for(var pair:dictionary.children(3))values.add(new Entry(pair,index++));
        entries=List.copyOf(values);
    }
    static NativeHealthDefaults decode(NativeHealthReceivePolicy.Entity entity,String build,byte[] original) {
        if(!NativeHealthTypeCatalog.BUILD.equals(build) || entity!=NativeHealthReceivePolicy.Entity.DEFAULTS
                && entity!=NativeHealthReceivePolicy.Entity.PROTECTED_DEFAULTS)
            throw new IllegalArgumentException("Unsupported native Health defaults context");
        var dictionary=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.DOMAIN_DICTIONARY,original);
        try {
            long category=entity==NativeHealthReceivePolicy.Entity.DEFAULTS ? 1 : 105;
            if(dictionary.unknownFieldsDeep()!=0 || !Long.valueOf(category).equals(dictionary.int64(1)))
                throw new IllegalArgumentException("Unsupported native Health defaults category/schema");
            for(var pair:dictionary.children(3)) {
                Double date=pair.doubleValue(2);
                if(pair.string(1)==null || date==null)
                    throw new IllegalArgumentException("Missing native Health defaults key/date");
                // Conservative local hold: SQL ordering of nonfinite dates is not implemented.
                if(!Double.isFinite(date))throw new IllegalArgumentException("Unsupported Health defaults date");
            }
            var result=new NativeHealthDefaults(dictionary,entity);dictionary=null;return result;
        } finally { if(dictionary!=null)dictionary.close(); }
    }
    // Native _insertCodableCategoryDomainDictionary block normalizes nil to @"" before SQL insertion.
    String domain() { requireOpen();String domain=dictionary.string(2);return domain==null ? "" : domain; }
    boolean hasDomain() { requireOpen();return dictionary.string(2)!=null; }
    List<Entry> entries() { requireOpen();return entries; }
    byte[] original() { requireOpen();return dictionary.original(); }
    boolean hasSyncIdentity() { requireOpen();return !dictionary.children(4).isEmpty(); }
    /** Successful empty lookup is null; a failed lookup must throw, never substitute null. */
    static boolean replaces(Double storedDate,double incomingDate) {
        if(!Double.isFinite(incomingDate) || storedDate!=null && !Double.isFinite(storedDate))
            throw new IllegalArgumentException("Unsupported Health defaults date");
        return storedDate==null || storedDate<incomingDate;
    }
    private void requireOpen() { if(dictionary==null)throw new IllegalStateException("Closed Health defaults record"); }
    @Override public void close() { if(dictionary!=null) { dictionary.close();dictionary=null; } }
    @Override public String toString() { return "Native Health defaults (values withheld)"; }
    private static void wipe(byte[] bytes) { if(bytes!=null)Arrays.fill(bytes,(byte)0); }
}
