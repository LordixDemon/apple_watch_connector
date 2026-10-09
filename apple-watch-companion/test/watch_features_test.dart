import 'dart:async';
import 'package:apple_watch_companion/models/watch_features.dart';
import 'package:apple_watch_companion/models/watch_connection_state.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/widgets/watch_features_panel.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';

class _Transport implements BridgeTransport {
  final observations = StreamController<dynamic>();
  final calls = <String>[];
  @override
  Stream<dynamic> get events => observations.stream;
  @override
  Future<dynamic> invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    calls.add(method);
    return {'status': 'QUEUED'};
  }
}

void main() {
  test('Activation and connection cannot imply feature support', () {
    final state = WatchConnectionState.fromBridge({
      'connected': true,
      'activationConfirmed': true,
      'features': <String, bool>{},
    });
    expect(state.features.known, isTrue);
    expect(state.features.nativeFaces, isFalse);
    expect(state.features.watchSettings, isFalse);
    expect(WatchFeatures.fromMap(null).known, isFalse);
    expect(WatchFeatures.fromMap({'nativeFaces': 'true'}).nativeFaces, isFalse);
  });

  test('Unsupported service commands never reach the transport', () async {
    final transport = _Transport();
    final bridge = WatchBridgeService(transport: transport);
    transport.observations.add({
      'type': 'connection',
      'data': {
        'bridgeAvailable': true,
        'connected': true,
        'features': <String, bool>{},
      },
    });
    await Future<void>.delayed(Duration.zero);
    for (final method in [
      'addNativeFace',
      'setWatchSetting',
      'sendNotification',
      'stopPhonePing',
    ]) {
      expect(await bridge.invokeBridgeMethod(method), {
        'status': 'UNSUPPORTED_OPERATION',
      });
    }
    expect(transport.calls, isEmpty);
    expect(await bridge.invokeBridgeMethod('disconnectWatch'), {
      'status': 'QUEUED',
    });
    transport.observations.add({
      'type': 'connection',
      'data': {
        'features': {'nativeFaces': true},
      },
    });
    await Future<void>.delayed(Duration.zero);
    expect(await bridge.invokeBridgeMethod('refreshFaceCollection'), {
      'status': 'QUEUED',
    });
    expect(await bridge.invokeBridgeMethod('setWatchSetting'), {
      'status': 'UNSUPPORTED_OPERATION',
    });
    bridge.dispose();
    await transport.observations.close();
  });

  testWidgets('Unavailable groups stay explicit at compact width', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      CupertinoApp(
        home: CupertinoPageScaffold(
          child: SingleChildScrollView(
            child: WatchFeaturesPanel(
              state: WatchConnectionState.fromBridge({
                'features': <String, bool>{},
                'wifiAvailable': false,
              }),
            ),
          ),
        ),
      ),
    );
    expect(find.text('AVAILABLE FEATURES'), findsOneWidget);
    expect(find.text('Not supported yet'), findsNWidgets(8));
    expect(find.text('Available'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
