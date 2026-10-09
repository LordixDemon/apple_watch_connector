import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../controllers/native_face_controller.dart';
import '../../controllers/native_face_transient_preview.dart';
import '../../l10n/strings.dart';
import '../../providers/watch_provider.dart';
import '../../services/native_complication_choices.dart';
import '../../services/native_complication_presentation.dart';
import '../../services/native_intent_parameters.dart';
import '../../services/native_intent_variants.dart';
import 'native_complication_settings_screen.dart';
import '../../services/native_face_controls.dart';
import '../../services/native_face_gallery.dart';
import '../../services/native_face_options.dart';
import '../../services/native_face_edit_metadata.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import 'native_face_editor_screen.dart';
import 'native_face_presentation.dart';
import 'native_face_preview.dart';
import 'native_face_value_picker.dart';
import 'native_face_option_section.dart';
import 'native_face_monogram_section.dart';
import 'native_face_pigment_section.dart';

/// A native configuration draft, never a locally invented installed Watch face.
class NativeFaceCreationScreen extends StatefulWidget {
  final NativeFaceTemplate template;
  final String? pair, epoch;
  const NativeFaceCreationScreen({
    super.key,
    required this.template,
    this.pair,
    this.epoch,
  });
  @override
  State<NativeFaceCreationScreen> createState() =>
      _NativeFaceCreationScreenState();
}

class _NativeFaceCreationScreenState extends State<NativeFaceCreationScreen> {
  final _preview = NativeFaceTransientPreview();

  @override
  void dispose() {
    _preview.dispose();
    super.dispose();
  }

  final _parameters = NativeIntentParameterIndex();
  final _variants = NativeIntentVariantIndex();
  final _editMetadata = NativeFaceEditMetadata();
  NativeIntentVariants? _variantsFor(
    String slot,
    Object? value,
    NativeFaceController controller,
  ) => _sameTarget(controller)
      ? _variants.bind(
          source: controller.collection,
          slot: slot,
          current: value,
          choices: () => NativeComplicationChoices.forTemplateSlot(
            widget.template,
            slot,
            controller.collection,
            template: value,
          ),
        )
      : null;
  NativeIntentParameters? _parametersFor(Object? value) =>
      value is Map && value['descriptor'] is Map
      ? _parameters.read(value['descriptor']['intent'])
      : null;

  String _slotTitle(String slot) =>
      NativeFaceOptions.slotTitle(slot, template: widget.template);
  bool _slotAvailable(String slot) => NativeFaceOptions.slotAvailable(
    slot,
    _configuration['customization'],
    template: widget.template,
  );

  Future<void> _editParameters(
    String slot,
    NativeFaceController controller,
  ) async {
    if (!_slotAvailable(slot)) return;
    final section = _configuration['complications'];
    final value = section is Map ? section[slot] : null;
    final parameters = _parametersFor(value);
    final variants = _variantsFor(slot, value, controller);
    if (value is! Map<String, dynamic> ||
        parameters == null && variants == null) {
      return;
    }
    final before = jsonEncode(value);
    final pair = controller.collection.pair,
        epoch = controller.collection.epoch;
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
        _adding ||
        !controller.canMutate ||
        !_slotAvailable(slot) ||
        !_sameTarget(controller) ||
        pair != controller.collection.pair ||
        epoch != controller.collection.epoch ||
        jsonEncode(section[slot]) != before ||
        jsonEncode(edited) == before) {
      return;
    }
    setState(() {
      section[slot] = edited;
      _editMetadata.markEdited(_configuration);
    });
  }

  late final Map<String, dynamic> _configuration = jsonDecode(
    widget.template.configurationJson,
  );
  late String? _pair = widget.pair, _epoch = widget.epoch;
  bool _adding = false, _unresolved = false;
  bool _resultIsRefresh = false;
  NativeFaceResult _result = NativeFaceResult.idle;
  Map<String, dynamic>? _presentationCatalog;
  NativeComplicationPresentation? _presentation;

  bool _sameTarget(NativeFaceController controller) =>
      _pair != null &&
      _epoch != null &&
      controller.collection.pair == _pair &&
      controller.collection.epoch == _epoch;

  Future<String?> _pick(
    String title,
    List<String> values,
    String? selected,
    String Function(String) label, {
    Widget? Function(String)? preview,
  }) => Navigator.of(context).push<String>(
    CupertinoPageRoute(
      builder: (_) => NativeFaceValuePicker(
        title: title,
        selected: selected ?? '',
        values: values,
        label: label,
        preview: preview,
      ),
    ),
  );

  String _fieldTitle(String field) =>
      widget.template.fieldTitle(field, Strings.current.localeName) ??
      NativeFaceOptions.fieldTitle(field);
  String _valueTitle(String field, String value) =>
      widget.template.valueTitle(field, value, Strings.current.localeName) ??
      NativeFaceOptions.valueTitle(field, value);

  Future<void> _chooseField(String field) async {
    final value = NativeFaceControl.read(_configuration, [
      'customization',
      field,
    ]);
    final selected = await _pick(
      _fieldTitle(field),
      widget.template.options[field]!,
      value?.toString(),
      (token) => _valueTitle(field, token),
      preview: (token) => NativeFacePreview.option(
        widget.template,
        _configuration,
        field,
        token,
      ),
    );
    if (!mounted || _adding || selected == null) return;
    _selectField(field, selected);
  }

  void _selectField(String field, String selected) {
    if (!mounted ||
        _adding ||
        widget.template.options[field]?.contains(selected) != true ||
        NativeFaceControl.read(_configuration, ['customization', field]) ==
            selected) {
      return;
    }
    setState(() {
      widget.template.selectOption(_configuration, field, selected);
      _editMetadata.markEdited(_configuration);
    });
  }

  void _selectPigment(String field, String selected) {
    _preview.clear();
    if (!mounted ||
        _adding ||
        widget.template.pigmentSection(field)?.accepts(selected) != true ||
        NativeFaceControl.read(_configuration, ['customization', field]) ==
            selected) {
      return;
    }
    setState(() {
      widget.template.selectPigment(_configuration, field, selected);
      _editMetadata.markEdited(_configuration);
    });
  }

  Future<void> _chooseControl(NativeFaceControl control) async {
    final group = control.group(_configuration);
    if (group == null) return;
    final selected = await _pick(
      control.title(Strings.current.localeName),
      List.unmodifiable(group.labels.keys),
      control.value(_configuration),
      (token) => group.title(token, Strings.current.localeName),
    );
    if (!mounted || _adding || selected == null) return;
    setState(() {
      if (control.select(_configuration, selected)) {
        _editMetadata.markEdited(_configuration);
      }
    });
  }

  String _complicationTitle(Object? value, Map<String, dynamic> catalog) =>
      _names(catalog).title(value);

  NativeComplicationPresentation _names(Map<String, dynamic> catalog) {
    if (!identical(catalog, _presentationCatalog)) {
      _presentationCatalog = catalog;
      _presentation = NativeComplicationPresentation(catalog);
    }
    return _presentation!;
  }

  Future<void> _chooseComplication(
    String slot,
    NativeFaceController controller,
  ) async {
    if (!_slotAvailable(slot)) return;
    final current = (_configuration['complications'] as Map?)?[slot];
    final choices = _sameTarget(controller)
        ? NativeComplicationChoices.forTemplateSlot(
            widget.template,
            slot,
            controller.collection,
            template: current,
          )
        : const <NativeComplicationChoice>[];
    final titles = <String, String>{
      '': Strings.current.nativeFaceNoComplication,
    };
    for (final choice in choices) {
      titles[jsonEncode(choice.value)] = choice.title;
    }
    final pair = controller.collection.pair,
        epoch = controller.collection.epoch;
    final selected = await _pick(
      _slotTitle(slot),
      List.unmodifiable(titles.keys),
      current == null ? '' : jsonEncode(current),
      (token) => titles[token]!,
    );
    if (!mounted ||
        _adding ||
        !_slotAvailable(slot) ||
        selected == null ||
        pair != controller.collection.pair ||
        epoch != controller.collection.epoch) {
      return;
    }
    if (selected == (current == null ? '' : jsonEncode(current))) return;
    setState(() {
      final section =
          _configuration.putIfAbsent('complications', () => <String, dynamic>{})
              as Map<String, dynamic>;
      if (selected.isEmpty) {
        section.remove(slot);
        if (section.isEmpty) _configuration.remove('complications');
      } else {
        section[slot] = jsonDecode(selected);
      }
      _editMetadata.markEdited(_configuration);
    });
  }

  Future<void> _add(NativeFaceController controller) async {
    if (_adding ||
        _unresolved ||
        !controller.canMutate ||
        !_sameTarget(controller)) {
      return;
    }
    try {
      final package = widget.template.package(
        configuration: NativeFaceEditMetadata.forGalleryAddition(
          _configuration,
        ),
      );
      setState(() {
        _adding = true;
        _result = NativeFaceResult.waiting;
        _resultIsRefresh = false;
      });
      final applied = await controller.import(package);
      if (!mounted) return;
      setState(() {
        _adding = false;
        _result = controller.result;
        // A stale-baseline read can fail before ADD was submitted. Its UNKNOWN
        // result cannot imply an uncertain mutation or lock a recoverable draft.
        _resultIsRefresh = !applied && controller.lastOperationWasRefresh;
        _unresolved =
            !_resultIsRefresh &&
            (_result == NativeFaceResult.unknown ||
                _result == NativeFaceResult.connectionChanged);
      });
      if (!applied || !_sameTarget(controller)) return;
      final collection = controller.collection;
      final face = collection.face(collection.selected ?? '');
      if (face == null) return;
      await Navigator.of(context).pushReplacement(
        CupertinoPageRoute(
          builder: (_) => NativeFaceEditorScreen(
            face: face,
            pair: collection.pair!,
            epoch: collection.epoch!,
          ),
        ),
      );
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _adding = false;
        _result = NativeFaceResult.rejected;
      });
      await showFaceFileError(context, error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.select<WatchProvider, NativeFaceController>(
      (provider) => provider.nativeFaces,
    );
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        // A draft opened offline binds once, when the first usable Watch appears.
        // An already-bound draft never follows a different Watch or reconnection.
        if (_pair == null &&
            _epoch == null &&
            controller.collection.connected &&
            controller.collection.pair != null &&
            controller.collection.epoch != null) {
          _pair = controller.collection.pair;
          _epoch = controller.collection.epoch;
        }
        final canAdd =
            controller.canMutate &&
            _sameTarget(controller) &&
            !_adding &&
            !_unresolved;
        final catalog = controller.collection.complicationCatalog;
        _preview.bind(_configuration, (
          controller.collection.pair,
          controller.collection.epoch,
        ), enabled: !_adding);
        final slots = NativeFaceOptions.templateSlots(widget.template);
        final unavailable =
            widget.template.complicationLayout?.unavailable(
              _configuration['customization'],
            ) ??
            const <String>{};
        final status = nativeFaceStatus(
          _resultIsRefresh ? controller.result : _result,
          refresh: _resultIsRefresh,
        );
        return CupertinoPageScaffold(
          backgroundColor: IosColors.systemBackground,
          navigationBar: CupertinoNavigationBar(
            // Content is below SafeArea; an opaque bar avoids reblurring it.
            backgroundColor: IosColors.systemBackground,
            middle: Text(widget.template.title(Strings.current.localeName)),
          ),
          child: SafeArea(
            child: Column(
              children: [
                Expanded(
                  child: ListView(
                    children: [
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        child: ListenableBuilder(
                          listenable: _preview,
                          builder: (context, _) => Column(
                            children: [
                              NativeFacePreview(
                                template: widget.template,
                                width: 112,
                                configuration: _preview.configuration,
                              ),
                              const SizedBox(height: 8),
                              NativeFacePreviewCaption(
                                template: widget.template,
                                configuration: _preview.configuration,
                                style: const TextStyle(
                                  color: IosColors.secondaryLabel,
                                ),
                              ),
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 20,
                                  vertical: 4,
                                ),
                                child: Text(
                                  Strings.current.nativeFaceStylePreviewHint,
                                  textAlign: TextAlign.center,
                                  style: const TextStyle(
                                    color: IosColors.secondaryLabel,
                                    fontSize: 12,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      for (final field in widget.template.editableFields)
                        if (widget.template.pigmentSection(field)
                            case final section?)
                          NativeFacePigmentSectionView(
                            key: ValueKey('native-pigment-$field'),
                            section: section,
                            favorites: context
                                .read<WatchProvider>()
                                .pigmentFavorites,
                            canCommit: () =>
                                !_adding &&
                                !_unresolved &&
                                _sameTarget(controller),
                            title: _fieldTitle(field),
                            selected: NativeFaceControl.read(_configuration, [
                              'customization',
                              field,
                            ]).toString(),
                            label: (token) => _valueTitle(field, token),
                            onSelected: _adding
                                ? null
                                : (token) => _selectPigment(field, token),
                            onPreviewSelected: (token) {
                              if (!_adding && section.accepts(token)) {
                                _preview.show(field, token);
                              }
                            },
                          )
                        else if (widget.template.editSection(field)
                            case final section?)
                          NativeFaceOptionSection(
                            key: ValueKey('native-option-$field'),
                            section: section,
                            title: _fieldTitle(field),
                            selected: NativeFaceControl.read(_configuration, [
                              'customization',
                              field,
                            ]).toString(),
                            label: (token) => _valueTitle(field, token),
                            onSelected: _adding
                                ? null
                                : (token) => _selectField(field, token),
                          )
                        else
                          IosListSection(
                            children: [
                              IosListTile(
                                title: _fieldTitle(field),
                                trailingText: _valueTitle(
                                  field,
                                  NativeFaceControl.read(_configuration, [
                                    'customization',
                                    field,
                                  ]).toString(),
                                ),
                                onTap: _adding
                                    ? null
                                    : () => _chooseField(field),
                              ),
                            ],
                          ),
                      if (widget.template.controls.isNotEmpty)
                        IosListSection(
                          children: [
                            for (final control in widget.template.controls)
                              if (control.group(_configuration)
                                  case final group?)
                                IosListTile(
                                  title: control.title(
                                    Strings.current.localeName,
                                  ),
                                  trailingText: group.title(
                                    control.value(_configuration) ?? '',
                                    Strings.current.localeName,
                                  ),
                                  onTap: _adding
                                      ? null
                                      : () => _chooseControl(control),
                                ),
                          ],
                        ),
                      if (slots.isNotEmpty)
                        IosListSection(
                          header: Strings.current.nativeFaceComplications,
                          footer: _names(catalog).catalogNotice(
                            controller.collection.complicationCatalogComplete,
                            controller.collection.faces.map(
                              (face) => face.configuration,
                            ),
                          ),
                          children: [
                            for (final slot in slots) ...[
                              IosListTile(
                                title: _slotTitle(slot),
                                enabled: !unavailable.contains(slot),
                                trailingText: _complicationTitle(
                                  (_configuration['complications']
                                      as Map?)?[slot],
                                  catalog,
                                ),
                                onTap: _adding || unavailable.contains(slot)
                                    ? null
                                    : () =>
                                          _chooseComplication(slot, controller),
                              ),
                              if (_parametersFor(
                                    (_configuration['complications']
                                        as Map?)?[slot],
                                  )
                                  case final parameters?)
                                IosListTile(
                                  title: Strings.current.nativeIntentSettings(
                                    _slotTitle(slot),
                                  ),
                                  enabled: !unavailable.contains(slot),
                                  trailingText: parameters.durations
                                      .map((p) => p.summary)
                                      .join(' · '),
                                  onTap: canAdd && !unavailable.contains(slot)
                                      ? () => _editParameters(slot, controller)
                                      : null,
                                ),
                              if (_variantsFor(
                                    slot,
                                    (_configuration['complications']
                                        as Map?)?[slot],
                                    controller,
                                  )
                                  case final variants?)
                                IosListTile(
                                  title: Strings.current.nativeIntentSettings(
                                    _slotTitle(slot),
                                  ),
                                  enabled: !unavailable.contains(slot),
                                  trailingText: variants.summary,
                                  onTap: canAdd && !unavailable.contains(slot)
                                      ? () => _editParameters(slot, controller)
                                      : null,
                                ),
                            ],
                          ],
                        ),
                      if (widget.template.complicationLayout?.monogram
                          case final monogram?)
                        NativeFaceMonogramSection(
                          monogram: monogram,
                          configuration: _configuration,
                          onChanged: _adding
                              ? null
                              : (value) {
                                  if (_adding) return;
                                  setState(() {
                                    if (monogram.select(
                                      _configuration,
                                      value,
                                    )) {
                                      _editMetadata.markEdited(_configuration);
                                    }
                                  });
                                },
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (status.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: Text(
                            status,
                            style: const TextStyle(
                              color: IosColors.secondaryLabel,
                            ),
                          ),
                        ),
                      SizedBox(
                        width: double.infinity,
                        child: CupertinoButton.filled(
                          onPressed: canAdd ? () => _add(controller) : null,
                          child: _adding
                              ? const CupertinoActivityIndicator()
                              : Text(Strings.current.nativeFaceAddToWatch),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
