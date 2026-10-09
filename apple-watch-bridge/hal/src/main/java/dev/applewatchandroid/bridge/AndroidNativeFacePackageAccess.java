package dev.applewatchandroid.bridge;

import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** HAL-created exports belong to the Bridge app, including its SELinux data labels. */
final class AndroidNativeFacePackageAccess {
    private AndroidNativeFacePackageAccess() { }
    static void prepare(Path appFiles, String pair) throws Exception {
        NtkFacePayloadCodec.uuid(pair);
        if (Os.getuid() != 0) return;
        StructStat owner = Os.stat(appFiles.toString());
        if (owner.st_uid < 10000) throw new IOException("Bridge app owner unavailable");
        Path receiver = appFiles.resolve("clockface-receiver");
        Path paired = receiver.resolve(pair);
        Path packages = paired.resolve("packages");
        for (Path directory : new Path[]{receiver, paired, packages}) {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid export directory");
            Os.chown(directory.toString(), owner.st_uid, owner.st_gid);
            Os.chmod(directory.toString(), 0700);
        }
        try (var files = Files.list(packages)) {
            for (Path path : files.collect(java.util.stream.Collectors.toList())) {
                if (!path.getFileName().toString().matches("[0-9a-f]{64}\\.watchface")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                Os.chown(path.toString(), owner.st_uid, owner.st_gid);
                Os.chmod(path.toString(), 0600);
            }
        }
        Process restore = new ProcessBuilder("/system/bin/restorecon", "-DRF", receiver.toString())
                .redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
        if (!restore.waitFor(10, TimeUnit.SECONDS)) {
            restore.destroy(); throw new IOException("Export label restoration timed out");
        }
        if (restore.exitValue() != 0) throw new IOException("Export label restoration failed");
    }
}
