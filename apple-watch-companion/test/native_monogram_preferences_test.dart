import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/models/native_monogram_preferences.dart';
import 'package:apple_watch_companion/services/native_monogram_text_rules.dart';

void main() {
  test('All native emoji-set scalar observations match the Dart validator', () {
    final document =
        jsonDecode(
              File(
                'test/fixtures/native_faces/monogram_rules.json',
              ).readAsStringSync(),
            )
            as Map;
    final ranges = (document['emojiRanges'] as List).cast<List>();
    var interval = 0, checked = 0;
    for (var scalar = 0; scalar < 0x110000; scalar++) {
      if (scalar >= 0xd800 && scalar <= 0xdfff) continue;
      while (interval < ranges.length && scalar > ranges[interval][1]) {
        interval++;
      }
      final emoji = interval < ranges.length && scalar >= ranges[interval][0];
      if (NativeMonogramTextRules.valid(String.fromCharCode(scalar)) == emoji) {
        fail('Native scalar mismatch: $scalar');
      }
      checked++;
    }
    expect(checked, document['checked']);
    for (final invalid in [
      '',
      'ABCDEF',
      'ééé',
      '1️⃣',
      '🇺🇦',
      String.fromCharCode(0xd800),
    ]) {
      expect(NativeMonogramTextRules.valid(invalid), false);
    }
    for (final valid in ['ABCDE', 'É', '你好', '𐐀', 'AB CD', 'é']) {
      expect(NativeMonogramTextRules.valid(valid), true);
    }
  });

  test(
    'Only exact target observations publish custom text; deletion is distinct',
    () {
      const pair = '11111111-2222-3333-4444-555555555555';
      const epoch = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
      const time = 1800000000000;
      final report = <String, dynamic>{
        'connected': true,
        'bridgeAvailable': true,
        'observedAt': time,
        'faceCollectionPair': pair,
        'faceCollectionEpoch': epoch,
        'monogramPreferencePair': pair,
        'monogramPreferenceEpoch': epoch,
        'monogramPreferenceText': 'É',
        'monogramPreferenceObservedAt': time,
        'monogramPreferenceSourceTimestamp': time / 1000 - 978307200,
      };
      final received = NativeMonogramPreferences.fromBridge(report);
      expect(received.received, true);
      expect(received.text, 'É');
      final removed = NativeMonogramPreferences.fromBridge(
        {...report}..remove('monogramPreferenceText'),
      );
      expect(removed.received, true);
      expect(removed.text, isNull);
      for (final change in <String, Object?>{
        'connected': false,
        'monogramPreferencePair': epoch,
        'monogramPreferenceEpoch': pair,
        'monogramPreferenceObservedAt': 0,
        'monogramPreferenceSourceTimestamp': double.nan,
        'monogramPreferenceText': false,
      }.entries) {
        expect(
          NativeMonogramPreferences.fromBridge({
            ...report,
            change.key: change.value,
          }).received,
          false,
        );
      }
      expect(const NativeMonogramPreferences.unknown().received, false);
    },
  );
}
