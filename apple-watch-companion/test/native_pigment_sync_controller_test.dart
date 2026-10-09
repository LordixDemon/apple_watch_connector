import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/native_pigment_sync_controller.dart';
import 'package:apple_watch_companion/models/native_pigment_preferences.dart';

const pairA = '10000000-0000-0000-0000-000000000001';
const pairB = '10000000-0000-0000-0000-000000000002';
const epoch = '20000000-0000-0000-0000-000000000001';
const request1 = '30000000-0000-0000-0000-000000000001';
const request2 = '30000000-0000-0000-0000-000000000002';
const originalTime = 1791253000000;
const originalSource = 812945799.0;
NativePigmentPreferences report(
  List<String> names, {
  String pair = pairA,
  int at = originalTime,
  double source = originalSource,
}) => NativePigmentPreferences.fromBridge({
  'connected': true,
  'bridgeAvailable': true,
  'faceCollectionPair': pair,
  'faceCollectionEpoch': epoch,
  'pigmentPreferencePair': pair,
  'pigmentPreferenceEpoch': epoch,
  'pigmentPreferenceObservedAt': at,
  'observedAt': at,
  'pigmentPreferenceSourceTimestamp': source,
  'pigmentPreferenceNames': names,
});
Future<void> flush() => Future<void>.delayed(Duration.zero);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Large native readback reconciles additions and removals without replay',
    () async {
      String? saved;
      var now = originalTime, sends = 0;
      final initial = List.generate(1023, (i) => 'native.$i');
      final changes = {
        for (var i = 0; i < 511; i++) 'native.$i': false,
        for (var i = 0; i < 511; i++) 'next.$i': true,
      };
      final next = [
        ...initial.skip(511),
        for (var i = 0; i < 511; i++) 'next.$i',
      ];
      final sync = NativePigmentSyncController(
        persist: (value) async => saved = value,
        now: () => now,
        send: (delta, {required baseline}) async {
          sends++;
          expect(delta, changes);
          expect(baseline.names, initial);
          return (status: 'QUEUED', requestId: request1);
        },
      );
      sync.observe(pairA, report(initial));
      await sync.update(changes);
      await flush();
      expect(sends, 1);
      expect(sync.state, PigmentSyncState.awaitingDelivery);
      now += 1000;
      // One manual removal still differs; all remaining 1021 changes match.
      sync.observe(
        pairA,
        report(
          ['native.0', ...next.skip(1)],
          at: now,
          source: originalSource + 1,
        ),
      );
      await flush();
      expect(sync.state, PigmentSyncState.uncertain);
      expect(sends, 1);
      now += 1000;
      sync.observe(pairA, report(next, at: now, source: originalSource + 2));
      await flush();
      expect(sync.state, PigmentSyncState.idle);
      expect(jsonDecode(saved!)['pairs'], isEmpty);
      expect(sends, 1);
      expect(sync.visible('native.0', true), isFalse);
      expect(sync.visible('native.1022', false), isTrue);
      expect(sync.visible('next.510', false), isTrue);
      sync.dispose();
    },
  );
  test(
    'A receipt for an earlier Done cannot claim delivery of later unsent edits, including after restart',
    () async {
      String? saved;
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (value) async => saved = value,
        now: () => originalTime,
        send: (delta, {required baseline}) async {
          sends++;
          expect(delta, {'standard.blue': true});
          return (status: 'QUEUED', requestId: request1);
        },
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      await sync.update({'standard.red': true});
      await flush();
      expect(sends, 1);
      sync.dispose();
      final restarted = NativePigmentSyncController(
        saved: saved,
        persist: (value) async => saved = value,
        now: () => originalTime,
        send: (_, {required baseline}) async {
          fail(
            'An uncertain superseded submission cannot replay automatically',
          );
        },
      );
      restarted.observe(pairA, report([]));
      restarted.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      expect(restarted.state, PigmentSyncState.deliveryUncertain);
      expect(jsonDecode(saved!)['pairs'][pairA]['changes'], {
        'standard.blue': true,
        'standard.red': true,
      });
      expect(jsonDecode(saved!)['pairs'][pairA]['delivered'], isNull);
      expect(restarted.visible('standard.red', false), isTrue);
      expect(restarted.canRetry, isFalse);
      restarted.dispose();
    },
  );
  test(
    'Native delivery ACK ends sending without fabricating a Watch preference report; later expiry does not undo it',
    () async {
      String? saved;
      final native = report([]);
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (value) async => saved = value,
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (status: 'QUEUED', requestId: request1);
        },
      );
      sync.observe(pairA, native);
      await sync.update({'standard.blue': true});
      await flush();
      sync.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'IDS_QUEUED',
      });
      await flush();
      expect(sync.state, PigmentSyncState.awaitingDelivery);
      sync.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      expect(sync.state, PigmentSyncState.delivered);
      expect(native.names, isEmpty);
      expect(jsonDecode(saved!)['pairs'][pairA]['delivered'], isTrue);
      expect(jsonDecode(saved!)['pairs'][pairA]['changes'], {
        'standard.blue': true,
      });
      for (final status in ['EXPIRED', 'UNKNOWN', 'FAILED']) {
        sync.operation({
          'epoch': epoch,
          'requestId': request1,
          'status': status,
        });
        await flush();
      }
      expect(sync.state, PigmentSyncState.delivered);
      expect(sends, 1);
      sync.dispose();
    },
  );

  test(
    'An ACK before the queue receipt is correlated after submission finishes',
    () async {
      final held = Completer<PigmentReceipt>();
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => originalTime,
        send: (_, {required baseline}) => held.future,
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      sync.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'APP_ACK_RECEIVED',
      });
      held.complete((status: 'QUEUED', requestId: request1));
      await flush();
      expect(sync.state, PigmentSyncState.delivered);
      sync.dispose();
    },
  );

  test(
    'Another request, another epoch, absent ownership or forged applied stages cannot mark delivery',
    () async {
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => originalTime,
        send: (_, {required baseline}) async =>
            (status: 'QUEUED', requestId: request1),
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      for (final event in [
        {'epoch': epoch, 'requestId': request2, 'status': 'APP_ACK_RECEIVED'},
        {'epoch': pairB, 'requestId': request1, 'status': 'APP_ACK_RECEIVED'},
        {'epoch': epoch, 'requestId': request1, 'status': 'APPLIED'},
        {
          'epoch': epoch,
          'requestId': request1,
          'status': 'NATIVE_FACE_APPLIED',
        },
        {
          'epoch': epoch,
          'requestId': request1,
          'status': 'APP_RESPONSE_RECEIVED',
        },
        {'epoch': epoch, 'requestId': '', 'status': 'APP_ACK_RECEIVED'},
      ]) {
        sync.operation(event);
        await flush();
      }
      expect(sync.state, PigmentSyncState.awaitingDelivery);
      sync.observe(pairB, report([], pair: pairB));
      sync.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      sync.observe(pairA, report([]));
      await flush();
      expect(sync.state, PigmentSyncState.awaitingDelivery);
      sync.dispose();
    },
  );

  test(
    'Delivered desired selections survive restart without replay; the next manual Done is a new request',
    () async {
      String? saved;
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (value) async => saved = value,
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (status: 'QUEUED', requestId: request1);
        },
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      sync.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      sync.dispose();
      final restarted = NativePigmentSyncController(
        saved: saved,
        persist: (value) async => saved = value,
        now: () => originalTime,
        send: (delta, {required baseline}) async {
          sends++;
          expect(delta, {'standard.blue': true, 'standard.red': true});
          return (status: 'QUEUED', requestId: request2);
        },
      );
      restarted.observe(pairA, report([]));
      await flush();
      expect(restarted.state, PigmentSyncState.delivered);
      expect(sends, 1);
      await restarted.update({'standard.red': true});
      await flush();
      expect(sends, 2);
      restarted.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      expect(restarted.state, PigmentSyncState.awaitingDelivery);
      restarted.operation({
        'epoch': epoch,
        'requestId': request2,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      expect(restarted.state, PigmentSyncState.delivered);
      restarted.dispose();
    },
  );

  test(
    'A queued request can expire without confirming delivery or triggering a duplicate',
    () async {
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (status: 'QUEUED', requestId: request1);
        },
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      sync.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'EXPIRED',
      });
      await flush();
      expect(sync.state, PigmentSyncState.deliveryUncertain);
      expect(sends, 1);
      expect(sync.canRetry, isFalse);
      expect(await sync.retry(), isFalse);
      sync.dispose();
    },
  );

  test(
    'Persisting a received ACK can fail; delivery must remain unconfirmed until a successful durable receipt',
    () async {
      var fail = false;
      final sync = NativePigmentSyncController(
        persist: (_) async {
          if (fail) throw StateError('disk full');
        },
        now: () => originalTime,
        send: (_, {required baseline}) async =>
            (status: 'QUEUED', requestId: request1),
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      fail = true;
      sync.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      expect(sync.state, PigmentSyncState.storageError);
      fail = false;
      sync.operation({
        'epoch': epoch,
        'requestId': request1,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      expect(sync.state, PigmentSyncState.delivered);
      sync.dispose();
    },
  );

  test(
    'New Done changes stay durable during uncertain delivery without resending the first command',
    () async {
      var now = originalTime, sends = 0;
      String? saved;
      final sync = NativePigmentSyncController(
        persist: (value) async => saved = value,
        now: () => now,
        send: (_, {required baseline}) async {
          sends++;
          return (status: 'UNKNOWN', requestId: null);
        },
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      await sync.update({'standard.red': true});
      await flush();
      expect(sends, 1);
      expect(sync.canRetry, isFalse);
      expect(jsonDecode(saved!)['pairs'][pairA]['changes'], {
        'standard.blue': true,
        'standard.red': true,
      });
      expect(jsonDecode(saved!)['pairs'][pairA]['attemptAt'], originalTime);
      now++;
      sync.observe(
        pairA,
        report(['standard.blue'], at: now, source: originalSource + 1),
      );
      await flush();
      expect(sends, 1);
      expect(sync.state, PigmentSyncState.uncertain);
      await sync.retry();
      await flush();
      expect(sends, 2);
      sync.dispose();
    },
  );
  test(
    'Manual retry requires a newer peer readback; never replays an ambiguous initial submission',
    () async {
      var now = originalTime, sends = 0;
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => now,
        send: (_, {required baseline}) async {
          sends++;
          return (status: 'UNKNOWN', requestId: null);
        },
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      expect(sends, 1);
      expect(sync.canRetry, isFalse);
      expect(await sync.retry(), isFalse);
      now++;
      sync.observe(pairA, report([], at: now));
      await flush();
      expect(sync.canRetry, isFalse);
      expect(await sync.retry(), isFalse);
      sync.observe(pairA, report([], at: now, source: originalSource + 1));
      await flush();
      expect(sends, 1);
      expect(sync.canRetry, isTrue);
      expect(await sync.retry(), isTrue);
      await flush();
      expect(sends, 2);
      expect(sync.canRetry, isFalse);
      sync.dispose();
    },
  );
  testWidgets(
    'Missing transport receipt times out without replay or blocking subsequent targets',
    (tester) async {
      final held = Completer<PigmentReceipt>();
      final targets = <String>[];
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => originalTime,
        send: (_, {required baseline}) {
          targets.add(baseline.pair!);
          return targets.length == 1
              ? held.future
              : Future.value((
                  status: 'QUEUED',
                  requestId: '30000000-0000-0000-0000-000000000001',
                ));
        },
      );
      sync.observe(pairA, report([]));
      await tester.pump();
      await sync.update({'standard.blue': true});
      await tester.pump();
      expect(targets, [pairA]);
      sync.observe(pairB, report([], pair: pairB));
      final other = sync.update({'standard.red': true});
      await tester.pump(const Duration(seconds: 16));
      await other;
      await tester.pump();
      expect(targets, [pairA, pairB]);
      held.complete((
        status: 'QUEUED',
        requestId: '30000000-0000-0000-0000-000000000001',
      ));
      await tester.pump();
      expect(sync.pair, pairB);
      expect(targets, hasLength(2));
      sync.dispose();
    },
  );

  test(
    'A native deletion clears displayed cached names; disconnected intent still cannot send',
    () async {
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(pairA, report(['standard.blue']));
      await flush();
      expect(sync.visible('standard.blue', false), isTrue);
      final tombstone = NativePigmentPreferences.fromBridge({
        'connected': true,
        'bridgeAvailable': true,
        'faceCollectionPair': pairA,
        'faceCollectionEpoch': epoch,
        'pigmentPreferencePair': pairA,
        'pigmentPreferenceEpoch': epoch,
        'pigmentPreferenceObservedAt': originalTime,
        'observedAt': originalTime,
        'pigmentPreferenceSourceTimestamp': originalSource + 1,
      });
      sync.observe(pairA, tombstone);
      await flush();
      expect(sync.visible('standard.blue', false), isFalse);
      await sync.update({'standard.blue': true});
      await flush();
      expect(sends, 0);
      sync.dispose();
    },
  );

  test(
    'Disposing during storage cannot submit or notify a released UI owner',
    () async {
      final held = Completer<void>();
      var sends = 0, notifications = 0;
      final sync = NativePigmentSyncController(
        persist: (_) => held.future,
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(pairA, report([]));
      sync.addListener(() => notifications++);
      final update = sync.update({'standard.blue': true});
      await flush();
      sync.dispose();
      held.complete();
      expect(await update, isFalse);
      await flush();
      expect(sends, 0);
      expect(notifications, 0);
    },
  );

  test(
    'Done is persisted for one pair before submission; queue receipt is not confirmation',
    () async {
      final writes = <String>[];
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (value) async => writes.add(value),
        now: () => originalTime,
        send: (changes, {required baseline}) async {
          sends++;
          final pending = jsonDecode(writes.last)['pairs'][pairA];
          expect(pending['attemptAt'], originalTime);
          expect(pending['sourceTimestamp'], originalSource);
          expect(changes, {'standard.blue': true, 'standard.navyBlue': false});
          expect(baseline.names, ['standard.navyBlue', 'zeus.unknown']);
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(pairA, report(['standard.navyBlue', 'zeus.unknown']));
      expect(
        await sync.update({'standard.blue': true, 'standard.navyBlue': false}),
        isTrue,
      );
      await flush();
      expect(sends, 1);
      expect(sync.state, PigmentSyncState.awaitingDelivery);
      expect(
        sync.visible('standard.blue', false),
        isTrue,
      ); // Desired overlay, not observed state.
      expect(jsonDecode(writes.last)['pairs'][pairA]['changes'], isNotEmpty);
      sync.dispose();
    },
  );

  test(
    'Fresh matching Watch values clear intent; duplicates, conflicts and other pairs do not',
    () async {
      var now = originalTime;
      String? saved;
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (value) async => saved = value,
        now: () => now,
        send: (_, {required baseline}) async {
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      now++;
      sync.observe(pairA, report(['standard.blue'], at: now));
      await flush();
      expect(
        sync.state,
        PigmentSyncState.awaitingDelivery,
      ); // Same source is not proof.
      sync.observe(
        pairB,
        report(
          ['standard.blue'],
          pair: pairB,
          at: now,
          source: originalSource + 1,
        ),
      );
      await flush();
      expect(sync.state, PigmentSyncState.idle);
      sync.observe(pairA, report([], at: now, source: originalSource + 1));
      await flush();
      expect(sync.state, PigmentSyncState.uncertain);
      expect(sends, 1);
      sync.observe(
        pairA,
        report(
          ['standard.blue', 'zeus.unknown'],
          at: now,
          source: originalSource + 2,
        ),
      );
      await flush();
      expect(sync.state, PigmentSyncState.idle);
      expect(jsonDecode(saved!)['pairs'], isEmpty);
      expect(sync.visible('zeus.unknown', false), isTrue);
      sync.observe(pairA, report([], at: now, source: originalSource + 3));
      await flush();
      expect(
        sync.visible('standard.blue', true),
        isFalse,
      ); // Confirmed overrides do not pin peer state.
      sync.dispose();
    },
  );

  test(
    'Offline intent survives restart; no migration of legacy global preferences',
    () async {
      String? saved;
      var sends = 0;
      final sync = NativePigmentSyncController(
        saved: jsonEncode({
          'version': 1,
          'overrides': {'legacy.red': true},
        }),
        persist: (value) async => saved = value,
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(pairA, const NativePigmentPreferences.unknown());
      expect(sync.visible('legacy.red', false), isFalse);
      await sync.update({'standard.blue': true});
      await flush();
      expect(sends, 0);
      sync.dispose();
      final restarted = NativePigmentSyncController(
        saved: saved,
        persist: (value) async => saved = value,
        now: () => originalTime,
        send: (_, {required baseline}) async {
          expect(baseline.pair, pairA);
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      restarted.observe(pairB, report([], pair: pairB));
      await flush();
      expect(sends, 0);
      expect(restarted.visible('standard.blue', false), isFalse);
      restarted.observe(pairA, report([]));
      await flush();
      expect(sends, 1);
      expect(restarted.state, PigmentSyncState.awaitingDelivery);
      restarted.dispose();
    },
  );

  test(
    'Crash after submission marker never auto-replays; matching newer readback recovers',
    () async {
      var now = originalTime;
      String? saved;
      final sync = NativePigmentSyncController(
        persist: (value) async => saved = value,
        now: () => now,
        send: (_, {required baseline}) async =>
            throw StateError('lost receipt'),
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      sync.dispose();
      now++;
      var sends = 0;
      final restarted = NativePigmentSyncController(
        saved: saved,
        persist: (_) async {},
        now: () => now,
        send: (_, {required baseline}) async {
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      restarted.observe(pairA, report([], at: now, source: originalSource + 1));
      await flush();
      expect(sends, 0);
      expect(restarted.state, PigmentSyncState.uncertain);
      restarted.observe(
        pairA,
        report(['standard.blue'], at: now, source: originalSource + 2),
      );
      await flush();
      expect(restarted.state, PigmentSyncState.idle);
      expect(sends, 0);
      restarted.dispose();
    },
  );

  test(
    'Storage failures before submission cannot reach Watch and remain retryable',
    () async {
      var writes = 0, sends = 0, failAt = 1;
      final sync = NativePigmentSyncController(
        persist: (_) async {
          if (++writes == failAt) throw StateError('disk full');
        },
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(pairA, report([]));
      await expectLater(sync.update({'standard.blue': true}), throwsStateError);
      expect(sends, 0);
      expect(sync.visible('standard.blue', false), isFalse);
      failAt = 3;
      await sync.update({'standard.blue': true});
      await flush();
      expect(sends, 0);
      expect(sync.state, PigmentSyncState.storageError);
      failAt = -1;
      sync.observe(pairA, report([], source: originalSource + 1));
      await flush();
      expect(sends, 1);
      sync.dispose();
    },
  );

  test(
    'Pair switch while durable write waits cannot send or expose the old target',
    () async {
      final gate = Completer<void>();
      String? saved;
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (value) async {
          saved = value;
          await gate.future;
        },
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(pairA, report([]));
      final update = sync.update({'standard.blue': true});
      await flush();
      sync.observe(pairB, report([], pair: pairB));
      gate.complete();
      await update;
      await flush();
      expect(sends, 0);
      expect(sync.visible('standard.blue', false), isFalse);
      expect(jsonDecode(saved!)['pairs'].keys, [pairA]);
      sync.dispose();
    },
  );

  test(
    'Readback arriving before a held queue receipt is still reconciled',
    () async {
      final receipt = Completer<PigmentReceipt>();
      var now = originalTime;
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => now,
        send: (_, {required baseline}) => receipt.future,
      );
      sync.observe(pairA, report([]));
      await sync.update({'standard.blue': true});
      await flush();
      now++;
      sync.observe(
        pairA,
        report(['standard.blue'], at: now, source: originalSource + 1),
      );
      receipt.complete((
        status: 'QUEUED',
        requestId: '30000000-0000-0000-0000-000000000001',
      ));
      await flush();
      expect(sync.state, PigmentSyncState.idle);
      sync.dispose();
    },
  );

  test(
    'Stale, future and missing baselines do not send; empty current baseline can add',
    () async {
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(pairA, report([], at: originalTime - 300001));
      await sync.update({'standard.blue': true});
      await flush();
      expect(sends, 0);
      sync.observe(pairA, report([], at: originalTime + 1));
      await flush();
      expect(sends, 0);
      sync.observe(pairA, report([]));
      await flush();
      expect(sends, 1);
      sync.dispose();
    },
  );

  test(
    'Known values already satisfy manual intent without another command',
    () async {
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => originalTime,
        send: (_, {required baseline}) async {
          sends++;
          return (
            status: 'QUEUED',
            requestId: '30000000-0000-0000-0000-000000000001',
          );
        },
      );
      sync.observe(pairA, report(['standard.blue']));
      await sync.update({'standard.blue': true, 'standard.red': false});
      await flush();
      expect(sync.state, PigmentSyncState.idle);
      expect(sends, 0);
      sync.dispose();
    },
  );

  test(
    'Invalid values, unscoped saved data and stale modal edits cannot persist',
    () async {
      var writes = 0;
      final sync = NativePigmentSyncController(
        persist: (_) async {
          writes++;
        },
        now: () => originalTime,
        send: (_, {required baseline}) async => (
          status: 'QUEUED',
          requestId: '30000000-0000-0000-0000-000000000001',
        ),
      );
      expect(await sync.update({'standard.blue': true}), isFalse);
      sync.observe(pairA, const NativePigmentPreferences.unknown());
      for (final changes in [
        <String, bool>{},
        {'native.test:0.50': true},
        {'native.\x00test': false},
        {for (var i = 0; i < 1024; i++) 'native.color$i': true},
      ]) {
        await expectLater(sync.update(changes), throwsFormatException);
      }
      expect(
        await sync.update({'standard.blue': true}, canCommit: () => false),
        isFalse,
      );
      expect(writes, 0);
      sync.dispose();
    },
  );
}
