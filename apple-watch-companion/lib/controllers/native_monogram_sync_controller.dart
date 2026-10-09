import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import '../models/native_monogram_mirror.dart';
import '../models/native_monogram_preferences.dart';
import '../services/native_monogram_text_rules.dart';

typedef MonogramReceipt = ({String status, String? requestId});
typedef MonogramSender =
    Future<MonogramReceipt> Function(
      String text,
      NativeMonogramMirror baseline,
    );

enum MonogramSyncState {
  idle,
  awaitingDelivery,
  delivered,
  uncertain,
  confirmed,
  storageError,
}

/// Persist manual intent before transport. Reopening never replays a write.
/// A receipt proves delivery only; a current owned native echo proves preference state.
final class NativeMonogramSyncController extends ChangeNotifier {
  final Future<void> Function(String) _persist;
  final MonogramSender _send;
  final int Function() _now;
  Map<String, _Intent> _intents;
  NativeMonogramMirror? _baseline;
  NativeMonogramPreferences _observation =
      const NativeMonogramPreferences.unknown();
  Future<void> _work = Future.value();
  bool _disposed = false, _storageError = false;

  NativeMonogramSyncController({
    String? saved,
    required Future<void> Function(String) persist,
    required MonogramSender send,
    int Function()? now,
  }) : _persist = persist,
       _send = send,
       _now = now ?? (() => DateTime.now().millisecondsSinceEpoch),
       _intents = _decode(saved);

  String? get text {
    final intent = _intents[_baseline?.pair];
    return intent != null && intent.state != MonogramSyncState.confirmed
        ? intent.text
        : _baseline?.text ?? _observation.text;
  }

  MonogramSyncState get state {
    if (_storageError) return MonogramSyncState.storageError;
    final intent = _intents[_baseline?.pair];
    if (intent == null) return MonogramSyncState.idle;
    if (_baseline?.epoch != intent.epoch) return MonogramSyncState.uncertain;
    if (intent.state == MonogramSyncState.confirmed &&
        _baseline?.text != intent.text) {
      return MonogramSyncState.idle;
    }
    return intent.state;
  }

  void observe(
    NativeMonogramMirror? baseline,
    NativeMonogramPreferences observation,
  ) {
    if (_disposed) return;
    final unchanged =
        (baseline == null && _baseline == null ||
            baseline != null &&
                _baseline != null &&
                baseline.sameBaseline(_baseline!)) &&
        observation.pair == _observation.pair &&
        observation.epoch == _observation.epoch &&
        observation.received == _observation.received &&
        observation.text == _observation.text &&
        observation.sourceTimestamp == _observation.sourceTimestamp &&
        observation.observedAt == _observation.observedAt;
    if (unchanged) return;
    _baseline = baseline;
    _observation = observation;
    notifyListeners();
    unawaited(
      _enqueue(() async {
        final mirror = _baseline, native = _observation;
        final intent = _intents[mirror?.pair];
        if (_disposed ||
            mirror == null ||
            intent == null ||
            intent.state == MonogramSyncState.confirmed ||
            !native.received ||
            native.pair != mirror.pair ||
            native.epoch != intent.epoch ||
            native.epoch != mirror.epoch ||
            native.text != intent.text ||
            native.observedAt! < intent.at ||
            native.sourceTimestamp! < intent.minimumSource ||
            !mirror.canWrite(_now()) ||
            mirror.origin != 'REMOTE' ||
            mirror.text != native.text ||
            mirror.sourceTimestamp != native.sourceTimestamp) {
          return;
        }
        // An old matching value is not an echo: every write advances its baseline clock.
        if (intent.previousSource != null &&
            native.sourceTimestamp! <= intent.previousSource!) {
          return;
        }
        await _commit({
          ..._intents,
          mirror.pair: intent.copy(state: MonogramSyncState.confirmed),
        });
      }).catchError((Object _) {}),
    );
  }

  Future<MonogramReceipt> update(String text, NativeMonogramMirror baseline) =>
      _enqueue(() async {
        if (_disposed ||
            !NativeMonogramTextRules.valid(text) ||
            !_current(baseline)) {
          return (status: 'REJECTED', requestId: null);
        }
        if (!_intents.containsKey(baseline.pair) && _intents.length >= 8) {
          throw StateError('Too many pending Watch targets.');
        }
        final at = _now();
        final intent = _Intent(
          text,
          baseline.epoch,
          at,
          at / 1000 - 978307200,
          baseline.sourceTimestamp,
          null,
          MonogramSyncState.uncertain,
        );
        await _commit({..._intents, baseline.pair: intent});
        if (_disposed || !_current(baseline)) {
          return (status: 'REJECTED', requestId: null);
        }
        MonogramReceipt receipt;
        try {
          receipt = await _send(
            text,
            baseline,
          ).timeout(const Duration(seconds: 15));
        } catch (_) {
          receipt = (status: 'UNKNOWN', requestId: null);
        }
        if (_disposed) return receipt;
        final queued =
            receipt.status == 'QUEUED' &&
            _uuid.hasMatch(receipt.requestId ?? '');
        await _commit({
          ..._intents,
          baseline.pair: intent.copy(
            request: queued ? receipt.requestId : null,
            state: queued
                ? MonogramSyncState.awaitingDelivery
                : MonogramSyncState.uncertain,
          ),
        });
        // Reconcile a native observation that arrived before the queue receipt.
        observe(_baseline, _observation);
        return receipt;
      });

  bool _current(NativeMonogramMirror baseline) =>
      _baseline != null &&
      baseline.sameBaseline(_baseline!) &&
      baseline.canWrite(_now());

  void operation(Map<String, dynamic> event) {
    if (_disposed) return;
    final pair = _baseline?.pair;
    // Serialize behind submission to retain ACKs arriving before queue receipts.
    unawaited(
      _enqueue(() async {
        final intent = _intents[pair];
        if (_disposed ||
            pair == null ||
            pair != _baseline?.pair ||
            intent == null ||
            intent.state == MonogramSyncState.confirmed ||
            intent.request == null ||
            event['requestId'] != intent.request ||
            event['epoch'] != intent.epoch ||
            _baseline?.epoch != intent.epoch) {
          return;
        }
        final status = event['status'];
        if (intent.state == MonogramSyncState.delivered) return;
        if (!const {
          'APP_ACK_RECEIVED',
          'FAILED',
          'EXPIRED',
          'UNKNOWN',
          'REJECTED',
        }.contains(status)) {
          return;
        }
        await _commit({
          ..._intents,
          pair: intent.copy(
            state: status == 'APP_ACK_RECEIVED'
                ? MonogramSyncState.delivered
                : MonogramSyncState.uncertain,
          ),
        });
      }).catchError((Object _) {}),
    );
  }

  Future<T> _enqueue<T>(Future<T> Function() action) {
    final result = _work.then((_) => action());
    _work = result.then<void>((_) {}, onError: (Object _) {});
    return result;
  }

  Future<void> _commit(Map<String, _Intent> next) async {
    try {
      await _persist(
        jsonEncode({
          'version': 1,
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
  static Map<String, _Intent> _decode(String? saved) {
    if (saved == null || saved.length > 8192) return {};
    try {
      final data = jsonDecode(saved);
      if (data is! Map ||
          data['version'] != 1 ||
          data['pairs'] is! Map ||
          (data['pairs'] as Map).length > 8) {
        return {};
      }
      final result = <String, _Intent>{};
      for (final entry in (data['pairs'] as Map).entries) {
        final value = entry.value;
        if (entry.key is! String ||
            !_uuid.hasMatch(entry.key) ||
            value is! Map) {
          return {};
        }
        final text = value['text'],
            epoch = value['epoch'],
            at = value['at'],
            source = value['minimumSource'];
        final prior = value['previousSource'], request = value['request'];
        if (text is! String ||
            !NativeMonogramTextRules.valid(text) ||
            epoch is! String ||
            !_uuid.hasMatch(epoch) ||
            at is! int ||
            at <= 978307200000 ||
            at > 8640000000000000 ||
            source is! num ||
            !source.isFinite ||
            source < 0 ||
            source != at / 1000 - 978307200 ||
            prior != null && (prior is! num || !prior.isFinite || prior < 0) ||
            request != null &&
                (request is! String || !_uuid.hasMatch(request))) {
          return {};
        }
        final state = value['state'];
        if (state is! String ||
            !MonogramSyncState.values.any((s) => s.name == state)) {
          return {};
        }
        // A completed intent no longer overrides newer Watch preferences after restart.
        if (state == MonogramSyncState.confirmed.name) continue;
        // After a process death a saved delivery receipt cannot establish current native state.
        result[entry.key as String] = _Intent(
          text,
          epoch,
          at,
          source.toDouble(),
          (prior as num?)?.toDouble(),
          request as String?,
          MonogramSyncState.uncertain,
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
  final String text, epoch;
  final int at;
  final double minimumSource;
  final double? previousSource;
  final String? request;
  final MonogramSyncState state;
  const _Intent(
    this.text,
    this.epoch,
    this.at,
    this.minimumSource,
    this.previousSource,
    this.request,
    this.state,
  );
  _Intent copy({String? request, required MonogramSyncState state}) => _Intent(
    text,
    epoch,
    at,
    minimumSource,
    previousSource,
    request ?? this.request,
    state,
  );
  Map<String, Object?> get json => {
    'text': text,
    'epoch': epoch,
    'at': at,
    'minimumSource': minimumSource,
    'previousSource': previousSource,
    'request': request,
    'state': state.name,
  };
}
