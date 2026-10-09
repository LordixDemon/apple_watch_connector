import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:flutter/services.dart';
import 'native_component_preview_codec.dart';
import 'native_component_previews.dart';
import 'native_preview_decode_queue.dart';

@immutable
final class NativeComponentPreviewImage
    extends ImageProvider<NativeComponentPreviewImage> {
  const NativeComponentPreviewImage(this.request, {required this.cacheWidth});
  final NativeComponentPreviewRequest request;
  final int cacheWidth;
  static final _programs = <String, Uint8List>{};
  static final _loading = <String, Future<Uint8List>>{};
  static int _bytes = 0;
  static final _workerMicros = <int>[];
  static int _errors = 0;
  static Map<String, num> get profileMetrics {
    final values = _workerMicros.toList()..sort();
    return {
      'decodes': values.length,
      'errors': _errors,
      'workerP95Ms': values.isEmpty
          ? 0
          : values[((values.length - 1) * .95).round()] / 1000,
      'workerMaxMs': values.isEmpty ? 0 : values.last / 1000,
      'compressedProgramBytes': _bytes,
      'compressedProgramEntries': _programs.length,
      'queuePeak': NativePreviewDecodeQueue.shared.peak,
      'queuePending': NativePreviewDecodeQueue.shared.pending,
      'queueActive': NativePreviewDecodeQueue.shared.active,
    };
  }

  static final _path = RegExp(
    r'^assets/native_faces/component_previews/([a-f0-9]{64})\.nfc$',
  );
  @override
  Future<NativeComponentPreviewImage> obtainKey(
    ImageConfiguration configuration,
  ) => SynchronousFuture(this);
  @override
  ImageStreamCompleter loadImage(
    NativeComponentPreviewImage key,
    ImageDecoderCallback decode,
  ) => OneFrameImageStreamCompleter(_load());

  Future<ImageInfo> _load() => NativePreviewDecodeQueue.shared.run(() async {
    try {
      final match = _path.firstMatch(request.asset);
      if (match == null || cacheWidth < 1 || cacheWidth > 1024) {
        throw const FormatException('Invalid native component image key.');
      }
      final program = await _program(request.asset);
      final watch = kProfileMode ? (Stopwatch()..start()) : null;
      final pixels = await compute(_reconstruct, (
        data: program,
        digest: match.group(1)!,
        colors: request.colors,
      ), debugLabel: 'Native component pixels');
      if (watch != null && _workerMicros.length < 10000) {
        _workerMicros.add(watch.elapsedMicroseconds);
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
        return ImageInfo(
          image: frame.image,
          scale: 1,
          debugLabel: request.asset,
        );
      } finally {
        codec?.dispose();
        descriptor?.dispose();
        buffer.dispose();
      }
    } catch (_) {
      if (kProfileMode) _errors++;
      scheduleMicrotask(() => PaintingBinding.instance.imageCache.evict(this));
      rethrow;
    }
  });

  static ({int width, int height, Uint8List rgba}) _reconstruct(
    ({Uint8List data, String digest, List<double> colors}) request,
  ) => NativeComponentPreviewCodec.decode(
    request.data,
    request.digest,
    request.colors,
  );

  static Future<Uint8List> _program(String asset) async {
    final cached = _programs.remove(asset);
    if (cached != null) {
      _programs[asset] = cached;
      return cached;
    }
    return _loading.putIfAbsent(asset, () async {
      try {
        final data = Uint8List.sublistView(await rootBundle.load(asset));
        if (data.length > NativeComponentPreviewCodec.maxEncodedBytes) {
          throw const FormatException('Native component program exceeded.');
        }
        if (data.length <= 256 * 1024) {
          while (_programs.isNotEmpty &&
              (_programs.length >= 4 || _bytes + data.length > 256 * 1024)) {
            _bytes -= _programs.remove(_programs.keys.first)!.length;
          }
          _programs[asset] = data;
          _bytes += data.length;
        }
        return data;
      } finally {
        _loading.remove(asset);
      }
    });
  }

  @override
  bool operator ==(Object other) =>
      other is NativeComponentPreviewImage &&
      request.asset == other.request.asset &&
      listEquals(request.colors, other.request.colors) &&
      cacheWidth == other.cacheWidth;
  @override
  int get hashCode =>
      Object.hash(request.asset, Object.hashAll(request.colors), cacheWidth);
}
