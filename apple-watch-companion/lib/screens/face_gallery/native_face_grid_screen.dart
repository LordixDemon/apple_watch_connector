import 'dart:math' as math;
import 'package:flutter/cupertino.dart';
import '../../controllers/native_face_controller.dart';
import '../../l10n/strings.dart';
import '../../services/native_gallery_collections.dart';
import '../../theme/ios_colors.dart';
import 'native_gallery_card.dart';

/// NanoFaceGallery's AllFacesLink opens a separate alphabetized GalleryGrid.
/// The current catalog supplies factory presets; curation placement filtering
/// is a separate data source and is not inferred from family names here.
class NativeFaceGridScreen extends StatefulWidget {
  final List<NativeGallerySection> sections;
  final ValueChanged<NativeGalleryVariant> onSelect;
  final NativeFaceController controller;
  const NativeFaceGridScreen({
    super.key,
    required this.sections,
    required this.onSelect,
    required this.controller,
  });
  @override
  State<NativeFaceGridScreen> createState() => _NativeFaceGridScreenState();
}

class _NativeFaceGridScreenState extends State<NativeFaceGridScreen> {
  String? _locale;
  List<NativeGalleryVariant> _variants = const [];

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _prepare();
  }

  @override
  void didUpdateWidget(NativeFaceGridScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.sections, widget.sections)) _prepare(force: true);
  }

  void _prepare({bool force = false}) {
    final locale = Strings.current.localeName;
    if (!force && _locale == locale) return;
    _locale = locale;
    // Sort indices to retain factory order for equal display names. Do not
    // deduplicate presets: native rows can contain distinct recipes/positions.
    final entries = [
      for (final section in widget.sections) ...section.variants,
    ].indexed.toList();
    entries.sort((a, b) {
      final comparison = a.$2.template
          .title(locale)
          .toLowerCase()
          .compareTo(b.$2.template.title(locale).toLowerCase());
      return comparison == 0 ? a.$1.compareTo(b.$1) : comparison;
    });
    _variants = List.unmodifiable(entries.map((entry) => entry.$2));
  }

  @override
  Widget build(BuildContext context) => CupertinoPageScaffold(
    backgroundColor: IosColors.systemBackground,
    navigationBar: CupertinoNavigationBar(
      backgroundColor: IosColors.systemBackground,
      middle: Text(Strings.current.nativeFaceAllWatchFaces),
    ),
    child: SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) {
          const spacing = 16.0;
          final available = math.max(1.0, constraints.maxWidth - 32);
          final scale = MediaQuery.textScalerOf(context).scale(13) / 13;
          final columns = ((available + spacing) / (128 * scale + spacing))
              .floor()
              .clamp(1, 6);
          final cellWidth = (available - spacing * (columns - 1)) / columns;
          final cardWidth = math.min(144.0, cellWidth);
          final height =
              cardWidth * 1.22 +
              8 +
              NativeGalleryCard.labelHeight(context, _variants, cardWidth);
          return GridView.builder(
            key: const ValueKey('native-all-faces-grid'),
            padding: const EdgeInsets.all(16),
            itemCount: _variants.length,
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: columns,
              crossAxisSpacing: spacing,
              mainAxisSpacing: 24,
              mainAxisExtent: height,
            ),
            itemBuilder: (context, index) {
              final variant = _variants[index];
              Widget card() => Align(
                alignment: Alignment.topCenter,
                child: NativeGalleryCard(
                  key: ValueKey('native-all-face-$index'),
                  variant: variant,
                  width: cardWidth,
                  onPressed:
                      !variant.template.requiresPhotos ||
                          widget.controller.canMutate
                      ? () => widget.onSelect(variant)
                      : null,
                ),
              );
              return variant.template.requiresPhotos
                  ? ListenableBuilder(
                      listenable: widget.controller,
                      builder: (_, _) => card(),
                    )
                  : card();
            },
          );
        },
      ),
    ),
  );
}
