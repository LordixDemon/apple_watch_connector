import 'dart:convert';
import 'package:flutter/services.dart';
import 'native_configured_previews.dart';
import 'native_face_pigment.dart';

/// Only explicitly validated native style/base pairs can use a component program.
/// Unknown fields, colors, styles and noncanonical fractions never match it.
abstract final class NativeComponentPreviews {
  static Future<void>? _loading;
  static Map<String, NativeComponentPreviewDefinition> _entries = {};
  static Set<String> _fields = {};
  static Future<void> load() => _loading ??= _load();
  static Future<void> _load() async {
    final text = await rootBundle.loadString(
      'assets/native_faces/component_previews/catalog.json',
    );
    _entries = decodeManifest(text);
    _fields = _entries.values.map((entry) => entry.field).toSet();
  }

  static Map<String, NativeComponentPreviewDefinition> decodeManifest(
    String text,
  ) {
    if (text.length > 128 * 1024) {
      throw const FormatException('Native component catalog exceeded.');
    }
    final data = jsonDecode(text);
    if (data is! Map ||
        data['version'] != 1 ||
        data['entries'] is! Map ||
        data['entries'].length > 128) {
      throw const FormatException('Invalid native component catalog.');
    }
    final entries = <String, NativeComponentPreviewDefinition>{};
    for (final entry in (data['entries'] as Map).entries) {
      if (entry.key is! String ||
          !RegExp(r'^[a-f0-9]{64}$').hasMatch(entry.key)) {
        throw const FormatException('Invalid native component style identity.');
      }
      entries[entry.key] = NativeComponentPreviewDefinition.fromJson(
        entry.value,
      );
    }
    return Map.unmodifiable(entries);
  }

  static NativeComponentPreviewRequest? resolve(
    String family,
    Map<String, dynamic>? configuration, {
    Map<String, NativeComponentPreviewDefinition>? entries,
  }) {
    final custom = configuration?['customization'];
    if (custom is! Map<String, dynamic>) return null;
    final catalog = entries ?? _entries;
    // Index by the actual pigment axis, then complete native style identity.
    // Increasing color coverage must not hash every palette on each UI rebuild.
    final fields = entries == null
        ? _fields
        : entries.values.map((entry) => entry.field).toSet();
    for (final field in fields) {
      final token = custom[field];
      if (token is! String) continue;
      final colon = token.indexOf(':');
      final base = colon < 0 ? token : token.substring(0, colon);
      final identity = NativeConfiguredPreviews.configurationKey(family, {
        ...custom,
        field: base,
      });
      final definition = catalog[identity];
      if (definition == null ||
          definition.field != field ||
          definition.base != base) {
        continue;
      }
      final percent = definition.palettes.first.percentFor(token);
      if (percent == null) continue;
      final colors = <double>[];
      for (final palette in definition.palettes) {
        final color = palette.colorAt(percent);
        colors.addAll([color.r, color.g, color.b]);
      }
      return NativeComponentPreviewRequest(
        definition.asset,
        definition.percentColors[percent] ?? colors,
      );
    }
    return null;
  }
}

final class NativeComponentPreviewRequest {
  NativeComponentPreviewRequest(this.asset, Iterable<double> colors)
    : colors = List.unmodifiable(colors);
  final String asset;
  final List<double> colors;
}

final class NativeComponentPreviewDefinition {
  const NativeComponentPreviewDefinition._(
    this.field,
    this.base,
    this.asset,
    this.palettes,
    this.percentColors,
  );
  final String field, base, asset;
  final List<NativeFacePigment> palettes;
  final Map<int, List<double>> percentColors;
  factory NativeComponentPreviewDefinition.fromJson(Object? value) {
    const knownRoles = {
      'primaryColor',
      'secondaryColor',
      'primaryShiftedColor',
      'secondaryShiftedColor',
    };
    if (value is! Map ||
        value['field'] is! String ||
        value['field'].isEmpty ||
        value['field'].length > 64 ||
        value['base'] is! String ||
        value['asset'] is! String ||
        !RegExp(r'^[a-f0-9]{64}\.nfc$').hasMatch(value['asset']) ||
        value['roles'] is! List ||
        value['roles'].isEmpty ||
        value['roles'].length > 4 ||
        value['roles'].any(
          (r) =>
              !knownRoles.contains(r) &&
              (r is! String || !RegExp(r'^g_[a-f0-9]{16}$').hasMatch(r)),
        ) ||
        value['roles'].toSet().length != value['roles'].length ||
        value['stops'] is! List ||
        value['stops'].length != value['roles'].length) {
      throw const FormatException('Invalid native component definition.');
    }
    final palettes = <NativeFacePigment>[];
    for (final stops in value['stops']) {
      if (stops is! List ||
          stops.length != 3 ||
          stops.any(
            (v) =>
                v is! List ||
                v.length != 4 ||
                v.any((n) => n is! num || !n.isFinite || n < 0 || n > 1) ||
                v[3] != 1,
          )) {
        throw const FormatException('Unsupported native component palette.');
      }
      palettes.add(
        NativeFacePigment.fromJson({
          'rgba': stops[1],
          'stops': stops,
          'base': value['base'],
          'percent': 50,
        }),
      );
    }
    final rawOverrides = value['percentColors'] ?? const {};
    if (rawOverrides is! Map || rawOverrides.length > 101) {
      throw const FormatException('Invalid native component color branches.');
    }
    final overrides = <int, List<double>>{};
    for (final entry in rawOverrides.entries) {
      if (entry.key is! String ||
          !RegExp(r'^(0|[1-9][0-9]?|100)$').hasMatch(entry.key) ||
          entry.value is! List ||
          entry.value.length != palettes.length) {
        throw const FormatException(
          'Invalid native component branch fraction.',
        );
      }
      final colors = <double>[];
      for (final rgba in entry.value) {
        if (rgba is! List ||
            rgba.length != 4 ||
            rgba.any((n) => n is! num || !n.isFinite || n < 0 || n > 1) ||
            rgba[3] != 1) {
          throw const FormatException('Invalid native component branch color.');
        }
        colors.addAll(rgba.take(3).map<double>((n) => (n as num).toDouble()));
      }
      overrides[int.parse(entry.key)] = List.unmodifiable(colors);
    }
    return NativeComponentPreviewDefinition._(
      value['field'],
      value['base'],
      'assets/native_faces/component_previews/${value['asset']}',
      List.unmodifiable(palettes),
      Map.unmodifiable(overrides),
    );
  }
}
