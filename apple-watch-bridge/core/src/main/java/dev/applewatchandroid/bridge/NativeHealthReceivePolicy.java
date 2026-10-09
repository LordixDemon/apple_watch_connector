package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 23S303 receive controls. A permitted plan still requires a successful entity/data transaction. */
final class NativeHealthReceivePolicy {
    enum Entity {
        SOURCE(11,10,false), DEVICE(13,12,false), CATEGORY(2,1,true), QUANTITY(4,2,true),
        DEFAULTS(16,16,true), PROTECTED_DEFAULTS(17,17,true);
        final long identifier;final int legacyType;final boolean speculative;
        Entity(long identifier,int legacyType,boolean speculative) {
            this.identifier=identifier;this.legacyType=legacyType;this.speculative=speculative;
        }
        // No currentSyncVersion/supportedSyncVersionRange on these native classes or their parents.
        int currentVersion() { return 0; }
    }
    enum Outcome { REQUIRES_DATA_TRANSACTION, ALREADY_APPLIED, REJECTED, HELD }
    enum Reason { CONTROL_VALID, COVERED_RANGE, FOREIGN_BUILD, UNSUPPORTED_SCHEMA_ENTITY,
        SEQUENCE_STATE_UNAVAILABLE, OUT_OF_ORDER, UNSUPPORTED_SPECULATIVE, INVALID_REQUIRED_MAP,
        RECEIVED_STATE_UNAVAILABLE, INVALID_LAST_ANCHOR, INVALID_RANGE, RANGE_GAP, DEPENDENCY_GAP }
    /** Null is unavailable, never fabricated as an accepted zero anchor. */
    @FunctionalInterface interface ReceivedAnchors { Long received(Entity entity); }
    record Version(boolean present,Long minimum,Long current) {
        // Native getters use zero-initialized ivars/nil messages. Presence is retained separately.
        long projectedCurrent() { return current==null ? 0 : current; }
        @Override public String toString() { return "Native Health version (values withheld)"; }
    }
    record Effect(Long expectedSequence,Long receivedAnchor,Long validatedAnchor) {
        @Override public String toString() { return "Post-apply Health effect (values withheld)"; }
    }
    record Plan(Outcome outcome,Reason reason,Integer nativeError,Entity entity,Version version,Effect afterDataApplied) {
        @Override public String toString() { return "Health receive plan (values withheld)"; }
    }
    private NativeHealthReceivePolicy() { }

    static Plan prepare(NativeHealthChangesCodec.Node change,String build,Long expectedSequence,ReceivedAnchors anchors) {
        if(change==null || change.schema!=NativeHealthChangesCodec.Schema.CHANGE || anchors==null)
            throw new IllegalArgumentException("Missing Health receive control/context");
        if(!NativeHealthTypeCatalog.BUILD.equals(build))return held(Reason.FOREIGN_BUILD);
        if(change.unknownFields()!=0)return held(Reason.UNSUPPORTED_SCHEMA_ENTITY);
        Entity entity=entity(change,1,9);
        if(entity==null)return held(Reason.UNSUPPORTED_SCHEMA_ENTITY);
        Version version=version(change);
        if(version==null)return held(Reason.UNSUPPORTED_SCHEMA_ENTITY);
        Long sequence=change.int64(7);
        if(sequence!=null && sequence!=0) {
            if(expectedSequence==null)return held(Reason.SEQUENCE_STATE_UNAVAILABLE);
            if(!sequence.equals(expectedSequence))return rejected(Reason.OUT_OF_ORDER,0x57a,entity,version);
        }
        boolean speculative=Boolean.TRUE.equals(change.bool(8));
        if(speculative) {
            if(!entity.speculative)return rejected(Reason.UNSUPPORTED_SPECULATIVE,100,entity,version);
            // Native speculative branch does not construct or validate its range/required-anchor map.
            return applicable(change,entity,version,true);
        }
        Map<Entity,Long> required=new LinkedHashMap<>();
        for(var dependency:change.children(5)) {
            if(dependency.unknownFields()!=0)return held(Reason.UNSUPPORTED_SCHEMA_ENTITY);
            Entity key=entity(dependency,1,3);Long anchor=dependency.int64(2);
            if(key==null)return held(Reason.UNSUPPORTED_SCHEMA_ENTITY);
            if(anchor==null || anchor<0)return rejected(Reason.INVALID_REQUIRED_MAP,0x578,entity,version);
            // Native HDSyncAnchorMap setAnchor replaces a repeated entity's value, preserving list order.
            required.put(key,anchor);
        }
        Long last=anchors.received(entity);
        if(last==null)return held(Reason.RECEIVED_STATE_UNAVAILABLE);
        if(last<0)return rejected(Reason.INVALID_LAST_ANCHOR,0x578,entity,version);
        Long start=change.int64(2),end=change.int64(3);
        if(start==null || end==null || start<0 || end<start)
            return rejected(Reason.INVALID_RANGE,0x578,entity,version);
        // Strict start<last: (last,last) is not an already-applied range in native code.
        if(start<last && end<=last)return new Plan(Outcome.ALREADY_APPLIED,Reason.COVERED_RANGE,null,entity,version,null);
        if(last<start)return rejected(Reason.RANGE_GAP,0x578,entity,version);
        for(var entry:required.entrySet()) {
            Long existing=anchors.received(entry.getKey());
            if(existing==null)return held(Reason.RECEIVED_STATE_UNAVAILABLE);
            if(existing<entry.getValue())return rejected(Reason.DEPENDENCY_GAP,0x578,entity,version);
        }
        return applicable(change,entity,version,false);
    }
    private static Plan applicable(NativeHealthChangesCodec.Node change,Entity entity,Version version,boolean speculative) {
        Long sequence=change.int64(7);
        boolean done=Boolean.TRUE.equals(change.bool(6));
        // ARM64 ADD wraps a 64-bit sequence; retain exact signed bits, including overflow.
        Long next=sequence==null ? null : done ? Long.valueOf(0) : Long.valueOf(sequence+1);
        Long received=null,validated=null;
        if(!speculative && (sequence==null || done)) {
            received=change.int64(3);
            // Native CMP w0,w20/B.LT compares the upper range component as signed int32.
            if(entity.currentVersion()>=(int)version.projectedCurrent())validated=received;
        }
        return new Plan(Outcome.REQUIRES_DATA_TRANSACTION,Reason.CONTROL_VALID,null,entity,version,new Effect(next,received,validated));
    }
    private static Version version(NativeHealthChangesCodec.Node change) {
        byte[] bytes=change.bytes(10);
        if(bytes==null)return new Version(false,null,null);
        try(var range=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.VERSION_RANGE,bytes)) {
            return range.unknownFields()==0 ? new Version(true,range.uint32(1),range.uint32(2)) : null;
        } catch(IllegalArgumentException malformed) {
            return null;
        } finally { Arrays.fill(bytes,(byte)0); }
    }
    private static Entity entity(NativeHealthChangesCodec.Node node,int legacyField,int identifierField) {
        var identifiers=node.children(identifierField);
        if(!identifiers.isEmpty()) {
            var id=identifiers.get(0);Long code=id.int64(2);
            if(id.unknownFields()!=0 || id.string(1)!=null || code==null)return null;
            for(Entity entity:Entity.values())if(entity.identifier==code)return entity;
            // Complete native registry resolution outside this implemented subset remains held.
            return null;
        }
        Integer type=node.int32(legacyField);
        if(type!=null)for(Entity entity:Entity.values())if(entity.legacyType==type)return entity;
        return null;
    }
    private static Plan held(Reason reason) { return new Plan(Outcome.HELD,reason,null,null,null,null); }
    private static Plan rejected(Reason reason,int code,Entity entity,Version version) {
        return new Plan(Outcome.REJECTED,reason,code,entity,version,null);
    }
}
