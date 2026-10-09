import 'dart:convert';
import '../models/native_face_collection.dart';
import 'native_face_options.dart';
import 'native_face_gallery.dart';
import 'native_complication_presentation.dart';

typedef NativeComplicationChoice = ({String title, Map<String, dynamic> value});

/// Firmware slot descriptors rank alternative ClockKit families. If that slot
/// schema is unknown, preserve the conservative incumbent-family fallback.
abstract final class NativeComplicationChoices {
  /// Legacy ClockKit JSON need not contain a numeric type or WidgetKit
  /// descriptor. Reuse exact configurations proven in this native family/slot,
  /// including the original value after the user clears an unsaved draft.
  static List<NativeComplicationChoice> forFaceSlot(
    NativeWatchFace face,
    String slot,
    NativeFaceCollection collection, {
    Object? template,
    String Function(Map<String, dynamic>)? titleForObserved,
  }) {
    final current = collection.face(face.id);
    if (current == null) return const [];
    final original = current.configuration?['complications'];
    final incumbent = template ?? (original is Map ? original[slot] : null);
    return _choices(
      NativeFaceOptions.family(face),
      slot,
      collection,
      NativeFaceOptions.slotFamilies(face.bundle, slot, face: face),
      incumbent,
      titleForObserved,
    );
  }

  /// Drafts have no Watch UUID. Their compatibility comes from a native template
  /// slot, while every provider and opaque descriptor still comes from the Watch.
  static List<NativeComplicationChoice> forTemplateSlot(
    NativeFaceTemplate profile,
    String slot,
    NativeFaceCollection collection, {
    Object? template,
  }) {
    final families = profile.slotFamilies[slot];
    if (families == null || families.isEmpty) return const [];
    return _choices(profile.family, slot, collection, families, template, null);
  }

  static List<NativeComplicationChoice> _choices(
    String? family,
    String slot,
    NativeFaceCollection collection,
    List<int>? slotFamilies,
    Object? incumbent,
    String Function(Map<String, dynamic>)? titleForObserved,
  ) {
    final catalog = collection.complicationCatalog;
    final presentation = NativeComplicationPresentation(catalog);
    final result = <String, NativeComplicationChoice>{};
    final groups = <String, List<Map<String, dynamic>>>{};
    void add(Map<String, dynamic> value, String title) {
      // Equivalent catalog settings should mark the received incumbent, not
      // replace its archive with a new donation UUID just by opening the picker.
      final source =
          incumbent is Map<String, dynamic> &&
              presentation.sameConfiguration(value, incumbent)
          ? incumbent
          : value;
      final key = _identity(source);
      final group = groups.putIfAbsent(_pickerGroup(source), () => []);
      if (result.containsKey(key) ||
          group.any(
            (choice) => presentation.sameConfiguration(choice, source),
          )) {
        return;
      }
      final copy = jsonDecode(jsonEncode(source)) as Map<String, dynamic>;
      result[key] = (title: title, value: copy);
      group.add(copy);
    }

    for (final choice in forSlot(
      incumbent,
      catalog,
      requiredFamilies: slotFamilies,
    )) {
      add(choice.value, choice.title);
    }
    // A legacy inventory identifies an extension, not its containing app.
    // Learn that relationship only from configurations received from this Watch.
    // Firmware-proven slot families permit using an observed provider from a
    // different face; without them keep the same-family/same-slot fallback.
    for (final observed in collection.faces) {
      final complications = observed.configuration?['complications'];
      if (complications is! Map) continue;
      for (final entry in complications.entries) {
        final value = entry.value;
        if (slotFamilies != null &&
            value is Map<String, dynamic> &&
            value['type'] == 56 &&
            value['descriptor'] is Map) {
          final sourceFamilies = NativeFaceOptions.slotFamilies(
            observed.bundle,
            entry.key.toString(),
            face: observed,
          );
          // A single-family native source slot proves where this exact received
          // widget was used. Ranked alternatives alone do not prove which family
          // its provider supports, so they cannot authorize cross-slot reuse.
          if (sourceFamilies?.length == 1 &&
              slotFamilies.contains(sourceFamilies!.single)) {
            add(
              value,
              presentation.title(
                value,
                faceId: observed.id,
                slot: entry.key.toString(),
              ),
            );
          }
        }
        if (slotFamilies == null &&
            (NativeFaceOptions.family(observed) != family ||
                entry.key != slot)) {
          continue;
        }
        for (final choice in forBundleSlot(
          entry.value,
          catalog,
          requiredFamilies: slotFamilies,
        )) {
          add(choice.value, choice.title);
        }
      }
    }
    if (family != null) {
      for (final observed in collection.faces) {
        if (NativeFaceOptions.family(observed) != family) continue;
        final complications = observed.configuration?['complications'];
        final value = complications is Map ? complications[slot] : null;
        if (value is! Map<String, dynamic> || value.isEmpty) continue;
        add(
          value,
          titleForObserved?.call(value) ??
              presentation.title(value, faceId: observed.id, slot: slot),
        );
      }
    }
    final choices = result.values.toList()
      ..sort((a, b) => a.title.compareTo(b.title));
    return List.unmodifiable(choices);
  }

  static String _identity(Map<String, dynamic> value) {
    Object? ordered(Object? item) {
      if (item is Map<String, dynamic>) {
        return <String, dynamic>{
          for (final key in item.keys.toList()..sort()) key: ordered(item[key]),
        };
      }
      if (item is List) return item.map(ordered).toList();
      return item;
    }

    return jsonEncode(ordered(value));
  }

  // Compare re-archived intents only within the exact same provider/settings
  // group. Large inventories should not parse unrelated provider archives.
  static String _pickerGroup(Map<String, dynamic> value) {
    final descriptor = value['descriptor'];
    if (descriptor is! Map<String, dynamic> || descriptor['intent'] is! String) {
      return _identity(value);
    }
    return _identity({
      ...value,
      'descriptor': Map<String, dynamic>.from(descriptor)..remove('intent'),
    });
  }

  /// NTKBundleComplication's JSON shape is different from WidgetKit's.
  /// Keep the actual provider/app identity and replace only the received
  /// CLKComplicationDescriptor. Its userInfo is provider-owned opaque data.
  static List<NativeComplicationChoice> forBundleSlot(
    Object? template,
    Map<String, dynamic> catalog, {
    List<int>? requiredFamilies,
  }) {
    if (template is! Map<String, dynamic> ||
        template['bundle identifier'] is! String ||
        template['bundle app identifier'] is! String) {
      return const [];
    }
    final extension = template['bundle identifier'] as String;
    final app = template['bundle app identifier'] as String;
    if (extension.isEmpty || app.isEmpty) return const [];
    final group = catalog['BundleComplications:$extension'];
    if (group is! Map) return const [];
    final original = template['bundle app complication descriptor'];
    final families =
        requiredFamilies ??
        (original is Map ? original['supportedFamilies'] : null);
    if (families is! List ||
        families.isEmpty ||
        families.any((v) => v is! int)) {
      return const [];
    }
    final result = <NativeComplicationChoice>[];
    for (final row in group.values.whereType<Map>()) {
      final descriptor = row['bundleDescriptor'];
      final supported = row['families'];
      if (descriptor is! Map<String, dynamic> ||
          descriptor['identifier'] is! String ||
          (descriptor['identifier'] as String).isEmpty ||
          supported is! List ||
          supported.any((v) => v is! int)) {
        continue;
      }
      final compatible = requiredFamilies != null
          ? families.any(supported.contains)
          : families.every(supported.contains);
      if (!compatible) continue;
      final value = jsonDecode(jsonEncode(template)) as Map<String, dynamic>;
      value['bundle app complication descriptor'] = jsonDecode(
        jsonEncode(descriptor),
      );
      result.add((
        title:
            (row['name'] ??
                    descriptor['displayName'] ??
                    descriptor['identifier'])
                .toString(),
        value: value,
      ));
    }
    result.sort((a, b) => a.title.compareTo(b.title));
    return List.unmodifiable(result);
  }

  static List<NativeComplicationChoice> forSlot(
    Object? template,
    Map<String, dynamic> catalog, {
    List<int>? requiredFamilies,
  }) {
    final bundleChoices = forBundleSlot(
      template,
      catalog,
      requiredFamilies: requiredFamilies,
    );
    if (template is! Map<String, dynamic> || template['type'] != 56) {
      if (requiredFamilies == null || requiredFamilies.isEmpty) {
        return bundleChoices;
      }
      // NTKWidgetComplication's native JSON shape, also observed on the Watch.
      // Only received descriptors and firmware-proven slot families are used.
      template = <String, dynamic>{
        'type': 56,
        'descriptor': <String, dynamic>{},
      };
    }
    if (template['descriptor'] is! Map) return bundleChoices;
    final original = template['descriptor'] as Map;
    final rows = <Map<String, dynamic>>[];
    for (final group in catalog.entries) {
      if (!group.key.startsWith('WidgetComplications:') ||
          group.value is! Map) {
        continue;
      }
      for (final value in (group.value as Map).values) {
        if (value is Map<String, dynamic> &&
            value['descriptor'] is Map<String, dynamic> &&
            value['families'] is List) {
          rows.add(value);
        }
      }
    }
    final incumbent = rows.where((row) {
      final descriptor = row['descriptor'] as Map;
      return descriptor['kind'] == original['kind'] &&
          descriptor['containerBundleIdentifier'] ==
              original['containerBundleIdentifier'] &&
          descriptor['extensionBundleIdentifier'] ==
              original['extensionBundleIdentifier'];
    }).firstOrNull;
    final families = requiredFamilies ?? incumbent?['families'];
    if (families is! List ||
        families.isEmpty ||
        families.any((v) => v is! int)) {
      return bundleChoices;
    }
    final result = <NativeComplicationChoice>[...bundleChoices];
    for (final row in rows) {
      final supported = row['families'] as List;
      final compatible = requiredFamilies != null
          ? families.any(supported.contains)
          : families.every(supported.contains);
      if (!compatible) continue;
      final descriptor = row['descriptor'] as Map<String, dynamic>;
      final app = descriptor['containerBundleIdentifier'];
      if (app is! String ||
          descriptor['kind'] is! String ||
          descriptor['extensionBundleIdentifier'] is! String) {
        continue;
      }
      final value = jsonDecode(jsonEncode(template)) as Map<String, dynamic>;
      value['app'] = app;
      value['extension'] = app;
      value['descriptor'] = jsonDecode(jsonEncode(descriptor));
      result.add((
        title: (row['name'] ?? descriptor['kind']).toString(),
        value: value,
      ));
    }
    result.sort((a, b) => a.title.compareTo(b.title));
    return List.unmodifiable(result);
  }
}
