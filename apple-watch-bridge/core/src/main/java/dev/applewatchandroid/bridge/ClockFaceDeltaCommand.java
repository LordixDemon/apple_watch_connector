package dev.applewatchandroid.bridge;

import java.io.IOException;

/** Fixed operational commands reference committed UUIDs, never accept a raw native packet. */
final class ClockFaceDeltaCommand {
    static final String SELECT = "NATIVE_FACE_SELECT:", DUPLICATE = "NATIVE_FACE_DUPLICATE:",
            REMOVE = "NATIVE_FACE_REMOVE:", REORDER = "NATIVE_FACE_REORDER:", UPDATE = "NATIVE_FACE_UPDATE:",
            ADD = "NATIVE_FACE_ADD:", ADD_FILE = "NATIVE_FACE_ADD_FILE:", RESOURCES_FILE = "NATIVE_FACE_RESOURCES_FILE:";
    static final int MAX_PAYLOAD_BYTES = 128 * 1024;
    record Command(ClockFaceDeltaPlan.Kind kind, String source, String target, String payload, boolean staged) {
        Command(ClockFaceDeltaPlan.Kind kind, String source, String target, String payload) { this(kind, source, target, payload, false); }
        boolean duplicate() { return kind == ClockFaceDeltaPlan.Kind.DUPLICATE; }
        ClockFaceDeltaPlan plan(ClockFaceCollection collection) throws IOException {
            return plan(collection, null);
        }
        ClockFaceDeltaPlan plan(ClockFaceCollection collection, java.nio.file.Path stagingRoot) throws IOException {
            if (staged) {
                if (stagingRoot == null) throw new IllegalArgumentException("Missing native package staging root");
                String[] fields = payload.split(":", -1);
                java.nio.file.Path path = stagingRoot.resolve(fields[0] + ".watchface");
                byte[] bytes = NativeFacePackageStore.readFile(path, fields[1]);
                try { return kind == ClockFaceDeltaPlan.Kind.RESOURCES ? ClockFaceDeltaPlan.resources(collection, target, bytes, fields[2])
                        : ClockFaceDeltaPlan.add(collection, target, bytes); }
                finally { java.util.Arrays.fill(bytes, (byte) 0); java.nio.file.Files.deleteIfExists(path); }
            }
            return switch (kind) {
                case SELECT -> ClockFaceDeltaPlan.select(collection, target);
                case DUPLICATE -> ClockFaceDeltaPlan.duplicate(collection, source, target);
                case REMOVE -> ClockFaceDeltaPlan.remove(collection, target);
                case REORDER -> ClockFaceDeltaPlan.reorder(collection, java.util.List.of(payload.split(",", -1)));
                case UPDATE, ADD -> {
                    byte[] bytes = bytes(payload);
                    try { yield kind == ClockFaceDeltaPlan.Kind.UPDATE ? ClockFaceDeltaPlan.update(collection, target, bytes)
                            : ClockFaceDeltaPlan.add(collection, target, bytes); }
                    finally { java.util.Arrays.fill(bytes, (byte) 0); }
                }
                case RESOURCES -> throw new IllegalArgumentException("Resource updates require a sealed staged package");
            };
        }
    }
    private ClockFaceDeltaCommand() { }
    static boolean matches(String command) {
        return command != null && (command.startsWith(SELECT) || command.startsWith(DUPLICATE)
                || command.startsWith(REMOVE) || command.startsWith(REORDER) || command.startsWith(UPDATE)
                || command.startsWith(ADD) || command.startsWith(ADD_FILE) || command.startsWith(RESOURCES_FILE));
    }
    static Command parse(String command) {
        if (!matches(command)) throw new IllegalArgumentException("Unknown native face command");
        if (command.startsWith(ADD_FILE) || command.startsWith(RESOURCES_FILE)) {
            boolean resources = command.startsWith(RESOURCES_FILE);
            String[] fields = command.substring(resources ? RESOURCES_FILE.length() : ADD_FILE.length()).split(":", -1);
            if (fields.length != (resources ? 4 : 3) || !fields[2].matches("[0-9a-f]{64}")
                    || resources && !fields[3].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid staged face command");
            return new Command(resources ? ClockFaceDeltaPlan.Kind.RESOURCES : ClockFaceDeltaPlan.Kind.ADD,
                    "", canonical(fields[0]), canonical(fields[1]) + ":" + fields[2] + (resources ? ":" + fields[3] : ""), true);
        }
        if (command.startsWith(SELECT)) return new Command(ClockFaceDeltaPlan.Kind.SELECT, "", canonical(command.substring(SELECT.length())), "");
        if (command.startsWith(REMOVE)) return new Command(ClockFaceDeltaPlan.Kind.REMOVE, "", canonical(command.substring(REMOVE.length())), "");
        if (command.startsWith(REORDER)) {
            String order = command.substring(REORDER.length());
            String[] ids = order.split(",", -1);
            if (ids.length > NtkFacePayloadCodec.MAX_FACES) throw new IllegalArgumentException("Oversized face order");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String id : ids) if (!seen.add(canonical(id))) throw new IllegalArgumentException("Duplicate face order UUID");
            return new Command(ClockFaceDeltaPlan.Kind.REORDER, "", "", order);
        }
        if (command.startsWith(UPDATE) || command.startsWith(ADD)) {
            boolean add = command.startsWith(ADD);
            String[] fields = command.substring(add ? ADD.length() : UPDATE.length()).split(":", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Invalid face payload command");
            String target = canonical(fields[0]);
            byte[] decoded = bytes(fields[1]);
            try {
                if (add) NtkFacePayloadCodec.configurationFromZip(decoded);
                else NtkFacePayloadCodec.configurationBundle(decoded);
            } catch (IOException invalid) { throw new IllegalArgumentException("Invalid face archive", invalid); }
            finally { java.util.Arrays.fill(decoded, (byte) 0); }
            return new Command(add ? ClockFaceDeltaPlan.Kind.ADD : ClockFaceDeltaPlan.Kind.UPDATE, "", target, fields[1]);
        }
        String[] fields = command.substring(DUPLICATE.length()).split(":", -1);
        if (fields.length != 2) throw new IllegalArgumentException("Invalid native face duplicate command");
        String source = canonical(fields[0]), target = canonical(fields[1]);
        if (source.equals(target)) throw new IllegalArgumentException("Native face duplicate requires a new UUID");
        return new Command(ClockFaceDeltaPlan.Kind.DUPLICATE, source, target, "");
    }
    private static byte[] bytes(String text) {
        if (text == null || text.isEmpty() || text.length() > (MAX_PAYLOAD_BYTES + 2) / 3 * 4)
            throw new IllegalArgumentException("Oversized face payload");
        byte[] bytes = java.util.Base64.getDecoder().decode(text);
        if (bytes.length > MAX_PAYLOAD_BYTES || !java.util.Base64.getEncoder().encodeToString(bytes).equals(text)) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException("Noncanonical face payload");
        }
        return bytes;
    }
    private static String canonical(String id) {
        if (!NtkFacePayloadCodec.uuid(id).equals(id)) throw new IllegalArgumentException("Noncanonical native face UUID");
        return id;
    }
}
