import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/native_face_autosave_controller.dart';
import 'package:apple_watch_companion/controllers/native_face_controller.dart';
import 'package:apple_watch_companion/services/native_face_edit_metadata.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'native_face_management_test.dart' as facts;

const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
final messenger =
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
String change(String json, void Function(Map<String, dynamic>) edit) {
  final value = jsonDecode(json) as Map<String, dynamic>;
  edit(value);
  return jsonEncode(value);
}

class Harness {
  final events = StreamController<dynamic>();
  late final bridge = WatchBridgeService(events: events.stream);
  late final faces = NativeFaceController(bridge);
  late NativeFaceAutosaveController saves;
  final writes = <String>[];
  final calls = <MethodCall>[];
  int stamp = DateTime.now().millisecondsSinceEpoch;
  String epoch = facts.epoch;
  Completer<void>? hold;
  bool failStorage = false;
  String receipt = 'QUEUED';
  Future<void> start({String? saved}) async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {
        'status': receipt,
        'requestId': calls.length == 1 ? facts.first : facts.second,
        'faceId': facts.first,
      };
    });
    saves = NativeFaceAutosaveController(
      faces: faces,
      saved: saved,
      persist: (value) async {
        await hold?.future;
        if (failStorage) throw StateError('storage unavailable');
        writes.add(value);
      },
    );
    observe(facts.config);
    await flush();
  }

  void observe(String json) => events.add({
    'type': 'connection',
    'data': {
      ...facts.report(++stamp, json: json),
      'faceCollectionEpoch': epoch,
    },
  });
  void operation(String request, String status) => events.add({
    'type': 'operation',
    'data': {'requestId': request, 'epoch': epoch, 'status': status},
  });
  Future<bool> stage(String expected, String desired) =>
      saves.stage(facts.pair, epoch, facts.first, expected, desired);
  NativeFaceEdit? get edit => saves.edit(facts.pair, facts.first);
  Map<String, dynamic> submitted(int i) =>
      jsonDecode(utf8.decode(calls[i].arguments['configuration'] as Uint8List))
          as Map<String, dynamic>;
  Future<void> close() async {
    saves.dispose();
    faces.dispose();
    bridge.dispose();
    await events.close();
    messenger.setMockMethodCallHandler(channel, null);
  }
}

Future<void> flush() async {
  for (var i = 0; i < 4; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final blue = change(
    facts.config,
    (c) => c['customization']['color'] = 'blue',
  );
  test(
    'Autosave is durable before sending; ACK is not proof and newer edits preserve normalized readback',
    () async {
      final h = Harness();
      await h.start();
      addTearDown(h.close);
      expect(await h.stage(facts.config, blue), true);
      await flush();
      expect(h.calls.single.method, 'updateNativeFace');
      expect(
        h.writes.any(
          (s) => (jsonDecode(s)['edits'] as List).single['state'] == 'sending',
        ),
        true,
      );
      expect(h.saves.canLeave(facts.pair, facts.first), true);
      final latest = change(blue, (c) {
        c['customization']['style'] = 'round';
        c['complications'].remove('bottom');
      });
      expect(await h.stage(blue, latest), true);
      await flush();
      expect(h.calls.length, 1);
      h.operation(facts.first, 'APP_ACK_RECEIVED');
      final normalized = change(blue, (c) {
        c['customization']['color'] = 'watch-normalized-blue';
        c['customization']['opaque'] = 'peer';
        c['peer field'] = {'keep': true};
      });
      h.observe(normalized);
      await flush();
      expect(h.edit!.state, NativeFaceSaveState.sending);
      expect(h.calls.length, 1);
      h.operation(facts.first, 'NATIVE_FACE_APPLIED');
      await flush();
      expect(h.calls.length, 2);
      final second = h.submitted(1);
      expect(second['customization'], {
        'color': 'watch-normalized-blue',
        'opaque': 'peer',
        'style': 'round',
      });
      expect(second['peer field'], {'keep': true});
      expect(second['complications'], isEmpty);
      h.observe(jsonEncode(second));
      h.operation(facts.second, 'NATIVE_FACE_APPLIED');
      await flush();
      expect(h.edit, null);
      expect(jsonDecode(h.writes.last)['edits'], isEmpty);
    },
  );

  test(
    'Unsent rapid edits coalesce while storage is held and never send before durable save',
    () async {
      final h = Harness();
      await h.start();
      addTearDown(h.close);
      h.hold = Completer<void>();
      final one = h.stage(facts.config, blue);
      final latest = change(blue, (c) => c['customization']['style'] = 'round');
      final two = h.stage(blue, latest);
      await flush();
      expect(h.calls, isEmpty);
      expect(h.saves.canLeave(facts.pair, facts.first), false);
      h.hold!.complete();
      h.hold = null;
      expect(await one, true);
      expect(await two, true);
      await flush();
      expect(h.calls.length, 1);
      expect(h.submitted(0)['customization']['style'], 'round');
      expect(h.saves.canLeave(facts.pair, facts.first), true);
    },
  );

  test(
    'A changed epoch during storage prevents transmission and requires explicit retry with current owner',
    () async {
      final h = Harness();
      await h.start();
      addTearDown(h.close);
      h.hold = Completer<void>();
      final pending = h.stage(facts.config, blue);
      await flush();
      h.epoch = facts.second;
      h.observe(facts.config);
      await flush();
      h.hold!.complete();
      h.hold = null;
      await pending;
      await flush();
      expect(h.calls, isEmpty);
      expect(h.saves.canEdit(facts.pair, h.epoch, facts.first), false);
      expect(h.edit!.state, NativeFaceSaveState.conflict);
      expect(await h.saves.retry(facts.pair, h.epoch, facts.first), true);
      await flush();
      expect(h.calls.length, 1);
      expect(h.edit!.epoch, h.epoch);
      expect(h.faces.collection.epoch, h.epoch);
    },
  );

  test(
    'Failed storage does not transmit; explicit retry saves before sending',
    () async {
      final h = Harness();
      await h.start();
      addTearDown(h.close);
      h.failStorage = true;
      expect(await h.stage(facts.config, blue), false);
      await flush();
      expect(h.calls, isEmpty);
      expect(h.edit!.state, NativeFaceSaveState.storageFailure);
      expect(h.saves.canLeave(facts.pair, facts.first), false);
      h.failStorage = false;
      expect(await h.saves.retry(facts.pair, h.epoch, facts.first), true);
      await flush();
      expect(h.calls.length, 1);
    },
  );

  test(
    'Storage failure during an active operation cannot allow retry or duplicate transmission',
    () async {
      final h = Harness();
      await h.start();
      addTearDown(h.close);
      await h.stage(facts.config, blue);
      await flush();
      h.failStorage = true;
      final latest = change(blue, (c) => c['customization']['style'] = 'round');
      expect(await h.stage(blue, latest), false);
      await flush();
      h.failStorage = false;
      expect(await h.saves.retry(facts.pair, h.epoch, facts.first), false);
      expect(h.calls.length, 1);
      h.observe(blue);
      h.operation(facts.first, 'NATIVE_FACE_APPLIED');
      await flush();
      expect(h.calls.length, 2);
      expect(h.submitted(1)['customization']['style'], 'round');
    },
  );

  test(
    'Unknown result, reconnect and process reload keep the draft but never automatically replay',
    () async {
      final h = Harness();
      await h.start();
      await h.stage(facts.config, blue);
      await flush();
      h.operation(facts.first, 'UNKNOWN');
      await flush();
      expect(h.edit!.state, NativeFaceSaveState.uncertain);
      final saved = h.writes.last;
      h.observe(facts.config);
      await flush();
      expect(h.calls.length, 1);
      await h.close();
      final resumed = Harness();
      await resumed.start(saved: saved);
      addTearDown(resumed.close);
      expect(resumed.calls, isEmpty);
      expect(resumed.edit!.desired, blue);
      expect(resumed.edit!.state, NativeFaceSaveState.uncertain);
      expect(resumed.saves.canLeave(facts.pair, facts.first), true);
      expect(
        await resumed.saves.retry(facts.pair, resumed.epoch, facts.first),
        true,
      );
      await flush();
      expect(resumed.calls.length, 1);
    },
  );

  test(
    'An external configuration conflict never overwrites the peer; explicit retry merges only edited fields',
    () async {
      final h = Harness();
      await h.start();
      addTearDown(h.close);
      h.hold = Completer<void>();
      final staged = h.stage(facts.config, blue);
      await flush();
      final peer = change(
        facts.config,
        (c) => c['customization']['peer'] = 'untouched',
      );
      h.observe(peer);
      await flush();
      h.hold!.complete();
      h.hold = null;
      await staged;
      await flush();
      expect(h.calls, isEmpty);
      expect(h.edit!.state, NativeFaceSaveState.conflict);
      expect(await h.saves.retry(facts.pair, h.epoch, facts.first), true);
      await flush();
      expect(h.submitted(0)['customization'], {
        'color': 'blue',
        'peer': 'untouched',
      });
    },
  );

  test(
    'Retry retains intervening Watch metrics without counting a retained editor visit twice',
    () async {
      final h = Harness();
      await h.start();
      addTearDown(h.close);
      final baseline = change(facts.config, (c) {
        c['metrics'] = {
          'origin': 9,
          'editedState': 1,
          'dateCreated': 10,
          'dateLastEdited': 20,
          'numberOfCompanionEdits': 7,
          'numberOfGizmoEdits': 1,
        };
      });
      h.observe(baseline);
      await flush();
      final edited = jsonDecode(baseline) as Map<String, dynamic>;
      edited['customization']['color'] = 'blue';
      final session = NativeFaceEditMetadata(
        now: () => DateTime.fromMicrosecondsSinceEpoch(30 * 1000000),
      );
      session.markEdited(edited);
      expect(await h.stage(baseline, jsonEncode(edited)), true);
      await flush();
      expect(h.submitted(0)['metrics']['numberOfCompanionEdits'], 8);
      h.operation(facts.first, 'UNKNOWN');
      await flush();
      expect(h.edit!.state, NativeFaceSaveState.uncertain);
      final peer = change(baseline, (c) {
        c['metrics']['numberOfCompanionEdits'] = 9;
        c['metrics']['numberOfGizmoEdits'] = 2;
        c['metrics']['dateLastEdited'] = 40;
        c['metrics']['editedState'] = 3;
        c['metrics']['future'] = {'keep': true};
      });
      h.observe(peer);
      await flush();
      expect(h.calls, hasLength(1));
      expect(await h.saves.retry(facts.pair, h.epoch, facts.first), true);
      await flush();
      expect(h.calls, hasLength(2));
      final retried = h.submitted(1);
      expect(retried['customization']['color'], 'blue');
      expect(retried['metrics'], jsonDecode(peer)['metrics']);
      h.operation(facts.second, 'APP_ACK_RECEIVED');
      h.observe(jsonEncode(retried));
      await flush();
      expect(h.edit!.state, NativeFaceSaveState.sending);
      h.operation(facts.second, 'NATIVE_FACE_APPLIED');
      await flush();
      expect(h.edit, null);
    },
  );

  test(
    'A first edit with absent metrics preserves peer-created metrics when retrying',
    () async {
      final h = Harness();
      await h.start();
      addTearDown(h.close);
      final edited = jsonDecode(blue) as Map<String, dynamic>;
      NativeFaceEditMetadata(
        now: () => DateTime.fromMicrosecondsSinceEpoch(30 * 1000000),
      ).markEdited(edited);
      expect(await h.stage(facts.config, jsonEncode(edited)), true);
      await flush();
      h.operation(facts.first, 'UNKNOWN');
      await flush();
      final peer = change(facts.config, (c) {
        c['metrics'] = {
          'origin': 5,
          'dateCreated': 10,
          'dateLastEdited': 40,
          'numberOfCompanionEdits': 2,
          'numberOfGizmoEdits': 1,
          'editedState': 2,
          'future': {'keep': true},
        };
      });
      h.observe(peer);
      await flush();
      expect(h.calls, hasLength(1));
      expect(await h.saves.retry(facts.pair, h.epoch, facts.first), true);
      await flush();
      expect(h.calls, hasLength(2));
      final retried = h.submitted(1);
      expect(retried['customization']['color'], 'blue');
      expect(retried['metrics'], jsonDecode(peer)['metrics']);
      expect(h.faces.collection.face(facts.first)!.configurationJson, peer);
      expect(edited['metrics']['numberOfCompanionEdits'], 1);
    },
  );

  test(
    'Stale editors, malformed input and different face families do not reserve, save or transmit',
    () async {
      final h = Harness();
      await h.start();
      addTearDown(h.close);
      expect(await h.stage('invalid', blue), false);
      expect(await h.stage(facts.config, '{'), false);
      expect(
        await h.stage(
          facts.config,
          blue.replaceAll('com.apple.NativeFace', 'another.bundle'),
        ),
        false,
      );
      expect(await h.stage(blue, facts.config), false);
      expect(h.writes, isEmpty);
      expect(h.calls, isEmpty);
      expect(h.edit, null);
    },
  );

  test(
    'Disposal during durable storage prevents sending after the provider goes away',
    () async {
      final h = Harness();
      await h.start();
      h.hold = Completer<void>();
      final stage = h.stage(facts.config, blue);
      await flush();
      h.saves.dispose();
      h.hold!.complete();
      h.hold = null;
      await stage;
      await flush();
      expect(h.calls, isEmpty);
      h.faces.dispose();
      h.bridge.dispose();
      await h.events.close();
      messenger.setMockMethodCallHandler(channel, null);
    },
  );
}
