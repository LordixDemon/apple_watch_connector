import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_configured_previews.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_indexed_preview_image.dart';
import 'package:apple_watch_companion/services/native_component_preview_image.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_preview.dart';
import 'native_preview_assets.dart';

class _DirectNativeBundle extends CachingAssetBundle {
  final requested = <String>[];
  @override
  Future<ByteData> load(String key) {
    requested.add(key);
    if (key.startsWith('AssetManifest.')) {
      throw StateError('Fixed native images must not load the asset manifest');
    }
    return rootBundle.load(key);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Absent native customization resolves the empty style, malformed does not',
    () {
      final configuration = <String, dynamic>{'face type': 'solar'};
      expect(
        NativeConfiguredPreviews.keyFor('type:solar', configuration),
        NativeConfiguredPreviews.configurationKey('type:solar', {}),
      );
      expect(configuration.containsKey('customization'), isFalse);
      expect(NativeConfiguredPreviews.keyFor('type:solar', null), isNull);
      for (final value in [null, [], 'unknown']) {
        expect(
          NativeConfiguredPreviews.keyFor('type:solar', {
            ...configuration,
            'customization': value,
          }),
          isNull,
        );
      }
    },
  );
  test('Preview catalog decoder runs as a pure isolate worker', () async {
    final entries = {
      'a' * 64: 'assets/native_faces/configured_previews/${'b' * 64}.webp',
    };
    final bytes = Uint8List.fromList(
      utf8.encode(
        jsonEncode({
          'version': 1,
          'locale': 'en_US',
          'blankComplications': true,
          'entries': entries,
        }),
      ),
    );
    final before = Uint8List.fromList(bytes);
    expect(
      await compute(NativeConfiguredPreviews.decodeManifest, bytes),
      entries,
    );
    expect(bytes, before);
  });
  test('Worker refuses malformed, oversized and incompatible catalog data', () {
    expect(
      () => NativeConfiguredPreviews.decodeManifest(
        Uint8List(2 * 1024 * 1024 + 1),
      ),
      throwsFormatException,
    );
    expect(
      () => NativeConfiguredPreviews.decodeManifest(Uint8List.fromList([255])),
      throwsFormatException,
    );
    for (final value in [
      [],
      {'version': 1, 'locale': 'ru_UA', 'entries': {}},
      {
        'version': 1,
        'locale': 'en_US',
        'blankComplications': false,
        'entries': {},
      },
      {
        'version': 1,
        'locale': 'en_US',
        'blankComplications': true,
        'entries': {'a' * 64: '../outside.webp'},
      },
    ]) {
      expect(
        () => NativeConfiguredPreviews.decodeManifest(
          Uint8List.fromList(utf8.encode(jsonEncode(value))),
        ),
        throwsFormatException,
      );
    }
  });
  test(
    'Version two routes only managed indexed frames, preserving version one',
    () {
      Uint8List bytes(int version, String asset) => Uint8List.fromList(
        utf8.encode(
          jsonEncode({
            'version': version,
            'locale': 'en_US',
            'blankComplications': true,
            'entries': {'a' * 64: asset},
          }),
        ),
      );
      final indexed = 'assets/native_faces/configured_previews/${'b' * 64}.nfp';
      expect(NativeConfiguredPreviews.decodeManifest(bytes(2, indexed)), {
        'a' * 64: indexed,
      });
      expect(
        () => NativeConfiguredPreviews.decodeManifest(bytes(1, indexed)),
        throwsFormatException,
      );
      for (final asset in [
        indexed.replaceAll('.nfp', '.nfpm'),
        '../outside.nfp',
      ]) {
        expect(
          () => NativeConfiguredPreviews.decodeManifest(bytes(2, asset)),
          throwsFormatException,
        );
      }
    },
  );
  test(
    'Canonical key ignores JSON order and preserves every native style field',
    () {
      final a = NativeConfiguredPreviews.configurationKey('bundle:example', {
        'style': 'analog',
        'color': 'orange',
      });
      final b = NativeConfiguredPreviews.configurationKey('bundle:example', {
        'color': 'orange',
        'style': 'analog',
      });
      expect(a, b);
      expect(
        NativeConfiguredPreviews.configurationKey('bundle:other', {
          'color': 'orange',
          'style': 'analog',
        }),
        isNot(a),
      );
      expect(
        NativeConfiguredPreviews.configurationKey('bundle:example', {
          'color': 'green',
          'style': 'analog',
        }),
        isNot(a),
      );
      expect(
        NativeConfiguredPreviews.configurationKey('bundle:example', {
          'color': 'orange',
          'style': 'analog',
          'future-field': 'opaque',
        }),
        isNot(a),
      );
    },
  );
  test(
    'Catalog refuses traversal, mismatched filenames and excessive entries',
    () {
      final key = 'a' * 64;
      expect(
        () => NativeConfiguredPreviews.decodeEntries({key: '../../image.webp'}),
        throwsFormatException,
      );
      expect(
        () => NativeConfiguredPreviews.decodeEntries({
          key: 'assets/native_faces/configured_previews/not-a-hash.webp',
        }),
        throwsFormatException,
      );
      expect(
        () =>
            NativeConfiguredPreviews.decodeEntries({'not-a-key': 'image.webp'}),
        throwsFormatException,
      );
    },
  );
  test(
    'Every Waypoint native style combination has its own matching verified asset',
    () async {
      final profiles = await NativeFaceGallery.load();
      final template = profiles.singleWhere((p) => p.title('en') == 'Waypoint');
      final configuration =
          jsonDecode(template.configurationJson) as Map<String, dynamic>;
      (configuration['customization'] as Map)['color'] = 'leghorn.hero-3';
      expect(
        NativeConfiguredPreviews.configurationKey(
          template.family,
          configuration['customization'] as Map<String, dynamic>,
        ),
        'df690d33b919565fd363ecdb2e33208bc06eb75e0c2bbb9598ec9a1c7126202c',
      );
      expect(
        NativeFacePreview.option(
          template,
          configuration,
          'color',
          'future-token',
        ),
        isNull,
      );
      final fields = template.options.keys.toList();
      final allAssets = await allNativePreviewAssets();
      var count = 0;
      void visit(int index) {
        if (index == fields.length) {
          expect(
            allAssets[NativeConfiguredPreviews.configurationKey(
              template.family,
              configuration['customization'] as Map<String, dynamic>,
            )],
            isNotNull,
          );
          count++;
          return;
        }
        for (final value in template.options[fields[index]]!) {
          (configuration['customization'] as Map)[fields[index]] = value;
          visit(index + 1);
        }
      }

      visit(0);
      expect(count, 3420);
      (configuration['customization'] as Map)['color'] = 'unknown-future-token';
      expect(
        NativeConfiguredPreviews.asset(template.family, configuration),
        isNull,
      );
      // Indexed frames and maps are all decoded/hash-checked in the codec suite.
      // Check the remaining native fallback images here without duplicate I/O.
      for (final asset in allAssets.values.toSet().where(
        (asset) => asset.endsWith('.webp'),
      )) {
        expect((await rootBundle.load(asset)).lengthInBytes, greaterThan(12));
      }
    },
  );
  testWidgets(
    'Default native sample loads directly without a global asset manifest',
    (tester) async {
      final templates = await tester.runAsync(NativeFaceGallery.load);
      final template = templates!.firstWhere((t) => !t.requiresPhotos);
      final bundle = _DirectNativeBundle();
      await tester.pumpWidget(
        DefaultAssetBundle(
          bundle: bundle,
          child: CupertinoApp(home: NativeFacePreview(template: template)),
        ),
      );
      final image = tester.widget<Image>(find.byType(Image));
      await tester.runAsync(
        () => precacheImage(image.image, tester.element(find.byType(Image))),
      );
      expect(bundle.requested, contains(template.previewAsset));
      expect(
        bundle.requested.where((k) => k.startsWith('AssetManifest.')),
        isEmpty,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'A component shade preserves SUBDIALS, updates its image and keeps the draft',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final template = profiles!.singleWhere(
        (p) => p.title('en') == 'Activity Analog',
      );
      final configuration =
          jsonDecode(template.configurationJson) as Map<String, dynamic>;
      final customization = configuration['customization'] as Map;
      customization['detail'] = 'detailed';
      customization['color'] = 'standard.plum';
      Future<void> show() async {
        await tester.runAsync(
          () =>
              NativeConfiguredPreviews.resolve(template.family, configuration),
        );
        await tester.pumpWidget(
          CupertinoApp(
            home: Column(
              children: [
                NativeFacePreview(
                  template: template,
                  configuration: configuration,
                  width: 112,
                ),
                NativeFacePreviewCaption(
                  template: template,
                  configuration: configuration,
                ),
              ],
            ),
          ),
        );
      }

      await show();
      expect(find.byType(Image), findsOneWidget);
      expect(find.text('Style preview'), findsOneWidget);
      final initial = tester.widget<Image>(find.byType(Image)).image;
      customization['color'] = 'standard.plum:0.25';
      final requested = jsonEncode(configuration);
      await show();
      expect(find.byType(Image), findsOneWidget);
      final component = tester.widget<Image>(find.byType(Image)).image;
      expect(component, isA<NativeComponentPreviewImage>());
      expect(component, isNot(initial));
      expect(find.text('Style preview'), findsOneWidget);
      expect(jsonEncode(configuration), requested);
      expect(customization['detail'], 'detailed');
      expect(
        NativeFacePreview.caption(template, configuration),
        'Style preview',
      );

      // Returning to a catalogued shade restores its exact SUBDIALS image.
      customization['color'] = 'standard.plum';
      await show();
      expect(tester.widget<Image>(find.byType(Image)).image, initial);
      expect(find.text('Style preview'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'Explicit unknown settings cannot load the default sample asset',
    (tester) async {
      final templates = await tester.runAsync(NativeFaceGallery.load);
      final template = templates!.firstWhere((t) => !t.requiresPhotos);
      final bundle = _DirectNativeBundle();
      const configuration = <String, dynamic>{
        'customization': {'unknown': 'style'},
      };
      await tester.runAsync(
        () => NativeConfiguredPreviews.resolve(template.family, configuration),
      );
      await tester.pumpWidget(
        DefaultAssetBundle(
          bundle: bundle,
          child: CupertinoApp(
            home: NativeFacePreview(
              template: template,
              configuration: configuration,
            ),
          ),
        ),
      );
      expect(find.byType(Image), findsNothing);
      expect(find.byIcon(CupertinoIcons.clock), findsOneWidget);
      expect(bundle.requested, isEmpty);
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'Choosing a style changes the native image with bounded decoding and no animation',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final template = profiles!.singleWhere(
        (p) => p.title('en') == 'Waypoint',
      );
      final configuration =
          jsonDecode(template.configurationJson) as Map<String, dynamic>;
      Future<void> show() async {
        await tester.runAsync(
          () =>
              NativeConfiguredPreviews.resolve(template.family, configuration),
        );
        await tester.pumpWidget(
          CupertinoApp(
            home: NativeFacePreview(
              template: template,
              configuration: configuration,
              width: 112,
            ),
          ),
        );
      }

      await show();
      String asset(ImageProvider provider) =>
          provider is NativeIndexedPreviewImage
          ? provider.asset
          : ((provider as ResizeImage).imageProvider as ExactAssetImage)
                .assetName;
      final initial = tester.widget<Image>(find.byType(Image)).image;
      (configuration['customization'] as Map)['style'] = 'analog';
      await show();
      final next = tester.widget<Image>(find.byType(Image)).image;
      expect(asset(next), isNot(asset(initial)));
      final resizedWidth = next is NativeIndexedPreviewImage
          ? next.cacheWidth
          : (next as ResizeImage).width;
      expect(resizedWidth, lessThanOrEqualTo(336));
      expect(tester.binding.hasScheduledFrame, isFalse);
      await tester.pumpWidget(const SizedBox());
    },
  );
  for (final entry in const {
    'Infograph': 185,
    'GMT': 195,
    'Count Up': 195,
    'Chronograph Pro': 975,
    'California': 7562,
    'Contour': 744,
    'Solar Graph': 1,
    'Pride Threads': 2,
    'Pride Woven': 2,
    'Solar Dial': 2,
    'Motion': 3,
    'Unity': 3,
    'Breathe': 4,
    'Timelapse': 6,
    'Unity Lights': 8,
    'Unity Bloom': 10,
    'Playtime': 18,
    'Nike Globe': 19,
    'Unity Mosaic': 20,
    'Kaleidoscope': 132,
    'Nike Bounce': 134,
    'Nike Digital': 171,
    'Palette': 197,
    'Nike Analog': 232,
    'Nike Compact': 330,
    'Activity Digital': 368,
    'Activity Analog': 370,
    'Stripes': 384,
    'Color': 552,
    'Flow': 696,
    'Simple': 736,
    'Nike Hybrid': 954,
    'Flux': 1080,
    'Exactograph': 1170,
  }.entries) {
    test(
      'Every ${entry.key} configuration has an exact native preview',
      () async {
        final templates = await NativeFaceGallery.load();
        final template = templates.singleWhere(
          (t) => t.title('en') == entry.key,
        );
        final configuration =
            jsonDecode(template.configurationJson) as Map<String, dynamic>;
        final fields = template.options.keys.toList();
        final allAssets = await allNativePreviewAssets();
        var checked = 0;
        void visit(int index) {
          if (index == fields.length) {
            expect(
              allAssets[NativeConfiguredPreviews.keyFor(
                template.family,
                configuration,
              )],
              isNotNull,
              reason: jsonEncode(configuration['customization']),
            );
            checked++;
            return;
          }
          for (final value in template.options[fields[index]]!) {
            (configuration['customization'] as Map)[fields[index]] = value;
            visit(index + 1);
          }
        }

        visit(0);
        expect(checked, entry.value);
        await NativeConfiguredPreviews.resolve(template.family, configuration);
        expect(
          NativeFacePreview.caption(template, configuration),
          isNot(contains('Default')),
        );
        (configuration.putIfAbsent('customization', () => <String, dynamic>{})
                as Map)['color'] =
            'future-unknown-color';
        expect(
          NativeConfiguredPreviews.asset(template.family, configuration),
          isNull,
        );
      },
    );
  }
}
