import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/services.dart';
import 'native_watch_face_archive.dart';
import 'native_face_controls.dart';
import 'native_face_edit_section.dart';
import 'native_face_pigment.dart';
import 'native_pigment_layout.dart';
import 'native_pigment_check_artwork.dart';
import 'native_configured_previews.dart';
import 'native_component_previews.dart';
import 'native_complication_presentation.dart';
import 'native_complication_layout.dart';

/// Firmware-derived profiles are loaded lazily once and indexed by native family.
/// They contain no Watch address, simulator intent, photo path or pairing state.
abstract final class NativeFaceGallery {
  static Future<List<NativeFaceTemplate>>? _loading;
  static final Map<String, NativeFaceTemplate> _profiles = {};
  static Future<List<NativeFaceTemplate>> load() => _loading ??= _load();
  static NativeFaceTemplate? profile(String? family) => _profiles[family];

  static Future<List<NativeFaceTemplate>> _load() async {
    final raw = await rootBundle.loadString(
      'assets/native_faces/watchos_26_2.json',
    );
    if (raw.length > 1024 * 1024) {
      throw const FormatException('Native gallery size exceeded.');
    }
    final data = jsonDecode(raw) as Map<String, dynamic>;
    final photos =
        jsonDecode(
              await rootBundle.loadString(
                'assets/native_faces/photos_26_2.json',
              ),
            )
            as Map<String, dynamic>;
    final rows = [...data['templates'] as List, ...photos['templates'] as List];
    final layoutText = await rootBundle.loadString(
      'assets/native_faces/complication_layouts_26_2.json',
    );
    if (layoutText.length > 512 * 1024) {
      throw const FormatException(
        'Native complication metadata size exceeded.',
      );
    }
    final layoutData = jsonDecode(layoutText);
    if (layoutData is! Map ||
        layoutData['version'] != 1 ||
        layoutData['families'] is! Map ||
        layoutData['families'].length > 256) {
      throw const FormatException('Invalid native complication metadata.');
    }
    final sectionText = await rootBundle.loadString(
      'assets/native_faces/edit_sections_26_2.json',
    );
    if (sectionText.length > 128 * 1024) {
      throw const FormatException('Native editor metadata size exceeded.');
    }
    final sectionData = jsonDecode(sectionText);
    if (sectionData is! Map ||
        sectionData['version'] != 1 ||
        sectionData['families'] is! Map ||
        sectionData['families'].length > 256) {
      throw const FormatException('Invalid native editor metadata.');
    }
    if (rows.length > 256) {
      throw const FormatException('Native gallery count exceeded.');
    }
    final pigmentText = await rootBundle.loadString(
      'assets/native_faces/pigments_26_2.json',
    );
    if (pigmentText.length > 1024 * 1024) {
      throw const FormatException('Native pigment metadata size exceeded.');
    }
    final pigmentData = jsonDecode(pigmentText);
    if (pigmentData is! Map ||
        pigmentData['version'] != 1 ||
        pigmentData['colorSpace'] != 'extendedSRGB' ||
        pigmentData['families'] is! Map ||
        pigmentData['families'].length > 256 ||
        pigmentData['pigments'] is! List ||
        pigmentData['pigments'].length > 2048) {
      throw const FormatException('Invalid native pigment metadata.');
    }
    final pigments = List<NativeFacePigment>.unmodifiable([
      for (final row in pigmentData['pigments'])
        NativeFacePigment.fromJson(Map<String, dynamic>.from(row as Map)),
    ]);
    final collectionText = await rootBundle.loadString(
      'assets/native_faces/pigment_collections_26_2.json',
    );
    if (collectionText.length > 1024 * 1024) {
      throw const FormatException('Native color collection size exceeded.');
    }
    final collectionData = jsonDecode(collectionText);
    if (collectionData is! Map ||
        collectionData['version'] != 1 ||
        collectionData['families'] is! Map ||
        collectionData['families'].length > 256 ||
        collectionData['colors'] is! List ||
        collectionData['colors'].length > 2048 ||
        !collectionData['families'].keys.toSet().containsAll(
          pigmentData['families'].keys,
        ) ||
        collectionData['families'].length != pigmentData['families'].length) {
      throw const FormatException('Invalid native color collection metadata.');
    }
    final colors = List<NativePigmentChoice>.unmodifiable([
      for (final row in collectionData['colors'])
        NativePigmentChoice.fromJson(Map<String, dynamic>.from(row as Map)),
    ]);
    final pigmentLayout = NativePigmentLayout.fromJson(
      pigmentData['swatchLayout'],
    );
    final pigmentCheck = pigmentData['checkArtwork'] == null
        ? null
        : NativePigmentCheckArtwork.fromJson(pigmentData['checkArtwork']);
    final profiles = <String, NativeFaceTemplate>{};
    for (final row in rows) {
      final face = NativeFaceTemplate.fromJson(
        {
          ...row as Map<String, dynamic>,
          'editSections':
              sectionData['families'][row['family']]?['sections'] ?? const [],
          'editOrder':
              sectionData['families'][row['family']]?['order'] ?? const [],
          'pigmentSections': pigmentData['families'][row['family']] ?? const [],
          'pigmentCollections': collectionData['families'][row['family']],
          'complicationLayout': layoutData['families'][row['family']],
        },
        pigments: pigments,
        colors: colors,
        pigmentLayout: pigmentLayout,
        pigmentCheck: pigmentCheck,
      );
      if (profiles.containsKey(face.family)) {
        throw const FormatException('Duplicate native family.');
      }
      profiles[face.family] = face;
    }
    _profiles.addAll(profiles);
    try {
      await NativeComplicationPresentation.load();
    } on Exception {
      // Optional display labels cannot disable native Watch operations.
    }
    try {
      await NativeConfiguredPreviews.load();
    } on Exception {
      // Styling and real Watch operations remain available without preview assets.
    }
    try {
      await NativeComponentPreviews.load();
    } on Exception {
      // Component previews are optional; unknown styles retain honest fallback.
    }
    return List.unmodifiable(profiles.values);
  }
}

final class NativeFaceTemplate {
  final String family, configurationJson;
  final Map<String, String> _titles;
  final Map<String, Map<String, String>> _fieldTitles;
  final Map<String, List<String>> options;
  final Map<String, Map<String, Map<String, String>>> _labels;
  final Map<String, List<int>> slotFamilies;
  final NativeComplicationLayout? complicationLayout;
  final bool requiresPhotos;
  final List<NativeFaceControl> controls;
  final List<NativeFaceEditSection> editSections;
  final List<NativeFacePigmentSection> pigmentSections;
  final List<String> _editOrder;
  const NativeFaceTemplate._(
    this.family,
    this.configurationJson,
    this._titles,
    this._fieldTitles,
    this.options,
    this._labels,
    this.slotFamilies,
    this.complicationLayout,
    this.requiresPhotos,
    this.controls,
    this.editSections,
    this.pigmentSections,
    this._editOrder,
  );

  /// A native gallery appearance preset retains this family's editing metadata.
  /// It is a new immutable prototype, never an edit to a loaded default profile.
  NativeFaceTemplate withConfiguration(String json) {
    final config = NativeWatchFaceArchive.decodeConfiguration(
      Uint8List.fromList(utf8.encode(json)),
    );
    if (requiresPhotos ||
        NativeWatchFaceArchive.familyIdentity(config) != family ||
        config.containsKey('resource directory') ||
        config.containsKey('customData')) {
      throw const FormatException(
        'Gallery preset does not match its native family.',
      );
    }
    return NativeFaceTemplate._(
      family,
      jsonEncode(config),
      _titles,
      _fieldTitles,
      options,
      _labels,
      slotFamilies,
      complicationLayout,
      requiresPhotos,
      controls,
      editSections,
      pigmentSections,
      _editOrder,
    );
  }

  factory NativeFaceTemplate.fromJson(
    Map<String, dynamic> row, {
    List<NativeFacePigment> pigments = const [],
    List<NativePigmentChoice> colors = const [],
    NativePigmentLayout pigmentLayout = NativePigmentLayout.legacy,
    NativePigmentCheckArtwork? pigmentCheck,
  }) {
    final json = jsonEncode(row['configuration']);
    final config = NativeWatchFaceArchive.decodeConfiguration(
      Uint8List.fromList(utf8.encode(json)),
    );
    final family = NativeWatchFaceArchive.familyIdentity(config)!;
    final photos =
        row['resourceBuilder'] == 'parmesan-v2' &&
        family == 'bundle:com.apple.NTKParmesanFaceBundle';
    if (row['family'] != family ||
        (!photos && config.containsKey('resource directory')) ||
        (!photos && config.containsKey('customData')) ||
        config.containsKey('complications')) {
      throw const FormatException(
        'Native template contains external or device-specific data.',
      );
    }
    final options = <String, List<String>>{},
        labels = <String, Map<String, Map<String, String>>>{};
    for (final entry in (row['options'] as Map<String, dynamic>).entries) {
      final values = <String>[], names = <String, Map<String, String>>{};
      for (final value in entry.value as List) {
        final token = value['value'] as String;
        if (values.contains(token)) {
          throw const FormatException('Duplicate native option token.');
        }
        values.add(token);
        names[token] = Map.unmodifiable(
          value['labels'] is Map
              ? Map<String, String>.from(value['labels'] as Map)
              : {'en': value['label'] as String},
        );
      }
      options[entry.key] = List.unmodifiable(values);
      labels[entry.key] = Map.unmodifiable(names);
    }
    final families = <String, List<int>>{};
    for (final entry in (row['slotFamilies'] as Map<String, dynamic>).entries) {
      final values = List<int>.from(entry.value as List);
      if (values.any((v) => v < 0 || v > 255)) {
        throw const FormatException('Invalid complication family.');
      }
      families[entry.key] = List.unmodifiable(values);
    }
    final sectionRows = row['editSections'] ?? const [];
    if (sectionRows is! List || sectionRows.length > 64) {
      throw const FormatException('Native editor section count exceeded.');
    }
    final sections = <NativeFaceEditSection>[];
    final order = row['editOrder'] ?? const [];
    if (order is! List ||
        order.length > 64 ||
        order.any((field) => field is! String || !options.containsKey(field)) ||
        order.toSet().length != order.length) {
      throw const FormatException('Invalid native editor order.');
    }
    for (final section in sectionRows) {
      if (section is! Map<String, dynamic>) {
        throw const FormatException('Invalid native editor section.');
      }
      final value = NativeFaceEditSection.fromJson(section, options);
      if (sections.any((previous) => previous.field == value.field)) {
        throw const FormatException('Duplicate native editor field.');
      }
      sections.add(value);
    }
    final pigmentRows = row['pigmentSections'] ?? const [];
    if (pigmentRows is! List || pigmentRows.length > 64) {
      throw const FormatException('Native pigment section count exceeded.');
    }
    final pigmentSections = <NativeFacePigmentSection>[];
    for (final section in pigmentRows) {
      if (section is! Map<String, dynamic>) {
        throw const FormatException('Invalid native pigment section.');
      }
      final value = NativeFacePigmentSection.fromJson(
        section,
        options,
        pigments,
        collections: row['pigmentCollections'] == null
            ? null
            : Map<String, dynamic>.from(row['pigmentCollections'] as Map),
        colors: colors,
        layout: pigmentLayout,
        checkArtwork: pigmentCheck,
      );
      if (pigmentSections.any((previous) => previous.field == value.field)) {
        throw const FormatException('Duplicate native pigment field.');
      }
      pigmentSections.add(value);
    }
    return NativeFaceTemplate._(
      family,
      json,
      Map.unmodifiable(Map<String, String>.from(row['titles'] as Map)),
      Map<String, Map<String, String>>.unmodifiable(
        (row['fieldTitles'] as Map<String, dynamic>).map(
          (k, v) => MapEntry(
            k,
            Map<String, String>.unmodifiable(
              Map<String, String>.from(v as Map),
            ),
          ),
        ),
      ),
      Map.unmodifiable(options),
      Map.unmodifiable(labels),
      Map.unmodifiable(families),
      row['complicationLayout'] == null
          ? null
          : NativeComplicationLayout.fromJson(
              Map<String, dynamic>.from(row['complicationLayout'] as Map),
              families,
              options,
            ),
      photos,
      List.unmodifiable([
        for (final control in row['controls'] as List? ?? const [])
          NativeFaceControl.fromJson(control as Map<String, dynamic>),
      ]),
      List.unmodifiable(sections),
      List.unmodifiable(pigmentSections),
      List<String>.unmodifiable(order),
    );
  }

  NativeFaceEditSection? editSection(String field) {
    for (final section in editSections) {
      if (section.field == field) return section;
    }
    return null;
  }

  NativeFacePigmentSection? pigmentSection(String field) {
    for (final section in pigmentSections) {
      if (section.field == field) return section;
    }
    return null;
  }

  Iterable<String> get editableFields sync* {
    yield* _editOrder;
    yield* options.keys.where((field) => !_editOrder.contains(field));
  }

  String title(String locale) =>
      _titles[locale] ??
      _titles[locale.split('_').first] ??
      _titles['en'] ??
      family;
  String? fieldTitle(String field, String locale) =>
      _fieldTitles[locale]?[field] ??
      _fieldTitles[locale.split('_').first]?[field] ??
      _fieldTitles['en']?[field];
  String? valueTitle(String field, String value, String locale) {
    final labels =
        _labels[field]?[value] ??
        _labels[field]?[pigmentSection(field)?.optionFor(value)];
    return labels?[locale] ?? labels?[locale.split('_').first] ?? labels?['en'];
  }

  void selectOption(
    Map<String, dynamic> configuration,
    String field,
    String value,
  ) {
    if (options[field]?.contains(value) != true) {
      throw const FormatException('Unknown native option.');
    }
    _writeOption(configuration, field, value);
  }

  /// Only native pigment options with verified fraction schemas may add tokens.
  void selectPigment(
    Map<String, dynamic> configuration,
    String field,
    String value,
  ) {
    if (pigmentSection(field)?.accepts(value) != true) {
      throw const FormatException('Unknown native pigment.');
    }
    _writeOption(configuration, field, value);
  }

  void _writeOption(
    Map<String, dynamic> configuration,
    String field,
    String value,
  ) {
    NativeFaceControl.write(configuration, ['customization', field], value);
    for (final control in controls) {
      control.selectorChanged(configuration, ['customization', field]);
    }
  }

  String get previewAsset =>
      'assets/native_faces/previews/${family.replaceAll(RegExp(r'[^A-Za-z0-9]'), '_')}.png';

  Uint8List package({Map<String, dynamic>? configuration}) {
    if (requiresPhotos) throw const FormatException('Choose photos first.');
    final json = configuration == null
        ? configurationJson
        : jsonEncode(configuration);
    final value = NativeWatchFaceArchive.decodeConfiguration(
      Uint8List.fromList(utf8.encode(json)),
    );
    if (NativeWatchFaceArchive.familyIdentity(value) != family ||
        value.containsKey('resource directory') ||
        value.containsKey('customData')) {
      throw const FormatException(
        'Draft does not match a resource-free template.',
      );
    }
    return NativeWatchFaceArchive.fromConfiguration(json);
  }
}
