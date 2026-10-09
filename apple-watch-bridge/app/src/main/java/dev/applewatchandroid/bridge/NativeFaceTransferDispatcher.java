package dev.applewatchandroid.bridge;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Chunked package I/O stays separate from face mutations and never places a large package in Binder. */
final class NativeFaceTransferDispatcher implements AutoCloseable {
    private static final int CHUNK = 64 * 1024;
    private static final long TTL = 30 * 60 * 1000L;
    private final Path root, receiver;
    private final Map<String, Upload> uploads = new HashMap<>();
    private final Map<String, Export> exports = new LinkedHashMap<>();
    private record Upload(String pair, String epoch, String face, String baselineHash, int total, long created, Path path, RandomAccessFile file) { }
    private record Export(byte[] bytes, String hash, long created) { }
    NativeFaceTransferDispatcher(Context context) {
        root = context.getFilesDir().toPath().resolve("face-imports");
        receiver = context.getFilesDir().toPath().resolve("clockface-receiver");
    }
    Bundle handle(String method, Bundle data, String requestId) {
        if (!Set.of("beginNativeFaceImport", "appendNativeFaceImport", "finishNativeFaceImport", "cancelNativeFaceImport", "exportNativeFace").contains(method)) return null;
        try {
            expire();
            var current = BridgeIpcDispatcher.getInstance().clockFaces();
            if ("cancelNativeFaceImport".equals(method)) {
                discard(uuid(data, "uploadId")); return reply(requestId, "CANCELLED");
            }
            if (current == null || !current.complete() || !BridgeIpcDispatcher.getInstance().isConnected()
                    || !current.pair().toString().equals(uuid(data, "pairId")) || !current.epoch().toString().equals(uuid(data, "epoch")))
                throw new IllegalArgumentException("Watch connection changed");
            if ("exportNativeFace".equals(method)) return export(current, data, requestId);
            if ("beginNativeFaceImport".equals(method)) {
                int total = data.getInt("total", 0);
                if (uploads.size() >= 2 || total < 4 || total > NativeFacePackageStore.MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Native package transfer limit");
                String face = data.containsKey("faceId") ? uuid(data, "faceId") : null;
                if (face != null && !current.ordered().contains(face)) throw new IllegalArgumentException("Unknown native face");
                String baselineHash = data.getString("baselineHash");
                if (face != null && (baselineHash == null || !baselineHash.matches("[0-9a-f]{64}")))
                    throw new IllegalArgumentException("Missing original native package hash");
                Files.createDirectories(root);
                Path path = root.resolve(requestId + ".partial");
                Files.createFile(path);
                var upload = new Upload(current.pair().toString(), current.epoch().toString(), face, baselineHash, total, SystemClock.elapsedRealtime(), path, new RandomAccessFile(path.toFile(), "rw"));
                uploads.put(requestId, upload);
                Bundle reply = reply(requestId, "UPLOADING"); reply.putString("uploadId", requestId); reply.putInt("offset", 0); return reply;
            }
            String token = uuid(data, "uploadId"); Upload upload = uploads.get(token);
            if (upload == null || !upload.pair().equals(current.pair().toString()) || !upload.epoch().equals(current.epoch().toString()))
                throw new IllegalArgumentException("Expired native package upload");
            if ("appendNativeFaceImport".equals(method)) {
                byte[] bytes = data.getByteArray("chunk"); int offset = data.getInt("offset", -1);
                if (bytes == null || bytes.length < 1 || bytes.length > CHUNK || offset != upload.file().length()
                        || offset > upload.total() - bytes.length) throw new IllegalArgumentException("Invalid native package chunk");
                upload.file().seek(offset); upload.file().write(bytes);
                Bundle reply = reply(requestId, "UPLOADING"); reply.putInt("offset", offset + bytes.length); return reply;
            }
            if (upload.file().length() != upload.total()) throw new IllegalArgumentException("Incomplete native package upload");
            upload.file().getFD().sync(); upload.file().close();
            byte[] bytes = Files.readAllBytes(upload.path());
            String hash;
            try { byte[] config = NtkFacePayloadCodec.configurationFromZip(bytes); Arrays.fill(config, (byte) 0); hash = NativeFacePackageStore.hash(bytes); }
            finally { Arrays.fill(bytes, (byte) 0); }
            Path ready = root.resolve(token + ".watchface");
            Files.move(upload.path(), ready, StandardCopyOption.ATOMIC_MOVE);
            if (!ready.toFile().setReadOnly()) throw new IOException("Could not seal native package upload");
            uploads.remove(token);
            String face = upload.face() == null ? requestId : upload.face();
            String command = (upload.face() == null ? ClockFaceDeltaCommand.ADD_FILE : ClockFaceDeltaCommand.RESOURCES_FILE)
                    + face + ":" + token + ":" + hash + (upload.face() == null ? "" : ":" + upload.baselineHash());
            boolean queued = OperationalWatchService.sendCommand(command, UUID.fromString(requestId), current.epoch());
            if (!queued) Files.deleteIfExists(ready);
            Bundle reply = reply(requestId, queued ? "QUEUED" : "UNAVAILABLE"); reply.putString("faceId", face); return reply;
        } catch (IOException invalid) { throw new IllegalArgumentException("Native package file is unavailable", invalid); }
    }
    private Bundle export(ClockFaceObservationCodec.Observation current, Bundle data, String requestId) throws IOException {
        String id = uuid(data, "faceId");
        var face = current.faces().stream().filter(value -> value.id().equals(id) && current.ordered().contains(id)).findFirst().orElseThrow();
        String key = current.pair() + ":" + current.epoch() + ":" + id;
        int offset = data.getInt("offset", 0);
        if (offset == 0) {
            Export previous = exports.remove(key); if (previous != null) Arrays.fill(previous.bytes(), (byte) 0);
            if (exports.size() >= 2) { String oldest = exports.keySet().iterator().next(); Arrays.fill(exports.remove(oldest).bytes(), (byte) 0); }
            byte[] bytes = face.packageDigest() != null ? new NativeFacePackageStore(receiver.resolve(current.pair().toString()).resolve("packages")).read(face.packageDigest())
                    : face.archiveBase64() != null ? java.util.Base64.getDecoder().decode(face.archiveBase64()) : null;
            if (bytes == null) return reply(requestId, "UNAVAILABLE");
            exports.put(key, new Export(bytes, NativeFacePackageStore.hash(bytes), SystemClock.elapsedRealtime()));
        }
        Export export = exports.get(key);
        if (export == null || offset < 0 || offset >= export.bytes().length || offset > 0 && !export.hash().equals(data.getString("sha256")))
            throw new IllegalArgumentException("Invalid or expired native package export");
        String observedHash = face.packageDigest() != null ? face.packageDigest()
                : face.archiveBase64() != null ? NativeFacePackageStore.hash(java.util.Base64.getDecoder().decode(face.archiveBase64())) : null;
        if (!export.hash().equals(observedHash)) throw new IllegalArgumentException("Native package changed during export");
        byte[] bytes = export.bytes();
        Bundle result = reply(requestId, "EXPORTED"); result.putInt("total", bytes.length); result.putInt("offset", offset);
        result.putString("sha256", export.hash());
        int end = Math.min(bytes.length, offset + CHUNK);
        result.putByteArray("archive", Arrays.copyOfRange(bytes, offset, end));
        if (end == bytes.length) { exports.remove(key); Arrays.fill(bytes, (byte) 0); }
        return result;
    }
    private void expire() throws IOException {
        long now = SystemClock.elapsedRealtime();
        for (String key : List.copyOf(exports.keySet())) if (now - exports.get(key).created() >= TTL)
            Arrays.fill(exports.remove(key).bytes(), (byte) 0);
        for (String id : List.copyOf(uploads.keySet())) if (now - uploads.get(id).created() >= TTL) discard(id);
        if (Files.isDirectory(root)) try (var files = Files.list(root)) {
            for (Path path : files.collect(java.util.stream.Collectors.toList())) {
                if (path.getFileName().toString().matches("[0-9a-f-]{36}\\.(watchface|partial)")
                        && System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() > TTL) Files.deleteIfExists(path);
            }
        }
    }
    private void discard(String id) throws IOException {
        Upload upload = uploads.remove(id);
        if (upload != null) { upload.file().close(); Files.deleteIfExists(upload.path()); }
    }
    private static String uuid(Bundle data, String key) {
        String value = data.getString(key);
        if (!NtkFacePayloadCodec.uuid(value).equals(value)) throw new IllegalArgumentException("Invalid native transfer identifier");
        return value;
    }
    private static Bundle reply(String id, String status) {
        Bundle value = new Bundle();
        value.putInt("version", 1);
        value.putString("requestId", id);
        value.putString("status", status);
        return value;
    }
    @Override public void close() {
        for (String id : List.copyOf(uploads.keySet())) try { discard(id); } catch (IOException ignored) { }
        for (Export value : exports.values()) Arrays.fill(value.bytes(), (byte) 0); exports.clear();
    }
}
