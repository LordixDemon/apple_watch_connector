/// NanoTimeKit's edit metrics, verified in the iOS 26.6 model and serializer.
/// A detail controller marks its face once per editor lifetime, not per packet.
class NativeFaceEditMetadata {
  final DateTime Function() _now;
  bool _edited = false;
  NativeFaceEditMetadata({DateTime Function()? now})
    : _now = now ?? DateTime.now;

  void markEdited(Map<String, dynamic> configuration) {
    if (_edited) return;
    final metrics = _metrics(configuration);
    metrics['dateLastEdited'] = _seconds(_now());
    final counter = metrics['numberOfCompanionEdits'];
    if (!metrics.containsKey('numberOfCompanionEdits')) {
      metrics['numberOfCompanionEdits'] = 1;
    } else if (counter is num && counter.isFinite) {
      metrics['numberOfCompanionEdits'] = counter.toInt() + 1;
    } else if (counter is bool) {
      // NSNumber also represents JSON booleans in the native increment helper.
      metrics['numberOfCompanionEdits'] = (counter ? 1 : 0) + 1;
    }
    _setEditedState(metrics, 2);
    configuration['metrics'] = metrics;
    _edited = true;
  }

  /// _addFace copies the draft, assigns its origin and creation date, then
  /// appends/selects it. This must not modify a gallery template or its draft.
  static Map<String, dynamic> forGalleryAddition(
    Map<String, dynamic> configuration, {
    DateTime? now,
    bool externalAssets = false,
  }) {
    return _forAddition(configuration, externalAssets ? 8 : 6, now);
  }

  /// Greenfield imports preserve a recorded origin; only native origin 0
  /// (including an absent origin) becomes 12. Resource presence is unrelated.
  static Map<String, dynamic> forSharedAddition(
    Map<String, dynamic> configuration, {
    DateTime? now,
  }) {
    final metrics = _metrics(configuration);
    final origin = metrics.containsKey('origin')
        ? _integer(metrics['origin'])
        : 0;
    return _forAddition(configuration, origin == 0 ? 12 : null, now);
  }

  static Map<String, dynamic> _forAddition(
    Map<String, dynamic> configuration,
    int? origin,
    DateTime? now,
  ) {
    final result = Map<String, dynamic>.from(configuration);
    final metrics = _metrics(configuration);
    if (origin != null) metrics['origin'] = origin;
    if (_editedState(metrics) == 2) {
      final edited = metrics['dateLastEdited'];
      if (edited is num && edited.isFinite) {
        metrics['dateCreated'] = edited;
      } else {
        // A nil/non-date metric is not emitted as a JSON date by NTK.
        metrics.remove('dateCreated');
      }
    } else {
      metrics['dateCreated'] = _seconds(now ?? DateTime.now());
      _setEditedState(metrics, 1);
    }
    result['metrics'] = metrics;
    return result;
  }

  static Map<String, dynamic> _metrics(Map<String, dynamic> configuration) {
    if (!configuration.containsKey('metrics')) return {};
    final value = configuration['metrics'];
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Invalid native face metrics.');
    }
    return Map<String, dynamic>.from(value);
  }

  /// Rebase transport adaptation: a retained edit is not a new editor visit.
  /// Keep intervening peer metrics rather than replaying an older absolute
  /// counter/date or forcing a state that NTK's setter would refuse to change.
  static void reconcileRebase(
    Map<String, dynamic> original,
    Map<String, dynamic> desired,
    Map<String, dynamic> observed,
    Map<String, dynamic> result,
  ) {
    final before = original['metrics'], requested = desired['metrics'];
    final actual = observed['metrics'], rebased = result['metrics'];
    if (requested is! Map<String, dynamic> ||
        actual is! Map<String, dynamic> ||
        rebased is! Map<String, dynamic>) {
      return;
    }
    // If metrics were absent when the editor opened, the generic merge treats
    // their addition as a whole new dictionary. Retain peer-created keys too.
    final merged = <String, dynamic>{
      if (!original.containsKey('metrics')) ...actual,
      ...rebased,
    };
    result['metrics'] = merged;
    final old = before is Map<String, dynamic> ? before : <String, dynamic>{};
    const count = 'numberOfCompanionEdits';
    if (requested[count] != old[count] &&
        actual[count] != old[count] &&
        actual.containsKey(count)) {
      merged[count] = actual[count];
    }
    const date = 'dateLastEdited';
    final actualDate = actual[date], requestedDate = requested[date];
    if (requestedDate is num &&
        actualDate is num &&
        actualDate > requestedDate) {
      merged[date] = actualDate;
    }
    final state = _editedState(actual);
    if (requested['editedState'] != old['editedState'] &&
        state != 0 &&
        state != 1 &&
        actual.containsKey('editedState')) {
      merged['editedState'] = actual['editedState'];
    }
  }

  static double _seconds(DateTime value) =>
      value.microsecondsSinceEpoch / Duration.microsecondsPerSecond;

  static int? _editedState(Map<String, dynamic> metrics) {
    if (!metrics.containsKey('editedState')) return 0;
    return _integer(metrics['editedState']);
  }

  static int? _integer(Object? value) {
    if (value is bool) return value ? 1 : 0;
    if (value is num && value.isFinite) return value.toInt();
    // NSNumber is the native schema. Preserve an unsupported future value.
    return null;
  }

  static void _setEditedState(Map<String, dynamic> metrics, int value) {
    final state = _editedState(metrics);
    if (state == 0 || state == 1) metrics['editedState'] = value;
  }
}
