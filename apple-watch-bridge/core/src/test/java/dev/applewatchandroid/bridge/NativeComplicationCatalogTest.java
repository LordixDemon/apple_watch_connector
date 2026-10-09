package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public final class NativeComplicationCatalogTest {
    @Test public void nativeActivityAndNullCatalogsRoundtripWithoutDiscardingTheirConfiguredData() throws Exception {
        for (String kind : List.of("activity", "null")) {
            String prefix = "src/test/resources/clockface-399-" + kind + "-descriptor";
            byte[] bytes = Files.readAllBytes(Path.of(prefix + ".bplist"));
            Object expected = BoundedJson.decode(Files.readAllBytes(Path.of(prefix + ".json")), 4096);
            try (var message = new NtkSyncMessageCodec.Message(10, null, bytes, null, 0,
                    "research.only.extension", "BundleComplications", null, null, 0, 0, 0)) {
                var row = (Map<?, ?>) NativeComplicationCatalog.project(message).get("research-only-descriptor");
                // JSON parser represents numbers as Double; keyed archives use
                // NSNumber integers. Compare JSON values without changing strings.
                assertEquals(expected, jsonNumbers(row.get("bundleDescriptor")));
                var collection = ClockFaceDeltaPlanTest.baseline();
                collection.apply(message);
                assertTrue(collection.complete());
                assertTrue(collection.complicationCatalogComplete);
                var restored = ClockFaceCollection.decode((Map<?, ?>) AppleBinaryPropertyList.decode(
                        AppleBinaryPropertyList.encode(collection.encode())));
                assertEquals(collection.complicationCatalog, restored.complicationCatalog);
                var observation = ClockFaceObservationCodec.fromCollection(UUID.randomUUID(), UUID.randomUUID(), restored);
                var throughHal = ClockFaceObservationCodec.decode(ClockFaceObservationCodec.encode(observation));
                assertEquals(restored.complicationCatalog, throughHal.complicationCatalog());
                assertTrue(throughHal.complete());
                assertTrue(throughHal.complicationCatalogComplete());
            }
        }
    }
    private static Object jsonNumbers(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key, jsonNumbers(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(NativeComplicationCatalogTest::jsonNumbers).toList();
        return value;
    }
    @Test public void nativeLegacyDescriptorPreservesItsConfigurationWithoutClaimingProviderIdentity() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/clockface-398-bundle-descriptor.bplist"));
        try (var message = new NtkSyncMessageCodec.Message(10, null, bytes, null, 0,
                "research.only.extension", "BundleComplications", null, null, 0, 0, 0)) {
            var row = (Map<?, ?>) NativeComplicationCatalog.project(message).get("research-only-descriptor");
            assertEquals("Research only", row.get("name"));
            assertEquals(List.of(8L, 9L), row.get("families"));
            assertFalse(row.containsKey("descriptor"));
            assertFalse(row.containsKey("app"));
            assertEquals(Map.of("identifier", "research-only-descriptor", "displayName", "Research only",
                    "supportedFamilies", List.of(8L, 9L), "locale", "ru_UA",
                    "userInfo", Map.of("choice", "sample", "count", 7L)), row.get("bundleDescriptor"));
            var collection = new ClockFaceCollection(); collection.apply(message);
            var restored = ClockFaceCollection.decode((Map<?, ?>) AppleBinaryPropertyList.decode(
                    AppleBinaryPropertyList.encode(collection.encode())));
            assertEquals(collection.complicationCatalog, restored.complicationCatalog);
        }
    }
    @Test public void allCapturedDescriptorInventoriesDecodeWithoutInventingClasses() throws Exception {
        var failures = new ArrayList<String>();
        for (String folder : List.of("clockface-355-session", "clockface-355-descriptors")) {
            try (var files = Files.list(Path.of("src/test/resources/" + folder))) {
                for (Path file : files.toList()) try (var frame = ClockFaceSyncFrame.parse(Files.readAllBytes(file))) {
                    for (byte[] change : frame.changes) try (var message = ClockFaceSyncFrame.decodeChange(change)) {
                        if (message.type() == 10) try { NativeComplicationCatalog.project(message); }
                        catch (IllegalArgumentException unsupported) { failures.add(file.getFileName() + " " + message.complicationClientId() + " " + unsupported.getMessage()); }
                    }
                }
            }
        }
        assertEquals(List.of(), failures);
    }
    @Test public void actualWeatherInventoryRetainsNamesFamiliesAndNativeWidgetIdentity() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/clockface-352-descriptors.bin"));
        try (var frame = ClockFaceSyncFrame.parse(bytes); var message = ClockFaceSyncFrame.decodeChange(frame.changes.get(0))) {
            assertEquals(10, message.type()); var entries = NativeComplicationCatalog.project(message);
            assertEquals(8, entries.size());
            var wind = (Map<?, ?>) entries.get("widget-com.apple.weather.watchapp.widgets-com.apple.weather.widget.wind");
            assertNotNull(wind); assertTrue(((List<?>) wind.get("families")).contains(10L));
            assertEquals("com.apple.weather.watchapp", wind.get("app"));
            assertEquals("com.apple.weather.widget.wind", ((Map<?, ?>) wind.get("descriptor")).get("kind"));
            var collection = new ClockFaceCollection(); collection.apply(message);
            assertEquals(entries, collection.complicationCatalog.get("WidgetComplications:com.apple.weather.watchapp"));
            var restored = ClockFaceCollection.decode((Map<?, ?>) AppleBinaryPropertyList.decode(AppleBinaryPropertyList.encode(collection.encode())));
            assertEquals(collection.complicationCatalog, restored.complicationCatalog);
        }
    }
    @Test public void corruptOptionalCatalogNeverDestroysCommittedFaceFacts() throws Exception {
        var collection = ClockFaceDeltaPlanTest.baseline(); var configurations = new LinkedHashMap<>(collection.configurations);
        try (var message = new NtkSyncMessageCodec.Message(10, null, new byte[]{1,2}, null, 0,
                "app", "WidgetComplications", null, null, 0, 0, 0)) { collection.apply(message); }
        assertFalse(collection.complicationCatalogComplete); assertTrue(collection.complete());
        assertEquals(configurations.keySet(), collection.configurations.keySet());
        assertThrows(IllegalArgumentException.class, () -> NativeComplicationCatalog.validate(Map.of("invalid", "not a descriptor")));
    }
}
