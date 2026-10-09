package dev.applewatchandroid.bridge;

import java.io.*;

/** HAL mirror publication is separate from genuine NPS Watch observations. */
final class MonogramMirrorIpcCodec {
    static final String PREFIX = "BRIDGE_MONOGRAM_MIRROR_V1:";
    static final int MAX_FRAME = 256;
    private static final int MAGIC = 0x4d474931;
    static byte[] encode(MonogramPreferenceMirror.Snapshot snapshot) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); MonogramPreferenceMirror.uuid(out, snapshot.pair()); MonogramPreferenceMirror.uuid(out, snapshot.epoch());
            MonogramPreferenceMirror.writeEntry(out, snapshot.value());
        }
        if (bytes.size() > MAX_FRAME) throw new IOException("Monogram IPC exceeds limit");
        return bytes.toByteArray();
    }
    static MonogramPreferenceMirror.Snapshot decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_FRAME) throw new IOException("Invalid monogram IPC size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid monogram IPC version");
            var result = new MonogramPreferenceMirror.Snapshot(MonogramPreferenceMirror.uuid(in), MonogramPreferenceMirror.uuid(in), MonogramPreferenceMirror.readEntry(in));
            if (in.available() != 0) throw new IOException("Trailing monogram IPC data");
            return result;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid monogram IPC value", invalid); }
    }
}
