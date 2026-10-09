import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_preview_catalog.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_preview.dart';

Uint8List bytes(Object value) =>
    Uint8List.fromList(utf8.encode(jsonEncode(value)));
String key(String prefix) => prefix + 'a' * 62;
String image(String prefix) =>
    'assets/native_faces/configured_previews/${key(prefix)}.nfp';

class Fixture {
  final files = <String, Uint8List>{};
  final shards = <String, NativePreviewShard>{};
  Fixture(List<String> prefixes) {
    final references = <String, Object>{};
    for (final prefix in prefixes) {
      final data = bytes({
        'version': 1,
        'prefix': prefix,
        'entries': {key(prefix): image(prefix)},
      });
      final asset =
          'assets/native_faces/configured_preview_indexes/${sha256.convert(data)}.json';
      files[asset] = data;
      references[prefix] = {'asset': asset, 'count': 1, 'bytes': data.length};
      shards[prefix] = NativePreviewShard(prefix, asset, 1, data.length);
    }
    files[nativePreviewManifestAsset] = bytes({
      'version': 3,
      'locale': 'en_US',
      'blankComplications': true,
      'shards': references,
    });
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Root loading reads no shards; isolate checks exact identity and membership',
    () async {
      final fixture = Fixture(['00', '01']);
      final reads = <String>[];
      final catalog = NativePreviewCatalog(
        loadAsset: (asset) async {
          reads.add(asset);
          return fixture.files[asset]!;
        },
      );
      await catalog.load();
      expect(reads, [nativePreviewManifestAsset]);
      expect(catalog.peek(key('00')), isNull);
      expect(await catalog.resolve(key('00')), image('00'));
      expect(reads.length, 2);
      expect(catalog.cachedShardCount, 1);
      expect(
        await compute(
          decodeNativePreviewShard,
          NativePreviewShardRequest(
            fixture.shards['00']!,
            fixture.files[fixture.shards['00']!.asset]!,
          ),
        ),
        {key('00'): image('00')},
      );
    },
  );

  test(
    'Coalesces concurrent prefix reads and caps active jobs at two',
    () async {
      final fixture = Fixture(['00', '01', '02']);
      final gates = <String, Completer<Uint8List>>{};
      final catalog = NativePreviewCatalog(
        loadAsset: (asset) async {
          if (asset == nativePreviewManifestAsset) return fixture.files[asset]!;
          return (gates[asset] = Completer<Uint8List>()).future;
        },
      );
      await catalog.load();
      final first = List.generate(20, (_) => catalog.resolve(key('00')));
      final second = catalog.resolve(key('01'));
      final third = catalog.resolve(key('02'));
      await Future<void>.delayed(Duration.zero);
      expect(gates.length, 2);
      expect(catalog.activeLoads, 2);
      expect(catalog.pendingLoads, 1);
      final zero = fixture.shards['00']!.asset;
      gates[zero]!.complete(fixture.files[zero]);
      expect(await Future.wait(first), List.filled(20, image('00')));
      await Future<void>.delayed(Duration.zero);
      expect(gates.length, 3);
      for (final prefix in ['01', '02']) {
        final asset = fixture.shards[prefix]!.asset;
        gates[asset]!.complete(fixture.files[asset]);
      }
      expect(await second, image('01'));
      expect(await third, image('02'));
      expect(catalog.activeLoads, 0);
      expect(catalog.pendingLoads, 0);
    },
  );

  test(
    'Evicts encoded metadata, keeps bounded resolved paths and known misses',
    () async {
      final fixture = Fixture(['00', '01', '02']);
      var reads = 0;
      final catalog = NativePreviewCatalog(
        maxCachedShards: 1,
        maxCachedBytes: 2048,
        maxMemoEntries: 2,
        loadAsset: (asset) async {
          reads++;
          return fixture.files[asset]!;
        },
      );
      final retained = await catalog.resolve(key('00'));
      await catalog.resolve(key('01'));
      expect(catalog.cachedShardCount, 1);
      expect(catalog.cachedEncodedBytes, lessThanOrEqualTo(2048));
      expect(catalog.peek(key('00')), retained);
      final before = reads;
      expect(await catalog.resolve(key('00')), retained);
      expect(reads, before);
      final absent = '01${'b' * 62}';
      expect(await catalog.resolve(absent), isNull);
      expect(catalog.isResolved(absent), isTrue);
      await catalog.resolve(key('02'));
      expect(catalog.memoEntryCount, lessThanOrEqualTo(2));
      expect(retained, image('00'));
      expect(await catalog.resolve(key('ff')), isNull);
      expect(catalog.peek('invalid'), isNull);
      expect(catalog.isResolved('invalid'), isTrue);
    },
  );

  test(
    'Corrupt shard fails closed and a subsequent valid read can retry',
    () async {
      final fixture = Fixture(['00']);
      var corrupt = true;
      final catalog = NativePreviewCatalog(
        loadAsset: (asset) async {
          final data = fixture.files[asset]!;
          return asset != nativePreviewManifestAsset && corrupt
              ? (Uint8List.fromList(data)..[0] ^= 1)
              : data;
        },
      );
      await expectLater(catalog.resolve(key('00')), throwsFormatException);
      expect(catalog.isResolved(key('00')), isFalse);
      expect(catalog.activeLoads, 0);
      corrupt = false;
      expect(await catalog.resolve(key('00')), image('00'));
    },
  );

  test('Refuses wrong prefix, count, version, managed path and byte size', () {
    final fixture = Fixture(['00']);
    final reference = fixture.shards['00']!;
    final original = fixture.files[reference.asset]!;
    for (final value in [
      {
        'version': 2,
        'prefix': '00',
        'entries': {key('00'): image('00')},
      },
      {
        'version': 1,
        'prefix': '01',
        'entries': {key('00'): image('00')},
      },
      {
        'version': 1,
        'prefix': '00',
        'entries': {key('01'): image('01')},
      },
      {
        'version': 1,
        'prefix': '00',
        'entries': {key('00'): '../outside.nfp'},
      },
      {'version': 1, 'prefix': '00', 'entries': {}},
    ]) {
      final data = bytes(value);
      final ref = NativePreviewShard(
        '00',
        'assets/native_faces/configured_preview_indexes/${sha256.convert(data)}.json',
        1,
        data.length,
      );
      expect(
        () => decodeNativePreviewShard(NativePreviewShardRequest(ref, data)),
        throwsFormatException,
      );
    }
    expect(
      () => decodeNativePreviewShard(
        NativePreviewShardRequest(
          NativePreviewShard('00', reference.asset, 1, original.length + 1),
          original,
        ),
      ),
      throwsFormatException,
    );
    final root =
        jsonDecode(utf8.decode(fixture.files[nativePreviewManifestAsset]!))
            as Map;
    (root['shards'] as Map)['00']['asset'] = '../outside.json';
    expect(() => NativePreviewIndex.decode(bytes(root)), throwsFormatException);
    root['version'] = 4;
    expect(() => NativePreviewIndex.decode(bytes(root)), throwsFormatException);
    expect(
      () => NativePreviewIndex.decode(Uint8List(2 * 1024 * 1024 + 1)),
      throwsFormatException,
    );
  });

  testWidgets(
    'Rapid changes and disposal cannot show an older selected style',
    (tester) async {
      final fixture = Fixture(['00', '01', '02']);
      final gates = <String, Completer<Uint8List>>{};
      final catalog = NativePreviewCatalog(
        loadAsset: (asset) async {
          if (asset == nativePreviewManifestAsset) return fixture.files[asset]!;
          return (gates[asset] = Completer<Uint8List>()).future;
        },
      );
      await catalog.load();
      Future<void> show(String prefix) => tester.pumpWidget(
        CupertinoApp(
          home: NativePreviewAssetResolver(
            configurationKey: key(prefix),
            catalog: catalog,
            builder: (_, asset) => Text(asset ?? 'Unresolved'),
          ),
        ),
      );
      await show('00');
      await show('01');
      final one = fixture.shards['01']!.asset;
      gates[one]!.complete(fixture.files[one]);
      await catalog.resolve(key('01'));
      await tester.pump();
      expect(find.text(image('01')), findsOneWidget);
      final zero = fixture.shards['00']!.asset;
      gates[zero]!.complete(fixture.files[zero]);
      await catalog.resolve(key('00'));
      await tester.pump();
      expect(find.text(image('01')), findsOneWidget);
      expect(find.text(image('00')), findsNothing);
      await show('02');
      expect(find.text(image('01')), findsNothing);
      await tester.pumpWidget(const SizedBox());
      final two = fixture.shards['02']!.asset;
      gates[two]!.complete(fixture.files[two]);
      await catalog.resolve(key('02'));
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
  );
}
