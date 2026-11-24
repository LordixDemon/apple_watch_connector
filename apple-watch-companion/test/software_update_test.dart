import 'dart:convert';
import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/models/software_update_state.dart';
import 'package:apple_watch_companion/models/watch_features.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/screens/my_watch/software_update_screen.dart';

class _NoNetworkFaces extends WatchFaceApiService {
  @override
  Future<List<WatchFace>> loadCachedFaces() async => [];
  @override
  Future<List<WatchFace>> fetchRemoteFaces({
    String endpoint = WatchFaceApiService.defaultGalleryEndpoint,
  }) async => [];
}

class _UpdateBridge extends WatchBridgeService {
  final SoftwareUpdateState state;
  final calls = <String>[];
  _UpdateBridge(this.state) : super(events: const Stream.empty());
  @override
  SoftwareUpdateState get softwareUpdate => state;
  @override
  Future<String> updateSoftware(
    String method,
    SoftwareUpdateState baseline,
  ) async {
    calls.add(method);
    return 'QUEUED';
  }
}

const pair = '11111111-1111-4111-8111-111111111111';
const epoch = '22222222-2222-4222-8222-222222222222';
Map<String, dynamic> native({String phase = 'AVAILABLE'}) => {
  'pairId': pair,
  'epoch': epoch,
  'known': true,
  'phase': phase,
  'observedAt': 100000,
  'scanCompleted': true,
  'installAttempted': false,
  'stalled': false,
  'termsAccepted': true,
  'installedVersion': '26.2',
  'installedBuild': '23S303',
  'targetVersion': '27.0.1',
  'targetBuild': '24R365',
  'downloadBytes': 1000000,
  'requiredSpaceBytes': 2000000,
  'progress': null,
  'errorCode': null,
};
Map<String, dynamic> bridge(Map update) => {
  'features': {'softwareUpdates': true},
  'connected': true,
  'pairId': pair,
  'faceCollectionEpoch': epoch,
  'softwareUpdate': update,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'waiting screen cannot manufacture up-to-date status after a timer',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      final service = _UpdateBridge(
        const SoftwareUpdateState(available: true, connected: true),
      );
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: service,
        apiService: _NoNetworkFaces(),
      );
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: const CupertinoApp(home: SoftwareUpdateScreen()),
        ),
      );
      await tester.pump(const Duration(seconds: 3));
      expect(
        find.text("Waiting for the watch's update status."),
        findsOneWidget,
      );
      expect(
        find.text('Your Apple Watch software is up to date.'),
        findsNothing,
      );
      expect(service.calls, isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      provider.dispose();
      service.dispose();
    },
  );
  testWidgets(
    'installation is explicit and queue receipt cannot advance native state',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      final state = SoftwareUpdateState.fromBridge(
        bridge({
          ...native(phase: 'PREPARED'),
          'observedAt': DateTime.now().millisecondsSinceEpoch,
        }),
      );
      final service = _UpdateBridge(state);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: service,
        apiService: _NoNetworkFaces(),
      );
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: const CupertinoApp(home: SoftwareUpdateScreen()),
        ),
      );
      await tester.pump();
      await tester.ensureVisible(find.text('Install Update'));
      await tester.tap(find.text('Install Update'));
      await tester.pumpAndSettle();
      expect(service.calls, ['querySoftwareUpdate']);
      expect(find.textContaining('24R365'), findsWidgets);
      await tester.tap(
        find.widgetWithText(CupertinoDialogAction, 'Install Update'),
      );
      await tester.pumpAndSettle();
      expect(service.calls.last, 'installSoftwareUpdate');
      expect(provider.softwareUpdate.phase, 'PREPARED');
      expect(
        find.text('Request queued. Waiting for confirmation from the watch.'),
        findsOneWidget,
      );
      expect(
        find.text('The watch has confirmed the installed build.'),
        findsNothing,
      );
      await tester.pumpWidget(const SizedBox.shrink());
      provider.dispose();
      service.dispose();
    },
  );
  test('Android JSON and Rust observations use one contract', () {
    final value = native();
    final desktop = SoftwareUpdateState.fromBridge(bridge(value));
    final android = SoftwareUpdateState.fromBridge({
      ...bridge(value),
      'softwareUpdate': null,
      'softwareUpdateJson': jsonEncode(value),
    });
    expect(desktop.targetBuild, android.targetBuild);
    expect(android.canDownload(100001), isTrue);
    expect(android.canInstall(100001), isFalse);
  });
  test(
    'disconnect, wrong pair, wrong epoch and invalid phases cannot enable updates',
    () {
      for (final data in [
        {...bridge(native()), 'connected': false},
        bridge({...native(), 'pairId': epoch}),
        bridge({...native(), 'epoch': pair}),
        bridge(native(phase: 'APPLIED')),
        {...bridge(native()), 'features': <String, bool>{}},
      ]) {
        final state = SoftwareUpdateState.fromBridge(data);
        expect(state.canQuery, isFalse);
        expect(state.canDownload(100001), isFalse);
      }
    },
  );
  test(
    'prepared, accepted, fresh, unattempted and different build are required',
    () {
      final value = native(phase: 'PREPARED');
      expect(
        SoftwareUpdateState.fromBridge(bridge(value)).canInstall(100001),
        isTrue,
      );
      for (final changed in [
        {...value, 'installAttempted': true},
        {...value, 'termsAccepted': false},
        {...value, 'installedBuild': '24R365'},
        {...value, 'known': false},
        {...value, 'observedAt': 1},
      ]) {
        expect(
          SoftwareUpdateState.fromBridge(bridge(changed)).canInstall(100001),
          isFalse,
        );
      }
    },
  );
  test('restart barrier cannot imply a completed update', () {
    final restart = SoftwareUpdateState.fromBridge(
      bridge(native(phase: 'VERIFYING_RESTART')),
    );
    expect(restart.phase, 'VERIFYING_RESTART');
    expect(restart.canInstall(100001), isFalse);
    expect(
      SoftwareUpdateState.fromBridge(bridge(native(phase: 'COMPLETE'))).known,
      isFalse,
    );
    final completed = {
      ...native(phase: 'COMPLETE'),
      'installedBuild': '24R365',
    };
    expect(SoftwareUpdateState.fromBridge(bridge(completed)).phase, 'COMPLETE');
  });
  test('malformed, oversized and nonfinite data are rejected', () {
    for (final value in [
      {...native(), 'progress': double.nan},
      {...native(), 'progress': 1.01},
      {...native(), 'targetBuild': '24R365\nUPDATE_INSTALL'},
      {...native(), 'downloadBytes': -1},
      {...native(), 'stalled': 'false'},
      {...native(), 'observedAt': 1.5},
    ]) {
      expect(SoftwareUpdateState.fromBridge(bridge(value)).known, isFalse);
    }
    expect(
      SoftwareUpdateState.fromBridge({
        ...bridge(native()),
        'softwareUpdateJson': '{invalid',
      }).known,
      isFalse,
    );
    expect(
      SoftwareUpdateState.fromBridge({
        ...bridge(native()),
        'softwareUpdate': null,
        'softwareUpdateJson': 'x' * 8193,
      }).known,
      isFalse,
    );
  });
  test('initial authenticated epoch can query without claiming an update', () {
    final state = SoftwareUpdateState.fromBridge(
      bridge({
        ...native(phase: 'UNKNOWN'),
        'known': false,
        'observedAt': 0,
        'scanCompleted': false,
      }),
    );
    expect(state.canQuery, isTrue);
    expect(state.canDownload(100001), isFalse);
    expect(
      WatchFeatures.featureForCommand('installSoftwareUpdate'),
      'softwareUpdates',
    );
  });
}
