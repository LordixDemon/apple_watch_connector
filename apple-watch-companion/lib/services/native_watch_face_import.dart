import 'dart:convert';
import 'dart:typed_data';
import 'package:archive/archive.dart';
import 'package:flutter/foundation.dart';
import 'native_face_edit_metadata.dart';
import 'native_watch_face_archive.dart';

/// The original file and its shared preview, never an installed Watch observation.
/// Review inflation/JSON decoding happen in a worker, before opening the review.
final class NativeWatchFaceImport {
  static const maxPreviewBytes = 2 * 1024 * 1024;
  final Uint8List _archive;
  final Uint8List? _preview;
  final String configurationJson, family;
  NativeWatchFaceImport._(
    this._archive,
    this._preview,
    this.configurationJson,
    this.family,
  );

  Uint8List get archive => Uint8List.fromList(_archive);
  Uint8List? get preview =>
      _preview == null ? null : Uint8List.fromList(_preview);
  Map<String, dynamic> get configuration => jsonDecode(configurationJson);

  /// Called only for an explicit Add. Review keeps the original file intact;
  /// preparation adds verified native import metrics and retains every resource.
  Future<Uint8List> prepareForAddition() => compute(
    _prepareAddition,
    _archive,
    debugLabel: 'native-watchface-import-prepare',
  );

  static Uint8List _prepareAddition(Uint8List bytes) {
    final archive = NativeWatchFaceArchive.validatedArchive(bytes);
    final original = NativeWatchFaceArchive.configurationFromArchive(archive);
    final configuration = NativeFaceEditMetadata.forSharedAddition(original);
    final json = Uint8List.fromList(utf8.encode(jsonEncode(configuration)));
    NativeWatchFaceArchive.decodeConfiguration(json);
    archive.addFile(ArchiveFile('face.json', json.length, json));
    final prepared = Uint8List.fromList(ZipEncoder().encode(archive)!);
    NativeWatchFaceArchive.configuration(prepared);
    return prepared;
  }

  static Future<NativeWatchFaceImport> inspect(Uint8List bytes) => compute(
    _inspect,
    Uint8List.fromList(bytes),
    debugLabel: 'native-watchface-import-review',
  );

  static NativeWatchFaceImport _inspect(Uint8List bytes) {
    final archive = NativeWatchFaceArchive.validatedArchive(bytes);
    final configuration = NativeWatchFaceArchive.configurationFromArchive(
      archive,
    );
    Uint8List? preview;
    // Only exact root sharing presentation. Resources with these names are opaque.
    for (final name in ['no_borders_snapshot.png', 'snapshot.png']) {
      final file = archive.findFile(name);
      if (file == null || !file.isFile || file.size > maxPreviewBytes) continue;
      final content = Uint8List.fromList(file.content as List<int>);
      if (_boundedPng(content)) {
        preview = content;
        break;
      }
    }
    return NativeWatchFaceImport._(
      bytes,
      preview,
      jsonEncode(configuration),
      NativeWatchFaceArchive.familyIdentity(configuration)!,
    );
  }

  static bool _boundedPng(Uint8List bytes) {
    if (bytes.length < 33 || bytes.length > maxPreviewBytes) return false;
    const signature = [137, 80, 78, 71, 13, 10, 26, 10];
    for (var i = 0; i < signature.length; i++) {
      if (bytes[i] != signature[i]) return false;
    }
    final header = ByteData.sublistView(bytes);
    if (header.getUint32(8) != 13 || header.getUint32(12) != 0x49484452) {
      return false;
    }
    final width = header.getUint32(16), height = header.getUint32(20);
    // Native image decoding checks the rest of the PNG; corrupt images get the
    // UI fallback and do not change the original package kept for review.
    return width > 0 && height > 0 && width <= 2048 && height <= 2048;
  }
}
