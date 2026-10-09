package dev.applewatchandroid.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Appends IDS resource-transfer chunks (socket command 0x16) that ride the
 * preferences sync lane. The live session logged four of these and dropped
 * them, so only the small user-defaults keys were kept.
 */
final class PairedSyncResourceStore {
    static volatile File directory = ProtocolPaths.files().resolve("paired-sync-resources").toFile();

    static final class Result {
        final int chunkBytes;
        final long totalBytes;
        final String savedName;

        Result(int chunkBytes, long totalBytes, String savedName) {
            this.chunkBytes = chunkBytes;
            this.totalBytes = totalBytes;
            this.savedName = savedName;
        }
    }

    private PairedSyncResourceStore() {
    }

    static void setDirectoryForTesting(File dir) {
        directory = dir != null
                ? dir
                : ProtocolPaths.files().resolve("paired-sync-resources").toFile();
    }

    static Result append(IdsSocketPairCodec.DataMessage message) {
        if (message == null || message.messageUuid == null) {
            throw new IllegalArgumentException("Resource transfer is incomplete");
        }
        String name = safeUuid(message.messageUuid);
        File dir = directory;
        try {
            Result nativeChunk = IdsResourceTransferStore.append(message, dir.toPath());
            if (nativeChunk != null) return nativeChunk;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot store native resource transfer", error);
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create resource transfer directory");
        }
        File data = new File(dir, name + ".bin");
        File seq = new File(dir, name + ".seq");
        long seen = readSequence(seq);
        byte[] chunk = message.payload == null ? new byte[0] : message.payload.clone();
        try {
            if (seen == message.sequence && data.isFile()) {
                return new Result(0, data.length(), null);
            }
            try (FileOutputStream out = new FileOutputStream(data, true)) {
                out.write(chunk);
                out.getFD().sync();
            }
            try (FileOutputStream seqOut = new FileOutputStream(seq)) {
                seqOut.write(Long.toUnsignedString(message.sequence).getBytes(StandardCharsets.US_ASCII));
            }
            String saved = saveInnerFile(dir, chunk);
            return new Result(chunk.length, data.length(), saved);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot store resource transfer", error);
        } finally {
            java.util.Arrays.fill(chunk, (byte) 0);
        }
    }

    /**
     * Live transfers are {@code 0x01} plus gzip of a binary plist. The file
     * bytes sit under {@code ids-message-resource-transfer-data}, and a watch
     * face backup is a zip whose only entry is {@code face.json}.
     */
    private static String saveInnerFile(File dir, byte[] chunk) {
        if (chunk.length < 3 || chunk[0] != 1
                || chunk[1] != 0x1f || (chunk[2] & 0xff) != 0x8b) {
            return null;
        }
        byte[] plain;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(chunk, 1, chunk.length - 1));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = gzip.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            plain = out.toByteArray();
        } catch (IOException invalid) {
            return null;
        }
        Map<String, Object> dictionary;
        try {
            dictionary = BinaryPropertyListCodec.decodeDictionary(plain);
        } catch (RuntimeException invalid) {
            return null;
        }
        Object url = dictionary.get("ids-message-resource-transfer-url");
        Object body = dictionary.get("ids-message-resource-transfer-data");
        if (!(url instanceof String path) || !(body instanceof byte[] fileBytes)) {
            return null;
        }
        String name = fileName(path);
        if (name == null) {
            return null;
        }
        File named = new File(new File(dir, "files"), name);
        File parent = named.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return null;
        }
        try (FileOutputStream out = new FileOutputStream(named)) {
            out.write(fileBytes);
            out.getFD().sync();
        } catch (IOException error) {
            return null;
        }
        if (fileBytes.length >= 4
                && fileBytes[0] == 0x50 && fileBytes[1] == 0x4b
                && fileBytes[2] == 0x03 && fileBytes[3] == 0x04) {
            extractFaceJson(fileBytes, new File(named.getParentFile(), name + ".face.json"));
        }
        return name;
    }

    private static void extractFaceJson(byte[] zipBytes, File destination) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"face.json".equals(entry.getName()) || entry.isDirectory()) {
                    continue;
                }
                try (FileOutputStream out = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    int written = 0;
                    while ((read = zip.read(buffer)) > 0) {
                        written += read;
                        if (written > 1024 * 1024) {
                            return;
                        }
                        out.write(buffer, 0, read);
                    }
                }
                return;
            }
        } catch (IOException ignored) {
            // The raw zip is already stored beside this optional extract.
        }
    }

    private static String fileName(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        if (name.isEmpty() || name.contains("..") || !name.matches("[A-Za-z0-9._-]{1,180}")) {
            return null;
        }
        return name;
    }

    private static long readSequence(File seq) {
        if (!seq.isFile()) {
            return -1L;
        }
        try {
            byte[] raw;
            try (java.io.FileInputStream in = new java.io.FileInputStream(seq)) {
                raw = in.readAllBytes();
            }
            String text = new String(raw, StandardCharsets.US_ASCII).trim();
            return text.isEmpty() ? -1L : Long.parseUnsignedLong(text);
        } catch (IOException | NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String safeUuid(String uuid) {
        if (!uuid.matches("[0-9A-Fa-f-]{36}")) {
            throw new IllegalArgumentException("Resource transfer UUID is invalid");
        }
        return uuid;
    }
}
