package dev.applewatchandroid.bridge;

/**
 * Locates a helper discovery observation in a stdout line. The helper emits it
 * through ProtocolHost.log, which prefixes "[WatchHal] [T+...ms|Δ...ms] ", so
 * the marker is not at the start of the line.
 */
final class WatchDiscoveryEvent {
    static final String PREFIX = "WATCH_DISCOVERED_V1:";

    private WatchDiscoveryEvent() {}

    /** Returns the line from the marker onward, or null when the line is not a discovery. */
    static String find(String line) {
        if (line == null) return null;
        int at = line.indexOf(PREFIX);
        return at < 0 ? null : line.substring(at);
    }
}
