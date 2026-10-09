import '../l10n/strings.dart';
import 'bluetooth_adapter.dart';
import 'watch_features.dart';

final class DiscoveredWatch {
  final String token, productType, watchOs;
  final int rssi;
  const DiscoveredWatch(this.token, this.productType, this.watchOs, this.rssi);
  static DiscoveredWatch? parse(dynamic value) {
    if (value is! Map ||
        value['token'] is! String ||
        value['productType'] is! String ||
        value['watchOs'] is! String ||
        value['rssi'] is! int) {
      return null;
    }
    final token = value['token'] as String;
    if (!RegExp(
      r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
    ).hasMatch(token)) {
      return null;
    }
    return DiscoveredWatch(
      token,
      value['productType'] as String,
      value['watchOs'] as String,
      value['rssi'] as int,
    );
  }
}

/// Public native snapshot. No PIN, account credentials or pairing keys are retained.
final class WatchConnectionState {
  final WatchFeatures features;
  final bool bridgeAvailable,
      identityKnown,
      hasPair,
      operationalEligible,
      activationConfirmed,
      connected,
      bluetoothPermission,
      setupRunning,
      pinRequired,
      pairingAvailable,
      discoveryAvailable,
      wifiAvailable,
      bluetoothLink;
  final String pairId,
      productType,
      buildVersion,
      status,
      setupPhase,
      discoveredProductType,
      discoveredWatchOs,
      journal,
      backendError;
  final int? challengeId;
  final String challengeTitle, challengeMessage;
  final List<String> challengeFields;
  final List<DiscoveredWatch> discoveredWatches;
  final List<BluetoothAdapter> bluetoothAdapters;
  final bool adapterSelectionAvailable,
      adapterSelectionBusy,
      adapterInventoryRefreshing;
  final String selectedAdapterId, adapterSelectionStatus, adapterSelectionError;

  const WatchConnectionState({
    this.features = const WatchFeatures.unknown(),
    this.bridgeAvailable = false,
    this.identityKnown = false,
    this.hasPair = false,
    this.operationalEligible = false,
    this.activationConfirmed = false,
    this.connected = false,
    this.bluetoothPermission = false,
    this.setupRunning = false,
    this.pinRequired = false,
    this.pairingAvailable = true,
    this.discoveryAvailable = false,
    this.wifiAvailable = true,
    this.bluetoothLink = false,
    this.backendError = '',
    this.pairId = '',
    this.productType = '',
    this.buildVersion = '',
    this.status = 'DISCONNECTED',
    this.setupPhase = 'IDLE',
    this.discoveredProductType = '',
    this.discoveredWatchOs = '',
    this.journal = '',
    this.challengeId,
    this.challengeTitle = '',
    this.challengeMessage = '',
    this.challengeFields = const [],
    this.discoveredWatches = const [],
    this.bluetoothAdapters = const [],
    this.adapterSelectionAvailable = false,
    this.adapterSelectionBusy = false,
    this.adapterInventoryRefreshing = false,
    this.selectedAdapterId = '',
    this.adapterSelectionStatus = '',
    this.adapterSelectionError = '',
  });

  factory WatchConnectionState.fromBridge(Map data) => WatchConnectionState(
    features: WatchFeatures.fromMap(
      data['features'] is Map ? data['features'] as Map : null,
    ),
    bridgeAvailable: data['bridgeAvailable'] == true,
    identityKnown: data['identityKnown'] == true,
    hasPair: data['hasPair'] == true,
    operationalEligible: data['operationalEligible'] == true,
    activationConfirmed: data['activationConfirmed'] == true,
    connected: data['connected'] == true,
    bluetoothPermission: data['bluetoothPermission'] == true,
    setupRunning: data['setupRunning'] == true,
    pinRequired: data['pinRequired'] == true,
    pairingAvailable: data['pairingAvailable'] != false,
    discoveryAvailable: data['discoveryAvailable'] == true,
    wifiAvailable: data['wifiAvailable'] != false,
    bluetoothLink: data['bluetoothLink'] == true,
    adapterSelectionAvailable: data['adapterSelectionAvailable'] == true,
    adapterSelectionBusy: data['adapterSelectionBusy'] == true,
    adapterInventoryRefreshing: data['adapterInventoryRefreshing'] == true,
    selectedAdapterId: data['selectedAdapterId'] as String? ?? '',
    adapterSelectionStatus: data['adapterSelectionStatus'] as String? ?? '',
    adapterSelectionError: data['adapterSelectionError'] as String? ?? '',
    bluetoothAdapters: List<BluetoothAdapter>.unmodifiable(
      (data['bluetoothAdapters'] as List? ?? const [])
          .take(64)
          .map(BluetoothAdapter.parse)
          .whereType<BluetoothAdapter>(),
    ),
    backendError: data['backendError'] as String? ?? '',
    pairId: data['pairId'] as String? ?? '',
    productType: data['productType'] as String? ?? '',
    buildVersion: data['buildVersion'] as String? ?? '',
    status: data['connectionStatus'] as String? ?? 'DISCONNECTED',
    setupPhase: data['setupPhase'] as String? ?? 'IDLE',
    discoveredProductType: data['discoveredProductType'] as String? ?? '',
    discoveredWatchOs: data['discoveredWatchOs'] as String? ?? '',
    journal: data['bridgeJournal'] as String? ?? '',
    challengeId: data['activationChallengeId'] as int?,
    challengeTitle: data['activationTitle'] as String? ?? '',
    challengeMessage: data['activationMessage'] as String? ?? '',
    challengeFields: List<String>.unmodifiable(
      (data['activationFields'] as List? ?? const []).whereType<String>(),
    ),
    discoveredWatches: List<DiscoveredWatch>.unmodifiable(
      (data['discoveredWatches'] as List? ?? const [])
          .take(8)
          .map(DiscoveredWatch.parse)
          .whereType<DiscoveredWatch>(),
    ),
  );

  bool get busy =>
      setupRunning || status == 'CONNECTING' || status == 'STOPPING';
  String get effectiveSetupPhase {
    if (operationalEligible && setupPhase != 'STOPPING') return 'VERIFIED';
    // A durable activation observation takes precedence over an older progress
    // snapshot from Binder. Explicit HAL phases still describe later setup work.
    if (activationConfirmed &&
        const {
          'IDLE',
          'STARTING',
          'DISCOVERING',
          'CONNECTING',
          'PIN_REQUIRED',
          'SECURITY',
          'IDS',
          'REGISTRY',
          'CONFIGURING',
          'ACTIVATING',
          'ACTIVATION_INPUT',
        }.contains(setupPhase)) {
      return 'ACTIVATED';
    }
    return setupPhase;
  }

  bool get setupWorking =>
      setupRunning &&
      !pinRequired &&
      challengeId == null &&
      !const {
        'IDLE',
        'ACTIVATED',
        'WAITING_FOR_WATCH',
        'VERIFYING_RECONNECT',
        'VERIFIED',
        'FAILED',
        'STOPPED',
        'BLUETOOTH_LINK',
      }.contains(effectiveSetupPhase);
  bool get needsWatchConfirmation =>
      hasPair && activationConfirmed && !operationalEligible;
  bool get canConfirmSetup =>
      bridgeAvailable &&
      identityKnown &&
      bluetoothPermission &&
      needsWatchConfirmation &&
      setupPhase != 'STOPPING' &&
      status != 'STOPPING' &&
      (setupRunning || !busy && !connected && !bluetoothLink);
  bool get canResumeSetup =>
      bridgeAvailable &&
      identityKnown &&
      bluetoothPermission &&
      hasPair &&
      !operationalEligible &&
      !busy &&
      !connected &&
      !bluetoothLink;
  bool get canPair =>
      adapterConnectionAllowed &&
      pairingAvailable &&
      bridgeAvailable &&
      identityKnown &&
      !hasPair &&
      !busy &&
      !connected &&
      !bluetoothLink;
  bool get canDiscover =>
      adapterConnectionAllowed &&
      discoveryAvailable &&
      bridgeAvailable &&
      identityKnown &&
      !hasPair &&
      !busy &&
      !connected &&
      !bluetoothLink;
  bool get canConnect =>
      adapterConnectionAllowed &&
      bridgeAvailable &&
      hasPair &&
      operationalEligible &&
      !busy &&
      !connected;
  bool get adapterConnectionAllowed =>
      !adapterSelectionAvailable || adapterSelectionStatus == 'READY';
  bool get canSelectAdapter =>
      adapterSelectionAvailable && bridgeAvailable && !adapterSelectionBusy;
  String get modelName => nameForProduct(
    productType.isNotEmpty ? productType : discoveredProductType,
  );
  static String nameForProduct(String product) => switch (product) {
    'Watch7,5' => 'Apple Watch Ultra 2',
    _ =>
      product.isEmpty
          ? Strings.current.appleWatch
          : '${Strings.current.appleWatch} ($product)',
  };
}
