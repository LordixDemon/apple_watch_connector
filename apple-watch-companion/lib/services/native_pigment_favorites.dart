import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'native_face_pigment.dart';
import '../controllers/native_pigment_sync_controller.dart';

/// Shared palette facade. Production uses pair-scoped durable manual intent and
/// native observations; the local constructor supports standalone catalog tools.
/// Visible pending selections never constitute Watch application proof.
final class NativePigmentFavorites extends ChangeNotifier {
  final Future<void> Function(String) _persist;
  final NativePigmentSyncController? _sync;
  Map<String, bool> _overrides;
  Future<void> _pending = Future.value();
  bool _disposed = false;
  NativePigmentFavorites({
    String? saved,
    required Future<void> Function(String) persist,
  }) : _persist = persist,
       _sync = null,
       _overrides = _decode(saved);

  /// Production facade: selections belong to a paired Watch, not legacy global v1 flags.
  NativePigmentFavorites.synchronized(NativePigmentSyncController sync)
    : _sync = sync,
      _persist = _unusedPersist,
      _overrides = {} {
    sync.addListener(_syncChanged);
  }
  static Future<void> _unusedPersist(String _) async {}
  void _syncChanged() {
    if (!_disposed) notifyListeners();
  }

  String? get pair => _sync?.pair;
  bool get canEdit => !_disposed && (_sync == null || pair != null);
  PigmentSyncState get syncState => _sync?.state ?? PigmentSyncState.idle;
  bool get canRetry => _sync?.canRetry ?? false;
  Future<bool> retrySync() => _sync?.retry() ?? Future.value(false);

  static Map<String, bool> _decode(String? saved) {
    if (saved == null || saved.length > 1024 * 1024) return {};
    try {
      final data = jsonDecode(saved);
      if (data is! Map || data['version'] != 1 || data['overrides'] is! Map) {
        return {};
      }
      final values = data['overrides'] as Map;
      if (!_valid(values)) return {};
      return Map<String, bool>.from(values);
    } on FormatException {
      return {};
    }
  }

  static bool _valid(Map values) =>
      values.length <= 4096 &&
      values.entries.every(
        (e) =>
            e.key is String &&
            (e.key as String).isNotEmpty &&
            (e.key as String).length <= 256 &&
            !(e.key as String).contains(':') &&
            e.value is bool,
      );

  bool isVisible(NativePigmentChoice choice) =>
      !choice.addable ||
      (_sync?.visible(choice.name, choice.automatic) ??
          _overrides[choice.name] ??
          choice.automatic);

  List<String> visibleOptions(
    NativeFacePigmentSection section, {
    String? selected,
  }) {
    final active = selected == null ? null : section.optionFor(selected);
    final visible = [
      for (final token in section.options.keys)
        if (token == active ||
            section.choices[token] == null ||
            isVisible(section.choices[token]!))
          token,
    ];
    // Native supportsSlider groups precede fixed colors; isAddable controls only
    // visibility. Keep each group's original collection order.
    return [
      ...visible.where(section.editable.contains),
      ...visible.where((token) => !section.editable.contains(token)),
    ];
  }

  /// Publish only after a successful, ordered write; failed writes remain retryable.
  Future<bool> update(Map<String, bool> changes, {bool Function()? canCommit}) {
    if (_sync != null) return _sync.update(changes, canCommit: canCommit);
    if (!_valid(changes)) {
      return Future.error(
        const FormatException('Invalid pigment preferences.'),
      );
    }
    final delta = Map<String, bool>.of(changes);
    final operation = _pending.then((_) async {
      if (_disposed || canCommit?.call() == false) return false;
      final next = {..._overrides, ...delta};
      if (!_valid(next)) {
        throw const FormatException('Pigment preference limit exceeded.');
      }
      if (mapEquals(next, _overrides)) return false;
      await _persist(jsonEncode({'version': 1, 'overrides': next}));
      if (_disposed) return false;
      _overrides = next;
      notifyListeners();
      return true;
    });
    _pending = operation.then<void>((_) {}, onError: (Object _) {});
    return operation;
  }

  @override
  void dispose() {
    _disposed = true;
    _sync?.removeListener(_syncChanged);
    super.dispose();
  }
}
