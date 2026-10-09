import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/models/native_pigment_preferences.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';

const _pair = '10000000-0000-0000-0000-000000000001';
const _epoch = '20000000-0000-0000-0000-000000000001';
const _request = '30000000-0000-0000-0000-000000000001';
Map<String, Object> _report() => {
  'connected': true,
  'bridgeAvailable': true,
  'pairId': _pair,
  'faceCollectionPair': _pair,
  'faceCollectionEpoch': _epoch,
  'pigmentPreferencePair': _pair,
  'pigmentPreferenceEpoch': _epoch,
  'pigmentPreferenceSourceTimestamp': 812945799.0,
  'pigmentPreferenceObservedAt': 1791253000000,
  'observedAt': 1791253000000,
  'pigmentPreferenceNames': ['standard.navyBlue', 'zeus.fall2025.bleuHydra'],
  'pigmentMirrorVersion': 2,
  'pigmentMirrorPair': _pair,
  'pigmentMirrorEpoch': _epoch,
  'pigmentMirrorOrigin': 'REMOTE',
  'pigmentMirrorSourceTimestamp': 812945799.0,
  'pigmentMirrorUpdatedAt': 1791253000000,
  'pigmentMirrorNames': ['standard.navyBlue', 'zeus.fall2025.bleuHydra'],
  'pigmentMirrorAutomaticOrigin': 'REMOTE',
  'pigmentMirrorAutomaticTimestamp': 812945798.0,
  'pigmentMirrorAutomaticUpdatedAt': 1791253000000,
  'pigmentMirrorAutomaticNames': ['standard.navyBlue', 'unknown.automatic'],
};

final class _Transport implements BridgeTransport {
  final stream = StreamController<dynamic>();
  Object? receipt = {'status': 'QUEUED', 'requestId': _request};
  final calls = <Map<String, dynamic>>[];
  @override
  Stream<dynamic> get events => stream.stream;
  @override
  Future<dynamic> invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    expect(method, 'setNativePigmentVisibility');
    calls.add(Map.of(arguments!));
    return receipt;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Transport transport;
  late WatchBridgeService bridge;
  Future<void> send(Map data) async {
    final device = bridge.deviceStream.first;
    transport.stream.add({'type': 'connection', 'data': data});
    await device;
  }

  setUp(() {
    transport = _Transport();
    bridge = WatchBridgeService(transport: transport);
  });
  tearDown(() async {
    bridge.dispose();
    await transport.stream.close();
  });

  test(
    'Only current native baseline and manual deltas reach the platform',
    () async {
      await send(_report());
      final baseline = bridge.pigmentMirror!;
      final receipt = await bridge.setNativePigmentVisibility({
        'standard.navyBlue': false,
        'standard.blue': true,
      }, baseline: baseline);
      expect(receipt, (status: 'QUEUED', requestId: _request));
      expect(transport.calls.single, {
        'pairId': _pair,
        'epoch': _epoch,
        'sourceTimestamp': 812945799.0,
        'expectedNames': ['standard.navyBlue', 'zeus.fall2025.bleuHydra'],
        'automaticTimestamp': 812945798.0,
        'expectedAutomaticNames': ['standard.navyBlue', 'unknown.automatic'],
        'changes': {'standard.navyBlue': false, 'standard.blue': true},
      });
      expect(identical(baseline, bridge.pigmentMirror), isTrue);
      expect(bridge.pigmentPreferences.names, [
        'standard.navyBlue',
        'zeus.fall2025.bleuHydra',
      ]);
    },
  );

  test(
    'Unknown, disconnected and old-session observations send nothing',
    () async {
      expect(
        (await bridge.setNativePigmentVisibility({
          'standard.blue': true,
        }, baseline: const NativePigmentPreferences.unknown())).status,
        'REJECTED',
      );
      await send(_report());
      final baseline = bridge.pigmentMirror!;
      await send(_report()..['faceCollectionEpoch'] = _pair);
      expect(
        (await bridge.setNativePigmentVisibility({
          'standard.blue': true,
        }, baseline: baseline)).status,
        'REJECTED',
      );
      await send({'connected': false});
      expect(
        (await bridge.setNativePigmentVisibility({
          'standard.blue': true,
        }, baseline: baseline)).status,
        'REJECTED',
      );
      expect(transport.calls, isEmpty);
    },
  );

  test(
    'A live selected report cannot stand in for the paired automatic baseline',
    () async {
      await send(_report());
      final singleList = bridge.pigmentPreferences;
      expect(
        (await bridge.setNativePigmentVisibility({
          'standard.blue': true,
        }, baseline: singleList)).status,
        'REJECTED',
      );
      expect(transport.calls, isEmpty);
    },
  );

  test(
    'An automatic-only publication invalidates a previously captured command baseline',
    () async {
      await send(_report());
      final previous = bridge.pigmentMirror!;
      await send(
        _report()
          ..['pigmentMirrorAutomaticTimestamp'] = 812945798.5
          ..['pigmentMirrorAutomaticNames'] = ['unknown.automatic'],
      );
      expect(bridge.pigmentMirror!.names, previous.names);
      expect(
        (await bridge.setNativePigmentVisibility({
          'standard.blue': true,
        }, baseline: previous)).status,
        'REJECTED',
      );
      expect(transport.calls, isEmpty);
    },
  );

  test(
    'Shades, invalid names and oversized deltas cannot be favorites writes',
    () async {
      await send(_report());
      for (final delta in [
        <String, bool>{},
        {'standard.blue:0.25': true},
        {'standard.\nblue': false},
        {'': true},
        {'x' * 257: true},
        {for (var i = 0; i < 1024; i++) 'native.$i': true},
      ]) {
        expect(
          (await bridge.setNativePigmentVisibility(
            delta,
            baseline: bridge.pigmentMirror!,
          )).status,
          'REJECTED',
        );
      }
      expect(transport.calls, isEmpty);
    },
  );

  test(
    'Boolean, applied/ACK and malformed queue receipts never confirm a color',
    () async {
      await send(_report());
      final before = bridge.pigmentMirror!;
      for (final receipt in [
        true,
        {'status': 'APPLIED', 'requestId': _request},
        {'status': 'APP_ACK_RECEIVED', 'requestId': _request},
        {'status': 'QUEUED', 'requestId': 'invalid'},
        {'status': 'QUEUED'},
      ]) {
        transport.receipt = receipt;
        expect(
          (await bridge.setNativePigmentVisibility({
            'standard.blue': true,
          }, baseline: before)).status,
          'UNAVAILABLE',
        );
        expect(identical(before, bridge.pigmentMirror), isTrue);
      }
    },
  );

  test(
    'Late receipt after reconnect stays uncertain without retry or state mutation',
    () async {
      await send(_report());
      final baseline = bridge.pigmentMirror!;
      final pending = Completer<dynamic>();
      transport.receipt = pending.future;
      final write = bridge.setNativePigmentVisibility({
        'standard.blue': true,
      }, baseline: baseline);
      await send(_report()..['faceCollectionEpoch'] = _pair);
      pending.complete({'status': 'QUEUED', 'requestId': _request});
      expect(await write, (status: 'UNKNOWN', requestId: _request));
      expect(transport.calls.length, 1);
      expect(bridge.pigmentPreferences.known, isFalse);
    },
  );
}
