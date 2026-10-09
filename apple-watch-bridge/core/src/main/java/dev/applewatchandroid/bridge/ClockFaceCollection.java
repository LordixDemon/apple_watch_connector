package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native facts projected only after an ordered, successfully ended sender session. */
final class ClockFaceCollection {
    final Map<String, byte[]> configurations = new LinkedHashMap<>();
    // Keep small complete native packages, including opaque resources, for lossless copy/export.
    final Map<String, byte[]> archives = new LinkedHashMap<>();
    final Map<String, String> packageDigests = new LinkedHashMap<>();
    final Map<String, Object> complicationCatalog = new LinkedHashMap<>();
    boolean complicationCatalogComplete = true;
    NativeFacePackageStore packageStore;
    private static final int MAX_ARCHIVE_TOTAL = 256 * 1024;
    final List<String> ordered = new ArrayList<>();
    String selected = "";
    boolean orderKnown;
    boolean selectionKnown;
    boolean hasResetBaseline;
    long observedAt;

    void apply(NtkSyncMessageCodec.Message message) throws IOException {
        byte[] payload = message.payload();
        try {
            if (message.multipart()) throw new IOException("Unassembled native wide-load message");
            switch (message.type()) {
                case -1 -> {
                    configurations.clear(); archives.clear(); packageDigests.clear(); complicationCatalog.clear();
                    complicationCatalogComplete = true; ordered.clear(); selected = "";
                    orderKnown = false; selectionKnown = false; hasResetBaseline = true;
                }
                case 0, 9 -> {
                    put(message, NtkFacePayloadCodec.configurationFromZip(payload));
                    cachePackage(faceId(message), payload);
                }
                case 1 -> {
                    String id = faceId(message); byte[] old = null, updated = null;
                    try {
                        old = nativePackage(id);
                        put(message, payload); invalidatePackage(id);
                        if (old != null) {
                            updated = NtkFacePayloadCodec.replacingConfiguration(old, payload);
                            cachePackage(id, updated);
                        }
                    } catch (IOException unavailable) {
                        // A missing/corrupt optional export blob must not reject a valid Watch update.
                        put(message, payload); invalidatePackage(id);
                    } finally {
                        if (old != null) Arrays.fill(old, (byte) 0);
                        if (updated != null) Arrays.fill(updated, (byte) 0);
                    }
                }
                case 2 -> {
                    String id = faceId(message);
                    // Invalid native input must never become a verified export package.
                    NtkFaceResources.validate(payload);
                    byte[] old = null, updated = null;
                    try {
                        old = nativePackage(id);
                        if (old == null) invalidatePackage(id);
                        else { updated = NtkFaceResources.replacing(old, payload); cachePackage(id, updated); }
                    } catch (IOException unavailable) {
                        // Configuration/order remain valid even if an optional stored blob was lost.
                        invalidatePackage(id);
                    } finally {
                        if (old != null) Arrays.fill(old, (byte) 0);
                        if (updated != null) Arrays.fill(updated, (byte) 0);
                    }
                }
                case 3 -> {
                    String id = faceId(message);
                    configurations.remove(id); invalidatePackage(id); ordered.remove(id);
                    if (selected.equals(id)) { selected = ""; selectionKnown = false; }
                }
                case 4 -> { selected = NtkFacePayloadCodec.selected(payload); selectionKnown = true; }
                case 5 -> { ordered.clear(); ordered.addAll(NtkFacePayloadCodec.ordered(payload)); orderKnown = true; }
                // Resource, complication and color payloads stay in the native journal.
                // This projection is face configuration/order/selection, not their application.
                case 10 -> {
                    if (message.complicationClientId() != null && message.complicationCollectionIdentifier() != null) {
                        String key = message.complicationCollectionIdentifier() + ":" + message.complicationClientId();
                        try {
                            Map<String, Object> replacement = NativeComplicationCatalog.project(message);
                            var candidate = new LinkedHashMap<>(complicationCatalog); candidate.put(key, replacement);
                            NativeComplicationCatalog.validate(candidate);
                            complicationCatalog.put(key, replacement);
                        } catch (IllegalArgumentException unsupported) {
                            complicationCatalog.remove(key); complicationCatalogComplete = false;
                        }
                    }
                }
                case 6, 7, 8, 11 -> { }
                default -> throw new IOException("Unsupported native face operation");
            }
        } finally { if (payload != null) Arrays.fill(payload, (byte) 0); }
    }

    private void invalidatePackage(String id) { archives.remove(id); packageDigests.remove(id); }
    private void cachePackage(String id, byte[] payload) throws IOException {
        invalidatePackage(id);
        if (packageStore != null) packageDigests.put(id, packageStore.save(payload));
        if (packageStore == null && payload.length <= ClockFaceDeltaCommand.MAX_PAYLOAD_BYTES) {
            long total = payload.length;
            for (byte[] bytes : archives.values()) total += bytes.length;
            if (total <= MAX_ARCHIVE_TOTAL) archives.put(id, payload.clone());
        }
    }
    boolean hasPackage(String id) { return archives.containsKey(id) || packageDigests.containsKey(id); }
    byte[] nativePackage(String id) throws IOException {
        if (packageDigests.containsKey(id) && packageStore != null) return packageStore.read(packageDigests.get(id));
        byte[] value = archives.get(id); return value == null ? null : value.clone();
    }

    private void put(NtkSyncMessageCodec.Message message, byte[] config) {
        String id = faceId(message);
        NtkFacePayloadCodec.configurationBundle(config);
        if (!configurations.containsKey(id) && configurations.size() >= NtkFacePayloadCodec.MAX_FACES) {
            throw new IllegalArgumentException("Native face count exceeded");
        }
        long size = config.length;
        for (var entry : configurations.entrySet()) if (!entry.getKey().equals(id)) size += entry.getValue().length;
        if (size > 512 * 1024) throw new IllegalArgumentException("Native face configuration total exceeded");
        configurations.put(id, config.clone());
    }

    private static String faceId(NtkSyncMessageCodec.Message message) {
        if (message.faceUuid() == null) throw new IllegalArgumentException("Native face operation requires UUID");
        return message.faceUuid().toString();
    }

    boolean complete() {
        return hasResetBaseline && orderKnown && (ordered.isEmpty() || selectionKnown && ordered.contains(selected))
                && configurations.keySet().containsAll(ordered);
    }

    Map<String, Object> encode() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configurations", configurations);
        result.put("archives", archives);
        result.put("packageDigests", packageDigests);
        result.put("complicationCatalog", complicationCatalog);
        result.put("complicationCatalogComplete", complicationCatalogComplete);
        result.put("ordered", ordered);
        result.put("selected", selected);
        result.put("orderKnown", orderKnown);
        result.put("selectionKnown", selectionKnown);
        result.put("hasResetBaseline", hasResetBaseline);
        result.put("observedAt", observedAt);
        return result;
    }

    static ClockFaceCollection decode(Map<?, ?> encoded) {
        var keys = new java.util.HashSet<>(encoded.keySet()); keys.remove("archives"); keys.remove("packageDigests");
        keys.remove("complicationCatalog"); keys.remove("complicationCatalogComplete");
        if (!keys.equals(java.util.Set.of("configurations", "ordered", "selected", "orderKnown",
                "selectionKnown", "hasResetBaseline", "observedAt"))) throw new IllegalArgumentException("Invalid face snapshot schema");
        ClockFaceCollection result = new ClockFaceCollection();
        if (encoded.containsKey("complicationCatalog")) {
            if (!(encoded.get("complicationCatalog") instanceof Map<?, ?> catalog)) throw new IllegalArgumentException("Invalid stored complication catalog");
            result.complicationCatalog.putAll(NativeComplicationCatalog.validate(catalog));
            result.complicationCatalogComplete = bool(encoded, "complicationCatalogComplete");
        } else result.complicationCatalogComplete = false;
        if (!(encoded.get("configurations") instanceof Map<?, ?> configs)
                || configs.size() > NtkFacePayloadCodec.MAX_FACES) throw new IllegalArgumentException("Invalid face snapshot configs");
        long size = 0;
        for (var entry : configs.entrySet()) {
            if (!(entry.getKey() instanceof String id) || !(entry.getValue() instanceof byte[] bytes)) {
                throw new IllegalArgumentException("Invalid stored face configuration");
            }
            NtkFacePayloadCodec.uuid(id); NtkFacePayloadCodec.configurationBundle(bytes);
            size += bytes.length;
            if (size > 512 * 1024) throw new IllegalArgumentException("Stored face configuration bound exceeded");
            result.configurations.put(id, bytes.clone());
        }
        if (encoded.containsKey("archives")) {
            if (!(encoded.get("archives") instanceof Map<?, ?> packages)) throw new IllegalArgumentException("Invalid stored packages");
            long total = 0;
            for (var entry : packages.entrySet()) {
                if (!(entry.getKey() instanceof String id) || !result.configurations.containsKey(id)
                        || !(entry.getValue() instanceof byte[] bytes) || bytes.length > ClockFaceDeltaCommand.MAX_PAYLOAD_BYTES)
                    throw new IllegalArgumentException("Invalid stored native package");
                total += bytes.length;
                if (total > MAX_ARCHIVE_TOTAL) throw new IllegalArgumentException("Stored packages bound exceeded");
                try {
                    byte[] config = NtkFacePayloadCodec.configurationFromZip(bytes);
                    if (!BoundedJson.decode(config, NtkFacePayloadCodec.MAX_CONFIG_BYTES).equals(
                            BoundedJson.decode(result.configurations.get(id), NtkFacePayloadCodec.MAX_CONFIG_BYTES)))
                        throw new IllegalArgumentException("Stored native package configuration mismatch");
                } catch (IOException invalid) { throw new IllegalArgumentException("Invalid stored native package", invalid); }
                result.archives.put(id, bytes.clone());
            }
        }
        if (encoded.containsKey("packageDigests")) {
            if (!(encoded.get("packageDigests") instanceof Map<?, ?> packages) || packages.size() > NtkFacePayloadCodec.MAX_FACES)
                throw new IllegalArgumentException("Invalid stored package references");
            for (var entry : packages.entrySet()) {
                if (!(entry.getKey() instanceof String id) || !result.configurations.containsKey(id)
                        || !(entry.getValue() instanceof String hash) || !hash.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid stored package reference");
                result.packageDigests.put(id, hash);
            }
        }
        if (!(encoded.get("ordered") instanceof List<?> order) || order.size() > NtkFacePayloadCodec.MAX_FACES) {
            throw new IllegalArgumentException("Invalid stored order");
        }
        for (Object item : order) {
            if (!(item instanceof String text)) throw new IllegalArgumentException("Invalid stored UUID");
            String id = NtkFacePayloadCodec.uuid(text);
            if (result.ordered.contains(id)) throw new IllegalArgumentException("Duplicate stored UUID");
            result.ordered.add(id);
        }
        if (!(encoded.get("selected") instanceof String selected)) throw new IllegalArgumentException("Invalid stored selection");
        result.selected = selected.isEmpty() ? "" : NtkFacePayloadCodec.uuid(selected);
        result.orderKnown = bool(encoded, "orderKnown");
        result.selectionKnown = bool(encoded, "selectionKnown");
        result.hasResetBaseline = bool(encoded, "hasResetBaseline");
        if (!(encoded.get("observedAt") instanceof Long time) || time < 0) throw new IllegalArgumentException("Invalid stored observation time");
        result.observedAt = time;
        return result;
    }

    private static boolean bool(Map<?, ?> encoded, String key) {
        if (!(encoded.get(key) instanceof Boolean value)) throw new IllegalArgumentException("Invalid stored boolean");
        return value;
    }
}
