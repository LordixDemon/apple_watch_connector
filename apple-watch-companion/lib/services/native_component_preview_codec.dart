import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';

/// Native component geometry/occlusion, indexed once and evaluated with native
/// palette roles. Output is premultiplied RGBA, ready for ImageDescriptor.raw.
abstract final class NativeComponentPreviewCodec {
  static const maxEncodedBytes = 1024 * 1024;
  static const maxPixels = 512 * 1024;
  static ({int width, int height, Uint8List rgba}) decode(
    Uint8List data,
    String digest,
    List<double> colors,
  ) {
    if (data.length <= 24 ||
        data.length > maxEncodedBytes ||
        sha256.convert(data).toString() != digest) {
      throw const FormatException('Native component identity mismatch.');
    }
    final h = ByteData.sublistView(data);
    final roles = data[5];
    final width = h.getUint16(8, Endian.little);
    final height = h.getUint16(10, Endian.little);
    final classes = h.getUint32(12, Endian.little);
    final planeBytes = h.getUint32(16, Endian.little);
    final tableBytes = h.getUint32(20, Endian.little);
    if (ascii.decode(data.sublist(0, 4), allowInvalid: true) != 'NFCP' ||
        data[4] != 1 ||
        roles < 1 ||
        roles > 4 ||
        h.getUint16(6, Endian.little) != 0 ||
        width < 1 ||
        height < 1 ||
        width > 1024 ||
        height > 1024 ||
        width * height > maxPixels ||
        classes < 1 ||
        classes > 65536 ||
        planeBytes < 1 ||
        tableBytes < 1 ||
        24 + planeBytes + tableBytes != data.length ||
        colors.length != roles * 3 ||
        colors.any((v) => !v.isFinite || v < 0 || v > 1)) {
      throw const FormatException('Invalid native component header/palette.');
    }
    final plane = _inflate(
      Uint8List.sublistView(data, 24, 24 + planeBytes),
      width * height * 2,
    );
    final stride = 4 + roles * 3;
    final table = _inflate(
      Uint8List.sublistView(data, 24 + planeBytes),
      classes * stride,
    );
    final palette = Uint8List(classes * 4);
    for (var index = 0; index < classes; index++) {
      final offset = index * stride, alpha = table[offset + 3];
      palette[index * 4 + 3] = alpha;
      for (var channel = 0; channel < 3; channel++) {
        var value = table[offset + channel].toDouble();
        var maximum = table[offset + channel];
        for (var role = 0; role < roles; role++) {
          final weight = table[offset + 4 + role * 3 + channel];
          value += weight * colors[role * 3 + channel];
          maximum += weight;
        }
        if (maximum > alpha) {
          throw const FormatException('Native component alpha exceeded.');
        }
        palette[index * 4 + channel] = value.round().clamp(0, alpha);
      }
    }
    final indices = ByteData.sublistView(plane);
    final rgba = Uint32List(width * height);
    final packed = Uint32List.view(palette.buffer);
    for (var index = 0; index < rgba.length; index++) {
      final color = indices.getUint16(index * 2, Endian.little);
      if (color >= classes) {
        throw const FormatException('Native component index exceeded.');
      }
      rgba[index] = packed[color];
    }
    return (width: width, height: height, rgba: Uint8List.view(rgba.buffer));
  }

  static Uint8List _inflate(Uint8List data, int length) {
    final sink = _ComponentByteSink(length);
    try {
      ZLibDecoder().startChunkedConversion(sink)
        ..add(data)
        ..close();
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('Invalid native component compression.');
    }
    if (!sink.closed || sink.offset != length) {
      throw const FormatException('Native component expanded size mismatch.');
    }
    return sink.bytes;
  }
}

final class _ComponentByteSink extends ByteConversionSinkBase {
  _ComponentByteSink(int length) : bytes = Uint8List(length);
  final Uint8List bytes;
  int offset = 0;
  bool closed = false;
  @override
  void add(List<int> chunk) => addSlice(chunk, 0, chunk.length, false);
  @override
  void addSlice(List<int> chunk, int start, int end, bool isLast) {
    if (closed || end - start > bytes.length - offset) {
      throw const FormatException('Native component expanded size exceeded.');
    }
    bytes.setRange(offset, offset + end - start, chunk, start);
    offset += end - start;
    if (isLast) close();
  }

  @override
  void close() => closed = true;
}
