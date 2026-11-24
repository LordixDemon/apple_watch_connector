import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/models/notification_relay_state.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/widgets/notification_relay_panel.dart';

Map<String, dynamic> snapshot({bool access = true, bool connected = true}) => {
  'bridgeAvailable': true,
  'connected': connected,
  'notificationAccessGranted': access,
  'notificationListenerConnected': true,
  'notificationRelayEnabled': true,
  'notificationRelayReady': true,
  'notificationPendingCount': 2,
  'notificationApps': [
    {
      'packageName': 'org.example.chat',
      'name': 'Example Chat',
      'enabled': true,
    },
  ],
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'Missing permission or Watch connection cannot manufacture ready state',
    () {
      expect(NotificationRelayState.fromBridge({}).available, isFalse);
      expect(
        NotificationRelayState.fromBridge(snapshot(access: false)).ready,
        isFalse,
      );
      expect(
        NotificationRelayState.fromBridge(snapshot(connected: false)).ready,
        isFalse,
      );
      expect(NotificationRelayState.fromBridge(snapshot()).ready, isTrue);
      final state = NotificationRelayState.fromBridge({
        ...snapshot(),
        'notificationApps': [
          {'packageName': 'bad\npackage', 'name': 'Bad', 'enabled': true},
          {
            'packageName': 'org.example.chat',
            'name': 'Example Chat',
            'enabled': false,
          },
          {
            'packageName': 'org.example.chat',
            'name': 'Duplicate',
            'enabled': true,
          },
        ],
      });
      expect(state.apps.length, 1);
      expect(state.apps.single.enabled, isFalse);
    },
  );

  test(
    'Opening settings is not a permission grant and policy uses exact package',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {
          'status': call.method == 'requestNotificationAccess'
              ? 'OPENED'
              : 'STORED',
        };
      });
      final bridge = WatchBridgeService(events: const Stream.empty());
      expect(await bridge.requestNotificationAccess(), isTrue);
      expect(bridge.notifications.accessGranted, isFalse);
      expect(
        await bridge.setNotificationRelay(
          false,
          packageName: 'org.example.chat',
        ),
        'STORED',
      );
      expect(calls.last.arguments, {
        'value': false,
        'packageName': 'org.example.chat',
      });
      bridge.dispose();
    },
  );

  test(
    'Only correlated queue receipt accepts test; ACK is not display confirmation',
    () async {
      var receipt = <String, dynamic>{'status': 'QUEUED'};
      messenger.setMockMethodCallHandler(channel, (_) async => receipt);
      final bridge = WatchBridgeService(events: const Stream.empty());
      expect(
        (await bridge.queueNotificationTest('Test', 'Body')).status,
        'UNKNOWN',
      );
      receipt = {
        'status': 'QUEUED',
        'requestId': '10000000-0000-0000-0000-000000000001',
      };
      expect(await bridge.sendNotificationTest('Test', 'Body'), isTrue);
      receipt = {'status': 'APP_ACK_RECEIVED'};
      expect(await bridge.sendNotificationTest('Test', 'Body'), isFalse);
      bridge.dispose();
    },
  );

  testWidgets(
    'Permission panel remains permission-required after opening settings',
    (tester) async {
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: ListView(
              children: [
                NotificationRelayPanel(
                  state: NotificationRelayState.fromBridge(
                    snapshot(access: false),
                  ),
                  requestAccess: () async => true,
                  setPolicy: (_, {packageName}) async => 'STORED',
                  sendTest: () async =>
                      (status: 'UNAVAILABLE', requestId: null),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.tap(find.text('Allow Notification Access'));
      await tester.pumpAndSettle();
      expect(
        find.text('Allow notification access on your phone.'),
        findsOneWidget,
      );
      expect(find.text('Ready to forward phone notifications.'), findsNothing);
    },
  );

  testWidgets('Refused test does not show success', (tester) async {
    await tester.pumpWidget(
      CupertinoApp(
        home: CupertinoPageScaffold(
          child: ListView(
            children: [
              NotificationRelayPanel(
                state: NotificationRelayState.fromBridge(snapshot()),
                requestAccess: () async => true,
                setPolicy: (_, {packageName}) async => 'STORED',
                sendTest: () async => (status: 'UNAVAILABLE', requestId: null),
              ),
            ],
          ),
        ),
      ),
    );
    await tester.ensureVisible(find.text('Send Test Notification'));
    await tester.tap(find.text('Send Test Notification'));
    await tester.pumpAndSettle();
    expect(find.text('Notification Not Queued'), findsOneWidget);
    expect(find.text('Notification Queued'), findsNothing);
  });

  testWidgets(
    'Failed policy update retains observed switch and reports error',
    (tester) async {
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: ListView(
              children: [
                NotificationRelayPanel(
                  state: NotificationRelayState.fromBridge(snapshot()),
                  requestAccess: () async => false,
                  setPolicy: (_, {packageName}) async => 'REJECTED',
                  sendTest: () async =>
                      (status: 'UNAVAILABLE', requestId: null),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.tap(find.byType(CupertinoSwitch).first);
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<CupertinoSwitch>(find.byType(CupertinoSwitch).first)
            .value,
        isTrue,
      );
      expect(
        find.text('Could not save notification settings. Try again.'),
        findsOneWidget,
      );
    },
  );
}
