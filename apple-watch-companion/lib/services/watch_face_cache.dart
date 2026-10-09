import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import '../models/watch_face.dart';
import 'watch_face_codec.dart';

/// Existing documents/watchfaces storage and archive format.
class WatchFaceCache {
  Future<File?> saveFaceToCache(
    WatchFace face, {
    Uint8List? snapshotBytes,
  }) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final facesDir = Directory('${dir.path}/watchfaces');
      if (!await facesDir.exists()) {
        await facesDir.create(recursive: true);
      }
      final file = File('${facesDir.path}/${face.id}.watchface');
      final encoded = WatchFaceCodec.encode(face, snapshotBytes: snapshotBytes);
      await file.writeAsBytes(encoded, flush: true);
      return file;
    } catch (e) {
      debugPrint('[WatchFaceApiService] Error caching watchface locally: $e');
      return null;
    }
  }

  Future<List<WatchFace>> loadCachedFaces() async {
    final List<WatchFace> faces = [];
    try {
      final dir = await getApplicationDocumentsDirectory();
      final facesDir = Directory('${dir.path}/watchfaces');
      if (await facesDir.exists()) {
        final entities = await facesDir.list().toList();
        for (final entity in entities) {
          if (entity is File && entity.path.endsWith('.watchface')) {
            try {
              final bytes = await entity.readAsBytes();
              final result = WatchFaceCodec.decode(bytes);
              faces.add(result.face);
            } catch (e) {
              debugPrint(
                '[WatchFaceApiService] Failed to decode cached ${entity.path}: $e',
              );
            }
          }
        }
      }
    } catch (e) {
      debugPrint('[WatchFaceApiService] Error reading cached faces: $e');
    }
    return faces;
  }
}
