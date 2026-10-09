import '../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import '../theme/ios_colors.dart';

enum WatchFaceFamily {
  wayfinder,
  ultraModular,
  modular,
  california,
  chronographPro,
  infograph,
  solarDial,
  activityAnalog,
  activityDigital,
  nike,
}

enum BezelStyle { elevation, incline, seconds, none }

/// Slot identifier for watch face complications.
enum ComplicationSlot {
  topSubdial,
  topLeft,
  topRight,
  center,
  bottomLeft,
  bottomRight,
  bottomSubdial,
  bezel,
}

/// A complication item that can be placed in a slot.
class ComplicationItem {
  final String id;
  final String title;
  final String subtitle;
  final IconData icon;
  final Color tintColor;

  const ComplicationItem({
    required this.id,
    required this.title,
    required this.subtitle,
    required this.icon,
    this.tintColor = IosColors.label,
  });

  static ComplicationItem get battery => ComplicationItem(
    id: 'battery',
    title: Strings.current.battery,
    subtitle: '88%',
    icon: CupertinoIcons.battery_75_percent,
    tintColor: IosColors.systemGreen,
  );

  static ComplicationItem get activity => ComplicationItem(
    id: 'activity',
    title: Strings.current.activity,
    subtitle: '620/30/12',
    icon: CupertinoIcons.flame_fill,
    tintColor: IosColors.activityRed,
  );

  static ComplicationItem get heartRate => ComplicationItem(
    id: 'heart_rate',
    title: Strings.current.heartRate,
    subtitle: Strings.current.message72Bpm,
    icon: CupertinoIcons.heart_fill,
    tintColor: IosColors.systemRed,
  );

  static ComplicationItem get weather => ComplicationItem(
    id: 'weather',
    title: Strings.current.weather,
    subtitle: Strings.current.message21Sunny,
    icon: CupertinoIcons.sun_max_fill,
    tintColor: IosColors.systemYellow,
  );

  static ComplicationItem get compass => ComplicationItem(
    id: 'compass',
    title: Strings.current.compass,
    subtitle: Strings.current.message124Se182M,
    icon: CupertinoIcons.compass_fill,
    tintColor: IosColors.systemOrange,
  );

  static ComplicationItem get workout => ComplicationItem(
    id: 'workout',
    title: Strings.current.workout,
    subtitle: Strings.current.outdoorRun,
    icon: CupertinoIcons.play_circle_fill,
    tintColor: IosColors.activityGreen,
  );

  static ComplicationItem get audio => ComplicationItem(
    id: 'music',
    title: Strings.current.nowPlaying,
    subtitle: Strings.current.appleMusic,
    icon: CupertinoIcons.music_note_2,
    tintColor: IosColors.systemPink,
  );

  static ComplicationItem get calendar => ComplicationItem(
    id: 'calendar',
    title: Strings.current.calendar,
    subtitle: Strings.current.meetingAt1930,
    icon: CupertinoIcons.calendar,
    tintColor: IosColors.systemRed,
  );

  static List<ComplicationItem> get allPresetItems => [
    battery,
    activity,
    heartRate,
    weather,
    compass,
    workout,
    audio,
    calendar,
  ];
}

/// Watch face configuration.
class WatchFace {
  final String id;
  final String title;
  final String collection;
  final WatchFaceFamily family;
  final Color primaryColor;
  final Color accentColor;
  final BezelStyle bezelStyle;
  final bool nightMode;
  final Map<ComplicationSlot, ComplicationItem> complications;
  final bool isUltraExclusive;
  final String? appleBundleId;
  final String? ntkFaceStyle;
  final Map<String, dynamic> customEditOptions;

  const WatchFace({
    required this.id,
    required this.title,
    required this.collection,
    required this.family,
    required this.primaryColor,
    required this.accentColor,
    this.bezelStyle = BezelStyle.elevation,
    this.nightMode = false,
    required this.complications,
    this.isUltraExclusive = false,
    this.appleBundleId,
    this.ntkFaceStyle,
    this.customEditOptions = const {},
  });

  String get effectiveAppleBundleId =>
      appleBundleId ?? 'com.apple.NanoTimeKit.face.${family.name}';

  String get effectiveNtkFaceStyle =>
      ntkFaceStyle ?? ntkStyleFromFamily(family);

  static String ntkStyleFromFamily(WatchFaceFamily family) {
    switch (family) {
      case WatchFaceFamily.wayfinder:
        return 'NTKFaceStyleWayfinder';
      case WatchFaceFamily.ultraModular:
        return 'NTKFaceStyleModularUltra';
      case WatchFaceFamily.modular:
        return 'NTKFaceStyleModular';
      case WatchFaceFamily.california:
        return 'NTKFaceStyleCalifornia';
      case WatchFaceFamily.chronographPro:
        return 'NTKFaceStyleChronographPro';
      case WatchFaceFamily.infograph:
        return 'NTKFaceStyleInfograph';
      case WatchFaceFamily.solarDial:
        return 'NTKFaceStyleSolarDial';
      case WatchFaceFamily.activityAnalog:
        return 'NTKFaceStyleActivityAnalog';
      case WatchFaceFamily.activityDigital:
        return 'NTKFaceStyleActivityDigital';
      case WatchFaceFamily.nike:
        return 'NTKFaceStyleNike';
    }
  }

  static WatchFaceFamily familyFromNtkStyle(String style) {
    final lower = style.toLowerCase();
    if (lower.contains('wayfinder')) return WatchFaceFamily.wayfinder;
    if (lower.contains('ultra') || lower.contains('modularultra')) {
      return WatchFaceFamily.ultraModular;
    }
    if (lower.contains('modular')) return WatchFaceFamily.modular;
    if (lower.contains('california')) return WatchFaceFamily.california;
    if (lower.contains('chronograph')) return WatchFaceFamily.chronographPro;
    if (lower.contains('infograph')) return WatchFaceFamily.infograph;
    if (lower.contains('solar')) return WatchFaceFamily.solarDial;
    if (lower.contains('activityanalog')) return WatchFaceFamily.activityAnalog;
    if (lower.contains('activity')) return WatchFaceFamily.activityDigital;
    if (lower.contains('nike')) return WatchFaceFamily.nike;
    return WatchFaceFamily.modular;
  }

  WatchFace copyWith({
    String? id,
    String? title,
    String? collection,
    WatchFaceFamily? family,
    Color? primaryColor,
    Color? accentColor,
    BezelStyle? bezelStyle,
    bool? nightMode,
    Map<ComplicationSlot, ComplicationItem>? complications,
    bool? isUltraExclusive,
    String? appleBundleId,
    String? ntkFaceStyle,
    Map<String, dynamic>? customEditOptions,
  }) {
    return WatchFace(
      id: id ?? this.id,
      title: title ?? this.title,
      collection: collection ?? this.collection,
      family: family ?? this.family,
      primaryColor: primaryColor ?? this.primaryColor,
      accentColor: accentColor ?? this.accentColor,
      bezelStyle: bezelStyle ?? this.bezelStyle,
      nightMode: nightMode ?? this.nightMode,
      complications: complications ?? this.complications,
      isUltraExclusive: isUltraExclusive ?? this.isUltraExclusive,
      appleBundleId: appleBundleId ?? this.appleBundleId,
      ntkFaceStyle: ntkFaceStyle ?? this.ntkFaceStyle,
      customEditOptions: customEditOptions ?? this.customEditOptions,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'collection': collection,
      'family': family.name,
      'primaryColor': primaryColor.value,
      'accentColor': accentColor.value,
      'bezelStyle': bezelStyle.name,
      'nightMode': nightMode,
      'isUltraExclusive': isUltraExclusive,
      'appleBundleId': effectiveAppleBundleId,
      'ntkFaceStyle': effectiveNtkFaceStyle,
      'customEditOptions': customEditOptions,
      'complications': complications.map((k, v) => MapEntry(k.name, v.id)),
    };
  }

  static WatchFace fromJson(Map<String, dynamic> json) {
    final family = WatchFaceFamily.values.firstWhere(
      (f) => f.name == json['family'],
      orElse: () => familyFromNtkStyle(json['ntkFaceStyle'] ?? ''),
    );

    final bezel = BezelStyle.values.firstWhere(
      (b) => b.name == json['bezelStyle'],
      orElse: () => BezelStyle.none,
    );

    final Map<ComplicationSlot, ComplicationItem> comps = {};
    if (json['complications'] is Map) {
      final rawComps = json['complications'] as Map<String, dynamic>;
      for (final entry in rawComps.entries) {
        final slot = ComplicationSlot.values.firstWhere(
          (s) => s.name == entry.key,
          orElse: () => ComplicationSlot.topSubdial,
        );
        final item = ComplicationItem.allPresetItems.firstWhere(
          (item) => item.id == entry.value,
          orElse: () => ComplicationItem.battery,
        );
        comps[slot] = item;
      }
    }

    return WatchFace(
      id: json['id'] ?? 'imported_${DateTime.now().millisecondsSinceEpoch}',
      title: json['title'] ?? Strings.current.custom,
      collection: json['collection'] ?? Strings.current.imported,
      family: family,
      primaryColor: Color(json['primaryColor'] as int? ?? 0xFFFF9500),
      accentColor: Color(json['accentColor'] as int? ?? 0xFF1C1C1E),
      bezelStyle: bezel,
      nightMode: json['nightMode'] == true,
      isUltraExclusive: json['isUltraExclusive'] == true,
      appleBundleId: json['appleBundleId'],
      ntkFaceStyle: json['ntkFaceStyle'],
      customEditOptions:
          (json['customEditOptions'] as Map<String, dynamic>?) ?? const {},
      complications: comps,
    );
  }
}
