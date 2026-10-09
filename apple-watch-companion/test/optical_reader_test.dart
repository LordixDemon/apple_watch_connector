import 'dart:typed_data';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/optical/optical_frame.dart';
import 'package:apple_watch_companion/services/optical/optical_reader.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final calls = <MethodCall>[];
  var recognized = false;
  var malformed = false;
  final frame = OpticalFrame.fromYuv420(
    32,
    32,
    OpticalPlane(Uint8List(256), 16, 1),
    OpticalPlane(Uint8List(256), 16, 1),
  );
  setUp(() {
    calls.clear();
    recognized = false;
    malformed = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'processOpticalFrame') {
            return {
              'frames': calls
                  .where((c) => c.method == 'processOpticalFrame')
                  .length,
              'recognized': recognized,
              if (recognized)
                'token': malformed
                    ? 'invalid'
                    : 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
              if (recognized) 'advertisedName': '12345EOT',
            };
          }
          return null;
        });
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  test(
    'starts one decoder, sends packed UV and treats empty frame as no recognition',
    () async {
      final reader = OpticalReader();
      expect(await reader.add(frame), isNull);
      expect(await reader.add(frame), isNull);
      expect(calls.map((c) => c.method), [
        'startOpticalDecoder',
        'processOpticalFrame',
        'processOpticalFrame',
      ]);
      expect(calls.first.arguments, {'width': 16, 'height': 16});
      expect((calls[1].arguments['uv'] as Uint8List).length, 512);
      expect(reader.frames, 2);
      await reader.close();
      await reader.close();
      expect(calls.where((c) => c.method == 'stopOpticalDecoder').length, 1);
    },
  );
  test(
    'recognition returns only a handle and public name without starting a pair',
    () async {
      recognized = true;
      final reader = OpticalReader();
      final result = await reader.add(frame);
      expect(result?.advertisedName, '12345EOT');
      expect(result?.token, 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee');
      expect(calls.any((c) => c.method == 'beginPairing'), isFalse);
      await reader.close();
    },
  );
  test(
    'rejects malformed recognition instead of manufacturing a candidate',
    () async {
      recognized = true;
      malformed = true;
      final reader = OpticalReader();
      await expectLater(reader.add(frame), throwsFormatException);
      await reader.close();
    },
  );
}
