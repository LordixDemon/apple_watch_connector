import 'dart:async';
import 'dart:convert';
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
  bool _initialConnectionChecked = false;
  int _epoch = -1;
  final Map<String, String> _discoveredIds = {};
  Map<String, dynamic> _connection = const {};
  String? _lastPublication;
  final Duration? pollInterval;

  RustBridgeTransport(
    this._api, {
    this.pollInterval = const Duration(milliseconds: 250),
  }) {
    _events = StreamController<dynamic>.broadcast(
      onListen: () {
        _lastPublication = null;
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
        _discoveredIds.clear();
      }
      final watches = <Map<String, dynamic>>[];
      Map? selected;
      _discoveredIds.clear();
      for (final device in (snapshot['devices'] as List? ?? const []).take(
        64,
      )) {
        if (device is! Map ||
            device['id'] is! String ||
            device['productType'] is! String ||
            (device['productType'] as String).isEmpty ||
            device['watchOs'] is! String ||
            device['rssi'] is! int) {
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
            'productType': device['productType'],
            'watchOs': device['watchOs'],
            'rssi': device['rssi'],
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
        'discoveredProductType': selected?['productType'] ?? '',
        'discoveredWatchOs': selected?['watchOs'] ?? '',
        'productType': pairId.isNotEmpty
            ? snapshot['productType'] as String? ?? ''
            : '',
        'discoveredWatches': watches,
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
            'Core ${snapshot['coreVersion'] ?? '—'} • API 1\n'
            'Adapter: $adapter\nPhase: $phase • epoch: $epoch\n'
            'Pairing exchange: ${snapshot['pairingStage'] ?? '—'}\n'
            'Endpoint registered: ${snapshot['pairingEndpointRegistered'] == true} • stream open: ${snapshot['pairingStreamOpen'] == true}\n'
            'Verified setup advertisements: ${watches.length}\n'
            'Pairing available: $pairing • IDS available: ${capabilities['operationalIds'] == true}\n'
            'Pairing restriction: ${capabilities['pairingUnavailableReason'] ?? '—'}\n'
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

  void _publish(Map<String, dynamic> connection) {
    _connection = connection;
    final fingerprint = jsonEncode(connection);
    if (fingerprint == _lastPublication || _closed) return;
    _lastPublication = fingerprint;
    _events.add({'type': 'connection', 'data': connection});
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
        request = {'method': 'scan'};
      case 'requestConnectionPermissions':
        request = {
          'method': _connection['hasPair'] == true ? 'resume' : 'scan',
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
          request = {'method': 'pair', 'mode': 'code'};
        }
      case 'refreshWatchState':
        return {'status': 'OBSERVED'};
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
    } catch (_) {
      _discoveredIds.clear();
      _publish({
        'bridgeAvailable': false,
        'identityKnown': false,
        'discoveryAvailable': false,
        'pairingAvailable': false,
        'wifiAvailable': false,
        'backendError': 'Rust core command failed',
        'bridgeJournal': 'Rust core command failed',
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
