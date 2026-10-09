import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_complication_presentation.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Static bundle names require exact app and bundle and never name an opaque preset',
    () async {
      await NativeComplicationPresentation.load();
      final names = NativeComplicationPresentation({});
      const value = {
        'bundle app identifier': 'com.apple.NanoCompass.watchkitapp',
        'bundle identifier': 'com.apple.NanoCompass.complications.level',
      };
      expect(names.title(value), 'Level');
      expect(
        names.title({...value, 'bundle app identifier': 'other.app'}),
        value['bundle identifier'],
      );
      expect(
        names.title({...value, 'bundle identifier': 'unknown.bundle'}),
        'unknown.bundle',
      );
      expect(
        names.title({
          ...value,
          'bundle app complication descriptor': {'identifier': 'opaque-preset'},
        }),
        'opaque-preset',
      );
      expect(
        names.title({
          ...value,
          'bundle app complication descriptor': {
            'displayName': 'Received name',
          },
        }),
        'Received name',
      );
      expect(
        names.catalogNotice(true, [
          {
            'complications': {'center': value},
          },
        ]),
        isNull,
      );
    },
  );
  Map<String, dynamic> descriptor(Object? intent) => {
    'containerBundleIdentifier': 'received.app',
    'extensionBundleIdentifier': 'received.extension',
    'kind': 'received.kind',
    'intent': ?intent,
  };
  Map<String, dynamic> catalog(List<Map<String, dynamic>> rows) => {
    'WidgetComplications:received.app': {
      for (var i = 0; i < rows.length; i++) '$i': rows[i],
    },
  };
  test('Decoded inventory cannot hide missing current widget variants', () {
    final config = <String, dynamic>{
      'complications': {
        'bottom': {'type': 56, 'descriptor': descriptor('current-archive')},
      },
    };
    final absent = NativeComplicationPresentation({});
    expect(
      absent.catalogNotice(true, [config]),
      contains('available variants'),
    );
    final present = NativeComplicationPresentation(
      catalog([
        {
          'descriptor': descriptor('different-preset'),
          'name': 'Received preset',
        },
      ]),
    );
    // Archive equality is not the definition of provider inventory coverage.
    expect(present.catalogNotice(true, [config]), isNull);
    expect(
      present.catalogNotice(false, [config]),
      contains('Some descriptors'),
    );
    expect(present.catalogNotice(true, [null, {}]), isNull);
    final different = jsonDecode(jsonEncode(config)) as Map<String, dynamic>;
    different['complications']['bottom']['descriptor']['extensionBundleIdentifier'] =
        'different.extension';
    expect(
      present.catalogNotice(true, [different]),
      contains('available variants'),
    );
    expect(
      config['complications']['bottom']['descriptor']['extensionBundleIdentifier'],
      'received.extension',
    );
  });
  test(
    'Exact opaque descriptors match structurally without conflating presets',
    () {
      final first = descriptor({
        'values': [1, 2],
        'id': 'first',
      });
      final second = descriptor({
        'id': 'second',
        'values': [1, 2],
      });
      final data = catalog([
        {'descriptor': first, 'name': 'First preset'},
        {'descriptor': second, 'name': 'Second preset'},
      ]);
      final before = jsonEncode(data);
      final names = NativeComplicationPresentation(data);
      expect(
        names.title({
          'descriptor': descriptor({
            'id': 'first',
            'values': [1, 2],
          }),
        }),
        'First preset',
      );
      expect(names.title({'descriptor': second}), 'Second preset');
      expect(
        names.title({'descriptor': descriptor('unknown')}),
        'received.kind',
      );
      expect(jsonEncode(data), before);
    },
  );
  test('Observed slot hint is scoped to the actual face and slot', () {
    final names = NativeComplicationPresentation(
      catalog([
        {
          'descriptor': descriptor('one'),
          'name': 'One',
          'observedSlots': ['face:top'],
        },
        {
          'descriptor': descriptor('two'),
          'name': 'Two',
          'observedSlots': ['face:bottom'],
        },
      ]),
    );
    final value = {'descriptor': descriptor('unresolved')};
    expect(names.title(value, faceId: 'face', slot: 'top'), 'One');
    expect(names.title(value, faceId: 'other', slot: 'top'), 'received.kind');
  });
  test('Legacy lookup is extension scoped and preserves user info', () {
    final legacy = {
      'identifier': 'id',
      'userInfo': {
        'settings': [1, null],
      },
    };
    final data = <String, dynamic>{
      'BundleComplications:extension': {
        'id': {'bundleDescriptor': legacy, 'name': 'Native preset'},
      },
      'BundleComplications:other': {
        'id': {'bundleDescriptor': legacy, 'name': 'Wrong provider'},
      },
    };
    final names = NativeComplicationPresentation(data);
    expect(
      names.title({
        'bundle identifier': 'extension',
        'bundle app complication descriptor': jsonDecode(jsonEncode(legacy)),
      }),
      'Native preset',
    );
    expect(
      names.title({
        'bundle identifier': 'unknown',
        'bundle app complication descriptor': legacy,
      }),
      'id',
    );
  });
  test(
    'Firmware label hints are exact identities and subordinate to Watch names',
    () async {
      await NativeComplicationPresentation.load();
      final uv = {
        'containerBundleIdentifier': 'com.apple.weather.watchapp',
        'extensionBundleIdentifier': 'com.apple.weather.watchapp.widgets',
        'kind': 'com.apple.weather.widget.uv',
        'intent': 'private-original-data',
      };
      final names = NativeComplicationPresentation({});
      final value = {'descriptor': uv};
      final before = jsonEncode(value);
      expect(names.title(value), 'UV Index');
      expect(
        names.title({
          'descriptor': {
            ...uv,
            'extensionBundleIdentifier': 'different.extension',
          },
        }),
        'com.apple.weather.widget.uv',
      );
      expect(
        NativeComplicationPresentation(
          catalog([
            {'descriptor': uv, 'name': 'Received location'},
          ]),
        ).title(value),
        'Received location',
      );
      expect(jsonEncode(value), before);
    },
  );
  test('Opaque JSON is never shown as a fallback title', () {
    final names = NativeComplicationPresentation({});
    expect(names.title(null), 'None');
    expect(
      names.title({
        'future': {'secret': 'opaque-intent'},
      }),
      'Unknown complication',
    );
    expect(names.title({'descriptor': {}}), 'Unknown complication');
  });
}
