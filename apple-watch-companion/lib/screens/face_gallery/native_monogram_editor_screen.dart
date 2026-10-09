import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import '../../l10n/strings.dart';
import '../../controllers/native_monogram_sync_controller.dart';
import '../../services/native_monogram_text_normalizer.dart';
import '../../services/native_monogram_text_rules.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';

/// Rejects invalid committed input without altering IME composition.
/// No keyboard length/uppercase transformation: Foundation handles commit order.
final class NativeMonogramInputFormatter extends TextInputFormatter {
  const NativeMonogramInputFormatter();
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    if (!newValue.composing.isCollapsed) return newValue;
    return newValue.text.length <= 4096 &&
            NativeMonogramTextRules.validCharacters(newValue.text)
        ? newValue
        : oldValue;
  }
}

class NativeMonogramEditorScreen extends StatefulWidget {
  final String initialText;
  final Future<({String text, bool valid})> Function(String) normalize;
  final Future<MonogramReceipt> Function(String) submit;
  final bool Function() canSubmit;
  const NativeMonogramEditorScreen({
    super.key,
    required this.initialText,
    this.normalize = _normalize,
    required this.submit,
    required this.canSubmit,
  });
  static Future<({String text, bool valid})> _normalize(String text) =>
      const NativeMonogramTextNormalizer().normalize(text);
  @override
  State<NativeMonogramEditorScreen> createState() =>
      _NativeMonogramEditorScreenState();
}

class _NativeMonogramEditorScreenState
    extends State<NativeMonogramEditorScreen> {
  late final _text = TextEditingController(text: widget.initialText);
  bool _saving = false;
  String? _error;
  Future<void> _done() async {
    if (_saving) return;
    // Native didEndEditing does not overwrite customMonogram for an empty field.
    if (_text.text.isEmpty) {
      Navigator.of(context).pop();
      return;
    }
    if (!_text.value.composing.isCollapsed || !widget.canSubmit()) {
      setState(() => _error = Strings.current.nativeMonogramUnavailable);
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final result = await widget.normalize(_text.text);
      if (!mounted) return;
      _text.value = TextEditingValue(
        text: result.text,
        selection: TextSelection.collapsed(offset: result.text.length),
      );
      if (!result.valid || !NativeMonogramTextRules.valid(result.text)) {
        setState(() => _error = Strings.current.nativeMonogramInvalid);
        return;
      }
      if (!widget.canSubmit()) {
        setState(() => _error = Strings.current.nativeMonogramUnavailable);
        return;
      }
      final receipt = await widget.submit(result.text);
      if (!mounted) return;
      if (receipt.status == 'QUEUED') {
        Navigator.of(context).pop();
      } else {
        setState(() => _error = Strings.current.nativeMonogramUncertain);
      }
    } catch (_) {
      if (mounted) {
        setState(() => _error = Strings.current.nativeMonogramSaveError);
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => CupertinoPageScaffold(
    backgroundColor: IosColors.groupedBackground,
    navigationBar: CupertinoNavigationBar(
      middle: Text(Strings.current.nativeFaceMonogram),
      trailing: _saving
          ? const CupertinoActivityIndicator()
          : CupertinoButton(
              padding: EdgeInsets.zero,
              onPressed: _done,
              child: Text(Strings.current.nativeMonogramDone),
            ),
    ),
    child: SafeArea(
      child: ListView(
        children: [
          IosListSection(
            footer: _error,
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
                child: CupertinoTextField(
                  controller: _text,
                  autofocus: true,
                  enabled: !_saving,
                  decoration: null,
                  inputFormatters: const [NativeMonogramInputFormatter()],
                  autocorrect: false,
                  textInputAction: TextInputAction.done,
                  onSubmitted: (_) => _done(),
                ),
              ),
            ],
          ),
        ],
      ),
    ),
  );
}
