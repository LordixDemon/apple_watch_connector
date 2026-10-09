package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Installation identity, separate from the UUIDs of individual pairing runs. */
final class IdsDeviceIdentifier {
    private static final String PREFIX = "IDS_DEVICE_ID_V1\n";
    private IdsDeviceIdentifier() { }

    static String canonical(String value) {
        if (value == null) throw new IllegalArgumentException("IDS device identifier is absent");
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equalsIgnoreCase(value) || uuid.equals(new UUID(0, 0))) {
            throw new IllegalArgumentException("Invalid IDS device identifier");
        }
        return uuid.toString();
    }

    static String choose(String persisted, String existingPairing) {
        if (persisted != null) return canonical(persisted);
        if (existingPairing != null) return canonical(existingPairing);
        return UUID.randomUUID().toString();
    }

    static byte[] encode(String value) {
        return (PREFIX + canonical(value)).getBytes(StandardCharsets.US_ASCII);
    }

    static String decode(byte[] bytes) {
        if (bytes == null || bytes.length != PREFIX.length() + 36) {
            throw new IllegalArgumentException("Invalid IDS device identifier record");
        }
        String value = new String(bytes, StandardCharsets.US_ASCII);
        if (!value.startsWith(PREFIX)) throw new IllegalArgumentException("Unsupported IDS device identifier record");
        return canonical(value.substring(PREFIX.length()));
    }
}
