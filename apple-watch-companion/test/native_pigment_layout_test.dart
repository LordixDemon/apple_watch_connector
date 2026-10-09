import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_pigment_layout.dart';
import 'package:apple_watch_companion/services/native_pigment_favorites.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_pigment_section.dart';

void main() {
  test(
    'Color row metadata is strict and older versions stay compatible',
    () async {
      final profiles = await NativeFaceGallery.load();
      final row = profiles
          .firstWhere((p) => p.family == 'type:activity analog rich')
          .pigmentSection('color')!
          .layout
          .row!;
      expect(row.height(36), 82);
      expect(row.height(127 / 3), closeTo(265 / 3, 1e-8));
      expect(row.horizontalInset(false), 16);
      expect(row.horizontalInset(true), 20);
      final valid = {
        'swatchVerticalInset': 23,
        'compactHorizontalInset': 16,
        'expandedHorizontalInset': 20,
        'headerTopInset': 6,
        'headerLabelGap': 16,
        'headerFontSize': 17,
        'headerMaxFontSize': 33,
        'headerLineHeight': 20.287109375,
      };
      for (final bad in [
        {'headerFontSize': true},
        {'headerLineHeight': double.nan},
        {'swatchVerticalInset': -1},
        {'expandedHorizontalInset': 15},
        {'headerMaxFontSize': 16},
        {'headerLineHeight': 16},
        {'unknown': 1},
      ]) {
        expect(
          () => NativePigmentRowLayout.fromJson({...valid, ...bad}),
          throwsFormatException,
        );
      }
      expect(NativePigmentLayout.fromJson(null).row, isNull);
      expect(
        NativePigmentLayout.fromJson({
          'version': 1,
          'compactDiameter': 36,
          'expandedDiameter': 127 / 3,
          'expandedAbovePhysicalPixels': 750,
        }).row,
        isNull,
      );
    },
  );
  test(
    'Picker metadata is strict and independent of small-circle sizes',
    () async {
      final profiles = await NativeFaceGallery.load();
      final layout = profiles
          .firstWhere((p) => p.family == 'type:activity analog rich')
          .pigmentSection('color')!
          .layout;
      expect(layout.picker.diameter, 42);
      expect(layout.pickerHorizontalInset(750), 16);
      expect(layout.pickerHorizontalInset(751), 20);
      expect(layout.picker.headerFontSize, 17);
      expect(layout.picker.headerMaxFontSize, 33);
      for (final bad in [
        {'diameter': true},
        {'headerGap': double.nan},
        {'topInset': -1},
        {'headerMaxFontSize': 16},
        {'expandedHorizontalInset': 15},
        {'unknown': 12},
      ]) {
        expect(
          () => NativePigmentPickerLayout.fromJson({
            'diameter': 42,
            'compactHorizontalInset': 16,
            'expandedHorizontalInset': 20,
            'topInset': 15,
            'headerGap': 12,
            'sectionSpacing': 25,
            'itemSpacing': 12,
            'headerFontSize': 17,
            'headerMaxFontSize': 33,
            ...bad,
          }),
          throwsFormatException,
        );
      }
    },
  );

  for (final pixels in [750.0, 1080.0]) {
    for (final scale in [1.0, 3.0]) {
      testWidgets(
        'Native Add Colors spacing, trailing inset and text cap at $pixels / $scale',
        (tester) async {
          tester.view.physicalSize = const Size(393, 900);
          tester.view.devicePixelRatio = 1;
          tester.view.display.size = Size(pixels, 2000);
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          addTearDown(tester.view.display.resetSize);
          final profiles = await tester.runAsync(NativeFaceGallery.load);
          final section = profiles!
              .firstWhere((p) => p.family == 'type:activity analog rich')
              .pigmentSection('color')!;
          var writes = 0;
          final favorites = NativePigmentFavorites(
            persist: (_) async {
              writes++;
            },
          );
          await tester.pumpWidget(
            CupertinoApp(
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(
                  context,
                ).copyWith(textScaler: TextScaler.linear(scale)),
                child: child!,
              ),
              home: NativeFacePigmentPicker(
                section: section,
                favorites: favorites,
                label: (s) => s,
                canCommit: () => true,
              ),
            ),
          );
          await tester.pumpAndSettle();
          final swatches = find.byType(NativeFacePigmentSwatch);
          final inset = pixels > 750 ? 20.0 : 16.0;
          expect(tester.getSize(swatches.first), const Size(42, 42));
          expect(tester.getTopLeft(swatches.first).dx, inset);
          expect(
            tester.getTopLeft(swatches.at(1)).dx -
                tester.getTopLeft(swatches.first).dx,
            54,
          );
          final title = tester.widget<Text>(find.text('Fall 2025'));
          expect(title.style!.fontSize, 17);
          expect(title.textScaler!.scale(17), scale == 1 ? 17 : 33);
          final horizontal = find
              .byWidgetPredicate(
                (w) =>
                    w is Scrollable && w.axisDirection == AxisDirection.right,
              )
              .first;
          final position = tester.state<ScrollableState>(horizontal).position;
          position.jumpTo(position.maxScrollExtent);
          await tester.pumpAndSettle();
          final firstRow = find.descendant(
            of: find.byType(ListView).first,
            matching: find.byType(NativeFacePigmentSwatch),
          );
          expect(tester.getBottomRight(firstRow.last).dx, 393 - inset);
          expect(writes, 0);
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox());
          favorites.dispose();
        },
      );
    }
  }
  test('Native screen classification keeps the equality boundary compact', () {
    final layout = NativePigmentLayout.fromJson({
      'version': 1,
      'compactDiameter': 36,
      'expandedDiameter': 127 / 3,
      'expandedAbovePhysicalPixels': 750,
    });
    expect(layout.diameter(749), 36);
    expect(layout.diameter(750), 36);
    expect(layout.diameter(751), 127 / 3);
    expect(NativePigmentLayout.fromJson(null).diameter(1080), 42);
  });

  test('Malformed geometry cannot introduce unsupported sizes', () {
    final valid = {
      'sectionHorizontalInset': 16,
      'version': 1,
      'compactDiameter': 36,
      'expandedDiameter': 127 / 3,
      'expandedAbovePhysicalPixels': 750,
    };
    for (final invalid in [
      {...valid, 'version': 1.0},
      {...valid, 'compactDiameter': double.nan},
      {...valid, 'expandedDiameter': 35},
      {...valid, 'expandedDiameter': 97},
      {...valid, 'expandedAbovePhysicalPixels': 0},
      {...valid, 'guessed': 42},
    ]) {
      expect(
        () => NativePigmentLayout.fromJson(invalid),
        throwsFormatException,
      );
    }
  });

  for (final pixels in [750.0, 1080.0]) {
    testWidgets(
      'Both shade samples use native geometry on a $pixels-pixel display',
      (tester) async {
        tester.view.display.size = Size(pixels, 2000);
        addTearDown(tester.view.display.resetSize);
        final profiles = await tester.runAsync(NativeFaceGallery.load);
        final section = profiles!
            .singleWhere((p) => p.family == 'type:activity analog rich')
            .pigmentSection('color')!;
        await tester.pumpWidget(
          CupertinoApp(
            home: CupertinoPageScaffold(
              child: Center(
                child: SizedBox(
                  width: 220,
                  child: NativeFacePigmentSectionView(
                    section: section,
                    title: 'COLOR',
                    selected: 'standard.red:0.25',
                    label: (s) => s,
                    onSelected: (_) {},
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final diameter = pixels > 750 ? 127 / 3 : 36.0;
        final swatches = tester.widgetList<NativeFacePigmentSwatch>(
          find.byType(NativeFacePigmentSwatch),
        );
        expect(swatches, isNotEmpty);
        expect(swatches.every((w) => w.diameter == diameter), isTrue);
        expect(swatches.where((w) => w.percent == 25), hasLength(2));
        expect(tester.takeException(), isNull);
      },
    );
  }
}
