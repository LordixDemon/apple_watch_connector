import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_binary_plist.dart';
import 'package:apple_watch_companion/services/native_face_snapshot.dart';
import 'package:apple_watch_companion/services/native_photos_plist.dart';

const analogKey =
    'face44(special.multicolor)(right)(analog)(all)(auto)-(-1)(-1)(-1)(-1)(-1)-(9)-mc';
const digitalKey =
    'face44(special.multicolor)(right)(digital)(all)(auto)-(-1)(-1)(-1)(-1)(-1)-(9)-mc';

Uint8List fixture(String style) =>
    File('test/fixtures/native-snapshot-38-$style.bplist').readAsBytesSync();

List<int> objectOffsets(Uint8List bytes) {
  final data = ByteData.sublistView(bytes), trailer = bytes.length - 32;
  final count = data.getUint64(trailer + 8),
      table = data.getUint64(trailer + 24);
  final size = bytes[trailer + 6];
  return List.generate(count, (i) {
    var value = 0;
    for (var j = 0; j < size; j++) {
      value = value * 256 + bytes[table + i * size + j];
    }
    return value;
  });
}

Uint8List changeReference(Uint8List original, int from, int to) {
  final bytes = Uint8List.fromList(original);
  var changes = 0;
  for (final offset in objectOffsets(bytes)) {
    if (bytes[offset] == 0x80 && bytes[offset + 1] == from) {
      bytes[offset + 1] = to;
      changes++;
    }
  }
  expect(changes, greaterThan(0));
  return bytes;
}

Uint8List changeString(Uint8List original, String from, String to) {
  expect(from.length, to.length);
  final bytes = Uint8List.fromList(original);
  var changes = 0;
  for (final offset in objectOffsets(bytes)) {
    if (from.length < 15 &&
        bytes[offset] == 0x50 + from.length &&
        String.fromCharCodes(
              bytes.sublist(offset + 1, offset + 1 + from.length),
            ) ==
            from) {
      bytes.setAll(offset + 1, to.codeUnits);
      changes++;
    }
  }
  expect(changes, 1);
  return bytes;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Worker parsing retains its captured bytes and validates the expected key',
    () async {
      final bytes = fixture('analog');
      final parsing = NativeFaceSnapshot.inspect(
        bytes,
        expectedSnapshotKey: analogKey,
      );
      bytes.fillRange(0, bytes.length, 0);
      expect(
        sha256.convert((await parsing).png).toString(),
        'ef92afa0dccf47afc5e1bc7da6ba0034eecedb7ba93c866f50be39e3ee97696e',
      );
      await expectLater(
        NativeFaceSnapshot.inspect(
          fixture('analog'),
          expectedSnapshotKey: digitalKey,
        ),
        throwsFormatException,
      );
    },
  );
  test(
    'Actual native analog/digital records decode the exact NanoTimeKit PNG',
    () {
      final analog = NativeFaceSnapshot.decode(
        fixture('analog'),
        expectedSnapshotKey: analogKey,
      );
      final digital = NativeFaceSnapshot.decode(
        fixture('digital'),
        expectedSnapshotKey: digitalKey,
      );
      expect(
        sha256.convert(analog.png).toString(),
        'ef92afa0dccf47afc5e1bc7da6ba0034eecedb7ba93c866f50be39e3ee97696e',
      );
      expect(
        sha256.convert(digital.png).toString(),
        'dbc5da5e696142c5248de52b6a14a9c01b5832770d4e5df18c45ffb5bb36f944',
      );
      expect(analog.png.length, 66494);
      expect(digital.png.length, 61059);
      expect((analog.width, analog.height, analog.scale), (422, 514, 2.0));
      expect(analog.blankComplications, false);
      expect(analog.rawSnapshotKey, analogKey);
      expect(digital.rawSnapshotKey, digitalKey);
      expect(
        () => NativeFaceSnapshot.decode(
          fixture('analog'),
          expectedSnapshotKey: digitalKey,
        ),
        throwsFormatException,
      );
    },
  );

  test(
    'Snapshot bytes are immutable and ordinary Photos still reject keyed-archive UIDs',
    () {
      final bytes = fixture('analog');
      final snapshot = NativeFaceSnapshot.decode(
        bytes,
        expectedSnapshotKey: analogKey,
      );
      final hash = sha256.convert(snapshot.png).toString();
      bytes.fillRange(0, bytes.length, 0);
      snapshot.png.fillRange(0, snapshot.png.length, 0);
      expect(sha256.convert(snapshot.png).toString(), hash);
      expect(
        () => NativePhotosPlist.decode(fixture('analog')),
        throwsFormatException,
      );
      expect(
        () => NativeBinaryPlist.decode(fixture('analog')),
        throwsFormatException,
      );
      expect(
        NativeBinaryPlist.decode(fixture('analog'), allowUids: true),
        isA<Map>(),
      );
    },
  );

  test(
    'Invalid class, image UID, cyclic UID and missing image never become a preview',
    () {
      final bytes = fixture('analog');
      for (final invalid in [
        changeString(bytes, 'UIImage', 'Unknown'),
        changeReference(bytes, 3, 255),
        changeReference(bytes, 2, 1),
        changeReference(bytes, 2, 0),
      ]) {
        expect(
          () => NativeFaceSnapshot.decode(
            invalid,
            expectedSnapshotKey: analogKey,
          ),
          throwsFormatException,
        );
      }
    },
  );

  test(
    'Archive header/size, PNG dimensions and advertised pixel size are bounded',
    () {
      final bytes = fixture('analog');
      final dimensionMismatch = changeString(bytes, '{422, 514}', '{421, 514}');
      expect(
        () => NativeFaceSnapshot.decode(
          dimensionMismatch,
          expectedSnapshotKey: analogKey,
        ),
        throwsFormatException,
      );
      final data = NativeBinaryPlist.decode(bytes, allowUids: true) as Map;
      final objects = data['\$objects'] as List;
      final png = objects.whereType<Uint8List>().singleWhere(
        (v) => v.length == 66494,
      );
      var offset = -1;
      for (var i = 0; i < bytes.length - png.length; i++) {
        if (bytes[i] == 137 &&
            bytes[i + 1] == 80 &&
            bytes[i + 2] == 78 &&
            bytes[i + 3] == 71) {
          offset = i;
          break;
        }
      }
      expect(offset, greaterThan(0));
      final oversized = Uint8List.fromList(bytes);
      ByteData.sublistView(oversized).setUint32(offset + 16, 2049);
      for (final invalid in [
        Uint8List(40),
        bytes.sublist(0, bytes.length - 32),
        Uint8List(NativeFaceSnapshot.maxArchiveBytes + 1),
        oversized,
      ]) {
        expect(
          () => NativeFaceSnapshot.decode(
            invalid,
            expectedSnapshotKey: analogKey,
          ),
          throwsFormatException,
        );
      }
      expect(
        () => NativeFaceSnapshot.decode(bytes, expectedSnapshotKey: ''),
        throwsFormatException,
      );
    },
  );

  test(
    'Snapshot decoding uses embedded data without opening the archived cached-file URL',
    () {
      final bytes = fixture('analog');
      final original = NativeFaceSnapshot.decode(
        bytes,
        expectedSnapshotKey: analogKey,
      );
      // Keep binary offsets intact while redirecting the existing file URL to an
      // inaccessible root. Decoding must succeed solely from UIImageData.
      var changed = false;
      for (var i = 0; i < bytes.length - 5; i++) {
        if (String.fromCharCodes(bytes.sublist(i, i + 5)) == '/tmp/') {
          bytes.setAll(i, '/etc/'.codeUnits);
          changed = true;
          break;
        }
      }
      expect(changed, true);
      expect(
        NativeFaceSnapshot.decode(bytes, expectedSnapshotKey: analogKey).png,
        orderedEquals(original.png),
      );
    },
  );

  testWidgets(
    'Extracted actual native PNG decodes in Flutter at the bounded preview size',
    (tester) async {
      final snapshot = NativeFaceSnapshot.decode(
        fixture('digital'),
        expectedSnapshotKey: digitalKey,
      );
      await tester.pumpWidget(
        CupertinoApp(
          home: Center(
            child: Image.memory(snapshot.png, cacheWidth: 240, width: 120),
          ),
        ),
      );
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 60)),
      );
      await tester.pumpAndSettle();
      final image = tester.widget<RawImage>(find.byType(RawImage)).image!;
      expect(image.width, 240);
      expect(image.height, (240 * 514 / 422).round());
      expect(tester.takeException(), isNull);
    },
  );
}
