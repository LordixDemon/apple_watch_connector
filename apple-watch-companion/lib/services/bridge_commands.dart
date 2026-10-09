import 'package:flutter/services.dart';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import '../models/native_face_collection.dart';
import '../models/native_watch_settings.dart';
import '../models/native_pigment_baseline.dart';
import '../models/native_pigment_mirror.dart';
import '../models/native_monogram_mirror.dart';
import 'native_monogram_text_rules.dart';

/// Commands return delivery receipts; only observations establish Watch state.
mixin BridgeCommands {
  NativeFaceCollection get faceCollection;
  NativePigmentMirror? get pigmentMirror;
  NativeMonogramMirror? get monogramMirror;
  String? nativeFaceRequestId;
  Future<({String status, String? requestId})> setNativeMonogram(
    String text,
    NativeMonogramMirror baseline,
  ) async {
    if (!NativeMonogramTextRules.valid(text) ||
        monogramMirror == null ||
        !baseline.sameBaseline(monogramMirror!) ||
        !baseline.canWrite(DateTime.now().millisecondsSinceEpoch) ||
        !faceCollection.connected ||
        faceCollection.pair != baseline.pair ||
        faceCollection.epoch != baseline.epoch) {
      return (status: 'REJECTED', requestId: null);
    }
    try {
      final receipt = await invokeBridgeMethod('setNativeMonogram', {
        'pairId': baseline.pair,
        'epoch': baseline.epoch,
        'revision': baseline.revision,
        'text': text,
      });
      final request = _readNativeRequestId(receipt),
          status = receipt is Map ? receipt['status'] : null;
      if (status is String &&
          const {'QUEUED', 'REJECTED', 'UNAVAILABLE'}.contains(status) &&
          (status != 'QUEUED' || request != null)) {
        if (!faceCollection.connected ||
            faceCollection.pair != baseline.pair ||
            faceCollection.epoch != baseline.epoch) {
          return (status: 'UNKNOWN', requestId: request);
        }
        return (status: status, requestId: request);
      }
    } on MissingPluginException {
      return (status: 'UNAVAILABLE', requestId: null);
    } on PlatformException {
      return (status: 'UNAVAILABLE', requestId: null);
    }
    return (status: 'UNAVAILABLE', requestId: null);
  }

  Future<dynamic> invokeBridgeMethod(
    String method, [
    Map<String, dynamic>? args,
  ]);

  /// Manual deltas only. A queue receipt/IDS ACK cannot establish Watch state.
  Future<({String status, String? requestId})> setNativePigmentVisibility(
    Map<String, bool> changes, {
    required NativePigmentBaseline baseline,
  }) async {
    if (baseline is! NativePigmentMirror ||
        baseline.automatic?.known != true ||
        pigmentMirror == null ||
        !samePigmentBaseline(baseline, pigmentMirror!) ||
        !baseline.canWrite(DateTime.now().millisecondsSinceEpoch) ||
        !baseline.connected ||
        !baseline.known ||
        !faceCollection.connected ||
        baseline.pair != faceCollection.pair ||
        baseline.epoch != faceCollection.epoch ||
        changes.isEmpty ||
        changes.length > 1023 ||
        changes.keys.any(
          (name) =>
              name.isEmpty ||
              name.length > 256 ||
              name.contains(':') ||
              RegExp(r'[\x00-\x1f\x7f-\x9f]').hasMatch(name),
        )) {
      return (status: 'REJECTED', requestId: null);
    }
    try {
      final receipt = await invokeBridgeMethod('setNativePigmentVisibility', {
        'pairId': baseline.pair,
        'epoch': baseline.epoch,
        'sourceTimestamp': baseline.sourceTimestamp,
        'expectedNames': baseline.names,
        'automaticTimestamp': baseline.automatic!.sourceTimestamp,
        'expectedAutomaticNames': baseline.automatic!.names,
        'changes': Map<String, bool>.of(changes),
      });
      final status = receipt is Map ? receipt['status'] : null;
      final request = _readNativeRequestId(receipt);
      if (status is String &&
          const {'QUEUED', 'REJECTED', 'UNAVAILABLE'}.contains(status) &&
          (status != 'QUEUED' || request != null)) {
        if (status == 'QUEUED' &&
            (!faceCollection.connected ||
                faceCollection.pair != baseline.pair ||
                faceCollection.epoch != baseline.epoch)) {
          return (status: 'UNKNOWN', requestId: request);
        }
        return (status: status, requestId: request);
      }
    } on MissingPluginException {
      return (status: 'UNAVAILABLE', requestId: null);
    } on PlatformException {
      return (status: 'UNAVAILABLE', requestId: null);
    }
    return (status: 'UNAVAILABLE', requestId: null);
  }

  /// Queue receipt only. Actual device info arrives separately on deviceStream.
  Future<String> refreshDeviceInfo() async {
    try {
      final receipt = await invokeBridgeMethod('refreshWatchState');
      return receipt is Map && receipt['status'] is String
          ? receipt['status'] as String
          : 'UNAVAILABLE';
    } on MissingPluginException {
      return 'UNAVAILABLE';
    } on PlatformException {
      return 'UNAVAILABLE';
    }
  }

  /// Queue receipt only; inventory changes only on a committed Watch observation.
  Future<String> refreshFaceCollection() async {
    try {
      final receipt = await invokeBridgeMethod('refreshFaceCollection');
      nativeFaceRequestId = _readNativeRequestId(receipt);
      return receipt is Map && receipt['status'] is String
          ? receipt['status'] as String
          : 'UNAVAILABLE';
    } on MissingPluginException {
      return 'UNAVAILABLE';
    } on PlatformException {
      return 'UNAVAILABLE';
    }
  }

  Future<bool> _invokeConfirmed(
    String method, [
    Map<String, dynamic>? args,
  ]) async {
    try {
      final receipt = await invokeBridgeMethod(method, args);
      // A Binder queue receipt or an IDS ACK does not establish Watch-local effect.
      return receipt is Map && receipt['status'] == 'APPLIED';
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// Native queue receipt only. The selected UUID is confirmed by collection observations.
  Future<({String status, String? faceId})> selectNativeFace(String faceId) =>
      _queueNativeFace('setActiveFace', {'faceId': faceId});

  Future<({String status, String? faceId})> duplicateNativeFace(
    String source,
  ) => _queueNativeFace('duplicateNativeFace', {'sourceFaceId': source});

  Future<({String status, String? faceId})> removeNativeFace(String id) =>
      _queueNativeFace('removeNativeFace', {'faceId': id});
  Future<({String status, String? faceId})> reorderNativeFaces(
    List<String> ids,
  ) => _queueNativeFace('reorderNativeFaces', {'faceIds': ids});
  Future<({String status, String? faceId})> updateNativeFace(
    String id,
    Uint8List configuration,
  ) => _queueNativeFace('updateNativeFace', {
    'faceId': id,
    'configuration': configuration,
  });
  Future<({String status, String? faceId})> addNativeFace(Uint8List archive) =>
      _stageNativeFace(archive);

  Future<({String status, String? faceId})> replaceNativeFaceResources(
    String id,
    Uint8List archive,
    String baselineHash,
  ) => _stageNativeFace(archive, faceId: id, baselineHash: baselineHash);

  Future<({String status, String? faceId})> _stageNativeFace(
    Uint8List archive, {
    String? faceId,
    String? baselineHash,
  }) async {
    if (faceId == null && archive.length <= 128 * 1024) {
      return _queueNativeFace('addNativeFace', {'archive': archive});
    }
    if (archive.length > 16 * 1024 * 1024 ||
        faceCollection.pair == null ||
        faceCollection.epoch == null) {
      return (status: 'REJECTED', faceId: null);
    }
    final pair = faceCollection.pair!, epoch = faceCollection.epoch!;
    String? upload;
    bool queued = false;
    try {
      final start = await invokeBridgeMethod('beginNativeFaceImport', {
        'pairId': pair,
        'epoch': epoch,
        'total': archive.length,
        'faceId': ?faceId,
        'baselineHash': ?baselineHash,
      });
      if (start is! Map ||
          start['status'] != 'UPLOADING' ||
          start['uploadId'] is! String) {
        return (status: 'UNAVAILABLE', faceId: null);
      }
      upload = start['uploadId'] as String;
      var offset = 0;
      while (offset < archive.length) {
        if (!faceCollection.connected ||
            faceCollection.pair != pair ||
            faceCollection.epoch != epoch) {
          return (status: 'UNAVAILABLE', faceId: null);
        }
        final end = (offset + 65536).clamp(0, archive.length);
        final result = await invokeBridgeMethod('appendNativeFaceImport', {
          'pairId': pair,
          'epoch': epoch,
          'uploadId': upload,
          'offset': offset,
          'chunk': Uint8List.sublistView(archive, offset, end),
        });
        if (result is! Map ||
            result['status'] != 'UPLOADING' ||
            result['offset'] != end) {
          return (status: 'REJECTED', faceId: null);
        }
        offset = end;
      }
      final result = await _queueNativeFace('finishNativeFaceImport', {
        'pairId': pair,
        'epoch': epoch,
        'uploadId': upload,
      });
      queued = result.status == 'QUEUED';
      return result;
    } on PlatformException {
      return (status: 'UNAVAILABLE', faceId: null);
    } on MissingPluginException {
      return (status: 'UNAVAILABLE', faceId: null);
    } finally {
      if (upload != null && !queued) {
        try {
          await invokeBridgeMethod('cancelNativeFaceImport', {
            'uploadId': upload,
          });
        } on PlatformException {
          /* Bridge also expires incomplete transfers. */
        } on MissingPluginException {}
      }
    }
  }

  Future<Uint8List?> exportNativeFace(
    String id,
    String pair,
    String epoch,
  ) async {
    try {
      final bytes = BytesBuilder(copy: false);
      String? hash;
      int? total;
      do {
        if (!faceCollection.connected ||
            faceCollection.pair != pair ||
            faceCollection.epoch != epoch) {
          return null;
        }
        final value = await invokeBridgeMethod('exportNativeFace', {
          'faceId': id,
          'pairId': pair,
          'epoch': epoch,
          'offset': bytes.length,
          'sha256': ?hash,
        });
        if (value is! Map ||
            value['status'] != 'EXPORTED' ||
            value['archive'] is! Uint8List ||
            value['offset'] != bytes.length ||
            value['total'] is! int ||
            value['sha256'] is! String) {
          return null;
        }
        final chunk = value['archive'] as Uint8List;
        if (chunk.isEmpty ||
            chunk.length > 65536 ||
            (value['total'] as int) < 1 ||
            (value['total'] as int) > 16 * 1024 * 1024 ||
            total != null && total != value['total'] ||
            hash != null && hash != value['sha256']) {
          return null;
        }
        hash = value['sha256'] as String;
        total = value['total'] as int;
        if (bytes.length + chunk.length > total) {
          return null;
        }
        bytes.add(chunk);
      } while (bytes.length < total);
      final result = bytes.takeBytes();
      if (sha256.convert(result).toString() == hash) {
        return result;
      }
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
    return null;
  }

  Future<({String status, String? faceId})> _queueNativeFace(
    String method,
    Map<String, dynamic> args,
  ) async {
    try {
      final receipt = await invokeBridgeMethod(method, args);
      if (receipt is Map && receipt['status'] is String) {
        nativeFaceRequestId = _readNativeRequestId(receipt);
        final id = receipt['faceId'];
        return (
          status: receipt['status'] as String,
          faceId:
              id is String &&
                  RegExp(
                    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
                  ).hasMatch(id)
              ? id
              : null,
        );
      }
    } on MissingPluginException {
      return (status: 'UNAVAILABLE', faceId: null);
    } on PlatformException {
      return (status: 'UNAVAILABLE', faceId: null);
    }
    return (status: 'UNAVAILABLE', faceId: null);
  }

  String? _readNativeRequestId(dynamic receipt) {
    final request = receipt is Map ? receipt['requestId'] : null;
    return request is String &&
            RegExp(
              r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            ).hasMatch(request)
        ? request
        : null;
  }

  /// Syncs updated settings to the watch over NanoRegistry.
  Future<bool> syncSettings(Map<String, dynamic> settingsMap) async {
    return _invokeConfirmed('syncSettings', settingsMap);
  }

  /// Delivery receipt only. Current values arrive as separate Watch observations.
  Future<String> setWatchSetting(NativeWatchSetting setting, bool value) async {
    try {
      final receipt = await invokeBridgeMethod('setWatchSetting', {
        'setting': setting.wireName,
        'value': value,
      });
      return receipt is Map && receipt['status'] is String
          ? receipt['status'] as String
          : 'UNAVAILABLE';
    } on MissingPluginException {
      return 'UNAVAILABLE';
    } on PlatformException {
      return 'UNAVAILABLE';
    }
  }

  /// Triggers a test vibration / ping on the watch.
  Future<void> pingWatch() async {
    await _invokeConfirmed('pingWatch');
  }

  Future<bool> stopPhonePing() async {
    try {
      final receipt = await invokeBridgeMethod('stopPhonePing');
      return receipt is Map && receipt['status'] == 'STOPPED';
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> requestPhoneFlashPermission() async {
    try {
      final receipt = await invokeBridgeMethod('requestPhoneFlashPermission');
      // OPENED means only the permission screen opened, never that permission was granted.
      return receipt is Map && receipt['status'] == 'OPENED';
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// Unpairs the current Apple Watch and wipes keys.
  Future<bool> unpairWatch() => _invokeConfirmed('unpairWatch');

  /// Sends a mirrored notification to Apple Watch (com.apple.private.alloy.bulletindistributor).
  Future<bool> sendNotificationTest(
    String title,
    String message, {
    String? sectionDisplayName,
    String? sectionId,
  }) async {
    return _invokeConfirmed('sendNotification', {
      'title': title,
      'message': message,
      'sectionDisplayName': sectionDisplayName ?? 'Messages',
      'sectionId': sectionId ?? 'com.apple.MobileSMS',
    });
  }

  /// Sends incoming call notification to Apple Watch (com.apple.private.alloy.telephony).
  Future<bool> triggerIncomingCall(
    String callerName,
    String callerNumber, {
    bool isVideo = false,
  }) async {
    return _invokeConfirmed('triggerCall', {
      'callerName': callerName,
      'callerNumber': callerNumber,
      'isVideo': isVideo,
    });
  }

  /// Responds to watch call action (answer/decline).
  Future<void> handleCallAction(String callId, int action) async {
    await _invokeConfirmed('callAction', {'callId': callId, 'action': action});
  }
}
