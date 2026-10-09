import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import '../models/native_face_collection.dart';
import '../services/native_watch_face_archive.dart';
import '../services/watch_bridge_service.dart';

enum NativeFaceResult {
  idle,
  waiting,
  applied,
  rejected,
  unknown,
  connectionChanged,
  conflict,
}

typedef FaceReceipt = ({String status, String? faceId});

/// One writer across screens; only a fresh committed Watch readback establishes success.
class NativeFaceController extends ChangeNotifier {
  final WatchBridgeService _bridge;
  late final StreamSubscription<NativeFaceCollection> _subscription;
  late final StreamSubscription<Map<String, dynamic>> _operations;
  NativeFaceCollection _collection;
  NativeFaceResult _result = NativeFaceResult.idle;
  Completer<bool>? _pending;
  Timer? _deadline;
  bool Function(NativeFaceCollection)? _matches;
  NativeFaceCollection? _baseline;
  NativeFaceCollection? _lateRefreshBaseline;
  bool _receiptReceived = false;
  bool _disposed = false;
  bool _mutation = true;
  String? _request;
  bool _nativeVerified = false;
  bool _requireNativeProof = false;
  final Map<String, String> _earlyFailures = {};
  NativeFaceController(this._bridge) : _collection = _bridge.faceCollection {
    _subscription = _bridge.faceCollectionStream.listen(_observe);
    _operations = _bridge.notificationActionStream.listen(_operation);
  }
  NativeFaceCollection get collection => _collection;
  NativeFaceResult get result => _result;
  bool get busy => _pending != null;
  bool get lastOperationWasRefresh => !_mutation;
  bool get canMutate => !busy && _collection.connected && _collection.complete;

  bool _conflict() {
    if (!busy && !_disposed) {
      _mutation = true;
      _finish(NativeFaceResult.conflict);
    }
    return false;
  }

  void _operation(Map<String, dynamic> value) {
    if (!busy ||
        value['epoch'] != _baseline?.epoch ||
        value['requestId'] is! String) {
      return;
    }
    final status = value['status'];
    if (status == 'NATIVE_FACE_APPLIED' && _mutation) {
      final id = value['requestId'] as String;
      if (_request == id) {
        _nativeVerified = true;
        _evaluate();
      } else if (_request == null && _earlyFailures.length < 16) {
        _earlyFailures[id] = status as String;
      }
      return;
    }
    if (!const {'REJECTED', 'FAILED', 'UNKNOWN', 'EXPIRED'}.contains(status)) {
      return;
    }
    final id = value['requestId'] as String;
    if (_request == id) {
      _finish(
        status == 'UNKNOWN'
            ? NativeFaceResult.unknown
            : NativeFaceResult.rejected,
      );
    } else if (_request == null && _earlyFailures.length < 16) {
      _earlyFailures[id] = status as String;
    }
  }

  void _observe(NativeFaceCollection value) {
    _collection = value;
    final base = _baseline;
    if (_pending != null && base != null) {
      if (!value.connected ||
          value.pair != base.pair ||
          value.epoch != base.epoch) {
        _finish(NativeFaceResult.connectionChanged);
      } else {
        _evaluate();
      }
    }
    final late = _lateRefreshBaseline;
    if (!busy &&
        !_mutation &&
        _result == NativeFaceResult.unknown &&
        late != null &&
        value.complete &&
        value.connected &&
        value.pair == late.pair &&
        value.epoch == late.epoch &&
        (value.observedAt ?? 0) > (late.observedAt ?? 0)) {
      _lateRefreshBaseline = null;
      _result = NativeFaceResult.applied;
    }
    if (!_disposed) notifyListeners();
  }

  void _evaluate() {
    final base = _baseline;
    if (_pending != null &&
        _receiptReceived &&
        base != null &&
        _collection.complete &&
        (_collection.observedAt ?? 0) > (base.observedAt ?? 0) &&
        ((!_requireNativeProof && (_matches?.call(_collection) ?? false)) ||
            _mutation && _nativeVerified)) {
      _finish(NativeFaceResult.applied);
    }
  }

  void _finish(NativeFaceResult result) {
    _deadline?.cancel();
    _deadline = null;
    final pending = _pending;
    _pending = null;
    _baseline = null;
    _matches = null;
    _result = result;
    pending?.complete(result == NativeFaceResult.applied);
    if (!_disposed) notifyListeners();
  }

  Future<bool> refresh() => _run(
    () async => (status: await _bridge.refreshFaceCollection(), faceId: null),
    (_) =>
        (_) => true,
    mutation: false,
  );
  Future<bool> select(String id) => _run(
    () => _bridge.selectNativeFace(id),
    (_) =>
        (v) => v.selected == id,
  );
  Future<bool> duplicate(String source) {
    final config = _collection.face(source)?.configuration;
    final order = [..._collection.ordered];
    return _run(
      () => _bridge.duplicateNativeFace(source),
      (id) =>
          (v) =>
              id != null &&
              v.selected == id &&
              listEquals([...order, id], v.ordered) &&
              config != null &&
              structurallyEqual(config, v.face(id)?.configuration),
      timeoutSeconds: 3660,
      requireNativeProof: true,
    );
  }

  Future<bool> remove(String id) {
    final order = [..._collection.ordered]..remove(id);
    if (!canMutate || order.isEmpty || !_collection.ordered.contains(id)) {
      return Future.value(_conflict());
    }
    final selected = _collection.selected == id
        ? order.first
        : _collection.selected;
    return _run(
      () => _bridge.removeNativeFace(id),
      (_) =>
          (v) =>
              v.face(id) == null &&
              listEquals(order, v.ordered) &&
              v.selected == selected,
    );
  }

  Future<bool> reorder(List<String> ids) {
    final order = List<String>.unmodifiable(ids);
    if (order.length != _collection.ordered.length ||
        order.toSet().length != order.length ||
        !setEquals(order.toSet(), _collection.ordered.toSet())) {
      return Future.value(_conflict());
    }
    return _run(
      () => _bridge.reorderNativeFaces(order),
      (_) =>
          (v) => listEquals(order, v.ordered),
    );
  }

  Future<bool> update(
    String id,
    String json, {
    required String originalJson,
    required String pair,
    required String epoch,
  }) {
    final config = NativeWatchFaceArchive.decodeConfiguration(
      Uint8List.fromList(utf8.encode(json)),
    );
    final current = _collection.face(id);
    if (current == null ||
        current.configurationJson != originalJson ||
        _collection.pair != pair ||
        _collection.epoch != epoch ||
        current.configuration == null ||
        NativeWatchFaceArchive.familyIdentity(config) !=
            NativeWatchFaceArchive.familyIdentity(current.configuration!)) {
      return Future.value(_conflict());
    }
    return _run(
      () => _bridge.updateNativeFace(id, Uint8List.fromList(utf8.encode(json))),
      (_) =>
          (v) => structurallyEqual(config, v.face(id)?.configuration),
      requireNativeProof: true,
    );
  }

  Future<bool> import(Uint8List archive) {
    final bytes = Uint8List.fromList(archive);
    final config = NativeWatchFaceArchive.configuration(bytes);
    final order = [..._collection.ordered];
    return _run(
      () => _bridge.addNativeFace(bytes),
      (id) =>
          (v) =>
              id != null &&
              v.selected == id &&
              listEquals([...order, id], v.ordered) &&
              structurallyEqual(config, v.face(id)?.configuration),
      timeoutSeconds: bytes.length > 128 * 1024 ? 3660 : 270,
      requireNativeProof: true,
    );
  }

  Future<bool> replaceResources(
    String id,
    Uint8List archive, {
    required String originalJson,
    required String originalArchiveHash,
    required String pair,
    required String epoch,
  }) {
    final bytes = Uint8List.fromList(archive);
    final config = NativeWatchFaceArchive.configuration(bytes);
    final current = _collection.face(id);
    if (current == null ||
        current.configurationJson != originalJson ||
        _collection.pair != pair ||
        _collection.epoch != epoch ||
        !current.archiveAvailable ||
        !structurallyEqual(config, current.configuration)) {
      return Future.value(_conflict());
    }
    final order = [..._collection.ordered], selected = _collection.selected;
    return _run(
      () => _bridge.replaceNativeFaceResources(id, bytes, originalArchiveHash),
      (_) =>
          (v) =>
              listEquals(order, v.ordered) &&
              v.selected == selected &&
              structurallyEqual(config, v.face(id)?.configuration),
      timeoutSeconds: bytes.length > 128 * 1024 ? 3660 : 270,
      requireNativeProof: true,
    );
  }

  Future<Uint8List?> export(String id) async {
    if (!canMutate ||
        !_collection.faces.any(
          (face) => face.id == id && face.archiveAvailable,
        )) {
      return null;
    }
    final pair = _collection.pair!, epoch = _collection.epoch!;
    final bytes = await _bridge.exportNativeFace(id, pair, epoch);
    if (_collection.pair != pair || _collection.epoch != epoch || !canMutate) {
      return null;
    }
    return bytes;
  }

  Future<bool> _run(
    Future<FaceReceipt> Function() send,
    bool Function(NativeFaceCollection) Function(String?) expectation, {
    bool mutation = true,
    bool requireNativeProof = false,
    int? timeoutSeconds,
  }) async {
    if (busy ||
        _disposed ||
        !_collection.connected ||
        _collection.pair == null ||
        _collection.epoch == null ||
        mutation && !_collection.complete) {
      return _conflict();
    }
    // Obtain a fresh baseline automatically; the HAL independently enforces this bound.
    if (mutation &&
        DateTime.now().millisecondsSinceEpoch - (_collection.observedAt ?? 0) >
            240000) {
      final previous = _collection;
      final pair = _collection.pair, epoch = _collection.epoch;
      if (!await refresh() ||
          _collection.pair != pair ||
          _collection.epoch != epoch) {
        return false;
      }
      if (!listEquals(previous.ordered, _collection.ordered) ||
          previous.selected != _collection.selected ||
          previous.faces.any(
            (face) =>
                face.configurationJson !=
                _collection.face(face.id)?.configurationJson,
          )) {
        return _conflict();
      }
    }
    if (_disposed || busy) {
      return false;
    }
    final pending = Completer<bool>();
    _mutation = mutation;
    _pending = pending;
    _baseline = _collection;
    _lateRefreshBaseline = mutation ? null : _collection;
    _matches = null;
    _receiptReceived = false;
    _request = null;
    _nativeVerified = false;
    // JSON projections cannot establish that an imported/copied image arrived.
    // Bridge verifies the opaque resource bytes in the committed native archive.
    _requireNativeProof = requireNativeProof;
    _bridge.nativeFaceRequestId = null;
    _earlyFailures.clear();
    _result = NativeFaceResult.waiting;
    _deadline = Timer(
      Duration(seconds: timeoutSeconds ?? (mutation ? 270 : 180)),
      () {
        if (identical(_pending, pending)) _finish(NativeFaceResult.unknown);
      },
    );
    notifyListeners();
    try {
      final receipt = await send();
      if (!identical(_pending, pending)) {
        return await pending.future;
      }
      if (receipt.status != 'QUEUED') {
        _finish(NativeFaceResult.rejected);
      } else {
        _request = _bridge.nativeFaceRequestId;
        _matches = expectation(receipt.faceId);
        _receiptReceived = true;
        final early = _earlyFailures[_request];
        if (early == 'NATIVE_FACE_APPLIED' && _mutation) {
          _nativeVerified = true;
          _evaluate();
        } else if (early != null) {
          _finish(
            early == 'UNKNOWN'
                ? NativeFaceResult.unknown
                : NativeFaceResult.rejected,
          );
        } else {
          _evaluate();
        }
      }
    } catch (_) {
      if (identical(_pending, pending)) _finish(NativeFaceResult.rejected);
    }
    return pending.future;
  }

  static bool structurallyEqual(Object? a, Object? b) {
    if (a is Map && b is Map) {
      return a.length == b.length &&
          a.keys.every(
            (key) => b.containsKey(key) && structurallyEqual(a[key], b[key]),
          );
    }
    if (a is List && b is List) {
      return a.length == b.length &&
          List.generate(
            a.length,
            (i) => structurallyEqual(a[i], b[i]),
          ).every((v) => v);
    }
    return a == b;
  }

  @override
  void dispose() {
    _disposed = true;
    _finish(NativeFaceResult.connectionChanged);
    unawaited(_subscription.cancel());
    unawaited(_operations.cancel());
    super.dispose();
  }
}
