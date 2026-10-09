import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:archive/archive.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as image;
import 'native_face_gallery.dart';
import 'native_face_edit_metadata.dart';
import 'native_watch_face_archive.dart';
import 'native_photos_plist.dart';

/// Bounded decoding uses the platform codec (including orientation/HEIC where
/// supported). JPEG encoding and ZIP compression run outside the UI isolate.
abstract final class NativeFacePhotos {
  static const maxInputBytes = 24 * 1024 * 1024;
  static const maxPhotos = 24;
  static const maxTotalPhotoBytes = 12 * 1024 * 1024;

  static Future<ui.Image> decode(Uint8List bytes) async {
    if (bytes.isEmpty || bytes.length > maxInputBytes) {
      throw const FormatException(
        'Invalid or oversized image (maximum 24 MiB).',
      );
    }
    final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
    final codec = await ui.instantiateImageCodecWithSize(
      buffer,
      getTargetSize: (w, h) {
        if (w <= 0 ||
            h <= 0 ||
            w.toDouble() / h > 32 ||
            h.toDouble() / w > 32) {
          throw const FormatException('Unsupported image dimensions.');
        }
        final scale = math.min(1.0, 1536 / math.max(w, h));
        return ui.TargetImageSize(
          width: math.max(1, (w * scale).round()),
          height: math.max(1, (h * scale).round()),
        );
      },
    );
    try {
      return (await codec.getNextFrame()).image;
    } finally {
      codec.dispose();
    }
  }

  /// x/y are fractions of the available movement, never raw screen coordinates.
  static ui.Rect crop(
    ui.Image source,
    double aspect,
    double zoom,
    double x,
    double y,
  ) => cropForSize(source.width, source.height, aspect, zoom, x, y);

  static ui.Rect cropForSize(
    int width,
    int height,
    double aspect,
    double zoom,
    double x,
    double y,
  ) {
    if (width < 1 ||
        height < 1 ||
        !aspect.isFinite ||
        aspect < .65 ||
        aspect > 1 ||
        !zoom.isFinite ||
        zoom < 1 ||
        zoom > 4 ||
        !x.isFinite ||
        !y.isFinite) {
      throw const FormatException('Invalid crop.');
    }
    final w = math.min(width.toDouble(), height * aspect) / zoom;
    final h = w / aspect;
    return ui.Rect.fromLTWH(
      (width - w) * x.clamp(0, 1),
      (height - h) * y.clamp(0, 1),
      w,
      h,
    );
  }

  static Future<NativeFacePhoto> render(ui.Image source, ui.Rect crop) async {
    final width = math.min(1024, crop.width.round());
    final height = (width * crop.height / crop.width).round();
    if (width < 1 ||
        height < 1 ||
        height > 1576 ||
        !ui.Rect.fromLTWH(
          0,
          0,
          source.width.toDouble(),
          source.height.toDouble(),
        ).contains(crop.topLeft) ||
        crop.right > source.width ||
        crop.bottom > source.height) {
      throw const FormatException('Crop outside image.');
    }
    final recorder = ui.PictureRecorder();
    final canvas = ui.Canvas(recorder);
    canvas.drawColor(const ui.Color(0xff000000), ui.BlendMode.src);
    canvas.drawImageRect(
      source,
      crop,
      ui.Rect.fromLTWH(0, 0, width.toDouble(), height.toDouble()),
      ui.Paint()..filterQuality = ui.FilterQuality.high,
    );
    final picture = recorder.endRecording();
    ui.Image? raster;
    try {
      raster = await picture.toImage(width, height);
      final rgba = await raster.toByteData(format: ui.ImageByteFormat.rawRgba);
      if (rgba == null) throw const FormatException('Image rendering failed.');
      final bytes = await compute(_jpeg, (
        width,
        height,
        rgba.buffer.asUint8List(rgba.offsetInBytes, rgba.lengthInBytes),
      ));
      final photo = NativeFacePhoto(bytes, width, height);
      photo.validate();
      return photo;
    } finally {
      raster?.dispose();
      picture.dispose();
    }
  }

  static Uint8List _jpeg((int, int, Uint8List) data) => image.encodeJpg(
    image.Image.fromBytes(
      width: data.$1,
      height: data.$2,
      bytes: data.$3.buffer,
      numChannels: 4,
    ),
    quality: 90,
  );

  static Future<Uint8List> package(
    NativeFaceTemplate template,
    List<NativeFacePhoto> photos, {
    int alignment = 0,
    int scale = 1,
  }) async {
    if (!template.requiresPhotos ||
        photos.fold<int>(0, (sum, p) => sum + p.jpeg.length) >
            maxTotalPhotoBytes ||
        photos.isEmpty ||
        photos.length > maxPhotos ||
        alignment < 0 ||
        alignment > 1 ||
        scale < 0 ||
        scale > 3) {
      throw const FormatException('Invalid Photos selection.');
    }
    return compute(_package, (
      jsonEncode(
        NativeFaceEditMetadata.forGalleryAddition(
          jsonDecode(template.configurationJson) as Map<String, dynamic>,
        ),
      ),
      photos,
      alignment,
      scale,
      null,
      false,
    ));
  }

  static Future<NativePhotoAlbum> openPackage(Uint8List bytes) =>
      compute(_openPackage, bytes);

  static NativePhotoAlbum _openPackage(Uint8List bytes) {
    final config = NativeWatchFaceArchive.configuration(bytes);
    if (config['bundle id'] != 'com.apple.NTKParmesanFaceBundle' ||
        config['customization'] is! Map ||
        config['customization']['content'] != 'manual') {
      throw const FormatException(
        'Only manual Photos albums can be edited here.',
      );
    }
    final archive = ZipDecoder().decodeBytes(bytes);
    final files = <String, Uint8List>{
      for (final file in archive.files.where((f) => f.isFile))
        file.name: Uint8List.fromList(file.content as List<int>),
    };
    final manifestBytes = files['Resources/Images.plist'];
    if (manifestBytes == null) {
      throw const FormatException('Missing Photos manifest.');
    }
    final manifest = NativePhotosPlist.decode(manifestBytes);
    if (manifest is! Map<String, dynamic> ||
        manifest['version'] != 2 ||
        manifest['imageList'] is! List) {
      throw const FormatException('Unsupported Photos manifest.');
    }
    final assets = manifest['imageList'] as List;
    if (assets.isEmpty || assets.length > maxPhotos) {
      throw const FormatException('Unsupported Photos album size.');
    }
    final photos = <NativeFacePhoto>[];
    final ids = <String>{};
    for (final row in assets) {
      if (row is! Map<String, dynamic> ||
          row['localIdentifier'] is! String ||
          !ids.add(row['localIdentifier'] as String) ||
          row['layouts'] is! List) {
        throw const FormatException('Invalid Photos asset.');
      }
      final layouts = row['layouts'] as List;
      // Parmesan constructs assets through JSONSerialization internally. A data
      // or date object nested in an asset can throw inside the native daemon.
      try {
        jsonEncode(row);
      } on JsonUnsupportedObjectError {
        throw const FormatException(
          'Photos asset contains unsupported native JSON metadata.',
        );
      }
      final preferred = row['preferredTimeLayout'];
      Map? chosen;
      for (final layout in layouts.whereType<Map>()) {
        if (layout['baseImageName'] is String &&
            (chosen == null ||
                preferred is Map &&
                    layout['timeLayout'] is Map &&
                    layout['timeLayout']['alignment'] ==
                        preferred['alignment'] &&
                    layout['timeLayout']['scale'] == preferred['scale'])) {
          chosen = layout;
        }
      }
      final name = chosen?['baseImageName'];
      final jpeg = name is String ? files['Resources/$name'] : null;
      if (jpeg == null ||
          jpeg.length < NativeFacePhoto.minJpegBytes ||
          jpeg.length > NativeFacePhoto.maxJpegBytes ||
          jpeg[0] != 0xff ||
          jpeg[1] != 0xd8) {
        throw const FormatException('Photos asset image is unavailable.');
      }
      final size = row['presentationSize'];
      if (size is! List ||
          size.length != 2 ||
          size.any((v) => v is! num || !v.isFinite || v < 1 || v > 8192)) {
        throw const FormatException('Invalid Photos presentation size.');
      }
      photos.add(
        NativeFacePhoto._retained(
          jpeg,
          (size[0] as num).round(),
          (size[1] as num).round(),
          row,
        ),
      );
    }
    return NativePhotoAlbum(
      jsonEncode(config),
      sha256.convert(bytes).toString(),
      files,
      manifest,
      photos,
    );
  }

  static Future<Uint8List> editPackage(
    NativePhotoAlbum album,
    List<NativeFacePhoto> photos, {
    required String configurationJson,
    int alignment = 0,
    int scale = 1,
    bool changeTimeLayout = false,
  }) {
    if (photos.isEmpty ||
        photos.length > maxPhotos ||
        alignment < 0 ||
        alignment > 1 ||
        scale < 0 ||
        scale > 3 ||
        photos.fold<int>(0, (sum, p) => sum + p.jpeg.length) >
            maxTotalPhotoBytes) {
      throw const FormatException('Invalid Photos selection.');
    }
    // The controller and HAL also compare this with the current committed face.
    return compute(_package, (
      configurationJson,
      photos,
      alignment,
      scale,
      album,
      changeTimeLayout,
    ));
  }

  static Uint8List _package(
    (String, List<NativeFacePhoto>, int, int, NativePhotoAlbum?, bool) input,
  ) {
    final config = jsonDecode(input.$1) as Map<String, dynamic>;
    final archive = Archive();
    final assets = <Map<String, dynamic>>[];
    final files = <String>{};
    final assignedIds = input.$2
        .map((p) => p._asset?['localIdentifier'])
        .whereType<String>()
        .toSet();
    final album = input.$5;
    final removedResources = <String>{};
    if (album != null) {
      final retained = input.$2
          .map((p) => p._asset)
          .whereType<Map<String, dynamic>>()
          .toList();
      final retainedIds = retained.map((a) => a['localIdentifier']).toSet();
      final keptNames = retained
          .expand((a) => _resourceNames(a, album.files))
          .toSet();
      keptNames.addAll(
        _resourceNames({...album.manifest}..remove('imageList'), album.files),
      );
      for (final old in album.photos.where(
        (p) => !retainedIds.contains(p._asset?['localIdentifier']),
      )) {
        removedResources.addAll(
          _resourceNames(
            old._asset!,
            album.files,
          ).where((n) => !keptNames.contains(n)),
        );
      }
      for (final entry in album.files.entries) {
        if (entry.key == 'face.json' ||
            entry.key == 'Resources/Images.plist' ||
            removedResources.contains(entry.key)) {
          continue;
        }
        archive.addFile(
          ArchiveFile(entry.key, entry.value.length, entry.value),
        );
        files.add(entry.key);
      }
    }
    for (final photo in input.$2) {
      if (photo._asset != null) {
        final asset = Map<String, dynamic>.from(photo._asset);
        if (input.$6) {
          asset['preferredTimeLayout'] = {
            'alignment': input.$3,
            'scale': input.$4,
          };
        }
        assets.add(asset);
        continue;
      }
      photo.validate();
      final digest = sha256.convert(photo.jpeg).toString();
      var id = digest, suffix = 0;
      while (!assignedIds.add(id)) {
        id = '$digest-${++suffix}';
      }
      final name = '$id.jpg';
      if (files.add('Resources/$name')) {
        archive.addFile(
          ArchiveFile('Resources/$name', photo.jpeg.length, photo.jpeg),
        );
      }
      // Native Swift dictionary encoding alternates TimeLayout keys and values.
      final layouts = <Map<String, dynamic>>[];
      for (var alignment = 0; alignment < 2; alignment++) {
        for (var scale = 0; scale < 4; scale++) {
          final layout = {'alignment': alignment, 'scale': scale};
          layouts.add(layout);
          layouts.add({
            'timeLayout': layout,
            'baseImageName': name,
            'originalCrop': {
              'origin': {'x': 0, 'y': 0},
              'size': {'width': 1, 'height': 1},
            },
            'imageAOTBrightness': 0,
            'userEdited': true,
          });
        }
      }
      assets.add({
        'localIdentifier': id,
        'modificationDate':
            DateTime.now().millisecondsSinceEpoch / 1000 - 978307200,
        'presentationSize': [photo.width, photo.height],
        'preferredTimeLayout': {'alignment': input.$3, 'scale': input.$4},
        'layouts': layouts,
      });
    }
    if (assets.map((a) => a['localIdentifier']).toSet().length !=
        assets.length) {
      throw const FormatException(
        'A retained Photos asset cannot be repeated.',
      );
    }
    final manifest = NativePhotosPlist.encode({
      ...?album?.manifest,
      'version': 2,
      'imageList': assets,
    });
    archive.addFile(
      ArchiveFile('Resources/Images.plist', manifest.length, manifest),
    );
    final json = utf8.encode(jsonEncode(config));
    archive.addFile(ArchiveFile('face.json', json.length, json));
    final bytes = Uint8List.fromList(ZipEncoder().encode(archive)!);
    NativeWatchFaceArchive.configuration(bytes);
    return bytes;
  }

  static Iterable<String> _resourceNames(
    Object value,
    Map<String, Uint8List> files,
  ) sync* {
    if (value is String && files.containsKey('Resources/$value')) {
      yield 'Resources/$value';
    } else if (value is Map) {
      for (final v in value.values) {
        if (v != null) yield* _resourceNames(v as Object, files);
      }
    } else if (value is List && value is! Uint8List) {
      for (final v in value) {
        if (v != null) yield* _resourceNames(v as Object, files);
      }
    }
  }
}

final class NativePhotoAlbum {
  final String configurationJson;
  final String archiveHash;
  final Map<String, Uint8List> files;
  final Map<String, dynamic> manifest;
  final List<NativeFacePhoto> photos;
  NativePhotoAlbum(
    this.configurationJson,
    this.archiveHash,
    this.files,
    this.manifest,
    this.photos,
  );
}

final class NativeFacePhoto {
  // Observed NTKParmesanResourcesManifest native validation, watchOS 23S303.
  static const minJpegBytes = 1000, maxJpegBytes = 4000000;
  final Uint8List jpeg;
  final int width, height;
  final Map<String, dynamic>? _asset;
  NativeFacePhoto(this.jpeg, this.width, this.height) : _asset = null;
  NativeFacePhoto._retained(this.jpeg, this.width, this.height, this._asset);
  void validate() {
    if (jpeg.length < minJpegBytes ||
        jpeg.length > maxJpegBytes ||
        jpeg[0] != 0xff ||
        jpeg[1] != 0xd8 ||
        width < 1 ||
        width > 1024 ||
        height < 1 ||
        height > 1576) {
      throw const FormatException('Invalid Photos image.');
    }
  }
}
