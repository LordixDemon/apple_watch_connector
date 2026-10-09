import 'native_face_monogram.dart';

/// Native slot order, presentation and availability, independent of transport.
/// Rules are derived from the full native customization cross product, not a
/// family-specific switch. Changing style never removes stored complications.
final class NativeComplicationLayout {
  final List<String> order;
  final Set<String> excluded, _constant;
  final Map<String, Map<String, String>> _labels;
  final Map<String, Set<String>> _domains;
  final Map<String, Map<String, Set<String>>> _rules;
  final NativeFaceMonogram? monogram;

  const NativeComplicationLayout._(
    this.order,
    this.excluded,
    this._constant,
    this._labels,
    this._domains,
    this._rules,
    this.monogram,
  );

  factory NativeComplicationLayout.fromJson(
    Map<String, dynamic> json,
    Map<String, List<int>> slotFamilies,
    Map<String, List<String>> options,
  ) {
    List<String> slots(Object? value) {
      if (value is! List ||
          value.length > 32 ||
          value.any(
            (slot) => slot is! String || !slotFamilies.containsKey(slot),
          ) ||
          value.toSet().length != value.length) {
        throw const FormatException('Invalid native complication slots.');
      }
      return List<String>.unmodifiable(value);
    }

    final order = slots(json['order']);
    if (order.length != slotFamilies.length) {
      throw const FormatException('Incomplete native complication order.');
    }
    final excluded = Set<String>.unmodifiable(slots(json['excluded']));
    final constant = Set<String>.unmodifiable(slots(json['constant']));
    final labels = <String, Map<String, String>>{};
    final rawLabels = json['labels'];
    if (rawLabels is! Map || rawLabels.length > 32) {
      throw const FormatException('Invalid native complication labels.');
    }
    for (final entry in rawLabels.entries) {
      final value = entry.value;
      if (!slotFamilies.containsKey(entry.key) ||
          value is! Map ||
          value.length > 32 ||
          value.entries.any(
            (e) =>
                e.key is! String ||
                e.value is! String ||
                (e.value as String).isEmpty ||
                (e.value as String).length > 256,
          )) {
        throw const FormatException('Invalid native complication title.');
      }
      labels[entry.key as String] = Map<String, String>.unmodifiable(
        value.cast<String, String>(),
      );
    }
    final rawDomains = json['domains'], rawRules = json['rules'];
    if (rawDomains is! Map ||
        rawDomains.length > 64 ||
        rawRules is! Map ||
        rawRules.length > 64) {
      throw const FormatException('Invalid native complication rules.');
    }
    final domains = <String, Set<String>>{};
    for (final entry in rawDomains.entries) {
      final values = entry.value;
      if (!options.containsKey(entry.key) ||
          values is! List ||
          values.isEmpty ||
          values.length > 512 ||
          values.any((v) => v is! String || v.length > 256) ||
          values.toSet().length != values.length ||
          !values.toSet().containsAll(options[entry.key]!)) {
        throw const FormatException('Invalid native complication domain.');
      }
      domains[entry.key as String] = Set<String>.unmodifiable(
        values.cast<String>(),
      );
    }
    if (domains.length != options.length) {
      throw const FormatException('Incomplete native complication domains.');
    }
    final rules = <String, Map<String, Set<String>>>{};
    for (final entry in rawRules.entries) {
      final values = entry.value, domain = domains[entry.key];
      if (domain == null ||
          values is! Map ||
          values.length != domain.length ||
          !values.keys.toSet().containsAll(domain)) {
        throw const FormatException('Invalid native complication rule values.');
      }
      rules[entry.key as String] = Map<String, Set<String>>.unmodifiable({
        for (final value in values.entries)
          value.key as String: Set<String>.unmodifiable(slots(value.value)),
      });
    }
    return NativeComplicationLayout._(
      order,
      excluded,
      constant,
      Map.unmodifiable(labels),
      Map.unmodifiable(domains),
      Map.unmodifiable(rules),
      json['monogram'] == null
          ? null
          : NativeFaceMonogram.fromJson(
              Map<String, dynamic>.from(json['monogram'] as Map),
              excluded,
            ),
    );
  }

  String? title(String slot, String locale) {
    final labels = _labels[slot];
    return labels?[locale] ??
        labels?[locale.split(RegExp('[-_]')).first] ??
        labels?['en'];
  }

  /// Unknown future native tokens retain access; they are not guessed layouts.
  Set<String>? unavailable(Object? customization) {
    if (customization is! Map) return null;
    for (final entry in _domains.entries) {
      if (!entry.value.contains(customization[entry.key])) return null;
    }
    return Set<String>.unmodifiable({
      ..._constant,
      for (final entry in _rules.entries)
        ...entry.value[customization[entry.key]]!,
    });
  }
}
