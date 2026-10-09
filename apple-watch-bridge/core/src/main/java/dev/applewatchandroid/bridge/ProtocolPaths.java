package dev.applewatchandroid.bridge;

import java.nio.file.Path;

/** Portable state root. Android retains its app directory; desktop supplies its own. */
final class ProtocolPaths {
    private ProtocolPaths() { }
    static Path files() {
        return Path.of(System.getProperty("watch.files", "/data/data/dev.applewatchandroid.bridge/files"));
    }
}
