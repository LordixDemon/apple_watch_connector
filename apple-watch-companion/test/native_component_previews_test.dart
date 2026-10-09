import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_component_preview_codec.dart';
import 'package:apple_watch_companion/services/native_component_previews.dart';
import 'package:apple_watch_companion/services/native_preview_decode_queue.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';

Uint8List fixture({
  List<int> plane = const [0, 0],
  List<int> table = const [10, 20, 30, 128, 100, 80, 60],
}) {
  final p = zlib.encode(plane), t = zlib.encode(table);
  final header = Uint8List(24)..setRange(0, 4, ascii.encode('NFCP'));
  header[4] = 1;
  header[5] = 1;
  ByteData.sublistView(header)
    ..setUint16(8, 1, Endian.little)
    ..setUint16(10, 1, Endian.little)
    ..setUint32(12, 1, Endian.little)
    ..setUint32(16, p.length, Endian.little)
    ..setUint32(20, t.length, Endian.little);
  return Uint8List.fromList([...header, ...p, ...t]);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Native role basis preserves premultiplied alpha and all fixed channels',
    () {
      final data = fixture(), before = Uint8List.fromList(fixture());
      final decoded = NativeComponentPreviewCodec.decode(
        data,
        sha256.convert(data).toString(),
        [.5, .25, 1],
      );
      expect(decoded.rgba, [60, 40, 90, 128]);
      expect(data, before);
    },
  );
  test(
    'Rejects identity, palette, expanded size, index and alpha violations',
    () {
      void fails(
        Uint8List data, {
        List<double> colors = const [1, 1, 1],
        String? digest,
      }) {
        expect(
          () => NativeComponentPreviewCodec.decode(
            data,
            digest ?? sha256.convert(data).toString(),
            colors,
          ),
          throwsFormatException,
        );
      }

      fails(fixture(), digest: '0' * 64);
      fails(fixture(), colors: [double.nan, 0, 0]);
      fails(fixture(), colors: [-.1, 0, 0]);
      fails(fixture(), colors: [1, 0]);
      fails(fixture(plane: [1, 0]));
      fails(fixture(table: [100, 20, 30, 128, 100, 80, 60]));
      fails(fixture(plane: List.filled(65536, 0)));
      final header = fixture();
      ByteData.sublistView(header).setUint16(8, 1025, Endian.little);
      fails(header);
    },
  );
  test('Half-byte conversion retains the adjacent lower double', () {
    final data = fixture(table: [0, 0, 0, 255, 1, 1, 1]);
    final decoded = NativeComponentPreviewCodec.decode(
      data,
      sha256.convert(data).toString(),
      [.49999999999999994, .5, .5000000000000001],
    );
    expect(decoded.rgba, [0, 1, 1, 255]);
  });
  test(
    'Dart worker matches every independently validated native reconstruction',
    () async {
      final definitions = NativeComponentPreviews.decodeManifest(
        await rootBundle.loadString(
          'assets/native_faces/component_previews/catalog.json',
        ),
      );
      final reports =
          jsonDecode(
                File(
                  'test/fixtures/native_faces/face_components.json',
                ).readAsStringSync(),
              )
              as List;
      for (final report in reports) {
        for (final reference in report['references']) {
          final percent = reference['percent'] as int;
          final configuration = <String, dynamic>{
            'customization': <String, dynamic>{
              'detail': report['detail'],
              'color': percent == 50
                  ? report['base']
                  : '${report['base']}:${(percent / 100).toStringAsFixed(2)}',
            },
          };
          final original = jsonEncode(configuration);
          final request = NativeComponentPreviews.resolve(
            'type:activity analog rich',
            configuration,
            entries: definitions,
          )!;
          final bytes = Uint8List.sublistView(
            await rootBundle.load(request.asset),
          );
          final digest = request.asset.split('/').last.split('.').first;
          final pixels = NativeComponentPreviewCodec.decode(
            bytes,
            digest,
            request.colors,
          );
          expect(
            sha256.convert(pixels.rgba).toString(),
            reference['reconstructedRgbaSha256'],
            reason: '${report['base']} ${report['detail']} $percent',
          );
          expect(jsonEncode(configuration), original);
        }
      }
    },
  );
  test(
    'Only complete proven native style identities accept canonical fractions',
    () async {
      final definitions = NativeComponentPreviews.decodeManifest(
        await rootBundle.loadString(
          'assets/native_faces/component_previews/catalog.json',
        ),
      );
      NativeComponentPreviewRequest? resolve(
        Map<String, dynamic> custom, {
        String family = 'type:activity analog rich',
      }) => NativeComponentPreviews.resolve(family, {
        'customization': custom,
      }, entries: definitions);
      final detailed = resolve({
        'detail': 'detailed',
        'color': 'standard.plum:0.37',
      })!;
      final rings = resolve({
        'detail': 'simple',
        'color': 'standard.plum:0.37',
      })!;
      expect(detailed.asset, isNot(rings.asset));
      expect(
        detailed.colors,
        isNot(
          resolve({
            'detail': 'detailed',
            'color': 'standard.plum:0.64',
          })!.colors,
        ),
      );
      expect(() => detailed.colors.add(1), throwsUnsupportedError);
      for (final token in [
        'standard.plum:0.5',
        'standard.plum:0.50',
        'standard.plum:1.01',
        'standard.plum:NaN',
        'future-color',
      ]) {
        expect(resolve({'detail': 'detailed', 'color': token}), isNull);
      }
      expect(
        resolve({'detail': 'future', 'color': 'standard.plum:0.37'}),
        isNull,
      );
      expect(
        resolve({
          'detail': 'detailed',
          'color': 'standard.plum:0.37',
          'unknown': 1,
        }),
        isNull,
      );
      expect(
        resolve({
          'detail': 'detailed',
          'color': 'standard.plum:0.37',
        }, family: 'type:unknown'),
        isNull,
      );
    },
  );
  test(
    'Every native Activity Analog slider color supports both styles at all canonical positions',
    () async {
      final templates = await NativeFaceGallery.load();
      final template = templates.singleWhere(
        (t) => t.family == 'type:activity analog rich',
      );
      final section = template.pigmentSection('color')!;
      final definitionText = await rootBundle.loadString(
        'assets/native_faces/component_previews/catalog.json',
      );
      final entries = NativeComponentPreviews.decodeManifest(definitionText);
      final colors = section.options.values
          .where((p) => p.supportsShade)
          .toList();
      expect(colors.length, 19);
      final assets = <String>{};
      var checked = 0;
      for (final color in colors) {
        for (final detail in template.options['detail']!) {
          for (var percent = 0; percent <= 100; percent++) {
            final configuration = <String, dynamic>{
              'customization': <String, dynamic>{
                'color': color.tokenAt(percent),
                'detail': detail,
              },
            };
            final before = jsonEncode(configuration);
            final request = NativeComponentPreviews.resolve(
              template.family,
              configuration,
              entries: entries,
            );
            expect(
              request,
              isNotNull,
              reason: '$detail ${color.tokenAt(percent)}',
            );
            expect(jsonEncode(configuration), before);
            assets.add(request!.asset);
            checked++;
          }
        }
      }
      expect(checked, 3838);
      expect(assets.length, 2);
    },
  );
  test(
    'Pure-white native scheme is isolated to its captured endpoint',
    () async {
      final entries = NativeComponentPreviews.decodeManifest(
        await rootBundle.loadString(
          'assets/native_faces/component_previews/catalog.json',
        ),
      );
      for (final definition in entries.values.where(
        (d) => d.base == 'standard.gray',
      )) {
        expect(definition.percentColors.keys, [0]);
        final branch = definition.percentColors[0]!;
        expect(branch.any((c) => c != 0 && c != 1 && c != .15), isTrue);
      }
      final source =
          jsonDecode(
                await rootBundle.loadString(
                  'assets/native_faces/component_previews/catalog.json',
                ),
              )
              as Map;
      final row = Map<String, dynamic>.from(
        (source['entries'] as Map).values.first,
      );
      for (final invalid in [
        {'00': []},
        {'101': []},
        {
          '0': [
            [1, 1, 1, 0],
          ],
        },
      ]) {
        expect(
          () => NativeComponentPreviewDefinition.fromJson({
            ...row,
            'percentColors': invalid,
          }),
          throwsFormatException,
        );
      }
    },
  );
  test(
    'Indexed and component decode queue never exceeds two simultaneous tasks',
    () async {
      final queue = NativePreviewDecodeQueue();
      final first = Completer<int>(),
          second = Completer<int>(),
          third = Completer<int>();
      final a = queue.run(() => first.future),
          b = queue.run(() => second.future),
          c = queue.run(() => third.future);
      expect(queue.active, 2);
      expect(queue.pending, 1);
      first.complete(1);
      expect(await a, 1);
      await Future<void>.delayed(Duration.zero);
      expect(queue.active, 2);
      expect(queue.pending, 0);
      second.complete(2);
      third.complete(3);
      expect(await b, 2);
      expect(await c, 3);
      await Future<void>.delayed(Duration.zero);
      expect(queue.active, 0);
    },
  );
}
