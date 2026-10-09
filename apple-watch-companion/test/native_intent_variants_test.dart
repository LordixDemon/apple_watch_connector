import 'dart:convert';
import 'dart:io';
import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_intent_identity.dart';
import 'package:apple_watch_companion/services/native_intent_variants.dart';
import 'package:apple_watch_companion/services/native_complication_choices.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_complication_settings_screen.dart';
import 'package:apple_watch_companion/widgets/ios_list_tile.dart';

String fixture(String name) => base64Encode(
  File('test/fixtures/native_app_intent/$name.bplist').readAsBytesSync(),
);
Map<String, dynamic> value(
  String name, {
  String provider = 'fixture.provider',
}) => {
  'type': 56,
  'app': provider,
  'opaqueSlot': {
    'keep': [1, 2],
  },
  'descriptor': {
    'containerBundleIdentifier': provider,
    'extensionBundleIdentifier': '$provider.extension',
    'kind': 'FixtureWidget',
    'intent': fixture(name),
    'opaqueDescriptor': 'keep',
  },
};
List<NativeComplicationChoice> grid() => [
  for (final period in ['week', 'month'])
    for (final category in ['walk', 'run'])
      (
        title:
            '${period == 'week' ? 'Weekly' : 'Monthly'} ${category == 'walk' ? 'Walking' : 'Running'} Distance',
        value: value('$period-$category'),
      ),
];

void main() {
  for (final scale in [1.0, 2.0]) {
    testWidgets('Native preset settings select both axes at 320dp / $scale', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final original = value('reordered-incumbent');
      final model = NativeIntentVariantIndex().bind(
        source: Object(),
        slot: 'slot',
        current: original,
        choices: grid,
      )!;
      Map<String, dynamic>? returned;
      await tester.pumpWidget(
        CupertinoApp(
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
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
                          variants: model,
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
      await tester.tap(find.text('Period'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Monthly'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Category'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Running'));
      await tester.pumpAndSettle();
      expect(find.text('Monthly'), findsOneWidget);
      expect(find.text('Running'), findsOneWidget);
      await tester.tap(find.text('SAVE'));
      await tester.pumpAndSettle();
      expect(returned, model.update({'period': 'month', 'category': 'run'}));
      expect(original, value('reordered-incumbent'));
      expect(tester.takeException(), isNull);
    });
  }
  testWidgets(
    'Sparse native inventory disables an unavailable dependent field',
    (tester) async {
      final candidates = grid()..removeLast();
      final original = value('week-walk');
      final model = NativeIntentVariantIndex().bind(
        source: Object(),
        slot: 'slot',
        current: original,
        choices: () => candidates,
      )!;
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeComplicationSettingsScreen(
            title: 'Settings',
            value: original,
            variants: model,
          ),
        ),
      );
      await tester.tap(find.text('Period'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Monthly'));
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<IosListTile>(find.widgetWithText(IosListTile, 'Category'))
            .onTap,
        isNull,
      );
      expect(find.text('Monthly Walking Distance'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  test(
    'AppIntent parameters compare independent UID/order with strict routing',
    () {
      final a = NativeIntentIdentity.parse(fixture('week-walk'))!;
      final b = NativeIntentIdentity.parse(fixture('reordered-incumbent'))!;
      expect(a.same(b), isTrue);
      expect(a.appStringParameters, {'period': 'week', 'category': 'walk'});
      expect(
        a.sameAppIntentRouting(
          NativeIntentIdentity.parse(fixture('month-run'))!,
        ),
        isTrue,
      );
      for (final other in ['different-routing', 'unknown-metadata']) {
        expect(
          a.sameAppIntentRouting(NativeIntentIdentity.parse(fixture(other))!),
          isFalse,
        );
      }
      for (final invalid in ['non-string', 'duplicate-key']) {
        expect(
          NativeIntentIdentity.parse(fixture(invalid))!.appStringParameters,
          isNull,
        );
      }
    },
  );
  test(
    'Independent fields select only complete native presets and preserve opaque wrapper',
    () {
      final source = Object();
      final current = value('reordered-incumbent');
      final model = NativeIntentVariantIndex().bind(
        source: source,
        slot: 'test-slot',
        current: current,
        choices: grid,
      )!;
      expect(model.fields, ['category', 'period']);
      expect(model.options('period', model.initial), [
        (value: 'week', label: 'Weekly'),
        (value: 'month', label: 'Monthly'),
      ]);
      expect(model.options('category', model.initial), [
        (value: 'walk', label: 'Walking'),
        (value: 'run', label: 'Running'),
      ]);
      final unchanged = model.update(model.initial);
      expect(unchanged, current);
      expect(unchanged['descriptor']['intent'], fixture('reordered-incumbent'));
      final next = {...model.initial, 'period': 'month', 'category': 'run'};
      final edited = model.update(next);
      expect(edited['descriptor']['intent'], fixture('month-run'));
      final outside = jsonDecode(jsonEncode(edited)) as Map<String, dynamic>;
      outside['descriptor']['intent'] = current['descriptor']['intent'];
      expect(outside, current);
      expect(current, value('reordered-incumbent'));
      expect(
        () => model.update({...next, 'category': 'invented'}),
        throwsFormatException,
      );
      expect(
        () => model.update({...next, 'future': 'ignored'}),
        throwsFormatException,
      );
    },
  );
  test(
    'Sparse inventory restricts dependent choices without inventing combinations',
    () {
      final candidates = grid()
        ..removeWhere(
          (c) => c.value['descriptor']['intent'] == fixture('month-run'),
        );
      final model = NativeIntentVariantIndex().bind(
        source: Object(),
        slot: 'test-slot',
        current: value('week-walk'),
        choices: () => candidates,
      )!;
      final monthly = {...model.initial, 'period': 'month'};
      expect(model.options('category', monthly), [
        (value: 'walk', label: 'Monthly Walking Distance'),
      ]);
      expect(model.accepts({...monthly, 'category': 'run'}), isFalse);
      expect(
        NativeIntentVariantIndex().bind(
          source: Object(),
          slot: 'slot',
          current: value('week-walk'),
          choices: () => [grid().first, grid().last],
        ),
        isNull,
      );
    },
  );
  test(
    'Provider, routing, unknown fields and replacement inventories stay isolated',
    () {
      final index = NativeIntentVariantIndex();
      var reads = 0;
      final source = Object();
      final current = value('week-walk');
      List<NativeComplicationChoice> choices() {
        reads++;
        return grid();
      }

      expect(
        index.bind(
          source: source,
          slot: 's',
          current: current,
          choices: choices,
        ),
        isNotNull,
      );
      expect(
        index.bind(
          source: source,
          slot: 's',
          current: current,
          choices: choices,
        ),
        isNotNull,
      );
      expect(reads, 1);
      expect(
        index.bind(
          source: source,
          slot: 's',
          current: value('week-walk', provider: 'other'),
          choices: choices,
        ),
        isNull,
      );
      expect(
        index.bind(
          source: Object(),
          slot: 's',
          current: current,
          choices: () => [],
        ),
        isNull,
      );
      expect(
        index.bind(
          source: source,
          slot: 's',
          current: value('different-routing'),
          choices: grid,
        ),
        isNull,
      );
      expect(
        index.bind(
          source: source,
          slot: 's',
          current: value('unknown-metadata'),
          choices: grid,
        ),
        isNull,
      );
    },
  );
}
