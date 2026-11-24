import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show SelectableText;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:apple_watch_companion/app/companion_app.dart';
import 'package:apple_watch_companion/controllers/watch_observation_controller.dart';
import 'package:apple_watch_companion/l10n/strings.dart';
import 'package:apple_watch_companion/models/health_metrics.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/providers/watch_connection_provider.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/screens/connection/watch_connection_screen.dart';
import 'package:apple_watch_companion/screens/connection/watch_diagnostics_screen.dart';
import 'package:apple_watch_companion/screens/discover/discover_tab.dart';
import 'package:apple_watch_companion/screens/discover/gesture_guide_screen.dart';
import 'package:apple_watch_companion/screens/my_watch/about_device_screen.dart';
import 'package:apple_watch_companion/screens/my_watch/health_activity_screen.dart';
import 'package:apple_watch_companion/screens/my_watch/notifications_screen.dart';
import 'package:apple_watch_companion/services/bridge_observation.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/services/native_gallery_collections.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:apple_watch_companion/services/watch_face_cache.dart';
import 'package:apple_watch_companion/widgets/watch_features_panel.dart';

class _TestTransport implements BridgeTransport {
  final controller = StreamController<dynamic>.broadcast();
  final calls = <(String, Map<String, dynamic>?)>[];
  @override
  Stream<dynamic> get events => controller.stream;

  @override
  Future<dynamic> invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    calls.add((method, arguments));
    return {'status': 'QUEUED'};
  }

  void publish(Map<String, dynamic> data) =>
      controller.add({'type': 'connection', 'data': data});
}

class _NoNetworkFaces extends WatchFaceApiService {
  @override
  Future<List<WatchFace>> loadCachedFaces() async => [];
  @override
  Future<List<WatchFace>> fetchRemoteFaces({String endpoint = ''}) async => [];
}

class _ZeroGoalProvider extends WatchProvider {
  _ZeroGoalProvider({required super.storage, required super.bridge})
    : super(apiService: _NoNetworkFaces());
  @override
  bool get hasHealthObservation => true;
  @override
  HealthMetrics get health => HealthMetrics.initial();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    Strings.resetToDefault();
  });

  tearDown(Strings.resetToDefault);

  test(
    'WatchConnectionProvider suppresses UI rebuilds on journal-only updates and notifies once on command completion',
    () async {
      final transport = _TestTransport();
      final bridge = WatchBridgeService(transport: transport);
      final provider = WatchConnectionProvider(bridge: bridge);
      var notifications = 0;
      provider.addListener(() => notifications++);

      transport.publish({
        'bridgeAvailable': true,
        'connected': true,
        'pairId': 'pair-1',
        'bridgeJournal': 'line 1',
      });
      await Future<void>.delayed(Duration.zero);
      expect(notifications, 1);
      expect(provider.journalListenable.value, 'line 1');

      // Journal-only updates update journalListenable without rebuilding WatchConnectionProvider listeners.
      for (var i = 2; i <= 5; i++) {
        transport.publish({
          'bridgeAvailable': true,
          'connected': true,
          'pairId': 'pair-1',
          'bridgeJournal': 'line $i',
        });
      }
      await Future<void>.delayed(Duration.zero);
      expect(notifications, 1);
      expect(provider.journalListenable.value, 'line 5');
      expect(provider.state.journal, 'line 5');

      // Command emits once for sending=true and once on completion (2 total, not 3).
      notifications = 0;
      expect(await provider.command('disconnectWatch'), 'QUEUED');
      expect(notifications, 2);

      provider.dispose();
      bridge.dispose();
      await transport.controller.close();
    },
  );

  test(
    'WatchObservationController skips redundant notifications when projected data is unchanged',
    () async {
      final transport = _TestTransport();
      final bridge = WatchBridgeService(transport: transport);
      final controller = WatchObservationController(bridge: bridge);
      var changes = 0;
      controller.addListener(() => changes++);

      final snapshot = {
        'bridgeAvailable': true,
        'connected': true,
        'pairId': 'pair-1',
        'productType': 'Watch7,5',
        'buildVersion': '23S303',
        'bridgeJournal': 'tick 1',
      };
      transport.publish(snapshot);
      await Future<void>.delayed(Duration.zero);
      expect(changes, 1);

      transport.publish({...snapshot, 'bridgeJournal': 'tick 2'});
      await Future<void>.delayed(Duration.zero);
      expect(changes, 1);

      controller.dispose();
      bridge.dispose();
      await transport.controller.close();
    },
  );

  test(
    'BridgeObservation decodes watchOsVersion from buildVersion and softwareUpdate',
    () {
      final fromBuild = BridgeObservation.decode({
        'connected': true,
        'pairId': 'pair-1',
        'productType': 'Watch7,5',
        'buildVersion': '23S303',
      });
      expect(fromBuild.device.watchOsVersion, '23S303');

      const pair = '11111111-1111-4111-8111-111111111111';
      const epoch = '22222222-2222-4222-8222-222222222222';
      final fromUpdate = BridgeObservation.decode({
        'connected': true,
        'pairId': pair,
        'productType': 'Watch7,5',
        'buildVersion': '23S303',
        'faceCollectionEpoch': epoch,
        'features': {'softwareUpdates': true},
        'softwareUpdate': {
          'pairId': pair,
          'epoch': epoch,
          'known': true,
          'phase': 'IDLE',
          'observedAt': 100000,
          'scanCompleted': true,
          'installAttempted': false,
          'stalled': false,
          'termsAccepted': true,
          'installedVersion': '26.2',
          'installedBuild': '23S303',
        },
      });
      expect(fromUpdate.device.watchOsVersion, '26.2 (23S303)');
    },
  );

  test('WatchFaceCache.sanitizeFileStem neutralizes path traversal', () {
    expect(
      WatchFaceCache.sanitizeFileStem('../../etc/passwd'),
      '____etc_passwd',
    );
    expect(WatchFaceCache.sanitizeFileStem('..'), 'face');
    expect(WatchFaceCache.sanitizeFileStem('.hidden'), 'hidden');
    expect(WatchFaceCache.sanitizeFileStem('local_123'), 'local_123');
  });

  testWidgets('HealthActivityScreen handles zero goals without NaN crash', (
    tester,
  ) async {
    final transport = _TestTransport();
    final bridge = WatchBridgeService(transport: transport);
    final storage = SettingsStorage(await SharedPreferences.getInstance());
    final provider = _ZeroGoalProvider(storage: storage, bridge: bridge);

    await tester.pumpWidget(
      ChangeNotifierProvider<WatchProvider>.value(
        value: provider,
        child: const CupertinoApp(home: HealthActivityScreen()),
      ),
    );
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.text('0 / 0 kcal'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    provider.dispose();
    bridge.dispose();
    await transport.controller.close();
  });

  testWidgets(
    'MainNavigationScreen preserves MyWatch navigation stack when nativeFaces toggles',
    (tester) async {
      await tester.runAsync(NativeGalleryCollections.load);
      final transport = _TestTransport();
      final bridge = WatchBridgeService(transport: transport);
      final storage = SettingsStorage(await SharedPreferences.getInstance());
      final provider = WatchProvider(
        storage: storage,
        bridge: bridge,
        apiService: _NoNetworkFaces(),
      );
      final connection = WatchConnectionProvider(bridge: bridge);

      await tester.pumpWidget(
        MultiProvider(
          providers: [
            ChangeNotifierProvider<WatchProvider>.value(value: provider),
            ChangeNotifierProvider<WatchConnectionProvider>.value(
              value: connection,
            ),
          ],
          child: const AppleWatchCompanionApp(),
        ),
      );
      await tester.pump(const Duration(milliseconds: 200));

      await tester.tap(find.text('All Watches'));
      await tester.pumpAndSettle();
      expect(find.byType(WatchConnectionScreen), findsOneWidget);

      // Flip nativeFaces false -> true -> false while pushed inside MyWatchTab.
      transport.publish({
        'bridgeAvailable': true,
        'features': {'nativeFaces': true, 'watchSettings': true},
      });
      await tester.pump();
      expect(find.byType(WatchConnectionScreen), findsOneWidget);

      transport.publish({
        'bridgeAvailable': true,
        'features': {'nativeFaces': false, 'watchSettings': true},
      });
      await tester.pump();
      expect(find.byType(WatchConnectionScreen), findsOneWidget);
      expect(tester.takeException(), isNull);

      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      connection.dispose();
      provider.dispose();
      bridge.dispose();
      await transport.controller.close();
    },
  );

  testWidgets(
    'AboutDeviceScreen hides unknown fields and copies identifiers',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
            if (call.method == 'Clipboard.setData') return null;
            return null;
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(SystemChannels.platform, null),
      );

      final transport = _TestTransport();
      final bridge = WatchBridgeService(transport: transport);
      final storage = SettingsStorage(await SharedPreferences.getInstance());
      final provider = WatchProvider(
        storage: storage,
        bridge: bridge,
        apiService: _NoNetworkFaces(),
      );

      transport.publish({
        'bridgeAvailable': true,
        'connected': true,
        'pairId': 'pair-abc',
        'productType': 'Watch7,5',
        'buildVersion': '23S303',
      });

      await tester.pumpWidget(
        ChangeNotifierProvider<WatchProvider>.value(
          value: provider,
          child: const CupertinoApp(home: AboutDeviceScreen()),
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(find.text('23S303'), findsOneWidget);
      expect(find.text('Network Addresses'), findsNothing);
      expect(find.text('Serial Number'), findsNothing);

      await tester.tap(find.text('23S303'));
      await tester.pump();
      expect(find.text('Copied: watchOS Version'), findsOneWidget);

      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      provider.dispose();
      bridge.dispose();
      await transport.controller.close();
    },
  );

  testWidgets(
    'DiscoverTab wires live screens and WatchDiagnosticsScreen filters journal + shows features',
    (tester) async {
      final transport = _TestTransport();
      final bridge = WatchBridgeService(transport: transport);
      final storage = SettingsStorage(await SharedPreferences.getInstance());
      final provider = WatchProvider(
        storage: storage,
        bridge: bridge,
        apiService: _NoNetworkFaces(),
      );
      final connection = WatchConnectionProvider(bridge: bridge);

      transport.publish({
        'bridgeAvailable': true,
        'connected': true,
        'features': {
          'nativeFaces': true,
          'watchSettings': true,
          'notificationRelay': true,
        },
        'bridgeJournal': 'ALPHA first line\nBETA error occurred',
      });

      await tester.pumpWidget(
        MultiProvider(
          providers: [
            ChangeNotifierProvider<WatchProvider>.value(value: provider),
            ChangeNotifierProvider<WatchConnectionProvider>.value(
              value: connection,
            ),
          ],
          child: const CupertinoApp(home: DiscoverTab()),
        ),
      );
      await tester.pump();
      await tester.pump();

      await tester.tap(find.text('Gestures & Controls'));
      await tester.pumpAndSettle();
      expect(find.byType(GestureGuideScreen), findsOneWidget);
      expect(find.text('Apple Watch Controls'), findsOneWidget);
      await tester.tap(find.byType(CupertinoNavigationBarBackButton));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Notification Mirroring'));
      await tester.pumpAndSettle();
      expect(find.byType(NotificationsScreen), findsOneWidget);
      await tester.tap(find.byType(CupertinoNavigationBarBackButton));
      await tester.pumpAndSettle();

      await tester.tap(find.text('HAL Support & Diagnostics'));
      await tester.pumpAndSettle();
      expect(find.byType(WatchDiagnosticsScreen), findsOneWidget);
      expect(find.byType(WatchFeaturesPanel), findsOneWidget);

      await tester.scrollUntilVisible(
        find.byType(SelectableText),
        300,
        scrollable: find.byType(Scrollable).last,
      );
      expect(
        tester.widget<SelectableText>(find.byType(SelectableText)).data,
        contains('ALPHA first line'),
      );

      await tester.enterText(find.byType(CupertinoSearchTextField), 'beta');
      await tester.pump();
      final filteredText = tester
          .widget<SelectableText>(find.byType(SelectableText))
          .data!;
      expect(filteredText, isNot(contains('ALPHA first line')));
      expect(filteredText, contains('BETA error occurred'));

      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      connection.dispose();
      provider.dispose();
      bridge.dispose();
      await transport.controller.close();
    },
  );
}
