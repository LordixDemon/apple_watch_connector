import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_face_controls.dart';
import 'package:apple_watch_companion/models/native_face_collection.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_presentation.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'Photos summary uses received palette labels without displaying native dictionaries',
    () async {
      await NativeFaceGallery.load();
      final profile = NativeFaceGallery.profile(
        'bundle:com.apple.NTKParmesanFaceBundle',
      )!;
      final config =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      config['customization']['color']['slots']['future-slot'] = {'opaque': 31};
      final before = jsonEncode(config);
      final face = NativeWatchFace(
        'test',
        'com.apple.NTKParmesanFaceBundle',
        before.length,
        configurationJson: before,
      );
      final summary = nativeFaceSummary(face);
      for (final control in profile.controls) {
        final token = control.value(config);
        final group = control.group(config);
        if (token != null && group != null && group.labels.containsKey(token)) {
          expect(summary, contains(group.title(token, 'en')));
        }
      }
      expect(summary, isNot(contains('{')));
      expect(summary, isNot(contains('future-slot')));
      expect(jsonEncode(face.configuration), before);
    },
  );

  test(
    'Photos follows native style palettes and remembers each independently',
    () async {
      await NativeFaceGallery.load();
      final profile = NativeFaceGallery.profile(
        'bundle:com.apple.NTKParmesanFaceBundle',
      )!;
      final config =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      config['opaque'] = {'future-field': 31};
      config['customization']['color']['slots']['unknown-slot'] =
          'opaque-color';
      final photo = profile.controls.singleWhere(
        (v) => v.path.last == 'style-color',
      );
      final time = profile.controls.singleWhere(
        (v) => v.path.last == 'time-color',
      );
      expect(photo.group(config)!.labels.length, 1);
      expect(time.group(config)!.labels.length, 186);
      expect(time.select(config, 'standard.blue'), isTrue);

      profile.selectOption(config, 'style', 'duotone');
      expect(photo.group(config)!.labels.length, 114);
      expect(photo.value(config), 'standard.red');
      expect(photo.select(config, 'standard.orange'), isTrue);
      expect(config['customData']['3'], 'standard.orange');
      profile.selectOption(config, 'style', 'monotone');
      expect(photo.group(config)!.labels.length, 184);
      expect(photo.value(config), 'standard.red');
      expect(photo.select(config, 'standard.blue'), isTrue);
      expect(config['customData']['2'], 'standard.blue');
      profile.selectOption(config, 'style', 'duotone');
      expect(photo.value(config), 'standard.orange');
      profile.selectOption(config, 'style', 'bw');
      expect(photo.value(config), 'plain.plain');
      profile.selectOption(config, 'style', 'tritone');
      expect(photo.group(config)!.labels.length, 6);
      final before = jsonEncode(config);
      expect(photo.select(config, 'standard.blue'), isFalse);
      expect(jsonEncode(config), before);
      expect(photo.select(config, 'tritone.tritone-02'), isTrue);
      expect(config['customData']['4'], 'tritone.tritone-02');
      expect(time.value(config), 'standard.blue');
      expect(
        config['customization']['color']['slots']['unknown-slot'],
        'opaque-color',
      );
      expect(config['opaque'], {'future-field': 31});
    },
  );

  test(
    'Unknown observed styles and palettes are preserved without guessed replacements',
    () async {
      await NativeFaceGallery.load();
      final profile = NativeFaceGallery.profile(
        'bundle:com.apple.NTKParmesanFaceBundle',
      )!;
      final config =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      config['customization']['style'] = 'future-style';
      config['customization']['color']['slots']['style-color'] = 'future-color';
      final photo = profile.controls.first;
      final before = jsonEncode(config);
      expect(photo.group(config), isNull);
      expect(photo.select(config, 'standard.red'), isFalse);
      profile.selectOption(config, 'typeface', 'classic');
      expect(photo.value(config), 'future-color');
      expect(jsonEncode(config).replaceFirst('classic', 'modern'), before);
    },
  );

  test('Nested profiles restrict edits and use modular locale fallback', () {
    final raw = {
      'path': ['customization', 'color', 'slots', 'time-color'],
      'titles': {'en': 'Time Color', 'de': 'Zeitfarbe'},
      'groups': {
        '*': {
          'values': [
            {
              'value': 'red',
              'labels': {'en': 'Red', 'de': 'Rot'},
            },
          ],
        },
      },
    };
    final control = NativeFaceControl.fromJson(raw);
    final config = <String, dynamic>{};
    expect(control.title('de_DE'), 'Zeitfarbe');
    expect(control.group(config)!.title('red', 'fr'), 'Red');
    expect(control.select(config, 'red'), isTrue);
    expect(control.value(config), 'red');
    expect(
      () => NativeFaceControl.fromJson({
        ...raw,
        'path': ['version', 'value'],
      }),
      throwsFormatException,
    );
    expect(() => control.group(config)!.labels.clear(), throwsUnsupportedError);
    expect(() => control.path.clear(), throwsUnsupportedError);
  });
}
