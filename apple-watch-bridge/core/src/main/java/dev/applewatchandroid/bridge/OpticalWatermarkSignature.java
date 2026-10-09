package dev.applewatchandroid.bridge;

import java.util.Arrays;

/** Fixed public watermark mask, shared by the native MOOF optical profile.
 * This is a protocol constant, not a device key. Receives image correlations
 * from a separate spatial detector; does not recognize a camera image itself.
 */
final class OpticalWatermarkSignature {
    private static final String MASK =
            "68b6c8ebb21b7690792c8bf1b632b8d1bc38408040ddf7a78b39ef2b687e5b9928"
            + "ad9bebe4c4915d3276c989713faeaeb955c7cdec5e46b4a1afb1c22cfa4ac9be15"
            + "fda969c8854d5cb82a665a69a75c0d48b98c823d43ffe43cfb6152f3883facaa10e"
            + "687ecc16f437966b6862f6f077033f73d848412eda21f3f6c5c8b2815";
    private OpticalWatermarkSignature() { }

    static byte[] decode(short[] correlations) {
        if (correlations == null || correlations.length != 1024) {
            throw new IllegalArgumentException("Optical signature requires 1024 correlations");
        }
        int[] soft = new int[924];
        try {
            for (int i = 100; i < 1024; i++) {
                int nibble = Character.digit(MASK.charAt(i / 4), 16);
                boolean masked = ((nibble >>> (3 - i % 4)) & 1) != 0;
                int value = masked ? -correlations[i] : correlations[i];
                soft[i - 100] = Math.max(-32767, Math.min(32767, value));
            }
            return OpticalConvolution.decode(soft);
        } finally { Arrays.fill(soft, 0); }
    }
}
