/// A write baseline may be a live report or the HAL's durable paired NPS value.
/// It never by itself establishes what is currently applied on the Watch.
abstract interface class NativePigmentBaseline {
  bool get connected;
  bool get known;
  String? get pair;
  String? get epoch;
  List<String>? get names;
  double? get sourceTimestamp;
  int? get observedAt;
  bool canWrite(int now);
  bool sameBaseline(NativePigmentBaseline other);
}

bool samePigmentBaseline(NativePigmentBaseline a, NativePigmentBaseline b) {
  return a.sameBaseline(b);
}
