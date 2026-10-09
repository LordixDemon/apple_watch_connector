import 'native_monogram_preferences.dart';

/// A durable paired local preference. LOCAL never means applied on the Watch.
final class NativeMonogramMirror {
  final String pair, epoch, revision;
  final bool known;
  final String? text, origin, requestId;
  final double? sourceTimestamp;
  final int? updatedAt;
  const NativeMonogramMirror._(
    this.pair,
    this.epoch,
    this.revision,
    this.known,
    this.text,
    this.origin,
    this.requestId,
    this.sourceTimestamp,
    this.updatedAt,
  );
  static final _uuid = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  );

  static NativeMonogramMirror? fromBridge(Map data) {
    final pair = data['monogramMirrorPair'],
        epoch = data['monogramMirrorEpoch'],
        revision = data['monogramMirrorRevision'];
    final known = data['monogramMirrorKnown'];
    if (data['monogramMirrorVersion'] != 1 ||
        data['connected'] != true ||
        data['bridgeAvailable'] != true ||
        pair is! String ||
        epoch is! String ||
        revision is! String ||
        known is! bool ||
        !_uuid.hasMatch(pair) ||
        !_uuid.hasMatch(epoch) ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(revision) ||
        pair != data['pairId'] ||
        pair != data['faceCollectionPair'] ||
        epoch != data['faceCollectionEpoch']) {
      return null;
    }
    if (!known) {
      if (data.keys.any(
        (key) => const {
          'monogramMirrorText',
          'monogramMirrorOrigin',
          'monogramMirrorRequestId',
          'monogramMirrorSourceTimestamp',
          'monogramMirrorUpdatedAt',
        }.contains(key),
      )) {
        return null;
      }
      return NativeMonogramMirror._(
        pair,
        epoch,
        revision,
        false,
        null,
        null,
        null,
        null,
        null,
      );
    }
    final origin = data['monogramMirrorOrigin'],
        request = data['monogramMirrorRequestId'];
    if (!const {'LOCAL', 'REMOTE'}.contains(origin) ||
        origin == 'LOCAL' && (request is! String || !_uuid.hasMatch(request)) ||
        origin == 'REMOTE' && request != null ||
        origin == 'LOCAL' && data['monogramMirrorText'] == null) {
      return null;
    }
    final value = NativeMonogramPreferences.fromBridge({
      ...data,
      'monogramPreferencePair': pair,
      'monogramPreferenceEpoch': epoch,
      'monogramPreferenceText': data['monogramMirrorText'],
      'monogramPreferenceSourceTimestamp':
          data['monogramMirrorSourceTimestamp'],
      'monogramPreferenceObservedAt': data['monogramMirrorUpdatedAt'],
    });
    if (!value.received) return null;
    return NativeMonogramMirror._(
      pair,
      epoch,
      revision,
      true,
      value.text,
      origin as String,
      request as String?,
      value.sourceTimestamp,
      value.observedAt,
    );
  }

  bool canWrite(int now) =>
      now > 978307200000 &&
      (!known ||
          updatedAt! <= now && sourceTimestamp! <= now / 1000 - 978307200 + 5);
  bool sameBaseline(NativeMonogramMirror other) =>
      pair == other.pair && epoch == other.epoch && revision == other.revision;
}
