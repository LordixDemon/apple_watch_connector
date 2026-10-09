import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../models/watch_face.dart';
import '../../providers/watch_provider.dart';
import '../../services/watch_face_catalog.dart';
import '../../theme/ios_colors.dart';
import '../../theme/companion_spacing.dart';
import '../../widgets/watch_face_view.dart';
import 'face_customizer_screen.dart';
import 'native_faces_screen.dart';
import 'native_face_templates_screen.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class FaceGalleryTab extends StatelessWidget {
  final bool localLayouts;
  const FaceGalleryTab({super.key, this.localLayouts = false});

  @override
  Widget build(BuildContext context) {
    if (!localLayouts) {
      return const NativeFaceTemplatesScreen(gallery: true);
    }
    final provider = context.watch<WatchProvider>();
    final collections = WatchFaceCatalog.allCollections;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      child: CustomScrollView(
        slivers: [
          CupertinoSliverNavigationBar(
            largeTitle: Text(Strings.current.faceGallery),
            backgroundColor: const Color(0xCC121212),
            trailing: CupertinoButton(
              padding: EdgeInsets.zero,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    CupertinoIcons.arrow_down_doc,
                    color: IosColors.systemOrange,
                    size: 18,
                  ),
                  SizedBox(width: 4),
                  Text(
                    Strings.current.import,
                    style: TextStyle(
                      color: IosColors.systemOrange,
                      fontWeight: FontWeight.w600,
                      fontSize: 14,
                    ),
                  ),
                ],
              ),
              onPressed: () => _handleImportWatchFace(context, provider),
            ),
          ),

          // Online / Remote Collections banner if available
          SliverToBoxAdapter(
            child: IosListSection(
              children: [
                IosListTile(
                  title: Strings.current.manageWatchFaces,
                  onTap: () => Navigator.of(context).push(
                    CupertinoPageRoute(
                      builder: (_) => const NativeFacesScreen(),
                    ),
                  ),
                ),
              ],
            ),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: Text(
                Strings.current.nativeFaceLocalLayoutHint,
                style: const TextStyle(
                  color: IosColors.secondaryLabel,
                  fontSize: 13,
                ),
              ),
            ),
          ),
          if (provider.remoteFaces.isNotEmpty)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.only(top: 16),
                child: _buildCollectionSection(
                  context,
                  WatchFaceCollection(
                    name: Strings.current.onlineGallery,
                    description: Strings
                        .current
                        .downloadedFromTheAppleWatchFaceCatalogServer,
                    faces: provider.remoteFaces,
                  ),
                ),
              ),
            ),

          SliverToBoxAdapter(
            child: ListView.separated(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              padding: const EdgeInsets.symmetric(vertical: 16),
              itemCount: collections.length,
              separatorBuilder: (context, index) =>
                  const SizedBox(height: CompanionSpacing.sectionGap),
              itemBuilder: (context, index) {
                final collection = collections[index];
                return _buildCollectionSection(context, collection);
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCollectionSection(
    BuildContext context,
    WatchFaceCollection collection,
  ) {
    const previewSize = 140.0;
    const labelStyle = TextStyle(
      color: IosColors.label,
      fontSize: 13,
      fontWeight: FontWeight.w600,
    );
    var labelHeight = 0.0;
    for (final face in collection.faces) {
      final painter = TextPainter(
        text: TextSpan(
          text: face.title,
          style: DefaultTextStyle.of(context).style.merge(labelStyle),
        ),
        textDirection: Directionality.of(context),
        textScaler: MediaQuery.textScalerOf(context),
      )..layout(maxWidth: previewSize);
      if (painter.height > labelHeight) labelHeight = painter.height;
      painter.dispose();
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                collection.name.toUpperCase(),
                style: const TextStyle(
                  color: IosColors.label,
                  fontSize: 17,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.2,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                collection.description,
                style: const TextStyle(
                  color: IosColors.secondaryLabel,
                  fontSize: 13,
                  letterSpacing: -0.1,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: CompanionSpacing.contentGap),
        SizedBox(
          height:
              previewSize * 1.22 + CompanionSpacing.contentGap + labelHeight,
          child: ListView.separated(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            scrollDirection: Axis.horizontal,
            itemCount: collection.faces.length,
            separatorBuilder: (context, index) => const SizedBox(width: 16),
            itemBuilder: (context, index) {
              final face = collection.faces[index];
              return _buildFaceCard(context, face);
            },
          ),
        ),
      ],
    );
  }

  Widget _buildFaceCard(BuildContext context, WatchFace face) {
    return GestureDetector(
      onTap: () {
        Navigator.of(context).push(
          CupertinoPageRoute(
            builder: (_) =>
                FaceCustomizerScreen(face: face, isNewAddition: true),
          ),
        );
      },
      child: SizedBox(
        width: 140,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // The miniature is watch artwork; its external label remains scalable.
            MediaQuery.withNoTextScaling(
              child: WatchFaceView(face: face, size: 140, showLiveTime: false),
            ),
            const SizedBox(height: CompanionSpacing.contentGap),
            Text(
              face.title,
              style: const TextStyle(
                color: IosColors.label,
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _handleImportWatchFace(BuildContext context, WatchProvider provider) {
    Navigator.of(context).push(
      CupertinoPageRoute(
        builder: (_) => const NativeFacesScreen(importOnOpen: true),
      ),
    );
  }
}
