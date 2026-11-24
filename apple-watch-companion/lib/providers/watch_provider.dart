import 'dart:typed_data';
import 'dart:async';
import '../controllers/watch_observation_controller.dart';
import '../controllers/native_pigment_sync_controller.dart';
import '../controllers/native_monogram_sync_controller.dart';
import 'package:flutter/foundation.dart';
import '../models/watch_device.dart';
import '../models/watch_face.dart';
import '../models/watch_settings.dart';
import '../controllers/face_library_controller.dart';
import '../controllers/native_face_controller.dart';
import '../controllers/native_face_autosave_controller.dart';
import '../services/settings_storage.dart';
import '../services/watch_bridge_service.dart';
import '../services/watch_face_api_service.dart';
import '../services/native_face_gallery.dart';
import '../services/native_pigment_favorites.dart';

import '../models/health_metrics.dart';
import '../models/phone_find_state.dart';
import '../models/notification_relay_state.dart';
import '../models/software_update_state.dart';
import '../models/native_watch_settings.dart';
import '../models/native_monogram_preferences.dart';
import '../models/native_monogram_mirror.dart';
import '../models/native_pigment_preferences.dart';
import '../models/native_face_collection.dart';

class WatchProvider extends ChangeNotifier {
  final SettingsStorage _storage;
  final WatchBridgeService _bridge;
  late final FaceLibraryController _faces;
  late final NativeFaceController nativeFaces;
  late final NativeFaceAutosaveController nativeFaceAutosave;
  late final NativePigmentFavorites pigmentFavorites;
  late final NativePigmentSyncController _pigmentSync;
  late final StreamSubscription<Map<String, dynamic>> _pigmentOperations;
  late final NativeMonogramSyncController monogramSync;
  late final StreamSubscription<Map<String, dynamic>> _monogramOperations;
  bool _disposed = false;
  bool _faceProfilesRequested = false;

  final WatchObservationController _observations;
  WatchDevice get _device => _observations.device;
  WatchSettings _settings;
  bool _isLoading = false;

  WatchProvider({
    required SettingsStorage storage,
    required WatchBridgeService bridge,
    WatchFaceApiService? apiService,
    bool? ownsApiService,
  }) : _storage = storage,
       _bridge = bridge,
       _observations = WatchObservationController(bridge: bridge),
       _settings = storage.loadSettings() {
    _faces = FaceLibraryController(
      storage: storage,
      api: apiService ?? WatchFaceApiService(),
      ownsApi: ownsApiService ?? apiService == null,
    );
    _faces.addListener(_changed);
    nativeFaces = NativeFaceController(bridge)..addListener(_changed);
    nativeFaceAutosave = NativeFaceAutosaveController(
      faces: nativeFaces,
      saved: storage.loadFaceEditIntents(),
      persist: storage.saveFaceEditIntents,
    )..addListener(_changed);
    _pigmentSync = NativePigmentSyncController(
      saved: storage.loadPigmentIntents(),
      persist: storage.savePigmentIntents,
      send: bridge.setNativePigmentVisibility,
    );
    pigmentFavorites = NativePigmentFavorites.synchronized(_pigmentSync);
    _pigmentOperations = bridge.operationStatusStream.listen(
      _pigmentSync.operation,
    );
    _observePigments();
    _observations.addListener(_observePigments);
    monogramSync = NativeMonogramSyncController(
      saved: storage.loadMonogramIntents(),
      persist: storage.saveMonogramIntents,
      send: bridge.setNativeMonogram,
    )..addListener(_changed);
    _monogramOperations = bridge.operationStatusStream.listen(
      monogramSync.operation,
    );
    _observeMonogram();
    _observations.addListener(_observeMonogram);
    _observations.addListener(_changed);
  }

  void _changed() {
    if (_disposed) return;
    if (!_faceProfilesRequested && nativeFaces.collection.faces.isNotEmpty) {
      _faceProfilesRequested = true;
      _loadFaceProfiles();
    }
    notifyListeners();
  }

  void _observePigments() {
    _pigmentSync.observe(
      _observations.device.id,
      _observations.pigmentPreferences,
      baseline: _observations.pigmentMirror,
    );
  }

  void _observeMonogram() => monogramSync.observe(
    _observations.monogramMirror,
    _observations.monogramPreferences,
  );

  Future<void> _loadFaceProfiles() async {
    try {
      await NativeFaceGallery.load();
      if (!_disposed) notifyListeners();
    } on Exception {
      // The received native names/configurations remain the fallback.
    }
  }

  Future<void> saveFaceDesign(WatchFace face) => _faces.saveFaceDesign(face);
  Future<void> removeLocalFaceDesign(String id) =>
      _faces.removeLocalFaceDesign(id);
  Future<void> fetchRemoteCatalog() => _faces.fetchRemoteCatalog();
  Future<WatchFace> importWatchFace(Uint8List bytes) async {
    final face = await _faces.importWatchFace(bytes);
    if (!_disposed) await _storage.saveDevice(_device);
    return face;
  }

  Uint8List exportWatchFace(WatchFace face, {Uint8List? snapshotBytes}) =>
      _faces.exportWatchFace(face, snapshotBytes: snapshotBytes);

  WatchDevice get device => _device;
  PhoneFindState get phoneFind => _observations.phoneFind;
  NotificationRelayState get notifications => _observations.notifications;
  Stream<Map<String, dynamic>> get operationStatusStream =>
      _bridge.operationStatusStream;
  SoftwareUpdateState get softwareUpdate => _observations.softwareUpdate;
  Future<String> updateSoftware(String method, SoftwareUpdateState baseline) =>
      _bridge.updateSoftware(method, baseline);
  Future<bool> requestNotificationAccess() =>
      _bridge.requestNotificationAccess();
  Future<String> setNotificationRelay(
    bool enabled, {
    String? packageName,
    String? mode,
  }) => _bridge.setNotificationRelay(
    enabled,
    packageName: packageName,
    mode: mode,
  );
  Future<String> retryPendingNotifications() =>
      _bridge.retryPendingNotifications();
  Future<({String status, String? requestId})> queueTestNotification(
    String title,
    String body,
  ) => _bridge.queueNotificationTest(title, body);
  NativeWatchSettings get nativeSettings => _observations.nativeSettings;
  NativeMonogramPreferences get monogramPreferences =>
      _observations.monogramPreferences;
  NativeMonogramMirror? get monogramMirror => _observations.monogramMirror;
  Future<({String status, String? requestId})> setNativeMonogram(
    String text,
    NativeMonogramMirror baseline,
  ) => monogramSync.update(text, baseline);
  NativePigmentPreferences get pigmentPreferences =>
      _observations.pigmentPreferences;
  NativeFaceCollection get faceCollection => nativeFaces.collection;
  Future<String> setWatchSetting(NativeWatchSetting setting, bool value) =>
      _bridge.setWatchSetting(setting, value);
  Future<bool> stopPhonePing() => _bridge.stopPhonePing();
  Future<bool> requestPhoneFlashPermission() =>
      _bridge.requestPhoneFlashPermission();
  WatchSettings get settings => _settings;
  HealthMetrics get health => _observations.health;
  bool get hasHealthObservation => _observations.hasHealthObservation;
  List<WatchFace> get localFaceDesigns => _faces.designs;
  List<WatchFace> get remoteFaces => _faces.remote;
  bool get isLoading => _isLoading;
  Future<String> refreshDeviceInfo() => _bridge.refreshDeviceInfo();

  Future<void> updateSettings(WatchSettings newSettings) async {
    _settings = newSettings;
    _changed();
    await _storage.saveSettings(newSettings);
    await _bridge.syncSettings({
      'wrist': newSettings.wristOrientation.name,
      'crown': newSettings.crownOrientation.name,
      'brightness': newSettings.brightness,
      'haptics': newSettings.hapticAlerts,
      'silent': newSettings.silentMode,
    });
  }

  Future<void> pingWatch() async {
    await _bridge.pingWatch();
  }

  Future<bool> sendTestNotification(
    String title,
    String body, {
    String? appName,
  }) async {
    return await _bridge.sendNotificationTest(
      title,
      body,
      sectionDisplayName: appName,
    );
  }

  Future<bool> triggerIncomingCall(
    String callerName,
    String callerNumber, {
    bool isVideo = false,
  }) async {
    return await _bridge.triggerIncomingCall(
      callerName,
      callerNumber,
      isVideo: isVideo,
    );
  }

  Future<void> unpairWatch() async {
    _isLoading = true;
    _changed();
    final applied = await _bridge.unpairWatch();
    if (_disposed) return;
    if (applied) _observations.disconnectLocally();
    _isLoading = false;
    _changed();
    await _storage.saveDevice(_device);
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    nativeFaceAutosave.removeListener(_changed);
    nativeFaceAutosave.dispose();
    nativeFaces.removeListener(_changed);
    nativeFaces.dispose();
    pigmentFavorites.dispose();
    unawaited(_pigmentOperations.cancel());
    _pigmentSync.dispose();
    unawaited(_monogramOperations.cancel());
    monogramSync.removeListener(_changed);
    monogramSync.dispose();
    _faces.removeListener(_changed);
    _faces.dispose();
    _observations.removeListener(_changed);
    _observations.removeListener(_observePigments);
    _observations.removeListener(_observeMonogram);
    _observations.dispose();
    super.dispose();
  }
}
