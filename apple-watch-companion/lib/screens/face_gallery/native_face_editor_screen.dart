import 'dart:async';
import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../l10n/strings.dart';
import '../../models/native_face_collection.dart';
import '../../controllers/native_face_controller.dart';
import '../../controllers/native_face_transient_preview.dart';
import '../../controllers/native_face_autosave_controller.dart';
import '../../providers/watch_provider.dart';
import '../../services/watch_face_files.dart';
import '../../services/native_watch_face_archive.dart';
import '../../services/native_complication_choices.dart';
import '../../services/native_complication_presentation.dart';
import '../../services/native_intent_parameters.dart';
import '../../services/native_intent_variants.dart';
import '../../services/native_face_options.dart';
import '../../services/native_face_gallery.dart';
import '../../services/native_face_controls.dart';
import '../../services/native_face_edit_metadata.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import 'native_face_presentation.dart';
import 'native_face_value_picker.dart';
import 'native_face_option_section.dart';
import 'native_face_monogram_section.dart';
import 'native_monogram_editor_screen.dart';
import '../../controllers/native_monogram_sync_controller.dart';
import 'native_face_pigment_section.dart';
import 'native_face_photos_screen.dart';
import 'native_face_preview.dart';
import 'native_complication_settings_screen.dart';

/// Edits the actual NTK dictionary; opaque descriptors and intents survive every save.
class NativeFaceEditorScreen extends StatefulWidget {
  final NativeWatchFace face;
  final String pair, epoch;
  const NativeFaceEditorScreen({
    super.key,
    required this.face,
    required this.pair,
    required this.epoch,
  });
  @override
  State<NativeFaceEditorScreen> createState() => _NativeFaceEditorScreenState();
}

class _NativeFaceEditorScreenState extends State<NativeFaceEditorScreen> {
  final _preview = NativeFaceTransientPreview();
  late Map<String, dynamic>? _config = widget.face.configuration;
  late String? _original = widget.face.configurationJson;
  final _json = TextEditingController();
  bool _dirty = false;
  bool _localUnstaged = false;
  String? _staged;
  int _draftRevision = 0;
  bool _advanced = false;
  bool _leaving = false;
  final _parameters = NativeIntentParameterIndex();
  final _variants = NativeIntentVariantIndex();
  final _editMetadata = NativeFaceEditMetadata();

  NativeFaceAutosaveController get _autosave =>
      context.read<WatchProvider>().nativeFaceAutosave;

  String? _monogramStatus(MonogramSyncState state) => switch (state) {
    MonogramSyncState.idle => null,
    MonogramSyncState.awaitingDelivery =>
      Strings.current.nativeMonogramAwaiting,
    MonogramSyncState.delivered => Strings.current.nativeMonogramDelivered,
    MonogramSyncState.uncertain => Strings.current.nativeMonogramUncertain,
    MonogramSyncState.confirmed => Strings.current.nativeMonogramConfirmed,
    MonogramSyncState.storageError => Strings.current.nativeMonogramSaveError,
  };

  Future<void> _editMonogram() async {
    final watch = context.read<WatchProvider>();
    final baseline = watch.monogramMirror;
    if (baseline == null ||
        baseline.pair != widget.pair ||
        baseline.epoch != widget.epoch ||
        !baseline.canWrite(DateTime.now().millisecondsSinceEpoch)) {
      return;
    }
    await Navigator.of(context).push(
      CupertinoPageRoute<void>(
        fullscreenDialog: true,
        builder: (_) => NativeMonogramEditorScreen(
          initialText: watch.monogramSync.text ?? '',
          canSubmit: () =>
              mounted &&
              watch.monogramMirror != null &&
              baseline.sameBaseline(watch.monogramMirror!) &&
              baseline.canWrite(DateTime.now().millisecondsSinceEpoch),
          submit: (text) => watch.setNativeMonogram(text, baseline),
        ),
      ),
    );
  }

  bool _canEditMonogram(WatchProvider watch) {
    final baseline = watch.monogramMirror;
    return baseline != null &&
        baseline.pair == widget.pair &&
        baseline.epoch == widget.epoch &&
        baseline.canWrite(DateTime.now().millisecondsSinceEpoch);
  }

  bool _canEdit(NativeFaceController controller) =>
      _samePair(controller) &&
      _autosave.canEdit(widget.pair, widget.epoch, widget.face.id);

  String _draftJson() {
    final value = Map<String, dynamic>.from(_config!);
    if (value['complications'] is Map &&
        (value['complications'] as Map).isEmpty) {
      value.remove('complications');
    }
    return jsonEncode(value);
  }

  void _stage() {
    if (_config == null || _original == null) return;
    _draftRevision++;
    final expected = _staged ?? _original!;
    final normalized = Map<String, dynamic>.from(_config!);
    if (normalized['complications'] is Map &&
        (normalized['complications'] as Map).isEmpty) {
      normalized.remove('complications');
    }
    if (!NativeFaceController.structurallyEqual(
      jsonDecode(expected),
      normalized,
    )) {
      try {
        _editMetadata.markEdited(_config!);
      } on FormatException {
        _localUnstaged = true;
        return;
      }
    }
    final desired = _draftJson();
    _staged = desired;
    _localUnstaged = false;
    final autosave = _autosave;
    unawaited(() async {
      final saved = await autosave.stage(
        widget.pair,
        widget.epoch,
        widget.face.id,
        expected,
        desired,
      );
      if (!mounted || _staged != desired) return;
      if (!saved &&
          autosave.edit(widget.pair, widget.face.id)?.desired != desired) {
        setState(() {
          _staged = expected;
          _localUnstaged = true;
        });
      }
    }());
  }

  Future<void> _retry() async {
    if (_localUnstaged) {
      setState(_stage);
      return;
    }
    await _autosave.retry(widget.pair, widget.epoch, widget.face.id);
  }

  NativeIntentVariants? _variantsFor(
    String slot,
    Object? value,
    NativeFaceController controller,
  ) => _variants.bind(
    source: controller.collection,
    slot: slot,
    current: value,
    choices: () => NativeComplicationChoices.forFaceSlot(
      widget.face,
      slot,
      controller.collection,
      template: value,
    ),
  );

  NativeIntentParameters? _parametersFor(Object? value) =>
      value is Map && value['descriptor'] is Map
      ? _parameters.read(value['descriptor']['intent'])
      : null;

  String _slotTitle(String slot) =>
      NativeFaceOptions.slotTitle(slot, face: widget.face);
  bool _slotAvailable(String slot) => NativeFaceOptions.slotAvailable(
    slot,
    _config?['customization'],
    face: widget.face,
  );

  Future<void> _editParameters(
    Map<String, dynamic> section,
    String slot,
    NativeFaceController controller,
  ) async {
    if (!_slotAvailable(slot)) return;
    final value = section[slot];
    final parameters = _parametersFor(value);
    final variants = _variantsFor(slot, value, controller);
    if (value is! Map<String, dynamic> ||
        parameters == null && variants == null) {
      return;
    }
    final before = jsonEncode(value);
    final revision = _draftRevision;
    final edited = await Navigator.of(context).push<Map<String, dynamic>>(
      CupertinoPageRoute(
        builder: (_) => NativeComplicationSettingsScreen(
          title: Strings.current.nativeIntentSettings(_slotTitle(slot)),
          value: value,
          parameters: parameters,
          variants: variants,
        ),
      ),
    );
    if (!mounted ||
        edited == null ||
        !_canEdit(controller) ||
        !_slotAvailable(slot) ||
        revision != _draftRevision ||
        !_samePair(controller) ||
        jsonEncode(section[slot]) != before ||
        jsonEncode(edited) == before) {
      return;
    }
    setState(() {
      section[slot] = edited;
      _dirty = true;
      _stage();
    });
  }

  @override
  void initState() {
    super.initState();
    unawaited(_loadProfiles());
  }

  Future<void> _loadProfiles() async {
    try {
      await NativeFaceGallery.load();
      if (mounted) setState(() {});
    } on Exception {
      // The observed configuration and known bundle schema remain editable.
    }
  }

  Future<void> _leave() async {
    if (_leaving || !_dirty) return;
    if (!_localUnstaged &&
        _autosave.edit(widget.pair, widget.face.id) != null) {
      await showFaceFileError(
        context,
        FormatException(Strings.current.nativeFaceSaveFailed),
      );
      return;
    }
    _leaving = true;
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
      if (discard != true || !mounted) return;
      setState(() {
        _dirty = false;
        _localUnstaged = false;
      });
      await WidgetsBinding.instance.endOfFrame;
      if (mounted) await Navigator.of(context).maybePop();
    } finally {
      _leaving = false;
    }
  }

  @override
  void dispose() {
    _preview.dispose();
    _json.dispose();
    super.dispose();
  }

  bool _samePair(NativeFaceController controller) =>
      controller.collection.pair == widget.pair &&
      controller.collection.epoch == widget.epoch &&
      controller.collection.face(widget.face.id) != null;

  void _selectField(
    Map<String, dynamic> values,
    String field,
    String selected,
    NativeFaceController controller,
  ) {
    if (!mounted ||
        selected == values[field] ||
        !_canEdit(controller) ||
        !_samePair(controller)) {
      return;
    }
    final profile = NativeFaceOptions.profile(widget.face);
    if (profile?.options[field]?.contains(selected) != true) return;
    setState(() {
      profile!.selectOption(_config!, field, selected);
      _dirty = true;
      _stage();
    });
  }

  void _selectPigment(
    String field,
    String selected,
    NativeFaceController controller,
  ) {
    _preview.clear();
    final profile = NativeFaceOptions.profile(widget.face);
    if (!mounted ||
        !_canEdit(controller) ||
        !_samePair(controller) ||
        profile?.pigmentSection(field)?.accepts(selected) != true ||
        NativeFaceControl.read(_config!, ['customization', field]) ==
            selected) {
      return;
    }
    setState(() {
      profile!.selectPigment(_config!, field, selected);
      _dirty = true;
      _stage();
    });
  }

  Future<void> _choose(
    Map<String, dynamic> values,
    String field,
    NativeFaceController controller,
  ) async {
    final revision = _draftRevision;
    final choices = NativeFaceOptions.choices(
      widget.face,
      field,
      controller.collection,
    );
    final selected = choices.length > 16
        ? await Navigator.of(context).push<String>(
            CupertinoPageRoute(
              builder: (_) => NativeFaceValuePicker(
                title: NativeFaceOptions.fieldTitle(field, face: widget.face),
                selected: values[field].toString(),
                values: choices,
                label: (value) => NativeFaceOptions.valueTitle(
                  field,
                  value,
                  face: widget.face,
                ),
                preview: switch (NativeFaceOptions.profile(widget.face)) {
                  final template? => (value) => NativeFacePreview.option(
                    template,
                    _config,
                    field,
                    value,
                  ),
                  _ => null,
                },
              ),
            ),
          )
        : await showCupertinoModalPopup<String>(
            context: context,
            builder: (ctx) => CupertinoActionSheet(
              title: Text(
                NativeFaceOptions.fieldTitle(field, face: widget.face),
              ),
              actions: [
                for (final value in choices)
                  CupertinoActionSheetAction(
                    onPressed: () => Navigator.pop(ctx, value),
                    child: Text(
                      '${value == values[field] ? '✓ ' : ''}${NativeFaceOptions.valueTitle(field, value, face: widget.face)}',
                    ),
                  ),
              ],
              cancelButton: CupertinoActionSheetAction(
                onPressed: () => Navigator.pop(ctx),
                child: Text(Strings.current.cancel),
              ),
            ),
          );
    if (selected != null &&
        selected != values[field] &&
        mounted &&
        _canEdit(controller) &&
        revision == _draftRevision &&
        _samePair(controller)) {
      setState(() {
        final profile = NativeFaceOptions.profile(widget.face);
        if (profile?.options[field]?.contains(selected) == true) {
          profile!.selectOption(_config!, field, selected);
        } else {
          values[field] = selected;
        }
        _dirty = true;
        _stage();
      });
    }
  }

  Future<void> _chooseControl(NativeFaceControl control) async {
    final configuration = _config;
    if (configuration == null) return;
    final group = control.group(configuration);
    if (group == null) return;
    final revision = _draftRevision;
    final selected = await Navigator.of(context).push<String>(
      CupertinoPageRoute(
        builder: (_) => NativeFaceValuePicker(
          title: control.title(Strings.current.localeName),
          selected: control.value(configuration) ?? '',
          values: List.unmodifiable(group.labels.keys),
          label: (value) => group.title(value, Strings.current.localeName),
        ),
      ),
    );
    if (!mounted || selected == null) return;
    final controller = context.read<WatchProvider>().nativeFaces;
    if (!_canEdit(controller) || revision != _draftRevision) return;
    setState(() {
      if (control.select(configuration, selected)) {
        _dirty = true;
        _stage();
      }
    });
  }

  Future<void> _copy(NativeFaceController controller) async {
    if (!await controller.duplicate(widget.face.id) || !mounted) return;
    final collection = controller.collection;
    final face = collection.face(collection.selected ?? '');
    if (face == null || face.id == widget.face.id) return;
    Navigator.of(context).pushReplacement(
      CupertinoPageRoute(
        builder: (_) => NativeFaceEditorScreen(
          face: face,
          pair: collection.pair!,
          epoch: collection.epoch!,
        ),
      ),
    );
  }

  Future<void> _edit(Map<String, dynamic> section, String key) async {
    final revision = _draftRevision;
    final stringValue = section[key] is String;
    final input = TextEditingController(
      text: stringValue ? section[key] as String : jsonEncode(section[key]),
    );
    String? error;
    final accepted = await showCupertinoDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, update) => CupertinoAlertDialog(
          title: Text(key),
          content: Column(
            children: [
              if (!stringValue) Text(Strings.current.nativeFaceValueHint),
              const SizedBox(height: 8),
              CupertinoTextField(
                controller: input,
                maxLines: 4,
                style: const TextStyle(color: IosColors.label),
              ),
              if (error != null)
                Text(
                  error!,
                  style: const TextStyle(color: IosColors.systemRed),
                ),
            ],
          ),
          actions: [
            CupertinoDialogAction(
              onPressed: () => Navigator.pop(ctx, false),
              child: Text(Strings.current.cancel),
            ),
            CupertinoDialogAction(
              onPressed: () {
                try {
                  if (!stringValue) jsonDecode(input.text);
                  Navigator.pop(ctx, true);
                } on FormatException {
                  update(() {
                    error = Strings.current.nativeFaceInvalidValue;
                  });
                }
              },
              child: Text(Strings.current.save),
            ),
          ],
        ),
      ),
    );
    final text = input.text;
    // Dialog route transitions can still use the controller until their animation ends.
    await Future<void>.delayed(const Duration(milliseconds: 300));
    input.dispose();
    if (accepted == true &&
        mounted &&
        revision == _draftRevision &&
        _canEdit(context.read<WatchProvider>().nativeFaces)) {
      setState(() {
        section[key] = stringValue ? text : jsonDecode(text);
        _dirty = true;
        _stage();
      });
    }
  }

  Future<void> _complication(
    Map<String, dynamic> section,
    String slot,
    NativeFaceController controller,
  ) async {
    if (!_slotAvailable(slot)) return;
    final revision = _draftRevision;
    final choices = <String, Object?>{};
    final titles = <String, String>{};
    final catalog = controller.collection.complicationCatalog;
    final template =
        section[slot] ??
        (widget.face.configuration?['complications'] as Map?)?[slot];
    for (final choice in NativeComplicationChoices.forFaceSlot(
      widget.face,
      slot,
      controller.collection,
      template: template,
      titleForObserved: (value) =>
          _complicationTitle(value, catalog, slot: slot),
    )) {
      final key = jsonEncode(choice.value);
      choices[key] = choice.value;
      titles[key] = choice.title;
    }
    final selected = choices.length > 16
        ? await Navigator.of(context).push<String>(
            CupertinoPageRoute(
              builder: (_) => NativeFaceValuePicker(
                title: _slotTitle(slot),
                selected: section.containsKey(slot)
                    ? jsonEncode(section[slot])
                    : '',
                values: ['', ...choices.keys],
                label: (key) => key.isEmpty
                    ? Strings.current.nativeFaceNoComplication
                    : titles[key] ?? _complicationTitle(choices[key], catalog),
              ),
            ),
          )
        : await showCupertinoModalPopup<String>(
            context: context,
            builder: (ctx) => CupertinoActionSheet(
              title: Text(_slotTitle(slot)),
              message: Text(Strings.current.nativeFaceComplicationHint),
              actions: [
                CupertinoActionSheetAction(
                  onPressed: () => Navigator.pop(ctx, ''),
                  child: Text(Strings.current.nativeFaceNoComplication),
                ),
                for (final entry in choices.entries)
                  CupertinoActionSheetAction(
                    onPressed: () => Navigator.pop(ctx, entry.key),
                    child: Text(
                      titles[entry.key] ??
                          _complicationTitle(entry.value, catalog),
                    ),
                  ),
              ],
              cancelButton: CupertinoActionSheetAction(
                onPressed: () => Navigator.pop(ctx),
                child: Text(Strings.current.cancel),
              ),
            ),
          );
    if (selected != null &&
        mounted &&
        _canEdit(controller) &&
        _slotAvailable(slot) &&
        revision == _draftRevision &&
        _samePair(controller)) {
      final current = section.containsKey(slot)
          ? jsonEncode(section[slot])
          : '';
      if (selected == current) return;
      setState(() {
        if (selected.isEmpty) {
          section.remove(slot);
        } else {
          section[slot] = jsonDecode(selected);
        }
        _dirty = true;
        _stage();
      });
    }
  }

  String _complicationTitle(
    Object? value,
    Map<String, dynamic> catalog, {
    String? slot,
  }) => _names(catalog).title(value, faceId: widget.face.id, slot: slot);

  NativeComplicationPresentation _names(Map<String, dynamic> catalog) {
    if (!identical(catalog, _presentationCatalog)) {
      _presentationCatalog = catalog;
      _presentation = NativeComplicationPresentation(catalog);
    }
    return _presentation!;
  }

  Map<String, dynamic>? _presentationCatalog;
  NativeComplicationPresentation? _presentation;

  Future<void> _delete(NativeFaceController controller) async {
    final accepted = await showCupertinoDialog<bool>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(Strings.current.nativeFaceDeleteQuestion),
        content: Text(Strings.current.nativeFaceDeleteDetail),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(Strings.current.cancel),
          ),
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(Strings.current.nativeFaceDelete),
          ),
        ],
      ),
    );
    if (accepted == true &&
        mounted &&
        _samePair(controller) &&
        await controller.remove(widget.face.id) &&
        mounted) {
      Navigator.pop(context);
    }
  }

  Future<void> _export() async {
    try {
      final controller = context.read<WatchProvider>().nativeFaces;
      final archive = _samePair(controller)
          ? await controller.export(widget.face.id)
          : null;
      if (archive == null) {
        throw FormatException(
          Strings.current.nativeFaceResourceExportUnavailable,
        );
      }
      final saved = await WatchFaceFiles().save(
        archive,
        '${widget.face.id}.watchface',
      );
      if (saved && mounted) {
        await showCupertinoDialog<void>(
          context: context,
          builder: (ctx) => CupertinoAlertDialog(
            title: Text(Strings.current.nativeFaceFileSaved),
            actions: [
              CupertinoDialogAction(
                onPressed: () => Navigator.pop(ctx),
                child: Text(Strings.current.ok),
              ),
            ],
          ),
        );
      }
    } catch (error) {
      if (mounted) await showFaceFileError(context, error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.select<WatchProvider, NativeFaceController>(
      (provider) => provider.nativeFaces,
    );
    final autosave = context
        .select<WatchProvider, NativeFaceAutosaveController>(
          (provider) => provider.nativeFaceAutosave,
        );
    return ListenableBuilder(
      listenable: Listenable.merge([controller, autosave]),
      builder: (context, _) => _buildEditor(context, controller),
    );
  }

  Widget _buildEditor(BuildContext context, NativeFaceController controller) {
    final current = controller.collection.face(widget.face.id);
    final edit = _autosave.edit(widget.pair, widget.face.id);
    if (edit != null && !_localUnstaged) {
      // Status publications share the immutable draft string. Decode only when
      // the draft changed, not on every queue/receipt notification.
      if (_config == null || _staged != edit.desired) {
        _config = jsonDecode(edit.desired) as Map<String, dynamic>;
        _draftRevision++;
      }
      _original = edit.original;
      _staged = edit.desired;
      _dirty = true;
    } else if (edit == null && !_localUnstaged && _staged != null) {
      _dirty = false;
      _staged = null;
    }
    if (!_dirty &&
        current?.configurationJson != null &&
        current!.configurationJson != _original) {
      _config = current.configuration;
      _original = current.configurationJson;
      _draftRevision++;
    }
    final enabled = _canEdit(controller);
    _preview.bind(_config, (
      controller.collection.pair,
      controller.collection.epoch,
      widget.face.id,
    ), enabled: enabled && _samePair(controller));
    final actionable = enabled && controller.canMutate && !_dirty;
    final saveState = edit?.state;
    final retry =
        _localUnstaged ||
        saveState != null &&
            saveState != NativeFaceSaveState.pending &&
            saveState != NativeFaceSaveState.sending;
    final status = _localUnstaged
        ? Strings.current.nativeFaceDraft
        : edit != null &&
              !_autosave.canLeave(widget.pair, widget.face.id) &&
              saveState != NativeFaceSaveState.storageFailure
        ? Strings.current.nativeFaceSaving
        : switch (saveState) {
            NativeFaceSaveState.pending =>
              Strings.current.nativeFaceEditPending,
            NativeFaceSaveState.sending => Strings.current.nativeFaceWaiting,
            NativeFaceSaveState.storageFailure =>
              Strings.current.nativeFaceSaveFailed,
            NativeFaceSaveState.uncertain =>
              Strings.current.nativeFaceEditUncertain,
            NativeFaceSaveState.conflict => Strings.current.nativeFaceConflict,
            NativeFaceSaveState.failed => Strings.current.nativeFaceRejected,
            null => nativeFaceStatus(
              controller.result,
              refresh: controller.lastOperationWasRefresh,
            ),
          };
    if (NativeFaceOptions.slotNames(widget.face).isNotEmpty) {
      _config?.putIfAbsent('complications', () => <String, dynamic>{});
    }
    final customization = _config?['customization'];
    final complications = _config?['complications'];
    final catalog = controller.collection.complicationCatalog;
    final originalComplications = widget.face.configuration?['complications'];
    final layout = NativeFaceOptions.profile(widget.face)?.complicationLayout;
    final unavailable = layout?.unavailable(customization) ?? const <String>{};
    final slots = <String>{
      ...NativeFaceOptions.slotNames(widget.face),
      if (originalComplications is Map<String, dynamic>)
        ...originalComplications.keys,
      if (complications is Map<String, dynamic>) ...complications.keys,
    }..removeAll(layout?.excluded ?? const <String>{});
    final index = controller.collection.ordered.indexOf(widget.face.id);
    Future<bool> move(int offset) {
      final order = [...controller.collection.ordered];
      final id = order.removeAt(index);
      order.insert(index + offset, id);
      return controller.reorder(order);
    }

    final page = CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        // Content is below SafeArea; an opaque bar avoids reblurring it.
        backgroundColor: IosColors.systemBackground,
        middle: Text(nativeFaceTitle(current ?? widget.face)),
        trailing: saveState == NativeFaceSaveState.sending
            ? const CupertinoActivityIndicator()
            : retry
            ? CupertinoButton(
                padding: EdgeInsets.zero,
                onPressed: _samePair(controller) && controller.canMutate
                    ? _retry
                    : null,
                child: Text(Strings.current.nativeFaceRetry),
              )
            : null,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            if (NativeFaceOptions.profile(current ?? widget.face)
                case final template? when !template.requiresPhotos)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                child: ListenableBuilder(
                  listenable: _preview,
                  builder: (context, _) => Column(
                    children: [
                      NativeFacePreview(
                        template: template,
                        configuration: _preview.configuration,
                        width: 132,
                      ),
                      const SizedBox(height: 8),
                      NativeFacePreviewCaption(
                        template: template,
                        configuration: _preview.configuration,
                        style: const TextStyle(
                          color: IosColors.secondaryLabel,
                          fontSize: 13,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        Strings.current.nativeFaceStylePreviewHint,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: IosColors.secondaryLabel,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            if (_config == null)
              Padding(
                padding: const EdgeInsets.all(16),
                child: Text(Strings.current.nativeFaceConfigurationUnavailable),
              ),
            if (customization is Map<String, dynamic>) ...[
              for (final field
                  in NativeFaceOptions.profile(widget.face)?.editableFields ??
                      customization.keys)
                if (NativeFaceOptions.choices(
                      widget.face,
                      field,
                      controller.collection,
                    ).length >
                    1)
                  if (NativeFaceOptions.profile(
                        widget.face,
                      )?.pigmentSection(field)
                      case final section?
                      when section.accepts(customization[field].toString()))
                    NativeFacePigmentSectionView(
                      key: ValueKey('native-pigment-$field'),
                      section: section,
                      favorites: context.read<WatchProvider>().pigmentFavorites,
                      canCommit: () => _canEdit(controller),
                      title: NativeFaceOptions.fieldTitle(
                        field,
                        face: widget.face,
                      ),
                      selected: customization[field].toString(),
                      label: (token) => NativeFaceOptions.valueTitle(
                        field,
                        token,
                        face: widget.face,
                      ),
                      onSelected: enabled
                          ? (token) => _selectPigment(field, token, controller)
                          : null,
                      onPreviewSelected: (token) {
                        if (_canEdit(controller) &&
                            _samePair(controller) &&
                            section.accepts(token)) {
                          _preview.show(field, token);
                        }
                      },
                    )
                  else if (NativeFaceOptions.profile(
                        widget.face,
                      )?.editSection(field)
                      case final section?)
                    NativeFaceOptionSection(
                      key: ValueKey('native-option-$field'),
                      section: section,
                      title: NativeFaceOptions.fieldTitle(
                        field,
                        face: widget.face,
                      ),
                      selected: customization[field].toString(),
                      label: (token) => NativeFaceOptions.valueTitle(
                        field,
                        token,
                        face: widget.face,
                      ),
                      onSelected: enabled
                          ? (token) => _selectField(
                              customization,
                              field,
                              token,
                              controller,
                            )
                          : null,
                    )
                  else
                    IosListSection(
                      children: [
                        IosListTile(
                          title: NativeFaceOptions.fieldTitle(
                            field,
                            face: widget.face,
                          ),
                          trailingText: NativeFaceOptions.valueTitle(
                            field,
                            customization[field],
                            face: widget.face,
                          ),
                          onTap: enabled
                              ? () => _choose(customization, field, controller)
                              : null,
                        ),
                      ],
                    ),
              if (NativeFaceOptions.profile(widget.face)?.controls.isNotEmpty ==
                  true)
                IosListSection(
                  header: Strings.current.nativeFaceCustomization,
                  footer: _dirty ? status : Strings.current.nativeFaceEditHint,
                  children: [
                    for (final control
                        in NativeFaceOptions.profile(widget.face)?.controls ??
                            const <NativeFaceControl>[])
                      if (control.group(_config!)?.labels.length != null &&
                          control.group(_config!)!.labels.length > 1)
                        IosListTile(
                          title: control.title(Strings.current.localeName),
                          trailingText: control
                              .group(_config!)!
                              .title(
                                control.value(_config!) ?? '',
                                Strings.current.localeName,
                              ),
                          onTap: enabled ? () => _chooseControl(control) : null,
                        ),
                  ],
                ),
            ],
            if (complications is Map<String, dynamic>)
              IosListSection(
                header: Strings.current.nativeFaceComplications,
                footer: _names(catalog).catalogNotice(
                  controller.collection.complicationCatalogComplete,
                  controller.collection.faces.map((face) => face.configuration),
                ),
                children: [
                  for (final slot in slots) ...[
                    IosListTile(
                      title: _slotTitle(slot),
                      enabled: !unavailable.contains(slot),
                      trailingText: complications.containsKey(slot)
                          ? _complicationTitle(
                              complications[slot],
                              catalog,
                              slot: slot,
                            )
                          : Strings.current.nativeFaceNoComplication,
                      onTap: enabled && !unavailable.contains(slot)
                          ? () => _complication(complications, slot, controller)
                          : null,
                    ),
                    if (_parametersFor(complications[slot])
                        case final parameters?)
                      IosListTile(
                        title: Strings.current.nativeIntentSettings(
                          _slotTitle(slot),
                        ),
                        enabled: !unavailable.contains(slot),
                        trailingText: parameters.durations
                            .map((p) => p.summary)
                            .join(' · '),
                        onTap: enabled && !unavailable.contains(slot)
                            ? () => _editParameters(
                                complications,
                                slot,
                                controller,
                              )
                            : null,
                      ),
                    if (_variantsFor(slot, complications[slot], controller)
                        case final variants?)
                      IosListTile(
                        title: Strings.current.nativeIntentSettings(
                          _slotTitle(slot),
                        ),
                        enabled: !unavailable.contains(slot),
                        trailingText: variants.summary,
                        onTap: enabled && !unavailable.contains(slot)
                            ? () => _editParameters(
                                complications,
                                slot,
                                controller,
                              )
                            : null,
                      ),
                  ],
                ],
              ),
            if (layout?.monogram case final monogram?)
              ListenableBuilder(
                listenable: context.read<WatchProvider>().monogramSync,
                builder: (context, _) {
                  final watch = context.read<WatchProvider>();
                  final owned =
                      watch.monogramMirror?.pair == widget.pair &&
                      watch.monogramMirror?.epoch == widget.epoch;
                  return NativeFaceMonogramSection(
                    monogram: monogram,
                    configuration: _config!,
                    text: owned ? watch.monogramSync.text : null,
                    status: owned
                        ? _monogramStatus(watch.monogramSync.state)
                        : Strings.current.nativeMonogramUnavailable,
                    hasTextEditor: true,
                    onEdit: _canEditMonogram(watch) ? _editMonogram : null,
                    onChanged: enabled
                        ? (value) {
                            if (!_canEdit(controller)) return;
                            setState(() {
                              if (monogram.select(_config!, value)) {
                                _dirty = true;
                                _stage();
                              }
                            });
                          }
                        : null,
                  );
                },
              ),
            IosListSection(
              footer: status,
              children: [
                IosListTile(
                  title: Strings.current.nativeFaceSetActive,
                  trailingText: controller.collection.selected == widget.face.id
                      ? Strings.current.selectedOnWatch
                      : null,
                  onTap:
                      actionable &&
                          controller.collection.selected != widget.face.id
                      ? () => controller.select(widget.face.id)
                      : null,
                ),
              ],
            ),
            if (_config != null)
              IosListSection(
                children: [
                  IosListTile(
                    title: Strings.current.nativeFaceAdvanced,
                    onTap: () => setState(() => _advanced = !_advanced),
                    trailing: Icon(
                      _advanced
                          ? CupertinoIcons.chevron_up
                          : CupertinoIcons.chevron_down,
                      color: IosColors.secondaryLabel,
                      size: 16,
                    ),
                  ),
                  if (_advanced && current?.canCopyConfiguration == true)
                    IosListTile(
                      title: Strings.current.createCopy,
                      onTap: actionable ? () => _copy(controller) : null,
                    ),
                  if (_advanced && index > 0)
                    IosListTile(
                      title: Strings.current.nativeFaceMoveEarlier,
                      onTap: actionable ? () => move(-1) : null,
                    ),
                  if (_advanced &&
                      index >= 0 &&
                      index < controller.collection.ordered.length - 1)
                    IosListTile(
                      title: Strings.current.nativeFaceMoveLater,
                      onTap: actionable ? () => move(1) : null,
                    ),
                  if (_advanced && customization is Map<String, dynamic>)
                    for (final entry in customization.entries)
                      IosListTile(
                        title: entry.key,
                        trailingText: entry.value.toString(),
                        onTap: enabled
                            ? () => _edit(customization, entry.key)
                            : null,
                      ),
                  if (_advanced)
                    IosListTile(
                      title: Strings.current.nativeFaceConfiguration,
                      onTap: enabled ? () => _editRaw() : null,
                    ),
                  if (retry)
                    CupertinoButton(
                      onPressed: _samePair(controller) && controller.canMutate
                          ? _retry
                          : null,
                      child: controller.busy
                          ? const CupertinoActivityIndicator()
                          : Text(Strings.current.nativeFaceRetry),
                    ),
                ],
              ),
            IosListSection(
              children: [
                if (NativeFaceGallery.profile(
                      widget.face.bundle.isEmpty
                          ? NativeWatchFaceArchive.familyIdentity(_config ?? {})
                          : 'bundle:${widget.face.bundle}',
                    )?.requiresPhotos ==
                    true)
                  IosListTile(
                    title: Strings.current.photos,
                    onTap: actionable && current?.archiveAvailable == true
                        ? () => Navigator.of(context).push(
                            CupertinoPageRoute(
                              builder: (_) => NativeFacePhotosScreen(
                                template: NativeFaceGallery.profile(
                                  'bundle:${widget.face.bundle}',
                                )!,
                                face: current!,
                                pair: widget.pair,
                                epoch: widget.epoch,
                              ),
                            ),
                          )
                        : null,
                  ),
                IosListTile(
                  title: Strings.current.exportWatchfacePackage,
                  onTap: actionable && current?.archiveAvailable == true
                      ? _export
                      : null,
                ),
                IosListTile(
                  title: Strings.current.nativeFaceDelete,
                  isDestructive: true,
                  onTap: actionable && controller.collection.ordered.length > 1
                      ? () => _delete(controller)
                      : null,
                ),
              ],
            ),
          ],
        ),
      ),
    );
    return PopScope(
      canPop:
          !_dirty ||
          !_localUnstaged && _autosave.canLeave(widget.pair, widget.face.id),
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) unawaited(_leave());
      },
      child: page,
    );
  }

  Future<void> _editRaw() async {
    final revision = _draftRevision;
    _json.text = const JsonEncoder.withIndent('  ').convert(_config);
    final accepted = await Navigator.of(context).push<bool>(
      CupertinoPageRoute(
        builder: (ctx) => CupertinoPageScaffold(
          navigationBar: CupertinoNavigationBar(
            middle: Text(Strings.current.nativeFaceConfiguration),
            trailing: CupertinoButton(
              padding: EdgeInsets.zero,
              onPressed: () async {
                try {
                  final value = jsonDecode(_json.text);
                  if (value is! Map<String, dynamic> ||
                      NativeWatchFaceArchive.familyIdentity(value) !=
                          NativeWatchFaceArchive.familyIdentity(
                            widget.face.configuration!,
                          )) {
                    throw const FormatException('Invalid face family.');
                  }
                  Navigator.pop(ctx, true);
                } catch (error) {
                  await showFaceFileError(ctx, error);
                }
              },
              child: Text(Strings.current.save),
            ),
          ),
          child: SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: CupertinoTextField(
                controller: _json,
                maxLines: null,
                expands: true,
                textAlignVertical: TextAlignVertical.top,
                style: const TextStyle(
                  fontFamily: 'monospace',
                  color: IosColors.label,
                  fontSize: 13,
                ),
              ),
            ),
          ),
        ),
      ),
    );
    if (accepted == true &&
        mounted &&
        revision == _draftRevision &&
        _canEdit(context.read<WatchProvider>().nativeFaces)) {
      setState(() {
        _config = jsonDecode(_json.text) as Map<String, dynamic>;
        _dirty = true;
        _stage();
      });
    }
  }
}
