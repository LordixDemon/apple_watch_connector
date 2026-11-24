package dev.applewatchandroid.bridge;

/**
 * Locates a helper discovery observation in a stdout line. The helper emits it
 * through ProtocolHost.log, which prefixes "[WatchHal] [T+...ms|Δ...ms] ", so
 * the marker is not at the start of the line.
 */
final class WatchDiscoveryEvent {
    static final String PREFIX = "WATCH_DISCOVERED_V1:";

    private WatchDiscoveryEvent() {}

    /**
     * Returns the line from the marker onward when it appears at a token boundary
     * with the expected 5-field format, or null otherwise.
     */
    static String find(String line) {
        if (line == null) return null;
        int from = 0;
        int at;
        while ((at = line.indexOf(PREFIX, from)) >= 0) {
            if (at == 0 || Character.isWhitespace(line.charAt(at - 1))) {
                String candidate = line.substring(at);
                if (candidate.split(":", -1).length == 5) {
                    return candidate;
                }
            }
            from = at + PREFIX.length();
        }
        return null;
    }
}
