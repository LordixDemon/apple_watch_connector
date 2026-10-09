package dev.applewatchandroid.bridge;

import java.util.Arrays;

/**
 * Apple wire representation of Bluetooth LE Secure Connections OOB data.
 *
 * <p>The 34-byte layout was recovered from iOS 26.6 {@code bluetoothd}.
 * This object deliberately has no {@code toString()} implementation so that
 * transient pairing material cannot be formatted accidentally.</p>
 */
final class AppleLeScOobData {
    static final int VALUE_LENGTH = 16;
    static final int SERIALIZED_LENGTH = 34;
    static final byte RANDOMIZER_TAG = 0x72; // 'r'
    static final byte CONFIRMATION_TAG = 0x63; // 'c'

    private final byte[] randomizer;
    private final byte[] confirmation;
    private boolean destroyed;

    private AppleLeScOobData(
            byte[] randomizer,
            byte[] confirmation) {
        this.randomizer = randomizer.clone();
        this.confirmation = confirmation.clone();
    }

    static AppleLeScOobData of(
            byte[] randomizer,
            byte[] confirmation) {
        requireValue("LE SC OOB randomizer", randomizer);
        requireValue("LE SC OOB confirmation", confirmation);
        return new AppleLeScOobData(randomizer, confirmation);
    }

    /**
     * Accepts either record order, as Apple's parser does, but requires one
     * randomizer and one confirmation record instead of silently accepting a
     * duplicate tag.
     */
    static AppleLeScOobData parse(byte[] serialized) {
        if (serialized == null
                || serialized.length != SERIALIZED_LENGTH) {
            throw new IllegalArgumentException(
                    "Apple LE SC OOB data must be exactly "
                            + SERIALIZED_LENGTH
                            + " bytes");
        }
        byte[] randomizer = null;
        byte[] confirmation = null;
        try {
            for (int offset = 0;
                    offset < SERIALIZED_LENGTH;
                    offset += VALUE_LENGTH + 1) {
                byte tag = serialized[offset];
                byte[] value = Arrays.copyOfRange(
                        serialized,
                        offset + 1,
                        offset + 1 + VALUE_LENGTH);
                if (tag == RANDOMIZER_TAG && randomizer == null) {
                    randomizer = value;
                } else if (tag == CONFIRMATION_TAG
                        && confirmation == null) {
                    confirmation = value;
                } else {
                    Arrays.fill(value, (byte) 0);
                    throw new IllegalArgumentException(
                            "Apple LE SC OOB data has an unknown "
                                    + "or duplicate record tag");
                }
            }
            if (randomizer == null || confirmation == null) {
                throw new IllegalArgumentException(
                        "Apple LE SC OOB data lacks r or c");
            }
            return new AppleLeScOobData(
                    randomizer,
                    confirmation);
        } finally {
            wipe(randomizer);
            wipe(confirmation);
        }
    }

    /**
     * Emits Apple's canonical {@code 'r' || r || 'c' || c} order.
     */
    byte[] serialize() {
        requireLive();
        byte[] output = new byte[SERIALIZED_LENGTH];
        output[0] = RANDOMIZER_TAG;
        System.arraycopy(
                randomizer,
                0,
                output,
                1,
                VALUE_LENGTH);
        output[VALUE_LENGTH + 1] = CONFIRMATION_TAG;
        System.arraycopy(
                confirmation,
                0,
                output,
                VALUE_LENGTH + 2,
                VALUE_LENGTH);
        return output;
    }

    /**
     * Android {@code OobData.LeBuilder#setRandomizerHash} value.
     */
    byte[] randomizerForAndroid() {
        requireLive();
        return randomizer.clone();
    }

    /**
     * Android {@code OobData.LeBuilder} constructor confirmation value.
     */
    byte[] confirmationForAndroid() {
        requireLive();
        return confirmation.clone();
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        wipe(randomizer);
        wipe(confirmation);
        destroyed = true;
    }

    boolean isDestroyed() {
        return destroyed;
    }

    private void requireLive() {
        if (destroyed) {
            throw new IllegalStateException(
                    "Apple LE SC OOB data has been destroyed");
        }
    }

    private static void requireValue(
            String name,
            byte[] value) {
        if (value == null || value.length != VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must be exactly "
                            + VALUE_LENGTH
                            + " bytes");
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
