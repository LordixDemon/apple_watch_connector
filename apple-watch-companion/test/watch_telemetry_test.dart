import 'dart:async';
import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:provider/provider.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/models/watch_device.dart';
import 'package:apple_watch_companion/models/watch_telemetry.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/bridge_observation.dart';
import 'package:apple_watch_companion/widgets/watch_telemetry_panel.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/screens/my_watch/storage_screen.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';

class _EmptyFaceApi extends WatchFaceApiService {
  @override
  Future<List<WatchFace>> loadCachedFaces() async => [];
  @override
  Future<List<WatchFace>> fetchRemoteFaces({
    String endpoint = WatchFaceApiService.defaultGalleryEndpoint,
  }) async => [];
}

class _SnapshotBridge extends WatchBridgeService {
  _SnapshotBridge() : super(events: const Stream.empty());
  // This fixture exposes one fixed snapshot, independent of platform stream closure.
  @override
  Stream<BridgeObservation> get observationStream => const Stream.empty();
  @override
  WatchDevice get currentDevice => WatchDevice.unknown().copyWith(
    isConnected: true,
    telemetry: WatchTelemetry(
      observedAt: DateTime.now().millisecondsSinceEpoch,
      batteryLevel: 64,
      availableStorageBytes: 12345678901,
      numberOfApps: 42,
      numberOfSongs: 0,
    ),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late SharedPreferences prefsForWidget;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefsForWidget = await SharedPreferences.getInstance();
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'About snapshot preserves zero and clears absent scalars without defaults',
    () {
      final data = WatchTelemetry.fromBridge({
        'connected': true,
        'aboutObservedAt': 1000,
        'batteryLevel': 0,
        'chargingObserved': true,
        'isCharging': false,
        'availableStorageBytes': 12345678901,
        'numberOfApps': 42,
        'numberOfSongs': 0,
      });
      expect(data.batteryLevel, 0);
      expect(data.isCharging, false);
      expect(data.availableStorageBytes, 12345678901);
      expect(data.numberOfSongs, 0);
      expect(data.numberOfPhotos, isNull);
      expect(data.userDeletableSpaceBytes, isNull);
      expect(
        WatchTelemetry.fromBridge({
          'connected': true,
          'aboutObservedAt': 1001,
        }).availableStorageBytes,
        isNull,
      );
      expect(
        WatchTelemetry.fromBridge({
          'connected': false,
          'aboutObservedAt': 1001,
          'batteryLevel': 99,
        }).batteryLevel,
        isNull,
      );
    },
  );

  test(
    'Freshness uses the actual reading time and rejects invalid scalars',
    () {
      final now = DateTime.fromMillisecondsSinceEpoch(1000000);
      expect(const WatchTelemetry(observedAt: 1000000).isFreshAt(now), isTrue);
      expect(const WatchTelemetry(observedAt: 700000).isFreshAt(now), isFalse);
      expect(const WatchTelemetry(observedAt: 1000001).isFreshAt(now), isFalse);
      final data = WatchTelemetry.fromBridge({
        'connected': true,
        'aboutObservedAt': 1000,
        'batteryLevel': 101,
        'chargingObserved': false,
        'isCharging': true,
        'numberOfApps': -1,
        'numberOfSongs': '3',
        'numberOfPhotos': 1.2,
      });
      expect(data.batteryLevel, isNull);
      expect(data.isCharging, isNull);
      expect(data.numberOfApps, isNull);
      expect(data.numberOfSongs, isNull);
      expect(data.numberOfPhotos, isNull);
      expect(
        WatchTelemetry.fromBridge({
          'connected': true,
          'aboutObservedAt': 9223372036854775807,
        }).observedAt,
        isNull,
      );
      expect(WatchTelemetry.formatBytes(null), '—');
      expect(WatchTelemetry.formatBytes(0), '0 B');
      expect(WatchTelemetry.formatBytes(12345678901), "12.3 GB");
    },
  );

  test(
    'Cached telemetry cannot resurrect connection or fabricated charge after restart',
    () async {
      SharedPreferences.setMockInitialValues({
        'aw_device_v1': jsonEncode({
          'batteryLevel': 88,
          'isCharging': true,
          'isConnected': true,
        }),
      });
      final storage = SettingsStorage(await SharedPreferences.getInstance());
      final device = storage.loadDevice()!;
      expect(device.batteryLevel, -1);
      expect(device.isConnected, isFalse);
      expect(device.telemetry.observedAt, isNull);
      await storage.saveDevice(device);
      expect(storage.loadDevice()!.batteryLevel, -1);
    },
  );

  test(
    'Native snapshots replace rather than merge when fields disappear',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      var next = bridge.deviceStream.first;
      events.add({
        'type': 'connection',
        'data': {
          'connected': true,
          'aboutObservedAt': 1000,
          'batteryLevel': 77,
          'availableStorageBytes': 12345,
          'chargingObserved': true,
          'isCharging': true,
        },
      });
      expect((await next).telemetry.availableStorageBytes, 12345);
      next = bridge.deviceStream.first;
      events.add({
        'type': 'connection',
        'data': {
          'connected': true,
          'aboutObservedAt': 1001,
          'batteryLevel': 76,
        },
      });
      final replacement = await next;
      expect(replacement.telemetry.availableStorageBytes, isNull);
      expect(replacement.telemetry.isCharging, isNull);
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {'status': 'QUEUED'},
      );
      expect(await bridge.refreshDeviceInfo(), 'QUEUED');
      expect(bridge.currentDevice.telemetry.observedAt, 1001);
      bridge.dispose();
      await events.close();
    },
  );

  testWidgets(
    'Queued refresh stays pending until actual newer data or timeout',
    (tester) async {
      var now = DateTime.fromMillisecondsSinceEpoch(1000000);
      var telemetry = const WatchTelemetry(
        observedAt: 999999,
        batteryLevel: 75,
      );
      var calls = 0;
      Widget panel() => CupertinoApp(
        home: CupertinoPageScaffold(
          child: WatchTelemetryPanel(
            telemetry: telemetry,
            connected: true,
            clock: () => now,
            refresh: () async {
              calls++;
              return 'QUEUED';
            },
          ),
        ),
      );
      await tester.pumpWidget(panel());
      await tester.tap(find.text("Refresh Data"));
      await tester.pump();
      expect(calls, 1);
      expect(find.text("Waiting for watch reply…"), findsOneWidget);
      expect(find.text("Data updated"), findsNothing);
      await tester.pumpWidget(panel());
      expect(calls, 1);
      telemetry = const WatchTelemetry(
        observedAt: 1000001,
        batteryLevel: 74,
        isCharging: false,
      );
      now = DateTime.fromMillisecondsSinceEpoch(1000001);
      await tester.pumpWidget(panel());
      expect(find.text("Data updated"), findsOneWidget);
      await tester.tap(find.text("Refresh Data"));
      await tester.pump();
      now = now.add(const Duration(seconds: 60));
      await tester.pump(const Duration(seconds: 60));
      expect(
        find.text("The watch has not replied yet. Refresh again."),
        findsOneWidget,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('Stale reading is labelled and refresh unavailable offline', (
    tester,
  ) async {
    var now = DateTime.fromMillisecondsSinceEpoch(1000000);
    Widget panel(bool connected) => CupertinoApp(
      home: CupertinoPageScaffold(
        child: WatchTelemetryPanel(
          telemetry: const WatchTelemetry(
            observedAt: 1000000,
            batteryLevel: 100,
          ),
          connected: connected,
          clock: () => now,
          refresh: () async => throw StateError('unavailable'),
        ),
      ),
    );
    await tester.pumpWidget(panel(true));
    expect(find.textContaining("Battery: 100%"), findsOneWidget);
    now = now.add(const Duration(minutes: 5));
    await tester.pump(const Duration(minutes: 5));
    expect(find.textContaining("Data is stale"), findsOneWidget);
    expect(find.textContaining("Last battery: 100%"), findsOneWidget);
    await tester.tap(find.text("Refresh Data"));
    await tester.pump();
    expect(find.text("Could not send request"), findsOneWidget);
    await tester.pumpWidget(panel(false));
    expect(find.text("No connection to watch"), findsOneWidget);
    expect(
      tester.widget<CupertinoButton>(find.byType(CupertinoButton)).onPressed,
      isNull,
    );
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'Storage screen renders native counts and no fabricated capacity breakdown',
    (tester) async {
      final bridge = _SnapshotBridge();
      final provider = WatchProvider(
        storage: SettingsStorage(prefsForWidget),
        bridge: bridge,
        apiService: _EmptyFaceApi(),
      );
      await tester.pumpWidget(
        ChangeNotifierProvider<WatchProvider>.value(
          value: provider,
          child: const CupertinoApp(home: StorageScreen()),
        ),
      );
      await tester.pump();
      expect(find.text("12.3 GB"), findsOneWidget);
      expect(find.text('42'), findsOneWidget);
      expect(find.text('0'), findsOneWidget);
      expect(find.text('—'), findsWidgets);
      expect(find.textContaining("of 64 GB"), findsNothing);
      expect(find.text("3.8 GB"), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      provider.dispose();
      bridge.dispose();
    },
  );
}
