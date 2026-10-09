/// Observation of Android-owned hardware, separate from Watch telemetry.
class PhoneFindState {
  final bool available;
  final bool flashPermission;
  final bool active;
  final int? behavior;
  final bool localProbe;
  final bool? didPlay;
  final int? observedAt;
  final String? stopReason;
  const PhoneFindState({
    this.available = false,
    this.flashPermission = false,
    this.active = false,
    this.behavior,
    this.localProbe = false,
    this.didPlay,
    this.observedAt,
    this.stopReason,
  });
  factory PhoneFindState.fromBridge(Map<dynamic, dynamic> data) {
    if (data['bridgeAvailable'] != true) return const PhoneFindState();
    final available = data['phoneFindAvailable'] == true;
    final permission = data['phoneFlashPermission'] == true;
    final behavior = data['phoneFindBehavior'];
    final time = data['phoneFindObservedAt'];
    final played = data['phoneFindDidPlay'];
    final active = data['phoneFindActive'];
    if (!available ||
        data['phoneFindKnown'] != true ||
        behavior is! int ||
        behavior < 0 ||
        behavior > 4 ||
        time is! int ||
        time <= 0 ||
        played is! bool ||
        active is! bool ||
        (active && (!played || behavior == 4))) {
      return PhoneFindState(available: available, flashPermission: permission);
    }
    return PhoneFindState(
      available: available,
      flashPermission: permission,
      active: active,
      behavior: behavior,
      localProbe: data['phoneFindLocalProbe'] == true,
      didPlay: played,
      observedAt: time,
      stopReason: data['phoneFindStopReason'] is String
          ? data['phoneFindStopReason'] as String
          : null,
    );
  }
  bool activeObservationIsStale(DateTime now) {
    if (!active || observedAt == null) return false;
    final age = now.millisecondsSinceEpoch - observedAt!;
    return age >= 5500 || age < -5000;
  }
}
