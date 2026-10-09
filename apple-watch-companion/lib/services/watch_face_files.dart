import 'dart:io';
import 'dart:typed_data';
import 'package:file_selector/file_selector.dart';
import 'package:flutter/services.dart';
import 'native_watch_face_archive.dart';
import 'native_face_photos.dart';

/// File dialogs belong to the platform; no storage permissions or hardcoded paths.
class WatchFaceFiles {
  Future<Uint8List?> openPhoto() async {
    final file = await openFile(
      acceptedTypeGroups: const [
        XTypeGroup(
          label: 'Photo',
          extensions: ['jpg', 'jpeg', 'png', 'heic', 'heif', 'webp'],
          mimeTypes: ['image/*'],
          uniformTypeIdentifiers: ['public.image'],
        ),
      ],
    );
    if (file == null) return null;
    if (await file.length() > NativeFacePhotos.maxInputBytes) {
      throw const FormatException('Photo exceeds 24 MiB.');
    }
    return file.readAsBytes();
  }

  static const _android = MethodChannel(
    'dev.applewatchandroid.companion/face_files',
  );
  Future<Uint8List?> open() async {
    final file = await openFile(
      acceptedTypeGroups: const [
        XTypeGroup(
          label: 'Watch face',
          extensions: ['watchface', 'zip'],
          mimeTypes: ['*/*'],
          uniformTypeIdentifiers: ['public.data'],
        ),
      ],
    );
    if (file == null) return null;
    if (await file.length() > NativeWatchFaceArchive.maxBytes) {
      throw const FormatException(
        'Native .watchface packages support up to 16 MiB.',
      );
    }
    return file.readAsBytes();
  }

  Future<bool> save(Uint8List bytes, String name) async {
    if (Platform.isAndroid) {
      return await _android.invokeMethod<bool>('save', {
            'bytes': bytes,
            'name': name,
          }) ??
          false;
    }
    final location = await getSaveLocation(
      suggestedName: name,
      acceptedTypeGroups: [
        XTypeGroup(
          label: 'Watch face',
          extensions: [
            name.endsWith('.watchlayout') ? 'watchlayout' : 'watchface',
          ],
          uniformTypeIdentifiers: const ['public.data'],
        ),
      ],
    );
    if (location == null) return false;
    await XFile.fromData(
      bytes,
      mimeType: 'application/zip',
      name: name,
    ).saveTo(location.path);
    return true;
  }
}
