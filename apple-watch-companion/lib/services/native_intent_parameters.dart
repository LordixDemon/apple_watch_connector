import 'dart:convert';
import 'dart:typed_data';
import '../l10n/strings.dart';
import 'native_binary_plist.dart';

final class NativeIntentDuration {
  final String name, title;
  final double seconds, minimum;
  final double? maximum;
  final int _offset;
  const NativeIntentDuration._(
    this.name,
    this.title,
    this.seconds,
    this.minimum,
    this.maximum,
    this._offset,
  );

  bool accepts(double value) =>
      value.isFinite &&
      value >= minimum &&
      (maximum == null || value <= maximum!);

  String get summary => Strings.current.nativeIntentDurationValue(
    title,
    seconds == seconds.truncateToDouble()
        ? seconds.toStringAsFixed(0)
        : '$seconds',
  );
}

/// Per-editor/presentation cache. Never retains a prior pair in a static cache.
final class NativeIntentParameterIndex {
  final _entries = <String, NativeIntentParameters?>{};
  NativeIntentParameters? read(Object? value) {
    if (value is! String || value.length > 131072) return null;
    if (_entries.length >= 32 && !_entries.containsKey(value)) {
      _entries.remove(_entries.keys.first);
    }
    return _entries.putIfAbsent(
      value,
      () => NativeIntentParameters.parse(value),
    );
  }
}

/// Native scalar TimeInterval parameters. No app/kind names or wire field
/// numbers are hardcoded: field numbers and titles come from the received schema.
/// Mutation patches only the recognized eight-byte double in the original blob.
final class NativeIntentParameters {
  final Uint8List _bytes;
  final String _encoded;
  final List<NativeIntentDuration> durations;
  NativeIntentParameters._(
    this._bytes,
    this._encoded,
    List<NativeIntentDuration> durations,
  ) : durations = List.unmodifiable(durations);

  static NativeIntentParameters? parse(Object? encoded) {
    if (encoded is! String || encoded.length > 131072) return null;
    try {
      final bytes = base64Decode(encoded);
      final document = NativeBinaryPlist.inspect(
        bytes,
        maxBytes: 98304,
        allowUids: true,
        allowWideIntegers: true,
      );
      final archive = _Archive(document.value);
      if (archive.className(archive.root) != 'INIntent') return null;
      final backing = archive.object(archive.root['backingStore']);
      if (archive.className(backing) != 'INCodable') return null;
      final payload = backing['bytes'],
          schemaBytes = backing['codableDescriptionBytes'];
      if (payload is! Uint8List || schemaBytes is! Uint8List) return null;
      final payloadOffset = document.dataOffsets[payload];
      if (payloadOffset == null) return null;
      var visits = 0;
      int uses(Object? value) {
        if (++visits > 16384) {
          throw const FormatException('Intent reference count exceeded');
        }
        if (identical(value, payload)) return 1;
        if (value is Map) return value.values.fold(0, (n, v) => n + uses(v));
        if (value is List) return value.fold(0, (n, v) => n + uses(v));
        return 0;
      }

      if (uses(document.value) != 1) return null;
      // Reject overlapping/aliased data objects. A patch must not affect an
      // unrelated opaque field which happens to reuse the same plist storage.
      if (document.dataOffsets.entries.any(
        (entry) =>
            !identical(entry.key, payload) &&
            entry.value < payloadOffset + payload.length &&
            payloadOffset < entry.value + entry.key.length,
      )) {
        return null;
      }
      final schema = _Archive(
        NativeBinaryPlist.decode(
          schemaBytes,
          maxBytes: 65536,
          allowUids: true,
          allowWideIntegers: true,
        ),
      );
      if (schema.className(schema.root) != 'INIntentCodableDescription') {
        return null;
      }
      final fields = _Wire.fields(payload, 0, payload.length);
      final attributes = schema.dictionary(schema.root['attributes']);
      final durations = <NativeIntentDuration>[];
      final names = <String>{};
      for (final entry in attributes.entries) {
        if (entry.key is! int ||
            (entry.key as int) < 1 ||
            (entry.key as int) >= 65535) {
          continue;
        }
        final attribute = schema.object(entry.value);
        if (schema.className(attribute) != 'INCodableObjectAttribute' ||
            schema.resolve(attribute['_typeString']) != 'TimeInterval' ||
            schema.resolve(attribute['typeName']) != 'Double' ||
            attribute['modifier'] != 1 ||
            attribute['fixedSizeArray'] != false ||
            attribute['supportsDynamicEnumeration'] != false) {
          continue;
        }
        final name = schema.resolve(attribute['propertyName']);
        if (name is! String ||
            name.isEmpty ||
            name.length > 128 ||
            name.contains('\u0000') ||
            !names.add(name)) {
          continue;
        }
        final metadata = schema.object(attribute['metadata']);
        if (schema.className(metadata) !=
            'INCodableTimeIntervalAttributeMetadata') {
          continue;
        }
        final minimum = schema.resolve(metadata['minimumValue']);
        final maximum = schema.resolve(metadata['maximumValue']);
        if (minimum is! num ||
            !minimum.isFinite ||
            maximum is! num ||
            !maximum.isFinite ||
            minimum < 0 ||
            maximum < 0 ||
            maximum > 0 && maximum < minimum) {
          continue;
        }
        // Zero maximum is the native metadata's unset bound.
        final outer = fields.where((f) => f.number == entry.key).toList();
        if (outer.length != 1 || outer.single.wire != 2) continue;
        final wrapped = _Wire.fields(
          payload,
          outer.single.start,
          outer.single.end,
        );
        if (wrapped.length != 1 ||
            wrapped.single.number != 1 ||
            wrapped.single.wire != 2) {
          continue;
        }
        final scalar = _Wire.fields(
          payload,
          wrapped.single.start,
          wrapped.single.end,
        );
        if (scalar.length != 1 ||
            scalar.single.number != 2 ||
            scalar.single.wire != 1) {
          continue;
        }
        final value = ByteData.sublistView(
          payload,
        ).getFloat64(scalar.single.start, Endian.little);
        final rawTitle = schema.resolve(attribute['displayName']);
        final duration = NativeIntentDuration._(
          name,
          rawTitle is String &&
                  rawTitle.isNotEmpty &&
                  rawTitle.length <= 256 &&
                  !rawTitle.contains('\u0000')
              ? rawTitle
              : name,
          value,
          minimum.toDouble(),
          maximum > 0 ? maximum.toDouble() : null,
          payloadOffset + scalar.single.start,
        );
        if (duration.accepts(value)) durations.add(duration);
      }
      return durations.isEmpty
          ? null
          : NativeIntentParameters._(bytes, encoded, durations);
    } on FormatException {
      return null;
    }
  }

  String update(Map<String, double> values) {
    if (values.keys.any((name) => !durations.any((p) => p.name == name))) {
      throw const FormatException('Unknown native parameter');
    }
    final changes = <NativeIntentDuration, double>{};
    for (final parameter in durations) {
      final value = values[parameter.name];
      if (value == null) continue;
      if (!parameter.accepts(value)) {
        throw const FormatException('Invalid native duration');
      }
      if (value == parameter.seconds) continue;
      changes[parameter] = value;
    }
    if (changes.isEmpty) return _encoded;
    final result = Uint8List.fromList(_bytes);
    final data = ByteData.sublistView(result);
    for (final entry in changes.entries) {
      data.setFloat64(entry.key._offset, entry.value, Endian.little);
    }
    return base64Encode(result);
  }
}

final class _Archive {
  late final List objects;
  late final Map root;
  _Archive(Object value) {
    if (value is! Map ||
        value['\$archiver'] != 'NSKeyedArchiver' ||
        value['\$version'] != 100000 ||
        value['\$objects'] is! List ||
        value['\$top'] is! Map) {
      throw const FormatException('Invalid intent archive');
    }
    objects = value['\$objects'] as List;
    if (objects.isEmpty || objects.length > 2048 || objects.first != '\$null') {
      throw const FormatException('Invalid intent objects');
    }
    final top = value['\$top'] as Map;
    if (top.length != 1 || top['root'] is! NativePlistUid) {
      throw const FormatException('Invalid intent root');
    }
    root = object(top['root']);
  }
  Object? resolve(Object? value) {
    if (value is! NativePlistUid) return value;
    if (value.value < 0 || value.value >= objects.length) {
      throw const FormatException('Invalid intent UID');
    }
    return objects[value.value];
  }

  Map object(Object? value) {
    final result = resolve(value);
    if (result is! Map) throw const FormatException('Invalid intent object');
    return result;
  }

  String? className(Map value) {
    final metadata = object(value['\$class']);
    final name = metadata['\$classname'];
    return name is String &&
            metadata['\$classes'] is List &&
            (metadata['\$classes'] as List).contains(name)
        ? name
        : null;
  }

  Map<Object, Object?> dictionary(Object? value) {
    final source = object(value);
    if (!['NSDictionary', 'NSMutableDictionary'].contains(className(source)) ||
        source['NS.keys'] is! List ||
        source['NS.objects'] is! List) {
      throw const FormatException('Invalid intent attributes');
    }
    final keys = source['NS.keys'] as List,
        values = source['NS.objects'] as List;
    if (keys.length != values.length || keys.length > 128) {
      throw const FormatException('Invalid intent attributes');
    }
    final result = <Object, Object?>{};
    for (var i = 0; i < keys.length; i++) {
      final key = resolve(keys[i]);
      if (key is! int || result.containsKey(key)) {
        throw const FormatException('Invalid intent attribute index');
      }
      result[key] = values[i];
    }
    return result;
  }
}

typedef _Field = ({int number, int wire, int start, int end});

abstract final class _Wire {
  static List<_Field> fields(Uint8List bytes, int start, int end) {
    var offset = start;
    int varint({bool opaque = false}) {
      var result = 0;
      for (var index = 0; index < 10; index++) {
        if (offset >= end) {
          throw const FormatException('Truncated intent field');
        }
        final byte = bytes[offset++];
        if (index == 9 && byte > 1 || !opaque && index >= 5) {
          throw const FormatException('Invalid intent varint');
        }
        if (!opaque) result |= (byte & 127) << (index * 7);
        if (byte < 128) return result;
      }
      throw const FormatException('Invalid intent varint');
    }

    final result = <_Field>[];
    while (offset < end) {
      if (result.length >= 128) {
        throw const FormatException('Intent field count exceeded');
      }
      final tag = varint(), number = tag >> 3, wire = tag & 7;
      if (number < 1 || number > 0x1fffffff) {
        throw const FormatException('Invalid intent field');
      }
      var fieldStart = offset;
      switch (wire) {
        case 0:
          varint(opaque: true);
        case 1:
          offset += 8;
        case 2:
          final length = varint();
          fieldStart = offset;
          if (length < 0 || length > end - offset) {
            throw const FormatException('Invalid intent field length');
          }
          offset += length;
        case 5:
          offset += 4;
        default:
          throw const FormatException('Unsupported intent wire type');
      }
      if (offset > end) throw const FormatException('Truncated intent field');
      result.add((number: number, wire: wire, start: fieldStart, end: offset));
    }
    return result;
  }
}
