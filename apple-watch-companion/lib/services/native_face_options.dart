import '../l10n/strings.dart';
import '../models/native_face_collection.dart';
import 'native_face_gallery.dart';
import 'native_watch_face_archive.dart';

/// Bundle schemas are independent of Watch addresses, device models and UI widgets.
/// 23S303 NTKLeghornFaceBundle: style 0x2113c3768, position 0x2113c3570,
/// night 0x2113be980. Colors come from the bundle's Leghorn.color.plist.
abstract final class NativeFaceOptions {
  /// watchOS 26.2 en.lproj/Localizable.strings and FaceColors.strings.
  static String? title(String bundle) => switch (bundle) {
    'com.apple.NTKLeghornFaceBundle' => Strings.current.nativeFaceWaypoint,
    _ => NativeFaceGallery.profile(
      'bundle:$bundle',
    )?.title(Strings.current.localeName),
  };

  static String? family(NativeWatchFace face) => face.bundle.isNotEmpty
      ? 'bundle:${face.bundle}'
      : NativeWatchFaceArchive.familyIdentity(face.configuration ?? {});
  static NativeFaceTemplate? profile(NativeWatchFace face) =>
      NativeFaceGallery.profile(family(face));
  static List<String> slotNames(NativeWatchFace face) =>
      templateSlots(profile(face));

  static List<String> templateSlots(NativeFaceTemplate? template) =>
      List.unmodifiable(
        (template?.complicationLayout?.order ??
                template?.slotFamilies.keys ??
                const <String>[])
            .where(
              (slot) =>
                  template?.complicationLayout?.excluded.contains(slot) != true,
            ),
      );

  static bool slotAvailable(
    String slot,
    Object? customization, {
    NativeWatchFace? face,
    NativeFaceTemplate? template,
  }) =>
      (template ?? (face == null ? null : profile(face)))?.complicationLayout
          ?.unavailable(customization)
          ?.contains(slot) !=
      true;

  static String slotTitle(
    String slot, {
    NativeWatchFace? face,
    NativeFaceTemplate? template,
  }) =>
      (template ?? (face == null ? null : profile(face)))?.complicationLayout
          ?.title(slot, Strings.current.localeName) ??
      switch (slot) {
        'top' => Strings.current.nativeFaceTop,
        'bottom' => Strings.current.nativeFaceBottom,
        'top left' => Strings.current.nativeFaceTopLeft,
        'top right' => Strings.current.nativeFaceTopRight,
        'bottom left' => Strings.current.nativeFaceBottomLeft,
        'bottom right' => Strings.current.nativeFaceBottomRight,
        'center' => Strings.current.nativeFaceCenter,
        _ => slot,
      };

  /// NTKLeghornFace._complicationSlotDescriptors, 23S303, 0x2113c4928–0x2113c49b8.
  /// The center uses a multi-family descriptor and needs separate evidence.
  static List<int>? slotFamilies(
    String bundle,
    String slot, {
    NativeWatchFace? face,
  }) =>
      bundle == 'com.apple.NTKLeghornFaceBundle' &&
          const {
            'top left',
            'top right',
            'bottom left',
            'bottom right',
          }.contains(slot)
      ? const [8]
      : (face == null
                ? NativeFaceGallery.profile('bundle:$bundle')
                : profile(face))
            ?.slotFamilies[slot];

  static const _schemas = {
    'com.apple.NTKLeghornFaceBundle': {
      'style': ['digital', 'analog'],
      'position': ['none', 'left', 'right'],
      'night': ['auto', 'off', 'on'],
      'color': [
        'leghorn.hero-1',
        'leghorn.hero-2',
        'leghorn.hero-3',
        'leghorn.hero-4-duo',
        'leghorn.hero-5-duo',
        'leghorn.hero-6-duo',
        'leghorn.seasonal-2025-1',
        'leghorn.seasonal-2025-2',
        'leghorn.seasonal-2025-3',
      ],
    },
  };

  static List<String> choices(
    NativeWatchFace face,
    String field,
    NativeFaceCollection collection,
  ) {
    final values = <String>{
      ...?_schemas[face.bundle]?[field],
      ...?profile(face)?.options[field],
    };
    // Current and previously observed native values remain available for every family.
    for (final other in collection.faces.where(
      (v) => family(v) == family(face),
    )) {
      final value = other.configuration?['customization'];
      if (value is Map && value[field] is String) {
        values.add(value[field] as String);
      }
    }
    return List.unmodifiable(values);
  }

  static String fieldTitle(String field, {NativeWatchFace? face}) {
    final native = face == null
        ? null
        : profile(face)?.fieldTitle(field, Strings.current.localeName);
    if (native != null) return native;
    return switch (field) {
      'style' => Strings.current.nativeFaceTimeStyle,
      'position' => Strings.current.nativeFaceDialStyle,
      'night' => Strings.current.nativeFaceNightMode,
      'color' => Strings.current.nativeFaceColor,
      _ => field,
    };
  }

  static String valueTitle(
    String field,
    Object? value, {
    NativeWatchFace? face,
  }) {
    final native = face == null || value is! String
        ? null
        : profile(face)?.valueTitle(field, value, Strings.current.localeName);
    if (native != null) return native;
    if (field == 'color' && value is String && value.startsWith('leghorn.')) {
      final nativeName = switch (value) {
        'leghorn.hero-1' => Strings.current.nativeFacePink,
        'leghorn.hero-2' => Strings.current.nativeFaceNeonGreen,
        'leghorn.hero-3' => Strings.current.nativeFaceOrange,
        'leghorn.hero-4-duo' => Strings.current.nativeFacePinkSand,
        'leghorn.hero-5-duo' => Strings.current.nativeFaceNeonGreenCloud,
        'leghorn.hero-6-duo' => Strings.current.nativeFaceOrangeLightSage,
        'leghorn.seasonal-2025-1' => Strings.current.nativeFaceLightBlue,
        'leghorn.seasonal-2025-2' => Strings.current.nativeFaceBrightBlue,
        'leghorn.seasonal-2025-3' => Strings.current.nativeFaceTerraCotta,
        _ => null,
      };
      if (nativeName != null) return nativeName;
      final token = value.substring('leghorn.'.length);
      if (token.startsWith('hero-')) {
        return Strings.current.nativeFacePalette(
          token.substring(5).replaceAll('-duo', ' Duo'),
        );
      }
      if (token.startsWith('seasonal-2025-')) {
        return Strings.current.nativeFaceSeasonalPalette(token.substring(14));
      }
    }
    return switch (value) {
      'digital' => Strings.current.nativeFaceDigital,
      'analog' => Strings.current.nativeFaceAnalog,
      'auto' => Strings.current.nativeFaceAutomatic,
      'on' => Strings.current.on,
      'off' => Strings.current.off,
      'none' => Strings.current.nativeFaceNoComplication,
      'left' => Strings.current.nativeFaceLeft,
      'right' => Strings.current.nativeFaceRight,
      _ => value.toString(),
    };
  }
}
