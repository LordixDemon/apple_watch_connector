import 'dart:convert';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'native_preview_catalog.dart';

/// Build-time NanoTimeKit renders, indexed by exact family and customization.
/// Never matches a nearby color/style or treats demo timelines as live Watch data.
abstract final class NativeConfiguredPreviews {
  static final catalog = NativePreviewCatalog(
    loadAsset: (asset) async =>
        Uint8List.sublistView(await rootBundle.load(asset)),
    decodeIndex: (bytes) => compute(
      NativePreviewIndex.decode,
      bytes,
      debugLabel: 'Native preview index',
    ),
    decodeShard: (request) => compute(
      decodeNativePreviewShard,
      request,
      debugLabel: 'Native preview shard',
    ),
  );
  static Future<void> load() => catalog.load();

  /// Pure bounded worker: no asset reads, UI objects or global gallery state.
  static Map<String, String> decodeManifest(Uint8List bytes) {
    final index = NativePreviewIndex.decode(bytes);
    if (index.shards.isNotEmpty) {
      throw const FormatException('Sharded catalog requires lazy resolution.');
    }
    return index.entries;
  }

  static Map<String, String> decodeEntries(
    Map entries, {
    bool indexed = false,
  }) {
    return NativePreviewIndex.decodeEntries(entries, indexed: indexed);
  }

  static String configurationKey(
    String family,
    Map<String, dynamic> customization,
  ) {
    Object? canonical(Object? value) {
      if (value is Map) {
        final keys = value.keys.cast<String>().toList()..sort();
        return {for (final key in keys) key: canonical(value[key])};
      }
      if (value is List) return value.map(canonical).toList();
      return value;
    }

    return sha256
        .convert(
          utf8.encode(
            jsonEncode(
              canonical({'family': family, 'customization': customization}),
            ),
          ),
        )
        .toString();
  }

  static String? asset(String family, Map<String, dynamic>? configuration) {
    final key = keyFor(family, configuration);
    return key == null ? null : catalog.peek(key);
  }

  static String? keyFor(String family, Map<String, dynamic>? configuration) {
    if (configuration == null) return null;
    // Native families without edit modes omit this dictionary altogether.
    // An explicitly malformed/null value must never match the empty style.
    final customization = configuration.containsKey('customization')
        ? configuration['customization']
        : <String, dynamic>{};
    return customization is Map<String, dynamic>
        ? configurationKey(family, customization)
        : null;
  }

  static Future<String?> resolve(
    String family,
    Map<String, dynamic>? configuration,
  ) {
    final key = keyFor(family, configuration);
    return key == null ? Future.value(null) : catalog.resolve(key);
  }
}
