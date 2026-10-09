package dev.applewatchandroid.bridge;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class NativeComplicationPresentationTest {
    @Test public void optionalLabelsCannotMakeACompleteCatalogUnavailableAtItsSizeLimit() {
        Map<String, Object> row = new LinkedHashMap<>(Map.of(
                "identifier", "timer", "families", List.of(8L), "descriptor", Map.of("kind", "timer"), "padding", ""));
        Map<String, Object> catalog = Map.of("WidgetComplications:timer", Map.of("timer", row));
        int padding = NativeComplicationCatalog.MAX_BYTES - AppleBinaryPropertyList.encode(catalog).length - 16;
        row.put("padding", "x".repeat(padding));
        padding -= AppleBinaryPropertyList.encode(catalog).length - NativeComplicationCatalog.MAX_BYTES + 16;
        row.put("padding", "x".repeat(padding));
        NativeComplicationCatalog.validate(catalog);
        byte[] config = "{\"complications\":{\"bottom left\":{\"descriptor\":{\"kind\":\"timer\"}}}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(catalog, NativeComplicationCatalog.forObservation(catalog,
                Map.of("11111111-1111-1111-1111-111111111111", config)));
    }

    @Test public void actualTimerVariantMatchesRearchivedFaceWithoutMutatingNativeConfiguration() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/clockface-390-normalization/timer-five-min.json")) {
            bytes = stream.readAllBytes();
        }
        Map<?, ?> fixture = (Map<?, ?>) BoundedJson.decode(bytes, 131072);
        Map<?, ?> config = (Map<?, ?>) fixture.get("configuration");
        Map<String, Object> row = new LinkedHashMap<>();
        ((Map<?, ?>) fixture.get("row")).forEach((k, v) -> row.put((String) k, v));
        row.put("families", ((List<?>) row.get("families")).stream().map(v -> ((Number) v).longValue()).toList());
        Map<?, ?> slot = (Map<?, ?>) ((Map<?, ?>) config.get("complications")).get("bottom left");
        assertNotEquals(slot.get("descriptor"), row.get("descriptor"));
        assertTrue(NativeFaceConfigurationEquality.same(Map.of("descriptor", slot.get("descriptor")),
                Map.of("descriptor", row.get("descriptor"))));
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        byte[] original = text.substring(text.indexOf('{', text.indexOf("\"configuration\"")),
                text.indexOf(",\n  \"row\":")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] before = original.clone();
        assertEquals(config, BoundedJson.decode(original, 131072));
        String face = "11111111-1111-1111-1111-111111111111";
        Map<String, Object> catalog = Map.of("WidgetComplications:timer", Map.of("5min", row));
        var display = NativeComplicationCatalog.forObservation(catalog, Map.of(face, original));
        Map<?, ?> annotated = (Map<?, ?>) ((Map<?, ?>) display.get("WidgetComplications:timer")).get("5min");
        assertEquals(List.of(face + ":bottom left"), annotated.get("observedSlots"));
        assertEquals("5 min", annotated.get("name"));
        assertFalse(row.containsKey("observedSlots"));
        assertArrayEquals(before, original);
    }
}
