import 'package:flutter/foundation.dart';

final class NotificationApp {
  final String packageName, name;
  final bool enabled;
  final bool observed;
  const NotificationApp(
    this.packageName,
    this.name,
    this.enabled, {
    this.observed = true,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is NotificationApp &&
          packageName == other.packageName &&
          name == other.name &&
          enabled == other.enabled &&
          observed == other.observed;

  @override
  int get hashCode => Object.hash(packageName, name, enabled, observed);
}

/// Phone-side permission and policy observations, separate from Watch delivery.
final class NotificationRelayState {
  final bool available,
      accessGranted,
      listenerConnected,
      enabled,
      onlyWhenScreenOff,
      connected,
      ready;
  final int pending;
  final List<NotificationApp> apps;
  const NotificationRelayState({
    this.available = false,
    this.accessGranted = false,
    this.listenerConnected = false,
    this.enabled = false,
    this.onlyWhenScreenOff = false,
    this.connected = false,
    this.ready = false,
    this.pending = 0,
    this.apps = const [],
  });

  NotificationRelayState disconnected() => NotificationRelayState(
    available: available,
    accessGranted: accessGranted,
    listenerConnected: listenerConnected,
    enabled: enabled,
    onlyWhenScreenOff: onlyWhenScreenOff,
    connected: false,
    ready: false,
    pending: pending,
    apps: apps,
  );

  factory NotificationRelayState.fromBridge(Map data) {
    if (data['bridgeAvailable'] != true ||
        const [
          'notificationAccessGranted',
          'notificationListenerConnected',
          'notificationRelayEnabled',
          'notificationRelayReady',
        ].any((key) => data[key] is! bool)) {
      return const NotificationRelayState();
    }
    final access = data['notificationAccessGranted'] == true;
    final listener = data['notificationListenerConnected'] == true;
    final enabled = data['notificationRelayEnabled'] == true;
    final onlyWhenScreenOff = data['notificationOnlyWhenScreenOff'] == true;
    final connected = data['connected'] == true;
    final rows = data['notificationApps'];
    final apps = <NotificationApp>[];
    final seen = <String>{};
    if (rows is List && rows.length <= 1024) {
      for (final row in rows) {
        if (row is! Map ||
            row['packageName'] is! String ||
            row['name'] is! String ||
            row['enabled'] is! bool) {
          continue;
        }
        final pkg = row['packageName'] as String, name = row['name'] as String;
        if (pkg.length > 256 ||
            !RegExp(r'^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$').hasMatch(pkg) ||
            !seen.add(pkg)) {
          continue;
        }
        apps.add(
          NotificationApp(
            pkg,
            name.isEmpty ? pkg : name,
            row['enabled'] as bool,
            observed: row['observed'] != false,
          ),
        );
      }
    }
    return NotificationRelayState(
      available: true,
      accessGranted: access,
      listenerConnected: listener,
      enabled: enabled,
      onlyWhenScreenOff: onlyWhenScreenOff,
      connected: connected,
      ready:
          enabled &&
          access &&
          listener &&
          connected &&
          data['notificationRelayReady'] == true,
      pending:
          data['notificationPendingCount'] is int &&
              (data['notificationPendingCount'] as int) >= 0 &&
              (data['notificationPendingCount'] as int) <= 256
          ? data['notificationPendingCount'] as int
          : 0,
      apps: List.unmodifiable(apps),
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is NotificationRelayState &&
          available == other.available &&
          accessGranted == other.accessGranted &&
          listenerConnected == other.listenerConnected &&
          enabled == other.enabled &&
          onlyWhenScreenOff == other.onlyWhenScreenOff &&
          connected == other.connected &&
          ready == other.ready &&
          pending == other.pending &&
          listEquals(apps, other.apps);

  @override
  int get hashCode => Object.hash(
    available,
    accessGranted,
    listenerConnected,
    enabled,
    onlyWhenScreenOff,
    connected,
    ready,
    pending,
    Object.hashAll(apps),
  );
}
