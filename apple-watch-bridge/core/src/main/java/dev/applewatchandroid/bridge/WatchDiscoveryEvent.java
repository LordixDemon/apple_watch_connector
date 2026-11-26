package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.spi.DiscoveryEventCodec;

/**
 * Locates a helper discovery observation in a stdout line. The helper emits it
 * through ProtocolHost.log, which prefixes "[WatchHal] [T+...ms|Δ...ms] ", so
 * the marker is not at the start of the line.
 */
final class WatchDiscoveryEvent {
    static final String PREFIX = DiscoveryEventCodec.V1_PREFIX;
    static final String V2_PREFIX = DiscoveryEventCodec.V2_PREFIX;

    private WatchDiscoveryEvent() {}

    /**
     * Returns the line from the V1 marker onward when it appears at a token boundary
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

    /**
     * Returns the line from the V2 JSON marker onward when it appears at a token
     * boundary within bounds, or null otherwise.
     */
    static String findV2(String line) {
        return DiscoveryEventCodec.findV2(line);
    }
}
