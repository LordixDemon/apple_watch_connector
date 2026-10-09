import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';

/// Lossless native pixels: a shared uint16 index plane and one RGBA table.
/// This is storage factoring, never interpolation between native styles.
abstract final class NativeIndexedPreviewCodec {
  static const maxEncodedBytes = 1024 * 1024;
  static const maxPixels = 512 * 1024;
  static const frameHeaderBytes = 80;

  static String mapDigest(Uint8List frame) {
    _header(frame, 'NFPT', frameHeaderBytes);
    return _hex(Uint8List.sublistView(frame, 16, 48));
  }

  static ({int width, int height, Uint8List rgba}) decode(
    Uint8List frame,
    Uint8List map,
    String frameDigest, {
    bool premultiply = false,
  }) {
    final f = _header(frame, 'NFPT', frameHeaderBytes);
    final m = _header(map, 'NFPM', 16);
    if (f != m ||
        sha256.convert(frame).toString() != frameDigest ||
        sha256.convert(map).toString() != mapDigest(frame)) {
      throw const FormatException('Native preview identity mismatch.');
    }
    final indices = _inflate(
      Uint8List.sublistView(map, 16),
      f.width * f.height * 2,
    );
    final colors = _inflate(
      Uint8List.sublistView(frame, frameHeaderBytes),
      f.classes * 4,
    );
    if (premultiply) {
      for (var i = 0; i < colors.length; i += 4) {
        final alpha = colors[i + 3];
        if (alpha == 255) continue;
        for (var channel = 0; channel < 3; channel++) {
          colors[i + channel] = (colors[i + channel] * alpha + 127) ~/ 255;
        }
      }
    }
    final indexView = ByteData.sublistView(indices);
    final palette = Uint32List.view(colors.buffer);
    final pixels = Uint32List(f.width * f.height);
    for (var i = 0; i < pixels.length; i++) {
      final index = indexView.getUint16(i * 2, Endian.little);
      if (index >= f.classes) {
        throw const FormatException('Native preview index outside palette.');
      }
      pixels[i] = palette[index];
    }
    final rgba = Uint8List.view(pixels.buffer);
    if (!premultiply &&
        sha256.convert(rgba).toString() !=
            _hex(Uint8List.sublistView(frame, 48, 80))) {
      throw const FormatException('Native preview pixel mismatch.');
    }
    return (width: f.width, height: f.height, rgba: rgba);
  }

  static ({int width, int height, int classes}) _header(
    Uint8List data,
    String magic,
    int size,
  ) {
    if (data.length <= size || data.length > maxEncodedBytes) {
      throw const FormatException('Native preview size exceeded.');
    }
    final view = ByteData.sublistView(data);
    final width = view.getUint16(8, Endian.little);
    final height = view.getUint16(10, Endian.little);
    final classes = view.getUint32(12, Endian.little);
    if (ascii.decode(data.sublist(0, 4), allowInvalid: true) != magic ||
        data[4] != 1 ||
        data[5] != 2 ||
        view.getUint16(6, Endian.little) != 0 ||
        width < 1 ||
        height < 1 ||
        width > 1024 ||
        height > 1024 ||
        width * height > maxPixels ||
        classes < 1 ||
        classes > 65536) {
      throw const FormatException('Invalid native preview header.');
    }
    return (width: width, height: height, classes: classes);
  }

  static Uint8List _inflate(Uint8List data, int length) {
    final sink = _ExactByteSink(length);
    try {
      ZLibDecoder().startChunkedConversion(sink)
        ..add(data)
        ..close();
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('Invalid native preview compression.');
    }
    if (!sink.closed || sink.offset != length) {
      throw const FormatException('Native preview expanded size mismatch.');
    }
    return sink.bytes;
  }

  static String _hex(Uint8List bytes) =>
      bytes.map((v) => v.toRadixString(16).padLeft(2, '0')).join();
}

/// Allocate only the declared, bounded size; stop inflation on excess output.
final class _ExactByteSink extends ByteConversionSinkBase {
  _ExactByteSink(int length) : bytes = Uint8List(length);
  final Uint8List bytes;
  int offset = 0;
  bool closed = false;
  @override
  void add(List<int> chunk) => addSlice(chunk, 0, chunk.length, false);
  @override
  void addSlice(List<int> chunk, int start, int end, bool isLast) {
    if (closed || end - start > bytes.length - offset) {
      throw const FormatException('Native preview expanded size exceeded.');
    }
    bytes.setRange(offset, offset + end - start, chunk, start);
    offset += end - start;
    if (isLast) close();
  }

  @override
  void close() => closed = true;
}
