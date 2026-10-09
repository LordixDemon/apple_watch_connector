import 'dart:convert';
import 'dart:typed_data';

/// A data reference, never an Objective-C object or executable archived class.
final class NativePlistUid {
  final int value;
  const NativePlistUid(this.value);
}

/// Offsets refer to the original plist buffer, not a re-encoded archive.
final class NativePlistDocument {
  final Object value;
  final Map<Uint8List, int> dataOffsets;
  NativePlistDocument._(this.value, Map<Uint8List, int> offsets)
    : dataOffsets = Map.unmodifiable(offsets);
}

/// Shared bounded binary reader. Ordinary Photos manifests continue to reject UIDs.
abstract final class NativeBinaryPlist {
  static Object decode(
    Uint8List bytes, {
    int maxBytes = 1024 * 1024,
    bool allowUids = false,
    bool allowWideIntegers = false,
  }) => _reader(bytes, maxBytes, allowUids, allowWideIntegers).decode();

  /// Explicit inspection for fixed-size data patches; ordinary reads do not
  /// allocate the offset index. UID/class metadata remains opaque data.
  static NativePlistDocument inspect(
    Uint8List bytes, {
    int maxBytes = 1024 * 1024,
    bool allowUids = false,
    bool allowWideIntegers = false,
  }) {
    final reader = _reader(bytes, maxBytes, allowUids, allowWideIntegers);
    reader.dataOffsets = Map.identity();
    final value = reader.decode();
    return NativePlistDocument._(value, reader.dataOffsets!);
  }

  static _BinaryPlist _reader(
    Uint8List bytes,
    int maxBytes,
    bool allowUids,
    bool allowWideIntegers,
  ) {
    if (maxBytes < 40 ||
        maxBytes > 4 * 1024 * 1024 ||
        bytes.length < 40 ||
        bytes.length > maxBytes ||
        ascii.decode(bytes.sublist(0, 8), allowInvalid: true) != 'bplist00') {
      throw const FormatException(
        'Invalid binary property list size or header.',
      );
    }
    return _BinaryPlist(bytes, allowUids, allowWideIntegers);
  }
}

final class _BinaryPlist {
  final Uint8List bytes;
  late final ByteData data = ByteData.sublistView(bytes);
  late int count, top, table, offsetSize, referenceSize;
  final _cache = <int, Object>{}, _visiting = <int>{};
  final bool allowUids;
  final bool allowWideIntegers;
  Map<Uint8List, int>? dataOffsets;
  _BinaryPlist(this.bytes, this.allowUids, this.allowWideIntegers);

  int integer(int offset, int size, {int? end}) {
    if (size < 1 ||
        size > 8 ||
        offset < 8 ||
        offset > (end ?? bytes.length) - size) {
      throw const FormatException('Invalid binary property list integer.');
    }
    var result = 0;
    for (var i = 0; i < size; i++) {
      if (result > 0x1fffffffffffff ~/ 256) {
        throw const FormatException('Property list integer overflow.');
      }
      result = result * 256 + bytes[offset + i];
    }
    return result;
  }

  Object decode() {
    if (bytes.length < 40) {
      throw const FormatException('Truncated binary property list.');
    }
    final trailer = bytes.length - 32;
    offsetSize = bytes[trailer + 6];
    referenceSize = bytes[trailer + 7];
    count = integer(trailer + 8, 8);
    top = integer(trailer + 16, 8);
    table = integer(trailer + 24, 8);
    if (count < 1 ||
        count > 16384 ||
        top >= count ||
        referenceSize < 1 ||
        referenceSize > 4 ||
        offsetSize < 1 ||
        offsetSize > 8 ||
        table < 8 ||
        table > trailer - count * offsetSize) {
      throw const FormatException('Invalid binary property list trailer.');
    }
    return read(top, 0);
  }

  Object read(int index, int depth) {
    if (index < 0 || index >= count || depth > 32 || !_visiting.add(index)) {
      throw const FormatException('Cyclic or invalid binary property list.');
    }
    try {
      final cached = _cache[index];
      if (cached != null) return cached;
      var offset = integer(table + index * offsetSize, offsetSize);
      if (offset < 8 || offset >= table) {
        throw const FormatException('Invalid property list object offset.');
      }
      final marker = bytes[offset++], type = marker >> 4, nibble = marker & 15;
      int length() {
        if (nibble != 15) return nibble;
        if (offset >= table ||
            bytes[offset] >> 4 != 1 ||
            (bytes[offset] & 15) > 3) {
          throw const FormatException('Invalid property list length.');
        }
        final size = 1 << (bytes[offset++] & 15);
        final value = integer(offset, size, end: table);
        offset += size;
        return value;
      }

      void bounded(int size) {
        if (size < 0 || offset > table - size) {
          throw const FormatException('Truncated property list object.');
        }
      }

      late Object result;
      switch (type) {
        case 0:
          if (nibble != 8 && nibble != 9) {
            throw const FormatException('Unsupported property list primitive.');
          }
          result = nibble == 9;
        case 1:
          if (nibble == 4 && allowWideIntegers) {
            bounded(16);
            var value = BigInt.zero;
            for (var i = 0; i < 16; i++) {
              value = (value << 8) | BigInt.from(bytes[offset + i]);
            }
            if (bytes[offset] & 128 != 0) value -= BigInt.one << 128;
            result = value;
            break;
          }
          if (nibble > 3) {
            throw const FormatException('Oversized property list integer.');
          }
          final size = 1 << nibble;
          bounded(size);
          // Apple encodes negative ordinary integers using eight signed bytes.
          result = size == 8
              ? data.getInt64(offset)
              : integer(offset, size, end: table);
        case 2:
          final size = 1 << nibble;
          bounded(size);
          if (size != 4 && size != 8) {
            throw const FormatException('Unsupported property list real.');
          }
          final value = size == 4
              ? data.getFloat32(offset)
              : data.getFloat64(offset);
          if (!value.isFinite) {
            throw const FormatException('Non-finite property list real.');
          }
          result = value;
        case 3:
          if (nibble != 3) {
            throw const FormatException('Invalid property list date.');
          }
          bounded(8);
          final seconds = data.getFloat64(offset);
          if (!seconds.isFinite || seconds.abs() > 1e11) {
            throw const FormatException('Invalid property list date.');
          }
          result = DateTime.utc(
            2001,
          ).add(Duration(microseconds: (seconds * 1000000).round()));
        case 4:
          final size = length();
          bounded(size);
          result = Uint8List.fromList(bytes.sublist(offset, offset + size));
          dataOffsets?[result as Uint8List] = offset;
        case 5:
          final size = length();
          bounded(size);
          result = ascii.decode(bytes.sublist(offset, offset + size));
        case 6:
          final size = length();
          bounded(size * 2);
          result = String.fromCharCodes(
            List.generate(size, (i) => data.getUint16(offset + i * 2)),
          );
        case 8:
          if (!allowUids || nibble > 3) {
            throw const FormatException('Unsupported property list UID.');
          }
          result = NativePlistUid(integer(offset, nibble + 1, end: table));
        case 10:
        case 13:
          final size = length();
          if (size > 16384) {
            throw const FormatException(
              'Property list collection bound exceeded.',
            );
          }
          bounded(size * referenceSize * (type == 13 ? 2 : 1));
          Object ref(int i) => read(
            integer(offset + i * referenceSize, referenceSize, end: table),
            depth + 1,
          );
          if (type == 10) {
            result = List.generate(size, ref);
          } else {
            final dict = <String, Object>{};
            for (var i = 0; i < size; i++) {
              final key = ref(i);
              if (key is! String || dict.containsKey(key)) {
                throw const FormatException(
                  'Invalid property list dictionary key.',
                );
              }
              dict[key] = ref(i + size);
            }
            result = dict;
          }
        default:
          throw const FormatException(
            'Unsupported Photos binary property list value.',
          );
      }
      _cache[index] = result;
      return result;
    } finally {
      _visiting.remove(index);
    }
  }
}
