package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The six-field PBBridge optical payload, with secret bytes kept mutable.
 * Only the verified NetworkRelay format (4, 32-byte key) is supported.
 * Parsing is not authentication; match a fresh advertisement before use.
 */
final class OpticalPairingCode implements AutoCloseable {
    final int compatibilityVersion;
    final String advertisedName;
    final int material, size;
    final String systemVersion;
    private byte[] key;

    private OpticalPairingCode(int compatibilityVersion, String advertisedName,
            int material, int size, String systemVersion, byte[] key) {
        this.compatibilityVersion = compatibilityVersion;
        this.advertisedName = advertisedName;
        this.material = material; this.size = size;
        this.systemVersion = systemVersion; this.key = key;
    }

    static OpticalPairingCode parse(byte[] payload) {
        if (payload == null || payload.length != 110) throw invalid();
        int end = 0;
        while (end < payload.length && payload[end] != 0) {
            if (payload[end] < 33 || payload[end] > 126) throw invalid();
            end++;
        }
        if (end == 0) throw invalid();
        int[] positions = new int[12];
        positions[0] = 0;
        int field = 0;
        for (int i = 0; i + 1 < end; i++) {
            if (payload[i] == '-' && payload[i + 1] == '-') {
                if (field >= 5) throw invalid();
                positions[field * 2 + 1] = i;
                positions[++field * 2] = i + 2;
                i++;
            }
        }
        if (field != 5) throw invalid();
        positions[11] = end;
        for (int i = 0; i < 6; i++) if (positions[i * 2] == positions[i * 2 + 1]) throw invalid();
        if (integer(payload, positions[0], positions[1], 4) != 4) throw invalid();
        int compatibility = integer(payload, positions[2], positions[3], 63);
        if (compatibility == 0 || positions[5] - positions[4] != 8
                || positions[7] - positions[6] != 64) throw invalid();
        String name = new String(payload, positions[4], 8, StandardCharsets.US_ASCII);
        if (!name.matches("[0-9]{5}E[A-Za-z0-9]{2}")) throw invalid();
        int material = integer(payload, positions[8], positions[9], 61);
        int sizeEnd = positions[11];
        String version = "";
        for (int i = positions[10]; i + 1 < end; i++) {
            if (payload[i] == '&' && payload[i + 1] == '&') {
                sizeEnd = i;
                version = new String(payload, i + 2, end - i - 2, StandardCharsets.US_ASCII);
                if (!version.matches("[0-9]{1,2}\\.[0-9]{1,2}(\\.[0-9]{1,2})?")) throw invalid();
                break;
            }
        }
        int size = integer(payload, positions[10], sizeEnd, 61);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890";
        if (name.charAt(6) != alphabet.charAt(material) || name.charAt(7) != alphabet.charAt(size)) throw invalid();
        byte[] key = new byte[32];
        try {
            for (int i = 0; i < key.length; i++) {
                int upper = Character.digit(payload[positions[6] + i * 2], 16);
                int lower = Character.digit(payload[positions[6] + i * 2 + 1], 16);
                if (upper < 0 || lower < 0) throw invalid();
                key[i] = (byte) ((upper << 4) | lower);
            }
            OpticalPairingCode result = new OpticalPairingCode(compatibility, name, material, size, version, key);
            key = null;
            return result;
        } finally { if (key != null) Arrays.fill(key, (byte) 0); }
    }

    boolean matches(WatchSetupMetadataCodec.Identifier identifier,
            WatchSetupMetadataCodec.ExtendedMetadata metadata) {
        return key != null && identifier != null && metadata != null
                && identifier.pairingStrategy == WatchSetupMetadataCodec.NETWORK_RELAY_PAIRING_STRATEGY
                && identifier.enclosureMaterial == material && identifier.deviceSize == size
                && identifier.advertisingIdentifier <= 99999
                && advertisedName.equals(identifier.humanReadablePayload())
                && metadata.pairingVersion == compatibilityVersion
                && versionMatches(metadata);
    }

    private boolean versionMatches(WatchSetupMetadataCodec.ExtendedMetadata metadata) {
        if (systemVersion.isEmpty()) return true;
        String[] parts = systemVersion.split("\\.");
        return Integer.parseInt(parts[0]) == metadata.systemVersionMajor()
                && Integer.parseInt(parts[1]) == metadata.systemVersionMinor()
                && (parts.length < 3 || Integer.parseInt(parts[2]) == metadata.systemVersionPatch());
    }

    /** The caller owns and must erase the returned PSK input. */
    byte[] authenticationData() {
        if (key == null) throw new IllegalStateException("Optical code is closed");
        return key.clone();
    }

    byte[] boundSharedSecret(byte[] setupData) {
        if (setupData == null || setupData.length != 12 || (setupData[0] & 0xe0) != 0x20) {
            throw new IllegalArgumentException("Optical pairing advertisement does not match");
        }
        WatchSetupMetadataCodec.Identifier identifier = WatchSetupMetadataCodec.decodeIdentifier(
                Arrays.copyOfRange(setupData, 1, 5));
        WatchSetupMetadataCodec.ExtendedMetadata metadata = WatchSetupMetadataCodec.decodeExtendedMetadata(
                Arrays.copyOfRange(setupData, 5, 12));
        if (!matches(identifier, metadata)) {
            throw new IllegalArgumentException("Optical pairing advertisement does not match");
        }
        byte[] result = Arrays.copyOf(key, 44);
        System.arraycopy(setupData, 0, result, 32, 12);
        return result;
    }

    private static int integer(byte[] input, int start, int end, int maximum) {
        if (start == end || end - start > 3) throw invalid();
        int value = 0;
        for (int i = start; i < end; i++) {
            if (input[i] < '0' || input[i] > '9') throw invalid();
            value = value * 10 + input[i] - '0';
        }
        if (value > maximum) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or unsupported optical pairing payload");
    }
    @Override public void close() {
        if (key != null) { Arrays.fill(key, (byte) 0); key = null; }
    }
}
