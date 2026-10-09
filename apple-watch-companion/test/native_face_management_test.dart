import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:archive/archive.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/native_face_controller.dart';
import 'package:apple_watch_companion/services/native_watch_face_archive.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/models/native_face_collection.dart';

const pair = '11111111-2222-3333-4444-555555555555';
const epoch = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
const first = '11111111-1111-1111-1111-111111111111';
const second = '22222222-2222-2222-2222-222222222222';
const config =
    '{"bundle id":"com.apple.NativeFace","customization":{"color":"opaque-token"},"complications":{"bottom":{"descriptor":{"intent":"opaque"}}}}';
Map<String, dynamic> report(
  int time, {
  List<String> order = const [first, second],
  String selected = first,
  String json = config,
}) => {
  'connected': true,
  'faceCollectionKnown': true,
  'faceCollectionPair': pair,
  'faceCollectionEpoch': epoch,
  'faceCollectionObservedAt': time,
  'faceCollectionComplete': true,
  'faceCollectionOrderKnown': true,
  'faceCollectionSelectionKnown': true,
  'activeFaceId': selected,
  'faceCollectionOrder': order,
  'faceCollectionFaces': [
    for (final id in order)
      {
        'id': id,
        'bundle': 'com.apple.NativeFace',
        'configurationBytes': utf8.encode(json).length,
        'configuration': Uint8List.fromList(utf8.encode(json)),
        'archiveAvailable': true,
      },
  ],
};
Uint8List zip(String json, {String resource = 'Resources/photo.jpg'}) {
  final content = utf8.encode(json);
  final value = Archive()
    ..addFile(ArchiveFile('face.json', content.length, content))
    ..addFile(ArchiveFile(resource, 4, [0, 1, 2, 3]));
  return Uint8List.fromList(ZipEncoder().encode(value)!);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  test(
    'Only correlated native readback proof can confirm Watch-normalized intent bytes',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final stamp = DateTime.now().millisecondsSinceEpoch;
      events.add({
        'type': 'connection',
        'data': report(stamp, order: [first]),
      });
      await Future<void>.delayed(Duration.zero);
      final controller = NativeFaceController(bridge);
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {
          'status': 'QUEUED',
          'faceId': second,
          'requestId': second,
        },
      );
      final result = controller.duplicate(first);
      await Future<void>.delayed(Duration.zero);
      for (final status in ['APP_ACK_RECEIVED', 'APP_RESPONSE_RECEIVED']) {
        events.add({
          'type': 'operation',
          'data': {'requestId': second, 'epoch': epoch, 'status': status},
        });
      }
      events.add({
        'type': 'connection',
        'data': report(
          stamp + 1,
          order: [first, second],
          selected: second,
          json: config.replaceFirst(
            '"intent":"opaque"',
            '"intent":"rearchived"',
          ),
        ),
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, isTrue);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': first,
          'epoch': epoch,
          'status': 'NATIVE_FACE_APPLIED',
        },
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, isTrue);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': second,
          'epoch': epoch,
          'status': 'NATIVE_FACE_APPLIED',
        },
      });
      expect(await result, isTrue);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  test(
    'In-place resources stage the existing UUID and original hash and require native resource proof',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': report(now)});
      await Future<void>.delayed(Duration.zero);
      final controller = NativeFaceController(bridge);
      final archive = zip(config), hash = sha256.convert(archive).toString();
      final uploaded = BytesBuilder();
      final methods = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        methods.add(call.method);
        final args = call.arguments as Map;
        switch (call.method) {
          case 'beginNativeFaceImport':
            expect(args['faceId'], first);
            expect(args['baselineHash'], hash);
            return {'status': 'UPLOADING', 'uploadId': second, 'offset': 0};
          case 'appendNativeFaceImport':
            expect(args['offset'], uploaded.length);
            uploaded.add(args['chunk'] as Uint8List);
            return {'status': 'UPLOADING', 'offset': uploaded.length};
          case 'finishNativeFaceImport':
            return {'status': 'QUEUED', 'faceId': first, 'requestId': second};
          default:
            fail('Unexpected mutation: ${call.method}');
        }
      });
      final result = controller.replaceResources(
        first,
        archive,
        originalJson: config,
        originalArchiveHash: hash,
        pair: pair,
        epoch: epoch,
      );
      await Future<void>.delayed(Duration.zero);
      expect(methods, [
        'beginNativeFaceImport',
        'appendNativeFaceImport',
        'finishNativeFaceImport',
      ]);
      expect(uploaded.takeBytes(), archive);
      events.add({'type': 'connection', 'data': report(now + 1)});
      events.add({
        'type': 'operation',
        'data': {
          'requestId': second,
          'epoch': epoch,
          'status': 'APP_ACK_RECEIVED',
        },
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, isTrue);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': second,
          'epoch': first,
          'status': 'NATIVE_FACE_APPLIED',
        },
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, isTrue);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': second,
          'epoch': epoch,
          'status': 'NATIVE_FACE_APPLIED',
        },
      });
      expect(await result, isTrue);
      expect(controller.collection.selected, first);
      expect(controller.collection.ordered, [first, second]);
      final calls = methods.length;
      expect(
        await controller.replaceResources(
          first,
          archive,
          originalJson: 'stale',
          originalArchiveHash: hash,
          pair: pair,
          epoch: epoch,
        ),
        isFalse,
      );
      expect(methods.length, calls);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  test(
    'Native packages preserve resource bytes and opaque descriptors, while local layouts are rejected',
    () {
      final bytes = zip(config);
      final decoded = NativeWatchFaceArchive.configuration(bytes);
      expect(decoded['customization']['color'], 'opaque-token');
      expect(
        decoded['complications']['bottom']['descriptor']['intent'],
        'opaque',
      );
      expect(
        () => NativeWatchFaceArchive.configuration(zip('{"bundle_id":"fake"}')),
        throwsFormatException,
      );
      expect(
        () => NativeWatchFaceArchive.configuration(
          zip(config, resource: '../escape'),
        ),
        throwsFormatException,
      );
      expect(
        () => NativeWatchFaceArchive.configuration(
          Uint8List(NativeWatchFaceArchive.maxBytes + 1),
        ),
        throwsFormatException,
      );
    },
  );
  test(
    'Malformed configuration projections never become editable observations',
    () {
      final raw = report(1000);
      final row = (raw['faceCollectionFaces'] as List).first as Map;
      row['configuration'] = Uint8List.fromList(
        utf8.encode(
          config.replaceAll('com.apple.NativeFace', 'another.bundle'),
        ),
      );
      expect(NativeFaceCollection.fromBridge(raw).known, isFalse);
      final good = NativeFaceCollection.fromBridge(report(1000));
      final copy = good.faces.first.configuration!;
      copy['customization']['color'] = 'changed';
      expect(
        good.faces.first.configuration!['customization']['color'],
        'opaque-token',
      );
    },
  );
  test(
    'Removal protects final face and confirms absence, retained selection and exact order from readback',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': report(now)});
      await Future<void>.delayed(Duration.zero);
      var calls = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls++;
        expect(call.method, 'removeNativeFace');
        expect(call.arguments['faceId'], first);
        return {'status': 'QUEUED', 'faceId': first};
      });
      final result = controller.remove(first);
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, isTrue);
      expect(await controller.select(second), isFalse);
      events.add({
        'type': 'connection',
        'data': report(now + 1, selected: second),
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.waiting);
      events.add({
        'type': 'connection',
        'data': report(now + 2, selected: second, order: [second]),
      });
      expect(await result, isTrue);
      expect(calls, 1);
      expect(await controller.remove(second), isFalse);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  test(
    'Reorder queue does not change order, and observation arriving before receipt can confirm later',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': report(now)});
      await Future<void>.delayed(Duration.zero);
      final receipt = Completer<Map<String, dynamic>>();
      messenger.setMockMethodCallHandler(channel, (call) {
        expect(call.method, 'reorderNativeFaces');
        return receipt.future;
      });
      final result = controller.reorder([second, first]);
      await Future<void>.delayed(Duration.zero);
      expect(controller.collection.ordered, [first, second]);
      events.add({
        'type': 'connection',
        'data': report(now + 1, order: [second, first]),
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.waiting);
      receipt.complete({'status': 'QUEUED'});
      expect(await result, isTrue);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  test(
    'Configuration edits retain unknown fields and require matching pair epoch original and fresh structural equality',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': report(now)});
      await Future<void>.delayed(Duration.zero);
      final changed = config.replaceAll('opaque-token', 'new-token');
      var calls = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls++;
        expect(call.method, 'updateNativeFace');
        final value = jsonDecode(
          utf8.decode(call.arguments['configuration'] as Uint8List),
        );
        expect(
          value['complications']['bottom']['descriptor']['intent'],
          'opaque',
        );
        return {'status': 'QUEUED', 'faceId': first, 'requestId': first};
      });
      expect(
        await controller.update(
          first,
          changed,
          originalJson: config,
          pair: second,
          epoch: epoch,
        ),
        isFalse,
      );
      expect(calls, 0);
      final result = controller.update(
        first,
        changed,
        originalJson: config,
        pair: pair,
        epoch: epoch,
      );
      await Future<void>.delayed(Duration.zero);
      events.add({'type': 'connection', 'data': report(now + 1)});
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.waiting);
      events.add({
        'type': 'connection',
        'data': report(now + 2, json: changed),
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.waiting);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': first,
          'epoch': epoch,
          'status': 'NATIVE_FACE_APPLIED',
        },
      });
      expect(await result, isTrue);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  test(
    'Reconnect and late receipts cannot confirm a mutation or restart it',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': report(now)});
      await Future<void>.delayed(Duration.zero);
      final receipt = Completer<Map<String, dynamic>>();
      messenger.setMockMethodCallHandler(channel, (_) => receipt.future);
      final result = controller.select(second);
      await Future<void>.delayed(Duration.zero);
      events.add({
        'type': 'connection',
        'data': {
          ...report(now + 1, selected: second),
          'faceCollectionEpoch': first,
        },
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.connectionChanged);
      receipt.complete({'status': 'QUEUED', 'faceId': second});
      expect(await result, isFalse);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  test(
    'Import requires correlated resource proof even when projected configuration and selection match',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({
        'type': 'connection',
        'data': report(now, order: [first]),
      });
      await Future<void>.delayed(Duration.zero);
      final bytes = zip(config);
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'addNativeFace');
        expect(call.arguments['archive'], orderedEquals(bytes));
        return {'status': 'QUEUED', 'faceId': second, 'requestId': second};
      });
      final result = controller.import(bytes);
      await Future<void>.delayed(Duration.zero);
      events.add({'type': 'connection', 'data': report(now + 1)});
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.waiting);
      events.add({
        'type': 'connection',
        'data': report(now + 2, selected: second),
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.waiting);
      for (final proof in [
        {'requestId': second, 'epoch': epoch, 'status': 'APP_ACK_RECEIVED'},
        {'requestId': first, 'epoch': epoch, 'status': 'NATIVE_FACE_APPLIED'},
        {'requestId': second, 'epoch': first, 'status': 'NATIVE_FACE_APPLIED'},
      ]) {
        events.add({'type': 'operation', 'data': proof});
      }
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.waiting);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': second,
          'epoch': epoch,
          'status': 'NATIVE_FACE_APPLIED',
        },
      });
      expect(await result, isTrue);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  testWidgets(
    'Timeout releases the writer and an unknown result never becomes success',
    (tester) async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      events.add({
        'type': 'connection',
        'data': report(DateTime.now().millisecondsSinceEpoch),
      });
      await tester.pump();
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {'status': 'QUEUED', 'faceId': second},
      );
      final result = controller.select(second);
      await tester.pump();
      await tester.pump(const Duration(seconds: 271));
      expect(await result, isFalse);
      expect(controller.result, NativeFaceResult.unknown);
      expect(controller.busy, isFalse);
      controller.dispose();
      bridge.dispose();
      unawaited(events.close());
      await tester.pump();
    },
  );

  test(
    'Large imports use bounded contiguous chunks and cancel rejected transfers',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      events.add({
        'type': 'connection',
        'data': report(DateTime.now().millisecondsSinceEpoch),
      });
      await Future<void>.delayed(Duration.zero);
      final source = Uint8List.fromList(List.generate(200000, (i) => i % 251));
      final received = BytesBuilder();
      var fail = false, cancelled = 0, finishes = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        final args = call.arguments;
        switch (call.method) {
          case 'beginNativeFaceImport':
            expect(args['pairId'], pair);
            expect(args['epoch'], epoch);
            expect(args['total'], source.length);
            return {'status': 'UPLOADING', 'uploadId': second};
          case 'appendNativeFaceImport':
            expect(args['offset'], received.length);
            final chunk = args['chunk'] as Uint8List;
            expect(chunk.length, lessThanOrEqualTo(65536));
            if (fail) return {'status': 'REJECTED'};
            received.add(chunk);
            return {'status': 'UPLOADING', 'offset': received.length};
          case 'finishNativeFaceImport':
            finishes++;
            expect(received.toBytes(), orderedEquals(source));
            return {'status': 'QUEUED', 'faceId': second, 'requestId': second};
          case 'cancelNativeFaceImport':
            cancelled++;
            return {'status': 'CANCELLED'};
        }
        throw StateError('Unexpected method ${call.method}');
      });
      expect((await bridge.addNativeFace(source)).status, 'QUEUED');
      expect(finishes, 1);
      expect(cancelled, 0);
      received.clear();
      fail = true;
      expect((await bridge.addNativeFace(source)).status, 'REJECTED');
      expect(finishes, 1);
      expect(cancelled, 1);
      bridge.dispose();
      await events.close();
    },
  );

  test(
    'Exports verify the complete hash and reject package changes between chunks',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      events.add({
        'type': 'connection',
        'data': report(DateTime.now().millisecondsSinceEpoch),
      });
      await Future<void>.delayed(Duration.zero);
      final bytes = Uint8List.fromList(List.generate(150000, (i) => i % 239));
      final hash = sha256.convert(bytes).toString();
      var corrupt = false, change = false;
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'exportNativeFace');
        final offset = call.arguments['offset'] as int;
        if (offset > 0) expect(call.arguments['sha256'], hash);
        final end = (offset + 65536).clamp(0, bytes.length);
        final chunk = Uint8List.fromList(bytes.sublist(offset, end));
        if (corrupt && offset == 0) chunk[0] ^= 1;
        return {
          'status': 'EXPORTED',
          'offset': offset,
          'total': bytes.length,
          'archive': chunk,
          'sha256': change && offset > 0 ? '0' * 64 : hash,
        };
      });
      expect(
        await bridge.exportNativeFace(first, pair, epoch),
        orderedEquals(bytes),
      );
      corrupt = true;
      expect(await bridge.exportNativeFace(first, pair, epoch), isNull);
      corrupt = false;
      change = true;
      expect(await bridge.exportNativeFace(first, pair, epoch), isNull);
      bridge.dispose();
      await events.close();
    },
  );

  test(
    'A correlated terminal failure ends waiting even when it arrives before the queue receipt',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      events.add({
        'type': 'connection',
        'data': report(DateTime.now().millisecondsSinceEpoch),
      });
      await Future<void>.delayed(Duration.zero);
      final receipt = Completer<Map<String, dynamic>>();
      messenger.setMockMethodCallHandler(channel, (_) => receipt.future);
      final result = controller.select(second);
      await Future<void>.delayed(Duration.zero);
      events.add({
        'type': 'operation',
        'data': {'requestId': first, 'epoch': epoch, 'status': 'FAILED'},
      });
      events.add({
        'type': 'operation',
        'data': {'requestId': second, 'epoch': epoch, 'status': 'UNKNOWN'},
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, isTrue);
      receipt.complete({
        'status': 'QUEUED',
        'faceId': second,
        'requestId': second,
      });
      expect(await result, isFalse);
      expect(controller.result, NativeFaceResult.unknown);
      expect(controller.busy, isFalse);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  test(
    'Refreshing a stale baseline cannot report a mutation as applied if the Watch changed it',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': report(now - 250000)});
      await Future<void>.delayed(Duration.zero);
      var writes = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'refreshFaceCollection') {
          events.add({
            'type': 'connection',
            'data': report(now, selected: second),
          });
          return {'status': 'QUEUED', 'requestId': first};
        }
        writes++;
        return {'status': 'QUEUED', 'faceId': second};
      });
      expect(await controller.select(second), false);
      expect(writes, 0);
      expect(controller.result, NativeFaceResult.conflict);
      expect(controller.lastOperationWasRefresh, false);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  test(
    'A refresh correlates its own terminal failure and releases the shared writer',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      events.add({
        'type': 'connection',
        'data': report(DateTime.now().millisecondsSinceEpoch),
      });
      await Future<void>.delayed(Duration.zero);
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {'status': 'QUEUED', 'requestId': first},
      );
      final pending = controller.refresh();
      await Future<void>.delayed(Duration.zero);
      events.add({
        'type': 'operation',
        'data': {'epoch': epoch, 'requestId': first, 'status': 'FAILED'},
      });
      expect(await pending, false);
      expect(controller.busy, false);
      expect(controller.lastOperationWasRefresh, true);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
  test(
    'A delayed complete refresh replaces the stale timeout status only in the same pair and epoch',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final stamp = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': report(stamp)});
      await Future<void>.delayed(Duration.zero);
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {'status': 'QUEUED', 'requestId': first},
      );
      final pending = controller.refresh();
      await Future<void>.delayed(Duration.zero);
      events.add({
        'type': 'operation',
        'data': {'epoch': epoch, 'requestId': first, 'status': 'UNKNOWN'},
      });
      expect(await pending, false);
      events.add({
        'type': 'connection',
        'data': {...report(stamp + 1), 'faceCollectionComplete': false},
      });
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.unknown);
      events.add({'type': 'connection', 'data': report(stamp + 2)});
      await Future<void>.delayed(Duration.zero);
      expect(controller.result, NativeFaceResult.applied);
      expect(controller.busy, false);
      expect(controller.lastOperationWasRefresh, true);
      controller.dispose();
      bridge.dispose();
      await events.close();
    },
  );
}
