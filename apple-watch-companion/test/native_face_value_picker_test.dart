import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_value_picker.dart';

void main() {
  testWidgets(
    'Previews build lazily, survive search and invalidate with a changed configuration',
    (tester) async {
      var previews = 0;
      Widget? preview(String value) {
        previews++;
        return const Icon(CupertinoIcons.clock);
      }

      final values = List.generate(2000, (index) => '$index');
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceValuePicker(
            title: 'Palette',
            selected: '0',
            values: values,
            label: (value) => 'Choice $value',
            preview: preview,
          ),
        ),
      );
      expect(previews, inExclusiveRange(0, 100));
      final initial = previews;
      await tester.enterText(find.byType(CupertinoSearchTextField), 'Choice 0');
      await tester.pump();
      expect(previews, initial);
      await tester.enterText(find.byType(CupertinoSearchTextField), '');
      await tester.pump();
      expect(previews, initial);
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceValuePicker(
            title: 'Palette',
            selected: '0',
            values: values,
            label: (value) => 'Choice $value',
            preview: (value) {
              previews++;
              return const Icon(CupertinoIcons.photo);
            },
          ),
        ),
      );
      expect(previews, greaterThan(initial));
      expect(find.byIcon(CupertinoIcons.clock), findsNothing);
    },
  );
  testWidgets(
    'Search indexes labels once, retains native values and refreshes changed inventory',
    (tester) async {
      var calls = 0;
      String label(String value) {
        calls++;
        return 'Native choice $value';
      }

      final values = List.generate(2000, (index) => '$index');
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceValuePicker(
            title: 'Options',
            selected: '1999',
            values: values,
            label: label,
          ),
        ),
      );
      expect(calls, 2000);
      for (final query in ['choice 1999', 'CHOICE 1998', '', 'choice 1999']) {
        await tester.enterText(find.byType(CupertinoSearchTextField), query);
        await tester.pump();
        expect(calls, 2000);
      }
      expect(find.text('Native choice 1999'), findsOneWidget);
      expect(find.byIcon(CupertinoIcons.check_mark), findsOneWidget);
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceValuePicker(
            title: 'Options',
            selected: '',
            values: const ['new'],
            label: label,
          ),
        ),
      );
      await tester.enterText(find.byType(CupertinoSearchTextField), '');
      await tester.pump();
      expect(calls, 2001);
      expect(find.text('Native choice new'), findsOneWidget);
      expect(find.text('Native choice 1999'), findsNothing);
    },
  );
}
