package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Native face mutations with opaque resources. No ResetStore. */
final class NtkFaceChangeEncoder {
    private NtkFaceChangeEncoder() { }

    static byte[] select(String id) {
        return change(4, null, asciiUuid(id));
    }

    static byte[] order(List<String> ids) {
        if (ids == null || ids.size() > NtkFacePayloadCodec.MAX_FACES) throw invalid();
        List<String> canonical = new ArrayList<>();
        for (String id : ids) canonical.add(NtkFacePayloadCodec.uuid(id));
        if (new java.util.HashSet<>(canonical).size() != canonical.size()) throw invalid();
        String json = "[" + String.join(",", canonical.stream()
                .map(id -> "\"" + id.toUpperCase(Locale.ROOT) + "\"").collect(java.util.stream.Collectors.toList())) + "]";
        return change(5, null, json.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] update(String id, byte[] configuration) {
        NtkFacePayloadCodec.configurationBundle(configuration);
        return change(1, UUID.fromString(NtkFacePayloadCodec.uuid(id)), configuration);
    }

    static byte[] remove(String id) {
        return change(3, UUID.fromString(NtkFacePayloadCodec.uuid(id)), null);
    }

    /** Only a face.json ZIP; callers must establish that this face needs no external resources. */
    static byte[] addConfiguration(String id, byte[] configuration) throws IOException {
        UUID uuid = UUID.fromString(NtkFacePayloadCodec.uuid(id));
        NtkFacePayloadCodec.configurationBundle(configuration);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            ZipEntry entry = new ZipEntry("face.json"); entry.setTime(0);
            zip.putNextEntry(entry); zip.write(configuration); zip.closeEntry();
        }
        byte[] payload = bytes.toByteArray();
        try { return change(0, uuid, payload); }
        finally { Arrays.fill(payload, (byte) 0); }
    }

    /** Preserve every archive resource byte, instead of reconstructing an imported face from a preview model. */
    static byte[] addArchive(String id, byte[] archive) throws IOException {
        byte[] config = NtkFacePayloadCodec.configurationFromZip(archive);
        Arrays.fill(config, (byte) 0);
        return change(0, UUID.fromString(NtkFacePayloadCodec.uuid(id)), archive);
    }

    static List<byte[]> addArchiveParts(String id, byte[] archive) throws IOException {
        if (archive == null || archive.length > NativeFacePackageStore.MAX_PACKAGE_BYTES) throw invalid();
        byte[] config = NtkFacePayloadCodec.configurationFromZip(archive); Arrays.fill(config, (byte) 0);
        return payloadParts(0, id, archive);
    }

    static List<byte[]> resourceParts(String id, byte[] resources) throws IOException {
        NtkFaceResources.validate(resources);
        return payloadParts(2, id, resources);
    }

    private static List<byte[]> payloadParts(int type, String id, byte[] archive) {
        UUID face = UUID.fromString(NtkFacePayloadCodec.uuid(id));
        if (archive.length <= 128 * 1024) return List.of(change(type, face, archive));
        final int partSize = 128 * 1024;
        int count = (archive.length + partSize - 1) / partSize;
        UUID load = UUID.randomUUID();
        List<byte[]> changes = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                byte[] part = Arrays.copyOfRange(archive, i * partSize, Math.min(archive.length, (i + 1) * partSize));
                try { changes.add(change(type, face, part, load, count, i, partSize)); }
                finally { Arrays.fill(part, (byte) 0); }
            }
            return changes;
        } catch (RuntimeException invalid) { for (byte[] bytes : changes) Arrays.fill(bytes, (byte) 0); throw invalid; }
    }

    private static byte[] asciiUuid(String id) {
        return NtkFacePayloadCodec.uuid(id).toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] change(int type, UUID face, byte[] payload) {
        return change(type, face, payload, null, 0, 0, 0);
    }

    private static byte[] change(int type, UUID face, byte[] payload, UUID load, int count, int part, int maxSize) {
        List<Object> objects = new ArrayList<>(); objects.add("$null");
        Map<String, Object> message = new LinkedHashMap<>(); objects.add(message);
        message.put("messageType", (long) type);
        message.put("$class", reference(objects, descriptor("NTKDSyncMessage", "NSObject")));
        if (face == null) message.put("faceUUID", new AppleBinaryPropertyList.Uid(0));
        else {
            byte[] uuid = ByteBuffer.allocate(16).putLong(face.getMostSignificantBits())
                    .putLong(face.getLeastSignificantBits()).array();
            message.put("faceUUID", reference(objects, Map.of("$class",
                    reference(objects, descriptor("NSUUID", "NSObject")), "NS.uuidbytes", uuid)));
        }
        message.put("payload", payload == null ? new AppleBinaryPropertyList.Uid(0)
                : reference(objects, payload));
        message.put("progress", reference(objects, 0.0));
        message.put("numberOfParts", reference(objects, (long) count));
        message.put("partNumber", reference(objects, (long) part)); message.put("maxPartSize", reference(objects, (long) maxSize));
        for (String key : List.of("label", "complicationClientID", "complicationDescriptor", "complicationFamily",
                "complicationCollectionIdentifier")) message.put(key, new AppleBinaryPropertyList.Uid(0));
        message.put("wideLoadId", load == null ? new AppleBinaryPropertyList.Uid(0) : reference(objects,
                Map.of("$class", reference(objects, descriptor("NSUUID", "NSObject")), "NS.uuidbytes",
                        ByteBuffer.allocate(16).putLong(load.getMostSignificantBits()).putLong(load.getLeastSignificantBits()).array())));
        byte[] archive = archive(objects);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ClockFaceSyncHeaderCodec.integer(out, 1, type == 0 ? 0 : type == 3 ? 2 : 1);
            // NTKDSyncMessage.objectIdentifier is "unknown", including the actual AddFace fixture.
            ClockFaceSyncHeaderCodec.field(out, 2, "unknown".getBytes(StandardCharsets.US_ASCII));
            ClockFaceSyncHeaderCodec.integer(out, 3, 0);
            ClockFaceSyncHeaderCodec.field(out, 4, archive);
            return out.toByteArray();
        } finally { Arrays.fill(archive, (byte) 0); }
    }

    static byte[] senderMetadata() {
        return metadata(false);
    }

    static byte[] receiverMetadata() {
        return metadata(true);
    }

    private static byte[] metadata(boolean receiver) {
        List<Object> objects = new ArrayList<>(); objects.add("$null");
        Map<String, Object> root = new LinkedHashMap<>(); objects.add(root);
        root.put("$class", reference(objects, descriptor("NSDictionary", "NSObject")));
        List<Object> keys = new ArrayList<>(), values = new ArrayList<>();
        keys.add(reference(objects, "senderSyncVersion")); values.add(reference(objects, 2L));
        if (receiver) {
            keys.add(reference(objects, "receiverSyncVersion")); values.add(reference(objects, 2L));
        }
        root.put("NS.keys", keys); root.put("NS.objects", values);
        return archive(objects);
    }

    private static Map<String, Object> descriptor(String name, String... parents) {
        List<String> classes = new ArrayList<>(); classes.add(name); classes.addAll(List.of(parents));
        return Map.of("$classname", name, "$classes", classes);
    }

    private static AppleBinaryPropertyList.Uid reference(List<Object> objects, Object value) {
        int index = objects.size(); objects.add(value); return new AppleBinaryPropertyList.Uid(index);
    }

    private static byte[] archive(List<Object> objects) {
        return AppleBinaryPropertyList.encode(Map.of("$version", 100000L, "$archiver", "NSKeyedArchiver",
                "$top", Map.of("root", new AppleBinaryPropertyList.Uid(1)), "$objects", objects));
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid native face mutation"); }
}
