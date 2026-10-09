import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:apple_watch_companion/controllers/native_face_controller.dart';
import 'package:apple_watch_companion/models/native_face_collection.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/widgets/native_face_collection_panel.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_library_screen.dart';

const pair = '5e111a6b-a56a-5b3a-b795-1c9799c1d92a';
const epoch = '12345678-1234-1234-1234-123456789012';
const face = '3122508d-c08f-536e-bc37-d7f82cb8675d';
const secondFace = '12345678-5555-5555-5555-123456789012';
Map<String, dynamic> report([int time = 1000]) => {
  'connected': true,
  'faceCollectionKnown': true,
  'faceCollectionPair': pair,
  'faceCollectionEpoch': epoch,
  'faceCollectionObservedAt': time,
  'faceCollectionComplete': true,
  'faceCollectionOrderKnown': true,
  'faceCollectionSelectionKnown': true,
  'activeFaceId': face,
  'faceCollectionOrder': [face],
  'faceCollectionFaces': [
    {
      'id': face,
      'bundle': 'com.apple.NTKLeghornFaceBundle',
      'configurationBytes': 6665,
      'archiveAvailable': true,
    },
  ],
};

void main() {
  setUpAll(initializeDateFormatting);
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'Native UUIDs, selected face, order and observation time are immutable facts',
    () {
      final value = NativeFaceCollection.fromBridge(report());
      expect(value.complete, isTrue);
      expect(value.selected, face);
      expect(value.observedAt, 1000);
      expect(value.faces.single.configurationBytes, 6665);
      expect(value.displayOrder, [face]);
      expect(() => value.faces.clear(), throwsUnsupportedError);
      expect(() => value.ordered.clear(), throwsUnsupportedError);
      expect(
        NativeFaceCollection.fromBridge({
          ...report(),
          'connected': false,
        }).known,
        isFalse,
      );
      expect(NativeFaceCollection.fromBridge({}).known, isFalse);
    },
  );

  test(
    'Unknown, incomplete and empty complete inventories remain distinct',
    () {
      final unknown = NativeFaceCollection.fromBridge({
        'connected': true,
        'faceCollectionKnown': false,
        'faceCollectionPair': pair,
        'faceCollectionEpoch': epoch,
      });
      expect(unknown.known, isFalse);
      expect(unknown.pair, pair);
      expect(unknown.epoch, epoch);
      final partial = NativeFaceCollection.fromBridge({
        ...report(),
        'faceCollectionComplete': false,
      });
      expect(partial.known, isTrue);
      expect(partial.complete, isFalse);
      final empty = NativeFaceCollection.fromBridge({
        ...report(),
        'faceCollectionFaces': [],
        'faceCollectionOrder': [],
        'faceCollectionSelectionKnown': false,
        'activeFaceId': '',
      });
      expect(empty.known, isTrue);
      expect(empty.complete, isTrue);
      expect(empty.faces, isEmpty);
    },
  );

  test(
    'Malformed and contradictory complete inventories never show installed faces',
    () {
      for (final patch in <Map<String, dynamic>>[
        {'faceCollectionPair': '1-1-1-1-1'},
        {'faceCollectionObservedAt': 0},
        {'faceCollectionFaces': []},
        {
          'faceCollectionOrder': [face, face],
        },
        {'activeFaceId': 'local_catalog_face'},
        {'faceCollectionSelectionKnown': false},
        {'faceCollectionOrderKnown': false},
        {
          'faceCollectionFaces': [
            {'id': face, 'bundle': '', 'configurationBytes': 0},
          ],
        },
        {
          'faceCollectionFaces': List.filled(
            257,
            report()['faceCollectionFaces'][0],
          ),
        },
      ]) {
        expect(
          NativeFaceCollection.fromBridge({...report(), ...patch}).known,
          isFalse,
          reason: '$patch',
        );
      }
    },
  );

  test(
    'QUEUED refresh does not manufacture a newer observation or active face',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {'status': 'QUEUED'};
      });
      events.add({'type': 'connection', 'data': report()});
      await Future<void>.delayed(Duration.zero);
      expect(await bridge.refreshFaceCollection(), 'QUEUED');
      expect(calls.single.method, 'refreshFaceCollection');
      expect(bridge.faceCollection.observedAt, 1000);
      expect(bridge.currentDevice.activeFaceId, face);
      events.add({
        'type': 'connection',
        'data': {'connected': false},
      });
      await Future<void>.delayed(Duration.zero);
      expect(bridge.faceCollection.known, isFalse);
      expect(bridge.currentDevice.activeFaceId, '');
      bridge.dispose();
      await events.close();
    },
  );

  test(
    'Native face receipts retain target UUID without changing observed collection',
    () async {
      final bridge = WatchBridgeService(events: const Stream.empty());
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'duplicateNativeFace');
        expect(call.arguments, {'sourceFaceId': face});
        return {'status': 'QUEUED', 'faceId': secondFace};
      });
      expect(await bridge.duplicateNativeFace(face), (
        status: 'QUEUED',
        faceId: secondFace,
      ));
      expect(bridge.faceCollection.known, isFalse);
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {'status': 'QUEUED', 'faceId': 'wayfinder'},
      );
      expect((await bridge.selectNativeFace(face)).faceId, isNull);
      bridge.dispose();
    },
  );

  Map<String, dynamic> twoFaces(int time, String selected) => {
    ...report(time),
    'activeFaceId': selected,
    'faceCollectionOrder': [face, secondFace],
    'faceCollectionFaces': [
      ...(report(time)['faceCollectionFaces'] as List),
      {
        'id': secondFace,
        'bundle': 'com.apple.NTKLeghornFaceBundle',
        'configurationBytes': 6665,
      },
    ],
  };

  Future<
    ({
      StreamController<dynamic> events,
      WatchBridgeService bridge,
      NativeFaceController controller,
      List<MethodCall> calls,
    })
  >
  mountPanel(
    WidgetTester tester,
    Map<String, dynamic> data, {
    Future<dynamic> Function(MethodCall)? reply,
    bool library = false,
  }) async {
    final events = StreamController<dynamic>();
    final bridge = WatchBridgeService(events: events.stream);
    final controller = NativeFaceController(bridge);
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (reply != null) return await reply(call);
      return {
        'status': 'QUEUED',
        'requestId': secondFace,
        'faceId': secondFace,
      };
    });
    events.add({'type': 'connection', 'data': data});
    await tester.pump();
    await tester.pumpWidget(
      CupertinoApp(
        home: CupertinoPageScaffold(
          child: library
              ? NativeFaceLibraryScreen(
                  controller: controller,
                  faceTitle: (face) => face.id,
                  openFace: (_) {},
                )
              : NativeFaceCollectionPanel(controller: controller),
        ),
      ),
    );
    addTearDown(() async {
      controller.dispose();
      bridge.dispose();
      await events.close();
    });
    return (
      events: events,
      bridge: bridge,
      controller: controller,
      calls: calls,
    );
  }

  testWidgets(
    'Copy shares the main panel writer and requires correlated native resource proof',
    (tester) async {
      final stamp = DateTime.now().millisecondsSinceEpoch;
      final h = await mountPanel(tester, report(stamp));
      unawaited(h.controller.duplicate(face));
      await tester.pump();
      expect(h.controller.busy, isTrue);
      expect(h.calls.single.method, 'duplicateNativeFace');
      expect(h.controller.collection.selected, face);
      // A gallery/editor operation cannot get past the main panel's writer.
      expect(await h.controller.select(face), false);
      expect(h.calls, hasLength(1));
      h.events.add({
        'type': 'connection',
        'data': twoFaces(stamp + 1, secondFace),
      });
      await tester.pump();
      expect(h.controller.busy, isTrue);
      expect(find.textContaining('Changes confirmed'), findsNothing);
      for (final status in ['APP_ACK_RECEIVED', 'APP_RESPONSE_RECEIVED']) {
        h.events.add({
          'type': 'operation',
          'data': {'requestId': secondFace, 'epoch': epoch, 'status': status},
        });
      }
      await tester.pump();
      expect(h.controller.busy, isTrue);
      h.events.add({
        'type': 'operation',
        'data': {
          'requestId': secondFace,
          'epoch': epoch,
          'status': 'NATIVE_FACE_APPLIED',
        },
      });
      await tester.pump();
      expect(h.controller.result, NativeFaceResult.applied);
      expect(find.byType(CupertinoActivityIndicator), findsNothing);
      await tester.pumpWidget(const CupertinoApp(home: SizedBox()));
    },
  );

  testWidgets(
    'Edit sheet refresh reacts immediately to HAL failure and accepts a later complete observation',
    (tester) async {
      final stamp = DateTime.now().millisecondsSinceEpoch;
      final h = await mountPanel(tester, report(stamp), library: true);
      await tester.tap(find.text('Refresh from Watch'));
      await tester.pump();
      expect(h.controller.busy, isTrue);
      expect(
        find.textContaining('Waiting for the watch face collection'),
        findsOneWidget,
      );
      h.events.add({'type': 'connection', 'data': report(stamp)});
      h.events.add({
        'type': 'operation',
        'data': {'requestId': face, 'epoch': epoch, 'status': 'UNKNOWN'},
      });
      await tester.pump();
      expect(h.controller.busy, isTrue);
      h.events.add({
        'type': 'operation',
        'data': {'requestId': secondFace, 'epoch': epoch, 'status': 'UNKNOWN'},
      });
      await tester.pump();
      expect(h.controller.busy, isFalse);
      expect(find.byType(CupertinoActivityIndicator), findsNothing);
      expect(
        find.textContaining('New collection not received yet'),
        findsOneWidget,
      );
      h.events.add({
        'type': 'connection',
        'data': {...report(stamp + 1), 'faceCollectionComplete': false},
      });
      await tester.pump();
      expect(h.controller.result, NativeFaceResult.unknown);
      h.events.add({'type': 'connection', 'data': report(stamp + 2)});
      await tester.pump();
      expect(h.controller.result, NativeFaceResult.applied);
      // Replacing the partial-inventory sliver is laid out on the next frame.
      await tester.pump();
      expect(
        find.textContaining('Collection received from the watch.'),
        findsOneWidget,
      );
      expect(h.calls, hasLength(1));
      await tester.pumpWidget(const CupertinoApp(home: SizedBox()));
    },
  );

  testWidgets(
    'A selection observed before its queue receipt does not confirm early',
    (tester) async {
      final stamp = DateTime.now().millisecondsSinceEpoch;
      final receipt = Completer<dynamic>();
      final h = await mountPanel(
        tester,
        twoFaces(stamp, face),
        reply: (_) => receipt.future,
      );
      unawaited(h.controller.select(secondFace));
      await tester.pump();
      h.events.add({
        'type': 'connection',
        'data': twoFaces(stamp + 1, secondFace),
      });
      await tester.pump();
      expect(h.controller.busy, isTrue);
      receipt.complete({
        'status': 'QUEUED',
        'faceId': secondFace,
        'requestId': secondFace,
      });
      await tester.pump();
      expect(h.controller.result, NativeFaceResult.applied);
      expect(find.byType(CupertinoActivityIndicator), findsNothing);
      await tester.pumpWidget(const CupertinoApp(home: SizedBox()));
    },
  );

  testWidgets(
    'First refresh of an unknown inventory requires a complete native observation',
    (tester) async {
      final h = await mountPanel(tester, {
        'connected': true,
        'faceCollectionPair': pair,
        'faceCollectionEpoch': epoch,
      }, library: true);
      await tester.tap(find.text('Refresh from Watch'));
      await tester.pump();
      h.events.add({
        'type': 'connection',
        'data': {...report(), 'faceCollectionComplete': false},
      });
      await tester.pump();
      expect(h.controller.busy, isTrue);
      h.events.add({'type': 'connection', 'data': report()});
      await tester.pump();
      expect(
        find.textContaining('Collection received from the watch.'),
        findsOneWidget,
      );
      await tester.pumpWidget(const CupertinoApp(home: SizedBox()));
    },
  );

  testWidgets(
    'Shared refresh deadline releases the edit sheet without retry and reconnect cannot confirm it',
    (tester) async {
      final stamp = DateTime.now().millisecondsSinceEpoch;
      final h = await mountPanel(tester, report(stamp), library: true);
      await tester.tap(find.text('Refresh from Watch'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 181));
      expect(h.controller.busy, isFalse);
      expect(
        find.textContaining('New collection not received yet'),
        findsOneWidget,
      );
      expect(h.calls, hasLength(1));
      h.events.add({
        'type': 'connection',
        'data': {...report(stamp + 1), 'faceCollectionEpoch': secondFace},
      });
      await tester.pump();
      expect(h.controller.result, NativeFaceResult.unknown);
      await tester.tap(find.text('Refresh from Watch'));
      await tester.pump();
      h.events.add({
        'type': 'connection',
        'data': {'connected': false},
      });
      await tester.pump();
      expect(h.controller.result, NativeFaceResult.connectionChanged);
      expect(find.textContaining('Connection changed'), findsOneWidget);
      await tester.pumpWidget(const CupertinoApp(home: SizedBox()));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Rejected refresh cannot leave a spinner or report cached collection success',
    (tester) async {
      final h = await mountPanel(
        tester,
        report(),
        reply: (_) async => {'status': 'REJECTED'},
        library: true,
      );
      await tester.tap(find.text('Refresh from Watch'));
      await tester.pump();
      expect(h.controller.busy, isFalse);
      expect(find.textContaining('Request not sent'), findsOneWidget);
      expect(
        find.textContaining('Collection received from the watch.'),
        findsNothing,
      );
      await tester.pumpWidget(const CupertinoApp(home: SizedBox()));
    },
  );
}
