import 'dart:math' as math;
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import '../../services/native_face_edit_section.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';

/// Inline native options; selection updates the draft without a picker route.
class NativeFaceOptionSection extends StatefulWidget {
  final NativeFaceEditSection section;
  final String title, selected;
  final String Function(String) label;
  final ValueChanged<String>? onSelected;
  const NativeFaceOptionSection({
    super.key,
    required this.section,
    required this.title,
    required this.selected,
    required this.label,
    this.onSelected,
  });

  @override
  State<NativeFaceOptionSection> createState() =>
      _NativeFaceOptionSectionState();
}

class _NativeFaceOptionSectionState extends State<NativeFaceOptionSection> {
  final _scroll = ScrollController();
  String? _visibleSelection;
  NativeFaceEditSection? _visibleSection;
  double? _itemWidth;
  int _revealTicket = 0;
  bool _didReveal = false;
  ({
    double width,
    TextStyle style,
    TextScaler scaler,
    TextDirection direction,
    Locale? locale,
  })?
  _measurementKey;
  List<String> _measuredLabels = const [];
  double _measuredHeight = 48;
  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  TextStyle _textStyle() {
    final style = DefaultTextStyle.of(
      context,
    ).style.merge(const TextStyle(fontSize: 13));
    return MediaQuery.boldTextOf(context)
        ? style.copyWith(fontWeight: FontWeight.bold)
        : style;
  }

  Widget _option(String token, {double? width}) {
    final selected = token == widget.selected;
    final label = widget.label(token);
    return Semantics(
      button: true,
      selected: selected,
      enabled: widget.onSelected != null,
      label: '${widget.title}: $label',
      excludeSemantics: true,
      child: CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: widget.onSelected == null
            ? null
            : () => widget.onSelected!(token),
        child: Container(
          width: width,
          margin: const EdgeInsets.all(4),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: IosColors.secondarySystemBackground,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: selected ? IosColors.systemOrange : IosColors.separator,
              width: selected ? 2 : 1,
            ),
          ),
          child: Text(
            label.toUpperCase(),
            textAlign: TextAlign.center,
            style: _textStyle().copyWith(
              color: widget.onSelected == null
                  ? IosColors.secondaryLabel
                  : IosColors.label,
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final values = widget.section.values;
    if (widget.section.vertical) {
      return IosListSection(
        header: widget.title,
        children: [
          for (final value in values) _option(value, width: double.infinity),
        ],
      );
    }
    return IosListSection(
      header: widget.title,
      children: [
        LayoutBuilder(
          builder: (context, constraints) {
            final scaler = MediaQuery.textScalerOf(context);
            final width = math.min(
              constraints.maxWidth,
              math.min(160.0, math.max(88.0, constraints.maxWidth / 3)) *
                  math.max(1.0, scaler.scale(13) / 13),
            );
            final labels = [
              for (final value in values) widget.label(value).toUpperCase(),
            ];
            final key = (
              width: width,
              style: _textStyle(),
              scaler: scaler,
              direction: Directionality.of(context),
              locale: Localizations.maybeLocaleOf(context),
            );
            // Changing selection does not change text geometry. Keep only this
            // row's last measurement; language/font/viewport changes invalidate it.
            if (_measurementKey != key ||
                !listEquals(_measuredLabels, labels)) {
              var height = 48.0;
              for (final label in labels) {
                final painter = TextPainter(
                  text: TextSpan(text: label, style: key.style),
                  textDirection: key.direction,
                  textScaler: scaler,
                  locale: key.locale,
                )..layout(maxWidth: math.max(0, width - 32));
                height = math.max(height, painter.height + 36);
                painter.dispose();
              }
              _measurementKey = key;
              _measuredLabels = labels;
              _measuredHeight = height;
            }
            if (_visibleSelection != widget.selected ||
                _itemWidth != width ||
                !identical(_visibleSection, widget.section)) {
              _visibleSelection = widget.selected;
              _visibleSection = widget.section;
              _itemWidth = width;
              final ticket = ++_revealTicket;
              final section = widget.section, selected = widget.selected;
              WidgetsBinding.instance.addPostFrameCallback((_) {
                if (!mounted ||
                    !_scroll.hasClients ||
                    ticket != _revealTicket ||
                    !identical(widget.section, section) ||
                    widget.selected != selected ||
                    widget.section.vertical) {
                  return;
                }
                final index = section.values.indexOf(selected);
                if (index < 0) return;
                final position = _scroll.position;
                final start = index * width, end = start + width;
                // Original UICollectionView centers initial/offscreen choices
                // and preserves the position of even partially visible ones.
                final offset =
                    !_didReveal ||
                        end <= position.pixels ||
                        start >= position.pixels + position.viewportDimension
                    ? (start + end - position.viewportDimension) / 2
                    : position.pixels;
                _didReveal = true;
                _scroll.jumpTo(
                  offset.clamp(
                    position.minScrollExtent,
                    position.maxScrollExtent,
                  ),
                );
              });
            }
            return SizedBox(
              height: _measuredHeight,
              child: ListView.builder(
                controller: _scroll,
                scrollDirection: Axis.horizontal,
                itemExtent: width,
                itemCount: values.length,
                itemBuilder: (_, index) => _option(values[index], width: width),
              ),
            );
          },
        ),
      ],
    );
  }
}
