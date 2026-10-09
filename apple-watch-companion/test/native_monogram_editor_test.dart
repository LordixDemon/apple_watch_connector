import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_monogram_editor_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_monogram_section.dart';
import 'package:apple_watch_companion/services/native_face_monogram.dart';
import 'package:apple_watch_companion/widgets/ios_list_tile.dart';

void main() {
  testWidgets(
    'Text row belongs to enabled installed faces, while gallery keeps only its switch',
    (tester) async {
      final monogram = NativeFaceMonogram.fromJson(
        {
          'slot': 'monogram',
          'enabled': {'app': 'monogram'},
        },
        {'monogram'},
      );
      final configuration = <String, dynamic>{
        'complications': {
          'monogram': {'app': 'monogram'},
        },
      };
      Future<void> show(bool installed) => tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceMonogramSection(
            monogram: monogram,
            configuration: configuration,
            hasTextEditor: installed,
            text: 'AB',
            onEdit: () {},
            onChanged: (_) {},
          ),
        ),
      );
      await show(false);
      expect(find.byType(IosListTile), findsOneWidget);
      await show(true);
      expect(find.byType(IosListTile), findsNWidgets(2));
      expect(find.text('AB'), findsOneWidget);
      monogram.select(configuration, false);
      await show(true);
      expect(find.byType(IosListTile), findsOneWidget);
      expect(find.text('AB'), findsNothing);
    },
  );
  test(
    'Editing permits long ordinary text, preserves composition and rejects committed emoji',
    () {
      const formatter = NativeMonogramInputFormatter();
      const old = TextEditingValue(text: 'ABC');
      const long = TextEditingValue(text: 'abcdefgh');
      expect(formatter.formatEditUpdate(old, long), long);
      expect(
        formatter.formatEditUpdate(old, const TextEditingValue(text: '😀')),
        old,
      );
      const composing = TextEditingValue(
        text: '你好',
        composing: TextRange(start: 0, end: 2),
      );
      expect(formatter.formatEditUpdate(old, composing), composing);
      expect(
        formatter.formatEditUpdate(old, const TextEditingValue()).text,
        '',
      );
    },
  );
  testWidgets(
    'Done commits host-normalized text; empty input leaves the previous preference intact',
    (tester) async {
      var writes = 0, normalizes = 0;
      String input = 'abcdef';
      Future<void> open() async {
        await tester.pumpWidget(
          CupertinoApp(
            home: Builder(
              builder: (context) => CupertinoPageScaffold(
                child: CupertinoButton(
                  child: const Text('Edit'),
                  onPressed: () => Navigator.of(context).push(
                    CupertinoPageRoute<void>(
                      fullscreenDialog: true,
                      builder: (_) => NativeMonogramEditorScreen(
                        initialText: input,
                        canSubmit: () => true,
                        normalize: (value) async {
                          normalizes++;
                          expect(value, input);
                          return (text: 'ABCDE', valid: true);
                        },
                        submit: (value) async {
                          writes++;
                          expect(value, 'ABCDE');
                          return (status: 'QUEUED', requestId: null);
                        },
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('Edit'));
        await tester.pumpAndSettle();
      }

      await open();
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(writes, 1);
      expect(normalizes, 1);
      expect(find.byType(NativeMonogramEditorScreen), findsNothing);
      input = '';
      await open();
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(writes, 1);
      expect(normalizes, 1);
    },
  );
  testWidgets(
    'Invalid normalized result and changed target keep editor open without sending',
    (tester) async {
      var writes = 0, allowed = true;
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeMonogramEditorScreen(
            initialText: 'ßßß',
            canSubmit: () => allowed,
            normalize: (_) async => (text: 'SSSSSS', valid: false),
            submit: (_) async {
              writes++;
              return (status: 'QUEUED', requestId: null);
            },
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(writes, 0);
      expect(
        find.text('This text cannot be used as a monogram.'),
        findsOneWidget,
      );
      allowed = false;
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(writes, 0);
      expect(
        find.text('Connect your Apple Watch to edit its monogram.'),
        findsOneWidget,
      );
    },
  );
}
