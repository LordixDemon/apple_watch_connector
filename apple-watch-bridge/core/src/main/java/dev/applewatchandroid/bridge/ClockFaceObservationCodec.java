package dev.applewatchandroid.bridge;

import java.io.*;
import java.util.*;

/** Bounded private HAL-to-APK projection. This is not a Watch wire command. */
final class ClockFaceObservationCodec {
    static final String PREFIX = "BRIDGE_CLOCKFACE_V1:";
    static final int MAX_FRAME = 1024 * 1024;
    record Face(String id, String bundle, int configurationBytes, String configuration, String archiveBase64, String packageDigest) {
        Face(String id, String bundle, int configurationBytes) { this(id, bundle, configurationBytes, null, null, null); }
        Face(String id, String bundle, int configurationBytes, String configuration, String archiveBase64) { this(id, bundle, configurationBytes, configuration, archiveBase64, null); }
        Face {
            if (!NtkFacePayloadCodec.uuid(id).equals(id) || bundle == null || bundle.length() > 255
                    || bundle.indexOf('\0') >= 0 || configurationBytes < 1
                    || configurationBytes > NtkFacePayloadCodec.MAX_CONFIG_BYTES) {
                throw new IllegalArgumentException("Invalid face observation");
            }
            if (configuration != null) {
                byte[] bytes = configuration.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (bytes.length != configurationBytes || !bundle.equals(NtkFacePayloadCodec.configurationBundle(bytes)))
                    throw new IllegalArgumentException("Mismatched face configuration observation");
            }
            if (archiveBase64 != null) {
                byte[] archive = java.util.Base64.getDecoder().decode(archiveBase64);
                if (archive.length > ClockFaceDeltaCommand.MAX_PAYLOAD_BYTES || configuration == null)
                    throw new IllegalArgumentException("Oversized observed native package");
            }
            if (packageDigest != null && !packageDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid native package reference");
        }
    }
    record Observation(UUID pair, UUID epoch, long observedAt, boolean resetBaseline,
                       boolean orderKnown, boolean selectionKnown, String selected,
                       List<String> ordered, List<Face> faces, Map<String, Object> complicationCatalog, boolean complicationCatalogComplete) {
        Observation(UUID pair, UUID epoch, long observedAt, boolean resetBaseline,
                    boolean orderKnown, boolean selectionKnown, String selected, List<String> ordered, List<Face> faces) {
            this(pair, epoch, observedAt, resetBaseline, orderKnown, selectionKnown, selected, ordered, faces, Map.of(), false);
        }
        Observation {
            Objects.requireNonNull(pair); Objects.requireNonNull(epoch);
            Objects.requireNonNull(selected);
            ordered = List.copyOf(ordered); faces = List.copyOf(faces);
            complicationCatalog = NativeComplicationCatalog.validate(complicationCatalog);
            if (observedAt <= 0 || ordered.size() > 256 || faces.size() > 256
                    || !selected.isEmpty() && !NtkFacePayloadCodec.uuid(selected).equals(selected)) {
                throw new IllegalArgumentException("Invalid collection observation");
            }
            Set<String> ids = new HashSet<>();
            for (String id : ordered) if (!NtkFacePayloadCodec.uuid(id).equals(id) || !ids.add(id)) {
                throw new IllegalArgumentException("Invalid observation order");
            }
            ids.clear();
            long size = 0;
            long packages = 0;
            for (Face face : faces) if (!ids.add(face.id())) throw new IllegalArgumentException("Duplicate observed face");
            for (Face face : faces) size += face.configurationBytes();
            for (Face face : faces) if (face.archiveBase64() != null) packages += java.util.Base64.getDecoder().decode(face.archiveBase64()).length;
            if (size > 512 * 1024) throw new IllegalArgumentException("Observed configurations bound exceeded");
            if (packages > 256 * 1024) throw new IllegalArgumentException("Observed packages bound exceeded");
        }
        boolean complete() {
            Set<String> ids = new HashSet<>();
            for (Face face : faces) ids.add(face.id());
            return resetBaseline && orderKnown && ids.containsAll(ordered)
                    && (ordered.isEmpty() || selectionKnown && ordered.contains(selected));
        }
    }
    static Observation fromCollection(UUID pair, UUID epoch, ClockFaceCollection collection) {
        List<Face> faces = new ArrayList<>();
        for (var entry : collection.configurations.entrySet()) faces.add(new Face(entry.getKey(),
                NtkFacePayloadCodec.configurationBundle(entry.getValue()), entry.getValue().length,
                new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8),
                !collection.packageDigests.containsKey(entry.getKey()) && collection.archives.containsKey(entry.getKey())
                        ? java.util.Base64.getEncoder().encodeToString(collection.archives.get(entry.getKey())) : null,
                collection.packageDigests.get(entry.getKey())));
        return new Observation(pair, epoch, collection.observedAt, collection.hasResetBaseline,
                collection.orderKnown, collection.selectionKnown, collection.selected, collection.ordered, faces,
                NativeComplicationCatalog.forObservation(collection.complicationCatalog, collection.configurations),
                collection.complicationCatalogComplete);
    }
    static byte[] encode(Observation value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41574635); out.writeUTF(value.pair().toString()); out.writeUTF(value.epoch().toString());
            out.writeLong(value.observedAt()); out.writeBoolean(value.resetBaseline());
            out.writeBoolean(value.orderKnown()); out.writeBoolean(value.selectionKnown()); out.writeUTF(value.selected());
            out.writeShort(value.ordered().size());
            for (String id : value.ordered()) out.writeUTF(id);
            out.writeShort(value.faces().size());
            for (Face face : value.faces()) {
                out.writeUTF(face.id()); out.writeUTF(face.bundle()); out.writeInt(face.configurationBytes());
                byte[] config = face.configuration() == null ? new byte[0]
                        : face.configuration().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeInt(config.length); out.write(config);
                byte[] archive = face.archiveBase64() == null ? new byte[0] : java.util.Base64.getDecoder().decode(face.archiveBase64());
                out.writeInt(archive.length); out.write(archive);
                out.writeUTF(face.packageDigest() == null ? "" : face.packageDigest());
            }
            byte[] catalog = AppleBinaryPropertyList.encode(value.complicationCatalog());
            out.writeBoolean(value.complicationCatalogComplete()); out.writeInt(catalog.length); out.write(catalog);
        }
        if (bytes.size() > MAX_FRAME) throw new IOException("Face observation bound exceeded");
        return bytes.toByteArray();
    }
    static Observation decode(byte[] frame) throws IOException {
        if (frame == null || frame.length > MAX_FRAME) throw new IOException("Invalid face observation size");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame))) {
            int magic = in.readInt();
            if (magic < 0x41574631 || magic > 0x41574635) throw new IOException("Invalid face observation version");
            UUID pair = canonicalUuid(in.readUTF()), epoch = canonicalUuid(in.readUTF());
            long time = in.readLong();
            boolean reset = bool(in), order = bool(in), selection = bool(in);
            String selected = in.readUTF();
            int count = in.readUnsignedShort();
            if (count > 256) throw new IOException("Observed order bound exceeded");
            List<String> ordered = new ArrayList<>();
            for (int i = 0; i < count; i++) ordered.add(in.readUTF());
            count = in.readUnsignedShort();
            if (count > 256) throw new IOException("Observed face bound exceeded");
            List<Face> faces = new ArrayList<>();
            long total = 0;
            long packageTotal = 0;
            for (int i = 0; i < count; i++) {
                String id = in.readUTF(), bundle = in.readUTF(); int size = in.readInt();
                String configuration = null;
                if (magic != 0x41574631) {
                    int length = in.readInt(); total += length;
                    if (length < 0 || length > NtkFacePayloadCodec.MAX_CONFIG_BYTES || total > 512 * 1024)
                        throw new IOException("Oversized face configuration observation");
                    byte[] bytes = in.readNBytes(length);
                    if (bytes.length != length) throw new EOFException("Truncated face configuration");
                    if (length > 0) {
                        NtkFacePayloadCodec.configurationBundle(bytes);
                        configuration = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                String archive = null;
                if (magic >= 0x41574633) {
                    int length = in.readInt(); packageTotal += length;
                    if (length < 0 || length > ClockFaceDeltaCommand.MAX_PAYLOAD_BYTES || packageTotal > 256 * 1024)
                        throw new IOException("Oversized observed native packages");
                    byte[] bytes = in.readNBytes(length);
                    if (bytes.length != length) throw new EOFException("Truncated native package");
                    if (length > 0) {
                        byte[] config = NtkFacePayloadCodec.configurationFromZip(bytes);
                        if (configuration == null || !BoundedJson.decode(config, NtkFacePayloadCodec.MAX_CONFIG_BYTES).equals(
                                BoundedJson.decode(configuration.getBytes(java.nio.charset.StandardCharsets.UTF_8), NtkFacePayloadCodec.MAX_CONFIG_BYTES)))
                            throw new IOException("Observed package configuration mismatch");
                        archive = java.util.Base64.getEncoder().encodeToString(bytes);
                    }
                }
                String hash = magic >= 0x41574634 ? in.readUTF() : "";
                faces.add(new Face(id, bundle, size, configuration, archive, hash.isEmpty() ? null : hash));
            }
            Map<String, Object> catalog = Map.of(); boolean catalogComplete = false;
            if (magic >= 0x41574635) {
                catalogComplete = bool(in); int length = in.readInt();
                if (length < 1 || length > NativeComplicationCatalog.MAX_BYTES) throw new IOException("Invalid complication catalog size");
                byte[] bytes = in.readNBytes(length);
                if (bytes.length != length) throw new EOFException("Truncated complication catalog");
                if (!(AppleBinaryPropertyList.decodeBounded(bytes, NativeComplicationCatalog.MAX_BYTES) instanceof Map<?, ?> decoded))
                    throw new IOException("Invalid complication catalog");
                catalog = NativeComplicationCatalog.validate(decoded);
            }
            if (in.read() != -1) throw new IOException("Trailing face observation data");
            return new Observation(pair, epoch, time, reset, order, selection, selected, ordered, faces, catalog, catalogComplete);
        }
    }
    private static UUID canonicalUuid(String text) {
        if (!NtkFacePayloadCodec.uuid(text).equals(text)) throw new IllegalArgumentException("Noncanonical observation UUID");
        return UUID.fromString(text);
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IOException("Invalid observation boolean");
        return value == 1;
    }
    private ClockFaceObservationCodec() { }
}
