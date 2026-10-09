import '../services/native_monogram_text_rules.dart';

/// A custom-text receipt from the owned Watch. Null text is an observed deletion.
final class NativeMonogramPreferences {
  final bool connected, received;
  final String? pair, epoch, text;
  final double? sourceTimestamp;
  final int? observedAt;
  const NativeMonogramPreferences._({
    this.connected = false,
    this.received = false,
    this.pair,
    this.epoch,
    this.text,
    this.sourceTimestamp,
    this.observedAt,
  });
  const NativeMonogramPreferences.unknown() : this._();

  static final _uuid = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  );
  factory NativeMonogramPreferences.fromBridge(Map data) {
    final connected =
        data['connected'] == true && data['bridgeAvailable'] == true;
    final pair = data['faceCollectionPair'],
        epoch = data['faceCollectionEpoch'];
    if (!connected ||
        pair is! String ||
        epoch is! String ||
        !_uuid.hasMatch(pair) ||
        !_uuid.hasMatch(epoch)) {
      return const NativeMonogramPreferences.unknown();
    }
    final time = data['monogramPreferenceObservedAt'],
        source = data['monogramPreferenceSourceTimestamp'];
    final text = data['monogramPreferenceText'], published = data['observedAt'];
    if (data['monogramPreferencePair'] != pair ||
        data['monogramPreferenceEpoch'] != epoch ||
        time is! int ||
        time <= 0 ||
        time > 8640000000000000 ||
        published is! int ||
        published <= 0 ||
        time > published + 5000 ||
        source is! num ||
        !source.isFinite ||
        source < 0 ||
        source > time / 1000 - 978307200 + 5 ||
        text != null &&
            (text is! String || !NativeMonogramTextRules.valid(text))) {
      return NativeMonogramPreferences._(
        connected: true,
        pair: pair,
        epoch: epoch,
      );
    }
    return NativeMonogramPreferences._(
      connected: true,
      received: true,
      pair: pair,
      epoch: epoch,
      text: text as String?,
      sourceTimestamp: source.toDouble(),
      observedAt: time,
    );
  }
}
