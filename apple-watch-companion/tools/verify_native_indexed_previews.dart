// Build-time cross-language verification before promoting factored native assets.
// Does not initialize Flutter, connect to a Watch or mutate any application files.
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:apple_watch_companion/services/native_indexed_preview_codec.dart';

void main(List<String> arguments) {
  if (arguments.length != 1) {
    throw ArgumentError('Pass the absolute temporary staging directory.');
  }
  final directory = Directory(arguments.single).absolute;
  final manifestBytes = File(
    '${directory.path}/manifest.json',
  ).readAsBytesSync();
  // Offline staging traversal, not the phone's lazy reader. Individual encoded
  // frames and planes still use the production decoder's strict size budgets.
  if (manifestBytes.length > 64 * 1024 * 1024) {
    throw const FormatException('Native manifest size exceeded.');
  }
  final manifest = jsonDecode(utf8.decode(manifestBytes)) as Map;
  final entries = manifest['entries'] as Map;
  if (manifest['version'] != 2 || entries.length > 256 * 10000) {
    throw const FormatException('Invalid native staged manifest.');
  }
  final paths = entries.values.cast<String>().toSet();
  final maps = <String, Uint8List>{};
  var decoded = 0;
  for (final asset in paths.where((v) => v.endsWith('.nfp'))) {
    final match = RegExp(
      r'^assets/native_faces/configured_previews/([a-f0-9]{64})\.nfp$',
    ).firstMatch(asset);
    if (match == null) {
      throw const FormatException('Invalid staged asset path.');
    }
    final frame = File(
      '${directory.path}/files/${match.group(1)}.nfp',
    ).readAsBytesSync();
    final mapHash = NativeIndexedPreviewCodec.mapDigest(frame);
    final map = maps[mapHash] ??= File(
      '${directory.path}/files/$mapHash.nfpm',
    ).readAsBytesSync();
    final result = NativeIndexedPreviewCodec.decode(
      frame,
      map,
      match.group(1)!,
    );
    if (result.width != 422 || result.height != 514) {
      throw const FormatException('Unexpected native renderer dimensions.');
    }
    decoded++;
  }
  if (decoded == 0) throw const FormatException('No indexed frames verified.');
  stdout.writeln(
    jsonEncode({
      'configurationCount': entries.length,
      'indexedFrames': decoded,
      'sharedMaps': maps.length,
      'allNativePixelHashesExact': true,
    }),
  );
}
