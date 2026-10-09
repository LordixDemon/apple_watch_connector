package dev.applewatchandroid.bridge;

import java.util.Arrays;

/** Portable decoding of the 23G71 optical packet's tail-biting convolution code.
 * Input is 924 deinterleaved soft values after the fixed 100-bit header and
 * watermark mask have been removed. Positive values indicate an encoded one.
 * Image localization and the mask are separate stages, not implemented here.
 */
final class OpticalConvolution {
    private static final int DATA_BITS = 149;
    private static final int[] GENERATORS = {315, 331, 441, 501, 431, 485};
    private static final int[] REPEATED = {30, 59, 88, 118, 147, 176, 206, 235,
            264, 294, 323, 352, 382, 411, 440, 470, 499, 528, 558, 587, 616,
            646, 675, 704, 734, 763, 792, 822, 851, 880};
    private static final int[] CRC_POLYNOMIAL = {1, 0, 0, 0, 0, 0, 0, 0,
            1, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1};

    private OpticalConvolution() { }

    /** Returns the 125 packet bits only when the packet CRC and tail match. */
    static byte[] decode(int[] soft) {
        if (soft == null || soft.length != 924) {
            throw new IllegalArgumentException("Optical convolution requires 924 soft values");
        }
        boolean hasSignal = false;
        for (int value : soft) {
            if (Math.abs((long) value) > 32767) return null;
            hasSignal |= value != 0;
        }
        if (!hasSignal) return null;
        int[][] branches = new int[DATA_BITS][512];
        byte[][] traceback = new byte[DATA_BITS * 3][256];
        int[] current = new int[256];
        int[] next = new int[256];
        int[] coded = new int[894];
        byte[] decoded = new byte[DATA_BITS];
        try {
            int source = 0, repeated = 0;
            for (int i = 0; i < coded.length; i++) {
                int value = soft[source++];
                if (Math.abs((long) value) > 32767) return null;
                if (repeated < REPEATED.length && REPEATED[repeated] == i) {
                    int additional = soft[source++];
                    if (Math.abs((long) additional) > 32767) return null;
                    value += additional;
                    repeated++;
                } else {
                    // Native reader weighs unrepeated symbols twice.
                    value *= 2;
                }
                coded[i] = value;
            }
            for (int t = 0; t < DATA_BITS; t++) {
                for (int register = 0; register < 512; register++) {
                    int score = 0;
                    for (int g = 0; g < GENERATORS.length; g++) {
                        score += (Integer.bitCount(register & GENERATORS[g]) % 2 == 1 ? 1 : -1)
                                * coded[t * 6 + g];
                    }
                    branches[t][register] = score;
                }
            }
            // Circular Viterbi: two warm-up cycles remove the arbitrary start.
            // Reject ambiguous/noisy paths unless the final cycle closes and
            // passes the independently defined native CRC.
            for (int t = 0; t < DATA_BITS * 3; t++) {
                for (int state = 0; state < 256; state++) {
                    int previous = (state & 127) << 1;
                    int input = state >>> 7;
                    int a = current[previous] + branches[t % DATA_BITS][(input << 8) | previous];
                    int b = current[previous + 1] + branches[t % DATA_BITS][(input << 8) | (previous + 1)];
                    next[state] = Math.max(a, b);
                    traceback[t][state] = (byte) (b > a ? 1 : 0);
                }
                int[] swap = current; current = next; next = swap;
                int maximum = Arrays.stream(current).max().orElse(0);
                for (int state = 0; state < 256; state++) current[state] -= maximum;
            }
            int end = 0;
            for (int state = 1; state < 256; state++) if (current[state] > current[end]) end = state;
            int state = end;
            for (int t = DATA_BITS * 3 - 1; t >= DATA_BITS * 2; t--) {
                decoded[t - DATA_BITS * 2] = (byte) (state >>> 7);
                state = ((state & 127) << 1) | traceback[t][state];
            }
            if (state != end || !validCrc(decoded)) return null;
            return Arrays.copyOf(decoded, 125);
        } finally {
            Arrays.fill(coded, 0); Arrays.fill(current, 0); Arrays.fill(next, 0);
            Arrays.fill(decoded, (byte) 0);
            for (int[] row : branches) Arrays.fill(row, 0);
            for (byte[] row : traceback) Arrays.fill(row, (byte) 0);
        }
    }

    private static boolean validCrc(byte[] bits) {
        int[] register = new int[24];
        try {
            for (int t = 0; t < 149; t++) {
                int feedback = register[23];
                for (int i = 23; i > 0; i--) register[i] = register[i - 1] ^ feedback * CRC_POLYNOMIAL[i];
                register[0] = (t < 125 ? bits[t] : 0) ^ feedback;
            }
            int difference = 0;
            for (int i = 0; i < 24; i++) difference |= register[23 - i] ^ bits[125 + i];
            return difference == 0;
        } finally { Arrays.fill(register, 0); }
    }
}
