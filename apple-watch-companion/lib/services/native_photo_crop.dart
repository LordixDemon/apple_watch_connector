import 'dart:ui';
import 'native_face_photos.dart';

/// A crop in source coordinates, independent of gesture speed and display DPI.
final class NativePhotoCrop {
  final double aspect, zoom, x, y;
  const NativePhotoCrop({
    this.aspect = .82,
    this.zoom = 1,
    this.x = .5,
    this.y = .5,
  });

  NativePhotoCrop copyWith({double? aspect, double? zoom}) => NativePhotoCrop(
    aspect: aspect ?? this.aspect,
    zoom: zoom ?? this.zoom,
    x: x,
    y: y,
  );

  Rect rect(int width, int height) =>
      NativeFacePhotos.cropForSize(width, height, aspect, zoom, x, y);

  /// Keep the source pixel under the previous focal point under the new one.
  /// Incremental updates also work when zoom has reached a boundary.
  NativePhotoCrop transform({
    required int width,
    required int height,
    required Size viewport,
    required Offset previousFocalPoint,
    required Offset focalPoint,
    required double scale,
  }) {
    if (!viewport.width.isFinite ||
        !viewport.height.isFinite ||
        viewport.width <= 0 ||
        viewport.height <= 0 ||
        !previousFocalPoint.dx.isFinite ||
        !previousFocalPoint.dy.isFinite ||
        !focalPoint.dx.isFinite ||
        !focalPoint.dy.isFinite ||
        !scale.isFinite ||
        scale <= 0) {
      return this;
    }
    final before = rect(width, height);
    final nextZoom = (zoom * scale).clamp(1.0, 4.0);
    final after = copyWith(zoom: nextZoom).rect(width, height);
    final left =
        before.left +
        previousFocalPoint.dx / viewport.width * before.width -
        focalPoint.dx / viewport.width * after.width;
    final top =
        before.top +
        previousFocalPoint.dy / viewport.height * before.height -
        focalPoint.dy / viewport.height * after.height;
    return NativePhotoCrop(
      aspect: aspect,
      zoom: nextZoom,
      x: width > after.width ? (left / (width - after.width)).clamp(0, 1) : .5,
      y: height > after.height
          ? (top / (height - after.height)).clamp(0, 1)
          : .5,
    );
  }
}
