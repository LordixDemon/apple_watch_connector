import 'dart:io';
import 'dart:typed_data';
import 'package:path_provider/path_provider.dart';
import 'optical_frame.dart';

/// Bounded, explicit diagnostic capture. Frames can contain pairing material;
/// keep them in private temporary storage, never include them in IPC/logs.
class OpticalCapture {
  static const maxFrames = 90;
  static const maxBytes = 64 * 1024 * 1024;
  final RandomAccessFile _output;
  final File _file;
  final Stopwatch _clock = Stopwatch()..start();
  int frames = 0, _bytes = 8;
  bool _closed = false;
  bool _capacityReached = false;

  OpticalCapture._(this._file, this._output);

  static Future<OpticalCapture> start() async {
    final cache = await getTemporaryDirectory();
    final directory = Directory('${cache.path}/optical-pairing');
    await directory.create(recursive: true);
    // One capture at a time; opening the lab discards previous camera material.
    await clear();
    final file = File('${directory.path}/frames.awuv');
    final output = await file.open(mode: FileMode.write);
    try {
      await output.writeFrom([65, 87, 85, 86, 48, 48, 48, 49]); // AWUV0001
      return OpticalCapture._(file, output);
    } catch (_) {
      await output.close();
      if (await file.exists()) await file.delete();
      rethrow;
    }
  }

  static Future<void> clear() async {
    final cache = await getTemporaryDirectory();
    final file = File('${cache.path}/optical-pairing/frames.awuv');
    if (await file.exists()) await file.delete();
  }

  bool get full =>
      _capacityReached || frames >= maxFrames || _bytes >= maxBytes;

  Future<void> add(OpticalFrame frame, int sensorOrientation) async {
    if (_closed || full) return;
    final size = 24 + frame.uv.length;
    if (_bytes + size > maxBytes) {
      _capacityReached = true;
      return;
    }
    final header = ByteData(24)
      ..setUint32(0, frame.uv.length, Endian.little)
      ..setUint32(4, frame.width, Endian.little)
      ..setUint32(8, frame.height, Endian.little)
      ..setUint32(12, sensorOrientation, Endian.little)
      ..setUint64(16, _clock.elapsedMicroseconds, Endian.little);
    await _output.writeFrom(header.buffer.asUint8List());
    await _output.writeFrom(frame.uv);
    frames++;
    _bytes += size;
  }

  Future<void> close({bool discard = false}) async {
    if (!_closed) {
      _closed = true;
      _clock.stop();
      await _output.close();
    }
    if (discard && await _file.exists()) await _file.delete();
  }
}
