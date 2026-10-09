package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Strict decoder for the full-UUID set shape used by NanoRegistry.
 *
 * <p>The wire representation is a {@code PropertyValue} collection with an
 * explicit {@code isSet=true}. Every child must contain exactly one 16-byte
 * UUID. Mini UUID sets and mixed/scalar collection members are deliberately
 * rejected.</p>
 */
final class NanoRegistryUuidSetCodec {
    private NanoRegistryUuidSetCodec() {
    }

    static NanoRegistryPropertyCodec.PropertyValue encode(
            String[] uuids) {
        if (uuids == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry UUID set is absent");
        }
        return encode(
                Arrays.asList(
                        uuids));
    }

    static NanoRegistryPropertyCodec.PropertyValue encode(
            Collection<String> uuids) {
        if (uuids == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry UUID set is absent");
        }
        List<NanoRegistryPropertyCodec.PropertyValue> values =
                new ArrayList<>();
        Set<String> seen =
                new LinkedHashSet<>();
        for (String uuid : uuids) {
            if (uuid == null
                    || uuid.isBlank()) {
                throw new IllegalArgumentException(
                        "NanoRegistry UUID set contains an empty UUID");
            }
            String canonical;
            try {
                canonical =
                        UUID.fromString(
                                uuid).toString();
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "NanoRegistry UUID set contains a non-UUID value",
                        invalid);
            }
            if (!seen.add(
                    canonical)) {
                throw new IllegalArgumentException(
                        "NanoRegistry UUID set contains a duplicate UUID");
            }
            values.add(
                    NanoRegistryPropertyCodec.PropertyValue.uuid(
                            uuidBytes(
                                    canonical)));
        }
        return NanoRegistryPropertyCodec.PropertyValue.set(
                values);
    }

    static Set<String> decode(
            NanoRegistryPropertyCodec.PropertyValue value) {
        requireSetOnly(
                value);
        Set<String> decoded =
                new LinkedHashSet<>();
        if (value.arrayValues != null) {
            for (NanoRegistryPropertyCodec.PropertyValue element :
                    value.arrayValues) {
                String uuid = extractUuid(element);
                if (uuid == null) {
                    throw new IllegalArgumentException(
                            "NanoRegistry UUID set contains a non-UUID value");
                }
                if (!decoded.add(uuid)) {
                    throw new IllegalArgumentException(
                            "NanoRegistry UUID set contains a duplicate UUID");
                }
            }
        }
        return Collections.unmodifiableSet(
                decoded);
    }

    private static String extractUuid(
            NanoRegistryPropertyCodec.PropertyValue value) {
        if (value == null
                || value.numberValue != null
                || value.sizeValue != null
                || value.dictionaryKey != null
                || value.arrayValues != null
                || value.hasIsError
                || value.hasIsMiniUuidSet) {
            return null;
        }
        if (value.uuidValue != null && value.uuidValue.length == 16) {
            return uuidString(value.uuidValue);
        }
        if (value.dataValue != null && value.dataValue.length == 16) {
            return uuidString(value.dataValue);
        }
        if (value.stringValue != null && !value.stringValue.isBlank()) {
            try {
                return UUID.fromString(value.stringValue.trim()).toString();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private static byte[] uuidBytes(
            String uuid) {
        UUID parsed =
                UUID.fromString(
                        uuid);
        ByteBuffer bytes =
                ByteBuffer.allocate(
                        16);
        bytes.putLong(
                parsed.getMostSignificantBits());
        bytes.putLong(
                parsed.getLeastSignificantBits());
        return bytes.array();
    }

    private static String uuidString(
            byte[] value) {
        ByteBuffer bytes =
                ByteBuffer.wrap(
                        value);
        return new UUID(
                bytes.getLong(),
                bytes.getLong()).toString();
    }

    private static void requireSetOnly(
            NanoRegistryPropertyCodec.PropertyValue value) {
        if (value == null
                || value.stringValue != null
                || value.numberValue != null
                || value.uuidValue != null
                || value.dataValue != null
                || value.sizeValue != null
                || value.dictionaryKey != null
                || !value.hasIsSet
                || !value.isSet
                || value.hasIsDate
                || value.hasIsError
                || value.hasIsMiniUuidSet) {
            throw new IllegalArgumentException(
                    "NanoRegistry value is not a canonical UUID set");
        }
    }

    private static void requireUuidOnly(
            NanoRegistryPropertyCodec.PropertyValue value) {
        if (value == null
                || value.stringValue != null
                || value.numberValue != null
                || value.uuidValue == null
                || value.uuidValue.length != 16
                || value.dataValue != null
                || value.sizeValue != null
                || value.dictionaryKey != null
                || value.arrayValues != null
                || value.hasIsSet
                || value.hasIsDate
                || value.hasIsError
                || value.hasIsMiniUuidSet) {
            throw new IllegalArgumentException(
                    "NanoRegistry UUID set contains a non-UUID value");
        }
    }
}
