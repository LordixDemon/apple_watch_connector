package dev.applewatchandroid.bridge;

/** Portable octaxis_O: compare each unsigned pixel with its eight existing
 * neighbors. This nonlinear signed contrast is used after geometric resampling;
 * it does not detect the watermark grid or decode a camera frame by itself. */
final class OpticalLocalContrast {
    private OpticalLocalContrast() {}

    static byte[] convert(byte[] image, int width, int height, int rowStride) {
        if (image == null || width < 2 || width > 960 || height < 2 || height > 540
                || rowStride < width || rowStride > 4096
                || (long) (height - 1) * rowStride + width > image.length) {
            throw new IllegalArgumentException("Invalid optical contrast image shape");
        }
        byte[] contrast = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int center = image[y * rowStride + x] & 255, sum = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int row = y + dy, col = x + dx;
                        if ((dx != 0 || dy != 0) && row >= 0 && row < height
                                && col >= 0 && col < width) {
                            sum += Integer.compare(center, image[row * rowStride + col] & 255);
                        }
                    }
                }
                contrast[y * width + x] = (byte) sum;
            }
        }
        return contrast;
    }
}
