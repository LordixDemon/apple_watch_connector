/// Native generic editor collection. Pigment/dynamic sections use other models.
final class NativeFaceEditSection {
  final String field;
  final int mode, collectionType;
  final List<String> values;
  const NativeFaceEditSection._(
    this.field,
    this.mode,
    this.collectionType,
    this.values,
  );

  factory NativeFaceEditSection.fromJson(
    Map<String, dynamic> json,
    Map<String, List<String>> options,
  ) {
    final field = json['field'], mode = json['mode'];
    final type = json['collectionType'], raw = json['values'];
    if (field is! String ||
        mode is! int ||
        mode < 0 ||
        mode > 255 ||
        type is! int ||
        !const [0, 2, 3].contains(type) ||
        raw is! List ||
        raw.isEmpty ||
        raw.length > 512 ||
        raw.any(
          (value) =>
              value is! String || options[field]?.contains(value) != true,
        ) ||
        raw.toSet().length != raw.length) {
      throw const FormatException('Invalid native editor section.');
    }
    return NativeFaceEditSection._(
      field,
      mode,
      type,
      List<String>.unmodifiable(raw),
    );
  }

  // Original iPhone generic factory: vertical for type 2, horizontal otherwise.
  bool get vertical => collectionType == 2;
}
