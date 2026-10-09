package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.Test;

public final class NativeHealthObjectCodecTest {
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s.replace(" ","")); }
    private static NativeHealthChangesCodec.Node read(NativeHealthChangesCodec.Schema schema,String wire) {
        return NativeHealthChangesCodec.decode(schema,hex(wire));
    }
    private static final String SAMPLE="0a12 0a10 000102030405060708090a0b0c0d0e0f 1000"
            +" 190000000000000000 21000000000000f03f";

    @Test public void entityNamespaceOverridesLegacyObjectType() {
        for(String fixture:new String[]{"0802 4a02100b","080a 4a02100d","080a 4a021004","0802 4a021010"}) {
            try(var change=read(NativeHealthChangesCodec.Schema.CHANGE,fixture)) {
                var expected=switch(change.children(9).get(0).int64(2).intValue()) {
                    case 11 -> NativeHealthChangesCodec.Schema.SOURCE;
                    case 13 -> NativeHealthChangesCodec.Schema.DEVICE;
                    case 4 -> NativeHealthChangesCodec.Schema.OBJECT_COLLECTION;
                    default -> NativeHealthChangesCodec.Schema.DOMAIN_DICTIONARY;
                };
                assertEquals(expected,NativeHealthObjectCodec.schemaFor(change));
            }
        }
    }
    @Test public void presentUnknownOrEmptySchemaNeverFallsBack() {
        for(String fixture:new String[]{"080a 4a00","080a 4a040a00100b","080a 4a021063",
                "080a 4a06108b80808010","080a 4a04100b1800","080a 4a070a0378797a100b"}) {
            try(var change=read(NativeHealthChangesCodec.Schema.CHANGE,fixture)) {
                assertNull(NativeHealthObjectCodec.schemaFor(change));
            }
        }
    }
    @Test public void absentIdentifierUsesNativeLegacyTypes() {
        try(var source=read(NativeHealthChangesCodec.Schema.CHANGE,"080a");
            var samples=read(NativeHealthChangesCodec.Schema.CHANGE,"0801");
            var missing=read(NativeHealthChangesCodec.Schema.CHANGE,"")) {
            assertEquals(NativeHealthChangesCodec.Schema.SOURCE,NativeHealthObjectCodec.schemaFor(source));
            assertEquals(NativeHealthChangesCodec.Schema.OBJECT_COLLECTION,NativeHealthObjectCodec.schemaFor(samples));
            assertNull(NativeHealthObjectCodec.schemaFor(missing));
        }
    }
    @Test public void nestedCollectionsAreNotIndividualObjectDataSamples() {
        try(var collection=read(NativeHealthChangesCodec.Schema.OBJECT_COLLECTION,
                "1a2c 0a28 "+SAMPLE+" 1000 2233 0a28 "+SAMPLE+" 110000000000000000")) {
            var counts=NativeHealthObjectCodec.counts(collection);
            assertEquals(1,counts.categorySamples());assertEquals(1,counts.quantitySamples());
            assertEquals(2,counts.completeSampleHeaders());assertEquals(0,counts.opaqueChildren());
            var sample=collection.children(4).get(0).children(1).get(0);
            var header=NativeHealthObjectCodec.header(sample);
            assertEquals(UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f"),header.uuid());
            assertEquals(Long.valueOf(0),header.dataType());assertTrue(header.structurallyComplete());
            assertEquals(Double.valueOf(0),collection.children(4).get(0).doubleValue(2));
            assertNull(collection.children(4).get(0).doubleValue(3));
            assertNull(collection.children(4).get(0).string(4));
            assertFalse(header.toString().contains(header.uuid().toString()));
        }
    }
    @Test public void incompleteUuidAndNonfiniteOrInvertedDatesStayUnusable() {
        for(String fixture:new String[]{"", "0a030a0100 1000 190000000000000000 210000000000000000",
                SAMPLE.replace("21000000000000f03f","21000000000000f0bf"),
                SAMPLE.replace("190000000000000000","19000000000000f87f")}) {
            try(var sample=read(NativeHealthChangesCodec.Schema.SAMPLE,fixture)) {
                assertFalse(NativeHealthObjectCodec.header(sample).structurallyComplete());
            }
        }
    }
    @Test public void nativeFullWidthFieldsAndDatesPreservePresence() {
        try(var source=read(NativeHealthChangesCodec.Schema.SOURCE,"208080808010 310000000000000000 3800");
            var sample=read(NativeHealthChangesCodec.Schema.SAMPLE,"108180808010");
            var quantity=read(NativeHealthChangesCodec.Schema.QUANTITY_SAMPLE,"308080808010 51000000000000f83f")) {
            assertEquals(Long.valueOf(4294967296L),source.int64(4));assertEquals(Double.valueOf(0),source.doubleValue(6));
            assertEquals(Boolean.FALSE,source.bool(7));assertNull(source.string(1));
            assertEquals(Long.valueOf(4294967297L),sample.int64(2));
            assertEquals(Long.valueOf(4294967296L),quantity.int64(6));assertEquals(Double.valueOf(1.5),quantity.doubleValue(10));
        }
    }
    @Test public void opaqueCollectionsAndNestedUnknownsAreCountedSeparately() {
        byte[] original=hex("2a0300ff80 920100 a2010410016001 c2010100");
        try(var collection=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.OBJECT_COLLECTION,original)) {
            var counts=NativeHealthObjectCodec.counts(collection);
            assertEquals(2,counts.opaqueChildren());assertEquals(3,counts.unknownFields());
            assertArrayEquals(original,collection.original());
        }
    }
    @Test public void wrongWireAndDuplicateMetadataRejectWithoutCoercion() {
        for(String fixture:new String[]{"0a00 0a00","1900","1202c328","2200"}) {
            assertThrows(IllegalArgumentException.class,()->read(NativeHealthChangesCodec.Schema.METADATA_PAIR,fixture));
        }
    }
    @Test public void objectCollectionChildrenShareBudgetAndLifetime() {
        ByteArrayOutputStream wire=new ByteArrayOutputStream();
        for(int i=0;i<4096;i++)wire.writeBytes(hex("1a040a021000"));
        assertThrows(IllegalArgumentException.class,()->NativeHealthChangesCodec.decode(
                NativeHealthChangesCodec.Schema.OBJECT_COLLECTION,wire.toByteArray()));
        var collection=read(NativeHealthChangesCodec.Schema.OBJECT_COLLECTION,"1a020a00");
        var child=collection.children(3).get(0);collection.close();
        assertThrows(IllegalStateException.class,()->child.children(1));
    }
    @Test public void metadataRetainsNativeAlternativesWithoutInventedPrecedence() {
        try(var pair=read(NativeHealthChangesCodec.Schema.METADATA_PAIR,
                "0a016b 120176 190000000000000000 208080808010 29000000000000f83f"
                +" 320c09000000000000004012016d 3a0200ff")) {
            assertEquals("k",pair.string(1));assertEquals("v",pair.string(2));
            assertEquals(Double.valueOf(0),pair.doubleValue(3));assertEquals(Long.valueOf(4294967296L),pair.int64(4));
            assertEquals(Double.valueOf(1.5),pair.doubleValue(5));
            assertEquals(Double.valueOf(2),pair.children(6).get(0).doubleValue(1));
            assertEquals("m",pair.children(6).get(0).string(2));assertArrayEquals(hex("00ff"),pair.bytes(7));
        }
    }
    @Test public void nativeDeviceProvenanceAndDefaultsHaveSeparateSchemas() {
        try(var device=read(NativeHealthChangesCodec.Schema.DEVICE,"0a016e 4a0100 510000000000000000 5a020a00 620162");
            var provenance=read(NativeHealthChangesCodec.Schema.PROVENANCE,"0a0131 1a0100 220101 2a0132 320133 3a0134 4000 4801 5002 5a0102");
            var defaults=read(NativeHealthChangesCodec.Schema.DOMAIN_DICTIONARY,"088080808010 120164 1a030a016b")) {
            assertEquals("n",device.string(1));assertEquals("b",device.string(12));
            assertEquals(Double.valueOf(0),device.doubleValue(10));
            assertArrayEquals(new byte[0],device.children(11).get(0).bytes(1));
            assertEquals("1",provenance.string(1));assertEquals(Integer.valueOf(0),provenance.int32(8));
            assertEquals(Integer.valueOf(2),provenance.int32(10));assertArrayEquals(hex("02"),provenance.bytes(11));
            assertEquals(Long.valueOf(4294967296L),defaults.int64(1));
            assertEquals("k",defaults.children(3).get(0).string(1));
            assertNull(defaults.children(3).get(0).doubleValue(2));
        }
    }
    @Test public void deletionAndSeriesAreNativeChildrenWithOptionalFields() {
        try(var collection=read(NativeHealthChangesCodec.Schema.OBJECT_COLLECTION,"4a2a0a28"+SAMPLE);
            var quantity=read(NativeHealthChangesCodec.Schema.QUANTITY_SAMPLE,
                    "621b09000000000000f03f110000000000000040190000000000000000")) {
            assertEquals(1,NativeHealthObjectCodec.counts(collection).deletedSamples());
            assertEquals(1,NativeHealthObjectCodec.counts(collection).completeSampleHeaders());
            var datum=quantity.children(12).get(0);
            assertEquals(Double.valueOf(1),datum.doubleValue(1));assertEquals(Double.valueOf(2),datum.doubleValue(2));
            assertEquals(Double.valueOf(0),datum.doubleValue(3));assertNull(quantity.doubleValue(2));
        }
    }
    @Test public void observedDefaultsAbsentZeroEmptyAndFullWidthAreDifferentKeys() {
        assertArrayEquals(hex("0000"),NativeHealthObjectCodec.defaultsObservationKey(null,null));
        assertArrayEquals(hex("01000000000000000000"),NativeHealthObjectCodec.defaultsObservationKey(0L,null));
        assertArrayEquals(hex("000100000000"),NativeHealthObjectCodec.defaultsObservationKey(null,""));
        assertArrayEquals(hex("0100000001000000000100000003e29883"),
                NativeHealthObjectCodec.defaultsObservationKey(4294967296L,"☃"));
        assertFalse(java.util.Arrays.equals(NativeHealthObjectCodec.defaultsObservationKey(null,""),
                NativeHealthObjectCodec.defaultsObservationKey(0L,"")));
    }
}
