import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:apple_watch_companion/models/native_pigment_mirror.dart';
import 'package:apple_watch_companion/models/native_pigment_preferences.dart';
import 'package:apple_watch_companion/controllers/native_pigment_sync_controller.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:apple_watch_companion/models/watch_face.dart';

const pair = '10000000-0000-0000-0000-000000000001';
const epoch = '20000000-0000-0000-0000-000000000001';
const nextEpoch = '20000000-0000-0000-0000-000000000002';
const request = '30000000-0000-0000-0000-000000000001';
const nextRequest = '30000000-0000-0000-0000-000000000002';
const now = 1791253000000;
const source = 812945799.0;
Map<String, dynamic> publication({
  List<String>? names = const ['zeus.unknown'],
  double timestamp = source,
  String origin = 'REMOTE',
  String session = epoch,
  int changedAt = now,
  int publishedAt = now,
  String? localRequest,
}) => {
  'connected': true,
  'bridgeAvailable': true,
  'pairId': pair,
  'faceCollectionPair': pair,
  'faceCollectionEpoch': session,
  'observedAt': publishedAt,
  'pigmentMirrorVersion': 2,
  'pigmentMirrorPair': pair,
  'pigmentMirrorEpoch': session,
  'pigmentMirrorOrigin': origin,
  'pigmentMirrorSourceTimestamp': timestamp,
  'pigmentMirrorUpdatedAt': changedAt,
  'pigmentMirrorNames': names,
  'pigmentMirrorRequestId': ?localRequest,
  'pigmentMirrorAutomaticOrigin': origin,
  'pigmentMirrorAutomaticTimestamp': timestamp,
  'pigmentMirrorAutomaticUpdatedAt': changedAt,
  'pigmentMirrorAutomaticNames': <String>[],
  'pigmentMirrorAutomaticRequestId': ?localRequest,
};
Future<void> flush() => Future<void>.delayed(Duration.zero);
NativePigmentPreferences liveContext(Map data) =>
    NativePigmentPreferences.fromBridge(data);

final class _Transport implements BridgeTransport {
  final controller = StreamController<dynamic>();
  final calls = <Map<String, dynamic>>[];
  @override
  Stream<dynamic> get events => controller.stream;
  @override
  Future<dynamic> invoke(String method, [Map<String, dynamic>? args]) async {
    expect(method, 'setNativePigmentVisibility');
    calls.add(args!);
    return {
      'status': 'QUEUED',
      'requestId': calls.length == 1 ? request : nextRequest,
    };
  }
}

final class _Catalog extends WatchFaceApiService {
  @override
  Future<List<WatchFace>> loadCachedFaces() async => [];
  @override
  Future<List<WatchFace>> fetchRemoteFaces({String endpoint = ''}) async => [];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Historical single-list intent is not silently cleared while the peer still tracks its manual color automatically',
    () async {
      final saved = jsonEncode({
        'version': 2,
        'pairs': {
          pair: {
            'changes': {'standard.blue': true},
            'attemptAt': now - 1000,
            'sourceTimestamp': source - 1,
            'epoch': epoch,
            'requestId': request,
          },
        },
      });
      var sends = 0;
      final sync = NativePigmentSyncController(
        saved: saved,
        persist: (_) async {},
        now: () => now,
        send: (_, {required baseline}) async {
          sends++;
          expect((baseline as NativePigmentMirror).automatic!.names, [
            'standard.blue',
            'unknown.automatic',
          ]);
          return (status: 'QUEUED', requestId: nextRequest);
        },
      );
      final data = publication(names: ['standard.blue'])
        ..['pigmentMirrorAutomaticNames'] = [
          'standard.blue',
          'unknown.automatic',
        ]
        ..addAll({
          'pigmentPreferencePair': pair,
          'pigmentPreferenceEpoch': epoch,
          'pigmentPreferenceNames': ['standard.blue'],
          'pigmentPreferenceSourceTimestamp': source,
          'pigmentPreferenceObservedAt': now,
        });
      sync.observe(
        pair,
        liveContext(data),
        baseline: NativePigmentMirror.fromBridge(data),
      );
      await flush();
      expect(sync.state, PigmentSyncState.uncertain);
      expect(sync.canRetry, isTrue);
      expect(sends, 0);
      expect(await sync.retry(), isTrue);
      await flush();
      expect(sends, 1);
      expect(sync.state, PigmentSyncState.awaitingDelivery);
      sync.dispose();
    },
  );
  test(
    'A durable paired remote mirror remains writable after idle without becoming a live Watch observation',
    () {
      final data = publication(publishedAt: now + 86400000);
      final mirror = NativePigmentMirror.fromBridge(data)!;
      expect(mirror.canWrite(now + 86400000), isTrue);
      expect(mirror.observedAt, now);
      expect(mirror.origin, 'REMOTE');
      expect(liveContext(data).known, isFalse);
    },
  );
  test(
    'Selected-only and deleted automatic values stay readable but cannot write',
    () {
      final old = publication(names: ['standard.blue'])
        ..removeWhere((key, _) => key.startsWith('pigmentMirrorAutomatic'))
        ..remove('pigmentMirrorVersion');
      final mirror = NativePigmentMirror.fromBridge(old)!;
      expect(mirror.names, ['standard.blue']);
      expect(mirror.canWrite(now), isFalse);
      final deleted = NativePigmentMirror.fromBridge(
        publication()..remove('pigmentMirrorAutomaticNames'),
      )!;
      expect(deleted.known, isTrue);
      expect(deleted.automatic!.known, isFalse);
      expect(deleted.canWrite(now), isFalse);
      expect(
        NativePigmentMirror.fromBridge(publication())!.canWrite(now),
        isTrue,
      );
    },
  );
  test('Malformed automatic data cannot supply a manual baseline', () {
    for (final delta in [
      {'pigmentMirrorAutomaticOrigin': 'APPLIED'},
      {'pigmentMirrorAutomaticOrigin': 'LOCAL'},
      {'pigmentMirrorAutomaticRequestId': request},
      {'pigmentMirrorAutomaticTimestamp': source + 100},
      {
        'pigmentMirrorAutomaticNames': ['duplicate', 'duplicate'],
      },
      {'pigmentMirrorAutomaticUpdatedAt': now + 10000},
      {'pigmentMirrorVersion': 1},
    ]) {
      expect(
        NativePigmentMirror.fromBridge({
          ...publication(),
          ...delta,
        })!.canWrite(now),
        isFalse,
        reason: '$delta',
      );
    }
  });
  test(
    'A manual already-visible choice leaves automatic tracking and needs both genuine readbacks after restart',
    () async {
      String? saved;
      var sends = 0, clock = now + 2000;
      Future<PigmentReceipt> send(
        Map<String, bool> changes, {
        required baseline,
      }) async {
        sends++;
        expect(changes, {'standard.blue': true});
        return (status: 'QUEUED', requestId: request);
      }

      NativePigmentSyncController owner() => NativePigmentSyncController(
        saved: saved,
        persist: (value) async => saved = value,
        now: () => clock,
        send: send,
      );
      final first = publication(names: ['standard.blue', 'unknown.visible'])
        ..['pigmentMirrorAutomaticNames'] = [
          'standard.blue',
          'unknown.automatic',
        ];
      var sync = owner();
      sync.observe(
        pair,
        liveContext(first),
        baseline: NativePigmentMirror.fromBridge(first),
      );
      await sync.update({'standard.blue': true});
      await flush();
      expect(sends, 1);
      expect(saved, contains('automaticTimestamp'));
      sync.dispose();
      sync = owner();
      clock += 1000;
      Map<String, dynamic> echo({
        required String automaticOrigin,
        required List<String> autoNames,
        required double stamp,
      }) {
        final data =
            publication(
                names: ['standard.blue', 'unknown.visible'],
                timestamp: stamp,
                changedAt: clock,
                publishedAt: clock,
              )
              ..['pigmentMirrorAutomaticOrigin'] = automaticOrigin
              ..['pigmentMirrorAutomaticNames'] = autoNames;
        if (automaticOrigin == 'LOCAL') {
          data['pigmentMirrorAutomaticRequestId'] = request;
        }
        data.addAll({
          'pigmentPreferencePair': pair,
          'pigmentPreferenceEpoch': epoch,
          'pigmentPreferenceNames': ['standard.blue', 'unknown.visible'],
          'pigmentPreferenceSourceTimestamp': stamp,
          'pigmentPreferenceObservedAt': clock,
        });
        return data;
      }

      final selectedEchoOnly = echo(
        automaticOrigin: 'LOCAL',
        autoNames: ['unknown.automatic'],
        stamp: source + 1,
      );
      sync.observe(
        pair,
        liveContext(selectedEchoOnly),
        baseline: NativePigmentMirror.fromBridge(selectedEchoOnly),
      );
      await flush();
      expect(sync.state, PigmentSyncState.awaitingDelivery);
      expect(sync.canRetry, isFalse);
      clock += 1000;
      final conflict = echo(
        automaticOrigin: 'REMOTE',
        autoNames: ['standard.blue', 'unknown.automatic'],
        stamp: source + 2,
      );
      sync.observe(
        pair,
        liveContext(conflict),
        baseline: NativePigmentMirror.fromBridge(conflict),
      );
      await flush();
      expect(sync.state, PigmentSyncState.uncertain);
      expect(sync.canRetry, isTrue);
      clock += 1000;
      final both = echo(
        automaticOrigin: 'REMOTE',
        autoNames: ['unknown.automatic'],
        stamp: source + 3,
      );
      sync.observe(
        pair,
        liveContext(both),
        baseline: NativePigmentMirror.fromBridge(both),
      );
      await flush();
      expect(sync.state, PigmentSyncState.idle);
      expect(sends, 1);
      sync.dispose();
    },
  );
  test(
    'Production automatic-only publication releases a pending edit without changing the visible list',
    () async {
      SharedPreferences.setMockInitialValues({});
      final transport = _Transport(), catalog = _Catalog();
      final bridge = WatchBridgeService(transport: transport);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: catalog,
      );
      final realNow = DateTime.now().millisecondsSinceEpoch;
      final complete = publication(
        names: ['standard.blue'],
        timestamp: realNow / 1000 - 978307200 - 1,
        changedAt: realNow,
        publishedAt: realNow,
      );
      final selectedOnly = Map<String, dynamic>.from(complete)
        ..removeWhere((key, _) => key.startsWith('pigmentMirrorAutomatic'));
      Future<void> publish(Map data) async {
        final event = bridge.observationStream.first;
        transport.controller.add({'type': 'connection', 'data': data});
        await event;
        await flush();
      }

      await publish(selectedOnly);
      await provider.pigmentFavorites.update({'standard.blue': false});
      await flush();
      expect(transport.calls, isEmpty);
      expect(provider.pigmentFavorites.syncState, PigmentSyncState.pending);
      await publish(complete);
      expect(transport.calls, hasLength(1));
      expect(transport.calls.single['expectedNames'], ['standard.blue']);
      expect(transport.calls.single['expectedAutomaticNames'], isEmpty);
      expect(
        transport.calls.single['automaticTimestamp'],
        complete['pigmentMirrorAutomaticTimestamp'],
      );
      expect(provider.pigmentPreferences.known, isFalse);
      provider.dispose();
      bridge.dispose();
      catalog.dispose();
      await transport.controller.close();
    },
  );
  test(
    'Older Bridge data is absent; malformed present mirrors cannot silently fall back',
    () {
      expect(NativePigmentMirror.fromBridge({'connected': true}), isNull);
      for (final delta in [
        {'pigmentMirrorPair': nextEpoch},
        {'pigmentMirrorEpoch': nextEpoch},
        {'pairId': nextEpoch},
        {'pigmentMirrorOrigin': 'APPLIED'},
        {'pigmentMirrorOrigin': 'LOCAL'},
        {'pigmentMirrorRequestId': request},
        {
          'pigmentMirrorNames': ['duplicate', 'duplicate'],
        },
        {'pigmentMirrorSourceTimestamp': double.nan},
        {'pigmentMirrorSourceTimestamp': source + 100},
        {'pigmentMirrorUpdatedAt': now + 10000},
        {'connected': false},
      ]) {
        final value = NativePigmentMirror.fromBridge({
          ...publication(),
          ...delta,
        });
        expect(value, isNotNull);
        expect(value!.canWrite(now), isFalse, reason: '$delta');
      }
    },
  );
  test(
    'Deletion stays unknown while a genuine empty mirror is a complete writable baseline',
    () {
      expect(
        NativePigmentMirror.fromBridge(publication(names: null))!.canWrite(now),
        isFalse,
      );
      expect(
        NativePigmentMirror.fromBridge(publication(names: []))!.canWrite(now),
        isTrue,
      );
      final localDeletion = NativePigmentMirror.fromBridge(
        publication(names: null, origin: 'LOCAL', localRequest: request),
      );
      expect(localDeletion!.canWrite(now), isFalse);
    },
  );
  test(
    'Clock rollback cannot make an old mirror writable by inventing a receipt time',
    () {
      final mirror = NativePigmentMirror.fromBridge(publication())!;
      expect(mirror.canWrite(now - 1), isFalse);
    },
  );
  test(
    'Local preparation alone cannot confirm an attempted native change or replay it',
    () async {
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => now,
        send: (_, {required baseline}) async {
          sends++;
          return (status: 'QUEUED', requestId: request);
        },
      );
      final first = publication();
      sync.observe(
        pair,
        liveContext(first),
        baseline: NativePigmentMirror.fromBridge(first),
      );
      await sync.update({'standard.blue': true});
      await flush();
      final prepared = publication(
        names: ['zeus.unknown', 'standard.blue'],
        timestamp: source + 1,
        origin: 'LOCAL',
        localRequest: request,
      );
      sync.observe(
        pair,
        liveContext(prepared),
        baseline: NativePigmentMirror.fromBridge(prepared),
      );
      await flush();
      expect(sync.state, PigmentSyncState.awaitingDelivery);
      expect(sync.canRetry, isFalse);
      expect(sends, 1);
      sync.dispose();
    },
  );
  test(
    'A pending new edit after idle uses the original paired full list and preserves unknown names',
    () async {
      final data = publication(publishedAt: now + 86400000);
      var sends = 0;
      final sync = NativePigmentSyncController(
        persist: (_) async {},
        now: () => now + 86400000,
        send: (changes, {required baseline}) async {
          sends++;
          expect(baseline.names, ['zeus.unknown']);
          expect(baseline.observedAt, now);
          expect(changes, {'standard.blue': true});
          return (status: 'QUEUED', requestId: request);
        },
      );
      sync.observe(
        pair,
        liveContext(data),
        baseline: NativePigmentMirror.fromBridge(data),
      );
      await sync.update({'standard.blue': true});
      await flush();
      expect(sends, 1);
      expect(sync.state, PigmentSyncState.awaitingDelivery);
      sync.dispose();
    },
  );
  test(
    'After reconnect a new explicit Done supersedes the persisted local value without automatic retry',
    () async {
      String? saved;
      var sends = 0;
      final original = NativePigmentSyncController(
        persist: (v) async => saved = v,
        now: () => now,
        send: (_, {required baseline}) async {
          sends++;
          return (status: 'QUEUED', requestId: request);
        },
      );
      final first = publication();
      original.observe(
        pair,
        liveContext(first),
        baseline: NativePigmentMirror.fromBridge(first),
      );
      await original.update({'standard.blue': true});
      await flush();
      original.dispose();
      final data = publication(
        names: ['zeus.unknown', 'standard.blue'],
        timestamp: source + 1,
        origin: 'LOCAL',
        localRequest: request,
        session: nextEpoch,
      );
      final restarted = NativePigmentSyncController(
        saved: saved,
        persist: (_) async {},
        now: () => now,
        send: (changes, {required baseline}) async {
          sends++;
          expect(changes, {'standard.blue': true, 'standard.red': true});
          expect(baseline.names, ['zeus.unknown', 'standard.blue']);
          expect(baseline.epoch, nextEpoch);
          return (status: 'QUEUED', requestId: nextRequest);
        },
      );
      restarted.observe(
        pair,
        liveContext(data),
        baseline: NativePigmentMirror.fromBridge(data),
      );
      await flush();
      expect(sends, 1);
      await restarted.update({'standard.red': true});
      await flush();
      expect(sends, 2);
      restarted.operation({
        'epoch': epoch,
        'requestId': request,
        'status': 'APP_ACK_RECEIVED',
      });
      await flush();
      expect(restarted.state, PigmentSyncState.awaitingDelivery);
      restarted.dispose();
    },
  );
  test(
    'Production Provider composes mirror, two manual requests, exact IDS receipts and separate native readback',
    () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      final transport = _Transport();
      final bridge = WatchBridgeService(transport: transport);
      final catalog = _Catalog();
      final provider = WatchProvider(
        storage: SettingsStorage(prefs),
        bridge: bridge,
        apiService: catalog,
      );
      final realNow = DateTime.now().millisecondsSinceEpoch;
      final baseSource = realNow / 1000 - 978307200 - 10;
      Future<void> publish(Map data) async {
        final delivered = bridge.observationStream.first;
        transport.controller.add({'type': 'connection', 'data': data});
        await delivered;
        await flush();
      }

      await publish(
        publication(
          timestamp: baseSource,
          changedAt: realNow - 86400000,
          publishedAt: realNow,
        )..addAll({
          'pigmentMirrorSourceTimestamp': baseSource - 86400,
          'pigmentMirrorAutomaticTimestamp': baseSource - 86400,
        }),
      );
      await provider.pigmentFavorites.update({'standard.blue': true});
      await flush();
      expect(transport.calls, hasLength(1));
      expect(transport.calls.first['expectedNames'], ['zeus.unknown']);
      final preparedAt = DateTime.now().millisecondsSinceEpoch;
      final ownSource = preparedAt / 1000 - 978307200;
      await publish(
        publication(
          names: ['zeus.unknown', 'standard.blue'],
          timestamp: ownSource,
          origin: 'LOCAL',
          localRequest: request,
          changedAt: preparedAt,
          publishedAt: preparedAt,
        ),
      );
      expect(provider.pigmentPreferences.known, isFalse);
      expect(
        provider.pigmentFavorites.syncState,
        PigmentSyncState.awaitingDelivery,
      );
      await provider.pigmentFavorites.update({'standard.red': true});
      await flush();
      expect(transport.calls, hasLength(2));
      expect(transport.calls.last['expectedNames'], [
        'zeus.unknown',
        'standard.blue',
      ]);
      expect(transport.calls.last['changes'], {
        'standard.blue': true,
        'standard.red': true,
      });
      transport.controller.add({
        'type': 'operation',
        'data': {
          'epoch': epoch,
          'requestId': request,
          'status': 'APP_ACK_RECEIVED',
        },
      });
      await flush();
      expect(
        provider.pigmentFavorites.syncState,
        PigmentSyncState.awaitingDelivery,
      );
      transport.controller.add({
        'type': 'operation',
        'data': {
          'epoch': epoch,
          'requestId': nextRequest,
          'status': 'APP_ACK_RECEIVED',
        },
      });
      await flush();
      expect(provider.pigmentFavorites.syncState, PigmentSyncState.delivered);
      expect(provider.pigmentPreferences.known, isFalse);
      final receivedAt = DateTime.now().millisecondsSinceEpoch;
      final native = publication(
        names: ['zeus.unknown', 'standard.blue', 'standard.red'],
        timestamp: ownSource + 0.001,
        changedAt: receivedAt,
        publishedAt: receivedAt,
      );
      native.addAll({
        'pigmentPreferencePair': pair,
        'pigmentPreferenceEpoch': epoch,
        'pigmentPreferenceNames': [
          'zeus.unknown',
          'standard.blue',
          'standard.red',
        ],
        'pigmentPreferenceSourceTimestamp': ownSource + 0.001,
        'pigmentPreferenceObservedAt': receivedAt,
      });
      await publish(native);
      expect(provider.pigmentPreferences.known, isTrue);
      expect(provider.pigmentFavorites.syncState, PigmentSyncState.idle);
      expect(transport.calls, hasLength(2));
      provider.dispose();
      bridge.dispose();
      catalog.dispose();
      await transport.controller.close();
    },
  );
}
