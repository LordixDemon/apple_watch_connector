import 'dart:convert';
import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_monogram_text_normalizer.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/text');
  const normalizer = NativeMonogramTextNormalizer();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'Native locale/composed-range corpus retains order and validates the final value',
    () async {
      final corpus =
          jsonDecode(
                File(
                  'test/fixtures/native_faces/monogram_normalization.json',
                ).readAsStringSync(),
              )
              as Map;
      for (final row in corpus['cases'] as List) {
        messenger.setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'normalizeMonogram');
          expect(call.arguments, {'text': row['input']});
          return row['normalized'];
        });
        final value = await normalizer.normalize(row['input'] as String);
        expect(value.text, row['normalized']);
        expect(value.valid, row['valid']);
      }
    },
  );

  test(
    'Malformed text and oversized input never cross the host boundary',
    () async {
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => fail('No host call expected'),
      );
      for (final input in [
        String.fromCharCode(0xd800),
        String.fromCharCode(0xdc00),
        'a' * 4097,
      ]) {
        await expectLater(normalizer.normalize(input), throwsFormatException);
      }
    },
  );

  test(
    'Unavailable or malformed host replies cannot imply a successful edit',
    () async {
      for (final reply in <Object?>[
        null,
        false,
        {'text': 'AB'},
        'a' * 12289,
      ]) {
        messenger.setMockMethodCallHandler(channel, (_) async => reply);
        await expectLater(normalizer.normalize('ab'), throwsFormatException);
      }
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => throw MissingPluginException(),
      );
      await expectLater(
        normalizer.normalize('i'),
        throwsA(isA<MissingPluginException>()),
      );
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => throw PlatformException(code: 'INVALID_TEXT'),
      );
      await expectLater(
        normalizer.normalize('i'),
        throwsA(isA<PlatformException>()),
      );
    },
  );
}
