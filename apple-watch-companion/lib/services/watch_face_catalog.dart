import '../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import '../models/watch_face.dart';
import '../theme/ios_colors.dart';

class WatchFaceCatalog {
  WatchFaceCatalog._();

  static List<WatchFace> get defaultInstalledFaces => [
    wayfinderOrange,
    ultraModularDark,
    californiaClassic,
    infographPro,
    modularDuo,
    solarDialBlue,
  ];

  static List<WatchFaceCollection> get allCollections => [
    WatchFaceCollection(
      name: Strings.current.ultra,
      description:
          Strings.current.exclusiveAppleWatchUltraFacesWithACustomizableBezel,
      faces: [
        wayfinderOrange,
        wayfinderWhite,
        wayfinderNight,
        ultraModularDark,
        ultraModularTrail,
      ],
    ),
    WatchFaceCollection(
      name: Strings.current.modular,
      description: Strings.current.largeInformationBlocksAndGraphsForDataAtA,
      faces: [modularDuo, modularMultiColor],
    ),
    WatchFaceCollection(
      name: Strings.current.classic,
      description:
          Strings.current.traditionalAnalogFacesWithRomanAndArabicNumerals,
      faces: [californiaClassic, californiaCaliforniaBlue, chronographProNavy],
    ),
    WatchFaceCollection(
      name: Strings.current.infographAstronomy,
      description: Strings.current.detailedAstronomicalAndWeatherDisplays,
      faces: [infographPro, solarDialBlue],
    ),
    WatchFaceCollection(
      name: Strings.current.activityNike,
      description: Strings.current.activityRingsWorkoutsAndSportsMetrics,
      faces: [activityAnalogLive, nikeBounceVolt],
    ),
  ];

  // Specific preset face instances:
  static WatchFace get wayfinderOrange => WatchFace(
    id: 'face_wayfinder_orange',
    title: Strings.current.wayfinder,
    collection: Strings.current.ultra,
    family: WatchFaceFamily.wayfinder,
    primaryColor: IosColors.ultraOrange,
    accentColor: Color(0xFF1E1E20),
    bezelStyle: BezelStyle.elevation,
    nightMode: false,
    isUltraExclusive: true,
    complications: {
      ComplicationSlot.topSubdial: ComplicationItem.compass,
      ComplicationSlot.topLeft: ComplicationItem.weather,
      ComplicationSlot.topRight: ComplicationItem.battery,
      ComplicationSlot.bottomLeft: ComplicationItem.heartRate,
      ComplicationSlot.bottomRight: ComplicationItem.workout,
      ComplicationSlot.bottomSubdial: ComplicationItem.activity,
    },
  );

  static WatchFace get wayfinderWhite => WatchFace(
    id: 'face_wayfinder_white',
    title: Strings.current.wayfinderAlpine,
    collection: Strings.current.ultra,
    family: WatchFaceFamily.wayfinder,
    primaryColor: Color(0xFFE5E5EA),
    accentColor: Color(0xFF0A84FF),
    bezelStyle: BezelStyle.incline,
    nightMode: false,
    isUltraExclusive: true,
    complications: {
      ComplicationSlot.topSubdial: ComplicationItem.compass,
      ComplicationSlot.topLeft: ComplicationItem.weather,
      ComplicationSlot.topRight: ComplicationItem.battery,
      ComplicationSlot.bottomLeft: ComplicationItem.heartRate,
      ComplicationSlot.bottomRight: ComplicationItem.audio,
      ComplicationSlot.bottomSubdial: ComplicationItem.activity,
    },
  );

  static WatchFace get wayfinderNight => WatchFace(
    id: 'face_wayfinder_night',
    title: Strings.current.wayfinderNightMode,
    collection: Strings.current.ultra,
    family: WatchFaceFamily.wayfinder,
    primaryColor: Color(0xFFFF2D55),
    accentColor: Color(0xFF000000),
    bezelStyle: BezelStyle.elevation,
    nightMode: true,
    isUltraExclusive: true,
    complications: {
      ComplicationSlot.topSubdial: ComplicationItem.compass,
      ComplicationSlot.topLeft: ComplicationItem.weather,
      ComplicationSlot.topRight: ComplicationItem.battery,
      ComplicationSlot.bottomLeft: ComplicationItem.heartRate,
      ComplicationSlot.bottomRight: ComplicationItem.workout,
      ComplicationSlot.bottomSubdial: ComplicationItem.activity,
    },
  );

  static WatchFace get ultraModularDark => WatchFace(
    id: 'face_ultra_modular_dark',
    title: Strings.current.ultraModular,
    collection: Strings.current.ultra,
    family: WatchFaceFamily.ultraModular,
    primaryColor: IosColors.systemOrange,
    accentColor: Color(0xFF2C2C2E),
    bezelStyle: BezelStyle.seconds,
    nightMode: false,
    isUltraExclusive: true,
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.weather,
      ComplicationSlot.topRight: ComplicationItem.battery,
      ComplicationSlot.center: ComplicationItem.activity,
      ComplicationSlot.bottomLeft: ComplicationItem.heartRate,
      ComplicationSlot.bottomRight: ComplicationItem.workout,
    },
  );

  static WatchFace get ultraModularTrail => WatchFace(
    id: 'face_ultra_modular_trail',
    title: Strings.current.ultraModularTrail,
    collection: Strings.current.ultra,
    family: WatchFaceFamily.ultraModular,
    primaryColor: IosColors.activityGreen,
    accentColor: Color(0xFF1C1C1E),
    bezelStyle: BezelStyle.elevation,
    nightMode: false,
    isUltraExclusive: true,
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.compass,
      ComplicationSlot.topRight: ComplicationItem.battery,
      ComplicationSlot.center: ComplicationItem.workout,
      ComplicationSlot.bottomLeft: ComplicationItem.heartRate,
      ComplicationSlot.bottomRight: ComplicationItem.weather,
    },
  );

  static WatchFace get modularDuo => WatchFace(
    id: 'face_modular_duo',
    title: Strings.current.modularDuo,
    collection: Strings.current.modular,
    family: WatchFaceFamily.modular,
    primaryColor: IosColors.systemBlue,
    accentColor: Color(0xFF1C1C1E),
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.calendar,
      ComplicationSlot.center: ComplicationItem.activity,
      ComplicationSlot.bottomLeft: ComplicationItem.heartRate,
      ComplicationSlot.bottomRight: ComplicationItem.battery,
    },
  );

  static WatchFace get modularMultiColor => WatchFace(
    id: 'face_modular_multi',
    title: Strings.current.modularMulticolor,
    collection: Strings.current.modular,
    family: WatchFaceFamily.modular,
    primaryColor: IosColors.systemPurple,
    accentColor: Color(0xFF1C1C1E),
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.weather,
      ComplicationSlot.center: ComplicationItem.workout,
      ComplicationSlot.bottomLeft: ComplicationItem.compass,
      ComplicationSlot.bottomRight: ComplicationItem.audio,
    },
  );

  static WatchFace get californiaClassic => WatchFace(
    id: 'face_california_classic',
    title: Strings.current.california,
    collection: Strings.current.classic,
    family: WatchFaceFamily.california,
    primaryColor: Color(0xFFD4AF37), // Gold
    accentColor: Color(0xFF1C1C1E),
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.calendar,
      ComplicationSlot.topRight: ComplicationItem.weather,
      ComplicationSlot.bottomSubdial: ComplicationItem.activity,
    },
  );

  static WatchFace get californiaCaliforniaBlue => WatchFace(
    id: 'face_california_blue',
    title: Strings.current.californiaOcean,
    collection: Strings.current.classic,
    family: WatchFaceFamily.california,
    primaryColor: IosColors.systemTeal,
    accentColor: Color(0xFF001B2E),
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.calendar,
      ComplicationSlot.topRight: ComplicationItem.battery,
      ComplicationSlot.bottomSubdial: ComplicationItem.heartRate,
    },
  );

  static WatchFace get chronographProNavy => WatchFace(
    id: 'face_chronograph_pro',
    title: Strings.current.chronographPro,
    collection: Strings.current.classic,
    family: WatchFaceFamily.chronographPro,
    primaryColor: Color(0xFF5E5CE6),
    accentColor: Color(0xFF1C1C1E),
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.calendar,
      ComplicationSlot.topRight: ComplicationItem.weather,
      ComplicationSlot.bottomSubdial: ComplicationItem.workout,
    },
  );

  static WatchFace get infographPro => WatchFace(
    id: 'face_infograph_pro',
    title: Strings.current.infograph,
    collection: Strings.current.infographAstronomy,
    family: WatchFaceFamily.infograph,
    primaryColor: IosColors.label,
    accentColor: Color(0xFF1C1C1E),
    complications: {
      ComplicationSlot.topSubdial: ComplicationItem.calendar,
      ComplicationSlot.topLeft: ComplicationItem.weather,
      ComplicationSlot.topRight: ComplicationItem.battery,
      ComplicationSlot.bottomLeft: ComplicationItem.heartRate,
      ComplicationSlot.bottomRight: ComplicationItem.workout,
      ComplicationSlot.bottomSubdial: ComplicationItem.activity,
    },
  );

  static WatchFace get solarDialBlue => WatchFace(
    id: 'face_solar_dial',
    title: Strings.current.solarDial,
    collection: Strings.current.infographAstronomy,
    family: WatchFaceFamily.solarDial,
    primaryColor: IosColors.systemTeal,
    accentColor: Color(0xFF001A33),
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.weather,
      ComplicationSlot.topRight: ComplicationItem.battery,
      ComplicationSlot.bottomSubdial: ComplicationItem.compass,
    },
  );

  static WatchFace get activityAnalogLive => WatchFace(
    id: 'face_activity_analog',
    title: Strings.current.activityAnalog,
    collection: Strings.current.activityNike,
    family: WatchFaceFamily.activityAnalog,
    primaryColor: IosColors.activityGreen,
    accentColor: Color(0xFF1C1C1E),
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.workout,
      ComplicationSlot.topRight: ComplicationItem.heartRate,
      ComplicationSlot.bottomSubdial: ComplicationItem.battery,
    },
  );

  static WatchFace get nikeBounceVolt => WatchFace(
    id: 'face_nike_volt',
    title: Strings.current.nikeBounceVolt,
    collection: Strings.current.activityNike,
    family: WatchFaceFamily.nike,
    primaryColor: Color(0xFFCCFF00), // Volt green
    accentColor: Color(0xFF000000),
    complications: {
      ComplicationSlot.topLeft: ComplicationItem.workout,
      ComplicationSlot.bottomLeft: ComplicationItem.activity,
    },
  );
}

class WatchFaceCollection {
  final String name;
  final String description;
  final List<WatchFace> faces;

  const WatchFaceCollection({
    required this.name,
    required this.description,
    required this.faces,
  });
}
