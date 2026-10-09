import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_photo_crop.dart';
import 'package:apple_watch_companion/widgets/native_photo_crop_view.dart';

void main() {
  test('Pinch keeps the focal source pixel fixed at any viewport size', () {
    for (final viewport in [const Size(100, 125), const Size(250, 312.5)]) {
      const before = NativePhotoCrop(aspect: .8);
      final focal = Offset(viewport.width * .3, viewport.height * .7);
      final next = before.transform(
        width: 1200,
        height: 1600,
        viewport: viewport,
        previousFocalPoint: focal,
        focalPoint: focal,
        scale: 2,
      );
      final a = before.rect(1200, 1600), b = next.rect(1200, 1600);
      expect(next.zoom, 2);
      expect(b.left + .3 * b.width, closeTo(a.left + .3 * a.width, 1e-8));
      expect(b.top + .7 * b.height, closeTo(a.top + .7 * a.height, 1e-8));
    }
  });

  test('Pan follows the finger and stays bounded across aspect ratios', () {
    for (final size in [(1200, 1600), (1600, 500)]) {
      const before = NativePhotoCrop(zoom: 2);
      const viewport = Size(200, 250);
      final a = before.rect(size.$1, size.$2);
      final next = before.transform(
        width: size.$1,
        height: size.$2,
        viewport: viewport,
        previousFocalPoint: const Offset(100, 100),
        focalPoint: const Offset(120, 125),
        scale: 1,
      );
      final b = next.rect(size.$1, size.$2);
      expect(b.left, closeTo(a.left - a.width * .1, 1e-8));
      expect(b.top, closeTo(a.top - a.height * .1, 1e-8));
      var edge = next;
      for (var i = 0; i < 20; i++) {
        edge = edge.transform(
          width: size.$1,
          height: size.$2,
          viewport: viewport,
          previousFocalPoint: const Offset(100, 100),
          focalPoint: const Offset(-100, -100),
          scale: 2,
        );
      }
      final last = edge.rect(size.$1, size.$2);
      expect(edge.zoom, 4);
      expect(last.right, lessThanOrEqualTo(size.$1));
      expect(last.bottom, lessThanOrEqualTo(size.$2));
      expect(last.left, greaterThanOrEqualTo(0));
      expect(last.top, greaterThanOrEqualTo(0));
    }
  });

  test('Zoom boundaries allow immediate reversal and reject invalid input', () {
    const before = NativePhotoCrop(zoom: 4);
    NativePhotoCrop update(
      double scale, {
      Size viewport = const Size(250, 300),
    }) => before.transform(
      width: 1000,
      height: 1300,
      viewport: viewport,
      previousFocalPoint: const Offset(125, 150),
      focalPoint: const Offset(125, 150),
      scale: scale,
    );
    expect(update(2).zoom, 4);
    expect(update(.5).zoom, 2);
    expect(update(.01).zoom, 1);
    expect(update(double.nan), same(before));
    expect(update(0), same(before));
    expect(update(1, viewport: Size.zero), same(before));
  });

  testWidgets(
    'Pinch and drag repaint only the crop and disabled crop is inert',
    (tester) async {
      final recorder = PictureRecorder();
      Canvas(recorder).drawColor(const Color(0xff304050), BlendMode.src);
      final picture = recorder.endRecording();
      final source = await tester.runAsync(() => picture.toImage(400, 600));
      picture.dispose();
      final controller = ValueNotifier(const NativePhotoCrop());
      var parentBuilds = 0;
      Widget show(bool enabled) => CupertinoApp(
        home: Builder(
          builder: (_) {
            parentBuilds++;
            return Center(
              child: SizedBox(
                width: 200,
                child: NativePhotoCropView(
                  source: source!,
                  controller: controller,
                  enabled: enabled,
                ),
              ),
            );
          },
        ),
      );
      await tester.pumpWidget(show(true));
      final initialBuilds = parentBuilds;
      final center = tester.getCenter(find.byType(NativePhotoCropView));
      final a = await tester.startGesture(
        center - const Offset(30, 0),
        pointer: 1,
      );
      final b = await tester.startGesture(
        center + const Offset(30, 0),
        pointer: 2,
      );
      await tester.pump();
      await a.moveTo(center - const Offset(40, 0));
      await b.moveTo(center + const Offset(40, 0));
      await tester.pump();
      // Continue after the scale recognizer has accepted both pointers.
      await a.moveTo(center - const Offset(70, 0));
      await b.moveTo(center + const Offset(70, 0));
      await tester.pump();
      expect(controller.value.zoom, greaterThan(1));
      expect(parentBuilds, initialBuilds);
      await a.up();
      await b.up();
      await tester.pump();
      final beforeDrag = controller.value.x;
      await tester.drag(find.byType(NativePhotoCropView), const Offset(25, 0));
      await tester.pump();
      expect(controller.value.x, lessThan(beforeDrag));
      expect(parentBuilds, initialBuilds);
      await tester.pumpWidget(show(false));
      final disabled = controller.value;
      await tester.drag(find.byType(NativePhotoCropView), const Offset(-25, 0));
      await tester.pump();
      expect(controller.value, same(disabled));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
      source!.dispose();
    },
  );
}
