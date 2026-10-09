import 'dart:math' as math;
import 'dart:typed_data';
import 'package:flutter/cupertino.dart';
import '../../controllers/native_face_controller.dart';
import '../../l10n/strings.dart';
import '../../services/native_face_gallery.dart';
import '../../services/native_face_options.dart';
import '../../services/native_watch_face_import.dart';
import '../../theme/ios_colors.dart';
import 'native_face_presentation.dart';

/// Review is read-only; one explicit ADD retains the captured pair/epoch.
class NativeFaceImportScreen extends StatefulWidget {
  final NativeWatchFaceImport package;
  final NativeFaceController controller;
  final String pair, epoch;
  const NativeFaceImportScreen({
    super.key,
    required this.package,
    required this.controller,
    required this.pair,
    required this.epoch,
  });

  @override
  State<NativeFaceImportScreen> createState() => _NativeFaceImportScreenState();
}

class _NativeFaceImportScreenState extends State<NativeFaceImportScreen> {
  late final Uint8List? _preview = widget.package.preview;
  bool _adding = false, _unresolved = false, _applied = false;
  bool _resultIsRefresh = false;
  NativeFaceResult _result = NativeFaceResult.idle;

  bool get _sameTarget =>
      widget.controller.collection.pair == widget.pair &&
      widget.controller.collection.epoch == widget.epoch;

  String get _title {
    final configuration = widget.package.configuration;
    final bundle = configuration['bundle id'];
    final known = bundle is String ? NativeFaceOptions.title(bundle) : null;
    if (known != null) return known;
    final profile = NativeFaceGallery.profile(widget.package.family);
    if (profile != null) return profile.title(Strings.current.localeName);
    for (final key in ['name', 'analytics id', 'face type']) {
      final value = configuration[key];
      if (value is String && value.trim().isNotEmpty) return value;
    }
    return widget.package.family.split(':').last.split('.').last;
  }

  Future<void> _add() async {
    final controller = widget.controller;
    if (_adding ||
        _unresolved ||
        _applied ||
        !_sameTarget ||
        !controller.canMutate) {
      return;
    }
    setState(() {
      _adding = true;
      _result = NativeFaceResult.waiting;
      _resultIsRefresh = false;
    });
    try {
      final prepared = await widget.package.prepareForAddition();
      // Archive work may outlive the captured connection or this review route.
      if (!mounted) return;
      if (!_sameTarget || !controller.canMutate) {
        setState(() {
          _adding = false;
          _result = NativeFaceResult.connectionChanged;
        });
        return;
      }
      final applied = await controller.import(prepared);
      if (!mounted) return;
      setState(() {
        _adding = false;
        _result = controller.result;
        _applied = applied;
        // A failed baseline READ did not submit ADD; it may be retried safely.
        _resultIsRefresh = !applied && controller.lastOperationWasRefresh;
        _unresolved =
            !_resultIsRefresh &&
            (_result == NativeFaceResult.unknown ||
                _result == NativeFaceResult.connectionChanged);
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _adding = false;
        _result = NativeFaceResult.rejected;
      });
      await showFaceFileError(context, error);
    }
  }

  Widget _missingPreview() => Padding(
    padding: const EdgeInsets.all(24),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(
          CupertinoIcons.clock,
          size: 48,
          color: IosColors.secondaryLabel,
        ),
        const SizedBox(height: 12),
        Text(
          Strings.current.nativeFaceSharedPreviewUnavailable,
          textAlign: TextAlign.center,
        ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final controller = widget.controller;
      final status = !_sameTarget
          ? Strings.current.nativeFaceConflict
          : nativeFaceStatus(
              _resultIsRefresh ? controller.result : _result,
              refresh: _resultIsRefresh,
            );
      return CupertinoPageScaffold(
        backgroundColor: IosColors.systemBackground,
        navigationBar: CupertinoNavigationBar(
          middle: Text(Strings.current.nativeFaceImport),
        ),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(
                _title,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 16),
              Center(
                child: _preview == null
                    ? _missingPreview()
                    : SizedBox(
                        height: 240,
                        child: Image.memory(
                          _preview,
                          fit: BoxFit.contain,
                          cacheWidth: math.min(
                            ByteData.sublistView(_preview).getUint32(16),
                            (240 *
                                    MediaQuery.devicePixelRatioOf(
                                      context,
                                    ).clamp(1.0, 3.0))
                                .round(),
                          ),
                          semanticLabel:
                              Strings.current.nativeFaceSharedPreview,
                          errorBuilder: (_, _, _) =>
                              SingleChildScrollView(child: _missingPreview()),
                        ),
                      ),
              ),
              const SizedBox(height: 12),
              Text(
                Strings.current.nativeFaceSharedPreviewHint,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: IosColors.secondaryLabel,
                  fontSize: 13,
                ),
              ),
              const SizedBox(height: 24),
              CupertinoButton.filled(
                onPressed: _applied
                    ? () => Navigator.of(context).pop(true)
                    : controller.canMutate &&
                          _sameTarget &&
                          !_adding &&
                          !_unresolved
                    ? _add
                    : null,
                child: _adding
                    ? const CupertinoActivityIndicator()
                    : Text(
                        _applied
                            ? Strings.current.nativeFaceImportDone
                            : Strings.current.nativeFaceAddToWatch,
                      ),
              ),
              if (status.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text(status, textAlign: TextAlign.center),
              ],
              if (!_applied && !_adding && _result != NativeFaceResult.idle)
                CupertinoButton(
                  onPressed:
                      _sameTarget &&
                          controller.collection.connected &&
                          !controller.busy
                      ? controller.refresh
                      : null,
                  child: Text(Strings.current.refreshFromWatch),
                ),
            ],
          ),
        ),
      );
    },
  );
}
