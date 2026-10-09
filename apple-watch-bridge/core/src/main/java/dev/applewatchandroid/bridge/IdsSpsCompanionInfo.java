package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Authenticated peer SPS metadata, independent of account login or activation. */
final class IdsSpsCompanionInfo implements AutoCloseable {
    static final String PHONE_NUMBERS = "sps-phone-numbers";
    static final String DEVICE_UDID = "sps-device-udid";
    static final int MAX_SERIALIZED_LENGTH = 4096;
    private final byte[] serialized;
    private final int numberCount;
    private boolean closed;

    private IdsSpsCompanionInfo(byte[] serialized, int numberCount) {
        this.serialized = serialized;
        this.numberCount = numberCount;
    }

    static IdsSpsCompanionInfo fromMessage(Map<?, ?> message) {
        if (!Long.valueOf(5).equals(message.get("command"))
                || !(message.get(DEVICE_UDID) instanceof String udid)
                || !udid.matches("[A-Za-z0-9-]{1,128}")
                || !(message.get(PHONE_NUMBERS) instanceof List<?> numbers)
                || numbers.size() > 16) {
            throw new IllegalArgumentException("Invalid SPS companion metadata");
        }
        TreeSet<String> canonicalNumbers = new TreeSet<>();
        for (Object number : numbers) {
            // Native produces a set from IDSUser.phoneNumber; receivers convert
            // these strings to IDSURI. Accept bare numbers and prefixed URIs.
            if (!(number instanceof String value) || !value.matches("(?:tel:)?\\+?[0-9]{1,31}")) {
                throw new IllegalArgumentException("Invalid SPS phone number representation");
            }
            canonicalNumbers.add(value);
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("command", 5L);
        stored.put(DEVICE_UDID, udid);
        stored.put(PHONE_NUMBERS, List.copyOf(canonicalNumbers));
        byte[] encoded = AppleBinaryPropertyList.encode(stored);
        if (encoded.length > MAX_SERIALIZED_LENGTH) {
            Arrays.fill(encoded, (byte)0);
            throw new IllegalArgumentException("SPS companion metadata exceeds its storage limit");
        }
        return new IdsSpsCompanionInfo(encoded, canonicalNumbers.size());
    }

    static IdsSpsCompanionInfo parse(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_SERIALIZED_LENGTH) {
            throw new IllegalArgumentException("Invalid SPS companion metadata size");
        }
        Object decoded = AppleBinaryPropertyList.decode(encoded);
        try {
            if (!(decoded instanceof Map<?, ?> map) || map.size() != 3) {
                throw new IllegalArgumentException("Invalid stored SPS companion metadata");
            }
            return fromMessage(map);
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }

    byte[] serialize() {
        if (closed) throw new IllegalStateException("SPS metadata is closed");
        return serialized.clone();
    }

    String summary() { return "SPS companion metadata: deviceUdidPresent=true phoneNumberCount=" + numberCount; }
    @Override public void close() { Arrays.fill(serialized, (byte)0); closed = true; }
}
