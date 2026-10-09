import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../l10n/strings.dart';
import '../../providers/watch_provider.dart';
import '../../controllers/native_face_controller.dart';
import '../../services/native_face_gallery.dart';
import '../../services/native_gallery_collections.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_tile.dart';
import 'native_face_creation_screen.dart';
import 'native_face_presentation.dart';
import 'native_gallery_card.dart';
import 'native_face_grid_screen.dart';
import 'native_face_photos_screen.dart';
import 'native_faces_screen.dart';

class NativeFaceTemplatesScreen extends StatefulWidget {
  final bool gallery;
  final VoidCallback? localDesigns;
  const NativeFaceTemplatesScreen({
    super.key,
    this.gallery = false,
    this.localDesigns,
  });
  @override
  State<NativeFaceTemplatesScreen> createState() =>
      _NativeFaceTemplatesScreenState();
}

class _NativeFaceTemplatesScreenState extends State<NativeFaceTemplatesScreen> {
  final _profiles = NativeGalleryCollections.load();
  final _search = TextEditingController();
  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _add(NativeFaceTemplate template) async {
    final controller = context.read<WatchProvider>().nativeFaces;
    final pair = controller.collection.pair,
        epoch = controller.collection.epoch;
    if (template.requiresPhotos) {
      if (pair == null || epoch == null || !controller.canMutate) return;
      await Navigator.of(context).push(
        CupertinoPageRoute(
          builder: (_) => NativeFacePhotosScreen(
            template: template,
            pair: pair,
            epoch: epoch,
          ),
        ),
      );
      return;
    }
    await Navigator.of(context).push(
      CupertinoPageRoute(
        builder: (_) => NativeFaceCreationScreen(
          template: template,
          pair: pair,
          epoch: epoch,
        ),
      ),
    );
  }

  void _openVariant(NativeGalleryVariant variant) => _add(
    variant.template.requiresPhotos
        ? variant.template
        : variant.template.withConfiguration(variant.configurationJson),
  );

  @override
  Widget build(BuildContext context) {
    final controller = context.select<WatchProvider, NativeFaceController>(
      (provider) => provider.nativeFaces,
    );
    return _buildTemplates(context, controller);
  }

  Widget _buildTemplates(
    BuildContext context,
    NativeFaceController controller,
  ) {
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        // The list is below SafeArea; no content scrolls behind this bar.
        // Opaque paint avoids sampling/reblurring the whole scrolling scene.
        backgroundColor: IosColors.systemBackground,
        middle: Text(
          widget.gallery
              ? Strings.current.faceGallery
              : Strings.current.nativeFaceAvailable,
        ),
        trailing: widget.gallery
            ? Semantics(
                label: Strings.current.nativeFaceImport,
                button: true,
                child: CupertinoButton(
                  padding: EdgeInsets.zero,
                  onPressed: () => Navigator.of(context).push(
                    CupertinoPageRoute(
                      builder: (_) =>
                          const NativeFacesScreen(importOnOpen: true),
                    ),
                  ),
                  child: const Icon(CupertinoIcons.arrow_down_doc),
                ),
              )
            : null,
      ),
      child: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(12),
              // Cupertino exposes the placeholder as the editable field's
              // label only while empty. Keep its accessible name after typing
              // without announcing the placeholder twice in an empty field.
              child: Semantics(
                label: _search.text.isEmpty
                    ? null
                    : Strings.current.nativeFaceSearch,
                child: CupertinoSearchTextField(
                  controller: _search,
                  placeholder: Strings.current.nativeFaceSearch,
                  onChanged: (_) => setState(() {}),
                ),
              ),
            ),
            ListenableBuilder(
              listenable: controller,
              builder: (context, _) {
                final status = nativeFaceStatus(
                  controller.result,
                  refresh: controller.lastOperationWasRefresh,
                );
                return Column(
                  children: [
                    if (controller.busy) const CupertinoActivityIndicator(),
                    if (status.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.all(12),
                        child: Text(
                          status,
                          style: const TextStyle(
                            color: IosColors.secondaryLabel,
                          ),
                        ),
                      ),
                  ],
                );
              },
            ),
            Expanded(
              child: FutureBuilder<List<NativeGallerySection>>(
                future: _profiles,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return Center(
                      child: Text(
                        Strings.current.nativeFaceConfigurationUnavailable,
                      ),
                    );
                  }
                  if (!snapshot.hasData) {
                    return const Center(child: CupertinoActivityIndicator());
                  }
                  final query = _search.text.toLowerCase();
                  final faces = snapshot.data!
                      .where(
                        (v) =>
                            v
                                .title(Strings.current.localeName)
                                .toLowerCase()
                                .contains(query) ||
                            v.variants.any(
                              (f) => f.template
                                  .title(Strings.current.localeName)
                                  .toLowerCase()
                                  .contains(query),
                            ),
                      )
                      .toList();
                  final showAll = widget.gallery && query.isEmpty;
                  return ListView.builder(
                    itemCount:
                        faces.length +
                        (showAll ? 1 : 0) +
                        (widget.localDesigns == null ? 0 : 1),
                    itemBuilder: (_, index) {
                      if (showAll && index == 0) {
                        return IosListTile(
                          title: Strings.current.nativeFaceSeeAllWatchFaces,
                          onTap: () => Navigator.of(context).push(
                            CupertinoPageRoute(
                              builder: (_) => NativeFaceGridScreen(
                                sections: snapshot.data!,
                                controller: controller,
                                onSelect: _openVariant,
                              ),
                            ),
                          ),
                        );
                      }
                      if (showAll) index--;
                      if (index == faces.length) {
                        return IosListTile(
                          title: Strings.current.nativeFaceLocalLibrary,
                          onTap: widget.localDesigns,
                        );
                      }
                      final section = faces[index];
                      Widget row() => NativeGallerySectionRow(
                        section: section,
                        onSelect: _openVariant,
                        photosEnabled: controller.canMutate,
                      );
                      return section.variants.any(
                            (v) => v.template.requiresPhotos,
                          )
                          ? ListenableBuilder(
                              listenable: controller,
                              builder: (_, _) => row(),
                            )
                          : row();
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Native gallery collections are horizontal, with independently lazy cards.
/// Receipts update the status banner rather than rebuilding every appearance row.
class NativeGallerySectionRow extends StatelessWidget {
  final NativeGallerySection section;
  final ValueChanged<NativeGalleryVariant>? onSelect;
  final bool photosEnabled;
  const NativeGallerySectionRow({
    super.key,
    required this.section,
    this.onSelect,
    this.photosEnabled = true,
  });
  @override
  Widget build(BuildContext context) {
    final width = (MediaQuery.sizeOf(context).width / 2.5).clamp(112.0, 144.0);
    final labelHeight = NativeGalleryCard.labelHeight(
      context,
      section.variants,
      width,
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: Text(
              section.title(Strings.current.localeName).toUpperCase(),
              style: const TextStyle(
                fontSize: 17,
                color: IosColors.label,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          SizedBox(
            height: width * 1.22 + 8 + labelHeight,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              itemCount: section.variants.length,
              separatorBuilder: (_, _) => const SizedBox(width: 16),
              itemBuilder: (_, index) {
                final variant = section.variants[index];
                return NativeGalleryCard(
                  variant: variant,
                  width: width,
                  onPressed:
                      onSelect != null &&
                          (!variant.template.requiresPhotos || photosEnabled)
                      ? () => onSelect!(variant)
                      : null,
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
