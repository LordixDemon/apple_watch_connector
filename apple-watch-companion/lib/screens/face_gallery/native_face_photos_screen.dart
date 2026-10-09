import 'dart:ui' as ui;
import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../l10n/strings.dart';
import '../../providers/watch_provider.dart';
import '../../controllers/native_face_controller.dart';
import '../../models/native_face_collection.dart';
import '../../services/native_face_gallery.dart';
import '../../services/native_face_photos.dart';
import '../../services/native_photo_crop.dart';
import '../../services/watch_face_files.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_tile.dart';
import '../../widgets/native_photo_crop_view.dart';
import 'native_face_editor_screen.dart';
import 'native_face_presentation.dart';
import 'native_face_value_picker.dart';

/// Photo drafts own their decoded images and are tied to one connection epoch.
class NativeFacePhotosScreen extends StatefulWidget {
  final NativeFaceTemplate template;
  final String pair, epoch;
  final WatchFaceFiles? files;
  final NativeWatchFace? face;
  const NativeFacePhotosScreen({
    super.key,
    required this.template,
    required this.pair,
    required this.epoch,
    this.files,
    this.face,
  });
  @override
  State<NativeFacePhotosScreen> createState() => _NativeFacePhotosScreenState();
}

class _NativeFacePhotosScreenState extends State<NativeFacePhotosScreen> {
  ui.Image? _source;
  final _photos = <NativeFacePhoto>[];
  NativePhotoAlbum? _album;
  int? _editingPhoto;
  bool _changed = false;
  bool _layoutChanged = false;
  bool _processing = false;
  bool _unresolved = false, _resultIsRefresh = false;
  NativeFaceResult _result = NativeFaceResult.idle;
  bool _allowLeave = false, _askingToLeave = false;
  final _crop = ValueNotifier(const NativePhotoCrop());
  int _alignment = 0, _scale = 1;
  @override
  void initState() {
    super.initState();
    if (widget.face != null) {
      _processing = true;
      WidgetsBinding.instance.addPostFrameCallback((_) => _loadAlbum());
    }
  }

  Future<void> _loadAlbum() async {
    if (!mounted) return;
    try {
      final c = context.read<WatchProvider>().nativeFaces;
      final bytes = await c.export(widget.face!.id);
      if (bytes == null) {
        throw FormatException(
          Strings.current.nativeFaceResourceExportUnavailable,
        );
      }
      final album = await NativeFacePhotos.openPackage(bytes);
      if (!mounted ||
          !_sameConnection ||
          !NativeFaceController.structurallyEqual(
            jsonDecode(album.configurationJson),
            widget.face!.configuration,
          )) {
        return;
      }
      setState(() {
        _album = album;
        _photos.addAll(album.photos);
        final preferred =
            (album.manifest['imageList'] as List).first['preferredTimeLayout'];
        if (preferred is Map) {
          if (preferred['alignment'] is int &&
              preferred['alignment'] >= 0 &&
              preferred['alignment'] <= 1) {
            _alignment = preferred['alignment'] as int;
          }
          if (preferred['scale'] is int &&
              preferred['scale'] >= 0 &&
              preferred['scale'] <= 3) {
            _scale = preferred['scale'] as int;
          }
        }
      });
    } catch (error) {
      if (mounted) await showFaceFileError(context, error);
    } finally {
      if (mounted) setState(() => _processing = false);
    }
  }

  @override
  void dispose() {
    _source?.dispose();
    _crop.dispose();
    super.dispose();
  }

  Future<void> _leave() async {
    if (_processing || _askingToLeave) return;
    _askingToLeave = true;
    try {
      final discard = await showCupertinoDialog<bool>(
        context: context,
        builder: (ctx) => CupertinoAlertDialog(
          title: Text(Strings.current.nativeFaceUnsaved),
          actions: [
            CupertinoDialogAction(
              onPressed: () => Navigator.pop(ctx, false),
              child: Text(Strings.current.cancel),
            ),
            CupertinoDialogAction(
              isDestructiveAction: true,
              onPressed: () => Navigator.pop(ctx, true),
              child: Text(Strings.current.nativeFaceDiscard),
            ),
          ],
        ),
      );
      if (discard == true && mounted) {
        setState(() => _allowLeave = true);
        await WidgetsBinding.instance.endOfFrame;
        if (mounted) await Navigator.of(context).maybePop();
      }
    } finally {
      _askingToLeave = false;
    }
  }

  bool get _sameConnection {
    final c = context.read<WatchProvider>().nativeFaces;
    return c.collection.pair == widget.pair &&
        c.collection.epoch == widget.epoch &&
        (widget.face == null ||
            c.collection.face(widget.face!.id)?.configurationJson ==
                widget.face!.configurationJson) &&
        c.canMutate;
  }

  Future<void> _pick() async {
    if (_processing) return;
    setState(() => _processing = true);
    try {
      final bytes = await (widget.files ?? WatchFaceFiles()).openPhoto();
      if (bytes == null || !mounted) return;
      final source = await NativeFacePhotos.decode(bytes);
      if (!mounted) {
        source.dispose();
        return;
      }
      final old = _source;
      setState(() {
        _source = source;
        _editingPhoto = null;
        _crop.value = NativePhotoCrop(aspect: _crop.value.aspect);
      });
      // Let the previous paint complete before releasing its image.
      await WidgetsBinding.instance.endOfFrame;
      old?.dispose();
    } catch (error) {
      if (mounted) await showFaceFileError(context, error);
    } finally {
      if (mounted) setState(() => _processing = false);
    }
  }

  Future<void> _keepCrop() async {
    if (_processing ||
        _source == null ||
        _editingPhoto == null && _photos.length >= NativeFacePhotos.maxPhotos) {
      return;
    }
    setState(() => _processing = true);
    try {
      final photo = await NativeFacePhotos.render(
        _source!,
        _crop.value.rect(_source!.width, _source!.height),
      );
      if (_photos.fold<int>(0, (sum, p) => sum + p.jpeg.length) -
              (_editingPhoto == null
                  ? 0
                  : _photos[_editingPhoto!].jpeg.length) +
              photo.jpeg.length >
          NativeFacePhotos.maxTotalPhotoBytes) {
        throw const FormatException('Selected photos exceed 12 MiB.');
      }
      if (mounted && _sameConnection) {
        final old = _source;
        setState(() {
          if (_editingPhoto == null) {
            _photos.add(photo);
          } else {
            _photos[_editingPhoto!] = photo;
          }
          _changed = true;
          _source = null;
          _editingPhoto = null;
        });
        await WidgetsBinding.instance.endOfFrame;
        old?.dispose();
      }
    } catch (error) {
      if (mounted) await showFaceFileError(context, error);
    } finally {
      if (mounted) setState(() => _processing = false);
    }
  }

  Future<void> _editPhoto(int index) async {
    if (_processing) return;
    setState(() => _processing = true);
    try {
      final source = await NativeFacePhotos.decode(_photos[index].jpeg);
      if (!mounted) {
        source.dispose();
        return;
      }
      final old = _source;
      setState(() {
        _source = source;
        _editingPhoto = index;
        _crop.value = NativePhotoCrop(
          aspect: (source.width / source.height).clamp(.65, 1.0),
        );
      });
      await WidgetsBinding.instance.endOfFrame;
      old?.dispose();
    } catch (error) {
      if (mounted) await showFaceFileError(context, error);
    } finally {
      if (mounted) setState(() => _processing = false);
    }
  }

  void _movePhoto(int index, int offset) {
    setState(() {
      final photo = _photos.removeAt(index);
      _photos.insert(index + offset, photo);
      _changed = true;
    });
  }

  Future<void> _install() async {
    if (_processing || _unresolved || !_sameConnection) return;
    if (widget.face != null && (_album == null || !_changed)) return;
    if (_photos.isEmpty && _source != null) await _keepCrop();
    if (!mounted || _photos.isEmpty || !_sameConnection) return;
    setState(() {
      _processing = true;
      _result = NativeFaceResult.waiting;
      _resultIsRefresh = false;
    });
    try {
      final package = _album != null
          ? await NativeFacePhotos.editPackage(
              _album!,
              _photos,
              configurationJson: widget.face!.configurationJson!,
              changeTimeLayout: _layoutChanged,
              alignment: _alignment,
              scale: _scale,
            )
          : await NativeFacePhotos.package(
              widget.template,
              _photos,
              alignment: _alignment,
              scale: _scale,
            );
      if (!mounted || !_sameConnection) return;
      final c = context.read<WatchProvider>().nativeFaces;
      final applied = widget.face != null
          ? await c.replaceResources(
              widget.face!.id,
              package,
              originalJson: widget.face!.configurationJson!,
              originalArchiveHash: _album!.archiveHash,
              pair: widget.pair,
              epoch: widget.epoch,
            )
          : await c.import(package);
      if (!mounted) return;
      setState(() {
        _result = c.result;
        // An UNKNOWN preflight read never submitted the photo mutation.
        _resultIsRefresh = !applied && c.lastOperationWasRefresh;
        _unresolved =
            !_resultIsRefresh &&
            (_result == NativeFaceResult.unknown ||
                _result == NativeFaceResult.connectionChanged);
      });
      if (!applied) return;
      if (widget.face != null) {
        setState(() => _allowLeave = true);
        await WidgetsBinding.instance.endOfFrame;
        if (mounted) Navigator.of(context).pop(true);
        return;
      }
      final face = c.collection.face(c.collection.selected ?? '');
      if (face == null) return;
      await Navigator.of(context).pushReplacement(
        CupertinoPageRoute(
          builder: (_) => NativeFaceEditorScreen(
            face: face,
            pair: widget.pair,
            epoch: widget.epoch,
          ),
        ),
      );
    } catch (error) {
      if (mounted) {
        setState(() => _result = NativeFaceResult.rejected);
        await showFaceFileError(context, error);
      }
    } finally {
      if (mounted) setState(() => _processing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.select<WatchProvider, NativeFaceController>(
      (provider) => provider.nativeFaces,
    );
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) => _buildAlbum(context, c),
    );
  }

  Widget _buildAlbum(BuildContext context, NativeFaceController c) {
    final enabled =
        !_processing &&
        _sameConnection &&
        (widget.face == null || _album != null);
    final s = Strings.current;
    final source = _source;
    return PopScope<Object?>(
      canPop: !_processing && (_allowLeave || _source == null && !_changed),
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _leave();
      },
      child: CupertinoPageScaffold(
        backgroundColor: IosColors.systemBackground,
        navigationBar: CupertinoNavigationBar(middle: Text(s.photos)),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (!enabled && !_processing) Text(s.nativeFaceConflict),
              CupertinoButton.filled(
                onPressed: enabled ? _pick : null,
                child: Text(s.nativePhotoChoose),
              ),
              if (source != null) ...[
                const SizedBox(height: 16),
                Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 250),
                    child: NativePhotoCropView(
                      source: source,
                      controller: _crop,
                      enabled: enabled,
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  s.nativePhotoCropHint,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: IosColors.secondaryLabel),
                ),
                ValueListenableBuilder<NativePhotoCrop>(
                  valueListenable: _crop,
                  builder: (_, crop, _) => Column(
                    children: [
                      Semantics(
                        label: s.nativePhotoZoom,
                        child: CupertinoSlider(
                          value: crop.zoom,
                          min: 1,
                          max: 4,
                          onChanged: enabled
                              ? (v) => _crop.value = crop.copyWith(zoom: v)
                              : null,
                        ),
                      ),
                      Text(s.nativePhotoAspect),
                      Semantics(
                        label: s.nativePhotoAspect,
                        child: CupertinoSlider(
                          value: crop.aspect,
                          min: .65,
                          max: 1,
                          onChanged: enabled
                              ? (v) => _crop.value = crop.copyWith(aspect: v)
                              : null,
                        ),
                      ),
                    ],
                  ),
                ),
                CupertinoButton(
                  onPressed:
                      !_processing &&
                          (_editingPhoto != null ||
                              _photos.length < NativeFacePhotos.maxPhotos)
                      ? _keepCrop
                      : null,
                  child: Text(s.nativePhotoKeepCrop),
                ),
              ],
              if (_photos.isNotEmpty) ...[
                Text(s.nativePhotoSelected(_photos.length)),
                const SizedBox(height: 8),
                SizedBox(
                  height: 112,
                  child: ListView.builder(
                    scrollDirection: Axis.horizontal,
                    itemCount: _photos.length,
                    itemBuilder: (_, i) => Padding(
                      padding: const EdgeInsets.only(right: 12),
                      child: Column(
                        children: [
                          GestureDetector(
                            onTap: !_processing && _source == null
                                ? () => _editPhoto(i)
                                : null,
                            child: Image.memory(
                              _photos[i].jpeg,
                              width: 60,
                              height: 72,
                              fit: BoxFit.cover,
                              cacheWidth: 180,
                            ),
                          ),
                          Row(
                            children: [
                              for (final offset in [-1, 1])
                                CupertinoButton(
                                  sizeStyle: CupertinoButtonSize.small,
                                  padding: EdgeInsets.zero,
                                  onPressed:
                                      !_processing &&
                                          _source == null &&
                                          i + offset >= 0 &&
                                          i + offset < _photos.length
                                      ? () => _movePhoto(i, offset)
                                      : null,
                                  child: Semantics(
                                    label: offset < 0
                                        ? s.nativeFaceMoveEarlier
                                        : s.nativeFaceMoveLater,
                                    child: Icon(
                                      offset < 0
                                          ? CupertinoIcons.chevron_left
                                          : CupertinoIcons.chevron_right,
                                      size: 16,
                                    ),
                                  ),
                                ),
                              CupertinoButton(
                                sizeStyle: CupertinoButtonSize.small,
                                padding: EdgeInsets.zero,
                                onPressed: !_processing && _source == null
                                    ? () => setState(() {
                                        _photos.removeAt(i);
                                        _changed = true;
                                      })
                                    : null,
                                child: Semantics(
                                  label: s.nativePhotoRemove,
                                  child: const Icon(
                                    CupertinoIcons.delete,
                                    size: 16,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 12),
              Text(s.nativePhotoTimeAlignment),
              const SizedBox(height: 8),
              CupertinoSlidingSegmentedControl<int>(
                groupValue: _alignment,
                children: {
                  0: Text(s.nativePhotoLeading),
                  1: Text(s.nativePhotoTrailing),
                },
                onValueChanged: (v) {
                  if (!_processing) {
                    if (v != null && v != _alignment) {
                      setState(() {
                        _alignment = v;
                        _changed = _layoutChanged = true;
                      });
                    }
                  }
                },
              ),
              const SizedBox(height: 12),
              Text(s.nativePhotoTimeSize),
              const SizedBox(height: 8),
              IosListTile(
                title: [
                  s.nativePhotoSmall,
                  s.nativePhotoMedium,
                  s.nativePhotoLarge,
                  s.nativePhotoXLarge,
                ][_scale],
                onTap: !_processing
                    ? () async {
                        final values = [
                          s.nativePhotoSmall,
                          s.nativePhotoMedium,
                          s.nativePhotoLarge,
                          s.nativePhotoXLarge,
                        ];
                        final chosen = await Navigator.of(context).push<String>(
                          CupertinoPageRoute(
                            builder: (_) => NativeFaceValuePicker(
                              title: s.nativePhotoTimeSize,
                              selected: '$_scale',
                              values: const ['0', '1', '2', '3'],
                              label: (key) => values[int.parse(key)],
                            ),
                          ),
                        );
                        if (mounted &&
                            chosen != null &&
                            _scale != int.parse(chosen)) {
                          setState(() {
                            _scale = int.parse(chosen);
                            _changed = _layoutChanged = true;
                          });
                        }
                      }
                    : null,
              ),
              const SizedBox(height: 20),
              if (_processing || c.busy) const CupertinoActivityIndicator(),
              Text(
                nativeFaceStatus(
                  _resultIsRefresh ? c.result : _result,
                  refresh: _resultIsRefresh,
                ),
                textAlign: TextAlign.center,
              ),
              CupertinoButton.filled(
                onPressed:
                    enabled &&
                        !_unresolved &&
                        (widget.face == null
                            ? source != null || _photos.isNotEmpty
                            : _changed && _photos.isNotEmpty)
                    ? _install
                    : null,
                child: Text(
                  widget.face == null
                      ? s.nativeFaceAddToWatch
                      : s.nativeFaceApply,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
