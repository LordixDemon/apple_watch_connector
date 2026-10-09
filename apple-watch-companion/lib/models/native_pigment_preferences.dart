import 'native_pigment_baseline.dart';

/// Complete SelectedPigmentList received through the current owned transport.
/// Null names mean unknown/deleted; an empty list is a known empty selection.
final class NativePigmentPreferences implements NativePigmentBaseline {
  @override
  final bool connected;
  @override
  final String? pair;
  @override
  final String? epoch;
  @override
  final List<String>? names;
  @override
  final double? sourceTimestamp;
  @override
  final int? observedAt;

  const NativePigmentPreferences._({
    this.connected = false,
    this.pair,
    this.epoch,
    this.names,
    this.sourceTimestamp,
    this.observedAt,
  });
  const NativePigmentPreferences.unknown() : this._();

  @override
  bool get known => names != null;
  @override
  bool canWrite(int now) =>
      connected &&
      known &&
      pair != null &&
      epoch != null &&
      observedAt != null &&
      observedAt! <= now &&
      now - observedAt! <= 300000;
  static final _uuid = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  );
  static final _controls = RegExp(r'[\x00-\x1f\x7f-\x9f]');
  static bool _isUuid(Object? value) =>
      value is String && _uuid.hasMatch(value);

  factory NativePigmentPreferences.fromBridge(Map data) {
    final connected =
        data['connected'] == true && data['bridgeAvailable'] == true;
    final pair = data['faceCollectionPair'];
    final epoch = data['faceCollectionEpoch'];
    final targetKnown = connected && _isUuid(pair) && _isUuid(epoch);
    NativePigmentPreferences unknown() => NativePigmentPreferences._(
      connected: connected,
      pair: targetKnown ? pair as String : null,
      epoch: targetKnown ? epoch as String : null,
    );
    final stamp = data['pigmentPreferenceObservedAt'];
    final source = data['pigmentPreferenceSourceTimestamp'];
    final published = data['observedAt'];
    if (!targetKnown ||
        data['pigmentPreferencePair'] != pair ||
        data['pigmentPreferenceEpoch'] != epoch ||
        stamp is! int ||
        stamp <= 0 ||
        stamp > 8640000000000000 ||
        published is! int ||
        published <= 0 ||
        stamp > published + 5000 ||
        source is! num ||
        !source.isFinite ||
        source < 0 ||
        source > stamp / 1000 - 978307200 + 5) {
      return unknown();
    }
    final raw = data['pigmentPreferenceNames'];
    List<String>? names;
    if (raw != null) {
      if (raw is! List || raw.length > 1023) return unknown();
      final distinct = <String>{};
      var bytes = 0;
      for (final name in raw) {
        if (name is! String ||
            name.isEmpty ||
            name.length > 256 ||
            name.contains(':') ||
            _controls.hasMatch(name) ||
            !distinct.add(name)) {
          return unknown();
        }
        bytes += name.length * 2;
        if (bytes > 32768) return unknown();
      }
      names = List.unmodifiable(distinct);
    }
    return NativePigmentPreferences._(
      connected: true,
      pair: pair as String,
      epoch: epoch as String,
      names: names,
      sourceTimestamp: source.toDouble(),
      observedAt: stamp,
    );
  }

  /// Battery/telemetry publications should not rebuild the color picker.
  @override
  bool sameBaseline(NativePigmentBaseline other) =>
      other is NativePigmentPreferences && sameObservation(other);

  bool sameObservation(NativePigmentPreferences other) {
    if (connected != other.connected ||
        pair != other.pair ||
        epoch != other.epoch ||
        sourceTimestamp != other.sourceTimestamp ||
        observedAt != other.observedAt ||
        (names == null) != (other.names == null)) {
      return false;
    }
    if (names == null) return true;
    if (names!.length != other.names!.length) return false;
    for (var i = 0; i < names!.length; i++) {
      if (names![i] != other.names![i]) return false;
    }
    return true;
  }
}
