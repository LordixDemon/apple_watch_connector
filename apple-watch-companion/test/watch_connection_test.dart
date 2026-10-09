import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:apple_watch_companion/models/watch_connection_state.dart';
import 'package:apple_watch_companion/providers/watch_connection_provider.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/screens/connection/watch_connection_screen.dart';
import 'package:apple_watch_companion/theme/ios_theme.dart';
import 'package:apple_watch_companion/widgets/watch_setup_panel.dart';
import 'package:apple_watch_companion/widgets/watch_connection_card.dart';

class _Transport implements BridgeTransport {
  final controller = StreamController<dynamic>();
  final requests = <(String, Map<String, dynamic>?)>[];
  Completer<dynamic>? pending;
  @override
  Stream<dynamic> get events => controller.stream;
  @override
  Future<dynamic> invoke(String method, [Map<String, dynamic>? args]) {
    requests.add((method, args));
    return pending?.future ?? Future.value({'status': 'QUEUED'});
  }

  void publish(Map<String, dynamic> data) =>
      controller.add({'type': 'connection', 'data': data});
}

void main() {
  const token = '125c3d32-adf0-4d49-bc7c-6ed0f7b8a999';
  late _Transport transport;
  late WatchBridgeService bridge;
  late WatchConnectionProvider connection;
  Future<void> observe(Map<String, dynamic> data) async {
    final observed = bridge.connectionStream.first;
    transport.publish({
      'bridgeAvailable': true,
      'identityKnown': true,
      'bluetoothPermission': true,
      ...data,
    });
    await observed;
    await Future<void>.value();
  }

  setUp(() {
    transport = _Transport();
    bridge = WatchBridgeService(transport: transport);
    connection = WatchConnectionProvider(bridge: bridge);
  });
  tearDown(() async {
    connection.dispose();
    bridge.dispose();
    await transport.controller.close();
  });
  test(
    'No fixed hardware exists before identity, and foreign identity clears old readings',
    () async {
      expect(bridge.currentDevice.modelIdentifier, '—');
      expect(bridge.currentDevice.caseMaterial, '—');
      await observe({
        'hasPair': true,
        'pairId': 'first',
        'productType': 'Watch7,5',
        'connected': true,
        'batteryLevel': 66,
      });
      expect(bridge.currentDevice.name, 'Apple Watch Ultra 2');
      expect(bridge.currentDevice.id, 'first');
      await observe({
        'hasPair': true,
        'pairId': 'second',
        'productType': 'Watch99,1',
      });
      expect(bridge.currentDevice.name, 'Apple Watch (Watch99,1)');
      expect(bridge.currentDevice.caseSize, '—');
      expect(bridge.currentDevice.batteryLevel, -1);
      expect(bridge.currentDevice.activeFaceId, '');
    },
  );
  test(
    'Disconnected saved identity remains selectable without showing connected telemetry',
    () async {
      await observe({
        'hasPair': true,
        'pairId': 'same-pair',
        'productType': 'Watch7,5',
        'operationalEligible': true,
      });
      expect(connection.state.canConnect, isTrue);
      expect(connection.state.canPair, isFalse);
      await connection.connect();
      expect(transport.requests.single.$1, 'connectWatch');
      expect(transport.requests.single.$2, {'pairId': 'same-pair'});
      expect(connection.state.connected, isFalse);
      expect(connection.state.status, 'DISCONNECTED');
    },
  );
  test('Unknown store and saved pair never permit a fresh pair', () async {
    expect(await connection.pair(), 'REJECTED');
    await observe({'hasPair': true, 'pairId': 'saved'});
    expect(await connection.pair(), 'REJECTED');
    expect(transport.requests, isEmpty);
    await observe({'hasPair': false});
    expect(await connection.pair(), 'QUEUED');
    expect(connection.state.setupRunning, isFalse);
  });
  test(
    'Optical recognition requires an explicit matching idle pair request and never implies completion',
    () async {
      expect(
        await connection.pairOptically(token, previousPair: null),
        'REJECTED',
      );
      await observe({'hasPair': false});
      expect(
        await connection.pairOptically('invalid', previousPair: null),
        'REJECTED',
      );
      expect(
        await connection.pairOptically(token, previousPair: null),
        'QUEUED',
      );
      expect(transport.requests.single.$1, 'beginOpticalPairing');
      expect(transport.requests.single.$2, {'opticalToken': token});
      expect(connection.state.hasPair, isFalse);
      expect(connection.state.connected, isFalse);
      transport.requests.clear();
      await observe({'hasPair': true, 'pairId': 'saved'});
      expect(
        await connection.pairOptically(token, previousPair: null),
        'REJECTED',
      );
      expect(
        await connection.pairOptically(token, previousPair: 'other'),
        'REJECTED',
      );
      expect(
        await connection.pairOptically(token, previousPair: 'saved'),
        'QUEUED',
      );
      expect(transport.requests.single.$1, 'replacePairOptically');
      expect(transport.requests.single.$2, {
        'pairId': 'saved',
        'opticalToken': token,
      });
      expect(connection.state.operationalEligible, isFalse);
      transport.requests.clear();
      await observe({'hasPair': true, 'pairId': 'saved', 'setupRunning': true});
      expect(
        await connection.pairOptically(token, previousPair: 'saved'),
        'REJECTED',
      );
      expect(transport.requests, isEmpty);
    },
  );
  test(
    'PIN is accepted only during native PIN request; receipt does not finish setup',
    () async {
      expect(await connection.submitPin('123456'), 'REJECTED');
      await observe({
        'setupRunning': true,
        'pinRequired': true,
        'setupPhase': 'PIN_REQUIRED',
      });
      expect(await connection.submitPin('１２３４５６'), 'REJECTED');
      expect(await connection.submitPin('123456'), 'QUEUED');
      expect(connection.state.setupPhase, 'PIN_REQUIRED');
      expect(connection.state.connected, isFalse);
      expect(connection.state.operationalEligible, isFalse);
      await observe({'setupRunning': true, 'setupPhase': 'SYNCING'});
      expect(await connection.submitPin('123456'), 'REJECTED');
    },
  );
  test(
    'Selection accepts only an observed opaque candidate during discovery',
    () async {
      await observe({
        'setupRunning': true,
        'setupPhase': 'DISCOVERING',
        'discoveredWatches': [
          {
            'token': token,
            'productType': 'Watch7,5',
            'watchOs': '26.2',
            'rssi': -50,
          },
          {
            'token': 'bad',
            'productType': 'Watch7,5',
            'watchOs': '26.2',
            'rssi': -50,
          },
        ],
      });
      expect(connection.state.discoveredWatches.length, 1);
      expect(await connection.selectWatch('unknown'), 'REJECTED');
      expect(await connection.selectWatch(token), 'QUEUED');
      expect(transport.requests.single.$1, 'selectDiscoveredWatch');
      expect(transport.requests.single.$2, {'discoveryToken': token});
      await observe({'setupRunning': true, 'setupPhase': 'SECURITY'});
      expect(await connection.selectWatch(token), 'REJECTED');
    },
  );
  test(
    'Pending command blocks repeated taps and late receipt does not invent state',
    () async {
      transport.pending = Completer();
      final result = connection.command('disconnectWatch');
      expect(await connection.command('beginPairing'), 'BUSY');
      transport.pending!.complete({'status': 'QUEUED'});
      expect(await result, 'QUEUED');
      expect(connection.state, isA<WatchConnectionState>());
      expect(connection.state.connected, isFalse);
    },
  );
  test(
    'IPC loss clears connected identity projections and permissions',
    () async {
      await observe({
        'connected': true,
        'hasPair': true,
        'pairId': 'pair',
        'productType': 'Watch7,5',
      });
      final lost = bridge.connectionStream.first;
      transport.controller.addError(StateError('Binder unavailable'));
      await lost;
      expect(connection.state.bridgeAvailable, isFalse);
      expect(connection.state.canPair, isFalse);
      expect(bridge.currentDevice.modelIdentifier, '—');
    },
  );
  test(
    'Activation cannot remain labeled as activating or imply a watch face',
    () {
      const state = WatchConnectionState(
        hasPair: true,
        activationConfirmed: true,
        setupRunning: true,
        setupPhase: 'ACTIVATING',
      );
      expect(state.effectiveSetupPhase, 'ACTIVATED');
      expect(state.setupWorking, isFalse);
      expect(state.needsWatchConfirmation, isTrue);
      expect(state.operationalEligible, isFalse);
    },
  );
  test(
    'Progress clears queued receipts, including receipts arriving late',
    () async {
      await observe({'setupRunning': true, 'setupPhase': 'ACTIVATING'});
      await connection.command('activationResponse');
      expect(connection.receipt, 'QUEUED');
      await observe({
        'setupRunning': true,
        'setupPhase': 'ACTIVATED',
        'activationConfirmed': true,
      });
      expect(connection.receipt, isNull);
      transport.pending = Completer();
      final command = connection.command('confirmSetup');
      await observe({'setupRunning': true, 'setupPhase': 'STOPPING'});
      transport.pending!.complete({'status': 'QUEUED'});
      expect(await command, 'QUEUED');
      expect(connection.receipt, isNull);
      expect(connection.state.operationalEligible, isFalse);
    },
  );
  testWidgets(
    'Every setup surface follows observed phases; delivery is not completion',
    (tester) async {
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: connection,
          child: CupertinoApp(
            home: Consumer<WatchConnectionProvider>(
              builder: (_, value, _) => CupertinoPageScaffold(
                child: Column(
                  children: [
                    WatchConnectionCard(state: value.state),
                    WatchSetupPanel(connection: value),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      const stages = [
        ('STARTING', 'Starting the watch session…', true),
        ('DISCOVERING', 'Looking for a watch…', true),
        ('CONNECTING', 'Connecting to the discovered watch…', true),
        ('SECURITY', 'Establishing a secure pair…', true),
        ('IDS', 'Connecting watch services…', true),
        ('REGISTRY', 'Reading watch configuration…', true),
        ('CONFIGURING', 'Applying watch settings…', true),
        ('ACTIVATING', 'Activating with Apple…', true),
        ('ACTIVATED', 'Watch activated', false),
        ('SYNCING', 'Synchronizing watch setup…', true),
        (
          'WAITING_FOR_WATCH',
          'Initial synchronization sent. Check your watch.',
          false,
        ),
        (
          'VERIFYING_RECONNECT',
          'Watch setup observed. Connection verification pending.',
          false,
        ),
        ('STOPPING', 'Disconnecting…', true),
        ('STOPPED', 'Setup connection stopped', false),
        (
          'FAILED',
          'The setup session ended with an error. Check the journal.',
          false,
        ),
        ('VERIFIED', 'Watch setup complete', false),
      ];
      for (final (phase, label, working) in stages) {
        await tester.runAsync(
          () => observe({
            'setupRunning': true,
            'setupPhase': phase,
            'hasPair': true,
            'pairId': 'pair',
          }),
        );
        await tester.pump();
        expect(find.text(label), findsNWidgets(2), reason: phase);
        expect(
          find.byType(CupertinoActivityIndicator),
          working ? findsNWidgets(2) : findsNothing,
          reason: phase,
        );
        expect(connection.state.operationalEligible, isFalse, reason: phase);
      }
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'Visible-face confirmation works during the retained setup session',
    (tester) async {
      await tester.runAsync(
        () => observe({
          'hasPair': true,
          'pairId': 'actual-pair',
          'activationConfirmed': true,
          'setupRunning': true,
          'setupPhase': 'WAITING_FOR_WATCH',
        }),
      );
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: connection,
          child: CupertinoApp(
            theme: IosTheme.cupertinoDarkTheme,
            home: const WatchConnectionScreen(),
          ),
        ),
      );
      final finish = find.text('Finish setup');
      await tester.ensureVisible(finish);
      await tester.tap(finish);
      await tester.pumpAndSettle();
      expect(find.text('Does your watch show its watch face?'), findsOneWidget);
      await tester.tap(find.text('The watch face is visible'));
      await tester.pumpAndSettle();
      expect(transport.requests.single.$1, 'confirmSetup');
      expect(transport.requests.single.$2, {'pairId': 'actual-pair'});
      expect(connection.state.operationalEligible, isFalse);
      expect(connection.state.effectiveSetupPhase, 'WAITING_FOR_WATCH');
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'Activated idle pair finishes instead of resuming; native completion removes stale setup UI',
    (tester) async {
      await tester.runAsync(
        () => observe({
          'hasPair': true,
          'pairId': 'actual-pair',
          'activationConfirmed': true,
          'setupPhase': 'FAILED',
        }),
      );
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: connection,
          child: CupertinoApp(
            theme: IosTheme.cupertinoDarkTheme,
            home: const WatchConnectionScreen(),
          ),
        ),
      );
      expect(find.text('Continue setup'), findsNothing);
      expect(find.text('Finish setup'), findsOneWidget);
      await tester.ensureVisible(find.text('Finish setup'));
      await tester.tap(find.text('Finish setup'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('The watch face is visible'));
      await tester.pumpAndSettle();
      expect(transport.requests.single.$1, 'confirmSetup');
      expect(connection.state.operationalEligible, isFalse);
      await tester.runAsync(
        () => observe({
          'hasPair': true,
          'pairId': 'actual-pair',
          'activationConfirmed': true,
          'operationalEligible': true,
          'setupRunning': true,
          'setupPhase': 'FAILED',
          'connected': true,
          'bluetoothLink': true,
          'connectionStatus': 'CONNECTED',
        }),
      );
      await tester.pump();
      expect(find.text('Continue setup'), findsNothing);
      expect(find.text('Finish setup'), findsNothing);
      expect(find.text('Stop setup'), findsNothing);
      expect(find.text('Disconnect'), findsOneWidget);
      expect(find.byType(WatchSetupPanel), findsNothing);
      tester
          .state<ScrollableState>(find.byType(Scrollable).first)
          .position
          .jumpTo(0);
      await tester.pump();
      expect(WatchConnectionCard.statusText(connection.state), 'Connected');
      expect(find.text('Connected'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );
  test(
    'Completion and resume actions remain bound to the pair shown in the dialog',
    () async {
      await observe({
        'hasPair': true,
        'pairId': 'first',
        'activationConfirmed': true,
      });
      expect(connection.state.canConfirmSetup, isTrue);
      await observe({
        'hasPair': true,
        'pairId': 'second',
        'activationConfirmed': true,
      });
      expect(await connection.confirmSetup('first'), 'REJECTED');
      expect(await connection.resumeSetup('first'), 'REJECTED');
      expect(transport.requests, isEmpty);
      await observe({
        'hasPair': true,
        'pairId': 'second',
        'activationConfirmed': true,
        'operationalEligible': true,
      });
      expect(await connection.confirmSetup('second'), 'REJECTED');
      expect(await connection.resumeSetup('second'), 'REJECTED');
      expect(transport.requests, isEmpty);
    },
  );
  testWidgets(
    'Activation-only retry requires the explicit still-setting-up choice',
    (tester) async {
      await tester.runAsync(
        () => observe({
          'hasPair': true,
          'pairId': 'actual-pair',
          'activationConfirmed': true,
          'setupPhase': 'WAITING_FOR_WATCH',
        }),
      );
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: connection,
          child: CupertinoApp(
            theme: IosTheme.cupertinoDarkTheme,
            home: const WatchConnectionScreen(),
          ),
        ),
      );
      final retry = find.text('My watch still shows setup');
      await tester.ensureVisible(retry);
      await tester.tap(retry);
      await tester.pumpAndSettle();
      expect(transport.requests, isEmpty);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(transport.requests, isEmpty);
      await tester.tap(retry);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Resume synchronization').last);
      await tester.pumpAndSettle();
      expect(transport.requests.single.$1, 'resumeSetup');
      expect(transport.requests.single.$2, {'pairId': 'actual-pair'});
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'Empty store offers real camera recognition and PIN fallback without a fake model',
    (tester) async {
      await tester.runAsync(() => observe({'hasPair': false}));
      await tester.pump();
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: connection,
          child: CupertinoApp(
            theme: IosTheme.cupertinoDarkTheme,
            home: const WatchConnectionScreen(),
          ),
        ),
      );
      expect(find.text('No paired watch'), findsOneWidget);
      expect(find.text('Apple Watch Ultra 2'), findsNothing);
      expect(find.text('Pair with Camera'), findsOneWidget);
      await tester.tap(find.text('Pair Apple Watch'));
      await tester.pump();
      expect(transport.requests.single.$1, 'beginPairing');
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'PIN and activation forms clear secrets before waiting for receipt',
    (tester) async {
      await tester.runAsync(
        () => observe({
          'setupRunning': true,
          'pinRequired': true,
          'setupPhase': 'PIN_REQUIRED',
        }),
      );
      await tester.pump();
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: connection,
          child: CupertinoApp(
            home: CupertinoPageScaffold(
              child: SafeArea(
                child: Consumer<WatchConnectionProvider>(
                  builder: (_, value, _) => WatchSetupPanel(connection: value),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.enterText(find.byType(CupertinoTextField), '123456');
      await tester.pump();
      transport.pending = Completer();
      await tester.tap(find.text('Send code'));
      await tester.pump();
      expect(
        tester
            .widget<CupertinoTextField>(find.byType(CupertinoTextField))
            .controller!
            .text,
        '',
      );
      expect(transport.requests.single.$2!['pin'], '123456');
      transport.pending!.complete({'status': 'QUEUED'});
      await tester.pump();
      await tester.runAsync(
        () => observe({
          'setupRunning': true,
          'setupPhase': 'ACTIVATION_INPUT',
          'activationChallengeId': 9,
          'activationFields': ['login', 'password'],
        }),
      );
      await tester.pump();
      await tester.enterText(
        find.byType(CupertinoTextField).at(0),
        'owner@example.test',
      );
      await tester.enterText(
        find.byType(CupertinoTextField).at(1),
        'test-password',
      );
      await tester.pump();
      transport.pending = Completer();
      await tester.tap(find.text('Continue activation'));
      await tester.pump();
      for (final input in tester.widgetList<CupertinoTextField>(
        find.byType(CupertinoTextField),
      )) {
        expect(input.controller!.text, '');
      }
      expect(connection.state.challengeId, 9);
      transport.pending!.complete({'status': 'QUEUED'});
      await tester.pump();
      await tester.pumpWidget(const SizedBox());
      expect(tester.takeException(), isNull);
    },
  );
}
