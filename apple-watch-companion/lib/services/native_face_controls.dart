/// Firmware profiles describe nested options and their native remembered values.
/// This model has no transport, device identity or widget dependencies.
final class NativeFaceControl {
  final List<String> path;
  final List<String>? selectorPath;
  final Map<String, String> titles;
  final Map<String, NativeFaceControlGroup> groups;
  final bool followsSelector;

  NativeFaceControl.fromJson(Map<String, dynamic> row)
    : path = _path(row['path']),
      selectorPath = row['selectorPath'] == null
          ? null
          : _path(row['selectorPath']),
      titles = Map.unmodifiable(Map<String, String>.from(row['titles'] as Map)),
      followsSelector = row['followsSelector'] == true,
      groups = Map.unmodifiable(
        (row['groups'] as Map<String, dynamic>).map(
          (key, value) => MapEntry(
            key,
            NativeFaceControlGroup.fromJson(value as Map<String, dynamic>),
          ),
        ),
      );

  NativeFaceControlGroup? group(Map<String, dynamic> configuration) =>
      groups[selectorPath == null ? '*' : read(configuration, selectorPath!)];

  String? value(Map<String, dynamic> configuration) {
    final raw = read(configuration, path);
    return raw is String ? raw : null;
  }

  String title(String locale) => localized(titles, locale) ?? path.last;

  bool select(Map<String, dynamic> configuration, String token) {
    final active = group(configuration);
    if (active == null || !active.labels.containsKey(token)) return false;
    write(configuration, path, token);
    if (active.rememberPath != null) {
      write(configuration, active.rememberPath!, token);
    }
    return true;
  }

  void selectorChanged(
    Map<String, dynamic> configuration,
    List<String> changed,
  ) {
    if (!followsSelector ||
        selectorPath == null ||
        changed.length != selectorPath!.length) {
      return;
    }
    for (var i = 0; i < changed.length; i++) {
      if (changed[i] != selectorPath![i]) return;
    }
    final active = group(configuration);
    if (active == null || active.labels.isEmpty) return;
    final remembered = active.rememberPath == null
        ? null
        : read(configuration, active.rememberPath!);
    final token = remembered is String && active.labels.containsKey(remembered)
        ? remembered
        : active.labels.keys.first;
    write(configuration, path, token);
  }

  static Object? read(Map<String, dynamic> configuration, List<String> path) {
    Object? value = configuration;
    for (final key in path) {
      if (value is! Map) return null;
      value = value[key];
    }
    return value;
  }

  static void write(
    Map<String, dynamic> configuration,
    List<String> path,
    String value,
  ) {
    var parent = configuration;
    for (final key in path.take(path.length - 1)) {
      final existing = parent[key];
      if (existing != null && existing is! Map<String, dynamic>) {
        throw const FormatException(
          'Native option parent is not a dictionary.',
        );
      }
      parent =
          parent.putIfAbsent(key, () => <String, dynamic>{})
              as Map<String, dynamic>;
    }
    parent[path.last] = value;
  }

  static String? localized(Map<String, String> labels, String locale) =>
      labels[locale] ?? labels[locale.split('_').first] ?? labels['en'];

  static List<String> _path(Object? raw) {
    final path = List<String>.from(raw as List);
    if (path.length < 2 ||
        path.length > 8 ||
        !const {'customization', 'customData'}.contains(path.first) ||
        path.any((v) => v.isEmpty || v.length > 128)) {
      throw const FormatException('Invalid native option path.');
    }
    return List.unmodifiable(path);
  }
}

final class NativeFaceControlGroup {
  final Map<String, Map<String, String>> labels;
  final List<String>? rememberPath;

  NativeFaceControlGroup.fromJson(Map<String, dynamic> row)
    : rememberPath = row['rememberPath'] == null
          ? null
          : NativeFaceControl._path(row['rememberPath']),
      labels = Map.unmodifiable({
        for (final value in row['values'] as List)
          value['value'] as String: Map<String, String>.unmodifiable(
            Map<String, String>.from(value['labels'] as Map),
          ),
      });

  String title(String token, String locale) =>
      NativeFaceControl.localized(labels[token] ?? const {}, locale) ?? token;
}
