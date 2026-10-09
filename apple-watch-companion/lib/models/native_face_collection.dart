import 'dart:convert';
import 'dart:typed_data';

/// Committed observations from the paired Watch, distinct from local designs.
class NativeWatchFace {
  final String id;
  final String bundle;
  final int configurationBytes;
  final String? configurationJson;
  final bool archiveAvailable;
  const NativeWatchFace(
    this.id,
    this.bundle,
    this.configurationBytes, {
    this.configurationJson,
    this.archiveAvailable = false,
  });

  /// A fresh copy preserves opaque native fields without exposing mutable observation state.
  Map<String, dynamic>? get configuration => configurationJson == null
      ? null
      : jsonDecode(configurationJson!) as Map<String, dynamic>;
  bool get canCopyConfiguration => archiveAvailable;
}

class NativeFaceCollection {
  final bool connected;
  final String? pair;
  final String? epoch;
  final int? observedAt;
  final bool complete;
  final bool orderKnown;
  final bool selectionKnown;
  final String? selected;
  final List<String> ordered;
  final List<NativeWatchFace> faces;
  final String? complicationCatalogJson;
  final bool complicationCatalogComplete;
  const NativeFaceCollection({
    this.connected = false,
    this.pair,
    this.epoch,
    this.observedAt,
    this.complete = false,
    this.orderKnown = false,
    this.selectionKnown = false,
    this.selected,
    this.ordered = const [],
    this.faces = const [],
    this.complicationCatalogJson,
    this.complicationCatalogComplete = false,
  });

  bool get known => observedAt != null;
  Map<String, dynamic> get complicationCatalog =>
      complicationCatalogJson == null
      ? {}
      : jsonDecode(complicationCatalogJson!) as Map<String, dynamic>;
  static final _uuid = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  );
  static bool _isUuid(dynamic value) =>
      value is String && _uuid.hasMatch(value);

  factory NativeFaceCollection.fromBridge(Map<dynamic, dynamic> data) {
    final connected = data['connected'] == true;
    NativeFaceCollection unknown() => NativeFaceCollection(
      connected: connected,
      pair: connected && _isUuid(data['faceCollectionPair'])
          ? data['faceCollectionPair'] as String
          : null,
      epoch: connected && _isUuid(data['faceCollectionEpoch'])
          ? data['faceCollectionEpoch'] as String
          : null,
    );
    if (!connected || data['faceCollectionKnown'] != true) return unknown();
    final stamp = data['faceCollectionObservedAt'];
    final rows = data['faceCollectionFaces'];
    final order = data['faceCollectionOrder'];
    if (!_isUuid(data['faceCollectionPair']) ||
        !_isUuid(data['faceCollectionEpoch']) ||
        stamp is! int ||
        stamp <= 0 ||
        stamp > 8640000000000000 ||
        rows is! List ||
        rows.length > 256 ||
        order is! List ||
        order.length > 256 ||
        data['faceCollectionComplete'] is! bool ||
        data['faceCollectionOrderKnown'] is! bool ||
        data['faceCollectionSelectionKnown'] is! bool) {
      return unknown();
    }
    final faces = <NativeWatchFace>[];
    final ids = <String>{};
    for (final row in rows) {
      if (row is! Map ||
          !_isUuid(row['id']) ||
          !ids.add(row['id'] as String) ||
          row['bundle'] is! String ||
          (row['bundle'] as String).length > 255 ||
          (row['bundle'] as String).contains('\u0000') ||
          row['configurationBytes'] is! int ||
          (row['configurationBytes'] as int) < 1 ||
          (row['configurationBytes'] as int) > 131072) {
        return unknown();
      }
      String? configuration;
      final raw = row['configuration'];
      if (raw != null) {
        if (raw is! Uint8List || raw.length != row['configurationBytes']) {
          return unknown();
        }
        try {
          configuration = utf8.decode(raw);
          final decoded = jsonDecode(configuration);
          if (decoded is! Map ||
              (decoded['bundle id'] ?? '') != row['bundle']) {
            return unknown();
          }
        } on FormatException {
          return unknown();
        }
      }
      faces.add(
        NativeWatchFace(
          row['id'] as String,
          row['bundle'] as String,
          row['configurationBytes'] as int,
          configurationJson: configuration,
          archiveAvailable: row['archiveAvailable'] == true,
        ),
      );
    }
    if (order.any((id) => !_isUuid(id)) ||
        order.toSet().length != order.length) {
      return unknown();
    }
    final ordered = List<String>.from(order);
    final selectionKnown = data['faceCollectionSelectionKnown'] == true;
    final selected = selectionKnown ? data['activeFaceId'] : null;
    if (selectionKnown && selected != '' && !_isUuid(selected)) {
      return unknown();
    }
    final orderKnown = data['faceCollectionOrderKnown'] == true;
    final complete = data['faceCollectionComplete'] == true;
    if (complete &&
        (!orderKnown ||
            ordered.any((id) => !ids.contains(id)) ||
            ordered.isNotEmpty &&
                (!selectionKnown || !ordered.contains(selected)))) {
      return unknown();
    }
    String? catalog;
    final rawCatalog = data['faceComplicationCatalog'];
    if (rawCatalog is Uint8List && rawCatalog.length <= 256 * 1024) {
      try {
        final decoded = utf8.decode(rawCatalog);
        final value = jsonDecode(decoded);
        if (value is Map<String, dynamic> && value.length <= 1024) {
          catalog = decoded;
        }
      } on FormatException {
        /* Optional metadata never invalidates face facts. */
      }
    }
    return NativeFaceCollection(
      connected: connected,
      pair: data['faceCollectionPair'] as String,
      epoch: data['faceCollectionEpoch'] as String,
      observedAt: stamp,
      complete: complete,
      orderKnown: orderKnown,
      selectionKnown: selectionKnown,
      selected: selected is String && selected.isNotEmpty ? selected : null,
      ordered: List.unmodifiable(ordered),
      faces: List.unmodifiable(faces),
      complicationCatalogJson: catalog,
      complicationCatalogComplete:
          catalog != null && data['faceComplicationCatalogComplete'] == true,
    );
  }

  List<String> get displayOrder => List.unmodifiable([
    ...ordered,
    ...faces.where((face) => !ordered.contains(face.id)).map((face) => face.id),
  ]);

  NativeWatchFace? face(String id) {
    for (final face in faces) {
      if (face.id == id) return face;
    }
    return null;
  }
}
