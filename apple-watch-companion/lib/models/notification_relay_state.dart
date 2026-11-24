final class NotificationApp {
  final String packageName, name;
  final bool enabled;
  const NotificationApp(this.packageName, this.name, this.enabled);
}

/// Phone-side permission and policy observations, separate from Watch delivery.
final class NotificationRelayState {
  final bool available, accessGranted, listenerConnected, enabled, ready;
  final int pending;
  final List<NotificationApp> apps;
  const NotificationRelayState({
    this.available = false,
    this.accessGranted = false,
    this.listenerConnected = false,
    this.enabled = false,
    this.ready = false,
    this.pending = 0,
    this.apps = const [],
  });

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
    final rows = data['notificationApps'];
    final apps = <NotificationApp>[];
    final seen = <String>{};
    if (rows is List && rows.length <= 512) {
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
          ),
        );
      }
    }
    return NotificationRelayState(
      available: true,
      accessGranted: access,
      listenerConnected: listener,
      enabled: enabled,
      ready:
          enabled &&
          access &&
          listener &&
          data['connected'] == true &&
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
}
