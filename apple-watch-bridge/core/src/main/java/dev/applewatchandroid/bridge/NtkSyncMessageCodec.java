package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exact NTKDSyncMessage keyed-archive schema from Watch7,5 / 23S303.
 * Payload bytes remain opaque: decoding an envelope does not apply a face.
 */
final class NtkSyncMessageCodec {
    private static final Set<String> KEYS = Set.of("$class", "messageType", "faceUUID",
            "payload", "label", "progress", "complicationClientID", "complicationDescriptor",
            "complicationFamily", "complicationCollectionIdentifier", "wideLoadId",
            "numberOfParts", "partNumber", "maxPartSize");
    private static final String[] TYPES = {"ResetStore", "AddFace", "UpdateFaceConfiguration",
            "UpdateFaceResources", "RemoveFace", "SelectFace", "ReorderFaces",
            "UpdateComplication", "RemoveComplication", "UpdateComplicationFamily",
            "UpgradeFace", "UpdateComplicationDescriptors", "ColorSync"};

    record Message(int type, UUID faceUuid, byte[] payload, String label, double progress,
                   String complicationClientId, String complicationCollectionIdentifier,
                   Long complicationFamily, UUID wideLoadId, long numberOfParts,
                   long partNumber, long maxPartSize,
                   ComplicationDescriptor complicationDescriptor) implements AutoCloseable {
        Message {
            payload = payload == null ? null : payload.clone();
            complicationDescriptor = complicationDescriptor == null ? null : complicationDescriptor.copy();
        }
        Message(int type, UUID faceUuid, byte[] payload, String label, double progress,
                String client, String collection, Long family, UUID load, long parts, long part, long size) {
            this(type, faceUuid, payload, label, progress, client, collection, family, load, parts, part, size, null);
        }
        @Override public byte[] payload() { return payload == null ? null : payload.clone(); }
        int payloadLength() { return payload == null ? 0 : payload.length; }
        String typeName() { return TYPES[type + 1]; }
        boolean multipart() { return wideLoadId != null || numberOfParts != 0 || partNumber != 0; }
        @Override public void close() {
            if (payload != null) Arrays.fill(payload, (byte) 0);
            if (complicationDescriptor != null) complicationDescriptor.clearIntent();
        }
    }

    /** ClockKit metadata only; opaque intent bytes are never deserialized or executed. */
    record IntentReference(long indexingHash, byte[] intentData) {
        IntentReference { intentData = intentData == null ? null : intentData.clone(); }
        @Override public byte[] intentData() { return intentData == null ? null : intentData.clone(); }
        IntentReference copy() { return new IntentReference(indexingHash, intentData); }
        void clear() { if (intentData != null) Arrays.fill(intentData, (byte) 0); }
        boolean same(IntentReference other) {
            return other != null && indexingHash == other.indexingHash && Arrays.equals(intentData, other.intentData);
        }
    }
    record WidgetDescriptor(String extensionBundleIdentifier, String containerBundleIdentifier,
                            String kind, IntentReference intentReference) {
        WidgetDescriptor copy() { return new WidgetDescriptor(extensionBundleIdentifier,
                containerBundleIdentifier, kind, intentReference == null ? null : intentReference.copy()); }
        boolean same(WidgetDescriptor other) {
            return other != null && java.util.Objects.equals(extensionBundleIdentifier, other.extensionBundleIdentifier)
                    && java.util.Objects.equals(containerBundleIdentifier, other.containerBundleIdentifier)
                    && java.util.Objects.equals(kind, other.kind)
                    && (intentReference == null ? other.intentReference == null : intentReference.same(other.intentReference));
        }
    }
    record ComplicationDescriptor(String identifier, String displayName, String locale,
                                  List<Long> supportedFamilies, Map<String, Object> userInfo,
                                  boolean needsAppNotify, WidgetDescriptor widgetDescriptor,
                                  String encodedUserActivity) {
        ComplicationDescriptor {
            supportedFamilies = List.copyOf(supportedFamilies);
            // Nested JSON collections were already decoded into immutable data.
            // Preserve native NSNull values rather than Map.copyOf rejecting them.
            userInfo = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(userInfo));
        }
        ComplicationDescriptor copy() { return new ComplicationDescriptor(identifier, displayName, locale,
                supportedFamilies, userInfo, needsAppNotify, widgetDescriptor == null ? null : widgetDescriptor.copy(),
                encodedUserActivity); }
        void clearIntent() {
            if (widgetDescriptor != null && widgetDescriptor.intentReference != null) widgetDescriptor.intentReference.clear();
        }
        boolean same(ComplicationDescriptor other) {
            return other != null && java.util.Objects.equals(identifier, other.identifier)
                    && java.util.Objects.equals(displayName, other.displayName) && java.util.Objects.equals(locale, other.locale)
                    && supportedFamilies.equals(other.supportedFamilies) && userInfo.equals(other.userInfo)
                    && needsAppNotify == other.needsAppNotify
                    && java.util.Objects.equals(encodedUserActivity, other.encodedUserActivity)
                    && (widgetDescriptor == null ? other.widgetDescriptor == null : widgetDescriptor.same(other.widgetDescriptor));
        }
    }

    private final List<?> objects;

    private NtkSyncMessageCodec(List<?> objects) { this.objects = objects; }

    /** Descriptor inventories use their own keyed NSArray archive, not an NTK message root. */
    static List<ComplicationDescriptor> descriptors(byte[] bytes) {
        Map<?, ?> archive = dictionary(AppleBinaryPropertyList.decodeBounded(bytes, ClockFaceSyncFrame.MAX_BYTES));
        if (!"NSKeyedArchiver".equals(archive.get("$archiver")) || integer(archive.get("$version")) != 100000
                || !(archive.get("$objects") instanceof List<?> objects) || objects.isEmpty()
                || !"$null".equals(objects.get(0))) throw invalid("descriptor archive");
        var codec = new NtkSyncMessageCodec(objects);
        Map<?, ?> top = dictionary(archive.get("$top"));
        if (!top.keySet().equals(Set.of("root"))) throw invalid("descriptor root");
        Map<?, ?> array = dictionary(codec.resolve(top.get("root")));
        codec.requireCollectionClass(array, "NSArray", "NSMutableArray");
        if (!array.keySet().equals(Set.of("$class", "NS.objects"))
                || !(array.get("NS.objects") instanceof List<?> entries) || entries.size() > 256) throw invalid("descriptor list");
        List<ComplicationDescriptor> result = new ArrayList<>();
        try {
            for (Object entry : entries) {
                var descriptor = codec.descriptor(codec.resolve(entry));
                if (descriptor == null) throw invalid("null descriptor");
                result.add(descriptor);
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            for (var descriptor : result) descriptor.clearIntent();
            throw invalid;
        }
    }

    static Message decode(byte[] data) {
        Map<?, ?> archive = dictionary(AppleBinaryPropertyList.decodeBounded(data, ClockFaceSyncFrame.MAX_BYTES));
        if (!archive.keySet().equals(Set.of("$version", "$archiver", "$top", "$objects"))
                || !"NSKeyedArchiver".equals(archive.get("$archiver"))
                || integer(archive.get("$version")) != 100000
                || !(archive.get("$objects") instanceof List<?> objects)
                || objects.isEmpty() || !"$null".equals(objects.get(0))) {
            throw invalid("archive header");
        }
        NtkSyncMessageCodec codec = new NtkSyncMessageCodec(objects);
        Map<?, ?> top = dictionary(archive.get("$top"));
        if (!top.keySet().equals(Set.of("root"))) throw invalid("archive root");
        Map<?, ?> message = dictionary(codec.resolve(top.get("root")));
        codec.requireClass(message, "NTKDSyncMessage", List.of("NTKDSyncMessage", "NSObject"));
        if (!KEYS.containsAll(message.keySet())) throw invalid("unknown message key");
        long type = integer(message.get("messageType"));
        if (type < -1 || type > 11) throw invalid("message type");
        ComplicationDescriptor descriptor = codec.descriptor(codec.optional(message, "complicationDescriptor"));
        double progress = codec.number(message, "progress", 0).doubleValue();
        if (!Double.isFinite(progress) || progress < 0 || progress > 1) throw invalid("progress");
        byte[] payload = codec.data(codec.optional(message, "payload"));
        try {
            return new Message((int) type, codec.uuid(codec.optional(message, "faceUUID")),
                    payload, codec.string(message, "label"), progress,
                    codec.string(message, "complicationClientID"),
                    codec.string(message, "complicationCollectionIdentifier"),
                    codec.optionalInteger(message, "complicationFamily"),
                    codec.uuid(codec.optional(message, "wideLoadId")),
                    codec.unsigned(message, "numberOfParts"),
                    codec.unsigned(message, "partNumber"), codec.unsigned(message, "maxPartSize"), descriptor);
        } finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
            if (descriptor != null) descriptor.clearIntent();
        }
    }

    private ComplicationDescriptor descriptor(Object value) {
        if (value == null) return null;
        Map<?, ?> descriptor = dictionary(value);
        requireClass(descriptor, "CLKComplicationDescriptor", List.of("CLKComplicationDescriptor", "NSObject"));
        if (!descriptor.keySet().equals(Set.of("$class", "identifier", "displayName", "supportedFamilies",
                "userInfo", "userActivity", "needsAppNotify", "locale", "widgetDescriptor"))) throw invalid("descriptor keys");
        // CLKUserActivity.encodedUserActivity is an NSString. Never decode or
        // instantiate its archived application objects; retain the native text.
        String activity = string(descriptor, "userActivity", NtkFacePayloadCodec.MAX_CONFIG_BYTES);
        if (!(descriptor.get("needsAppNotify") instanceof Boolean notify)) throw invalid("descriptor notify");
        Object familiesValue = optional(descriptor, "supportedFamilies");
        List<Long> families = new ArrayList<>();
        if (familiesValue != null) {
            Map<?, ?> array = dictionary(familiesValue);
            requireCollectionClass(array, "NSArray", "NSMutableArray");
            if (!array.keySet().equals(Set.of("$class", "NS.objects"))
                    || !(array.get("NS.objects") instanceof List<?> entries) || entries.size() > 64) throw invalid("families array");
            for (Object entry : entries) {
                long family = integer(resolve(entry));
                if (family < 0 || family > 255 || families.contains(family)) throw invalid("descriptor family");
                families.add(family);
            }
        }
        return new ComplicationDescriptor(string(descriptor, "identifier"), string(descriptor, "displayName"),
                string(descriptor, "locale"), families, userInfo(optional(descriptor, "userInfo")), notify,
                widget(optional(descriptor, "widgetDescriptor")), activity);
    }

    private Map<String, Object> userInfo(Object value) {
        if (value == null) return Map.of();
        var active = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        Object decoded = userInfoValue(value, 0, new int[]{0}, active);
        if (!(decoded instanceof Map<?, ?> map)) throw invalid("descriptor user info dictionary");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put((String) key, item));
        return java.util.Collections.unmodifiableMap(result);
    }

    /** Allow-listed JSON data only, with work/depth/cycle bounds for keyed graphs. */
    private Object userInfoValue(Object value, int depth, int[] nodes, Set<Object> active) {
        if (depth > 16 || ++nodes[0] > 2048) throw invalid("user info graph bound");
        if (value instanceof String text) {
            if (text.length() > 4096) throw invalid("user info string");
            return text;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw invalid("user info number");
            return number;
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) return value;
        Map<?, ?> info = dictionary(value);
        if (!active.add(info)) throw invalid("user info cycle");
        try {
            Map<?, ?> clazz = dictionary(resolve(info.get("$class")));
            if ("NSNull".equals(clazz.get("$classname"))) {
                requireClass(info, "NSNull", List.of("NSNull", "NSObject"));
                if (!info.keySet().equals(Set.of("$class"))) throw invalid("user info null");
                return null;
            }
            if ("NSArray".equals(clazz.get("$classname")) || "NSMutableArray".equals(clazz.get("$classname"))) {
                requireCollectionClass(info, "NSArray", "NSMutableArray");
                if (!info.keySet().equals(Set.of("$class", "NS.objects"))
                        || !(info.get("NS.objects") instanceof List<?> items) || items.size() > 2048)
                    throw invalid("user info array");
                List<Object> result = new ArrayList<>();
                for (Object item : items) result.add(userInfoValue(resolve(item), depth + 1, nodes, active));
                return java.util.Collections.unmodifiableList(result);
            }
            requireCollectionClass(info, "NSDictionary", "NSMutableDictionary");
            if (!info.keySet().equals(Set.of("$class", "NS.keys", "NS.objects"))
                    || !(info.get("NS.keys") instanceof List<?> keys) || !(info.get("NS.objects") instanceof List<?> values)
                    || keys.size() != values.size() || keys.size() > 64) throw invalid("descriptor user info");
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                Object key = resolve(keys.get(i)), item = resolve(values.get(i));
                if (!(key instanceof String text) || text.isEmpty() || text.length() > 4096 || result.containsKey(text)) throw invalid("user info key");
                result.put(text, userInfoValue(item, depth + 1, nodes, active));
            }
            return java.util.Collections.unmodifiableMap(result);
        } finally { active.remove(info); }
    }

    private WidgetDescriptor widget(Object value) {
        if (value == null) return null;
        Map<?, ?> widget = dictionary(value);
        requireClass(widget, "CLKWidgetComplicationDescriptor", List.of("CLKWidgetComplicationDescriptor", "NSObject"));
        if (!widget.keySet().equals(Set.of("$class", "extensionBundleIdentifier", "containerBundleIdentifier",
                "kind", "intentReference"))) throw invalid("widget keys");
        IntentReference intent = null;
        Object reference = optional(widget, "intentReference");
        if (reference != null) {
            Map<?, ?> map = dictionary(reference);
            requireClass(map, "CLKIntentReference", List.of("CLKIntentReference", "NSObject"));
            if (!map.keySet().equals(Set.of("$class", "indexingHash", "intentData"))) throw invalid("intent keys");
            byte[] bytes = data(optional(map, "intentData"));
            try {
                if (bytes != null && bytes.length > 1024 * 1024) throw invalid("intent data bound");
                intent = new IntentReference(integer(map.get("indexingHash")), bytes);
            } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
        }
        return new WidgetDescriptor(string(widget, "extensionBundleIdentifier"), string(widget, "containerBundleIdentifier"),
                string(widget, "kind"), intent);
    }

    private Object resolve(Object value) {
        if (!(value instanceof AppleBinaryPropertyList.Uid uid) || uid.value() >= objects.size()) {
            throw invalid("object reference");
        }
        return uid.value() == 0 ? null : objects.get(uid.value());
    }

    private Object optional(Map<?, ?> message, String key) {
        return message.containsKey(key) ? resolve(message.get(key)) : null;
    }

    private String string(Map<?, ?> message, String key) {
        return string(message, key, 4096);
    }

    private String string(Map<?, ?> message, String key, int maxCharacters) {
        Object value = optional(message, key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > maxCharacters) throw invalid(key);
        return text;
    }

    private Number number(Map<?, ?> message, String key, Number fallback) {
        Object value = optional(message, key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw invalid(key);
        return number;
    }

    private Long optionalInteger(Map<?, ?> message, String key) {
        Object value = optional(message, key);
        return value == null ? null : integer(value);
    }

    private long unsigned(Map<?, ?> message, String key) {
        Long value = optionalInteger(message, key);
        if (value != null && value < 0) throw invalid(key);
        return value == null ? 0 : value;
    }

    private byte[] data(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] bytes) return bytes.clone();
        Map<?, ?> data = dictionary(value);
        requireClass(data, "NSMutableData", List.of("NSMutableData", "NSData", "NSObject"));
        if (!data.keySet().equals(Set.of("$class", "NS.data"))
                || !(data.get("NS.data") instanceof byte[] bytes)) throw invalid("data");
        return bytes.clone();
    }

    private UUID uuid(Object value) {
        if (value == null) return null;
        Map<?, ?> uuid = dictionary(value);
        requireClass(uuid, "NSUUID", List.of("NSUUID", "NSObject"));
        if (!uuid.keySet().equals(Set.of("$class", "NS.uuidbytes"))
                || !(uuid.get("NS.uuidbytes") instanceof byte[] bytes) || bytes.length != 16) {
            throw invalid("UUID bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private void requireClass(Map<?, ?> value, String name, List<String> hierarchy) {
        Map<?, ?> descriptor = dictionary(resolve(value.get("$class")));
        if (!descriptor.keySet().equals(Set.of("$classname", "$classes"))
                || !name.equals(descriptor.get("$classname"))
                || !hierarchy.equals(descriptor.get("$classes"))) throw invalid("class");
    }

    private void requireCollectionClass(Map<?, ?> value, String immutable, String mutable) {
        Map<?, ?> descriptor = dictionary(resolve(value.get("$class")));
        if (mutable.equals(descriptor.get("$classname"))) requireClass(value, mutable, List.of(mutable, immutable, "NSObject"));
        else requireClass(value, immutable, List.of(immutable, "NSObject"));
    }

    private static Map<?, ?> dictionary(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("dictionary");
        return map;
    }

    private static long integer(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) throw invalid("integer");
        return ((Number) value).longValue();
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid NTK sync " + field);
    }
}
