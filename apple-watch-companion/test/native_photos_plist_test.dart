import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_photos_plist.dart';

void main() {
  // Generated with Python plistlib (FMT_BINARY), independent of the Dart codec.
  final binary = base64.decode(
    'YnBsaXN0MDDVAQIDBAUGBwgJClRkYXRhVGRhdGVXaW50ZWdlclR0ZXh0VnZhbHVlc0MAAf8zQcg64YAAAAAT//////////hqAEgAZQBsAGwAbwAgA6kAINg83xmjCwwNCQgjP/QAAAAAAAAIExgdJSoxNT5HXGBhYgAAAAAAAAEBAAAAAAAAAA4AAAAAAAAAAAAAAAAAAABr',
  );
  test(
    'Binary metadata survives XML roundtrip including UTF16, data and dates',
    () {
      final value = NativePhotosPlist.decode(binary) as Map;
      expect(value['text'], 'Hello Ω 🌙');
      expect(value['integer'], -8);
      expect(value['data'], [0, 1, 255]);
      expect(value['date'], DateTime.utc(2026, 10, 7));
      expect(value['values'], [true, false, 1.25]);
      expect(NativePhotosPlist.decode(NativePhotosPlist.encode(value)), value);
    },
  );
  test('Malformed lists are rejected before any replacement is constructed', () {
    for (final bytes in [
      Uint8List(0),
      binary.sublist(0, 39),
      Uint8List(NativePhotosPlist.maxBytes + 1),
      Uint8List.fromList(
        utf8.encode(
          '<plist><dict><key>a</key><integer>1</integer><key>a</key><integer>2</integer></dict></plist>',
        ),
      ),
    ]) {
      expect(() => NativePhotosPlist.decode(bytes), throwsFormatException);
    }
    final bad = Uint8List.fromList(binary)..[binary.length - 25] = 0;
    expect(() => NativePhotosPlist.decode(bad), throwsFormatException);
    final offset = Uint8List.fromList(binary)..[binary.length - 1] = 255;
    expect(() => NativePhotosPlist.decode(offset), throwsFormatException);
    expect(
      () => NativePhotosPlist.encode({
        'date': DateTime.utc(2026, 10, 7, 0, 0, 0, 1),
      }),
      throwsFormatException,
    );
  });
}
