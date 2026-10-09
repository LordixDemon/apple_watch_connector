import 'dart:convert';
import 'dart:typed_data';
import 'native_binary_plist.dart';

/// Data-only identity for presentation. Never instantiates an archived class,
/// executes an intent, rewrites a descriptor, or authorizes a Watch mutation.
final class NativeIntentIdentity {
  final _IntentArchive _archive;
  NativeIntentIdentity._(this._archive);

  static NativeIntentIdentity? parse(Object? encoded) {
    if (encoded is! String || encoded.length > 131072) return null;
    try {
      final archive = _IntentArchive.read(base64Decode(encoded));
      if (!const ['INIntent', 'INAppIntent'].contains(archive.rootClass)) {
        return null;
      }
      return NativeIntentIdentity._(archive);
    } on FormatException {
      return null;
    }
  }

  bool same(NativeIntentIdentity other) {
    try {
      return _IntentComparison().archive(_archive, other._archive, 0);
    } on FormatException {
      return false;
    }
  }

  /// Received AppIntent string parameters, not an inferred editable schema.
  /// The variant editor may select only complete settings supplied by the Watch.
  Map<String, String>? get appStringParameters {
    if (_archive.rootClass != 'INAppIntent') return null;
    try {
      final root = _archive.resolve(_archive.top['root']) as Map;
      for (final key in ['launchId', 'appIntentIdentifier']) {
        final id = _archive.resolve(root[key]);
        if (id is! String ||
            id == '\$null' ||
            id.isEmpty ||
            id.length > 512 ||
            id.contains('\u0000')) {
          return null;
        }
      }
      final source = _archive.resolve(root['serializedParameters']);
      if (source is! Map || source.length != 3) return null;
      final metadata = _archive.resolve(source['\$class']);
      if (metadata is! Map ||
          !const [
            'NSDictionary',
            'NSMutableDictionary',
          ].contains(metadata['\$classname']) ||
          source['NS.keys'] is! List ||
          source['NS.objects'] is! List) {
        return null;
      }
      final keys = source['NS.keys'] as List,
          values = source['NS.objects'] as List;
      if (keys.isEmpty || keys.length > 32 || keys.length != values.length) {
        return null;
      }
      final result = <String, String>{};
      for (var i = 0; i < keys.length; i++) {
        final key = _archive.resolve(keys[i]),
            value = _archive.resolve(values[i]);
        if (keys[i] is NativePlistUid &&
                (keys[i] as NativePlistUid).value == 0 ||
            values[i] is NativePlistUid &&
                (values[i] as NativePlistUid).value == 0 ||
            key is! String ||
            key.isEmpty ||
            key.length > 128 ||
            key.contains('\u0000') ||
            value is! String ||
            value.isEmpty ||
            value.length > 512 ||
            value.contains('\u0000') ||
            result.containsKey(key)) {
          return null;
        }
        result[key] = value;
      }
      return Map.unmodifiable(result);
    } on FormatException {
      return null;
    }
  }

  /// Exact routing/unknown-field comparison excluding only the recognized
  /// serialized parameter dictionary. Never authorizes an arbitrary rewrite.
  bool sameAppIntentRouting(NativeIntentIdentity other) {
    if (appStringParameters == null || other.appStringParameters == null) {
      return false;
    }
    try {
      return _IntentComparison().archive(
        _archive.withoutRootField('serializedParameters'),
        other._archive.withoutRootField('serializedParameters'),
        0,
      );
    } on FormatException {
      return false;
    }
  }
}

final class _IntentArchive {
  final List objects;
  final Map top;
  final String? rootClass;
  _IntentArchive(this.objects, this.top, this.rootClass);

  _IntentArchive withoutRootField(String name) {
    final rootId = (top['root'] as NativePlistUid).value;
    final copy = List<Object>.from(objects);
    copy[rootId] = Map.from(resolve(top['root']) as Map)..remove(name);
    return _IntentArchive(copy, top, rootClass);
  }

  static _IntentArchive read(Uint8List bytes) {
    final data = NativeBinaryPlist.decode(
      bytes,
      maxBytes: 98304,
      allowUids: true,
      allowWideIntegers: true,
    );
    if (data is! Map ||
        data.length != 4 ||
        data['\$archiver'] != 'NSKeyedArchiver' ||
        data['\$version'] != 100000 ||
        data['\$objects'] is! List ||
        data['\$top'] is! Map) {
      throw const FormatException('Unsupported intent archive');
    }
    final objects = List<Object>.from(data['\$objects'] as List);
    final top = data['\$top'] as Map;
    if (objects.isEmpty ||
        objects.length > 2048 ||
        objects.first != '\$null' ||
        top.length != 1 ||
        top['root'] is! NativePlistUid) {
      throw const FormatException('Invalid intent root');
    }
    final rootId = (top['root'] as NativePlistUid).value;
    final archive = _IntentArchive(objects, top, null);
    final root = archive.resolve(top['root']);
    if (root is! Map) throw const FormatException('Invalid intent object');
    final descriptor = archive.resolve(root['\$class']);
    if (descriptor is! Map ||
        descriptor['\$classname'] is! String ||
        descriptor['\$classes'] is! List) {
      throw const FormatException('Invalid intent class metadata');
    }
    final name = descriptor['\$classname'] as String;
    if (!(descriptor['\$classes'] as List).contains(name)) {
      throw const FormatException('Invalid intent class hierarchy');
    }
    if (name == 'INIntent') {
      final identifier = archive.resolve(root['identifier']);
      // NSKeyedArchiver assigns a fresh donation UUID to a re-created INIntent.
      // Only this exact root field is excluded; all settings, schema bytes,
      // routing metadata, unknown fields and nested identifiers remain compared.
      if (identifier is String && _uuid.hasMatch(identifier)) {
        objects[rootId] = Map.from(root)..remove('identifier');
      }
    } else if (name == 'INIntentCodableDescription' &&
        (archive.resolve(root['versioningHash']) is int ||
            archive.resolve(root['versioningHash']) is BigInt)) {
      // Watch and catalog use different schema version stamps. For presentation,
      // compare schema fields and all parameter bytes rather than the stamp.
      objects[rootId] = Map.from(root)..remove('versioningHash');
    }
    return _IntentArchive(objects, top, name);
  }

  static final _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );
  Object? resolve(Object? value) {
    if (value is! NativePlistUid) return value;
    if (value.value < 0 || value.value >= objects.length) {
      throw const FormatException('Invalid intent UID');
    }
    return objects[value.value];
  }
}

final class _IntentComparison {
  int visits = 0, nestedBytes = 0;
  bool archive(_IntentArchive a, _IntentArchive b, int depth) =>
      value(a.top, b.top, a, b, <(int, int)>{}, depth + 1);

  bool value(
    Object? x,
    Object? y,
    _IntentArchive a,
    _IntentArchive b,
    Set<(int, int)> seen,
    int depth,
  ) {
    if (++visits > 32768 || depth > 64) {
      throw const FormatException('Intent comparison bound exceeded');
    }
    if (x is NativePlistUid && y is NativePlistUid) {
      final left = a.resolve(x), right = b.resolve(y);
      if (!seen.add((x.value, y.value))) return true;
      return value(left, right, a, b, seen, depth + 1);
    }
    if (x is Map && y is Map) {
      final left = normalize(x, a), right = normalize(y, b);
      if (left.length != right.length ||
          left.keys.any((k) => !right.containsKey(k))) {
        return false;
      }
      for (final key in left.keys) {
        if (!value(left[key], right[key], a, b, seen, depth + 1)) return false;
      }
      return true;
    }
    if (x is Uint8List && y is Uint8List) {
      if (x.length == y.length) {
        var equal = true;
        for (var i = 0; i < x.length; i++) {
          if (x[i] != y[i]) {
            equal = false;
            break;
          }
        }
        if (equal) return true;
      }
      if (_binaryArchive(x) && _binaryArchive(y)) {
        nestedBytes += x.length + y.length;
        if (nestedBytes > 1024 * 1024) {
          throw const FormatException('Nested intent size exceeded');
        }
        return archive(
          _IntentArchive.read(x),
          _IntentArchive.read(y),
          depth + 1,
        );
      }
      return false;
    }
    if (x is List && y is List) {
      if (x.length != y.length) return false;
      for (var i = 0; i < x.length; i++) {
        if (!value(x[i], y[i], a, b, seen, depth + 1)) return false;
      }
      return true;
    }
    return x.runtimeType == y.runtimeType && x == y;
  }

  static const _mutable = {
    'NSMutableDictionary': 'NSDictionary',
    'NSMutableArray': 'NSArray',
    'NSMutableSet': 'NSSet',
  };
  Map normalize(Map source, _IntentArchive archive) {
    final ownerClass = archive.resolve(source['\$class']);
    if (archive.rootClass == 'INIntentCodableDescription' &&
        ownerClass is Map &&
        ownerClass['\$classname'] == 'INCodableObjectAttribute' &&
        source['_codableDescription'] is NativePlistUid) {
      final parent = source['_codableDescription'] as NativePlistUid;
      final root = archive.top['root'] as NativePlistUid;
      // Codable attribute schema already contains its owning description. Watch
      // serialization may omit this inverse pointer; normalize only a reference
      // to this exact root or the archive's null object, never another parent.
      if (parent.value == root.value || parent.value == 0) {
        source = Map.from(source)..remove('_codableDescription');
      }
    }
    final name = source['\$classname'];
    if (source.length == 2 &&
        _mutable.containsKey(name) &&
        source['\$classes'] is List) {
      return {
        '\$classname': _mutable[name],
        '\$classes': [
          for (final v
              in (source['\$classes'] as List)
                  .map((v) => _mutable[v] ?? v)
                  .toSet())
            v,
        ],
      };
    }
    if (source.length == 3 &&
        source.containsKey('\$class') &&
        source['NS.keys'] is List &&
        source['NS.objects'] is List) {
      final metadata = archive.resolve(source['\$class']);
      if (metadata is! Map ||
          ![
            'NSDictionary',
            'NSMutableDictionary',
          ].contains(metadata['\$classname'])) {
        return source;
      }
      final keys = source['NS.keys'] as List,
          values = source['NS.objects'] as List;
      if (keys.length != values.length) {
        throw const FormatException('Invalid intent dictionary');
      }
      final entries = <Object, Object?>{};
      for (var i = 0; i < keys.length; i++) {
        final key = archive.resolve(keys[i]);
        if (key == null ||
            (key is! String && key is! num) ||
            entries.containsKey(key)) {
          throw const FormatException('Invalid intent dictionary key');
        }
        entries[key] = values[i];
      }
      return {'\$class': source['\$class'], 'entries': entries};
    }
    return source;
  }

  static bool _binaryArchive(Uint8List b) =>
      b.length >= 8 &&
      ascii.decode(b.sublist(0, 8), allowInvalid: true) == 'bplist00';
}
