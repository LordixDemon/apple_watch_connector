import 'dart:ui';

/// Original native check glyph geometry and its palette/template artwork.
final class NativePigmentCheckArtwork {
  final double width, height;
  final String paletteImage, templateImage;
  const NativePigmentCheckArtwork._(
    this.width,
    this.height,
    this.paletteImage,
    this.templateImage,
  );

  factory NativePigmentCheckArtwork.fromJson(Object? value) {
    const keys = {
      'version',
      'appearance',
      'primaryTransform',
      'width',
      'height',
      'paletteImage',
      'templateImage',
    };
    bool image(Object? v) =>
        v is String && RegExp(r'^[a-f0-9]{64}\.png$').hasMatch(v);
    if (value is! Map ||
        value.length != keys.length ||
        !value.keys.toSet().containsAll(keys) ||
        value['version'] is! int ||
        value['version'] != 1 ||
        value['appearance'] != 'dark' ||
        value['primaryTransform'] != 'clampSRGB' ||
        ['width', 'height'].any(
          (k) =>
              value[k] is! num ||
              !(value[k] as num).isFinite ||
              value[k] < 12 ||
              value[k] > 96,
        ) ||
        value['width'] != value['height'] ||
        !image(value['paletteImage']) ||
        !image(value['templateImage'])) {
      throw const FormatException('Invalid native pigment check artwork.');
    }
    return NativePigmentCheckArtwork._(
      (value['width'] as num).toDouble(),
      (value['height'] as num).toDouble(),
      'assets/native_faces/pigment_swatches/${value['paletteImage']}',
      'assets/native_faces/pigment_swatches/${value['templateImage']}',
    );
  }

  /// UIKit's symbol palette clamps input coordinates before edge blending.
  /// Preserve the face's extended color; this conversion belongs only to its check.
  Color? primaryColor(Color? value) => value == null
      ? null
      : Color.from(
          alpha: value.a,
          red: value.r.clamp(0, 1),
          green: value.g.clamp(0, 1),
          blue: value.b.clamp(0, 1),
        );
}
