import 'dart:async';
import 'package:flutter/services.dart';
import '../models/watch_device.dart';
import '../models/watch_connection_state.dart';
import 'bridge_transport.dart';
import 'bridge_commands.dart';
import 'bridge_observation.dart';
import '../models/native_monogram_preferences.dart';
import '../models/native_monogram_mirror.dart';
import 'companion_platform.dart';
import '../models/health_metrics.dart';
import '../models/phone_find_state.dart';
import '../models/native_watch_settings.dart';
import '../models/native_pigment_preferences.dart';
import '../models/native_pigment_mirror.dart';
import '../models/native_face_collection.dart';
import 'native_face_observation_cache.dart';
import '../models/watch_features.dart';

/// Shared UI client; Android Binder and desktop Rust implement its transport.
class WatchBridgeService with BridgeCommands {
  final BridgeTransport _transport;
  final CompanionPlatform platform;

  final StreamController<WatchDevice> _deviceStreamController =
      StreamController<WatchDevice>.broadcast();
  final _observationsController =
      StreamController<BridgeObservation>.broadcast();
  Stream<BridgeObservation> get observationStream =>
      _observationsController.stream;
  final StreamController<HealthMetrics> _healthStreamController =
      StreamController<HealthMetrics>.broadcast();
  final StreamController<Map<String, dynamic>> _notificationActionController =
      StreamController<Map<String, dynamic>>.broadcast();
  final StreamController<PhoneFindState> _phoneFindController =
      StreamController<PhoneFindState>.broadcast();
  PhoneFindState _phoneFindState = const PhoneFindState();
  PhoneFindState get phoneFindState => _phoneFindState;
  final StreamController<NativeWatchSettings> _nativeSettingsController =
      StreamController<NativeWatchSettings>.broadcast();
  NativeWatchSettings _nativeSettings = const NativeWatchSettings();
  final _pigmentPreferencesController =
      StreamController<NativePigmentPreferences>.broadcast();
  NativePigmentPreferences _pigmentPreferences =
      const NativePigmentPreferences.unknown();
  NativePigmentPreferences get pigmentPreferences => _pigmentPreferences;
  NativePigmentMirror? _pigmentMirror;
  @override
  NativePigmentMirror? get pigmentMirror => _pigmentMirror;
  Stream<NativePigmentPreferences> get pigmentPreferencesStream =>
      _pigmentPreferencesController.stream;
  final StreamController<NativeFaceCollection> _faceCollectionController =
      StreamController<NativeFaceCollection>.broadcast();
  NativeFaceCollection _faceCollection = const NativeFaceCollection();
  final _faceObservationCache = NativeFaceObservationCache();
  @override
  NativeFaceCollection get faceCollection => _faceCollection;
  Stream<NativeFaceCollection> get faceCollectionStream =>
      _faceCollectionController.stream;
  NativeWatchSettings get nativeSettings => _nativeSettings;
  NativeMonogramPreferences _monogram =
      const NativeMonogramPreferences.unknown();
  NativeMonogramPreferences get monogramPreferences => _monogram;
  NativeMonogramMirror? _monogramMirror;
  @override
  NativeMonogramMirror? get monogramMirror => _monogramMirror;
  Stream<NativeWatchSettings> get nativeSettingsStream =>
      _nativeSettingsController.stream;
  final StreamController<Map<String, dynamic>> _callRelayController =
      StreamController<Map<String, dynamic>>.broadcast();
  StreamSubscription<dynamic>? _events;
  bool _disposed = false;
  WatchDevice _device = WatchDevice.unknown();
  WatchConnectionState _connectionState = const WatchConnectionState();
  final _connectionController =
      StreamController<WatchConnectionState>.broadcast();
  WatchConnectionState get connectionState => _connectionState;
  Stream<WatchConnectionState> get connectionStream =>
      _connectionController.stream;
  WatchDevice get currentDevice => _device;

  Stream<WatchDevice> get deviceStream => _deviceStreamController.stream;
  Stream<HealthMetrics> get healthStream => _healthStreamController.stream;
  Stream<Map<String, dynamic>> get notificationActionStream =>
      _notificationActionController.stream;
  Stream<PhoneFindState> get phoneFindStateStream =>
      _phoneFindController.stream;
  Stream<Map<String, dynamic>> get callRelayStream =>
      _callRelayController.stream;

  WatchBridgeService({
    Stream<dynamic>? events,
    BridgeTransport? transport,
    this.platform = CompanionPlatform.android,
  }) : _transport = transport ?? const PlatformBridgeTransport() {
    _initPlatformEvents(events);
  }

  void _initPlatformEvents(Stream<dynamic>? events) {
    try {
      _events = (events ?? _transport.events).listen(
        (dynamic event) {
          if (_disposed) return;
          if (event is Map) {
            final type = event['type'] as String?;
            final data = event['data'];
            if (type == 'connection' && data is Map) {
              _publishConnection(data);
            } else if (type == 'health' &&
                data is Map &&
                HealthMetrics.isCompleteObservation(data)) {
              _healthStreamController.add(
                HealthMetrics.fromMap(Map<String, dynamic>.from(data)),
              );
            } else if ((type == 'notificationAction' || type == 'operation') &&
                data is Map) {
              _notificationActionController.add(
                Map<String, dynamic>.from(data),
              );
            } else if (type == 'callAction' && data is Map) {
              _callRelayController.add(Map<String, dynamic>.from(data));
            }
          }
        },
        onError: (dynamic error) {
          if (!_disposed) _publishConnection({'connected': false});
        },
        onDone: () {
          if (!_disposed) _publishConnection({'connected': false});
        },
      );
    } catch (_) {
      if (!_disposed) _publishConnection({'connected': false});
    }
  }

  void _publishConnection(Map data) {
    _connectionState = WatchConnectionState.fromBridge(data);
    final faces = _faceObservationCache.decode(data);
    final facesChanged = !identical(faces, _faceCollection);
    final observation = BridgeObservation.decode(data, faces: faces);
    final pigmentsChanged = !_pigmentPreferences.sameObservation(
      observation.pigments,
    );
    _device = observation.device;
    _faceCollection = observation.faces;
    _nativeSettings = observation.settings;
    _monogram = observation.monogram;
    _monogramMirror = observation.monogramMirror;
    _phoneFindState = observation.phoneFind;
    if (pigmentsChanged) _pigmentPreferences = observation.pigments;
    _pigmentMirror = observation.pigmentMirror;
    _connectionController.add(_connectionState);
    _observationsController.add(observation);
    if (facesChanged) _faceCollectionController.add(_faceCollection);
    if (pigmentsChanged) {
      _pigmentPreferencesController.add(_pigmentPreferences);
    }
    _nativeSettingsController.add(_nativeSettings);
    _phoneFindController.add(_phoneFindState);
    _deviceStreamController.add(_device);
  }

  @override
  Future<dynamic> invokeBridgeMethod(
    String method, [
    Map<String, dynamic>? args,
  ]) {
    if (_disposed) throw PlatformException(code: 'UNAVAILABLE');
    final feature = WatchFeatures.featureForCommand(method);
    if (feature != null &&
        _connectionState.features.known &&
        !_connectionState.features.supports(feature)) {
      return Future.value({'status': 'UNSUPPORTED_OPERATION'});
    }
    return _transport.invoke(method, args);
  }

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    if (_events != null) unawaited(_events!.cancel());
    if (_transport case OwnedBridgeTransport owned) owned.dispose();
    _deviceStreamController.close();
    _observationsController.close();
    _connectionController.close();
    _nativeSettingsController.close();
    _pigmentPreferencesController.close();
    _faceCollectionController.close();
    _healthStreamController.close();
    _notificationActionController.close();
    _phoneFindController.close();
    _callRelayController.close();
  }
}
