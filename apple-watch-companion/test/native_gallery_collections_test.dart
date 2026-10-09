import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_configured_previews.dart';
import 'package:apple_watch_companion/services/native_gallery_collections.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_templates_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'All 210 factory appearance presets retain family metadata and leave default profiles immutable',
    () async {
      final profiles = await NativeFaceGallery.load();
      final before = {for (final p in profiles) p.family: p.configurationJson};
      final raw = await rootBundle.loadString(
        'assets/native_faces/gallery_collections_26_2.json',
      );
      final rows = NativeGalleryCollections.decode(raw);
      expect(rows, hasLength(50));
      expect(rows.expand((s) => s.variants), hasLength(210));
      final california = rows.singleWhere((s) => s.title('en') == 'California');
      expect(california.variants, hasLength(6));
      final preset = california.variants.first;
      expect(preset.configuration, same(preset.configuration));
      expect(
        () => preset.configuration['customization'] = {},
        throwsUnsupportedError,
      );
      expect(
        () =>
            (preset.configuration['customization'] as Map)['color'] = 'changed',
        throwsUnsupportedError,
      );
      final native = jsonDecode(raw) as Map;
      for (var i = 0; i < rows.length; i++) {
        for (var j = 0; j < rows[i].variants.length; j++) {
          final variant = rows[i].variants[j];
          final candidate = variant.template.withConfiguration(
            variant.configurationJson,
          );
          expect(candidate.configurationJson, variant.configurationJson);
          expect(
            variant.configuration['customization'],
            native['sections'][i]['variants'][j]['customization'],
          );
          expect(candidate.options, same(variant.template.options));
          expect(candidate.package(), isNotEmpty);
          expect(
            await NativeConfiguredPreviews.resolve(
              variant.template.family,
              variant.configuration,
            ),
            isNotNull,
            reason: 'Every gallery card needs an exact native appearance image',
          );
          expect(
            variant.template.configurationJson,
            before[variant.template.family],
          );
          expect(
            variant.configuration.containsKey('resource directory'),
            false,
          );
        }
      }
      for (final p in profiles) {
        expect(p.configurationJson, before[p.family]);
      }
    },
  );
  test(
    'Unknown family, simulator recipe fields, excessive nesting and crossed prototypes cannot open a preset',
    () async {
      final profiles = await NativeFaceGallery.load();
      final raw = await rootBundle.loadString(
        'assets/native_faces/gallery_collections_26_2.json',
      );
      Map<String, dynamic> broken() => jsonDecode(raw) as Map<String, dynamic>;
      final data = broken();
      data['sections'][0]['variants'][0]['intent'] = 'simulator identity';
      expect(
        () => NativeGalleryCollections.decode(jsonEncode(data)),
        throwsFormatException,
      );
      final unknown = broken();
      unknown['sections'][0]['variants'][0]['family'] = 'bundle:missing';
      expect(
        () => NativeGalleryCollections.decode(jsonEncode(unknown)),
        throwsFormatException,
      );
      final empty = broken();
      empty['sections'][0]['variants'] = [];
      expect(
        () => NativeGalleryCollections.decode(jsonEncode(empty)),
        throwsFormatException,
      );
      final deep = broken();
      Object nested = 'native';
      for (var i = 0; i < 10; i++) {
        nested = {'nested': nested};
      }
      deep['sections'][0]['variants'][0]['customization'] = nested;
      expect(
        () => NativeGalleryCollections.decode(jsonEncode(deep)),
        throwsFormatException,
      );
      expect(
        () => profiles.first.withConfiguration(profiles.last.configurationJson),
        throwsFormatException,
      );
    },
  );
  testWidgets(
    'Native rows scroll horizontally, select the exact preset and fit narrow scaled text',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(280, 640);
      addTearDown(tester.view.reset);
      final rows = await tester.runAsync(NativeGalleryCollections.load);
      final section = rows!.singleWhere((s) => s.title('en') == 'California');
      NativeGalleryVariant? selected;
      await tester.pumpWidget(
        CupertinoApp(
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(280, 640),
              textScaler: TextScaler.linear(2.4),
            ),
            child: CupertinoPageScaffold(
              child: NativeGallerySectionRow(
                section: section,
                onSelect: (v) => selected = v,
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      final list = tester.widget<ListView>(find.byType(ListView));
      expect(list.scrollDirection, Axis.horizontal);
      await tester.tap(find.byType(CupertinoButton).first);
      expect(selected, same(section.variants.first));
      await tester.drag(find.byType(ListView), const Offset(-1200, 0));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );
}
