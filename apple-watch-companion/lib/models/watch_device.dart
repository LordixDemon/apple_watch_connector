import '../l10n/strings.dart';
import 'watch_telemetry.dart';

/// Information about the paired Apple Watch hardware and OS.
class WatchDevice {
  final String id;
  final String name;
  final String model;
  final String modelIdentifier;
  final String caseMaterial;
  final String caseSize;
  final String watchOsVersion;
  final String serialNumber;
  final String bluetoothAddress;
  final String wifiAddress;
  final String seId;
  final int batteryLevel; // 0 to 100
  final bool isCharging;
  final bool isConnected;
  final bool isSyncing;
  final String activeFaceId;
  final WatchTelemetry telemetry;

  const WatchDevice({
    required this.id,
    required this.name,
    required this.model,
    required this.modelIdentifier,
    required this.caseMaterial,
    required this.caseSize,
    required this.watchOsVersion,
    required this.serialNumber,
    required this.bluetoothAddress,
    required this.wifiAddress,
    required this.seId,
    required this.batteryLevel,
    required this.isCharging,
    required this.isConnected,
    required this.isSyncing,
    required this.activeFaceId,
    this.telemetry = const WatchTelemetry(),
  });

  /// No hardware is assumed before a public identity arrives from Bridge.
  factory WatchDevice.unknown() {
    return WatchDevice(
      id: '',
      name: Strings.current.appleWatch,
      model: '—',
      modelIdentifier: '—',
      caseMaterial: '—',
      caseSize: '—',
      watchOsVersion: '—',
      serialNumber: '—',
      bluetoothAddress: '—',
      wifiAddress: '—',
      seId: '—',
      batteryLevel: -1,
      isCharging: false,
      isConnected: false,
      isSyncing: false,
      activeFaceId: '',
    );
  }

  WatchDevice copyWith({
    String? id,
    String? name,
    String? model,
    String? modelIdentifier,
    String? caseMaterial,
    String? caseSize,
    String? watchOsVersion,
    String? serialNumber,
    String? bluetoothAddress,
    String? wifiAddress,
    String? seId,
    int? batteryLevel,
    bool? isCharging,
    bool? isConnected,
    bool? isSyncing,
    String? activeFaceId,
    WatchTelemetry? telemetry,
  }) {
    return WatchDevice(
      id: id ?? this.id,
      name: name ?? this.name,
      model: model ?? this.model,
      modelIdentifier: modelIdentifier ?? this.modelIdentifier,
      caseMaterial: caseMaterial ?? this.caseMaterial,
      caseSize: caseSize ?? this.caseSize,
      watchOsVersion: watchOsVersion ?? this.watchOsVersion,
      serialNumber: serialNumber ?? this.serialNumber,
      bluetoothAddress: bluetoothAddress ?? this.bluetoothAddress,
      wifiAddress: wifiAddress ?? this.wifiAddress,
      seId: seId ?? this.seId,
      batteryLevel: batteryLevel ?? this.batteryLevel,
      isCharging: isCharging ?? this.isCharging,
      isConnected: isConnected ?? this.isConnected,
      isSyncing: isSyncing ?? this.isSyncing,
      activeFaceId: activeFaceId ?? this.activeFaceId,
      telemetry: telemetry ?? this.telemetry,
    );
  }
}
