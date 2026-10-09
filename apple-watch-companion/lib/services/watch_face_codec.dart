import '../l10n/strings.dart';
import 'dart:convert';
import 'dart:typed_data';
import 'package:archive/archive.dart';
import 'package:flutter/cupertino.dart';
import '../models/watch_face.dart';
import '../theme/ios_colors.dart';

/// Legacy Companion preview-layout storage. This schema is not native NTK.
/// Use NativeWatchFaceArchive for actual Watch packages, preserving their opaque fields.
class WatchFaceCodec {
  static const int currentArchiveVersion = 3;
  static const String facePlistName = 'Face.plist';
  static const String faceJsonName = 'face.json';
  static const String metadataPlistName = 'metadata.plist';
  static const String snapshotPngName = 'snapshot.png';

  /// Decodes a `.watchface` ZIP archive into a [WatchFace] model and optional snapshot bytes.
  static WatchFaceArchiveResult decode(Uint8List bytes) {
    try {
      final archive = ZipDecoder().decodeBytes(bytes);
      Map<String, dynamic>? faceData;
      Uint8List? snapshotBytes;

      for (final file in archive) {
        if (file.isFile) {
          if (file.name == faceJsonName) {
            final content = utf8.decode(file.content as List<int>);
            faceData = jsonDecode(content) as Map<String, dynamic>;
          } else if (file.name == facePlistName && faceData == null) {
            // Attempt JSON parse or simple XML plist parsing
            final content = utf8.decode(
              file.content as List<int>,
              allowMalformed: true,
            );
            faceData = _parseSimplePlistOrJson(content);
          } else if (file.name == snapshotPngName) {
            snapshotBytes = Uint8List.fromList(file.content as List<int>);
          }
        }
      }

      if (faceData == null) {
        throw FormatException(
          'Archive does not contain a valid $facePlistName or $faceJsonName',
        );
      }

      final face = _watchFaceFromAppleMap(faceData);
      return WatchFaceArchiveResult(face: face, snapshotBytes: snapshotBytes);
    } catch (e) {
      throw FormatException('Failed to decode .watchface archive: $e');
    }
  }

  /// Encodes a local preview layout; this cannot be installed on a Watch.
  static Uint8List encode(WatchFace face, {Uint8List? snapshotBytes}) {
    final archive = Archive();

    // 1. Generate Face.plist / face.json payload
    final faceMap = _watchFaceToAppleMap(face);
    final faceJsonString = const JsonEncoder.withIndent('  ').convert(faceMap);
    final faceJsonBytes = utf8.encode(faceJsonString);

    archive.addFile(
      ArchiveFile(faceJsonName, faceJsonBytes.length, faceJsonBytes),
    );
    // Retain the legacy local-cache entry for existing Companion layouts.
    final plistXmlString = _generatePlistXml(faceMap);
    final plistXmlBytes = utf8.encode(plistXmlString);
    archive.addFile(
      ArchiveFile(facePlistName, plistXmlBytes.length, plistXmlBytes),
    );

    // 2. Metadata
    final metadataMap = {
      'version': currentArchiveVersion,
      'format': 'dev.applewatchandroid.companion.layout',
      'created_at': DateTime.now().toUtc().toIso8601String(),
      'bundle_id': face.effectiveAppleBundleId,
    };
    final metaJson = utf8.encode(jsonEncode(metadataMap));
    archive.addFile(ArchiveFile(metadataPlistName, metaJson.length, metaJson));

    // 3. Snapshot thumbnail if provided
    if (snapshotBytes != null && snapshotBytes.isNotEmpty) {
      archive.addFile(
        ArchiveFile(snapshotPngName, snapshotBytes.length, snapshotBytes),
      );
    }

    final encoded = ZipEncoder().encode(archive);
    return Uint8List.fromList(encoded ?? []);
  }

  static WatchFace _watchFaceFromAppleMap(Map<String, dynamic> map) {
    final ntkStyle =
        map['face_style']?.toString() ??
        map['ntkFaceStyle']?.toString() ??
        'NTKFaceStyleWayfinder';

    final family = WatchFace.familyFromNtkStyle(ntkStyle);
    final title =
        map['title']?.toString() ??
        map['name']?.toString() ??
        _defaultTitleForFamily(family);

    // Color extraction
    Color primary = IosColors.ultraOrange;
    Color accent = const Color(0xFF1E1E20);

    if (map['primaryColor'] is int) {
      primary = Color(map['primaryColor'] as int);
    } else if (map['color_palette'] is String) {
      primary = _parseColorPaletteName(map['color_palette'] as String);
    }

    if (map['accentColor'] is int) {
      accent = Color(map['accentColor'] as int);
    }

    // Bezel
    BezelStyle bezel = BezelStyle.elevation;
    final bezelStr = map['bezel_style']?.toString().toLowerCase() ?? '';
    if (bezelStr.contains('incline')) {
      bezel = BezelStyle.incline;
    } else if (bezelStr.contains('second')) {
      bezel = BezelStyle.seconds;
    } else if (bezelStr.contains('none')) {
      bezel = BezelStyle.none;
    }

    // Complications
    final Map<ComplicationSlot, ComplicationItem> complications = {};
    final compsMap = map['complications'];
    if (compsMap is Map) {
      for (final entry in compsMap.entries) {
        final slot = _parseComplicationSlot(entry.key.toString());
        final compId = entry.value is Map
            ? entry.value['type']?.toString() ?? 'battery'
            : entry.value.toString();

        final item = ComplicationItem.allPresetItems.firstWhere(
          (c) => c.id.toLowerCase() == compId.toLowerCase(),
          orElse: () => ComplicationItem.battery,
        );
        complications[slot] = item;
      }
    }

    final isUltra =
        family == WatchFaceFamily.wayfinder ||
        family == WatchFaceFamily.ultraModular ||
        map['is_ultra'] == true;

    return WatchFace(
      id:
          map['id']?.toString() ??
          'watchface_${DateTime.now().millisecondsSinceEpoch}',
      title: title,
      collection: isUltra ? 'Ultra' : Strings.current.custom,
      family: family,
      primaryColor: primary,
      accentColor: accent,
      bezelStyle: bezel,
      nightMode: map['night_mode'] == true,
      complications: complications.isEmpty
          ? _defaultComplicationsFor(family)
          : complications,
      isUltraExclusive: isUltra,
      appleBundleId: map['bundle_id']?.toString(),
      ntkFaceStyle: ntkStyle,
      customEditOptions:
          (map['custom_edit_options'] as Map<String, dynamic>?) ?? {},
    );
  }

  static Map<String, dynamic> _watchFaceToAppleMap(WatchFace face) {
    return {
      'face_style': face.effectiveNtkFaceStyle,
      'bundle_id': face.effectiveAppleBundleId,
      'title': face.title,
      'name': face.title,
      'collection': face.collection,
      'family': face.family.name,
      'is_ultra': face.isUltraExclusive,
      'primaryColor': face.primaryColor.value,
      'accentColor': face.accentColor.value,
      'bezel_style': face.bezelStyle.name,
      'night_mode': face.nightMode,
      'custom_edit_options': face.customEditOptions,
      'complications': face.complications.map(
        (slot, item) => MapEntry(_slotToAppleKey(slot), {
          'type': item.id,
          'title': item.title,
        }),
      ),
    };
  }

  static ComplicationSlot _parseComplicationSlot(String key) {
    final lower = key.toLowerCase().replaceAll('-', '_');
    if (lower.contains('top_subdial') || lower.contains('subdial_top')) {
      return ComplicationSlot.topSubdial;
    }
    if (lower.contains('bottom_subdial') || lower.contains('subdial_bottom')) {
      return ComplicationSlot.bottomSubdial;
    }
    if (lower.contains('top_left')) return ComplicationSlot.topLeft;
    if (lower.contains('top_right')) return ComplicationSlot.topRight;
    if (lower.contains('bottom_left')) return ComplicationSlot.bottomLeft;
    if (lower.contains('bottom_right')) return ComplicationSlot.bottomRight;
    if (lower.contains('center')) return ComplicationSlot.center;
    if (lower.contains('bezel')) return ComplicationSlot.bezel;
    return ComplicationSlot.topSubdial;
  }

  static String _slotToAppleKey(ComplicationSlot slot) {
    switch (slot) {
      case ComplicationSlot.topSubdial:
        return 'subdial-top';
      case ComplicationSlot.topLeft:
        return 'top-left';
      case ComplicationSlot.topRight:
        return 'top-right';
      case ComplicationSlot.center:
        return 'center';
      case ComplicationSlot.bottomLeft:
        return 'bottom-left';
      case ComplicationSlot.bottomRight:
        return 'bottom-right';
      case ComplicationSlot.bottomSubdial:
        return 'subdial-bottom';
      case ComplicationSlot.bezel:
        return 'bezel';
    }
  }

  static Map<ComplicationSlot, ComplicationItem> _defaultComplicationsFor(
    WatchFaceFamily family,
  ) {
    return {
      ComplicationSlot.topSubdial: ComplicationItem.compass,
      ComplicationSlot.topLeft: ComplicationItem.weather,
      ComplicationSlot.topRight: ComplicationItem.battery,
      ComplicationSlot.bottomLeft: ComplicationItem.heartRate,
      ComplicationSlot.bottomRight: ComplicationItem.workout,
      ComplicationSlot.bottomSubdial: ComplicationItem.activity,
    };
  }

  static String _defaultTitleForFamily(WatchFaceFamily family) {
    switch (family) {
      case WatchFaceFamily.wayfinder:
        return Strings.current.wayfinder;
      case WatchFaceFamily.ultraModular:
        return Strings.current.modularUltra;
      case WatchFaceFamily.modular:
        return Strings.current.modular;
      case WatchFaceFamily.california:
        return Strings.current.california;
      case WatchFaceFamily.chronographPro:
        return Strings.current.chronographPro;
      case WatchFaceFamily.infograph:
        return Strings.current.infograph;
      case WatchFaceFamily.solarDial:
        return Strings.current.solarDial;
      case WatchFaceFamily.activityAnalog:
        return Strings.current.activityAnalog;
      case WatchFaceFamily.activityDigital:
        return Strings.current.activityDigital;
      case WatchFaceFamily.nike:
        return 'Nike';
    }
  }

  static Color _parseColorPaletteName(String palette) {
    final lower = palette.toLowerCase();
    if (lower.contains('orange') || lower.contains('ultra')) {
      return IosColors.ultraOrange;
    }
    if (lower.contains('red')) return IosColors.systemRed;
    if (lower.contains('blue')) return IosColors.systemBlue;
    if (lower.contains('green') || lower.contains('volt')) {
      return const Color(0xFFCCFF00);
    }
    if (lower.contains('yellow')) return IosColors.systemYellow;
    if (lower.contains('white')) return const Color(0xFFF2F2F7);
    return IosColors.ultraOrange;
  }

  static Map<String, dynamic> _parseSimplePlistOrJson(String content) {
    try {
      return jsonDecode(content) as Map<String, dynamic>;
    } catch (_) {
      // Very basic XML plist parser for key-value extraction
      final Map<String, dynamic> result = {};
      final regex = RegExp(
        r'<key>([^<]+)<\/key>\s*<(string|integer|true|false)[^>]*>([^<]*)<\/?',
        multiLine: true,
      );
      for (final match in regex.allMatches(content)) {
        final key = match.group(1);
        final type = match.group(2);
        final value = match.group(3);
        if (key != null) {
          if (type == 'integer') {
            result[key] = int.tryParse(value ?? '0') ?? 0;
          } else if (type == 'true') {
            result[key] = true;
          } else if (type == 'false') {
            result[key] = false;
          } else {
            result[key] = value ?? '';
          }
        }
      }
      return result;
    }
  }

  static String _generatePlistXml(Map<String, dynamic> map) {
    final buffer = StringBuffer();
    buffer.writeln('<?xml version="1.0" encoding="UTF-8"?>');
    buffer.writeln(
      '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
    );
    buffer.writeln('<plist version="1.0">');
    buffer.writeln('<dict>');
    for (final entry in map.entries) {
      buffer.writeln('  <key>${entry.key}</key>');
      if (entry.value is bool) {
        buffer.writeln('  <${entry.value == true ? "true" : "false"}/>');
      } else if (entry.value is int) {
        buffer.writeln('  <integer>${entry.value}</integer>');
      } else {
        buffer.writeln('  <string>${entry.value.toString()}</string>');
      }
    }
    buffer.writeln('</dict>');
    buffer.writeln('</plist>');
    return buffer.toString();
  }
}

/// Result of decoding a `.watchface` bundle.
class WatchFaceArchiveResult {
  final WatchFace face;
  final Uint8List? snapshotBytes;

  const WatchFaceArchiveResult({required this.face, this.snapshotBytes});
}
