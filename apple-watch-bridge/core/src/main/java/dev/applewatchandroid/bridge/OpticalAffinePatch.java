package dev.applewatchandroid.bridge;

/** Exact fixed-point InvertLTBilinear36x36Stride48_G sampling. The supplied
 * transform must come from grid detection; this class does not estimate it. */
final class OpticalAffinePatch {
    static final int SIZE = 36, STRIDE = 48;
    private OpticalAffinePatch() {}

    /** Matrix is {rowY,rowX,colY,colX} in signed Q20. Returns null when the
     * complete patch and its bilinear neighbors do not fit inside the image. */
    static byte[] sample(byte[] image, int width, int height, int rowStride,
            int[] matrix, int originX, int originY) {
        if (image == null || width < 2 || width > 960 || height < 2 || height > 540
                || rowStride < width || rowStride > 4096
                || (long) (height - 1) * rowStride + width > image.length
                || matrix == null || matrix.length != 4) {
            throw new IllegalArgumentException("Invalid optical patch shape");
        }
        for (int coefficient : matrix) {
            if (Math.abs((long) coefficient) > 4L * (1 << 20)) {
                throw new IllegalArgumentException("Optical patch transform is out of bounds");
            }
        }
        // Affine extrema lie at corners. Use longs here so an invalid caller
        // cannot wrap a source coordinate into an apparently valid image row.
        for (int row : new int[]{0, SIZE - 1}) {
            for (int col : new int[]{0, SIZE - 1}) {
                long px = (long) originX + 512 + (long) row * matrix[1] + (long) col * matrix[3];
                long py = (long) originY + 512 + (long) row * matrix[0] + (long) col * matrix[2];
                if ((px >> 20) < 0 || (px >> 20) >= width - 1
                        || (py >> 20) < 0 || (py >> 20) >= height - 1) return null;
            }
        }
        byte[] patch = new byte[SIZE * STRIDE];
        for (int row = 0; row < SIZE; row++) {
            int px = originX + 512 + row * matrix[1];
            int py = originY + 512 + row * matrix[0];
            for (int col = 0; col < SIZE; col++) {
                int offset = (py >> 20) * rowStride + (px >> 20);
                int fx = (px >>> 10) & 1023, fy = (py >>> 10) & 1023;
                int topLeft = image[offset] & 255, bottomLeft = image[offset + rowStride] & 255;
                int top = ((image[offset + 1] & 255) - topLeft) * fx + topLeft * 1024;
                int bottom = ((image[offset + rowStride + 1] & 255) - bottomLeft) * fx + bottomLeft * 1024;
                patch[row * STRIDE + col] = (byte) ((((bottom - top) >> 5) * fy + top * 32) >> 15);
                px += matrix[3]; py += matrix[2];
            }
        }
        return patch;
    }
}
