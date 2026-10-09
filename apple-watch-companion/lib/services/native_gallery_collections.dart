import 'dart:convert';
import 'package:flutter/services.dart';
import 'native_face_gallery.dart';

/// Native factory order and appearance presets. No simulator intents or identity.
abstract final class NativeGalleryCollections {
  static Future<List<NativeGallerySection>>? _loading;
  static Future<List<NativeGallerySection>> load() => _loading ??= _load();
  static Future<List<NativeGallerySection>> _load() async {
    final profiles = await NativeFaceGallery.load();
    final raw = await rootBundle.loadString(
      'assets/native_faces/gallery_collections_26_2.json',
    );
    if (raw.length > 256 * 1024) {
      throw const FormatException('Gallery metadata exceeds limit.');
    }
    final sections = decode(raw);
    final represented = sections
        .expand((s) => s.variants)
        .map((v) => v.template.family)
        .toSet();
    // Photos requires its live resource editor, never a simulator resource directory.
    for (final profile in profiles.where(
      (p) => p.requiresPhotos && !represented.contains(p.family),
    )) {
      sections.add(
        NativeGallerySection(
          {'en': profile.title('en')},
          [NativeGalleryVariant(profile, profile.configurationJson)],
        ),
      );
    }
    return List.unmodifiable(sections);
  }

  static List<NativeGallerySection> decode(String raw) {
    if (raw.length > 256 * 1024) {
      throw const FormatException('Gallery metadata exceeds limit.');
    }
    final data = jsonDecode(raw);
    if (data is! Map ||
        data['version'] != 1 ||
        data['sections'] is! List ||
        (data['sections'] as List).length > 128) {
      throw const FormatException('Invalid native gallery metadata.');
    }
    var total = 0;
    final result = <NativeGallerySection>[];
    for (final row in data['sections'] as List) {
      if (row is! Map ||
          row['titles'] is! Map ||
          row['variants'] is! List ||
          (row['variants'] as List).isEmpty ||
          (row['variants'] as List).length > 128) {
        throw const FormatException('Invalid native gallery section.');
      }
      final titles = <String, String>{};
      for (final entry in (row['titles'] as Map).entries) {
        if (entry.key is! String ||
            (entry.key as String).length > 32 ||
            entry.value is! String ||
            (entry.value as String).isEmpty ||
            (entry.value as String).length > 128) {
          throw const FormatException('Invalid native gallery localization.');
        }
        titles[entry.key as String] = entry.value as String;
      }
      if (!titles.containsKey('en') || titles.length > 64) {
        throw const FormatException('Missing gallery title.');
      }
      final variants = <NativeGalleryVariant>[];
      for (final item in row['variants'] as List) {
        if (++total > 1024 ||
            item is! Map ||
            item.keys.any(
              (k) => !const {'family', 'customization'}.contains(k),
            ) ||
            item['family'] is! String ||
            item['customization'] is! Map) {
          throw const FormatException('Invalid gallery variant.');
        }
        final template = NativeFaceGallery.profile(item['family'] as String);
        if (template == null || template.requiresPhotos) {
          throw const FormatException('Unavailable gallery family.');
        }
        final values = _customization(item['customization'], 0);
        final config =
            jsonDecode(template.configurationJson) as Map<String, dynamic>;
        config['customization'] = values;
        final json = jsonEncode(config);
        // Validate the exact existing resource-free family/archive boundary.
        template.withConfiguration(json);
        variants.add(NativeGalleryVariant(template, json));
      }
      result.add(NativeGallerySection(titles, variants));
    }
    return result;
  }

  static Object _customization(Object? value, int depth) {
    if (depth > 8) {
      throw const FormatException('Gallery customization exceeds limit.');
    }
    if (value is String && value.length <= 256) return value;
    if (value is Map && value.length <= 64) {
      final result = <String, dynamic>{};
      for (final entry in value.entries) {
        if (entry.key is! String ||
            (entry.key as String).isEmpty ||
            (entry.key as String).length > 128) {
          throw const FormatException('Invalid gallery customization field.');
        }
        result[entry.key as String] = _customization(entry.value, depth + 1);
      }
      return result;
    }
    throw const FormatException('Invalid gallery customization value.');
  }
}

final class NativeGallerySection {
  final Map<String, String> titles;
  final List<NativeGalleryVariant> variants;
  NativeGallerySection(
    Map<String, String> titles,
    List<NativeGalleryVariant> variants,
  ) : titles = Map.unmodifiable(titles),
      variants = List.unmodifiable(variants);
  String title(String locale) =>
      titles[locale] ??
      titles[locale.split(RegExp('[-_]')).first] ??
      titles['en']!;
}

final class NativeGalleryVariant {
  final NativeFaceTemplate template;
  final String configurationJson;
  late final Map<String, dynamic> configuration =
      _freeze(jsonDecode(configurationJson)) as Map<String, dynamic>;
  NativeGalleryVariant(this.template, this.configurationJson);

  // Reused by every gallery view. Editors receive configurationJson and make
  // their own draft; a preview must never change a catalog preset.
  static Object? _freeze(Object? value) => switch (value) {
    Map<String, dynamic> map => Map<String, dynamic>.unmodifiable(
      map.map((key, value) => MapEntry(key, _freeze(value))),
    ),
    List list => List<Object?>.unmodifiable(list.map(_freeze)),
    _ => value,
  };
}
