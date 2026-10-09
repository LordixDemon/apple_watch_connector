package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HealthDaemon23S303 PB records. Read-only: decoding records/anchors never commits a sync. */
final class NativeHealthChangesCodec {
    enum Schema { CHANGE_SET, CHANGE, STATUS, ANCHOR, ENTITY_IDENTIFIER,
        AUTHORIZATION_REQUEST, AUTHORIZATION_RESPONSE, AUTHORIZATION_COMPLETE, ACTIVATION_RESTORE,
        SOURCE, DEVICE, SYNC_IDENTITY, PROVENANCE, HEALTH_OBJECT, SAMPLE,
        CATEGORY_SAMPLE, QUANTITY_SAMPLE, QUANTITY_SERIES_DATUM, QUANTITY,
        METADATA_DICTIONARY, METADATA_PAIR, DOMAIN_DICTIONARY, TIMESTAMPED_PAIR,
        OBJECT_COLLECTION, DELETED_SAMPLE, VERSION_RANGE }
    private enum Kind { INT32, UINT32, INT64, BOOL, DOUBLE, BYTES, UTF8, REPEATED_BYTES,
        REPEATED_INT64, CHILD, REPEATED_CHILD }
    private record Field(Kind kind, Schema child) {
        Field(Kind kind) { this(kind,null); }
        int wire() { return kind==Kind.DOUBLE ? 1 : switch(kind) {
            case INT32,UINT32,INT64,BOOL,REPEATED_INT64 -> 0;
            default -> 2;
        }; }
        boolean repeated() { return kind==Kind.REPEATED_BYTES || kind==Kind.REPEATED_CHILD || kind==Kind.REPEATED_INT64; }
        boolean acceptsWire(int wire) { return wire==wire() || kind==Kind.REPEATED_INT64 && wire==2; }
    }
    private static final int MAX_ITEMS=4096;
    private NativeHealthChangesCodec() { }

    /** Field tags/types are paired writer+reader evidence; objectData/error/version remain opaque. */
    private static Field field(Schema schema,int number) {
        return switch(schema) {
            case VERSION_RANGE -> switch(number) {
                case 1,2 -> new Field(Kind.UINT32);
                default -> null;
            };
            case CHANGE_SET -> switch(number) {
                case 1 -> new Field(Kind.REPEATED_CHILD,Schema.CHANGE);
                case 2,4 -> new Field(Kind.BYTES);
                case 3 -> new Field(Kind.DOUBLE);
                case 5 -> new Field(Kind.INT32);
                default -> null;
            };
            case CHANGE -> switch(number) {
                case 1 -> new Field(Kind.INT32);
                case 2,3,7 -> new Field(Kind.INT64);
                case 4 -> new Field(Kind.REPEATED_BYTES);
                case 5 -> new Field(Kind.REPEATED_CHILD,Schema.ANCHOR);
                case 6,8 -> new Field(Kind.BOOL);
                case 9 -> new Field(Kind.CHILD,Schema.ENTITY_IDENTIFIER);
                case 10 -> new Field(Kind.BYTES);
                default -> null;
            };
            case STATUS -> switch(number) {
                case 1 -> new Field(Kind.INT32);
                case 2 -> new Field(Kind.REPEATED_CHILD,Schema.ANCHOR);
                default -> null;
            };
            case ANCHOR -> switch(number) {
                case 1 -> new Field(Kind.INT32);
                case 2 -> new Field(Kind.INT64);
                case 3 -> new Field(Kind.CHILD,Schema.ENTITY_IDENTIFIER);
                default -> null;
            };
            case ENTITY_IDENTIFIER -> switch(number) {
                case 1 -> new Field(Kind.UTF8);
                case 2 -> new Field(Kind.INT64);
                default -> null;
            };
            case AUTHORIZATION_REQUEST -> switch(number) {
                case 1 -> new Field(Kind.UTF8);
                case 2 -> new Field(Kind.BYTES);
                case 10,11 -> new Field(Kind.REPEATED_INT64);
                default -> null;
            };
            case AUTHORIZATION_RESPONSE -> switch(number) {
                case 1,11,12 -> new Field(Kind.UTF8);
                case 2 -> new Field(Kind.BYTES);
                case 10 -> new Field(Kind.BOOL);
                default -> null;
            };
            case AUTHORIZATION_COMPLETE -> switch(number) {
                case 1,10 -> new Field(Kind.UTF8);
                case 2 -> new Field(Kind.BYTES);
                default -> null;
            };
            case ACTIVATION_RESTORE -> switch(number) {
                case 1 -> new Field(Kind.BYTES);
                case 2 -> new Field(Kind.INT64);
                case 3 -> new Field(Kind.INT32);
                case 4 -> new Field(Kind.UTF8);
                case 6 -> new Field(Kind.REPEATED_BYTES);
                default -> null;
            };
            case SOURCE -> switch(number) {
                case 1,2,3,8 -> new Field(Kind.UTF8);
                case 4 -> new Field(Kind.INT64);
                case 5 -> new Field(Kind.BYTES);
                case 6 -> new Field(Kind.DOUBLE);
                case 7 -> new Field(Kind.BOOL);
                case 9 -> new Field(Kind.CHILD,Schema.SYNC_IDENTITY);
                default -> null;
            };
            case DEVICE -> switch(number) {
                case 1,2,3,4,5,6,7,8,12 -> new Field(Kind.UTF8);
                case 9 -> new Field(Kind.BYTES);
                case 10 -> new Field(Kind.DOUBLE);
                case 11 -> new Field(Kind.CHILD,Schema.SYNC_IDENTITY);
                default -> null;
            };
            case SYNC_IDENTITY -> switch(number) {
                case 1,2 -> new Field(Kind.BYTES);
                case 3 -> new Field(Kind.UTF8);
                default -> null;
            };
            case PROVENANCE -> switch(number) {
                case 1,5,6,7 -> new Field(Kind.UTF8);
                case 3,4,11 -> new Field(Kind.BYTES);
                case 8,9,10 -> new Field(Kind.INT32);
                default -> null;
            };
            case HEALTH_OBJECT -> switch(number) {
                case 1 -> new Field(Kind.BYTES);
                case 2 -> new Field(Kind.CHILD,Schema.METADATA_DICTIONARY);
                case 3 -> new Field(Kind.UTF8);
                case 4 -> new Field(Kind.DOUBLE);
                case 5 -> new Field(Kind.INT64);
                default -> null;
            };
            case SAMPLE -> switch(number) {
                case 1 -> new Field(Kind.CHILD,Schema.HEALTH_OBJECT);
                case 2 -> new Field(Kind.INT64);
                case 3,4 -> new Field(Kind.DOUBLE);
                default -> null;
            };
            case CATEGORY_SAMPLE -> switch(number) {
                case 1 -> new Field(Kind.CHILD,Schema.SAMPLE);
                case 2 -> new Field(Kind.INT64);
                default -> null;
            };
            case QUANTITY_SAMPLE -> switch(number) {
                case 1 -> new Field(Kind.CHILD,Schema.SAMPLE);
                case 2,3,8,9,10,11,13 -> new Field(Kind.DOUBLE);
                case 4 -> new Field(Kind.UTF8);
                case 5,7 -> new Field(Kind.BOOL);
                case 6 -> new Field(Kind.INT64);
                case 12 -> new Field(Kind.REPEATED_CHILD,Schema.QUANTITY_SERIES_DATUM);
                default -> null;
            };
            case QUANTITY_SERIES_DATUM -> switch(number) {
                case 1,2,3 -> new Field(Kind.DOUBLE);
                default -> null;
            };
            case QUANTITY -> switch(number) {
                case 1 -> new Field(Kind.DOUBLE);
                case 2 -> new Field(Kind.UTF8);
                default -> null;
            };
            case METADATA_DICTIONARY -> switch(number) {
                case 1 -> new Field(Kind.REPEATED_CHILD,Schema.METADATA_PAIR);
                default -> null;
            };
            case METADATA_PAIR -> switch(number) {
                case 1,2 -> new Field(Kind.UTF8);
                case 3,5 -> new Field(Kind.DOUBLE);
                case 4 -> new Field(Kind.INT64);
                case 6 -> new Field(Kind.CHILD,Schema.QUANTITY);
                case 7 -> new Field(Kind.BYTES);
                default -> null;
            };
            case DOMAIN_DICTIONARY -> switch(number) {
                case 1 -> new Field(Kind.INT64);
                case 2 -> new Field(Kind.UTF8);
                case 3 -> new Field(Kind.REPEATED_CHILD,Schema.TIMESTAMPED_PAIR);
                case 4 -> new Field(Kind.CHILD,Schema.SYNC_IDENTITY);
                default -> null;
            };
            case TIMESTAMPED_PAIR -> switch(number) {
                case 1,5 -> new Field(Kind.UTF8);
                case 2,4 -> new Field(Kind.DOUBLE);
                case 3 -> new Field(Kind.INT64);
                case 6 -> new Field(Kind.BYTES);
                default -> null;
            };
            case OBJECT_COLLECTION -> switch(number) {
                case 1 -> new Field(Kind.UTF8);
                case 2 -> new Field(Kind.CHILD,Schema.SOURCE);
                case 3 -> new Field(Kind.REPEATED_CHILD,Schema.CATEGORY_SAMPLE);
                case 4 -> new Field(Kind.REPEATED_CHILD,Schema.QUANTITY_SAMPLE);
                case 9 -> new Field(Kind.REPEATED_CHILD,Schema.DELETED_SAMPLE);
                case 20 -> new Field(Kind.CHILD,Schema.PROVENANCE);
                case 26 -> new Field(Kind.CHILD,Schema.SYNC_IDENTITY);
                // Native length-delimited children retained opaque until their own schema is implemented.
                case 5,6,7,8,10,11,13,14,15,21,22,23,25,27,28,29,30 -> new Field(Kind.REPEATED_BYTES);
                case 18 -> new Field(Kind.BYTES);
                default -> null;
            };
            case DELETED_SAMPLE -> switch(number) {
                case 1 -> new Field(Kind.CHILD,Schema.SAMPLE);
                default -> null;
            };
        };
    }

    /** Owned immutable tree. All binary getters clone; children belong to this node's lifetime. */
    static final class Node implements AutoCloseable {
        final Schema schema;
        private byte[] original;
        private final Map<Integer,Long> scalars=new LinkedHashMap<>();
        private final Map<Integer,List<byte[]>> blobs=new LinkedHashMap<>();
        private final Map<Integer,List<Node>> nested=new LinkedHashMap<>();
        private final Map<Integer,List<Long>> repeatedIntegers=new LinkedHashMap<>();
        private int unknownFields;
        private Node(Schema schema,byte[] original) { this.schema=schema; this.original=original.clone(); }
        private void requireOpen() { if(original==null) throw new IllegalStateException("Health native record closed"); }
        private void requireKind(int number,Kind... allowed) {
            requireOpen(); Field field=field(schema,number);
            if(field!=null) for(Kind kind:allowed) if(kind==field.kind) return;
            throw new IllegalArgumentException("Wrong Health field accessor/schema");
        }
        Integer int32(int number) {
            requireKind(number,Kind.INT32); Long value=scalars.get(number); return value==null ? null : value.intValue();
        }
        Long int64(int number) { requireKind(number,Kind.INT64); return scalars.get(number); }
        Long uint32(int number) { requireKind(number,Kind.UINT32); return scalars.get(number); }
        List<Long> int64s(int number) {
            requireKind(number,Kind.REPEATED_INT64);
            return List.copyOf(repeatedIntegers.getOrDefault(number,List.of()));
        }
        Boolean bool(int number) {
            requireKind(number,Kind.BOOL); Long value=scalars.get(number); return value==null ? null : value!=0;
        }
        Double doubleValue(int number) {
            requireKind(number,Kind.DOUBLE); Long value=scalars.get(number); return value==null ? null : Double.longBitsToDouble(value);
        }
        byte[] bytes(int number) {
            requireKind(number,Kind.BYTES,Kind.UTF8); List<byte[]> values=blobs.get(number);
            return values==null ? null : values.get(0).clone();
        }
        String string(int number) {
            requireKind(number,Kind.UTF8); List<byte[]> values=blobs.get(number);
            return values==null ? null : new String(values.get(0),StandardCharsets.UTF_8);
        }
        int byteCount(int number) {
            requireKind(number,Kind.REPEATED_BYTES); List<byte[]> values=blobs.get(number);
            return values==null ? 0 : values.size();
        }
        byte[] bytesAt(int number,int index) {
            requireKind(number,Kind.REPEATED_BYTES); List<byte[]> values=blobs.get(number);
            if(values==null) throw new IndexOutOfBoundsException(); return values.get(index).clone();
        }
        List<Node> children(int number) {
            requireKind(number,Kind.CHILD,Kind.REPEATED_CHILD);
            return List.copyOf(nested.getOrDefault(number,List.of()));
        }
        byte[] original() { requireOpen(); return original.clone(); }
        int unknownFields() { requireOpen(); return unknownFields; }
        int unknownFieldsDeep() {
            requireOpen();int count=unknownFields;
            for(var values:nested.values())for(Node child:values)count+=child.unknownFieldsDeep();
            return count;
        }
        @Override public void close() {
            if(original==null) return;
            wipe(original); original=null;
            blobs.values().forEach(values -> values.forEach(NativeHealthChangesCodec::wipe));
            nested.values().forEach(values -> values.forEach(Node::close));
            blobs.clear(); nested.clear(); scalars.clear(); repeatedIntegers.clear();
        }
    }

    static Node decode(Schema schema,byte[] bytes) {
        return decode(schema,bytes,new NativeHealthSyncCodec.Budget());
    }
    private static Node decode(Schema schema,byte[] bytes,NativeHealthSyncCodec.Budget budget) {
        if(schema==null || bytes==null || bytes.length>NativeHealthSyncCodec.MAX_BYTES) {
            throw new IllegalArgumentException("Invalid Health native record size/schema");
        }
        Node node=new Node(schema,bytes);
        var reader=new NativeHealthSyncCodec.Reader(node.original,budget);
        try {
            while(reader.hasNext()) {
                int tag=reader.tag(), number=tag>>>3; Field definition=field(schema,number);
                if(definition==null) { reader.skip(tag); node.unknownFields++; continue; }
                if(!definition.acceptsWire(tag&7) || !definition.repeated()
                        && (node.scalars.containsKey(number) || node.blobs.containsKey(number) || node.nested.containsKey(number))) {
                    throw new IllegalArgumentException("Wrong or duplicate Health native field");
                }
                switch(definition.kind) {
                    case REPEATED_INT64 -> {
                        List<Long> values=node.repeatedIntegers.computeIfAbsent(number,k -> new ArrayList<>());
                        if((tag&7)==0) {
                            addInteger(values,reader.varint());
                        } else {
                            byte[] packed=reader.bytes();
                            try {
                                var items=new NativeHealthSyncCodec.Reader(packed,budget);
                                while(items.hasNext()) {
                                    budget.field(); // Packed entries count toward the aggregate work limit.
                                    addInteger(values,items.varint());
                                }
                            } finally { wipe(packed); }
                        }
                    }
                    case INT32,UINT32,INT64,BOOL,DOUBLE -> {
                        long value=definition.kind==Kind.DOUBLE ? reader.fixed64() : reader.varint();
                        if(definition.kind==Kind.INT32 && value!=(int)value && (value<0 || value>0xffffffffL)) {
                            throw new IllegalArgumentException("Health native int32 overflow");
                        }
                        if(definition.kind==Kind.UINT32 && (value<0 || value>0xffffffffL))
                            throw new IllegalArgumentException("Health native uint32 overflow");
                        if(definition.kind==Kind.BOOL && value!=0 && value!=1) {
                            throw new IllegalArgumentException("Noncanonical Health native boolean");
                        }
                        node.scalars.put(number,value);
                    }
                    case BYTES,UTF8,REPEATED_BYTES -> {
                        List<byte[]> values=node.blobs.computeIfAbsent(number,k -> new ArrayList<>());
                        if(values.size()>=MAX_ITEMS) throw new IllegalArgumentException("Health native item limit");
                        byte[] value=reader.bytes();
                        try {
                            if(definition.kind==Kind.UTF8) {
                                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value));
                            }
                            values.add(value); value=null;
                        } catch(java.nio.charset.CharacterCodingException invalid) {
                            throw new IllegalArgumentException("Invalid Health entity schema UTF-8",invalid);
                        } finally { wipe(value); }
                    }
                    case CHILD,REPEATED_CHILD -> {
                        List<Node> values=node.nested.computeIfAbsent(number,k -> new ArrayList<>());
                        if(values.size()>=MAX_ITEMS) throw new IllegalArgumentException("Health native item limit");
                        byte[] child=reader.bytes();
                        try { values.add(decode(definition.child,child,budget)); }
                        finally { wipe(child); }
                    }
                }
            }
            return node;
        } catch(RuntimeException invalid) { node.close(); throw invalid; }
    }
    private static void addInteger(List<Long> values,long value) {
        if(values.size()>=MAX_ITEMS) throw new IllegalArgumentException("Health native item limit");
        values.add(value);
    }
    private static void wipe(byte[] bytes) { if(bytes!=null) Arrays.fill(bytes,(byte)0); }
}
