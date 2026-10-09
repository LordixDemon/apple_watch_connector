import 'dart:typed_data';

/// Canonical UV chroma input used by the Watch watermark reader. No RGB/JPEG
/// conversion: compression and discarding chroma destroy the encoded signal.
class OpticalPlane {
  final Uint8List bytes;
  final int rowStride;
  final int pixelStride;
  const OpticalPlane(this.bytes, this.rowStride, this.pixelStride);
}

class OpticalFrame {
  final int width;
  final int height;
  final Uint8List uv;
  const OpticalFrame._(this.width, this.height, this.uv);

  static OpticalFrame fromYuv420(
    int imageWidth,
    int imageHeight,
    OpticalPlane u,
    OpticalPlane v,
  ) {
    if (imageWidth < 2 ||
        imageHeight < 2 ||
        imageWidth > 1920 ||
        imageHeight > 1080 ||
        imageWidth.isOdd ||
        imageHeight.isOdd) {
      throw const FormatException('Unsupported camera dimensions');
    }
    final width = imageWidth ~/ 2, height = imageHeight ~/ 2;
    for (final plane in [u, v]) {
      if (plane.pixelStride < 1 ||
          plane.pixelStride > 4 ||
          plane.rowStride < (width - 1) * plane.pixelStride + 1 ||
          (height - 1) * plane.rowStride + (width - 1) * plane.pixelStride >=
              plane.bytes.length) {
        throw const FormatException('Invalid camera plane layout');
      }
    }
    final uv = Uint8List(width * height * 2);
    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        final target = (y * width + x) * 2;
        uv[target] = u.bytes[y * u.rowStride + x * u.pixelStride];
        uv[target + 1] = v.bytes[y * v.rowStride + x * v.pixelStride];
      }
    }
    return OpticalFrame._(width, height, uv);
  }
}
