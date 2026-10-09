import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/native_monogram_sync_controller.dart';
import 'package:apple_watch_companion/models/native_monogram_mirror.dart';
import 'package:apple_watch_companion/models/native_monogram_preferences.dart';

const pair = '10000000-0000-0000-0000-000000000001';
const epoch = '20000000-0000-0000-0000-000000000001';
const request = '30000000-0000-0000-0000-000000000001';
const now = 1791253000000;
const source = now / 1000 - 978307200;
Map<String, dynamic> data({
  String? text = 'OLD',
  String origin = 'REMOTE',
  double stamp = source - 1,
  int at = now,
  bool known = true,
  String target = pair,
  String session = epoch,
  String revision = 'a',
}) => {
  'connected': true,
  'bridgeAvailable': true,
  'observedAt': at,
  'pairId': target,
  'faceCollectionPair': target,
  'faceCollectionEpoch': session,
  'monogramMirrorVersion': 1,
  'monogramMirrorPair': target,
  'monogramMirrorEpoch': session,
  'monogramMirrorRevision': List.filled(64, revision).join(),
  'monogramMirrorKnown': known,
  if (known) ...{
    'monogramMirrorText': text,
    'monogramMirrorOrigin': origin,
    if (origin == 'LOCAL') 'monogramMirrorRequestId': request,
    'monogramMirrorSourceTimestamp': stamp,
    'monogramMirrorUpdatedAt': at,
  },
  'monogramPreferencePair': target,
  'monogramPreferenceEpoch': session,
  'monogramPreferenceText': text,
  'monogramPreferenceSourceTimestamp': stamp,
  'monogramPreferenceObservedAt': at,
};
NativeMonogramMirror mirror(Map map) => NativeMonogramMirror.fromBridge(map)!;
Future<void> flush() => Future<void>.delayed(Duration.zero);

void main() {
  test(
    'Equivalent Bridge publications do not enqueue reconciliation or notify the editor',
    () async {
      var notifications = 0, sends = 0;
      final sync = NativeMonogramSyncController(
        now: () => now,
        persist: (_) async {},
        send: (_, _) async {
          sends++;
          return (status: 'QUEUED', requestId: request);
        },
      );
      sync.addListener(() => notifications++);
      for (var i = 0; i < 1000; i++) {
        final publication = data();
        sync.observe(
          mirror(publication),
          NativeMonogramPreferences.fromBridge(publication),
        );
      }
      await flush();
      expect(notifications, 1);
      expect(sends, 0);
      sync.dispose();
    },
  );
  test(
    'Unknown, deletion and local intent remain separate and malformed scope is rejected',
    () {
      expect(mirror(data(known: false)).known, false);
      expect(mirror(data(text: null)).known, true);
      expect(mirror(data(text: null)).text, isNull);
      expect(mirror(data(origin: 'LOCAL')).origin, 'LOCAL');
      for (final entry in <String, Object?>{
        'connected': false,
        'pairId': epoch,
        'monogramMirrorVersion': 2,
        'monogramMirrorRevision': 'a',
        'monogramMirrorText': '😀',
        'monogramMirrorSourceTimestamp': double.nan,
      }.entries) {
        expect(
          NativeMonogramMirror.fromBridge({...data(), entry.key: entry.value}),
          isNull,
        );
      }
      expect(
        NativeMonogramMirror.fromBridge({
          ...data(known: false),
          'monogramMirrorText': 'X',
        }),
        isNull,
      );
    },
  );
  test(
    'Persistence failure prevents transport; losing context while saving also prevents transport',
    () async {
      var sends = 0;
      final baseline = mirror(data());
      final broken = NativeMonogramSyncController(
        now: () => now,
        persist: (_) async => throw StateError('disk'),
        send: (_, _) async {
          sends++;
          return (status: 'QUEUED', requestId: request);
        },
      );
      broken.observe(baseline, const NativeMonogramPreferences.unknown());
      await expectLater(broken.update('X', baseline), throwsStateError);
      expect(sends, 0);
      expect(broken.state, MonogramSyncState.storageError);
      broken.dispose();
      final gate = Completer<void>();
      final sync = NativeMonogramSyncController(
        now: () => now,
        persist: (_) => gate.future,
        send: (_, _) async {
          sends++;
          return (status: 'QUEUED', requestId: request);
        },
      );
      sync.observe(baseline, const NativeMonogramPreferences.unknown());
      final result = sync.update('X', baseline);
      await flush();
      sync.observe(null, const NativeMonogramPreferences.unknown());
      gate.complete();
      expect((await result).status, 'REJECTED');
      expect(sends, 0);
      sync.dispose();
    },
  );
  test(
    'Early ACK proves delivery only; a matching owned newer native echo confirms',
    () async {
      String? saved;
      var sends = 0;
      final gate = Completer<MonogramReceipt>();
      final initial = data();
      final baseline = mirror(initial);
      final sync = NativeMonogramSyncController(
        now: () => now + 1000,
        persist: (value) async => saved = value,
        send: (text, _) {
          sends++;
          expect(jsonDecode(saved!)['pairs'][pair]['text'], text);
          return gate.future;
        },
      );
      sync.observe(baseline, NativeMonogramPreferences.fromBridge(initial));
      final result = sync.update('NEW', baseline);
      await flush();
      sync.operation({
        'requestId': request,
        'epoch': epoch,
        'status': 'APP_ACK_RECEIVED',
      });
      gate.complete((status: 'QUEUED', requestId: request));
      await result;
      await flush();
      expect(sync.state, MonogramSyncState.delivered);
      final local = data(
        text: 'NEW',
        origin: 'LOCAL',
        stamp: source + 1,
        at: now + 1000,
        revision: 'b',
      );
      sync.observe(mirror(local), NativeMonogramPreferences.fromBridge(local));
      await flush();
      expect(sync.state, MonogramSyncState.delivered);
      final remote = data(
        text: 'NEW',
        stamp: source + 1,
        at: now + 1000,
        revision: 'c',
      );
      sync.observe(
        mirror(remote),
        NativeMonogramPreferences.fromBridge(remote),
      );
      await flush();
      expect(sync.state, MonogramSyncState.confirmed);
      expect(sends, 1);
      sync.dispose();
      final restored = NativeMonogramSyncController(
        saved: saved,
        now: () => now + 1000,
        persist: (_) async {},
        send: (_, _) async {
          sends++;
          return (status: 'QUEUED', requestId: request);
        },
      );
      restored.observe(baseline, NativeMonogramPreferences.fromBridge(initial));
      await flush();
      expect(restored.state, MonogramSyncState.idle);
      expect(restored.text, 'OLD');
      expect(sends, 1);
      restored.dispose();
    },
  );
  test(
    'Old matching text, foreign epoch, and unrelated ACK never confirm; restart never replays',
    () async {
      var sends = 0;
      String? saved;
      final initial = data(text: 'NEW', stamp: source);
      final baseline = mirror(initial);
      final sync = NativeMonogramSyncController(
        now: () => now,
        persist: (value) async => saved = value,
        send: (_, _) async {
          sends++;
          return (status: 'QUEUED', requestId: request);
        },
      );
      sync.observe(baseline, NativeMonogramPreferences.fromBridge(initial));
      await sync.update('NEW', baseline);
      await flush();
      expect(sync.state, MonogramSyncState.awaitingDelivery);
      sync.operation({
        'requestId': pair,
        'epoch': epoch,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      expect(sync.state, MonogramSyncState.awaitingDelivery);
      final foreign = data(
        text: 'NEW',
        stamp: source + 1,
        at: now + 1000,
        session: pair,
      );
      sync.observe(
        mirror(foreign),
        NativeMonogramPreferences.fromBridge(foreign),
      );
      await flush();
      expect(sync.state, MonogramSyncState.uncertain);
      sync.dispose();
      final restored = NativeMonogramSyncController(
        saved: saved,
        now: () => now,
        persist: (_) async {},
        send: (_, _) async {
          sends++;
          return (status: 'QUEUED', requestId: request);
        },
      );
      restored.observe(baseline, NativeMonogramPreferences.fromBridge(initial));
      await flush();
      expect(restored.state, MonogramSyncState.uncertain);
      expect(sends, 1);
      restored.dispose();
    },
  );
}
