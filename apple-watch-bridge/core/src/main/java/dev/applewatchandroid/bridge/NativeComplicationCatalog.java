package dev.applewatchandroid.bridge;

import java.util.*;

/** Data-only ClockKit inventory. Opaque intents are preserved without interpreting them. */
final class NativeComplicationCatalog {
    static final int MAX_BYTES = 256 * 1024;
    /** Display metadata only; original face configurations and native intents stay untouched. */
    static Map<String, Object> forObservation(Map<String, Object> catalog, Map<String, byte[]> configurations) {
        // Decode each face once, rather than once per catalog descriptor.
        Map<String, Map<?, ?>> complicationsByFace = new LinkedHashMap<>();
        for (var face : configurations.entrySet()) {
            Map<?, ?> config = (Map<?, ?>) BoundedJson.decode(face.getValue(), NtkFacePayloadCodec.MAX_CONFIG_BYTES);
            if (config.get("complications") instanceof Map<?, ?> complications)
                complicationsByFace.put(face.getKey(), complications);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var group : catalog.entrySet()) {
            Map<String, Object> rows = new LinkedHashMap<>();
            for (var entry : ((Map<?, ?>) group.getValue()).entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                ((Map<?, ?>) entry.getValue()).forEach((k, v) -> row.put((String) k, v));
                if (row.get("descriptor") instanceof Map<?, ?> descriptor) {
                    List<String> slots = new ArrayList<>();
                    for (var face : complicationsByFace.entrySet()) {
                        for (var slot : face.getValue().entrySet()) {
                            if (slot.getValue() instanceof Map<?, ?> value && value.get("descriptor") instanceof Map<?, ?> current
                                    && NativeFaceConfigurationEquality.same(Map.of("descriptor", current), Map.of("descriptor", descriptor))) {
                                slots.add(face.getKey() + ":" + slot.getKey());
                            }
                        }
                    }
                    if (!slots.isEmpty()) row.put("observedSlots", List.copyOf(slots));
                }
                rows.put((String) entry.getKey(), row);
            }
            result.put(group.getKey(), rows);
        }
        // Optional display hints must never hide an otherwise valid full inventory.
        if (AppleBinaryPropertyList.encode(result).length > MAX_BYTES) return validate(catalog);
        return validate(result);
    }
    static Map<String, Object> project(NtkSyncMessageCodec.Message message) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<NtkSyncMessageCodec.ComplicationDescriptor> descriptors = NtkSyncMessageCodec.descriptors(message.payload());
        try {
            for (var descriptor : descriptors) {
                if (descriptor.identifier() == null || descriptor.identifier().isEmpty()) throw new IllegalArgumentException("Missing complication identifier");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("identifier", descriptor.identifier()); row.put("families", descriptor.supportedFamilies());
                if (descriptor.displayName() != null) row.put("name", descriptor.displayName());
                if (descriptor.locale() != null) row.put("locale", descriptor.locale());
                var widget = descriptor.widgetDescriptor();
                if (widget != null && widget.containerBundleIdentifier() != null
                        && widget.extensionBundleIdentifier() != null && widget.kind() != null) {
                    Map<String, Object> nativeDescriptor = new LinkedHashMap<>();
                    nativeDescriptor.put("containerBundleIdentifier", widget.containerBundleIdentifier());
                    nativeDescriptor.put("extensionBundleIdentifier", widget.extensionBundleIdentifier()); nativeDescriptor.put("kind", widget.kind());
                    var intent = widget.intentReference();
                    if (intent != null && intent.intentData() != null)
                        nativeDescriptor.put("intent", Base64.getEncoder().encodeToString(intent.intentData()));
                    row.put("descriptor", nativeDescriptor);
                    row.put("app", widget.containerBundleIdentifier());
                } else if (widget == null) {
                    // CLKComplicationDescriptor.JSONObjectRepresentation, distinct
                    // from CLKWidgetComplicationDescriptor (which carries intents).
                    // Provider/application identity belongs to the catalog group
                    // and observed NTKBundleComplication, not this descriptor.
                    Map<String, Object> nativeDescriptor = new LinkedHashMap<>();
                    nativeDescriptor.put("identifier", descriptor.identifier());
                    nativeDescriptor.put("supportedFamilies", descriptor.supportedFamilies());
                    nativeDescriptor.put("userInfo", descriptor.userInfo());
                    if (descriptor.encodedUserActivity() != null)
                        nativeDescriptor.put("userActivity", descriptor.encodedUserActivity());
                    if (descriptor.needsAppNotify()) nativeDescriptor.put("needsAppNotify", true);
                    if (descriptor.displayName() != null) nativeDescriptor.put("displayName", descriptor.displayName());
                    if (descriptor.locale() != null) nativeDescriptor.put("locale", descriptor.locale());
                    row.put("bundleDescriptor", nativeDescriptor);
                }
                if (result.put(descriptor.identifier(), row) != null) throw new IllegalArgumentException("Duplicate complication identifier");
            }
            validate(result); return result;
        } finally { for (var descriptor : descriptors) descriptor.clearIntent(); }
    }
    static Map<String, Object> validate(Map<?, ?> data) {
        if (data.size() > 1024 || AppleBinaryPropertyList.encode(data).length > MAX_BYTES)
            throw new IllegalArgumentException("Native complication catalog bound exceeded");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : data.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.length() > 8192 || !(entry.getValue() instanceof Map<?, ?> row))
                throw new IllegalArgumentException("Invalid complication catalog row");
            // Catalog groups contain descriptor dictionaries; descriptor rows contain native metadata.
            if (row.containsKey("identifier")) {
                if (!(row.get("identifier") instanceof String) || !(row.get("families") instanceof List<?> families)
                        || families.size() > 64) throw new IllegalArgumentException("Invalid complication catalog descriptor");
                for (Object family : families) if (!(family instanceof Long || family instanceof Integer)
                        || ((Number) family).longValue() < 0 || ((Number) family).longValue() > 255)
                    throw new IllegalArgumentException("Invalid complication family");
            } else validate(row);
            result.put(key, row);
        }
        return Collections.unmodifiableMap(result);
    }
    private NativeComplicationCatalog() { }
}
