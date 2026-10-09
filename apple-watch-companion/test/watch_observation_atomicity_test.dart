import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/watch_observation_controller.dart';
import 'package:apple_watch_companion/models/native_watch_settings.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Queued native reports notify once each and expose no partial state',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final observations = WatchObservationController(bridge: bridge);
      final seen = <int>[];
      observations.addListener(() {
        final battery = observations.device.batteryLevel;
        seen.add(battery);
        expect(observations.nativeSettings.connected, battery != -1);
        if (battery != -1) {
          expect(
            observations
                .nativeSettings
                .values[NativeWatchSetting.time24Hour]!
                .value,
            battery == 80,
          );
          expect(observations.pigmentPreferences.names, [
            'native.color$battery',
          ]);
        } else {
          expect(observations.pigmentPreferences.known, isFalse);
          expect(observations.nativeSettings.values, isEmpty);
        }
      });
      const pair = '10000000-0000-0000-0000-000000000001';
      const epoch = '20000000-0000-0000-0000-000000000001';
      const now = 1791253000000;
      for (final battery in [80, 79]) {
        events.add({
          'type': 'connection',
          'data': {
            'connected': true,
            'bridgeAvailable': true,
            'batteryLevel': battery,
            'watchSetting_TIME_24_HOUR': battery == 80,
            'watchSetting_TIME_24_HOUR_observedAt': now,
            'faceCollectionPair': pair,
            'faceCollectionEpoch': epoch,
            'pigmentPreferencePair': pair,
            'pigmentPreferenceEpoch': epoch,
            'pigmentPreferenceObservedAt': now,
            'pigmentPreferenceSourceTimestamp': 812945799.0,
            'pigmentPreferenceNames': ['native.color$battery'],
            'observedAt': now,
          },
        });
      }
      events.add({
        'type': 'connection',
        'data': {'connected': false},
      });
      await Future<void>.delayed(Duration.zero);
      expect(seen, [80, 79, -1]);
      observations.dispose();
      bridge.dispose();
      await events.close();
    },
  );
}
