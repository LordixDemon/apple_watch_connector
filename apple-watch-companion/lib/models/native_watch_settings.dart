import '../l10n/strings.dart';

enum NativeWatchSetting {
  rightWrist('RIGHT_WRIST'),
  invertScreen('INVERT_SCREEN'),
  time24Hour('TIME_24_HOUR');

  final String wireName;
  const NativeWatchSetting(this.wireName);
  String get title => switch (this) {
    rightWrist => Strings.current.rightWrist,
    invertScreen => Strings.current.rotateScreen180,
    time24Hour => Strings.current.message24Hourtime,
  };
}

class NativeSettingObservation {
  final bool value;
  final DateTime observedAt;
  const NativeSettingObservation(this.value, this.observedAt);
}

class NativeWatchSettings {
  final bool connected;
  final Map<NativeWatchSetting, NativeSettingObservation> values;
  const NativeWatchSettings({this.connected = false, this.values = const {}});

  factory NativeWatchSettings.fromBridge(Map<dynamic, dynamic> data) {
    final connected =
        data['connected'] == true && data['bridgeAvailable'] == true;
    final values = <NativeWatchSetting, NativeSettingObservation>{};
    if (connected) {
      for (final setting in NativeWatchSetting.values) {
        final key = 'watchSetting_${setting.wireName}';
        final value = data[key];
        final time = data['${key}_observedAt'];
        if (value is bool &&
            time is int &&
            time > 0 &&
            time <= 8640000000000000) {
          values[setting] = NativeSettingObservation(
            value,
            DateTime.fromMillisecondsSinceEpoch(time),
          );
        }
      }
    }
    return NativeWatchSettings(
      connected: connected,
      values: Map.unmodifiable(values),
    );
  }
}
