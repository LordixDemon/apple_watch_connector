import 'package:flutter/foundation.dart';
import 'bluetooth_adapter.dart';
import 'pairing_strategy.dart';
import 'watch_features.dart';
import 'watch_model_catalog.dart';

final class DiscoveredWatch {
  final String token, productType, watchOs;
  final int rssi;
  final int strategy;
  final bool experimental;
  final String? displayName;
  final List<String> authStyles;

  const DiscoveredWatch(
    this.token,
    this.productType,
    this.watchOs,
    this.rssi, {
    this.strategy = 4,
    this.experimental = false,
    this.displayName,
    this.authStyles = const ['PIN'],
  });

  String get name => WatchConnectionState.nameForProduct(productType);
  String get effectiveName => displayName ?? name;
  String get strategyBadge => PairingStrategyInfo.badgeFor(strategy);
  bool get isLegacy => experimental || strategy < 4;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is DiscoveredWatch &&
          token == other.token &&
          productType == other.productType &&
          watchOs == other.watchOs &&
          rssi == other.rssi &&
          strategy == other.strategy &&
          experimental == other.experimental &&
          displayName == other.displayName &&
          listEquals(authStyles, other.authStyles);

  @override
  int get hashCode => Object.hash(
    token,
    productType,
    watchOs,
    rssi,
    strategy,
    experimental,
    displayName,
    Object.hashAll(authStyles),
  );

  static DiscoveredWatch? parse(dynamic value) {
    if (value is! Map || value['token'] is! String || value['rssi'] is! int) {
      return null;
    }
    final token = value['token'] as String;
    if (!RegExp(
      r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
    ).hasMatch(token)) {
      return null;
    }
    final rawProductType = value['productType'];
    final rawWatchOs = value['watchOs'];
    final rawDisplayName = value['displayName'];
    if ((rawProductType != null && rawProductType is! String) ||
        (rawWatchOs != null && rawWatchOs is! String) ||
        (rawDisplayName != null && rawDisplayName is! String)) {
      return null;
    }
    final rawStrategy = value['strategy'];
    if (rawStrategy != null &&
        (rawStrategy is! int || rawStrategy < 1 || rawStrategy > 4)) {
      return null;
    }
    final rawExperimental = value['experimental'];
    if (rawExperimental != null && rawExperimental is! bool) {
      return null;
    }
    final productType = rawProductType as String? ?? '';
    final watchOs = rawWatchOs as String? ?? '';
    final trimmedDisplayName = (rawDisplayName as String?)?.trim();
    final displayName =
        trimmedDisplayName != null && trimmedDisplayName.isNotEmpty
        ? trimmedDisplayName
        : null;
    if (productType.isEmpty && displayName == null && rawStrategy == null) {
      return null;
    }
    final strategy = rawStrategy as int? ?? 4;
    final experimental = rawExperimental as bool? ?? strategy < 4;
    final rawAuthStyles = value['authStyles'];
    List<String> authStyles = const ['PIN'];
    if (rawAuthStyles is List) {
      final parsed = List<String>.unmodifiable(
        rawAuthStyles.whereType<String>().where((s) => s.isNotEmpty),
      );
      if (parsed.isNotEmpty) {
        authStyles = parsed;
      }
    }
    return DiscoveredWatch(
      token,
      productType,
      watchOs,
      value['rssi'] as int,
      strategy: strategy,
      experimental: experimental,
      displayName: displayName,
      authStyles: authStyles,
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
  final List<PairingStrategyInfo> supportedStrategies;
  final int? pairingStrategy;
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
    this.supportedStrategies = const [],
    this.pairingStrategy,
    this.bluetoothAdapters = const [],
    this.adapterSelectionAvailable = false,
    this.adapterSelectionBusy = false,
    this.adapterInventoryRefreshing = false,
    this.selectedAdapterId = '',
    this.adapterSelectionStatus = '',
    this.adapterSelectionError = '',
  });

  static List<PairingStrategyInfo> _parseSupportedStrategies(Map data) {
    final capabilities = data['capabilities'];
    final rawList = capabilities is Map && capabilities['strategies'] is List
        ? capabilities['strategies'] as List
        : (data['supportedStrategies'] is List
              ? data['supportedStrategies'] as List
              : null);
    if (rawList == null) return PairingStrategyInfo.defaults;
    return List<PairingStrategyInfo>.unmodifiable(
      rawList.map(PairingStrategyInfo.parse).whereType<PairingStrategyInfo>(),
    );
  }

  static int? _parsePairingStrategy(dynamic value) =>
      value is int && value >= 1 && value <= 4 ? value : null;

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
    supportedStrategies: _parseSupportedStrategies(data),
    pairingStrategy: _parsePairingStrategy(data['pairingStrategy']),
  );

  int? get pairedStrategy => pairingStrategy;

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
  bool get _bluetoothReady =>
      adapterSelectionAvailable ? adapterConnectionAllowed : bluetoothPermission;
  bool get needsWatchConfirmation =>
      hasPair && activationConfirmed && !operationalEligible;
  bool get canConfirmSetup =>
      bridgeAvailable &&
      identityKnown &&
      _bluetoothReady &&
      needsWatchConfirmation &&
      setupPhase != 'STOPPING' &&
      status != 'STOPPING' &&
      (setupRunning || !busy && !connected && !bluetoothLink);
  bool get canResumeSetup =>
      bridgeAvailable &&
      identityKnown &&
      _bluetoothReady &&
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
  bool sameUiState(WatchConnectionState other) =>
      identical(this, other) ||
      features == other.features &&
          bridgeAvailable == other.bridgeAvailable &&
          identityKnown == other.identityKnown &&
          hasPair == other.hasPair &&
          operationalEligible == other.operationalEligible &&
          activationConfirmed == other.activationConfirmed &&
          connected == other.connected &&
          bluetoothPermission == other.bluetoothPermission &&
          setupRunning == other.setupRunning &&
          pinRequired == other.pinRequired &&
          pairingAvailable == other.pairingAvailable &&
          discoveryAvailable == other.discoveryAvailable &&
          wifiAvailable == other.wifiAvailable &&
          bluetoothLink == other.bluetoothLink &&
          pairId == other.pairId &&
          productType == other.productType &&
          buildVersion == other.buildVersion &&
          status == other.status &&
          setupPhase == other.setupPhase &&
          discoveredProductType == other.discoveredProductType &&
          discoveredWatchOs == other.discoveredWatchOs &&
          backendError == other.backendError &&
          challengeId == other.challengeId &&
          challengeTitle == other.challengeTitle &&
          challengeMessage == other.challengeMessage &&
          adapterSelectionAvailable == other.adapterSelectionAvailable &&
          adapterSelectionBusy == other.adapterSelectionBusy &&
          adapterInventoryRefreshing == other.adapterInventoryRefreshing &&
          selectedAdapterId == other.selectedAdapterId &&
          adapterSelectionStatus == other.adapterSelectionStatus &&
          adapterSelectionError == other.adapterSelectionError &&
          pairingStrategy == other.pairingStrategy &&
          listEquals(challengeFields, other.challengeFields) &&
          listEquals(discoveredWatches, other.discoveredWatches) &&
          listEquals(supportedStrategies, other.supportedStrategies) &&
          listEquals(bluetoothAdapters, other.bluetoothAdapters);

  static String nameForProduct(String product) =>
      WatchModelCatalog.nameForProduct(product);
}
