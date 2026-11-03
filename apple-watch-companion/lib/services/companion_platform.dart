/// User-flow policy, independent of runtime transport capabilities.
/// Camera pairing remains a phone feature; desktop uses the watch's code.
final class CompanionPlatform {
  final bool opticalPairing, androidDiagnostics, codeOnlyPairing;
  const CompanionPlatform({
    required this.opticalPairing,
    required this.androidDiagnostics,
    required this.codeOnlyPairing,
  });
  // Camera pairing needs the iOS VisualPairing asset, which is not part of the
  // source tree. Builds without it pass --dart-define=OPTICAL_PAIRING=false and
  // pair with the watch's six-digit code instead.
  static const android = CompanionPlatform(
    opticalPairing: bool.fromEnvironment('OPTICAL_PAIRING', defaultValue: true),
    androidDiagnostics: true,
    codeOnlyPairing: false,
  );
  static const desktop = CompanionPlatform(
    opticalPairing: false,
    androidDiagnostics: false,
    codeOnlyPairing: true,
  );
}
