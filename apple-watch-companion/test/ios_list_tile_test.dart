import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/widgets/ios_list_tile.dart';
import 'package:apple_watch_companion/theme/ios_colors.dart';

void main() {
  testWidgets('Unavailable native slot is muted and cannot invoke its action', (
    tester,
  ) async {
    var taps = 0;
    await tester.pumpWidget(
      CupertinoApp(
        home: Center(
          child: IosListTile(
            title: 'Sub-dial Top',
            trailingText: 'Stored complication',
            enabled: false,
            onTap: () => taps++,
          ),
        ),
      ),
    );
    expect(find.byType(CupertinoButton), findsNothing);
    expect(
      tester.widget<Text>(find.text('Sub-dial Top')).style!.color,
      IosColors.tertiaryLabel,
    );
    expect(
      tester.widget<Text>(find.text('Stored complication')).style!.color,
      IosColors.tertiaryLabel,
    );
    await tester.tap(find.text('Sub-dial Top'));
    expect(taps, 0);
  });
  testWidgets(
    'Interactive rows use Cupertino press behavior; read-only rows have no button',
    (tester) async {
      var taps = 0;
      await tester.pumpWidget(
        CupertinoApp(
          home: Center(
            child: SizedBox(
              width: 320,
              child: IosListTile(title: 'Native option', onTap: () => taps++),
            ),
          ),
        ),
      );
      expect(find.byType(CupertinoButton), findsOneWidget);
      await tester.tap(find.text('Native option'));
      await tester.pumpAndSettle();
      expect(taps, 1);
      await tester.pumpWidget(
        const CupertinoApp(
          home: Center(
            child: SizedBox(
              width: 320,
              child: IosListTile(title: 'Native option'),
            ),
          ),
        ),
      );
      expect(find.byType(CupertinoButton), findsNothing);
      await tester.tap(find.text('Native option'));
      expect(taps, 1);
    },
  );
}
