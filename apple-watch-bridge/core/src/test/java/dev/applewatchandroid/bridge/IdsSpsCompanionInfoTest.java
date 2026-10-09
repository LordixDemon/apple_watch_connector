package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class IdsSpsCompanionInfoTest {
    static final String UDID = "00008020-0011223344556677";

    @Test public void independentPlistFixtureIsCanonicalAndDoesNotLogValues() throws Exception {
        byte[] fixture;
        try (var source = getClass().getResourceAsStream("/ids/sps-independent.bplist")) {
            assertNotNull(source); fixture = source.readAllBytes();
        }
        try (var info = IdsSpsCompanionInfo.parse(fixture)) {
            var data = (Map<?, ?>) AppleBinaryPropertyList.decode(info.serialize());
            assertEquals(UDID, data.get("sps-device-udid"));
            assertEquals(List.of("+12025550123", "tel:+12025550124"), data.get("sps-phone-numbers"));
            assertTrue(info.summary().contains("phoneNumberCount=2"));
            assertFalse(info.summary().contains(UDID));
            assertFalse(info.summary().contains("1202555"));
        }
    }

    @Test public void unorderedDuplicatesAndUnrelatedFieldsDoNotChangeStoredMetadata() {
        var first = Map.of("command",5L,"sps-device-udid",UDID,"sps-phone-numbers",List.of("+12025550123", "+12025550124"));
        var second = Map.of("command",5L,"sps-device-udid",UDID,"sps-phone-numbers",List.of("+12025550124", "+12025550123", "+12025550123"), "unused-token", new byte[]{1,2,3});
        try (var a = IdsSpsCompanionInfo.fromMessage(first); var b = IdsSpsCompanionInfo.fromMessage(second)) {
            assertArrayEquals(a.serialize(), b.serialize());
            assertEquals(3, ((Map<?, ?>)AppleBinaryPropertyList.decode(b.serialize())).size());
        }
    }

    @Test public void emptyPhoneListIsValidButMalformedMetadataIsRejected() {
        try (var empty = IdsSpsCompanionInfo.fromMessage(Map.of("command",5L,"sps-device-udid",UDID,"sps-phone-numbers",List.of()))) {
            assertTrue(empty.summary().contains("phoneNumberCount=0"));
        }
        for (Object numbers : List.of("not-a-list", List.of(1L), List.of("private@example.invalid"), java.util.Collections.nCopies(17,"+12025550123"))) {
            assertThrows(IllegalArgumentException.class, () -> IdsSpsCompanionInfo.fromMessage(
                    Map.of("command",5L,"sps-device-udid",UDID,"sps-phone-numbers",numbers)));
        }
        assertThrows(IllegalArgumentException.class, () -> IdsSpsCompanionInfo.fromMessage(
                Map.of("command",5L,"sps-device-udid","", "sps-phone-numbers",List.of())));
        assertThrows(IllegalArgumentException.class, () -> IdsSpsCompanionInfo.parse(new byte[4097]));
        var info = IdsSpsCompanionInfo.fromMessage(Map.of("command",5L,"sps-device-udid",UDID,"sps-phone-numbers",List.of()));
        info.close();
        assertThrows(IllegalStateException.class, info::serialize);
    }
}
