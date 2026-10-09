package dev.applewatchandroid.bridge;

/** UV → unsigned signal plane of the verified optical profile.
 * Uses the native Q15 truncation before the final Q6 rounding and saturation.
 * Returns privately owned image data, not a recognized/authenticated device.
 */
final class OpticalChromaSignal {
    private OpticalChromaSignal() { }
    static byte[] convert(byte[] uv, int width, int height) {
        if (width < 1 || height < 1 || width > 960 || height > 540
                || uv == null || uv.length != width * height * 2) {
            throw new IllegalArgumentException("Invalid optical UV frame");
        }
        byte[] output = new byte[width * height];
        for (int i = 0; i < output.length; i++) {
            int u = uv[i * 2] & 255, v = uv[i * 2 + 1] & 255;
            int signal = (((u << 6) * 19631 >> 15) + ((v << 6) * 29009 >> 15)
                    - 62 * 64 + 32) >> 6;
            output[i] = (byte) Math.max(0, Math.min(255, signal));
        }
        return output;
    }
}
