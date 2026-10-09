package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.HashSet;
import org.junit.Test;

public final class NativeHealthTypeCatalogTest {
    @Test public void exactNativeTableHas267DefinedAnd75ReservedSlots() {
        int defined=0,quantity=0,category=0;var identifiers=new HashSet<String>();
        for(long code=0;code<342;code++) {
            var type=NativeHealthTypeCatalog.lookup("23S303",code);
            if(type==null)continue;
            assertEquals(code,type.code());assertTrue(identifiers.add(type.identifier()));defined++;
            if(type.quantity())quantity++;
            if(type.category())category++;
        }
        assertEquals(267,defined);assertEquals(126,quantity);assertEquals(77,category);
    }
    @Test public void nativeCanonicalUnitsAreNotDisplayUnitGuesses() {
        assertEquals("count/s",NativeHealthTypeCatalog.lookup("23S303",5L).canonicalUnit());
        assertEquals("m",NativeHealthTypeCatalog.lookup("23S303",8L).canonicalUnit());
        assertEquals("kcal",NativeHealthTypeCatalog.lookup("23S303",10L).canonicalUnit());
        assertEquals("%",NativeHealthTypeCatalog.lookup("23S303",14L).canonicalUnit());
        assertEquals("min",NativeHealthTypeCatalog.lookup("23S303",75L).canonicalUnit());
        assertEquals("HKCategoryTypeIdentifierSleepAnalysis",NativeHealthTypeCatalog.lookup("23S303",63L).identifier());
        assertNull(NativeHealthTypeCatalog.lookup("23S303",63L).canonicalUnit());
        // Native UVExposure definition has a nil canonicalUnit. Never manufacture "count".
        assertTrue(NativeHealthTypeCatalog.lookup("23S303",89L).quantity());
        assertNull(NativeHealthTypeCatalog.lookup("23S303",89L).canonicalUnit());
        assertEquals("HKDataTypeAppleSleepScore",NativeHealthTypeCatalog.lookup("23S303",341L).identifier());
    }
    @Test public void foreignBuildNilNegativeReservedOutOfRangeAndOverflowNeverNarrow() {
        for(Long code:new Long[]{null,-1L,6L,11L,340L,342L,Long.MAX_VALUE,Long.MIN_VALUE,4294967301L})
            assertNull(NativeHealthTypeCatalog.lookup("23S303",code));
        for(String build:new String[]{null,"","23S304","23S303 "})
            assertNull(NativeHealthTypeCatalog.lookup(build,5L));
    }
}
