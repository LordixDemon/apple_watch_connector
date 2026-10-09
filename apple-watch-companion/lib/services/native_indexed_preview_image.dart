import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:flutter/services.dart';
import 'native_indexed_preview_codec.dart';
import 'native_preview_decode_queue.dart';

/// A scaled Flutter-cache image. Reconstruction and alpha conversion are workers.
/// No full-size decoded image is retained by this provider or its asset cache.
@immutable
final class NativeIndexedPreviewImage
    extends ImageProvider<NativeIndexedPreviewImage> {
  const NativeIndexedPreviewImage(this.asset, {required this.cacheWidth});
  final String asset;
  final int cacheWidth;
  static final _work = NativePreviewDecodeQueue.shared;
  static final _maps = _CompressedMapCache();
  static final _profileWorkerMicros = <int>[];
  static int _profileErrors = 0;

  /// Explicit profile entry point reads these; ordinary release retains no samples.
  static Map<String, num> get profileMetrics {
    final values = _profileWorkerMicros.toList()..sort();
    return {
      'decodes': values.length,
      'errors': _profileErrors,
      'workerP95Ms': values.isEmpty
          ? 0
          : values[((values.length - 1) * .95).round()] / 1000,
      'workerMaxMs': values.isEmpty ? 0 : values.last / 1000,
      'compressedMapBytes': _maps._bytes,
      'compressedMapEntries': _maps._entries.length,
      'queuePeak': _work.peak,
      'queuePending': _work.pending,
      'queueActive': _work.active,
    };
  }

  static final _path = RegExp(
    r'^assets/native_faces/configured_previews/([a-f0-9]{64})\.nfp$',
  );

  @override
  Future<NativeIndexedPreviewImage> obtainKey(
    ImageConfiguration configuration,
  ) => SynchronousFuture(this);

  @override
  ImageStreamCompleter loadImage(
    NativeIndexedPreviewImage key,
    ImageDecoderCallback decode,
  ) => OneFrameImageStreamCompleter(_load());

  Future<ImageInfo> _load() => _work.run(() async {
    try {
      final match = _path.firstMatch(asset);
      if (match == null || cacheWidth < 1 || cacheWidth > 1024) {
        throw const FormatException('Invalid native preview image key.');
      }
      final frame = Uint8List.sublistView(await rootBundle.load(asset));
      final mapDigest = NativeIndexedPreviewCodec.mapDigest(frame);
      final map = await _maps.load(
        'assets/native_faces/configured_previews/$mapDigest.nfpm',
      );
      final watch = kProfileMode ? (Stopwatch()..start()) : null;
      final pixels = await compute(_reconstruct, (
        frame: frame,
        map: map,
        digest: match.group(1)!,
      ), debugLabel: 'Native preview pixels');
      if (watch != null && _profileWorkerMicros.length < 10000) {
        _profileWorkerMicros.add(watch.elapsedMicroseconds);
      }
      final buffer = await ui.ImmutableBuffer.fromUint8List(pixels.rgba);
      ui.ImageDescriptor? descriptor;
      ui.Codec? codec;
      try {
        descriptor = ui.ImageDescriptor.raw(
          buffer,
          width: pixels.width,
          height: pixels.height,
          pixelFormat: ui.PixelFormat.rgba8888,
        );
        codec = await descriptor.instantiateCodec(
          targetWidth: cacheWidth.clamp(1, pixels.width),
        );
        final frame = await codec.getNextFrame();
        return ImageInfo(image: frame.image, scale: 1, debugLabel: asset);
      } finally {
        codec?.dispose();
        // Raw descriptors clear their pixel buffer on dispose: retain both
        // descriptor and buffer until the first platform frame is produced.
        descriptor?.dispose();
        buffer.dispose();
      }
    } catch (_) {
      if (kProfileMode) _profileErrors++;
      scheduleMicrotask(() => PaintingBinding.instance.imageCache.evict(this));
      rethrow;
    }
  });

  static ({int width, int height, Uint8List rgba}) _reconstruct(
    ({Uint8List frame, Uint8List map, String digest}) request,
  ) => NativeIndexedPreviewCodec.decode(
    request.frame,
    request.map,
    request.digest,
    premultiply: true,
  );

  @override
  bool operator ==(Object other) =>
      other is NativeIndexedPreviewImage &&
      other.asset == asset &&
      other.cacheWidth == cacheWidth;
  @override
  int get hashCode => Object.hash(asset, cacheWidth);
}

/// Only compressed maps: LRU bounded to eight entries and 512 KiB total.
final class _CompressedMapCache {
  final _entries = <String, Uint8List>{};
  final _pending = <String, Future<Uint8List>>{};
  int _bytes = 0;
  Future<Uint8List> load(String asset) async {
    final cached = _entries.remove(asset);
    if (cached != null) {
      _entries[asset] = cached;
      return cached;
    }
    return _pending.putIfAbsent(asset, () async {
      try {
        final data = Uint8List.sublistView(await rootBundle.load(asset));
        if (data.length > NativeIndexedPreviewCodec.maxEncodedBytes) {
          throw const FormatException('Native preview map size exceeded.');
        }
        if (data.length <= 512 * 1024) {
          while (_entries.isNotEmpty &&
              (_entries.length >= 8 || _bytes + data.length > 512 * 1024)) {
            _bytes -= _entries.remove(_entries.keys.first)!.length;
          }
          _entries[asset] = data;
          _bytes += data.length;
        }
        return data;
      } finally {
        _pending.remove(asset);
      }
    });
  }
}
