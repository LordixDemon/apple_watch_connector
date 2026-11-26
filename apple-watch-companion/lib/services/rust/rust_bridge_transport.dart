import 'dart:async';
import '../bridge_transport.dart';
import '../../l10n/strings.dart';
import 'rust_core_api.dart';

/// Projects observed Rust facts into the same contract used by Android Binder.
/// No native device UUID is a persisted pairing, and no BLE callback is IDS.
final class RustBridgeTransport implements OwnedBridgeTransport {
  final CoreApi _api;
  late final StreamController<dynamic> _events;
  Timer? _poll;
  bool _closed = false;
  bool _hasPublished = false;
  bool _initialConnectionChecked = false;
  int _epoch = -1;
  final Map<String, String> _discoveredIds = {};
  Map<String, dynamic> _connection = const {};
  final Duration? pollInterval;

  RustBridgeTransport(
    this._api, {
    this.pollInterval = const Duration(milliseconds: 250),
  }) {
    _events = StreamController<dynamic>.broadcast(
      onListen: () {
        _hasPublished = false;
        refresh();
        final interval = pollInterval;
        if (interval != null) {
          _poll = Timer.periodic(interval, (_) => refresh());
        }
      },
      onCancel: () {
        _poll?.cancel();
        _poll = null;
      },
    );
  }
  @override
  Stream<dynamic> get events => _events.stream;

  void refresh() {
    if (_closed) return;
    try {
      final snapshot = _api.command({'method': 'snapshot'});
      if (snapshot['apiVersion'] != 1) {
        throw StateError('Unsupported Rust core API');
      }
      final capabilities = snapshot['capabilities'] as Map? ?? const {};
      final phase = snapshot['phase'] as String? ?? 'IDLE';
      final adapter = snapshot['adapterState'] as String? ?? 'UNKNOWN';
      final epoch = snapshot['epoch'] as int? ?? 0;
      if (epoch != _epoch) {
        _epoch = epoch;
      }
      final watches = <Map<String, dynamic>>[];
      Map? selected;
      _discoveredIds.clear();
      for (final device in (snapshot['devices'] as List? ?? const []).take(
        64,
      )) {
        if (device is! Map ||
            device['id'] is! String ||
            device['rssi'] is! int) {
          continue;
        }
        final rawProductType = device['productType'];
        final rawWatchOs = device['watchOs'];
        final rawDisplayName = device['displayName'];
        final rawStrategy = device['strategy'];
        if ((rawProductType != null && rawProductType is! String) ||
            (rawWatchOs != null && rawWatchOs is! String) ||
            (rawDisplayName != null && rawDisplayName is! String)) {
          continue;
        }
        final productType = rawProductType as String? ?? '';
        final watchOs = rawWatchOs as String? ?? '';
        final displayName = (rawDisplayName as String?)?.trim() ?? '';
        if (productType.isEmpty && displayName.isEmpty && rawStrategy is! int) {
          continue;
        }
        final id = device['id'] as String;
        final token = id.toLowerCase();
        if (!RegExp(
          r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ).hasMatch(token)) {
          continue;
        }
        if (id == snapshot['selectedId']) selected = device;
        if (phase == 'DISCOVERING') {
          _discoveredIds[token] = id;
          watches.add({
            'token': token,
            'productType': productType,
            'watchOs': watchOs,
            'rssi': device['rssi'],
            if (displayName.isNotEmpty) 'displayName': displayName,
            if (rawStrategy is int) 'strategy': rawStrategy,
            if (device['experimental'] is bool)
              'experimental': device['experimental'],
            if (device['authStyles'] is List)
              'authStyles': device['authStyles'],
          });
        }
      }
      final bluetoothLink =
          phase == 'BLUETOOTH_LINK' || snapshot['pairingStreamOpen'] == true;
      final pairing = capabilities['pairing'] == true;
      final pairId = snapshot['pairId'] as String? ?? '';
      if (!_initialConnectionChecked &&
          (capabilities['adapterSelection'] != true ||
              snapshot['adapterSelectionStatus'] != 'LOADING')) {
        _initialConnectionChecked = true;
        if (const {'linux', 'windows'}.contains(capabilities['platform']) &&
            pairId.isNotEmpty &&
            snapshot['operationalEligible'] == true &&
            (capabilities['adapterSelection'] != true ||
                snapshot['adapterSelectionStatus'] == 'READY') &&
            phase == 'IDLE') {
          _api.command({'method': 'resume'});
        }
      }
      final ready =
          pairId.isNotEmpty &&
          phase == 'OPERATIONAL' &&
          snapshot['watchReady'] == true &&
          capabilities['operationalIds'] == true;
      final setupPhase = switch (phase) {
        'DISCONNECTING' => 'STOPPING',
        'ACTIVATION_REJECTED' => 'FAILED',
        'OPERATIONAL' => ready ? 'VERIFIED' : 'IDS',
        'PAIRING_SECURITY' || 'PIN_AUTHENTICATION' => 'SECURITY',
        'PIN_DERIVATION' => 'CHECKING_CODE',
        'PIN_AUTHENTICATED' => 'BOND_REQUIRED',
        _ => phase,
      };
      final coreGitRev = (snapshot['coreGitRev'] as String?)?.trim() ?? '';
      final protocolFingerprint =
          (snapshot['protocolFingerprint'] as String?)?.trim() ?? '';
      final leLinkSummary =
          (snapshot['leLinkSummary'] as String?)?.trim() ?? '';
      final coreHeader = StringBuffer(
        'Core ${snapshot['coreVersion'] ?? '—'} • API 1',
      );
      if (coreGitRev.isNotEmpty) {
        coreHeader.write(' • rev $coreGitRev');
      }
      if (protocolFingerprint.isNotEmpty) {
        coreHeader.write(' • jar $protocolFingerprint');
      }
      final selectedProduct = selected?['productType'] as String?;
      final selectedDisplay = selected?['displayName'] as String?;
      _publish({
        'bridgeAvailable': true,
        'identityKnown': true,
        // This backend exposes connection/setup commands only. IDS readiness
        // does not imply that application services have been wired into FFI.
        'features': capabilities['features'] is Map
            ? capabilities['features'] as Map
            : <String, bool>{},
        'bluetoothPermission': adapter == 'POWERED_ON',
        'discoveryAvailable': capabilities['discovery'] == true,
        'pairingAvailable': pairing,
        'wifiAvailable': capabilities['wifi'] == true,
        'ownerConfirmationAvailable': capabilities['ownerConfirmation'] == true,
        'adapterSelectionAvailable': capabilities['adapterSelection'] == true,
        'bluetoothAdapters': snapshot['bluetoothAdapters'] as List? ?? const [],
        'selectedAdapterId': snapshot['selectedAdapterId'] as String? ?? '',
        'adapterSelectionStatus':
            snapshot['adapterSelectionStatus'] as String? ?? '',
        'adapterSelectionError':
            snapshot['adapterSelectionError'] as String? ?? '',
        'adapterSelectionBusy': snapshot['adapterSelectionBusy'] == true,
        'adapterInventoryRefreshing':
            snapshot['adapterInventoryRefreshing'] == true,
        'bluetoothLink': bluetoothLink,
        'hasPair': pairId.isNotEmpty,
        'pairId': pairId,
        'connected': ready,
        'softwareUpdate': snapshot['softwareUpdate'],
        'faceCollectionEpoch': (snapshot['softwareUpdate'] as Map?)?['epoch'],
        if (snapshot['activationChallengeId'] != null)
          'activationChallengeId': snapshot['activationChallengeId'],
        if (snapshot['activationTitle'] != null)
          'activationTitle': snapshot['activationTitle'],
        if (snapshot['activationMessage'] != null)
          'activationMessage': snapshot['activationMessage'],
        if (snapshot['activationFields'] != null)
          'activationFields': snapshot['activationFields'],
        if (snapshot.containsKey('aboutObservedAt'))
          'aboutObservedAt': snapshot['aboutObservedAt'],
        if (snapshot.containsKey('batteryLevel'))
          'batteryLevel': snapshot['batteryLevel'],
        if (snapshot.containsKey('isCharging'))
          'isCharging': snapshot['isCharging'],
        if (snapshot.containsKey('chargingObserved'))
          'chargingObserved': snapshot['chargingObserved'],
        if (snapshot.containsKey('availableStorageBytes'))
          'availableStorageBytes': snapshot['availableStorageBytes'],
        if (snapshot.containsKey('numberOfApps'))
          'numberOfApps': snapshot['numberOfApps'],
        if (snapshot.containsKey('numberOfSongs'))
          'numberOfSongs': snapshot['numberOfSongs'],
        if (snapshot.containsKey('numberOfPhotos'))
          'numberOfPhotos': snapshot['numberOfPhotos'],
        if (snapshot.containsKey('purgeableSpaceBytes'))
          'purgeableSpaceBytes': snapshot['purgeableSpaceBytes'],
        if (snapshot.containsKey('userDeletableSpaceBytes'))
          'userDeletableSpaceBytes': snapshot['userDeletableSpaceBytes'],
        for (final key in const [
          'watchSetting_RIGHT_WRIST',
          'watchSetting_RIGHT_WRIST_observedAt',
          'watchSetting_INVERT_SCREEN',
          'watchSetting_INVERT_SCREEN_observedAt',
          'watchSetting_TIME_24_HOUR',
          'watchSetting_TIME_24_HOUR_observedAt',
        ])
          if (snapshot.containsKey(key)) key: snapshot[key],
        'operationalEligible':
            snapshot['operationalEligible'] == true && pairId.isNotEmpty,
        'activationConfirmed':
            snapshot['activationConfirmed'] == true && pairId.isNotEmpty,
        'connectionStatus': ready
            ? 'CONNECTED'
            : switch (phase) {
                'CONNECTING' || 'RECONNECTING' => 'CONNECTING',
                'DISCONNECTING' => 'STOPPING',
                'BLUETOOTH_LINK' => 'BLUETOOTH_LINK',
                'PAIRING_SECURITY' ||
                'PIN_DERIVATION' ||
                'PIN_AUTHENTICATION' ||
                'PIN_REQUIRED' => 'CONNECTING',
                'PIN_AUTHENTICATED' => 'BLUETOOTH_LINK',
                _ => 'DISCONNECTED',
              },
        'setupRunning':
            snapshot['operationalEligible'] != true &&
            !const {
              'IDLE',
              'FAILED',
              'BLUETOOTH_LINK',
              'OPERATIONAL',
              'PIN_AUTHENTICATED',
            }.contains(phase),
        'setupPhase': setupPhase,
        'pinRequired':
            pairing &&
            phase == 'PIN_REQUIRED' &&
            snapshot['pinRequired'] == true,
        'discoveredProductType':
            (selectedProduct != null && selectedProduct.isNotEmpty)
            ? selectedProduct
            : (selectedDisplay ?? ''),
        'discoveredWatchOs': selected?['watchOs'] as String? ?? '',
        'productType': pairId.isNotEmpty
            ? snapshot['productType'] as String? ?? ''
            : '',
        'discoveredWatches': watches,
        if (capabilities['strategies'] is List)
          'capabilities': {'strategies': capabilities['strategies']},
        if (snapshot['pairingStrategy'] is int)
          'pairingStrategy': snapshot['pairingStrategy']
        else if (selected?['strategy'] is int)
          'pairingStrategy': selected!['strategy'],
        'backendError':
            snapshot['error'] as String? ??
            switch (capabilities['pairingUnavailableReason']) {
              'MACOS_PAIRING_BOOTSTRAP_RESTRICTED' =>
                Strings.current.macosPairingBootstrapRestricted,
              'WINDOWS_PAIRING_BOOTSTRAP_RESTRICTED' =>
                Strings.current.windowsPairingBootstrapRestricted,
              'WINDOWS_RAW_HCI_REQUIRED' =>
                Strings.current.windowsRawHciRequired,
              _ => '',
            },
        'bridgeJournal':
            '$coreHeader\n'
            'Adapter: $adapter\nPhase: $phase • epoch: $epoch\n'
            'Pairing exchange: ${snapshot['pairingStage'] ?? '—'}\n'
            'Endpoint registered: ${snapshot['pairingEndpointRegistered'] == true} • stream open: ${snapshot['pairingStreamOpen'] == true}\n'
            'Verified setup advertisements: ${watches.length}\n'
            'Pairing available: $pairing • IDS available: ${capabilities['operationalIds'] == true}\n'
            'Pairing restriction: ${capabilities['pairingUnavailableReason'] ?? '—'}\n'
            '${leLinkSummary.isNotEmpty ? 'LE link: $leLinkSummary\n' : ''}'
            'Log file: ${snapshot['logPath'] ?? '—'}\n'
            '${snapshot['error'] as String? ?? ''}\n'
            '${snapshot['journal'] as String? ?? ''}',
      });
    } catch (error) {
      _discoveredIds.clear();
      _publish({
        'bridgeAvailable': false,
        'identityKnown': false,
        'discoveryAvailable': false,
        'pairingAvailable': false,
        'wifiAvailable': false,
        'backendError': error.toString(),
        'bridgeJournal': error.toString(),
      });
    }
  }

  static bool _deepEquals(Object? a, Object? b) {
    if (identical(a, b)) return true;
    if (a is Map && b is Map) {
      if (a.length != b.length) return false;
      for (final entry in a.entries) {
        if (!b.containsKey(entry.key) ||
            !_deepEquals(entry.value, b[entry.key])) {
          return false;
        }
      }
      return true;
    }
    if (a is List && b is List) {
      if (a.length != b.length) return false;
      for (var i = 0; i < a.length; i++) {
        if (!_deepEquals(a[i], b[i])) return false;
      }
      return true;
    }
    return a == b;
  }

  void _publish(Map<String, dynamic> connection) {
    final unchanged = _hasPublished && _deepEquals(_connection, connection);
    _connection = connection;
    if (unchanged || _closed) return;
    _hasPublished = true;
    _events.add({'type': 'connection', 'data': connection});
  }

  static List<int>? _parseStrategies(Map<String, dynamic>? arguments) {
    final raw = arguments?['strategies'];
    if (raw is! List) return null;
    final ids = <int>[];
    for (final item in raw) {
      if (item is int && item >= 1 && item <= 4 && !ids.contains(item)) {
        ids.add(item);
      }
    }
    return ids.isEmpty ? null : ids;
  }

  @override
  Future<dynamic> invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    if (_closed) return {'status': 'UNAVAILABLE'};
    refresh();
    if (_connection['bridgeAvailable'] != true && method != 'disconnectWatch') {
      return {'status': 'UNAVAILABLE'};
    }
    Map<String, dynamic> request;
    switch (method) {
      case 'querySoftwareUpdate':
      case 'checkSoftwareUpdate':
      case 'downloadSoftwareUpdate':
      case 'installSoftwareUpdate':
        if (_connection['connected'] != true ||
            (_connection['features'] as Map?)?['softwareUpdates'] != true) {
          return {'status': 'UNAVAILABLE'};
        }
        request = {'method': method, ...?arguments};
      case 'refreshBluetoothAdapters':
      case 'selectBluetoothAdapter':
        if (_connection['adapterSelectionAvailable'] != true) {
          return {'status': 'UNSUPPORTED_OPERATION'};
        }
        if (_connection['adapterSelectionBusy'] == true) {
          return {'status': 'BUSY'};
        }
        if (method == 'selectBluetoothAdapter') {
          final id = arguments?['id'];
          if (id is! String ||
              !(_connection['bluetoothAdapters'] as List).any(
                (a) => a is Map && a['id'] == id,
              )) {
            return {'status': 'REJECTED'};
          }
          request = {'method': 'selectAdapter', 'id': id};
        } else {
          request = {'method': 'refreshAdapters'};
        }
      case 'discoverWatches':
        final strategies = _parseStrategies(arguments);
        request = {'method': 'scan', 'strategies': ?strategies};
      case 'requestConnectionPermissions':
        final isResume = _connection['hasPair'] == true;
        final strategies = isResume ? null : _parseStrategies(arguments);
        request = {
          'method': isResume ? 'resume' : 'scan',
          'strategies': ?strategies,
        };
      case 'selectDiscoveredWatch':
        final id = _discoveredIds[arguments?['discoveryToken']];
        if (id == null || _connection['setupPhase'] != 'DISCOVERING') {
          return {'status': 'REJECTED'};
        }
        request = {'method': 'connect', 'id': id};
      case 'disconnectWatch':
        request = {'method': 'stop'};
      case 'beginPairing':
      case 'submitPin':
        if (_connection['pairingAvailable'] != true) {
          return {'status': 'WATCH_PROTOCOL_UNAVAILABLE'};
        }
        if (method == 'submitPin') {
          final pin = arguments?['pin'];
          if (_connection['pinRequired'] != true ||
              pin is! String ||
              !RegExp(r'^[0-9]{6}$').hasMatch(pin)) {
            return {'status': 'REJECTED'};
          }
          request = {'method': 'submitPin', 'pin': pin};
        } else {
          final strategies = _parseStrategies(arguments);
          request = {
            'method': 'pair',
            'mode': 'code',
            'strategies': ?strategies,
          };
        }
      case 'refreshWatchState':
        if (_connection['connected'] != true ||
            (_connection['features'] as Map?)?['telemetry'] != true) {
          return {'status': 'OBSERVED'};
        }
        request = {'method': 'refreshWatchState'};
      case 'setWatchSetting':
        if (_connection['connected'] != true ||
            (_connection['features'] as Map?)?['watchSettings'] != true) {
          return {'status': 'UNAVAILABLE'};
        }
        final setting = arguments?['setting'];
        final settingValue = arguments?['value'];
        if (setting is! String || settingValue is! bool) {
          return {'status': 'REJECTED'};
        }
        request = {
          'method': 'setWatchSetting',
          'setting': setting,
          'value': settingValue,
        };
      case 'pingWatch':
        if (_connection['connected'] != true) {
          return {'status': 'UNAVAILABLE'};
        }
        request = {'method': 'pingWatch'};
      case 'activationResponse':
        final challengeId = arguments?['challengeId'];
        final action = arguments?['action'];
        if (challengeId is! int ||
            challengeId != _connection['activationChallengeId'] ||
            !const {'submit', 'retry', 'cancel'}.contains(action)) {
          return {'status': 'REJECTED'};
        }
        final credentials = arguments?['credentials'];
        request = {
          'method': 'activationResponse',
          'challengeId': challengeId,
          'action': action,
          if (credentials is Map) 'credentials': credentials,
        };
      case 'connectWatch':
      case 'resumeSetup':
        if (_connection['hasPair'] != true) {
          return {'status': 'WATCH_PROTOCOL_UNAVAILABLE'};
        }
        request = {'method': 'resume'};
      case 'confirmSetup':
        if (_connection['ownerConfirmationAvailable'] != true) {
          return {'status': 'WATCH_PROTOCOL_UNAVAILABLE'};
        }
        if (_connection['hasPair'] != true ||
            _connection['activationConfirmed'] != true) {
          return {'status': 'REJECTED'};
        }
        request = {'method': 'confirmSetup'};
      default:
        return {'status': 'UNSUPPORTED_OPERATION'};
    }
    try {
      final result = _api.command(request);
      scheduleMicrotask(refresh);
      return result;
    } catch (error) {
      _discoveredIds.clear();
      _publish({
        'bridgeAvailable': false,
        'identityKnown': false,
        'discoveryAvailable': false,
        'pairingAvailable': false,
        'wifiAvailable': false,
        'backendError': 'Rust core command failed',
        'bridgeJournal': 'Rust core command failed: $error',
      });
      return {'status': 'UNAVAILABLE'};
    }
  }

  @override
  void dispose() {
    if (_closed) return;
    _closed = true;
    _poll?.cancel();
    _discoveredIds.clear();
    _connection = const {};
    try {
      _api.command({'method': 'stop'});
    } catch (_) {
      /* Already unavailable. */
    }
    unawaited(_events.close());
  }
}

/// Loading errors stay visible in the common diagnostics screen; no fallback
/// to Android method channels is attempted on a desktop.
final class UnavailableRustTransport implements OwnedBridgeTransport {
  final String error;
  bool _closed = false;
  late final StreamController<dynamic> _events =
      StreamController<dynamic>.broadcast(
        onListen: () => _events.add({
          'type': 'connection',
          'data': {
            'bridgeAvailable': false,
            'identityKnown': false,
            'pairingAvailable': false,
            'discoveryAvailable': false,
            'wifiAvailable': false,
            'backendError': error,
            'bridgeJournal': error,
          },
        }),
      );
  UnavailableRustTransport(this.error);
  @override
  Stream<dynamic> get events => _events.stream;
  @override
  Future<dynamic> invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async => {'status': 'UNAVAILABLE'};
  @override
  void dispose() {
    if (_closed) return;
    _closed = true;
    unawaited(_events.close());
  }
}
