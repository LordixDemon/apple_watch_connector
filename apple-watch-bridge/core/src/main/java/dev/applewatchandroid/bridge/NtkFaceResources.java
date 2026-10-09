package dev.applewatchandroid.bridge;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Type 2 replaces the entire resource directory, not individual files.
 * watchOS 23S303: NTKDFaceResourceDirectoryFromPayloadPath unzips at root;
 * NTKDCollectionStore 0x10003c218 copies that directory to a new Resources UUID.
 * The face configuration, collection order and selected UUID are separate facts. */
final class NtkFaceResources {
    private NtkFaceResources() { }

    static byte[] fromPackage(byte[] archive) throws IOException {
        byte[] configuration = NtkFacePayloadCodec.configurationFromZip(archive);
        Arrays.fill(configuration, (byte) 0);
        Map<String, byte[]> files = read(archive, false, name -> name.startsWith("Resources/"));
        Map<String, byte[]> resources = new LinkedHashMap<>();
        try {
            for (var entry : files.entrySet()) if (entry.getKey().startsWith("Resources/"))
                resources.put(entry.getKey().substring("Resources/".length()), entry.getValue());
            if (resources.isEmpty()) throw new IOException("Native resource directory is empty");
            return zip(resources);
        } finally { wipe(files); }
    }

    static void validate(byte[] resources) throws IOException {
        Map<String, byte[]> files = read(resources, true, name -> false);
        wipe(files);
    }

    static byte[] replacing(byte[] archive, byte[] resources) throws IOException {
        byte[] configuration = NtkFacePayloadCodec.configurationFromZip(archive);
        Arrays.fill(configuration, (byte) 0);
        // Stream discarded old JPEGs rather than allocating and then cloning them.
        Map<String, byte[]> original = read(archive, false, name -> !name.startsWith("Resources/")), replacement = null;
        try {
            replacement = read(resources, true, name -> true);
            for (var entry : replacement.entrySet()) original.put("Resources/" + entry.getKey(), entry.getValue());
            return zip(original);
        } finally { wipe(original); if (replacement != null) wipe(replacement); }
    }

    private static Map<String, byte[]> read(byte[] bytes, boolean resources, java.util.function.Predicate<String> retain) throws IOException {
        if (bytes == null || bytes.length < 4 || bytes.length > NativeFacePackageStore.MAX_PACKAGE_BYTES
                || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4)
            throw new IOException("Invalid native resource ZIP");
        Map<String, byte[]> files = new LinkedHashMap<>();
        Set<String> names = new HashSet<>(), fileNames = new HashSet<>(); long total = 0;
        byte[] buffer = new byte[65536];
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                String canonical = entry.isDirectory() && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                if (names.size() >= 2048 || !names.add(canonical) || canonical.isEmpty()
                        || canonical.indexOf('\\') >= 0 || canonical.indexOf('\0') >= 0
                        || Arrays.stream(canonical.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))
                        || resources && (canonical.equals("face.json") || canonical.equals("Resources") || canonical.startsWith("Resources/")))
                    throw new IOException("Unsafe native resource ZIP entry");
                ByteArrayOutputStream out = !entry.isDirectory() && retain.test(canonical) ? new ByteArrayOutputStream() : null;
                int count; long entryBytes = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    entryBytes += count;
                    if (total > 32L * 1024 * 1024) throw new IOException("Native resources expanded bound exceeded");
                    if (out != null) out.write(buffer, 0, count);
                }
                if (entry.isDirectory()) {
                    if (entryBytes != 0) throw new IOException("Resource directory has file contents");
                } else {
                    fileNames.add(canonical);
                    if (out != null) files.put(canonical, out.toByteArray());
                }
                input.closeEntry();
            }
            if (fileNames.isEmpty()) throw new IOException("Native resource ZIP has no files");
            // A file cannot also be a parent directory of another entry.
            for (String name : names) {
                int slash = name.indexOf('/');
                while (slash >= 0) {
                    if (fileNames.contains(name.substring(0, slash))) throw new IOException("Native ZIP file/directory collision");
                    slash = name.indexOf('/', slash + 1);
                }
            }
            return files;
        } catch (IOException | RuntimeException failure) { wipe(files); throw failure; }
        finally { Arrays.fill(buffer, (byte) 0); }
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : files.entrySet()) {
                ZipEntry file = new ZipEntry(entry.getKey()); file.setTime(0);
                zip.putNextEntry(file); zip.write(entry.getValue()); zip.closeEntry();
                if (out.size() > NativeFacePackageStore.MAX_PACKAGE_BYTES) throw new IOException("Native resource package bound exceeded");
            }
        }
        if (out.size() > NativeFacePackageStore.MAX_PACKAGE_BYTES) throw new IOException("Native resource package bound exceeded");
        return out.toByteArray();
    }

    private static void wipe(Map<String, byte[]> files) { for (byte[] bytes : files.values()) Arrays.fill(bytes, (byte) 0); }
}
