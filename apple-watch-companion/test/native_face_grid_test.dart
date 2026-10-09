import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/native_face_controller.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_grid_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_gallery_card.dart';
import 'package:apple_watch_companion/services/native_gallery_collections.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  for (final (size, scale) in [
    (const Size(280, 640), 2.4),
    (const Size(411, 905), 1.0),
    (const Size(900, 700), 1.0),
  ]) {
    testWidgets('All Faces lazy traversal and exact preset $size / $scale', (
      tester,
    ) async {
      final semantics = tester.ensureSemantics();
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = size;
      addTearDown(tester.view.reset);
      final sections = await tester.runAsync(NativeGalleryCollections.load);
      final bridge = WatchBridgeService(events: const Stream.empty());
      final controller = NativeFaceController(bridge);
      addTearDown(controller.dispose);
      addTearDown(bridge.dispose);
      NativeGalleryVariant? selected;
      await tester.pumpWidget(
        CupertinoApp(
          home: MediaQuery(
            data: MediaQueryData(
              size: size,
              textScaler: TextScaler.linear(scale),
            ),
            child: NativeFaceGridScreen(
              sections: sections!.reversed.toList(),
              controller: controller,
              onSelect: (variant) => selected = variant,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('All Watch Faces'), findsOneWidget);
      final cards = tester
          .widgetList<NativeGalleryCard>(find.byType(NativeGalleryCard))
          .toList();
      expect(cards.length, lessThan(211)); // GridView builds only nearby rows.
      expect(cards.first.variant.template.title('en'), 'Activity Analog');
      final exact = cards.first.variant;
      await tester.tap(
        find.descendant(
          of: find.byKey(const ValueKey('native-all-face-0')),
          matching: find.byType(CupertinoButton),
        ),
      );
      expect(selected, same(exact));
      expect(controller.result, NativeFaceResult.idle);

      final scrollable = tester.state<ScrollableState>(find.byType(Scrollable));
      // Lazy grids revise their estimate as rows are visited. Settle at the
      // actual tail before asserting the final preset rather than a first extent.
      for (var i = 0; i < 8; i++) {
        scrollable.position.jumpTo(scrollable.position.maxScrollExtent);
        await tester.pumpAndSettle();
        if ((scrollable.position.pixels - scrollable.position.maxScrollExtent)
                .abs() <
            1) {
          break;
        }
      }
      expect(find.byKey(const ValueKey('native-all-face-210')), findsOneWidget);
      expect(tester.takeException(), isNull);
      expect(controller.result, NativeFaceResult.idle);
      semantics.dispose();
    });
  }

  testWidgets(
    'Equal names retain native preset order; unavailable Photos is disabled',
    (tester) async {
      final sections = await tester.runAsync(NativeGalleryCollections.load);
      final california = sections!.singleWhere(
        (s) => s.title('en') == 'California',
      );
      final photos = sections.singleWhere(
        (s) => s.variants.any((v) => v.template.requiresPhotos),
      );
      final bridge = WatchBridgeService(events: const Stream.empty());
      final controller = NativeFaceController(bridge);
      addTearDown(controller.dispose);
      addTearDown(bridge.dispose);
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceGridScreen(
            sections: [photos, california],
            controller: controller,
            onSelect: (_) => fail('No preset was tapped'),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final cards = tester
          .widgetList<NativeGalleryCard>(find.byType(NativeGalleryCard))
          .toList();
      expect(cards.take(6).map((c) => c.variant), california.variants);
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('native-all-face-6')),
        200,
      );
      await tester.pumpAndSettle();
      final photoCard = tester.widget<NativeGalleryCard>(
        find.byKey(const ValueKey('native-all-face-6')),
      );
      expect(photoCard.variant, same(photos.variants.first));
      expect(photoCard.onPressed, isNull);
    },
  );
}
