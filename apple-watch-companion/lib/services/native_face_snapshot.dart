import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'native_binary_plist.dart';

/// A decoded native snapshot, not evidence of installation or a live Watch screen.
/// The caller must obtain its expected key from the authenticated native record.
final class NativeFaceSnapshot {
  static const maxArchiveBytes = 4 * 1024 * 1024;
  static const maxImageBytes = 2 * 1024 * 1024;
  final String snapshotKey, rawSnapshotKey;
  final int width, height;
  final double scale;
  final bool blankComplications;
  final Uint8List _png;
  NativeFaceSnapshot._(
    this.snapshotKey,
    this.rawSnapshotKey,
    this.width,
    this.height,
    this.scale,
    this.blankComplications,
    this._png,
  );

  Uint8List get png => Uint8List.fromList(_png);

  static Future<NativeFaceSnapshot> inspect(
    Uint8List archive, {
    required String expectedSnapshotKey,
  }) => compute(_inspect, (
    Uint8List.fromList(archive),
    expectedSnapshotKey,
  ), debugLabel: 'native-watchface-snapshot');

  static NativeFaceSnapshot _inspect((Uint8List, String) input) =>
      decode(input.$1, expectedSnapshotKey: input.$2);

  static NativeFaceSnapshot decode(
    Uint8List archive, {
    required String expectedSnapshotKey,
  }) {
    if (expectedSnapshotKey.isEmpty || expectedSnapshotKey.length > 512) {
      throw const FormatException('Invalid expected native snapshot key.');
    }
    final value = NativeBinaryPlist.decode(
      archive,
      maxBytes: maxArchiveBytes,
      allowUids: true,
    );
    if (value is! Map ||
        value['\$archiver'] != 'NSKeyedArchiver' ||
        value['\$version'] != 100000 ||
        value['\$objects'] is! List ||
        value['\$top'] is! Map) {
      throw const FormatException('Invalid native snapshot archive.');
    }
    final objects = value['\$objects'] as List;
    if (objects.isEmpty || objects.length > 2048 || objects.first != '\$null') {
      throw const FormatException('Invalid native snapshot object table.');
    }
    Object? resolve(Object? reference) {
      if (reference is! NativePlistUid || reference.value >= objects.length) {
        throw const FormatException('Invalid native snapshot reference.');
      }
      return objects[reference.value];
    }

    Map object(Object? reference, String name) {
      final resolved = resolve(reference);
      if (resolved is! Map) {
        throw const FormatException('Invalid native snapshot object.');
      }
      final descriptor = resolve(resolved['\$class']);
      if (descriptor is! Map ||
          descriptor['\$classname'] != name ||
          descriptor['\$classes'] is! List ||
          !(descriptor['\$classes'] as List).contains(name)) {
        throw const FormatException('Unexpected native snapshot class.');
      }
      return resolved;
    }

    String key(Object? reference) {
      final resolved = resolve(reference);
      if (resolved is! String ||
          resolved.isEmpty ||
          resolved.length > 512 ||
          resolved.contains('\u0000')) {
        throw const FormatException('Invalid native snapshot key.');
      }
      return resolved;
    }

    final top = value['\$top'] as Map;
    if (top.length != 1 || !top.containsKey('root')) {
      throw const FormatException('Invalid native snapshot root.');
    }
    final root = object(top['root'], 'NTKFaceSnapshotResult');
    final snapshotKey = key(root['snapshotKey']);
    if (snapshotKey != expectedSnapshotKey) {
      throw const FormatException(
        'Native snapshot belongs to another configuration.',
      );
    }
    final rawKey = key(root['rawSnapshotKey']);
    final blank = root['blankComplications'];
    if (blank is! bool) {
      throw const FormatException('Invalid native complication snapshot flag.');
    }
    final image = object(root['snapshot'], 'UIImage');
    final scale = image['UIScale'];
    if (image['UIImageOrientation'] != 0 ||
        scale is! num ||
        !scale.isFinite ||
        scale < 1 ||
        scale > 4) {
      throw const FormatException(
        'Unsupported native snapshot orientation or scale.',
      );
    }
    final imageBytes = resolve(image['UIImageData']);
    if (imageBytes is! Uint8List ||
        imageBytes.length < 33 ||
        imageBytes.length > maxImageBytes) {
      throw const FormatException('Invalid native snapshot image size.');
    }
    const signature = [137, 80, 78, 71, 13, 10, 26, 10];
    for (var i = 0; i < signature.length; i++) {
      if (imageBytes[i] != signature[i]) {
        throw const FormatException(
          'Unsupported native snapshot image format.',
        );
      }
    }
    final header = ByteData.sublistView(imageBytes);
    final width = header.getUint32(16), height = header.getUint32(20);
    if (header.getUint32(8) != 13 ||
        header.getUint32(12) != 0x49484452 ||
        width == 0 ||
        height == 0 ||
        width > 2048 ||
        height > 2048) {
      throw const FormatException('Invalid native snapshot image dimensions.');
    }
    final archivedSize = resolve(image['UIImageSizeInPixels']);
    if (archivedSize != '{$width, $height}') {
      throw const FormatException(
        'Native snapshot dimensions do not match its image.',
      );
    }
    // cachedFile.fileURL is deliberately never resolved or opened. The encoded
    // image is sufficient; receiver-controlled storage owns external assets.
    return NativeFaceSnapshot._(
      snapshotKey,
      rawKey,
      width,
      height,
      scale.toDouble(),
      blank,
      imageBytes,
    );
  }
}
