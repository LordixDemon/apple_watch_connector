import 'dart:typed_data';
import '../models/native_face_collection.dart';

/// Reuse only byte-identical observations; timestamps alone never prove equality.
/// Wire copies protect the cache from a caller mutating a previous event buffer.
final class NativeFaceObservationCache {
  Map<String, Object?>? _wire;
  NativeFaceCollection? _decoded;
  static const _fields = [
    'connected',
    'faceCollectionKnown',
    'faceCollectionPair',
    'faceCollectionEpoch',
    'faceCollectionObservedAt',
    'faceCollectionComplete',
    'faceCollectionOrderKnown',
    'faceCollectionSelectionKnown',
    'activeFaceId',
    'faceCollectionOrder',
    'faceComplicationCatalog',
    'faceComplicationCatalogComplete',
  ];
  static const _rowFields = [
    'id',
    'bundle',
    'configurationBytes',
    'configuration',
    'archiveAvailable',
  ];

  NativeFaceCollection decode(Map data) {
    final projection = _projection(data);
    if (projection != null && _wire != null && _equal(projection, _wire)) {
      return _decoded!;
    }
    final collection = NativeFaceCollection.fromBridge(data);
    if (collection.known && projection != null) {
      _wire = _copy(projection) as Map<String, Object?>;
      _decoded = collection;
    } else {
      _wire = null;
      _decoded = null;
    }
    return collection;
  }

  Map<String, Object?>? _projection(Map data) {
    if (data['connected'] != true || data['faceCollectionKnown'] != true) {
      return null;
    }
    final rows = data['faceCollectionFaces'];
    final order = data['faceCollectionOrder'];
    final catalog = data['faceComplicationCatalog'];
    if (rows is! List ||
        rows.length > 256 ||
        order is! List ||
        order.length > 256 ||
        order.any((v) => v is! String) ||
        catalog != null &&
            (catalog is! Uint8List || catalog.length > 256 * 1024)) {
      return null;
    }
    final projectedRows = <Map<String, Object?>>[];
    for (final row in rows) {
      if (row is! Map) return null;
      final bytes = row['configuration'];
      if (bytes != null && (bytes is! Uint8List || bytes.length > 131072)) {
        return null;
      }
      final projected = {for (final key in _rowFields) key: row[key]};
      if (projected.values.any((v) => !_scalar(v) && v is! Uint8List)) {
        return null;
      }
      projectedRows.add(projected);
    }
    final result = {for (final key in _fields) key: data[key]};
    for (final key in _fields.where(
      (v) => v != 'faceCollectionOrder' && v != 'faceComplicationCatalog',
    )) {
      if (!_scalar(result[key])) return null;
    }
    result['faceCollectionFaces'] = projectedRows;
    return result;
  }

  static bool _scalar(Object? value) =>
      value == null || value is String || value is bool || value is int;

  static bool _equal(Object? a, Object? b) {
    if (a is Uint8List && b is Uint8List) {
      if (a.length != b.length) return false;
      for (var i = 0; i < a.length; i++) {
        if (a[i] != b[i]) return false;
      }
      return true;
    }
    if (a is List && b is List) {
      if (a.length != b.length) return false;
      for (var i = 0; i < a.length; i++) {
        if (!_equal(a[i], b[i])) return false;
      }
      return true;
    }
    if (a is Map && b is Map) {
      if (a.length != b.length) return false;
      for (final key in a.keys) {
        if (!b.containsKey(key) || !_equal(a[key], b[key])) return false;
      }
      return true;
    }
    return a == b;
  }

  static Object? _copy(Object? value) {
    if (value is Uint8List) return Uint8List.fromList(value);
    if (value is List) return List<Object?>.unmodifiable(value.map(_copy));
    if (value is Map) {
      return Map<String, Object?>.unmodifiable(
        value.map((k, v) => MapEntry(k as String, _copy(v))),
      );
    }
    return value;
  }
}
