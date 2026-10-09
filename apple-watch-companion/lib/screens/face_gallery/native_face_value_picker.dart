import 'package:flutter/cupertino.dart';
import '../../l10n/strings.dart';
import '../../widgets/ios_list_tile.dart';

/// Large firmware palettes stay searchable and build only the visible rows.
class NativeFaceValuePicker extends StatefulWidget {
  final String title, selected;
  final List<String> values;
  final String Function(String) label;
  final Widget? Function(String)? preview;
  const NativeFaceValuePicker({
    super.key,
    required this.title,
    required this.selected,
    required this.values,
    required this.label,
    this.preview,
  });
  @override
  State<NativeFaceValuePicker> createState() => _NativeFaceValuePickerState();
}

class _NativeFaceValuePickerState extends State<NativeFaceValuePicker> {
  String _query = '';
  List<({String value, String label, String search})> _rows = const [];
  List<({String value, String label, String search})> _visible = const [];
  final Map<String, Widget?> _previews = {};
  final Map<String, Widget> _tiles = {};

  @override
  void initState() {
    super.initState();
    _indexLabels();
  }

  @override
  void didUpdateWidget(NativeFaceValuePicker oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(widget.values, oldWidget.values) ||
        !identical(widget.label, oldWidget.label)) {
      _indexLabels();
    }
    if (!identical(widget.preview, oldWidget.preview) ||
        !identical(widget.values, oldWidget.values)) {
      _previews.clear();
      _tiles.clear();
    }
    if (widget.selected != oldWidget.selected) _tiles.clear();
  }

  void _indexLabels() {
    _tiles.clear();
    _rows = [for (final value in widget.values) _row(value)];
    _filter();
  }

  Widget _tile(({String value, String label, String search}) row) =>
      _tiles.putIfAbsent(
        row.value,
        () => IosListTile(
          title: row.label,
          leading: widget.preview == null
              ? null
              : _previews.putIfAbsent(
                  row.value,
                  () => widget.preview!(row.value),
                ),
          trailing: row.value == widget.selected
              ? const Icon(CupertinoIcons.check_mark)
              : null,
          onTap: () => Navigator.pop(context, row.value),
        ),
      );

  ({String value, String label, String search}) _row(String value) {
    final label = widget.label(value);
    return (value: value, label: label, search: label.toLowerCase());
  }

  void _filter() {
    _visible = _query.isEmpty
        ? _rows
        : _rows.where((row) => row.search.contains(_query)).toList();
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      navigationBar: CupertinoNavigationBar(middle: Text(widget.title)),
      child: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(12),
              child: CupertinoSearchTextField(
                placeholder: Strings.current.nativeFaceSearch,
                onChanged: (value) {
                  final query = value.toLowerCase();
                  if (query == _query) return;
                  setState(() {
                    _query = query;
                    _filter();
                  });
                },
              ),
            ),
            Expanded(
              child: ListView.builder(
                itemCount: _visible.length,
                itemBuilder: (_, index) => _tile(_visible[index]),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
