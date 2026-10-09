import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/watch_observation_controller.dart';
import 'package:apple_watch_companion/models/native_pigment_preferences.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';

const pair = '10000000-0000-0000-0000-000000000001';
const epoch = '20000000-0000-0000-0000-000000000001';
const now = 1791253000000;
Map<String, Object?> report() => {
  'connected': true,
  'bridgeAvailable': true,
  'faceCollectionPair': pair,
  'faceCollectionEpoch': epoch,
  'pigmentPreferencePair': pair,
  'pigmentPreferenceEpoch': epoch,
  'pigmentPreferenceSourceTimestamp': 812945799.0,
  'pigmentPreferenceObservedAt': now,
  'observedAt': now,
  'pigmentPreferenceNames': ['standard.navyBlue', 'zeus.fall2025.bleuHydra'],
};

final class _PassiveTransport implements BridgeTransport {
  final stream = StreamController<dynamic>();
  @override
  Stream<dynamic> get events => stream.stream;
  @override
  Future<dynamic> invoke(String method, [Map<String, dynamic>? arguments]) =>
      throw StateError('Passive observations must not send watch commands');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Complete native names are immutable, including unknown catalog colors',
    () {
      final data = report();
      final value = NativePigmentPreferences.fromBridge(data);
      expect(value.known, isTrue);
      expect(value.pair, pair);
      expect(value.epoch, epoch);
      expect(value.names, ['standard.navyBlue', 'zeus.fall2025.bleuHydra']);
      (data['pigmentPreferenceNames'] as List).clear();
      expect(value.names!.length, 2);
      expect(() => value.names!.clear(), throwsUnsupportedError);
    },
  );

  test('Deletion/missing state differs from an explicit empty selection', () {
    final missing = report()..remove('pigmentPreferenceNames');
    final removed = NativePigmentPreferences.fromBridge(missing);
    expect(removed.known, isFalse);
    expect(removed.sourceTimestamp, 812945799.0);
    final empty = NativePigmentPreferences.fromBridge(
      report()..['pigmentPreferenceNames'] = <String>[],
    );
    expect(empty.known, isTrue);
    expect(empty.names, isEmpty);
    expect(removed.sameObservation(empty), isFalse);
  });

  test(
    'Disconnected, unavailable or stale pair/session never supplies names',
    () {
      for (final change in [
        {'connected': false},
        {'bridgeAvailable': false},
        {'pigmentPreferencePair': epoch},
        {'pigmentPreferenceEpoch': pair},
        {'faceCollectionPair': 'invalid'},
        {'faceCollectionEpoch': null},
      ]) {
        final value = NativePigmentPreferences.fromBridge(
          report()..addAll(change),
        );
        expect(value.known, isFalse, reason: '$change');
        expect(value.names, isNull);
      }
    },
  );

  test(
    'Malformed, partial, duplicate, future or oversized data stays unknown',
    () {
      for (final change in [
        {'pigmentPreferenceNames': 'standard.blue'},
        {
          'pigmentPreferenceNames': ['standard.blue', true],
        },
        {
          'pigmentPreferenceNames': ['standard.blue', 'standard.blue'],
        },
        {
          'pigmentPreferenceNames': ['standard.blue:0.25'],
        },
        {
          'pigmentPreferenceNames': ['standard.\nblue'],
        },
        {
          'pigmentPreferenceNames': ['standard.\u0080blue'],
        },
        {
          'pigmentPreferenceNames': [''],
        },
        {'pigmentPreferenceNames': List.generate(1024, (i) => 'native.$i')},
        {'pigmentPreferenceSourceTimestamp': double.nan},
        {'pigmentPreferenceSourceTimestamp': 812945806.0},
        {'pigmentPreferenceObservedAt': now + 5001},
        {'pigmentPreferenceObservedAt': 8640000000000001},
        {'observedAt': null},
      ]) {
        expect(
          NativePigmentPreferences.fromBridge(report()..addAll(change)).known,
          isFalse,
          reason: '$change',
        );
      }
    },
  );

  test(
    'Telemetry does not change observation but a new native report does',
    () {
      final original = NativePigmentPreferences.fromBridge(report());
      expect(
        original.sameObservation(
          NativePigmentPreferences.fromBridge(
            report()..addAll({'batteryLevel': 64, 'observedAt': now + 100}),
          ),
        ),
        isTrue,
      );
      expect(
        original.sameObservation(
          NativePigmentPreferences.fromBridge(
            report()..['pigmentPreferenceNames'] = ['standard.blue'],
          ),
        ),
        isFalse,
      );
    },
  );

  test(
    'Native publication reaches controller atomically without a watch command',
    () async {
      final transport = _PassiveTransport();
      final bridge = WatchBridgeService(transport: transport);
      final observations = WatchObservationController(bridge: bridge);
      final published = <NativePigmentPreferences>[];
      var notified = 0;
      observations.addListener(() => notified++);
      final subscription = bridge.pigmentPreferencesStream.listen(
        published.add,
      );
      Future<void> send(Map data) async {
        final device = bridge.deviceStream.first;
        transport.stream.add({'type': 'connection', 'data': data});
        await device;
        await Future<void>.delayed(Duration.zero);
      }

      await send(report());
      expect(
        observations.pigmentPreferences.names,
        bridge.pigmentPreferences.names,
      );
      expect(observations.device.isConnected, isTrue);
      expect(published.length, 1);
      expect(notified, 1);
      final first = bridge.pigmentPreferences;
      await send(report()..['batteryLevel'] = 64);
      expect(published.length, 1);
      expect(notified, 2);
      expect(identical(first, bridge.pigmentPreferences), isTrue);
      await send(report()..['faceCollectionEpoch'] = pair);
      expect(bridge.pigmentPreferences.known, isFalse);
      expect(observations.pigmentPreferences.known, isFalse);
      await send(report());
      expect(bridge.pigmentPreferences.known, isTrue);
      await send({'connected': false});
      expect(observations.pigmentPreferences.known, isFalse);
      expect(observations.device.isConnected, isFalse);
      await subscription.cancel();
      observations.dispose();
      bridge.dispose();
      await transport.stream.close();
    },
  );
}
