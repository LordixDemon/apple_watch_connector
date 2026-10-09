import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_intent_identity.dart';
import 'package:apple_watch_companion/services/native_binary_plist.dart';
import 'package:apple_watch_companion/services/native_complication_presentation.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  Uint8List fixture(String name) =>
      File('test/fixtures/native-intent-43-$name.bplist').readAsBytesSync();
  NativeIntentIdentity intent(String name) =>
      NativeIntentIdentity.parse(base64Encode(fixture(name)))!;
  test(
    'Re-archived settings retain identity across UUID, UID allocation and schema metadata changes',
    () {
      expect(intent('original').same(intent('rearchived')), true);
      expect(intent('rearchived').same(intent('original')), true);
      for (final changed in ['duration', 'schema', 'future', 'identifier']) {
        expect(
          intent('original').same(intent(changed)),
          false,
          reason: changed,
        );
      }
    },
  );
  test(
    'Malformed and oversized input cannot supply a presentation identity',
    () {
      expect(NativeIntentIdentity.parse('not base64'), isNull);
      expect(NativeIntentIdentity.parse(base64Encode(Uint8List(10))), isNull);
      expect(NativeIntentIdentity.parse('A' * 131073), isNull);
      expect(NativeIntentIdentity.parse(null), isNull);
    },
  );
  test(
    '128-bit integer encoding is explicit and lossless; ordinary readers remain strict',
    () {
      final data =
          NativeBinaryPlist.decode(fixture('original'), allowUids: true) as Map;
      final objects = data[r'$objects'] as List;
      final root =
          objects[(data[r'$top']['root'] as NativePlistUid).value] as Map;
      final backing =
          objects[(root['backingStore'] as NativePlistUid).value] as Map;
      final nested = backing['codableDescriptionBytes'] as Uint8List;
      expect(
        () => NativeBinaryPlist.decode(nested, allowUids: true),
        throwsFormatException,
      );
      final wide =
          NativeBinaryPlist.decode(
                nested,
                allowUids: true,
                allowWideIntegers: true,
              )
              as Map;
      final table = wide[r'$objects'] as List;
      final schema =
          table[(wide[r'$top']['root'] as NativePlistUid).value] as Map;
      expect(
        table[(schema['versioningHash'] as NativePlistUid).value],
        (BigInt.one << 63) + BigInt.from(17),
      );
    },
  );
  test(
    'Presentation uses matched parameters and isolates unknown fields and provider identities',
    () {
      Map<String, dynamic> descriptor(String name) => {
        'kind': 'research.timer',
        'containerBundleIdentifier': 'research.app',
        'extensionBundleIdentifier': 'research.extension',
        'intent': base64Encode(fixture(name)),
      };
      final catalog = <String, dynamic>{
        'WidgetComplications:research.app': {
          '15': {'descriptor': descriptor('rearchived'), 'name': '15 min'},
          '1': {'descriptor': descriptor('duration'), 'name': '1 min'},
        },
      };
      final names = NativeComplicationPresentation(catalog);
      final value = {'descriptor': descriptor('original')};
      final before = jsonEncode(value);
      expect(names.title(value), '15 min');
      expect(names.title({'descriptor': descriptor('duration')}), '1 min');
      expect(
        names.title({'descriptor': descriptor('schema')}),
        'research.timer',
      );
      expect(
        names.title({
          'descriptor': {...descriptor('original'), 'future': 'unknown'},
        }),
        'research.timer',
      );
      expect(
        names.title({
          'descriptor': {
            ...descriptor('original'),
            'extensionBundleIdentifier': 'different',
          },
        }),
        'research.timer',
      );
      expect(jsonEncode(value), before);
    },
  );
}
