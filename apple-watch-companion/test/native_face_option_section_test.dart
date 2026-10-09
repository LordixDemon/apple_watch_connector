import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_edit_section.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_option_section.dart';

void main() {
  NativeFaceEditSection sectionFor(List<String> values) =>
      NativeFaceEditSection.fromJson(
        {'field': 'style', 'mode': 15, 'collectionType': 0, 'values': values},
        {'style': values},
      );

  Widget row(
    NativeFaceEditSection section,
    String selected, {
    String Function(String)? label,
    double scale = 1,
  }) => CupertinoApp(
    home: MediaQuery(
      data: MediaQueryData(textScaler: TextScaler.linear(scale)),
      child: CupertinoPageScaffold(
        child: Center(
          child: SizedBox(
            width: 240,
            child: NativeFaceOptionSection(
              section: section,
              title: 'Style',
              selected: selected,
              label: label ?? (value) => value,
              onSelected: (_) {},
            ),
          ),
        ),
      ),
    ),
  );

  testWidgets(
    'Native reveal centers initial/offscreen choices and preserves partial visibility',
    (tester) async {
      final values = List.generate(20, (index) => 'style-$index');
      final section = sectionFor(values);
      await tester.pumpWidget(row(section, values[8]));
      await tester.pumpAndSettle();
      final list = tester.widget<ListView>(find.byType(ListView));
      final position = list.controller!.position, extent = list.itemExtent!;
      expect(
        position.pixels,
        closeTo((8.5 * extent - position.viewportDimension / 2), .001),
      );

      final partial = 5 * extent - position.viewportDimension + extent / 2;
      list.controller!.jumpTo(partial);
      await tester.pumpWidget(row(section, values[5]));
      await tester.pumpAndSettle();
      expect(position.pixels, closeTo(partial, .001));

      await tester.pumpWidget(row(section, values[14]));
      await tester.pumpAndSettle();
      expect(
        position.pixels,
        closeTo((14.5 * extent - position.viewportDimension / 2), .001),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Replacing the native section reveals the same token at its new index',
    (tester) async {
      final values = List.generate(20, (index) => 'style-$index');
      await tester.pumpWidget(row(sectionFor(values), values[8]));
      await tester.pumpAndSettle();
      final reordered = [...values]..remove(values[8]);
      reordered.insert(15, values[8]);
      await tester.pumpWidget(row(sectionFor(reordered), values[8]));
      await tester.pumpAndSettle();
      final list = tester.widget<ListView>(find.byType(ListView));
      expect(
        list.controller!.offset,
        closeTo(
          15.5 * list.itemExtent! -
              list.controller!.position.viewportDimension / 2,
          .001,
        ),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Cached label geometry invalidates on language and text-size changes',
    (tester) async {
      final section = sectionFor(['one', 'two']);
      await tester.pumpWidget(row(section, 'one'));
      await tester.pumpAndSettle();
      final small = tester.getSize(find.byType(ListView)).height;
      await tester.pumpWidget(
        row(
          section,
          'two',
          label: (value) => 'A much longer translated native label for $value',
          scale: 2.4,
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.getSize(find.byType(ListView)).height, greaterThan(small));
      await tester.pumpWidget(row(section, 'two'));
      await tester.pumpAndSettle();
      expect(tester.getSize(find.byType(ListView)).height, small);
      expect(tester.takeException(), isNull);
    },
  );

  for (final vertical in [false, true]) {
    testWidgets(
      'Inline ${vertical ? "vertical" : "horizontal"} options select exact tokens and disable actions',
      (tester) async {
        final values = List.generate(
          vertical ? 3 : 24,
          (index) => 'native-$index',
        );
        final section = NativeFaceEditSection.fromJson(
          {
            'field': 'content',
            'mode': 12,
            'collectionType': vertical ? 2 : 0,
            'values': values,
          },
          {'content': values},
        );
        final selected = ValueNotifier(values.last);
        final enabled = ValueNotifier(true);
        final calls = <String>[];
        await tester.pumpWidget(
          CupertinoApp(
            home: ValueListenableBuilder(
              valueListenable: selected,
              builder: (_, token, _) => ValueListenableBuilder(
                valueListenable: enabled,
                builder: (_, active, _) => CupertinoPageScaffold(
                  child: Center(
                    child: SizedBox(
                      width: 240,
                      child: NativeFaceOptionSection(
                        section: section,
                        title: 'CONTENT',
                        selected: token,
                        label: (value) => 'Choice $value',
                        onSelected: active
                            ? (value) {
                                calls.add(value);
                                selected.value = value;
                              }
                            : null,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(
          find.text('CHOICE ${values.last.toUpperCase()}'),
          findsOneWidget,
        );
        selected.value = values.first;
        await tester.pumpAndSettle();
        await tester.tap(find.text('CHOICE ${values[1].toUpperCase()}'));
        await tester.pumpAndSettle();
        expect(calls, [values[1]]);
        expect(selected.value, values[1]);
        enabled.value = false;
        await tester.pumpAndSettle();
        await tester.tap(find.text('CHOICE ${values.first.toUpperCase()}'));
        expect(calls, [values[1]]);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        selected.dispose();
        enabled.dispose();
      },
    );
  }

  testWidgets(
    'Small viewport with large text keeps full native labels accessible',
    (tester) async {
      final values = ['one', 'two'];
      final section = NativeFaceEditSection.fromJson(
        {'field': 'style', 'mode': 15, 'collectionType': 0, 'values': values},
        {'style': values},
      );
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(
        CupertinoApp(
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(220, 640),
              textScaler: TextScaler.linear(2.4),
            ),
            child: CupertinoPageScaffold(
              child: Center(
                child: SizedBox(
                  width: 220,
                  child: NativeFaceOptionSection(
                    section: section,
                    title: 'STYLE',
                    selected: 'two',
                    label: (value) => 'Long native label $value',
                    onSelected: (_) {},
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.bySemanticsLabel('STYLE: Long native label two'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
      semantics.dispose();
    },
  );
}
