import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_indexed_preview_codec.dart';
import 'native_preview_assets.dart';

Uint8List _header(
  String magic, {
  int width = 2,
  int height = 2,
  int classes = 3,
}) {
  final bytes = Uint8List(16)..setRange(0, 4, ascii.encode(magic));
  bytes[4] = 1;
  bytes[5] = 2;
  ByteData.sublistView(bytes)
    ..setUint16(8, width, Endian.little)
    ..setUint16(10, height, Endian.little)
    ..setUint32(12, classes, Endian.little);
  return bytes;
}

({Uint8List frame, Uint8List map, Uint8List pixels, String digest}) _fixture({
  List<int> indices = const [0, 1, 2, 0],
  List<int> palette = const [10, 20, 30, 255, 200, 50, 80, 128, 99, 98, 97, 0],
}) {
  final rawIndices = Uint8List(indices.length * 2);
  final view = ByteData.sublistView(rawIndices);
  for (var i = 0; i < indices.length; i++) {
    view.setUint16(i * 2, indices[i], Endian.little);
  }
  final map = Uint8List.fromList([
    ..._header('NFPM'),
    ...zlib.encode(rawIndices),
  ]);
  // Explicit 2x2 reference, including straight alpha and transparent RGB.
  final pixels = Uint8List.fromList([
    10,
    20,
    30,
    255,
    200,
    50,
    80,
    128,
    99,
    98,
    97,
    0,
    10,
    20,
    30,
    255,
  ]);
  final frame = Uint8List.fromList([
    ..._header('NFPT'),
    ...sha256.convert(map).bytes,
    ...sha256.convert(pixels).bytes,
    ...zlib.encode(palette),
  ]);
  return (
    frame: frame,
    map: map,
    pixels: pixels,
    digest: sha256.convert(frame).toString(),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('Factored pixels retain straight alpha and unknown transparent RGB', () {
    final fixture = _fixture();
    final decoded = NativeIndexedPreviewCodec.decode(
      fixture.frame,
      fixture.map,
      fixture.digest,
    );
    expect(decoded.width, 2);
    expect(decoded.height, 2);
    expect(decoded.rgba, fixture.pixels);
    expect(
      NativeIndexedPreviewCodec.mapDigest(fixture.frame),
      sha256.convert(fixture.map).toString(),
    );
    final displayed = NativeIndexedPreviewCodec.decode(
      fixture.frame,
      fixture.map,
      fixture.digest,
      premultiply: true,
    );
    expect(displayed.rgba, [
      10,
      20,
      30,
      255,
      100,
      25,
      40,
      128,
      0,
      0,
      0,
      0,
      10,
      20,
      30,
      255,
    ]);
    expect(
      NativeIndexedPreviewCodec.decode(
        fixture.frame,
        fixture.map,
        fixture.digest,
      ).rgba,
      fixture.pixels,
    ); // Shared source inputs were not modified.
  });

  test('Codec rejects corrupt identity, headers and pixel references', () {
    final f = _fixture();
    expect(
      () => NativeIndexedPreviewCodec.decode(f.frame, f.map, '0' * 64),
      throwsFormatException,
    );
    for (final offset in [0, 4, 5, 6, 8, 12, 16, 48]) {
      final bad = Uint8List.fromList(f.frame)..[offset] ^= 127;
      expect(
        () => NativeIndexedPreviewCodec.decode(
          bad,
          f.map,
          sha256.convert(bad).toString(),
        ),
        throwsFormatException,
        reason: 'header/hash offset $offset',
      );
    }
    final invalidIndex = _fixture(indices: [0, 1, 3, 0]);
    expect(
      () => NativeIndexedPreviewCodec.decode(
        invalidIndex.frame,
        invalidIndex.map,
        invalidIndex.digest,
      ),
      throwsFormatException,
    );
    final wrongPixels = _fixture(
      palette: [11, 20, 30, 255, 200, 50, 80, 128, 99, 98, 97, 0],
    );
    expect(
      () => NativeIndexedPreviewCodec.decode(
        wrongPixels.frame,
        wrongPixels.map,
        wrongPixels.digest,
      ),
      throwsFormatException,
    );
  });

  test(
    'Compressed input cannot exceed declared buffers or allocation bounds',
    () {
      for (final f in [
        _fixture(indices: [0, 1, 2]),
        _fixture(indices: List.filled(4096, 0)),
        _fixture(palette: [1, 2, 3]),
        _fixture(palette: List.filled(4096, 0)),
      ]) {
        expect(
          () => NativeIndexedPreviewCodec.decode(f.frame, f.map, f.digest),
          throwsFormatException,
        );
      }
      expect(
        () => NativeIndexedPreviewCodec.mapDigest(Uint8List(80)),
        throwsFormatException,
      );
      expect(
        () => NativeIndexedPreviewCodec.mapDigest(
          Uint8List(NativeIndexedPreviewCodec.maxEncodedBytes + 1),
        ),
        throwsFormatException,
      );
      final f = _fixture();
      for (final size in [(0, 2), (1025, 2), (1024, 1024)]) {
        final bad = Uint8List.fromList(f.frame);
        ByteData.sublistView(bad)
          ..setUint16(8, size.$1, Endian.little)
          ..setUint16(10, size.$2, Endian.little);
        expect(
          () => NativeIndexedPreviewCodec.mapDigest(bad),
          throwsFormatException,
        );
      }
    },
  );

  test(
    'Every promoted frame decodes to its build-verified native pixel hash',
    () async {
      final frames = (await allNativePreviewAssets()).values
          .toSet()
          .cast<String>()
          .where((v) => v.endsWith('.nfp'));
      expect(frames, isNotEmpty);
      final maps = <String, Uint8List>{};
      var checked = 0;
      for (final asset in frames) {
        // The test messenger resolves asset loads synchronously. Yield between
        // bounded batches instead of nesting ten thousand synchronous callbacks.
        if (checked++ % 64 == 0) {
          await Future<void>.delayed(Duration.zero);
        }
        final frame = Uint8List.sublistView(await rootBundle.load(asset));
        final mapHash = NativeIndexedPreviewCodec.mapDigest(frame);
        final map = maps[mapHash] ??= Uint8List.sublistView(
          await rootBundle.load(
            'assets/native_faces/configured_previews/$mapHash.nfpm',
          ),
        );
        final decoded = NativeIndexedPreviewCodec.decode(
          frame,
          map,
          asset.split('/').last.split('.').first,
        );
        expect(decoded.width, 422);
        expect(decoded.height, 514);
      }
    },
    timeout: const Timeout(Duration(minutes: 4)),
  );
}
