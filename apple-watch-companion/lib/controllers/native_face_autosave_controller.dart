import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import '../services/native_watch_face_archive.dart';
import '../services/native_face_edit_metadata.dart';
import 'native_face_controller.dart';

enum NativeFaceSaveState {
  pending,
  sending,
  failed,
  uncertain,
  conflict,
  storageFailure,
}

/// Durable edits outlive the editor route. Only its correlated native operation
/// establishes application; a saved draft, queue receipt or ACK does not.
class NativeFaceAutosaveController extends ChangeNotifier {
  static const _maxSavedBytes = 1024 * 1024;
  // Failure-state names can be longer than pending/sending. Leave space so a
  // valid staged document remains readable after all 64 entries change phase.
  static const _phaseReserve = 1024;
  final NativeFaceController faces;
  final Future<void> Function(String) persist;
  final Map<String, NativeFaceEdit> _edits = {};
  final Map<String, String> _durable = {};
  Future<void> _writes = Future.value();
  bool _driving = false, _disposed = false;
  String? _activeKey;

  NativeFaceAutosaveController({
    required this.faces,
    required this.persist,
    String? saved,
  }) {
    _load(saved);
    faces.addListener(_observe);
  }

  static String _key(String pair, String face) => '$pair/$face';
  NativeFaceEdit? edit(String pair, String face) => _edits[_key(pair, face)];
  bool canLeave(String pair, String face) {
    final value = edit(pair, face);
    return value == null || _durable[_key(pair, face)] == value.desired;
  }

  bool canEdit(String pair, String epoch, String face) =>
      !_disposed &&
      faces.collection.connected &&
      faces.collection.complete &&
      faces.collection.pair == pair &&
      faces.collection.epoch == epoch &&
      faces.collection.face(face)?.configuration != null &&
      (edit(pair, face) == null || edit(pair, face)!.epoch == epoch);

  /// The expected draft prevents an old modal/editor from overwriting newer edits.
  Future<bool> stage(
    String pair,
    String epoch,
    String face,
    String expected,
    String desired,
  ) async {
    if (!canEdit(pair, epoch, face)) return false;
    final key = _key(pair, face), old = _edits[key];
    final base =
        old?.desired ?? faces.collection.face(face)!.configurationJson!;
    Map<String, dynamic> current, next;
    try {
      if (!_equal(base, expected)) return false;
      current = _configuration(base);
      next = _configuration(desired);
    } on FormatException {
      return false;
    }
    if (NativeWatchFaceArchive.familyIdentity(current) !=
        NativeWatchFaceArchive.familyIdentity(next)) {
      return false;
    }
    if (_equal(base, desired)) return true;
    if (old == null && _edits.length >= 64) return false;
    final value = NativeFaceEdit(
      pair,
      epoch,
      face,
      old?.original ?? base,
      desired,
      old?.state ?? NativeFaceSaveState.pending,
    );
    final encoded = _encoded({..._edits, key: value});
    if (utf8.encode(encoded).length > _maxSavedBytes - _phaseReserve) {
      return false;
    }
    _edits[key] = value;
    _notify();
    final saved = await _save();
    if (saved) _wake();
    return saved;
  }

  /// An uncertain operation is never retried by a timer, reconnect or restart.
  Future<bool> retry(String pair, String epoch, String face) async {
    final key = _key(pair, face), old = _edits[key];
    final source = faces.collection;
    if (_disposed ||
        old == null ||
        key == _activeKey ||
        old.state == NativeFaceSaveState.sending ||
        !source.connected ||
        !source.complete ||
        source.pair != pair ||
        source.epoch != epoch) {
      return false;
    }
    final current = source.face(face)?.configurationJson;
    if (current == null ||
        NativeWatchFaceArchive.familyIdentity(_configuration(current)) !=
            NativeWatchFaceArchive.familyIdentity(
              _configuration(old.original),
            )) {
      return false;
    }
    _edits[key] = NativeFaceEdit(
      pair,
      epoch,
      face,
      current,
      jsonEncode(
        _rebaseConfiguration(
          _configuration(old.original),
          _configuration(old.desired),
          _configuration(current),
        ),
      ),
      NativeFaceSaveState.pending,
    );
    _notify();
    final saved = await _save();
    if (saved) _wake();
    return saved;
  }

  void _observe() {
    if (_disposed) return;
    final source = faces.collection;
    var changed = false;
    for (final key in _edits.keys.toList()) {
      final edit = _edits[key]!;
      if (edit.state == NativeFaceSaveState.pending &&
          (!source.connected ||
              edit.pair != source.pair ||
              edit.epoch != source.epoch)) {
        _edits[key] = edit.withState(NativeFaceSaveState.conflict);
        changed = true;
      }
    }
    if (changed) {
      _notify();
      unawaited(_save());
    }
    _wake();
  }

  void _wake() {
    if (_disposed || _driving) return;
    _driving = true;
    scheduleMicrotask(() async {
      try {
        await _drive();
      } catch (_) {
        final key = _activeKey;
        if (key != null && _edits[key] != null) {
          _edits[key] = _edits[key]!.withState(NativeFaceSaveState.uncertain);
          _notify();
          await _save();
        }
      } finally {
        _activeKey = null;
        _driving = false;
        // A stage/write or writer-release notification can arrive while the
        // previous drive is finishing. Do not lose that wakeup.
        if (!_disposed &&
            faces.canMutate &&
            _edits.values.any(
              (e) =>
                  e.state == NativeFaceSaveState.pending &&
                  e.pair == faces.collection.pair &&
                  e.epoch == faces.collection.epoch,
            )) {
          _wake();
        }
      }
    });
  }

  Future<void> _drive() async {
    while (!_disposed && faces.canMutate) {
      final source = faces.collection;
      final queued = _edits.entries
          .where(
            (e) =>
                e.value.state == NativeFaceSaveState.pending &&
                e.value.pair == source.pair &&
                e.value.epoch == source.epoch,
          )
          .firstOrNull;
      if (queued == null) return;
      final key = queued.key, sent = queued.value;
      _activeKey = key;
      if (source.face(sent.face)?.configurationJson != sent.original) {
        _edits[key] = sent.withState(NativeFaceSaveState.conflict);
        _notify();
        await _save();
        continue;
      }
      _edits[key] = sent.withState(NativeFaceSaveState.sending);
      _notify();
      if (!await _save() || _disposed) return;
      // Recheck after storage: an external operation can own the writer now.
      if (faces.collection.pair != sent.pair ||
          faces.collection.epoch != sent.epoch ||
          faces.collection.face(sent.face)?.configurationJson !=
              sent.original) {
        _edits[key] = _edits[key]!.withState(NativeFaceSaveState.conflict);
        _notify();
        await _save();
        continue;
      }
      if (!faces.canMutate) {
        _edits[key] = _edits[key]!.withState(NativeFaceSaveState.pending);
        _notify();
        await _save();
        return;
      }
      bool applied;
      try {
        applied = await faces.update(
          sent.face,
          sent.desired,
          originalJson: sent.original,
          pair: sent.pair,
          epoch: sent.epoch,
        );
      } catch (_) {
        applied = false;
      }
      if (_disposed) return;
      final latest = _edits[key]!;
      final observed = faces.collection;
      final actual = observed.face(sent.face)?.configurationJson;
      if (applied &&
          actual != null &&
          observed.pair == sent.pair &&
          observed.epoch == sent.epoch) {
        if (_equal(sent.desired, latest.desired)) {
          _edits.remove(key);
        } else {
          // Preserve normalization/opaque peer fields, applying only edits made
          // after this submission over its proven native readback.
          _edits[key] = NativeFaceEdit(
            sent.pair,
            sent.epoch,
            sent.face,
            actual,
            jsonEncode(
              _rebaseConfiguration(
                _configuration(sent.desired),
                _configuration(latest.desired),
                _configuration(actual),
              ),
            ),
            NativeFaceSaveState.pending,
          );
        }
      } else {
        final state = observed.pair != sent.pair || observed.epoch != sent.epoch
            ? NativeFaceSaveState.conflict
            : switch (faces.result) {
                NativeFaceResult.rejected => NativeFaceSaveState.failed,
                NativeFaceResult.conflict ||
                NativeFaceResult.connectionChanged =>
                  NativeFaceSaveState.conflict,
                _ => NativeFaceSaveState.uncertain,
              };
        _edits[key] = latest.withState(state);
      }
      _notify();
      if (!await _save()) return;
      _activeKey = null;
    }
  }

  Future<bool> _save() {
    final result = Completer<bool>();
    _writes = _writes.then((_) async {
      final snapshot = Map<String, NativeFaceEdit>.of(_edits);
      try {
        await persist(_encoded(snapshot));
        _durable
          ..clear()
          ..addAll(snapshot.map((k, v) => MapEntry(k, v.desired)));
        result.complete(true);
      } catch (_) {
        for (final key in _edits.keys.toList()) {
          _edits[key] = _edits[key]!.withState(
            NativeFaceSaveState.storageFailure,
          );
        }
        result.complete(false);
      }
      _notify();
    });
    return result.future;
  }

  static Map<String, dynamic> _configuration(String value) =>
      NativeWatchFaceArchive.decodeConfiguration(
        Uint8List.fromList(utf8.encode(value)),
      );
  static bool _equal(String a, String b) =>
      NativeFaceController.structurallyEqual(
        _configuration(a),
        _configuration(b),
      );
  static String _encoded(Map<String, NativeFaceEdit> edits) => jsonEncode({
    'version': 1,
    'edits': edits.values.map((e) => e.json).toList(),
  });

  static Map<String, dynamic> _rebaseConfiguration(
    Map<String, dynamic> original,
    Map<String, dynamic> desired,
    Map<String, dynamic> observed,
  ) {
    final result = _rebase(original, desired, observed);
    NativeFaceEditMetadata.reconcileRebase(original, desired, observed, result);
    return result;
  }

  static Map<String, dynamic> _rebase(
    Map<String, dynamic> sent,
    Map<String, dynamic> desired,
    Map<String, dynamic> actual,
  ) {
    final result = Map<String, dynamic>.from(actual);
    for (final key in {...sent.keys, ...desired.keys}) {
      if (sent.containsKey(key) == desired.containsKey(key) &&
          NativeFaceController.structurallyEqual(sent[key], desired[key])) {
        continue;
      }
      if (!desired.containsKey(key)) {
        result.remove(key);
      } else if (sent[key] is Map<String, dynamic> &&
          desired[key] is Map<String, dynamic> &&
          actual[key] is Map<String, dynamic>) {
        result[key] = _rebase(
          sent[key] as Map<String, dynamic>,
          desired[key] as Map<String, dynamic>,
          actual[key] as Map<String, dynamic>,
        );
      } else {
        result[key] = desired[key];
      }
    }
    return result;
  }

  void _load(String? saved) {
    if (saved == null ||
        saved.length > _maxSavedBytes ||
        utf8.encode(saved).length > _maxSavedBytes) {
      return;
    }
    try {
      final root = jsonDecode(saved);
      if (root is! Map ||
          root['version'] != 1 ||
          root['edits'] is! List ||
          (root['edits'] as List).length > 64) {
        return;
      }
      final loaded = <String, NativeFaceEdit>{};
      for (final item in root['edits'] as List) {
        if (item is! Map) return;
        final pair = item['pair'],
            epoch = item['epoch'],
            face = item['face'],
            original = item['original'],
            desired = item['desired'];
        if ([
              pair,
              epoch,
              face,
            ].any((v) => v is! String || !_uuid.hasMatch(v)) ||
            original is! String ||
            desired is! String ||
            NativeWatchFaceArchive.familyIdentity(_configuration(original)) !=
                NativeWatchFaceArchive.familyIdentity(
                  _configuration(desired),
                )) {
          return;
        }
        final key = _key(pair as String, face as String);
        if (loaded.containsKey(key)) return;
        // Process death loses the request's live proof. Even a previously staged
        // edit requires explicit retry; never infer non-delivery from saved state.
        loaded[key] = NativeFaceEdit(
          pair,
          epoch as String,
          face,
          original,
          desired,
          NativeFaceSaveState.uncertain,
        );
      }
      _edits.addAll(loaded);
      _durable.addAll(loaded.map((k, v) => MapEntry(k, v.desired)));
    } catch (_) {
      /* Malformed saved data is never a transport instruction. */
    }
  }

  static final _uuid = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  );
  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    faces.removeListener(_observe);
    super.dispose();
  }
}

@immutable
class NativeFaceEdit {
  final String pair, epoch, face, original, desired;
  final NativeFaceSaveState state;
  const NativeFaceEdit(
    this.pair,
    this.epoch,
    this.face,
    this.original,
    this.desired,
    this.state,
  );
  NativeFaceEdit withState(NativeFaceSaveState next) =>
      NativeFaceEdit(pair, epoch, face, original, desired, next);
  Map<String, dynamic> get json => {
    'pair': pair,
    'epoch': epoch,
    'face': face,
    'original': original,
    'desired': desired,
    'state': state.name,
  };
}
