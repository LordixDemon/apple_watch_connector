package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.Test;

public final class HealthObservationRecordTest {
    private static final String UUID="000102030405060708090a0b0c0d0e0f";
    private static final String SAMPLE="0a120a10"+UUID+"100719000000000000000021000000000000f03f";
    // Independent PB literal: two quantity samples sharing their header, values 0 and 2.
    private static final String COLLECTION="22330a28"+SAMPLE+"110000000000000000"
            +"22330a28"+SAMPLE+"110000000000000040";
    private static byte[] cell(int field,int item,String wire) {
        byte[] pb=HexFormat.of().parseHex(wire);
        return ByteBuffer.allocate(8+pb.length).putInt(field).putInt(item).put(pb).array();
    }
    @Test public void selectsExactOriginalItemAndPreservesZeroInsteadOfMissing() {
        byte[] input=cell(4,1,COLLECTION);
        try(var record=HealthObservationRecord.decode("QUANTITY",input)) {
            Arrays.fill(input,(byte)0);
            assertEquals(Double.valueOf(2),record.canonicalValue());
            assertNull(record.originalValue());assertNull(record.originalUnit());assertNull(record.categoryValue());
            assertArrayEquals(HexFormat.of().parseHex(UUID),record.objectKey());
            assertEquals("HKQuantityTypeIdentifierStepCount",record.definition("23S303").identifier());
            assertEquals("count",record.definition("23S303").canonicalUnit());
            assertEquals(2,record.collection().children(4).size());
            assertEquals(Long.valueOf(7),record.header().dataType());
        }
        try(var zero=HealthObservationRecord.decode("QUANTITY",cell(4,0,COLLECTION))) {
            assertEquals(Double.valueOf(0),zero.canonicalValue());
        }
    }
    @Test public void kindFieldIndexAndPayloadBudgetsCannotSelectDifferentRecords() {
        for(byte[] bytes:new byte[][]{cell(3,0,COLLECTION),cell(4,-1,COLLECTION),cell(4,2,COLLECTION),new byte[7],
                new byte[NativeHealthSyncCodec.MAX_BYTES+9]})
            assertThrows(IllegalArgumentException.class,()->HealthObservationRecord.decode("QUANTITY",bytes));
        assertThrows(IllegalArgumentException.class,()->HealthObservationRecord.decode("UNKNOWN",cell(4,0,COLLECTION)));
        assertThrows(IllegalArgumentException.class,()->HealthObservationRecord.decode(null,cell(4,0,COLLECTION)));
        assertThrows(IllegalArgumentException.class,()->HealthObservationRecord.decode("SOURCE",cell(0,1,"2a10"+UUID)));
    }
    @Test public void sourceDeviceAndDefaultsKeysUseExactStoredPresenceAndWidth() {
        try(var source=HealthObservationRecord.decode("SOURCE",cell(0,0,"2a10"+UUID));
            var device=HealthObservationRecord.decode("DEVICE",cell(0,0,"4a10"+UUID));
            var nil=HealthObservationRecord.decode("DEFAULTS",cell(0,0,""));
            var zero=HealthObservationRecord.decode("PROTECTED_DEFAULTS",cell(0,0,"08001200"))) {
            assertArrayEquals(HexFormat.of().parseHex(UUID),source.objectKey());
            assertArrayEquals(source.objectKey(),device.objectKey());
            assertArrayEquals(new byte[]{0,0},nil.objectKey());
            assertFalse(Arrays.equals(nil.objectKey(),zero.objectKey()));
            assertNull(source.header());assertNull(device.definition("23S303"));assertNull(nil.canonicalValue());
        }
        for(String kind:new String[]{"SOURCE","DEVICE"})
            assertThrows(IllegalArgumentException.class,()->HealthObservationRecord.decode(kind,cell(0,0,"")));
    }
    @Test public void wrongBuildClassFullWidthCodeAndReservedTypesStayUnknown() {
        try(var record=HealthObservationRecord.decode("QUANTITY",cell(4,0,COLLECTION));
            var mismatch=HealthObservationRecord.decode("CATEGORY",cell(3,0,"1a2c0a28"+SAMPLE+"1000"));
            var wide=HealthObservationRecord.decode("QUANTITY",cell(4,0,"222e0a2c"+SAMPLE.replace("1007","108780808010")))) {
            assertNull(record.definition("23S304"));assertNull(record.definition(null));
            assertNull(mismatch.definition("23S303"));assertEquals(Long.valueOf(0),mismatch.categoryValue());
            assertEquals(Long.valueOf(4294967303L),wide.header().dataType());assertNull(wide.definition("23S303"));
        }
    }
    @Test public void missingValuesAndNonfiniteNumbersArePreservedWithoutFabrication() {
        try(var absent=HealthObservationRecord.decode("QUANTITY",cell(4,0,"222a0a28"+SAMPLE));
            var nan=HealthObservationRecord.decode("QUANTITY",cell(4,0,"22330a28"+SAMPLE+"11000000000000f87f"))) {
            assertNull(absent.canonicalValue());assertNull(absent.originalValue());
            assertTrue(Double.isNaN(nan.canonicalValue()));
        }
    }
    @Test public void malformedHeaderAndUnknownSchemaNeverBecomeTypedRecords() {
        String noDates="0a120a10"+UUID+"1007";
        for(String wire:new String[]{"22180a16"+noDates,"222c0a28"+SAMPLE+"7800"})
            assertThrows(IllegalArgumentException.class,()->HealthObservationRecord.decode("QUANTITY",cell(4,0,wire)));
        assertThrows(IllegalArgumentException.class,()->HealthObservationRecord.decode("SOURCE",cell(0,0,"2a01ff")));
    }
    @Test public void closeInvalidatesParentChildAndTypedAccessButNeverLogsValues() {
        var record=HealthObservationRecord.decode("QUANTITY",cell(4,0,COLLECTION));
        var selected=record.selected();var root=record.collection();
        assertEquals("Original Health observation (values withheld)",record.toString());
        record.close();record.close();
        assertThrows(IllegalStateException.class,record::canonicalValue);
        assertThrows(IllegalStateException.class,record::objectKey);
        assertThrows(IllegalStateException.class,()->record.definition("23S303"));
        assertThrows(IllegalStateException.class,()->selected.doubleValue(2));
        assertThrows(IllegalStateException.class,()->root.children(4));
    }
}
