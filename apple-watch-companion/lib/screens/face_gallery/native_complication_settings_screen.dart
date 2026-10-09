import 'dart:convert';
import 'package:flutter/cupertino.dart';
import '../../l10n/strings.dart';
import '../../services/native_intent_parameters.dart';
import '../../services/native_intent_variants.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_tile.dart';
import '../../widgets/ios_list_section.dart';
import 'native_face_value_picker.dart';

/// A local draft only. The caller verifies pair/epoch before retaining it;
/// its ordinary Apply/Add path still requires native ACK and readback.
class NativeComplicationSettingsScreen extends StatefulWidget {
  final String title;
  final Map<String, dynamic> value;
  final NativeIntentParameters? parameters;
  final NativeIntentVariants? variants;
  const NativeComplicationSettingsScreen({
    super.key,
    required this.title,
    required this.value,
    this.parameters,
    this.variants,
  }) : assert((parameters == null) != (variants == null));
  @override
  State<NativeComplicationSettingsScreen> createState() =>
      _NativeComplicationSettingsScreenState();
}

class _NativeComplicationSettingsScreenState
    extends State<NativeComplicationSettingsScreen> {
  late final _inputs = {
    for (final p
        in widget.parameters?.durations ?? const <NativeIntentDuration>[])
      p.name: TextEditingController(
        text: p.seconds == p.seconds.truncateToDouble()
            ? p.seconds.toStringAsFixed(0)
            : '${p.seconds}',
      ),
  };
  late final _selected = Map<String, String>.from(
    widget.variants?.initial ?? const {},
  );
  bool get _valid => widget.variants?.accepts(_selected) ?? (_values != null);
  Map<String, double>? get _values {
    final result = <String, double>{};
    for (final p
        in widget.parameters?.durations ?? const <NativeIntentDuration>[]) {
      final value = double.tryParse(_inputs[p.name]!.text.trim());
      if (value == null || !p.accepts(value)) return null;
      result[p.name] = value;
    }
    return result;
  }

  @override
  void dispose() {
    for (final input in _inputs.values) {
      input.dispose();
    }
    super.dispose();
  }

  void _save() {
    if (widget.variants case final variants?) {
      if (_valid) Navigator.of(context).pop(variants.update(_selected));
      return;
    }
    final values = _values;
    if (values == null) return;
    final result = jsonDecode(jsonEncode(widget.value)) as Map<String, dynamic>;
    (result['descriptor'] as Map)['intent'] = widget.parameters!.update(values);
    Navigator.of(context).pop(result);
  }

  Future<void> _chooseVariant(String field) async {
    final variants = widget.variants!;
    final options = variants.options(field, _selected);
    final before = Map<String, String>.from(_selected);
    final selected = await Navigator.of(context).push<String>(
      CupertinoPageRoute(
        builder: (_) => NativeFaceValuePicker(
          title: NativeIntentVariants.fieldTitle(field),
          selected: _selected[field] ?? '',
          values: options.map((o) => o.value).toList(),
          label: (value) => options.singleWhere((o) => o.value == value).label,
        ),
      ),
    );
    if (!mounted ||
        selected == null ||
        !options.any((o) => o.value == selected)) {
      return;
    }
    final next = {...before, field: selected};
    if (!variants.accepts(next)) return;
    setState(() {
      _selected[field] = selected;
    });
  }

  @override
  Widget build(BuildContext context) => CupertinoPageScaffold(
    backgroundColor: IosColors.systemBackground,
    navigationBar: CupertinoNavigationBar(
      middle: Text(widget.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      trailing: CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: _valid ? _save : null,
        child: Text(Strings.current.save),
      ),
    ),
    child: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (widget.variants case final variants?)
            IosListSection(
              children: [
                for (final field in variants.fields)
                  IosListTile(
                    title: NativeIntentVariants.fieldTitle(field),
                    trailingText: variants.label(field, _selected),
                    onTap: variants.options(field, _selected).length > 1
                        ? () => _chooseVariant(field)
                        : null,
                  ),
              ],
            ),
          for (final p
              in widget.parameters?.durations ??
                  const <NativeIntentDuration>[]) ...[
            Text(Strings.current.nativeIntentParameterSeconds(p.title)),
            const SizedBox(height: 8),
            CupertinoTextField(
              controller: _inputs[p.name],
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 16),
          ],
          if (!_valid)
            Text(
              Strings.current.nativeIntentParameterInvalid,
              style: const TextStyle(color: IosColors.systemRed),
            ),
          Text(
            Strings.current.nativeIntentLocalDraft,
            style: const TextStyle(color: IosColors.secondaryLabel),
          ),
        ],
      ),
    ),
  );
}
