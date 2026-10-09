import 'dart:ui';
import 'native_pigment_layout.dart';
import 'native_pigment_check_artwork.dart';

/// Small native faceView swatches and proven, canonical shade tokens.
/// Color channels retain the source extended-sRGB values, including wide gamut.
final class NativeFacePigment {
  final Color? color;
  final String? image, base;
  final int? defaultPercent;
  final List<Color> stops;
  final bool shadeImageMask;
  const NativeFacePigment._(
    this.color,
    this.image,
    this.base,
    this.defaultPercent,
    this.stops,
    this.shadeImageMask,
  );

  factory NativeFacePigment.fromJson(Map<String, dynamic> row) {
    final image = row['image'];
    if (image != null &&
        (image is! String || !RegExp(r'^[a-f0-9]{64}\.png$').hasMatch(image))) {
      throw const FormatException('Invalid native pigment image.');
    }
    final rgba = row['rgba'];
    final color = rgba == null ? null : _color(rgba);
    if (color == null && image == null) {
      throw const FormatException('Native pigment swatch unavailable.');
    }
    final base = row['base'], percent = row['percent'];
    final rawStops = row['stops'];
    final shadeImageMask = row['shadeImageMask'] ?? false;
    if (shadeImageMask is! bool ||
        (shadeImageMask && (image == null || base == null))) {
      throw const FormatException('Invalid native shade image mask.');
    }
    if ((base != null || percent != null || rawStops != null) &&
        (base is! String ||
            base.isEmpty ||
            base.length > 256 ||
            base.contains(':') ||
            percent is! int ||
            percent < 0 ||
            percent > 100 ||
            rawStops is! List ||
            rawStops.length != 3)) {
      throw const FormatException('Invalid native pigment shade.');
    }
    return NativeFacePigment._(
      color,
      image == null ? null : 'assets/native_faces/pigment_swatches/$image',
      base,
      percent,
      List.unmodifiable([
        for (final stop in rawStops ?? const []) _color(stop),
      ]),
      shadeImageMask,
    );
  }

  static Color _color(Object? value) {
    if (value is! List ||
        value.length != 4 ||
        value.any((v) => v is! num || !v.isFinite) ||
        value.take(3).any((v) => v < -2 || v > 2) ||
        value[3] < 0 ||
        value[3] > 1) {
      throw const FormatException('Invalid native extended-sRGB color.');
    }
    return Color.from(
      alpha: (value[3] as num).toDouble(),
      red: (value[0] as num).toDouble(),
      green: (value[1] as num).toDouble(),
      blue: (value[2] as num).toDouble(),
      colorSpace: ColorSpace.extendedSRGB,
    );
  }

  bool get supportsShade => base != null;
  String tokenAt(int percent) {
    if (!supportsShade || percent < 0 || percent > 100) {
      throw const FormatException('Unsupported native pigment fraction.');
    }
    return percent == 50
        ? base!
        : '$base:${(percent / 100).toStringAsFixed(2)}';
  }

  int? percentFor(String token) {
    if (!supportsShade) return null;
    if (token == base) return 50;
    if (!token.startsWith('$base:')) return null;
    final fraction = double.tryParse(token.substring(base!.length + 1));
    if (fraction == null ||
        !fraction.isFinite ||
        fraction < 0 ||
        fraction > 1) {
      return null;
    }
    final percent = (fraction * 100).round();
    return percent >= 0 && percent <= 100 && tokenAt(percent) == token
        ? percent
        : null;
  }

  Color colorAt(int percent) {
    if (!supportsShade || percent < 0 || percent > 100) {
      throw const FormatException('Unsupported native pigment fraction.');
    }
    final index = percent <= 50 ? 0 : 1;
    final t = percent <= 50 ? percent / 50 : (percent - 50) / 50;
    // Color.lerp clamps channels. Preserve the native extended color space.
    final a = stops[index], b = stops[index + 1];
    return Color.from(
      alpha: a.a + (b.a - a.a) * t,
      red: a.r + (b.r - a.r) * t,
      green: a.g + (b.g - a.g) * t,
      blue: a.b + (b.b - a.b) * t,
      colorSpace: ColorSpace.extendedSRGB,
    );
  }
}

/// Native identity is independent of the face's token and selected shade.
final class NativePigmentChoice {
  final String name, collection;
  final Map<String, String> titles;
  final bool addable, automatic;
  const NativePigmentChoice._(
    this.name,
    this.collection,
    this.titles,
    this.addable,
    this.automatic,
  );
  factory NativePigmentChoice.fromJson(Map<String, dynamic> row) {
    bool text(Object? v) => v is String && v.isNotEmpty && v.length <= 256;
    final titles = row['titles'];
    if (!text(row['name']) ||
        (row['name'] as String).contains(':') ||
        !text(row['collection']) ||
        row['addable'] is! bool ||
        row['automatic'] is! bool ||
        titles is! Map ||
        titles.isEmpty ||
        titles.length > 32 ||
        !text(titles['en']) ||
        titles.entries.any((e) => !text(e.key) || !text(e.value))) {
      throw const FormatException('Invalid native pigment collection.');
    }
    return NativePigmentChoice._(
      row['name'],
      row['collection'],
      Map.unmodifiable(Map<String, String>.from(titles)),
      row['addable'],
      row['automatic'],
    );
  }
  String title(String language) => titles[language] ?? titles['en']!;
}

final class NativeFacePigmentSection {
  final NativePigmentLayout layout;
  final NativePigmentCheckArtwork? checkArtwork;
  final String field;
  final Map<String, NativeFacePigment> options;
  final Map<String, NativePigmentChoice> choices;
  final Set<String> editable;
  final String? defaultToken;
  const NativeFacePigmentSection._(
    this.layout,
    this.checkArtwork,
    this.field,
    this.options,
    this.choices,
    this.editable,
    this.defaultToken,
  );

  factory NativeFacePigmentSection.fromJson(
    Map<String, dynamic> row,
    Map<String, List<String>> known,
    List<NativeFacePigment> pigments, {
    Map<String, dynamic>? collections,
    List<NativePigmentChoice> colors = const [],
    NativePigmentLayout layout = NativePigmentLayout.legacy,
    NativePigmentCheckArtwork? checkArtwork,
  }) {
    final field = row['field'], values = row['values'];
    if (field is! String ||
        !known.containsKey(field) ||
        row['mode'] != 10 ||
        values is! List ||
        values.isEmpty ||
        values.length > 512) {
      throw const FormatException('Invalid native pigment section.');
    }
    final options = <String, NativeFacePigment>{};
    final bases = <String>{};
    for (final entry in values) {
      if (entry is! List ||
          entry.length != 2 ||
          entry[0] is! String ||
          !known[field]!.contains(entry[0]) ||
          options.containsKey(entry[0]) ||
          entry[1] is! int ||
          entry[1] < 0 ||
          entry[1] >= pigments.length) {
        throw const FormatException('Invalid native pigment option.');
      }
      final pigment = pigments[entry[1]];
      if (pigment.supportsShade &&
          (pigment.tokenAt(pigment.defaultPercent!) != entry[0] ||
              !bases.add(pigment.base!))) {
        throw const FormatException('Ambiguous native pigment shade.');
      }
      options[entry[0]] = pigment;
    }
    final choices = <String, NativePigmentChoice>{};
    if (collections != null) {
      final rows = collections['values'];
      if (collections['field'] != field ||
          (collections['default'] is! String ||
              (!options.containsKey(collections['default']) &&
                  !options.values.any(
                    (p) => p.percentFor(collections['default']) != null,
                  ))) ||
          rows is! List ||
          rows.length != options.length) {
        throw const FormatException('Incomplete native pigment collection.');
      }
      final names = <String>{};
      for (final entry in rows) {
        if (entry is! List ||
            entry.length != 2 ||
            entry[0] is! String ||
            !options.containsKey(entry[0]) ||
            choices.containsKey(entry[0]) ||
            entry[1] is! int ||
            entry[1] < 0 ||
            entry[1] >= colors.length) {
          throw const FormatException(
            'Unknown native pigment collection option.',
          );
        }
        final choice = colors[entry[1]];
        if (!names.add(choice.name) ||
            (options[entry[0]]!.base != null &&
                options[entry[0]]!.base != choice.name)) {
          throw const FormatException('Ambiguous native pigment identity.');
        }
        choices[entry[0]] = choice;
      }
    }
    final rawEditable = row['editable'];
    if (rawEditable != null &&
        (rawEditable is! List ||
            rawEditable.length > options.length ||
            rawEditable.any((t) => t is! String || !options.containsKey(t)) ||
            rawEditable.toSet().length != rawEditable.length)) {
      throw const FormatException('Invalid native editable pigments.');
    }
    return NativeFacePigmentSection._(
      layout,
      checkArtwork,
      field,
      Map.unmodifiable(options),
      Map.unmodifiable(choices),
      Set.unmodifiable(
        rawEditable == null
            ? options.entries
                  .where((e) => e.value.supportsShade)
                  .map((e) => e.key)
            : List<String>.from(rawEditable),
      ),
      collections?['default'],
    );
  }

  String? optionFor(String token) {
    if (options.containsKey(token)) return token;
    for (final entry in options.entries) {
      if (entry.value.percentFor(token) != null) return entry.key;
    }
    return null;
  }

  bool accepts(String token) => optionFor(token) != null;
  NativeFacePigment? pigmentFor(String token) => options[optionFor(token)];
}
