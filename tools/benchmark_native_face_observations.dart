import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import '../apple-watch-companion/lib/models/native_face_collection.dart';
import '../apple-watch-companion/lib/services/native_face_observation_cache.dart';

/// AOT benchmark of unchanged collection publications, outside Flutter rendering.
void main(List<String> args) {
  final data =
      jsonDecode(File(args.single).readAsStringSync()) as Map<String, dynamic>;
  for (final row in data['faceCollectionFaces'] as List) {
    row['configuration'] = Uint8List.fromList(
      utf8.encode(row['configuration'] as String),
    );
  }
  data['faceComplicationCatalog'] = Uint8List.fromList(
    utf8.encode(jsonEncode(data['faceComplicationCatalog'])),
  );
  final first = NativeFaceCollection.fromBridge(data);
  if (!first.known)
    throw StateError('Benchmark needs a valid native observation');
  final cache = NativeFaceObservationCache();
  cache.decode(data);
  const count = 2000;
  var observed = 0;
  final parseTimes = <int>[], cacheTimes = <int>[];
  for (var run = 0; run < 5; run++) {
    final clock = Stopwatch()..start();
    for (var i = 0; i < count; i++) {
      observed += NativeFaceCollection.fromBridge(data).faces.length;
    }
    parseTimes.add(clock.elapsedMicroseconds);
    clock.reset();
    for (var i = 0; i < count; i++) {
      observed += cache.decode(data).faces.length;
    }
    cacheTimes.add(clock.elapsedMicroseconds);
    clock.stop();
  }
  stdout.writeln(
    jsonEncode({
      'scope':
          'macOS AOT repeated native observation decode; excludes UI and IPC',
      'publicationsPerRun': count,
      'faces': first.faces.length,
      'catalogBytes': (data['faceComplicationCatalog'] as Uint8List).length,
      'uncachedMicroseconds': parseTimes,
      'cachedMicroseconds': cacheTimes,
      'observedGuard': observed,
    }),
  );
}
