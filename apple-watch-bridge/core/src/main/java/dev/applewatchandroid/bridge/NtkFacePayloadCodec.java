package dev.applewatchandroid.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Native selection is UTF-8 UUID; order is JSON; AddFace/UpgradeFace are ZIPs. */
final class NtkFacePayloadCodec {
    static final int MAX_CONFIG_BYTES = 128 * 1024;
    static final int MAX_FACES = 256;

    private NtkFacePayloadCodec() { }

    static String uuid(String text) {
        if (text == null || !text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Invalid native face UUID");
        }
        return UUID.fromString(text).toString();
    }

    static String selected(byte[] bytes) {
        if (bytes == null || bytes.length != 36) throw new IllegalArgumentException("Invalid selected face payload");
        try {
            return uuid(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString());
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid selected face UTF-8", failure);
        }
    }

    static List<String> ordered(byte[] bytes) {
        Object decoded = BoundedJson.decode(bytes, 32 * 1024);
        if (!(decoded instanceof List<?> list) || list.size() > MAX_FACES) {
            throw new IllegalArgumentException("Invalid ordered face payload");
        }
        List<String> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text)) throw new IllegalArgumentException("Invalid ordered UUID type");
            String id = uuid(text);
            if (!seen.add(id)) throw new IllegalArgumentException("Duplicate ordered face UUID");
            result.add(id);
        }
        return List.copyOf(result);
    }

    static String configurationBundle(byte[] bytes) {
        Object decoded = BoundedJson.decode(bytes, MAX_CONFIG_BYTES);
        if (!(decoded instanceof Map<?, ?> config) || config.isEmpty()) {
            throw new IllegalArgumentException("Invalid native face configuration");
        }
        Object bundle = config.get("bundle id");
        if (bundle == null) return "";
        if (!(bundle instanceof String name) || name.isEmpty() || name.length() > 255
                || name.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid native face bundle");
        return name;
    }

    static byte[] configurationFromZip(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4 || bytes.length > 16 * 1024 * 1024
                || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw new IOException("Invalid native face ZIP");
        }
        byte[] configuration = null;
        long total = 0;
        int count = 0;
        HashSet<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (++count > 2048 || !names.add(name) || name.startsWith("/") || name.indexOf('\\') >= 0
                        || name.indexOf('\0') >= 0 || List.of(name.split("/", -1)).contains("..")) {
                    throw new IOException("Invalid native face ZIP entry");
                }
                ByteArrayOutputStream out = name.equals("face.json") ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (total > 32L * 1024 * 1024 || (out != null && out.size() > MAX_CONFIG_BYTES - read)) {
                        throw new IOException("Native face ZIP expanded bound exceeded");
                    }
                    if (out != null) out.write(buffer, 0, read);
                }
                if (out != null) configuration = out.toByteArray();
                zip.closeEntry();
            }
        }
        if (configuration == null) throw new IOException("Native face ZIP has no face.json");
        configurationBundle(configuration);
        return configuration;
    }

    /** Prove every opaque resource, including the manifest that references the images.
     * Greenfield's root sharing metadata/previews are not NTK resources: the native
     * sharing decoder and SY ZIP writer deliberately omit them from Watch readback.
     * A rewritten resource manifest still requires its own semantic proof. */
    static Map<String, byte[]> resourceDigests(byte[] archive) throws IOException {
        byte[] configuration = configurationFromZip(archive); // bounds and path validation
        java.util.Arrays.fill(configuration, (byte) 0);
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            byte[] buffer = new byte[4096]; ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || sharingPresentation(entry.getName()) || entry.getName().equals("face.json")) continue;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                int count; while ((count = zip.read(buffer)) != -1) digest.update(buffer, 0, count);
                result.put(entry.getName(), digest.digest());
            }
            java.util.Arrays.fill(buffer, (byte) 0);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        return Map.copyOf(result);
    }

    private static boolean sharingPresentation(String name) {
        // Exact root names verified against NTKGreenfieldUtilities' encoder and
        // a genuine exported/decoded native package. Nested resources stay pinned.
        return name.equals("metadata.json") || name.equals("snapshot.png") || name.equals("no_borders_snapshot.png");
    }

    /** Keep sharing previews locally, avoiding their needless upload over Bluetooth. */
    static byte[] wireArchive(byte[] archive) throws IOException {
        byte[] configuration = configurationFromZip(archive);
        java.util.Arrays.fill(configuration, (byte) 0);
        boolean presentation = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (sharingPresentation(entry.getName())) { presentation = true; break; }
            }
        }
        if (!presentation) return archive.clone();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive));
                ZipOutputStream writer = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (sharingPresentation(entry.getName())) continue;
                writer.putNextEntry(new ZipEntry(entry.getName()));
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    writer.write(buffer, 0, count);
                    if (output.size() > NativeFacePackageStore.MAX_PACKAGE_BYTES) throw new IOException("Native wire archive exceeded bound");
                }
                writer.closeEntry();
            }
        } finally { java.util.Arrays.fill(buffer, (byte) 0); }
        byte[] bytes = output.toByteArray();
        if (bytes.length > NativeFacePackageStore.MAX_PACKAGE_BYTES) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw new IOException("Native wire archive exceeded bound");
        }
        return bytes;
    }

    static String configurationIdentity(byte[] bytes) {
        String bundle = configurationBundle(bytes);
        if (!bundle.isEmpty()) return "bundle:" + bundle;
        Map<?, ?> config = (Map<?, ?>) BoundedJson.decode(bytes, MAX_CONFIG_BYTES);
        Object type = config.get("face type");
        if (!(type instanceof String name) || name.isEmpty() || name.length() > 255 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Missing native face family");
        return "type:" + name;
    }

    /** A configuration-only update leaves native resource contents intact. */
    static byte[] replacingConfiguration(byte[] archive, byte[] configuration) throws IOException {
        configurationFromZip(archive); configurationBundle(configuration);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(archive)); var zip = new ZipOutputStream(out)) {
            ZipEntry entry; byte[] buffer = new byte[65536];
            while ((entry = input.getNextEntry()) != null) {
                ZipEntry replacement = new ZipEntry(entry.getName());
                if (entry.getTime() >= 0) replacement.setTime(entry.getTime());
                zip.putNextEntry(replacement);
                if (entry.getName().equals("face.json")) zip.write(configuration);
                else { int count; while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count); }
                input.closeEntry(); zip.closeEntry();
                if (out.size() > NativeFacePackageStore.MAX_PACKAGE_BYTES) throw new IOException("Updated native package bound exceeded");
            }
        }
        byte[] bytes = out.toByteArray();
        if (bytes.length > NativeFacePackageStore.MAX_PACKAGE_BYTES) throw new IOException("Updated native package bound exceeded");
        return bytes;
    }
}
