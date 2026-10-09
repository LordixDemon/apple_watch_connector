/// The native monogram complication is independent of its global NPS text.
/// watchOS 23S303: type 14 serializes as app=monogram; null removes the slot.
final class NativeFaceMonogram {
  final String slot;
  final Map<String, dynamic> _enabled;
  const NativeFaceMonogram._(this.slot, this._enabled);

  factory NativeFaceMonogram.fromJson(
    Map<String, dynamic> json,
    Set<String> excluded,
  ) {
    final slot = json['slot'], enabled = json['enabled'];
    if (slot is! String ||
        !excluded.contains(slot) ||
        enabled is! Map ||
        enabled.length != 1 ||
        enabled['app'] != 'monogram') {
      throw const FormatException('Invalid native monogram configuration.');
    }
    return NativeFaceMonogram._(
      slot,
      Map<String, dynamic>.unmodifiable(enabled.cast<String, dynamic>()),
    );
  }

  /// A future opaque complication is retained until an explicit toggle.
  /// Malformed sections cannot be edited through the switch.
  bool? value(Map<String, dynamic> configuration) {
    final section = configuration['complications'];
    if (section == null) return false;
    if (section is! Map<String, dynamic>) return null;
    final current = section[slot];
    if (current == null) return false;
    return current is Map &&
            current['app'] is String &&
            (current['app'] as String).isNotEmpty
        ? true
        : null;
  }

  bool select(Map<String, dynamic> configuration, bool enabled) {
    final previous = value(configuration);
    if (previous == null || previous == enabled) return false;
    final section = Map<String, dynamic>.from(
      configuration['complications'] as Map<String, dynamic>? ?? const {},
    );
    if (enabled) {
      section[slot] = Map<String, dynamic>.from(_enabled);
    } else {
      section.remove(slot);
    }
    if (section.isEmpty) {
      configuration.remove('complications');
    } else {
      configuration['complications'] = section;
    }
    return true;
  }
}
