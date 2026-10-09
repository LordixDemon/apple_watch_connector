import 'native_pigment_baseline.dart';
import 'native_pigment_preferences.dart';

/// A pair-bound HAL local preference. LOCAL can be unsent/uncertain, never applied.
final class NativePigmentMirror implements NativePigmentBaseline {
  final NativePigmentPreferences _value;
  final String? origin;
  final String? requestId;
  final NativePigmentPreferences? automatic;
  final String? automaticOrigin, automaticRequestId;
  const NativePigmentMirror._(
    this._value,
    this.origin,
    this.requestId, [
    this.automatic,
    this.automaticOrigin,
    this.automaticRequestId,
  ]);

  /// Null means an older Bridge without this protocol. Present malformed data is
  /// an unusable baseline, so it cannot silently fall back to another source.
  static NativePigmentMirror? fromBridge(Map data) {
    if (!data.keys.any(
      (key) => key is String && key.startsWith('pigmentMirror'),
    )) {
      return null;
    }
    NativePigmentMirror invalid() => const NativePigmentMirror._(
      NativePigmentPreferences.unknown(),
      null,
      null,
    );
    final origin = data['pigmentMirrorOrigin'],
        request = data['pigmentMirrorRequestId'];
    if (!const {'LOCAL', 'REMOTE'}.contains(origin) ||
        (origin == 'LOCAL' &&
            (request is! String || !_uuid.hasMatch(request))) ||
        (origin == 'REMOTE' && request != null)) {
      return invalid();
    }
    final value = NativePigmentPreferences.fromBridge({
      ...data,
      'pigmentPreferencePair': data['pigmentMirrorPair'],
      'pigmentPreferenceEpoch': data['pigmentMirrorEpoch'],
      'pigmentPreferenceNames': data['pigmentMirrorNames'],
      'pigmentPreferenceSourceTimestamp': data['pigmentMirrorSourceTimestamp'],
      'pigmentPreferenceObservedAt': data['pigmentMirrorUpdatedAt'],
    });
    if (!value.connected ||
        value.sourceTimestamp == null ||
        value.pair != data['pairId'] ||
        origin == 'LOCAL' && !value.known) {
      return invalid();
    }
    NativePigmentPreferences? automatic;
    String? autoOrigin, autoRequest;
    final rawOrigin = data['pigmentMirrorAutomaticOrigin'];
    final rawRequest = data['pigmentMirrorAutomaticRequestId'];
    final automaticPresent = data.keys.any(
      (key) => key is String && key.startsWith('pigmentMirrorAutomatic'),
    );
    if (automaticPresent) {
      if (data['pigmentMirrorVersion'] != 2 ||
          !const {'LOCAL', 'REMOTE'}.contains(rawOrigin) ||
          (rawOrigin == 'LOCAL' &&
              (rawRequest is! String || !_uuid.hasMatch(rawRequest))) ||
          (rawOrigin == 'REMOTE' && rawRequest != null)) {
        return invalid();
      }
      automatic = NativePigmentPreferences.fromBridge({
        ...data,
        'pigmentPreferencePair': data['pigmentMirrorPair'],
        'pigmentPreferenceEpoch': data['pigmentMirrorEpoch'],
        'pigmentPreferenceNames': data['pigmentMirrorAutomaticNames'],
        'pigmentPreferenceSourceTimestamp':
            data['pigmentMirrorAutomaticTimestamp'],
        'pigmentPreferenceObservedAt': data['pigmentMirrorAutomaticUpdatedAt'],
      });
      if (!automatic.connected ||
          automatic.sourceTimestamp == null ||
          rawOrigin == 'LOCAL' && !automatic.known) {
        return invalid();
      }
      autoOrigin = rawOrigin as String;
      autoRequest = rawRequest as String?;
    }
    return NativePigmentMirror._(
      value,
      origin as String,
      request as String?,
      automatic,
      autoOrigin,
      autoRequest,
    );
  }

  static final _uuid = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  );
  @override
  bool get connected => _value.connected;
  @override
  bool get known => _value.known;
  @override
  String? get pair => _value.pair;
  @override
  String? get epoch => _value.epoch;
  @override
  List<String>? get names => _value.names;
  @override
  double? get sourceTimestamp => _value.sourceTimestamp;
  @override
  int? get observedAt => _value.observedAt;
  @override
  bool canWrite(int now) =>
      connected &&
      known &&
      observedAt! <= now &&
      sourceTimestamp! <= now / 1000 - 978307200 + 5 &&
      automatic?.known == true &&
      automatic!.observedAt! <= now &&
      automatic!.sourceTimestamp! <= now / 1000 - 978307200 + 5;

  @override
  bool sameBaseline(NativePigmentBaseline other) =>
      other is NativePigmentMirror &&
      origin == other.origin &&
      requestId == other.requestId &&
      automaticOrigin == other.automaticOrigin &&
      automaticRequestId == other.automaticRequestId &&
      _value.sameObservation(other._value) &&
      (automatic == null
          ? other.automatic == null
          : other.automatic != null &&
                automatic!.sameObservation(other.automatic!));
}
