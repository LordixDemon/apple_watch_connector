package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static dev.applewatchandroid.bridge.ClockFaceDeltaProtocolTest.*;

public final class NativeFaceConfigurationEqualityTest {
    private byte[] fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/clockface-390-normalization/" + name + ".watchface")) {
            assertNotNull(in); return in.readAllBytes();
        }
    }
    @Test public void realWatchCopyRearchivesTheSameTimerIntentWithoutChangingItsMeaning() throws Exception {
        byte[] original = NtkFacePayloadCodec.configurationFromZip(fixture("original"));
        byte[] copy = NtkFacePayloadCodec.configurationFromZip(fixture("copy"));
        Object left = BoundedJson.decode(original, 131072), right = BoundedJson.decode(copy, 131072);
        assertNotEquals(left, right);
        assertTrue(NativeFaceConfigurationEquality.same(left, right));
        assertTrue(NativeFaceConfigurationEquality.same(right, left));
        var base = ClockFaceDeltaPlanTest.baseline();
        base.configurations.put(FACE, original); base.archives.put(FACE, fixture("original"));
        try (var plan = ClockFaceDeltaPlan.duplicate(base, FACE, NEW_FACE)) {
            var observed = ClockFaceDeltaPlanTest.apply(plan, base, NOW + 1);
            observed.configurations.put(NEW_FACE, copy);
            assertTrue(plan.matches(observed));
            observed.configurations.put(NEW_FACE, new String(copy, StandardCharsets.UTF_8)
                    .replace("leghorn.hero-2", "leghorn.hero-1").getBytes(StandardCharsets.UTF_8));
            assertFalse(plan.matches(observed));
        }
    }
    @Test public void semanticEqualityNeverIgnoresIntentValuesClassesUnknownFieldsOrInvalidArchives() {
        Object one = Map.of("descriptor", Map.of("intent", archive("TimerIntent", "60", false)));
        Object reordered = Map.of("descriptor", Map.of("intent", archive("TimerIntent", "60", true)));
        assertTrue(NativeFaceConfigurationEquality.same(one, reordered));
        assertFalse(NativeFaceConfigurationEquality.same(one,
                Map.of("descriptor", Map.of("intent", archive("TimerIntent", "120", true)))));
        assertFalse(NativeFaceConfigurationEquality.same(one,
                Map.of("descriptor", Map.of("intent", archive("OtherIntent", "60", true)))));
        assertFalse(NativeFaceConfigurationEquality.same(one, Map.of("descriptor", Map.of("intent", "@@@"))));
        assertFalse(NativeFaceConfigurationEquality.same(one, Map.of("descriptor", Map.of("intent", "AA=="))));
        assertFalse(NativeFaceConfigurationEquality.same(one, Map.of("descriptor", Map.of("intent", archive("TimerIntent", "60", true), "unknown", 1))));
    }
    private String archive(String type, String duration, boolean reversed) {
        var root = Map.of("$class", new AppleBinaryPropertyList.Uid(2), "duration", new AppleBinaryPropertyList.Uid(3));
        List<Object> objects = reversed
                ? List.of("$null", duration, Map.of("$classname", type), Map.of("$class", new AppleBinaryPropertyList.Uid(2), "duration", new AppleBinaryPropertyList.Uid(1)))
                : List.of("$null", root, Map.of("$classname", type), duration);
        return Base64.getEncoder().encodeToString(AppleBinaryPropertyList.encode(Map.of("$archiver", "NSKeyedArchiver", "$version", 100000L,
                "$top", Map.of("root", new AppleBinaryPropertyList.Uid(reversed ? 3 : 1)), "$objects", objects)));
    }
}
