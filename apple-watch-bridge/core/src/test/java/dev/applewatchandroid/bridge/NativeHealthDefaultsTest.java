package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class NativeHealthDefaultsTest {
    private static final NativeHealthReceivePolicy.Entity DEFAULTS=NativeHealthReceivePolicy.Entity.DEFAULTS;
    private static byte[] join(byte[]... parts) { var out=new ByteArrayOutputStream();for(byte[] part:parts)out.writeBytes(part);return out.toByteArray(); }
    private static byte[] vi(int field,long value) {
        var out=new ByteArrayOutputStream();out.write(field<<3);
        do { int b=(int)value&127;value>>>=7;out.write(value==0 ? b : b|128); }while(value!=0);return out.toByteArray();
    }
    private static byte[] blob(int field,byte[] value) { return join(new byte[]{(byte)((field<<3)|2)},varint(value.length),value); }
    private static byte[] varint(int n) { byte[] tagged=vi(1,n);return Arrays.copyOfRange(tagged,1,tagged.length); }
    private static byte[] string(int field,String value) { return blob(field,value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] dbl(int field,double value) { return join(new byte[]{(byte)((field<<3)|1)},ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()); }
    private static byte[] pair(double date,byte[]... values) { return join(string(1,"secret-key"),dbl(2,date),join(values)); }
    private static byte[] dictionary(byte[]... pairs) {
        var out=new ByteArrayOutputStream();out.writeBytes(join(vi(1,1),string(2,"private-domain")));
        for(byte[] pair:pairs)out.writeBytes(blob(3,pair));return out.toByteArray();
    }
    private static NativeHealthDefaults read(byte[] wire) { return NativeHealthDefaults.decode(DEFAULTS,"23S303",wire); }

    @Test public void doubleWinsOverAllOtherPresentFields() {
        try(var data=read(dictionary(pair(0,dbl(4,-0.0),vi(3,5),string(5,"s"),blob(6,new byte[]{1}))))) {
            var entry=data.entries().get(0);assertEquals(NativeHealthDefaults.Kind.DOUBLE,entry.kind());
            assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(entry.doubleValue()));
            assertNull(entry.integerValue());assertNull(entry.stringValue());assertNull(entry.bytesValue());
        }
    }
    @Test public void integerWinsOverStringAndBytesAndPreservesSigned64Bits() {
        try(var data=read(dictionary(pair(1,vi(3,Long.MIN_VALUE),string(5,"s"),blob(6,new byte[]{1}))))) {
            var entry=data.entries().get(0);assertEquals(NativeHealthDefaults.Kind.INTEGER,entry.kind());
            assertEquals(Long.valueOf(Long.MIN_VALUE),entry.integerValue());assertNull(entry.stringValue());assertNull(entry.bytesValue());
        }
    }
    @Test public void stringWinsOverBytesIncludingEmptyPresentString() {
        try(var data=read(dictionary(pair(1,string(5,""),blob(6,new byte[]{1}))))) {
            var entry=data.entries().get(0);assertEquals(NativeHealthDefaults.Kind.STRING,entry.kind());assertEquals("",entry.stringValue());
        }
    }
    @Test public void bytesAreRawAndOwnedRatherThanDecodedAsAnArchive() {
        byte[] bytes={0,(byte)255,2};
        try(var data=read(dictionary(pair(1,blob(6,bytes))))) {
            var entry=data.entries().get(0);assertEquals(NativeHealthDefaults.Kind.BYTES,entry.kind());
            byte[] first=entry.bytesValue();assertArrayEquals(bytes,first);first[0]=7;assertArrayEquals(bytes,entry.bytesValue());
        }
    }
    @Test public void emptyBytesAreAValueWhileAbsentValueIsATombstone() {
        try(var data=read(dictionary(pair(0,blob(6,new byte[0])),pair(1)))) {
            assertEquals(NativeHealthDefaults.Kind.BYTES,data.entries().get(0).kind());
            assertArrayEquals(new byte[0],data.entries().get(0).bytesValue());
            assertEquals(NativeHealthDefaults.Kind.TOMBSTONE,data.entries().get(1).kind());
        }
    }
    @Test public void zeroValuesArePresentAndNotDeletions() {
        try(var data=read(dictionary(pair(0,vi(3,0)),pair(0,dbl(4,0))))) {
            assertEquals(Long.valueOf(0),data.entries().get(0).integerValue());assertEquals(Double.valueOf(0),data.entries().get(1).doubleValue());
        }
    }
    @Test public void categoriesAreExactlyOneAnd105WithoutTruncationOrFallback() {
        try(var data=NativeHealthDefaults.decode(NativeHealthReceivePolicy.Entity.PROTECTED_DEFAULTS,"23S303",join(vi(1,105),string(2,"")))) {
            assertEquals("",data.domain());assertTrue(data.entries().isEmpty());
        }
        for(long category:new long[]{0,105,4294967297L})assertThrows(IllegalArgumentException.class,()->read(join(vi(1,category),string(2,""))));
        assertThrows(IllegalArgumentException.class,()->read(string(2,"")));
        assertThrows(IllegalArgumentException.class,()->NativeHealthDefaults.decode(NativeHealthReceivePolicy.Entity.PROTECTED_DEFAULTS,"23S303",dictionary()));
    }
    @Test public void missingKeysAndDatesDoNotBecomeEmptyStringsOrZeroDates() {
        for(byte[] wire:new byte[][]{dictionary(dbl(2,0)),dictionary(string(1,""))})
            assertThrows(IllegalArgumentException.class,()->read(wire));
        try(var valid=read(join(vi(1,1),string(2,""),blob(3,join(string(1,""),dbl(2,0)))))) {
            assertEquals("",valid.domain());assertEquals("",valid.entries().get(0).key());assertEquals(0,valid.entries().get(0).date(),0);
        }
    }
    @Test public void absentDomainUsesNativeEmptyNamespaceButRetainsOriginalPresence() {
        byte[] absent=join(vi(1,1),blob(3,pair(0,vi(3,1))));
        try(var data=read(absent);var empty=read(join(vi(1,1),string(2,""),blob(3,pair(0,vi(3,1)))))) {
            assertFalse(data.hasDomain());assertTrue(empty.hasDomain());assertEquals("",data.domain());
            assertArrayEquals(absent,data.original());
            try(var a=HealthDefaultsRecord.decode(DEFAULTS,"23S303",HealthDefaultsRecord.encode(data,data.entries().get(0)));
                var b=HealthDefaultsRecord.decode(DEFAULTS,"23S303",HealthDefaultsRecord.encode(empty,empty.entries().get(0)))) {
                assertArrayEquals(a.key(),b.key());
            }
        }
    }
    @Test public void unsupportedBuildEntityUnknownFieldsAndNonfiniteDatesAreHeld() {
        assertThrows(IllegalArgumentException.class,()->NativeHealthDefaults.decode(DEFAULTS,"23S304",dictionary()));
        assertThrows(IllegalArgumentException.class,()->NativeHealthDefaults.decode(NativeHealthReceivePolicy.Entity.SOURCE,"23S303",dictionary()));
        for(byte[] wire:new byte[][]{join(dictionary(),vi(5,0)),dictionary(join(pair(0),vi(7,0))),dictionary(pair(Double.NaN)),dictionary(pair(Double.POSITIVE_INFINITY))})
            assertThrows(IllegalArgumentException.class,()->read(wire));
    }
    @Test public void finiteNativeReferenceSecondsAreKeptWithoutUnixOrMillisecondConversion() {
        try(var data=read(dictionary(pair(-1),pair(0.125),pair(800000000.5)))) {
            assertEquals(-1,data.entries().get(0).date(),0);assertEquals(.125,data.entries().get(1).date(),0);
            assertEquals(800000000.5,data.entries().get(2).date(),0);
        }
    }
    @Test public void valueDoubleBitsIncludingNonfiniteAreKeptWithoutInventedCoercion() {
        try(var data=read(dictionary(pair(0,dbl(4,Double.NaN)),pair(0,dbl(4,Double.NEGATIVE_INFINITY))))) {
            assertTrue(data.entries().get(0).doubleValue().isNaN());
            assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),data.entries().get(1).doubleValue());
        }
    }
    @Test public void policyTwoRetainsEqualAndNewerStoredDatesIncludingTombstones() {
        assertTrue(NativeHealthDefaults.replaces(null,0));assertTrue(NativeHealthDefaults.replaces(1.0,2));
        assertFalse(NativeHealthDefaults.replaces(2.0,2));assertFalse(NativeHealthDefaults.replaces(3.0,2));
        assertFalse(NativeHealthDefaults.replaces(-0.0,0));assertTrue(NativeHealthDefaults.replaces(-2.0,-1));
        assertThrows(IllegalArgumentException.class,()->NativeHealthDefaults.replaces(Double.NaN,0));
    }
    @Test public void duplicateKeyOrderIsRetainedSoEqualDateKeepsFirstAndNewestWins() {
        try(var data=read(dictionary(pair(2,vi(3,2)),pair(2,vi(3,3)),pair(1,vi(3,1)),pair(3)))) {
            Double date=null;NativeHealthDefaults.Entry selected=null;
            for(var entry:data.entries())if(NativeHealthDefaults.replaces(date,entry.date())) { selected=entry;date=entry.date(); }
            assertSame(data.entries().get(3),selected);assertEquals(NativeHealthDefaults.Kind.TOMBSTONE,selected.kind());
        }
    }
    @Test public void nativeIdentityAndDictionaryOriginalArePreservedWithoutAdoption() {
        byte[] wire=join(dictionary(pair(0,vi(3,1))),blob(4,join(blob(1,new byte[16]),blob(2,new byte[16]),string(3,"origin"))));
        try(var data=read(wire)) {
            assertTrue(data.hasSyncIdentity());assertArrayEquals(wire,data.original());wire[0]=0;assertNotEquals(0,data.original()[0]);
        }
    }
    @Test public void recordSelectorCannotEscapeItsDictionaryAndOwnsLifetime() {
        byte[] encoded;NativeHealthDefaults.Entry entry;
        try(var data=read(dictionary(pair(0,vi(3,8)),pair(1)))) {
            entry=data.entries().get(1);encoded=HealthDefaultsRecord.encode(data,entry);
            try(var other=read(dictionary(pair(0)))) {
                assertThrows(IllegalArgumentException.class,()->HealthDefaultsRecord.encode(data,other.entries().get(0)));
            }
        }
        assertThrows(IllegalStateException.class,entry::key);
        var record=HealthDefaultsRecord.decode(DEFAULTS,"23S303",encoded);var selected=record.entry();
        assertEquals(NativeHealthDefaults.Kind.TOMBSTONE,selected.kind());record.close();record.close();
        assertThrows(IllegalStateException.class,record::entry);assertThrows(IllegalStateException.class,selected::date);
        for(int index:new int[]{-1,2}) {
            byte[] invalid=encoded.clone();ByteBuffer.wrap(invalid).putInt(4,index);
            assertThrows(IllegalArgumentException.class,()->HealthDefaultsRecord.decode(DEFAULTS,"23S303",invalid));
        }
    }
    @Test public void keyFramingDoesNotCollapseDomainAndKeySeparatorsOrEmptyFields() {
        assertFalse(Arrays.equals(HealthDefaultsRecord.key("a\nb","c"),HealthDefaultsRecord.key("a","b\nc")));
        assertFalse(Arrays.equals(HealthDefaultsRecord.key("","a"),HealthDefaultsRecord.key("a","")));
        assertArrayEquals(HealthDefaultsRecord.key("домен","ключ"),HealthDefaultsRecord.key("домен","ключ"));
    }
    @Test public void mirrorAadCannotBeReusedAsAnObservationOrAnotherEntity() {
        String token="a".repeat(64);
        assertFalse(Arrays.equals(HealthDefaultsRecord.aad(token,"DEFAULTS",token,token),HealthObservationCipher.aad(token,"DEFAULTS",token,token)));
        assertFalse(Arrays.equals(HealthDefaultsRecord.aad(token,"DEFAULTS",token,token),HealthDefaultsRecord.aad(token,"PROTECTED_DEFAULTS",token,token)));
        assertThrows(IllegalArgumentException.class,()->HealthDefaultsRecord.aad(token,"SOURCE",token,token));
        assertThrows(IllegalArgumentException.class,()->HealthDefaultsRecord.aad("", "DEFAULTS",token,token));
    }
    @Test public void loggingNeverContainsKeysDomainsValuesOrRawBytes() {
        try(var data=read(dictionary(pair(0,string(5,"private-value"))));
            var record=HealthDefaultsRecord.decode(DEFAULTS,"23S303",HealthDefaultsRecord.encode(data,data.entries().get(0)))) {
            String log=data.toString()+data.entries().get(0)+record;
            for(String secret:new String[]{"private-domain","secret-key","private-value"})assertFalse(log.contains(secret));
        }
    }
}
