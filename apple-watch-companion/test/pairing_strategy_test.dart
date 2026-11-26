import 'dart:async';

import 'package:apple_watch_companion/l10n/strings.dart';
import 'package:apple_watch_companion/models/pairing_strategy.dart';
import 'package:apple_watch_companion/models/watch_connection_state.dart';
import 'package:apple_watch_companion/providers/watch_connection_provider.dart';
import 'package:apple_watch_companion/screens/connection/watch_connection_screen.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/services/rust/rust_bridge_transport.dart';
import 'package:apple_watch_companion/services/rust/rust_core_api.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/theme/ios_theme.dart';
import 'package:apple_watch_companion/widgets/watch_connection_card.dart';
import 'package:apple_watch_companion/widgets/watch_setup_panel.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _token1 = '125c3d32-adf0-4d49-bc7c-6ed0f7b8a999';
const _token2 = '4a8c08cd-7bdb-5718-b9f6-316651d517b5';

class _RecordingTransport implements BridgeTransport {
  final controller = StreamController<dynamic>.broadcast();
  final requests = <(String, Map<String, dynamic>?)>[];

  @override
  Stream<dynamic> get events => controller.stream;

  @override
  Future<dynamic> invoke(String method, [Map<String, dynamic>? arguments]) async {
    requests.add((method, arguments));
    return {'status': 'QUEUED'};
  }

  void publish(Map<String, dynamic> data) =>
      controller.add({'type': 'connection', 'data': data});
}

class _FakeCoreApi implements CoreApi {
  final Map<String, dynamic> state = {
    'apiVersion': 1,
    'epoch': 1,
    'phase': 'IDLE',
    'adapterState': 'POWERED_ON',
    'watchReady': false,
    'devices': <Map<String, dynamic>>[],
    'capabilities': {
      'discovery': true,
      'pairing': true,
      'operationalIds': false,
    },
  };
  final requests = <Map<String, dynamic>>[];

  @override
  Map<String, dynamic> command(Map<String, dynamic> request) {
    requests.add(Map<String, dynamic>.from(request));
    if (request['method'] == 'snapshot') {
      return Map<String, dynamic>.from(state);
    }
    return {'status': 'QUEUED'};
  }

  List<Map<String, dynamic>> get nonSnapshotCommands =>
      requests.where((r) => r['method'] != 'snapshot').toList();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    Strings.resetToDefault();
  });

  group('PairingStrategyInfo', () {
    test('defaults match S4..S1 specification', () {
      expect(PairingStrategyInfo.defaults, hasLength(4));
      expect(
        PairingStrategyInfo.defaults.map((s) => s.id).toList(),
        [4, 3, 2, 1],
      );

      final s4 = PairingStrategyInfo.defaults[0];
      expect(s4.slug, 'network_relay');
      expect(s4.maturity, 'production');
      expect(s4.watchOs, 'watchOS 9+');
      expect(s4.watchOsRange, 'watchOS 9+');
      expect(s4.isExperimental, isFalse);
      expect(s4.experimental, isFalse);
      expect(s4.defaultEnabled, isTrue);
      expect(s4.shortBadge, 'S4');
      expect(s4.badgeLabel, 'S4');

      final s3 = PairingStrategyInfo.defaults[1];
      expect(s3.slug, 'direct_ipsec_legacy');
      expect(s3.maturity, 'experimental');
      expect(s3.watchOs, 'watchOS 6-9');
      expect(s3.watchOsRange, 'watchOS 6-9');
      expect(s3.isExperimental, isTrue);
      expect(s3.experimental, isTrue);
      expect(s3.defaultEnabled, isFalse);
      expect(s3.shortBadge, 'S3');
      expect(s3.badgeLabel, 'Legacy S3');

      final s2 = PairingStrategyInfo.defaults[2];
      expect(s2.slug, 'sequential_legacy');
      expect(s2.maturity, 'experimental');
      expect(s2.watchOs, 'watchOS 3-5');
      expect(s2.isExperimental, isTrue);
      expect(s2.defaultEnabled, isFalse);
      expect(s2.shortBadge, 'S2');
      expect(s2.badgeLabel, 'Legacy S2');

      final s1 = PairingStrategyInfo.defaults[3];
      expect(s1.slug, 'simultaneous_legacy');
      expect(s1.maturity, 'experimental');
      expect(s1.watchOs, 'watchOS 1-3');
      expect(s1.isExperimental, isTrue);
      expect(s1.defaultEnabled, isFalse);
      expect(s1.shortBadge, 'S1');
      expect(s1.badgeLabel, 'Legacy S1');
    });

    test('fromJson and parse decode full, partial, and invalid payloads', () {
      final full = PairingStrategyInfo.fromJson({
        'id': 3,
        'slug': 'direct_ipsec_legacy',
        'maturity': 'experimental',
        'watchOs': 'watchOS 6-9',
      });
      expect(full, PairingStrategyInfo.byId(3));

      final minimal = PairingStrategyInfo.fromJson({'id': 1});
      expect(minimal, PairingStrategyInfo.byId(1));
      expect(minimal.isExperimental, isTrue);

      expect(PairingStrategyInfo.parse(4), PairingStrategyInfo.byId(4));
      expect(PairingStrategyInfo.parse(99), isNull);
      expect(PairingStrategyInfo.parse({'id': 0}), isNull);
      expect(
        () => PairingStrategyInfo.fromJson({'id': 5}),
        throwsFormatException,
      );
    });
  });

  group('DiscoveredWatch parsing', () {
    test('V1 JSON falls back cleanly to strategy 4 defaults', () {
      final watch = DiscoveredWatch.parse({
        'token': _token1,
        'productType': 'Watch7,5',
        'watchOs': '26.2',
        'rssi': -50,
      });
      expect(watch, isNotNull);
      expect(watch!.strategy, 4);
      expect(watch.experimental, isFalse);
      expect(watch.displayName, isNull);
      expect(watch.authStyles, ['PIN']);
      expect(watch.effectiveName, 'Apple Watch Ultra 2');
      expect(watch.isLegacy, isFalse);
      expect(watch.strategyBadge, 'S4');
    });

    test('V2 legacy S1 without productType parses and uses displayName', () {
      final withoutKey = DiscoveredWatch.parse({
        'token': _token1,
        'displayName': 'Apple Watch (Legacy S1)',
        'watchOs': 'watchOS 1-3',
        'rssi': -61,
        'strategy': 1,
        'experimental': true,
        'authStyles': ['PIN'],
      });
      expect(withoutKey, isNotNull);
      expect(withoutKey!.productType, '');
      expect(withoutKey.displayName, 'Apple Watch (Legacy S1)');
      expect(withoutKey.effectiveName, 'Apple Watch (Legacy S1)');
      expect(withoutKey.strategy, 1);
      expect(withoutKey.experimental, isTrue);
      expect(withoutKey.isLegacy, isTrue);
      expect(withoutKey.strategyBadge, 'S1');
      expect(withoutKey.authStyles, ['PIN']);

      final withNullProductType = DiscoveredWatch.parse({
        'token': _token1,
        'productType': null,
        'displayName': 'Apple Watch (Legacy S1)',
        'watchOs': null,
        'rssi': -61,
        'strategy': 1,
        'experimental': true,
        'authStyles': ['PIN', 'OPTICAL'],
      });
      expect(withNullProductType, isNotNull);
      expect(withNullProductType!.productType, '');
      expect(withNullProductType.watchOs, '');
      expect(withNullProductType.authStyles, ['PIN', 'OPTICAL']);
    });

    test('equality compares authStyles by value', () {
      final a = DiscoveredWatch.parse({
        'token': _token1,
        'displayName': 'Legacy Watch',
        'rssi': -60,
        'strategy': 2,
        'authStyles': ['PIN', 'OOB_KEY'],
      });
      final b = DiscoveredWatch.parse({
        'token': _token1,
        'displayName': 'Legacy Watch',
        'rssi': -60,
        'strategy': 2,
        'authStyles': ['PIN', 'OOB_KEY'],
      });
      final c = DiscoveredWatch.parse({
        'token': _token1,
        'displayName': 'Legacy Watch',
        'rssi': -60,
        'strategy': 2,
        'authStyles': ['PIN'],
      });
      expect(a, b);
      expect(a.hashCode, b.hashCode);
      expect(a, isNot(c));
    });
  });

  group('WatchConnectionState strategy metadata', () {
    test('parses supportedStrategies and pairingStrategy with defaults', () {
      const empty = WatchConnectionState();
      expect(empty.supportedStrategies, isEmpty);
      expect(empty.pairingStrategy, isNull);

      final parsed = WatchConnectionState.fromBridge({
        'capabilities': {
          'strategies': [
            {
              'id': 4,
              'slug': 'network_relay',
              'maturity': 'production',
              'watchOs': 'watchOS 9+',
            },
            {
              'id': 1,
              'slug': 'simultaneous_legacy',
              'maturity': 'experimental',
              'watchOs': 'watchOS 1-3',
            },
          ],
        },
        'pairingStrategy': 1,
      });
      expect(parsed.supportedStrategies, [
        PairingStrategyInfo.byId(4),
        PairingStrategyInfo.byId(1),
      ]);
      expect(parsed.pairingStrategy, 1);

      final changedStrategy = WatchConnectionState.fromBridge({
        'capabilities': {
          'strategies': [4, 1],
        },
        'pairingStrategy': 4,
      });
      expect(parsed.sameUiState(changedStrategy), isFalse);

      final androidProjected = WatchConnectionState.fromBridge({
        'supportedStrategies': [4, 3, 2, 1],
        'pairingStrategy': 2,
      });
      expect(androidProjected.supportedStrategies, PairingStrategyInfo.defaults);
      expect(androidProjected.pairingStrategy, 2);
      expect(androidProjected.pairedStrategy, 2);

      final defaulted = WatchConnectionState.fromBridge({});
      expect(defaulted.supportedStrategies, PairingStrategyInfo.defaults);
      expect(defaulted.pairedStrategy, isNull);
    });
  });

  group('Strategy opt-in & transport forwarding', () {
    test(
      'WatchConnectionProvider defaults to [4], toggles to [4, 3, 2, 1], and persists',
      () async {
        final prefs = await SharedPreferences.getInstance();
        final storage = SettingsStorage(prefs);
        final transport = _RecordingTransport();
        final bridge = WatchBridgeService(transport: transport);
        final provider = WatchConnectionProvider(
          bridge: bridge,
          storage: storage,
        );

        expect(provider.includeLegacyStrategies, isFalse);
        expect(provider.enabledStrategies, [4]);

        transport.publish({
          'bridgeAvailable': true,
          'identityKnown': true,
          'bluetoothPermission': true,
          'discoveryAvailable': true,
          'pairingAvailable': true,
        });
        await Future<void>.delayed(Duration.zero);

        expect(await provider.discover(), 'QUEUED');
        expect(transport.requests.last.$1, 'discoverWatches');
        expect(transport.requests.last.$2, {
          'strategies': [4],
        });

        await provider.setIncludeLegacyStrategies(true);
        expect(provider.includeLegacyStrategies, isTrue);
        expect(provider.enabledStrategies, [4, 3, 2, 1]);
        expect(storage.loadIncludeLegacyStrategies(), isTrue);

        expect(await provider.discover(), 'QUEUED');
        expect(transport.requests.last.$2, {
          'strategies': [4, 3, 2, 1],
        });

        expect(await provider.pair(), 'QUEUED');
        expect(transport.requests.last.$1, 'beginPairing');
        expect(transport.requests.last.$2, {
          'strategies': [4, 3, 2, 1],
        });

        provider.dispose();
        bridge.dispose();
        await transport.controller.close();
      },
    );

    test(
      'BridgeCommands forwards strategies over Android MethodChannel',
      () async {
        const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
        final messenger =
            TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
        final calls = <MethodCall>[];
        messenger.setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return {'status': 'QUEUED'};
        });
        addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

        final bridge = WatchBridgeService(events: const Stream.empty());
        expect(await bridge.discoverWatches(strategies: [4, 3, 2, 1]), 'QUEUED');
        expect(calls.last.method, 'discoverWatches');
        expect(calls.last.arguments, {
          'strategies': [4, 3, 2, 1],
        });

        expect(await bridge.beginPairing(strategies: [4]), 'QUEUED');
        expect(calls.last.method, 'beginPairing');
        expect(calls.last.arguments, {
          'strategies': [4],
        });
        bridge.dispose();
      },
    );

    test(
      'RustBridgeTransport forwards strategies on scan/pair and decodes V2 legacy devices',
      () async {
        final api = _FakeCoreApi();
        api.state['capabilities'] = {
          'discovery': true,
          'pairing': true,
          'operationalIds': false,
          'strategies': [
            {
              'id': 4,
              'slug': 'network_relay',
              'maturity': 'production',
              'watchOs': 'watchOS 9+',
            },
            {
              'id': 1,
              'slug': 'simultaneous_legacy',
              'maturity': 'experimental',
              'watchOs': 'watchOS 1-3',
            },
          ],
        };
        final transport = RustBridgeTransport(api, pollInterval: null);
        final bridge = WatchBridgeService(transport: transport);
        final provider = WatchConnectionProvider(bridge: bridge);
        await Future<void>.delayed(Duration.zero);

        expect(provider.state.supportedStrategies, [
          PairingStrategyInfo.byId(4),
          PairingStrategyInfo.byId(1),
        ]);

        await provider.setIncludeLegacyStrategies(true);
        expect(await provider.discover(), 'QUEUED');
        expect(api.nonSnapshotCommands.last, {
          'method': 'scan',
          'strategies': [4, 3, 2, 1],
        });

        expect(await provider.pair(), 'QUEUED');
        expect(api.nonSnapshotCommands.last, {
          'method': 'pair',
          'mode': 'code',
          'strategies': [4, 3, 2, 1],
        });

        api.state['phase'] = 'DISCOVERING';
        api.state['devices'] = [
          {
            'id': _token1.toUpperCase(),
            'displayName': 'Apple Watch (Legacy S1)',
            'productType': null,
            'watchOs': 'watchOS 1-3',
            'rssi': -63,
            'strategy': 1,
            'experimental': true,
            'authStyles': ['PIN'],
          },
        ];
        transport.refresh();
        await Future<void>.delayed(Duration.zero);

        final watch = provider.state.discoveredWatches.single;
        expect(watch.token, _token1);
        expect(watch.effectiveName, 'Apple Watch (Legacy S1)');
        expect(watch.strategy, 1);
        expect(watch.experimental, isTrue);

        api.state['phase'] = 'PIN_REQUIRED';
        api.state['pinRequired'] = true;
        api.state['selectedId'] = _token1.toUpperCase();
        api.state['pairingStrategy'] = 1;
        transport.refresh();
        await Future<void>.delayed(Duration.zero);
        expect(provider.state.pairingStrategy, 1);
        expect(provider.state.discoveredProductType, 'Apple Watch (Legacy S1)');
        expect(provider.state.modelName, 'Apple Watch (Legacy S1)');
        expect(provider.includeLegacyWatches, isTrue);

        provider.dispose();
        bridge.dispose();
      },
    );
  });

  group('UI and localization', () {
    testWidgets(
      'WatchConnectionScreen legacy toggle defaults OFF and updates scan payload',
      (tester) async {
        final transport = _RecordingTransport();
        final bridge = WatchBridgeService(transport: transport);
        final provider = WatchConnectionProvider(bridge: bridge);
        addTearDown(() async {
          provider.dispose();
          bridge.dispose();
          await transport.controller.close();
        });

        await tester.runAsync(() async {
          transport.publish({
            'bridgeAvailable': true,
            'identityKnown': true,
            'bluetoothPermission': true,
            'discoveryAvailable': true,
            'pairingAvailable': true,
            'hasPair': false,
          });
          await Future<void>.delayed(Duration.zero);
        });

        await tester.pumpWidget(
          ChangeNotifierProvider.value(
            value: provider,
            child: CupertinoApp(
              theme: IosTheme.cupertinoDarkTheme,
              home: const WatchConnectionScreen(),
            ),
          ),
        );
        await tester.pump();

        expect(
          find.text('Include Legacy Watches (watchOS 1–9)'),
          findsOneWidget,
        );
        expect(
          find.text(
            'Experimental support for older Apple Watch models (Strategies 1–3).',
          ),
          findsOneWidget,
        );
        final toggleFinder = find.byKey(
          const ValueKey('include-legacy-strategies-switch'),
        );
        expect(tester.widget<CupertinoSwitch>(toggleFinder).value, isFalse);

        await tester.ensureVisible(toggleFinder);
        await tester.tap(toggleFinder);
        await tester.pump();

        expect(provider.includeLegacyStrategies, isTrue);
        expect(tester.widget<CupertinoSwitch>(toggleFinder).value, isTrue);

        await tester.ensureVisible(find.text('Find a Watch'));
        await tester.tap(find.text('Find a Watch'));
        await tester.pump();

        expect(transport.requests.last.$1, 'discoverWatches');
        expect(transport.requests.last.$2, {
          'strategies': [4, 3, 2, 1],
        });
        await tester.pumpWidget(const SizedBox());
      },
    );

    testWidgets(
      'WatchSetupPanel and WatchConnectionCard surface Legacy badges and effectiveName',
      (tester) async {
        final transport = _RecordingTransport();
        final bridge = WatchBridgeService(transport: transport);
        final provider = WatchConnectionProvider(bridge: bridge);
        addTearDown(() async {
          provider.dispose();
          bridge.dispose();
          await transport.controller.close();
        });

        await tester.runAsync(() async {
          transport.publish({
            'bridgeAvailable': true,
            'identityKnown': true,
            'bluetoothPermission': true,
            'setupRunning': true,
            'setupPhase': 'DISCOVERING',
            'discoveredWatches': [
              {
                'token': _token1,
                'displayName': 'Apple Watch (Legacy S1)',
                'watchOs': 'watchOS 1-3',
                'rssi': -61,
                'strategy': 1,
                'experimental': true,
              },
              {
                'token': _token2,
                'productType': 'Watch7,5',
                'watchOs': '26.2',
                'rssi': -45,
                'strategy': 4,
                'experimental': false,
              },
            ],
          });
          await Future<void>.delayed(Duration.zero);
        });

        await tester.pumpWidget(
          ChangeNotifierProvider.value(
            value: provider,
            child: CupertinoApp(
              theme: IosTheme.cupertinoDarkTheme,
              home: CupertinoPageScaffold(
                child: SafeArea(
                  child: SingleChildScrollView(
                    child: Consumer<WatchConnectionProvider>(
                      builder: (_, value, _) => Column(
                        children: [
                          WatchConnectionCard(state: value.state),
                          WatchSetupPanel(connection: value),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pump();

        expect(find.text('Apple Watch (Legacy S1)'), findsOneWidget);
        expect(
          find.descendant(
            of: find.byType(WatchSetupPanel),
            matching: find.text('Legacy S1'),
          ),
          findsOneWidget,
        );
        expect(find.text('watchOS 1-3 • -61 dBm'), findsOneWidget);
        expect(find.text('Apple Watch Ultra 2'), findsOneWidget);
        expect(find.text('Watch7,5 • watchOS 26.2 • -45 dBm'), findsOneWidget);
        expect(find.text('Legacy S4'), findsNothing);

        await tester.runAsync(() async {
          transport.publish({
            'bridgeAvailable': true,
            'identityKnown': true,
            'bluetoothPermission': true,
            'setupRunning': true,
            'setupPhase': 'PIN_REQUIRED',
            'pinRequired': true,
            'pairingStrategy': 1,
          });
          await Future<void>.delayed(Duration.zero);
        });
        await tester.pump();

        expect(
          find.descendant(
            of: find.byType(WatchSetupPanel),
            matching: find.text('Legacy S1'),
          ),
          findsOneWidget,
        );
        expect(
          find.descendant(
            of: find.byType(WatchConnectionCard),
            matching: find.text('Legacy S1'),
          ),
          findsOneWidget,
        );

        await tester.runAsync(() async {
          transport.publish({
            'bridgeAvailable': true,
            'identityKnown': true,
            'bluetoothPermission': true,
            'hasPair': true,
            'pairId': _token1,
            'connected': true,
            'operationalEligible': true,
            'pairingStrategy': 1,
          });
          await Future<void>.delayed(Duration.zero);
        });
        await tester.pump();

        expect(
          find.descendant(
            of: find.byType(WatchConnectionCard),
            matching: find.text('Legacy S1'),
          ),
          findsOneWidget,
        );
        await tester.pumpWidget(const SizedBox());
      },
    );

    test('Strategy localization keys resolve in en, ru, zh, ko', () {
      for (final code in ['en', 'ru', 'zh', 'ko']) {
        final l10n = lookupAppLocalizations(Locale(code));
        expect(l10n.includeLegacyWatches, isNotEmpty);
        expect(l10n.includeLegacyWatchesSubtitle, isNotEmpty);
        expect(l10n.legacyStrategyBadge('S1'), contains('S1'));
      }
      expect(
        lookupAppLocalizations(const Locale('en')).includeLegacyWatches,
        'Include Legacy Watches (watchOS 1–9)',
      );
      expect(
        lookupAppLocalizations(const Locale('ru')).includeLegacyWatches,
        'Старые часы Apple Watch (watchOS 1–9)',
      );
      expect(
        lookupAppLocalizations(const Locale('zh')).includeLegacyWatches,
        '包含旧款 Apple Watch (watchOS 1–9)',
      );
      expect(
        lookupAppLocalizations(const Locale('ko')).includeLegacyWatches,
        '구형 Apple Watch 포함 (watchOS 1–9)',
      );
    });
  });
}
