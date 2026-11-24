import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'watch_face_cache.dart';
import '../models/watch_face.dart';
import 'watch_face_codec.dart';

/// Network and local caching service for Apple Watch face gallery feeds and `.watchface` bundles.
class WatchFaceApiService {
  // Remote catalog endpoint (standard Apple Watch Face community and CDN format)
  static const String defaultGalleryEndpoint =
      'https://raw.githubusercontent.com/seemoo-lab/apple-watch-faces/main/catalog.json';

  final http.Client _client;
  final WatchFaceCache _cache;

  WatchFaceApiService({http.Client? client, WatchFaceCache? cache})
    : _client = client ?? http.Client(),
      _cache = cache ?? WatchFaceCache();

  /// Fetches online watch face collections from the remote server.
  Future<List<WatchFace>> fetchRemoteFaces({
    String endpoint = defaultGalleryEndpoint,
  }) async {
    try {
      final response = await _client
          .get(Uri.parse(endpoint))
          .timeout(const Duration(seconds: 8));

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        if (data is List) {
          return data
              .whereType<Map<String, dynamic>>()
              .map((item) => WatchFace.fromJson(item))
              .toList();
        } else if (data is Map && data['faces'] is List) {
          final list = data['faces'] as List;
          return list
              .whereType<Map<String, dynamic>>()
              .map((item) => WatchFace.fromJson(item))
              .toList();
        }
      }
    } catch (e) {
      debugPrint(
        '[WatchFaceApiService] Remote catalog fetch failed or offline: $e',
      );
    }
    return [];
  }

  /// Downloads a `.watchface` package from a remote URL, decodes it, and caches it locally.
  Future<WatchFaceArchiveResult?> downloadAndCacheWatchFace(
    String fileUrl,
  ) async {
    try {
      final response = await _client
          .get(Uri.parse(fileUrl))
          .timeout(const Duration(seconds: 15));

      if (response.statusCode == 200) {
        final bytes = response.bodyBytes;
        final result = WatchFaceCodec.decode(bytes);

        // Cache locally
        await saveFaceToCache(result.face, snapshotBytes: result.snapshotBytes);
        return result;
      }
    } catch (e) {
      debugPrint(
        '[WatchFaceApiService] Failed to download watch face from $fileUrl: $e',
      );
    }
    return null;
  }

  Future<File?> saveFaceToCache(WatchFace face, {Uint8List? snapshotBytes}) =>
      _cache.saveFaceToCache(face, snapshotBytes: snapshotBytes);

  Future<List<WatchFace>> loadCachedFaces() => _cache.loadCachedFaces();

  void dispose() {
    _client.close();
  }
}
