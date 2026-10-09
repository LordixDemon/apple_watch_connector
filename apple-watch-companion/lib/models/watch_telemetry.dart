import '../l10n/strings.dart';

/// One complete About snapshot received from the trusted Bridge, never cached.
class WatchTelemetry {
  final int? batteryLevel;
  final bool? isCharging;
  final int? observedAt;
  final int? availableStorageBytes;
  final int? numberOfApps;
  final int? numberOfSongs;
  final int? numberOfPhotos;
  final int? purgeableSpaceBytes;
  final int? userDeletableSpaceBytes;

  const WatchTelemetry({
    this.batteryLevel,
    this.isCharging,
    this.observedAt,
    this.availableStorageBytes,
    this.numberOfApps,
    this.numberOfSongs,
    this.numberOfPhotos,
    this.purgeableSpaceBytes,
    this.userDeletableSpaceBytes,
  });

  factory WatchTelemetry.fromBridge(Map<dynamic, dynamic> data) {
    if (data['connected'] != true) return const WatchTelemetry();
    int? scalar(String key) {
      final value = data[key];
      return value is int && value >= 0 ? value : null;
    }

    final stamp = scalar('aboutObservedAt');
    if (stamp == null || stamp == 0 || stamp > 8640000000000000) {
      return const WatchTelemetry();
    }
    final capacity = scalar('batteryLevel');
    return WatchTelemetry(
      observedAt: stamp,
      batteryLevel: capacity != null && capacity <= 100 ? capacity : null,
      isCharging: data['chargingObserved'] == true && data['isCharging'] is bool
          ? data['isCharging'] as bool
          : null,
      availableStorageBytes: scalar('availableStorageBytes'),
      numberOfApps: scalar('numberOfApps'),
      numberOfSongs: scalar('numberOfSongs'),
      numberOfPhotos: scalar('numberOfPhotos'),
      purgeableSpaceBytes: scalar('purgeableSpaceBytes'),
      userDeletableSpaceBytes: scalar('userDeletableSpaceBytes'),
    );
  }

  bool isFreshAt(DateTime now) {
    final stamp = observedAt;
    if (stamp == null) return false;
    final age = now.millisecondsSinceEpoch - stamp;
    return age >= 0 && age < const Duration(minutes: 5).inMilliseconds;
  }

  static String formatBytes(int? bytes) {
    if (bytes == null) return '—';
    if (bytes >= 1000000000) {
      return Strings.current.gb(
        ((bytes / 1000000000).toStringAsFixed(1)).toString(),
      );
    }
    if (bytes >= 1000000) {
      return Strings.current.mb(
        ((bytes / 1000000).toStringAsFixed(1)).toString(),
      );
    }
    if (bytes >= 1000) {
      return Strings.current.kb(((bytes / 1000).toStringAsFixed(1)).toString());
    }
    return Strings.current.b((bytes).toString());
  }
}
