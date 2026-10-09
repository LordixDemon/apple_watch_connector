import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import '../models/native_pigment_preferences.dart';
import '../models/native_pigment_baseline.dart';
import '../models/native_pigment_mirror.dart';

typedef PigmentReceipt = ({String status, String? requestId});
typedef PigmentSender =
    Future<PigmentReceipt> Function(
      Map<String, bool> changes, {
      required NativePigmentBaseline baseline,
    });

enum PigmentSyncState {
  idle,
  pending,
  awaitingDelivery,
  delivered,
  deliveryUncertain,
  uncertain,
  storageError,
}

/// Durable manual intent belongs to a pair. The HAL mirror is a local write baseline;
/// only genuine live Watch observations can confirm native preference state.
/// Persist an uncertain marker BEFORE invoking transport: a crash never triggers a replay.
final class NativePigmentSyncController extends ChangeNotifier {
  final Future<void> Function(String) _persist;
  final PigmentSender _send;
  final int Function() _now;
  Map<String, _Intent> _intents;
  Future<void> _work = Future.value();
  NativePigmentPreferences _observation =
      const NativePigmentPreferences.unknown();
  NativePigmentBaseline _baseline = const NativePigmentPreferences.unknown();
  Set<String>? _visibleNames;
  Set<String>? _observedNames;
  Set<String>? _automaticNames;
  String? _pair;
  bool _disposed = false, _storageError = false;

  NativePigmentSyncController({
    String? saved,
    required Future<void> Function(String) persist,
    required PigmentSender send,
    int Function()? now,
  }) : _persist = persist,
       _send = send,
       _now = now ?? (() => DateTime.now().millisecondsSinceEpoch),
       _intents = _decode(saved);

  String? get pair => _pair;

  /// IDS delivery is distinct from a matching Watch preference observation.
  /// Queue this behind submission so an ACK before its queue receipt is retained.
  void operation(Map<String, dynamic> event) {
    final target = _pair, epoch = event['epoch'], id = event['requestId'];
    final status = event['status'];
    if (_disposed ||
        target == null ||
        _observation.pair != target ||
        epoch != _observation.epoch ||
        !_validPair(epoch) ||
        !_validPair(id) ||
        !const {
          'APP_ACK_RECEIVED',
          'FAILED',
          'EXPIRED',
          'UNKNOWN',
          'REJECTED',
        }.contains(status)) {
      return;
    }
    unawaited(
      _enqueue(() async {
        final intent = _intents[target];
        if (_disposed ||
            intent == null ||
            intent.attemptAt == null ||
            intent.epoch != epoch ||
            intent.requestId != id ||
            intent.delivered) {
          return;
        }
        await _commit({
          ..._intents,
          target: _Intent(
            intent.changes,
            attemptAt: intent.attemptAt,
            sourceTimestamp: intent.sourceTimestamp,
            automaticTimestamp: intent.automaticTimestamp,
            epoch: intent.epoch,
            requestId: intent.requestId,
            submissionSuperseded: intent.submissionSuperseded,
            delivered:
                status == 'APP_ACK_RECEIVED' && !intent.submissionSuperseded,
            deliveryUncertain:
                status != 'APP_ACK_RECEIVED' || intent.submissionSuperseded,
          ),
        });
      }).catchError((Object _) {}),
    );
  }

  bool get canRetry {
    final intent = _intents[_pair];
    return !_disposed &&
        intent != null &&
        _fresh() &&
        (intent.attemptAt == null || _freshAfterAttempt(intent));
  }

  /// An explicit user action may reassert intent against a newer current readback.
  /// It cannot replay an uncertain command against the same pre-send observation.
  Future<bool> retry() {
    final target = _pair;
    return _enqueue(() async {
      if (target == null || target != _pair || !canRetry) return false;
      await _commit({..._intents, target: _Intent(_intents[target]!.changes)});
      if (!_disposed) {
        unawaited(_enqueue(() => _reconcile(target)).catchError((Object _) {}));
      }
      return !_disposed;
    });
  }

  PigmentSyncState get state {
    if (_storageError) return PigmentSyncState.storageError;
    final intent = _intents[_pair];
    if (intent == null) return PigmentSyncState.idle;
    if (intent.attemptAt != null &&
        _freshAfterAttempt(intent) &&
        !_matches(intent)) {
      return PigmentSyncState.uncertain;
    }
    if (intent.delivered) return PigmentSyncState.delivered;
    if (intent.deliveryUncertain) return PigmentSyncState.deliveryUncertain;
    return intent.attemptAt == null
        ? PigmentSyncState.pending
        : _freshAfterAttempt(intent)
        ? PigmentSyncState.uncertain
        : PigmentSyncState.awaitingDelivery;
  }

  bool visible(String name, bool fallback) =>
      _intents[_pair]?.changes[name] ??
      (_visibleNames?.contains(name) ?? fallback);

  /// The target is the paired identity from the same immutable Bridge publication.
  /// Losing it disables edits; retaining a disconnected paired identity allows offline intent.
  void observe(
    String? pair,
    NativePigmentPreferences observation, {
    NativePigmentBaseline? baseline,
  }) {
    if (_disposed) return;
    final nextPair = _validPair(pair) ? pair : null;
    final targetChanged = _pair != nextPair;
    if (targetChanged) _visibleNames = null;
    final nextBaseline = baseline ?? observation;
    final observationChanged = !_observation.sameObservation(observation);
    final same =
        !observationChanged && samePigmentBaseline(_baseline, nextBaseline);
    if (targetChanged || observationChanged) {
      // Keep live receipt membership separate from the displayed local mirror.
      // Reconcile every manual delta in O(changes), including large native lists.
      _observedNames = observation.known && observation.pair == nextPair
          ? observation.names!.toSet()
          : null;
    }
    _pair = nextPair;
    _observation = observation;
    _baseline = nextBaseline;
    if (targetChanged || !same) {
      _automaticNames =
          nextBaseline is NativePigmentMirror &&
              nextBaseline.pair == nextPair &&
              nextBaseline.automatic?.known == true
          ? nextBaseline.automatic!.names!.toSet()
          : null;
    }
    if ((targetChanged || !same) &&
        nextBaseline.known &&
        nextBaseline.pair == _pair) {
      _visibleNames = nextBaseline.names!.toSet();
    } else if (!same &&
        nextBaseline.connected &&
        nextBaseline.pair == _pair &&
        nextBaseline.sourceTimestamp != null &&
        !nextBaseline.known) {
      // A native deletion invalidates the previous palette. Transport loss alone
      // may retain it for display, but neither case supplies a write baseline.
      _visibleNames = null;
    }
    if (targetChanged || !same) notifyListeners();
    if (nextPair != null && (targetChanged || !same)) {
      unawaited(_enqueue(() => _reconcile(nextPair)).catchError((Object _) {}));
    }
  }

  Future<T> _enqueue<T>(Future<T> Function() action) {
    final operation = _work.then((_) => action());
    _work = operation.then<void>((_) {}, onError: (Object _) {});
    return operation;
  }

  /// Completion means intent is durable, never that the Watch applied it.
  Future<bool> update(Map<String, bool> changes, {bool Function()? canCommit}) {
    if (!_validChanges(changes)) {
      return Future.error(const FormatException('Invalid colors.'));
    }
    final target = _pair, delta = Map<String, bool>.unmodifiable(changes);
    return _enqueue(() async {
      if (_disposed ||
          target == null ||
          _pair != target ||
          canCommit?.call() == false) {
        return false;
      }
      final old = _intents[target];
      final merged = {...?old?.changes, ...delta};
      // A new manual edit may be saved while a prior submission is unresolved.
      // Keep its attempt marker until a newer readback permits explicit reassertion.
      final retainAttempt =
          old?.attemptAt != null &&
          !old!.delivered &&
          !_freshAfterAttempt(old) &&
          !(!mapEquals(old.changes, merged) && _canSupersedeLocally(old));
      if (!_validChanges(merged)) {
        throw const FormatException('Color limit exceeded.');
      }
      if (old != null &&
          old.attemptAt == null &&
          mapEquals(old.changes, merged)) {
        return false;
      }
      if (!_intents.containsKey(target) && _intents.length >= 8) {
        throw StateError('Too many pending Watch targets.');
      }
      await _commit({
        ..._intents,
        target: _Intent(
          merged,
          attemptAt: retainAttempt ? old.attemptAt : null,
          sourceTimestamp: retainAttempt ? old.sourceTimestamp : null,
          automaticTimestamp: retainAttempt ? old.automaticTimestamp : null,
          epoch: retainAttempt ? old.epoch : null,
          requestId: retainAttempt ? old.requestId : null,
          deliveryUncertain: retainAttempt && old.deliveryUncertain,
          submissionSuperseded:
              retainAttempt &&
              (old.submissionSuperseded || !mapEquals(old.changes, merged)),
        ),
      });
      // The durable transaction belongs to its captured pair even if selection changed
      // during the write. Reconciliation rechecks the current target before sending.
      if (!_disposed) {
        unawaited(_enqueue(() => _reconcile(target)).catchError((Object _) {}));
      }
      return !_disposed;
    });
  }

  bool _fresh() =>
      _baseline.pair == _pair &&
      _baseline.epoch != null &&
      _baseline.epoch == _observation.epoch &&
      _baseline.canWrite(_now());

  /// A new explicit edit can supersede a prepared local value with a strictly
  /// newer full-value write. This is neither automatic retry nor delivery proof.
  bool _canSupersedeLocally(_Intent intent) =>
      _fresh() &&
      _baseline is NativePigmentMirror &&
      (_baseline as NativePigmentMirror).origin == 'LOCAL' &&
      _baseline.observedAt! >= intent.attemptAt! &&
      _baseline.sourceTimestamp! > intent.sourceTimestamp!;

  bool _freshAfterAttempt(_Intent intent) =>
      _observation.pair == _pair &&
      _observation.epoch == _baseline.epoch &&
      _observation.canWrite(_now()) &&
      _observation.observedAt! >= intent.attemptAt! &&
      _observation.sourceTimestamp! > intent.sourceTimestamp! &&
      _automaticAfterAttempt(intent);

  bool _automaticAfterAttempt(_Intent intent) {
    if (intent.automaticTimestamp == null) {
      // A historical single-list attempt has no prior automatic timestamp. An
      // owned remote value still decides whether its manual choice is tracked.
      final mirror = _baseline;
      return mirror is! NativePigmentMirror ||
          mirror.automaticOrigin == 'REMOTE' && mirror.automatic?.known == true;
    }
    final mirror = _baseline;
    return mirror is NativePigmentMirror &&
        mirror.pair == _pair &&
        mirror.automaticOrigin == 'REMOTE' &&
        mirror.automatic?.known == true &&
        mirror.automatic!.observedAt! >= intent.attemptAt! &&
        mirror.automatic!.sourceTimestamp! > intent.automaticTimestamp!;
  }

  bool _matches(_Intent intent) =>
      _observedNames != null &&
      intent.changes.entries.every(
        (entry) => _observedNames!.contains(entry.key) == entry.value,
      ) &&
      (_baseline is NativePigmentMirror
          ? (_baseline as NativePigmentMirror).automaticOrigin == 'REMOTE' &&
                _automaticNames != null &&
                intent.changes.keys.every(
                  (name) => !_automaticNames!.contains(name),
                )
          : intent.automaticTimestamp == null);
  bool _matchesBaseline(_Intent intent) =>
      _baseline.known &&
      intent.changes.entries.every(
        (entry) => _visibleNames!.contains(entry.key) == entry.value,
      ) &&
      (_baseline is! NativePigmentMirror ||
          _automaticNames != null &&
              intent.changes.keys.every(
                (name) => !_automaticNames!.contains(name),
              ));

  Future<void> _reconcile(String target) async {
    if (_disposed || _pair != target || !_fresh()) return;
    final intent = _intents[target];
    if (intent == null) return;
    if (intent.attemptAt == null
        ? _matchesBaseline(intent)
        : _matches(intent) && _freshAfterAttempt(intent)) {
      await _commit({..._intents}..remove(target));
      return;
    }
    // Once submission could have occurred, observe; never replay automatically.
    if (intent.attemptAt != null) return;
    final baseline = _baseline;
    final attempt = _Intent(
      intent.changes,
      attemptAt: _now(),
      sourceTimestamp: baseline.sourceTimestamp,
      automaticTimestamp: baseline is NativePigmentMirror
          ? baseline.automatic?.sourceTimestamp
          : null,
      epoch: baseline.epoch,
    );
    await _commit({..._intents, target: attempt});
    if (_disposed ||
        _pair != target ||
        !_fresh() ||
        !samePigmentBaseline(_baseline, baseline)) {
      // No transport invocation occurred; retain a retryable pending intent.
      await _commit({..._intents, target: intent});
      return;
    }
    PigmentReceipt receipt;
    try {
      receipt = await _send(
        attempt.changes,
        baseline: baseline,
      ).timeout(const Duration(seconds: 15));
    } catch (_) {
      // A transport exception is ambiguous. The durable marker remains uncertain.
      await _markSubmission(target, attempt, deliveryUncertain: true);
      return;
    }
    if (_disposed) return;
    if (receipt.status == 'REJECTED') {
      // Explicit rejection proves this invocation was not queued. Do not retry
      // in this task; a future native publication or manual edit can try again.
      await _commit({..._intents, target: intent});
    } else {
      await _markSubmission(
        target,
        attempt,
        requestId: _validPair(receipt.requestId) ? receipt.requestId : null,
        deliveryUncertain:
            receipt.status != 'QUEUED' || !_validPair(receipt.requestId),
      );
    }
    // QUEUED/UNKNOWN/UNAVAILABLE are not application proof. The already queued
    // observation work will reconcile a readback that raced with the receipt.
  }

  Future<void> _markSubmission(
    String target,
    _Intent attempt, {
    String? requestId,
    required bool deliveryUncertain,
  }) => _commit({
    ..._intents,
    target: _Intent(
      attempt.changes,
      attemptAt: attempt.attemptAt,
      sourceTimestamp: attempt.sourceTimestamp,
      automaticTimestamp: attempt.automaticTimestamp,
      epoch: attempt.epoch,
      requestId: requestId,
      deliveryUncertain: deliveryUncertain,
    ),
  });

  Future<void> _commit(Map<String, _Intent> next) async {
    if (_disposed) return;
    try {
      await _persist(
        jsonEncode({
          'version': 2,
          'pairs': {
            for (final entry in next.entries) entry.key: entry.value.json,
          },
        }),
      );
    } catch (_) {
      if (!_disposed) {
        _storageError = true;
        notifyListeners();
      }
      rethrow;
    }
    if (_disposed) return;
    _intents = Map.unmodifiable(next);
    _storageError = false;
    notifyListeners();
  }

  static final _uuid = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  );
  static final _controls = RegExp(r'[\x00-\x1f\x7f-\x9f]');
  static bool _validPair(Object? pair) =>
      pair is String && _uuid.hasMatch(pair);
  static bool _validChanges(Map values) {
    var size = 0;
    return values.isNotEmpty &&
        values.length <= 1023 &&
        values.entries.every((e) {
          if (e.key is! String || e.value is! bool) return false;
          final name = e.key as String;
          size += name.length * 2;
          return name.isNotEmpty &&
              name.length <= 256 &&
              !name.contains(':') &&
              !_controls.hasMatch(name) &&
              size <= 32768;
        });
  }

  static Map<String, _Intent> _decode(String? saved) {
    if (saved == null || saved.length > 1024 * 1024) return {};
    try {
      final data = jsonDecode(saved);
      if (data is! Map || data['version'] != 2 || data['pairs'] is! Map) {
        return {};
      }
      final pairs = data['pairs'] as Map;
      if (pairs.length > 8) return {};
      final result = <String, _Intent>{};
      for (final entry in pairs.entries) {
        final value = entry.value;
        if (!_validPair(entry.key) ||
            value is! Map ||
            value['changes'] is! Map ||
            !_validChanges(value['changes'] as Map)) {
          return {};
        }
        final at = value['attemptAt'], source = value['sourceTimestamp'];
        final automatic = value['automaticTimestamp'];
        if ((at == null) != (source == null) ||
            (at != null &&
                (at is! int ||
                    at <= 0 ||
                    at > 8640000000000000 ||
                    source is! num ||
                    !source.isFinite ||
                    source < 0))) {
          return {};
        }
        if (automatic != null &&
            (at == null ||
                automatic is! num ||
                !automatic.isFinite ||
                automatic < 0)) {
          return {};
        }
        final epoch = value['epoch'], request = value['requestId'];
        final delivered = value['delivered'] ?? false;
        final uncertain = value['deliveryUncertain'] ?? false;
        final superseded = value['submissionSuperseded'] ?? false;
        if ((epoch != null && !_validPair(epoch)) ||
            (request != null && !_validPair(request)) ||
            delivered is! bool ||
            uncertain is! bool ||
            superseded is! bool ||
            (delivered && uncertain) ||
            (delivered && superseded) ||
            (at == null &&
                (epoch != null ||
                    request != null ||
                    delivered ||
                    uncertain ||
                    superseded)) ||
            (request != null && epoch == null) ||
            (delivered && request == null)) {
          return {};
        }
        result[entry.key as String] = _Intent(
          Map<String, bool>.from(value['changes'] as Map),
          attemptAt: at as int?,
          sourceTimestamp: (source as num?)?.toDouble(),
          automaticTimestamp: (automatic as num?)?.toDouble(),
          epoch: epoch as String?,
          requestId: request as String?,
          delivered: delivered,
          deliveryUncertain: uncertain,
          submissionSuperseded: superseded,
        );
      }
      return result;
    } on FormatException {
      return {};
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}

final class _Intent {
  final Map<String, bool> changes;
  final int? attemptAt;
  final double? sourceTimestamp;
  final double? automaticTimestamp;
  final String? epoch, requestId;
  final bool delivered, deliveryUncertain, submissionSuperseded;
  _Intent(
    Map<String, bool> changes, {
    this.attemptAt,
    this.sourceTimestamp,
    this.automaticTimestamp,
    this.epoch,
    this.requestId,
    this.delivered = false,
    this.deliveryUncertain = false,
    this.submissionSuperseded = false,
  }) : changes = Map.unmodifiable(changes);
  Map<String, Object?> get json => {
    'changes': changes,
    if (attemptAt != null) 'attemptAt': attemptAt,
    if (sourceTimestamp != null) 'sourceTimestamp': sourceTimestamp,
    if (automaticTimestamp != null) 'automaticTimestamp': automaticTimestamp,
    if (epoch != null) 'epoch': epoch,
    if (requestId != null) 'requestId': requestId,
    if (delivered) 'delivered': true,
    if (deliveryUncertain) 'deliveryUncertain': true,
    if (submissionSuperseded) 'submissionSuperseded': true,
  };
}
