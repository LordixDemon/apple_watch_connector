import 'dart:async';
import 'package:apple_watch_companion/app/companion_app.dart';
import 'package:apple_watch_companion/app/companion_scope.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/providers/watch_connection_provider.dart';
import 'package:apple_watch_companion/services/companion_backend.dart';
import 'package:apple_watch_companion/services/companion_platform.dart';
import 'package:apple_watch_companion/services/rust/rust_bridge_transport.dart';
import 'package:apple_watch_companion/services/rust/rust_core_api.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/widgets/bluetooth_adapter_section.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _id = '125C3D32-ADF0-4D49-BC7C-6ED0F7B8A999';
const _token = '125c3d32-adf0-4d49-bc7c-6ed0f7b8a999';

class _Api implements CoreApi {
  final Map<String, dynamic> state = {
    'apiVersion': 1,
    'epoch': 0,
    'phase': 'IDLE',
    'adapterState': 'UNKNOWN',
    'watchReady': false,
    'devices': <Map<String, dynamic>>[],
    'capabilities': {
      'discovery': true,
      'pairing': false,
      'operationalIds': false,
    },
  };
  final calls = <Map<String, dynamic>>[];
  String? throwOn;
  @override
  Map<String, dynamic> command(Map<String, dynamic> request) {
    calls.add(Map.of(request));
    if (request['method'] == throwOn) throw StateError('Native call failed');
    if (request['method'] == 'selectAdapter') {
      state['selectedAdapterId'] = request['id'];
      state['adapterSelectionStatus'] = 'READY';
      return {'status': 'APPLIED'};
    }
    return request['method'] == 'snapshot'
        ? Map.of(state)
        : {'status': 'QUEUED'};
  }

  List<Map<String, dynamic>> get commands =>
      calls.where((c) => c['method'] != 'snapshot').toList();
  void discovering() {
    state.addAll({
      'phase': 'DISCOVERING',
      'epoch': 1,
      'devices': [
        {'id': 'other', 'rssi': -62, 'productType': null, 'watchOs': null},
        {
          'id': _id,
          'rssi': -41,
          'productType': 'Watch7,5',
          'watchOs': '26.2.0',
        },
      ],
    });
  }
}

class _Faces extends WatchFaceApiService {
  @override
  Future<List<WatchFace>> loadCachedFaces() async => [];
  @override
  Future<List<WatchFace>> fetchRemoteFaces({String endpoint = ''}) async => [];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Api api;
  late RustBridgeTransport transport;
  late WatchBridgeService bridge;
  late WatchConnectionProvider connection;
  Future<void> observe() async {
    transport.refresh();
    await Future<void>.delayed(Duration.zero);
  }

  setUp(() async {
    api = _Api();
    transport = RustBridgeTransport(api, pollInterval: null);
    bridge = WatchBridgeService(
      transport: transport,
      platform: CompanionPlatform.desktop,
    );
    connection = WatchConnectionProvider(bridge: bridge);
    await Future<void>.delayed(Duration.zero);
  });
  tearDown(() {
    connection.dispose();
    bridge.dispose();
  });

  Map<String, dynamic> adapter(String id, int index, String name) => {
    'id': id,
    'address': id,
    'name': name,
    'index': index,
    'controller': 'hci$index',
  };
  void adapters({String status = 'REQUIRED'}) {
    api.state['capabilities'] = {
      'platform': 'linux',
      'adapterSelection': true,
      'discovery': true,
      'pairing': true,
      'operationalIds': true,
    };
    api.state.addAll({
      'bluetoothAdapters': [
        adapter('aa:bb:cc:dd:ee:01', 2, 'Built-in'),
        adapter('aa:bb:cc:dd:ee:02', 7, 'USB Bluetooth'),
      ],
      'adapterSelectionStatus': status,
      'adapterSelectionBusy': false,
    });
  }

  test(
    'Adapter choice is sent by stable address and rejects stale or busy selections',
    () async {
      adapters();
      await observe();
      expect(connection.state.canPair, isFalse);
      expect(connection.state.canDiscover, isFalse);
      expect(await connection.selectAdapter('aa:bb:cc:dd:ee:02'), 'APPLIED');
      expect(api.commands.last, {
        'method': 'selectAdapter',
        'id': 'aa:bb:cc:dd:ee:02',
      });
      await observe();
      expect(connection.state.selectedAdapterId, 'aa:bb:cc:dd:ee:02');
      expect(connection.state.canPair, isTrue);
      expect(
        await transport.invoke('selectBluetoothAdapter', {
          'id': 'aa:bb:cc:dd:ee:ff',
        }),
        {'status': 'REJECTED'},
      );
      api.state['adapterSelectionBusy'] = true;
      expect(
        await transport.invoke('selectBluetoothAdapter', {
          'id': 'aa:bb:cc:dd:ee:01',
        }),
        {'status': 'BUSY'},
      );
      expect(await transport.invoke('refreshBluetoothAdapters'), {
        'status': 'BUSY',
      });
      expect(api.commands.length, 1);
    },
  );

  test(
    'Linux automatic resume waits for inventory and does not replace a missing adapter',
    () {
      final saved = _Api();
      saved.state.addAll({
        'pairId': _token,
        'operationalEligible': true,
        'adapterSelectionStatus': 'LOADING',
      });
      saved.state['capabilities'] = {
        'platform': 'linux',
        'adapterSelection': true,
      };
      final restored = RustBridgeTransport(saved, pollInterval: null);
      restored.refresh();
      expect(saved.commands, isEmpty);
      saved.state['adapterSelectionStatus'] = 'READY';
      restored.refresh();
      restored.refresh();
      expect(saved.commands, [
        {'method': 'resume'},
      ]);
      restored.dispose();
      final missing = _Api();
      missing.state.addAll({
        'pairId': _token,
        'operationalEligible': true,
        'adapterSelectionStatus': 'MISSING',
      });
      missing.state['capabilities'] = {
        'platform': 'linux',
        'adapterSelection': true,
      };
      final unavailable = RustBridgeTransport(missing, pollInterval: null);
      unavailable.refresh();
      expect(missing.commands, isEmpty);
      unavailable.dispose();
    },
  );

  test(
    'Missing controller keeps the Watch identity while disabling connection',
    () async {
      adapters(status: 'MISSING');
      api.state.addAll({
        'pairId': _token,
        'operationalEligible': true,
        'selectedAdapterId': 'aa:bb:cc:dd:ee:ff',
      });
      await observe();
      expect(connection.state.hasPair, isTrue);
      expect(connection.state.canConnect, isFalse);
      expect(connection.state.canSelectAdapter, isTrue);
      api.state['bluetoothAdapters'] = [
        adapter('bad-id', 3, 'Bad'),
        adapter('aa:bb:cc:dd:ee:02', 7, 'USB'),
      ];
      await observe();
      expect(connection.state.bluetoothAdapters.single.id, 'aa:bb:cc:dd:ee:02');
    },
  );

  testWidgets(
    'Adapter picker selects a controller, refreshes and locks during a session at compact width',
    (tester) async {
      tester.view.physicalSize = const Size(360, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      adapters();
      await tester.runAsync(observe);
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: SafeArea(
              child: ListView(
                children: [BluetoothAdapterSection(connection: connection)],
              ),
            ),
          ),
        ),
      );
      await tester.tap(
        find.byKey(const ValueKey('bluetooth-adapter-aa:bb:cc:dd:ee:02')),
      );
      await tester.pump();
      await tester.runAsync(observe);
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: SafeArea(
              child: ListView(
                children: [BluetoothAdapterSection(connection: connection)],
              ),
            ),
          ),
        ),
      );
      expect(find.byIcon(CupertinoIcons.check_mark), findsOneWidget);
      await tester.tap(find.text('Refresh Adapters'));
      await tester.pump();
      expect(api.commands.last, {'method': 'refreshAdapters'});
      final before = api.commands.length;
      api.state['adapterSelectionBusy'] = true;
      await tester.runAsync(observe);
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: SafeArea(
              child: ListView(
                children: [BluetoothAdapterSection(connection: connection)],
              ),
            ),
          ),
        ),
      );
      expect(
        find.text(
          'Disconnect your Watch before changing the Bluetooth adapter.',
        ),
        findsOneWidget,
      );
      await tester.tap(find.text('Built-in'));
      await tester.pump();
      expect(api.commands.length, before);
      expect(tester.takeException(), isNull);
    },
  );

  test(
    'Linux restores its confirmed pair once without restarting a stopped session',
    () async {
      final saved = _Api();
      saved.state.addAll({
        'pairId': _token,
        'activationConfirmed': true,
        'operationalEligible': true,
      });
      saved.state['capabilities'] = {
        'platform': 'linux',
        'ownerConfirmation': true,
        'pairing': true,
        'operationalIds': true,
      };
      final restored = RustBridgeTransport(saved, pollInterval: null);
      restored.refresh();
      restored.refresh();
      expect(saved.commands, [
        {'method': 'resume'},
      ]);
      await restored.invoke('disconnectWatch');
      restored.refresh();
      expect(saved.commands.where((c) => c['method'] == 'resume').length, 1);
      restored.dispose();
    },
  );

  test(
    'Owner confirmation requires a platform capability and an activated pair',
    () async {
      expect(await transport.invoke('confirmSetup'), {
        'status': 'WATCH_PROTOCOL_UNAVAILABLE',
      });
      api.state['capabilities'] = {
        'platform': 'linux',
        'ownerConfirmation': true,
        'pairing': true,
        'operationalIds': true,
      };
      expect(await transport.invoke('confirmSetup'), {'status': 'REJECTED'});
      api.state.addAll({
        'pairId': _token,
        'activationConfirmed': true,
        'phase': 'WAITING_FOR_WATCH',
      });
      expect(await transport.invoke('confirmSetup'), {'status': 'QUEUED'});
      expect(api.commands.last, {'method': 'confirmSetup'});
      expect(connection.state.connected, isFalse);
    },
  );

  test(
    'Only observed setup watches are selectable; queue/BLE cannot create a pair',
    () async {
      expect(connection.state.canDiscover, isTrue);
      expect(await connection.discover(), 'QUEUED');
      expect(connection.state.setupPhase, 'IDLE');
      api.discovering();
      await observe();
      expect(connection.state.discoveredWatches.single.token, _token);
      expect(await connection.selectWatch(_token), 'QUEUED');
      expect(api.commands.last, {'method': 'connect', 'id': _id});
      expect(connection.state.hasPair, isFalse);
      api.state.addAll({'phase': 'BLUETOOTH_LINK', 'selectedId': _id});
      await observe();
      expect(connection.state.modelName, 'Apple Watch Ultra 2');
      expect(connection.state.bluetoothLink, isTrue);
      expect(connection.state.connected, isFalse);
      expect(connection.state.busy, isFalse);
      expect(connection.state.setupWorking, isFalse);
      expect(connection.state.canPair, isFalse);
      expect(await connection.selectWatch(_token), 'REJECTED');
      expect(
        await connection.pairOptically(_token, previousPair: null),
        'REJECTED',
      );
      expect(api.commands.length, 2);
    },
  );

  test(
    'Restricted native pairing stays visible without enabling a PIN or ready state',
    () async {
      api.state['capabilities'] = {
        'discovery': true,
        'pairing': false,
        'operationalIds': false,
        'pairingUnavailableReason': 'MACOS_PAIRING_BOOTSTRAP_RESTRICTED',
      };
      api.state.addAll({'phase': 'PIN_REQUIRED', 'pinRequired': true});
      await observe();
      expect(connection.state.backendError, contains('macOS restricts'));
      expect(connection.state.canPair, isFalse);
      expect(connection.state.pinRequired, isFalse);
      expect(connection.state.connected, isFalse);
      expect(await transport.invoke('submitPin', {'pin': '123456'}), {
        'status': 'WATCH_PROTOCOL_UNAVAILABLE',
      });
      expect(api.commands, isEmpty);
      api.state.addAll({
        'phase': 'PIN_AUTHENTICATED',
        'pinRequired': false,
        'pairingStreamOpen': true,
        'capabilities': {
          'discovery': true,
          'pairing': true,
          'operationalIds': false,
        },
      });
      await observe();
      expect(connection.state.setupPhase, 'BOND_REQUIRED');
      expect(connection.state.setupWorking, isFalse);
      expect(connection.state.hasPair, isFalse);
      expect(connection.state.connected, isFalse);
      expect(connection.state.bluetoothLink, isTrue);
    },
  );

  test(
    'Transport rechecks native discovery before selection and rejects stale tokens',
    () async {
      api.discovering();
      await observe();
      api.state.addAll({'epoch': 2, 'devices': <Map<String, dynamic>>[]});
      expect(await connection.selectWatch(_token), 'REJECTED');
      expect(api.commands, isEmpty);
    },
  );

  test(
    'Readiness requires native operational services and a persisted identity',
    () async {
      api.state.addAll({'phase': 'OPERATIONAL', 'watchReady': true});
      await observe();
      expect(connection.state.connected, isFalse);
      api.state['capabilities'] = {'operationalIds': true};
      await observe();
      expect(connection.state.connected, isFalse);
      api.state['pairId'] = 'native-persisted-pair';
      await observe();
      expect(connection.state.connected, isTrue);
      expect(connection.state.features.nativeFaces, isFalse);
      expect(connection.state.features.watchSettings, isFalse);
      expect(await bridge.invokeBridgeMethod('addNativeFace'), {
        'status': 'UNSUPPORTED_OPERATION',
      });
      api.state.addAll({
        'phase': 'RECONNECTING',
        'watchReady': false,
        'operationalEligible': true,
      });
      await observe();
      expect(connection.state.connected, isFalse);
      expect(connection.state.setupWorking, isFalse);
      expect(connection.state.operationalEligible, isTrue);
    },
  );

  test(
    'API mismatch and command failures remain visible and release ownership once',
    () async {
      api.state['apiVersion'] = 2;
      await observe();
      expect(connection.state.bridgeAvailable, isFalse);
      expect(
        connection.state.backendError,
        contains('Unsupported Rust core API'),
      );
      expect(await connection.command('discoverWatches'), 'UNAVAILABLE');
      expect(api.commands, isEmpty);
      api.state['apiVersion'] = 1;
      await observe();
      api.throwOn = 'scan';
      expect(await connection.discover(), 'UNAVAILABLE');
      await Future<void>.delayed(Duration.zero);
      expect(connection.state.backendError, 'Rust core command failed');
      bridge.dispose();
      bridge.dispose();
      transport.refresh();
      expect(api.commands.where((c) => c['method'] == 'stop').length, 1);
      expect(await transport.invoke('discoverWatches'), {
        'status': 'UNAVAILABLE',
      });
    },
  );

  test(
    'Windows uses the native core and keeps a BLE link separate from pairing',
    () async {
      var loaded = false;
      api.state.addAll({
        'phase': 'BLUETOOTH_LINK',
        'adapterState': 'POWERED_ON',
        'capabilities': {
          'platform': 'windows',
          'backend': 'windows_winrt',
          'discovery': true,
          'bluetoothLink': true,
          'pairing': false,
          'operationalIds': false,
          'pairingUnavailableReason': 'WINDOWS_PAIRING_BOOTSTRAP_RESTRICTED',
        },
      });
      final windows = createCompanionBackend(
        platform: TargetPlatform.windows,
        coreFactory: () {
          loaded = true;
          return api;
        },
      );
      await Future<void>.delayed(Duration.zero);
      expect(loaded, isTrue);
      expect(windows.platform.opticalPairing, isFalse);
      expect(windows.connectionState.bridgeAvailable, isTrue);
      expect(windows.connectionState.discoveryAvailable, isTrue);
      expect(windows.connectionState.bluetoothLink, isTrue);
      expect(windows.connectionState.connected, isFalse);
      expect(windows.connectionState.hasPair, isFalse);
      expect(windows.connectionState.pairingAvailable, isFalse);
      expect(windows.connectionState.backendError, contains('Windows'));
      expect(await windows.invokeBridgeMethod('beginPairing'), {
        'status': 'WATCH_PROTOCOL_UNAVAILABLE',
      });
      expect(api.commands, isEmpty);
      windows.dispose();
    },
  );

  test(
    'Failed Windows loading never falls back to Android or enables the camera',
    () async {
      final unavailable = createCompanionBackend(
        platform: TargetPlatform.windows,
        coreFactory: () => throw StateError('Test loading failure'),
      );
      await Future<void>.delayed(Duration.zero);
      expect(unavailable.platform.opticalPairing, isFalse);
      expect(unavailable.connectionState.bridgeAvailable, isFalse);
      expect(
        unavailable.connectionState.backendError,
        contains('Test loading failure'),
      );
      expect(await unavailable.invokeBridgeMethod('beginPairing'), {
        'status': 'UNAVAILABLE',
      });
      unavailable.dispose();
    },
  );

  test(
    'Windows USB selection reaches the protocol and never infers readiness from a PIN',
    () async {
      const usb = 'winusb:2:4:0bda:b00e';
      api.state['capabilities'] = {
        'platform': 'windows',
        'backend': 'usb_hci_shared_protocol',
        'adapterSelection': true,
        'discovery': true,
        'pairing': true,
        'operationalIds': true,
      };
      api.state.addAll({
        'bluetoothAdapters': [
          {
            'id': usb,
            'address': usb,
            'name': 'Realtek',
            'index': 0,
            'controller': 'usb0',
          },
        ],
        'adapterSelectionStatus': 'REQUIRED',
        'journal': 'PIN_REQUIRED: enter the six-digit code',
        'logPath': r'C:\Users\HP\AppData\Local\watch-companion\protocol.log',
      });
      await observe();
      expect(connection.state.bluetoothAdapters.single.id, usb);
      expect(connection.state.canDiscover, isFalse);
      expect(await connection.selectAdapter(usb), 'APPLIED');
      expect(api.commands.last, {'method': 'selectAdapter', 'id': usb});
      api.state.addAll({'phase': 'PIN_REQUIRED', 'pinRequired': true});
      await observe();
      expect(connection.state.pinRequired, isTrue);
      expect(connection.state.hasPair, isFalse);
      expect(connection.state.connected, isFalse);
      expect(connection.state.journal, contains('protocol.log'));
      expect(connection.state.journal, contains('PIN_REQUIRED: enter'));
      api.state.addAll({
        'phase': 'OPERATIONAL',
        'watchReady': true,
        'pairId': _token,
      });
      await observe();
      expect(connection.state.connected, isTrue);
    },
  );

  for (final size in [const Size(1040, 760), const Size(740, 560)]) {
    testWidgets(
      'Shared phone UI on Mac at $size; scan and PIN use the common screens',
      (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        SharedPreferences.setMockInitialValues({});
        final storage = SettingsStorage(await SharedPreferences.getInstance());
        await tester.pumpWidget(
          CompanionScope(
            storage: storage,
            bridgeFactory: () => bridge,
            faceApiFactory: _Faces.new,
            child: const AppleWatchCompanionApp(),
          ),
        );
        await tester.pump(const Duration(milliseconds: 200));
        expect(find.text('My Watch'), findsWidgets);
        expect(find.text('Face Gallery'), findsWidgets);
        expect(find.text('Discover'), findsWidgets);
        expect(
          tester.widget<CupertinoTabBar>(find.byType(CupertinoTabBar)).items,
          hasLength(2),
        );
        await tester.tap(find.text('All Watches'));
        await tester.pumpAndSettle();
        expect(find.text('Pair with Camera'), findsNothing);
        expect(find.text('No paired watch'), findsOneWidget);
        await tester.ensureVisible(find.text('Find a Watch'));
        await tester.tap(find.text('Find a Watch'));
        await tester.pump();
        expect(api.commands.last['method'], 'scan');
        api.discovering();
        await tester.runAsync(observe);
        await tester.pump();
        expect(connection.state.discoveredWatches.single.token, _token);
        await tester.ensureVisible(find.text('Apple Watch Ultra 2'));
        expect(find.textContaining('Apple Watch Ultra 2'), findsWidgets);
        expect(find.text('Pair with Camera'), findsNothing);
        // Only an observed native challenge reveals the existing phone PIN form.
        api.state.addAll({
          'phase': 'PIN_REQUIRED',
          'pinRequired': true,
          'capabilities': {'discovery': true, 'pairing': true},
        });
        await tester.runAsync(observe);
        await tester.pump();
        await tester.ensureVisible(find.byType(CupertinoTextField));
        await tester.enterText(find.byType(CupertinoTextField), '123456');
        await tester.pump();
        await tester.ensureVisible(find.text('Send code'));
        await tester.tap(find.text('Send code'));
        await tester.pump();
        expect(api.commands.last, {'method': 'submitPin', 'pin': '123456'});
        expect(
          tester
              .widget<CupertinoTextField>(find.byType(CupertinoTextField))
              .controller!
              .text,
          '',
        );
        expect(connection.state.connected, isFalse);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        await tester.pump();
        expect(api.commands.where((c) => c['method'] == 'stop').length, 1);
      },
    );
  }
}
