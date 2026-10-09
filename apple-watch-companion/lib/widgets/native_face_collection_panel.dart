import 'package:flutter/cupertino.dart';
import '../controllers/native_face_controller.dart';
import '../l10n/strings.dart';
import '../models/native_face_collection.dart';
import 'ios_list_tile.dart';
import 'native_face_status.dart';
import '../services/native_face_options.dart';
import '../screens/face_gallery/native_face_preview.dart';
import '../theme/ios_colors.dart';
import '../theme/companion_spacing.dart';

/// Uses the same operation owner as the gallery and editors.
class NativeFaceCollectionPanel extends StatelessWidget {
  final NativeFaceController controller;
  final VoidCallback? manage;
  final void Function(NativeWatchFace)? openFace;
  final String Function(NativeWatchFace)? faceTitle;
  const NativeFaceCollectionPanel({
    super.key,
    required this.controller,
    this.manage,
    this.openFace,
    this.faceTitle,
  });

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final collection = controller.collection;
      final status = nativeFaceStatus(
        controller.result,
        refresh: controller.lastOperationWasRefresh,
      );
      return Padding(
        padding: CompanionSpacing.sectionMargin,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            LayoutBuilder(
              builder: (context, constraints) {
                final heading = Semantics(
                  header: true,
                  label: collection.complete
                      ? Strings.current.myFacesCount(collection.ordered.length)
                      : Strings.current.myFaces,
                  child: ExcludeSemantics(
                    child: Text(
                      Strings.current.myFaces,
                      style: const TextStyle(fontSize: 17),
                    ),
                  ),
                );
                if (manage == null) return heading;
                final edit = CupertinoButton(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  onPressed: manage,
                  child: Text(Strings.current.nativeFaceLibraryEdit),
                );
                double textWidth(String text, TextStyle style) {
                  final painter = TextPainter(
                    text: TextSpan(text: text, style: style),
                    textDirection: Directionality.of(context),
                    textScaler: MediaQuery.textScalerOf(context),
                  )..layout();
                  final width = painter.width;
                  painter.dispose();
                  return width;
                }

                final theme = CupertinoTheme.of(context).textTheme;
                final fits =
                    textWidth(
                          Strings.current.myFaces,
                          DefaultTextStyle.of(
                            context,
                          ).style.merge(const TextStyle(fontSize: 17)),
                        ) +
                        textWidth(
                          Strings.current.nativeFaceLibraryEdit,
                          theme.actionTextStyle,
                        ) +
                        16 <=
                    constraints.maxWidth;
                // The native header reflows when preferred text no longer fits.
                if (!fits) {
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      heading,
                      Align(
                        alignment: AlignmentDirectional.centerEnd,
                        child: edit,
                      ),
                    ],
                  );
                }
                return Row(
                  children: [
                    Expanded(child: heading),
                    edit,
                  ],
                );
              },
            ),
            if (!collection.known)
              IosListTile(title: Strings.current.collectionNotReceivedYet),
            if (collection.known && !collection.complete)
              IosListTile(title: Strings.current.partialCollectionReceived),
            if (collection.complete && collection.ordered.isEmpty)
              IosListTile(title: Strings.current.collectionIsEmpty),
            if (collection.displayOrder.isNotEmpty)
              LayoutBuilder(
                builder: (context, constraints) {
                  final width = (constraints.maxWidth / 2.5).clamp(
                    112.0,
                    144.0,
                  );
                  final style = CupertinoTheme.of(context).textTheme.textStyle
                      .copyWith(fontSize: 15, color: IosColors.label);
                  final previewWidth = width - 14;
                  final titles = [
                    for (final (index, id) in collection.displayOrder.indexed)
                      faceTitle != null && collection.face(id) != null
                          ? faceTitle!(collection.face(id)!)
                          : Strings.current.face((index + 1).toString()),
                  ];
                  var labelHeight = 0.0;
                  for (final title in titles.toSet()) {
                    final text = TextPainter(
                      text: TextSpan(text: title, style: style),
                      textDirection: Directionality.of(context),
                      textScaler: MediaQuery.textScalerOf(context),
                    )..layout(maxWidth: width);
                    if (text.height > labelHeight) labelHeight = text.height;
                    text.dispose();
                  }
                  return SizedBox(
                    height: previewWidth * 1.22 + 22 + labelHeight,
                    child: ListView.separated(
                      key: const ValueKey('native-my-faces-carousel'),
                      scrollDirection: Axis.horizontal,
                      itemCount: collection.displayOrder.length,
                      separatorBuilder: (_, _) => const SizedBox(width: 15),
                      itemBuilder: (context, index) {
                        final id = collection.displayOrder[index];
                        final face = collection.face(id);
                        final selected = id == collection.selected;
                        final template = face == null
                            ? null
                            : NativeFaceOptions.profile(face);
                        // Native card taps open detail. Reading a card never
                        // selects a face or sends a Watch command.
                        return Semantics(
                          key: ValueKey(id),
                          label: titles[index],
                          selected: selected,
                          hint: selected
                              ? Strings.current.selectedOnWatch
                              : null,
                          child: CupertinoButton(
                            padding: EdgeInsets.zero,
                            onPressed: face != null && openFace != null
                                ? () => openFace!(face)
                                : null,
                            child: RepaintBoundary(
                              child: SizedBox(
                                width: width,
                                child: Column(
                                  children: [
                                    Container(
                                      padding: const EdgeInsets.all(4),
                                      decoration: BoxDecoration(
                                        borderRadius: BorderRadius.circular(
                                          width * .2,
                                        ),
                                        border: Border.all(
                                          color: selected
                                              // NTKCActiveColor, iOS 26.6:
                                              // native RGB 27/255/140.
                                              ? const Color(0xFF1BFF8C)
                                              : IosColors.separator,
                                          width: 3,
                                        ),
                                      ),
                                      child: template == null
                                          ? SizedBox(
                                              width: previewWidth,
                                              height: previewWidth * 1.22,
                                              child: const Icon(
                                                CupertinoIcons.clock,
                                              ),
                                            )
                                          : NativeFacePreview(
                                              template: template,
                                              width: previewWidth,
                                              configuration:
                                                  face!.configuration,
                                              fallbackToDefault: false,
                                            ),
                                    ),
                                    const SizedBox(height: 8),
                                    ExcludeSemantics(
                                      child: Text(
                                        titles[index],
                                        textAlign: TextAlign.center,
                                        style: style.copyWith(
                                          color: IosColors.label,
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                  );
                },
              ),
            if (controller.busy) const CupertinoActivityIndicator(),
            if (status.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(
                  status,
                  style: const TextStyle(
                    color: IosColors.secondaryLabel,
                    fontSize: 13,
                  ),
                ),
              ),
          ],
        ),
      );
    },
  );
}
