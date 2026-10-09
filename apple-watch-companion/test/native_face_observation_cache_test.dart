import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_observation_cache.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'native_face_management_test.dart' as facts;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'Only exact face facts are cached; telemetry does not parse them again',
    () {
      final cache = NativeFaceObservationCache();
      final data = facts.report(1000);
      final first = cache.decode(data);
      for (var i = 0; i < 100; i++) {
        expect(
          identical(cache.decode({...data, 'batteryLevel': i}), first),
          isTrue,
        );
      }
      final changed = facts.report(1000, selected: facts.second);
      expect(identical(cache.decode(changed), first), isFalse);
      final next = cache.decode(changed);
      final bytes =
          ((changed['faceCollectionFaces'] as List).first
                  as Map)['configuration']
              as Uint8List;
      final index = utf8.decode(bytes).indexOf('opaque-token');
      bytes[index] = 'O'.codeUnitAt(0);
      final updated = cache.decode(changed);
      expect(identical(updated, next), isFalse);
      expect(updated.faces.first.configurationJson, contains('Opaque-token'));
      expect(next.faces.first.configurationJson, contains('opaque-token'));
    },
  );

  test(
    'Catalog bytes, availability, epoch and reconnection invalidate cached facts',
    () {
      final cache = NativeFaceObservationCache();
      final data = facts.report(1000);
      data['faceComplicationCatalog'] = Uint8List.fromList(
        utf8.encode('{"native":{}}'),
      );
      data['faceComplicationCatalogComplete'] = true;
      final first = cache.decode(data);
      data['faceComplicationCatalog'] = Uint8List.fromList(
        utf8.encode('{"native":{"row":{}}}'),
      );
      final catalog = cache.decode(data);
      expect(identical(catalog, first), isFalse);
      ((data['faceCollectionFaces'] as List).first as Map)['archiveAvailable'] =
          false;
      final availability = cache.decode(data);
      expect(identical(availability, catalog), isFalse);
      expect(availability.faces.first.archiveAvailable, isFalse);
      data['faceCollectionEpoch'] = facts.second;
      final epoch = cache.decode(data);
      expect(epoch.epoch, facts.second);
      expect(identical(epoch, availability), isFalse);
      expect(cache.decode({'connected': false}).known, isFalse);
      expect(identical(cache.decode(data), epoch), isFalse);
      data['faceCollectionFaces'] = 'malformed';
      expect(cache.decode(data).known, isFalse);
    },
  );

  test(
    'Battery publications preserve device events but suppress duplicate face events',
    () async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      var faces = 0, devices = 0;
      final fs = bridge.faceCollectionStream.listen((_) => faces++);
      final ds = bridge.deviceStream.listen((_) => devices++);
      for (var i = 0; i < 20; i++) {
        events.add({
          'type': 'connection',
          'data': {...facts.report(1000), 'batteryLevel': i},
        });
      }
      await Future<void>.delayed(Duration.zero);
      expect(devices, 20);
      expect(faces, 1);
      events.add({'type': 'connection', 'data': facts.report(1001)});
      await Future<void>.delayed(Duration.zero);
      expect(faces, 2);
      await fs.cancel();
      await ds.cancel();
      bridge.dispose();
      await events.close();
    },
  );
}
