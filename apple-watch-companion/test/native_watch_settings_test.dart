import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/models/native_watch_settings.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/widgets/native_watch_settings_panel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  Map<String, dynamic> report() => {
    'connected': true,
    'bridgeAvailable': true,
    'watchSetting_RIGHT_WRIST': false,
    'watchSetting_RIGHT_WRIST_observedAt': 1000,
  };

  test('Unknown, false, malformed and offline settings remain distinct', () {
    expect(NativeWatchSettings.fromBridge({}).values, isEmpty);
    final state = NativeWatchSettings.fromBridge(report());
    expect(state.values[NativeWatchSetting.rightWrist]?.value, isFalse);
    expect(state.values[NativeWatchSetting.invertScreen], isNull);
    expect(
      NativeWatchSettings.fromBridge({...report(), 'connected': false}).values,
      isEmpty,
    );
    expect(
      NativeWatchSettings.fromBridge({
        ...report(),
        'watchSetting_RIGHT_WRIST': 'false',
      }).values,
      isEmpty,
    );
    expect(
      NativeWatchSettings.fromBridge({
        ...report(),
        'watchSetting_RIGHT_WRIST_observedAt': -1,
      }).values,
      isEmpty,
    );
  });

  test(
    'Typed setting sends one boolean command and QUEUED never changes observations',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final received = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        received.add(call);
        return {'status': 'QUEUED'};
      });
      events.add({'type': 'connection', 'data': report()});
      await Future<void>.delayed(Duration.zero);
      expect(
        await bridge.setWatchSetting(NativeWatchSetting.rightWrist, true),
        'QUEUED',
      );
      expect(received.single.method, 'setWatchSetting');
      expect(received.single.arguments, {
        'setting': 'RIGHT_WRIST',
        'value': true,
      });
      expect(
        bridge.nativeSettings.values[NativeWatchSetting.rightWrist]?.value,
        isFalse,
      );
      events.add({
        'type': 'connection',
        'data': {'connected': false},
      });
      await Future<void>.delayed(Duration.zero);
      expect(bridge.nativeSettings.values, isEmpty);
      bridge.dispose();
      await events.close();
    },
  );

  testWidgets(
    'Unknown settings show no invented switch and queue receipt never marks applied',
    (tester) async {
      final settings = NativeWatchSettings.fromBridge(report());
      final calls = <String>[];
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: NativeWatchSettingsPanel(
              settings: settings,
              onChange: (key, value) async {
                calls.add('${key.wireName}:$value');
                return 'QUEUED';
              },
            ),
          ),
        ),
      );
      expect(find.text("Not received"), findsNWidgets(2));
      expect(find.byType(CupertinoSwitch), findsNothing);
      await tester.tap(find.text("Right wrist"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Enable"));
      await tester.pumpAndSettle();
      expect(calls, ['RIGHT_WRIST:true']);
      expect(find.text("Off"), findsOneWidget);
      expect(
        find.text("Command sent. Waiting for the watch result."),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'Late receipt after leaving screen cannot update disposed state',
    (tester) async {
      final complete = Completer<String>();
      await tester.pumpWidget(
        CupertinoApp(
          home: CupertinoPageScaffold(
            child: NativeWatchSettingsPanel(
              settings: NativeWatchSettings.fromBridge(report()),
              onChange: (_, _) => complete.future,
            ),
          ),
        ),
      );
      await tester.tap(find.text("Right wrist"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Enable"));
      await tester.pump();
      await tester.pumpWidget(const CupertinoApp(home: SizedBox()));
      complete.complete('QUEUED');
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
  );
}
