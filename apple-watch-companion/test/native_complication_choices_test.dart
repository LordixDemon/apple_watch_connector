import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_complication_choices.dart';
import 'package:apple_watch_companion/services/native_complication_presentation.dart';
import 'package:apple_watch_companion/services/native_face_options.dart';
import 'package:apple_watch_companion/models/native_face_collection.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'native_face_management_test.dart' as facts;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Re-archived preset rows retain the incumbent and remove duplicates',
    () async {
      final profile = (await NativeFaceGallery.load()).singleWhere(
        (p) => p.title('en') == 'Waypoint',
      );
      Map<String, dynamic> descriptor(String fixture) => {
        'kind': 'research.timer',
        'containerBundleIdentifier': 'research.app',
        'extensionBundleIdentifier': 'research.extension',
        'intent': base64Encode(
          File(
            'test/fixtures/native-intent-43-$fixture.bplist',
          ).readAsBytesSync(),
        ),
      };
      final current = <String, dynamic>{
        'type': 56,
        'app': 'research.app',
        'extension': 'research.app',
        'descriptor': descriptor('original'),
        'future': {'preserve': true},
      };
      final config =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      config['complications'] = {'bottom left': current};
      final face = NativeWatchFace(
        facts.first,
        config['bundle id'],
        0,
        configurationJson: jsonEncode(config),
      );
      final collection = NativeFaceCollection(
        faces: [face],
        complicationCatalogJson: jsonEncode({
          'WidgetComplications:research.app': {
            'a': {
              'name': '15 min',
              'families': [8],
              'descriptor': descriptor('rearchived'),
            },
            'b': {
              'name': '15 min',
              'families': [8],
              'descriptor': descriptor('original'),
            },
            'c': {
              'name': '1 min',
              'families': [8],
              'descriptor': descriptor('duration'),
            },
          },
        }),
      );
      final before = jsonEncode(current);
      final choices = NativeComplicationChoices.forFaceSlot(
        face,
        'bottom left',
        collection,
      );
      expect(choices, hasLength(2));
      final selected = choices.singleWhere((c) => c.title == '15 min');
      expect(jsonEncode(selected.value), before);
      expect(
        choices.singleWhere((c) => c.title == '1 min').value['descriptor'],
        descriptor('duration'),
      );
      selected.value['future']['preserve'] = false;
      expect(jsonEncode(current), before);
      expect(
        face.configuration?['complications']['bottom left']['future']['preserve'],
        true,
      );
      final names = NativeComplicationPresentation(
        collection.complicationCatalog,
      );
      expect(
        names.sameConfiguration(current, {...current, 'future': 'changed'}),
        false,
      );
      expect(
        names.sameConfiguration(current, {...current, 'app': 'other.app'}),
        false,
      );
      expect(
        names.sameConfiguration(current, {
          ...current,
          'descriptor': descriptor('duration'),
        }),
        false,
      );
    },
  );
  test(
    'Observed widgets can fill another slot proven to use the same native family',
    () async {
      final profile = (await NativeFaceGallery.load()).singleWhere(
        (p) => p.title('en') == 'Waypoint',
      );
      final value = <String, dynamic>{
        'type': 56,
        'app': 'com.apple.weather.watchapp',
        'extension': 'com.apple.weather.watchapp',
        'descriptor': {
          'kind': 'com.apple.weather.widget.uv',
          'containerBundleIdentifier': 'com.apple.weather.watchapp',
          'extensionBundleIdentifier': 'com.apple.weather.watchapp.widgets',
          'intent': 'received-opaque-intent',
        },
        'future': {'keep': true},
      };
      final config =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      config['complications'] = {'top left': value};
      final json = jsonEncode(config);
      final face = NativeWatchFace(
        facts.first,
        config['bundle id'],
        utf8.encode(json).length,
        configurationJson: json,
      );
      final collection = NativeFaceCollection(faces: [face]);
      final before = face.configurationJson;
      final choices = NativeComplicationChoices.forTemplateSlot(
        profile,
        'bottom right',
        collection,
      );
      expect(choices, hasLength(1));
      expect(choices.single.title, 'UV Index');
      expect(choices.single.value, value);
      expect(
        NativeComplicationChoices.forTemplateSlot(
          profile,
          'center',
          collection,
        ),
        isEmpty,
      );
      choices.single.value['descriptor']['intent'] = 'edited draft';
      expect(face.configurationJson, before);
      expect(value['descriptor']['intent'], 'received-opaque-intent');
      final unknown = NativeWatchFace(
        facts.second,
        'unknown.native.bundle',
        0,
        configurationJson: jsonEncode({
          ...config,
          'bundle id': 'unknown.native.bundle',
        }),
      );
      expect(
        NativeComplicationChoices.forTemplateSlot(
          profile,
          'bottom right',
          NativeFaceCollection(faces: [unknown]),
        ),
        isEmpty,
      );
    },
  );
  test(
    'Uninstalled native template drafts use received compatible providers without an invented Watch identity',
    () async {
      final profile = (await NativeFaceGallery.load()).singleWhere(
        (p) => p.title('en') == 'Waypoint',
      );
      final descriptor = <String, dynamic>{
        'containerBundleIdentifier': 'received.app',
        'extensionBundleIdentifier': 'received.extension',
        'kind': 'received.kind',
        'intent': 'received-opaque-data',
      };
      final collection = NativeFaceCollection(
        complicationCatalogJson: jsonEncode({
          'WidgetComplications:received.app': {
            'id': {
              'descriptor': descriptor,
              'families': [8],
              'name': 'Received provider',
            },
            'wrong': {
              'descriptor': {...descriptor, 'kind': 'wrong'},
              'families': [3],
            },
          },
        }),
      );
      final choices = NativeComplicationChoices.forTemplateSlot(
        profile,
        'top left',
        collection,
      );
      expect(choices, hasLength(1));
      expect(choices.single.value['descriptor'], descriptor);
      expect(collection.faces, isEmpty);
      expect(
        NativeComplicationChoices.forTemplateSlot(
          profile,
          'unknown-slot',
          collection,
        ),
        isEmpty,
      );
      expect(
        NativeComplicationChoices.forTemplateSlot(
          profile,
          'top left',
          const NativeFaceCollection(),
        ),
        isEmpty,
      );
      choices.single.value['descriptor']['intent'] = 'changed';
      expect(
        collection
            .complicationCatalog['WidgetComplications:received.app']['id']['descriptor']['intent'],
        'received-opaque-data',
      );
    },
  );
  test(
    'Native activity and null fixtures survive catalog choices and face export',
    () {
      for (final kind in ['activity', 'null']) {
        final descriptor =
            jsonDecode(
                  File(
                    '../apple-watch-bridge/core/src/test/resources/clockface-399-$kind-descriptor.json',
                  ).readAsStringSync(),
                )
                as Map<String, dynamic>;
        final template = <String, dynamic>{
          'bundle identifier': 'research.only.extension',
          'bundle app identifier': 'research.only.app',
          'bundle app complication descriptor': descriptor,
        };
        final catalog = <String, dynamic>{
          'BundleComplications:research.only.extension': {
            descriptor['identifier']: {
              'identifier': descriptor['identifier'],
              'families': descriptor['supportedFamilies'],
              'bundleDescriptor': descriptor,
            },
          },
        };
        final choice = NativeComplicationChoices.forBundleSlot(
          template,
          catalog,
        ).single;
        final exported =
            jsonDecode(
                  jsonEncode({
                    'complications': {'top': choice.value},
                  }),
                )
                as Map;
        final retained =
            exported['complications']['top']['bundle app complication descriptor']
                as Map;
        expect(retained, descriptor);
        if (kind == 'activity') {
          expect(retained['userActivity'], descriptor['userActivity']);
          expect(retained['needsAppNotify'], true);
          (choice.value['bundle app complication descriptor']
                  as Map)['userInfo']['nested']['values'][0] =
              'edited';
          expect(descriptor['userInfo']['nested']['values'][0], 'one');
        } else {
          expect((retained['userInfo'] as Map).containsKey('nullable'), true);
          expect(retained['userInfo']['nullable'], isNull);
          expect(retained['userInfo']['values'], [null, 3]);
        }
      }
    },
  );
  Map<String, dynamic> bundleTemplate(String extension, String app) => {
    'bundle identifier': extension,
    'bundle app identifier': app,
    'bundle app complication descriptor': {
      'identifier': 'old',
      'supportedFamilies': [8, 9],
      'userInfo': {'old': true},
    },
    'future': {'preserve': true},
  };
  Map<String, dynamic> bundleRow(String id, List<int> families) => {
    'identifier': id,
    'families': families,
    'name': 'Native $id',
    'bundleDescriptor': {
      'identifier': id,
      'displayName': 'Native $id',
      'supportedFamilies': families,
      'locale': 'observed-locale',
      'userInfo': {'choice': id, 'count': 7},
    },
  };
  test(
    'Bundle descriptor choices preserve native app identity and opaque userInfo',
    () {
      final template = bundleTemplate('observed.extension', 'observed.app');
      final row = bundleRow('compatible', [8, 9]);
      final catalog = <String, dynamic>{
        'BundleComplications:observed.extension': {
          'compatible': row,
          'incompatible': bundleRow('incompatible', [8]),
        },
        'BundleComplications:other.extension': {
          'other': bundleRow('other', [8, 9]),
        },
      };
      final choice = NativeComplicationChoices.forSlot(
        template,
        catalog,
      ).single;
      expect(choice.title, 'Native compatible');
      expect(choice.value['bundle identifier'], 'observed.extension');
      expect(choice.value['bundle app identifier'], 'observed.app');
      expect(choice.value.containsKey('descriptor'), false);
      expect(choice.value.containsKey('type'), false);
      expect(choice.value['future'], {'preserve': true});
      expect(
        choice.value['bundle app complication descriptor'],
        row['bundleDescriptor'],
      );
      (choice.value['bundle app complication descriptor']
              as Map)['userInfo']['choice'] =
          'edited';
      expect(row['bundleDescriptor']['userInfo']['choice'], 'compatible');
      expect(
        template['bundle app complication descriptor']['identifier'],
        'old',
      );
    },
  );

  test(
    'Received legacy provider variants can fill an empty compatible native slot',
    () {
      const face = NativeWatchFace(
        'target',
        'com.apple.NTKLeghornFaceBundle',
        0,
        configurationJson: '{"face type":"leghorn","complications":{}}',
      );
      final observed = NativeWatchFace(
        'provider-face',
        '',
        0,
        configurationJson: jsonEncode({
          'face type': 'different-native-family',
          'complications': {'bottom': bundleTemplate('extension', 'app')},
        }),
      );
      final collection = NativeFaceCollection(
        faces: [face, observed],
        complicationCatalogJson: jsonEncode({
          'BundleComplications:extension': {
            'compatible': bundleRow('compatible', [8]),
            'wrong': bundleRow('wrong', [3]),
          },
        }),
      );
      final choices = NativeComplicationChoices.forFaceSlot(
        face,
        'top left',
        collection,
      );
      expect(choices.single.title, 'Native compatible');
      expect(choices.single.value['bundle identifier'], 'extension');
    },
  );

  test(
    'Bundle descriptors alone never invent a containing app or provider identity',
    () {
      final catalog = <String, dynamic>{
        'BundleComplications:extension': {
          'id': bundleRow('id', [8]),
        },
      };
      for (final template in [
        null,
        {'bundle identifier': 'extension'},
        {'app': 'date'},
      ]) {
        expect(
          NativeComplicationChoices.forBundleSlot(
            template,
            catalog,
            requiredFamilies: [8],
          ),
          isEmpty,
        );
      }
      expect(
        NativeComplicationChoices.forBundleSlot(
          bundleTemplate('extension', 'app'),
          {},
          requiredFamilies: [8],
        ),
        isEmpty,
      );
      expect(
        NativeComplicationChoices.forBundleSlot(
          bundleTemplate('extension', 'app'),
          catalog,
          requiredFamilies: [3],
        ),
        isEmpty,
      );
    },
  );

  test(
    'Unknown native family cannot borrow another face family or slot provider',
    () {
      final face = NativeWatchFace(
        'target',
        '',
        0,
        configurationJson: jsonEncode({
          'face type': 'unknown',
          'complications': {},
        }),
      );
      final observed = NativeWatchFace(
        'provider',
        '',
        0,
        configurationJson: jsonEncode({
          'face type': 'other',
          'complications': {'top': bundleTemplate('extension', 'app')},
        }),
      );
      final collection = NativeFaceCollection(
        faces: [face, observed],
        complicationCatalogJson: jsonEncode({
          'BundleComplications:extension': {
            'id': bundleRow('id', [8, 9]),
          },
        }),
      );
      expect(
        NativeComplicationChoices.forFaceSlot(face, 'top', collection),
        isEmpty,
      );
    },
  );

  NativeWatchFace legacyFace(
    String id,
    Map<String, dynamic> complications, {
    String family = 'sidereal',
  }) {
    final json = jsonEncode({
      'face type': family,
      'complications': complications,
    });
    return NativeWatchFace(
      id,
      '',
      utf8.encode(json).length,
      configurationJson: json,
    );
  }

  test(
    'Observed legacy JSON without type is selectable in its actual family and slot',
    () {
      final date = legacyFace('date', {
        'top': {'app': 'date'},
      });
      final battery = legacyFace('battery', {
        'top': {'app': 'battery'},
      });
      final wrongSlot = legacyFace('other-slot', {
        'bottom': {'app': 'alarm'},
      });
      final wrongFamily = legacyFace('other-family', {
        'top': {'app': 'weather'},
      }, family: 'solar');
      final collection = NativeFaceCollection(
        faces: [date, battery, wrongSlot, wrongFamily],
      );
      final choices = NativeComplicationChoices.forFaceSlot(
        date,
        'top',
        collection,
      );
      expect(choices.map((v) => v.value['app']), ['battery', 'date']);
      expect(
        NativeComplicationChoices.forFaceSlot(date, 'unknown', collection),
        isEmpty,
      );
    },
  );

  test(
    'Cleared legacy draft can restore its observed value with opaque activity unchanged',
    () {
      final value = <String, dynamic>{
        'app': 'bundle',
        'bundle identifier': 'observed.extension',
        'descriptor': {'identifier': 'observed', 'intent': 'opaque-base64'},
        'user activity': {
          'type': 'observed.activity',
          'data': ['opaque', 17],
        },
        'unknown': {'preserve': true},
      };
      final face = legacyFace('original', {'top': value});
      final collection = NativeFaceCollection(faces: [face]);
      final choice = NativeComplicationChoices.forFaceSlot(
        face,
        'top',
        collection,
        template: null,
        titleForObserved: (_) => 'Observed bundle',
      ).single;
      expect(choice.title, 'Observed bundle');
      expect(choice.value, value);
      (choice.value['unknown'] as Map)['preserve'] = false;
      expect(value['unknown']['preserve'], true);
      expect(
        face.configuration!['complications']['top']['unknown']['preserve'],
        true,
      );
    },
  );

  test(
    'Native JSON identity deduplicates reordered keys without merging different intents',
    () {
      final a = legacyFace('a', {
        'top': {
          'app': 'bundle',
          'descriptor': {'intent': 'A', 'identifier': 'x'},
        },
      });
      final b = legacyFace('b', {
        'top': {
          'descriptor': {'identifier': 'x', 'intent': 'A'},
          'app': 'bundle',
        },
      });
      final c = legacyFace('c', {
        'top': {
          'app': 'bundle',
          'descriptor': {'identifier': 'x', 'intent': 'B'},
        },
      });
      final collection = NativeFaceCollection(faces: [a, b, c]);
      final choices = NativeComplicationChoices.forFaceSlot(
        a,
        'top',
        collection,
      );
      expect(choices, hasLength(2));
      expect(
        choices.map((v) => v.value['descriptor']['intent']),
        containsAll(['A', 'B']),
      );
    },
  );

  test(
    'Stale editor cannot supply choices after its face leaves the current collection',
    () {
      final face = legacyFace('removed', {
        'top': {'app': 'date'},
      });
      expect(
        NativeComplicationChoices.forFaceSlot(
          face,
          'top',
          const NativeFaceCollection(),
        ),
        isEmpty,
      );
    },
  );

  test(
    'Firmware slot families allow only received compatible descriptors even when the incumbent is missing',
    () {
      final template = <String, dynamic>{
        'type': 56,
        'descriptor': {'kind': 'missing'},
        'keep': true,
      };
      final catalog = <String, dynamic>{
        'WidgetComplications:app': {
          'corner': {
            'identifier': 'corner',
            'families': [8],
            'name': 'Corner',
            'descriptor': {
              'kind': 'corner',
              'containerBundleIdentifier': 'app',
              'extensionBundleIdentifier': 'extension',
              'intent': 'opaque',
            },
          },
          'other': {
            'identifier': 'other',
            'families': [3],
            'descriptor': {
              'kind': 'other',
              'containerBundleIdentifier': 'app',
              'extensionBundleIdentifier': 'extension',
            },
          },
        },
      };
      expect(NativeComplicationChoices.forSlot(template, catalog), isEmpty);
      final families = NativeFaceOptions.slotFamilies(
        'com.apple.NTKLeghornFaceBundle',
        'top left',
      );
      final choices = NativeComplicationChoices.forSlot(
        template,
        catalog,
        requiredFamilies: families,
      );
      expect(choices.single.title, 'Corner');
      expect(choices.single.value['keep'], true);
      expect(choices.single.value['descriptor']['intent'], 'opaque');
      expect(NativeFaceOptions.slotFamilies('unknown', 'top left'), isNull);
      expect(
        NativeFaceOptions.slotFamilies(
          'com.apple.NTKLeghornFaceBundle',
          'not-a-native-slot',
        ),
        isNull,
      );
    },
  );
  Map<String, dynamic> row(String kind, List<int> families, {String? intent}) =>
      {
        'identifier': kind,
        'name': '$kind name',
        'families': families,
        'descriptor': {
          'kind': kind,
          'containerBundleIdentifier': 'app.$kind',
          'extensionBundleIdentifier': 'extension.$kind',
          'intent': ?intent,
        },
      };
  test(
    'Empty and legacy slots accept real widgets for any ranked native family',
    () {
      final catalog = {
        'WidgetComplications:app': {
          'supported': row('supported', [9], intent: 'real-native-intent'),
          'wrong': row('wrong', [3]),
        },
      };
      for (final template in [
        null,
        {'type': 10},
      ]) {
        expect(NativeComplicationChoices.forSlot(template, catalog), isEmpty);
        final choices = NativeComplicationChoices.forSlot(
          template,
          catalog,
          requiredFamilies: [10, 9, 12],
        );
        expect(choices.single.title, 'supported name');
        expect(choices.single.value['type'], 56);
        expect(
          choices.single.value['descriptor']['intent'],
          'real-native-intent',
        );
        expect(choices.single.value['app'], 'app.supported');
      }
      expect(
        NativeComplicationChoices.forSlot(
          null,
          catalog,
          requiredFamilies: [106],
        ),
        isEmpty,
      );
    },
  );
  test(
    'Choices use actual descriptors, cover incumbent families and preserve opaque intents and unknown fields',
    () {
      final current = row('current', [3, 6, 10]);
      final catalog = {
        'WidgetComplications:app': {
          'current': current,
          'compatible': row('compatible', [
            3,
            6,
            10,
            12,
          ], intent: 'opaque-native-intent'),
          'incompatible': row('incompatible', [3]),
        },
      };
      final template = <String, dynamic>{
        'type': 56,
        'descriptor': current['descriptor'],
        'unknown': {'keep': true},
      };
      final choices = NativeComplicationChoices.forSlot(template, catalog);
      expect(choices.length, 2);
      expect(choices.map((v) => v.title), contains('compatible name'));
      final selected = choices
          .firstWhere((v) => v.title == 'compatible name')
          .value;
      expect(selected['descriptor']['intent'], 'opaque-native-intent');
      expect(selected['app'], 'app.compatible');
      expect(selected['extension'], 'app.compatible');
      expect(selected['type'], 56);
      expect(selected['unknown']['keep'], true);
      selected['unknown']['keep'] = false;
      expect(template['unknown']['keep'], true);
      expect(
        NativeComplicationChoices.forSlot({...template, 'type': 10}, catalog),
        isEmpty,
      );
      expect(NativeComplicationChoices.forSlot(template, {}), isEmpty);
    },
  );
  test(
    'Catalog remains independent optional metadata and cannot mutate the observation',
    () {
      final raw = facts.report(1000);
      raw['faceComplicationCatalog'] = Uint8List.fromList(
        utf8.encode(
          jsonEncode({
            'WidgetComplications:app': {
              'x': row('x', [3]),
            },
          }),
        ),
      );
      raw['faceComplicationCatalogComplete'] = true;
      final collection = NativeFaceCollection.fromBridge(raw);
      expect(collection.complicationCatalogComplete, true);
      collection.complicationCatalog.clear();
      expect(collection.complicationCatalog, isNotEmpty);
      raw['faceComplicationCatalog'] = Uint8List.fromList([1, 2]);
      final malformed = NativeFaceCollection.fromBridge(raw);
      expect(malformed.complete, true);
      expect(malformed.complicationCatalogComplete, false);
      expect(malformed.complicationCatalog, isEmpty);
    },
  );
}
