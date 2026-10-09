package dev.applewatchandroid.bridge;

import java.nio.file.Path;
import java.util.Objects;

/** Platform ownership/label handling after a portable face export. */
final class NativeFacePackageAccess {
    @FunctionalInterface
    interface Preparer {
        void prepare(Path files, String pair) throws Exception;
    }

    // Desktop exports already belong to the unprivileged session owner.
    private static volatile Preparer preparer = (files, pair) -> { };

    private NativeFacePackageAccess() { }

    static void installPlatformPreparer(Preparer platform) {
        preparer = Objects.requireNonNull(platform);
    }

    static void prepare(Path files, String pair) throws Exception {
        NtkFacePayloadCodec.uuid(pair);
        preparer.prepare(files, pair);
    }
}
