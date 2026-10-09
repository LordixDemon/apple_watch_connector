package dev.applewatchandroid.bridge;

import java.util.Arrays;

/**
 * Portable packet layer of VisualPairing 23G71. A validated 125-bit watermark
 * carries an LSB-first 5-bit packet ID and 15 MSB-first bytes. Reconstructs 110
 * payload bytes plus the 32-bit CRC using the native GF(256) erasure code.
 *
 * <p>This does not detect a watermark in an image and does not authenticate a
 * Watch. Its output is secret input to the next protocol stage, never a log.</p>
 */
final class OpticalWatermarkPackets implements AutoCloseable {
    static final int PAYLOAD_BYTES = 110;
    static final int ENCODED_BYTES = 114;
    static final int PACKET_BYTES = 15;
    private static final int[] CRC_POLY = {
            1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0, 0, 1, 1, 0, 1,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };
    enum Status { PROGRESS, COMPLETE, INVALID }

    private final byte[][] rows = new byte[ENCODED_BYTES][];
    private final byte[][] packets = new byte[32][];
    private int rank;
    private byte[] completed;

    int rank() { return rank; }

    Status accept(int packetId, byte[] bytes) {
        if (packetId < 0 || packetId >= 32 || bytes == null || bytes.length != PACKET_BYTES) {
            throw new IllegalArgumentException("Invalid optical packet shape");
        }
        if (completed != null) return Status.COMPLETE;
        if (packets[packetId] != null) {
            if (Arrays.equals(packets[packetId], bytes)) return Status.PROGRESS;
            reset();
            return Status.INVALID;
        }
        packets[packetId] = bytes.clone();
        int random = packetId;
        for (int i = 0; i < PACKET_BYTES; i++) {
            byte[] row = new byte[ENCODED_BYTES + 1];
            for (int column = 0; column < ENCODED_BYTES; column++) {
                random = random * 0x343fd + 0x269ec3; // uint32 wrap, native MSVC LCG
                row[column] = (byte) (random >>> 16);
            }
            row[ENCODED_BYTES] = bytes[i];
            boolean consistent;
            try { consistent = addEquation(row); }
            finally { Arrays.fill(row, (byte) 0); }
            if (!consistent) {
                reset();
                return Status.INVALID;
            }
        }
        if (rank < ENCODED_BYTES) return Status.PROGRESS;
        byte[] encoded = new byte[ENCODED_BYTES];
        try {
            for (int i = 0; i < ENCODED_BYTES; i++) encoded[i] = rows[i][ENCODED_BYTES];
            if (!validCrc(encoded)) {
                reset();
                return Status.INVALID;
            }
            completed = Arrays.copyOf(encoded, PAYLOAD_BYTES);
            return Status.COMPLETE;
        } finally { Arrays.fill(encoded, (byte) 0); }
    }

    /** Transfers ownership of the result and clears all decoder state. */
    byte[] takePayload() {
        byte[] result = completed;
        completed = null;
        reset();
        return result;
    }

    private boolean addEquation(byte[] row) {
        // Reduce all known pivots first. A gap before a later pivot must not
        // leave that later coefficient in the newly inserted row.
        for (int column = 0; column < ENCODED_BYTES; column++) {
            if (rows[column] != null && row[column] != 0) {
                subtract(row, rows[column], row[column] & 255);
            }
        }
        for (int column = 0; column < ENCODED_BYTES; column++) {
            int factor = row[column] & 255;
            if (factor == 0) continue;
            int inverse = inverse(factor);
            for (int i = column; i <= ENCODED_BYTES; i++) {
                row[i] = (byte) multiply(row[i] & 255, inverse);
            }
            for (byte[] other : rows) {
                if (other != null && other[column] != 0) {
                    subtract(other, row, other[column] & 255);
                }
            }
            rows[column] = row.clone();
            rank++;
            return true;
        }
        return row[ENCODED_BYTES] == 0;
    }

    private static void subtract(byte[] target, byte[] pivot, int factor) {
        for (int i = 0; i <= ENCODED_BYTES; i++) {
            target[i] ^= (byte) multiply(pivot[i] & 255, factor);
        }
    }

    private static int multiply(int a, int b) {
        int result = 0;
        while (b != 0) {
            if ((b & 1) != 0) result ^= a;
            b >>>= 1;
            a <<= 1;
            if ((a & 256) != 0) a ^= 0x11d;
        }
        return result;
    }

    private static int inverse(int value) {
        int result = 1, exponent = 254;
        while (exponent != 0) {
            if ((exponent & 1) != 0) result = multiply(result, value);
            value = multiply(value, value);
            exponent >>>= 1;
        }
        return result;
    }

    static boolean validCrc(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_BYTES) return false;
        int[] register = new int[32];
        for (int bit = 0; bit < ENCODED_BYTES * 8; bit++) {
            int feedback = register[31];
            for (int i = 31; i > 0; i--) register[i] = register[i - 1] ^ CRC_POLY[i] * feedback;
            int input = bit < PAYLOAD_BYTES * 8 ? (encoded[bit / 8] >>> (7 - bit % 8)) & 1 : 0;
            register[0] = input ^ CRC_POLY[0] * feedback;
        }
        boolean matches = true;
        for (int i = 0; i < 32; i++) {
            int bit = PAYLOAD_BYTES * 8 + i;
            matches &= ((encoded[bit / 8] >>> (7 - bit % 8)) & 1) == register[31 - i];
        }
        Arrays.fill(register, 0);
        return matches;
    }

    void reset() {
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] != null) Arrays.fill(rows[i], (byte) 0);
            rows[i] = null;
        }
        for (int i = 0; i < packets.length; i++) {
            if (packets[i] != null) Arrays.fill(packets[i], (byte) 0);
            packets[i] = null;
        }
        if (completed != null) Arrays.fill(completed, (byte) 0);
        completed = null;
        rank = 0;
    }

    @Override public void close() { reset(); }
}
