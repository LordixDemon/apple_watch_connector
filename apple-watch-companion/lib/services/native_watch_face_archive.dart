import 'dart:convert';
import 'dart:typed_data';
import 'package:archive/archive.dart';

/// Native NTK packages retain opaque descriptors, intents and bundled resources.
/// Local painted layouts use a separate codec and cannot be installed as native faces.
abstract final class NativeWatchFaceArchive {
  static const maxBytes = 16 * 1024 * 1024;
  static const maxConfigurationBytes = 128 * 1024;
  static Map<String, dynamic> configuration(Uint8List bytes) {
    return configurationFromArchive(validatedArchive(bytes));
  }

  /// Headers, declared sizes and paths are checked before inflating any entry.
  static Archive validatedArchive(Uint8List bytes) {
    if (bytes.length < 4 ||
        bytes.length > maxBytes ||
        bytes[0] != 80 ||
        bytes[1] != 75 ||
        bytes[2] != 3 ||
        bytes[3] != 4) {
      throw const FormatException(
        'Invalid or oversized native .watchface package (maximum 16 MiB).',
      );
    }
    final archive = ZipDecoder().decodeBytes(bytes);
    if (archive.length > 2048) {
      throw const FormatException('Too many archive entries.');
    }
    final seen = <String>{};
    var expanded = 0;
    for (final file in archive) {
      final name = file.name;
      expanded += file.size;
      if (!seen.add(name) ||
          name.startsWith('/') ||
          name.contains('\\') ||
          name.contains('\u0000') ||
          name.split('/').contains('..') ||
          file.isSymbolicLink ||
          expanded > 32 * 1024 * 1024) {
        throw const FormatException('Unsafe or oversized archive entry.');
      }
    }
    return archive;
  }

  static Map<String, dynamic> configurationFromArchive(Archive archive) {
    ArchiveFile? face;
    for (final file in archive) {
      if (file.name == 'face.json' && file.isFile) face = file;
    }
    if (face == null || face.size > maxConfigurationBytes) {
      throw const FormatException('A native package must contain face.json.');
    }
    return decodeConfiguration(Uint8List.fromList(face.content as List<int>));
  }

  static Map<String, dynamic> decodeConfiguration(Uint8List bytes) {
    if (bytes.isEmpty || bytes.length > maxConfigurationBytes) {
      throw const FormatException('Invalid native configuration size.');
    }
    final value = jsonDecode(utf8.decode(bytes));
    if (value is! Map<String, dynamic> || familyIdentity(value) == null) {
      throw const FormatException(
        'This is a local layout, not a native Watch configuration.',
      );
    }
    return value;
  }

  static String? familyIdentity(Map<String, dynamic> config) {
    final bundle = config['bundle id'];
    if (bundle is String && bundle.isNotEmpty) return 'bundle:$bundle';
    final type = config['face type'];
    return type is String && type.isNotEmpty ? 'type:$type' : null;
  }

  /// Only callers with evidence of a resource-free native face may use this.
  static Uint8List fromConfiguration(String json) {
    final bytes = Uint8List.fromList(utf8.encode(json));
    decodeConfiguration(bytes);
    final archive = Archive()
      ..addFile(ArchiveFile('face.json', bytes.length, bytes));
    return Uint8List.fromList(ZipEncoder().encode(archive)!);
  }
}
