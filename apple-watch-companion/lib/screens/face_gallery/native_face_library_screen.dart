import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/semantics.dart';
import '../../controllers/native_face_controller.dart';
import '../../l10n/strings.dart';
import '../../models/native_face_collection.dart';
import '../../services/native_face_options.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_tile.dart';
import '../../widgets/native_face_status.dart';
import 'native_face_preview.dart';

/// My Faces' Edit sheet shares the controller that owns all native writes.
class NativeFaceLibraryScreen extends StatefulWidget {
  final NativeFaceController controller;
  final String Function(NativeWatchFace) faceTitle;
  final void Function(NativeWatchFace) openFace;
  const NativeFaceLibraryScreen({
    super.key,
    required this.controller,
    required this.faceTitle,
    required this.openFace,
  });

  @override
  State<NativeFaceLibraryScreen> createState() =>
      _NativeFaceLibraryScreenState();
}

class _NativeFaceLibraryScreenState extends State<NativeFaceLibraryScreen> {
  List<String>? _pendingOrder;
  String? _pendingPair;
  String? _pendingEpoch;
  NativeFaceCollection? _dragBaseline;

  Future<void> _reorder(int from, int to) async {
    final controller = widget.controller;
    final collection = controller.collection;
    if (!controller.canMutate || _pendingOrder != null) return;
    final baseline = _dragBaseline;
    _dragBaseline = null;
    if (baseline == null ||
        baseline.pair != collection.pair ||
        baseline.epoch != collection.epoch ||
        !listEquals(baseline.ordered, collection.ordered)) {
      return;
    }
    final order = [...collection.ordered];
    if (from < 0 || from >= order.length || to < 0 || to >= order.length) {
      return;
    }
    if (from == to) return;
    final id = order.removeAt(from);
    order.insert(to, id);
    setState(() {
      _pendingOrder = order;
      _pendingPair = collection.pair;
      _pendingEpoch = collection.epoch;
    });
    try {
      // The controller requires a fresh matching native order after submission.
      // A local drag is only a pending view; it is never native confirmation.
      await controller.reorder(order);
    } finally {
      if (mounted) setState(() => _pendingOrder = null);
    }
  }

  Future<void> _delete(String id) async {
    final controller = widget.controller;
    final original = controller.collection;
    final face = original.face(id);
    if (!controller.canMutate || face == null || original.ordered.length < 2) {
      return;
    }
    final accepted = await showCupertinoDialog<bool>(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: Text(Strings.current.nativeFaceDeleteQuestion),
        content: Text(Strings.current.nativeFaceDeleteDetail),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(context, false),
            child: Text(Strings.current.cancel),
          ),
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () => Navigator.pop(context, true),
            child: Text(Strings.current.nativeFaceDelete),
          ),
        ],
      ),
    );
    final latest = controller.collection;
    if (accepted == true &&
        mounted &&
        controller.canMutate &&
        latest.pair == original.pair &&
        latest.epoch == original.epoch &&
        latest.face(id)?.configurationJson == face.configurationJson &&
        latest.ordered.length > 1) {
      await controller.remove(id);
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final controller = widget.controller;
      final collection = controller.collection;
      // A reconnect/conflict cannot expose a pending order from another pair.
      final pending = _pendingOrder;
      final order =
          pending != null &&
              collection.pair == _pendingPair &&
              collection.epoch == _pendingEpoch &&
              pending.toSet().containsAll(collection.displayOrder) &&
              pending.length == collection.displayOrder.length
          ? pending
          : collection.displayOrder;
      final canMutate = controller.canMutate && pending == null;
      final status = nativeFaceStatus(
        controller.result,
        refresh: controller.lastOperationWasRefresh,
      );
      return CupertinoPageScaffold(
        backgroundColor: IosColors.systemBackground,
        navigationBar: CupertinoNavigationBar(
          automaticallyImplyLeading: false,
          middle: Text(Strings.current.myFaces),
          trailing: CupertinoButton(
            padding: EdgeInsets.zero,
            onPressed: () => Navigator.pop(context),
            child: Text(Strings.current.nativeFaceLibraryDone),
          ),
        ),
        child: SafeArea(
          child: CustomScrollView(
            slivers: [
              if (!collection.known || !collection.complete)
                SliverToBoxAdapter(
                  child: IosListTile(
                    title: collection.known
                        ? Strings.current.partialCollectionReceived
                        : Strings.current.collectionNotReceivedYet,
                  ),
                ),
              SliverReorderableList(
                itemCount: order.length,
                onReorderStart: (_) =>
                    _dragBaseline = canMutate ? collection : null,
                onReorderItem: _reorder,
                proxyDecorator: (child, _, _) => child,
                itemBuilder: (context, index) {
                  final id = order[index];
                  final face = collection.face(id);
                  final title = face == null
                      ? Strings.current.face((index + 1).toString())
                      : widget.faceTitle(face);
                  final template = face == null
                      ? null
                      : NativeFaceOptions.profile(face);
                  return Container(
                    key: ValueKey(id),
                    decoration: const BoxDecoration(
                      color: IosColors.secondaryGroupedBackground,
                      border: Border(
                        bottom: BorderSide(
                          color: IosColors.separator,
                          width: .5,
                        ),
                      ),
                    ),
                    child: Row(
                      children: [
                        CupertinoButton(
                          padding: const EdgeInsets.symmetric(horizontal: 12),
                          onPressed:
                              canMutate && face != null && order.length > 1
                              ? () => _delete(id)
                              : null,
                          child: Semantics(
                            label:
                                '${Strings.current.nativeFaceDelete}: $title',
                            child: const Icon(
                              CupertinoIcons.minus_circle_fill,
                              color: IosColors.systemRed,
                            ),
                          ),
                        ),
                        Expanded(
                          child: IosListTile(
                            title: title,
                            leading: template == null
                                ? const Icon(CupertinoIcons.clock)
                                : NativeFacePreview(
                                    template: template,
                                    configuration: face!.configuration,
                                    fallbackToDefault: false,
                                  ),
                            subtitle: id == collection.selected
                                ? Strings.current.selectedOnWatch
                                : null,
                            onTap: face != null && pending == null
                                ? () => widget.openFace(face)
                                : null,
                          ),
                        ),
                        ReorderableDragStartListener(
                          index: index,
                          enabled: canMutate,
                          child: Semantics(
                            enabled: canMutate,
                            label: Strings.current.nativeFaceLibraryReorder(
                              title,
                            ),
                            customSemanticsActions: {
                              if (canMutate && index > 0)
                                CustomSemanticsAction(
                                  label: Strings.current.nativeFaceMoveEarlier,
                                ): () {
                                  _dragBaseline = collection;
                                  _reorder(index, index - 1);
                                },
                              if (canMutate && index < order.length - 1)
                                CustomSemanticsAction(
                                  label: Strings.current.nativeFaceMoveLater,
                                ): () {
                                  _dragBaseline = collection;
                                  _reorder(index, index + 1);
                                },
                            },
                            child: const ExcludeSemantics(
                              child: SizedBox(
                                width: 48,
                                height: 48,
                                child: Icon(
                                  CupertinoIcons.line_horizontal_3,
                                  color: IosColors.secondaryLabel,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
              if (status.isNotEmpty)
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      status,
                      style: const TextStyle(
                        color: IosColors.secondaryLabel,
                        fontSize: 13,
                      ),
                    ),
                  ),
                ),
              SliverToBoxAdapter(
                child: CupertinoButton(
                  onPressed: collection.connected && !controller.busy
                      ? controller.refresh
                      : null,
                  child: controller.busy
                      ? const CupertinoActivityIndicator()
                      : Text(Strings.current.refreshFromWatch),
                ),
              ),
            ],
          ),
        ),
      );
    },
  );
}
