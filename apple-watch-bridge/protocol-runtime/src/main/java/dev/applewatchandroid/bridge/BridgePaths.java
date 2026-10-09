package dev.applewatchandroid.bridge;

import java.nio.file.Path;

/** Platform file locations supplied at process launch, never derived from peer input. */
final class BridgePaths {
    private BridgePaths() { }
    static Path files() {
        return ProtocolPaths.files();
    }
    static Path temporary() {
        return Path.of(System.getProperty("watch.tmp", "/data/local/tmp"));
    }
}
