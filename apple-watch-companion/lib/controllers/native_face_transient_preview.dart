import 'package:flutter/foundation.dart';

/// Visual-only shade state. Never stages edits or copies opaque native intents.
/// Owners bind during their normal build; only the preview listens during drag.
class NativeFaceTransientPreview extends ChangeNotifier {
  Map<String, dynamic>? _source, _preview;
  Object? _scope;
  Map<String, dynamic>? _originalCustomization;
  String? _field;
  bool _enabled = false;

  Map<String, dynamic>? get configuration => _preview ?? _source;

  void bind(
    Map<String, dynamic>? source,
    Object? scope, {
    required bool enabled,
  }) {
    final customization = source?['customization'];
    if (!enabled ||
        !identical(source, _source) ||
        scope != _scope ||
        _field != null &&
            (customization is! Map<String, dynamic> ||
                !mapEquals(customization, _originalCustomization))) {
      _preview = null;
      _field = null;
    }
    _source = source;
    _scope = scope;
    _enabled = enabled;
  }

  void show(String field, String token) {
    final source = _source, customization = _source?['customization'];
    if (!_enabled || source == null || customization is! Map<String, dynamic>) {
      return;
    }
    if (_field == field &&
        (_preview?['customization'] as Map?)?[field] == token) {
      return;
    }
    _field = field;
    _originalCustomization = Map.of(customization);
    _preview = Map<String, dynamic>.unmodifiable({
      ...source,
      'customization': Map<String, dynamic>.unmodifiable({
        ...customization,
        field: token,
      }),
    });
    notifyListeners();
  }

  void clear() {
    if (_preview == null) return;
    _preview = null;
    _field = null;
    notifyListeners();
  }
}
