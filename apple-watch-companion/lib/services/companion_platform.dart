/// User-flow policy, independent of runtime transport capabilities.
/// Camera pairing remains a phone feature; desktop uses the watch's code.
final class CompanionPlatform {
  final bool opticalPairing, androidDiagnostics, codeOnlyPairing;
  const CompanionPlatform({
    required this.opticalPairing,
    required this.androidDiagnostics,
    required this.codeOnlyPairing,
  });
  static const android = CompanionPlatform(
    opticalPairing: true,
    androidDiagnostics: true,
    codeOnlyPairing: false,
  );
  static const desktop = CompanionPlatform(
    opticalPairing: false,
    androidDiagnostics: false,
    codeOnlyPairing: true,
  );
}
