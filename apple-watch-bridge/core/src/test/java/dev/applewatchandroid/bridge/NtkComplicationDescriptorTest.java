package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class NtkComplicationDescriptorTest {
    private static final String FAILED = "1791257602340-clockface-sync-5138770744153146533.bin";

    @Test public void decodesAllNinetyNineActualWatchDescriptorsAndRetainsOpaqueIntents() throws Exception {
        Path directory = Path.of(getClass().getResource("/clockface-355-descriptors").toURI());
        int total = 0, intents = 0;
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(Files.readAllBytes(file));
                     NtkSyncMessageCodec.Message message = ClockFaceSyncFrame.decodeChange(frame.changes.get(0))) {
                    var descriptor = message.complicationDescriptor();
                    assertNotNull(descriptor);
                    assertEquals(6, message.type());
                    assertNotNull(descriptor.identifier());
                    assertFalse(descriptor.supportedFamilies().isEmpty());
                    assertTrue(descriptor.userInfo().get("registrationDate") instanceof Double);
                    assertTrue(descriptor.userInfo().get("protectionLevel") instanceof Long);
                    var widget = descriptor.widgetDescriptor();
                    assertNotNull(widget);
                    assertNotNull(widget.extensionBundleIdentifier());
                    if (widget.intentReference() != null) {
                        intents++;
                        byte[] bytes = widget.intentReference().intentData();
                        assertNotNull(bytes);
                        byte first = bytes[0]; bytes[0] ^= 1;
                        assertEquals(first, widget.intentReference().intentData()[0]);
                    }
                    total++;
                }
            }
        }
        assertEquals(99, total);
        assertEquals(27, intents);
    }

    @Test public void previouslyRejectedBatchSixtySevenDecodesWithNativeFieldsAndDefensiveOwnership() throws Exception {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(failed());
             NtkSyncMessageCodec.Message message = ClockFaceSyncFrame.decodeChange(frame.changes.get(0))) {
            assertEquals(67, frame.batchIndex);
            var descriptor = message.complicationDescriptor();
            assertEquals("Недавние переводы", descriptor.displayName());
            assertEquals("ru_UA", descriptor.locale());
            assertEquals(List.of(11L), descriptor.supportedFamilies());
            assertEquals("com.apple.NanoTranslate", descriptor.widgetDescriptor().containerBundleIdentifier());
            assertEquals("NanoTranslateWidget", descriptor.widgetDescriptor().kind());
            assertEquals(6490701424575041387L, descriptor.widgetDescriptor().intentReference().indexingHash());
            assertFalse(descriptor.needsAppNotify());
            assertThrows(UnsupportedOperationException.class, () -> descriptor.supportedFamilies().add(1L));
            var copy = new NtkSyncMessageCodec.Message(message.type(), null, message.payload(), null,
                    message.progress(), null, null, null, null, 0, 0, 0, descriptor);
            assertTrue(copy.complicationDescriptor().same(descriptor));
            byte[] original = descriptor.widgetDescriptor().intentReference().intentData();
            copy.close();
            assertArrayEquals(original, descriptor.widgetDescriptor().intentReference().intentData());
        }
    }

    @Test public void refusesExecutableClassesCyclesAndUnknownDescriptorFields() throws Exception {
        Map<String, Object> root = archive();
        Map<String, Object> descriptor = descriptor(root);
        Map<String, Object> clazz = map(resolve(root, descriptor.get("$class")));
        clazz.put("$classname", "UnexpectedExecutableClass");
        refuses(root);
        root = archive(); descriptor(root).put("widgetDescriptor", message(root).get("complicationDescriptor")); refuses(root);
        root = archive(); descriptor(root).put("newUnknownField", false); refuses(root);
        root = archive(); descriptor(root).put("needsAppNotify", 1L); refuses(root);
    }

    @Test public void refusesDuplicateInvalidFamiliesAndDictionaryKeys() throws Exception {
        Map<String, Object> root = archive();
        Map<String, Object> families = map(resolve(root, descriptor(root).get("supportedFamilies")));
        @SuppressWarnings("unchecked") List<Object> values = (List<Object>) families.get("NS.objects");
        values.add(values.get(0)); refuses(root);
        root = archive(); families = map(resolve(root, descriptor(root).get("supportedFamilies")));
        @SuppressWarnings("unchecked") List<Object> badValues = (List<Object>) families.get("NS.objects");
        objects(root).set(((AppleBinaryPropertyList.Uid) badValues.get(0)).value(), -1L); refuses(root);
        root = archive(); var info = map(resolve(root, descriptor(root).get("userInfo")));
        @SuppressWarnings("unchecked") List<Object> keys = (List<Object>) info.get("NS.keys");
        keys.set(1, keys.get(0)); refuses(root);
        root = archive(); info = map(resolve(root, descriptor(root).get("userInfo")));
        @SuppressWarnings("unchecked") List<Object> badInfo = (List<Object>) info.get("NS.objects");
        badInfo.set(0, descriptor(root).get("userInfo")); refuses(root);
    }

    @Test public void refusesNonfiniteMetadataForgedIntentAndWrongUserActivityType() throws Exception {
        Map<String, Object> root = archive();
        var info = map(resolve(root, descriptor(root).get("userInfo")));
        @SuppressWarnings("unchecked") List<Object> values = (List<Object>) info.get("NS.objects");
        objects(root).set(((AppleBinaryPropertyList.Uid) values.get(0)).value(), 1.125);
        byte[] nonfinite = AppleBinaryPropertyList.encode(root);
        byte[] marker = java.nio.ByteBuffer.allocate(8).putDouble(1.125).array();
        boolean replaced = false;
        for (int offset = 1; offset + 8 <= nonfinite.length; offset++) {
            if (nonfinite[offset - 1] == 0x23 && java.util.Arrays.equals(marker,
                    java.util.Arrays.copyOfRange(nonfinite, offset, offset + 8))) {
                java.nio.ByteBuffer.wrap(nonfinite, offset, 8).putDouble(Double.NaN); replaced = true; break;
            }
        }
        assertTrue(replaced);
        assertThrows(IllegalArgumentException.class, () -> NtkSyncMessageCodec.decode(nonfinite));
        root = archive(); var widget = map(resolve(root, descriptor(root).get("widgetDescriptor")));
        var intent = map(resolve(root, widget.get("intentReference")));
        intent.put("indexingHash", "6490701424575041387"); refuses(root);
        root = archive(); widget = map(resolve(root, descriptor(root).get("widgetDescriptor")));
        intent = map(resolve(root, widget.get("intentReference")));
        intent.put("intentData", descriptor(root).get("widgetDescriptor")); refuses(root);
        root = archive(); descriptor(root).put("userActivity", message(root).get("payload")); refuses(root);
    }

    @Test public void nativeActivityAndNestedUserInfoRemainOpaqueImmutableAndParticipateInIdentity() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/clockface-399-activity-descriptor.bplist"));
        var values = NtkSyncMessageCodec.descriptors(bytes);
        try {
            var descriptor = values.get(0);
            assertTrue(descriptor.needsAppNotify());
            assertEquals(1068, descriptor.encodedUserActivity().length());
            assertTrue(descriptor.encodedUserActivity().startsWith("YnBsaXN0"));
            @SuppressWarnings("unchecked") var nested = (Map<String, Object>) descriptor.userInfo().get("nested");
            @SuppressWarnings("unchecked") var items = (List<Object>) nested.get("values");
            assertEquals(List.of("one", 7L, true), items);
            assertThrows(UnsupportedOperationException.class, () -> nested.put("changed", true));
            assertThrows(UnsupportedOperationException.class, () -> items.add("changed"));
            var copy = descriptor.copy();
            assertTrue(descriptor.same(copy));
            var changed = new NtkSyncMessageCodec.ComplicationDescriptor(descriptor.identifier(),
                    descriptor.displayName(), descriptor.locale(), descriptor.supportedFamilies(),
                    descriptor.userInfo(), descriptor.needsAppNotify(), null, "different-opaque-data");
            assertFalse(descriptor.same(changed));
        } finally { values.forEach(NtkSyncMessageCodec.ComplicationDescriptor::clearIntent); }
    }

    @Test public void activityTextHasItsOwnBoundAndNeverAcceptsAnObjectArchiveInPlaceOfText() throws Exception {
        Map<String, Object> root = archive();
        objects(root).add("a".repeat(NtkFacePayloadCodec.MAX_CONFIG_BYTES + 1));
        descriptor(root).put("userActivity", new AppleBinaryPropertyList.Uid(objects(root).size() - 1));
        refuses(root);
        root = archive();
        // The opaque text is retained as-is, not decoded into application objects.
        objects(root).add("not-an-executable-object");
        descriptor(root).put("userActivity", new AppleBinaryPropertyList.Uid(objects(root).size() - 1));
        try (var message = NtkSyncMessageCodec.decode(AppleBinaryPropertyList.encode(root))) {
            assertEquals("not-an-executable-object", message.complicationDescriptor().encodedUserActivity());
        }
    }

    @Test public void nestedMetadataRejectsCyclesUnknownClassesAndExcessiveDepth() throws Exception {
        Map<String, Object> root = archive();
        var info = map(resolve(root, descriptor(root).get("userInfo")));
        @SuppressWarnings("unchecked") var values = (List<Object>) info.get("NS.objects");
        values.set(0, descriptor(root).get("userInfo"));
        refuses(root);
        root = archive();
        info = map(resolve(root, descriptor(root).get("userInfo")));
        map(resolve(root, info.get("$class"))).put("$classname", "NSInvocation");
        refuses(root);
        root = archive();
        info = map(resolve(root, descriptor(root).get("userInfo")));
        Object chain = descriptor(root).get("userInfo");
        // Reuse the genuine NSDictionary class/keys, wrapping the prior object.
        for (int i = 0; i < 17; i++) {
            var wrapped = new LinkedHashMap<>(info);
            @SuppressWarnings("unchecked") var keys = (List<Object>) info.get("NS.keys");
            wrapped.put("NS.keys", List.of(keys.get(0)));
            wrapped.put("NS.objects", List.of(chain));
            objects(root).add(wrapped);
            chain = new AppleBinaryPropertyList.Uid(objects(root).size() - 1);
        }
        descriptor(root).put("userInfo", chain);
        refuses(root);
    }

    @Test public void nestedMetadataTraversalWorkIsBoundedEvenWithSharedScalarReferences() throws Exception {
        Map<String, Object> root = archive();
        var info = map(resolve(root, descriptor(root).get("userInfo")));
        @SuppressWarnings("unchecked") var values = (List<Object>) info.get("NS.objects");
        var array = new LinkedHashMap<>(map(resolve(root, descriptor(root).get("supportedFamilies"))));
        array.put("NS.objects", java.util.Collections.nCopies(2048, values.get(1)));
        objects(root).add(array);
        values.set(0, new AppleBinaryPropertyList.Uid(objects(root).size() - 1));
        refuses(root);
    }

    private byte[] failed() throws Exception {
        try (var input = getClass().getResourceAsStream("/clockface-355-descriptors/" + FAILED)) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
    private Map<String, Object> archive() throws Exception {
        try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(failed())) {
            byte[] change = frame.changes.get(0);
            for (int offset = 0; offset + 8 <= change.length; offset++) {
                if (new String(change, offset, 8, java.nio.charset.StandardCharsets.US_ASCII).equals("bplist00")) {
                    return map(mutable(AppleBinaryPropertyList.decode(java.util.Arrays.copyOfRange(change, offset, change.length))));
                }
            }
            throw new AssertionError("Missing actual archive");
        }
    }
    private static void refuses(Map<String, Object> root) {
        byte[] encoded = AppleBinaryPropertyList.encode(root);
        assertThrows(IllegalArgumentException.class, () -> NtkSyncMessageCodec.decode(encoded));
    }
    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) result.put(entry.getKey(), mutable(entry.getValue()));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(mutable(item));
            return result;
        }
        return value;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> objects(Map<String, Object> root) { return (List<Object>) root.get("$objects"); }
    private static Object resolve(Map<String, Object> root, Object uid) { return objects(root).get(((AppleBinaryPropertyList.Uid) uid).value()); }
    private static Map<String, Object> message(Map<String, Object> root) { return map(resolve(root, map(root.get("$top")).get("root"))); }
    private static Map<String, Object> descriptor(Map<String, Object> root) { return map(resolve(root, message(root).get("complicationDescriptor"))); }
}
