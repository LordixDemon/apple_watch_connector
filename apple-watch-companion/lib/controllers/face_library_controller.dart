import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import '../l10n/strings.dart';
import '../models/watch_face.dart';
import '../services/settings_storage.dart';
import '../services/watch_face_api_service.dart';
import '../services/watch_face_catalog.dart';
import '../services/watch_face_codec.dart';

/// Local designs/catalog are independent of native Watch inventory and selection.
class FaceLibraryController extends ChangeNotifier {
  final SettingsStorage _storage;
  final WatchFaceApiService _api;
  final bool _ownsApi;
  bool _disposed = false;
  List<WatchFace> _designs = [];
  List<WatchFace> _remote = [];
  late final Future<void> initialized;

  FaceLibraryController({
    required SettingsStorage storage,
    required WatchFaceApiService api,
    bool ownsApi = false,
  }) : _storage = storage,
       _api = api,
       _ownsApi = ownsApi {
    _initFaces();
    initialized = _loadCachedAndRemoteFaces();
  }

  List<WatchFace> get designs => List.unmodifiable(_designs);
  List<WatchFace> get remote => List.unmodifiable(_remote);
  void _changed() {
    if (!_disposed) notifyListeners();
  }

  void _initFaces() {
    final savedIds = _storage.loadInstalledFaceIds() ?? [];
    _designs = WatchFaceCatalog.allCollections
        .expand((c) => c.faces)
        .where((face) => savedIds.contains(face.id))
        .toList();
  }

  Future<void> saveFaceDesign(WatchFace face) async {
    final design = face.copyWith(
      id: 'local_${DateTime.now().microsecondsSinceEpoch}',
    );
    _designs.add(design);
    _changed();
    await _api.saveFaceToCache(design);
    await _storage.saveInstalledFaceIds(_designs.map((f) => f.id).toList());
  }

  Future<void> removeLocalFaceDesign(String id) async {
    _designs.removeWhere((face) => face.id == id);
    _changed();
    await _storage.saveInstalledFaceIds(_designs.map((f) => f.id).toList());
  }

  Future<void> _loadCachedAndRemoteFaces() async {
    try {
      final cached = await _api.loadCachedFaces();
      if (_disposed) return;
      if (cached.isNotEmpty) {
        for (final face in cached) {
          if (!_designs.any((f) => f.id == face.id)) {
            _designs.add(face);
          }
        }
        _changed();
      }
      await fetchRemoteCatalog();
    } catch (_) {}
  }

  Future<void> fetchRemoteCatalog() async {
    try {
      final remote = await _api.fetchRemoteFaces();
      if (_disposed) return;
      if (remote.isNotEmpty) {
        _remote = remote;
        _changed();
      }
    } catch (_) {}
  }

  Future<WatchFace> importWatchFace(Uint8List archiveBytes) async {
    final result = WatchFaceCodec.decode(archiveBytes);
    final importedFace = result.face.copyWith(
      id: 'imported_${DateTime.now().millisecondsSinceEpoch}',
      collection: Strings.current.imported,
    );

    _designs.add(importedFace);
    _changed();

    await _storage.saveInstalledFaceIds(_designs.map((f) => f.id).toList());
    await _api.saveFaceToCache(
      importedFace,
      snapshotBytes: result.snapshotBytes,
    );

    return importedFace;
  }

  Uint8List exportWatchFace(WatchFace face, {Uint8List? snapshotBytes}) {
    return WatchFaceCodec.encode(face, snapshotBytes: snapshotBytes);
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    if (_ownsApi) _api.dispose();
    super.dispose();
  }
}
