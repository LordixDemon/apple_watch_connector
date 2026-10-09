import 'dart:io';
import 'dart:convert';
import 'dart:typed_data';
import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as image;
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_face_photos.dart';
import 'package:apple_watch_companion/services/native_watch_face_archive.dart';
import 'package:apple_watch_companion/services/native_photos_plist.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Editing an existing album preserves settings and retained opaque resources while deleting old photos',
    () async {
      final profiles = await NativeFaceGallery.load();
      final template = profiles.singleWhere((p) => p.requiresPhotos);
      NativeFacePhoto photo(int shade) => NativeFacePhoto(
        image.encodeJpg(
          image.Image(width: 320, height: 400)
            ..clear(image.ColorRgb8(shade, 30, 70)),
        ),
        320,
        400,
      );
      final bytes = await NativeFacePhotos.package(template, [
        photo(10),
        photo(90),
      ]);
      final archive = ZipDecoder().decodeBytes(bytes);
      final manifest =
          NativePhotosPlist.decode(
                Uint8List.fromList(
                  archive.findFile('Resources/Images.plist')!.content
                      as List<int>,
                ),
              )
              as Map<String, dynamic>;
      final assets = manifest['imageList'] as List;
      assets[0]['opaqueMetadata'] = {
        'depthImage': 'retained-depth.bin',
        'data': [4, 5],
      };
      assets[1]['opaqueMetadata'] = {'depthImage': 'deleted-depth.bin'};
      manifest['unknown'] = 'preserved';
      archive.addFile(ArchiveFile('Resources/retained-depth.bin', 2, [8, 9]));
      archive.addFile(ArchiveFile('Resources/deleted-depth.bin', 2, [6, 7]));
      final plist = NativePhotosPlist.encode(manifest);
      archive.addFile(
        ArchiveFile('Resources/Images.plist', plist.length, plist),
      );
      final config = NativeWatchFaceArchive.configuration(bytes);
      config['metrics']['numberOfGizmoEdits'] = 4;
      config['metrics']['future'] = {'keep': true};
      config['complications'] = {
        'bottom': {'intent': 'opaque-intent'},
      };
      config['customData']['opaque'] = 'preserved';
      final json = jsonEncode(config), jsonBytes = utf8.encode(json);
      archive.addFile(ArchiveFile('face.json', jsonBytes.length, jsonBytes));
      final album = await NativeFacePhotos.openPackage(
        Uint8List.fromList(ZipEncoder().encode(archive)!),
      );
      final edited = await NativeFacePhotos.editPackage(album, [
        photo(200),
        album.photos[0],
      ], configurationJson: json);
      final opened = await NativeFacePhotos.openPackage(edited);
      final result = ZipDecoder().decodeBytes(edited);
      expect(NativeWatchFaceArchive.configuration(edited), config);
      expect(opened.manifest['unknown'], 'preserved');
      expect((opened.manifest['imageList'] as List)[1], assets[0]);
      expect(opened.photos[1].jpeg, album.photos[0].jpeg);
      expect(result.findFile('Resources/retained-depth.bin')!.content, [8, 9]);
      expect(result.findFile('Resources/deleted-depth.bin'), isNull);
      final oldName = assets[1]['layouts'][1]['baseImageName'];
      expect(result.findFile('Resources/$oldName'), isNull);
      final reordered = await NativeFacePhotos.editPackage(album, [
        album.photos[1],
        album.photos[0],
      ], configurationJson: json);
      final read = await NativeFacePhotos.openPackage(reordered);
      expect((read.manifest['imageList'] as List).first, assets[1]);
      expect(read.photos.first.jpeg, album.photos[1].jpeg);
      expect(read.photos.last.jpeg, album.photos[0].jpeg);
      final layout = await NativeFacePhotos.openPackage(
        await NativeFacePhotos.editPackage(
          album,
          album.photos,
          configurationJson: json,
          alignment: 1,
          scale: 3,
          changeTimeLayout: true,
        ),
      );
      for (final asset in layout.manifest['imageList'] as List) {
        expect(asset['preferredTimeLayout'], {'alignment': 1, 'scale': 3});
      }
      const fixture = String.fromEnvironment('EDITED_PHOTOS_NATIVE_FIXTURE');
      if (fixture.isNotEmpty) await File(fixture).writeAsBytes(edited);
    },
  );
  test(
    'Identical image bytes still have distinct native asset identities',
    () async {
      final template = (await NativeFaceGallery.load()).singleWhere(
        (p) => p.requiresPhotos,
      );
      final jpeg = image.encodeJpg(image.Image(width: 320, height: 400));
      final photo = NativeFacePhoto(jpeg, 320, 400);
      final album = await NativeFacePhotos.openPackage(
        await NativeFacePhotos.package(template, [photo, photo]),
      );
      expect(album.photos.length, 2);
      expect(
        (album.manifest['imageList'] as List)
            .map((v) => v['localIdentifier'])
            .toSet()
            .length,
        2,
      );
      expect(album.files.keys.where((n) => n.endsWith('.jpg')).length, 2);
      for (final asset in album.manifest['imageList'] as List) {
        expect(
          album.files.containsKey('Resources/${asset['localIdentifier']}.jpg'),
          isTrue,
        );
      }
    },
  );
  test('Crop remains within portrait and landscape images at every edge', () {
    for (final size in [(400, 800), (1000, 400)]) {
      for (final x in [0.0, .5, 1.0]) {
        for (final y in [0.0, .5, 1.0]) {
          final c = NativeFacePhotos.cropForSize(
            size.$1,
            size.$2,
            .82,
            2,
            x,
            y,
          );
          expect(c.left, greaterThanOrEqualTo(0));
          expect(c.top, greaterThanOrEqualTo(0));
          expect(c.right, lessThanOrEqualTo(size.$1));
          expect(c.bottom, lessThanOrEqualTo(size.$2));
          expect(c.width / c.height, closeTo(.82, .0001));
        }
      }
    }
    expect(
      () => NativeFacePhotos.cropForSize(200, 300, double.nan, 1, .5, .5),
      throwsFormatException,
    );
    expect(
      () => NativeFacePhotos.cropForSize(200, 300, .82, 0, .5, .5),
      throwsFormatException,
    );
  });

  test(
    'Platform crop and JPEG resources retain pixels and native manifest',
    () async {
      final original = image.Image(width: 600, height: 800);
      for (final p in original) {
        p.setRgb(p.x % 256, p.y % 256, (p.x + p.y) % 256);
      }
      final source = await NativeFacePhotos.decode(image.encodePng(original));
      try {
        final crop = NativeFacePhotos.crop(source, .82, 1.5, .2, .8);
        final photo = await NativeFacePhotos.render(source, crop);
        final decoded = image.decodeJpg(photo.jpeg)!;
        expect(decoded.width, photo.width);
        expect(decoded.height, photo.height);
        expect(
          decoded.getPixel(20, 20).r.toInt(),
          closeTo((crop.left + 20).round() % 256, 5),
        );
        final profiles = await NativeFaceGallery.load();
        final template = profiles.singleWhere((p) => p.requiresPhotos);
        final bytes = await NativeFacePhotos.package(
          template,
          [photo],
          alignment: 1,
          scale: 2,
        );
        final config = NativeWatchFaceArchive.configuration(bytes);
        expect(config['customization']['content'], 'manual');
        expect(config['metrics']['origin'], 6);
        expect(config['metrics']['editedState'], 1);
        expect(config['metrics']['dateCreated'], isA<double>());
        expect(config['metrics'].containsKey('numberOfCompanionEdits'), false);
        expect(config['metrics'].containsKey('dateLastEdited'), false);
        expect(
          jsonDecode(template.configurationJson).containsKey('metrics'),
          false,
        );
        final archive = ZipDecoder().decodeBytes(bytes);
        final manifest = utf8.decode(
          archive.findFile('Resources/Images.plist')!.content as List<int>,
        );
        expect(manifest, contains('<integer>2</integer>'));
        expect(manifest, contains('<key>preferredTimeLayout</key>'));
        expect(manifest, contains('<key>originalCrop</key>'));
        expect(archive.files.where((f) => f.name.endsWith('.jpg')).length, 1);
        expect(
          () => NativeFacePhoto(Uint8List(4), 20, 20).validate(),
          throwsFormatException,
        );
        for (final length in [999, 4000001]) {
          final jpeg = Uint8List(length)
            ..[0] = 0xff
            ..[1] = 0xd8;
          expect(
            () => NativeFacePhoto(jpeg, 20, 20).validate(),
            throwsFormatException,
          );
        }
        expect(NativeFacePhotos.package(template, []), throwsFormatException);
        const fixture = String.fromEnvironment('PHOTOS_NATIVE_FIXTURE');
        if (fixture.isNotEmpty) {
          await File(fixture).writeAsBytes(bytes);
        }
      } finally {
        source.dispose();
      }
    },
  );
}
