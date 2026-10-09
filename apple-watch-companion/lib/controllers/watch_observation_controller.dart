import 'dart:async';
import 'package:flutter/foundation.dart';
import '../models/watch_device.dart';
import '../models/health_metrics.dart';
import '../models/phone_find_state.dart';
import '../models/native_watch_settings.dart';
import '../models/native_monogram_preferences.dart';
import '../models/native_monogram_mirror.dart';
import '../models/native_pigment_preferences.dart';
import '../models/native_pigment_mirror.dart';
import '../models/native_face_collection.dart';
import '../services/watch_bridge_service.dart';

/// Owns subscriptions to native observations; local preferences/catalog never enter them.
class WatchObservationController extends ChangeNotifier {
  final WatchBridgeService _bridge;
  bool _disposed = false;
  WatchDevice _device;
  HealthMetrics _health = HealthMetrics.initial();
  bool _hasHealthObservation = false;
  PhoneFindState _phoneFind = const PhoneFindState();
  NativeWatchSettings _nativeSettings = const NativeWatchSettings();
  NativeMonogramPreferences _monogram =
      const NativeMonogramPreferences.unknown();
  NativePigmentPreferences _pigmentPreferences =
      const NativePigmentPreferences.unknown();
  NativeFaceCollection _faceCollection = const NativeFaceCollection();
  NativePigmentMirror? _pigmentMirror;
  NativeMonogramMirror? _monogramMirror;
  final List<StreamSubscription<dynamic>> _subscriptions = [];

  WatchObservationController({required WatchBridgeService bridge})
    : _bridge = bridge,
      _device = bridge.currentDevice {
    _phoneFind = bridge.phoneFindState;
    _nativeSettings = bridge.nativeSettings;
    _monogram = bridge.monogramPreferences;
    _pigmentPreferences = bridge.pigmentPreferences;
    _pigmentMirror = bridge.pigmentMirror;
    _monogramMirror = bridge.monogramMirror;
    _faceCollection = bridge.faceCollection;
    _initBridgeListeners();
  }
  WatchDevice get device => _device;
  HealthMetrics get health => _health;
  bool get hasHealthObservation => _hasHealthObservation;
  PhoneFindState get phoneFind => _phoneFind;
  NativeWatchSettings get nativeSettings => _nativeSettings;
  NativeMonogramPreferences get monogramPreferences => _monogram;
  NativeMonogramMirror? get monogramMirror => _monogramMirror;
  NativePigmentPreferences get pigmentPreferences => _pigmentPreferences;
  NativePigmentMirror? get pigmentMirror => _pigmentMirror;
  NativeFaceCollection get faceCollection => _faceCollection;
  void _changed() {
    if (!_disposed) notifyListeners();
  }

  void disconnectLocally() {
    _device = _device.copyWith(isConnected: false);
    _pigmentPreferences = const NativePigmentPreferences.unknown();
    _monogram = const NativeMonogramPreferences.unknown();
    _pigmentMirror = null;
    _monogramMirror = null;
    _changed();
  }

  void _initBridgeListeners() {
    // One immutable native publication updates all projections together. Listening
    // to each stream separately exposed partial states and notified five times.
    _subscriptions.add(
      _bridge.observationStream.listen((state) {
        _device = state.device;
        _faceCollection = state.faces;
        _nativeSettings = state.settings;
        _monogram = state.monogram;
        _monogramMirror = state.monogramMirror;
        _pigmentPreferences = state.pigments;
        _pigmentMirror = state.pigmentMirror;
        _phoneFind = state.phoneFind;
        _changed();
      }),
    );
    _subscriptions.add(
      _bridge.healthStream.listen((m) {
        _health = m;
        _hasHealthObservation = true;
        _changed();
      }),
    );
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    for (final subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    super.dispose();
  }
}
