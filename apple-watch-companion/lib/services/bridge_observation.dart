import '../models/native_face_collection.dart';
import '../models/native_watch_settings.dart';
import '../models/native_pigment_preferences.dart';
import '../models/native_monogram_preferences.dart';
import '../models/native_monogram_mirror.dart';
import '../models/native_pigment_mirror.dart';
import '../models/phone_find_state.dart';
import '../models/watch_device.dart';
import '../models/watch_telemetry.dart';
import '../models/watch_connection_state.dart';
import '../models/watch_model_catalog.dart';
import '../models/notification_relay_state.dart';
import '../models/software_update_state.dart';

/// One connection publication decoded before any of its projections are emitted.
final class BridgeObservation {
  final WatchDevice device;
  final NativeFaceCollection faces;
  final NativeWatchSettings settings;
  final NativePigmentPreferences pigments;
  final NativePigmentMirror? pigmentMirror;
  final NativeMonogramPreferences monogram;
  final NativeMonogramMirror? monogramMirror;
  final PhoneFindState phoneFind;
  final NotificationRelayState notifications;
  final SoftwareUpdateState softwareUpdate;

  const BridgeObservation(
    this.device,
    this.faces,
    this.settings,
    this.phoneFind,
    this.pigments, [
    this.pigmentMirror,
    this.monogram = const NativeMonogramPreferences.unknown(),
    this.monogramMirror,
    this.notifications = const NotificationRelayState(),
    this.softwareUpdate = const SoftwareUpdateState(),
  ]);

  factory BridgeObservation.decode(Map data, {NativeFaceCollection? faces}) {
    faces ??= NativeFaceCollection.fromBridge(data);
    final connected = data['connected'] == true;
    final softwareUpdate = SoftwareUpdateState.fromBridge(data);
    final buildVersion = data['buildVersion'] as String?;
    final installedVersion = softwareUpdate.installedVersion;
    final installedBuild = softwareUpdate.installedBuild;
    final watchOsVersion =
        installedVersion != null && installedVersion.isNotEmpty
        ? (installedBuild != null && installedBuild.isNotEmpty
              ? '$installedVersion ($installedBuild)'
              : installedVersion)
        : (buildVersion != null && buildVersion.isNotEmpty
              ? buildVersion
              : '—');
    return BridgeObservation(
      WatchDevice.unknown().copyWith(
        id: data['pairId'] as String? ?? '',
        isConnected: connected,
        name: data['productType'] is String
            ? WatchConnectionState.nameForProduct(data['productType'] as String)
            : data['deviceName'] as String?,
        model: data['productType'] is String
            ? WatchConnectionState.nameForProduct(data['productType'] as String)
            : '—',
        modelIdentifier: data['productType'] as String? ?? '—',
        caseSize: WatchModelCatalog.caseSizeForProduct(
          data['productType'] as String? ?? '',
        ),
        watchOsVersion: watchOsVersion,
        batteryLevel: connected ? data['batteryLevel'] as int? ?? -1 : -1,
        isCharging: connected && data['isCharging'] == true,
        telemetry: WatchTelemetry.fromBridge(data),
        activeFaceId: faces.selected ?? '',
      ),
      faces,
      NativeWatchSettings.fromBridge(data),
      PhoneFindState.fromBridge(data),
      NativePigmentPreferences.fromBridge(data),
      NativePigmentMirror.fromBridge(data),
      NativeMonogramPreferences.fromBridge(data),
      NativeMonogramMirror.fromBridge(data),
      NotificationRelayState.fromBridge(data),
      softwareUpdate,
    );
  }
}
