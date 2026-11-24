import 'package:flutter/foundation.dart';

/// Canonical UV chroma input used by the Watch watermark reader. No RGB/JPEG
/// conversion: compression and discarding chroma destroy the encoded signal.
class OpticalPlane {
  final Uint8List bytes;
  final int rowStride;
  final int pixelStride;
  const OpticalPlane(this.bytes, this.rowStride, this.pixelStride);
}

OpticalFrame _packYuv420(({int w, int h, OpticalPlane u, OpticalPlane v}) args) =>
    OpticalFrame.fromYuv420(args.w, args.h, args.u, args.v);

class OpticalFrame {
  final int width;
  final int height;
  final Uint8List uv;
  const OpticalFrame._(this.width, this.height, this.uv);

  static Future<OpticalFrame> fromYuv420Async(
    int imageWidth,
    int imageHeight,
    OpticalPlane u,
    OpticalPlane v,
  ) => compute(_packYuv420, (w: imageWidth, h: imageHeight, u: u, v: v));

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
    final uBytes = u.bytes, vBytes = v.bytes;
    final uRowStride = u.rowStride, vRowStride = v.rowStride;
    final uPixelStride = u.pixelStride, vPixelStride = v.pixelStride;
    var target = 0;
    if (uPixelStride == 1 && vPixelStride == 1) {
      for (var y = 0; y < height; y++) {
        final uRow = y * uRowStride;
        final vRow = y * vRowStride;
        for (var x = 0; x < width; x++) {
          uv[target++] = uBytes[uRow + x];
          uv[target++] = vBytes[vRow + x];
        }
      }
    } else {
      for (var y = 0; y < height; y++) {
        var uOffset = y * uRowStride;
        var vOffset = y * vRowStride;
        for (var x = 0; x < width; x++) {
          uv[target++] = uBytes[uOffset];
          uv[target++] = vBytes[vOffset];
          uOffset += uPixelStride;
          vOffset += vPixelStride;
        }
      }
    }
    return OpticalFrame._(width, height, uv);
  }
}
