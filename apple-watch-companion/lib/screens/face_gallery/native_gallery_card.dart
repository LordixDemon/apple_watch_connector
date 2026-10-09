import 'package:flutter/cupertino.dart';
import '../../l10n/strings.dart';
import '../../services/native_gallery_collections.dart';
import '../../theme/ios_colors.dart';
import 'native_face_preview.dart';

/// The same native preset and editor action in horizontal rows and All Faces.
class NativeGalleryCard extends StatelessWidget {
  final NativeGalleryVariant variant;
  final double width;
  final VoidCallback? onPressed;
  const NativeGalleryCard({
    super.key,
    required this.variant,
    required this.width,
    this.onPressed,
  });

  static const labelStyle = TextStyle(
    fontSize: 13,
    color: IosColors.label,
    fontWeight: FontWeight.w600,
  );

  static double labelHeight(
    BuildContext context,
    Iterable<NativeGalleryVariant> variants,
    double width,
  ) {
    var height = 0.0;
    for (final title
        in variants
            .map((v) => v.template.title(Strings.current.localeName))
            .toSet()) {
      final painter = TextPainter(
        text: TextSpan(
          text: title,
          style: DefaultTextStyle.of(context).style.merge(labelStyle),
        ),
        textDirection: Directionality.of(context),
        textScaler: MediaQuery.textScalerOf(context),
      )..layout(maxWidth: width);
      if (painter.height > height) height = painter.height;
      painter.dispose();
    }
    return height;
  }

  @override
  Widget build(BuildContext context) {
    final profile = variant.template;
    final configuration = variant.configuration;
    final title = profile.title(Strings.current.localeName);
    final values = configuration['customization'];
    final labels = values is Map
        ? [
            for (final entry in values.entries)
              if (entry.key is String && entry.value is String)
                profile.valueTitle(
                  entry.key as String,
                  entry.value as String,
                  Strings.current.localeName,
                ),
          ].whereType<String>().toSet().join(', ')
        : '';
    return RepaintBoundary(
      child: Semantics(
        label: labels.isEmpty ? title : '$title, $labels',
        button: true,
        child: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: onPressed,
          child: SizedBox(
            width: width,
            child: Column(
              children: [
                NativeFacePreview(
                  template: profile,
                  configuration: configuration,
                  width: width,
                  fallbackToDefault: false,
                ),
                const SizedBox(height: 8),
                Text(title, style: labelStyle, textAlign: TextAlign.center),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
