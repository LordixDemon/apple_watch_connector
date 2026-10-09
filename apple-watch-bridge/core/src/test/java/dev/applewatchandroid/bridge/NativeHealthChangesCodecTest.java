package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import org.junit.Test;

public final class NativeHealthChangesCodecTest {
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value.replace(" ","")); }
    private static NativeHealthChangesCodec.Node read(NativeHealthChangesCodec.Schema schema,String value) {
        return NativeHealthChangesCodec.decode(schema,hex(value));
    }
    @Test public void nativeChangeKeepsFullWidthAnchorsAndOpaqueObjectData() {
        // Tags/types from HealthDaemon23S303 writeTo/readFrom, independent literal wire fixture.
        try(var node=read(NativeHealthChangesCodec.Schema.CHANGE,
                "0807 108080808010 18ffffffffffffffffff01 220300ff80 2200 3000 388180808010 4001")) {
            assertEquals(Integer.valueOf(7),node.int32(1));
            assertEquals(Long.valueOf(4294967296L),node.int64(2));
            assertEquals(Long.valueOf(-1L),node.int64(3));
            assertEquals(Long.valueOf(4294967297L),node.int64(7));
            assertEquals(Boolean.FALSE,node.bool(6)); assertEquals(Boolean.TRUE,node.bool(8));
            assertEquals(2,node.byteCount(4)); assertArrayEquals(hex("00ff80"),node.bytesAt(4,0));
            assertArrayEquals(new byte[0],node.bytesAt(4,1));
            byte[] copy=node.bytesAt(4,0); copy[0]=99; assertEquals(0,node.bytesAt(4,0)[0]);
        }
    }
    @Test public void changeSetAndStatusParseNativeEntityAndAnchorHierarchy() {
        try(var set=read(NativeHealthChangesCodec.Schema.CHANGE_SET,
                "0a0f 2a0d 0807 1000 1a07 0a03e29883 1000 12020001 19000000000000f83f 2200 2800")) {
            var change=set.children(1).get(0); var anchor=change.children(5).get(0);
            var entity=anchor.children(3).get(0);
            assertEquals(Integer.valueOf(7),anchor.int32(1)); assertEquals(Long.valueOf(0),anchor.int64(2));
            assertEquals("☃",entity.string(1)); assertEquals(Long.valueOf(0),entity.int64(2));
            assertArrayEquals(hex("0001"),set.bytes(2)); assertEquals(1.5,set.doubleValue(3),0);
            assertArrayEquals(new byte[0],set.bytes(4)); assertEquals(Integer.valueOf(0),set.int32(5));
        }
        try(var status=read(NativeHealthChangesCodec.Schema.STATUS,"0802 1208 0807 108080808010")) {
            assertEquals(Integer.valueOf(2),status.int32(1));
            assertEquals(Long.valueOf(4294967296L),status.children(2).get(0).int64(2));
        }
    }
    @Test public void absentValuesStayAbsentAndPresentZeroStaysPresent() {
        try(var empty=read(NativeHealthChangesCodec.Schema.CHANGE,"");
            var zero=read(NativeHealthChangesCodec.Schema.CHANGE,"0800 1000 3000 4000 4a00 5200")) {
            assertNull(empty.int32(1)); assertNull(empty.int64(2)); assertNull(empty.bool(6));
            assertTrue(empty.children(9).isEmpty()); assertNull(empty.bytes(10));
            assertEquals(Integer.valueOf(0),zero.int32(1)); assertEquals(Long.valueOf(0),zero.int64(2));
            assertEquals(Boolean.FALSE,zero.bool(6)); assertEquals(1,zero.children(9).size());
            assertArrayEquals(new byte[0],zero.bytes(10));
        }
        try(var signed=read(NativeHealthChangesCodec.Schema.STATUS,"08ffffffffffffffffff01")) {
            assertEquals(Integer.valueOf(-1),signed.int32(1));
        }
    }
    @Test public void unknownFieldsRemainInOriginalWithoutInventingSemantics() {
        byte[] original=hex("a00101 a9010102030405060708 b201020001 bd0101020304");
        try(var node=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.CHANGE,original)) {
            assertEquals(4,node.unknownFields()); assertArrayEquals(original,node.original());
            original[0]=0; assertNotEquals(0,node.original()[0]);
            assertThrows(IllegalArgumentException.class,() -> node.int64(1));
        }
    }
    @Test public void invalidKnownFieldsRejectTheWholeTree() {
        for(String value:new String[]{"08010802","0a00","1080","088080808010","3002","2a0100",
                "220500","4a0100","40014000","00","0b","18ffffffffffffffffff02"}) {
            assertThrows(value,IllegalArgumentException.class,
                    () -> read(NativeHealthChangesCodec.Schema.CHANGE,value));
        }
        for(String value:new String[]{"0a02c328","0a03eda080","0a017f0a017f","10001001"}) {
            assertThrows(value,IllegalArgumentException.class,
                    () -> read(NativeHealthChangesCodec.Schema.ENTITY_IDENTIFIER,value));
        }
        assertThrows(IllegalArgumentException.class,() -> read(NativeHealthChangesCodec.Schema.CHANGE_SET,"190001"));
        assertThrows(IllegalArgumentException.class,() -> NativeHealthChangesCodec.decode(
                NativeHealthChangesCodec.Schema.CHANGE_SET,new byte[NativeHealthSyncCodec.MAX_BYTES+1]));
    }
    @Test public void aggregateNestedBudgetAndRepeatedItemLimitAreIndependent() {
        ByteArrayOutputStream wire=new ByteArrayOutputStream();
        // 4096 outer + 4096 inner tags exactly exhaust the shared budget.
        for(int i=0;i<4096;i++) wire.writeBytes(hex("0a020801"));
        try(var node=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.CHANGE_SET,wire.toByteArray())) {
            assertEquals(4096,node.children(1).size());
        }
        wire.reset(); wire.writeBytes(hex("0a0408013000"));
        for(int i=1;i<4096;i++) wire.writeBytes(hex("0a020801"));
        assertThrows(IllegalArgumentException.class,() -> NativeHealthChangesCodec.decode(
                NativeHealthChangesCodec.Schema.CHANGE_SET,wire.toByteArray()));
        wire.reset(); for(int i=0;i<4097;i++) wire.writeBytes(hex("0a00"));
        assertThrows(IllegalArgumentException.class,() -> NativeHealthChangesCodec.decode(
                NativeHealthChangesCodec.Schema.CHANGE_SET,wire.toByteArray()));
    }
    @Test public void parentCloseInvalidatesChildrenAndOwnedCopiesRemainIndependent() {
        var parent=read(NativeHealthChangesCodec.Schema.CHANGE_SET,"0a022200");
        var child=parent.children(1).get(0); byte[] copy=child.original();
        assertThrows(UnsupportedOperationException.class,() -> parent.children(1).clear());
        parent.close(); parent.close(); assertArrayEquals(hex("2200"),copy);
        assertThrows(IllegalStateException.class,child::original);
        assertThrows(IllegalStateException.class,() -> parent.children(1));
    }
    @Test public void envelopeIntegrationPreservesAbsenceAndRawDoubleBits() {
        try(var envelope=NativeHealthSyncCodec.decodeEnvelope(hex("3a022800 42020800"));
            var changes=envelope.changeSet(); var status=envelope.status()) {
            assertEquals(Integer.valueOf(0),changes.int32(5)); assertEquals(Integer.valueOf(0),status.int32(1));
        }
        try(var envelope=NativeHealthSyncCodec.decodeEnvelope(new byte[0])) {
            assertNull(envelope.changeSet()); assertNull(envelope.status());
        }
        try(var node=read(NativeHealthChangesCodec.Schema.CHANGE_SET,"190000000000000080")) {
            assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(node.doubleValue(3)));
        }
    }
}
