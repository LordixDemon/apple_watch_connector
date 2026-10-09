import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_face_pigment.dart';
import 'package:apple_watch_companion/services/native_pigment_favorites.dart';

void main() {
  late NativeFacePigmentSection california, contour;
  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    await NativeFaceGallery.load();
    california = NativeFaceGallery.profile(
      'type:california',
    )!.pigmentSection('color')!;
    contour = NativeFaceGallery.profile(
      'bundle:com.apple.NTKProteusFaceBundle',
    )!.pigmentSection('color')!;
  });

  test(
    'native identity is global; automatic colors, locked colors and selected shades survive',
    () async {
      String? saved;
      final store = NativePigmentFavorites(
        persist: (value) async => saved = value,
      );
      final chosen = california.choices.entries.firstWhere(
        (e) => e.value.addable && !e.value.automatic,
      );
      final shared = contour.choices.entries.where(
        (e) => e.value.name == chosen.value.name,
      );
      expect(shared, isNotEmpty);
      expect(store.isVisible(chosen.value), isFalse);
      await store.update({chosen.value.name: true, 'standard.orange': false});
      expect(store.isVisible(shared.first.value), isTrue);
      expect(store.visibleOptions(california), contains('standard.orange'));
      final reloaded = NativePigmentFavorites(
        saved: saved,
        persist: (_) async {},
      );
      expect(reloaded.isVisible(chosen.value), isTrue);
      final automatic = california.choices.entries.firstWhere(
        (e) => e.value.addable && e.value.automatic,
      );
      await reloaded.update({automatic.value.name: false});
      expect(
        reloaded.visibleOptions(california),
        isNot(contains(automatic.key)),
      );
      expect(
        reloaded.visibleOptions(california, selected: automatic.key),
        contains(automatic.key),
      );
      expect(
        reloaded.visibleOptions(california, selected: 'standard.orange:0.25'),
        contains('standard.orange'),
      );
      store.dispose();
      reloaded.dispose();
    },
  );

  test(
    'writes are serialized, failures do not publish and retries keep unrelated colors',
    () async {
      final gate = Completer<void>();
      final writes = <String>[];
      var fail = false, notifications = 0;
      final store = NativePigmentFavorites(
        persist: (value) async {
          writes.add(value);
          if (writes.length == 1) await gate.future;
          if (fail) throw StateError('storage unavailable');
        },
      )..addListener(() => notifications++);
      final a = california.choices.values.firstWhere(
        (c) => c.addable && !c.automatic,
      );
      final b = california.choices.values.lastWhere(
        (c) => c.addable && !c.automatic,
      );
      final first = store.update({a.name: true});
      final second = store.update({b.name: true});
      await Future<void>.delayed(Duration.zero);
      expect(writes, hasLength(1));
      expect(store.isVisible(a), isFalse);
      gate.complete();
      await first;
      await second;
      expect(notifications, 2);
      expect(jsonDecode(writes.last)['overrides'], {
        a.name: true,
        b.name: true,
      });
      fail = true;
      await expectLater(store.update({a.name: false}), throwsStateError);
      expect(store.isVisible(a), isTrue);
      expect(notifications, 2);
      fail = false;
      await store.update({a.name: false});
      expect(store.isVisible(a), isFalse);
      expect(store.isVisible(b), isTrue);
      store.dispose();
    },
  );

  test(
    'queued stale target and disposed owner cannot publish a color transaction',
    () async {
      final gate = Completer<void>();
      var writes = 0, current = true;
      final store = NativePigmentFavorites(
        persist: (_) async {
          writes++;
          await gate.future;
        },
      );
      final first = store.update({'seasons.test.a': true});
      final stale = store.update({
        'seasons.test.b': true,
      }, canCommit: () => current);
      await Future<void>.delayed(Duration.zero);
      current = false;
      gate.complete();
      await first;
      expect(await stale, isFalse);
      expect(writes, 1);
      store.dispose();
      expect(await store.update({'seasons.test.c': true}), isFalse);
      expect(writes, 1);
    },
  );

  test(
    'corrupt, oversized and shade-key preferences cannot become manual favorites',
    () async {
      for (final saved in [
        '{',
        jsonEncode({
          'version': 1,
          'overrides': {'standard.orange:0.50': true},
        }),
        jsonEncode({
          'version': 1,
          'overrides': {'seasons.test': 'yes'},
        }),
        'x' * (1024 * 1024 + 1),
      ]) {
        final store = NativePigmentFavorites(
          saved: saved,
          persist: (_) async {},
        );
        expect(store.visibleOptions(california), isNotEmpty);
        await expectLater(
          store.update({'standard.orange:0.20': true}),
          throwsFormatException,
        );
        store.dispose();
      }
    },
  );

  test(
    'native empty palette fallback and collection locale metadata are validated',
    () async {
      final section = NativeFaceGallery.profile(
        'bundle:com.apple.NTKVivaldiFaceBundle',
      )!.pigmentSection('color')!;
      expect(section.choices.values.every((c) => c.addable), isTrue);
      final store = NativePigmentFavorites(persist: (_) async {});
      await store.update({
        for (final c in section.choices.values) c.name: false,
      });
      expect(store.visibleOptions(section), isEmpty);
      expect(section.accepts(section.defaultToken!), isTrue);
      expect(
        () => NativePigmentChoice.fromJson({
          'name': 'bad:0.50',
          'collection': 'test',
          'titles': {'en': 'Test'},
          'addable': true,
          'automatic': false,
        }),
        throwsFormatException,
      );
      expect(california.choices.values.first.title('xx'), isNotEmpty);
      store.dispose();
    },
  );
}
