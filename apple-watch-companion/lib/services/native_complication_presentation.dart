import 'dart:convert';
import 'package:flutter/services.dart';
import '../l10n/strings.dart';
import 'native_intent_identity.dart';
import 'native_intent_parameters.dart';

/// Names never create a provider, change compatibility, or rewrite native data.
/// Exact Watch descriptors take precedence over optional firmware display hints.
final class NativeComplicationPresentation {
  static Future<void>? _loading;
  static Map<String, Map<String, String>> _labels = const {};
  final Map<String, List<({Map row, String descriptor})>> _widgets = {};
  final Map<String, List<({Map row, String descriptor})>> _bundles = {};
  final Map<String, NativeIntentIdentity?> _intents = {};
  final Map<String, Set<String>> _semanticNames = {};
  final _parameters = NativeIntentParameterIndex();

  NativeComplicationPresentation(Map<String, dynamic> catalog) {
    for (final group in catalog.entries) {
      if (group.value is! Map) continue;
      for (final row in (group.value as Map).values.whereType<Map>()) {
        final descriptor = row['descriptor'];
        if (descriptor is Map) {
          final key = _widgetIdentity(descriptor);
          if (key != null) {
            (_widgets[key] ??= []).add((
              row: row,
              descriptor: _canonical(descriptor),
            ));
          }
        }
        if (group.key.startsWith('BundleComplications:')) {
          (_bundles[group.key.substring('BundleComplications:'.length)] ??= [])
              .add((row: row, descriptor: _canonical(row['bundleDescriptor'])));
        }
      }
    }
  }

  static Future<void> load() => _loading ??= _load();
  static Future<void> _load() async {
    final raw = await rootBundle.loadString(
      'assets/native_faces/complication_labels.json',
    );
    if (raw.length > 256 * 1024) {
      throw const FormatException('Label size exceeded');
    }
    final data = jsonDecode(raw) as Map;
    if (data['schema'] != 1 ||
        data['labels'] is! List ||
        (data['labels'] as List).length > 1024) {
      throw const FormatException('Invalid complication label catalog');
    }
    final bundles = data['bundleLabels'] ?? const [];
    if (bundles is! List ||
        bundles.length + (data['labels'] as List).length > 1024) {
      throw const FormatException('Invalid bundle label catalog');
    }
    final labels = <String, Map<String, String>>{};
    for (final row in [...data['labels'] as List, ...bundles]) {
      if (row is! Map || row['identity'] is! Map || row['labels'] is! Map) {
        throw const FormatException('Invalid complication label');
      }
      final identity = row['identity'] as Map;
      final key = _widgetIdentity(identity) ?? _bundleIdentity(identity);
      final translations = <String, String>{};
      for (final entry in (row['labels'] as Map).entries) {
        if (entry.key is! String || _name(entry.value) == null) {
          throw const FormatException('Invalid complication translation');
        }
        translations[entry.key as String] = entry.value as String;
      }
      if (key == null || translations.isEmpty || labels.containsKey(key)) {
        throw const FormatException('Invalid complication identity');
      }
      labels[key] = Map.unmodifiable(translations);
    }
    _labels = Map.unmodifiable(labels);
  }

  String title(Object? value, {String? faceId, String? slot}) {
    if (value is! Map || value.isEmpty) {
      return Strings.current.nativeFaceNoComplication;
    }
    final descriptor = value['descriptor'];
    if (descriptor is Map) {
      final identity = _widgetIdentity(descriptor);
      final List<({Map row, String descriptor})> rows = identity == null
          ? const []
          : _widgets[identity] ?? const [];
      final exactNames = <String>{};
      final descriptorKey = _canonical(descriptor);
      final observedNames = <String>{};
      final providerNames = <String>{};
      for (final entry in rows) {
        final row = entry.row;
        final name = _name(row['name']);
        if (name == null) continue;
        providerNames.add(name);
        if (entry.descriptor == descriptorKey) {
          exactNames.add(name);
        }
        if (faceId != null &&
            slot != null &&
            row['observedSlots'] is List &&
            (row['observedSlots'] as List).contains('$faceId:$slot')) {
          observedNames.add(name);
        }
      }
      if (exactNames.length == 1) return exactNames.single;
      if (_semanticNames.length >= 32 &&
          !_semanticNames.containsKey(descriptorKey)) {
        _semanticNames.remove(_semanticNames.keys.first);
      }
      final semanticNames = _semanticNames.putIfAbsent(
        descriptorKey,
        () => {
          for (final entry in rows)
            if (_name(entry.row['name']) != null &&
                _sameIntentDescriptor(entry.row['descriptor'], descriptor))
              entry.row['name'] as String,
        },
      );
      if (semanticNames.length == 1) return semanticNames.single;
      final parameters = _parameters.read(descriptor['intent']);
      if (parameters != null) {
        return parameters.durations.map((p) => p.summary).join(' · ');
      }
      if (observedNames.length == 1) return observedNames.single;
      if (providerNames.length == 1) return providerNames.single;
      // Hints cover only verified static names. Configurable provider labels
      // remain unresolved if the Watch reports conflicting intent-specific names.
      if (rows.isEmpty && identity != null) {
        final hint = _hint(identity);
        if (hint != null) return hint;
      }
      return _name(descriptor['kind']) ??
          Strings.current.nativeFaceUnknownComplication;
    }
    final legacy = value['bundle app complication descriptor'];
    // Native built-in bundle defaults do not carry a CLK descriptor inventory.
    // Never replace the name of a configurable legacy descriptor with this hint.
    if (legacy == null) {
      final hint = _hint(_bundleIdentity(value));
      if (hint != null) return hint;
    }
    if (legacy is Map) {
      final embeddedName = _name(legacy['displayName']);
      if (embeddedName != null) return embeddedName;
      final names = <String>{};
      final descriptorKey = _canonical(legacy);
      for (final entry in _bundles[value['bundle identifier']] ?? const []) {
        if (entry.descriptor == descriptorKey) {
          final name = _name(entry.row['name']);
          if (name != null) names.add(name);
        }
      }
      if (names.length == 1) return names.single;
      final identifier = _name(legacy['identifier']);
      if (identifier != null) return identifier;
    }
    // Never expose JSON/Base64 opaque intents in the ordinary interface.
    return _name(value['bundle identifier']) ??
        _name(value['app']) ??
        Strings.current.nativeFaceUnknownComplication;
  }

  static String? _name(Object? value) =>
      value is String && value.trim().isNotEmpty ? value : null;

  String? _hint(String? identity) {
    final hints = _labels[identity];
    final language = Strings.current.localeName
        .replaceAll('_', '-')
        .split('-')
        .first;
    return hints?[language] ?? hints?['en'];
  }

  /// A successfully decoded inventory need not cover widgets already used on
  /// the Watch. Legacy built-in bundle defaults have no descriptor inventory.
  String? catalogNotice(
    bool decodedCompletely,
    Iterable<Map<String, dynamic>?> configurations,
  ) {
    if (!decodedCompletely) {
      return Strings.current.nativeFaceComplicationCatalogPartial;
    }
    for (final configuration in configurations) {
      final slots = configuration?['complications'];
      if (slots is! Map) continue;
      for (final value in slots.values.whereType<Map>()) {
        if (value['type'] != 56 || value['descriptor'] is! Map) continue;
        final identity = _widgetIdentity(value['descriptor'] as Map);
        if (identity == null || !_widgets.containsKey(identity)) {
          return Strings.current.nativeFaceComplicationVariantsMissing;
        }
      }
    }
    return null;
  }

  static String? _bundleIdentity(Map value) {
    const keys = ['bundle app identifier', 'bundle identifier'];
    if (keys.any((key) => _name(value[key]) == null)) return null;
    return jsonEncode([for (final key in keys) value[key]]);
  }

  /// Display equivalence only. Retain the incumbent's original bytes when
  /// grouping picker rows; never use this to acknowledge a Watch write.
  bool sameConfiguration(Object? a, Object? b) {
    if (a is! Map || b is! Map) return false;
    if (_canonical(a) == _canonical(b)) return true;
    final left = a['descriptor'], right = b['descriptor'];
    if (left is! Map || right is! Map) return false;
    Map withoutDescriptor(Map value) => Map.from(value)..remove('descriptor');
    return _canonical(withoutDescriptor(a)) ==
            _canonical(withoutDescriptor(b)) &&
        _sameIntentDescriptor(left, right);
  }

  bool _sameIntentDescriptor(Object? a, Map b) {
    if (a is! Map || a['intent'] is! String || b['intent'] is! String) {
      return false;
    }
    Map withoutIntent(Map value) => Map.from(value)..remove('intent');
    if (_canonical(withoutIntent(a)) != _canonical(withoutIntent(b))) {
      return false;
    }
    NativeIntentIdentity? parsed(String value) {
      if (_intents.length >= 32 && !_intents.containsKey(value)) {
        _intents.remove(_intents.keys.first);
      }
      return _intents.putIfAbsent(
        value,
        () => NativeIntentIdentity.parse(value),
      );
    }

    final left = parsed(a['intent'] as String),
        right = parsed(b['intent'] as String);
    return left != null && right != null && left.same(right);
  }

  static String? _widgetIdentity(Map descriptor) {
    const keys = [
      'containerBundleIdentifier',
      'extensionBundleIdentifier',
      'kind',
    ];
    if (keys.any((key) => _name(descriptor[key]) == null)) return null;
    return jsonEncode([for (final key in keys) descriptor[key]]);
  }

  static String _canonical(Object? value) {
    Object? ordered(Object? item) {
      if (item is Map) {
        final keys = item.keys.whereType<String>().toList()..sort();
        return {for (final key in keys) key: ordered(item[key])};
      }
      if (item is List) return item.map(ordered).toList();
      return item;
    }

    return jsonEncode(ordered(value));
  }
}
