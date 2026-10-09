import 'dart:convert';
import 'dart:io';
import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_binary_plist.dart';
import 'package:apple_watch_companion/services/native_intent_parameters.dart';
import 'package:apple_watch_companion/services/native_complication_presentation.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_complication_settings_screen.dart';

String fixture(String name) => base64Encode(
  File('test/fixtures/native-parameters-46-$name.bplist').readAsBytesSync(),
);
Map<String, dynamic> value(String intent) => {
  'type': 56,
  'unknownWrapper': {'keep': 'original'},
  'descriptor': {
    'containerBundleIdentifier': 'research.app',
    'extensionBundleIdentifier': 'research.widget',
    'kind': 'ResearchIntervalWidget',
    'intent': intent,
    'unknownDescriptor': true,
  },
};

void main() {
  for (final scale in [1.0, 2.0]) {
    testWidgets('Parameter settings fit 320dp at text scale $scale', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final source = fixture('bounded');
      await tester.pumpWidget(
        CupertinoApp(
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
          home: NativeComplicationSettingsScreen(
            title: 'Settings for Bottom Left',
            value: value(source),
            parameters: NativeIntentParameters.parse(source)!,
          ),
        ),
      );
      await tester.enterText(find.byType(CupertinoTextField), 'Infinity');
      await tester.pumpAndSettle();
      expect(
        find.text('Enter a finite duration within its supported range.'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    });
  }
  test('Schema supplies field, name and title; only duration bytes change', () {
    for (final name in ['scalar', 'alternate-field']) {
      final source = fixture(name);
      final parameters = NativeIntentParameters.parse(source)!;
      expect(parameters.durations.single.name, 'researchInterval');
      expect(parameters.durations.single.title, 'Research interval');
      expect(parameters.durations.single.seconds, 900);
      expect(parameters.update({'researchInterval': 900}), source);
      final alternateEncoding = source.replaceAll('+', '-').replaceAll('/', '_');
      expect(
        NativeIntentParameters.parse(
          alternateEncoding,
        )!.update({'researchInterval': 900}),
        alternateEncoding,
      );
      final edited = parameters.update({'researchInterval': 1234.5});
      expect(
        NativeIntentParameters.parse(edited)!.durations.single.seconds,
        1234.5,
      );
      final a = base64Decode(source), b = base64Decode(edited);
      expect(a.length, b.length);
      final changed = [
        for (var i = 0; i < a.length; i++)
          if (a[i] != b[i]) i,
      ];
      expect(changed, isNotEmpty);
      expect(changed.length, lessThanOrEqualTo(8));
      expect(changed.last - changed.first, lessThan(8));
      // Offset inspection is optional, and points into the original buffer.
      final document = NativeBinaryPlist.inspect(
        a,
        allowUids: true,
        allowWideIntegers: true,
      );
      for (final entry in document.dataOffsets.entries) {
        expect(
          a.sublist(entry.value, entry.value + entry.key.length),
          entry.key,
        );
      }
    }
  });

  test(
    'Ambiguous, aliased, corrupt and out-of-range parameters are refused',
    () {
      for (final name in ['duplicate', 'nan', 'alias']) {
        expect(NativeIntentParameters.parse(fixture(name)), isNull);
      }
      expect(NativeIntentParameters.parse('not base64'), isNull);
      expect(NativeIntentParameters.parse('A' * 131073), isNull);
      final parameters = NativeIntentParameters.parse(fixture('bounded'))!;
      expect(parameters.durations.single.maximum, 1200);
      expect(
        () => parameters.update({'researchInterval': 1201}),
        throwsFormatException,
      );
      expect(
        () => parameters.update({'researchInterval': -1}),
        throwsFormatException,
      );
      expect(
        () => parameters.update({'researchInterval': double.infinity}),
        throwsFormatException,
      );
      expect(
        () => parameters.update({'unrecognized': 10}),
        throwsFormatException,
      );
      expect(
        NativeIntentParameters.parse(
          parameters.update({'researchInterval': 0}),
        )!.durations.single.seconds,
        0,
      );
    },
  );

  test(
    'Custom duration titles override stale observed-preset display hints',
    () {
      final source = fixture('scalar');
      final original = value(source);
      final edited = value(
        NativeIntentParameters.parse(source)!.update({'researchInterval': 70}),
      );
      final names = NativeComplicationPresentation({
        'WidgetComplications:research.app': {
          'preset': {
            'name': '15 min',
            'descriptor': original['descriptor'],
            'observedSlots': ['face:bottom'],
          },
        },
      });
      expect(names.title(original, faceId: 'face', slot: 'bottom'), '15 min');
      expect(
        names.title(edited, faceId: 'face', slot: 'bottom'),
        'Research interval: 70 s',
      );
      expect((original['descriptor'] as Map)['intent'], source);
      final index = NativeIntentParameterIndex();
      expect(index.read(source), same(index.read(source)));
    },
  );

  testWidgets(
    'Settings saves a local copy, validates input and never sends a command',
    (tester) async {
      final original = value(fixture('bounded'));
      final originalJson = jsonEncode(original);
      Map<String, dynamic>? returned;
      await tester.pumpWidget(
        CupertinoApp(
          home: Builder(
            builder: (context) => CupertinoButton(
              child: const Text('Open'),
              onPressed: () async {
                returned = await Navigator.of(context)
                    .push<Map<String, dynamic>>(
                      CupertinoPageRoute(
                        builder: (_) => NativeComplicationSettingsScreen(
                          title: 'Complication settings',
                          value: original,
                          parameters: NativeIntentParameters.parse(
                            original['descriptor']['intent'],
                          )!,
                        ),
                      ),
                    );
              },
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      expect(find.text('Research interval (seconds)'), findsOneWidget);
      await tester.enterText(find.byType(CupertinoTextField), 'NaN');
      await tester.pump();
      expect(
        tester
            .widget<CupertinoButton>(
              find.widgetWithText(CupertinoButton, 'SAVE'),
            )
            .onPressed,
        isNull,
      );
      await tester.enterText(find.byType(CupertinoTextField), '1201');
      await tester.pump();
      expect(
        tester
            .widget<CupertinoButton>(
              find.widgetWithText(CupertinoButton, 'SAVE'),
            )
            .onPressed,
        isNull,
      );
      await tester.enterText(find.byType(CupertinoTextField), '70');
      await tester.pump();
      await tester.tap(find.text('SAVE'));
      await tester.pumpAndSettle();
      expect(returned, isNotNull);
      expect(jsonEncode(original), originalJson);
      expect(returned!['unknownWrapper'], original['unknownWrapper']);
      expect(returned!['descriptor']['unknownDescriptor'], true);
      expect(
        NativeIntentParameters.parse(
          returned!['descriptor']['intent'],
        )!.durations.single.seconds,
        70,
      );
      expect(tester.takeException(), isNull);
    },
  );
}
