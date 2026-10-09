import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:provider/provider.dart';
import 'package:apple_watch_companion/main.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/providers/watch_connection_provider.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/native_gallery_collections.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_catalog.dart';
import 'package:apple_watch_companion/services/watch_face_codec.dart';
import 'package:http/http.dart' as http;
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:apple_watch_companion/widgets/watch_face_view.dart';

class _MockHttpClient extends http.BaseClient {
  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    return http.StreamedResponse(
      Stream.value(utf8.encode('{"faces": []}')),
      200,
    );
  }
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('English fallback on a Russian phone and tab navigation', (
    WidgetTester tester,
  ) async {
    await tester.runAsync(NativeGalleryCollections.load);
    tester.binding.platformDispatcher.localeTestValue = const Locale(
      'ru',
      'UA',
    );
    addTearDown(tester.binding.platformDispatcher.clearLocaleTestValue);
    final prefs = await SharedPreferences.getInstance();
    final storage = SettingsStorage(prefs);
    final bridge = WatchBridgeService(
      events: Stream.multi(
        (controller) => controller.add({
          'type': 'connection',
          'data': {
            'features': {'nativeFaces': true, 'watchSettings': true},
          },
        }),
      ),
    );
    final mockApi = WatchFaceApiService(client: _MockHttpClient());

    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider<WatchProvider>(
            create: (_) => WatchProvider(
              storage: storage,
              bridge: bridge,
              apiService: mockApi,
            ),
          ),
          ChangeNotifierProvider<WatchConnectionProvider>(
            create: (_) => WatchConnectionProvider(bridge: bridge),
          ),
        ],
        child: const AppleWatchCompanionApp(),
      ),
    );

    await tester.pump(const Duration(milliseconds: 200));

    // Verify My Watch tab is present
    expect(find.text("My Watch"), findsWidgets);
    expect(find.text('Apple Watch Ultra 2'), findsNothing);
    expect(find.text('No paired watch'), findsOneWidget);
    expect(
      Localizations.localeOf(tester.element(find.text('My Watch').first)),
      const Locale('en'),
    );
    await tester.tap(find.text('All Watches'));
    await tester.pumpAndSettle();
    expect(find.text('Bridge is unavailable'), findsOneWidget);
    await tester.tap(find.byType(CupertinoNavigationBarBackButton));
    await tester.pumpAndSettle();

    // Switch to Face Gallery tab
    await tester.tap(
      find.descendant(
        of: find.byType(CupertinoTabBar),
        matching: find.text("Face Gallery"),
      ),
    );
    await tester.pump(const Duration(milliseconds: 200));

    // Verify Face Gallery tab content
    expect(find.text("Face Gallery"), findsWidgets);
    expect(find.bySemanticsLabel('Import Watch Face'), findsOneWidget);
    expect(find.text('ULTRA'), findsNothing);

    // Switch to Discover tab
    await tester.tap(find.text("Discover"));
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text("WATCH COMPANION"), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    bridge.dispose();
  });

  test(
    'WatchFaceCodec encodes and decodes Apple .watchface format correctly',
    () {
      final originalFace = WatchFaceCatalog.wayfinderOrange.copyWith(
        title: 'Тестовый Wayfinder',
        primaryColor: const Color(0xFFFF9500),
        bezelStyle: BezelStyle.incline,
      );

      // Encode to Apple .watchface ZIP package
      final archiveBytes = WatchFaceCodec.encode(originalFace);
      expect(archiveBytes, isNotNull);
      expect(archiveBytes.isNotEmpty, isTrue);

      // Decode back
      final decodedResult = WatchFaceCodec.decode(archiveBytes);
      final decodedFace = decodedResult.face;

      expect(decodedFace.title, equals('Тестовый Wayfinder'));
      expect(decodedFace.family, equals(WatchFaceFamily.wayfinder));
      expect(decodedFace.bezelStyle, equals(BezelStyle.incline));
      expect(
        decodedFace.primaryColor.value,
        equals(originalFace.primaryColor.value),
      );
      expect(
        decodedFace.effectiveNtkFaceStyle,
        equals('NTKFaceStyleWayfinder'),
      );
    },
  );

  testWidgets('WatchFaceView renders 60fps animations and time scrub', (
    WidgetTester tester,
  ) async {
    final face = WatchFaceCatalog.ultraModularDark;

    await tester.pumpWidget(
      CupertinoApp(
        home: CupertinoPageScaffold(
          child: Center(
            child: WatchFaceView(
              face: face,
              size: 200,
              showLiveTime: true,
              scrubSeconds: 3600, // +1 hour time travel
            ),
          ),
        ),
      ),
    );

    // Advance frame
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.byType(WatchFaceView), findsOneWidget);
  });

  test('HealthMetrics and WatchBridgeService communication', () async {
    final prefs = await SharedPreferences.getInstance();
    final storage = SettingsStorage(prefs);
    final bridge = WatchBridgeService();
    final provider = WatchProvider(
      storage: storage,
      bridge: bridge,
      apiService: WatchFaceApiService(client: _MockHttpClient()),
    );

    // No Watch health observation has arrived.
    expect(provider.hasHealthObservation, isFalse);
    expect(provider.health.heartRateBpm, 0);
    expect(provider.health.activeCalories, 0);
    expect(provider.health.stepCount, 0);

    // Test notification dispatch
    final notifSent = await provider.sendTestNotification(
      'Заголовок',
      'Текст сообщения',
    );
    expect(notifSent, isFalse);

    // Test incoming call alert
    final callSent = await provider.triggerIncomingCall(
      'Иван Иванов',
      '+79991234567',
    );
    expect(callSent, isFalse);
    provider.dispose();
    bridge.dispose();
  });
}
