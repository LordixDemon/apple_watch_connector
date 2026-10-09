import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/native_face_controller.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_library_screen.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/widgets/native_face_collection_panel.dart';

const _pair = '5e111a6b-a56a-5b3a-b795-1c9799c1d92a';
const _epoch = '12345678-1234-1234-1234-123456789012';
const _request = '12345678-5555-5555-5555-123456789012';
final _ids = List.generate(
  8,
  (i) => '00000000-0000-4000-8000-${(i + 1).toString().padLeft(12, '0')}',
);

Map<String, dynamic> _report(
  int stamp, {
  List<String>? order,
  String epoch = _epoch,
}) {
  final ids = order ?? _ids.take(3).toList();
  return {
    'connected': true,
    'faceCollectionKnown': true,
    'faceCollectionPair': _pair,
    'faceCollectionEpoch': epoch,
    'faceCollectionObservedAt': stamp,
    'faceCollectionComplete': true,
    'faceCollectionOrderKnown': true,
    'faceCollectionSelectionKnown': true,
    'activeFaceId': _ids.first,
    'faceCollectionOrder': ids,
    'faceCollectionFaces': [
      for (final id in ids)
        {
          'id': id,
          'bundle': 'com.apple.NTKLeghornFaceBundle',
          'configurationBytes': 6665,
          'archiveAvailable': true,
        },
    ],
  };
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late StreamController<dynamic> events;
  late WatchBridgeService bridge;
  late NativeFaceController controller;
  late List<MethodCall> calls;
  late int stamp;

  void initialize() {
    events = StreamController<dynamic>();
    bridge = WatchBridgeService(events: events.stream);
    controller = NativeFaceController(bridge);
    calls = [];
    stamp = DateTime.now().millisecondsSinceEpoch;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {'status': 'QUEUED', 'requestId': _request};
    });
  }

  tearDown(() async {
    controller.dispose();
    bridge.dispose();
    await events.close();
    messenger.setMockMethodCallHandler(channel, null);
  });

  String title(String id) => 'Face ${_ids.indexOf(id) + 1}';
  Future<void> mount(WidgetTester tester) async {
    initialize();
    events.add({'type': 'connection', 'data': _report(stamp)});
    await tester.pump();
    await tester.pumpWidget(
      CupertinoApp(
        home: NativeFaceLibraryScreen(
          controller: controller,
          faceTitle: (face) => title(face.id),
          openFace: (_) {},
        ),
      ),
    );
  }

  testWidgets(
    'Edit drag stays pending until a fresh native order; one writer owns it',
    (tester) async {
      await mount(tester);
      final list = tester.widget<SliverReorderableList>(
        find.byType(SliverReorderableList),
      );
      list.onReorderStart!(0);
      list.onReorderItem!(0, 2);
      await tester.pump();
      expect(calls.single.method, 'reorderNativeFaces');
      expect(calls.single.arguments['faceIds'], [_ids[1], _ids[2], _ids[0]]);
      expect(controller.collection.ordered, _ids.take(3).toList());
      expect(controller.busy, isTrue);
      expect(find.textContaining('Changes confirmed'), findsNothing);
      expect(await controller.select(_ids[1]), isFalse);
      expect(calls, hasLength(1));
      events.add({
        'type': 'operation',
        'data': {
          'requestId': _request,
          'epoch': _epoch,
          'status': 'APP_ACK_RECEIVED',
        },
      });
      await tester.pump();
      expect(controller.busy, isTrue);
      events.add({
        'type': 'connection',
        'data': _report(stamp + 1, order: [_ids[1], _ids[2], _ids[0]]),
      });
      await tester.pump();
      expect(controller.result, NativeFaceResult.applied);
      expect(controller.collection.ordered, [_ids[1], _ids[2], _ids[0]]);
      expect(find.byType(CupertinoActivityIndicator), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'Changed order or reconnect during drag cannot move another face',
    (tester) async {
      await mount(tester);
      var list = tester.widget<SliverReorderableList>(
        find.byType(SliverReorderableList),
      );
      list.onReorderStart!(0);
      events.add({
        'type': 'connection',
        'data': _report(stamp + 1, order: [_ids[1], _ids[0], _ids[2]]),
      });
      await tester.pump();
      list.onReorderItem!(0, 2);
      await tester.pump();
      expect(calls, isEmpty);
      list = tester.widget<SliverReorderableList>(
        find.byType(SliverReorderableList),
      );
      list.onReorderStart!(0);
      events.add({
        'type': 'connection',
        'data': _report(stamp + 2, epoch: _request),
      });
      await tester.pump();
      list.onReorderItem!(0, 2);
      await tester.pump();
      expect(calls, isEmpty);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'Deletion rechecks the pair after confirmation; last face is protected',
    (tester) async {
      await mount(tester);
      await tester.tap(
        find
            .widgetWithIcon(CupertinoButton, CupertinoIcons.minus_circle_fill)
            .first,
      );
      await tester.pumpAndSettle();
      expect(find.byType(CupertinoAlertDialog), findsOneWidget);
      events.add({
        'type': 'connection',
        'data': _report(stamp + 1, epoch: _request),
      });
      await tester.pump();
      await tester.tap(
        find.widgetWithText(CupertinoDialogAction, 'Delete Watch Face'),
      );
      await tester.pumpAndSettle();
      expect(calls, isEmpty);
      events.add({
        'type': 'connection',
        'data': _report(stamp + 2, order: [_ids.first], epoch: _request),
      });
      await tester.pump();
      await tester.pump();
      final button = tester.widget<CupertinoButton>(
        find.widgetWithIcon(CupertinoButton, CupertinoIcons.minus_circle_fill),
      );
      expect(button.onPressed, isNull);
      expect(controller.collection.ordered, [_ids.first]);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'Native drag handle is operable and rejected reorders restore native order',
    (tester) async {
      await mount(tester);
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {'status': 'REJECTED'};
      });
      final handles = find.byType(ReorderableDragStartListener);
      final gesture = await tester.startGesture(
        tester.getCenter(handles.first),
      );
      await gesture.moveTo(
        tester.getCenter(handles.last) + const Offset(0, 30),
      );
      await tester.pump(const Duration(milliseconds: 500));
      await gesture.up();
      await tester.pumpAndSettle();
      expect(calls.single.method, 'reorderNativeFaces');
      expect(controller.result, NativeFaceResult.rejected);
      expect(controller.collection.ordered, _ids.take(3).toList());
      final rows = [
        for (final id in _ids.take(3))
          tester.getTopLeft(find.byKey(ValueKey(id))).dy,
      ];
      expect(rows[0], lessThan(rows[1]));
      expect(rows[1], lessThan(rows[2]));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('Accessible reorder actions share the guarded native writer', (
    tester,
  ) async {
    await mount(tester);
    final handle = tester.widget<Semantics>(
      find.byWidgetPredicate(
        (widget) =>
            widget is Semantics && widget.properties.label == 'Reorder Face 2',
      ),
    );
    final actions = handle.properties.customSemanticsActions!;
    expect(actions.keys.map((action) => action.label), [
      'Move Earlier',
      'Move Later',
    ]);
    actions.entries.first.value();
    await tester.pump();
    expect(calls.single.method, 'reorderNativeFaces');
    expect(calls.single.arguments['faceIds'], [_ids[1], _ids[0], _ids[2]]);
    final busyHandle = tester.widget<Semantics>(
      find.byWidgetPredicate(
        (widget) =>
            widget is Semantics && widget.properties.label == 'Reorder Face 2',
      ),
    );
    expect(busyHandle.properties.customSemanticsActions, isEmpty);
    events.add({
      'type': 'connection',
      'data': _report(stamp + 1, order: [_ids[1], _ids[0], _ids[2]]),
    });
    await tester.pump();
    expect(controller.result, NativeFaceResult.applied);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'Edit library stays scrollable on a narrow display with large text',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(280, 640);
      addTearDown(tester.view.reset);
      initialize();
      events.add({'type': 'connection', 'data': _report(stamp, order: _ids)});
      await tester.pump();
      await tester.pumpWidget(
        CupertinoApp(
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(280, 640),
              textScaler: TextScaler.linear(2.4),
            ),
            child: NativeFaceLibraryScreen(
              controller: controller,
              faceTitle: (face) => '${title(face.id)} long native title',
              openFace: (_) {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.scrollUntilVisible(
        find.text('Refresh from Watch'),
        250,
        scrollable: find.byType(Scrollable),
      );
      expect(find.text('Refresh from Watch'), findsOneWidget);
      expect(tester.takeException(), isNull);
      expect(calls, isEmpty);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'My Faces scrolls horizontally with scaled titles and opens detail without selection',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      initialize();
      tester.view.physicalSize = const Size(280, 640);
      addTearDown(tester.view.reset);
      events.add({'type': 'connection', 'data': _report(stamp, order: _ids)});
      await tester.pump();
      String? opened;
      await tester.pumpWidget(
        CupertinoApp(
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(280, 640),
              textScaler: TextScaler.linear(2.4),
            ),
            child: CupertinoPageScaffold(
              child: NativeFaceCollectionPanel(
                controller: controller,
                faceTitle: (face) => '${title(face.id)} long native title',
                openFace: (face) => opened = face.id,
                manage: () {},
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Create Copy'), findsNothing);
      expect(find.text('Refresh from Watch'), findsNothing);
      await tester.tap(find.text('Face 1 long native title'));
      expect(opened, _ids.first);
      expect(calls, isEmpty);
      final list = find.byKey(const ValueKey('native-my-faces-carousel'));
      for (var i = 0; i < 7; i++) {
        await tester.drag(list, const Offset(-180, 0));
        await tester.pumpAndSettle();
      }
      await tester.tap(find.text('Face 8 long native title'));
      expect(opened, _ids.last);
      expect(calls, isEmpty);
      expect(controller.collection.selected, _ids.first);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
