import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/models/health_metrics.dart';
import 'package:apple_watch_companion/providers/watch_connection_provider.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test(
    'Queue receipts, missing plugin, and old mock true never mean applied',
    () async {
      final bridge = WatchBridgeService(events: const Stream.empty());
      expect((await bridge.selectNativeFace('face')).status, 'UNAVAILABLE');
      messenger.setMockMethodCallHandler(channel, (_) async => true);
      expect((await bridge.selectNativeFace('face')).status, 'UNAVAILABLE');
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {'status': 'QUEUED', 'requestId': 'test'},
      );
      expect(await bridge.sendNotificationTest('Title', 'Body'), isFalse);
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => {'status': 'APP_ACK_RECEIVED'},
      );
      expect(await bridge.syncSettings({}), isFalse);
      messenger.setMockMethodCallHandler(
        channel,
        (_) async => throw PlatformException(code: 'UNAVAILABLE'),
      );
      expect(await bridge.unpairWatch(), isFalse);
      bridge.dispose();
    },
  );

  test(
    'Only observed connection fields replace unknown device telemetry',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      expect(bridge.currentDevice.isConnected, isFalse);
      expect(bridge.currentDevice.batteryLevel, -1);
      final update = bridge.deviceStream.first;
      events.add({
        'type': 'connection',
        'data': {'connected': true},
      });
      expect((await update).isConnected, isTrue);
      expect(bridge.currentDevice.batteryLevel, -1);
      final battery = bridge.deviceStream.first;
      events.add({
        'type': 'connection',
        'data': {'connected': true, 'batteryLevel': 64, 'isCharging': true},
      });
      expect((await battery).batteryLevel, 64);
      expect(bridge.currentDevice.isCharging, isTrue);
      final lost = bridge.deviceStream.first;
      events.add({
        'type': 'connection',
        'data': {'connected': false},
      });
      expect((await lost).isConnected, isFalse);
      expect(bridge.currentDevice.batteryLevel, -1);
      expect(bridge.currentDevice.isCharging, isFalse);
      expect(bridge.currentDevice.telemetry.observedAt, isNull);
      bridge.dispose();
      await events.close();
    },
  );

  test('Incomplete health broadcast cannot invent samples or goals', () {
    expect(
      HealthMetrics.initial().heartRateTimestamp.millisecondsSinceEpoch,
      0,
    );
    expect(HealthMetrics.isCompleteObservation({'heartRateBpm': 76}), isFalse);
    expect(() => HealthMetrics.fromMap({}), throwsFormatException);
  });

  test('PIN submission cannot finish pairing through a timer', () async {
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => throw PlatformException(code: 'UNIMPLEMENTED'),
    );
    final bridge = WatchBridgeService(events: const Stream.empty());
    final pairing = WatchConnectionProvider(bridge: bridge);
    await pairing.submitPin('123456');
    expect(pairing.state.connected, isFalse);
    expect(pairing.state.operationalEligible, isFalse);
    pairing.dispose();
    bridge.dispose();
  });
}
