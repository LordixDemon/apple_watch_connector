import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_face_edit_section.dart';
import 'package:apple_watch_companion/services/native_watch_face_archive.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_value_picker.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'Native editor preserves collection order and exact option identities',
    () async {
      final profiles = await NativeFaceGallery.load();
      final california = profiles.singleWhere(
        (profile) => profile.title('en') == 'California',
      );
      expect(california.editableFields.take(3), ['typeface', 'style', 'color']);
      expect(california.editSection('typeface')!.mode, 13);
      expect(california.editSection('typeface')!.vertical, false);
      final lunar = profiles.singleWhere(
        (profile) => profile.title('en') == 'Lunar',
      );
      expect(lunar.editSection('content')!.vertical, true);
      expect(profiles.expand((profile) => profile.editSections), hasLength(68));
      for (final profile in profiles) {
        for (final section in profile.editSections) {
          expect(profile.options[section.field], containsAll(section.values));
          expect(section.values.toSet(), hasLength(section.values.length));
        }
      }
      for (final invalid in [
        {
          'field': 'style',
          'mode': 15,
          'collectionType': 0,
          'values': ['invented'],
        },
        {
          'field': 'style',
          'mode': 15,
          'collectionType': 0,
          'values': ['style 1', 'style 1'],
        },
        {
          'field': 'style',
          'mode': 15,
          'collectionType': 1,
          'values': ['style 1'],
        },
        {
          'field': 'style',
          'mode': -1,
          'collectionType': 0,
          'values': ['style 1'],
        },
      ]) {
        expect(
          () => NativeFaceEditSection.fromJson(invalid, california.options),
          throwsFormatException,
        );
      }
    },
  );

  test(
    'Firmware option tokens are unique and Contour keeps native background values',
    () async {
      final templates = await NativeFaceGallery.load();
      for (final template in templates) {
        for (final entry in template.options.entries) {
          expect(entry.value.toSet().length, entry.value.length);
        }
      }
      final contour = templates.singleWhere((t) => t.title('en') == 'Contour');
      final configuration =
          jsonDecode(contour.configurationJson) as Map<String, dynamic>;
      expect(configuration['customization']['content'], 'on');
      expect(contour.options['content'], ['on', 'off']);
      expect(contour.valueTitle('content', 'on', 'en'), 'ON');
      expect(contour.valueTitle('content', 'off', 'en'), 'OFF');
      expect(contour.fieldTitle('content', 'en'), 'DIAL COLOR');
      contour.selectOption(configuration, 'content', 'off');
      expect(
        NativeWatchFaceArchive.configuration(
          contour.package(configuration: configuration),
        ),
        configuration,
      );
    },
  );

  test(
    'Native drafts package selected options without mutating templates or changing family',
    () async {
      final profile = (await NativeFaceGallery.load()).singleWhere(
        (p) => p.title('en') == 'Flux',
      );
      final draft =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      final defaults = jsonDecode(profile.configurationJson) as Map;
      final styles = profile.options['style']!;
      final next = styles.firstWhere(
        (value) => value != draft['customization']['style'],
      );
      profile.selectOption(draft, 'style', next);
      draft['complications'] = {
        'top': {
          'type': 56,
          'descriptor': {'intent': 'opaque-received-value'},
        },
      };
      expect(
        NativeWatchFaceArchive.configuration(
          profile.package(configuration: draft),
        ),
        draft,
      );
      expect(jsonDecode(profile.configurationJson), defaults);
      expect(
        () => profile.package(
          configuration: {...draft, 'bundle id': 'different-native-family'},
        ),
        throwsFormatException,
      );
      expect(
        () => profile.package(
          configuration: {...draft, 'resource directory': 'missing'},
        ),
        throwsFormatException,
      );
      expect(
        () => profile.package(configuration: {...draft, 'customData': {}}),
        throwsFormatException,
      );
    },
  );

  test(
    'Every firmware template produces a resource-free native package',
    () async {
      final first = NativeFaceGallery.load();
      expect(identical(first, NativeFaceGallery.load()), isTrue);
      final templates = await first;
      expect(templates.length, 50);
      expect(templates.map((v) => v.family).toSet().length, templates.length);
      expect(
        templates.map((v) => v.previewAsset).toSet().length,
        templates.length,
      );
      for (final template in templates) {
        if (template.requiresPhotos) {
          expect(template.package, throwsFormatException);
          continue;
        }
        final preview = await rootBundle.load(template.previewAsset);
        expect(preview.buffer.asUint8List(preview.offsetInBytes, 8), [
          137,
          80,
          78,
          71,
          13,
          10,
          26,
          10,
        ]);
        final config = NativeWatchFaceArchive.configuration(template.package());
        expect(NativeWatchFaceArchive.familyIdentity(config), template.family);
        expect(config, jsonDecode(template.configurationJson));
        for (final key in [
          'complications',
          'resource directory',
          'customData',
        ]) {
          expect(config.containsKey(key), isFalse);
        }
        final defaults = config['customization'] as Map? ?? const {};
        for (final field in template.options.keys) {
          expect(template.options[field], contains(defaults[field]));
        }
      }
      final waypoint = NativeFaceGallery.profile(
        'bundle:com.apple.NTKLeghornFaceBundle',
      )!;
      expect(waypoint.title('de'), 'Waypoint');
      expect(waypoint.options['style'], containsAll(['analog', 'digital']));
      expect(waypoint.slotFamilies['top left'], [8]);
      final wayfinder = NativeFaceGallery.profile(
        'bundle:com.apple.NTKGalleonFaceBundle',
      )!;
      expect(wayfinder.title('en'), 'Wayfinder');
      expect(wayfinder.slotFamilies.keys, contains('slot 1'));
      expect(
        NativeFaceGallery.profile(
          'bundle:com.apple.NTKFoghornFaceBundle',
        )!.title('en'),
        'Modular Ultra',
      );
      expect(templates.any((v) => v.family.startsWith('type:')), isTrue);
    },
  );

  test(
    'Profile rejects external data and keeps localized value fallback modular',
    () {
      final row = <String, dynamic>{
        'family': 'type:test',
        'configuration': {'face type': 'test'},
        'titles': {'en': 'Test', 'de': 'Probe'},
        'fieldTitles': {
          'en': {'color': 'Color'},
        },
        'slotFamilies': <String, dynamic>{},
        'options': {
          'color': [
            {
              'value': 'red',
              'labels': {'en': 'Red', 'de': 'Rot'},
            },
          ],
        },
      };
      final template = NativeFaceTemplate.fromJson(row);
      expect(template.valueTitle('color', 'red', 'de_DE'), 'Rot');
      expect(template.valueTitle('color', 'red', 'fr'), 'Red');
      expect(template.title('de_DE'), 'Probe');
      expect(() => template.options['color']!.clear(), throwsUnsupportedError);
      expect(
        () => NativeFaceTemplate.fromJson({
          ...row,
          'options': {
            'color': [
              {'value': 'red', 'label': 'Red'},
              {'value': 'red', 'label': 'Different option'},
            ],
          },
        }),
        throwsFormatException,
      );
      for (final key in ['complications', 'resource directory', 'customData']) {
        expect(
          () => NativeFaceTemplate.fromJson({
            ...row,
            'configuration': {'face type': 'test', key: 'external'},
          }),
          throwsFormatException,
        );
      }
    },
  );

  testWidgets(
    'Large native palettes are searchable and only build visible rows',
    (tester) async {
      final values = List.generate(200, (i) => 'native-color-$i');
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceValuePicker(
            title: 'Color',
            selected: values.first,
            values: values,
            label: (v) => v,
          ),
        ),
      );
      expect(find.text(values.first), findsOneWidget);
      expect(find.text(values.last), findsNothing);
      expect(find.byType(ListView), findsOneWidget);
      await tester.enterText(
        find.byType(CupertinoSearchTextField),
        values.last,
      );
      await tester.pump();
      expect(
        find
            .text(values.last, findRichText: false)
            .evaluate()
            .where((e) => e.widget is Text),
        hasLength(1),
      );
      expect(find.text(values.first), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
