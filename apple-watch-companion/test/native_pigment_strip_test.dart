import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_pigment_favorites.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_pigment_section.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_pigment_header.dart';

void main() {
  testWidgets(
    'Native header caps accessibility text without overflowing narrow screens',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final row = profiles!
          .firstWhere((p) => p.family == 'type:activity analog rich')
          .pigmentSection('color')!
          .layout
          .row!;
      await tester.pumpWidget(
        CupertinoApp(
          theme: const CupertinoThemeData(brightness: Brightness.dark),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: const TextScaler.linear(3)),
            child: child!,
          ),
          home: CupertinoPageScaffold(
            child: Center(
              child: SizedBox(
                width: 200,
                child: NativeFacePigmentHeader(
                  layout: row,
                  title: 'Color',
                  subtitle: 'A long localized selected color name',
                  expanded: false,
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final title = tester.widget<Text>(find.text('Color'));
      expect(title.textScaler!.scale(17), 33);
      final subtitle = tester.widget<Text>(
        find.text('A long localized selected color name'),
      );
      expect(title.style!.color!.toARGB32(), 0xffffffff);
      expect(subtitle.style!.color!.toARGB32(), 0x99ebebf5);
      expect(subtitle.overflow, TextOverflow.ellipsis);
      expect(subtitle.maxLines, 1);
      expect(tester.takeException(), isNull);
    },
  );
  test(
    'Native slider classification survives a view denying shade editing',
    () async {
      final profiles = await NativeFaceGallery.load();
      for (final family in [
        'bundle:com.apple.NTKCrosswindFaceBundle',
        'bundle:com.apple.NTKGladiusFaceBundle',
      ]) {
        final section = profiles
            .firstWhere((p) => p.family == family)
            .pigmentSection('color')!;
        expect(section.editable, contains('standard.red'));
        expect(section.options['standard.red']!.supportsShade, isFalse);
      }
    },
  );

  for (final pixels in [750.0, 1080.0]) {
    testWidgets(
      'Original row/header and centered initial selection at $pixels',
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
        final favorites = NativePigmentFavorites(
          persist: (_) async => fail('Layout must not write'),
        );
        final visible = favorites.visibleOptions(section);
        expect(visible.length, greaterThan(12));
        final selected = ValueNotifier(visible[8]);
        await tester.pumpWidget(
          CupertinoApp(
            home: CupertinoPageScaffold(
              child: ValueListenableBuilder(
                valueListenable: selected,
                builder: (_, token, _) => NativeFacePigmentSectionView(
                  section: section,
                  title: 'Color',
                  selected: token,
                  label: (s) => s == token ? 'Multicolor' : s,
                  favorites: favorites,
                  onSelected: (_) {},
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final list = tester.getRect(find.byType(ListView));
        final diameter = pixels > 750 ? 127 / 3 : 36.0;
        expect(list.height, closeTo(diameter + 46, 1e-8));
        final active = find.descendant(
          of: find.byType(ListView),
          matching: find.byWidgetPredicate(
            (w) =>
                w is NativeFacePigmentSwatch &&
                w.pigment == section.options[selected.value],
          ),
        );
        expect(tester.getRect(active).center.dx, closeTo(list.center.dx, 1e-8));
        expect(tester.getRect(active).top - list.top, closeTo(23, 1e-8));
        final header = tester.getRect(find.byType(NativeFacePigmentHeader));
        final title = tester.getRect(find.text('Color'));
        final subtitle = tester.getRect(find.text('Multicolor'));
        expect(title.left, pixels > 750 ? 36 : 32);
        expect(subtitle.right, pixels > 750 ? 357 : 361);
        expect(title.top, closeTo(subtitle.top, 1e-8));
        expect(title.top - header.top, closeTo(6, 1e-8));
        expect(subtitle.bottom, lessThanOrEqualTo(list.top));
        expect(find.text('MULTICOLOR'), findsNothing);
        // The native collection considers a partly visible item visible.
        final position = tester
            .state<ScrollableState>(find.byType(Scrollable).first)
            .position;
        final partly = visible[9];
        final inset =
            section.layout.row!.horizontalInset(pixels > 750) -
            section.layout.strip!.outlinePadding(pixels > 750) / 2;
        position.jumpTo(inset + 9 * (diameter + 20) + diameter / 2);
        await tester.pumpAndSettle();
        final offset = position.pixels;
        selected.value = partly;
        await tester.pumpAndSettle();
        expect(position.pixels, offset);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        selected.dispose();
        favorites.dispose();
      },
    );
  }

  testWidgets(
    'Native strip pitch, rectangular actions and selected fixed color reveal',
    (tester) async {
      tester.view.physicalSize = const Size(393, 900);
      tester.view.devicePixelRatio = 1;
      tester.view.display.size = const Size(1080, 2000);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.display.resetSize);
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final section = profiles!
          .firstWhere((p) => p.family == 'type:activity analog rich')
          .pigmentSection('color')!;
      final strip = section.layout.strip!;
      expect(strip.cellWidth(127 / 3, true), closeTo(55.66666666666667, 1e-9));
      expect(strip.cellSpacing(true), closeTo(6.666666666666668, 1e-9));
      expect(strip.cellWidth(36, false), 49);
      expect(strip.cellSpacing(false), 7);
      expect(strip.dividerCellWidth(true), closeTo(16.333333333333332, 1e-9));
      var writes = 0;
      final favorites = NativePigmentFavorites(
        persist: (_) async {
          writes++;
        },
      );
      final visible = favorites.visibleOptions(section);
      final fixed = visible
          .where((t) => !section.editable.contains(t))
          .toList();
      expect(fixed, isNotEmpty);
      expect(visible, [...visible.where(section.editable.contains), ...fixed]);
      final selected = ValueNotifier(visible.first);
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: ValueListenableBuilder(
              valueListenable: selected,
              builder: (_, token, _) => NativeFacePigmentSectionView(
                section: section,
                title: 'COLOR',
                selected: token,
                label: (s) => s,
                favorites: favorites,
                onSelected: (_) {},
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final swatches = find.descendant(
        of: find.byType(ListView),
        matching: find.byType(NativeFacePigmentSwatch),
      );
      expect(
        tester.getTopLeft(swatches.at(1)).dx -
            tester.getTopLeft(swatches.first).dx,
        closeTo(127 / 3 + 20, 1e-8),
      );
      selected.value = fixed.last;
      await tester.pumpAndSettle();
      final active = find.byWidgetPredicate(
        (w) =>
            w is NativeFacePigmentSwatch &&
            w.pigment == section.options[fixed.last],
      );
      expect(active, findsOneWidget);
      final list = tester.getRect(find.byType(ListView));
      final selectedRect = tester.getRect(active);
      expect(selectedRect.left, greaterThanOrEqualTo(list.left));
      expect(selectedRect.right, lessThanOrEqualTo(list.right));
      final position = tester
          .state<ScrollableState>(find.byType(Scrollable).first)
          .position;
      position.jumpTo(position.maxScrollExtent);
      await tester.pumpAndSettle();
      final plus = find.byWidgetPredicate(
        (w) =>
            w is Image &&
            w.image is AssetImage &&
            (w.image as AssetImage).assetName == strip.plusImage,
      );
      expect(plus, findsOneWidget);
      expect(tester.getSize(plus), Size(strip.plusWidth, strip.plusHeight));
      expect(tester.widget<Image>(plus).color, isNull);
      expect(find.byIcon(CupertinoIcons.add_circled), findsNothing);
      expect(writes, 0);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      selected.dispose();
      favorites.dispose();
    },
  );
}
