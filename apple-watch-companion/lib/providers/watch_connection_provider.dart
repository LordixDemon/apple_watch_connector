import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../models/pairing_strategy.dart';
import '../models/watch_connection_state.dart';
import '../services/settings_storage.dart';
import '../services/watch_bridge_service.dart';
import '../services/companion_platform.dart';

/// Native observations own state; command receipts cannot complete pairing or connect a device.
final class WatchConnectionProvider extends ChangeNotifier {
  final WatchBridgeService _bridge;
  final SettingsStorage? _storage;
  late final StreamSubscription<WatchConnectionState> _subscription;
  late WatchConnectionState _state;
  late final ValueNotifier<String> _journal;
  bool _disposed = false, _sending = false;
  bool _includeLegacyStrategies;
  String? _receipt;
  int _progressRevision = 0;

  WatchConnectionProvider({
    required WatchBridgeService bridge,
    SettingsStorage? storage,
    bool? includeLegacyStrategies,
  }) : _bridge = bridge,
       _storage = storage,
       _includeLegacyStrategies =
           includeLegacyStrategies ??
           storage?.loadIncludeLegacyStrategies() ??
           false {
    _state = bridge.connectionState;
    _journal = ValueNotifier<String>(_state.journal);
    _subscription = bridge.connectionStream.listen((value) {
      if (value.pairId != _state.pairId ||
          value.status != _state.status ||
          value.setupRunning != _state.setupRunning ||
          value.effectiveSetupPhase != _state.effectiveSetupPhase ||
          value.connected != _state.connected) {
        _receipt = null;
        _progressRevision++;
      }
      final uiChanged = !value.sameUiState(_state);
      _state = value;
      _journal.value = value.journal;
      if (uiChanged && !_disposed) notifyListeners();
    });
  }

  WatchConnectionState get state => _state;
  ValueListenable<String> get journalListenable => _journal;
  CompanionPlatform get platform => _bridge.platform;
  bool get sending => _sending;
  String? get receipt => _receipt;
  bool get includeLegacyStrategies => _includeLegacyStrategies;
  bool get includeLegacyWatches => _includeLegacyStrategies;
  List<int> get enabledStrategies =>
      PairingStrategyInfo.idsFor(includeLegacy: _includeLegacyStrategies);

  Future<void> setIncludeLegacyStrategies(bool value) async {
    if (_disposed || _includeLegacyStrategies == value) return;
    _includeLegacyStrategies = value;
    notifyListeners();
    if (_storage != null) {
      await _storage.saveIncludeLegacyStrategies(value);
    }
  }

  Future<void> setIncludeLegacyWatches(bool value) =>
      setIncludeLegacyStrategies(value);

  Future<String> command(String method, [Map<String, dynamic>? args]) async {
    if (_disposed || _sending) return 'BUSY';
    _sending = true;
    _receipt = null;
    final revision = _progressRevision;
    String receipt;
    notifyListeners();
    try {
      final result = await _bridge.invokeBridgeMethod(method, {
        if (_state.hasPair) 'pairId': _state.pairId,
        ...?args,
      });
      receipt = result is Map
          ? result['status'] as String? ?? 'UNAVAILABLE'
          : 'UNAVAILABLE';
    } on PlatformException catch (error) {
      receipt = error.code;
    } on MissingPluginException {
      receipt = 'UNAVAILABLE';
    } finally {
      _sending = false;
    }
    if (!_disposed) {
      if (revision == _progressRevision) {
        _receipt = receipt;
      }
      notifyListeners();
    }
    return receipt;
  }

  Future<String> connect() =>
      command('connectWatch', {'pairId': _state.pairId});
  Future<String> disconnect() => command('disconnectWatch');
  Future<String> refreshAdapters() => _state.canSelectAdapter
      ? command('refreshBluetoothAdapters')
      : Future.value('BUSY');
  Future<String> selectAdapter(String id) =>
      _state.canSelectAdapter && _state.bluetoothAdapters.any((a) => a.id == id)
      ? command('selectBluetoothAdapter', {'id': id})
      : Future.value('REJECTED');
  Future<String> confirmSetup(String pairId) =>
      _state.canConfirmSetup && _state.pairId == pairId
      ? command('confirmSetup')
      : Future.value('REJECTED');
  Future<String> resumeSetup(String pairId) =>
      _state.canResumeSetup && _state.pairId == pairId
      ? command('resumeSetup')
      : Future.value('REJECTED');
  Future<String> discover({List<int>? strategies}) => _state.canDiscover
      ? command('discoverWatches', {
          'strategies': strategies != null && strategies.isNotEmpty
              ? List<int>.of(strategies)
              : enabledStrategies,
        })
      : Future.value('REJECTED');
  Future<String> selectWatch(String token) =>
      _state.setupPhase == 'DISCOVERING' &&
          _state.discoveredWatches.any((watch) => watch.token == token)
      ? command('selectDiscoveredWatch', {'discoveryToken': token})
      : Future.value('REJECTED');
  Future<String> pair({List<int>? strategies}) => _state.canPair
      ? command('beginPairing', {
          'strategies': strategies != null && strategies.isNotEmpty
              ? List<int>.of(strategies)
              : enabledStrategies,
        })
      : Future.value('REJECTED');
  Future<String> pairOptically(String token, {required String? previousPair}) {
    if (!platform.opticalPairing ||
        !_state.bridgeAvailable ||
        !_state.identityKnown ||
        !_state.bluetoothPermission ||
        _state.busy ||
        _state.connected ||
        (_state.hasPair ? _state.pairId : null) != previousPair ||
        _state.hasPair != (previousPair != null) ||
        !RegExp(
          r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ).hasMatch(token)) {
      return Future.value('REJECTED');
    }
    return command(
      previousPair == null ? 'beginOpticalPairing' : 'replacePairOptically',
      {'opticalToken': token, 'pairId': ?previousPair},
    );
  }

  Future<String> submitPin(String pin) =>
      _state.pinRequired && RegExp(r'^[0-9]{6}$').hasMatch(pin)
      ? command('submitPin', {'pin': pin})
      : Future.value('REJECTED');
  Future<String> respond(String action, Map<String, String> credentials) =>
      command('activationResponse', {
        'challengeId': _state.challengeId,
        'action': action,
        'credentials': credentials,
      });

  @override
  void dispose() {
    _disposed = true;
    unawaited(_subscription.cancel());
    _journal.dispose();
    super.dispose();
  }
}
