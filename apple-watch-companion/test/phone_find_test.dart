import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/models/phone_find_state.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/widgets/phone_find_panel.dart';

Map<String, dynamic> snapshot({
  bool active = true,
  int behavior = 1,
  bool played = true,
}) => {
  'bridgeAvailable': true,
  'phoneFindAvailable': true,
  'phoneFlashPermission': true,
  'phoneFindKnown': true,
  'phoneFindActive': active,
  'phoneFindBehavior': behavior,
  'phoneFindLocalProbe': true,
  'phoneFindDidPlay': played,
  'phoneFindObservedAt': 1000000,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'Invalid, absent and disconnected state cannot manufacture an active phone signal',
    () {
      expect(PhoneFindState.fromBridge({}).observedAt, isNull);
      expect(
        PhoneFindState.fromBridge({
          ...snapshot(),
          'bridgeAvailable': false,
        }).active,
        isFalse,
      );
      expect(
        PhoneFindState.fromBridge(snapshot(behavior: 4)).observedAt,
        isNull,
      );
      expect(
        PhoneFindState.fromBridge(snapshot(played: false)).observedAt,
        isNull,
      );
      expect(PhoneFindState.fromBridge(snapshot(behavior: 2)).active, isTrue);
      expect(
        PhoneFindState.fromBridge(snapshot(active: false, behavior: 4)).didPlay,
        isTrue,
      );
    },
  );
  test(
    'Service only accepts exact phone stop receipt and never replays a ping event',
    () async {
      var status = 'QUEUED';
      final methods = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        methods.add(call.method);
        return {'status': status};
      });
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final states = <PhoneFindState>[];
      final subscription = bridge.phoneFindStateStream.listen(states.add);
      events.add({'type': 'pingPhone'});
      await Future<void>.delayed(Duration.zero);
      expect(states, isEmpty);
      expect(methods, isEmpty);
      events.add({
        'type': 'connection',
        'data': {...snapshot(), 'connected': true},
      });
      await Future<void>.delayed(Duration.zero);
      expect(states.last.active, isTrue);
      expect(await bridge.stopPhonePing(), isFalse);
      status = 'APPLIED';
      expect(await bridge.stopPhonePing(), isFalse);
      status = 'STOPPED';
      expect(await bridge.stopPhonePing(), isTrue);
      expect(
        bridge.phoneFindState.active,
        isTrue,
      ); // Receipt is not a new hardware snapshot.
      events.add({
        'type': 'connection',
        'data': {'connected': false, 'bridgeAvailable': false},
      });
      await Future<void>.delayed(Duration.zero);
      expect(states.last.observedAt, isNull);
      await subscription.cancel();
      bridge.dispose();
      await events.close();
    },
  );
  testWidgets(
    'Stop waits for actual observation and handles refusal without optimistic idle',
    (tester) async {
      var state = PhoneFindState.fromBridge(snapshot());
      final reply = Completer<bool>();
      int calls = 0;
      Widget panel() => CupertinoApp(
        home: CupertinoPageScaffold(
          child: PhoneFindPanel(
            state: state,
            clock: () => DateTime.fromMillisecondsSinceEpoch(1000000),
            stop: () {
              calls++;
              return reply.future;
            },
            permission: () async => false,
          ),
        ),
      );
      await tester.pumpWidget(panel());
      expect(find.text("Sound and flashlight are on"), findsOneWidget);
      expect(find.text("Local APK Test"), findsOneWidget);
      await tester.tap(find.text("Stop Alert"));
      await tester.pump();
      expect(calls, 1);
      expect(find.text("Stopping…"), findsOneWidget);
      reply.complete(false);
      await tester.pump();
      expect(find.text("Stop not confirmed"), findsOneWidget);
      expect(find.text("Sound and flashlight are on"), findsOneWidget);
      state = PhoneFindState.fromBridge({
        ...snapshot(active: false),
        'phoneFindObservedAt': 1000001,
      });
      await tester.pumpWidget(panel());
      expect(find.text("Alert stopped"), findsOneWidget);
      expect(find.text("Stop not confirmed"), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'Stale active state retains safe stop; offline state removes the action',
    (tester) async {
      var now = DateTime.fromMillisecondsSinceEpoch(1000000);
      var state = PhoneFindState.fromBridge(snapshot(behavior: 2));
      Widget panel() => CupertinoApp(
        home: CupertinoPageScaffold(
          child: PhoneFindPanel(
            state: state,
            clock: () => now,
            stop: () async => true,
            permission: () async => false,
          ),
        ),
      );
      await tester.pumpWidget(panel());
      expect(find.text("Flashlight is on"), findsOneWidget);
      now = now.add(const Duration(seconds: 6));
      await tester.pump(const Duration(seconds: 6));
      expect(find.text("Alert status is stale"), findsOneWidget);
      expect(find.text("Stop Alert"), findsOneWidget);
      state = const PhoneFindState();
      await tester.pumpWidget(panel());
      expect(find.text("Phone service unavailable"), findsOneWidget);
      expect(find.text("Stop Alert"), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'Opening permission does not grant it and None is not shown as playback',
    (tester) async {
      var state = PhoneFindState.fromBridge({
        ...snapshot(active: false, behavior: 4),
        'phoneFlashPermission': false,
      });
      Widget panel() => CupertinoApp(
        home: CupertinoPageScaffold(
          child: PhoneFindPanel(
            state: state,
            stop: () async => false,
            permission: () async => true,
          ),
        ),
      );
      await tester.pumpWidget(panel());
      expect(find.text("Request had no sound or flashlight"), findsOneWidget);
      await tester.tap(find.text("Allow Flashlight"));
      await tester.pump();
      expect(find.text("Allow Flashlight"), findsOneWidget);
      state = PhoneFindState.fromBridge(snapshot(active: false, behavior: 4));
      await tester.pumpWidget(panel());
      expect(find.text("Allow Flashlight"), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
