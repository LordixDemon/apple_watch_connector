import 'dart:convert';
import 'dart:ui';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_face_pigment.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Captured iPhone complex swatches are scoped and retain exact native PNG bytes',
    () async {
      const hash =
          '09c339e7f7d1dbf583f1a63b860ff75f3709697be6e609b009dcd4a7b99d8b73';
      final profiles = await NativeFaceGallery.load();
      const captures = {
        'type:x-large rich': 'multicolor',
        'type:whistler-digital': 'multicolor',
        'bundle:com.apple.NTKCloudrakerFaceBundle': 'special.multicolor',
        'type:activity analog rich': 'special.multicolor',
        'bundle:com.apple.NTKKapacitorFaceBundle': 'dolomite.dynamic',
        'bundle:com.apple.NTKShibaFaceBundle': 'color 3000',
      };
      String? asset;
      for (final entry in captures.entries) {
        final section = profiles
            .singleWhere((p) => p.family == entry.key)
            .pigmentSection('color')!;
        final pigment = section.options[entry.value]!;
        expect(pigment.image, 'assets/native_faces/pigment_swatches/$hash.png');
        expect(pigment.color, isNull);
        expect(pigment.supportsShade, isFalse);
        expect(pigment.shadeImageMask, isFalse);
        asset = pigment.image;
      }
      final png = await rootBundle.load(asset!);
      expect(png.getUint32(16), 127);
      expect(png.getUint32(20), 127);
      expect(
        sha256
            .convert(
              png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
            )
            .toString(),
        hash,
      );
      // Device-specific Ultra captures are still unavailable; do not reuse artwork
      // merely because their previous Watch thumbnail was a shared pigment record.
      for (final family in [
        'bundle:com.apple.NTKLeghornFaceBundle',
        'bundle:com.apple.NTKFoghornFaceBundle',
      ]) {
        final pigment = profiles
            .singleWhere((p) => p.family == family)
            .pigmentSection('color')!
            .options['special.multicolor']!;
        expect(
          pigment.image,
          endsWith(
            '6655e6efb928453fc319df17d586316723c85a8db984ea0ab0c7564e227c3080.png',
          ),
        );
      }
    },
  );
  test(
    'Native shade serialization is canonical, scoped and preserves opaque data',
    () async {
      final profiles = await NativeFaceGallery.load();
      final profile = profiles.singleWhere(
        (p) => p.family == 'type:california',
      );
      final section = profile.pigmentSection('color')!;
      final pigment = section.options['standard.orange']!;
      expect(pigment.supportsShade, true);
      expect(pigment.color!.colorSpace, ColorSpace.extendedSRGB);
      for (var percent = 0; percent <= 100; percent++) {
        final token = pigment.tokenAt(percent);
        expect(pigment.percentFor(token), percent);
        expect(section.optionFor(token), 'standard.orange');
        expect(profile.valueTitle('color', token, 'en'), 'ORANGE');
      }
      expect(pigment.tokenAt(50), 'standard.orange');
      expect(pigment.tokenAt(0), 'standard.orange:0.00');
      expect(pigment.tokenAt(100), 'standard.orange:1.00');
      for (final value in [
        'standard.orange:0.50',
        'standard.orange:.2',
        'standard.orange:0.123',
        'standard.orange:NaN',
        'standard.orange:1e308',
        'standard.orange:2.00',
        'invented:0.25',
        'Pistachio:0.25',
      ]) {
        expect(section.accepts(value), false);
      }
      final config =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      config['customization']['unknown'] = {
        'keep': [1, 'opaque'],
      };
      config['complications'] = {
        'center': {
          'descriptor': 'keep',
          'intent': {'opaque': 'data'},
        },
      };
      final before = jsonDecode(jsonEncode(config)) as Map<String, dynamic>;
      profile.selectPigment(config, 'color', pigment.tokenAt(25));
      expect(config['customization']['color'], 'standard.orange:0.25');
      (before['customization'] as Map)['color'] = 'standard.orange:0.25';
      expect(config, before);
      expect(
        () => profile.selectOption(config, 'color', 'standard.orange:0.25'),
        throwsFormatException,
      );
      expect(
        () => profile.selectPigment(config, 'style', 'standard.orange:0.25'),
        throwsFormatException,
      );
      expect(
        () => profile.selectPigment(config, 'color', 'invented:0.25'),
        throwsFormatException,
      );
      expect(profile.package(configuration: config), isNotEmpty);
    },
  );

  test(
    'Native metadata retains swatches and only enables proven gradients',
    () async {
      final profiles = await NativeFaceGallery.load();
      final sections = profiles.expand((p) => p.pigmentSections).toList();
      expect(sections, hasLength(38));
      expect(
        sections.expand((s) => s.options.values).where((p) => p.supportsShade),
        hasLength(475),
      );
      final legacy = profiles.singleWhere(
        (p) => p.family == 'type:simple rich',
      );
      expect(
        legacy
            .pigmentSection('color')!
            .options
            .values
            .any((p) => p.supportsShade),
        true,
      );
      final california = profiles.singleWhere(
        (p) => p.family == 'type:california',
      );
      final pigment = california
          .pigmentSection('color')!
          .options['standard.orange']!;
      final quarter = pigment.colorAt(25);
      expect(quarter.r, closeTo(.982352941, 1e-5));
      expect(quarter.g, closeTo(.598039215, 1e-5));
      expect(quarter.b, closeTo(.509803922, 1e-5));
      for (final section in sections) {
        expect(
          section.options.values.every(
            (p) => p.color != null || p.image != null,
          ),
          true,
        );
        for (final pigment in section.options.values.where(
          (p) => p.supportsShade,
        )) {
          expect(
            pigment.percentFor(pigment.tokenAt(pigment.defaultPercent!)),
            pigment.defaultPercent,
          );
        }
      }
    },
  );

  test(
    'Activity Analog uses its controller palette and preserves native shade edits',
    () async {
      final profiles = await NativeFaceGallery.load();
      final profile = profiles.singleWhere(
        (p) => p.family == 'type:activity analog rich',
      );
      final section = profile.pigmentSection('color')!;
      final red = section.options['standard.red']!;
      final orange = section.options['standard.orange']!;
      expect(red.color!.r, closeTo(1, 1e-5));
      expect(red.color!.g, closeTo(.160784319, 1e-5));
      expect(red.color!.b, closeTo(.258823544, 1e-5));
      expect(orange.color!.g, closeTo(.396078438, 1e-5));
      expect(red.image, isNot(orange.image));
      expect(red.supportsShade, true);
      expect(red.shadeImageMask, true);
      expect(orange.colorAt(25).g, closeTo(.598039219, 1e-5));
      final multi = section.options['special.multicolor']!;
      expect(multi.color, isNull);
      expect(multi.image, isNotNull);
      expect(multi.supportsShade, false);
      expect(multi.shadeImageMask, false);

      final configuration =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      final original =
          jsonDecode(jsonEncode(configuration)) as Map<String, dynamic>;
      profile.selectPigment(configuration, 'color', orange.tokenAt(25));
      (original['customization'] as Map)['color'] = 'standard.orange:0.25';
      expect(configuration, original);
      expect(profile.package(configuration: configuration), isNotEmpty);
    },
  );

  test(
    'Corrupt pigment metadata cannot invent tokens, files or shader values',
    () {
      final valid = {
        'rgba': [.2, .4, 1.1, 1],
        'base': 'native.orange',
        'percent': 50,
        'stops': [
          [.5, .7, 1.2, 1],
          [.2, .4, 1.1, 1],
          [.1, .2, .7, 1],
        ],
      };
      final pigment = NativeFacePigment.fromJson(valid);
      expect(pigment.color!.b, 1.1);
      expect(pigment.colorAt(25).b, closeTo(1.15, 1e-10));
      for (final invalid in [
        {
          ...valid,
          'rgba': [double.nan, 0, 0, 1],
        },
        {
          ...valid,
          'rgba': [0, 0, 0, 2],
        },
        {
          ...valid,
          'rgba': [0, 0, 3, 1],
        },
        {...valid, 'image': '../secret.png'},
        {...valid, 'base': 'native.orange:0.20'},
        {...valid, 'percent': 101},
        {...valid, 'shadeImageMask': true},
        {...valid, 'shadeImageMask': 'yes'},
        {
          ...valid,
          'stops': [
            [0, 0, 0, 1],
          ],
        },
      ]) {
        expect(
          () => NativeFacePigment.fromJson(invalid),
          throwsFormatException,
        );
      }
      for (final values in [
        [
          ['invented', 0],
        ],
        [
          ['native.orange', 1],
        ],
        [
          ['native.orange', 0],
          ['native.orange', 0],
        ],
        [
          ['native.orange:0.50', 0],
        ],
      ]) {
        expect(
          () => NativeFacePigmentSection.fromJson(
            {'field': 'color', 'mode': 10, 'values': values},
            {
              'color': ['native.orange', 'native.orange:0.50'],
            },
            [pigment],
          ),
          throwsFormatException,
        );
      }
    },
  );
}
