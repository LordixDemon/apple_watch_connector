/// Implemented service groups, declared by the backend independently of connection readiness.
final class WatchFeatures {
  final bool known;
  final Set<String> _available;
  const WatchFeatures.unknown() : known = false, _available = const {};
  WatchFeatures.fromMap(Map? value)
    : known = value != null,
      _available = Set.unmodifiable(
        value?.entries
                .where((e) => e.value == true)
                .map((e) => e.key.toString()) ??
            const <String>[],
      );

  bool supports(String feature) => _available.contains(feature);
  bool get nativeFaces => supports('nativeFaces');
  bool get watchSettings => supports('watchSettings');
  bool get telemetry => supports('telemetry');
  bool get phoneFind => supports('phoneFind');
  bool get notifications => supports('notificationRelay');
  bool get health => supports('health');
  bool get appManagement => supports('appManagement');

  static String? featureForCommand(String method) => switch (method) {
    'setActiveFace' ||
    'duplicateNativeFace' ||
    'removeNativeFace' ||
    'reorderNativeFaces' ||
    'updateNativeFace' ||
    'addNativeFace' ||
    'exportNativeFace' ||
    'beginNativeFaceImport' ||
    'appendNativeFaceImport' ||
    'finishNativeFaceImport' ||
    'cancelNativeFaceImport' ||
    'refreshFaceCollection' ||
    'setNativePigmentVisibility' ||
    'setNativeMonogram' => 'nativeFaces',
    'setWatchSetting' => 'watchSettings',
    'stopPhonePing' || 'requestPhoneFlashPermission' => 'phoneFind',
    'sendNotification' => 'notificationRelay',
    _ => null,
  };
}
