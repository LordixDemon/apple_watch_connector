import 'dart:convert';

/// Native observations only. A queued command never advances this state.
final class SoftwareUpdateState {
  final bool available, connected, known, scanCompleted, installAttempted;
  final bool stalled, termsAccepted;
  final String? pair,
      epoch,
      installedVersion,
      installedBuild,
      targetVersion,
      targetBuild;
  final String phase;
  final int observedAt;
  final int? downloadBytes, requiredSpaceBytes, errorCode;
  final double? progress;
  const SoftwareUpdateState({
    this.available = false,
    this.connected = false,
    this.known = false,
    this.scanCompleted = false,
    this.installAttempted = false,
    this.stalled = false,
    this.termsAccepted = false,
    this.pair,
    this.epoch,
    this.installedVersion,
    this.installedBuild,
    this.targetVersion,
    this.targetBuild,
    this.phase = 'UNKNOWN',
    this.observedAt = 0,
    this.downloadBytes,
    this.requiredSpaceBytes,
    this.errorCode,
    this.progress,
  });

  static final _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );
  static final _version = RegExp(r'^[0-9]{1,3}(\.[0-9]{1,3}){0,3}$');
  static final _build = RegExp(r'^[0-9]{1,3}[A-Za-z][0-9]{1,6}[a-z]?$');
  static const phases = {
    'UNKNOWN',
    'IDLE',
    'CHECKING',
    'AVAILABLE',
    'DOWNLOADING_PREPARING',
    'WAITING_PREPARATION',
    'PREPARED',
    'WAITING_INSTALLATION',
    'INSTALLING',
    'VERIFYING_RESTART',
    'COMPLETE',
    'FAILED',
  };

  factory SoftwareUpdateState.fromBridge(Map data) {
    final features = data['features'];
    final available = features is Map && features['softwareUpdates'] == true;
    final connected = available && data['connected'] == true;
    final empty = SoftwareUpdateState(
      available: available,
      connected: connected,
    );
    try {
      final encoded = data['softwareUpdateJson'];
      final value = encoded is String && utf8.encode(encoded).length <= 8192
          ? jsonDecode(encoded)
          : data['softwareUpdate'];
      if (value is! Map ||
          !connected ||
          value['pairId'] != data['pairId'] ||
          value['pairId'] is! String ||
          !_uuid.hasMatch(value['pairId'] as String) ||
          value['epoch'] is! String ||
          !_uuid.hasMatch(value['epoch'] as String) ||
          data['faceCollectionEpoch'] != value['epoch'] ||
          !phases.contains(value['phase']) ||
          value['known'] is! bool ||
          value['observedAt'] is! int ||
          (value['observedAt'] as int) < 0 ||
          value['known'] == true && (value['observedAt'] as int) == 0) {
        return empty;
      }
      for (final key in [
        'installedVersion',
        'targetVersion',
        'installedBuild',
        'targetBuild',
      ]) {
        final field = value[key];
        if (field != null &&
            (field is! String ||
                field.length > 32 ||
                !(key.endsWith('Build') ? _build : _version).hasMatch(field))) {
          return empty;
        }
      }
      for (final key in [
        'scanCompleted',
        'installAttempted',
        'stalled',
        'termsAccepted',
      ]) {
        if (value[key] is! bool) return empty;
      }
      for (final key in ['downloadBytes', 'requiredSpaceBytes', 'errorCode']) {
        if (value[key] != null &&
            (value[key] is! int ||
                key != 'errorCode' && (value[key] as int) < 0)) {
          return empty;
        }
      }
      final portion = value['progress'];
      if (portion != null &&
          (portion is! num ||
              !portion.isFinite ||
              portion < 0 ||
              portion > 1)) {
        return empty;
      }
      if (value['phase'] == 'COMPLETE' &&
          (value['known'] != true ||
              value['targetBuild'] == null ||
              value['targetBuild'] != value['installedBuild'])) {
        return empty;
      }
      return SoftwareUpdateState(
        available: available,
        connected: connected,
        pair: value['pairId'] as String,
        epoch: value['epoch'] as String,
        known: value['known'] as bool,
        phase: value['phase'] as String,
        observedAt: value['observedAt'] as int,
        scanCompleted: value['scanCompleted'] as bool,
        installAttempted: value['installAttempted'] as bool,
        stalled: value['stalled'] as bool,
        termsAccepted: value['termsAccepted'] as bool,
        installedVersion: value['installedVersion'] as String?,
        installedBuild: value['installedBuild'] as String?,
        targetVersion: value['targetVersion'] as String?,
        targetBuild: value['targetBuild'] as String?,
        downloadBytes: value['downloadBytes'] as int?,
        requiredSpaceBytes: value['requiredSpaceBytes'] as int?,
        errorCode: value['errorCode'] as int?,
        progress: (portion as num?)?.toDouble(),
      );
    } on FormatException {
      return empty;
    }
  }
  bool get canQuery => available && connected && pair != null && epoch != null;
  bool fresh(int now) =>
      known && observedAt > 0 && now >= observedAt && now - observedAt <= 60000;
  bool canDownload(int now) =>
      canQuery &&
      fresh(now) &&
      phase == 'AVAILABLE' &&
      termsAccepted &&
      targetBuild != null;
  bool canInstall(int now) =>
      canQuery &&
      fresh(now) &&
      phase == 'PREPARED' &&
      termsAccepted &&
      !installAttempted &&
      targetBuild != null &&
      targetBuild != installedBuild;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SoftwareUpdateState &&
          available == other.available &&
          connected == other.connected &&
          known == other.known &&
          scanCompleted == other.scanCompleted &&
          installAttempted == other.installAttempted &&
          stalled == other.stalled &&
          termsAccepted == other.termsAccepted &&
          pair == other.pair &&
          epoch == other.epoch &&
          installedVersion == other.installedVersion &&
          installedBuild == other.installedBuild &&
          targetVersion == other.targetVersion &&
          targetBuild == other.targetBuild &&
          phase == other.phase &&
          observedAt == other.observedAt &&
          downloadBytes == other.downloadBytes &&
          requiredSpaceBytes == other.requiredSpaceBytes &&
          errorCode == other.errorCode &&
          progress == other.progress;

  @override
  int get hashCode => Object.hash(
    available,
    connected,
    known,
    scanCompleted,
    installAttempted,
    stalled,
    termsAccepted,
    pair,
    epoch,
    installedVersion,
    installedBuild,
    targetVersion,
    targetBuild,
    phase,
    observedAt,
    downloadBytes,
    requiredSpaceBytes,
    errorCode,
    progress,
  );
}
