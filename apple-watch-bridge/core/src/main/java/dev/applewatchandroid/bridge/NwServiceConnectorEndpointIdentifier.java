package dev.applewatchandroid.bridge;

/** Exact textual endpoint keys used by Network.framework service connectors. */
final class NwServiceConnectorEndpointIdentifier {
    private NwServiceConnectorEndpointIdentifier() {
    }

    /**
     * Darwin formats an IPv6 endpoint as inet_ntop(address), a dot, then the
     * decimal port. It does not add URL brackets or a scope for these UTun
     * addresses.
     */
    static String ipv6(
            byte[] address,
            int port) {
        if (address == null
                || address.length != 16) {
            throw new IllegalArgumentException(
                    "NWSC IPv6 address must contain 16 bytes");
        }
        requirePort(
                port);

        int[] words =
                new int[8];
        for (int index = 0;
                index < words.length;
                index++) {
            words[index] =
                    ((address[index * 2] & 0xff) << 8)
                            | (address[index * 2 + 1] & 0xff);
        }

        int bestStart = -1;
        int bestLength = 0;
        for (int index = 0;
                index < words.length;) {
            if (words[index] != 0) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < words.length
                    && words[end] == 0) {
                end++;
            }
            int length = end - index;
            if (length >= 2
                    && length > bestLength) {
                bestStart = index;
                bestLength = length;
            }
            index = end;
        }

        StringBuilder output =
                new StringBuilder(
                        48);
        for (int index = 0;
                index < words.length;) {
            if (index == bestStart) {
                output.append(
                        "::");
                index += bestLength;
                continue;
            }
            if (index > 0
                    && index != bestStart + bestLength) {
                output.append(
                        ':');
            }
            output.append(
                    Integer.toHexString(
                            words[index]));
            index++;
        }
        return output
                .append(
                        '.')
                .append(
                        port)
                .toString();
    }

    static String ipv4(
            byte[] address,
            int port) {
        if (address == null
                || address.length != 4) {
            throw new IllegalArgumentException(
                    "NWSC IPv4 address must contain four bytes");
        }
        requirePort(
                port);
        return (address[0] & 0xff)
                + "."
                + (address[1] & 0xff)
                + "."
                + (address[2] & 0xff)
                + "."
                + (address[3] & 0xff)
                + ":"
                + port;
    }

    private static void requirePort(
            int port) {
        if (port < 1
                || port > 0xffff) {
            throw new IllegalArgumentException(
                    "NWSC endpoint port must be nonzero uint16");
        }
    }
}
