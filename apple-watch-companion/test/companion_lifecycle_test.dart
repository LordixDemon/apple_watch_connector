import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:apple_watch_companion/app/companion_scope.dart';
import 'package:apple_watch_companion/controllers/face_library_controller.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/providers/watch_connection_provider.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:apple_watch_companion/services/watch_face_catalog.dart';
import 'package:apple_watch_companion/widgets/watch_face_view.dart';

class _Transport implements BridgeTransport {
  final controller = StreamController<dynamic>();
  final calls = <String>[];
  @override
  Stream<dynamic> get events => controller.stream;
  @override
  Future<dynamic> invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    calls.add(method);
    return {'status': 'QUEUED'};
  }
}

class _Faces extends WatchFaceApiService {
  final cached = Completer<List<WatchFace>>();
  final remote = Completer<List<WatchFace>>();
  int closed = 0;
  int fetches = 0;
  @override
  Future<List<WatchFace>> loadCachedFaces() => cached.future;
  @override
  Future<List<WatchFace>> fetchRemoteFaces({String endpoint = ''}) {
    fetches++;
    return remote.future;
  }

  @override
  Future<File?> saveFaceToCache(
    WatchFace face, {
    Uint8List? snapshotBytes,
  }) async => null;
  @override
  void dispose() {
    closed++;
    super.dispose();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late SettingsStorage storage;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    storage = SettingsStorage(await SharedPreferences.getInstance());
  });

  test(
    'Transport loss invalidates connected telemetry and native state',
    () async {
      final transport = _Transport();
      final bridge = WatchBridgeService(transport: transport);
      transport.controller.add({
        'type': 'connection',
        'data': {'connected': true, 'batteryLevel': 64, 'isCharging': true},
      });
      await bridge.deviceStream.first;
      expect(bridge.currentDevice.isConnected, isTrue);
      final lost = bridge.deviceStream.first;
      transport.controller.addError(StateError('channel lost'));
      expect((await lost).isConnected, isFalse);
      expect(bridge.currentDevice.batteryLevel, -1);
      expect(bridge.currentDevice.isCharging, isFalse);
      expect(bridge.currentDevice.activeFaceId, '');
      expect(bridge.faceCollection.known, isFalse);
      expect(bridge.nativeSettings.connected, isFalse);
      bridge.dispose();
      await transport.controller.close();
    },
  );

  test('Closing Bridge cancels once and rejects further commands', () async {
    final transport = _Transport();
    var cancellations = 0;
    transport.controller.onCancel = () {
      cancellations++;
    };
    final bridge = WatchBridgeService(transport: transport);
    expect(await bridge.refreshFaceCollection(), 'QUEUED');
    bridge.dispose();
    bridge.dispose();
    expect(await bridge.refreshFaceCollection(), 'UNAVAILABLE');
    expect(await bridge.sendNotificationTest('test', 'test'), isFalse);
    expect(transport.calls, ['refreshFaceCollection']);
    expect(cancellations, 1);
    await transport.controller.close();
  });

  test(
    'End of platform event stream clears a previously connected state',
    () async {
      final transport = _Transport();
      final bridge = WatchBridgeService(transport: transport);
      final connected = bridge.deviceStream.first;
      transport.controller.add({
        'type': 'connection',
        'data': {'connected': true},
      });
      await connected;
      final lost = bridge.deviceStream.first;
      await transport.controller.close();
      expect((await lost).isConnected, isFalse);
      expect(bridge.nativeSettings.connected, isFalse);
      bridge.dispose();
    },
  );

  test(
    'Late cache completion after disposal cannot start network or publish',
    () async {
      final api = _Faces();
      final library = FaceLibraryController(
        storage: storage,
        api: api,
        ownsApi: true,
      );
      var updates = 0;
      library.addListener(() {
        updates++;
      });
      library.dispose();
      api.cached.complete([WatchFaceCatalog.wayfinderOrange]);
      await library.initialized;
      expect(updates, 0);
      expect(library.designs, isEmpty);
      expect(api.fetches, 0);
      expect(api.closed, 1);
    },
  );

  test(
    'Late remote completion after disposal cannot replace catalog',
    () async {
      final api = _Faces();
      final library = FaceLibraryController(
        storage: storage,
        api: api,
        ownsApi: true,
      );
      api.cached.complete([]);
      await Future<void>.delayed(Duration.zero);
      expect(api.fetches, 1);
      library.dispose();
      api.remote.complete([WatchFaceCatalog.wayfinderOrange]);
      await library.initialized;
      expect(library.remote, isEmpty);
      expect(api.closed, 1);
    },
  );

  test(
    'Standalone provider does not dispose borrowed Bridge or face API',
    () async {
      final transport = _Transport();
      final bridge = WatchBridgeService(transport: transport);
      final api = _Faces();
      final provider = WatchProvider(
        storage: storage,
        bridge: bridge,
        apiService: api,
      );
      provider.dispose();
      api.cached.complete([]);
      await Future<void>.delayed(Duration.zero);
      expect(api.closed, 0);
      expect(await bridge.refreshDeviceInfo(), 'QUEUED');
      api.dispose();
      bridge.dispose();
      await transport.controller.close();
    },
  );

  test(
    'Saving local design cannot manufacture native face selection',
    () async {
      final transport = _Transport();
      final bridge = WatchBridgeService(transport: transport);
      final api = _Faces();
      final provider = WatchProvider(
        storage: storage,
        bridge: bridge,
        apiService: api,
      );
      await provider.saveFaceDesign(WatchFaceCatalog.wayfinderOrange);
      expect(provider.localFaceDesigns.single.id, startsWith('local_'));
      expect(provider.faceCollection.known, isFalse);
      expect(provider.device.activeFaceId, '');
      expect(transport.calls, isEmpty);
      provider.dispose();
      api.cached.complete([]);
      api.dispose();
      bridge.dispose();
      await transport.controller.close();
    },
  );

  testWidgets('Scope shares one Bridge and closes its resources once', (
    tester,
  ) async {
    final transport = _Transport();
    var cancellations = 0;
    transport.controller.onCancel = () {
      cancellations++;
    };
    final api = _Faces();
    var created = 0;
    await tester.pumpWidget(
      CompanionScope(
        storage: storage,
        bridgeFactory: () {
          created++;
          return WatchBridgeService(transport: transport);
        },
        faceApiFactory: () => api,
        child: Builder(
          builder: (context) {
            context.read<WatchProvider>();
            context.read<WatchConnectionProvider>();
            return const SizedBox();
          },
        ),
      ),
    );
    await tester.pumpWidget(const SizedBox());
    api.cached.complete([]);
    await tester.pump();
    expect(created, 1);
    expect(cancellations, 1);
    expect(api.closed, 1);
    expect(tester.takeException(), isNull);
    unawaited(transport.controller.close());
    await tester.pump();
  });

  testWidgets(
    'All face families render and live ticker survives toggling then closes',
    (tester) async {
      final time = DateTime(2026, 10, 6, 10, 9, 30);
      final faces = WatchFaceCatalog.allCollections.expand((c) => c.faces);
      final byFamily = {for (final face in faces) face.family: face};
      for (final family in WatchFaceFamily.values) {
        final face =
            byFamily[family] ??
            WatchFaceCatalog.wayfinderOrange.copyWith(family: family);
        for (final live in [false, true, false]) {
          await tester.pumpWidget(
            CupertinoApp(
              home: Center(
                child: WatchFaceView(
                  face: face,
                  showLiveTime: live,
                  displayTime: time,
                  scrubSeconds: 3600,
                ),
              ),
            ),
          );
          await tester.pump(const Duration(milliseconds: 100));
          expect(tester.takeException(), isNull, reason: face.family.name);
        }
      }
      await tester.pumpWidget(const SizedBox());
      expect(tester.takeException(), isNull);
    },
  );
}
