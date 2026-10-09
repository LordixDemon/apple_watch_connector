import 'dart:typed_data';
import 'package:flutter/services.dart';
import 'package:apple_watch_companion/services/native_preview_catalog.dart';

/// Offline test traversal; production deliberately never flattens the catalog.
Future<Map<String, String>> allNativePreviewAssets() async {
  final index = NativePreviewIndex.decode(
    Uint8List.sublistView(await rootBundle.load(nativePreviewManifestAsset)),
  );
  final entries = <String, String>{...index.entries};
  for (final shard in index.shards.values) {
    entries.addAll(
      decodeNativePreviewShard(
        NativePreviewShardRequest(
          shard,
          Uint8List.sublistView(await rootBundle.load(shard.asset)),
        ),
      ),
    );
  }
  return entries;
}
