import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show Slider;
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_pigment_section.dart';

void main() {
  testWidgets(
    'Native colors are lazy, selected shades visible, drag commits once and disabled controls reject changes',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final profile = profiles!.singleWhere(
        (p) => p.family == 'type:california',
      );
      final section = profile.pigmentSection('color')!;
      final selected = ValueNotifier('standard.orange:0.25');
      final enabled = ValueNotifier(true);
      final calls = <String>[];
      final previews = <String>[];
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(
        CupertinoApp(
          home: ValueListenableBuilder(
            valueListenable: selected,
            builder: (_, token, _) => ValueListenableBuilder(
              valueListenable: enabled,
              builder: (_, active, _) => CupertinoPageScaffold(
                child: Center(
                  child: SizedBox(
                    width: 220,
                    child: MediaQuery(
                      data: const MediaQueryData(
                        textScaler: TextScaler.linear(2.4),
                      ),
                      child: NativeFacePigmentSectionView(
                        section: section,
                        title: 'COLOR',
                        selected: token,
                        label: (token) =>
                            profile.valueTitle('color', token, 'en') ?? token,
                        onSelected: active
                            ? (value) {
                                calls.add(value);
                                selected.value = value;
                              }
                            : null,
                        onPreviewSelected: previews.add,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.bySemanticsLabel('COLOR: ORANGE'), findsOneWidget);
      expect(
        find.byType(NativeFacePigmentSwatch).evaluate().length,
        lessThan(15),
      );
      expect(tester.widget<Slider>(find.byType(Slider)).value, 25);
      final selectedSwatch = tester
          .widgetList<NativeFacePigmentSwatch>(
            find.descendant(
              of: find.byType(ListView),
              matching: find.byType(NativeFacePigmentSwatch),
            ),
          )
          .singleWhere((swatch) => swatch.percent != null);
      expect(selectedSwatch.percent, 25);
      final selectedImage = tester.widget<Image>(
        find.descendant(
          of: find.byWidget(selectedSwatch),
          matching: find.byType(Image),
        ),
      );
      expect(selectedImage.color, selectedSwatch.pigment.colorAt(25));
      expect(selectedImage.colorBlendMode, BlendMode.srcIn);
      final slider = tester.widget<Slider>(find.byType(Slider));
      slider.onChanged!(70);
      await tester.pump();
      expect(calls, isEmpty);
      expect(previews, ['standard.orange:0.70']);
      expect(tester.widget<Slider>(find.byType(Slider)).value, 70);
      expect(
        tester
            .widgetList<NativeFacePigmentSwatch>(
              find.byType(NativeFacePigmentSwatch),
            )
            .where((swatch) => swatch.percent == 70),
        hasLength(1),
      );
      tester.widget<Slider>(find.byType(Slider)).onChangeEnd!(70);
      await tester.pumpAndSettle();
      expect(calls, ['standard.orange:0.70']);
      expect(tester.widget<Slider>(find.byType(Slider)).value, 70);
      expect(
        tester
            .widgetList<NativeFacePigmentSwatch>(
              find.byType(NativeFacePigmentSwatch),
            )
            .where((swatch) => swatch.percent == 70),
        hasLength(2),
      );
      tester.widget<Slider>(find.byType(Slider)).onChanged!(90);
      enabled.value = false;
      await tester.pumpAndSettle();
      expect(tester.widget<Slider>(find.byType(Slider)).value, 70);
      expect(tester.widget<Slider>(find.byType(Slider)).onChanged, isNull);
      expect(tester.widget<Slider>(find.byType(Slider)).onChangeEnd, isNull);
      expect(calls, ['standard.orange:0.70']);
      expect(tester.takeException(), isNull);
      selected.value = 'standard.orange';
      await tester.pumpAndSettle();
      final defaultSwatch = tester
          .widgetList<NativeFacePigmentSwatch>(
            find.descendant(
              of: find.byType(ListView),
              matching: find.byType(NativeFacePigmentSwatch),
            ),
          )
          .singleWhere((swatch) => swatch.percent != null);
      expect(defaultSwatch.percent, 50);
      expect(
        tester
            .widget<Image>(
              find.descendant(
                of: find.byWidget(defaultSwatch),
                matching: find.byType(Image),
              ),
            )
            .color,
        isNull,
      );
      expect(calls, ['standard.orange:0.70']);
      await tester.pumpWidget(const SizedBox());
      selected.dispose();
      enabled.dispose();
      semantics.dispose();
    },
  );
}
