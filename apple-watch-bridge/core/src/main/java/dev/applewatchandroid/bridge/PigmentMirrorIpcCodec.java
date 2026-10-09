package dev.applewatchandroid.bridge;

import java.io.*;

/** Owned HAL local mirror publication, never a synthetic NPS/Watch observation. */
final class PigmentMirrorIpcCodec {
    static final String PREFIX = "BRIDGE_PIGMENT_MIRROR_V2:";
    static final int MAX_FRAME = 256 * 1024;
    private static final int LEGACY_MAGIC = 0x50474931, MAGIC = 0x50474932;
    private PigmentMirrorIpcCodec() { }
    static byte[] encode(PigmentPreferenceMirror.Snapshot snapshot) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); PigmentPreferenceMirror.uuid(out, snapshot.pair());
            PigmentPreferenceMirror.uuid(out, snapshot.epoch());
            PigmentPreferenceMirror.writeEntry(out, snapshot.value());
            out.writeBoolean(snapshot.automatic() != null);
            if (snapshot.automatic() != null) PigmentPreferenceMirror.writeEntry(out, snapshot.automatic());
        }
        if (bytes.size() > MAX_FRAME) throw new IOException("Pigment mirror bound exceeded");
        return bytes.toByteArray();
    }
    static PigmentPreferenceMirror.Snapshot decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_FRAME) throw new IOException("Invalid pigment mirror size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            if (magic != MAGIC && magic != LEGACY_MAGIC) throw new IOException("Invalid pigment mirror version");
            var result = new PigmentPreferenceMirror.Snapshot(PigmentPreferenceMirror.uuid(in),
                    PigmentPreferenceMirror.uuid(in), PigmentPreferenceMirror.readEntry(in),
                    magic == MAGIC && PigmentPreferenceMirror.present(in) ? PigmentPreferenceMirror.readEntry(in) : null);
            if (in.available() != 0) throw new IOException("Trailing pigment mirror data");
            return result;
        } catch (IllegalArgumentException malformed) { throw new IOException("Malformed pigment mirror", malformed); }
    }
}
