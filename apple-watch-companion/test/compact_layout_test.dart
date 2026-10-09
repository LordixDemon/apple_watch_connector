import 'dart:convert';
import 'dart:ui' show SemanticsAction;

import 'package:apple_watch_companion/main.dart';
import 'package:apple_watch_companion/providers/watch_connection_provider.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/native_gallery_collections.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:apple_watch_companion/widgets/ios_list_tile.dart';
import 'package:apple_watch_companion/widgets/watch_connection_card.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_library_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_grid_screen.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _EmptyCatalog extends http.BaseClient {
  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async =>
      http.StreamedResponse(Stream.value(utf8.encode('{"faces": []}')), 200);
}

void main() {
  for (final (size, scale) in [
    (const Size(320, 568), 1.0),
    (const Size(320, 568), 1.5),
    (const Size(320, 568), 2.0),
    (const Size(411, 905), 2.0),
  ]) {
    testWidgets('Phone layout $size with text scale $scale', (tester) async {
      final semantics = tester.ensureSemantics();
      await tester.runAsync(NativeGalleryCollections.load);
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = size;
      tester.view.padding = const FakeViewPadding(top: 48, bottom: 24);
      tester.platformDispatcher.textScaleFactorTestValue = scale;
      addTearDown(tester.view.reset);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
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
      final api = WatchFaceApiService(client: _EmptyCatalog());
      await tester.pumpWidget(
        MultiProvider(
          providers: [
            ChangeNotifierProvider(
              create: (_) => WatchProvider(
                storage: SettingsStorage(prefs),
                bridge: bridge,
                apiService: api,
              ),
            ),
            ChangeNotifierProvider(
              create: (_) => WatchConnectionProvider(bridge: bridge),
            ),
          ],
          child: const AppleWatchCompanionApp(),
        ),
      );
      await tester.pump(const Duration(milliseconds: 200));
      expect(tester.takeException(), isNull);
      final bar = find.byType(CupertinoSliverNavigationBar);
      final card = find.byType(WatchConnectionCard);
      // Content must not receive the status-bar inset a second time.
      expect(
        tester.getTopLeft(card).dy -
            tester.renderObject<RenderSliver>(bar).geometry!.paintExtent,
        inInclusiveRange(0, 12),
      );
      await tester.tap(find.text('All Watches'));
      await tester.pumpAndSettle();
      expect(find.text('Bridge is unavailable'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byType(CupertinoNavigationBarBackButton));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Edit'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Edit'));
      await tester.pumpAndSettle();
      expect(find.byType(NativeFaceLibraryScreen), findsOneWidget);
      // Original Edit is a presented library, above the app's tabs.
      expect(find.byType(CupertinoTabBar), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(find.byType(CupertinoTabBar), findsOneWidget);
      await tester.tap(find.text('Face Gallery').last);
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.bySemanticsLabel('Import Watch Face'), findsOneWidget);
      expect(find.text('ULTRA'), findsNothing);
      await tester.runAsync(() async {
        await Future<void>.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();
      await tester.tap(find.text('See All Watch Faces'));
      await tester.pumpAndSettle();
      expect(find.byType(NativeFaceGridScreen), findsOneWidget);
      expect(find.byType(CupertinoTabBar), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byType(CupertinoNavigationBarBackButton));
      await tester.pumpAndSettle();
      final editableSemantics = find
          .descendant(
            of: find.byType(CupertinoTextField),
            matching: find.byType(Semantics),
          )
          .first;
      expect(
        tester.getSemantics(editableSemantics).getSemanticsData().label,
        'Search',
      );
      await tester.enterText(
        find.byType(CupertinoSearchTextField),
        'no matching native family',
      );
      await tester.pump();
      final search = tester.getSemantics(editableSemantics);
      expect(search.getSemanticsData().label, 'Search');
      final children = <SemanticsData>[];
      search.visitChildren((node) {
        children.add(node.getSemanticsData());
        return true;
      });
      final input = children.singleWhere((s) => s.flagsCollection.isTextField);
      expect(input.value, 'no matching native family');
      expect(input.hasAction(SemanticsAction.setText), isTrue);
      // Naming the search group must not merge away its clear button.
      expect(children.where((s) => s.flagsCollection.isButton), hasLength(1));
      await tester.runAsync(() async {
        await Future<void>.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();
      expect(find.text('Local Design Library'), findsNothing);
      expect(find.text('ULTRA'), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Discover').last);
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.text('WATCH COMPANION'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      bridge.dispose();
      semantics.dispose();
    });
  }

  testWidgets('Long setting values reflow; compact rows remain tappable', (
    tester,
  ) async {
    await tester.pumpWidget(
      CupertinoApp(
        home: MediaQuery(
          data: const MediaQueryData(textScaler: TextScaler.linear(2)),
          child: Center(
            child: SizedBox(
              width: 280,
              child: IosListTile(
                title: 'Action Button',
                trailingText: 'Start an outdoor running workout',
                icon: CupertinoIcons.play,
                onTap: () {},
              ),
            ),
          ),
        ),
      ),
    );
    expect(tester.takeException(), isNull);
    expect(
      tester.getTopLeft(find.text('Start an outdoor running workout')).dy,
      greaterThan(tester.getBottomLeft(find.text('Action Button')).dy),
    );
    await tester.pumpWidget(
      const CupertinoApp(
        home: Center(
          child: SizedBox(width: 280, child: IosListTile(title: 'General')),
        ),
      ),
    );
    expect(
      tester.getSize(find.byType(IosListTile)).height,
      greaterThanOrEqualTo(48),
    );
  });
}
