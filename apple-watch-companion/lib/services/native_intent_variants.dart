import 'dart:convert';
import 'native_intent_identity.dart';
import 'native_complication_choices.dart';

/// Per-editor, source-scoped cache. Every candidate is a compatible received
/// descriptor; this module never manufactures an AppIntent parameter value.
final class NativeIntentVariantIndex {
  Object? _source;
  final _identities = <String, NativeIntentIdentity?>{};
  final _bindings = <String, _Binding>{};
  int _bytes = 0;
  int _identityBytes = 0;

  NativeIntentIdentity? _read(Object? value) {
    if (value is! String || value.length > 131072) return null;
    if (_identities.containsKey(value)) {
      final identity = _identities.remove(value);
      _identities[value] = identity;
      return identity;
    }
    while (_identities.isNotEmpty &&
        (_identities.length >= 32 ||
            _identityBytes + value.length > 256 * 1024)) {
      final oldest = _identities.keys.first;
      _identityBytes -= oldest.length;
      _identities.remove(oldest);
    }
    final identity = NativeIntentIdentity.parse(value);
    _identities[value] = identity;
    _identityBytes += value.length;
    return identity;
  }

  NativeIntentVariants? bind({
    required Object source,
    required String slot,
    required Object? current,
    required List<NativeComplicationChoice> Function() choices,
  }) {
    if (!identical(source, _source)) {
      _source = source;
      _bindings.clear();
      _identities.clear();
      _bytes = 0;
      _identityBytes = 0;
    }
    if (current is! Map<String, dynamic> || current['descriptor'] is! Map) {
      return null;
    }
    final encoded = current['descriptor']['intent'];
    final identity = _read(encoded);
    final parameters = identity?.appStringParameters;
    if (identity == null || parameters == null) return null;
    final outside = _outside(current);
    if (outside.length > 32768) return null;
    final key = '$slot\n$outside';
    var binding = _bindings[key];
    if (binding == null || !binding.routing.sameAppIntentRouting(identity)) {
      final candidates = choices();
      if (candidates.length > 512) return null;
      final variants = <_Variant>[];
      var bytes = 0;
      for (final choice in candidates) {
        if (_outside(choice.value) != outside || choice.title.length > 512) {
          continue;
        }
        final intent = choice.value['descriptor']['intent'];
        final parsed = _read(intent);
        final values = parsed?.appStringParameters;
        if (parsed == null ||
            values == null ||
            intent is! String ||
            !identity.sameAppIntentRouting(parsed) ||
            parameters.length != values.length ||
            parameters.keys.any((name) => !values.containsKey(name))) {
          continue;
        }
        final duplicate = variants
            .where((v) => _same(v.parameters, values))
            .firstOrNull;
        if (duplicate != null) {
          // Equal parameters with conflicting native archives are ambiguous.
          if (!duplicate.identity.same(parsed)) return null;
          continue;
        }
        bytes += intent.length + choice.title.length;
        if (bytes > 256 * 1024) return null;
        variants.add(_Variant(values, intent, choice.title, parsed));
      }
      if (variants.length < 2) return null;
      binding = _Binding(List.unmodifiable(variants), identity, bytes);
      while (_bindings.isNotEmpty &&
          (_bindings.length >= 8 || _bytes + bytes > 256 * 1024)) {
        _bytes -= _bindings.remove(_bindings.keys.first)!.bytes;
      }
      final prior = _bindings.remove(key);
      if (prior != null) _bytes -= prior.bytes;
      _bindings[key] = binding;
      _bytes += bytes;
    }
    final fields =
        parameters.keys
            .where(
              (name) => binding!.variants.any(
                (v) => v.parameters[name] != parameters[name],
              ),
            )
            .toList()
          ..sort();
    if (fields.isEmpty) return null;
    final result = NativeIntentVariants._(
      jsonDecode(jsonEncode(current)) as Map<String, dynamic>,
      parameters,
      List.unmodifiable(fields),
      binding.variants,
    );
    // A disconnected/sparse inventory can contain multiple presets without a
    // reachable one-field change. Do not expose a settings page with no action.
    return fields.any((name) => result.options(name, parameters).length > 1)
        ? result
        : null;
  }

  static String _outside(Map<String, dynamic> value) {
    if (value['descriptor'] is! Map) return '';
    Object? canonical(Object? v) {
      if (v is Map) {
        final keys = v.keys.cast<String>().toList()..sort();
        return {for (final k in keys) k: canonical(v[k])};
      }
      if (v is List) return v.map(canonical).toList();
      return v;
    }

    return jsonEncode(
      canonical({
        ...value,
        'descriptor': Map<String, dynamic>.from(value['descriptor'])
          ..remove('intent'),
      }),
    );
  }
}

final class NativeIntentVariants {
  final Map<String, dynamic> _original;
  final Map<String, String> initial;
  final List<String> fields;
  final List<_Variant> _variants;
  NativeIntentVariants._(
    this._original,
    this.initial,
    this.fields,
    this._variants,
  );

  String get summary => fields.map((f) => label(f, initial)).join(' · ');

  /// Received schemas do not include localized field titles for this format.
  /// Use a readable identifier fallback; option words still come from native names.
  static String fieldTitle(String name) {
    final words = name
        .replaceAllMapped(
          RegExp(r'([a-z0-9])([A-Z])'),
          (m) => '${m[1]} ${m[2]}',
        )
        .replaceAll(RegExp(r'[_-]+'), ' ')
        .trim();
    if (words.isEmpty) return name;
    return '${words[0].toUpperCase()}${words.substring(1)}';
  }

  /// Only options whose complete combination was actually supplied. Dependence
  /// between parameters is retained, including sparse future inventories.
  List<({String value, String label})> options(
    String field,
    Map<String, String> values,
  ) {
    if (!fields.contains(field) || !_validNames(values)) return const [];
    final matching = _variants
        .where(
          (v) => values.entries.every(
            (e) => e.key == field || e.value == v.parameters[e.key],
          ),
        )
        .toList();
    final titles = {for (final v in matching) v.parameters[field]!: v.title};
    final shortened = _shortLabels(titles);
    return List.unmodifiable(
      titles.keys.map(
        (value) => (value: value, label: shortened[value] ?? value),
      ),
    );
  }

  bool accepts(Map<String, String> values) =>
      _validNames(values) &&
      (_same(values, initial) ||
          _variants.any((v) => _same(v.parameters, values)));

  Map<String, dynamic> update(Map<String, String> values) {
    if (!accepts(values)) {
      throw const FormatException('Unreceived native parameter combination');
    }
    final result = jsonDecode(jsonEncode(_original)) as Map<String, dynamic>;
    if (_same(values, initial)) {
      return result; // Preserve exact incumbent archive.
    }
    final variant = _variants.singleWhere((v) => _same(v.parameters, values));
    // Only the whole, received archive changes. Unknown slot/descriptor fields
    // remain from the original, and exact routing was checked during binding.
    (result['descriptor'] as Map)['intent'] = variant.encoded;
    return result;
  }

  String label(String field, Map<String, String> values) =>
      options(
        field,
        values,
      ).where((o) => o.value == values[field]).firstOrNull?.label ??
      values[field] ??
      '';

  bool _validNames(Map<String, String> values) =>
      values.length == initial.length && values.keys.every(initial.containsKey);

  static Map<String, String> _shortLabels(Map<String, String> titles) {
    if (titles.length < 2) return titles;
    final words = titles.values
        .map((v) => v.trim().split(RegExp(r'\s+')))
        .toList();
    var prefix = 0, suffix = 0;
    while (words.every((w) => w.length > prefix + 1) &&
        words.every((w) => w[prefix] == words.first[prefix])) {
      prefix++;
    }
    while (words.every((w) => w.length > prefix + suffix + 1) &&
        words.every(
          (w) =>
              w[w.length - suffix - 1] ==
              words.first[words.first.length - suffix - 1],
        )) {
      suffix++;
    }
    final labels = {
      for (var i = 0; i < words.length; i++)
        titles.keys.elementAt(i): words[i]
            .sublist(prefix, words[i].length - suffix)
            .join(' '),
    };
    return labels.values.toSet().length == labels.length ? labels : titles;
  }
}

final class _Variant {
  final Map<String, String> parameters;
  final String encoded, title;
  final NativeIntentIdentity identity;
  const _Variant(this.parameters, this.encoded, this.title, this.identity);
}

final class _Binding {
  final List<_Variant> variants;
  final NativeIntentIdentity routing;
  final int bytes;
  const _Binding(this.variants, this.routing, this.bytes);
}

bool _same(Map<String, String> a, Map<String, String> b) =>
    a.length == b.length && a.entries.every((e) => b[e.key] == e.value);
