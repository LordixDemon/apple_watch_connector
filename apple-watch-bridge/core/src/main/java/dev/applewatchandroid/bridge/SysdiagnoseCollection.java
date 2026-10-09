package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Watch7,5 23S303 SDStart sets requestSource=4, idsCase=5 and creates a tarball.
 * Only an explicit caller can start collection. No stop/reset/erase requests. */
final class SysdiagnoseCollection {
    static final String COMMAND = "COLLECT_WATCH_DIAGNOSTIC";
    static final int START = 1;
    static final int FETCH_ARCHIVE = 8;
    static final int COMPANION_TIMEOUT = 3;
    private boolean requested;

    static void requireDiagnosticRequest(int type, byte[] payload) {
        // SDTransfer_Remote only enumerates diagnostic cache entries and selects
        // one whose path contains this diagnostic ID. Accept an archive basename
        // observed on the wire, never an arbitrary path or collection flags.
        if (type == FETCH_ARCHIVE && payload != null && payload.length <= 200) {
            String selector = new String(payload, java.nio.charset.StandardCharsets.US_ASCII);
            if (selector.matches("sysdiagnose_[A-Za-z0-9._+\\-]{1,180}\\.tar\\.gz")
                    && !selector.contains("..")) return;
        }
        // SDStart/SDWatch_List use bounded UUID correlation strings.
        if (type == COMPANION_TIMEOUT && java.util.Arrays.equals(payload,
                "CompanionTimedout".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return;
        if (type != START && type != FETCH_ARCHIVE && type != SysdiagnoseArchiveInventory.LIST_REQUEST) {
            throw new IllegalArgumentException("Unsupported sysdiagnose diagnostic request");
        }
        SysdiagnoseArchiveInventory.requireReadOnlyRequest(SysdiagnoseArchiveInventory.LIST_REQUEST, payload);
    }

    void queued(int type, byte[] payload) {
        requireDiagnosticRequest(type, payload);
        if (type == START || type == FETCH_ARCHIVE) requested = true;
    }

    Path receive(int type, byte[] payload, Path parent) throws IOException {
        if (!requested || (type < 3 || type > 5) && type != 9) return null;
        if (payload == null || payload.length > SysdiagnoseArchiveInventory.MAX_BYTES) {
            throw new IllegalArgumentException("Sysdiagnose status exceeds limit");
        }
        Path dir = Files.createTempDirectory(parent, "apple-watch-bridge-sysdiagnose-status-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path file = Files.createFile(dir.resolve("type-" + type + ".bin"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, payload);
        return file;
    }

    void reset() { requested = false; }
}
