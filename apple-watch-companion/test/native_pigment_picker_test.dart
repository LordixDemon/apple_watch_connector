import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_pigment_favorites.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_pigment_section.dart';
import 'package:apple_watch_companion/controllers/native_pigment_sync_controller.dart';
import 'package:apple_watch_companion/models/native_pigment_preferences.dart';

void main() {
  testWidgets(
    'Native color modal covers the tab navigator and Cancel returns without writing',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final section = profiles!
          .firstWhere((p) => p.family == 'type:california')
          .pigmentSection('color')!;
      var writes = 0;
      final favorites = NativePigmentFavorites(
        persist: (_) async {
          writes++;
        },
      );
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoTabScaffold(
            tabBar: CupertinoTabBar(
              items: const [
                BottomNavigationBarItem(
                  icon: Icon(CupertinoIcons.clock),
                  label: 'My Watch',
                ),
                BottomNavigationBarItem(
                  icon: Icon(CupertinoIcons.square_grid_2x2),
                  label: 'Face Gallery',
                ),
              ],
            ),
            tabBuilder: (_, _) => CupertinoTabView(
              builder: (_) => CupertinoPageScaffold(
                child: NativeFacePigmentSectionView(
                  section: section,
                  title: 'COLOR',
                  selected: 'standard.orange',
                  label: (s) => s,
                  favorites: favorites,
                  canCommit: () => true,
                  onSelected: (_) {},
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final position = tester
          .state<ScrollableState>(find.byType(Scrollable).first)
          .position;
      position.jumpTo(position.maxScrollExtent);
      await tester.pumpAndSettle();
      await tester.tap(find.bySemanticsLabel('Add Colors'));
      await tester.pumpAndSettle();
      expect(find.byType(NativeFacePigmentPicker), findsOneWidget);
      expect(find.byType(CupertinoTabBar), findsNothing);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(find.byType(NativeFacePigmentPicker), findsNothing);
      expect(find.byType(CupertinoTabBar), findsOneWidget);
      expect(writes, 0);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      semantics.dispose();
      favorites.dispose();
    },
  );
  testWidgets(
    'Production Done submits a pair-scoped delta; readback removes the waiting message',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final section = profiles!
          .firstWhere((p) => p.family == 'type:california')
          .pigmentSection('color')!;
      const pair = '10000000-0000-0000-0000-000000000001';
      const epoch = '20000000-0000-0000-0000-000000000001';
      var now = 1791253000000;
      NativePigmentPreferences observed(List<String> names, double source) =>
          NativePigmentPreferences.fromBridge({
            'connected': true,
            'bridgeAvailable': true,
            'faceCollectionPair': pair,
            'faceCollectionEpoch': epoch,
            'pigmentPreferencePair': pair,
            'pigmentPreferenceEpoch': epoch,
            'pigmentPreferenceNames': names,
            'pigmentPreferenceSourceTimestamp': source,
            'pigmentPreferenceObservedAt': now,
            'observedAt': now,
          });
      final changes = <Map<String, bool>>[];
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => now,
        send: (delta, {required baseline}) async {
          expect(baseline.pair, pair);
          changes.add(delta);
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(
        pair,
        observed(['standard.orange', 'zeus.unknown'], 812945799.0),
      );
      final favorites = NativePigmentFavorites.synchronized(sync);
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: NativeFacePigmentSectionView(
              section: section,
              title: 'COLOR',
              selected: 'standard.orange',
              label: (token) => token,
              favorites: favorites,
              canCommit: () => true,
              onSelected: (_) {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final scroll = tester.state<ScrollableState>(
        find.byType(Scrollable).first,
      );
      scroll.position.jumpTo(scroll.position.maxScrollExtent);
      await tester.pumpAndSettle();
      await tester.tap(find.bySemanticsLabel('Add Colors'));
      await tester.pumpAndSettle();
      final token = section.choices.entries
          .firstWhere(
            (e) => e.value.addable && e.value.name != 'standard.orange',
          )
          .key;
      await tester.scrollUntilVisible(
        find.bySemanticsLabel(token),
        100,
        scrollable: find
            .byWidgetPredicate(
              (w) => w is Scrollable && w.axisDirection == AxisDirection.down,
            )
            .last,
      );
      await tester.tap(find.bySemanticsLabel(token));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(changes, [
        {section.choices[token]!.name: true},
      ]);
      expect(find.text('Sending colors to your Apple Watch.'), findsOneWidget);
      sync.operation({
        'epoch': epoch,
        'requestId': '30000000-0000-0000-0000-000000000001',
        'status': 'APP_ACK_RECEIVED',
      });
      await tester.pumpAndSettle();
      expect(find.text('Sending colors to your Apple Watch.'), findsNothing);
      expect(find.text('Colors sent to your Apple Watch.'), findsOneWidget);
      now++;
      sync.observe(
        pair,
        observed(['standard.orange', 'zeus.unknown'], 812945800.0),
      );
      await tester.pumpAndSettle();
      expect(
        find.text('Your Apple Watch reported different colors.'),
        findsOneWidget,
      );
      await tester.tap(find.text('Retry Color Sync'));
      await tester.pumpAndSettle();
      expect(changes, hasLength(2));
      now++;
      sync.observe(
        pair,
        observed([
          'standard.orange',
          'zeus.unknown',
          section.choices[token]!.name,
        ], 812945801.0),
      );
      await tester.pumpAndSettle();
      expect(find.text('Sending colors to your Apple Watch.'), findsNothing);
      expect(changes, hasLength(2));
      await tester.pumpWidget(const SizedBox());
      favorites.dispose();
      sync.dispose();
      semantics.dispose();
    },
  );

  testWidgets('A color modal opened for one pair cannot save into another', (
    tester,
  ) async {
    final profiles = await tester.runAsync(NativeFaceGallery.load);
    final section = profiles!
        .firstWhere((p) => p.family == 'type:california')
        .pigmentSection('color')!;
    var writes = 0;
    final sync = NativePigmentSyncController(
      persist: (_) async {
        writes++;
      },
      send: (_, {required baseline}) async =>
          (status: 'QUEUED', requestId: '30000000-0000-0000-0000-000000000001'),
    );
    sync.observe(
      '10000000-0000-0000-0000-000000000001',
      const NativePigmentPreferences.unknown(),
    );
    final favorites = NativePigmentFavorites.synchronized(sync);
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(
      CupertinoApp(
        home: NativeFacePigmentPicker(
          section: section,
          favorites: favorites,
          label: (token) => token,
          canCommit: () => true,
        ),
      ),
    );
    await tester.pumpAndSettle();
    final token = section.choices.entries
        .firstWhere((e) => e.value.addable && e.value.automatic)
        .key;
    await tester.tap(find.bySemanticsLabel(token));
    await tester.pumpAndSettle();
    sync.observe(
      '10000000-0000-0000-0000-000000000002',
      const NativePigmentPreferences.unknown(),
    );
    await tester.tap(find.text('Done'));
    await tester.pumpAndSettle();
    expect(writes, 0);
    expect(find.byType(NativeFacePigmentPicker), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    favorites.dispose();
    sync.dispose();
    semantics.dispose();
  });

  testWidgets(
    'Add Colors stages multiple checks; cancel and no-op Done never edit; commit chooses native last color',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final section = profiles!
          .firstWhere((p) => p.family == 'type:california')
          .pigmentSection('color')!;
      final calls = <String>[], writes = <String>[];
      final favorites = NativePigmentFavorites(
        persist: (value) async => writes.add(value),
      );
      final selected = ValueNotifier('standard.orange:0.25');
      var active = true;
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: Center(
              child: SizedBox(
                width: 280,
                child: MediaQuery(
                  data: const MediaQueryData(
                    textScaler: TextScaler.linear(2.4),
                  ),
                  child: ValueListenableBuilder(
                    valueListenable: selected,
                    builder: (_, token, _) => NativeFacePigmentSectionView(
                      section: section,
                      title: 'COLOR',
                      selected: token,
                      label: (token) => token,
                      favorites: favorites,
                      canCommit: () => active,
                      onSelected: (token) {
                        calls.add(token);
                        selected.value = token;
                      },
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      Future<void> open() async {
        final state = tester.state<ScrollableState>(
          find.byType(Scrollable).first,
        );
        state.position.jumpTo(state.position.maxScrollExtent);
        await tester.pumpAndSettle();
        await tester.tap(find.bySemanticsLabel('Add Colors'));
        await tester.pumpAndSettle();
      }

      final added = section.choices.entries.firstWhere(
        (e) => e.value.addable && !e.value.automatic,
      );
      final removed = section.choices.entries.firstWhere(
        (e) => e.value.addable && e.value.automatic,
      );
      Future<void> toggle(String token) async {
        await tester.scrollUntilVisible(
          find.bySemanticsLabel(token),
          100,
          scrollable: find
              .byWidgetPredicate(
                (w) => w is Scrollable && w.axisDirection == AxisDirection.down,
              )
              .last,
        );
        await tester.tap(find.bySemanticsLabel(token));
        await tester.pumpAndSettle();
      }

      await open();
      await toggle(added.key);
      expect(writes, isEmpty);
      expect(calls, isEmpty);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(favorites.isVisible(added.value), isFalse);
      await open();
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(writes, isEmpty);
      expect(calls, isEmpty);
      await open();
      await toggle(removed.key);
      await toggle(removed.key);
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(writes, isEmpty);
      await open();
      await toggle(removed.key);
      await toggle(added.key);
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(writes, hasLength(1));
      expect(favorites.isVisible(added.value), isTrue);
      expect(favorites.isVisible(removed.value), isFalse);
      expect(calls, [favorites.visibleOptions(section).last]);
      await open();
      await toggle(added.key);
      active = false;
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(writes, hasLength(1));
      expect(find.byType(NativeFacePigmentPicker), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      favorites.dispose();
      selected.dispose();
      semantics.dispose();
    },
  );

  testWidgets(
    'failed Done keeps checked choices for retry; cancellation writes nothing',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final section = profiles!
          .firstWhere((p) => p.family == 'type:california')
          .pigmentSection('color')!;
      var fail = true, writes = 0;
      final store = NativePigmentFavorites(
        persist: (_) async {
          writes++;
          if (fail) throw StateError('offline storage');
        },
      );
      final token = section.choices.entries
          .firstWhere((e) => e.value.addable && e.value.automatic)
          .key;
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFacePigmentPicker(
            section: section,
            favorites: store,
            label: (token) => token,
            canCommit: () => true,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.byType(NativeFacePigmentSwatch).evaluate().length,
        lessThan(70),
      );
      await tester.tap(find.bySemanticsLabel(token));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(find.text('Could not save colors. Try again.'), findsOneWidget);
      expect(store.isVisible(section.choices[token]!), isTrue);
      fail = false;
      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(writes, 2);
      expect(store.isVisible(section.choices[token]!), isFalse);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      store.dispose();
      semantics.dispose();
    },
  );
}
