import '../l10n/strings.dart';
import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/watch_settings.dart';
import '../models/watch_device.dart';

/// Storage helper to persist paired watch configuration locally.
class SettingsStorage {
  static const _keySettings = 'aw_settings_v1';
  static const _keyDevice = 'aw_device_v1';
  static const _keyInstalledFaces = 'aw_installed_faces_v1';
  static const _keyPigmentFavorites = 'aw_native_pigment_favorites_v1';
  static const _keyPigmentIntents = 'aw_native_pigment_intents_v2';
  static const _keyFaceEditIntents = 'aw_native_face_edit_intents_v1';
  static const _keyMonogramIntents = 'aw_native_monogram_intents_v1';

  final SharedPreferences _prefs;

  SettingsStorage(this._prefs);

  String? loadMonogramIntents() => _prefs.getString(_keyMonogramIntents);
  Future<void> saveMonogramIntents(String value) async {
    if (!await _prefs.setString(_keyMonogramIntents, value)) {
      throw StateError('Monogram could not be saved.');
    }
  }

  String? loadFaceEditIntents() => _prefs.getString(_keyFaceEditIntents);
  Future<void> saveFaceEditIntents(String value) async {
    if (!await _prefs.setString(_keyFaceEditIntents, value)) {
      throw StateError('Face changes could not be saved.');
    }
  }

  String? loadPigmentFavorites() => _prefs.getString(_keyPigmentFavorites);

  String? loadPigmentIntents() => _prefs.getString(_keyPigmentIntents);
  Future<void> savePigmentIntents(String value) async {
    if (!await _prefs.setString(_keyPigmentIntents, value)) {
      throw StateError('Color changes could not be saved.');
    }
  }

  Future<void> savePigmentFavorites(String value) async {
    if (!await _prefs.setString(_keyPigmentFavorites, value)) {
      throw StateError('Color preferences could not be saved.');
    }
  }

  static Future<SettingsStorage> init() async {
    final prefs = await SharedPreferences.getInstance();
    return SettingsStorage(prefs);
  }

  WatchSettings loadSettings() {
    final jsonStr = _prefs.getString(_keySettings);
    if (jsonStr == null) return const WatchSettings();
    try {
      final map = jsonDecode(jsonStr) as Map<String, dynamic>;
      return WatchSettings(
        watchName: map['watchName'] as String? ?? Strings.current.appleWatch,
        wristOrientation: (map['wristOrientation'] == 'right')
            ? WristOrientation.right
            : WristOrientation.left,
        crownOrientation: (map['crownOrientation'] == 'left')
            ? CrownOrientation.left
            : CrownOrientation.right,
        airplaneMode: map['airplaneMode'] as bool? ?? false,
        bluetoothEnabled: map['bluetoothEnabled'] as bool? ?? true,
        wifiEnabled: map['wifiEnabled'] as bool? ?? true,
        backgroundAppRefresh: map['backgroundAppRefresh'] as bool? ?? true,
        brightness: (map['brightness'] as num?)?.toDouble() ?? 0.85,
        textSize: (map['textSize'] as num?)?.toDouble() ?? 0.5,
        boldText: map['boldText'] as bool? ?? false,
        alwaysOnDisplay: map['alwaysOnDisplay'] as bool? ?? true,
        wakeOnWristRaise: map['wakeOnWristRaise'] as bool? ?? true,
        alertVolume: (map['alertVolume'] as num?)?.toDouble() ?? 0.8,
        silentMode: map['silentMode'] as bool? ?? false,
        hapticAlerts: map['hapticAlerts'] as bool? ?? true,
        crownHaptics: map['crownHaptics'] as bool? ?? true,
        systemHaptics: map['systemHaptics'] as bool? ?? true,
        passcodeEnabled: map['passcodeEnabled'] as bool? ?? true,
        wristDetection: map['wristDetection'] as bool? ?? true,
        standReminders: map['standReminders'] as bool? ?? true,
        holdForSiren: map['holdForSiren'] as bool? ?? true,
        fallDetection: map['fallDetection'] as bool? ?? true,
      );
    } catch (_) {
      return const WatchSettings();
    }
  }

  Future<void> saveSettings(WatchSettings settings) async {
    final map = {
      'watchName': settings.watchName,
      'wristOrientation': settings.wristOrientation.name,
      'crownOrientation': settings.crownOrientation.name,
      'airplaneMode': settings.airplaneMode,
      'bluetoothEnabled': settings.bluetoothEnabled,
      'wifiEnabled': settings.wifiEnabled,
      'backgroundAppRefresh': settings.backgroundAppRefresh,
      'brightness': settings.brightness,
      'textSize': settings.textSize,
      'boldText': settings.boldText,
      'alwaysOnDisplay': settings.alwaysOnDisplay,
      'wakeOnWristRaise': settings.wakeOnWristRaise,
      'alertVolume': settings.alertVolume,
      'silentMode': settings.silentMode,
      'hapticAlerts': settings.hapticAlerts,
      'crownHaptics': settings.crownHaptics,
      'systemHaptics': settings.systemHaptics,
      'passcodeEnabled': settings.passcodeEnabled,
      'wristDetection': settings.wristDetection,
      'standReminders': settings.standReminders,
      'holdForSiren': settings.holdForSiren,
      'fallDetection': settings.fallDetection,
    };
    await _prefs.setString(_keySettings, jsonEncode(map));
  }

  WatchDevice? loadDevice() {
    final jsonStr = _prefs.getString(_keyDevice);
    if (jsonStr == null) return WatchDevice.unknown();
    try {
      final map = jsonDecode(jsonStr) as Map<String, dynamic>;
      return WatchDevice(
        id: map['id'] as String? ?? '',
        name: map['name'] as String? ?? Strings.current.appleWatch,
        model: map['model'] as String? ?? '—',
        modelIdentifier: map['modelIdentifier'] as String? ?? '—',
        caseMaterial: map['caseMaterial'] as String? ?? '—',
        caseSize: map['caseSize'] as String? ?? '—',
        watchOsVersion: map['watchOsVersion'] as String? ?? '—',
        serialNumber: map['serialNumber'] as String? ?? '—',
        bluetoothAddress: map['bluetoothAddress'] as String? ?? '—',
        wifiAddress: map['wifiAddress'] as String? ?? '—',
        seId: map['seId'] as String? ?? '',
        batteryLevel: -1,
        isCharging: false,
        isConnected: false,
        isSyncing: map['isSyncing'] as bool? ?? false,
        activeFaceId: map['activeFaceId'] as String? ?? '',
      );
    } catch (_) {
      return WatchDevice.unknown();
    }
  }

  Future<void> saveDevice(WatchDevice device) async {
    final map = {
      'id': device.id,
      'name': device.name,
      'model': device.model,
      'modelIdentifier': device.modelIdentifier,
      'caseMaterial': device.caseMaterial,
      'caseSize': device.caseSize,
      'watchOsVersion': device.watchOsVersion,
      'serialNumber': device.serialNumber,
      'bluetoothAddress': device.bluetoothAddress,
      'wifiAddress': device.wifiAddress,
      'seId': device.seId,
      'isSyncing': device.isSyncing,
      'activeFaceId': device.activeFaceId,
    };
    await _prefs.setString(_keyDevice, jsonEncode(map));
  }

  List<String>? loadInstalledFaceIds() {
    return _prefs.getStringList(_keyInstalledFaces);
  }

  Future<void> saveInstalledFaceIds(List<String> ids) async {
    await _prefs.setStringList(_keyInstalledFaces, ids);
  }
}
