import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_edit_metadata.dart';

void main() {
  final first = DateTime.utc(2026, 10, 8, 12, 1, 2, 345, 678);
  final seconds = first.microsecondsSinceEpoch / Duration.microsecondsPerSecond;
  test(
    'An edit marks date, companion count and state once per detail controller, retaining opaque metrics',
    () {
      var now = first;
      final original = <String, dynamic>{
        'origin': 9,
        'editedState': 1,
        'dateCreated': 123.456,
        'numberOfGizmoEdits': 4,
        'numberOfCompanionEdits': 7,
        'future': {
          'keep': ['opaque'],
        },
      };
      final configuration = <String, dynamic>{
        'face type': 'california',
        'metrics': original,
      };
      final edit = NativeFaceEditMetadata(now: () => now);
      edit.markEdited(configuration);
      expect(configuration['metrics'], {
        ...original,
        'editedState': 2,
        'numberOfCompanionEdits': 8,
        'dateLastEdited': seconds,
      });
      expect(original['numberOfCompanionEdits'], 7);
      now = first.add(const Duration(minutes: 1));
      edit.markEdited(configuration);
      expect(configuration['metrics']['numberOfCompanionEdits'], 8);
      expect(configuration['metrics']['dateLastEdited'], seconds);
      NativeFaceEditMetadata(now: () => now).markEdited(configuration);
      expect(configuration['metrics']['numberOfCompanionEdits'], 9);
      expect(configuration['metrics']['dateLastEdited'], seconds + 60);
    },
  );

  test(
    'Absent native metrics initialize only owned edit fields; nonnumeric counters and future edited states survive',
    () {
      final configuration = <String, dynamic>{'face type': 'california'};
      NativeFaceEditMetadata(now: () => first).markEdited(configuration);
      expect(configuration['metrics'], {
        'dateLastEdited': seconds,
        'numberOfCompanionEdits': 1,
        'editedState': 2,
      });
      for (final counter in [
        'opaque',
        null,
        {'unknown': 1},
      ]) {
        final future = <String, dynamic>{
          'metrics': <String, dynamic>{
            'numberOfCompanionEdits': counter,
            'editedState': 3,
            'dateCreated': 12,
          },
        };
        NativeFaceEditMetadata(now: () => first).markEdited(future);
        expect(future['metrics']['numberOfCompanionEdits'], counter);
        expect(future['metrics']['editedState'], 3);
        expect(future['metrics']['dateCreated'], 12);
      }
      final invalid = <String, dynamic>{
        'metrics': ['opaque'],
      };
      final session = NativeFaceEditMetadata(now: () => first);
      expect(() => session.markEdited(invalid), throwsFormatException);
      invalid.remove('metrics');
      session.markEdited(invalid);
      expect(invalid['metrics']['numberOfCompanionEdits'], 1);
    },
  );

  test(
    'NSNumber counters truncate like integerValue, and booleans are numeric in the native model',
    () {
      for (final entry in {2.75: 3, true: 2, false: 1}.entries) {
        final config = <String, dynamic>{
          'metrics': <String, dynamic>{'numberOfCompanionEdits': entry.key},
        };
        NativeFaceEditMetadata(now: () => first).markEdited(config);
        expect(config['metrics']['numberOfCompanionEdits'], entry.value);
      }
    },
  );

  test(
    'Unedited gallery Add assigns origin6, state1 and a fresh creation date on a copy',
    () {
      final draft = <String, dynamic>{
        'face type': 'california',
        'customization': {'style': 'round'},
      };
      final before = jsonEncode(draft);
      final added = NativeFaceEditMetadata.forGalleryAddition(
        draft,
        now: first,
      );
      expect(added['metrics'], {
        'origin': 6,
        'dateCreated': seconds,
        'editedState': 1,
      });
      expect(jsonEncode(draft), before);
      final next = NativeFaceEditMetadata.forGalleryAddition(
        draft,
        now: first.add(const Duration(seconds: 1)),
      );
      expect(next['metrics']['dateCreated'], seconds + 1);
      expect(next['metrics'].containsKey('numberOfCompanionEdits'), false);
    },
  );

  test(
    'Shared Add supplies origin12 only for native zero and preserves import history',
    () {
      for (final origin in [null, 0, false, .25, 6, 8, 12, true, 'future']) {
        final draft = <String, dynamic>{
          'face type': 'california',
          'metrics': <String, dynamic>{
            'origin': ?origin,
            'editedState': 2,
            'dateCreated': 10,
            'dateLastEdited': 20.5,
            'numberOfCompanionEdits': 3,
            'numberOfGizmoEdits': 4,
            'future': {'keep': true},
          },
        };
        final before = jsonEncode(draft);
        final added = NativeFaceEditMetadata.forSharedAddition(
          draft,
          now: first,
        );
        expect(
          added['metrics']['origin'],
          [null, 0, false, .25].contains(origin) ? 12 : origin,
        );
        expect(added['metrics']['dateCreated'], 20.5);
        expect(added['metrics']['dateLastEdited'], 20.5);
        expect(added['metrics']['editedState'], 2);
        expect(added['metrics']['numberOfCompanionEdits'], 3);
        expect(added['metrics']['numberOfGizmoEdits'], 4);
        expect(added['metrics']['future'], {'keep': true});
        expect(jsonEncode(draft), before);
      }
      final plain = NativeFaceEditMetadata.forSharedAddition({}, now: first);
      expect(plain['metrics'], {
        'origin': 12,
        'dateCreated': seconds,
        'editedState': 1,
      });
    },
  );

  test(
    'Edited gallery Add uses its first edit date, preserving metrics; external-assets origin is distinct',
    () {
      final draft = <String, dynamic>{
        'metrics': <String, dynamic>{
          'editedState': 1,
          'dateCreated': 10,
          'numberOfGizmoEdits': 2,
          'future': 'opaque',
        },
      };
      NativeFaceEditMetadata(now: () => first).markEdited(draft);
      final before = jsonEncode(draft);
      final added = NativeFaceEditMetadata.forGalleryAddition(
        draft,
        now: first.add(const Duration(days: 1)),
      );
      expect(added['metrics']['dateCreated'], seconds);
      expect(added['metrics']['dateLastEdited'], seconds);
      expect(added['metrics']['numberOfCompanionEdits'], 1);
      expect(added['metrics']['numberOfGizmoEdits'], 2);
      expect(added['metrics']['editedState'], 2);
      expect(added['metrics']['future'], 'opaque');
      expect(jsonEncode(draft), before);
      expect(
        NativeFaceEditMetadata.forGalleryAddition(
          draft,
          externalAssets: true,
        )['metrics']['origin'],
        8,
      );
      final future = <String, dynamic>{
        'metrics': <String, dynamic>{'editedState': 3},
      };
      expect(
        NativeFaceEditMetadata.forGalleryAddition(
          future,
          now: first,
        )['metrics']['editedState'],
        3,
      );
    },
  );
}
