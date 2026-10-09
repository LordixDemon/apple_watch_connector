import 'package:flutter/cupertino.dart';
import '../../l10n/strings.dart';
import '../../models/native_face_collection.dart';
import '../../services/native_face_options.dart';
export '../../widgets/native_face_status.dart';

String nativeFaceTitle(NativeWatchFace face) {
  final title = NativeFaceOptions.title(face.bundle);
  if (title != null) return title;
  final profile = NativeFaceOptions.profile(face);
  if (profile != null) return profile.title(Strings.current.localeName);
  final config = face.configuration;
  final name =
      config?['name'] ?? config?['analytics id'] ?? config?['face type'];
  if (name is String && name.trim().isNotEmpty) return name;
  return face.bundle
      .split('.')
      .last
      .replaceFirst(RegExp(r'^NTK'), '')
      .replaceFirst(RegExp(r'FaceBundle$'), '');
}

String nativeFaceSummary(NativeWatchFace face) {
  final config = face.configuration;
  final values = config?['customization'];
  if (values is! Map) return '';
  final labels = <String>[];
  final profile = NativeFaceOptions.profile(face);
  for (final key in ['style', 'color', 'night']) {
    final value = values[key];
    if (value is String || value is num || value is bool) {
      labels.add(NativeFaceOptions.valueTitle(key, value, face: face));
    } else if (value is Map && config != null && profile != null) {
      for (final control in profile.controls) {
        if (control.path.length < 3 ||
            control.path[0] != 'customization' ||
            control.path[1] != key) {
          continue;
        }
        final token = control.value(config);
        final group = control.group(config);
        if (token != null && group != null && group.labels.containsKey(token)) {
          final locale = Strings.current.localeName;
          labels.add('${control.title(locale)}: ${group.title(token, locale)}');
        }
      }
    }
  }
  return labels.join(' · ');
}

Future<void> showFaceFileError(BuildContext context, Object error) =>
    showCupertinoDialog<void>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(Strings.current.nativeFaceFileError),
        content: Text(
          error is FormatException
              ? error.message
              : Strings.current.nativeFaceRejected,
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(ctx),
            child: Text(Strings.current.ok),
          ),
        ],
      ),
    );
