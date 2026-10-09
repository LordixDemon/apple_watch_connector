import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../controllers/native_face_controller.dart';
import '../../l10n/strings.dart';
import '../../providers/watch_provider.dart';
import '../../services/watch_face_files.dart';
import '../../services/native_face_gallery.dart';
import '../../services/native_watch_face_import.dart';
import '../../services/native_face_options.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import 'native_face_editor_screen.dart';
import 'native_face_templates_screen.dart';
import 'native_face_presentation.dart';
import 'native_face_import_screen.dart';
import 'native_face_preview.dart';

class NativeFacesScreen extends StatefulWidget {
  final bool importOnOpen;
  final bool gallery;
  final VoidCallback? localDesigns;
  const NativeFacesScreen({
    super.key,
    this.importOnOpen = false,
    this.gallery = false,
    this.localDesigns,
  });
  @override
  State<NativeFacesScreen> createState() => _NativeFacesScreenState();
}

class _NativeFacesScreenState extends State<NativeFacesScreen> {
  NativeFaceController? _controller;
  String? _autoRefreshedEpoch;
  bool _refreshScheduled = false;
  bool _openingImport = false;

  void _collectionChanged() {
    if (_refreshScheduled || !mounted) return;
    _refreshScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _refreshScheduled = false;
      if (mounted) unawaited(_refreshIfNeeded());
    });
  }

  Future<void> _refreshIfNeeded() async {
    final controller = _controller;
    if (!mounted || controller == null) return;
    final collection = controller.collection;
    if (collection.connected &&
        collection.epoch != null &&
        _autoRefreshedEpoch != collection.epoch &&
        !controller.busy &&
        (!collection.complete ||
            DateTime.now().millisecondsSinceEpoch -
                    (collection.observedAt ?? 0) >
                240000)) {
      _autoRefreshedEpoch = collection.epoch;
      await controller.refresh();
    }
  }

  @override
  void dispose() {
    _controller?.removeListener(_collectionChanged);
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    unawaited(_loadProfiles());
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final controller = context.read<WatchProvider>().nativeFaces;
      _controller = controller;
      controller.addListener(_collectionChanged);
      await _refreshIfNeeded();
      if (mounted && widget.importOnOpen && controller.canMutate) {
        unawaited(_import());
      }
    });
  }

  Future<void> _loadProfiles() async {
    try {
      await NativeFaceGallery.load();
      if (mounted) setState(() {});
    } on Exception {
      // Observed native identifiers/configurations remain usable without labels.
    }
  }

  Future<void> _import() async {
    final controller = context.read<WatchProvider>().nativeFaces;
    if (_openingImport || !controller.canMutate) return;
    setState(() => _openingImport = true);
    final pair = controller.collection.pair,
        epoch = controller.collection.epoch;
    try {
      final bytes = await WatchFaceFiles().open();
      if (bytes == null || !mounted) return;
      final package = await NativeWatchFaceImport.inspect(bytes);
      if (!mounted) return;
      if (controller.collection.pair != pair ||
          controller.collection.epoch != epoch ||
          !controller.canMutate) {
        throw FormatException(Strings.current.nativeFaceConflict);
      }
      await Navigator.of(context).push<bool>(
        CupertinoPageRoute(
          builder: (_) => NativeFaceImportScreen(
            package: package,
            controller: controller,
            pair: pair!,
            epoch: epoch!,
          ),
        ),
      );
    } catch (error) {
      if (mounted) await showFaceFileError(context, error);
    } finally {
      if (mounted) setState(() => _openingImport = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.select<WatchProvider, NativeFaceController>(
      (provider) => provider.nativeFaces,
    );
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => _buildCollection(context, controller),
    );
  }

  Widget _buildCollection(
    BuildContext context,
    NativeFaceController controller,
  ) {
    final collection = controller.collection;
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        middle: Text(
          widget.gallery
              ? Strings.current.faceGallery
              : Strings.current.manageWatchFaces,
        ),
        trailing: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: controller.canMutate && !_openingImport ? _import : null,
          child: _openingImport
              ? const CupertinoActivityIndicator()
              : const Icon(CupertinoIcons.arrow_down_doc),
        ),
      ),
      child: SafeArea(
        child: ListView(
          children: [
            IosListSection(
              header: Strings.current.myFaces,
              footer: nativeFaceStatus(
                controller.result,
                refresh: controller.lastOperationWasRefresh,
              ),
              children: [
                if (!collection.known)
                  IosListTile(title: Strings.current.collectionNotReceivedYet),
                if (collection.known && !collection.complete)
                  IosListTile(title: Strings.current.partialCollectionReceived),
                for (final id in collection.displayOrder)
                  if (collection.face(id) case final face?)
                    IosListTile(
                      title: nativeFaceTitle(face),
                      leading: switch (NativeFaceOptions.profile(face)) {
                        final template? => NativeFacePreview(
                          template: template,
                          configuration: face.configuration,
                          fallbackToDefault: false,
                        ),
                        _ => const Icon(CupertinoIcons.clock),
                      },
                      subtitle: nativeFaceSummary(face),
                      trailingText: id == collection.selected
                          ? Strings.current.selectedOnWatch
                          : null,
                      onTap: () => Navigator.of(context).push(
                        CupertinoPageRoute(
                          builder: (_) => NativeFaceEditorScreen(
                            face: face,
                            pair: collection.pair!,
                            epoch: collection.epoch!,
                          ),
                        ),
                      ),
                    ),
                CupertinoButton(
                  onPressed: collection.connected && !controller.busy
                      ? controller.refresh
                      : null,
                  child: controller.busy
                      ? const CupertinoActivityIndicator()
                      : Text(Strings.current.refreshFromWatch),
                ),
              ],
            ),
            IosListSection(
              children: [
                IosListTile(
                  title: Strings.current.nativeFaceAdd,
                  icon: CupertinoIcons.add,
                  onTap: () => Navigator.of(context).push(
                    CupertinoPageRoute(
                      builder: (_) => const NativeFaceTemplatesScreen(),
                    ),
                  ),
                ),
                IosListTile(
                  title: Strings.current.nativeFaceImport,
                  icon: CupertinoIcons.arrow_down_doc,
                  onTap: controller.canMutate && !_openingImport
                      ? _import
                      : null,
                ),
                if (collection
                        .face(collection.selected ?? '')
                        ?.canCopyConfiguration ==
                    true)
                  IosListTile(
                    title: Strings.current.createCopy,
                    icon: CupertinoIcons.add,
                    onTap: controller.canMutate
                        ? () async {
                            if (!await controller.duplicate(
                                  collection.selected!,
                                ) ||
                                !context.mounted) {
                              return;
                            }
                            final updated = controller.collection;
                            final face = updated.face(updated.selected ?? '');
                            if (face == null) return;
                            await Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => NativeFaceEditorScreen(
                                  face: face,
                                  pair: updated.pair!,
                                  epoch: updated.epoch!,
                                ),
                              ),
                            );
                          }
                        : null,
                  ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Text(
                Strings.current.nativeFaceCopyHint,
                style: const TextStyle(
                  color: IosColors.secondaryLabel,
                  fontSize: 13,
                ),
              ),
            ),
            if (widget.localDesigns != null)
              IosListSection(
                children: [
                  IosListTile(
                    title: Strings.current.nativeFaceLocalLibrary,
                    onTap: widget.localDesigns,
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}
