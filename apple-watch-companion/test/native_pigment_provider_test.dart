import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:apple_watch_companion/controllers/native_pigment_sync_controller.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';

final class _Transport implements BridgeTransport {
  final controller = StreamController<dynamic>();
  final calls = <Map<String, dynamic>>[];
  @override
  Stream<dynamic> get events => controller.stream;
  @override
  Future<dynamic> invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    expect(method, 'setNativePigmentVisibility');
    calls.add(arguments!);
    return {
      'status': 'QUEUED',
      'requestId': '30000000-0000-0000-0000-000000000001',
    };
  }
}

final class _Catalog extends WatchFaceApiService {
  @override
  Future<List<WatchFace>> loadCachedFaces() async => [];
  @override
  Future<List<WatchFace>> fetchRemoteFaces({String endpoint = ''}) async => [];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Production composition binds Done, durable scoped storage and current Bridge readback',
    () async {
      SharedPreferences.setMockInitialValues({
        'aw_native_pigment_favorites_v1': jsonEncode({
          'version': 1,
          'overrides': {'legacy.red': true},
        }),
      });
      final prefs = await SharedPreferences.getInstance();
      final storage = SettingsStorage(prefs);
      final transport = _Transport();
      final bridge = WatchBridgeService(transport: transport);
      final catalog = _Catalog();
      final provider = WatchProvider(
        storage: storage,
        bridge: bridge,
        apiService: catalog,
      );
      const pair = '10000000-0000-0000-0000-000000000001';
      const epoch = '20000000-0000-0000-0000-000000000001';
      var now = DateTime.now().millisecondsSinceEpoch;
      final source = now / 1000 - 978307200 - 2;
      Future<void> publish(
        List<String> names,
        double stamp,
        List<String> automatic,
      ) async {
        final observed = bridge.deviceStream.first;
        transport.controller.add({
          'type': 'connection',
          'data': {
            'connected': true,
            'bridgeAvailable': true,
            'pairId': pair,
            'faceCollectionPair': pair,
            'faceCollectionEpoch': epoch,
            'pigmentPreferencePair': pair,
            'pigmentPreferenceEpoch': epoch,
            'pigmentPreferenceObservedAt': now,
            'observedAt': now,
            'pigmentPreferenceSourceTimestamp': stamp,
            'pigmentPreferenceNames': names,
            'pigmentMirrorVersion': 2,
            'pigmentMirrorPair': pair,
            'pigmentMirrorEpoch': epoch,
            'pigmentMirrorOrigin': 'REMOTE',
            'pigmentMirrorSourceTimestamp': stamp,
            'pigmentMirrorUpdatedAt': now,
            'pigmentMirrorNames': names,
            'pigmentMirrorAutomaticOrigin': 'REMOTE',
            'pigmentMirrorAutomaticTimestamp': stamp,
            'pigmentMirrorAutomaticUpdatedAt': now,
            'pigmentMirrorAutomaticNames': automatic,
          },
        });
        await observed;
        await Future<void>.delayed(Duration.zero);
      }

      await publish(
        ['zeus.unknown'],
        source,
        ['zeus.unknown', 'unknown.automatic'],
      );
      expect(provider.pigmentFavorites.pair, pair);
      expect(provider.pigmentFavorites.canEdit, isTrue);
      expect(transport.calls, isEmpty); // Never upload old global preferences.
      await provider.pigmentFavorites.update({'standard.blue': true});
      await Future<void>.delayed(Duration.zero);
      expect(transport.calls.single['changes'], {'standard.blue': true});
      expect(transport.calls.single['expectedNames'], ['zeus.unknown']);
      expect(transport.calls.single['expectedAutomaticNames'], [
        'zeus.unknown',
        'unknown.automatic',
      ]);
      expect(transport.calls.single['epoch'], epoch);
      expect(jsonDecode(storage.loadPigmentIntents()!)['pairs'].keys, [pair]);
      expect(
        provider.pigmentFavorites.syncState,
        PigmentSyncState.awaitingDelivery,
      );
      expect(provider.pigmentPreferences.names, [
        'zeus.unknown',
      ]); // Intent is not observation.
      transport.controller.add({
        'type': 'operation',
        'data': {
          'requestId': '30000000-0000-0000-0000-000000000001',
          'epoch': epoch,
          'status': 'APP_ACK_RECEIVED',
        },
      });
      await Future<void>.delayed(Duration.zero);
      await Future<void>.delayed(Duration.zero);
      expect(provider.pigmentFavorites.syncState, PigmentSyncState.delivered);
      expect(provider.pigmentPreferences.names, ['zeus.unknown']);
      now = DateTime.now().millisecondsSinceEpoch;
      await publish(
        ['zeus.unknown', 'standard.blue'],
        source + 1,
        ['zeus.unknown', 'unknown.automatic'],
      );
      expect(provider.pigmentFavorites.syncState, PigmentSyncState.idle);
      expect(jsonDecode(storage.loadPigmentIntents()!)['pairs'], isEmpty);
      expect(provider.pigmentPreferences.names, [
        'zeus.unknown',
        'standard.blue',
      ]);
      expect(transport.calls, hasLength(1));
      provider.dispose();
      bridge.dispose();
      catalog.dispose();
      await transport.controller.close();
    },
  );
}
